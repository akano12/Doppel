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
-- Apps of the system get the time of the firmware, which is the same on all devices with the same firmware.
-- That is their update time: Android sets the install time once, from the firmware the device first booted, or from
-- when the app was installed for the user, like in a new profile (from Android 15) or by pm install-existing, and moves
-- only the update time when an update of the firmware changes the file. The update time of an updated one, like of
-- Play services, is when the update was installed on the device; it gets the install time instead.
-- shortcut: an updated app of the system keeps its install time, which can tell the first firmware or when a profile
-- was made, since its package info has no time of the current firmware; upgrade when a hook can read that time.
-- An install time of 0 stays: a new profile has it for apps of the system until a reboot on Android 13 and 14.
-- The paths of the apps stay, like applicationInfo.sourceDir, which have random parts since Android 11
-- (/data/app/~~...==/com.example-...==/base.apk) that are the same for every app which asks too: the framework
-- reads the icons and labels of other apps from these files in the process of the app, which another path breaks.
-- Get.Applications hides the apps which are not of the system, which hides their paths too.
local FAKE = 1230768000000
local FLAG_SYSTEM = 1
local FLAG_UPDATED_SYSTEM_APP = 128

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
        local first = info.firstInstallTime
        local last = info.lastUpdateTime
        if last == first then
            return false
        end
        if math.floor(ai.flags / FLAG_UPDATED_SYSTEM_APP) % 2 == 1 then
            info.lastUpdateTime = first
            return true, info.packageName .. ' ' .. last, tostring(first)
        end
        if first == 0 then
            return false
        end
        info.firstInstallTime = last
        return true, info.packageName .. ' ' .. first, tostring(last)
    end

    local first = info.firstInstallTime
    if first == FAKE and info.lastUpdateTime == FAKE then
        return false
    end

    info.firstInstallTime = FAKE
    info.lastUpdateTime = FAKE
    return true, info.packageName .. ' ' .. first, tostring(FAKE)
end
