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
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;

import java.util.HashSet;
import java.util.Set;

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
            "    setResult = function(self, value) self.result = value end,",
            "    unhooked = function(self, fn) return fn() end }",
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

    // The device of an event the app got stays, like on Android, where the code of the guide to game controllers,
    // event.getDevice().getMotionRange(axis, event.getSource()), would else crash on null
    @Test
    public void eventsKeepTheirDevice() throws Exception {
        Varargs event = lookup(Scripts.load("input_devices", STUBS), "'android.view.InputDevice.getDevice', " +
                "'android.view.InputEvent.getDevice', 'com.example.game.Main.onGenericMotionEvent'");
        assertFalse("restricted for an event", event.arg1().toboolean());
        assertTrue(event.arg(2).toboolean());
    }

    // A listener of the app hears nothing of external devices: not when they are added or changed, nor when they are
    // removed, when they can no longer be looked up, also those connected before the listener was registered
    @Test
    public void listenersHearNothingOfExternalDevices() throws Exception {
        Varargs result = Scripts.run(Scripts.load("input_devices", STUBS),
                "local function deviceof(external) return { isExternal = function(self) return external end } end\n" +
                        "local devices = { [1] = deviceof(false), [7] = deviceof(true), [8] = deviceof(true) }\n" +
                        "local unhooked = false\n" +
                        "luajava = { bindClass = function(name)\n" +
                        "  assert(name == 'android.view.InputDevice', name)\n" +
                        "  return { getDevice = function(self, id) assert(unhooked, 'hooked lookup') return devices[id] end,\n" +
                        "    getDeviceIds = function(self) assert(unhooked, 'hooked ids') return { length = 2, 1, 8 } end }\n" +
                        "end }\n" +
                        "local values = {}\n" +
                        "local function paramof(argument)\n" +
                        "  return { getArgument = function(self, index) return argument end,\n" +
                        "    getApplicationContext = function(self) return 'context' end,\n" +
                        "    getValue = function(self, name, scope) return values[name .. scope] end,\n" +
                        "    putValue = function(self, name, value, scope) values[name .. scope] = value end,\n" +
                        "    unhooked = function(self, fn) unhooked = true local value = fn() unhooked = false return value end,\n" +
                        "    setResult = function(self, value) self.skipped = true end }\n" +
                        "end\n" +
                        "local hooked = {}\n" +
                        "function hook(object, method, fn) assert(object == 'listener') hooked[method] = fn end\n" +
                        "local registered = before(hookof('registerInputDeviceListener'), paramof('listener'))\n" +
                        "local function call(method, id, when)\n" +
                        "  local param = paramof(id)\n" +
                        "  hooked[method](when or 'before', param)\n" +
                        "  return param.skipped == true\n" +
                        "end\n" +
                        "local added, builtin, after = call('onInputDeviceAdded', 7), call('onInputDeviceAdded', 1),\n" +
                        "  call('onInputDeviceAdded', 7, 'after')\n" +
                        "local changed = call('onInputDeviceChanged', 1)\n" +
                        "devices[7] = nil\n" +
                        "devices[8] = nil\n" +
                        "return registered, added, builtin, after, changed, call('onInputDeviceRemoved', 7),\n" +
                        "  call('onInputDeviceRemoved', 8), call('onInputDeviceRemoved', 9),\n" +
                        "  before(hookof('registerInputDeviceListener'), paramof(nil))");
        assertTrue(result.arg(1).toboolean());
        assertTrue("added", result.arg(2).toboolean());
        assertFalse("built-in", result.arg(3).toboolean());
        assertFalse("after", result.arg(4).toboolean());
        assertFalse("changed", result.arg(5).toboolean());
        assertTrue("removed after added", result.arg(6).toboolean());
        assertTrue("removed, connected before", result.arg(7).toboolean());
        assertFalse("unknown", result.arg(8).toboolean());
        assertFalse("without a listener", result.arg(9).toboolean());
    }

    // A getter of a device: get(method, device, value, package) gives what the app gets for the value
    private static final String GETTERS = String.join("\n",
            "local values = {}",
            "function deviceof(id, sources, external)",
            "  return { getId = function(self) return id end,",
            "    getSources = function(self) return sources end,",
            "    isExternal = function(self) return external end }",
            "end",
            "function get(method, device, value, package)",
            "  local param = { result = value,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, v) self.result = v end,",
            "    getThis = function(self) return device end,",
            "    getPackageName = function(self) return package or 'com.example' end,",
            "    getApplicationContext = function(self) return package or 'com.example' end,",
            "    getValue = function(self, name, scope) return values[name .. scope] end,",
            "    putValue = function(self, name, value, scope) values[name .. scope] = value end,",
            // A stand-in of a hash
            "    uuidOf = function(self, seed) local sum = 0",
            "      for i = 1, #seed do sum = (sum * 31 + string.byte(seed, i)) % 4294967296 end",
            "      return string.format('%08x-1111-4111-8111-%012x', sum, sum) end }",
            "  after(hookof(method), param)",
            "  return param.result",
            "end");

    // Built-in devices get a generic name, no vendor and product id and a descriptor of the app, also in their text;
    // so do external ones (of events and key maps), and the virtual keyboard stays
    @Test
    public void builtInDevicesHaveNoIdentity() throws Exception {
        Varargs result = Scripts.run(Scripts.load("input_devices", STUBS + "\n" + GETTERS),
                "local touch = deviceof(3, 0x1002, false)\n" +
                        "local keys = deviceof(4, 0x101, false)\n" +
                        "local real = 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678'\n" +
                        "local text = 'Input Device 3: sec_touchscreen\\n  Descriptor: ' .. real .. '\\n  Generation: 7\\n'\n" +
                        "return get('getName', touch, 'sec_touchscreen'), get('getName', keys, 'qpnp_pon'),\n" +
                        "  get('getVendorId', touch, 0x1234), get('getProductId', touch, 0x5678),\n" +
                        "  get('getDescriptor', touch, real), get('toString', touch, text),\n" +
                        "  get('getName', deviceof(9, 0x5002, true), 'Pen'), get('getName', deviceof(-1, 0x101, false), 'Virtual'),\n" +
                        "  get('getDescriptor', touch, real, 'com.other'),\n" +
                        // A device read back from a parcel has the fake descriptor already
                        "  get('getDescriptor', touch, get('getDescriptor', touch, real))");
        assertEquals("touchscreen", result.arg(1).tojstring());
        assertEquals("gpio-keys", result.arg(2).tojstring());
        assertEquals(0, result.arg(3).toint());
        assertEquals(0, result.arg(4).toint());
        String descriptor = result.arg(5).tojstring();
        assertTrue(descriptor, descriptor.matches("[0-9a-f]{40}"));
        assertEquals("Input Device 3: touchscreen\n  Descriptor: " + descriptor + "\n  Generation: 7\n",
                result.arg(6).tojstring());
        assertEquals("stylus", result.arg(7).tojstring());
        assertEquals("Virtual", result.arg(8).tojstring());
        assertNotEquals("the same for other apps", descriptor, result.arg(9).tojstring());
        assertEquals("a fake of the fake", descriptor, result.arg(10).tojstring());
    }

    // The external device of an event, which the app gets, has a generic name by kind, no vendor and product id and a
    // descriptor of the app, like a built-in one, so the hidden game controller or keyboard is not named by its event
    @Test
    public void devicesOfEventsHaveNoIdentity() throws Exception {
        Varargs result = Scripts.run(Scripts.load("input_devices", STUBS + "\n" + GETTERS),
                "stack = stackof('android.view.InputDevice.getDevice', 'android.view.InputEvent.getDevice',\n" +
                        "  'com.example.game.Main.onGenericMotionEvent')\n" +
                        // Keys, a gamepad and a joystick, like an Xbox controller
                        "local pad = deviceof(7, 0x1000511, true)\n" +
                        "local lookup = paramof(pad)\n" +
                        "after(hookof('getDevice'), lookup)\n" +
                        "local real = 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678'\n" +
                        "local text = 'Input Device 7: Xbox Wireless Controller\\n  Descriptor: ' .. real .. '\\n'\n" +
                        "return lookup.result == pad, get('getName', pad, 'Xbox Wireless Controller'),\n" +
                        "  get('getVendorId', pad, 0x045e), get('getProductId', pad, 0x0b13), get('getDescriptor', pad, real),\n" +
                        "  get('toString', pad, text), get('getName', deviceof(8, 0x1000010, true), 'Thrustmaster'),\n" +
                        "  get('getName', deviceof(9, 0x301, true), 'Logitech K380'),\n" +
                        "  get('getName', deviceof(10, 0x2002, true), 'Logitech M720'),\n" +
                        "  get('getName', deviceof(11, 0x10200a, true), 'Apple Magic Trackpad')");
        assertTrue("the device of the event", result.arg1().toboolean());
        assertEquals("gamepad", result.arg(2).tojstring());
        assertEquals(0, result.arg(3).toint());
        assertEquals(0, result.arg(4).toint());
        String descriptor = result.arg(5).tojstring();
        assertTrue(descriptor, descriptor.matches("[0-9a-f]{40}"));
        assertNotEquals("a1b2c3d4e5f60718293a4b5c6d7e8f9012345678", descriptor);
        assertEquals("Input Device 7: gamepad\n  Descriptor: " + descriptor + "\n", result.arg(6).tojstring());
        assertEquals("gamepad", result.arg(7).tojstring());
        assertEquals("keyboard", result.arg(8).tojstring());
        assertEquals("mouse", result.arg(9).tojstring());
        assertEquals("touchpad", result.arg(10).tojstring());
    }

    // A real descriptor is a SHA-1 in hex without fixed digits, so a fake made of the UUIDs of XParam.uuidOf has
    // neither their version (4 at index 12) nor their variant (8 to b at index 16), which would tell the hook is there
    @Test
    public void fakeDescriptorsHaveNoFixedDigits() throws Exception {
        Globals globals = Scripts.load("input_devices", STUBS);
        globals.set("uuid", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue seed) {
                try {
                    return LuaValue.valueOf(XParam.uuid(seed.checkjstring()));
                } catch (Throwable ex) {
                    throw new AssertionError(ex);
                }
            }
        });
        String[] fakes = Scripts.run(globals,
                "local param = { getApplicationContext = function(self) return 'context' end,\n" +
                        "  getPackageName = function(self) return 'com.example' end,\n" +
                        "  getValue = function(self, name, scope) return nil end,\n" +
                        "  putValue = function(self, name, value, scope) end,\n" +
                        "  uuidOf = function(self, seed) return uuid(seed) end }\n" +
                        "local fakes = {}\n" +
                        "for index = 1, 50 do fakes[index] = fakedescriptor('device' .. index, param) end\n" +
                        "return table.concat(fakes, ' ')").arg1().tojstring().split(" ");
        for (String fake : fakes)
            assertTrue(fake, fake.matches("[0-9a-f]{40}"));
        // A digit with two fixed bits takes at most 4 values
        for (int i = 0; i < 40; i++) {
            Set<Character> digits = new HashSet<>();
            for (String fake : fakes)
                digits.add(fake.charAt(i));
            assertTrue("index " + i + ": " + digits, digits.size() > 4);
        }
    }

    // A parcel of a device, built-in or external, has the fake name, ids and descriptor, by the layout of each version:
    // the fields first up to Android 11, after the key map from Android 12, with the bus before the descriptor from
    // Android 14 QPR2 (SDK 34 with and without the bus); a parcel of another layout stays
    @Test
    public void parcelsOfDevicesHaveNoIdentity() throws Exception {
        Globals globals = Scripts.load("input_devices", STUBS + "\n" + String.join("\n",
                // A parcel of values, one at each position
                "function parcelof(...)",
                "  local parcel = { items = { ... }, position = 0 }",
                "  function parcel:dataPosition() return self.position end",
                "  function parcel:setDataPosition(position) self.position = position end",
                "  function parcel:dataSize() return #self.items end",
                "  function parcel:setDataSize(size) while #self.items > size do table.remove(self.items) end end",
                "  function parcel:write(value) self.position = self.position + 1 self.items[self.position] = value end",
                "  function parcel:read() self.position = self.position + 1 return self.items[self.position] end",
                // An int read at a string is its length, like the first int of a string in a Parcel
                "  function parcel:readInt() local value = self:read() return type(value) == 'string' and #value or value end",
                "  parcel.writeInt, parcel.writeString, parcel.readString = parcel.write, parcel.write, parcel.read",
                "  function parcel:appendFrom(from, offset, length)",
                "    for index = 1, length do self:write(from.items[offset + index]) end",
                "  end",
                "  function parcel:recycle() end",
                "  return parcel",
                "end",
                "luajava = { bindClass = function(name)",
                "  if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk }",
                "  elseif name == 'android.os.Parcel' then return { obtain = function(self) return parcelof() end }",
                "  end",
                "  assert(name == 'android.view.KeyCharacterMap', name)",
                "  return { CREATOR = { createFromParcel = function(self, parcel) assert(parcel:read() == 'map') end } }",
                "end }",
                "real = 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678'",
                "function write(version, external, ...)",
                "  sdk = version",
                "  local device = { getId = function(self) return 3 end, getSources = function(self) return 0x1002 end,",
                "    isExternal = function(self) return external end,",
                "    getName = function(self) return 'sec_touchscreen' end, getVendorId = function(self) return 0x1234 end,",
                "    getProductId = function(self) return 0x5678 end, getDescriptor = function(self) return real end }",
                // The device is written after a value of the app, and the app wrote one more after it
                "  local parcel = parcelof('app', ...)",
                "  parcel.items[#parcel.items + 1] = 'more'",
                "  local values = {}",
                "  local param = { getArgument = function(self, index) return parcel end,",
                "    getThis = function(self) return device end, getException = function(self) return nil end,",
                "    getApplicationContext = function(self) return 'context' end,",
                "    getPackageName = function(self) return 'com.example' end,",
                "    uuidOf = function(self, seed) return '11111111-2222-4333-8444-' .. string.rep('5', 12) end,",
                "    getValue = function(self, name, scope) return values[name .. tostring(scope)] end,",
                "    putValue = function(self, name, value, scope) values[name .. tostring(scope)] = value end,",
                "    unhooked = function(self, fn) return fn() end }",
                "  parcel:setDataPosition(1)",
                "  before(hookof('writeToParcel'), param)",
                "  parcel:setDataPosition(#parcel.items - 1)",
                "  local restricted = after(hookof('writeToParcel'), param)",
                "  assert(parcel:dataPosition() == #parcel.items - 1, 'end of the device')",
                "  return restricted, table.concat(parcel.items, ' ')",
                "end"));
        String fake = "111111112222333444555555555555" + "1111111122";
        String[][] cases = {
                {"29, false, 3, 7, 0, 'sec_touchscreen', 0x1234, 0x5678, real, 0, 0x1002, 'map'",
                        "app 3 7 0 touchscreen 0 0 " + fake + " 0 4098 map more"},
                {"34, false, 'map', 3, 7, 0, 'sec_touchscreen', 0x1234, 0x5678, real, 0, 0x1002",
                        "app map 3 7 0 touchscreen 0 0 " + fake + " 0 4098 more"},
                {"34, false, 'map', 3, 7, 0, 'sec_touchscreen', 0x1234, 0x5678, 0x18, real, 0, 0x1002",
                        "app map 3 7 0 touchscreen 0 0 24 " + fake + " 0 4098 more"},
                {"35, false, 'map', 3, 7, 0, 'sec_touchscreen', 0x1234, 0x5678, 0x18, real, 0, 0x1002",
                        "app map 3 7 0 touchscreen 0 0 24 " + fake + " 0 4098 more"},
                {"35, true, 'map', 3, 7, 0, 'sec_touchscreen', 0x1234, 0x5678, 0x18, real, 1",
                        "app map 3 7 0 touchscreen 0 0 24 " + fake + " 1 more"},
        };
        for (String[] c : cases) {
            Varargs result = Scripts.run(globals, "return write(" + c[0] + ")");
            assertTrue(c[0], result.arg1().toboolean());
            assertEquals(c[0], c[1], result.arg(2).tojstring());
        }
        // The layout of Android 14 QPR2 on Android 13
        for (String c : new String[]{"33, false, 'map', 3, 7, 0, 'sec_touchscreen', 0x1234, 0x5678, 0x18, real, 0"}) {
            Varargs result = Scripts.run(globals, "return write(" + c + ")");
            assertFalse(c, result.arg1().toboolean());
            assertEquals(c, "app " + c.substring(c.indexOf("'map'")).replace("'", "").replace(", ", " ")
                    .replace("0x1234", "4660").replace("0x5678", "22136").replace("0x18", "24")
                    .replace("real", "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678") + " more", result.arg(2).tojstring());
        }
    }
}
