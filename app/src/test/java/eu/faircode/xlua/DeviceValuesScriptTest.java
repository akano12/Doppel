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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Scripts which fake values of the device (system properties, the operator, the battery, the security patch)
// with Lua stand-ins, like LocationScriptTest
public class DeviceValuesScriptTest {
    private static final String STUBS = String.join("\n",
            "function hookof(name, method)",
            "  return { getName = function(self) return name end,",
            "    getMethodName = function(self) return method end }",
            "end",
            "function paramof(result, arguments)",
            "  local param = { result = result }",
            "  param.getResult = function(self) return self.result end",
            "  param.setResult = function(self, value) self.result = value end",
            "  param.getArgument = function(self, index) return arguments[index] end",
            "  param.getSetting = function(self, name) return nil end",
            "  return param",
            "end",
            // Runs after() and returns whether restricted and the result
            "function run(hook, param)",
            "  local restricted = after(hook, param)",
            "  return restricted, param.result",
            "end");

    private static Globals load(String script) throws Exception {
        return Scripts.load(script, STUBS);
    }

    private static Varargs run(Globals globals, String lua) {
        return Scripts.run(globals, lua);
    }

    @Test
    public void propertiesOfTheDeviceAreFaked() throws Exception {
        Globals globals = load("systemproperties_get");
        for (String key : new String[]{"ro.boot.hardware", "ro.board.platform", "ro.build.expect.bootloader",
                "ro.build.expect.baseband", "ro.boot.hardware.platform", "ro.vendor.build.id"})
            assertEquals(key, "unknown", run(globals, "return fakedevice('" + key + "', 'x')").arg1().tojstring());
        // Not all properties of the hardware, WallpaperManager reads ro.boot.hardware.color
        assertTrue(run(globals, "return fakedevice('ro.boot.hardware.color', 'x')").arg1().isnil());
        assertTrue(run(globals, "return fakedevice('ro.product.first_api_level', '34')").arg1().isnil());

        // The security patch level per quarter
        assertEquals("2025-04-01", run(globals, "return fakedevice('ro.build.version.security_patch', '2025-05-05')").arg1().tojstring());
        assertEquals("2024-10-01", run(globals, "return fakedevice('ro.vendor.build.security_patch', '2024-12-01')").arg1().tojstring());
        assertEquals("2025-01-01", run(globals, "return fakedevice('ro.build.version.security_patch', '2025-01-05')").arg1().tojstring());
        assertTrue(run(globals, "return fakedevice('ro.build.version.security_patch', 'x')").arg1().isnil());

        Varargs result = run(globals, "return run(hookof('SystemProperties.get/device', 'get'), " +
                "paramof('2025-05-05', { [0] = 'ro.build.version.security_patch' }))");
        assertTrue(result.arg1().toboolean());
        assertEquals("2025-04-01", result.arg(2).tojstring());
    }

    // getInt and getLong do not use get: the fake value as a number, else the default
    private Varargs typed(Globals globals, String method, String key, String result, String def) {
        return run(globals, "return run(hookof('SystemProperties." + method + "/device', '" + method + "'), " +
                "paramof(" + result + ", { [0] = '" + key + "', [1] = " + def + " }))");
    }

    @Test
    public void numbersOfPropertiesAreFaked() throws Exception {
        Globals globals = load("systemproperties_get");
        Varargs result = typed(globals, "getLong", "ro.build.date.utc", "1736100000", "0");
        assertTrue(result.arg1().toboolean());
        assertEquals(0, result.arg(2).toint());
        result = typed(globals, "getInt", "ro.vendor.build.date.utc", "1736100000", "-1");
        assertTrue(result.arg1().toboolean());
        assertEquals(0, result.arg(2).toint());

        // A fake which is no number gives the default
        result = typed(globals, "getLong", "ro.build.version.incremental", "12025142", "-1");
        assertTrue(result.arg1().toboolean());
        assertEquals(-1, result.arg(2).toint());

        // Not set, or not a number: the default stays
        assertFalse(typed(globals, "getLong", "ro.build.date.utc", "5", "5").arg1().toboolean());
        assertFalse(typed(globals, "getInt", "ro.build.version.sdk", "36", "0").arg1().toboolean());
        assertFalse(typed(globals, "getInt", "persist.sys.foo", "1", "0").arg1().toboolean());

        assertEquals(26, run(globals, "return number('0x1a', 'getInt')").arg1().toint());
        assertTrue(run(globals, "return number('12 ', 'getInt')").arg1().isnil());
        assertTrue(run(globals, "return number('3000000000', 'getInt')").arg1().isnil());
        assertEquals(3000000000L, run(globals, "return number('3000000000', 'getLong')").arg1().tolong());
    }

