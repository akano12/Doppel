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
-- The 12/24 hour clock is not included, because apps show times with it. Neither are the font scale,
-- the animator duration scale and whether accessibility or touch exploration is on: the framework
-- gives the real values too (Configuration.fontScale, ValueAnimator.getDurationScale, AccessibilityManager),
-- which cannot be faked without changing how the app works, and fake settings next to them would stand out.
local NONE = {}
local PERSONAL = {
    -- Global
    http_proxy = NONE,
    transition_animation_scale = '1.0',
    window_animation_scale = '1.0',
    data_roaming = '0',
    auto_time = '1',
    auto_time_zone = '1',
    -- Secure
    rtt_calling_mode = '0',
    -- System
    alarm_alert = NONE,
    ringtone = NONE,
    notification_sound = NONE,
    date_format = NONE,
    end_button_behavior = '2',
    screen_off_timeout = '60000',
    text_auto_replace = '1',
    text_auto_caps = '1',
    text_auto_punctuate = '1',
}

-- Secure settings which name apps, and what tells which of them stay visible: the keyboards ('input_method')
-- or the accessibility services ('accessibility'), see systemservices, else the package manager ('packages').
-- Lists are like com.example/.Keyboard;subtype:com.other/.Keyboard or com.example:com.other.
-- The voice recognition service and the text to speech engine are not included,
-- because the framework reads them in the app process to start speech recognition and speech.
local COMPONENTS = {
    default_input_method = 'input_method',
    enabled_input_methods = 'input_method',
    input_methods_subtype_history = 'input_method',
    selected_spell_checker = 'input_method',
    enabled_accessibility_services = 'accessibility',
    touch_exploration_granted_accessibility_services = 'accessibility',
    accessibility_shortcut_target_service = 'accessibility',
    accessibility_button_target_component = 'accessibility',
    accessibility_button_targets = 'accessibility',
    accessibility_gesture_targets = 'accessibility',
    enabled_notification_listeners = 'packages',
    enabled_notification_assistant = 'packages',
    enabled_notification_policy_access_packages = 'packages',
    autofill_service = 'packages',
    voice_interaction_service = 'packages',
    assistant = 'packages',
    sms_default_application = 'packages',
    dialer_default_application = 'packages',
    enabled_print_services = 'packages',
    enabled_vr_listeners = 'packages',
}

-- Shortcut targets of the framework itself, like com.android.server.accessibility/ColorInversion;
-- magnification has no package, like com.android.server.accessibility.MagnificationController
local FRAMEWORK = { android = true, ['com.android.server.accessibility'] = true }

