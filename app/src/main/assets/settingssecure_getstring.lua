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

-- Whether hooks change the setting; the hooked methods are called for all settings, so check the name first
function hooked(key)
    return key == 'android_id' or key == 'bluetooth_name' or key == 'device_name' or key == 'boot_count' or
            key == 'pps_oaid' or key == 'default_input_method' or key == 'enabled_input_methods' or
            key == 'enabled_accessibility_services' or key == 'adb_enabled' or key == 'adb_wifi_enabled' or
            key == 'development_settings_enabled' or key == 'low_power' or key == 'bluetooth_address' or
            PERSONAL[key] ~= nil
end

-- The fake value of a setting for a variant: true and the fake value, or false when it stays
function fakesetting(param, name, key, result)
    if name == 'android_id' and key == 'android_id' then
        local fake = param:getSetting('value.android_id')
        if fake == nil then
            fake = '0000000000000000'
        end
        return true, fake
    elseif (name == 'bluetooth_name' and key == 'bluetooth_name') or
            (name == 'device_name' and key == 'device_name') or
            (name == 'boot_count' and key == 'boot_count') or
            (name == 'oaid' and key == 'pps_oaid') then
        local fake
        return true, fake
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
        return true, fake
    elseif name == 'settings' and PERSONAL[key] ~= nil then
        local fake = PERSONAL[key]
        if fake == NONE then
            fake = nil
        end
        if fake == result then
            return false
        end
        return true, fake
    elseif name == 'adb' and (key == 'adb_enabled' or key == 'adb_wifi_enabled' or key == 'development_settings_enabled') then
        -- Developer options and USB or wireless debugging appear off
        if result == '0' then
            return false
        end
        return true, '0'
    elseif name == 'battery' and key == 'low_power' then
        -- Battery saver appears off, like PowerManager.isPowerSaveMode
        if result == '0' then
            return false
        end
        return true, '0'
    elseif name == 'mac' and key == 'bluetooth_address' then
        -- The Bluetooth MAC address, like BluetoothAdapter.getAddress
        local fake = '02:00:00:00:00:00'
        if result == fake then
            return false
        end
        return true, fake
    else
        return false
    end
end

-- The variants of the settings in each group, for the hooks of the settings provider
local PROVIDER = {
    identifiers = { 'android_id', 'oaid', 'mac' },
    tracking = { 'bluetooth_name', 'device_name', 'boot_count' },
    applications = { 'input_method', 'accessibility' },
    debugging = { 'adb' },
    settings = { 'settings' },
    battery = { 'battery' },
}

-- The fake value of a setting for the variants of a provider hook, like fakesetting
function fakeany(param, group, key, result)
    for _, name in ipairs(PROVIDER[group]) do
        local changed, value = fakesetting(param, name, key, result)
        if changed then
            return true, value
        end
    end
    return false
end

-- The setting a query selects with name=?, for rows without the name column; nil when none
function selected(hook, param)
    local selection, args
    if string.find(hook:getName(), 'query26', 1, true) then
        local bundle = param:getArgument(2)
        if bundle == nil then
            return nil
        end
        selection = bundle:getString('android:query-arg-sql-selection')
        args = bundle:getStringArray('android:query-arg-sql-selection-args')
    else
        selection = param:getArgument(2)
        args = param:getArgument(3)
    end
    if selection == nil or args == nil or args['length'] ~= 1 or
            (string.gsub(selection, '%s', '')) ~= 'name=?' then
        return nil
    end
    return args[1]
end

-- Apps can read settings from the settings provider too, which Settings.*.getString use:
-- call(GET_secure, name) returns a bundle with the value, a query rows of names and values
function provider(hook, param, group)
    local target = param:getArgument(0)
    local authority = target
    if type(target) ~= 'string' then
        authority = target:getAuthority()
    end
    if authority ~= 'settings' then
        return false
    end

    local result = param:getResult()
    if result == nil then
        return false
    end

    if hook:getMethodName() == 'call' then
        local method = param:getArgument(1)
        local key = param:getArgument(2)
        if method == nil or string.sub(method, 1, 4) ~= 'GET_' or not hooked(key) then
            return false
        end
        local value = result:getString('value')
        if value == nil then
            return false
        end
        local changed, fakevalue = fakeany(param, group, key, value)
        if not changed then
            return false
        end
        result:putString('value', fakevalue)
        return true, value, fakevalue
    end

    -- A query of content://settings/secure or content://settings/secure/android_id
    local columns = result:getColumnNames()
    local nameIndex = result:getColumnIndex('name')
    local valueIndex = result:getColumnIndex('value')
    if valueIndex < 0 then
        return false
    end
    local segments = target:getPathSegments()
    local pathKey
    if segments:size() == 2 then
        pathKey = segments:get(1)
    else
        pathKey = selected(hook, param)
    end

    local fakecursor = luajava.newInstance('android.database.MatrixCursor', columns)
    local changed = false
    local position = result:getPosition()
    result:moveToPosition(-1)
    while result:moveToNext() do
        local key = pathKey
        if nameIndex >= 0 then
            key = result:getString(nameIndex)
        end
        local row = fakecursor:newRow()
        for index = 0, columns['length'] - 1 do
            local value
            local kind = result:getType(index)
            if kind == 1 then
                value = result:getLong(index)
            elseif kind == 2 then
                value = result:getDouble(index)
            elseif kind == 3 then
                value = result:getString(index)
            elseif kind == 4 then
                value = result:getBlob(index)
            end
            if index == valueIndex and value ~= nil and hooked(key) then
                local hit, fakevalue = fakeany(param, group, key, value)
                if hit then
                    changed = true
                    value = fakevalue
                end
            end
            row:add(value)
        end
    end

    if not changed then
        result:moveToPosition(position)
        fakecursor:close()
        return false
    end
    result:close()
    param:setResult(fakecursor)
    return true
end

function after(hook, param)
    -- Hook names are like Function/variant
    local name = string.match(hook:getName(), '/(.*)$')
    if hook:getClassName() == 'android.content.ContentResolver' then
        return provider(hook, param, name)
    end

    local key = param:getArgument(1)
    if not hooked(key) then
        return false
    end

    local result = param:getResult()
    if result == nil then
        return false
    end

    local changed, value = fakesetting(param, name, key, result)
    if not changed then
        return false
    end
    param:setResult(value)
    return true, result, value
end