    @Test
    public void securityPatchIsAQuarter() throws Exception {
        Globals globals = load("security_patch");
        Varargs result = run(globals, "return run(hookof('Build.VERSION.SECURITY_PATCH', nil), paramof('2025-09-05', {}))");
        assertTrue(result.arg1().toboolean());
        assertEquals("2025-07-01", result.arg(2).tojstring());
        assertFalse(run(globals, "return run(hookof('Build.VERSION.SECURITY_PATCH', nil), paramof('2025-07-01', {}))").arg1().toboolean());
        assertFalse(run(globals, "return run(hookof('Build.VERSION.SECURITY_PATCH', nil), paramof('', {}))").arg1().toboolean());
    }

    private Varargs operator(Globals globals, String method, String result) {
        return run(globals, "return run(hookof('TelephonyManager." + method + "', '" + method + "'), paramof(" + result + ", {}))");
    }

    @Test
    public void operatorIsTheTestNetwork() throws Exception {
        Globals globals = load("test_network");
        Varargs result = operator(globals, "getSimOperator", "'26201'");
        assertTrue(result.arg1().toboolean());
        assertEquals("00101", result.arg(2).tojstring());
        assertEquals("unknown", operator(globals, "getSimOperatorName", "'Telekom.de'").arg(2).tojstring());
        assertEquals("unknown", operator(globals, "getOperatorAlphaLong", "'Telekom.de'").arg(2).tojstring());
        assertEquals("00101", operator(globals, "getRegisteredPlmn", "'310260'").arg(2).tojstring());
        assertEquals(-1, operator(globals, "getSimCarrierId", "1").arg(2).toint());

        // Without a SIM or service
        assertFalse(operator(globals, "getSimOperator", "''").arg1().toboolean());
        assertFalse(operator(globals, "getSimOperatorName", "''").arg1().toboolean());
        assertFalse(operator(globals, "getSimCarrierId", "-1").arg1().toboolean());
        assertFalse(operator(globals, "getOperatorNumeric", "nil").arg1().toboolean());
    }

