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

-- Exact battery measurements change slowly and together identify a device across apps;
-- the manufacturing date, the first use and the health identify the battery itself.
-- The capacity is given in steps of 10 percent, other properties as not supported,
-- like BatteryManager does on devices without them. The status stays, apps act on charging.
-- Battery saver is a setting people switch, it appears off.

function after(hook, param)
    local method = hook:getMethodName()
    local result = param:getResult()

    if method == 'isPowerSaveMode' then
        if not result then
            return false
        end
        param:setResult(false)
        return true, 'true', 'false'
    end

    if method == 'computeChargeTimeRemaining' then
        if result == -1 then
            return false
        end
        param:setResult(-1) -- Unknown
        return true, tostring(result), '-1'
    end

    local id = param:getArgument(0)
    if id == 6 then -- BATTERY_PROPERTY_STATUS
        return false
    end

    -- getIntProperty and getLongProperty give the same capacity
    local fake
    if id == 4 then -- BATTERY_PROPERTY_CAPACITY, percent
        if result < 0 then
            return false
        end
        fake = math.floor(result / 10 + 0.5) * 10
    elseif method == 'getIntProperty' then
        fake = luajava.bindClass('java.lang.Integer').MIN_VALUE
    else
        fake = luajava.bindClass('java.lang.Long').MIN_VALUE
    end

    if result == fake then
        return false
    end
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
