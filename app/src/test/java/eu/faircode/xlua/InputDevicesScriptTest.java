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

import static org.junit.Assert.assertFalse;
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
}
