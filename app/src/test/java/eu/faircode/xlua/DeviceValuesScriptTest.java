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
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
        Globals globals = JsePlatform.standardGlobals();
        String source = new String(Files.readAllBytes(new File("src/main/assets/" + script + ".lua").toPath()),
                StandardCharsets.UTF_8);
        globals.load(source, script).call();
        globals.load(STUBS, "stubs").call();
        return globals;
    }

    private static Varargs run(Globals globals, String lua) {
        return globals.load(lua, "test").invoke();
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
