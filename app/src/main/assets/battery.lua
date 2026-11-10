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
-- The capacity is given in steps of 10 percent, but at least 15, where devices warn of a low battery,
-- so a nearly empty battery does not show as 0 percent; other properties as not supported,
-- like BatteryManager does on devices without them. The status stays, apps act on charging.
-- Battery saver is a setting people switch, it appears off.
-- The capacity the battery was designed for (mAh), which PowerProfile of the framework reads from the power profile
-- of the device and apps by reflection, is not there either, like on devices without it: 0, or the default
-- of getAveragePowerOrDefault. The files of the kernel with the capacities (charge_full, charge_full_design of
-- /sys/class/power_supply) need no hooks: SELinux denies apps reading them (sysfs_batteryinfo, Android 10 and later).
local CAPACITY = 'battery.capacity'

function after(hook, param)
    local method = hook:getMethodName()
    local result = param:getResult()
    -- The exception of Android stays, like the SecurityException of properties which need BATTERY_STATS
    if param:getException() ~= nil then
        return false
    end

    if hook:getClassName() == 'com.android.internal.os.PowerProfile' then
        -- getBatteryCapacity, and getAveragePower and getAveragePowerOrDefault of battery.capacity, which it calls
        local fake = 0
        if method ~= 'getBatteryCapacity' then
            if param:getArgument(0) ~= CAPACITY then
                return false
            end
            if method == 'getAveragePowerOrDefault' then
                fake = param:getArgument(1)
            end
        end
        if result == fake then
            return false
        end
        param:setResult(fake)
        return true, tostring(result), tostring(fake)
    end

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
        fake = math.max(15, math.floor(result / 10 + 0.5) * 10)
    elseif method == 'getIntProperty' then
        -- Before Android 9 and for apps made for it, BatteryManager casts Long.MIN_VALUE to int, which is 0
        if luajava.bindClass('android.os.Build$VERSION').SDK_INT < 28 or
                param:getApplicationContext():getApplicationInfo().targetSdkVersion < 28 then
            fake = 0
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
