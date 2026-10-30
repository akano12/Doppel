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

-- The install and update times of other apps, to the millisecond, are the same for every app which asks,
-- which links the apps of a device to each other. Each package info an app gets from the package manager
-- is read from a parcel, whichever method returned it, like getPackageInfo and getInstalledPackages.
-- The times become those of apps of the system image of AOSP builds, which have the file times of
-- reproducible builds (2009-01-01 00:00 UTC). The times of the app itself stay: the app can use them,
-- like to show what is new after an update, and they do not tell other apps anything.
-- Apps of the system keep their times too, which are the same on all devices with the same firmware; an update
-- of one, like of Play services, has the time it was installed on the device, which becomes the time of the firmware.
-- The paths of the apps stay, like applicationInfo.sourceDir, which have random parts since Android 11
-- (/data/app/~~...==/com.example-...==/base.apk) that are the same for every app which asks too: the framework
-- reads the icons and labels of other apps from these files in the process of the app, which another path breaks.
-- Get.Applications hides the apps which are not of the system, which hides their paths too.
local FAKE = 1230768000000
local FLAG_SYSTEM = 1
local FLAG_UPDATED_SYSTEM_APP = 128

-- Times as digits; string.format of LuaJ formats numbers beyond an int as an int or with an exponent,
-- so the digits are formatted in two parts which each fit an int
function millis(value)
    local high = math.floor(value / 1000000)
    if high == 0 then
        return string.format('%d', value)
    end
    return string.format('%d%06d', high, value - high * 1000000)
end

function after(hook, param)
    local info = param:getResult()
    if info == nil or info.packageName == nil then
        return false
    end

    -- Processes of the system show the real times, like App info of Settings
    if param:isSystem() or info.packageName == param:getPackageName() then
        return false
    end

    local ai = info.applicationInfo
    if ai ~= nil and math.floor(ai.flags / FLAG_SYSTEM) % 2 == 1 then
        if math.floor(ai.flags / FLAG_UPDATED_SYSTEM_APP) % 2 == 0 or info.lastUpdateTime == info.firstInstallTime then
            return false
        end
        local last = info.lastUpdateTime
        info.lastUpdateTime = info.firstInstallTime
        return true, info.packageName .. ' ' .. millis(last), millis(info.firstInstallTime)
    end

    local first = info.firstInstallTime
    if first == FAKE and info.lastUpdateTime == FAKE then
        return false
    end

    info.firstInstallTime = FAKE
    info.lastUpdateTime = FAKE
    return true, info.packageName .. ' ' .. millis(first), millis(FAKE)
end
