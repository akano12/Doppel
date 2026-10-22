/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2017-2019 Marcel Bokhorst (M66B)
 */

package eu.faircode.xlua;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// The scripts of Get.Applications for single packages and user ids (package_probe, package_resolve)
// and for lists (generic_filter_by_uid, packagemanager_getchangedpackages), with Lua stand-ins like
// LocationScriptTest; luajava cannot make java.base classes accessible on a desktop JVM,
// so lists and arrays are stand-ins too and exceptions are of the test
public class PackageScriptsTest {
    private static final String STUBS = String.join("\n",
            "function hookof(name, method)",
            "  return { getName = function(self) return name end,",
            "    getMethodName = function(self) return method end }",
            "end",
            // A call with arguments, the packages and user ids which stay visible and the values of the process
            "function paramof(args, visible, values)",
            "  local param = { args = args, result = nil, exception = nil }",
            "  function param:getArgument(index) return self.args[index] end",
            "  function param:setArgument(index, value) self.args[index] = value end",
            "  function param:getResult() return self.result end",
            "  function param:setResult(value) self.result = value end",
            "  function param:getException() return self.exception end",
            "  function param:getUid() return 10001 end",
            "  function param:isSystem() return self.system == true end",
            "  function param:isPackageVisible(name) return visible[name] == true end",
            "  function param:isUidVisible(uid) return visible[uid] == true end",
            "  function param:isVisible(ai) return ai.uid == 10001 or ai.system == true end",
            "  function param:isInstance(object, name)",
            "    return name == '[Ljava.lang.String;' and type(object) == 'table' and object.length ~= nil end",
            "  function param:getApplicationContext() return values or {} end",
            "  function param:getValue(name, scope) return scope[name] end",
            "  function param:putValue(name, value, scope) scope[name] = value end",
            "  return param",
            "end",
            // A java.util.List and a Java array as Lua sees them
            "function listof(items)",
            "  return { items = items,",
            "    add = function(self, value) self.items[#self.items + 1] = value end,",
            "    get = function(self, index) return self.items[index + 1] end,",
            "    size = function(self) return #self.items end,",
            "    isEmpty = function(self) return #self.items == 0 end,",
            "    remove = function(self, index) table.remove(self.items, index + 1) end,",
            "    toArray = function(self) return arrayof(table.unpack(self.items)) end }",
            "end",
            "function arrayof(...)",
            "  local array = { ... }",
            "  array.length = select('#', ...)",
            "  return array",
            "end",
            "local real = luajava",
            "luajava = { new = real.new,",
            "  newInstance = function(name, ...)",
            "    if name == 'java.util.ArrayList' then return listof({}) end",
            "    assert(name == 'android.content.pm.ChangedPackages', name)",
            "    local sequence, names = ...",
            "    return { sequence = sequence, names = names }",
            "  end,",
            "  bindClass = function(name)",
            "    if name == 'java.lang.reflect.Array' then",
            "      return { newInstance = function(self, type, length) return { length = length } end }",
            "    end",
            "    assert(name == 'java.lang.String', name)",
            "    return {}",
            "  end }");

    // An exception of Android, like PackageManager.NameNotFoundException
    public static class ProbeException extends Exception {
        public ProbeException(String message) {
            super(message);
        }
    }

