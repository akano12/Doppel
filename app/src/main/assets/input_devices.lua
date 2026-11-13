-- This file is part of Doppel.

-- Doppel is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.

-- Doppel is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.

-- You should have received a copy of the GNU General Public License
-- along with Doppel.  If not, see <http://www.gnu.org/licenses/>.

-- Copyright 2026 akano12

-- Connected keyboards, mice, game controllers and styluses, with their names and vendor ids,
-- reveal what someone uses and help to recognize a device. External input devices are hidden: the lists of ids leave
-- them out, a lookup by id gives null and the listeners of the app hear nothing of them. Their events still reach the
-- app, so the device of an event (InputEvent.getDevice) and its key map stay (see ofevent).
-- Built-in devices, like the touch screen and the buttons, stay, because apps need them.
-- Neither has its name, like sec_touchscreen, fts_ts or Xbox Wireless Controller, or its ids, which tell the model of
-- the device and what someone uses: a generic name by kind, no vendor and product id and a descriptor of the app
-- (key maps use the device id), also in their parcel. The motion ranges stay, they are the size of the display.
-- The virtual keyboard is the same on all devices.

local TOUCHSCREEN = 0x1002 -- InputDevice.SOURCE_TOUCHSCREEN
-- The kinds of external devices by their sources (InputDevice.SOURCE_GAMEPAD, SOURCE_JOYSTICK, SOURCE_STYLUS,
-- SOURCE_TOUCHPAD, SOURCE_MOUSE), the first one a device has: game controllers have keys too, and a touchpad moves
-- the pointer like a mouse
local KINDS = { { 0x401, 'gamepad' }, { 0x1000010, 'gamepad' }, { 0x4002, 'stylus' }, { 0x100008, 'touchpad' },
    { 0x2002, 'mouse' }, { TOUCHSCREEN, 'touchscreen' } }

-- The generic name of a device by its kind; an external one of none of the kinds is a keyboard
function fakename(device)
    local sources = device:getSources()
    if not device:isExternal() then
        return (bit32.band(sources, TOUCHSCREEN) == TOUCHSCREEN and 'touchscreen' or 'gpio-keys')
    end
    for _, kind in ipairs(KINDS) do
        if bit32.band(sources, kind[1]) == kind[1] then
            return kind[2]
        end
    end
    return 'keyboard'
end

-- A descriptor of the app, a hash in hex like the real one, a SHA-1 with no fixed digits, so the digits of the version
-- (4) and the variant (8 to b) of the UUIDs are left out; a fake one stays, like the one of a device the app
-- read back from a parcel (see parcelled), which would else get a fake of the fake
-- shortcut: the fakes are known in the process which made them, so a parcel read back in another process of the app
-- gets a fake of the fake; upgrade by comparing with the fake of the live device of the id.
function fakedescriptor(descriptor, param)
    local context = param:getApplicationContext()
    if param:getValue('input.descriptor.' .. descriptor, context) then
        return descriptor
    end
    local package = param:getPackageName()
    local unfixed = '^(%x+)%-(%x+)%-%x(%x+)%-%x(%x+)%-(%x+)$'
    local hex = string.gsub(param:uuidOf(descriptor .. package), unfixed, '%1%2%3%4%5') ..
        string.gsub(param:uuidOf(package .. descriptor), unfixed, '%1%2%3%4%5')
    local fake = string.sub(hex, 1, 40)
    param:putValue('input.descriptor.' .. fake, true, context)
    return fake
end

-- InputDevice.getName, getVendorId, getProductId, getDescriptor and toString, of built-in devices and of the external
-- ones of events and key maps (see ofevent); the virtual keyboard stays
function identity(method, device, result, param)
    if device == nil or device:getId() == -1 then
        return nil
    end
    if method == 'getName' then
        return fakename(device)
    elseif method == 'getVendorId' or method == 'getProductId' then
        return 0
    elseif method == 'getDescriptor' then
        return fakedescriptor(result, param)
    end
    -- toString starts with 'Input Device <id>: <name>' and the line '  Descriptor: <descriptor>'
    local text = string.gsub(result, '^(Input Device %-?%d+: )[^\n]*', function(label)
        return label .. fakename(device)
    end, 1)
    return (string.gsub(text, '(\n  Descriptor: )([^\n]*)', function(label, descriptor)
        return label .. fakedescriptor(descriptor, param)
    end, 1))
