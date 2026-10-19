-- This file is part of XPrivacyLua.

-- XPrivacyLua is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.

-- XPrivacyLua is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.

-- You should have received a copy of the GNU General Public License
-- along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

-- Copyright 2017-2019 Marcel Bokhorst (M66B)

-- Exact battery measurements change slowly and together identify a device across apps;
-- the manufacturing date, the first use and the health identify the battery itself.
-- The capacity is given in steps of 10 percent, other properties as not supported,
-- like BatteryManager does on devices without them. The status stays, apps act on charging.

function after(hook, param)
    local method = hook:getMethodName()
    local result = param:getResult()

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

    local fake
    if method == 'getIntProperty' then
        if id == 4 then -- BATTERY_PROPERTY_CAPACITY, percent
            if result < 0 then
                return false
            end
            fake = math.floor(result / 10 + 0.5) * 10
        else
            fake = luajava.bindClass('java.lang.Integer').MIN_VALUE
        end
    else
        fake = luajava.bindClass('java.lang.Long').MIN_VALUE
    end

    if result == fake then
        return false
    end
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