    private static Globals load(String name) throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        String script = new String(Files.readAllBytes(new File("src/main/assets/" + name + ".lua").toPath()),
                StandardCharsets.UTF_8);
        globals.load(script, name).call();
        globals.load(STUBS, "stubs").call();
        return globals;
    }

    private static Varargs run(Globals globals, String lua) {
        return globals.load(lua, "test").invoke();
    }

    // A hidden package is looked up under a name which is not installed, so Android itself answers
    @Test
    public void hiddenPackageIsLookedUpAsMissing() throws Exception {
        Varargs result = run(load("package_probe"),
                "local param = paramof({ [0] = 'com.example.bank', [1] = 0 }, {})\n" +
                        "local restricted, old = before(hookof('PackageManager.getPackageInfo', 'getPackageInfo'), param)\n" +
                        "return restricted, old, param.args[0], param.args[1]");
        assertTrue(result.arg(1).toboolean());
        assertEquals("com.example.bank", result.arg(2).tojstring());
        String substitute = result.arg(3).tojstring();
        assertTrue(substitute, substitute.matches("com\\.example\\.bank\\.x[0-9]{9}"));
        assertEquals(0, result.arg(4).toint());
    }

    @Test
    public void visiblePackageStays() throws Exception {
        Globals globals = load("package_probe");
        globals.set("genuine", CoerceJavaToLua.coerce(new ProbeException("com.android.vending")));
        Varargs result = run(globals,
                "local hook = hookof('PackageManager.getPackageInfo', 'getPackageInfo')\n" +
                        "local param = paramof({ [0] = 'com.android.vending', [1] = 0 }, { ['com.android.vending'] = true })\n" +
                        "local restricted = before(hook, param)\n" +
                        "param.exception = genuine\n" +
                        "after(hook, param)\n" +
                        "return restricted, param.args[0], param.result");
        assertFalse(result.arg(1).toboolean());
        assertEquals("com.android.vending", result.arg(2).tojstring());
        assertTrue(result.arg(3).isnil());
    }

    // The exception of Android for the name which is not installed, with the name the app asked for,
    // like for createPackageContext, whose message is not just the name; the same class, stack trace and cause
    @Test
    public void exceptionHasNameOfApp() throws Exception {
        Globals globals = load("package_probe");
        String substitute = run(globals,
                "hook = hookof('Context.createPackageContext', 'createPackageContext')\n" +
                        "param = paramof({ [0] = 'com.example.bank', [1] = 0 }, {})\n" +
                        "before(hook, param)\n" +
                        "return param.args[0]").arg1().tojstring();

        ProbeException genuine = new ProbeException("Application package " + substitute + " not found");
        genuine.initCause(new RuntimeException("Remote stack trace"));
        globals.set("genuine", CoerceJavaToLua.coerce(genuine));
        Varargs result = run(globals,
                "param.exception = genuine\n" +
                        "return after(hook, param), param.result");

        assertFalse(result.arg(1).toboolean()); // Reported by before
        Throwable fake = (Throwable) result.arg(2).touserdata();
        assertEquals(ProbeException.class, fake.getClass());
        assertEquals("Application package com.example.bank not found", fake.getMessage());
        assertArrayEquals(genuine.getStackTrace(), fake.getStackTrace());
        assertSame(genuine.getCause(), fake.getCause());
    }

    // The package is the second argument of checkPermission, checkSignatures checks both
    @Test
    public void packageArguments() throws Exception {
        Globals globals = load("package_probe");
        Varargs permission = run(globals,
                "local param = paramof({ [0] = 'android.permission.INTERNET', [1] = 'com.example.bank' }, {})\n" +
                        "before(hookof('PackageManager.checkPermission', 'checkPermission'), param)\n" +
                        "return param.args[0], param.args[1]");
        assertEquals("android.permission.INTERNET", permission.arg(1).tojstring());
        assertTrue(permission.arg(2).tojstring().startsWith("com.example.bank.x"));

        Varargs signatures = run(globals,
                "local param = paramof({ [0] = 'com.example.app', [1] = 'com.example.bank' }, { ['com.example.app'] = true })\n" +
                        "before(hookof('PackageManager.checkSignatures', 'checkSignatures'), param)\n" +
                        "return param.args[0], param.args[1]");
        assertEquals("com.example.app", signatures.arg(1).tojstring());
        assertTrue(signatures.arg(2).tojstring().startsWith("com.example.bank.x"));
    }

    // An array of targets is copied, the array of the app stays as it is
    @Test
    public void arrayOfAppStays() throws Exception {
        Varargs result = run(load("package_probe"),
                "local targets = arrayof('com.android.vending', 'com.example.bank')\n" +
                        "local param = paramof({ [0] = 'com.example.app', [1] = targets },\n" +
                        "  { ['com.example.app'] = true, ['com.android.vending'] = true })\n" +
                        "before(hookof('PackageManager.canPackageQuery/targets', 'canPackageQuery'), param)\n" +
                        "return targets[2], param.args[1] ~= targets, param.args[1][1], param.args[1][2]");
        assertEquals("com.example.bank", result.arg(1).tojstring());
        assertTrue(result.arg(2).toboolean());
        assertEquals("com.android.vending", result.arg(3).tojstring());
        assertTrue(result.arg(4).tojstring().startsWith("com.example.bank.x"));
    }

    // Hooks of methods which the hooked method calls, like getPackageInfoAsUser, leave the substitute
    // and the exception to the hook which made it; the calls on a thread end in reverse order
    @Test
    public void nestedHooksLeaveSubstitute() throws Exception {
        Globals globals = load("package_probe");
        String substitute = run(globals,
                "values = {}\n" +
                        "hook = hookof('PackageManager.getPackageInfo', 'getPackageInfo')\n" +
                        "outer = paramof({ [0] = 'com.example.bank', [1] = 0 }, {}, values)\n" +
                        "before(hook, outer)\n" +
                        "return outer.args[0]").arg1().tojstring();

        globals.set("genuine", CoerceJavaToLua.coerce(new ProbeException(substitute)));
        Varargs result = run(globals,
                "local inner = paramof({ [0] = outer.args[0], [1] = 0 }, {}, values)\n" +
                        "local nested = before(hook, inner)\n" +
                        "inner.exception = genuine\n" +
                        "after(hook, inner)\n" +
                        "outer.exception = genuine\n" +
                        "after(hook, outer)\n" +
                        "return nested, inner.args[0] == outer.args[0], inner.result, outer.result");
        assertFalse(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).isnil());
        assertEquals("com.example.bank", ((Throwable) result.arg(4).touserdata()).getMessage());
    }

    // The suffix is the same for the process, but random, so apps cannot ask for a substitute
    @Test
    public void suffixIsRandom() throws Exception {
        Globals globals = load("package_probe");
        String suffix = "local param = paramof({}, {}, values)\nreturn suffixof(param)";
        String one = run(globals, "local values = {}\n" + suffix).arg1().tojstring();
        String two = run(globals, "local values = {}\n" + suffix).arg1().tojstring();
        String same = run(globals, "local values = { ['package_probe.suffix'] = '" + one + "' }\n" + suffix)
                .arg1().tojstring();
        assertTrue(one, one.matches("\\.x[0-9]{9}"));
        assertNotEquals(one, two);
        assertEquals(one, same);
    }

    // A shared user id of a visible app, like of Google Play services, stays visible
    @Test
    public void sharedUserOfVisibleApp() throws Exception {
        Globals globals = load("package_resolve");
        Varargs visible = run(globals,
                "local param = paramof({ [0] = 10050 }, { [10050] = true })\n" +
                        "param.result = 'com.google.uid.shared:10050'\n" +
                        "local restricted = after(hookof('PackageManager.getNameForUid', 'getNameForUid'), param)\n" +
                        "return restricted, param.result");
        assertFalse(visible.arg(1).toboolean());
        assertEquals("com.google.uid.shared:10050", visible.arg(2).tojstring());

        Varargs hidden = run(globals,
                "local param = paramof({ [0] = 10060 }, {})\n" +
                        "param.result = 'com.example.shared:10060'\n" +
                        "local restricted = after(hookof('PackageManager.getNameForUid', 'getNameForUid'), param)\n" +
                        "return restricted, param.result");
        assertTrue(hidden.arg(1).toboolean());
        assertTrue(hidden.arg(2).isnil());
    }

    // Like for a user id which is not in use
    @Test
    public void signaturesOfHiddenUid() throws Exception {
        Varargs result = run(load("package_resolve"),
                "local param = paramof({ [0] = 10001, [1] = 10060 }, { [10001] = true })\n" +
                        "param.result = -3\n" +
                        "local restricted = after(hookof('PackageManager.checkSignatures/uid', 'checkSignatures'), param)\n" +
                        "return restricted, param.result");
        assertTrue(result.arg(1).toboolean());
        assertEquals(-4, result.arg(2).toint());
    }

    // Lists of components keep system apps, which the Android framework uses, lists of packages do not
    @Test
    public void componentsOfSystemAppsStay() throws Exception {
        Globals globals = load("generic_filter_by_uid");
        String items = "local own = { uid = 10001 }\n" +
                "local system = { uid = 10010, system = true }\n" +
                "local other = { uid = 10060 }\n";
        Varargs activities = run(globals, items +
                "local list = listof({ { activityInfo = { applicationInfo = own } },\n" +
                "  { activityInfo = { applicationInfo = system } }, { activityInfo = { applicationInfo = other } } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "local restricted = after(hookof('PackageManager.queryIntentActivities.33'), param)\n" +
                "return restricted, list:size(), list:get(1).activityInfo.applicationInfo == system");
        assertTrue(activities.arg(1).toboolean());
        assertEquals(2, activities.arg(2).toint());
        assertTrue(activities.arg(3).toboolean());

        Varargs providers = run(globals, items +
                "local list = listof({ { applicationInfo = system }, { applicationInfo = other } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "after(hookof('PackageManager.queryContentProviders'), param)\n" +
                "return list:size()");
        assertEquals(1, providers.arg(1).toint());

        Varargs packages = run(globals, items +
                "local list = listof({ { applicationInfo = own }, { applicationInfo = system } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "after(hookof('PackageManager.getInstalledPackages'), param)\n" +
                "return list:size()");
        assertEquals(1, packages.arg(1).toint());

        // Properties have a package, like the lookups of single packages
        Varargs properties = run(globals,
                "local function property(name) return { getPackageName = function(self) return name end } end\n" +
                        "local list = listof({ property('com.android.vending'), property('com.example.bank') })\n" +
                        "local param = paramof({}, { ['com.android.vending'] = true })\n" +
                        "param.result = list\n" +
                        "after(hookof('PackageManager.queryApplicationProperty'), param)\n" +
                        "return list:size(), list:get(0):getPackageName()");
        assertEquals(1, properties.arg(1).toint());
        assertEquals("com.android.vending", properties.arg(2).tojstring());

        // Processes of the system manage all apps
        Varargs managed = run(globals, items +
                "local list = listof({ { applicationInfo = own }, { applicationInfo = other } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "param.system = true\n" +
                "return after(hookof('PackageManager.getInstalledPackages'), param), list:size()");
        assertFalse(managed.arg(1).toboolean());
        assertEquals(2, managed.arg(2).toint());
    }

    // Only visible packages are changed packages; no changes is null
    @Test
    public void changedPackages() throws Exception {
        Globals globals = load("packagemanager_getchangedpackages");
        String changed = "local result = { names = listof({ 'com.android.vending', 'com.example.bank' }),\n" +
                "  getPackageNames = function(self) return self.names end,\n" +
                "  getSequenceNumber = function(self) return 5 end }\n" +
                "local hook = hookof('PackageManager.getChangedPackages')\n";
        Varargs some = run(globals, changed +
                "local param = paramof({ [0] = 0 }, { ['com.android.vending'] = true })\n" +
                "param.result = result\n" +
                "return after(hook, param), param.result.sequence, param.result.names:size(), param.result.names:get(0)");
        assertTrue(some.arg(1).toboolean());
        assertEquals(5, some.arg(2).toint());
        assertEquals(1, some.arg(3).toint());
        assertEquals("com.android.vending", some.arg(4).tojstring());

        Varargs none = run(globals, changed +
                "local param = paramof({ [0] = 0 }, {})\n" +
                "param.result = result\n" +
                "return after(hook, param), param.result");
        assertTrue(none.arg(1).toboolean());
        assertTrue(none.arg(2).isnil());

        Varargs all = run(globals, changed +
                "local param = paramof({ [0] = 0 }, { ['com.android.vending'] = true, ['com.example.bank'] = true })\n" +
                "param.result = result\n" +
                "return after(hook, param), param.result == result");
        assertFalse(all.arg(1).toboolean());
        assertTrue(all.arg(2).toboolean());
    }
}