end

-- The classes on the stack (see ofevent)
function callers()
    return luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
end

-- Whether Android looks up the device of an event the app got: up to Android 13 KeyCharacterMap.load looks it up with
-- InputManager.getInputDevice, without which an external keyboard types with the US layout instead of its own, and
-- InputEvent.getDevice, which is never null for a device sending events, so the code of the guide to game controllers
-- (event.getDevice().getMotionRange) would crash.
-- shortcut: a lookup by the id of an event, like InputDevice.getDevice(event.getDeviceId()), still gives null, and an
-- event the app makes itself with the id of an external device gets the device; upgrade by keeping the ids of the
-- events the app got.
function ofevent()
    local frames = callers()
    for index = 1, frames['length'] do
        local class = frames[index]:getClassName()
        if class == 'android.view.KeyCharacterMap' or class == 'android.view.InputEvent' then
            return true
        end
    end
    return false
end

-- Whether a device is external; remembered, since a removed device can no longer be looked up, and Android never gives
-- the id of a device to another one (see InputReader)
function external(id, param)
    local context = param:getApplicationContext()
    local key = 'input.external.' .. id
    if param:getValue(key, context) then
        return true
    end
    local device = param:unhooked(function()
        return luajava.bindClass('android.view.InputDevice'):getDevice(id)
    end)
    if device == nil or not device:isExternal() then
        return false
    end
    param:putValue(key, true, context)
    return true
end

-- onInputDeviceAdded, onInputDeviceChanged and onInputDeviceRemoved of a listener of the app (see before)
-- shortcut: the methods are hooked for the class of the listener, so a call the app makes itself with the id of an
-- external device does nothing too; upgrade by looking for the delegate of InputManager on the stack.
function heard(when, param)
    if when == 'before' and external(param:getArgument(0), param) then
        param:setResult(nil)
    end
end

-- The parameter is not named hook, the function to hook a method is.
-- InputManager.registerInputDeviceListener: the listener hears nothing of external devices, also of those connected
-- already, which it hears of when they are removed; where a parcel of InputDevice.writeToParcel starts (see parcelled)
function before(h, param)
    local method = h:getMethodName()
    if method == 'writeToParcel' then
        local parcel = param:getArgument(0)
        if parcel ~= nil then
            param:putValue('input.parcel', parcel:dataPosition(), parcel)
        end
        return false
    elseif method ~= 'registerInputDeviceListener' or param:getArgument(0) == nil then
        return false
    end
    local ids = param:unhooked(function()
        return luajava.bindClass('android.view.InputDevice'):getDeviceIds()
    end)
    for index = 1, ids['length'] do
        external(ids[index], param)
    end
    local listener = param:getArgument(0)
    hook(listener, 'onInputDeviceAdded', heard)
    hook(listener, 'onInputDeviceChanged', heard)
    hook(listener, 'onInputDeviceRemoved', heard)
    return true
end

