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
                "local candidates\n" +
                "local pm = { queryIntentActivities = function(self, intent, flags)\n" +
                "    assert(flags == 65536) return listof(candidates) end,\n" +
                "  resolveActivity = function(self, intent, flags)\n" +
                "    assert(intent.target == 'android/com.android.internal.app.ResolverActivity')\n" +
                "    return { activityInfo = { packageName = 'android', name = 'com.android.internal.app.ResolverActivity'," +
                " processName = 'system:ui' } } end }\n" +
                "local function intentof(package) return { getComponent = function(self) return nil end,\n" +
                "  getPackage = function(self) return package end } end\n" +
                "local function resolve(intent, result, names)\n" +
                "  candidates = {}\n" +
                "  for _, name in ipairs(names) do candidates[#candidates + 1] = infoof(name, true) end\n" +
                "  local param = paramof({ [0] = intent, [1] = 65536 }, { ['org.mozilla.firefox'] = true," +
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
        // The chooser for a hidden and a visible app is that app, like without the hidden one
        Varargs chooser = Scripts.run(globals, setup +
                "local restricted, result = resolve(intentof(nil), 'android'," +
                " { 'com.hidden.browser', 'org.mozilla.firefox' })\n" +
                "return restricted, result.activityInfo.packageName");
        assertTrue(chooser.arg(1).toboolean());
        assertEquals("org.mozilla.firefox", chooser.arg(2).tojstring());
        Varargs app = Scripts.run(globals, setup +
                "return resolve(intentof('com.whatsapp'), 'com.whatsapp', { 'com.whatsapp' })");
        assertTrue(app.arg(1).toboolean());
        assertTrue(app.arg(2).isnil());
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
