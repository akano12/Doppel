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

-- The hidden sensors (Get.Sensors) with the answers of a device without them: the lists of sensors are empty and
-- unmodifiable like those of Android, of getSensorList the same list for the same type each time, which Android
-- keeps; dynamic sensors, like the head tracker of headphones, are never reported to a callback; and the screen
-- cannot be turned off by the proximity sensor.

function before(hook, param)
    -- registerDynamicSensorCallback: not registered, so it reports no sensor; Android throws for a null callback
    if hook:getMethodName() ~= 'registerDynamicSensorCallback' or param:getArgument(0) == nil then
        return false
    end
    param:setResult(nil)
    return true
end

function after(hook, param)
    local method = hook:getMethodName()
    local result = param:getResult()
    if method == 'isWakeLockLevelSupported' then
        -- PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK
        if param:getArgument(0) ~= 32 or result ~= true then
            return false
        end
        param:setResult(false)
        return true
    end
    if method ~= 'getSensorList' and method ~= 'getDynamicSensorList' then
        return false
    end
    if result == nil or result:isEmpty() then
        return false
    end
    local collections = luajava.bindClass('java.util.Collections')
    local fake
    if method == 'getSensorList' then
        local name = 'sensors.' .. tostring(param:getArgument(0))
        fake = param:getValue(name, param:getThis())
        if fake == nil then
            fake = collections:unmodifiableList(luajava.newInstance('java.util.ArrayList'))
            param:putValue(name, fake, param:getThis())
        end
    else
        fake = collections:unmodifiableList(luajava.newInstance('java.util.ArrayList'))
    end
    param:setResult(fake)
    return true, tostring(result:size()), '0'
end
