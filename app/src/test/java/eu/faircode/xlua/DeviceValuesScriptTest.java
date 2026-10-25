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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Scripts which fake values of the device (system properties, the operator, the battery, the security patch,
// memory and storage, input devices and biometrics) with Lua stand-ins, like LocationScriptTest
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

    // The operator of the test network, like TelephonyManager, and the same as getprop prints (see shell_commands);
    // properties which are not set stay empty
    @Test
    public void propertiesOfTheOperatorAreFaked() throws Exception {
        Globals globals = load("systemproperties_get");
        String[][] cases = {{"gsm.operator.numeric", "26201", "00101"}, {"gsm.sim.operator.numeric", "26201", "00101"},
                {"gsm.operator.alpha", "Telekom.de", "unknown"}, {"gsm.sim.operator.alpha", "Telekom.de", "unknown"},
                {"gsm.operator.iso-country", "de", "xx"}, {"gsm.sim.operator.iso-country", "de", "xx"}};
        for (String[] c : cases) {
            Varargs result = run(globals, "return run(hookof('SystemProperties.get.default/operator', 'get'), " +
                    "paramof('" + c[1] + "', { [0] = '" + c[0] + "', [1] = '' }))");
            assertTrue(c[0], result.arg1().toboolean());
            assertEquals(c[0], c[2], result.arg(2).tojstring());
        }
        assertFalse(run(globals, "return run(hookof('SystemProperties.get/operator', 'get'), " +
                "paramof('', { [0] = 'gsm.operator.numeric' }))").arg1().toboolean());
        assertFalse(run(globals, "return run(hookof('SystemProperties.get/operator', 'get'), " +
                "paramof('26201', { [0] = 'gsm.operator.foo' }))").arg1().toboolean());
    }

    // The variants with names run for their properties only, the properties of the device have patterns
    @Test
    public void filtersOfVariants() throws Exception {
        Globals globals = load("systemproperties_get");
        Varargs result = run(globals, "local function keys(name)\n" +
                "  local result = {}\n" +
                "  for _, key in ipairs(filter(hookof(name, 'get'))) do result[key] = true end\n" +
                "  return result\n" +
                "end\n" +
                "local operator = keys('SystemProperties.get/operator')\n" +
                "return keys('SystemProperties.get.default/serial')['ril.serialnumber'],\n" +
                "  operator['gsm.sim.operator.iso-country'], #filter(hookof('SystemProperties.get/operator', 'get')),\n" +
                "  keys('SystemProperties.get/timezone')['persist.sys.timezone'],\n" +
                "  filter(hookof('SystemProperties.get/device', 'get')) == nil,\n" +
                "  filter(hookof('SystemProperties.getInt/device', 'getInt')) == nil");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(6, result.arg(3).toint());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
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

        // The codes of a subscription are 0 without codes; an MNC of 0 (00) of a subscription with an MCC is a code
        assertFalse(operator(globals, "getMcc", "0").arg1().toboolean());
        assertFalse(subscription(globals, "getMnc", 0, 0).arg1().toboolean());
        result = subscription(globals, "getMnc", 0, 460);
        assertTrue(result.arg1().toboolean());
        assertEquals(1, result.arg(2).toint());
        assertEquals(1, operator(globals, "getMcc", "262").arg(2).toint());
    }

    // A code of a subscription, which getMcc of it tells whether the subscription has codes
    private Varargs subscription(Globals globals, String method, int result, int mcc) {
        return run(globals, "local param = paramof(" + result + ", {})\n" +
                "param.getThis = function(self) return { getMcc = function(self) return " + mcc + " end } end\n" +
                "param.unhooked = function(self, fn) return fn() end\n" +
                "return run(hookof('SubscriptionInfo." + method + "', '" + method + "'), param)");
    }

    // Stand-ins of the package manager, the system dialer and the configuration of the system, which are stubs
    // in unit tests, and of luajava: the lists hold Lua tables and the test reads them back as such
    private static final String APPS = String.join("\n",
            Scripts.ARRAYOF,
            Scripts.APPOF,
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

    // Real FeatureInfo and arrays, the setting and the classes on the stack (callers of features)
    private static final String FEATURES = String.join("\n",
            "function featureof(name)",
            "  local info = luajava.newInstance('android.content.pm.FeatureInfo')",
            "  info.name = name",
            "  return info",
            "end",
            "function featuresof(...)",
            "  local infos = { ... }",
            "  local array = luajava.bindClass('java.lang.reflect.Array'):newInstance(",
            "    luajava.bindClass('android.content.pm.FeatureInfo'), #infos)",
            "  for index, info in ipairs(infos) do array[index] = info end",
            "  return array",
            "end",
            "function featureparamof(result, name, keep)",
            "  local param = paramof(result, { [0] = name })",
            "  param.getSetting = function(self, key) assert(key == 'features.keep', key) return keep end",
            "  return param",
            "end",
            // Frames named like class.method
            "function stackof(...)",
            "  local frames = { length = select('#', ...) }",
            "  for index, name in ipairs({ ... }) do",
            "    local class, method = string.match(name, '^(.*)%.([^.]*)$')",
            "    frames[index] = { getClassName = function(self) return class end,",
            "      getMethodName = function(self) return method end }",
            "  end",
            "  return frames",
            "end",
            "stack = stackof('com.example.app.Main.onCreate')",
            "function callers() return stack end",
            "function names(result)",
            "  local list = {}",
            "  for index = 1, result['length'] do list[index] = tostring(result[index].name) end",
            "  return table.concat(list, ',')",
            "end");

    // All features are hidden but the kept ones; the framework gets its features when it asks itself
    @Test
    public void featuresAreHiddenButTheKeptOnes() throws Exception {
        Globals globals = load("features");
        run(globals, FEATURES);
        String has = "return run(hookof('PackageManager.hasSystemFeature', 'hasSystemFeature'), featureparamof(";
        String keep = "'android.hardware.nfc, android.hardware.camera*'";

        Varargs result = run(globals, has + "true, 'android.hardware.wifi', nil))");
        assertTrue(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(run(globals, has + "true, 'com.google.android.feature.PIXEL_EXPERIENCE', " + keep + "))").arg1().toboolean());
        // Kept by name or by a name ending in *
        assertFalse(run(globals, has + "true, 'android.hardware.nfc', " + keep + "))").arg1().toboolean());
        assertTrue(run(globals, has + "true, 'android.hardware.nfc.hce', " + keep + "))").arg1().toboolean());
        assertFalse(run(globals, has + "true, 'android.hardware.camera.front', " + keep + "))").arg1().toboolean());
        // A feature the device does not have
        assertFalse(run(globals, has + "false, 'android.hardware.wifi', nil))").arg1().toboolean());

        // The framework checks features itself, like WebView, which does not start without its feature, and
        // the Wi-Fi manager, which is null without its feature. The stacks are those of LSPosed, which replaces
        // the frame of the hooked method by one of a class of its own with a random name.
        String engine = "'eu.faircode.xlua.XCore$6.after', 'eu.faircode.xlua.ModernBackend.lambda$hook$0', " +
                "'m.proceed', 'android.telephony.PheendeeDaesteampDrenash.hasSystemFeature', ";
        assertTrue(run(globals, has + "true, 'android.software.webview', nil))").arg1().toboolean());
        run(globals, "stack = stackof(" + engine + "'android.webkit.WebViewFactory.getProvider', " +
                "'android.webkit.WebView.<init>', 'com.example.app.Main.onCreate')");
        assertFalse(run(globals, has + "true, 'android.software.webview', nil))").arg1().toboolean());
        run(globals, "stack = stackof(" + engine + "'android.net.wifi.WifiFrameworkInitializer.lambda$registerServiceWrappers$0', " +
                "'android.app.SystemServiceRegistry.getSystemService', 'com.example.app.Main.onCreate')");
        assertFalse(run(globals, has + "true, 'android.hardware.wifi', nil))").arg1().toboolean());
        // The package manager calls the hooked method itself, hasSystemFeature without a version the one with one
        run(globals, "stack = stackof(" + engine + "'android.app.ApplicationPackageManager.hasSystemFeature', " +
                "'com.android.internal.policy.PhoneWindow.<init>', 'android.app.Activity.attach')");
        assertFalse(run(globals, has + "true, 'android.software.picture_in_picture', nil))").arg1().toboolean());
        // The app and its libraries, also androidx and the support library, get false
        for (String caller : new String[]{"com.example.app.Main.onCreate", "androidx.core.content.ContextCompat.check",
                "android.support.v4.content.ContextCompat.check"}) {
            run(globals, "stack = stackof(" + engine + "'" + caller + "', 'android.app.Activity.performCreate')");
            assertTrue(caller, run(globals, has + "true, 'android.hardware.wifi', nil))").arg1().toboolean());
            run(globals, "stack = stackof(" + engine + "'android.app.ApplicationPackageManager.hasSystemFeature', " +
                    "'" + caller + "', 'android.app.Activity.performCreate')");
            assertTrue(caller, run(globals, has + "true, 'android.hardware.wifi', nil))").arg1().toboolean());
        }
        run(globals, "stack = stackof('com.example.app.Main.onCreate')");

        // The list has the same features, and the OpenGL ES version, which has no name
        String list = "local param = featureparamof(featuresof(%s), nil, " + keep + ")\n" +
                "local restricted = after(hookof('PackageManager.getSystemAvailableFeatures', 'getSystemAvailableFeatures'), param)\n" +
                "return restricted, names(param.result)";
        result = run(globals, String.format(list, "featureof('android.hardware.wifi'), featureof('android.hardware.nfc'), " +
                "featureof('android.hardware.camera.flash'), featureof('android.software.webview'), " +
                "featureof('com.samsung.feature.samsung_experience_mobile'), featureof(nil)"));
        assertTrue(result.arg1().toboolean());
        assertEquals("android.hardware.nfc,android.hardware.camera.flash,nil", result.arg(2).tojstring());
        result = run(globals, String.format(list, "featureof('android.hardware.nfc'), featureof(nil)"));
        assertFalse(result.arg1().toboolean());
        assertEquals("android.hardware.nfc,nil", result.arg(2).tojstring());
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

    @Test
    public void sizesAreRounded() throws Exception {
        Globals globals = load("resources");
        // 3919 MiB of memory is sold as 4 GiB, 118 GB of storage as 128 GB, free space has two digits
        assertEquals(4294967296L, run(globals, "return memorysize(4109369344)").arg1().tolong());
        assertEquals(128000000000L, run(globals, "return storagesize(118000000000)").arg1().tolong());
        assertEquals(7900, run(globals, "return freesize(7917)").arg1().toint());
        assertEquals(99, run(globals, "return freesize(99)").arg1().toint());

        Varargs result = run(globals, "return run(hookof('File.getTotalSpace', 'getTotalSpace'), paramof(118000000000, {}))");
        assertTrue(result.arg1().toboolean());
        assertEquals(128000000000L, result.arg(2).tolong());
        // Small file systems keep their size
        assertFalse(run(globals, "return run(hookof('File.getTotalSpace', 'getTotalSpace'), paramof(500000000, {}))")
                .arg1().toboolean());

        // The memory info of the app is changed in place
        result = run(globals, "local info = { totalMem = 4109369344, availMem = 1234567890 }\n" +
                "local restricted = after(hookof('ActivityManager.getMemoryInfo', 'getMemoryInfo'), paramof(nil, { [0] = info }))\n" +
                "return restricted, info.totalMem, info.availMem");
        assertTrue(result.arg1().toboolean());
        assertEquals(4294967296L, result.arg(2).tolong());
        assertEquals(1200000000L, result.arg(3).tolong());
    }

    // Input devices by id and luajava for the lookups, which must not run the hooks (see XParam.unhooked)
    private static final String INPUT = String.join("\n",
            Scripts.ARRAYOF,
            "function deviceof(external, name)",
            "  return { isExternal = function(self) return external end, getName = function(self) return name end }",
            "end",
            "devices = { [1] = deviceof(false, 'touchscreen'), [7] = deviceof(true, 'keyboard') }",
            "unhooked = false",
            "luajava = { bindClass = function(name)",
            "  if name == 'android.view.InputDevice' then",
            "    return { getDevice = function(self, id) assert(unhooked, 'hooked lookup') return devices[id] end }",
            "  elseif name == 'java.lang.Integer' then",
            "    return { TYPE = 'int' }",
            "  end",
            "  assert(name == 'java.lang.reflect.Array', name)",
            "  return { newInstance = function(self, type, size) assert(type == 'int') return { length = size } end }",
            "end }",
            "function inputparamof(result)",
            "  local param = paramof(result, {})",
            "  param.unhooked = function(self, fn) unhooked = true local value = fn() unhooked = false return value end",
            "  return param",
            "end");

    @Test
    public void externalInputDevicesAreHidden() throws Exception {
        Globals globals = Scripts.load("input_devices", STUBS + "\n" + INPUT);
        // The keyboard and a device which was removed meanwhile (9) are left out
        Varargs result = run(globals, "local param = inputparamof(arrayof(1, 7, 9))\n" +
                "local restricted = after(hookof('InputManager.getInputDeviceIds', 'getInputDeviceIds'), param)\n" +
                "return restricted, param.result.length, param.result[1]");
        assertTrue(result.arg1().toboolean());
        assertEquals(1, result.arg(2).toint());
        assertEquals(1, result.arg(3).toint());
        assertFalse(run(globals, "return run(hookof('InputDevice.getDeviceIds', 'getDeviceIds'), inputparamof(arrayof(1)))")
                .arg1().toboolean());

        result = run(globals, "return run(hookof('InputDevice.getDevice', 'getDevice'), inputparamof(devices[7]))");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        assertFalse(run(globals, "return run(hookof('InputManager.getInputDevice', 'getInputDevice'), inputparamof(devices[1]))")
                .arg1().toboolean());
    }

    // A param of BiometricManager with the values of the scopes, like XParam.putValue
    private static final String BIOMETRIC = String.join("\n",
            "values = {}",
            "function biometricparamof(result, authenticators, this)",
            "  local param = paramof(result, { [0] = authenticators })",
            "  param.setArgument = function(self, index, value) authenticators = value end",
            "  param.getArgument = function(self, index) return authenticators end",
            "  param.getException = function(self) return nil end",
            "  param.getThis = function(self) return this end",
            "  param.putValue = function(self, name, value, scope) values[name .. tostring(scope)] = value end",
            "  param.getValue = function(self, name, scope) return values[name .. tostring(scope)] end",
            "  return param",
            "end");

    @Test
    public void biometricsAreTheScreenLockOnly() throws Exception {
        Globals globals = Scripts.load("biometrics_credential", STUBS + "\n" + BIOMETRIC);
        // BIOMETRIC_WEAK and DEVICE_CREDENTIAL become DEVICE_CREDENTIAL; biometrics only, the screen lock only
        // and authenticators which are an error stay
        Varargs result = run(globals, "local param = biometricparamof(nil, 33023)\n" +
                "local restricted = before(hookof('BiometricManager.getStrings', 'getStrings'), param)\n" +
                "return restricted, param:getArgument(0)");
        assertTrue(result.arg1().toboolean());
        assertEquals(32768, result.arg(2).toint());
        for (String authenticators : new String[]{"255", "32768", "33024"})
            assertFalse(authenticators, run(globals, "return before(hookof('BiometricManager.getStrings', 'getStrings'), " +
                    "biometricparamof(nil, " + authenticators + "))").arg1().toboolean());

        // There was no biometric authentication, the screen lock keeps its time
        result = run(globals, "return run(hookof('BiometricManager.getLastAuthenticationTime', 'getLastAuthenticationTime'), " +
                "biometricparamof(1234, 15))");
        assertTrue(result.arg1().toboolean());
        assertEquals(-1, result.arg(2).toint());
        assertFalse(run(globals, "return run(hookof('BiometricManager.getLastAuthenticationTime', 'getLastAuthenticationTime'), " +
                "biometricparamof(1234, 32768))").arg1().toboolean());

        // The texts of biometrics only are none, the texts of the screen lock stay
        result = run(globals, "local strings = {}\n" +
                "after(hookof('BiometricManager.getStrings', 'getStrings'), biometricparamof(strings, 15))\n" +
                "return run(hookof('BiometricManager.Strings.getButtonLabel', 'getButtonLabel'), biometricparamof('Use fingerprint', 0, strings))");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        assertFalse(run(globals, "local strings = {}\n" +
                "after(hookof('BiometricManager.getStrings', 'getStrings'), biometricparamof(strings, 32768))\n" +
                "return run(hookof('BiometricManager.Strings.getButtonLabel', 'getButtonLabel'), biometricparamof('Use PIN', 0, strings))")
                .arg1().toboolean());
    }
}
