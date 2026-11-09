/*
    This file is part of Doppel.

    Doppel is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Doppel is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Doppel.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2026 akano12
 */

package eu.faircode.xlua;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// The scripts of Get.Applications for single packages and user ids (package_probe, package_resolve)
// and for lists (generic_filter_by_uid, packagemanager_getchangedpackages), with Lua stand-ins like
// LocationScriptTest; lists and arrays are stand-ins too, which the tests read back as Lua tables,
// and the exceptions are of the test, because those of Android are stubs in unit tests
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
            Scripts.ARRAYOF,
            // PackageManager.ResolveInfoFlags
            "function flagsof(value)",
            "  return { getValue = function(self) return value end }",
            "end",
            "callingUid = 10001", // Binder.getCallingUid
            "local real = luajava",
            "luajava = { new = real.new,",
            "  newInstance = function(name, ...)",
            "    if name == 'java.util.ArrayList' then return listof({}) end",
            "    if name == 'android.content.pm.PackageManager$NameNotFoundException' then",
            "      return { exception = name, message = ... }",
            "    end",
            "    assert(name == 'android.content.pm.ChangedPackages', name)",
            "    local sequence, names = ...",
            "    return { sequence = sequence, names = names }",
            "  end,",
            "  bindClass = function(name)",
            "    if name == 'java.lang.reflect.Array' then",
            "      return { newInstance = function(self, type, length) return { length = length } end }",
            "    end",
            "    if name == 'android.os.Binder' then",
            "      return { getCallingUid = function(self) return callingUid end }",
            "    end",
            "    if name == 'android.content.pm.PackageManager$ResolveInfoFlags' then",
            "      return { of = function(self, value) return flagsof(value) end }",
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
        return Scripts.load(name, STUBS);
    }

    // A hidden package is looked up under a name which is not installed, so Android itself answers
    @Test
    public void hiddenPackageIsLookedUpAsMissing() throws Exception {
        Varargs result = Scripts.run(load("package_probe"),
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
        Varargs result = Scripts.run(globals,
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
        String substitute = Scripts.run(globals,
                "hook = hookof('Context.createPackageContext', 'createPackageContext')\n" +
                        "param = paramof({ [0] = 'com.example.bank', [1] = 0 }, {})\n" +
                        "before(hook, param)\n" +
                        "return param.args[0]").arg1().tojstring();

        ProbeException genuine = new ProbeException("Application package " + substitute + " not found");
        genuine.initCause(new RuntimeException("Remote stack trace"));
        globals.set("genuine", CoerceJavaToLua.coerce(genuine));
        Varargs result = Scripts.run(globals,
                "param.exception = genuine\n" +
                        "return after(hook, param), param.result");

        assertFalse(result.arg(1).toboolean()); // Reported by before
        Throwable fake = (Throwable) result.arg(2).touserdata();
        assertEquals(ProbeException.class, fake.getClass());
        assertEquals("Application package com.example.bank not found", fake.getMessage());
        assertArrayEquals(genuine.getStackTrace(), fake.getStackTrace());
        assertSame(genuine.getCause(), fake.getCause());
    }

    // An exception without a constructor with a message: the app gets it as it is, without an error of the script
    public static class SilentException extends Exception {
        public SilentException() {
            super("silent");
        }
    }

    @Test
    public void exceptionWithoutMessageConstructor() throws Exception {
        Globals globals = load("package_probe");
        String substitute = Scripts.run(globals,
                "hook = hookof('PackageManager.getPackageInfo', 'getPackageInfo')\n" +
                        "param = paramof({ [0] = 'com.example.bank', [1] = 0 }, {})\n" +
                        "before(hook, param)\n" +
                        "return param.args[0]").arg1().tojstring();
        assertTrue(substitute.startsWith("com.example.bank.x"));
        globals.set("genuine", CoerceJavaToLua.coerce(new SilentException()));
        Varargs result = Scripts.run(globals, "param.exception = genuine\nreturn after(hook, param), param.result");
        assertFalse(result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());
    }

    // AppOpsManager answers differently for a package of the uid: the package is the third argument of the
    // checks of operations, the second of checkPackage
    @Test
    public void appOpsPackages() throws Exception {
        Globals globals = load("package_probe");
        Varargs result = Scripts.run(globals,
                "local op = paramof({ [0] = 'android:coarse_location', [1] = 10123, [2] = 'com.example.bank' }, {})\n" +
                        "before(hookof('AppOpsManager.checkOpNoThrow', 'checkOpNoThrow'), op)\n" +
                        "local unsafe = paramof({ [0] = 'android:coarse_location', [1] = 10123, [2] = 'com.example.bank' }, {})\n" +
                        "before(hookof('AppOpsManager.unsafeCheckOpNoThrow', 'unsafeCheckOpNoThrow'), unsafe)\n" +
                        "local check = paramof({ [0] = 10123, [1] = 'com.example.bank' }, {})\n" +
                        "before(hookof('AppOpsManager.checkPackage', 'checkPackage'), check)\n" +
                        "return op.args[0], op.args[2], unsafe.args[2], check.args[0], check.args[1]");
        assertEquals("android:coarse_location", result.arg(1).tojstring());
        for (int i : new int[]{2, 3, 5})
            assertTrue(String.valueOf(i), result.arg(i).tojstring().startsWith("com.example.bank.x"));
        assertEquals(10123, result.arg(4).toint());
    }

    // The enabled settings of a package and an attribution check of a uid are looked up under a name which is not
    // installed, like the other checks of packages (package_probe)
    @Test
    public void enabledSettingsOfHiddenPackages() throws Exception {
        Globals globals = load("package_probe");
        Varargs result = Scripts.run(globals,
                "local op = paramof({ [0] = 'android:coarse_location', [1] = 10123, [2] = 'com.example.bank' }, {})\n" +
                        "before(hookof('AppOpsManager.checkOpRawNoThrow/attribution', 'checkOpRawNoThrow'), op)\n" +
                        "local enabled = paramof({ [0] = 'com.example.bank', [1] = 2, [2] = 0 }, {})\n" +
                        "before(hookof('PackageManager.setApplicationEnabledSetting', 'setApplicationEnabledSetting'), enabled)\n" +
                        "return op.args[2], enabled.args[0], enabled.args[1]");
        assertTrue(result.arg(1).tojstring().startsWith("com.example.bank.x"));
        assertTrue(result.arg(2).tojstring().startsWith("com.example.bank.x"));
        assertEquals(2, result.arg(3).toint());
    }

    // A provider of the app checks the app calling it (ContentProvider.getCallingPackage), which is not a probe,
    // with an entry for after like any other call, inside a call which was a probe
    @Test
    public void checkPackageOfCaller() throws Exception {
        Globals globals = load("package_probe");
        String substitute = Scripts.run(globals,
                "callingUid = 10123\n" +
                        "hook = hookof('AppOpsManager.checkPackage', 'checkPackage')\n" +
                        "probe = paramof({ [0] = 10124, [1] = 'com.example.bank' }, {})\n" +
                        "before(hook, probe)\n" +
                        "return probe.args[1]").arg1().tojstring();
        assertTrue(substitute, substitute.startsWith("com.example.bank.x"));

        globals.set("genuine", CoerceJavaToLua.coerce(new ProbeException(substitute)));
        Varargs result = Scripts.run(globals,
                "local caller = paramof({ [0] = 10123, [1] = 'com.example.bank' }, {})\n" +
                        "local restricted = before(hook, caller)\n" +
                        "after(hook, caller)\n" +
                        "probe.exception = genuine\n" +
                        "after(hook, probe)\n" +
                        "return restricted, caller.args[1], probe.result");
        assertFalse(result.arg(1).toboolean());
        assertEquals("com.example.bank", result.arg(2).tojstring());
        assertEquals("com.example.bank", ((Throwable) result.arg(3).touserdata()).getMessage());
    }

    // The package is the second argument of checkPermission, checkSignatures checks both
    @Test
    public void packageArguments() throws Exception {
        Globals globals = load("package_probe");
        Varargs permission = Scripts.run(globals,
                "local param = paramof({ [0] = 'android.permission.INTERNET', [1] = 'com.example.bank' }, {})\n" +
                        "before(hookof('PackageManager.checkPermission', 'checkPermission'), param)\n" +
                        "return param.args[0], param.args[1]");
        assertEquals("android.permission.INTERNET", permission.arg(1).tojstring());
        assertTrue(permission.arg(2).tojstring().startsWith("com.example.bank.x"));

        Varargs signatures = Scripts.run(globals,
                "local param = paramof({ [0] = 'com.example.app', [1] = 'com.example.bank' }, { ['com.example.app'] = true })\n" +
                        "before(hookof('PackageManager.checkSignatures', 'checkSignatures'), param)\n" +
                        "return param.args[0], param.args[1]");
        assertEquals("com.example.app", signatures.arg(1).tojstring());
        assertTrue(signatures.arg(2).tojstring().startsWith("com.example.bank.x"));
        // The storage of another app, like its sizes, by its package, the second argument (volume, package, user)
        Varargs storage = Scripts.run(globals,
                "local param = paramof({ [0] = 'uuid', [1] = 'com.example.bank', [2] = 'user' }, {})\n" +
                        "before(hookof('StorageStatsManager.queryStatsForPackage', 'queryStatsForPackage'), param)\n" +
                        "return param.args[0], param.args[1]");
        assertEquals("uuid", storage.arg(1).tojstring());
        assertTrue(storage.arg(2).tojstring().startsWith("com.example.bank.x"));
    }

    // An intent with a selector is sent by the selector, so the package of the intent itself does not count for it
    @Test
    public void selectorOfIntentIsSubstituted() throws Exception {
        Varargs result = Scripts.run(load("package_probe"),
                "local base = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, ...)\n" +
                        "  if name == 'android.content.Intent' then\n" +
                        "    return { from = ..., setSelector = function(self, selector) self.selector = selector end,\n" +
                        "      setPackage = function(self, name) self.package = name return self end }\n" +
                        "  end\n" +
                        "  return base(name, ...)\n" +
                        "end\n" +
                        "function intentof(packageName, selector)\n" +
                        "  return { getComponent = function(self) return nil end,\n" +
                        "    getPackage = function(self) return packageName end,\n" +
                        "    getSelector = function(self) return selector end }\n" +
                        "end\n" +
                        "local hook = hookof('LauncherApps.resolveActivity', 'resolveActivity')\n" +
                        "local selector = intentof('com.example.bank', nil)\n" +
                        "local outer = intentof('com.example.app', selector)\n" +
                        "local hidden = paramof({ [0] = outer }, { ['com.example.app'] = true })\n" +
                        "hidden.isInstance = function(self, object, name) return name == 'android.content.Intent' end\n" +
                        "local restricted = before(hook, hidden)\n" +
                        "local copy = hidden.args[0]\n" +
                        "local visibleSelector = intentof('com.example.app', nil)\n" +
                        "local other = intentof('com.example.bank', visibleSelector)\n" +
                        "local visible = paramof({ [0] = other }, { ['com.example.app'] = true })\n" +
                        "visible.isInstance = hidden.isInstance\n" +
                        "local stays = before(hook, visible)\n" +
                        "return restricted, copy ~= outer, copy.from == outer, copy.selector.package,\n" +
                        "  stays, visible.args[0] == other");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).tojstring().startsWith("com.example.bank.x"));
        // A visible selector gives nothing, whatever the package of the intent is
        assertFalse(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
    }

    // A permission of a user id which is not in use is denied, like the ones of hidden apps; visible ones stay
    @Test
    public void permissionOfHiddenUidIsDenied() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local hook = hookof('Context.checkPermission', 'checkPermission')\n" +
                        "local granted = paramof({ [0] = 'android.permission.INTERNET', [1] = 1, [2] = 10060 }, {})\n" +
                        "granted.result = 0\n" +
                        "local visible = paramof({ [0] = 'android.permission.INTERNET', [1] = 1, [2] = 10050 }, { [10050] = true })\n" +
                        "visible.result = 0\n" +
                        "local denied = paramof({ [0] = 'android.permission.INTERNET', [1] = 1, [2] = 10060 }, {})\n" +
                        "denied.result = -1\n" +
                        "return after(hook, granted), granted.result, after(hook, visible), visible.result,\n" +
                        "  after(hook, denied), denied.result");
        assertTrue(result.arg(1).toboolean());
        assertEquals(-1, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
        assertEquals(0, result.arg(4).toint());
        assertFalse(result.arg(5).toboolean());
        assertEquals(-1, result.arg(6).toint());
    }

    // An array of targets is copied, the array of the app stays as it is
    @Test
    public void arrayOfAppStays() throws Exception {
        Varargs result = Scripts.run(load("package_probe"),
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
        String substitute = Scripts.run(globals,
                "values = {}\n" +
                        "hook = hookof('PackageManager.getPackageInfo', 'getPackageInfo')\n" +
                        "outer = paramof({ [0] = 'com.example.bank', [1] = 0 }, {}, values)\n" +
                        "before(hook, outer)\n" +
                        "return outer.args[0]").arg1().tojstring();

        globals.set("genuine", CoerceJavaToLua.coerce(new ProbeException(substitute)));
        Varargs result = Scripts.run(globals,
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
        String one = Scripts.run(globals, "local values = {}\n" + suffix).arg1().tojstring();
        String two = Scripts.run(globals, "local values = {}\n" + suffix).arg1().tojstring();
        String same = Scripts.run(globals, "local values = { ['package_probe.suffix'] = '" + one + "' }\n" + suffix)
                .arg1().tojstring();
        assertTrue(one, one.matches("\\.x[0-9]{9}"));
        assertNotEquals(one, two);
        assertEquals(one, same);
    }

    // A shared user id of a visible app, like of Google Play services, stays visible
    @Test
    public void sharedUserOfVisibleApp() throws Exception {
        Globals globals = load("package_resolve");
        Varargs visible = Scripts.run(globals,
                "local param = paramof({ [0] = 10050 }, { [10050] = true })\n" +
                        "param.result = 'com.google.uid.shared:10050'\n" +
                        "local restricted = after(hookof('PackageManager.getNameForUid', 'getNameForUid'), param)\n" +
                        "return restricted, param.result");
        assertFalse(visible.arg(1).toboolean());
        assertEquals("com.google.uid.shared:10050", visible.arg(2).tojstring());

        Varargs hidden = Scripts.run(globals,
                "local param = paramof({ [0] = 10060 }, {})\n" +
                        "param.result = 'com.example.shared:10060'\n" +
                        "local restricted = after(hookof('PackageManager.getNameForUid', 'getNameForUid'), param)\n" +
                        "return restricted, param.result");
        assertTrue(hidden.arg(1).toboolean());
        assertTrue(hidden.arg(2).isnil());
    }

    // An intent any app may handle, like a link, resolves like on a device without the hidden apps, so it agrees with
    // queryIntentActivities: nothing when only a hidden app handles it, the only visible app, or the chooser of
    // Android when several visible apps can and the hidden one was the default; an intent for a hidden app gives nothing
    @Test
    public void linksResolveLikeWithoutHiddenApps() throws Exception {
        Globals globals = load("package_resolve");
        // The Android classes are stand-ins, like the rest of the stubs
        String setup = "local base = luajava.newInstance\n" +
                "luajava.newInstance = function(name, ...)\n" +
                "  if name == 'android.content.Intent' then\n" +
                "    return { setClassName = function(self, p, c) self.target = p .. '/' .. c return self end } end\n" +
                "  return base(name, ...) end\n" +
                "local function infoof(name, web) return { priority = 0, preferredOrder = 0, isDefault = true,\n" +
                "  handleAllWebDataURI = web, activityInfo = { packageName = name, name = name .. '.Main' } } end\n" +
                "local candidates, default\n" +
                "local queries = 0\n" +
                // Of a link Android gives only the default app, which the hidden one can be, unless asked for all
                "local pm = { queryIntentActivities = function(self, intent, flags)\n" +
                "    local value = (type(flags) == 'number' and flags or flags:getValue())\n" +
                "    assert(value == 65536 or value == 196608, value)\n" +
                "    queries = queries + 1\n" +
                "    if default ~= nil and value == 65536 then return listof({ infoof(default, true) }) end\n" +
                "    return listof(candidates) end,\n" +
                "  resolveActivity = function(self, intent, flags)\n" +
                "    assert(intent.target == 'android/com.android.internal.app.ResolverActivity')\n" +
                "    return { activityInfo = { packageName = 'android', name = 'com.android.internal.app.ResolverActivity'," +
                " processName = 'system:ui' } } end }\n" +
                "local function intentof(package) return { getComponent = function(self) return nil end,\n" +
                "  getPackage = function(self) return package end } end\n" +
                "local function resolve(intent, result, names, flags)\n" +
                "  candidates = {}\n" +
                "  for _, name in ipairs(names) do candidates[#candidates + 1] = infoof(name, true) end\n" +
                "  default = (result ~= 'android' and result or nil)\n" +
                "  local param = paramof({ [0] = intent, [1] = flags or 65536 }, { ['org.mozilla.firefox'] = true," +
                " ['com.android.chrome'] = true })\n" +
                "  param.unhooked = function(self, fn) return fn() end\n" +
                "  param.getThis = function(self) return pm end\n" +
                "  param.result = infoof(result)\n" +
                "  if result == 'android' then param.result.activityInfo.name = 'com.android.internal.app.ResolverActivity' end\n" +
                "  local restricted = after(hookof('PackageManager.resolveActivity', 'resolveActivity'), param)\n" +
                "  return restricted, param.result\n" +
                "end\n";
        Varargs only = Scripts.run(globals, setup +
                "return resolve(intentof(nil), 'com.whatsapp', { 'com.whatsapp' })");
        assertTrue(only.arg(1).toboolean());
        assertTrue(only.arg(2).isnil());
        Varargs several = Scripts.run(globals, setup +
                "local restricted, result = resolve(intentof(nil), 'com.hidden.browser'," +
                " { 'com.hidden.browser', 'org.mozilla.firefox', 'com.android.chrome' })\n" +
                "return restricted, result.activityInfo.name, result.activityInfo.processName, result.handleAllWebDataURI");
        assertTrue(several.arg(1).toboolean());
        assertEquals("com.android.internal.app.ResolverActivity", several.arg(2).tojstring());
        assertEquals("system:ui", several.arg(3).tojstring());
        assertTrue(several.arg(4).toboolean());
        Varargs one = Scripts.run(globals, setup +
                "local restricted, result = resolve(intentof(nil), 'com.hidden.browser'," +
                " { 'com.hidden.browser', 'org.mozilla.firefox' })\n" +
                "return restricted, result.activityInfo.packageName");
        assertTrue(one.arg(1).toboolean());
        assertEquals("org.mozilla.firefox", one.arg(2).tojstring());
        // The default browser is hidden: the flags as ResolveInfoFlags of Android 13 and later too
        Varargs flags = Scripts.run(globals, setup +
                "local restricted, result = resolve(intentof(nil), 'com.hidden.browser'," +
                " { 'com.android.chrome', 'com.hidden.browser' }, flagsof(65536))\n" +
                "return restricted, result.activityInfo.packageName, queries");
        assertTrue(flags.arg(1).toboolean());
        assertEquals("com.android.chrome", flags.arg(2).tojstring());
        assertEquals(2, flags.arg(3).toint());
        // The chooser for a hidden and a visible app is that app, like without the hidden one, with one query
        Varargs chooser = Scripts.run(globals, setup +
                "local restricted, result = resolve(intentof(nil), 'android'," +
                " { 'com.hidden.browser', 'org.mozilla.firefox' })\n" +
                "return restricted, result.activityInfo.packageName, queries");
        assertTrue(chooser.arg(1).toboolean());
        assertEquals("org.mozilla.firefox", chooser.arg(2).tojstring());
        assertEquals(1, chooser.arg(3).toint());
        Varargs app = Scripts.run(globals, setup +
                "return resolve(intentof('com.whatsapp'), 'com.whatsapp', { 'com.whatsapp' })");
        assertTrue(app.arg(1).toboolean());
        assertTrue(app.arg(2).isnil());
        // The chooser between the screens of a hidden app gives nothing, of a visible app it stays
        Varargs screens = Scripts.run(globals, setup +
                "return resolve(intentof('com.whatsapp'), 'android', { 'com.whatsapp', 'com.whatsapp' })");
        assertTrue(screens.arg(1).toboolean());
        assertTrue(screens.arg(2).isnil());
        Varargs own = Scripts.run(globals, setup +
                "local restricted, result = resolve(intentof('org.mozilla.firefox'), 'android', {})\n" +
                "return restricted, result.activityInfo.packageName");
        assertFalse(own.arg(1).toboolean());
        assertEquals("android", own.arg(2).tojstring());
    }

    // MATCH_ALL once, also with the bits of a long, which bit32 would lose
    @Test
    public void matchAllKeepsFlags() throws Exception {
        assertEquals(Scripts.function("package_resolve", "matchall"), Scripts.function("generic_filter_by_uid", "matchall"));
        for (String script : new String[]{"package_resolve", "generic_filter_by_uid"}) {
            Globals globals = load(script);
            Varargs result = Scripts.run(globals, "return matchall(65536), matchall(196608), " +
                    "matchall(flagsof(4294967296 + 65536)):getValue(), matchall(flagsof(131072)):getValue()");
            assertEquals(script, 196608, result.arg(1).toint());
            assertEquals(script, 196608, result.arg(2).toint());
            assertEquals(script, 4295163904L, result.arg(3).tolong());
            assertEquals(script, 131072, result.arg(4).toint());
        }
    }

    // Of a link Android gives only the default browser: when it is hidden the visible apps of all, like on a device
    // without it, for each way to ask, and only then
    @Test
    public void linksOfAHiddenDefaultBrowser() throws Exception {
        Globals globals = load("generic_filter_by_uid");
        String run = "local function activity(uid) return { activityInfo = { applicationInfo = { uid = uid," +
                " system = (uid == 10010) } } } end\n" +
                "local asked\n" +
                "local param = paramof({ [0] = 'link', [1] = %s }, {})\n" +
                "param.result = listof({ activity(10060) })\n" +
                "function param:unhooked(fn) return fn() end\n" +
                "function param:getThis() return { queryIntentActivities = function(self, intent, flags)\n" +
                "  assert(intent == 'link')\n" +
                "  asked = (type(flags) == 'number' and flags or flags:getValue())\n" +
                "  return listof({ activity(10010), activity(10060) }) end } end\n" +
                "local restricted = after(hookof('%s'), param)\n" +
                "return restricted, param.result:size(), param.result:get(0).activityInfo.applicationInfo.uid, asked";
        String[][] calls = {{"65536", "PackageManager.queryIntentActivities"},
                {"flagsof(65536)", "PackageManager.queryIntentActivities.33"},
                {"65536", "PackageManager.queryIntentActivitiesAsUser"},
                {"flagsof(65536)", "PackageManager.queryIntentActivitiesAsUser.33"}};
        for (String[] call : calls) {
            Varargs result = Scripts.run(globals, String.format(run, call[0], call[1]));
            assertTrue(call[1], result.arg(1).toboolean());
            assertEquals(call[1], 1, result.arg(2).toint());
            assertEquals(call[1], 10010, result.arg(3).toint());
            assertEquals(call[1], 196608, result.arg(4).toint());
        }

        // Nothing to find for other lists
        Varargs services = Scripts.run(globals,
                "local param = paramof({ [0] = 'link', [1] = 0 }, {})\n" +
                        "param.result = listof({ { serviceInfo = { applicationInfo = { uid = 10060 } } } })\n" +
                        "after(hookof('PackageManager.queryIntentServices'), param)\n" +
                        "return param.result:size()");
        assertEquals(0, services.arg(1).toint());
    }

    // The activities of hidden apps are left out of both lists, which are filled by Android and of the app
    @Test
    public void preferredActivitiesOfHiddenApps() throws Exception {
        Globals globals = load("generic_filter_by_uid");
        String run = "local function activity(name) return { getPackageName = function(self) return name end } end\n" +
                "local filters = listof({ 'chrome', 'bank', 'mail' })\n" +
                "local activities = listof({ activity('com.android.chrome'), activity('com.example.bank'), activity('com.example.mail') })\n" +
                "local param = paramof({ [0] = filters, [1] = activities }, %s)\n" +
                "param.result = %d\n" +
                "local restricted = after(hookof('PackageManager.getPreferredActivities', 'getPreferredActivities'), param)\n" +
                "return restricted, param.result, filters:size(), activities:size(), filters:get(0), activities:get(0):getPackageName()";
        Varargs result = Scripts.run(globals, String.format(run, "{ ['com.android.chrome'] = true }", 3));
        assertTrue(result.arg(1).toboolean());
        assertEquals(1, result.arg(2).toint());
        assertEquals(1, result.arg(3).toint());
        assertEquals(1, result.arg(4).toint());
        assertEquals("chrome", result.arg(5).tojstring());
        assertEquals("com.android.chrome", result.arg(6).tojstring());

        // Android itself returns 0, whatever it found
        assertEquals(0, Scripts.run(globals, String.format(run, "{ ['com.android.chrome'] = true }", 0)).arg(2).toint());

        Varargs all = Scripts.run(globals, String.format(run, "{ ['com.android.chrome'] = true, " +
                "['com.example.bank'] = true, ['com.example.mail'] = true }", 3));
        assertFalse(all.arg(1).toboolean());
        assertEquals(3, all.arg(2).toint());
        assertEquals(3, all.arg(3).toint());
    }

    // The apps which sync, like com.whatsapp, of which the hidden ones are left out
    @Test
    public void syncAdaptersOfHiddenAppsAreLeftOut() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local function syncof(name) return { getPackageName = function(self) return name end } end\n" +
                        "local param = paramof({}, { ['com.google.android.gms'] = true })\n" +
                        "param.findClass = function(self, name) return name end\n" +
                        "param.result = { length = 3, syncof('com.google.android.gms'), syncof('com.whatsapp'), syncof(nil) }\n" +
                        "local restricted = after(hookof('ContentResolver.getSyncAdapterTypes', 'getSyncAdapterTypes'), param)\n" +
                        "return restricted, param.result['length']");
        assertTrue(result.arg(1).toboolean());
        assertEquals(2, result.arg(2).toint());

        // For a user, the hidden overloads: the types, and the packages which sync an authority
        Varargs user = Scripts.run(load("package_resolve"),
                "local function syncof(name) return { getPackageName = function(self) return name end } end\n" +
                        "local types = paramof({}, { ['com.google.android.gms'] = true })\n" +
                        "types.findClass = function(self, name) return name end\n" +
                        "types.result = { length = 2, syncof('com.google.android.gms'), syncof('com.whatsapp') }\n" +
                        "local packages = paramof({}, { ['com.google.android.gms'] = true })\n" +
                        "packages.result = arrayof('com.whatsapp', 'com.google.android.gms')\n" +
                        "return after(hookof('ContentResolver.getSyncAdapterTypesAsUser', 'getSyncAdapterTypesAsUser'), types),\n" +
                        "  types.result['length'],\n" +
                        "  after(hookof('ContentResolver.getSyncAdapterPackagesForAuthorityAsUser',\n" +
                        "    'getSyncAdapterPackagesForAuthorityAsUser'), packages), packages.result.length, packages.result[1]");
        assertTrue(user.arg(1).toboolean());
        assertEquals(1, user.arg(2).toint());
        assertTrue(user.arg(3).toboolean());
        assertEquals(1, user.arg(4).toint());
        assertEquals("com.google.android.gms", user.arg(5).tojstring());
    }

    // Like for a user id which is not in use
    @Test
    public void signaturesOfHiddenUid() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local param = paramof({ [0] = 10001, [1] = 10060 }, { [10001] = true })\n" +
                        "param.result = -3\n" +
                        "local restricted = after(hookof('PackageManager.checkSignatures/uid', 'checkSignatures'), param)\n" +
                        "return restricted, param.result");
        assertTrue(result.arg(1).toboolean());
        assertEquals(-4, result.arg(2).toint());
    }

    // A hidden user id has no certificate, also like for a user id which is not in use
    @Test
    public void signingCertificateOfHiddenUid() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local hook = hookof('PackageManager.hasSigningCertificate/uid', 'hasSigningCertificate')\n" +
                        "local hidden = paramof({ [0] = 10060 }, { [10001] = true })\n" +
                        "hidden.result = true\n" +
                        "local visible = paramof({ [0] = 10001 }, { [10001] = true })\n" +
                        "visible.result = true\n" +
                        "local none = paramof({ [0] = 10060 }, {})\n" +
                        "none.result = false\n" +
                        "return after(hook, hidden), hidden.result, after(hook, visible), visible.result, after(hook, none)");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
    }

    // The packages of a hidden user id are null, like of a user id which is not in use; the first one is reported
    @Test
    public void packagesOfHiddenUid() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local hook = hookof('PackageManager.getPackagesForUid', 'getPackagesForUid')\n" +
                        "local hidden = paramof({ [0] = 10060 }, { [10001] = true })\n" +
                        "hidden.result = arrayof('com.whatsapp', 'com.whatsapp.w4b')\n" +
                        "local visible = paramof({ [0] = 10001 }, { [10001] = true })\n" +
                        "visible.result = arrayof('com.example')\n" +
                        "local none = paramof({ [0] = 10060 }, {})\n" +
                        "none.result = arrayof()\n" +
                        "local restricted, name = after(hook, hidden)\n" +
                        "return restricted, name, hidden.result, after(hook, visible), visible.result[1], after(hook, none)");
        assertTrue(result.arg(1).toboolean());
        assertEquals("com.whatsapp", result.arg(2).tojstring());
        assertTrue(result.arg(3).isnil());
        assertFalse(result.arg(4).toboolean());
        assertEquals("com.example", result.arg(5).tojstring());
        assertFalse(result.arg(6).toboolean());

        // A shared user id with a visible app: its other packages stay hidden, like for their lookups
        Varargs shared = Scripts.run(load("package_resolve"),
                "local hook = hookof('PackageManager.getPackagesForUid', 'getPackagesForUid')\n" +
                        "local param = paramof({ [0] = 10070 }, { [10070] = true, ['com.example.shared'] = true })\n" +
                        "param.result = arrayof('com.example.shared', 'com.third.hidden')\n" +
                        "local all = paramof({ [0] = 10070 }, { [10070] = true, ['com.a'] = true, ['com.b'] = true })\n" +
                        "all.result = arrayof('com.a', 'com.b')\n" +
                        "local restricted, old, new = after(hook, param)\n" +
                        "return restricted, old, new, param.result.length, param.result[1], after(hook, all)");
        assertTrue(shared.arg(1).toboolean());
        assertEquals("2", shared.arg(2).tojstring());
        assertEquals("1", shared.arg(3).tojstring());
        assertEquals(1, shared.arg(4).toint());
        assertEquals("com.example.shared", shared.arg(5).tojstring());
        assertFalse(shared.arg(6).toboolean());
    }

    // The names of several user ids: null for the user ids of hidden apps, like for user ids which are not in use
    @Test
    public void namesOfHiddenUids() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local hook = hookof('PackageManager.getNamesForUids', 'getNamesForUids')\n" +
                        "local param = paramof({ [0] = arrayof(10001, 10060, 1000) }, { [10001] = true, [1000] = true })\n" +
                        "param.result = arrayof('test.app', 'com.hidden', 'android')\n" +
                        "local visible = paramof({ [0] = arrayof(10001) }, { [10001] = true })\n" +
                        "visible.result = arrayof('test.app')\n" +
                        "return after(hook, param), param.result.length, param.result[1], param.result[2], param.result[3],\n" +
                        "  after(hook, visible)");
        assertTrue(result.arg(1).toboolean());
        assertEquals(3, result.arg(2).toint());
        assertEquals("test.app", result.arg(3).tojstring());
        assertTrue(result.arg(4).isnil());
        assertEquals("android", result.arg(5).tojstring());
        assertFalse(result.arg(6).toboolean());
    }

    // A service resolved for a user, like resolveService: an implicit intent gets the first visible service,
    // an intent naming a hidden app gets none
    @Test
    public void servicesResolvedForUser() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local function service(name) return { serviceInfo = { packageName = name } } end\n" +
                        "local hidden, visible = service('com.hidden'), service('com.visible')\n" +
                        "local implicit = { getComponent = function() return nil end, getPackage = function() return nil end }\n" +
                        "local explicit = { getComponent = function() return nil end, getPackage = function() return 'com.hidden' end }\n" +
                        "local function call(intent)\n" +
                        "  local param = paramof({ [0] = intent, [1] = 0, [2] = 0 }, { ['com.visible'] = true })\n" +
                        "  param.result = hidden\n" +
                        "  param.getThis = function() return { queryIntentServices = function(self, i, f)\n" +
                        "    return listof({ hidden, visible }) end } end\n" +
                        "  param.unhooked = function(self, fn) return fn() end\n" +
                        "  return param\n" +
                        "end\n" +
                        "local hook = hookof('PackageManager.resolveServiceAsUser', 'resolveServiceAsUser')\n" +
                        "local any, named = call(implicit), call(explicit)\n" +
                        "local r1, old, new = after(hook, any)\n" +
                        "return r1, old, new, any.result == visible, after(hook, named), named.result");
        assertTrue(result.arg(1).toboolean());
        assertEquals("com.hidden", result.arg(2).tojstring());
        assertEquals("com.visible", result.arg(3).tojstring());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertTrue(result.arg(6).isnil());
    }

    // Lists of components keep system apps, which the Android framework uses, lists of packages do not
    @Test
    public void componentsOfSystemAppsStay() throws Exception {
        Globals globals = load("generic_filter_by_uid");
        String items = "local own = { uid = 10001 }\n" +
                "local system = { uid = 10010, system = true }\n" +
                "local other = { uid = 10060 }\n";
        Varargs activities = Scripts.run(globals, items +
                "local list = listof({ { activityInfo = { applicationInfo = own } },\n" +
                "  { activityInfo = { applicationInfo = system } }, { activityInfo = { applicationInfo = other } } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "local restricted = after(hookof('PackageManager.queryIntentActivities.33'), param)\n" +
                "return restricted, list:size(), list:get(1).activityInfo.applicationInfo == system");
        assertTrue(activities.arg(1).toboolean());
        assertEquals(2, activities.arg(2).toint());
        assertTrue(activities.arg(3).toboolean());

        Varargs providers = Scripts.run(globals, items +
                "local list = listof({ { applicationInfo = system }, { applicationInfo = other } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "after(hookof('PackageManager.queryContentProviders'), param)\n" +
                "return list:size()");
        assertEquals(1, providers.arg(1).toint());

        Varargs packages = Scripts.run(globals, items +
                "local list = listof({ { applicationInfo = own }, { applicationInfo = system } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "after(hookof('PackageManager.getInstalledPackages'), param)\n" +
                "return list:size()");
        assertEquals(1, packages.arg(1).toint());

        // Properties have a package, like the lookups of single packages
        Varargs properties = Scripts.run(globals,
                "local function property(name) return { getPackageName = function(self) return name end } end\n" +
                        "local list = listof({ property('com.android.vending'), property('com.example.bank') })\n" +
                        "local param = paramof({}, { ['com.android.vending'] = true })\n" +
                        "param.result = list\n" +
                        "after(hookof('PackageManager.queryApplicationProperty'), param)\n" +
                        "return list:size(), list:get(0):getPackageName()");
        assertEquals(1, properties.arg(1).toint());
        assertEquals("com.android.vending", properties.arg(2).tojstring());

        // Device admins are components, by their package
        Varargs admins = Scripts.run(globals,
                "local function admin(name) return { getPackageName = function(self) return name end } end\n" +
                        "local list = listof({ admin('com.google.android.gms'), admin('com.example.mdm') })\n" +
                        "local param = paramof({}, { ['com.google.android.gms'] = true })\n" +
                        "param.result = list\n" +
                        "after(hookof('DevicePolicyManager.getActiveAdmins'), param)\n" +
                        "return list:size(), list:get(0):getPackageName()");
        assertEquals(1, admins.arg(1).toint());
        assertEquals("com.google.android.gms", admins.arg(2).tojstring());

        // The hidden overloads of the widgets and the admins, like their public twins
        Varargs overloads = Scripts.run(globals,
                "local function widget(name) return { provider = { getPackageName = function(self) return name end } } end\n" +
                        "local function admin(name) return { getPackageName = function(self) return name end } end\n" +
                        "local widgets = listof({ widget('com.hidden'), widget('com.visible') })\n" +
                        "local w = paramof({}, { ['com.visible'] = true })\n" +
                        "w.result = widgets\n" +
                        "local a = paramof({}, {})\n" +
                        "a.result = listof({ admin('com.example.mdm') })\n" +
                        "return after(hookof('AppWidgetManager.getInstalledProvidersForProfile.category'), w), widgets:size(),\n" +
                        "  after(hookof('DevicePolicyManager.getActiveAdminsAsUser'), a), a.result == nil");
        assertTrue(overloads.arg(1).toboolean());
        assertEquals(1, overloads.arg(2).toint());
        assertTrue(overloads.arg(3).toboolean());
        assertTrue(overloads.arg(4).toboolean());

        // The search providers of the global search, by the package of their screen
        Varargs searches = Scripts.run(globals,
                "local function searchable(name) return { getSearchActivity = function(self)\n" +
                        "  return { getPackageName = function(self) return name end } end } end\n" +
                        "local list = listof({ searchable('com.hidden'), searchable('com.google.android.googlequicksearchbox') })\n" +
                        "local param = paramof({}, { ['com.google.android.googlequicksearchbox'] = true })\n" +
                        "param.result = list\n" +
                        "local activities = listof({ { activityInfo = { applicationInfo = { uid = 10060 } } },\n" +
                        "  { activityInfo = { applicationInfo = { uid = 10010, system = true } } } })\n" +
                        "local global = paramof({}, {})\n" +
                        "global.result = activities\n" +
                        "return after(hookof('SearchManager.getSearchablesInGlobalSearch'), param), list:size(),\n" +
                        "  after(hookof('SearchManager.getGlobalSearchActivities'), global), activities:size()");
        assertTrue(searches.arg(1).toboolean());
        assertEquals(1, searches.arg(2).toint());
        assertTrue(searches.arg(3).toboolean());
        assertEquals(1, searches.arg(4).toint());

        // The phone accounts of VoIP apps, by the package of their connection service
        Varargs accounts = Scripts.run(globals,
                "local function handle(name) return { getComponentName = function(self)\n" +
                        "  return { getPackageName = function(self) return name end } end } end\n" +
                        "local list = listof({ handle('com.hidden.voip'), handle('com.visible.voip') })\n" +
                        "local param = paramof({}, { ['com.visible.voip'] = true })\n" +
                        "param.result = list\n" +
                        "return after(hookof('TelecomManager.getSelfManagedPhoneAccounts'), param), list:size(),\n" +
                        "  list:get(0):getComponentName():getPackageName()");
        assertTrue(accounts.arg(1).toboolean());
        assertEquals(1, accounts.arg(2).toint());
        assertEquals("com.visible.voip", accounts.arg(3).tojstring());

        // The processes in error of other apps go, and none left is null, like Android answers without errors
        Varargs errors = Scripts.run(globals,
                "local list = listof({ { uid = 10060 }, { uid = 10001 } })\n" +
                        "local param = paramof({}, {})\n" +
                        "param.result = list\n" +
                        "local none = paramof({}, {})\n" +
                        "none.result = listof({ { uid = 10060 } })\n" +
                        "return after(hookof('ActivityManager.getProcessesInErrorState'), param), list:size(),\n" +
                        "  after(hookof('ActivityManager.getProcessesInErrorState'), none), none.result == nil");
        assertTrue(errors.arg(1).toboolean());
        assertEquals(1, errors.arg(2).toint());
        assertTrue(errors.arg(3).toboolean());
        assertTrue(errors.arg(4).toboolean());

        // No admins left is null, like Android answers without admins (DevicePolicyManagerService.getActiveAdmins)
        Varargs noadmins = Scripts.run(globals,
                "local list = listof({ { getPackageName = function(self) return 'com.example.mdm' end } })\n" +
                        "local param = paramof({}, {})\n" +
                        "param.result = list\n" +
                        "return after(hookof('DevicePolicyManager.getActiveAdmins'), param), param.result == nil");
        assertTrue(noadmins.arg(1).toboolean());
        assertTrue(noadmins.arg(2).toboolean());

        // Instrumentations stay when both the test runner and the package it tests are visible
        Varargs instrumentations = Scripts.run(globals,
                "local function instrumentation(name, target) return { packageName = name, targetPackage = target } end\n" +
                        "local list = listof({ instrumentation('com.example.test', 'com.example'),\n" +
                        "  instrumentation('com.example.test', 'com.example.bank'),\n" +
                        "  instrumentation('com.example.bank.test', 'com.example') })\n" +
                        "local param = paramof({}, { ['com.example'] = true, ['com.example.test'] = true })\n" +
                        "param.result = list\n" +
                        "after(hookof('PackageManager.queryInstrumentation'), param)\n" +
                        "return list:size(), list:get(0).targetPackage");
        assertEquals(1, instrumentations.arg(1).toint());
        assertEquals("com.example", instrumentations.arg(2).tojstring());

        // Processes of the system manage all apps
        Varargs managed = Scripts.run(globals, items +
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
        Varargs some = Scripts.run(globals, changed +
                "local param = paramof({ [0] = 0 }, { ['com.android.vending'] = true })\n" +
                "param.result = result\n" +
                "return after(hook, param), param.result.sequence, param.result.names:size(), param.result.names:get(0)");
        assertTrue(some.arg(1).toboolean());
        assertEquals(5, some.arg(2).toint());
        assertEquals(1, some.arg(3).toint());
        assertEquals("com.android.vending", some.arg(4).tojstring());

        Varargs none = Scripts.run(globals, changed +
                "local param = paramof({ [0] = 0 }, {})\n" +
                "param.result = result\n" +
                "return after(hook, param), param.result");
        assertTrue(none.arg(1).toboolean());
        assertTrue(none.arg(2).isnil());

        Varargs all = Scripts.run(globals, changed +
                "local param = paramof({ [0] = 0 }, { ['com.android.vending'] = true, ['com.example.bank'] = true })\n" +
                "param.result = result\n" +
                "return after(hook, param), param.result == result");
        assertFalse(all.arg(1).toboolean());
        assertTrue(all.arg(2).toboolean());
    }

    // A permission which a hidden app defines, like com.example.bank.permission.C2D_MESSAGE, is not found,
    // like of an app which is not installed; those of Android and of visible apps stay
    @Test
    public void permissionsOfHiddenAppsAreNotFound() throws Exception {
        Globals globals = load("package_resolve");
        Varargs single = Scripts.run(globals,
                "local param = paramof({ [0] = 'com.example.bank.permission.C2D_MESSAGE', [1] = 0 }, {})\n" +
                        "param.result = { name = 'com.example.bank.permission.C2D_MESSAGE', packageName = 'com.example.bank' }\n" +
                        "local restricted, old = after(hookof('PackageManager.getPermissionInfo', 'getPermissionInfo'), param)\n" +
                        "return restricted, old, param.result.exception, param.result.message");
        assertTrue(single.arg(1).toboolean());
        assertEquals("com.example.bank", single.arg(2).tojstring());
        assertEquals("android.content.pm.PackageManager$NameNotFoundException", single.arg(3).tojstring());
        assertEquals("com.example.bank.permission.C2D_MESSAGE", single.arg(4).tojstring());

        Varargs own = Scripts.run(globals,
                "local param = paramof({ [0] = 'android.permission.CAMERA', [1] = 0 }, { android = true })\n" +
                        "param.result = { name = 'android.permission.CAMERA', packageName = 'android' }\n" +
                        "return after(hookof('PackageManager.getPermissionInfo', 'getPermissionInfo'), param), param.result.name");
        assertFalse(own.arg(1).toboolean());
        assertEquals("android.permission.CAMERA", own.arg(2).tojstring());

        Varargs list = Scripts.run(globals,
                "local list = listof({ { packageName = 'android' }, { packageName = 'com.example.bank' } })\n" +
                        "local param = paramof({ [0] = nil, [1] = 0 }, { android = true })\n" +
                        "param.result = list\n" +
                        "local restricted = after(hookof('PackageManager.queryPermissionsByGroup', 'queryPermissionsByGroup'), param)\n" +
                        "return restricted, list:size(), list:get(0).packageName");
        assertTrue(list.arg(1).toboolean());
        assertEquals(1, list.arg(2).toint());
        assertEquals("android", list.arg(3).tojstring());

        // The permissions of a group of a hidden app: the group is not found, like getPermissionGroupInfo;
        // those of a group of Android stay
        String byGroup = "local param = paramof({ [0] = '%s', [1] = 0 }, { android = true })\n" +
                "param.result = listof({ { packageName = 'android' } })\n" +
                "function param:unhooked(fn) return fn() end\n" +
                "function param:getThis() return { getPermissionGroupInfo = function(self, name, flags)\n" +
                "  return { packageName = (name == 'android.permission-group.CAMERA' and 'android' or 'com.example.bank') } end } end\n" +
                "local restricted, old = after(hookof('PackageManager.queryPermissionsByGroup', 'queryPermissionsByGroup'), param)\n" +
                "return restricted, old, param.result.exception, param.result.message";
        Varargs hidden = Scripts.run(globals, String.format(byGroup, "com.example.bank.permission-group.API"));
        assertTrue(hidden.arg(1).toboolean());
        assertEquals("com.example.bank", hidden.arg(2).tojstring());
        assertEquals("android.content.pm.PackageManager$NameNotFoundException", hidden.arg(3).tojstring());
        assertEquals("com.example.bank.permission-group.API", hidden.arg(4).tojstring());
        Varargs camera = Scripts.run(globals, String.format(byGroup, "android.permission-group.CAMERA"));
        assertFalse(camera.arg(1).toboolean());
        assertTrue(camera.arg(3).isnil());
    }

    // Permission groups name the app which defines them too, like getAllPermissionGroups an app's own group
    @Test
    public void permissionGroupsOfHiddenAppsAreNotFound() throws Exception {
        Globals globals = load("package_resolve");
        Varargs single = Scripts.run(globals,
                "local param = paramof({ [0] = 'com.example.bank.permission-group.API', [1] = 0 }, {})\n" +
                        "param.result = { name = 'com.example.bank.permission-group.API', packageName = 'com.example.bank' }\n" +
                        "local restricted, old = after(hookof('PackageManager.getPermissionGroupInfo', 'getPermissionGroupInfo'), param)\n" +
                        "return restricted, old, param.result.exception, param.result.message");
        assertTrue(single.arg(1).toboolean());
        assertEquals("com.example.bank", single.arg(2).tojstring());
        assertEquals("android.content.pm.PackageManager$NameNotFoundException", single.arg(3).tojstring());
        assertEquals("com.example.bank.permission-group.API", single.arg(4).tojstring());

        Varargs list = Scripts.run(globals,
                "local list = listof({ { packageName = 'android' }, { packageName = 'com.example.bank' } })\n" +
                        "local param = paramof({ [0] = 0 }, { android = true })\n" +
                        "param.result = list\n" +
                        "local restricted = after(hookof('PackageManager.getAllPermissionGroups', 'getAllPermissionGroups'), param)\n" +
                        "return restricted, list:size(), list:get(0).packageName");
        assertTrue(list.arg(1).toboolean());
        assertEquals(1, list.arg(2).toint());
        assertEquals("android", list.arg(3).tojstring());
    }

    // The hidden methods for a user have the items of their public twins, also when filtered twice
    @Test
    public void listsForUser() throws Exception {
        Globals globals = load("generic_filter_by_uid");
        String items = "local own = { uid = 10001 }\n" +
                "local system = { uid = 10010, system = true }\n" +
                "local other = { uid = 10060 }\n";
        Varargs services = Scripts.run(globals, items +
                "local list = listof({ { serviceInfo = { applicationInfo = own } },\n" +
                "  { serviceInfo = { applicationInfo = system } }, { serviceInfo = { applicationInfo = other } } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "local restricted = after(hookof('PackageManager.queryIntentServicesAsUser.33'), param)\n" +
                "local again = after(hookof('PackageManager.queryIntentServices.33'), param)\n" +
                "return restricted, again, list:size()");
        assertTrue(services.arg(1).toboolean());
        assertFalse(services.arg(2).toboolean());
        assertEquals(2, services.arg(3).toint());

        Varargs packages = Scripts.run(globals, items +
                "local list = listof({ { applicationInfo = own }, { applicationInfo = other } })\n" +
                "local param = paramof({}, {})\n" +
                "param.result = list\n" +
                "after(hookof('PackageManager.getInstalledPackagesAsUser'), param)\n" +
                "return list:size(), list:get(0).applicationInfo == own");
        assertEquals(1, packages.arg(1).toint());
        assertTrue(packages.arg(2).toboolean());
    }

    // A document picked from a provider with several authorities: the grant of one of them counts, nothing without one
    @Test
    public void pickedDocumentsOfAProviderWithAuthorities() throws Exception {
        Globals globals = load("generic_filter_by_uid");
        Varargs result = Scripts.run(globals,
                "local grants = { { getUri = function(self) return { getAuthority = function(self) return 'com.example.docs.legacy' end } end } }\n" +
                        "local param = { getApplicationContext = function(self) return { getContentResolver = function(self)\n" +
                        "  return { getPersistedUriPermissions = function(self) return { size = function(self) return #grants end,\n" +
                        "    get = function(self, index) return grants[index + 1] end } end } end } end }\n" +
                        "return picked(param, 'com.example.docs;com.example.docs.legacy'), picked(param, 'com.example.docs'),\n" +
                        "  picked(param, nil)");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
    }

    // A provider of a hidden app is not found, also for a user
    @Test
    public void providerForUser() throws Exception {
        Varargs result = Scripts.run(load("package_resolve"),
                "local param = paramof({ [0] = 'com.example.bank.provider' }, {})\n" +
                        "param.result = { packageName = 'com.example.bank' }\n" +
                        "local restricted, pkg = after(hookof('PackageManager.resolveContentProviderAsUser', 'resolveContentProviderAsUser'), param)\n" +
                        "return restricted, param.result, pkg");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());
        assertEquals("com.example.bank", result.arg(3).tojstring());
    }
}
