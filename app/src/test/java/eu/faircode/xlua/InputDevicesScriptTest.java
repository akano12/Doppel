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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

// The input devices (assets/input_devices.lua) and the key maps: up to Android 13 KeyCharacterMap.load looks up
// the device of a key event with InputManager.getInputDevice, without it a keyboard types with the US layout
public class InputDevicesScriptTest {
    private static final String STUBS = String.join("\n",
            "function hookof(method)",
            "  return { getMethodName = function(self) return method end }",
            "end",
            "function paramof(result)",
            "  return { result = result,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value end }",
            "end",
            "keyboard = { isExternal = function(self) return true end, getName = function(self) return 'keyboard' end }",
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
            "function callers() return stack end");

    private static Varargs lookup(Globals globals, String stack) {
        return Scripts.run(globals, "stack = stackof(" + stack + ")\n" +
                "local param = paramof(keyboard)\n" +
                "return after(hookof('getInputDevice'), param), param.result == keyboard");
    }

    @Test
    public void keyMapsGetExternalKeyboards() throws Exception {
        Globals globals = Scripts.load("input_devices", STUBS);
        Varargs keys = lookup(globals, "'android.hardware.input.InputManager.getInputDevice', " +
                "'android.view.KeyCharacterMap.load', 'android.view.KeyEvent.getUnicodeChar', " +
                "'android.widget.TextView.onKeyDown'");
        assertFalse("restricted for the key map", keys.arg1().toboolean());
        assertTrue(keys.arg(2).toboolean());

        // The app itself does not get it
        Varargs app = lookup(globals, "'android.hardware.input.InputManager.getInputDevice', " +
                "'com.example.app.Main.onCreate'");
        assertTrue(app.arg1().toboolean());
        assertFalse(app.arg(2).toboolean());
    }

    // Built-in devices get a generic name, no vendor and product id and a descriptor of the app, also in their text;
    // external devices (kept for key maps) and the virtual keyboard stay
    @Test
    public void builtInDevicesHaveNoIdentity() throws Exception {
        Varargs result = Scripts.run(Scripts.load("input_devices", STUBS),
                "local function deviceof(id, sources, external)\n" +
                        "  return { getId = function(self) return id end,\n" +
                        "    getSources = function(self) return sources end,\n" +
                        "    isExternal = function(self) return external end }\n" +
                        "end\n" +
                        "local function get(method, device, value, package)\n" +
                        "  local param = { result = value,\n" +
                        "    getResult = function(self) return self.result end,\n" +
                        "    setResult = function(self, v) self.result = v end,\n" +
                        "    getThis = function(self) return device end,\n" +
                        "    getPackageName = function(self) return package or 'com.example' end,\n" +
                        // A stand-in of a hash
                        "    uuidOf = function(self, seed) local sum = 0\n" +
                        "      for i = 1, #seed do sum = (sum * 31 + string.byte(seed, i)) % 4294967296 end\n" +
                        "      return string.format('%08x-1111-4111-8111-%012x', sum, sum) end }\n" +
                        "  after(hookof(method), param)\n" +
                        "  return param.result\n" +
                        "end\n" +
                        "local touch = deviceof(3, 0x1002, false)\n" +
                        "local keys = deviceof(4, 0x101, false)\n" +
                        "local real = 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678'\n" +
                        "local text = 'Input Device 3: sec_touchscreen\\n  Descriptor: ' .. real .. '\\n  Generation: 7\\n'\n" +
                        "return get('getName', touch, 'sec_touchscreen'), get('getName', keys, 'qpnp_pon'),\n" +
                        "  get('getVendorId', touch, 0x1234), get('getProductId', touch, 0x5678),\n" +
                        "  get('getDescriptor', touch, real), get('toString', touch, text),\n" +
                        "  get('getName', deviceof(9, 0x1002, true), 'Pen'), get('getName', deviceof(-1, 0x101, false), 'Virtual'),\n" +
                        "  get('getDescriptor', touch, real, 'com.other')");
        assertEquals("touchscreen", result.arg(1).tojstring());
        assertEquals("gpio-keys", result.arg(2).tojstring());
        assertEquals(0, result.arg(3).toint());
        assertEquals(0, result.arg(4).toint());
        String descriptor = result.arg(5).tojstring();
        assertTrue(descriptor, descriptor.matches("[0-9a-f]{40}"));
        assertEquals("Input Device 3: touchscreen\n  Descriptor: " + descriptor + "\n  Generation: 7\n",
                result.arg(6).tojstring());
        assertEquals("Pen", result.arg(7).tojstring());
        assertEquals("Virtual", result.arg(8).tojstring());
        assertNotEquals("the same for other apps", descriptor, result.arg(9).tojstring());
    }
}