    // Stand-ins of the package manager, the system dialer and the configuration of the system, and of luajava,
    // Java classes of the JDK cannot be used by luajava on a desktop JVM
    private static final String APPS = String.join("\n",
            "function arrayof(...)",
            "  local array = { ... }",
            "  array['length'] = select('#', ...)",
            "  return array",
            "end",
            "function listof(...)",
            "  return { items = { ... },",
            "    add = function(self, value) self.items[#self.items + 1] = value end,",
            "    size = function(self) return #self.items end,",
            "    toArray = function(self) return arrayof(table.unpack(self.items)) end,",
            "    toString = function(self)",
            "      local strings = {}",
            "      for index, item in ipairs(self.items) do strings[index] = (type(item) == 'table' and item:toString() or item) end",
            "      return '[' .. table.concat(strings, ', ') .. ']'",
            "    end }",
            "end",
            "function appof(packageName, uid, system)",
            "  return { packageName = packageName, uid = uid, system = system }",
            "end",
            "apps = { ['com.android.dialer'] = appof('com.android.dialer', 10011, true),",
            "  ['com.google.android.apps.messaging'] = appof('com.google.android.apps.messaging', 10012, true),",
            "  ['com.third.visible'] = appof('com.third.visible', 10102, false) }",
            // The apps which can be the default phone app: the app itself and the system dialer
            "dialers = arrayof(",
            "  { activityInfo = { applicationInfo = appof('com.example.app', 10200, false) } },",
            "  { activityInfo = { applicationInfo = apps['com.android.dialer'] } })",
            "dialer = 'com.android.dialer'",
            "libraries = listof()",
            "local pm = {",
            "  getApplicationInfo = function(self, name, flags)",
            "    if apps[name] == nil then error('NameNotFoundException ' .. name) end",
            "    return apps[name]",
            "  end,",
            "  queryIntentActivities = function(self, intent, flags)",
            "    assert(intent.action == 'android.intent.action.DIAL', intent.action)",
            "    return { toArray = function(self) return dialers end }",
            "  end,",
            "  getSharedLibraries = function(self, flags) return libraries end }",
            "local context = {",
            "  getPackageManager = function(self) return pm end,",
            "  getSystemService = function(self, name)",
            "    assert(name == 'telecom', name)",
            "    return { getSystemDialerPackage = function(self) return dialer end }",
            "  end }",
            "local resources = {",
            "  getIdentifier = function(self, name, type, package)",
            "    return (name == 'default_sms_application' and 2 or 0)",
            "  end,",
            "  getString = function(self, id) return 'com.google.android.apps.messaging' end }",
            "luajava = {",
            "  newInstance = function(name, action)",
            "    if name == 'android.content.Intent' then return { action = action } end",
            "    assert(name == 'java.util.ArrayList', name)",
            "    return listof()",
            "  end,",
            "  bindClass = function(name)",
            "    if name == 'android.content.res.Resources' then",
            "      return { getSystem = function(self) return resources end }",
            "    end",
            "    return { newInstance = function(self, class, size) return {} end }",
            "  end }",
            "function appparamof(result)",
            "  local param = paramof(result, {})",
            "  param.getApplicationContext = function(self) return context end",
            "  param.getPackageName = function(self) return 'com.example.app' end",
            "  param.isVisible = function(self, ai) return ai.uid == 10200 or ai.system end",
            // Like XParam: the lookup without hooks, the app itself visible
            "  param.isInstalledVisible = function(self, name)",
            "    if name == 'com.example.app' then return true end",
            "    local ok, ai = pcall(pm.getApplicationInfo, pm, name, 0)",
            "    return (ok and ai ~= nil and (ai.uid == 10200 or ai.system == true))",
            "  end",
            "  param.unhooked = function(self, fn) return fn() end",
            "  return param",
            "end",
            // A SharedLibraryInfo
            "function libraryof(name, type)",
            "  return { getName = function(self) return name end, getType = function(self) return type end,",
            "    toString = function(self) return name end }",
            "end",
            // An array of names as the list it becomes
            "function names(result) return (result.toArray and result or listof(table.unpack(result))):toString() end");

    // settingssecure_getstring tells the default of the system for the settings with the same function
    @Test
    public void systemDefaultIsTheSameAsOfSettings() throws Exception {
        assertEquals(function("default_app_filter", "systemdefault"), function("settingssecure_getstring", "systemdefault"));
    }

    // The text of a function of a script, up to its end; a checkout can have CRLF line ends
    private static String function(String script, String name) throws Exception {
        String source = Scripts.read(script).replace("\r\n", "\n");
        int start = source.indexOf("\nfunction " + name + "(");
        int end = source.indexOf("\nend\n", start);
        assertTrue(script + " " + name, start >= 0 && end > start);
        return source.substring(start, end);
    }

    private Varargs defaultApp(Globals globals, String method, String result) {
        return run(globals, "return run(hookof('" + method + "', '" + method + "'), appparamof(" + result + "))");
    }

