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
-- The time until the battery is full stays unknown (-1) where Android has none, on battery and right after plugging in;
-- else it is the one of a typical charger, a minute per percent, from the faked level, instead of the real one,
-- which tells the charger and the wear of the battery.
-- Battery saver is a setting people switch, it appears off.
-- The remaining time on battery, which tells how the device is used, is unknown like before the first drop of
-- the level after unplugging: -1 ms, the value of BatteryStats PowerManagerService gives; while powered it stays null.
-- shortcut: it stays -1 ms the whole time on battery, where Android gets an estimate after a while, until the time
-- is faked from the faked level.
-- The capacity the battery was designed for (mAh), which PowerProfile of the framework reads from the power profile
-- of the device and apps by reflection, is the one of the default profile of Android, also for
-- getAveragePowerOrDefault, since the item is then there; a profile without it stays without it (0, or the default).
-- So is the text of the item in the framework resource power_profile, which PowerProfile parses and apps can too,
-- by Resources.getXml of getIdentifier('power_profile', 'xml', 'android'), so the maps PowerProfile fills from it
-- agree, also for apps which read them by reflection.
-- shortcut: the file of the resource opened by its name through AssetManager.openXmlResourceParser keeps the real
-- capacity, since a name and a cookie alone do not tell the file of the framework from one of the app, upgrade by
-- comparing them with the value of the resource in the same assets if apps do that.
-- shortcut: the resource read raw (openRawResource) and framework-res.apk with its overlays, which apps can parse
-- themselves, keep the real capacity, upgrade if Doppel ever rewrites the APK files of the system for apps.
-- shortcut: maps of PowerProfile filled before the hooks are installed, or by a compiled caller with getText inline,
-- keep the real capacity for reflection while the getters give the fake one, upgrade by writing the item into the
-- maps after the constructor of PowerProfile if that shows up.
-- The files of the kernel with the capacities (charge_full, charge_full_design of /sys/class/power_supply)
-- need no hooks: SELinux denies apps reading them (sysfs_batteryinfo, Android 10 and later).
local CAPACITY = 'battery.capacity'
-- battery.capacity of core/res/res/xml/power_profile.xml of AOSP; the charge_counter of intent_createfromparcel.lua agrees
local DESIGN_CAPACITY = 1000
local MS_PER_PERCENT = 60 * 1000

-- Only the file of the power profile, which every XML resource the app loads, like each layout, would else run
-- the script for; the script checks the id.
-- shortcut: an overlay built with shortened resource paths names the file otherwise and keeps the real capacity,
-- upgrade by checking every XML resource if a device has one.
function filter(hook)
    if hook:getMethodName() == 'loadXmlResourceParser' then
        return { '*power_profile*' }
    end
    return nil
end

function after(hook, param)
    local method = hook:getMethodName()
    local result = param:getResult()
    -- The exception of Android stays, like the SecurityException of properties which need BATTERY_STATS
    if param:getException() ~= nil then
        return false
    end

    -- A parser of the framework resource power_profile, the id the second argument, is marked with the lines of
    -- the capacity, see capacitylines; other XML resources stay as they are
    if hook:getClassName() == 'android.content.res.ResourcesImpl' then
        local id = param:getArgument(1)
        if result == nil or id ~= luajava.bindClass('android.content.res.Resources'):getSystem():getIdentifier(
                'power_profile', 'xml', 'android') then
            return false
        end
        local lines = capacitylines(param)
        if lines ~= nil then
            param:putValue(CAPACITY, lines, result)
        end
        return false
    end

    -- The text of the capacity item of a marked parser: getText and getTextCharacters while on the text,
    -- nextText on the end of the item; a text which is already the fake one, given by getText inside, stays
    if hook:getClassName() == 'android.content.res.XmlBlock$Parser' then
        local parser = param:getThis()
        local lines = param:getValue(CAPACITY, parser)
        if lines == nil or result == nil then
            return false
        end
        local real = result
        if method == 'getTextCharacters' then
            real = luajava.newInstance('java.lang.String', result)
        end
        if lines[(method == 'nextText' and 'end' or 'text') .. parser:getLineNumber()] ~= real then
            return false
        end
        local fake = tostring(DESIGN_CAPACITY)
        if method == 'getTextCharacters' then
            -- A new array, with the start and length of the text in the array of the app, like Android sets them
            local text = luajava.newInstance('java.lang.StringBuilder', fake)
            local chars = luajava.bindClass('java.lang.reflect.Array'):newInstance(
                    luajava.bindClass('java.lang.Character').TYPE, text:length())
            text:getChars(0, text:length(), chars, 0)
            local holder = param:getArgument(0)
            holder[1] = 0
            holder[2] = text:length()
            param:setResult(chars)
        else
            param:setResult(fake)
        end
        return true, real, fake
    end

    if hook:getClassName() == 'com.android.internal.os.PowerProfile' then
        -- getBatteryCapacity, and getAveragePower and getAveragePowerOrDefault of battery.capacity, which it calls;
        -- a missing item (or one of 0) stays: 0, or the default the caller gives
        if method ~= 'getBatteryCapacity' and param:getArgument(0) ~= CAPACITY then
            return false
        end
        local fake = DESIGN_CAPACITY
        if result == fake then
            return false
        end
        -- The default is also the answer when the item has that value, so the item is asked by getAveragePower
        -- with a level, which gives 0 for a missing one on every version; else other defaults would show the real item
        local real = result
        if method == 'getAveragePowerOrDefault' then
            real = param:unhooked(function()
                return param:getThis():getAveragePower(CAPACITY, 0)
            end)
        end
        if real == 0 then
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

    -- Milliseconds; the level by getLongProperty, which gives Long.MIN_VALUE when it cannot be read,
    -- where getIntProperty gives 0 to apps made for Android 8.1 or before
    if method == 'computeChargeTimeRemaining' then
        if result < 0 then
            return false
        end
        local level = param:unhooked(function()
            return param:getThis():getLongProperty(4) -- BATTERY_PROPERTY_CAPACITY
        end)
        local fake = -1 -- Unknown
        if level >= 0 then
            fake = (100 - batterylevel(param, level, 100)) * MS_PER_PERCENT
        end
        if result == fake then
            return false
        end
        param:setResult(fake)
        return true, tostring(result), tostring(fake)
    end

    if method == 'getBatteryDischargePrediction' then
        -- Duration is an object, compared by its value
        if result == nil or result:toMillis() == -1 then
            return false
        end
        local fake = luajava.bindClass('java.time.Duration'):ofMillis(-1)
        param:setResult(fake)
        return true, tostring(result), tostring(fake)
    end

    if method == 'isBatteryDischargePredictionPersonalized' then
        -- Android tells a personalized estimate only while it gives one instead of the one of BatteryStats
        if not result then
            return false
        end
        param:setResult(false)
        return true, 'true', 'false'
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
        fake = batterylevel(param, result, 100)
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

