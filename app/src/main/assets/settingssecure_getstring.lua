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

-- Settings people change, which together recognize a device (the device state of FingerprintJS),
-- with their values on a new device; NONE for settings which are not set on a new device.
-- The 12/24 hour clock is not included, because apps show times with it.
local NONE = {}
local PERSONAL = {
    -- Global
    http_proxy = NONE,
    transition_animation_scale = '1.0',
    window_animation_scale = '1.0',
    animator_duration_scale = '1.0',
    data_roaming = '0',
    auto_time = '1',
    auto_time_zone = '1',
    -- Secure
    accessibility_enabled = '0',
    touch_exploration_enabled = '0',
    rtt_calling_mode = '0',
    -- System
    alarm_alert = NONE,
    ringtone = NONE,
    notification_sound = NONE,
    date_format = NONE,
    end_button_behavior = '2',
    font_scale = '1.0',
    screen_off_timeout = '60000',
    text_auto_replace = '1',
    text_auto_caps = '1',
    text_auto_punctuate = '1',
}

-- Packages of the keyboards or accessibility services of system apps and of the app itself.
-- These come from the services, because the package manager does not tell about apps
-- which are hidden by the package visibility rules, like a system keyboard. See service_filter too.
function systemservices(param, name)
    local context = param:getApplicationContext()
    local infos = {}
    if name == 'accessibility' then
        local am = context:getSystemService('accessibility')
        for _, list in ipairs({ am:getInstalledAccessibilityServiceList(), am:getEnabledAccessibilityServiceList(-1) }) do
            local items = list:toArray()
            for index = 1, items['length'] do
                infos[#infos + 1] = items[index]:getResolveInfo().serviceInfo.applicationInfo
            end
        end
    else
        local items = context:getSystemService('input_method'):getInputMethodList():toArray()
        for index = 1, items['length'] do
            infos[#infos + 1] = items[index]:getServiceInfo().applicationInfo
        end
    end
    local packages = {}
    for _, ai in ipairs(infos) do
        -- ApplicationInfo.FLAG_SYSTEM (1) or FLAG_UPDATED_SYSTEM_APP (128)
        if ai.uid == param:getUid() or bit32.band(ai.flags, 129) ~= 0 then
            packages[ai.packageName] = true
        end
    end
    return packages
end

function after(hook, param)
    -- The hooked methods are called for all settings, so check the name first
    local key = param:getArgument(1)
    if key ~= 'android_id' and key ~= 'bluetooth_name' and key ~= 'device_name' and key ~= 'boot_count' and
            key ~= 'pps_oaid' and key ~= 'default_input_method' and key ~= 'enabled_input_methods' and
            key ~= 'enabled_accessibility_services' and key ~= 'adb_enabled' and key ~= 'adb_wifi_enabled' and
            key ~= 'development_settings_enabled' and PERSONAL[key] == nil then
        return false
    end

    local result = param:getResult()
    if result == nil then
        return false
    end

    -- Hook names are like Function/variant
    local name = string.match(hook:getName(), '/(.*)$')


    if name == 'android_id' and key == 'android_id' then
        local fake = param:getSetting('value.android_id')
        if fake == nil then
            fake = '0000000000000000'
        end
        param:setResult(fake)
        return true, result, fake
    elseif (name == 'bluetooth_name' and key == 'bluetooth_name') or
            (name == 'device_name' and key == 'device_name') or
            (name == 'boot_count' and key == 'boot_count') or
            (name == 'oaid' and key == 'pps_oaid') then
        local fake
        param:setResult(fake)
        return true, result, fake
    elseif (name == 'input_method' and (key == 'default_input_method' or key == 'enabled_input_methods')) or
            (name == 'accessibility' and key == 'enabled_accessibility_services') then
        -- Components of keyboards or accessibility services, like com.example/.Keyboard;subtype:com.other/.Keyboard
        local visible = systemservices(param, name)
        local kept = {}
        for entry in string.gmatch(result, '[^:]+') do
            local packageName = string.match(entry, '^[^/]+')
            if visible[packageName] then
                kept[#kept + 1] = entry
            end
        end
        local fake = table.concat(kept, ':')
        if fake == result then
            return false
        end
        if fake == '' then
            fake = nil
        end
        param:setResult(fake)
        return true, result, fake
    elseif name == 'settings' and PERSONAL[key] ~= nil then
        local fake = PERSONAL[key]
        if fake == NONE then
            fake = nil
        end
        if fake == result then
            return false
        end
        param:setResult(fake)
        return true, result, fake
    elseif name == 'adb' and (key == 'adb_enabled' or key == 'adb_wifi_enabled' or key == 'development_settings_enabled') then
        -- Developer options and USB or wireless debugging appear off
        if result == '0' then
            return false
        end
        param:setResult('0')
        return true, result, '0'
    else
        return false
    end
end
