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

import java.time.Duration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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

    // The text of the display, which Display makes from its fields, has the values of the hooked getters: the stable
    // density, only the active mode, also as the default one, with its refresh rates, and the luminance unknown, up to
    // Android 10 also in the hash of HdrCapabilities, and on Android 10 the panel without the model of its EDID; the
    // formats of AOSP android10-release, android14-release and android16-release, where frameRateCategoryRate stays like
    // getSuggestedFrameRate; a generic text stays
    @Test
    public void displayTextIsGeneric() throws Exception {
        Globals globals = load("display_panel");
        Scripts.run(globals, String.join("\n",
                "local bind = luajava.bindClass",
                "luajava.bindClass = function(name)",
                "  if name == 'android.util.DisplayMetrics' then return { DENSITY_DEVICE_STABLE = 440 } end",
                "  return bind(name) end",
                // HdrCapabilities as its text, with the one without luminance which hdr makes of it
                "local function hdrof(text, fake)",
                "  return setmetatable({ fake = fake }, { __tostring = function() return text end }) end",
                "hdr = function(real) return real and real.fake end",
                "function text(result, hdr)",
                "  local display = { getMode = function(self) return { getModeId = function(self) return 2 end } end,",
                "    getHdrCapabilities = function(self) return hdr end }",
                "  local param = paramof(result, {})",
                "  param.getThis = function(self) return display end",
                "  return after(hookof('Display.toString', 'toString', 'android.view.Display'), param), param.result",
                "end",
                "HDR10 = hdrof('android.view.Display$HdrCapabilities@6b1c2f', hdrof('android.view.Display$HdrCapabilities@3e4d1a'))",
                // From Android 14 the HdrCapabilities of the getter has the types of the mode
                "HDR14 = hdrof('HdrCapabilities{mSupportedHdrTypes=[2], mMaxLuminance=1000.0, mMaxAverageLuminance=500.0, " +
                        "mMinLuminance=5.0E-4}', hdrof('HdrCapabilities{mSupportedHdrTypes=[2], mMaxLuminance=-1.0, " +
                        "mMaxAverageLuminance=-1.0, mMinLuminance=-1.0}'))"));
        String metrics = ", DisplayMetrics{density=2.75, width=1080, height=2177, scaledDensity=2.75, ";
        String[][] cases = {{
                "Display id 0: DisplayInfo{\"Built-in Screen, displayId 0\", uniqueId \"local:4619827259835644545\", " +
                        "app 1080 x 2280, real 1080 x 2280, largest app 2280 x 2177, smallest app 1080 x 977, mode 2, " +
                        "defaultMode 1, modes [{id=1, width=1080, height=2280, fps=60.0}, {id=2, width=1080, height=2280, " +
                        "fps=90.0}], colorMode 0, supportedColorModes [0, 7, 9], hdrCapabilities " +
                        "android.view.Display$HdrCapabilities@6b1c2f, rotation 0, density 440 (403.411 x 403.041) dpi, " +
                        "layerStack 0, appVsyncOff 2000000, presDeadline 11111111, type BUILT_IN, address {port=129, " +
                        "model=0x401cecae7d6e8a}, state ON, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, removeMode 0}" +
                        metrics + "xdpi=403.411, ydpi=403.041}, isValid=true",
                "Display id 0: DisplayInfo{\"Built-in Screen, displayId 0\", uniqueId \"local:129\", " +
                        "app 1080 x 2280, real 1080 x 2280, largest app 2280 x 2177, smallest app 1080 x 977, mode 2, " +
                        "defaultMode 2, modes [{id=2, width=1080, height=2280, fps=90.0}], colorMode 0, supportedColorModes " +
                        "[0, 7, 9], hdrCapabilities android.view.Display$HdrCapabilities@3e4d1a, rotation 0, " +
                        "density 440 (440.0 x 440.0) dpi, layerStack 0, appVsyncOff 2000000, presDeadline 11111111, " +
                        "type BUILT_IN, address {port=129}, state ON, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, " +
                        "removeMode 0}" + metrics + "xdpi=440.0, ydpi=440.0}, isValid=true",
                "HDR10"}, {
                // Android 14 appends the render rate to the mode id without a space, 2 and 60.0
                "Display id 0: DisplayInfo{\"Built-in Screen\", displayId 0, displayGroupId 0, FLAG_SECURE, real 1080 x 2400, " +
                        "largest app 2400 x 2337, smallest app 1080 x 1017, appVsyncOff 6233334, presDeadline 11500000, " +
                        "mode 260.0, defaultMode 1, modes [{id=1, width=1080, height=2400, fps=60.0, " +
                        "alternativeRefreshRates=[120.0], supportedHdrTypes=[2, 3]}, {id=2, width=1080, height=2400, " +
                        "fps=120.0, alternativeRefreshRates=[60.0], supportedHdrTypes=[2]}], hdrCapabilities " +
                        "HdrCapabilities{mSupportedHdrTypes=[2, 3], mMaxLuminance=1000.0, mMaxAverageLuminance=500.0, " +
                        "mMinLuminance=5.0E-4}, userDisabledHdrTypes [], minimalPostProcessingSupported false, rotation 0, " +
                        "state ON, committedState ON}" + metrics + "xdpi=403.411, ydpi=403.041}, isValid=true",
                "Display id 0: DisplayInfo{\"Built-in Screen\", displayId 0, displayGroupId 0, FLAG_SECURE, real 1080 x 2400, " +
                        "largest app 2400 x 2337, smallest app 1080 x 1017, appVsyncOff 6233334, presDeadline 11500000, " +
                        "mode 260.0, defaultMode 2, modes [{id=2, width=1080, height=2400, " +
                        "fps=120.0, alternativeRefreshRates=[], supportedHdrTypes=[2]}], hdrCapabilities " +
                        "HdrCapabilities{mSupportedHdrTypes=[2, 3], mMaxLuminance=-1.0, mMaxAverageLuminance=-1.0, " +
                        "mMinLuminance=-1.0}, userDisabledHdrTypes [], minimalPostProcessingSupported false, rotation 0, " +
                        "state ON, committedState ON}" + metrics + "xdpi=440.0, ydpi=440.0}, isValid=true",
                "HDR14"}, {
                "Display id 0: DisplayInfo{\"Built-in Screen\", displayId 0, displayGroupId 0, FLAG_SECURE, mode 2, " +
                        "renderFrameRate 120.0, hasArrSupport false, frameRateCategoryRate FrameRateCategoryRate {normal=60.0, " +
                        "high=120.0}, supportedRefreshRates [60.0, 120.0], defaultMode 1, " +
                        "userPreferredModeId 1, supportedModes [{id=1, width=1080, height=2400, fps=60.0, vsync=60.0, " +
                        "synthetic=false, alternativeRefreshRates=[], supportedHdrTypes=[]}, {id=2, width=1080, height=2400, " +
                        "fps=120.0, vsync=120.0, synthetic=false, alternativeRefreshRates=[], supportedHdrTypes=[]}], " +
                        "appsSupportedModes [{id=1, width=1080, height=2400, fps=60.0, vsync=60.0, synthetic=false, " +
                        "alternativeRefreshRates=[], supportedHdrTypes=[]}, {id=2, width=1080, height=2400, fps=120.0, " +
                        "vsync=120.0, synthetic=false, alternativeRefreshRates=[], supportedHdrTypes=[]}], hdrCapabilities " +
                        "HdrCapabilities{mSupportedHdrTypes=[2], mMaxLuminance=1000.0, mMaxAverageLuminance=500.0, " +
                        "mMinLuminance=5.0E-4}, isForceSdr true, rotation 0, state ON, committedState ON}" + metrics +
                        "xdpi=440.0, ydpi=440.0}, isValid=true",
                "Display id 0: DisplayInfo{\"Built-in Screen\", displayId 0, displayGroupId 0, FLAG_SECURE, mode 2, " +
                        "renderFrameRate 120.0, hasArrSupport false, frameRateCategoryRate FrameRateCategoryRate {normal=60.0, " +
                        "high=120.0}, supportedRefreshRates [120.0], defaultMode 2, " +
                        "userPreferredModeId 2, supportedModes [{id=2, width=1080, height=2400, " +
                        "fps=120.0, vsync=120.0, synthetic=false, alternativeRefreshRates=[], supportedHdrTypes=[]}], " +
                        "appsSupportedModes [{id=2, width=1080, height=2400, fps=120.0, " +
                        "vsync=120.0, synthetic=false, alternativeRefreshRates=[], supportedHdrTypes=[]}], hdrCapabilities " +
                        "HdrCapabilities{mSupportedHdrTypes=[2], mMaxLuminance=-1.0, mMaxAverageLuminance=-1.0, " +
                        "mMinLuminance=-1.0}, isForceSdr true, rotation 0, state ON, committedState ON}" + metrics +
                        "xdpi=440.0, ydpi=440.0}, isValid=true",
                // Forced to SDR the getter gives no HdrCapabilities, the text has them
                "nil"}};
        for (String[] c : cases) {
            globals.set("TEXT", c[0]);
            Varargs result = Scripts.run(globals, "return text(TEXT, " + c[2] + ")");
            assertTrue(c[2], result.arg(1).toboolean());
            assertEquals(c[2], c[1], result.arg(2).tojstring());
        }
        // A panel with one mode, the stable density and no luminance
        globals.set("TEXT", "Display id 0: DisplayInfo{\"Built-in Screen\", displayId 0, mode 2, defaultMode 2, " +
                "userPreferredModeId -1, modes [{id=2, " +
                "width=1080, height=2400, fps=60.0}], hdrCapabilities null, state ON}" + metrics + "xdpi=440.0, ydpi=440.0}, " +
                "isValid=true");
        assertFalse(Scripts.run(globals, "return text(TEXT, nil)").arg1().toboolean());
    }

    // Android 10: the address of the panel, which apps get by reflection, has only its port like the text of the
    // display, made from a parcel like DisplayAddress.Physical writes itself, since its members are hidden from apps;
    // an address without model, of a network display, none, and a parcel read back otherwise stay
    @Test
    public void displayAddressHasNoModel() throws Exception {
        Globals globals = load("display_panel");
        Varargs result = Scripts.run(globals, String.join("\n",
                "local function textof(text) return setmetatable({}, { __tostring = function() return text end }) end",
                "local written = {}",
                "local parcel = { writeString = function(self, s) written[#written + 1] = s end,",
                "  writeLong = function(self, l) written[#written + 1] = l end,",
                "  setDataPosition = function(self, p) written.position = p end,",
                "  readParcelable = function(self, loader) written.loader = loader",
                "    return textof(READ or '{port=' .. written[2] .. '}') end,",
                "  recycle = function(self) written.recycled = true end }",
                "local bind = luajava.bindClass",
                "luajava.bindClass = function(name)",
                "  if name == 'android.os.Parcel' then return { obtain = function(self) return parcel end } end",
                "  return bind(name) end",
                "local function address(text)",
                "  local param = paramof(text and textof(text))",
                "  return after(hookof('Display.getAddress', 'getAddress', 'android.view.Display'), param), tostring(param.result)",
                "end",
                "local restricted, fake = address('{port=129, model=0x401cecae7d6e8a}')",
                "local made = written",
                "written = {}",
                "READ = '{port=0}'",
                "local other, kept = address('{port=129, model=0x401cecae7d6e8a}')",
                "return restricted, fake, made[1], made[2], made.position, made.loader, made.recycled,",
                "  address('{port=129}'), address('02:00:00:00:00:00'), address(nil), other, kept"));
        assertTrue(result.arg(1).toboolean());
        assertEquals("{port=129}", result.arg(2).tojstring());
        assertEquals("android.view.DisplayAddress$Physical", result.arg(3).tojstring());
        assertEquals(129, result.arg(4).toint());
        assertEquals(0, result.arg(5).toint());
        assertTrue("default loader", result.arg(6).isnil());
        assertTrue("recycled", result.arg(7).toboolean());
        assertFalse("no model", result.arg(8).toboolean());
        assertFalse("network", result.arg(9).toboolean());
        assertFalse("none", result.arg(10).toboolean());
        assertFalse("read back otherwise", result.arg(11).toboolean());
        assertEquals("{port=129, model=0x401cecae7d6e8a}", result.arg(12).tojstring());
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

    // The maker and the model in the build information of OEMCrypto become unknown in place, the rest stays as it is;
    // the free text of older OEMCrypto and a value already unknown stay
    @Test
    public void drmBuildInformationWithoutModel() throws Exception {
        Globals globals = load("mediadrm_version");
        String[][] cases = {
                {"{\"soc_vendor\": \"samsung\", \"soc_model\": \"SM-S938U1\", \"fused\": \"locked\", \"tee_os_ver\": \"150000\", " +
                        "\"tee_os\": \"strongbox\", \"uses_opk\": false, \"implementer\": \"Qualcomm\", \"ta_ver\": \"1.186\", " +
                        "\"ree\": {\"liboemcrypto_ver\": \"1.93\"}}",
                        "{\"soc_vendor\": \"unknown\", \"soc_model\": \"unknown\", \"fused\": \"locked\", \"tee_os_ver\": \"150000\", " +
                                "\"tee_os\": \"strongbox\", \"uses_opk\": false, \"implementer\": \"Qualcomm\", \"ta_ver\": \"1.186\", " +
                                "\"ree\": {\"liboemcrypto_ver\": \"1.93\"}}"},
                {"{\"soc_vendor\":\"L3_28615\",\"soc_model\":\"X86 64 bit\",\"ta_ver\":\"18.1.0+May  1 2023_06:32:45_\"," +
                        "\"uses_opk\":false,\"tee_os\":\"none\",\"form_factor\":\"L3\",\"implementer\":\"Widevine\",\"fused\":false}",
                        "{\"soc_vendor\":\"unknown\",\"soc_model\":\"unknown\",\"ta_ver\":\"18.1.0+May  1 2023_06:32:45_\"," +
                                "\"uses_opk\":false,\"tee_os\":\"none\",\"form_factor\":\"L3\"," +
                                "\"implementer\":\"Widevine\",\"fused\":false}"}};
        for (String[] c : cases) {
            globals.set("real", c[0]);
            Varargs result = Scripts.run(globals, "return run(hookof('MediaDrm.getPropertyString/version', " +
                    "'getPropertyString'), paramof(real, { [0] = 'oemCryptoBuildInformation' }))");
            assertTrue(c[0], result.arg1().toboolean());
            assertEquals(c[1], result.arg(2).tojstring());
            globals.set("real", c[1]);
            assertFalse(c[1], Scripts.run(globals, "return run(hookof('MediaDrm.getPropertyString/version', " +
                    "'getPropertyString'), paramof(real, { [0] = 'oemCryptoBuildInformation' }))").arg1().toboolean());
        }
        String[] kept = {"OEMCrypto Level3 Code 22594 May 28 2021 16:59:07", "", "{\"soc_vendor\":\"\",\"soc_model\":\"\"}"};
        for (String real : kept) {
            globals.set("real", real);
            assertFalse(real, Scripts.run(globals, "return run(hookof('MediaDrm.getPropertyString/version', " +
                    "'getPropertyString'), paramof(real, { [0] = 'oemCryptoBuildInformation' }))").arg1().toboolean());
        }
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
                "  getBoolean = function(self, key) return values[key] == true end,\n" +
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
                "  getBoolean = function(self, key) return values[key] == true end,\n" +
                "  putString = function(self, key, value) values[key] = value end }\n" +
                "return run(hookof('SmsManager.getCarrierConfigValues', 'getCarrierConfigValues'), paramof(bundle, {}))")
                .arg1().toboolean());
    }

    // The name of the carrier and the version of its configuration become the empty defaults of Android, and the
    // override of the name its default false; a bundle without them, like the MMS values of SmsManager or a subset for
    // other keys, gains none, and other values stay
    @Test
    public void carrierConfigNameAndVersionAreDefaults() throws Exception {
        Globals globals = load("mms_useragent");
        String bundle = "local bundle = { getString = function(self, key) return values[key] end,\n" +
                "  getBoolean = function(self, key) return values[key] == true end,\n" +
                "  putString = function(self, key, value) values[key] = value end,\n" +
                "  putBoolean = function(self, key, value) values[key] = value end }\n";
        Varargs result = Scripts.run(globals, "local values = { carrier_name_string = 'T-Mobile',\n" +
                "  carrier_config_version_string = '310260-tmobile_us-1234', carrier_volte_available_bool = true,\n" +
                "  carrier_name_override_bool = true }\n" + bundle +
                "local restricted = run(hookof('CarrierConfigManager.getConfigForSubId', 'getConfigForSubId'), paramof(bundle, {}))\n" +
                "return restricted, values.carrier_name_string, values.carrier_config_version_string,\n" +
                "  values.carrier_volte_available_bool, values.carrier_name_override_bool");
        assertTrue(result.arg(1).toboolean());
        assertEquals("", result.arg(2).tojstring());
        assertEquals("", result.arg(3).tojstring());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).isboolean());
        assertFalse(result.arg(5).toboolean());
        String[] methods = {"SmsManager.getCarrierConfigValues|getCarrierConfigValues",
                "CarrierConfigManager.getConfig/keys|getConfig"};
        for (String method : methods) {
            String[] m = method.split("\\|");
            result = Scripts.run(globals, "local values = { maxMessageSize = 1048576 }\n" + bundle +
                    "return run(hookof('" + m[0] + "', '" + m[1] + "'), paramof(bundle, {})),\n" +
                    "  values.carrier_name_string, values.carrier_config_version_string, values.carrier_name_override_bool");
            assertFalse(m[0], result.arg(1).toboolean());
            assertTrue(m[0], result.arg(2).isnil());
            assertTrue(m[0], result.arg(3).isnil());
            assertTrue(m[0], result.arg(4).isnil());
        }
        assertFalse(Scripts.run(globals, "local values = { carrier_name_string = '', carrier_config_version_string = '' }\n" +
                bundle + "return run(hookof('CarrierConfigManager.getConfig', 'getConfig'), paramof(bundle, {}))")
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
                "values = {}",
                "function connect(class, binder)",
                "  proxy = nil",
                "  local param = { args = { [0] = componentof(class), [1] = binder or service } }",
                "  param.getArgument = function(self, index) return self.args[index] end",
                "  param.setArgument = function(self, index, value) self.args[index] = value end",
                "  param.getValue = function(self, name, scope) return (values[scope] or {})[name] end",
                "  param.putValue = function(self, name, value, scope)",
                "    values[scope] = values[scope] or {} values[scope][name] = value end",
                "  return before({}, param), param.args[1] == service, param.args[1]",
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

        // The same binder of the service gets the same binder in front, so a second bind of a connection while it is
        // connected calls nothing, like without it (doConnected compares the binders); another binder of the service,
        // like after Play services restarted, gets one of its own. equals is left to the proxy, which equals only
        // itself like a BinderProxy (see LuaReflectionTest.proxyMethodsWithoutFunctions).
        Varargs again = Scripts.run(globals, "local adid = 'com.google.android.gms.ads.identifier.service.AdvertisingIdService'\n" +
                "local first, second = select(3, connect(adid)), select(3, connect(adid))\n" +
                "local restricted, same, restarted = connect(adid, { transact = service.transact })\n" +
                "return restricted, rawequal(first, second), rawequal(first, restarted), first.equals");
        assertTrue(again.arg(1).toboolean());
        assertTrue(again.arg(2).toboolean());
        assertFalse(again.arg(3).toboolean());
        assertTrue(again.arg(4).isnil());
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

    // getInt, getLong and getBoolean call natives, which apps reach by reflection: those fake like the getters, the
    // getter around them sees the fake and changes nothing; a property which is not set keeps the default
    @Test
    public void nativeNumbersAreFakedFirst() throws Exception {
        Globals globals = load("systemproperties_get");
        Varargs result = Scripts.run(globals,
                "PROPERTIES = { ['gsm.operator.numeric'] = '310260', ['ro.build.date.utc'] = '1720722121' }\n" +
                        "local operator, fake = run(hookof('SystemProperties.native_get_long/operator', 'native_get_long'), " +
                        "paramof(310260, { [0] = 'gsm.operator.numeric', [1] = 0 }))\n" +
                        "local date, zero = run(hookof('SystemProperties.native_get_long/device', 'native_get_long'), " +
                        "paramof(1720722121, { [0] = 'ro.build.date.utc', [1] = -1 }))\n" +
                        "local again = run(hookof('SystemProperties.getLong/device', 'getLong'), " +
                        "paramof(zero, { [0] = 'ro.build.date.utc', [1] = -1 }))\n" +
                        "local int, small = run(hookof('SystemProperties.native_get_int/operator', 'native_get_int'), " +
                        "paramof(310260, { [0] = 'gsm.operator.numeric', [1] = 0 }))\n" +
                        "local flag, off = run(hookof('SystemProperties.native_get_boolean/debugging', 'native_get_boolean'), " +
                        "paramof(true, { [0] = 'ro.debuggable', [1] = false }))\n" +
                        "local unset, default = run(hookof('SystemProperties.native_get_long/operator', 'native_get_long'), " +
                        "paramof(7, { [0] = 'gsm.sim.operator.numeric', [1] = 7 }))\n" +
                        "return operator, fake, date, zero, again, int, small, flag, off, unset, default");
        assertTrue(result.arg(1).toboolean());
        assertEquals(101, result.arg(2).toint());
        assertTrue(result.arg(3).toboolean());
        assertEquals(0, result.arg(4).toint());
        assertFalse(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
        assertEquals(101, result.arg(7).toint());
        assertTrue(result.arg(8).toboolean());
        assertFalse(result.arg(9).toboolean());
        assertFalse(result.arg(10).toboolean());
        assertEquals(7, result.arg(11).toint());

        // native_get(key, default) of the serial keeps the default of a property which is not set, like get(key, default)
        Varargs serial = Scripts.run(globals,
                "PROPERTIES = { ['ro.serialno'] = 'R58M' }\n" +
                        "local hook = hookof('SystemProperties.native_get.default/serial', 'native_get')\n" +
                        "local restricted, fake = run(hook, paramof('R58M', { [0] = 'ro.serialno', [1] = 'd' }))\n" +
                        "PROPERTIES = {}\n" +
                        "local kept, default = run(hook, paramof('d', { [0] = 'ro.serialno', [1] = 'd' }))\n" +
                        "return restricted, fake, kept, default");
        assertTrue(serial.arg(1).toboolean());
        assertEquals("unknown", serial.arg(2).tojstring());
        assertFalse(serial.arg(3).toboolean());
        assertEquals("d", serial.arg(4).tojstring());
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

    // A service state and a subscription the app gets, as Lua stand-ins which give their values only without the hooks,
    // and parcels of typed values at offsets in bytes, each padded to 4 bytes like Android, which the objects write and
    // read like Android of version sdk: the codes as numbers before Android 10, the raw names of a service state and the
    // fields of a subscription after its country from Android 10, its group UUID as UTF-8 from Android 14, of which the
    // script skips the bytes; made runs the hook of the creator and returns whether it restricted and the object the app
    // gets
    private static final String CREATED = String.join("\n",
            "function pad(size) return math.floor((size + 3) / 4) * 4 end",
            "function stringsize(v) return v and 4 + pad((#v + 1) * 2) or 4 end",
            "function stateof(long, short, numeric, rawlong, rawshort)",
            "  local state = { names = { long, short, numeric }, raw = { rawlong, rawshort } }",
            "  function state:getOperatorAlphaLong() assert(inservice) return self.names[1] end",
            "  function state:getOperatorAlphaShort() assert(inservice) return self.names[2] end",
            "  function state:getOperatorNumeric() assert(inservice) return self.names[3] end",
            "  function state:setOperatorName(long, short, numeric) self.set = { long, short, numeric } self.names = self.set end",
            "  function state:writeToParcel(p, flags) assert(inservice and sdk >= 29) p:writeInt(0) p:writeInt(1)",
            "    for _ = 1, (sdk == 29 and 2 or 1) do for i = 1, 3 do p:writeString(self.names[i]) end end",
            "    for i = 1, 10 do p:writeInt(i) end p:writeInt(1) p:put('value', 'registration', 12) p:writeInt(5)",
            "    p:put('ints', 5000, 8) p:writeInt(0) p:writeString(self.raw[1]) p:writeString(self.raw[2])",
            "    for _ = 1, (sdk == 29 and 1 or 2) do p:writeInt(1) end end",
            "  return state",
            "end",
            "states = { createFromParcel = function(self, p) assert(inservice) assert(p:readInt() == 0 and p:readInt() == 1)",
            "  local names = { p:readString(), p:readString(), p:readString() }",
            "  if sdk == 29 then for i = 1, 3 do assert(p:readString() == names[i]) end end",
            "  for i = 1, 10 do assert(p:readInt() == i) end assert(p:readInt() == 1 and p:get('value') == 'registration')",
            "  assert(p:readInt() == 5 and p:get('ints') == 5000 and p:readInt() == 0)",
            "  local state = stateof(names[1], names[2], names[3], p:readString(), p:readString())",
            "  for _ = 1, (sdk == 29 and 1 or 2) do assert(p:readInt() == 1) end return state end }",
            "parcels = {}",
            "function parcelof() local p = { items = {}, pos = 0, size = 0 } parcels[#parcels + 1] = p",
            "  function p:put(kind, value, size) size = size or 4 self.items[self.pos] = { kind, value, size }",
            "    self.pos = self.pos + size self.size = math.max(self.size, self.pos) end",
            "  function p:get(kind) local item = self.items[self.pos]",
            "    assert(item ~= nil and item[1] == kind, kind .. ' at ' .. self.pos) self.pos = self.pos + item[3] return item[2] end",
            "  function p:writeInt(v) self:put('int', v) end function p:readInt() return self:get('int') end",
            "  function p:writeLong(v) self:put('long', v, 8) end function p:readLong() return self:get('long') end",
            "  function p:writeString(v) self:put('string', v, stringsize(v)) end",
            "  function p:readString() return self:get('string') end",
            "  function p:readValue(loader) return self:get('value') end function p:createIntArray() return self:get('ints') end",
            "  function p:readParcelable(loader) return self:get('icon') end",
            "  function p:createByteArray() return self:get('bytes') end",
            "  function p:dataPosition() return self.pos end function p:setDataPosition(pos) self.pos = pos end",
            "  function p:dataSize() return self.size end function p:dataAvail() return self.size - self.pos end",
            "  function p:recycle() self.recycled = true end",
            "  function p:appendFrom(other, offset, length)",
            "    for at = offset, offset + length - 1 do local item = other.items[at]",
            "      if item ~= nil then assert(at + item[3] <= offset + length) self.items[self.pos + at - offset] = item end end",
            "    self.pos = self.pos + length self.size = math.max(self.size, self.pos) end",
            "  return p end",
            "sdk = 34",
            "UUID = 'c0ffee00-0000-4000-8000-000000000001'",
            "function infoof(display, carrier, mcc, mnc, country, carrierid, ehplmns, hplmns)",
            "  local info = { display = display, carrier = carrier, mcc = mcc, mnc = mnc, country = country,",
            "    carrierid = carrierid or -1, ehplmns = ehplmns or false, hplmns = hplmns or false, uuid = UUID }",
            "  function info:getDisplayName() assert(inservice) return self.display end",
            "  function info:getCarrierName() assert(inservice) return self.carrier end",
            "  function info:getMcc() assert(inservice) return (sdk < 29 and self.mcc or tonumber(self.mcc) or 0) end",
            "  function info:getMnc() assert(inservice) return (sdk < 29 and self.mnc or tonumber(self.mnc) or 0) end",
            "  function info:getMccString() assert(inservice and sdk >= 29) return self.mcc end",
            "  function info:getMncString() assert(inservice and sdk >= 29) return self.mnc end",
            "  function info:getCountryIso() assert(inservice) return self.country end",
            "  function info:getCarrierId() assert(inservice and sdk >= 29) return self.carrierid end",
            "  function info:writeToParcel(p, flags) assert(inservice) p:writeInt(7) p:writeString('8931') p:writeInt(0)",
            "    p:put('text', self.display, 12) p:put('text', self.carrier, 12) p:writeInt(1) p:writeInt(2)",
            "    p:writeString('+316') p:writeInt(0) if sdk < 29 then p:writeInt(self.mcc) p:writeInt(self.mnc)",
            "    else p:writeString(self.mcc) p:writeString(self.mnc) end p:writeString(self.country)",
            "    if sdk >= 29 then writetail(p, self) end p:writeString('tail') end",
            "  return info end",
            // The fields after the country: the icon before Android 13, an access rule and none, the port from
            // Android 13 and the group UUID, as UTF-8 from Android 14; home networks of false are none (null)
            "function writetail(p, info)",
            "  if sdk < 33 then p:put('icon', 'bitmap', 16) end p:writeInt(1)",
            "  p:writeInt(2) p:writeInt(1) p:put('bytes', 'hash', 8) p:writeString('com.carrier') p:writeLong(3) p:writeInt(0)",
            "  p:writeString('89014') p:writeInt(4) if sdk >= 33 then p:writeInt(0) end p:writeInt(1)",
            "  if sdk < 34 then p:writeString(info.uuid) else p:writeInt(#info.uuid) p:put('utf8', info.uuid, pad(#info.uuid + 1)) end",
            "  p:writeInt(0) p:writeInt(info.carrierid) p:writeInt(0) p:writeInt(0)",
            "  for _, list in ipairs({ info.ehplmns, info.hplmns }) do",
            "    if list then p:writeInt(#list) for _, plmn in ipairs(list) do p:writeString(plmn) end else p:writeInt(-1) end end",
            "end",
            "function readtail(p, info)",
            "  if sdk < 33 then assert(p:get('icon') == 'bitmap') end assert(p:readInt() == 1)",
            "  assert(p:readInt() == 2 and p:readInt() == 1 and p:get('bytes') == 'hash' and p:readString() == 'com.carrier')",
            "  assert(p:readLong() == 3 and p:readInt() == 0 and p:readString() == '89014' and p:readInt() == 4)",
            "  if sdk >= 33 then assert(p:readInt() == 0) end assert(p:readInt() == 1)",
            "  if sdk < 34 then info.uuid = p:readString() else assert(p:readInt() == #UUID) info.uuid = p:get('utf8') end",
            "  assert(p:readInt() == 0) info.carrierid = p:readInt() assert(p:readInt() == 0 and p:readInt() == 0)",
            "  info.ehplmns, info.hplmns = readplmns(p), readplmns(p)",
            "end",
            "function readplmns(p) local count = p:readInt() if count < 0 then return false end",
            "  local list = {} for i = 1, count do list[i] = p:readString() end return list end",
            "infos = { createFromParcel = function(self, p) assert(inservice) assert(p:readInt() == 7)",
            "  assert(p:readString() == '8931') assert(p:readInt() == 0) local display, carrier = p:get('text'), p:get('text')",
            "  assert(p:readInt() == 1 and p:readInt() == 2) assert(p:readString() == '+316') assert(p:readInt() == 0)",
            "  local mcc, mnc if sdk < 29 then mcc, mnc = p:readInt(), p:readInt() else mcc, mnc = p:readString(), p:readString() end",
            "  local info = infoof(display, carrier, mcc, mnc, p:readString()) if sdk >= 29 then readtail(p, info) end",
            "  assert(p:readString() == 'tail') return info end }",
            "luajava = { bindClass = function(name)",
            "  if name == 'android.os.Parcel' then return { obtain = function(self) return parcelof() end } end",
            "  if name == 'android.text.TextUtils' then",
            "    return { CHAR_SEQUENCE_CREATOR = { createFromParcel = function(self, p) return p:get('text') end },",
            "      writeToParcel = function(self, text, p, flags) p:put('text', text) end } end",
            "  if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end",
            "  if name == 'android.telephony.ServiceState' then return { CREATOR = states } end",
            "  assert(name == 'android.telephony.SubscriptionInfo', name)",
            "  return { CREATOR = infos }",
            "end }",
            "function made(class, object)",
            "  parcels = {}",
            "  local param = paramof(object, {})",
            "  param.unhooked = function(self, fn) inservice = true local value = fn() inservice = false return value end",
            "  local restricted = after(hookof(class .. '.createFromParcel/operator', 'CREATOR:createFromParcel',",
            "    'android.telephony.' .. class), param)",
            "  for _, p in ipairs(parcels) do assert(p.recycled) end",
            "  return restricted, param.result",
            "end");

    // A service state the app gets has the operator of the getters in its fields, by the public setter, which sets the
    // names of voice and data alike, like Android; empty names stay, and so does a state without service
    @Test
    public void serviceStateFieldsAreTheTestNetwork() throws Exception {
        Globals globals = load("test_network");
        Scripts.run(globals, CREATED);
        String set = "local state = stateof(%s)\n" +
                "local restricted = made('ServiceState', state)\n" +
                "local set = state.set or {}\n" +
                "return restricted, state.set ~= nil, set[1], set[2], set[3]";
        Varargs result = Scripts.run(globals, String.format(set, "'T-Mobile', 'T-Mobile', '310260'"));
        assertTrue(result.arg(1).toboolean());
        assertEquals("unknown", result.arg(3).tojstring());
        assertEquals("unknown", result.arg(4).tojstring());
        assertEquals("00101", result.arg(5).tojstring());
        result = Scripts.run(globals, String.format(set, "'T-Mobile', nil, '310260'"));
        assertTrue(result.arg(1).toboolean());
        assertEquals("unknown", result.arg(3).tojstring());
        assertTrue(result.arg(4).isnil());
        assertEquals("00101", result.arg(5).tojstring());
        result = Scripts.run(globals, String.format(set, "'', 'TMO', ''"));
        assertTrue(result.arg(1).toboolean());
        assertEquals("", result.arg(3).tojstring());
        assertEquals("unknown", result.arg(4).tojstring());
        assertEquals("", result.arg(5).tojstring());
        for (String stays : new String[]{"nil, nil, nil", "'', '', ''", "'unknown', 'unknown', '00101'"}) {
            result = Scripts.run(globals, String.format(set, stays));
            assertFalse(stays, result.arg(1).toboolean());
            assertFalse(stays, result.arg(2).toboolean());
        }
    }

    // From Android 10 a service state the app gets is made again from its parcel with its raw names faked like the
    // names, also where Android removed the names (an app without location); empty raw names stay, Android 9 has none,
    // and a state of which the parcel does not read like Android keeps the names of the setter
    @Test
    public void serviceStateRawNamesAreTheTestNetwork() throws Exception {
        Globals globals = load("test_network");
        Scripts.run(globals, CREATED);
        String raw = "sdk = %s\n" +
                "local state = stateof(%s)\n" +
                "local restricted, made = made('ServiceState', state)\n" +
                "return restricted, made ~= state, tostring(made.names[1]) .. ' ' .. tostring(made.names[2]) .. ' ' ..\n" +
                "  tostring(made.names[3]) .. ' ' .. tostring(made.raw[1]) .. ' ' .. tostring(made.raw[2])";
        String[][] cases = {
                // The version, the state, whether it is made again and the names and raw names of the one the app gets
                {"29", "'T-Mobile', 'T-Mobile', '310260', 'T-Mobile', 'TMO'", "true", "unknown unknown 00101 unknown unknown"},
                {"30", "'T-Mobile', 'T-Mobile', '310260', 'T-Mobile', 'TMO'", "true", "unknown unknown 00101 unknown unknown"},
                {"34", "nil, nil, nil, 'T-Mobile', 'TMO'", "true", "nil nil nil unknown unknown"},
                {"29", "nil, nil, nil, 'T-Mobile', nil", "true", "nil nil nil unknown nil"},
                {"34", "'T-Mobile', 'T-Mobile', '310260', '', nil", "false", "unknown unknown 00101  nil"},
                {"28", "'T-Mobile', 'T-Mobile', '310260', 'T-Mobile', 'TMO'", "false", "unknown unknown 00101 T-Mobile TMO"}};
        for (String[] c : cases) {
            String lua = String.format(raw, c[0], c[1]);
            Varargs result = Scripts.run(globals, lua);
            assertTrue(lua, result.arg(1).toboolean());
            assertEquals(lua, Boolean.parseBoolean(c[2]), result.arg(2).toboolean());
            assertEquals(lua, c[3], result.arg(3).tojstring());
        }
        Varargs result = Scripts.run(globals, String.format(raw, "34", "nil, nil, nil, '', 'unknown'"));
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        // A ROM which writes another field first
        result = Scripts.run(globals, "sdk = 34\n" +
                "local state = stateof('T-Mobile', 'T-Mobile', '310260', 'T-Mobile', 'TMO')\n" +
                "local write = state.writeToParcel\n" +
                "function state:writeToParcel(p, flags) p:writeInt(9) write(self, p, flags) end\n" +
                "local restricted, made = made('ServiceState', state)\n" +
                "return restricted, made == state, made.names[1], made.raw[1]");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals("unknown", result.arg(3).tojstring());
        assertEquals("T-Mobile", result.arg(4).tojstring());
    }

    // A subscription the app gets is made again from its parcel with the names, codes and country of the getters, the
    // other fields copied; no codes stay, like the getters keep them, and so does a subscription of which the parcel
    // does not read like Android
    @Test
    public void subscriptionFieldsAreTheTestNetwork() throws Exception {
        Globals globals = load("test_network");
        Scripts.run(globals, CREATED);
        String fields = "sdk = %s\n" +
                "local info = infoof(%s)\n" +
                "local restricted, made = made('SubscriptionInfo', info)\n" +
                "return restricted, made ~= info, made.display, made.carrier, made.mcc, made.mnc, made.country";
        String[][] cases = {
                // The version, the subscription and the fields of the one the app gets
                {"34", "'SIM 1', 'T-Mobile', '310', '260', 'us'", "unknown unknown 001 01 "},
                {"29", "'SIM 1', 'T-Mobile', '310', '260', 'us'", "unknown unknown 001 01 "},
                {"28", "'SIM 1', 'T-Mobile', 310, 260, 'us'", "unknown unknown 1 1 "},
                {"27", "'SIM 1', 'T-Mobile', 0, 0, ''", "unknown unknown 0 0 "},
                // An MNC of 0 (00) is a code with an MCC
                {"28", "'', '', 460, 0, ''", "  1 1 "},
                {"34", "'SIM 1', '', '', '', ''", "unknown    "}};
        for (String[] c : cases) {
            String lua = String.format(fields, c[0], c[1]);
            Varargs result = Scripts.run(globals, lua);
            assertTrue(lua, result.arg(1).toboolean());
            assertTrue(lua, result.arg(2).toboolean());
            assertEquals(lua, c[2], result.arg(3).tojstring() + " " + result.arg(4).tojstring() + " " +
                    result.arg(5).tojstring() + " " + result.arg(6).tojstring() + " " + result.arg(7).tojstring());
        }
        for (String stays : new String[]{"'', '', '', '', ''", "'unknown', 'unknown', '001', '01', ''"}) {
            Varargs result = Scripts.run(globals, String.format(fields, "34", stays));
            assertFalse(stays, result.arg(1).toboolean());
            assertFalse(stays, result.arg(2).toboolean());
        }
        // A getter which gives another value than the parcel has, like of a ROM which writes other fields first
        Varargs result = Scripts.run(globals, "sdk = 34\n" +
                "local info = infoof('SIM 1', 'T-Mobile', '310', '260', 'us')\n" +
                "function info:getCountryIso() return 'de' end\n" +
                "local restricted, made = made('SubscriptionInfo', info)\n" +
                "return restricted, made ~= info");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
    }

    // From Android 10 the carrier of a subscription the app gets is the test network too, like getCarrierId gives it,
    // and its home networks are none, like toString prints them; the fields between are copied, also the group UUID,
    // which Android 14 and later write as UTF-8. An unknown carrier and no home networks stay, and where the carrier
    // does not read back like the getter gives it, the names, codes and country are faked still
    @Test
    public void subscriptionCarrierIsTheTestNetwork() throws Exception {
        Globals globals = load("test_network");
        Scripts.run(globals, CREATED);
        String fields = "sdk = %s\n" +
                "local info = infoof('SIM 1', 'T-Mobile', '310', '260', 'us', %s)\n" +
                "%s\n" +
                "local restricted, made = made('SubscriptionInfo', info)\n" +
                "local plmns = function(list) return list and #list or 'none' end\n" +
                "return restricted, made ~= info, made.display .. ' ' .. made.carrierid .. ' ' .. plmns(made.ehplmns) ..\n" +
                "  ' ' .. plmns(made.hplmns), made.uuid";
        String[][] cases = {
                // The version, the carrier and home networks, a change of the getters and the fields the app gets
                {"29", "1, { '310260' }, { '310260', '310026' }", "", "unknown 1911 0 0"},
                {"31", "1, { '310260' }, { '310260', '310026' }", "", "unknown 1911 0 0"},
                {"33", "1, { '310260' }, { '310260', '310026' }", "", "unknown 1911 0 0"},
                {"34", "1, { '310260' }, { '310260', '310026' }", "", "unknown 1911 0 0"},
                {"36", "1, { '310260' }, { '310260', '310026' }", "", "unknown 1911 0 0"},
                {"34", "-1, false, {}", "", "unknown -1 none 0"},
                {"33", "1911, { '00101' }, false", "", "unknown 1911 0 none"},
                {"34", "1, { '310260' }", "function info:getCarrierId() return 2 end", "unknown 1 1 none"},
                {"31", "1, { '310260' }", "function info:getCarrierId() return 2 end", "unknown 1 1 none"}};
        for (String[] c : cases) {
            String lua = String.format(fields, c[0], c[1], c[2]);
            Varargs result = Scripts.run(globals, lua);
            assertTrue(lua, result.arg(1).toboolean());
            assertTrue(lua, result.arg(2).toboolean());
            assertEquals(lua, c[3], result.arg(3).tojstring());
            assertEquals(lua, "c0ffee00-0000-4000-8000-000000000001", result.arg(4).tojstring());
        }
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
            "  param.getApplicationContext = function(self)",
            "    return { getClassLoader = function(self) return loaders.app end } end",
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
            // The classes of the app, also named like the framework, are not of the boot class path; another class loader
            // is the set of the names it finds, the one of WebView none until WebView is loaded (see webviewclass)
            "loaders = { app = {} }",
            "function finds(name, loader)",
            "  if loader == nil then return not string.find(name, 'example', 1, true) end",
            "  return loader[name] == true",
            "end",
            "function loaderin(class, name)",
            "  assert(class == 'android.webkit.WebViewFactory' and name == 'sProviderInstance', class .. '.' .. name)",
            "  if loaders.failing then error('Not listed') end",
            "  return loaders.webview",
            "end",
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

        // WebView, of which the classes are not of the boot class path, gets the touchscreen features it counts the touch
        // points of pages by, once it is loaded; before, or when its class loader cannot be looked up, it gets false
        String touch = "'android.hardware.touchscreen.multitouch.jazzhand'";
        String webView = "'org.chromium.ui.base.TouchDevice.maxTouchPoints', 'J.N.MZ', " +
                "'org.chromium.android_webview.AwContents.<init>', 'android.webkit.WebView.<init>'";
        Scripts.run(globals, "stack = stackof(" + engine + webView + ")");
        assertTrue(Scripts.run(globals, has + "true, " + touch + ", nil))").arg1().toboolean());
        Scripts.run(globals, "loaders.webview = { ['org.chromium.ui.base.TouchDevice'] = true, " +
                "['org.chromium.base.BuildInfo'] = true }\n" +
                "loaders.app = { ['org.chromium.base.BuildInfo'] = true }\n" +
                "loaders.failing = true");
        assertTrue(Scripts.run(globals, has + "true, " + touch + ", nil))").arg1().toboolean());
        Scripts.run(globals, "loaders.failing = nil");
        assertFalse(Scripts.run(globals, has + "true, " + touch + ", nil))").arg1().toboolean());
        Scripts.run(globals, "stack = stackof(" + engine + "'android.app.ApplicationPackageManager.hasSystemFeature', " +
                webView + ")");
        assertFalse(Scripts.run(globals, has + "true, 'android.hardware.touchscreen', nil))").arg1().toboolean());
        Scripts.run(globals, "stack = stackof(" + engine.replace(".hasSystemFeature'", ".getSystemAvailableFeatures'") +
                webView + ")");
        result = Scripts.run(globals, "local param = featureparamof(featuresof(featureof('android.hardware.wifi'), " +
                "featureof(nil)), nil, nil)\n" +
                "local restricted = after(hookof('PackageManager.getSystemAvailableFeatures', 'getSystemAvailableFeatures'), param)\n" +
                "return restricted, names(param.result)");
        assertFalse(result.arg1().toboolean());
        assertEquals("android.hardware.wifi,nil", result.arg(2).tojstring());
        // A class of the app with the name of one of WebView, like of Chromium of its own, and a class of code
        // which neither loads, like code the app loads itself, are not WebView
        for (String caller : new String[]{"org.chromium.base.BuildInfo.getInstance", "com.example.dynamic.Check.run"}) {
            Scripts.run(globals, "stack = stackof(" + engine + "'" + caller + "', 'android.app.Activity.performCreate')");
            assertTrue(caller, Scripts.run(globals, has + "true, " + touch + ", nil))").arg1().toboolean());
        }
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

    // Static fields for the lookup of the class loader of WebView, which runs on the JVM (see classLoadersAreLookedUp)
    @SuppressWarnings("unused")
    private static final Object PROVIDER = new Object() {
    };
    @SuppressWarnings("unused")
    private static final Object NO_PROVIDER = null;

    // The lookups of webviewclass on classes of the JVM: the class loader of the object in a static field, which is
    // found by listing the fields, and whether a class loader finds a class
    @Test
    public void classLoadersAreLookedUp() throws Exception {
        Globals globals = load("features");
        String test = "'" + DeviceValuesScriptTest.class.getName() + "'";
        ClassLoader loader = DeviceValuesScriptTest.class.getClassLoader();
        assertTrue(Scripts.run(globals, "return loaderin(" + test + ", 'PROVIDER')").arg1().touserdata() == loader);
        assertTrue(Scripts.run(globals, "return loaderin(" + test + ", 'NO_PROVIDER')").arg1().isnil());
        assertTrue(Scripts.run(globals, "return loaderin(" + test + ", 'MISSING')").arg1().isnil());
        assertTrue(Scripts.run(globals, "return finds(" + test + ", loaderin(" + test + ", 'PROVIDER'))").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return finds('com.example.app.Main', loaderin(" + test + ", 'PROVIDER'))")
                .arg1().toboolean());
        assertTrue(Scripts.run(globals, "return finds('java.lang.String', nil)").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return finds(" + test + ", nil)").arg1().toboolean());
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

    // The levels of a low battery of Android 14 in the resources of the system; no level set for battery saver,
    // since the context has no ContentResolver (see IntentScriptTest.batteryLevelKeepsTheWarnings)
    private static final String BATTERY_LEVELS = "local real = luajava.bindClass\n" +
            "local levels = { config_lowBatteryWarningLevel = 20, config_criticalBatteryWarningLevel = 10,\n" +
            "  config_lowBatteryCloseWarningBump = 5 }\n" +
            "luajava.bindClass = function(name)\n" +
            "  if name ~= 'android.content.res.Resources' then return real(name) end\n" +
            "  return { getSystem = function(self) return { getIdentifier = function(self, name) return name end,\n" +
            "    getInteger = function(self, id) return levels[id] end } end }\n" +
            "end";

    @Test
    public void batteryCapacityIsRounded() throws Exception {
        Globals globals = load("battery");
        Scripts.run(globals, BATTERY_LEVELS);
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
        // On the side of the warning level (20) and the close level (25) of the real capacity, like BATTERY_CHANGED
        for (int[] level : new int[][]{{15, 15}, {18, 20}, {22, 21}, {25, 25}, {26, 30}})
            assertEquals(String.valueOf(level[0]), level[1], Scripts.run(globals, "return run(hookof(" +
                    "'BatteryManager.getIntProperty', 'getIntProperty'), paramof(" + level[0] + ", { [0] = 4 }))").arg(2).toint());
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

    // The design capacity of PowerProfile is the one of the default profile of Android, also with a default the caller
    // gives when the item is there, even with its value; a profile without it and other values of the power profile stay
    @Test
    public void batteryDesignCapacityIsTheDefault() throws Exception {
        Globals globals = load("battery");
        // The capacity item of the profile (nil: none) as getAveragePower with a level gives it, asked without the hooks
        Scripts.run(globals, "function profile(method, result, arguments, item)\n" +
                "  local param = paramof(result, arguments)\n" +
                "  param.getThis = function(self) return { getAveragePower = function(self, type, level)\n" +
                "    assert(type == 'battery.capacity' and level == 0, 'item') assert(param.inside, 'hooked')\n" +
                "    return item or 0 end } end\n" +
                "  param.unhooked = function(self, fn) param.inside = true local value = fn() param.inside = false return value end\n" +
                "  return run(hookof('PowerProfile.' .. method, method, 'com.android.internal.os.PowerProfile'), param)\n" +
                "end");
        String[][] cases = {
                {"'getBatteryCapacity', 4500.0, {}, 4500.0", "true", "1000"},
                {"'getAveragePower', 4500.0, { [0] = 'battery.capacity', [1] = 3 }, 4500.0", "true", "1000"},
                {"'getAveragePowerOrDefault', 4500.0, { [0] = 'battery.capacity', [1] = 5000.0 }, 4500.0", "true", "1000"},
                // The item has the value of the default
                {"'getAveragePowerOrDefault', 5000.0, { [0] = 'battery.capacity', [1] = 5000.0 }, 5000.0", "true", "1000"},
                {"'getAveragePower', 110.5, { [0] = 'cpu.active' }, 4500.0", "false", "110.5"},
                // A profile without the capacity stays: 0, or the default of the caller
                {"'getBatteryCapacity', 0.0, {}, nil", "false", "0"},
                {"'getAveragePowerOrDefault', 5000.0, { [0] = 'battery.capacity', [1] = 5000.0 }, nil", "false", "5000"},
                // An item of 0 stays like for getBatteryCapacity
                {"'getAveragePowerOrDefault', 0.0, { [0] = 'battery.capacity', [1] = 5000.0 }, 0.0", "false", "0"},
        };
        for (String[] test : cases) {
            Varargs result = Scripts.run(globals, "return profile(" + test[0] + ")");
            assertEquals(test[0], Boolean.parseBoolean(test[1]), result.arg1().toboolean());
            assertEquals(test[0], Double.parseDouble(test[2]), result.arg(2).todouble(), 0);
        }
    }

    // The text of the design capacity in the framework resource power_profile is the default capacity too, for each
    // way the parser reads a text, by the line the item has in a parser of its own of the same file, read without the
    // hooks; the same text elsewhere, other resources, unmarked parsers, an item of 0 or of the fake and a profile
    // without it stay
    @Test
    public void batteryDesignCapacityOfTheResourceIsTheDefault() throws Exception {
        Globals globals = load("battery");
        // The profile as events of a parser (2 START_TAG, 4 TEXT, 3 END_TAG) with their lines; the text of the
        // capacity (nil: no item) ends on the next line, the same text of another item is on the line after
        Scripts.run(globals, "ID = 0x01170013 VALUES = {} SCANS = 0\n" +
                "function profile(capacity)\n" +
                "  PROFILE = { { 2, 1, 'device' } }\n" +
                "  local items = { { 2, 5, 'item', 'cpu.idle' }, { 4, 5, '4500.0' }, { 3, 5, 'item' }, { 3, 6, 'device' } }\n" +
                "  if capacity then\n" +
                "    PROFILE = { PROFILE[1], { 2, 3, 'item', 'battery.capacity' }, { 4, 3, capacity }, { 3, 4, 'item' } }\n" +
                "  end\n" +
                "  for _, event in ipairs(items) do PROFILE[#PROFILE + 1] = event end\n" +
                "end\n" +
                "local real = luajava.bindClass\n" +
                "luajava.bindClass = function(name)\n" +
                "  if name ~= 'android.content.res.Resources' then return real(name) end\n" +
                "  return { getSystem = function(self) return {\n" +
                "    getIdentifier = function(self, name, type, package)\n" +
                "      return (name == 'power_profile' and type == 'xml' and package == 'android') and ID or 0 end } end }\n" +
                "end\n" +
                // The file of the call in the assets of the ResourcesImpl, not a lookup in the resources of the system
                "IMPL = { getAssets = function(self) return {\n" +
                "    openXmlResourceParser = function(self, cookie, file)\n" +
                "      assert(cookie == 7 and file == 'res/xml/power_profile.xml' and INSIDE, 'scan') SCANS = SCANS + 1\n" +
                "      local index, parser = 0, {}\n" +
                "      local function at() return PROFILE[index] or { 1, 0 } end\n" +
                "      parser.next = function(self) index = index + 1 return at()[1] end\n" +
                "      parser.getName = function(self) return at()[3] end\n" +
                "      parser.getAttributeValue = function(self, ns, name)\n" +
                "        assert(ns == nil and name == 'name') return at()[1] == 2 and at()[4] or nil end\n" +
                "      parser.getText = function(self) return at()[1] == 4 and at()[3] or nil end\n" +
                "      parser.getLineNumber = function(self) return at()[2] end\n" +
                "      parser.close = function(self) CLOSED = true end\n" +
                "      return parser end } end }\n" +
                "function valued(param)\n" +
                "  param.putValue = function(self, name, value, scope) VALUES[scope] = { [name] = value } end\n" +
                "  param.getValue = function(self, name, scope) return VALUES[scope] and VALUES[scope][name] end\n" +
                "  param.unhooked = function(self, fn) INSIDE = true local value = fn() INSIDE = false return value end\n" +
                "  return param\n" +
                "end\n" +
                "function loadxml(parser, id)\n" +
                "  local param = valued(paramof(parser, { [0] = 'res/xml/power_profile.xml', [1] = id, [2] = 7, [3] = 'xml' }))\n" +
                "  param.getThis = function(self) return IMPL end\n" +
                "  return after(hookof('ResourcesImpl.loadXmlResourceParser/power_profile', 'loadXmlResourceParser',\n" +
                "    'android.content.res.ResourcesImpl'), param)\n" +
                "end\n" +
                "function chars(text)\n" +
                "  local builder = luajava.newInstance('java.lang.StringBuilder', text)\n" +
                "  local array = luajava.bindClass('java.lang.reflect.Array'):newInstance(\n" +
                "    luajava.bindClass('java.lang.Character').TYPE, builder:length())\n" +
                "  builder:getChars(0, builder:length(), array, 0)\n" +
                "  return array\n" +
                "end\n" +
                "function readtext(parser, method, line, text)\n" +
                "  parser.getLineNumber = function(self) return line end\n" +
                "  local param = valued(paramof(text, { [0] = HOLDER }))\n" +
                "  param.getThis = function(self) return parser end\n" +
                "  local restricted = after(hookof('XmlBlock.Parser.' .. method .. '/power_profile', method,\n" +
                "    'android.content.res.XmlBlock$Parser'), param)\n" +
                "  return restricted, param.result\n" +
                "end");
        // Only the file of the power profile runs the script, which other XML resources would else run
        assertEquals("*power_profile*", Scripts.run(globals, "return filter(hookof('ResourcesImpl.loadXmlResourceParser" +
                "/power_profile', 'loadXmlResourceParser'))[1]").arg1().tojstring());
        assertTrue(Scripts.run(globals, "return filter(hookof('XmlBlock.Parser.getText/power_profile', 'getText'))")
                .arg1().isnil());
        assertTrue(Scripts.run(globals, "return filter(hookof('BatteryManager.getIntProperty', 'getIntProperty'))")
                .arg1().isnil());

        // A parser of the resource of the framework is marked, one of a resource of the app with the name is not
        Scripts.run(globals, "profile('4500.0') APP = {} OWN = {} assert(not loadxml(APP, ID)) assert(not loadxml(OWN, 0x7f0f0001))");
        assertEquals(1, Scripts.run(globals, "return SCANS").arg1().toint());
        assertTrue(Scripts.run(globals, "return CLOSED").arg1().toboolean());
        // Also by the definition with the flag of feature flags of Android 16 and 17
        Scripts.run(globals, "FLAGS = {}\n" +
                "local param = valued(paramof(FLAGS, { [0] = 'res/xml/power_profile.xml', [1] = ID, [2] = 7, [3] = 'xml',\n" +
                "  [4] = true }))\n" +
                "param.getThis = function(self) return IMPL end\n" +
                "assert(not after(hookof('ResourcesImpl.loadXmlResourceParser.flags/power_profile', 'loadXmlResourceParser',\n" +
                "  'android.content.res.ResourcesImpl'), param))");
        assertTrue(Scripts.run(globals, "return readtext(FLAGS, 'getText', 3, '4500.0')").arg1().toboolean());
        String[][] cases = {
                {"APP, 'getText', 3, '4500.0'", "true", "1000"},
                {"APP, 'nextText', 4, '4500.0'", "true", "1000"},
                // The same text of another item, the text on the line of the end, and the end on the line of the text
                {"APP, 'getText', 5, '4500.0'", "false", "4500.0"},
                {"APP, 'getText', 4, '4500.0'", "false", "4500.0"},
                {"APP, 'nextText', 3, '4500.0'", "false", "4500.0"},
                // The fake given by getText inside nextText, and a parser of another resource
                {"APP, 'nextText', 4, '1000'", "false", "1000"},
                {"OWN, 'getText', 3, '4500.0'", "false", "4500.0"},
                {"{}, 'getText', 3, '4500.0'", "false", "4500.0"},
        };
        for (String[] test : cases) {
            Varargs result = Scripts.run(globals, "return readtext(" + test[0] + ")");
            assertEquals(test[0], Boolean.parseBoolean(test[1]), result.arg1().toboolean());
            assertEquals(test[0], test[2], result.arg(2).tojstring());
        }
        // The characters with the start and length of the fake in the array the app gives
        Varargs chars = Scripts.run(globals, "HOLDER = luajava.bindClass('java.lang.reflect.Array'):newInstance(\n" +
                "  luajava.bindClass('java.lang.Integer').TYPE, 2) HOLDER[2] = 6\n" +
                "local restricted, result = readtext(APP, 'getTextCharacters', 3, chars('4500.0'))\n" +
                "return restricted, luajava.newInstance('java.lang.String', result), HOLDER[1], HOLDER[2]");
        assertTrue(chars.arg1().toboolean());
        assertEquals("1000", chars.arg(2).tojstring());
        assertEquals(0, chars.arg(3).toint());
        assertEquals(4, chars.arg(4).toint());
        assertFalse(Scripts.run(globals, "return readtext(APP, 'getTextCharacters', 3, chars('1000'))").arg1().toboolean());

        // An item of 0 or of the fake, and a profile without it, mark nothing
        for (String capacity : new String[]{"'0'", "' 0.0 '", "'1000'", "'1000.0'", "nil"}) {
            Scripts.run(globals, "profile(" + capacity + ") OTHER = {} loadxml(OTHER, ID)");
            assertFalse(capacity, Scripts.run(globals, "return readtext(OTHER, 'getText', 3, '0')").arg1().toboolean());
            assertTrue(capacity, Scripts.run(globals, "return VALUES[OTHER] == nil").arg1().toboolean());
        }
    }

    // The time until the battery is full: unknown stays, else a minute per percent from the level apps see (see
    // batteryCapacityIsRounded), which is read without the hooks; unknown when the level cannot be read
    @Test
    public void chargeTimeIsTypical() throws Exception {
        Globals globals = load("battery");
        Scripts.run(globals, BATTERY_LEVELS);
        Scripts.run(globals, "function charging(result, level)\n" +
                "  local param = paramof(result, {})\n" +
                "  param.getThis = function(self) return { getLongProperty = function(self, id)\n" +
                "    assert(id == 4, 'id') assert(param.inside, 'hooked') return level end } end\n" +
                "  param.unhooked = function(self, fn) param.inside = true local value = fn() param.inside = false return value end\n" +
                "  return run(hookof('BatteryManager.computeChargeTimeRemaining', 'computeChargeTimeRemaining'), param)\n" +
                "end");
        assertFalse(Scripts.run(globals, "return charging(-1, 57)").arg1().toboolean());
        // Half a step rounds down and the warning levels (20 and 25) keep their side, like getIntProperty
        long[][] cases = {{57, 40 * 60000L}, {55, 50 * 60000L}, {22, 79 * 60000L}, {3, 85 * 60000L}, {97, 0},
                {Long.MIN_VALUE, -1}};
        for (long[] level : cases) {
            Varargs result = Scripts.run(globals, "return charging(1234567, " + level[0] + ")");
            assertTrue(String.valueOf(level[0]), result.arg1().toboolean());
            assertEquals(String.valueOf(level[0]), level[1], result.arg(2).tolong());
        }
        // The fake answer stays without a change
        assertFalse(Scripts.run(globals, "return charging(" + 40 * 60000L + ", 57)").arg1().toboolean());
    }

    // On battery the remaining time is the unknown one of BatteryStats, -1 ms, never null, which Android gives only while
    // powered, and so not personalized
    @Test
    public void batteryDischargePredictionIsUnknown() throws Exception {
        Globals globals = load("battery");
        String prediction = "return run(hookof('PowerManager.getBatteryDischargePrediction', 'getBatteryDischargePrediction'), " +
                "paramof(%s, {}))";
        Varargs result = Scripts.run(globals, String.format(prediction, "luajava.bindClass('java.time.Duration'):ofMinutes(250)"));
        assertTrue(result.arg1().toboolean());
        assertEquals(Duration.ofMillis(-1), result.arg(2).checkuserdata(Duration.class));
        assertFalse(Scripts.run(globals, String.format(prediction, "nil")).arg1().toboolean());
        assertFalse(Scripts.run(globals, String.format(prediction, "luajava.bindClass('java.time.Duration'):ofMillis(-1)"))
                .arg1().toboolean());

        String personalized = "return run(hookof('PowerManager.isBatteryDischargePredictionPersonalized', " +
                "'isBatteryDischargePredictionPersonalized'), paramof(%s, {}))";
        result = Scripts.run(globals, String.format(personalized, "true"));
        assertTrue(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        assertFalse(Scripts.run(globals, String.format(personalized, "false")).arg1().toboolean());
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
    }

    // ActivityManager.getMemoryInfo and the binder proxy fake the info through its parcel, which has the thresholds the
    // runtime hides from apps that target Android 12 or later, with the advertised size first from Android 14: the total
    // and the thresholds grow like the total, the free memory is the one of the faked /proc/meminfo, MemFree and Cached,
    // MemAvailable from Android 16, rounded when the file cannot be read, and low memory follows them like
    // ProcessList.getMemoryInfo computes it: below the middle between the thresholds of the home app and of the cached
    // apps. The second hook of one call keeps what the first faked. A parcel of another layout and a subclass of the app,
    // whose parcel methods are not called, get their fields faked, low memory by the middle when the hidden thresholds are
    // fields, else at least below the threshold of the home app; a call which threw or did not fill the info keeps it.
    @Test
    public void memoryInfoIsFakedThroughItsParcel() throws Exception {
        Globals globals = load("resources");
        Scripts.run(globals, "local prior = luajava.bindClass\n" +
                "luajava.bindClass = function(name)\n" +
                "  if name == 'android.os.Build$VERSION' then return { SDK_INT = SDK } end\n" +
                "  if name == 'android.os.Parcel' then return prior('eu.faircode.xlua.DeviceValuesScriptTest$Parcel') end\n" +
                "  if name == 'android.app.ActivityManager$MemoryInfo' then\n" +
                "    return prior('eu.faircode.xlua.DeviceValuesScriptTest$MemoryInfo')\n" +
                "  end\n" +
                "  return prior(name)\n" +
                "end\n" +
                "function getmemoryinfo(name, text, exception)\n" +
                "  local param = paramof(nil, { [0] = info })\n" +
                "  param.readFile = function(self, path) assert(path == '/proc/meminfo') return text end\n" +
                "  param.getException = function(self) return exception end\n" +
                "  return after(hookof(name, 'getMemoryInfo'), param)\n" +
                "end");
        String text = String.join("\n",
                "MemTotal:        2537472 kB",
                "MemFree:           60000 kB",
                "MemAvailable:     300000 kB",
                "Cached:           200000 kB",
                "");
        globals.set("text", text);
        // 2.42 GiB is sold as 3 GiB; the real free memory, MemFree and Cached, is above the middle of the real thresholds
        double scale = 3221225472.0 / 2598371328L;
        long home = (long) Math.floor(226492416 * scale), cached = (long) Math.floor(301989888 * scale);
        assertFalse(266240000L < 226492416L + (301989888L - 226492416L) / 2);
        for (int version : new int[]{33, 34, 35, 36}) {
            MemoryInfo info = new MemoryInfo(version >= 34);
            globals.set("SDK", version);
            globals.set("info", CoerceJavaToLua.coerce(info));
            Varargs result = Scripts.run(globals, "return getmemoryinfo('IActivityManager.getMemoryInfo', text), " +
                    "getmemoryinfo('ActivityManager.getMemoryInfo', text)");
            assertTrue(result.arg1().toboolean());
            assertFalse(result.arg(2).toboolean());
            assertEquals(version >= 34 ? 3221225472L : 2684354560L, info.advertisedMem);
            assertEquals(3221225472L, info.totalMem);
            // 59570 kB of MemFree in the faked file, 292968 kB of MemAvailable
            long avail = (version >= 36 ? 292968L : 59570L + 200000) * 1024;
            assertEquals(avail, info.availMem);
            assertEquals(home, info.threshold);
            assertArrayEquals(new long[]{cached, (long) Math.floor(150994944 * scale), (long) Math.floor(113246208 * scale)},
                    info.hidden);
            assertEquals((long) Math.floor(75497472 * scale), info.foregroundAppThreshold);
            assertTrue(avail < home + (cached - home) / 2);
            assertTrue(info.lowMemory);
        }

        MemoryInfo info = new MemoryInfo(false);
        globals.set("SDK", 33);
        globals.set("info", CoerceJavaToLua.coerce(info));
        assertTrue(Scripts.run(globals, "return getmemoryinfo('ActivityManager.getMemoryInfo', nil)").arg1().toboolean());
        assertEquals(260000000L, info.availMem);
        assertTrue(info.lowMemory);

        // A parcel of another layout: the faked free memory is below the threshold of the home app, so low memory
        long[] real = {301989888, 150994944, 113246208};
        MemoryInfo other = new MemoryInfo(true);
        globals.set("info", CoerceJavaToLua.coerce(other));
        assertTrue(Scripts.run(globals, "return getmemoryinfo('ActivityManager.getMemoryInfo', text)").arg1().toboolean());
        assertEquals(3221225472L, other.advertisedMem);
        assertEquals(3221225472L, other.totalMem);
        assertEquals((59570L + 200000) * 1024, other.availMem);
        assertEquals(home, other.threshold);
        assertEquals((long) Math.floor(75497472 * scale), other.foregroundAppThreshold);
        assertArrayEquals(real, other.hidden);
        assertTrue(other.lowMemory);
        // With more cached memory, between the threshold of the home app and the middle: low memory stays without the
        // threshold of the cached apps, and follows the middle with it, of a subclass of an app that targets Android 11
        String more = text.replace("200000 kB", "233000 kB");
        long between = (59570L + 233000) * 1024;
        assertTrue(home < between && between < home + (cached - home) / 2);
        other = new MemoryInfo(true);
        globals.set("info", CoerceJavaToLua.coerce(other));
        globals.set("more", more);
        assertTrue(Scripts.run(globals, "return getmemoryinfo('ActivityManager.getMemoryInfo', more)").arg1().toboolean());
        assertEquals(between, other.availMem);
        assertFalse(other.lowMemory);
        AppMemoryInfo own = new AppMemoryInfo();
        globals.set("info", CoerceJavaToLua.coerce(own));
        assertTrue(Scripts.run(globals, "return getmemoryinfo('ActivityManager.getMemoryInfo', more)").arg1().toboolean());
        assertEquals(0, own.writes);
        assertEquals(0, own.reads);
        assertEquals(3221225472L, own.totalMem);
        assertEquals(between, own.availMem);
        assertEquals(home, own.threshold);
        assertEquals(cached, own.hiddenAppThreshold);
        assertTrue(own.lowMemory);

        // A subclass of the app whose readFromParcel reads nothing keeps the zeros of the call
        AppMemoryInfo empty = new AppMemoryInfo();
        empty.advertisedMem = empty.availMem = empty.totalMem = empty.threshold = empty.foregroundAppThreshold = 0;
        empty.hiddenAppThreshold = 0;
        globals.set("info", CoerceJavaToLua.coerce(empty));
        assertFalse(Scripts.run(globals, "return getmemoryinfo('ActivityManager.getMemoryInfo', more)").arg1().toboolean());
        assertEquals(0, empty.writes + empty.reads);
        assertEquals(0, empty.advertisedMem + empty.availMem + empty.totalMem + empty.threshold + empty.foregroundAppThreshold
                + empty.hiddenAppThreshold);
        assertFalse(empty.lowMemory);

        // A call which threw, like when the system server died
        MemoryInfo failed = new MemoryInfo(false);
        globals.set("info", CoerceJavaToLua.coerce(failed));
        globals.set("exception", CoerceJavaToLua.coerce(new RuntimeException("DeadObjectException")));
        assertFalse(Scripts.run(globals, "return getmemoryinfo('IActivityManager.getMemoryInfo', text, exception)")
                .arg1().toboolean());
        assertEquals(2598371328L, failed.totalMem);
        assertEquals(266240000L, failed.availMem);
        assertEquals(226492416L, failed.threshold);
        assertArrayEquals(real, failed.hidden);
        assertFalse(failed.lowMemory);
    }

    // A parcel of the longs and ints written to it, in their order, like android.os.Parcel, of which the script uses
    // only the start
    public static class Parcel {
        private final List<Number> values = new ArrayList<>();
        private int position;

        public static Parcel obtain() {
            return new Parcel();
        }

        public void setDataPosition(int position) {
            this.position = position;
        }

        public void writeLong(long value) {
            write(value);
        }

        public void writeInt(int value) {
            write(value);
        }

        public long readLong() {
            return (Long) this.values.get(this.position++);
        }

        public int readInt() {
            return (Integer) this.values.get(this.position++);
        }

        public void recycle() {
        }

        private void write(Number value) {
            if (this.position < this.values.size())
                this.values.set(this.position, value);
            else
                this.values.add(value);
            this.position++;
        }
    }

    // ActivityManager.MemoryInfo of an app which targets Android 12 or later: the runtime hides the thresholds of the
    // cached apps, the secondary server and the visible apps, so they are no fields for the script, only in the parcel,
    // which starts with the advertised size from Android 14
    public static class MemoryInfo {
        public long advertisedMem = 2684354560L, availMem = 266240000, totalMem = 2598371328L, threshold = 226492416;
        public long foregroundAppThreshold = 75497472;
        public boolean lowMemory = false;
        private final long[] hidden = {301989888, 150994944, 113246208};
        private final boolean advertised;

        MemoryInfo(boolean advertised) {
            this.advertised = advertised;
        }

        public void writeToParcel(Parcel dest, int flags) {
            if (this.advertised)
                dest.writeLong(this.advertisedMem);
            dest.writeLong(this.availMem);
            dest.writeLong(this.totalMem);
            dest.writeLong(this.threshold);
            dest.writeInt(this.lowMemory ? 1 : 0);
            for (long value : this.hidden)
                dest.writeLong(value);
            dest.writeLong(this.foregroundAppThreshold);
        }

        public void readFromParcel(Parcel source) {
            if (this.advertised)
                this.advertisedMem = source.readLong();
            this.availMem = source.readLong();
            this.totalMem = source.readLong();
            this.threshold = source.readLong();
            this.lowMemory = source.readInt() != 0;
            for (int i = 0; i < this.hidden.length; i++)
                this.hidden[i] = source.readLong();
            this.foregroundAppThreshold = source.readLong();
        }
    }

    // MemoryInfo of an app's own subclass, made for Android 11, which sees the threshold of the cached apps, and whose
    // parcel methods count their calls and do nothing
    public static class AppMemoryInfo extends MemoryInfo {
        public long hiddenAppThreshold = 301989888;
        int writes, reads;

        AppMemoryInfo() {
            super(false);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            this.writes++;
        }

        @Override
        public void readFromParcel(Parcel source) {
            this.reads++;
        }
    }

    // A disk of StorageManager, made of its parcel: an SD card of 64 GB has the size of the totals, a small USB disk keeps
    // its size like a small file system
    @Test
    public void diskSizesAreRounded() throws Exception {
        Globals globals = load("resources");
        Varargs result = Scripts.run(globals,
                "local hook = hookof('DiskInfo.createFromParcel', 'CREATOR:createFromParcel')\n" +
                        "local card, stick = { size = 63864569856 }, { size = 511705088 }\n" +
                        "local restricted, old, new = after(hook, paramof(card, {}))\n" +
                        "return restricted, old, new, card.size, after(hook, paramof(stick, {})), stick.size,\n" +
                        "  after(hook, paramof(nil, {}))");
        assertTrue(result.arg(1).toboolean());
        assertEquals("63864569856", result.arg(2).tojstring());
        assertEquals("64000000000", result.arg(3).tojstring());
        assertEquals(64000000000L, result.arg(4).tolong());
        assertFalse(result.arg(5).toboolean());
        assertEquals(511705088L, result.arg(6).tolong());
        assertFalse(result.arg(7).toboolean());
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

    // The hidden natives behind the sizes, which apps reach by reflection, give the sizes of getMemoryInfo and of
    // /proc/meminfo: Process.getTotalMemory and getFreeMemory, which keep -1 when they fail, with the free memory of the
    // faked file, MemFree and Cached, MemAvailable from Android 16, or rounded when the file cannot be read,
    // Debug.getMemInfo in kB at the indexes of Debug.MEMINFO_*, with the available memory from Android 14 and also an
    // array of Android 8.1 shorter than all of them, readProcLines of /proc/meminfo, the faked file parsed like Android,
    // and readProcFile, the faked file, before Android 10 its first 255 bytes, parsed by Process.parseProcLine
    @Test
    public void memoryNativesAreFaked() throws Exception {
        Globals globals = load("resources");
        Varargs result = Scripts.run(globals, "local total, free = paramof(4109369344, {}), paramof(1234567890, {})\n" +
                "free.readFile = function(self, path) return nil end\n" +
                "local failed, nothing = paramof(-1, {}), paramof(-1, {})\n" +
                "return after(hookof('Process.getTotalMemory', 'getTotalMemory'), total), total.result,\n" +
                "  after(hookof('Process.getFreeMemory', 'getFreeMemory'), free), free.result,\n" +
                "  after(hookof('Process.getTotalMemory', 'getTotalMemory'), failed), failed.result,\n" +
                "  after(hookof('Process.getFreeMemory', 'getFreeMemory'), nothing), nothing.result,\n" +
                "  filter(hookof('Debug.getMemInfo', 'getMemInfo')) == nil");
        assertTrue(result.arg(1).toboolean());
        assertEquals(4294967296L, result.arg(2).tolong());
        assertTrue(result.arg(3).toboolean());
        assertEquals(1200000000L, result.arg(4).tolong());
        assertFalse(result.arg(5).toboolean());
        assertEquals(-1L, result.arg(6).tolong());
        assertFalse(result.arg(7).toboolean());
        assertEquals(-1L, result.arg(8).tolong());
        // The array is no path, so the hook has no filter
        assertTrue(result.arg(9).toboolean());

        String sdk = "local prior = luajava.bindClass\n" +
                "luajava.bindClass = function(name)\n" +
                "  if name == 'android.os.Build$VERSION' then return { SDK_INT = SDK } end\n" +
                "  return prior(name)\n" +
                "end\n";
        Scripts.run(globals, sdk);
        String meminfo = String.join("\n",
                "MemTotal:        4013056 kB",
                "MemFree:          147624 kB",
                "MemAvailable:     551824 kB",
                "Cached:          1500000 kB",
                "");
        for (int version : new int[]{35, 36}) {
            globals.set("SDK", version);
            result = Scripts.run(globals, "local free = paramof(1234567890, {})\n" +
                    "free.readFile = function(self, path) assert(path == '/proc/meminfo') return [[" + meminfo + "]] end\n" +
                    "return after(hookof('Process.getFreeMemory', 'getFreeMemory'), free), free.result");
            assertTrue(result.arg1().toboolean());
            // 146484 kB of MemFree and 546875 kB of MemAvailable in the faked file
            assertEquals((version >= 36 ? 546875L : 146484L + 1500000) * 1024, result.arg(2).tolong());
        }

        // Total, free, cached and swap total of the real file in kB, and the available memory at index 19
        long swap = (long) Math.floor(1900580.0 * 1024 * (4294967296.0 / 4109369344L) / 1024);
        for (int version : new int[]{34, 33}) {
            long[] sizes = new long[26];
            sizes[0] = 4013056;
            sizes[1] = 147624;
            sizes[3] = 1500000;
            sizes[8] = 1900580;
            sizes[19] = 551824;
            globals.set("SDK", version);
            globals.set("sizes", CoerceJavaToLua.coerce(sizes));
            assertTrue(Scripts.run(globals, "return after(hookof('Debug.getMemInfo', 'getMemInfo'), paramof(nil, { [0] = sizes }))")
                    .arg1().toboolean());
            assertEquals(4194304L, sizes[0]);
            assertEquals(146484L, sizes[1]);
            assertEquals(1500000L, sizes[3]);
            assertEquals(swap, sizes[8]);
            assertEquals(version >= 34 ? 546875L : 551824L, sizes[19]);
        }
        long[] short81 = {4013056, 147624};
        globals.set("SDK", 27);
        globals.set("sizes", CoerceJavaToLua.coerce(short81));
        assertTrue(Scripts.run(globals, "return after(hookof('Debug.getMemInfo', 'getMemInfo'), paramof(nil, { [0] = sizes }))")
                .arg1().toboolean());
        assertArrayEquals(new long[]{4194304, 146484}, short81);

        // readProcLines: the first field a line starts with gets the number after it and its spaces, others keep 0,
        // like a name without its colon; a line the faked file leaves out gives 0, and the sizes after the fields stay
        String[] fields = {"MemTotal:", "Cached:", "RbinTotal:", "MemTotal", "MemFree: ", "HugePages_Total:"};
        long[] out = {4013056, 1500000, 409600, 0, 147624, 12, 7};
        globals.set("fields", CoerceJavaToLua.coerce(fields));
        globals.set("out", CoerceJavaToLua.coerce(out));
        String text = String.join("\n",
                "MemTotal:        4013056 kB",
                "MemFree:          147624 kB",
                "Cached:          1500000 kB",
                "RbinTotal:        409600 kB",
                "HugePages_Total:      12",
                "");
        result = Scripts.run(globals, "local strict = { allowThreadDiskReads = function(self) return 'policy' end,\n" +
                "  setThreadPolicy = function(self, policy) assert(policy == 'policy') restored = true end }\n" +
                "local prior = luajava.bindClass\n" +
                "luajava.bindClass = function(name)\n" +
                "  if name == 'android.os.StrictMode' then return strict end\n" +
                "  return prior(name)\n" +
                "end\n" +
                "local function call(path)\n" +
                "  local param = paramof(nil, { [0] = path, [1] = fields, [2] = out })\n" +
                "  param.opens = function(self, file) return path == '/proc/self/root/proc/meminfo' end\n" +
                "  param.readFile = function(self, file) assert(file == '/proc/meminfo') return [[" + text + "]] end\n" +
                "  return after(hookof('Process.readProcLines/meminfo', 'readProcLines'), param)\n" +
                "end\n" +
                "return filter(hookof('Process.readProcLines/meminfo', 'readProcLines'))[1], call('/data/meminfo'),\n" +
                "  call('/proc/self/root/proc/meminfo'), restored");
        assertEquals("*meminfo*", result.arg(1).tojstring());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertArrayEquals(new long[]{4194304, 1500000, 0, 0, 146484, 12, 7}, out);

        // readProcFile: the faked file, without the line of the maker, and the format and arrays of the app go to the
        // parser, of which the app gets the result; another file is not parsed again
        String[] lines = {"MemTotal:        4013056 kB", "MemFree:          147624 kB", "MemAvailable:     551824 kB",
                "Buffers:           20000 kB", "Cached:          1500000 kB", "SwapCached:            0 kB",
                "Active:           900000 kB", "Inactive:         800000 kB", "Active(anon):     400000 kB",
                "RbinTotal:        409600 kB", "Inactive(anon):   100000 kB", "Active(file):     500000 kB",
                "Inactive(file):   400000 kB"};
        String faked = String.join("\n", "MemTotal:        4194304 kB", "MemFree:          146484 kB",
                "MemAvailable:     546875 kB", lines[3], lines[4], lines[5], lines[6], lines[7], lines[8], lines[10],
                lines[11], lines[12], "");
        int[] format = {32, 8224, 10};
        globals.set("format", CoerceJavaToLua.coerce(format));
        globals.set("text", String.join("\n", lines) + "\n");
        for (int version : new int[]{29, 28}) {
            Process.buffer = null;
            globals.set("SDK", version);
            result = Scripts.run(globals, "local prior = luajava.bindClass\n" +
                    "luajava.bindClass = function(name)\n" +
                    "  if name == 'android.os.Process' then return prior('eu.faircode.xlua.DeviceValuesScriptTest$Process') end\n" +
                    "  return prior(name)\n" +
                    "end\n" +
                    "local function call(path)\n" +
                    "  local param = paramof(true, { [0] = path, [1] = format, [3] = out })\n" +
                    "  param.opens = function(self, file) return path == '/proc/meminfo' end\n" +
                    "  param.readFile = function(self, file) assert(file == '/proc/meminfo') return text end\n" +
                    "  return after(hookof('Process.readProcFile/meminfo', 'readProcFile'), param), param.result\n" +
                    "end\n" +
                    "local other, kept = call('/data/meminfo')\n" +
                    "local restricted, parsed = call('/proc/meminfo')\n" +
                    "luajava.bindClass = prior\n" +
                    "return filter(hookof('Process.readProcFile/meminfo', 'readProcFile'))[1], other, kept, restricted, parsed");
            assertEquals("*meminfo*", result.arg(1).tojstring());
            assertFalse(result.arg(2).toboolean());
            assertTrue(result.arg(3).toboolean());
            assertTrue(result.arg(4).toboolean());
            assertFalse(result.arg(5).toboolean());
            String expected = version >= 29 ? faked : faked.substring(0, 255);
            assertEquals(expected + "\0", new String(Process.buffer, StandardCharsets.US_ASCII));
            assertEquals(0, Process.start);
            assertEquals(expected.length(), Process.end);
            assertTrue(Process.format == format && Process.strings == null && Process.longs == out && Process.floats == null);
        }
    }

    // readProcLines of /proc/meminfo reads its first 2047 bytes on Android 8.1 and 4095 from Android 9, of the faked file,
    // which is the one the app can read: a field after the cut gives 0, a line the cut ends gives the digits before the
    // cut, and a field which only the lines of makers, which the faked file leaves out, put after the cut gives its size
    @Test
    public void procLinesAreCutLikeAndroid() throws Exception {
        Globals globals = load("resources");
        Scripts.run(globals, "local prior = luajava.bindClass\n" +
                "luajava.bindClass = function(name)\n" +
                "  if name == 'android.os.Build$VERSION' then return { SDK_INT = SDK } end\n" +
                "  return prior(name)\n" +
                "end\n" +
                "function readproclines(text)\n" +
                "  local param = paramof(nil, { [0] = '/proc/meminfo', [1] = fields, [2] = out })\n" +
                "  param.opens = function(self, file) return true end\n" +
                "  param.readFile = function(self, file) assert(file == '/proc/meminfo') return text end\n" +
                "  return after(hookof('Process.readProcLines/meminfo', 'readProcLines'), param)\n" +
                "end");
        globals.set("fields", CoerceJavaToLua.coerce(new String[]{"MemTotal:", "Cached:", "HugePages_Total:"}));
        StringBuilder kept = new StringBuilder("MemTotal:        4013056 kB\n");
        for (int i = 0; i < 71; i++)
            kept.append("Mapped:             1000 kB\n");
        kept.append("Buffers: 1\n").append("Cached:          1500000 kB\n").append("HugePages_Total:      12\n");
        // The faked total has as many digits, so the digits of Cached start at byte 2044 there too, of which the cut
        // keeps 150
        assertEquals(2044, kept.indexOf("1500000"));
        StringBuilder makers = new StringBuilder("MemTotal:        4013056 kB\n");
        for (int i = 0; i < 80; i++)
            makers.append("RbinTotal:        409600 kB\n");
        makers.append("Cached:          1500000 kB\n").append("HugePages_Total:      12\n");
        Object[][] cases = {{27, kept, new long[]{4194304, 150, 0}}, {28, kept, new long[]{4194304, 1500000, 12}},
                {27, makers, new long[]{4194304, 1500000, 12}}};
        for (Object[] c : cases) {
            long[] out = new long[3];
            globals.set("SDK", (Integer) c[0]);
            globals.set("out", CoerceJavaToLua.coerce(out));
            globals.set("real", c[1].toString());
            assertTrue(Scripts.run(globals, "return readproclines(real)").arg1().toboolean());
            assertArrayEquals("SDK " + c[0], (long[]) c[2], out);
        }
    }

    // Process.parseProcLine, which gets what readProcFile parses, and returns that the format ran off the end
    public static class Process {
        static byte[] buffer;
        static int start, end;
        static int[] format;
        static String[] strings;
        static long[] longs;
        static float[] floats;

        public static boolean parseProcLine(byte[] buffer, int start, int end, int[] format, String[] strings, long[] longs,
                                            float[] floats) {
            Process.buffer = buffer;
            Process.start = start;
            Process.end = end;
            Process.format = format;
            Process.strings = strings;
            Process.longs = longs;
            Process.floats = floats;
            return false;
        }
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

    // From Android 11 get(key) calls native_get(key), which calls native_get(key, ''), as get(key, default) calls
    // native_get(key, default) on all versions: the innermost hook fakes, the others see the fake; a property which is
    // not set gives the default, which stays
    @Test
    public void nativeGetWithDefaultIsFakedFirst() throws Exception {
        Globals globals = load("systemproperties_get");
        Varargs result = Scripts.run(globals,
                "PROPERTIES = { ['persist.sys.timezone'] = 'Europe/Berlin' }\n" +
                        "local hook = hookof('SystemProperties.native_get.default/timezone', 'native_get')\n" +
                        "local restricted, fake = run(hook, paramof('Europe/Berlin', { [0] = 'persist.sys.timezone', [1] = '' }))\n" +
                        "local native = run(hookof('SystemProperties.native_get/timezone', 'native_get'), " +
                        "paramof(fake, { [0] = 'persist.sys.timezone' }))\n" +
                        "local get = run(hookof('SystemProperties.get/timezone', 'get'), " +
                        "paramof(fake, { [0] = 'persist.sys.timezone' }))\n" +
                        "local defaulted, other = run(hook, paramof('Europe/Berlin', { [0] = 'persist.sys.timezone', [1] = 'd' }))\n" +
                        "local again = run(hookof('SystemProperties.get.default/timezone', 'get'), " +
                        "paramof(other, { [0] = 'persist.sys.timezone', [1] = 'd' }))\n" +
                        "PROPERTIES = {}\n" +
                        "local unset, empty = run(hook, paramof('', { [0] = 'persist.sys.timezone', [1] = '' }))\n" +
                        "local kept, default = run(hook, paramof('d', { [0] = 'persist.sys.timezone', [1] = 'd' }))\n" +
                        "return restricted, fake, native, get, defaulted, other, again, unset, empty, kept, default, " +
                        "filter(hook)[1]");
        assertTrue(result.arg(1).toboolean());
        assertEquals("UTC", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertEquals("UTC", result.arg(6).tojstring());
        assertFalse(result.arg(7).toboolean());
        assertFalse(result.arg(8).toboolean());
        assertEquals("", result.arg(9).tojstring());
        assertFalse(result.arg(10).toboolean());
        assertEquals("d", result.arg(11).tojstring());
        assertEquals("persist.sys.timezone", result.arg(12).tojstring());
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
        // BIOMETRIC_WEAK or BIOMETRIC_STRONG and DEVICE_CREDENTIAL become DEVICE_CREDENTIAL; biometrics only, the screen
        // lock only and authenticators which are an error stay, also a strength the service rejects (32771)
        String strings = "local param = biometricparamof(nil, %d)\n" +
                "local restricted = before(hookof('BiometricManager.getStrings', 'getStrings'), param)\n" +
                "return restricted, param:getArgument(0)";
        Varargs result;
        for (int authenticators : new int[]{33023, 32783}) {
            result = Scripts.run(globals, String.format(strings, authenticators));
            assertTrue(Integer.toString(authenticators), result.arg1().toboolean());
            assertEquals(32768, result.arg(2).toint());
        }
        for (int authenticators : new int[]{255, 32768, 33024, 32771}) {
            result = Scripts.run(globals, String.format(strings, authenticators));
            assertFalse(Integer.toString(authenticators), result.arg1().toboolean());
            assertEquals(authenticators, result.arg(2).toint());
        }
        // The time takes any bits of BIOMETRIC_STRONG, which the service reads as a fingerprint
        result = Scripts.run(globals, "local param = biometricparamof(nil, 32771)\n" +
                "local restricted = before(hookof('BiometricManager.getLastAuthenticationTime', 'getLastAuthenticationTime'), param)\n" +
                "return restricted, param:getArgument(0)");
        assertTrue(result.arg1().toboolean());
        assertEquals(32768, result.arg(2).toint());

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

    // A manager built with its service, like FingerprintManager, which keeps it in mService (see biometricHardwareIsAbsent)
    private static class Manager {
        private Object mService = new Object();
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
                "return names('getSystemService'), names('hasSystemFeature'), names('getSystemAvailableFeatures'),\n" +
                "  names('getService'), names('checkService'), names('listServices')");
        assertEquals("face,fingerprint,iris", result.arg(1).tojstring());
        assertEquals("android.hardware.biometrics.face,android.hardware.biometrics.iris,android.hardware.fingerprint",
                result.arg(2).tojstring());
        assertTrue(result.arg(3).isnil());
        assertEquals("biometric,face,fingerprint,iris", result.arg(4).tojstring());
        assertEquals("biometric,face,fingerprint,iris", result.arg(5).tojstring());
        assertTrue(result.arg(6).isnil());

        // ServiceManager has no binders of the services, for any target: the managers of older apps get none either.
        // BiometricService runs with the hardware only on Android 10, later always.
        Scripts.run(globals, "local real = luajava.bindClass\n" +
                "luajava.bindClass = function(name)\n" +
                "  if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end\n" +
                "  return real(name)\n" +
                "end\n" +
                "sdk = 30");
        // The IServiceManager behind it (ServiceManagerProxy) has none either
        for (String hook : new String[]{"ServiceManager.getService", "ServiceManager.checkService", "ServiceManagerProxy.getService",
                "ServiceManagerProxy.checkService"}) {
            String method = hook.substring(hook.indexOf('.') + 1);
            String lookup = "return run(hookof('" + hook + "/biometrics', '" + method + "'), paramof(%s, { [0] = '%s' }))";
            result = Scripts.run(globals, String.format(lookup, "'binder'", "fingerprint"));
            assertTrue(method, result.arg1().toboolean());
            assertTrue(method, result.arg(2).isnil());
            assertFalse(method, Scripts.run(globals, String.format(lookup, "'binder'", "biometric")).arg1().toboolean());
            assertFalse(method, Scripts.run(globals, String.format(lookup, "'binder'", "auth")).arg1().toboolean());
            assertFalse(method, Scripts.run(globals, String.format(lookup, "nil", "face")).arg1().toboolean());
            result = Scripts.run(globals, "sdk = 29\n" + String.format(lookup, "'binder'", "biometric"));
            assertTrue(method, result.arg1().toboolean());
            assertTrue(method, result.arg(2).isnil());
            assertFalse(method, Scripts.run(globals, String.format(lookup, "nil", "biometric")).arg1().toboolean());
            Scripts.run(globals, "sdk = 30");
        }

        // The names ServiceManager lists, without the services of the hardware and its HALs, as a new String[];
        // BiometricService only on Android 10
        String list = "local param = paramof(%s, {})\n" +
                "local restricted, old, new = after(hookof('ServiceManager.listServices/biometrics', 'listServices'), param)\n" +
                "return restricted, param.result, old, new";
        globals.set("listed", CoerceJavaToLua.coerce(new String[]{"window", "fingerprint", "biometric", "auth",
                "android.hardware.biometrics.fingerprint.IFingerprint/default", "face", "android.hardware.light.ILights/default"}));
        result = Scripts.run(globals, String.format(list, "listed"));
        assertTrue(result.arg1().toboolean());
        assertArrayEquals(new String[]{"window", "biometric", "auth", "android.hardware.light.ILights/default"},
                (String[]) result.arg(2).checkuserdata(String[].class));
        assertEquals("7", result.arg(3).tojstring());
        assertEquals("4", result.arg(4).tojstring());
        result = Scripts.run(globals, "sdk = 29\n" + String.format(list, "listed"));
        assertArrayEquals(new String[]{"window", "auth", "android.hardware.light.ILights/default"},
                (String[]) result.arg(2).checkuserdata(String[].class));
        Scripts.run(globals, "sdk = 30");
        // A list without them stays the same array, no list stays none
        String[] none = {"window", "biometric"};
        globals.set("listed", CoerceJavaToLua.coerce(none));
        result = Scripts.run(globals, String.format(list, "listed"));
        assertFalse(result.arg1().toboolean());
        assertSame(none, result.arg(2).touserdata());
        assertFalse(Scripts.run(globals, String.format(list, "nil")).arg1().toboolean());
        // The IServiceManager lists them the same, from Android 9 for the priority in the argument
        globals.set("listed", CoerceJavaToLua.coerce(new String[]{"window", "face", "iris"}));
        result = Scripts.run(globals, "local param = paramof(listed, { [0] = 15 })\n" +
                "local restricted = after(hookof('ServiceManagerProxy.listServices.priority/biometrics', 'listServices'), param)\n" +
                "return restricted, param.result");
        assertTrue(result.arg1().toboolean());
        assertArrayEquals(new String[]{"window"}, (String[]) result.arg(2).checkuserdata(String[].class));

        // No manager for apps targeting Android 8 or later, before only no iris manager, and the one of BiometricService stays
        String service = "return run(hookof('Context.getSystemService/biometrics', 'getSystemService'), paramof('manager', { [0] = '%s' }))";
        assertFalse(Scripts.run(globals, "sdk = 29 target = 26\n" + String.format(service, "biometric")).arg1().toboolean());
        Scripts.run(globals, "target = 26");
        result = Scripts.run(globals, String.format(service, "fingerprint"));
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        assertFalse(Scripts.run(globals, String.format(service, "window")).arg1().toboolean());
        Scripts.run(globals, "target = 25");
        assertFalse(Scripts.run(globals, String.format(service, "fingerprint")).arg1().toboolean());
        assertTrue(Scripts.run(globals, String.format(service, "iris")).arg1().toboolean());
        // A manager of an older app which kept its service, being built by a call of getService compiled inline, loses
        // it and stays the same manager; one without it stays as it is
        Manager manager = new Manager();
        globals.set("manager", CoerceJavaToLua.coerce(manager));
        String legacy = "return run(hookof('Context.getSystemService/biometrics', 'getSystemService'), " +
                "paramof(manager, { [0] = 'fingerprint' }))";
        result = Scripts.run(globals, legacy);
        assertTrue(result.arg1().toboolean());
        assertSame(manager, result.arg(2).touserdata());
        assertNull(manager.mService);
        assertFalse(Scripts.run(globals, legacy).arg1().toboolean());
        assertSame(manager, Scripts.run(globals, legacy).arg(2).touserdata());

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

        // No biometric is enrolled for keystore, as a new long[], from Android 12 by both methods; none stays none
        for (String name : new String[]{"BiometricManager.getAuthenticatorIds", "BiometricManager.getAuthenticatorIds/user"}) {
            result = Scripts.run(globals, "return run(hookof('" + name + "', 'getAuthenticatorIds'), " +
                    "paramof({ length = 2 }, { [0] = 0 }))");
            assertTrue(name, result.arg1().toboolean());
            assertEquals(name, 0, ((long[]) result.arg(2).checkuserdata(long[].class)).length);
            assertFalse(name, Scripts.run(globals, "return run(hookof('" + name + "', 'getAuthenticatorIds'), " +
                    "paramof({ length = 0 }, { [0] = 0 }))").arg1().toboolean());
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

    // TimeZone.setDefault keeps the fake zone when the default is reset with null, like the system does, but a zone the app
    // sets itself stays and the getters give it back, like on a device without Doppel; the ICU zone is one of ICU
    @Test
    public void timeZoneStaysFake() throws Exception {
        Globals globals = load("timezone");
        // The values the script keeps, see XParam.putValue
        Scripts.run(globals, "VALUES = {}\n" +
                "function valuesof(param)\n" +
                "  param.putValue = function(self, name, value, scope) assert(scope ~= nil) VALUES[name] = value end\n" +
                "  param.getValue = function(self, name, scope) assert(scope ~= nil) return VALUES[name] end\n" +
                "  return param\n" +
                "end\n" +
                "function zoneof(id) return luajava.bindClass('java.util.TimeZone'):getTimeZone(id) end");
        Varargs result = Scripts.run(globals, "local param = valuesof(paramof(nil, {}))\n" +
                "param.setArgument = function(self, index, value) self.zone = value end\n" +
                "local restricted, old, new = before(hookof('TimeZone.setDefault', 'setDefault'), param)\n" +
                "return restricted, old, new, param.zone:getID()");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        assertEquals("UTC", result.arg(3).tojstring());
        assertEquals("UTC", result.arg(4).tojstring());
        assertFalse(Scripts.run(globals, "return before(hookof('TimeZone.setDefault', 'setDefault'), " +
                "valuesof(paramof(nil, { [0] = zoneof('UTC') })))").arg1().toboolean());

        // A zone the app sets, like a zone of its server to show the times of the server in
        result = Scripts.run(globals, "local param = valuesof(paramof(nil, { [0] = zoneof('America/New_York') }))\n" +
                "param.setArgument = function(self, index, value) error('replaced') end\n" +
                "local set = before(hookof('TimeZone.setDefault', 'setDefault'), param)\n" +
                "local own = valuesof(paramof(zoneof('America/New_York'), {}))\n" +
                "local kept = after(hookof('TimeZone.getDefault', 'getDefault', 'java.util.TimeZone'), own)\n" +
                // Another zone, which the app did not set, is still replaced
                "local other = valuesof(paramof(zoneof('Europe/Berlin'), {}))\n" +
                "local replaced = after(hookof('TimeZone.getDefault', 'getDefault', 'java.util.TimeZone'), other)\n" +
                "return set, kept, own.result:getID(), replaced, other.result:getID()");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        assertEquals("America/New_York", result.arg(3).tojstring());
        assertTrue(result.arg(4).toboolean());
        assertEquals("UTC", result.arg(5).tojstring());

        // After a reset with null the zone the app set before is replaced again
        result = Scripts.run(globals, "local reset = valuesof(paramof(nil, {}))\n" +
                "reset.setArgument = function(self, index, value) end\n" +
                "local restricted = before(hookof('TimeZone.setDefault', 'setDefault'), reset)\n" +
                "local param = valuesof(paramof(zoneof('America/New_York'), {}))\n" +
                "return restricted, after(hookof('TimeZone.getDefault', 'getDefault', 'java.util.TimeZone'), param), " +
                "param.result:getID()");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals("UTC", result.arg(3).tojstring());

        result = Scripts.run(globals, "luajava = { bindClass = function(name)\n" +
                "  assert(name == 'android.icu.util.TimeZone', name)\n" +
                "  return { getTimeZone = function(self, id) return 'icu ' .. id end } end }\n" +
                "local param = valuesof(paramof({ getID = function(self) return 'Europe/Berlin' end }, {}))\n" +
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