-- The lines of the text of the capacity items of the power profile, as 'text' and the line, and of their ends, where
-- nextText leaves the parser, as 'end' and the line, each with the text, read like PowerProfile by a parser of its own
-- without the hooks; nil without such an item, or for items of 0, which stay like for getBatteryCapacity, and
-- of the fake capacity. The parser reads the file and the cookie of the hooked call in the assets of its ResourcesImpl,
-- which has the overlays switched on after the start of Android, unlike the resources of the system.
-- shortcut: another text of the same value on the line of the item would be faked too, which no profile of Android
-- has, upgrade by also comparing the depth if one does.
function capacitylines(param)
    return param:unhooked(function()
        local parser = param:getThis():getAssets():openXmlResourceParser(param:getArgument(2), param:getArgument(0))
        local lines
        local event = parser:next()
        while event ~= 1 do -- END_DOCUMENT
            -- START_TAG of the item, then TEXT
            if event == 2 and parser:getName() == 'item' and parser:getAttributeValue(nil, 'name') == CAPACITY and
                    parser:next() == 4 then
                local text = parser:getText()
                local value = tonumber((string.gsub(text, '%s', '')))
                if value ~= nil and value ~= 0 and value ~= DESIGN_CAPACITY then
                    lines = lines or {}
                    lines['text' .. parser:getLineNumber()] = text
                    if parser:next() == 3 then -- END_TAG
                        lines['end' .. parser:getLineNumber()] = text
                    end
                end
            end
            event = parser:next()
        end
        parser:close()
        return lines
    end)
end

-- The level in steps of 10 percent, but at least 15, on the same side as the real level of the levels at which Android
-- warns of a low battery and stops warning, a copy of intent_createfromparcel, so the capacity and the level of
-- BATTERY_CHANGED agree
function batterylevel(param, real, scale)
    local fake = math.max(math.floor(15 * scale / 100), math.floor(math.ceil(real * 10 / scale - 0.5) * scale / 10))
    local resources = luajava.bindClass('android.content.res.Resources'):getSystem()
    local function integer(name)
        return resources:getInteger(resources:getIdentifier(name, 'integer', 'android'))
    end
    local ok, warning = pcall(function()
        return param:unhooked(function()
            return luajava.bindClass('android.provider.Settings$Global'):getInt(
                    param:getApplicationContext():getContentResolver(), 'low_power_trigger_level', 0)
        end)
    end)
    if not ok or warning == 0 then
        warning = integer('config_lowBatteryWarningLevel')
    end
    warning = math.max(warning, integer('config_criticalBatteryWarningLevel'))
    local close = math.ceil((warning + integer('config_lowBatteryCloseWarningBump')) * scale / 100)
    warning = math.floor(warning * scale / 100)
    if real <= warning then
        return math.min(fake, warning)
    elseif real >= close then
        return math.max(fake, close)
    end
    return math.min(math.max(fake, warning + 1), close - 1)
end
