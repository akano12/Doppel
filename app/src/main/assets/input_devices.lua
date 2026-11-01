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
-- reveal what someone uses and help to recognize a device. External input devices are hidden,
-- built-in devices, like the touch screen and the buttons, stay, because apps need them,
-- but without their names, like sec_touchscreen or fts_ts, and ids, which tell the model of the device:
-- a generic name by kind, no vendor and product id and a descriptor of the app (key maps use the device id).
-- The motion ranges stay, they are the size of the display. The virtual keyboard is the same on all devices.

local TOUCHSCREEN = 0x1002 -- InputDevice.SOURCE_TOUCHSCREEN

-- The generic name of a built-in device
function fakename(device)
    return (bit32.band(device:getSources(), TOUCHSCREEN) == TOUCHSCREEN and 'touchscreen' or 'gpio-keys')
end

-- A descriptor of the app, a hash in hex like the real one
function fakedescriptor(descriptor, param)
    local package = param:getPackageName()
    local hex = string.gsub(param:uuidOf(descriptor .. package) .. param:uuidOf(package .. descriptor), '-', '')
    return string.sub(hex, 1, 40)
end

-- InputDevice.getName, getVendorId, getProductId, getDescriptor and toString of built-in devices
function identity(method, device, result, param)
    if device == nil or device:isExternal() or device:getId() == -1 then
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

-- The classes on the stack (see keymap)
function callers()
    return luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
end

-- Up to Android 13 KeyCharacterMap.load looks up the device of a key event with InputManager.getInputDevice:
-- without it an external keyboard types with the US layout instead of its own
function keymap()
    local frames = callers()
    for index = 1, frames['length'] do
        if frames[index]:getClassName() == 'android.view.KeyCharacterMap' then
            return true
        end
    end
    return false
end

function after(hook, param)
    local method = hook:getMethodName()
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
        if not result:isExternal() or keymap() then
            return false
        end
        param:setResult(nil)
        return true, result:getName(), nil
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
