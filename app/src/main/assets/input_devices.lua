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

function after(hook, param)
    local method = hook:getMethodName()
    local result = param:getResult()
    if result == nil then
        return false
    end

    -- InputManager.getInputDevice, InputDevice.getDevice
    if method == 'getInputDevice' or method == 'getDevice' then
        if not result:isExternal() then
            return false
        end
        param:setResult(nil)
        return true, result:getName(), nil
    end

    -- InputManager.getInputDeviceIds, InputDevice.getDeviceIds
    local inputDevice = luajava.bindClass('android.view.InputDevice')
    local kept = {}
    for index = 1, result['length'] do
        -- Hooked too: nil for external devices, as for devices which were removed meanwhile
        local device = inputDevice:getDevice(result[index])
        if device ~= nil and not device:isExternal() then
            kept[#kept + 1] = result[index]
        end
    end
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