    // Apps compare the default SMS and phone app without checking for null: a hidden one becomes the one of the system
    @Test
    public void defaultAppsAreTheOnesOfTheSystem() throws Exception {
        Globals globals = load("default_app_filter");
        run(globals, APPS);
        Varargs result = defaultApp(globals, "getDefaultDialerPackage", "'com.third.hidden'");
        assertTrue(result.arg1().toboolean());
        assertEquals("com.android.dialer", result.arg(2).tojstring());
        result = defaultApp(globals, "getDefaultSmsPackage", "'com.third.visible'");
        assertTrue(result.arg1().toboolean());
        assertEquals("com.google.android.apps.messaging", result.arg(2).tojstring());

        // Apps of the system, the app itself and none stay
        assertFalse(defaultApp(globals, "getDefaultDialerPackage", "'com.android.dialer'").arg1().toboolean());
        assertFalse(defaultApp(globals, "getDefaultSmsPackage", "'com.example.app'").arg1().toboolean());
        assertFalse(defaultApp(globals, "getDefaultSmsPackage", "nil").arg1().toboolean());

        // The configured SMS app is disabled
        run(globals, "apps['com.google.android.apps.messaging'].enabled = false");
        result = defaultApp(globals, "getDefaultSmsPackage", "'com.third.visible'");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());

        // Without the system dialer an app which can be the default, but not the app itself
        run(globals, "dialer = nil");
        assertEquals("com.android.dialer", defaultApp(globals, "getDefaultDialerPackage", "'com.third.hidden'").arg(2).tojstring());
        run(globals, "dialers = arrayof(dialers[1])");
        result = defaultApp(globals, "getDefaultDialerPackage", "'com.third.hidden'");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
    }

    // Both lists of shared libraries hide the vendor libraries of the system and keep the libraries of apps
    @Test
    public void sharedLibrariesAreFilteredAlike() throws Exception {
        Globals globals = load("features");
        run(globals, APPS);
        run(globals, "libraries = listof(libraryof('android.test.base', 0), libraryof('com.qualcomm.qti.audio', 0), " +
                "libraryof('com.samsung.android.sdk', 1), libraryof('com.google.android.trichromelibrary', 2), " +
                "libraryof('com.example.sdk', 3))");
        Varargs result = run(globals, "local param = appparamof(libraries)\n" +
                "local restricted = after(hookof('PackageManager.getSharedLibraries', 'getSharedLibraries'), param)\n" +
                "return restricted, param.result:toString()");
        assertTrue(result.arg1().toboolean());
        assertEquals("[android.test.base, com.google.android.trichromelibrary, com.example.sdk]", result.arg(2).tojstring());

        result = run(globals, "local param = appparamof(arrayof('android.test.base', 'com.qualcomm.qti.audio', " +
                "'com.samsung.android.sdk', 'com.google.android.trichromelibrary', 'com.example.sdk', 'com.google.android.maps'))\n" +
                "local restricted = after(hookof('PackageManager.getSystemSharedLibraryNames', 'getSystemSharedLibraryNames'), param)\n" +
                "return restricted, names(param.result)");
        assertTrue(result.arg1().toboolean());
        assertEquals("[android.test.base, com.google.android.trichromelibrary, com.example.sdk, com.google.android.maps]",
                result.arg(2).tojstring());

        // Before Android 8 there is no getSharedLibraries
        run(globals, "libraries = nil");
        result = run(globals, "local param = appparamof(arrayof('android.test.base', 'com.qualcomm.qti.audio'))\n" +
                "local restricted = after(hookof('PackageManager.getSystemSharedLibraryNames', 'getSystemSharedLibraryNames'), param)\n" +
                "return restricted, names(param.result)");
        assertTrue(result.arg1().toboolean());
        assertEquals("[android.test.base]", result.arg(2).tojstring());
    }

    @Test
    public void batteryCapacityIsRounded() throws Exception {
        Globals globals = load("battery");
        for (String method : new String[]{"getIntProperty", "getLongProperty"}) {
            Varargs result = run(globals, "return run(hookof('BatteryManager." + method + "', '" + method + "'), " +
                    "paramof(57, { [0] = 4 }))");
            assertTrue(method, result.arg1().toboolean());
            assertEquals(method, 60, result.arg(2).toint());
        }
    }
}