-- Whether packages of keyboards or accessibility services are visible, see XParam.isVisible.
-- These come from the services, because the package manager does not tell about apps
-- which are hidden by the package visibility rules, like a system keyboard. See service_filter too.
function systemservices(param, source)
    local context = param:getApplicationContext()
    -- The lists of the services are hooked themselves (see service_filter),
    -- which would filter them and report them as calls of the app
    local infos = param:unhooked(function()
        local infos = {}
        if source == 'accessibility' then
            local am = context:getSystemService('accessibility')
            for _, list in ipairs({ am:getInstalledAccessibilityServiceList(), am:getEnabledAccessibilityServiceList(-1) }) do
                local items = list:toArray()
                for index = 1, items['length'] do
                    infos[#infos + 1] = items[index]:getResolveInfo().serviceInfo.applicationInfo
                end
            end
        elseif source == 'input_method' then
            local items = context:getSystemService('input_method'):getInputMethodList():toArray()
            for index = 1, items['length'] do
                infos[#infos + 1] = items[index]:getServiceInfo().applicationInfo
            end
        end
        return infos
    end)
    local packages = {}
    for _, ai in ipairs(infos) do
        packages[ai.packageName] = (packages[ai.packageName] or param:isVisible(ai))
    end
    return packages
end

-- Whether the package manager tells about an app, and it is a system app or the app itself;
-- an app hidden by the package visibility rules is not visible
function installed(param, packageName)
    return param:isInstalledVisible(packageName)
end

-- The default app of a device on which the user did not choose one: the dialer of the system, or the SMS app
-- the configuration of the system names, else a system app which can be the default; nil when there is none.
-- The same function as in default_app_filter, which tells it for the hidden default app too; a test compares them.
function systemdefault(param, dialer)
    -- Its lookups are hooked themselves (see package_probe and generic_filter_by_uid),
    -- which would restrict them and report them as calls of the app
    return param:unhooked(function()
        local context = param:getApplicationContext()
        local names = {}
        if dialer then
            -- Public as of Android 10, hidden before
            local telecom = context:getSystemService('telecom')
            if telecom ~= nil then
                local ok, name = pcall(telecom.getSystemDialerPackage, telecom)
                names[#names + 1] = (ok and name or nil)
            end
        else
            -- The role default of Android 10 and later, the default of SmsApplication before
            local resources = luajava.bindClass('android.content.res.Resources'):getSystem()
            for _, config in ipairs({ 'config_defaultSms', 'default_sms_application' }) do
                local id = resources:getIdentifier(config, 'string', 'android')
                if id ~= 0 then
                    names[#names + 1] = resources:getString(id)
                end
            end
        end

        -- The configured app need not be installed or enabled
        local pm = context:getPackageManager()
        local infos = {}
        for _, name in ipairs(names) do
            local ok, ai = pcall(pm.getApplicationInfo, pm, name, 0)
            if ok then
                infos[#infos + 1] = ai
            end
        end
        local intent = luajava.newInstance('android.content.Intent',
                dialer and 'android.intent.action.DIAL' or 'android.provider.Telephony.SMS_DELIVER')
        local ok, list = pcall(dialer and pm.queryIntentActivities or pm.queryBroadcastReceivers, pm, intent, 0)
        if ok and list ~= nil then
            local items = list:toArray()
            for index = 1, items['length'] do
                infos[#infos + 1] = items[index].activityInfo.applicationInfo
            end
        end
        for _, ai in ipairs(infos) do
            if ai ~= nil and ai.enabled ~= false and ai.packageName ~= param:getPackageName() and param:isVisible(ai) then
                return ai.packageName
            end
        end
        return nil
    end)
end

-- Whether the app can change secure settings: the system (see XParam.isSystem), like the system server,
-- Settings and Phone, and apps given the permission, like with adb
function writer(param)
    if param:isSystem() then
        return true
    end
    local context = param:getApplicationContext()
    return (context:checkSelfPermission('android.permission.WRITE_SECURE_SETTINGS') == 0)
end

-- The entries of a list which name visible apps, nil when none are left;
-- the one default SMS or phone app becomes the one of the system, apps expect there is one
function components(param, key, value)
    local source = COMPONENTS[key]
    local services
    local kept = {}
    for entry in string.gmatch(value, '[^:]+') do
        local packageName = string.match(entry, '^[^/;]+')
        local visible
        if source == 'accessibility' and (not string.find(entry, '/', 1, true) or FRAMEWORK[packageName]) then
            visible = true
        else
            if services == nil then
                services = systemservices(param, source)
            end
            visible = services[packageName]
            if visible == nil then
                visible = installed(param, packageName)
            end
        end
        if visible then
            kept[#kept + 1] = entry
        end
    end
    local fake = table.concat(kept, ':')
    if fake == '' then
        fake = nil
        if key == 'sms_default_application' or key == 'dialer_default_application' then
            fake = systemdefault(param, key == 'dialer_default_application')
        end
    end
    return fake
end

-- The other settings which hooks change
local HOOKED = {
    android_id = true, bluetooth_name = true, device_name = true, boot_count = true, pps_oaid = true,
    adb_enabled = true, adb_wifi_enabled = true, development_settings_enabled = true, low_power = true,
    bluetooth_address = true,
}

-- Whether hooks change the setting; the hooked methods are called for all settings, so check the name first
function hooked(key)
    return key ~= nil and (HOOKED[key] ~= nil or COMPONENTS[key] ~= nil or PERSONAL[key] ~= nil)
end

-- The fake value of a setting for a variant: true and the fake value, or false when it stays
function fakesetting(param, name, key, result)
    if name == 'android_id' and key == 'android_id' then
        local fake = param:getSetting('value.android_id')
        if fake == nil then
            fake = '0000000000000000'
        end
        -- Settings.Secure.getString calls the hooked getStringForUser
        if fake == result then
            return false
        end
        return true, fake
    elseif (name == 'bluetooth_name' and key == 'bluetooth_name') or
            (name == 'device_name' and key == 'device_name') or
            (name == 'boot_count' and key == 'boot_count') or
            (name == 'oaid' and key == 'pps_oaid') then
        local fake
        return true, fake
    elseif (name == 'accessibility' and COMPONENTS[key] == 'accessibility') or
            (name == 'input_method' and COMPONENTS[key] ~= nil and COMPONENTS[key] ~= 'accessibility') then
        -- Keyboards and accessibility services, and the other apps named by settings, like notification listeners.
        -- Apps which can change the settings get them as they are: they write back what they read, like
        -- the keyboards and spell checkers the system server chooses when there are none, or an app enabling
        -- its accessibility service, which would remove the hidden apps for good
        if writer(param) then
            return false
        end
        local fake = components(param, key, result)
        if fake == result then
            return false
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

-- The setting a query selects, for rows without the name column, and the selection; the name is nil
-- when none. Like SettingsProvider: name=? or (name=?) with one argument, or name='android_id' or
-- (name="android_id"), where the name is between the first and the last quote
function selected(hook, param)
    local selection, args
    if string.find(hook:getName(), 'query26', 1, true) then
        local bundle = param:getArgument(2)
        if bundle == nil then
            return nil, nil
        end
        selection = bundle:getString('android:query-arg-sql-selection')
        args = bundle:getStringArray('android:query-arg-sql-selection-args')
    else
        selection = param:getArgument(2)
        args = param:getArgument(3)
    end
    if selection == nil then
        return nil, nil
    end

    -- Without spaces around and without brackets
    local where = string.match(selection, '^%s*(.-)%s*$')
    local inner = string.match(where, '^%((.*)%)$')
    if inner ~= nil then
        where = string.match(inner, '^%s*(.-)%s*$')
    end
    if string.match(where, '^name%s*=%s*%?$') then
        if args ~= nil and args['length'] == 1 then
            return args[1], selection
        end
    elseif string.match(where, '^name%s*=%s*[\'"].*[\'"]$') then
        local first = math.max(string.find(selection, "'", 1, true) or 0, string.find(selection, '"', 1, true) or 0)
        local last = math.max(string.match(selection, ".*()'") or 0, string.match(selection, '.*()"') or 0)
        return string.sub(selection, first + 1, last - 1), selection
    end
    return nil, selection
end

-- call(LIST_secure) returns all settings, as lines like android_id=1234 (null when not set);
-- settings without a value are left out, like they are not set
function listed(param, group, result)
    local list = result:getStringArrayList('result_settings_list')
    if list == nil then
        return false
    end
    local lines = list:toArray()
    local kept = luajava.newInstance('java.util.ArrayList')
    local names = {}
    for index = 1, lines['length'] do
        local line = lines[index]
        local key, value = string.match(line, '^([^=]*)=(.*)$')
        local changed, fake = false
        if key ~= nil and value ~= 'null' and hooked(key) then
            changed, fake = fakeany(param, group, key, value)
        end
        if changed then
            names[#names + 1] = key
            if fake ~= nil then
                kept:add(key .. '=' .. fake)
            end
        else
            kept:add(line)
        end
    end
    if #names == 0 then
        return false
    end
    result:putStringArrayList('result_settings_list', kept)
    return true, table.concat(names, ',')
end

-- Apps can read settings from the settings provider too, which Settings.*.getString use:
-- call(GET_secure, name) returns a bundle with the value, call(LIST_secure) all settings
-- and a query rows of names and values
-- The methods of the settings provider which read settings
local GETTERS = {
    GET_secure = true, GET_system = true, GET_global = true,
    LIST_secure = true, LIST_system = true, LIST_global = true,
}

function provider(hook, param, group)
    local target = param:getArgument(0)
    local authority = target
    -- ContentProviderClient.call(method, arg, extras) does not tell the provider, the getters are of settings;
    -- hooked up to Android 9 only, later it calls the hooked call(authority, method, arg, extras)
    local client = (string.find(hook:getName(), 'ContentProviderClient.call/', 1, true) == 1)
    if client then
        authority = (GETTERS[target] and 'settings' or nil)
    elseif type(target) ~= 'string' then
        authority = target:getAuthority()
    end
    -- Without the user, like 0@settings, which apps can add for their own user
    if authority ~= nil then
        authority = (string.gsub(authority, '^%d+@', ''))
    end
    if authority ~= 'settings' then
        return false
    end

    local result = param:getResult()
    if result == nil then
        return false
    end

    if hook:getMethodName() == 'call' then
        local offset = (client and 0 or 1)
        local method = param:getArgument(offset)
        if method ~= nil and string.sub(method, 1, 5) == 'LIST_' then
            return listed(param, group, result)
        end
        local key = param:getArgument(offset + 1)
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
    elseif nameIndex < 0 then
        local selection
        pathKey, selection = selected(hook, param)
        -- All settings without their names: which value is which cannot be told, so there are none
        if selection == nil then
            if result:getCount() == 0 then
                return false
            end
            result:close()
            param:setResult(luajava.newInstance('android.database.MatrixCursor', columns))
            return true
        end
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
    -- The getters are called for all settings, so check the name first; the second argument of
    -- the provider hooks is the method of call (GET_secure, LIST_secure), the name of the setting for
    -- ContentProviderClient.call(method, arg, extras) or the projection of a query
    local key = param:getArgument(1)
    local getter = (type(key) == 'string' and string.sub(key, 1, 4) ~= 'GET_' and string.sub(key, 1, 5) ~= 'LIST_')
    if getter and not hooked(key) then
        return false
    end

    -- Hook names are like Function/variant
    local name = string.match(hook:getName(), '/(.*)$')
    local class = hook:getClassName()
    if not getter or class == 'android.content.ContentResolver' or class == 'android.content.ContentProviderClient' then
        return provider(hook, param, name)
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