-- A parcel of a device has the name, the vendor and product id and the descriptor of the fields, which
-- InputDevice.CREATOR reads back: after the id, the generation and the controller number, from Android 12 after the key
-- map, and from Android 14 QPR2 (still SDK 34) with the bus between the product id and the descriptor
-- (see InputDevice.writeToParcel).
-- They are written like the getters give them, only when the parcel has the real ones at their place, so another
-- layout stays as it is.
-- shortcut: Android 17 is taken to write the parcel of Android 15 and 16; check when its source is out.
function parcelled(param)
    local parcel = param:getArgument(0)
    local start = (parcel ~= nil and param:getValue('input.parcel', parcel) or nil)
    local device = param:getThis()
    if start == nil or param:getException() ~= nil or device:getId() == -1 then
        return false
    end
    local real = param:unhooked(function()
        return { name = device:getName(), vendor = device:getVendorId(), product = device:getProductId(),
                 descriptor = device:getDescriptor() }
    end)
    local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
    local stop = parcel:dataPosition()
    local copy = luajava.bindClass('android.os.Parcel'):obtain()
    local ok, from, to, bus = pcall(function()
        copy:appendFrom(parcel, start, parcel:dataSize() - start)
        copy:setDataPosition(0)
        if sdk >= 31 then
            luajava.bindClass('android.view.KeyCharacterMap').CREATOR:createFromParcel(copy)
        end
        copy:readInt()
        copy:readInt()
        copy:readInt()
        local from = copy:dataPosition()
        if copy:readString() ~= real.name or copy:readInt() ~= real.vendor or copy:readInt() ~= real.product then
            return nil
        end
        local at = copy:dataPosition()
        local bus = (sdk >= 34 and copy:readInt() or nil)
        -- On Android 14 before QPR2 the int is the length of the descriptor, which is then read as a string; the bus is
        -- not read as a string, since Parcel logs a string with a bad length (SafetyNet 172655291)
        -- shortcut: a bus of 40, the length of the descriptor, is taken for the layout without the bus, so the parcel
        -- stays; no Linux bus is 40 (BUS_SDW, 0x21, is the highest), upgrade by also checking the int after it.
        if sdk == 34 and bus == #real.descriptor then
            bus = nil
            copy:setDataPosition(at)
        end
        if copy:readString() ~= real.descriptor then
            return nil
        end
        return from, copy:dataPosition(), bus
    end)
    if ok and from ~= nil then
        local name = fakename(device)
        parcel:setDataSize(start + from)
        parcel:setDataPosition(start + from)
        parcel:writeString(name)
        parcel:writeInt(0)
        parcel:writeInt(0)
        if bus ~= nil then
            parcel:writeInt(bus)
        end
        parcel:writeString(fakedescriptor(real.descriptor, param))
        -- The rest is copied, also what the parcel had after the device, where its end moves with the fake
        local moved = parcel:dataPosition() - (start + to)
        parcel:appendFrom(copy, to, copy:dataSize() - to)
        parcel:setDataPosition(stop + moved)
        copy:recycle()
        return true, real.name, name
    end
    copy:recycle()
    return false
end

function after(hook, param)
    local method = hook:getMethodName()
    if method == 'writeToParcel' then
        return parcelled(param)
    end
    local result = param:getResult()
    if result == nil then
        return false
    end

    if method == 'getName' or method == 'getVendorId' or method == 'getProductId' or method == 'getDescriptor' or
            method == 'toString' then
        local fake = identity(method, param:getThis(), result, param)
        if fake == nil or fake == result then
            return false
        end
        param:setResult(fake)
        return true, tostring(result), tostring(fake)
    end

    -- InputManager.getInputDevice, InputDevice.getDevice
    if method == 'getInputDevice' or method == 'getDevice' then
        if not result:isExternal() or ofevent() then
            return false
        end
        param:setResult(nil)
        -- The real name, which the hooked getName would fake as if the app asked for it
        return true, param:unhooked(function()
            return result:getName()
        end), nil
    end

    -- InputManager.getInputDeviceIds, InputDevice.getDeviceIds; the lookups of the devices are hooked themselves
    -- (getInputDevice and getDevice), which would report them as calls of the app
    local kept = param:unhooked(function()
        local inputDevice = luajava.bindClass('android.view.InputDevice')
        local ids = {}
        for index = 1, result['length'] do
            -- nil for devices which were removed meanwhile
            local device = inputDevice:getDevice(result[index])
            if device ~= nil and not device:isExternal() then
                ids[#ids + 1] = result[index]
            end
        end
        return ids
    end)
    if #kept == result['length'] then
        return false
    end

    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
    local intClass = luajava.bindClass('java.lang.Integer')
    local fake = arrayClass:newInstance(intClass.TYPE, #kept)
    for index = 1, #kept do
        fake[index] = kept[index]
    end
    param:setResult(fake)
    return true, tostring(result['length']), tostring(#kept)
end
