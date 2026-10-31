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
-- built-in devices, like the touch screen and the buttons, stay, because apps need them.

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
