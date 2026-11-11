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
            "function hookof(name, method, class, group)",
            "  return { getName = function(self) return name end,",
            "    getMethodName = function(self) return method end,",
            "    getClassName = function(self) return class end,",
            "    getGroup = function(self) return group end }",
            "end",
            "function paramof(result, arguments)",
            "  local param = { result = result }",
            "  param.getResult = function(self) return self.result end",
            "  param.setResult = function(self, value) self.result = value end",
            "  param.getArgument = function(self, index) return arguments[index] end",
            "  param.getException = function(self) return nil end",
            "  param.getSetting = function(self, name) return nil end",
            // The app is made for the version of Android in target, see batteryCapacityIsRounded
            "  param.getApplicationContext = function(self)",
            "    return { getApplicationInfo = function(self) return { targetSdkVersion = target } end } end",
            "  param.unhooked = function(self, fn) return fn() end",
            "  return param",
            "end",
            // The system properties which are set, for SystemProperties.get(key) of isset
            "PROPERTIES = {}",
            "local bind = luajava.bindClass",
            "luajava.bindClass = function(name)",
            "  if name ~= 'android.os.SystemProperties' then return bind(name) end",
            "  return { get = function(self, key) return PROPERTIES[key] or '' end }",
            "end",
            // Runs after() and returns whether restricted and the result
            "function run(hook, param)",
            "  local restricted = after(hook, param)",
            "  return restricted, param.result",
            "end");

    private static Globals load(String script) throws Exception {
        return Scripts.load(script, STUBS);
    }

    @Test
    public void propertiesOfTheDeviceAreFaked() throws Exception {
        Globals globals = load("systemproperties_get");
        for (String key : new String[]{"ro.boot.hardware", "ro.board.platform", "ro.build.expect.bootloader",
                "ro.build.expect.baseband", "ro.boot.hardware.platform", "ro.vendor.build.id",
                "ro.product.marketname", "ro.product.vendor.marketname", "ro.config.marketing_name",
                "ro.vendor.product.model", "ro.odm.product.manufacturer", "persist.sys.exif.model", "ro.vendor.soc.model",
                "ro.vendor.soc.model.part_name", "ro.hardware.chipname", "ro.chipname", "ro.vendor.qti.soc_model",
                "ro.mediatek.platform", "ro.hardware.egl", "ro.hardware.vulkan", "ro.boot.hardware.vulkan",
                "ro.hardware.gralloc", "ro.hardware.power", "ro.boot.hardware.revision", "ro.revision", "ro.boot.bootdevice"})
            assertEquals(key, "unknown", Scripts.run(globals, "return fakedevice('" + key + "', 'x')").arg1().tojstring());
        // Flags and numbers of the same form stay, like ro.hardware.keystore_desede, which AndroidKeyStore reads
        for (String[] c : new String[][]{{"ro.hardware.keystore_desede", "true"}, {"ro.hardware.virtual_device", "1"}})
            assertTrue(c[0], Scripts.run(globals, "return fakedevice('" + c[0] + "', '" + c[1] + "')").arg1().isnil());
        // The cores the runtime compiles for: generic of ARM, the default variant of x86
        assertEquals("generic", Scripts.run(globals, "return fakedevice('dalvik.vm.isa.arm64.variant', 'cortex-a78')").arg1().tojstring());
        assertEquals("x86_64", Scripts.run(globals, "return fakedevice('dalvik.vm.isa.x86_64.variant', 'kabylake')").arg1().tojstring());
        // Not all properties of the hardware, WallpaperManager reads ro.boot.hardware.color
        assertTrue(Scripts.run(globals, "return fakedevice('ro.boot.hardware.color', 'x')").arg1().isnil());
        assertTrue(Scripts.run(globals, "return fakedevice('ro.product.first_api_level', '34')").arg1().isnil());
        // Nor the ABIs apps load code with, nor the launch level, which TelephonyManager reads too
        for (String key : new String[]{"ro.vendor.product.cpu.abilist", "ro.vendor.api_level", "ro.vendor.qti.soc_id"})
            assertTrue(key, Scripts.run(globals, "return fakedevice('" + key + "', 'x')").arg1().isnil());
        // The GPU family is faked: the EGL and Vulkan loaders read the properties natively, which the hooks do not
        // change; ANGLE as the driver of the system stays, which GraphicsEnvironment checks in Java to set it up
        assertTrue(Scripts.run(globals, "return fakedevice('ro.hardware.egl', 'angle')").arg1().isnil());
        // A property of the device not starting with ro. is faked by get too
        assertEquals("unknown", Scripts.run(globals, "return run(hookof('SystemProperties.get/device', 'get'), " +
                "paramof('SM-S918B', { [0] = 'persist.sys.exif.model' }))").arg(2).tojstring());

        // The security patch level per quarter
        assertEquals("2025-04-01", Scripts.run(globals,
                "return fakedevice('ro.build.version.security_patch', '2025-05-05')").arg1().tojstring());
        assertEquals("2024-10-01", Scripts.run(globals,
                "return fakedevice('ro.vendor.build.security_patch', '2024-12-01')").arg1().tojstring());
        assertEquals("2025-01-01", Scripts.run(globals,
                "return fakedevice('ro.build.version.security_patch', '2025-01-05')").arg1().tojstring());
        assertTrue(Scripts.run(globals, "return fakedevice('ro.build.version.security_patch', 'x')").arg1().isnil());

        Varargs result = Scripts.run(globals, "return run(hookof('SystemProperties.get/device', 'get'), " +
                "paramof('2025-05-05', { [0] = 'ro.build.version.security_patch' }))");
        assertTrue(result.arg1().toboolean());
        assertEquals("2025-04-01", result.arg(2).tojstring());
    }

    // USB debugging off, like adb_enabled 0 (see settingssecure_getstring): adbd stopped, adb left out of the USB
    // functions, else they stay
    @Test
    public void propertiesOfDebuggingAreFaked() throws Exception {
        Globals globals = load("systemproperties_get");
        String[][] cases = {{"init.svc.adbd", "running", "stopped"}, {"sys.usb.config", "mtp,adb", "mtp"},
                // Adb alone is charging only, which Android gives as mtp, and its persistent config as not set
                {"sys.usb.state", "adb", "mtp"}, {"sys.usb.config", "adb", "mtp"}, {"persist.sys.usb.config", "adb", ""},
                {"persist.sys.usb.config", "adb,mtp", "mtp"},
                {"sys.usb.config", "mtp,adb,rndis", "mtp,rndis"}, {"ro.debuggable", "1", "0"}, {"ro.secure", "0", "1"},
                {"persist.logd.size", "16M", ""},
                // The logger buffer Off of the developer options, with the tag it keeps on
                {"persist.log.tag", "Settings", ""}, {"persist.log.tag", "Settings,I", "I"}, {"persist.log.tag.snet_event_log", "I", ""}};
        for (String[] c : cases) {
            Varargs result = Scripts.run(globals, "return run(hookof('SystemProperties.get/debugging', 'get'), " +
                    "paramof('" + c[1] + "', { [0] = '" + c[0] + "' }))");
            assertTrue(c[0] + " " + c[1], result.arg1().toboolean());
            assertEquals(c[0] + " " + c[1], c[2], result.arg(2).tojstring());
        }
        // A property not set gives the default of get(key, default), and getInt and getBoolean the number and flag
        assertEquals("def", Scripts.run(globals, "return run(hookof('SystemProperties.get.default/debugging', 'get'), " +
                "paramof('16M', { [0] = 'persist.logd.size.main', [1] = 'def' }))").arg(2).tojstring());
        assertEquals(0, Scripts.run(globals, "return run(hookof('SystemProperties.getInt/debugging', 'getInt'), " +
                "paramof(1, { [0] = 'ro.debuggable', [1] = 0 }))").arg(2).toint());
        assertFalse(Scripts.run(globals, "return run(hookof('SystemProperties.getBoolean/debugging', 'getBoolean'), " +
                "paramof(true, { [0] = 'ro.debuggable', [1] = false }))").arg(2).toboolean());
        Varargs flag = Scripts.run(globals, "return run(hookof('SystemProperties.getBoolean/debugging', 'getBoolean'), " +
                "paramof(false, { [0] = 'ro.debuggable', [1] = true }))");
        assertFalse(flag.arg1().toboolean());
        assertFalse(flag.arg(2).toboolean());
        assertEquals(262144, Scripts.run(globals, "return run(hookof('SystemProperties.getInt/debugging', 'getInt'), " +
                "paramof(1048576, { [0] = 'persist.logd.size', [1] = 262144 }))").arg(2).toint());
        // The USB functions are no numbers, which getInt leaves
        assertFalse(Scripts.run(globals, "return run(hookof('SystemProperties.getInt/debugging', 'getInt'), " +
                "paramof(5, { [0] = 'sys.usb.config', [1] = 0 }))").arg1().toboolean());
        for (String[] c : new String[][]{{"init.svc.adbd", "stopped"}, {"sys.usb.state", "mtp"}, {"sys.usb.state", ""}})
            assertFalse(c[0] + " " + c[1], Scripts.run(globals, "return run(hookof('SystemProperties.get/debugging', " +
                    "'get'), paramof('" + c[1] + "', { [0] = '" + c[0] + "' }))").arg1().toboolean());
    }

    // get(key, default) gives the default for a property which is not set or empty, so it stays, like on a device
    // without the property; an empty fake becomes the default, also null; get(key) is unchanged
    @Test
    public void propertiesWithDefault() throws Exception {
        Globals globals = load("systemproperties_get");
        String[][] cases = {
                // variant, key, result, default, restricted, expected
                {"device", "ro.zz.soc.x", "'d'", "'d'", "false", "d"},
                {"device", "ro.hardware.chipname", "'exynos'", "'d'", "true", "unknown"},
                {"serial", "ril.serialnumber", "'d'", "'d'", "false", "d"},
                {"serial", "ril.serialnumber", "'R58M'", "'d'", "true", "unknown"},
                {"serial", "ril.iccid.sim1", "'8949'", "'d'", "true", "d"},
                {"serial", "ril.iccid.sim1", "'8949'", "nil", "true", null},
                {"timezone", "persist.sys.timezone", "'d'", "'d'", "false", "d"},
                {"timezone", "persist.sys.timezone", "'Europe/Berlin'", "'d'", "true", "UTC"},
                {"debugging", "persist.logd.size", "'d'", "'d'", "false", "d"},
                {"debugging", "persist.logd.size", "'16M'", "nil", "true", null}};
        for (String[] c : cases) {
            Varargs result = Scripts.run(globals, "return run(hookof('SystemProperties.get.default/" + c[0] +
                    "', 'get'), paramof(" + c[2] + ", { [0] = '" + c[1] + "', [1] = " + c[3] + " }))");
            String name = c[0] + " " + c[1] + " " + c[2] + " " + c[3];
            assertEquals(name, Boolean.parseBoolean(c[4]), result.arg1().toboolean());
            assertEquals(name, c[5], result.arg(2).isnil() ? null : result.arg(2).tojstring());
        }
        // Without a default: an empty fake is empty
        assertEquals("", Scripts.run(globals, "return run(hookof('SystemProperties.get/serial', 'get'), " +
                "paramof('8949', { [0] = 'ril.iccid.sim1' }))").arg(2).tojstring());
    }

    // A property which is set to the default is faked too, else an app could confirm a guess of the real value by
    // passing it as the default; without SystemProperties it counts as set
    @Test
    public void propertiesSetToTheDefault() throws Exception {
        Globals globals = load("systemproperties_get");
        Scripts.run(globals, "PROPERTIES = { ['ro.product.model'] = 'Pixel 7', ['ro.debuggable'] = '1', " +
                "['ro.build.date.utc'] = '5' }");
        assertEquals("unknown", Scripts.run(globals, "return run(hookof('SystemProperties.get.default/device', 'get'), " +
                "paramof('Pixel 7', { [0] = 'ro.product.model', [1] = 'Pixel 7' }))").arg(2).tojstring());
        Varargs flag = Scripts.run(globals, "return run(hookof('SystemProperties.getBoolean/debugging', 'getBoolean'), " +
                "paramof(true, { [0] = 'ro.debuggable', [1] = true }))");
        assertTrue(flag.arg1().toboolean());
        assertFalse(flag.arg(2).toboolean());
        assertEquals(0, typed(globals, "getLong", "ro.build.date.utc", "5", "5").arg(2).toint());
        // Not set, the default stays
        assertEquals("Pixel 7", Scripts.run(globals, "return run(hookof('SystemProperties.get.default/device', 'get'), " +
                "paramof('Pixel 7', { [0] = 'ro.product.name', [1] = 'Pixel 7' }))").arg(2).tojstring());

        Scripts.run(globals, "luajava.bindClass = function(name) error('hidden') end");
        assertEquals("unknown", Scripts.run(globals, "return run(hookof('SystemProperties.get.default/device', 'get'), " +
                "paramof('Pixel 7', { [0] = 'ro.product.name', [1] = 'Pixel 7' }))").arg(2).tojstring());
    }

    // The OpenGL strings without the chip, its family and the build of the driver; the version stays
    @Test
    public void graphicsStringsNameNoChip() throws Exception {
        Globals globals = load("gles_getstring");
        String[][] cases = {{"7937", "Adreno (TM) 740", "unknown"}, {"7937", "Mali-G715-Immortalis MC11", "unknown"},
                {"7937", "PowerVR Rogue GE8320", "unknown"},
                {"7937", "Android Emulator OpenGL ES Translator (NVIDIA GeForce RTX 5060 Laptop GPU)", "unknown"},
                {"7936", "Google (NVIDIA Corporation)", "unknown"}, {"7936", "Qualcomm", "unknown"},
                {"7938", "OpenGL ES 3.2 V@0502.0 (GIT@4f7d2a8, I8bd1c1a3b0, 1681374963) (Date:04/13/23)", "OpenGL ES 3.2"}};
        for (String[] c : cases) {
            Varargs result = Scripts.run(globals, "return run(hookof('GLES20.glGetString', 'glGetString'), " +
                    "paramof('" + c[1] + "', { [0] = " + c[0] + " }))");
            assertTrue(c[1], result.arg1().toboolean());
            assertEquals(c[1], c[2], result.arg(2).tojstring());
        }
        // Already unknown, and extensions of Khronos only, stay
        assertFalse(Scripts.run(globals, "return run(hookof('GLES20.glGetString', 'glGetString'), " +
                "paramof('unknown', { [0] = 7936 }))").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return run(hookof('GLES20.glGetString', 'glGetString'), " +
                "paramof('GL_OES_EGL_image', { [0] = 7939 }))").arg1().toboolean());
    }

    // The panel of the display: the exact density becomes the stable density of the device, which does not follow the
    // display size the user sets, also in the metrics of Resources, and of the modes and refresh rates only the
    // active one stays, at the rate of the mode
    @Test
    public void displayPanelIsGeneric() throws Exception {
        Globals globals = load("display_panel");
        Varargs result = Scripts.run(globals, String.join("\n",
                "local bind = luajava.bindClass",
                "luajava.bindClass = function(name)",
                "  if name == 'android.util.DisplayMetrics' then return { DENSITY_DEVICE_STABLE = 440 } end",
                "  return bind(name) end",
                // getRefreshRate follows the frame rate of the app, the mode does not
                "local mode = { getRefreshRate = function(self) return 120 end }",
                "local display = { getMode = function(self) return mode end,",
                "  getRefreshRate = function(self) return 60 end }",
                "local function call(method, result, arguments)",
                "  local param = paramof(result, arguments or {})",
                "  param.getThis = function(self) return display end",
                "  return after(hookof('Display', method), param), param.result",
                "end",
                // A Java array as a Lua table, Display.Mode is a stub in unit tests
                "array = function(class, values) values['length'] = #values return values end",
                "local metrics = { densityDpi = 480, xdpi = 403.411, ydpi = 403.041 }",
                "local metrics2 = { densityDpi = 480, xdpi = 403.411, ydpi = 403.041 }",
                "local same = { densityDpi = 480, xdpi = 440, ydpi = 440 }",
                "local restricted = call('getRealMetrics', nil, { [0] = metrics })",
                "local restricted2 = call('getMetrics', nil, { [0] = same })",
                // The metrics of Resources: of ResourcesImpl when it fills them, and when the hooks are installed,
                // of the system and of the app; without a hook of Resources.getDisplayMetrics
                "local function resources(m) return { getDisplayMetrics = function(self) return m end } end",
                "local param3 = paramof(nil, {})",
                "param3.getThis = function(self) return resources(metrics2) end",
                "local restricted3 = after(hookof('ResourcesImpl', 'updateConfigurationImpl'), param3)",
                "local system = { densityDpi = 420, xdpi = 429.0, ydpi = 423.0 }",
                "local app = { densityDpi = 420, xdpi = 429.0, ydpi = 423.0 }",
                "local install = paramof(resources(system), {})",
                "install.getApplicationContext = function(self) return { getResources = function() return resources(app) end } end",
                "local installed = after(hookof('Resources.mSystem', '#mSystem'), install)",
                "local again = after(hookof('Resources.mSystem', '#mSystem'), install)",
                "local again2 = after(hookof('ResourcesImpl', 'updateConfiguration'), param3)",
                "local _, modes = call('getSupportedModes', { 'a', mode, 'b', length = 3 })",
                "local _, rates = call('getSupportedRefreshRates', { 60, 90, 120, length = 3 })",
                "local _, alternative = call('getAlternativeRefreshRates', { 60, 90, length = 2 })",
                "local single = call('getSupportedModes', { mode, length = 1 })",
                "return restricted, metrics.xdpi, metrics.ydpi, restricted2, restricted3, metrics2.xdpi,",
                "  modes.length, modes[1] == mode, rates.length, rates[1], alternative.length, single,",
                "  installed, system.xdpi, system.ydpi, app.xdpi, app.ydpi, app.densityDpi, again, again2"));
        assertTrue(result.arg(1).toboolean());
        assertEquals(440, result.arg(2).todouble(), 0);
        assertEquals(440, result.arg(3).todouble(), 0);
        assertFalse("already generic", result.arg(4).toboolean());
        assertTrue("resources", result.arg(5).toboolean());
        assertEquals(440, result.arg(6).todouble(), 0);
        assertEquals(1, result.arg(7).toint());
        assertTrue(result.arg(8).toboolean());
        assertEquals(1, result.arg(9).toint());
        assertEquals(120, result.arg(10).toint());
        assertEquals(0, result.arg(11).toint());
        assertFalse(result.arg(12).toboolean());
        assertTrue("installed", result.arg(13).toboolean());
        for (int i = 14; i <= 17; i++)
            assertEquals(String.valueOf(i), 440, result.arg(i).todouble(), 0);
        assertEquals(420, result.arg(18).toint());
        assertFalse("once", result.arg(19).toboolean());
        assertFalse("once", result.arg(20).toboolean());
    }

    // The text of a mode of the display has no other refresh rates, like getAlternativeRefreshRates
    @Test
    public void displayModeTextHasNoAlternatives() throws Exception {
        Globals globals = load("display_panel");
        Varargs result = Scripts.run(globals,
                "local mode = paramof('{id=1, width=1080, height=2400, fps=120.0, alternativeRefreshRates=[60.0, 90.0], " +
                        "supportedHdrTypes=[2, 3]}')\n" +
                        "local none = paramof('{id=1, width=1080, height=2400, fps=60.0, alternativeRefreshRates=[]}')\n" +
                        "return after(hookof('Display.Mode', 'toString'), mode), mode.result, after(hookof('Display.Mode', 'toString'), none)");
        assertTrue(result.arg(1).toboolean());
        assertEquals("{id=1, width=1080, height=2400, fps=120.0, alternativeRefreshRates=[], supportedHdrTypes=[2, 3]}",
                result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
    }

    // A prompt for biometrics gets the error of a device without them on its executor, without the dialog; a prompt
    // which allows the screen lock too (by its authenticators or without a negative button), and a call without its
    // arguments, go to Android
    @Test
    public void biometricPromptHasNoHardware() throws Exception {
        Globals globals = load("biometric_prompt");
        Varargs result = Scripts.run(globals,
                "luajava = { createProxy = function(name, object) return object end,\n" +
                        "  bindClass = function(name) return { getSystem = function(self) return {\n" +
                        "    getIdentifier = function(self, n, t, p) return 7 end,\n" +
                        "    getString = function(self, id) return 'Biometric hardware unavailable' end } end } end }\n" +
                        "local errors = {}\n" +
                        "local executor = { execute = function(self, runnable) runnable.run() end }\n" +
                        "local callback = { onAuthenticationError = function(self, code, text)\n" +
                        "  errors[#errors + 1] = code .. ' ' .. text end }\n" +
                        "local function prompt(authenticators, negative) return {\n" +
                        "  getAllowedAuthenticators = function(self) return authenticators end,\n" +
                        "  getNegativeButtonText = function(self) return negative end } end\n" +
                        "local function call(authenticators, args, negative) local param = paramof('real', args)\n" +
                        "  param.getThis = function(self) return prompt(authenticators, negative) end\n" +
                        "  local hook = { getParameterTypes = function(self) return { length = 3 } end }\n" +
                        "  return before(hook, param), param.result == nil end\n" +
                        "local cancel = 'Cancel'\n" +
                        "local weak, set = call(255, { [0] = 'cancel', [1] = executor, [2] = callback }, cancel)\n" +
                        "local credential = call(255 + 32768, { [0] = 'cancel', [1] = executor, [2] = callback }, nil)\n" +
                        "local deprecated = call(0, { [0] = 'cancel', [1] = executor, [2] = callback }, '')\n" +
                        "local missing = call(255, { [0] = 'cancel', [1] = nil, [2] = callback }, cancel)\n" +
                        "return weak, set, errors[1], #errors, credential, missing, deprecated");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals("12 Biometric hardware unavailable", result.arg(3).tojstring());
        assertEquals(1, result.arg(4).toint());
        assertFalse(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
    }

    // The version of the DRM plugin with the fake build of the firmware, like Build.ID; other properties stay
    @Test
    public void drmVersionWithFakeBuild() throws Exception {
        Globals globals = load("mediadrm_version");
        Varargs result = Scripts.run(globals, "return run(hookof('MediaDrm.getPropertyString/version', " +
                "'getPropertyString'), paramof('19.0.1@AV1A.240428.001', { [0] = 'version' }))");
        assertTrue(result.arg1().toboolean());
        assertEquals("19.0.1@unknown", result.arg(2).tojstring());
        assertFalse(Scripts.run(globals, "return run(hookof('MediaDrm.getPropertyString/version', " +
                "'getPropertyString'), paramof('19.0.1@unknown', { [0] = 'version' }))").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return run(hookof('MediaDrm.getPropertyString/version', " +
                "'getPropertyString'), paramof('16.1.0', { [0] = 'version' }))").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return run(hookof('MediaDrm.getPropertyString/version', " +
                "'getPropertyString'), paramof('Google@x', { [0] = 'vendor' }))").arg1().toboolean());
        // The id of the system of Widevine stays: DRM apps like Netflix verify the device by it
        assertFalse(Scripts.run(globals, "return run(hookof('MediaDrm.getPropertyString/version', " +
                "'getPropertyString'), paramof('18615', { [0] = 'systemId' }))").arg1().toboolean());
    }

    // Values of MMS other than the generic ones of Android become those; generic and empty ones stay
    @Test
    public void mmsValuesAreGeneric() throws Exception {
        Globals globals = load("mms_useragent");
        String[][] cases = {{"getMmsUserAgent", "SAMSUNG-SM-G991B", "Android-Mms/2.0"},
                {"getMmsUAProfUrl", "http://wap.samsungmobile.com/uaprof/SM-G991B.xml",
                        "http://www.google.com/oha/rdf/ua-profile-kila.xml"}};
        for (String[] c : cases) {
            Varargs result = Scripts.run(globals, "return run(hookof('TelephonyManager." + c[0] + "', '" + c[0] + "'), " +
                    "paramof('" + c[1] + "', {}))");
            assertTrue(c[0], result.arg1().toboolean());
            assertEquals(c[0], c[2], result.arg(2).tojstring());
            assertFalse(c[0], Scripts.run(globals, "return run(hookof('TelephonyManager." + c[0] + "', '" + c[0] +
                    "'), paramof('" + c[2] + "', {}))").arg1().toboolean());
            assertFalse(c[0], Scripts.run(globals, "return run(hookof('TelephonyManager." + c[0] + "', '" + c[0] +
                    "'), paramof('', {}))").arg1().toboolean());
        }
    }

    // The carrier configuration of MMS has the same user agent and profile, by its keys userAgent and uaProfUrl;
    // the configuration is a copy for the app, other values stay
    @Test
    public void mmsCarrierConfigIsGeneric() throws Exception {
        Globals globals = load("mms_useragent");
        Varargs result = Scripts.run(globals, "local values = { userAgent = 'SAMSUNG-SM-G991B',\n" +
                "  uaProfUrl = 'http://wap.samsungmobile.com/uaprof/SM-G991B.xml', maxMessageSize = 1048576 }\n" +
                "local bundle = { getString = function(self, key) return values[key] end,\n" +
                "  putString = function(self, key, value) values[key] = value end }\n" +
                "local restricted = run(hookof('SmsManager.getCarrierConfigValues', 'getCarrierConfigValues'), paramof(bundle, {}))\n" +
                "return restricted, values.userAgent, values.uaProfUrl, values.maxMessageSize");
        assertTrue(result.arg(1).toboolean());
        assertEquals("Android-Mms/2.0", result.arg(2).tojstring());
        assertEquals("http://www.google.com/oha/rdf/ua-profile-kila.xml", result.arg(3).tojstring());
        assertEquals(1048576, result.arg(4).toint());
        // Generic and empty values stay
        assertFalse(Scripts.run(globals, "local values = { userAgent = 'Android-Mms/2.0', uaProfUrl = '' }\n" +
                "local bundle = { getString = function(self, key) return values[key] end,\n" +
                "  putString = function(self, key, value) values[key] = value end }\n" +
                "return run(hookof('SmsManager.getCarrierConfigValues', 'getCarrierConfigValues'), paramof(bundle, {}))")
                .arg1().toboolean());
    }

    // The advertising id service of Play services gets a binder in front: the id of zeros and limited ad tracking,
    // other calls and other services go to the service
    @Test
    public void advertisingIdServiceAnswersZeros() throws Exception {
        Globals globals = Scripts.load("adid_service", String.join("\n",
                "proxy = nil",
                "luajava = { createProxy = function(interface, methods) proxy = methods return methods end }",
                "calls = {}",
                "service = { transact = function(self, code, data, reply, flags) calls[#calls + 1] = code return true end,",
                "  pingBinder = function(self) return true end }",
                "function componentof(class) return { getClassName = function(self) return class end } end",
                "function replyof()",
                "  return { written = {}, writeNoException = function(self) self.written[#self.written + 1] = 'ok' end,",
                "    writeString = function(self, value) self.written[#self.written + 1] = value end,",
                "    writeInt = function(self, value) self.written[#self.written + 1] = tostring(value) end,",
                "    setDataPosition = function(self, position) self.written[#self.written + 1] = '@' .. position end }",
                "end",
                "function connect(class)",
                "  proxy = nil",
                "  local param = { args = { [0] = componentof(class), [1] = service } }",
                "  param.getArgument = function(self, index) return self.args[index] end",
                "  param.setArgument = function(self, index, value) self.args[index] = value end",
                "  return before({}, param), param.args[1] == service",
                "end"));
        Varargs result = Scripts.run(globals,
                "local restricted, same = connect('com.google.android.gms.ads.identifier.service.AdvertisingIdService')\n" +
                        "local id, limited = replyof(), replyof()\n" +
                        "proxy.transact(1, nil, id, 0)\n" +
                        "proxy.transact(2, nil, limited, 0)\n" +
                        "proxy.transact(3, nil, replyof(), 0)\n" +
                        "return restricted, same, table.concat(id.written, ' '), table.concat(limited.written, ' '),\n" +
                        "  table.concat(calls, ','), tostring(proxy.queryLocalInterface('x')), tostring(proxy.pingBinder())");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertEquals("ok 00000000-0000-0000-0000-000000000000 @0", result.arg(3).tojstring());
        assertEquals("ok 1 @0", result.arg(4).tojstring());
        assertEquals("3", result.arg(5).tojstring());
        assertEquals("nil", result.arg(6).tojstring());
        assertEquals("true", result.arg(7).tojstring());

        // Other services keep their binder
        Varargs other = Scripts.run(globals, "return connect('com.example.Service')");
        assertFalse(other.arg(1).toboolean());
        assertTrue(other.arg(2).toboolean());
    }

    // The operator of the test network, like TelephonyManager;
    // properties which are not set stay empty
    @Test
    public void propertiesOfTheOperatorAreFaked() throws Exception {
        Globals globals = load("systemproperties_get");
        String[][] cases = {{"gsm.operator.numeric", "26201", "00101"}, {"gsm.sim.operator.numeric", "26201", "00101"},
                {"gsm.operator.alpha", "Telekom.de", "unknown"}, {"gsm.sim.operator.alpha", "Telekom.de", "unknown"},
                {"gsm.operator.iso-country", "de", ""}, {"gsm.sim.operator.iso-country", "de", ""}};
        for (String[] c : cases) {
            Varargs result = Scripts.run(globals, "return run(hookof('SystemProperties.get.default/operator', 'get'), " +
                    "paramof('" + c[1] + "', { [0] = '" + c[0] + "', [1] = '' }))");
            assertTrue(c[0], result.arg1().toboolean());
            assertEquals(c[0], c[2], result.arg(2).tojstring());
        }
        // The list of a phone with two SIM slots keeps its form, empty slots stay
        for (String[] c : new String[][]{{",310260", ",00101"}, {"26201,310260", "00101,00101"}, {"26201,", "00101,"}})
            assertEquals(c[0], c[1], Scripts.run(globals, "return run(hookof('SystemProperties.get/operator', 'get'), " +
                    "paramof('" + c[0] + "', { [0] = 'gsm.sim.operator.numeric' }))").arg(2).tojstring());
        assertFalse(Scripts.run(globals, "return run(hookof('SystemProperties.get/operator', 'get'), " +
                "paramof('', { [0] = 'gsm.operator.numeric' }))").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return run(hookof('SystemProperties.get/operator', 'get'), " +
                "paramof('26201', { [0] = 'gsm.operator.foo' }))").arg1().toboolean());
    }

    // The variants with names run for their properties only, the properties of the device by their prefix
    @Test
    public void filtersOfVariants() throws Exception {
        Globals globals = load("systemproperties_get");
        Varargs result = Scripts.run(globals, "local function keys(name)\n" +
                "  local result = {}\n" +
                "  for _, key in ipairs(filter(hookof(name, 'get'))) do result[key] = true end\n" +
                "  return result\n" +
                "end\n" +
                "local operator = keys('SystemProperties.get/operator')\n" +
                "local serial = keys('SystemProperties.get/serial')\n" +
                "assert(serial['*iccid*'] and serial['*Iccid*'] and serial['*ICCID*'] and serial['persist.radio.serialno'])\n" +
                "return keys('SystemProperties.get.default/serial')['ril.serialnumber'],\n" +
                "  operator['gsm.sim.operator.iso-country'], #filter(hookof('SystemProperties.get/operator', 'get')),\n" +
                "  keys('SystemProperties.get/timezone')['persist.sys.timezone'],\n" +
                "  keys('SystemProperties.get/device')['ro.*'],\n" +
                "  keys('SystemProperties.getInt/device')['gsm.version.baseband']");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(6, result.arg(3).toint());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
    }

    // getInt and getLong do not use get: the fake value as a number, else the default
    private Varargs typed(Globals globals, String method, String key, String result, String def) {
        return Scripts.run(globals, "return run(hookof('SystemProperties." + method + "/device', '" + method + "'), " +
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

        assertEquals(26, Scripts.run(globals, "return number('0x1a', 'getInt')").arg1().toint());
        assertTrue(Scripts.run(globals, "return number('12 ', 'getInt')").arg1().isnil());
        assertTrue(Scripts.run(globals, "return number('3000000000', 'getInt')").arg1().isnil());
        assertEquals(3000000000L, Scripts.run(globals, "return number('3000000000', 'getLong')").arg1().tolong());
    }

    @Test
    public void iccidOfNumbersIsEmpty() throws Exception {
        Globals globals = load("systemproperties_get");
        Varargs result = Scripts.run(globals, "return run(hookof('SystemProperties.getLong/serial', 'getLong'), " +
                "paramof('8988211000000000000', { [0] = 'ril.iccid.sim1', [1] = '0' }))");
        assertTrue(result.arg1().toboolean());
        assertEquals(0, result.arg(2).toint());
        result = Scripts.run(globals, "return run(hookof('SystemProperties.getInt/serial', 'getInt'), " +
                "paramof('12345', { [0] = 'ril.iccid.sim1', [1] = '-1' }))");
        assertTrue(result.arg1().toboolean());
        assertEquals(-1, result.arg(2).toint());
    }

    @Test
    public void securityPatchIsAQuarter() throws Exception {
        Globals globals = load("systemproperties_get");
        String hook = "hookof('Build.VERSION.SECURITY_PATCH', '#SECURITY_PATCH')";
        Varargs result = Scripts.run(globals, "return run(" + hook + ", paramof('2025-09-05', {}))");
        assertTrue(result.arg1().toboolean());
        assertEquals("2025-07-01", result.arg(2).tojstring());
        assertFalse(Scripts.run(globals, "return run(" + hook + ", paramof('2025-07-01', {}))").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return run(" + hook + ", paramof('', {}))").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return run(" + hook + ", paramof(nil, {}))").arg1().toboolean());
    }

    // The script reads the method of the hook only
    private Varargs operator(Globals globals, String method, String result) {
        return Scripts.run(globals, "return run(hookof(nil, '" + method + "'), paramof(" + result + ", {}))");
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
        // The carrier of 00101 in the carrier database of Android, with its name
        assertEquals(1911, operator(globals, "getSimCarrierId", "1").arg(2).toint());
        assertEquals(1911, operator(globals, "getCarrierIdFromSimMccMnc", "1839").arg(2).toint());
        assertEquals("Test Network, Used by GSM test equipment",
                operator(globals, "getSimCarrierIdName", "'T-Mobile'").arg(2).tojstring());
        assertEquals("", operator(globals, "getNetworkCountryIso", "'de'").arg(2).tojstring());
        // The operator of the network of a cell; unknown codes of a cell (Integer.MAX_VALUE) stay
        assertEquals("00101", operator(globals, "getMobileNetworkOperator", "'310260'").arg(2).tojstring());
        assertFalse(operator(globals, "getMcc", "2147483647").arg1().toboolean());
        // The country and MNC of an emergency number of the carrier, the MNC as text
        Varargs emergency = Scripts.run(globals,
                "local hook = hookof(nil, 'getMnc')\n" +
                        "hook.getClassName = function(self) return 'android.telephony.emergency.EmergencyNumber' end\n" +
                        "local mnc = paramof('260', {})\n" +
                        "local none = paramof('', {})\n" +
                        "return run(hook, mnc), mnc.result, run(hook, none)");
        assertTrue(emergency.arg(1).toboolean());
        assertEquals("01", emergency.arg(2).tojstring());
        assertFalse(emergency.arg(3).toboolean());

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
        return Scripts.run(globals, "local param = paramof(" + result + ", {})\n" +
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
            // The SMS app of the role, which Telephony.Sms.getDefaultSmsPackage answers without the hooks
            "    if name == 'android.provider.Telephony$Sms' then",
            "      return { getDefaultSmsPackage = function(self, context)",
            "        assert(inservice, 'with the hooks')",
            "        smscontexts[#smscontexts + 1] = context",
            "        return roleholder",
            "      end }",
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
            "  param.unhooked = function(self, fn)",
            "    inservice = true",
            "    local value = fn()",
            "    inservice = false",
            "    return value",
            "  end",
            "  return param",
            "end",
            "smscontexts = {}",
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
        assertEquals(Scripts.function("default_app_filter", "systemdefault"),
                Scripts.function("settingssecure_getstring", "systemdefault"));
    }

    // The text of a function of a script, up to its end; a checkout can have CRLF line ends
    private Varargs defaultApp(Globals globals, String method, String result) {
        return Scripts.run(globals, "return run(hookof('" + method + "', '" + method + "'), appparamof(" + result + "))");
    }

    // Apps compare the default SMS and phone app without checking for null: a hidden one becomes the one of the system
    @Test
    public void defaultAppsAreTheOnesOfTheSystem() throws Exception {
        Globals globals = load("default_app_filter");
        Scripts.run(globals, APPS);
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
        Scripts.run(globals, "apps['com.google.android.apps.messaging'].enabled = false");
        result = defaultApp(globals, "getDefaultSmsPackage", "'com.third.visible'");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());

        // Without the system dialer an app which can be the default, but not the app itself
        Scripts.run(globals, "dialer = nil");
        assertEquals("com.android.dialer", defaultApp(globals, "getDefaultDialerPackage", "'com.third.hidden'").arg(2).tojstring());
        Scripts.run(globals, "dialers = arrayof(dialers[1])");
        result = defaultApp(globals, "getDefaultDialerPackage", "'com.third.hidden'");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
    }

    // Android 10 and later answer null for a hidden SMS app of the role, since the hooked list of SMS receivers
    // does not have it: asked again without the hooks, it becomes the one of the system too
    @Test
    public void hiddenSmsAppOfTheRoleIsTheOneOfTheSystem() throws Exception {
        Globals globals = load("default_app_filter");
        Scripts.run(globals, APPS);
        String call = "local param = appparamof(nil)\n" +
                "param.getArgument = function(self, index) error('the context of the app is not used') end\n" +
                "return run(hookof('Telephony.Sms.getDefaultSmsPackage', 'getDefaultSmsPackage'), param)";
        Scripts.run(globals, "roleholder = 'com.third.hidden'");
        Varargs result = Scripts.run(globals, call);
        assertTrue(result.arg1().toboolean());
        assertEquals("com.google.android.apps.messaging", result.arg(2).tojstring());
        // With the context of the framework, not the one the app passed
        assertTrue(Scripts.run(globals, "return smscontexts[1] == appparamof(nil):getApplicationContext()").arg1().toboolean());

        // No SMS app, the app itself and a visible one stay null, like the framework answered
        for (String holder : new String[]{"nil", "'com.example.app'", "'com.google.android.apps.messaging'"}) {
            Scripts.run(globals, "roleholder = " + holder);
            result = Scripts.run(globals, call);
            assertFalse(holder, result.arg1().toboolean());
            assertTrue(holder, result.arg(2).isnil());
        }

        // A call which threw keeps its exception, and a null phone app is not asked again
        Scripts.run(globals, "roleholder = 'com.third.hidden'; smscontexts = {}");
        result = Scripts.run(globals, "local param = appparamof(nil)\n" +
                "param.getException = function(self) return 'SecurityException' end\n" +
                "return run(hookof('Telephony.Sms.getDefaultSmsPackage', 'getDefaultSmsPackage'), param)");
        assertFalse(result.arg1().toboolean());
        assertFalse(defaultApp(globals, "getDefaultDialerPackage", "nil").arg1().toboolean());
        assertEquals(0, Scripts.run(globals, "return #smscontexts").arg1().toint());
    }

    // The browsers of the device as the package manager gives them, the configured default, the version and the
    // app, which is com.example.app and may be a browser itself
    private static final String BROWSERS = String.join("\n",
            Scripts.ARRAYOF,
            "function browserof(name, flags, all)",
            "  return { handleAllWebDataURI = (all ~= false), activityInfo = { applicationInfo =",
            "    { packageName = name, flags = flags, uid = (name == 'com.example.app' and 10200 or 10100) } } }",
            "end",
            "browsers = {}",
            "configured = ''",
            "version = 34",
            "function sdkversion() return version end",
            "local pm = {",
            "  queryIntentActivities = function(self, intent, flags)",
            "    assert(intent.action == 'android.intent.action.VIEW' and intent.data == 'http:' and",
            "      intent.category == 'android.intent.category.BROWSABLE', intent.action)",
            "    assert(flags == (version >= 30 and 0xF0000 or version == 29 and 0xE0000 or 0x20000), flags)",
            "    return { toArray = function(self) return arrayof(table.unpack(browsers)) end }",
            "  end,",
            // The packages the package visibility rules hide from the app, which it cannot look up
            "  getPackageInfo = function(self, name, flags)",
            "    assert(flags == 0x2000, flags)",
            "    if lookupfails[name] then error('NameNotFoundException: ' .. name) end",
            "    return { packageName = name }",
            "  end }",
            "lookupfails = {}",
            "local resources = {",
            "  getIdentifier = function(self, name, type, package)",
            "    return ((name == (version >= 29 and 'config_defaultBrowser' or 'default_browser')) and 7 or 0)",
            "  end,",
            "  getString = function(self, id) return configured end }",
            "luajava = {",
            "  newInstance = function(name, action, data)",
            "    assert(name == 'android.content.Intent', name)",
            "    return { action = action, data = data,",
            "      addCategory = function(self, category) self.category = category; return self end }",
            "  end,",
            "  bindClass = function(name)",
            "    if name == 'android.net.Uri' then return { parse = function(self, text) return text end } end",
            "    assert(name == 'android.content.res.Resources', name)",
            "    return { getSystem = function(self) return resources end }",
            "  end }",
            // Visible are the app itself and the apps of the system, FLAG_SYSTEM
            "function visible(name)",
            "  if name == 'com.example.app' then return true end",
            "  for _, browser in ipairs(browsers) do",
            "    local ai = browser.activityInfo.applicationInfo",
            "    if ai.packageName == name then return ai.flags % 2 == 1 end",
            "  end",
            "  return name == 'com.android.htmlviewer'",
            "end",
            "function browserparamof(result)",
            "  local param = paramof(result, {})",
            "  param.getApplicationContext = function(self) return { getPackageManager = function(self) return pm end } end",
            "  param.isVisible = function(self, ai) return visible(ai.packageName) end",
            "  param.isPackageVisible = function(self, name) return visible(name) end",
            "  param.isInstalledVisible = function(self, name) return visible(name) end",
            "  return param",
            "end",
            "function browser(result) return run(nil, browserparamof(result)) end");

    // A hidden default browser is the one of a device on which the user did not choose one, by the rules of the
    // version: the configured one, else the only browser, of the system on Android 8.1 and 9, else from Android 12
    // the only browser of the system
    @Test
    public void defaultBrowserIsTheOneOfTheSystem() throws Exception {
        Globals globals = Scripts.load("default_browser", STUBS + "\n" + BROWSERS);
        Scripts.run(globals, "browsers = { browserof('org.mozilla.firefox', 0), browserof('com.android.chrome', 1),\n" +
                "browserof('com.example.links', 1, false) }");
        Varargs result = Scripts.run(globals, "return browser('org.mozilla.firefox')");
        assertTrue(result.arg1().toboolean());
        assertEquals("com.android.chrome", result.arg(2).tojstring());

        // Browsers of the system, the app itself and none stay
        assertFalse(Scripts.run(globals, "return browser('com.android.chrome')").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return browser('com.example.app')").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return browser(nil)").arg1().toboolean());

        // Two browsers of the system: the configured one, also with the digest of its certificate, else none
        Scripts.run(globals, "browsers[#browsers + 1] = browserof('com.sec.android.app.sbrowser', 1)");
        assertTrue(Scripts.run(globals, "return browser('org.mozilla.firefox')").arg(2).isnil());
        Scripts.run(globals, "configured = 'com.sec.android.app.sbrowser:0A1B'");
        assertEquals("com.sec.android.app.sbrowser", Scripts.run(globals, "return browser('org.mozilla.firefox')").arg(2).tojstring());
        // From Android 10 the configured one is a browser of the system
        Scripts.run(globals, "configured = 'org.mozilla.firefox'");
        assertTrue(Scripts.run(globals, "return browser('org.mozilla.firefox')").arg(2).isnil());

        // The app itself is a browser too: from Android 12 the only one of the system, before none
        Scripts.run(globals, "configured = ''; browsers = { browserof('org.mozilla.firefox', 0), browserof('com.android.chrome', 1),\n" +
                "browserof('com.example.app', 0) }");
        assertEquals("com.android.chrome", Scripts.run(globals, "return browser('org.mozilla.firefox')").arg(2).tojstring());
        Scripts.run(globals, "version = 30");
        assertTrue(Scripts.run(globals, "return browser('org.mozilla.firefox')").arg(2).isnil());

        // Android 8.1 and 9: the only browser of the system, the configured one when it is installed
        Scripts.run(globals, "version = 28");
        assertEquals("com.android.chrome", Scripts.run(globals, "return browser('org.mozilla.firefox')").arg(2).tojstring());
        Scripts.run(globals, "configured = 'com.android.htmlviewer'");
        assertEquals("com.android.htmlviewer", Scripts.run(globals, "return browser('org.mozilla.firefox')").arg(2).tojstring());
    }

    // From Android 11 the app may be unable to look up the configured browser of the system, which Android tells all
    // the same: it stays, and is the default of a device without a hidden browser too. One the app can look up stays
    // only when it is of the system, and on Android 10 one the app cannot look up is not installed.
    @Test
    public void configuredBrowserTheAppCannotLookUpStays() throws Exception {
        Globals globals = Scripts.load("default_browser", STUBS + "\n" + BROWSERS);
        Scripts.run(globals, "browsers = {}; configured = 'com.android.chrome'\n" +
                "lookupfails = { ['com.android.chrome'] = true, ['org.mozilla.firefox'] = true }");
        assertFalse(Scripts.run(globals, "return browser('com.android.chrome')").arg1().toboolean());
        Varargs result = Scripts.run(globals, "return browser('org.mozilla.firefox')");
        assertTrue(result.arg1().toboolean());
        assertEquals("com.android.chrome", result.arg(2).tojstring());

        Scripts.run(globals, "lookupfails = {}");
        result = Scripts.run(globals, "return browser('com.android.chrome')");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());

        Scripts.run(globals, "version = 29; lookupfails = { ['com.android.chrome'] = true }");
        result = Scripts.run(globals, "return browser('com.android.chrome')");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());

        // With the digest of a certificate the configured one may be an app the user installed or not be installed:
        // it is hidden and is not the default of another hidden browser
        Scripts.run(globals, "version = 34; configured = 'com.example.browser:0A1B'\n" +
                "lookupfails = { ['com.example.browser'] = true, ['org.mozilla.firefox'] = true }");
        result = Scripts.run(globals, "return browser('com.example.browser')");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        result = Scripts.run(globals, "return browser('org.mozilla.firefox')");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
    }

    // Both lists of shared libraries hide the vendor libraries of the system and keep the libraries of apps
    @Test
    public void sharedLibrariesAreFilteredAlike() throws Exception {
        Globals globals = load("features");
        Scripts.run(globals, APPS);
        Scripts.run(globals, "libraries = listof(libraryof('android.test.base', 0), libraryof('com.qualcomm.qti.audio', 0), " +
                "libraryof('com.samsung.android.sdk', 1), libraryof('com.google.android.trichromelibrary', 2), " +
                "libraryof('com.example.sdk', 3))");
        Varargs result = Scripts.run(globals, "local param = appparamof(libraries)\n" +
                "local restricted = after(hookof('PackageManager.getSharedLibraries', 'getSharedLibraries'), param)\n" +
                "return restricted, param.result:toString()");
        assertTrue(result.arg1().toboolean());
        assertEquals("[android.test.base, com.google.android.trichromelibrary, com.example.sdk]", result.arg(2).tojstring());

        result = Scripts.run(globals, "local param = appparamof(arrayof('android.test.base', 'com.qualcomm.qti.audio', " +
                "'com.samsung.android.sdk', 'com.google.android.trichromelibrary', 'com.example.sdk', 'com.google.android.maps'))\n" +
                "local restricted = after(hookof('PackageManager.getSystemSharedLibraryNames', 'getSystemSharedLibraryNames'), param)\n" +
                "return restricted, names(param.result)");
        assertTrue(result.arg1().toboolean());
        assertEquals("[android.test.base, com.google.android.trichromelibrary, com.example.sdk, com.google.android.maps]",
                result.arg(2).tojstring());

        // Without the libraries of apps, like when the lookup fails
        Scripts.run(globals, "libraries = nil");
        result = Scripts.run(globals, "local param = appparamof(arrayof('android.test.base', 'com.qualcomm.qti.audio'))\n" +
                "local restricted = after(hookof('PackageManager.getSystemSharedLibraryNames', 'getSystemSharedLibraryNames'), param)\n" +
                "return restricted, names(param.result)");
        assertTrue(result.arg1().toboolean());
        assertEquals("[android.test.base]", result.arg(2).tojstring());
    }

    // Get.Applications leaves the hidden apps out of the dependents of the shared libraries and drops the static and
    // SDK libraries hidden apps declare, but not one a visible app uses; the builtin and dynamic ones stay whoever
    // declares them, and a list of dependents left empty becomes none
    @Test
    public void sharedLibrariesHideHiddenApps() throws Exception {
        Globals globals = load("features");
        Scripts.run(globals, APPS);
        Scripts.run(globals, String.join("\n",
                // A list of VersionedPackage, which the script refills
                "function packagesof(...)",
                "  local list = listof()",
                "  for _, name in ipairs({ ... }) do",
                "    list:add({ getPackageName = function(self) return name end, toString = function(self) return name end })",
                "  end",
                "  list.isEmpty = function(self) return #self.items == 0 end",
                "  list.clear = function(self) self.items = {} end",
                "  list.addAll = function(self, other) for _, item in ipairs(other.items) do self:add(item) end end",
                "  return list",
                "end",
                // Like SharedLibraryInfo: no dependents is an empty list, only Android 15 and later have optional ones
                "function sharedof(name, type, declaring, dependents, optional)",
                "  local library = libraryof(name, type)",
                "  library.mDependentPackages = dependents",
                "  library.getDependentPackages = function(self) return self.mDependentPackages or packagesof() end",
                "  library.getDeclaringPackage = function(self) return { getPackageName = function(self) return declaring end } end",
                "  if optional ~= nil then library.getOptionalDependentPackages = function(self) return optional end end",
                "  return library",
                "end",
                "local visible = { ['android'] = true, ['com.example.app'] = true, ['com.android.dialer'] = true }",
                "function sharedparamof(result)",
                "  local param = appparamof(result)",
                "  param.isPackageVisible = function(self, name) return visible[name] == true end",
                "  return param",
                "end",
                "function sharedrun(result, method)",
                "  local param = sharedparamof(result)",
                "  method = method or 'getSharedLibraries'",
                "  local restricted, old, new = after(hookof('PackageManager.' .. method, method,",
                "    'android.content.pm.PackageManager', 'Get.Applications'), param)",
                "  local shown = {}",
                "  for index, library in ipairs(param.result.items) do",
                "    shown[index] = library:getName() .. library:getDependentPackages():toString()",
                "  end",
                "  return restricted, table.concat(shown, ' '), old, new, param.result",
                "end"));

        Scripts.run(globals, "libraries = listof(" +
                "sharedof('org.apache.http.legacy', 0, 'android', " +
                "packagesof('com.example.app', 'com.android.dialer', 'com.example.bank')), " +
                "sharedof('com.google.android.maps', 0, 'android', packagesof('com.example.bank')), " +
                "sharedof('com.example.bank.library', 2, 'com.example.bank', packagesof('com.example.bank')), " +
                "sharedof('com.example.trichrome', 2, 'com.example.trichrome', packagesof('com.example.app', 'com.example.bank')), " +
                "sharedof('com.example.sdk', 3, 'com.android.dialer', packagesof('com.example.app', 'com.example.bank'), " +
                "packagesof('com.example.bank')), " +
                "sharedof('android.test.base', 0, 'android', nil), " +
                // A system app which the visibility rules of Android hide declares it, a visible system app uses it
                "sharedof('android.ext.shared', 1, 'com.example.extshared', packagesof('com.example.bank')), " +
                "sharedof('com.example.chromelibrary', 2, 'com.example.chromelibrary', " +
                "packagesof('com.android.dialer', 'com.example.bank')))");
        // The hidden method for a user, which the public ones call
        Varargs result = Scripts.run(globals, "first = { sharedrun(libraries, 'getSharedLibrariesAsUser') }\n" +
                "return table.unpack(first)");
        assertTrue(result.arg1().toboolean());
        assertEquals("org.apache.http.legacy[com.example.app, com.android.dialer] com.google.android.maps[] " +
                "com.example.trichrome[com.example.app] com.example.sdk[com.example.app] android.test.base[] " +
                "android.ext.shared[] com.example.chromelibrary[com.android.dialer]", result.arg(2).tojstring());
        assertEquals("20", result.arg(3).tojstring());
        assertEquals("12", result.arg(4).tojstring());
        // The public method, which called it, finds nothing more to hide
        assertFalse(Scripts.run(globals, "return sharedrun(first[5])").arg1().toboolean());
        // The emptied list is none again, the optional dependents an empty list, like Android sends them
        assertTrue(Scripts.run(globals, "return libraries.items[2].mDependentPackages == nil").arg1().toboolean());
        assertEquals("[]", Scripts.run(globals, "return libraries.items[5]:getOptionalDependentPackages():toString()")
                .arg1().tojstring());

        // Nothing hidden: the list stays the one of Android
        Scripts.run(globals, "libraries = listof(sharedof('org.apache.http.legacy', 0, 'android', " +
                "packagesof('com.example.app', 'com.android.dialer')), sharedof('com.example.sdk', 3, 'com.android.dialer', nil))");
        result = Scripts.run(globals, "local restricted, shown, old, new, list = sharedrun(libraries)\n" +
                "return restricted, shown, list == libraries");
        assertFalse(result.arg1().toboolean());
        assertEquals("org.apache.http.legacy[com.example.app, com.android.dialer] com.example.sdk[]", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
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
            // The classes of the app, also named like the framework, are not of the boot class path
            "function bootclass(name) return not string.find(name, 'example', 1, true) end",
            "function names(result)",
            "  local list = {}",
            "  for index = 1, result['length'] do list[index] = tostring(result[index].name) end",
            "  return table.concat(list, ',')",
            "end");

    // All features are hidden but the kept ones; the framework gets its features when it asks itself
    @Test
    public void featuresAreHiddenButTheKeptOnes() throws Exception {
        Globals globals = load("features");
        Scripts.run(globals, FEATURES);
        String has = "return run(hookof('PackageManager.hasSystemFeature', 'hasSystemFeature'), featureparamof(";
        String keep = "'android.hardware.nfc, android.hardware.camera*'";

        Varargs result = Scripts.run(globals, has + "true, 'android.hardware.wifi', nil))");
        assertTrue(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(Scripts.run(globals, has + "true, 'com.google.android.feature.PIXEL_EXPERIENCE', " + keep + "))").arg1().toboolean());
        // Kept by name or by a name ending in *
        assertFalse(Scripts.run(globals, has + "true, 'android.hardware.nfc', " + keep + "))").arg1().toboolean());
        assertTrue(Scripts.run(globals, has + "true, 'android.hardware.nfc.hce', " + keep + "))").arg1().toboolean());
        assertFalse(Scripts.run(globals, has + "true, 'android.hardware.camera.front', " + keep + "))").arg1().toboolean());
        // A feature the device does not have
        assertFalse(Scripts.run(globals, has + "false, 'android.hardware.wifi', nil))").arg1().toboolean());

        // The framework checks features itself, like WebView, which does not start without its feature, and
        // the Wi-Fi manager, which is null without its feature. The stacks are those of LSPosed, which replaces
        // the frame of the hooked method by one of a class of its own with a random name.
        String engine = "'eu.faircode.xlua.XCore$6.after', 'eu.faircode.xlua.ModernBackend.lambda$hook$0', " +
                "'m.proceed', 'android.telephony.PheendeeDaesteampDrenash.hasSystemFeature', ";
        assertTrue(Scripts.run(globals, has + "true, 'android.software.webview', nil))").arg1().toboolean());
        Scripts.run(globals, "stack = stackof(" + engine + "'android.webkit.WebViewFactory.getProvider', " +
                "'android.webkit.WebView.<init>', 'com.example.app.Main.onCreate')");
        assertFalse(Scripts.run(globals, has + "true, 'android.software.webview', nil))").arg1().toboolean());
        Scripts.run(globals, "stack = stackof(" + engine +
                "'android.net.wifi.WifiFrameworkInitializer.lambda$registerServiceWrappers$0', " +
                "'android.app.SystemServiceRegistry.getSystemService', 'com.example.app.Main.onCreate')");
        assertFalse(Scripts.run(globals, has + "true, 'android.hardware.wifi', nil))").arg1().toboolean());
        // The package manager calls the hooked method itself, hasSystemFeature without a version the one with one
        Scripts.run(globals, "stack = stackof(" + engine + "'android.app.ApplicationPackageManager.hasSystemFeature', " +
                "'com.android.internal.policy.PhoneWindow.<init>', 'android.app.Activity.attach')");
        assertFalse(Scripts.run(globals, has + "true, 'android.software.picture_in_picture', nil))").arg1().toboolean());
        // The app and its libraries, also androidx and the support library, get false
        for (String caller : new String[]{"com.example.app.Main.onCreate", "androidx.core.content.ContextCompat.check",
                "android.support.v4.content.ContextCompat.check"}) {
            Scripts.run(globals, "stack = stackof(" + engine + "'" + caller + "', 'android.app.Activity.performCreate')");
            assertTrue(caller, Scripts.run(globals, has + "true, 'android.hardware.wifi', nil))").arg1().toboolean());
            Scripts.run(globals, "stack = stackof(" + engine + "'android.app.ApplicationPackageManager.hasSystemFeature', " +
                    "'" + caller + "', 'android.app.Activity.performCreate')");
            assertTrue(caller, Scripts.run(globals, has + "true, 'android.hardware.wifi', nil))").arg1().toboolean());
            // Also by reflection, which is not the framework calling
            Scripts.run(globals, "stack = stackof(" + engine + "'java.lang.reflect.Method.invoke', '" + caller + "', " +
                    "'android.app.Activity.performCreate')");
            assertTrue(caller, Scripts.run(globals, has + "true, 'android.hardware.wifi', nil))").arg1().toboolean());
        }
        // Classes of the app named like the framework, and method handles, are not the framework
        for (String caller : new String[]{"android.example.Check.run", "com.android.internal.example.Check.run"}) {
            Scripts.run(globals, "stack = stackof(" + engine + "'" + caller + "', 'android.app.Activity.performCreate')");
            assertTrue(caller, Scripts.run(globals, has + "true, 'android.hardware.wifi', nil))").arg1().toboolean());
        }
        Scripts.run(globals, "stack = stackof(" + engine + "'java.lang.invoke.MethodHandle.invokeExact', " +
                "'com.example.app.Main.onCreate', 'android.app.Activity.performCreate')");
        assertTrue(Scripts.run(globals, has + "true, 'android.hardware.wifi', nil))").arg1().toboolean());
        Scripts.run(globals, "stack = stackof('com.example.app.Main.onCreate')");

        // The list has the same features, and the OpenGL ES version, which has no name
        String list = "local param = featureparamof(featuresof(%s), nil, " + keep + ")\n" +
                "local restricted = after(hookof('PackageManager.getSystemAvailableFeatures', 'getSystemAvailableFeatures'), param)\n" +
                "return restricted, names(param.result)";
        result = Scripts.run(globals, String.format(list, "featureof('android.hardware.wifi'), featureof('android.hardware.nfc'), " +
                "featureof('android.hardware.camera.flash'), featureof('android.software.webview'), " +
                "featureof('com.samsung.feature.samsung_experience_mobile'), featureof(nil)"));
        assertTrue(result.arg1().toboolean());
        assertEquals("android.hardware.nfc,android.hardware.camera.flash,nil", result.arg(2).tojstring());
        result = Scripts.run(globals, String.format(list, "featureof('android.hardware.nfc'), featureof(nil)"));
        assertFalse(result.arg1().toboolean());
        assertEquals("android.hardware.nfc,nil", result.arg(2).tojstring());
    }

    // Whether features.lua restricts hasSystemFeature(name) of a hook in the group, with the feature there
    private static boolean hasFeature(Globals globals, String group, String name) {
        return Scripts.run(globals, "return run(hookof('PackageManager.hasSystemFeature', 'hasSystemFeature', nil, " + group +
                "), featureparamof(true, '" + name + "', nil))").arg1().toboolean();
    }

    // The features of hardware are left to the group which hides the hardware, and the others stay hidden
    @Test
    public void featuresOfHardwareAreOwnedByTheirGroups() throws Exception {
        Globals globals = load("features");
        Scripts.run(globals, FEATURES);
        for (String name : new String[]{"android.hardware.camera.front", "android.hardware.microphone",
                "android.hardware.sensor.accelerometer", "android.hardware.fingerprint"})
            assertFalse(name, hasFeature(globals, "nil", name));
        assertTrue(hasFeature(globals, "nil", "android.hardware.wifi"));
        assertTrue(hasFeature(globals, "'Use.Camera'", "android.hardware.camera.front"));
        assertFalse(hasFeature(globals, "'Use.Camera'", "android.hardware.wifi"));
        assertFalse(hasFeature(globals, "'Use.Camera'", "android.hardware.microphone"));
        assertTrue(hasFeature(globals, "'Record.Audio'", "android.hardware.microphone"));
        assertFalse(hasFeature(globals, "'Record.Audio'", "android.hardware.camera"));
        assertTrue(hasFeature(globals, "'Get.Sensors'", "android.hardware.sensor.gyroscope"));
        assertFalse(hasFeature(globals, "'Get.Sensors'", "android.hardware.microphone"));

        // The list of Read.Features keeps the features of the groups; the list of Use.Camera hides the camera only
        String list = "local param = featureparamof(featuresof(featureof('android.hardware.wifi'), " +
                "featureof('android.hardware.camera.flash'), featureof('android.hardware.microphone'), " +
                "featureof('android.hardware.sensor.light'), featureof('android.hardware.fingerprint'), featureof(nil)), nil, nil)\n" +
                "local restricted = after(hookof('PackageManager.getSystemAvailableFeatures', 'getSystemAvailableFeatures', nil, %s), param)\n" +
                "return restricted, names(param.result)";
        Varargs result = Scripts.run(globals, String.format(list, "nil"));
        assertTrue(result.arg1().toboolean());
        assertEquals("android.hardware.camera.flash,android.hardware.microphone,android.hardware.sensor.light," +
                "android.hardware.fingerprint,nil", result.arg(2).tojstring());
        result = Scripts.run(globals, String.format(list, "'Use.Camera'"));
        assertTrue(result.arg1().toboolean());
        assertEquals("android.hardware.wifi,android.hardware.microphone,android.hardware.sensor.light," +
                "android.hardware.fingerprint,nil", result.arg(2).tojstring());

        // The hooks of a group filter hasSystemFeature for its own features only, and the list of features not at all
        result = Scripts.run(globals, "return filter(hookof('x', 'hasSystemFeature', nil, 'Use.Camera'))[1], " +
                "filter(hookof('x', 'hasSystemFeature', nil, nil)), " +
                "filter(hookof('x', 'getSystemAvailableFeatures', nil, 'Use.Camera'))");
        assertEquals("android.hardware.camera*", result.arg1().tojstring());
        assertTrue(result.arg(2).isnil());
        assertTrue(result.arg(3).isnil());
    }

    // The names biometrics hides are the ones of its group, which features.lua leaves to it
    @Test
    public void biometricFeaturesAreOwnedByTheirGroup() throws Exception {
        Varargs listed = Scripts.run(load("biometrics"), "local names = filter(hookof('x', 'hasSystemFeature'))\n" +
                "assert(#names == 3, 'names')\n" +
                "return table.concat(names, ',')");
        String[] names = listed.arg1().tojstring().split(",");
        assertEquals(3, names.length);
        Globals globals = load("features");
        Scripts.run(globals, FEATURES);
        for (String name : names)
            assertFalse(name, hasFeature(globals, "nil", name));
    }

    @Test
    public void batteryCapacityIsRounded() throws Exception {
        Globals globals = load("battery");
        for (String method : new String[]{"getIntProperty", "getLongProperty"}) {
            Varargs result = Scripts.run(globals, "return run(hookof('BatteryManager." + method + "', '" + method + "'), " +
                    "paramof(57, { [0] = 4 }))");
            assertTrue(method, result.arg1().toboolean());
            assertEquals(method, 60, result.arg(2).toint());
        }
        // At least 15 percent, where devices warn, also for an empty battery
        for (int level : new int[]{0, 3, 12})
            assertEquals(String.valueOf(level), 15, Scripts.run(globals, "return run(hookof('BatteryManager.getIntProperty', " +
                    "'getIntProperty'), paramof(" + level + ", { [0] = 4 }))").arg(2).toint());
        // Other properties as not supported: an int is 0 before Android 9 and for apps made for it, else its minimum,
        // with the version of Android of the test, so a device runs the same branches as the JVM
        Scripts.run(globals, "local real = luajava.bindClass\n" +
                "luajava.bindClass = function(name)\n" +
                "  if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end\n" +
                "  return real(name)\n" +
                "end");
        for (int[] version : new int[][]{{27, 34, 0}, {34, 27, 0}, {34, 34, Integer.MIN_VALUE}})
            assertEquals(version[0] + "/" + version[1], version[2], Scripts.run(globals,
                    "sdk = " + version[0] + " target = " + version[1] + "\n" +
                            "return run(hookof('BatteryManager.getIntProperty', 'getIntProperty'), " +
                            "paramof(-1234, { [0] = 2 }))").arg(2).toint());
        assertEquals(Long.MIN_VALUE, Scripts.run(globals, "return run(hookof('BatteryManager.getLongProperty', " +
                "'getLongProperty'), paramof(-1234, { [0] = 2 }))").arg(2).tolong());
    }

    // The design capacity of PowerProfile as on devices without it: 0, or the default the caller gives; other
    // values of the power profile stay
    @Test
    public void batteryDesignCapacityIsMissing() throws Exception {
        Globals globals = load("battery");
        String profile = "'com.android.internal.os.PowerProfile'";
        Varargs result = Scripts.run(globals, "return run(hookof('PowerProfile.getBatteryCapacity', 'getBatteryCapacity', " +
                profile + "), paramof(4500.0, {}))");
        assertTrue(result.arg1().toboolean());
        assertEquals(0.0, result.arg(2).todouble(), 0);
        result = Scripts.run(globals, "return run(hookof('PowerProfile.getAveragePower', 'getAveragePower', " +
                profile + "), paramof(4500.0, { [0] = 'battery.capacity' }))");
        assertTrue(result.arg1().toboolean());
        assertEquals(0.0, result.arg(2).todouble(), 0);
        result = Scripts.run(globals, "return run(hookof('PowerProfile.getAveragePowerOrDefault', " +
                "'getAveragePowerOrDefault', " + profile + "), paramof(4500.0, { [0] = 'battery.capacity', [1] = 1000.0 }))");
        assertTrue(result.arg1().toboolean());
        assertEquals(1000.0, result.arg(2).todouble(), 0);
        result = Scripts.run(globals, "return run(hookof('PowerProfile.getAveragePower', 'getAveragePower', " +
                profile + "), paramof(110.5, { [0] = 'cpu.active' }))");
        assertFalse(result.arg1().toboolean());
        assertEquals(110.5, result.arg(2).todouble(), 0);
        // A profile without the capacity stays
        assertFalse(Scripts.run(globals, "return run(hookof('PowerProfile.getBatteryCapacity', 'getBatteryCapacity', " +
                profile + "), paramof(0, {}))").arg1().toboolean());
    }

    @Test
    public void sizesAreRounded() throws Exception {
        Globals globals = load("resources");
        // 3919 MiB of memory is sold as 4 GiB, 118 GB of storage as 128 GB, free space has two digits
        assertEquals(4294967296L, Scripts.run(globals, "return memorysize(4109369344)").arg1().tolong());
        assertEquals(128000000000L, Scripts.run(globals, "return storagesize(118000000000)").arg1().tolong());
        assertEquals(7900, Scripts.run(globals, "return freesize(7917)").arg1().toint());
        assertEquals(99, Scripts.run(globals, "return freesize(99)").arg1().toint());

        Varargs result = Scripts.run(globals, "return run(hookof('File.getTotalSpace', 'getTotalSpace'), paramof(118000000000, {}))");
        assertTrue(result.arg1().toboolean());
        assertEquals(128000000000L, result.arg(2).tolong());
        // The free space an app can allocate is rounded like other free space
        result = Scripts.run(globals, "return run(hookof('StorageManager.getAllocatableBytes', 'getAllocatableBytes'), paramof(7917, {}))");
        assertTrue(result.arg1().toboolean());
        assertEquals(7900, result.arg(2).toint());
        // Small file systems keep their size
        assertFalse(Scripts.run(globals, "return run(hookof('File.getTotalSpace', 'getTotalSpace'), paramof(500000000, {}))")
                .arg1().toboolean());

        // statvfs: the blocks are rounded, and the inodes with them, of which ext4 has a fixed number per size,
        // so their total does not give the exact size back
        result = Scripts.run(globals, "luajava = { newInstance = function(name, ...) return { name = name, args = { ... } } end }\n" +
                "local real = { f_bsize = 4096, f_frsize = 4096, f_blocks = 28808593, f_bfree = 12000000, f_bavail = 11900000,\n" +
                "  f_files = 7208960, f_ffree = 6000000, f_favail = 6000000, f_fsid = 1, f_flag = 0, f_namemax = 255 }\n" +
                "local param = paramof(real, {})\n" +
                "local restricted = run(hookof('Os.statvfs', 'statvfs'), param)\n" +
                "local a = param.result.args\n" +
                "return restricted, a[3], a[6], a[7], a[8]");
        assertTrue(result.arg(1).toboolean());
        long blocks = result.arg(2).tolong();
        assertEquals(31250000L, blocks);
        assertEquals((long) Math.floor(7208960.0 * blocks / 28808593), result.arg(3).tolong());
        assertEquals((long) Math.floor(6000000.0 * blocks / 28808593), result.arg(4).tolong());
        assertEquals((long) Math.floor(6000000.0 * blocks / 28808593), result.arg(5).tolong());

        // The memory info of the app is changed in place
        // with the thresholds grown like the total; without a hidden threshold it stays absent
        result = Scripts.run(globals, "local info = { totalMem = 4109369344, availMem = 1234567890,\n" +
                "  threshold = 226492416, foregroundAppThreshold = 75497472 }\n" +
                "local restricted = after(hookof('ActivityManager.getMemoryInfo', 'getMemoryInfo'), paramof(nil, { [0] = info }))\n" +
                "return restricted, info.totalMem, info.availMem, info.threshold, info.foregroundAppThreshold,\n" +
                "  info.hiddenAppThreshold");
        assertTrue(result.arg1().toboolean());
        assertEquals(4294967296L, result.arg(2).tolong());
        assertEquals(1200000000L, result.arg(3).tolong());
        assertEquals((long) Math.floor(226492416 * (4294967296.0 / 4109369344L)), result.arg(4).tolong());
        assertEquals((long) Math.floor(75497472 * (4294967296.0 / 4109369344L)), result.arg(5).tolong());
        assertTrue(result.arg(6).isnil());
    }

    // Pages of memory tell the same sizes as getMemoryInfo; Os.sysconf and ForwardingOs.sysconf both run for one call,
    // which must not round the free pages twice; other names of sysconf stay
    @Test
    public void memoryPagesAreRounded() throws Exception {
        Globals globals = load("resources");
        Varargs result = Scripts.run(globals,
                "luajava.bindClass = function(name)\n" +
                        "  return { _SC_PHYS_PAGES = 85, _SC_AVPHYS_PAGES = 86, _SC_PAGESIZE = 30,\n" +
                        "    sysconf = function(self, name) assert(name == 30) return 4096 end }\n" +
                        "end\n" +
                        "local hook = hookof('Os.sysconf/resources', 'sysconf')\n" +
                        "local total = paramof(1003486, { [0] = 85 })\n" +
                        "local free = paramof(300000, { [0] = 86 })\n" +
                        "local size = paramof(4096, { [0] = 30 })\n" +
                        "local count = paramof(1234567, { [0] = 96 })\n" +
                        "local a, b, c = after(hook, total), after(hook, free), after(hook, size)\n" +
                        "local d = after(hook, count)\n" +
                        "local rounded = free.result\n" +
                        "return a, total.result, b, rounded, c, size.result, d, count.result,\n" +
                        "  after(hook, total), after(hook, free), free.result");
        assertTrue(result.arg(1).toboolean());
        // 4110278656 bytes become 4 GiB
        assertEquals(1048576L, result.arg(2).tolong());
        assertTrue(result.arg(3).toboolean());
        // 1228800000 bytes become 1200000000, up to a whole page
        assertEquals(292969L, result.arg(4).tolong());
        assertFalse(result.arg(5).toboolean());
        assertEquals(4096L, result.arg(6).tolong());
        assertFalse(result.arg(7).toboolean());
        assertEquals(1234567L, result.arg(8).tolong());
        // The second hook of the call
        assertFalse(result.arg(9).toboolean());
        assertFalse(result.arg(10).toboolean());
        assertEquals(292969L, result.arg(11).tolong());
    }

    // SystemProperties.get(key) calls native_get(key), which apps also reach by reflection: the fake value is made once
    // and the hook of get, which gets it, neither changes nor reports anything
    @Test
    public void nativeGetIsFakedOnce() throws Exception {
        Globals globals = load("systemproperties_get");
        String[][] cases = {{"device", "ro.product.model", "Pixel 7", "unknown"},
                {"device", "ro.build.version.security_patch", "2025-05-05", "2025-04-01"},
                {"serial", "ro.serialno", "R58M", "unknown"}, {"serial", "ril.iccid.sim1", "8949", ""},
                {"operator", "gsm.operator.alpha", "Carrier", "unknown"},
                {"timezone", "persist.sys.timezone", "Europe/Berlin", "UTC"},
                {"debugging", "ro.debuggable", "1", "0"}, {"debugging", "sys.usb.config", "mtp,adb", "mtp"},
                {"resources", "dalvik.vm.heapsize", "333m", "256m"}};
        for (String[] c : cases) {
            Varargs result = Scripts.run(globals, "local hook = hookof('SystemProperties.native_get/" + c[0] + "', 'native_get')\n" +
                    "local restricted, fake = run(hook, paramof('" + c[2] + "', { [0] = '" + c[1] + "' }))\n" +
                    "local again, same = run(hookof('SystemProperties.get/" + c[0] + "', 'get'), " +
                    "paramof(fake, { [0] = '" + c[1] + "' }))\n" +
                    "return restricted, fake, again, same, #filter(hook) > 0");
            assertTrue(c[1], result.arg(1).toboolean());
            assertEquals(c[1], c[3], result.arg(2).tojstring());
            assertFalse(c[1], result.arg(3).toboolean());
            assertEquals(c[1], c[3], result.arg(4).tojstring());
            assertTrue(c[1], result.arg(5).toboolean());
        }
    }

    // Input devices by id and luajava for the lookups, which must not run the hooks (see XParam.unhooked)
    private static final String INPUT = String.join("\n",
            Scripts.ARRAYOF,
            "function deviceof(external, name)",
            "  return { isExternal = function(self) return external end, getName = function(self) return name end }",
            "end",
            "devices = { [1] = deviceof(false, 'touchscreen'), [7] = deviceof(true, 'keyboard') }",
            "unhooked = false",
            "function callers() return { length = 0 } end",
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
        Varargs result = Scripts.run(globals, "local param = inputparamof(arrayof(1, 7, 9))\n" +
                "local restricted = after(hookof('InputManager.getInputDeviceIds', 'getInputDeviceIds'), param)\n" +
                "return restricted, param.result.length, param.result[1]");
        assertTrue(result.arg1().toboolean());
        assertEquals(1, result.arg(2).toint());
        assertEquals(1, result.arg(3).toint());
        assertFalse(Scripts.run(globals, "return run(hookof('InputDevice.getDeviceIds', 'getDeviceIds'), inputparamof(arrayof(1)))")
                .arg1().toboolean());

        result = Scripts.run(globals, "return run(hookof('InputDevice.getDevice', 'getDevice'), inputparamof(devices[7]))");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        assertFalse(Scripts.run(globals, "return run(hookof('InputManager.getInputDevice', 'getInputDevice'), inputparamof(devices[1]))")
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
        Varargs result = Scripts.run(globals, "local param = biometricparamof(nil, 33023)\n" +
                "local restricted = before(hookof('BiometricManager.getStrings', 'getStrings'), param)\n" +
                "return restricted, param:getArgument(0)");
        assertTrue(result.arg1().toboolean());
        assertEquals(32768, result.arg(2).toint());
        for (String authenticators : new String[]{"255", "32768", "33024"})
            assertFalse(authenticators, Scripts.run(globals, "return before(hookof('BiometricManager.getStrings', 'getStrings'), " +
                    "biometricparamof(nil, " + authenticators + "))").arg1().toboolean());

        // There was no biometric authentication, the screen lock keeps its time
        result = Scripts.run(globals, "return run(hookof('BiometricManager.getLastAuthenticationTime', 'getLastAuthenticationTime'), " +
                "biometricparamof(1234, 15))");
        assertTrue(result.arg1().toboolean());
        assertEquals(-1, result.arg(2).toint());
        assertFalse(Scripts.run(globals, "return run(hookof('BiometricManager.getLastAuthenticationTime', 'getLastAuthenticationTime'), " +
                "biometricparamof(1234, 32768))").arg1().toboolean());

        // The texts of biometrics only are none, the texts of the screen lock stay
        result = Scripts.run(globals, "local strings = {}\n" +
                "after(hookof('BiometricManager.getStrings', 'getStrings'), biometricparamof(strings, 15))\n" +
                "return run(hookof('BiometricManager.Strings.getButtonLabel', 'getButtonLabel'),\n" +
                "  biometricparamof('Use fingerprint', 0, strings))");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        assertFalse(Scripts.run(globals, "local strings = {}\n" +
                "after(hookof('BiometricManager.getStrings', 'getStrings'), biometricparamof(strings, 32768))\n" +
                "return run(hookof('BiometricManager.Strings.getButtonLabel', 'getButtonLabel'), biometricparamof('Use PIN', 0, strings))")
                .arg1().toboolean());
    }

    // The answers of a device without biometric hardware; the script runs only for the names of biometrics
    @Test
    public void biometricHardwareIsAbsent() throws Exception {
        Globals globals = load("biometrics");
        Varargs result = Scripts.run(globals, "local function names(method)\n" +
                "  local list = filter(hookof('x', method))\n" +
                "  if list == nil then return nil end\n" +
                "  table.sort(list) return table.concat(list, ',')\n" +
                "end\n" +
                "return names('getSystemService'), names('hasSystemFeature'), names('getSystemAvailableFeatures')");
        assertEquals("face,fingerprint,iris", result.arg(1).tojstring());
        assertEquals("android.hardware.biometrics.face,android.hardware.biometrics.iris,android.hardware.fingerprint",
                result.arg(2).tojstring());
        assertTrue(result.arg(3).isnil());

        // No manager for apps targeting Android 8 or later, before only no iris manager
        String service = "return run(hookof('Context.getSystemService/biometrics', 'getSystemService'), paramof('manager', { [0] = '%s' }))";
        Scripts.run(globals, "target = 26");
        result = Scripts.run(globals, String.format(service, "fingerprint"));
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        assertFalse(Scripts.run(globals, String.format(service, "window")).arg1().toboolean());
        Scripts.run(globals, "target = 25");
        assertFalse(Scripts.run(globals, String.format(service, "fingerprint")).arg1().toboolean());
        assertTrue(Scripts.run(globals, String.format(service, "iris")).arg1().toboolean());

        // Biometrics only are no hardware, with the screen lock the answer stays
        String authenticate = "return run(hookof('BiometricManager.canAuthenticate%s', 'canAuthenticate'), paramof(%d, { [0] = %d }))";
        result = Scripts.run(globals, String.format(authenticate, "/authenticators", 0, 15));
        assertTrue(result.arg1().toboolean());
        assertEquals(12, result.arg(2).toint());
        assertFalse(Scripts.run(globals, String.format(authenticate, "/authenticators", 0, 32783)).arg1().toboolean());
        assertEquals(12, Scripts.run(globals, String.format(authenticate, "", 0, 0)).arg(2).toint());
        assertFalse(Scripts.run(globals, String.format(authenticate, "", 12, 0)).arg1().toboolean());
        // Without the permission USE_BIOMETRIC the SecurityException stays, like on a device without the hardware
        assertFalse(Scripts.run(globals, "local param = paramof(nil, { [0] = 15 })\n" +
                "param.getException = function(self) return 'SecurityException' end\n" +
                "return run(hookof('BiometricManager.canAuthenticate/authenticators', 'canAuthenticate'), param)").arg1().toboolean());

        // No sensors, no fingerprint hardware
        result = Scripts.run(globals, "local strengths = { isEmpty = function(self) return false end, toString = function(self) return '{0=15}' end }\n" +
                "return run(hookof('BiometricManager.getBiometricSensorStrengths', 'getBiometricSensorStrengths'), paramof(strengths, {}))");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).checkuserdata(java.util.Map.class) instanceof java.util.HashMap);
        for (String method : new String[]{"isHardwareDetected", "hasEnrolledFingerprints"}) {
            result = Scripts.run(globals, "return run(hookof('FingerprintManager." + method + "', '" + method + "'), paramof(true, {}))");
            assertTrue(method, result.arg1().toboolean());
            assertFalse(method, result.arg(2).toboolean());
            assertFalse(method, Scripts.run(globals, "return run(hookof('FingerprintManager." + method + "', '" + method + "'), " +
                    "paramof(false, {}))").arg1().toboolean());
        }

        // The list of features without the biometric ones, as a new array
        result = Scripts.run(globals, Scripts.ARRAYOF + "\n" +
                "luajava = { newInstance = function(name)\n" +
                "    local list = {}\n" +
                "    return { add = function(self, value) list[#list + 1] = value end, size = function(self) return #list end,\n" +
                "      toArray = function(self, array) array.list = list return array end }\n" +
                "  end,\n" +
                "  bindClass = function(name) return { newInstance = function(self, type, size) return {} end } end }\n" +
                "local wifi = { name = 'android.hardware.wifi' }\n" +
                "local param = paramof(arrayof({ name = 'android.hardware.fingerprint' }, wifi, { name = 'android.hardware.biometrics.face' }), {})\n" +
                "local restricted, old, new = after(hookof('PackageManager.getSystemAvailableFeatures/biometrics', 'getSystemAvailableFeatures'), param)\n" +
                "return restricted, old, new, #param.result.list, param.result.list[1] == wifi");
        assertTrue(result.arg1().toboolean());
        assertEquals("3", result.arg(2).tojstring());
        assertEquals("1", result.arg(3).tojstring());
        assertEquals(1, result.arg(4).toint());
        assertTrue(result.arg(5).toboolean());
    }

    // TimeZone.setDefault keeps the fake zone, also when the default is reset with null; the ICU zone is one of ICU
    @Test
    public void timeZoneStaysFake() throws Exception {
        Globals globals = load("timezone");
        Varargs result = Scripts.run(globals, "local param = paramof(nil, {})\n" +
                "param.setArgument = function(self, index, value) self.zone = value end\n" +
                "local restricted, old, new = before(hookof('TimeZone.setDefault', 'setDefault'), param)\n" +
                "return restricted, old, new, param.zone:getID()");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        assertEquals("UTC", result.arg(3).tojstring());
        assertEquals("UTC", result.arg(4).tojstring());
        assertFalse(Scripts.run(globals, "return before(hookof('TimeZone.setDefault', 'setDefault'), " +
                "paramof(nil, { [0] = luajava.bindClass('java.util.TimeZone'):getTimeZone('UTC') }))").arg1().toboolean());

        result = Scripts.run(globals, "luajava = { bindClass = function(name)\n" +
                "  assert(name == 'android.icu.util.TimeZone', name)\n" +
                "  return { getTimeZone = function(self, id) return 'icu ' .. id end } end }\n" +
                "local param = paramof({ getID = function(self) return 'Europe/Berlin' end }, {})\n" +
                "param.getSetting = function(self, name) assert(name == 'value.timezone') return 'Europe/Amsterdam' end\n" +
                "local restricted, old, new = after(hookof('TimeZone.getDefault', 'getDefault', 'android.icu.util.TimeZone'), param)\n" +
                "return restricted, old, new, param.result");
        assertTrue(result.arg1().toboolean());
        assertEquals("Europe/Berlin", result.arg(2).tojstring());
        assertEquals("Europe/Amsterdam", result.arg(3).tojstring());
        assertEquals("icu Europe/Amsterdam", result.arg(4).tojstring());
    }

    // The Java system properties lose the build of the kernel and the model and build in http.agent
    @Test
    public void systemPropertiesAreReduced() throws Exception {
        Globals globals = load("system_properties");
        Varargs result = Scripts.run(globals, "local values = { ['os.version'] = '6.1.75-android14-11-g8b1f2a9',\n" +
                "  ['http.agent'] = 'Dalvik/2.1.0 (Linux; U; Android 15; Pixel 9 Build/AP4A.250105.002)' }\n" +
                "local props = { get = function(self, key) return values[key] end,\n" +
                "  put = function(self, key, value) values[key] = value end }\n" +
                "local restricted, old, new = after(hookof('System.props', '#props'), paramof(props, {}))\n" +
                "return restricted, values['os.version'], values['http.agent'], new");
        assertTrue(result.arg1().toboolean());
        assertEquals("6.1.75", result.arg(2).tojstring());
        // Like RuntimeInit makes it of the fake Build.MODEL and Build.ID
        assertEquals("Dalvik/2.1.0 (Linux; U; Android 15; unknown Build/unknown)", result.arg(3).tojstring());
        assertEquals("6.1.75 Dalvik/2.1.0 (Linux; U; Android 15; unknown Build/unknown)", result.arg(4).tojstring());
        // A preview build has no model
        Varargs preview = Scripts.run(globals, "local values = { ['http.agent'] = 'Dalvik/2.1.0 (Linux; U; Android Baklava Build/BP21.250103.003)' }\n" +
                "local props = { get = function(self, key) return values[key] end,\n" +
                "  put = function(self, key, value) values[key] = value end }\n" +
                "after(hookof('System.props', '#props'), paramof(props, {}))\n" +
                "return values['http.agent']");
        assertEquals("Dalvik/2.1.0 (Linux; U; Android Baklava Build/unknown)", preview.arg1().tojstring());
        assertFalse(Scripts.run(globals, "local props = { get = function(self, key) return key == 'http.agent' and 'okhttp/4.12.0' or nil end }\n" +
                "return after(hookof('System.props', '#props'), paramof(props, {}))").arg1().toboolean());
    }

    // The serial is the one set, else unknown
    @Test
    public void serialIsFaked() throws Exception {
        Globals globals = load("value_serial");
        Varargs result = Scripts.run(globals, "return run(hookof('Build.getSerial', 'getSerial'), paramof('R58N123ABC', {}))");
        assertTrue(result.arg1().toboolean());
        assertEquals("unknown", result.arg(2).tojstring());
        assertEquals("ABC123", Scripts.run(globals, "local param = paramof('R58N123ABC', {})\n" +
                "param.getSetting = function(self, name) assert(name == 'value.serial') return 'ABC123' end\n" +
                "return run(hookof('Build.getSerial', 'getSerial'), param)").arg(2).tojstring());
        assertFalse(Scripts.run(globals, "return run(hookof('Build.getSerial', 'getSerial'), paramof(nil, {}))").arg1().toboolean());
        // Build.SERIAL of apps targeting Android 8 or later is unknown already, which is no restriction
        assertFalse(Scripts.run(globals, "return run(hookof('Build.SERIAL', 'SERIAL'), paramof('unknown', {}))").arg1().toboolean());
    }

    // Sizes of /proc/meminfo of 2 TB and more, which string.format('%d') of LuaJ wraps, stay whole
    @Test
    public void largeMeminfoSizesDoNotWrap() throws Exception {
        Globals globals = load("resources");
        Varargs result = Scripts.run(globals, "local param = paramof('fd', {})\n" +
                "param.text = 'MemTotal:       3221225472 kB\\nMemFree:          147624 kB\\n'\n" +
                "param.opens = function(self, file) return true end\n" +
                "param.getFileText = function(self) return self.text end\n" +
                "param.setFileText = function(self, value) self.text = value end\n" +
                "local restricted = after(hookof('open', 'open'), param)\n" +
                "return restricted, param.text");
        assertTrue(result.arg1().toboolean());
        assertEquals("MemTotal:       3221225472 kB\nMemFree:          146484 kB\n", result.arg(2).tojstring());
    }
}
