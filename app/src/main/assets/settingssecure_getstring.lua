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

-- Copyright 2017-2019 Marcel Bokhorst (M66B)
-- Copyright 2026 akano12

-- Settings people change, which together recognize a device (the device state of FingerprintJS),
-- with their values on a new device; NONE for settings which are not set on a new device.
-- The 12/24 hour clock is not included, because apps show times with it. Neither are the font scale,
-- the animator duration scale and whether accessibility or touch exploration is on: the framework
-- gives the real values too (Configuration.fontScale, ValueAnimator.getDurationScale, AccessibilityManager),
-- which cannot be faked without changing how the app works, and fake settings next to them would stand out.
-- Nor is the proxy (http_proxy): the app process sets the proxy its connections use from
-- ConnectivityManager.getDefaultProxy (ActivityThread), so hiding it there would break proxied networking.
local NONE = {}
local PERSONAL = {
    -- Global
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
    disabled_print_services = 'packages',
    enabled_vr_listeners = 'packages',
    screensaver_components = 'packages',
    screensaver_default_component = 'packages',
    nfc_payment_default_component = 'packages',
    credential_service = 'packages',
    credential_service_primary = 'packages',
    -- The media button receiver of the last media app, like com.example/.Receiver,0,1
    media_button_receiver = 'packages',
}

-- Lists of COMPONENTS which Android separates by a comma (DreamManagerService.componentsFromString) instead of a colon
local COMMAS = {
    screensaver_components = true,
}

-- Shortcut targets of the framework itself, like com.android.server.accessibility/ColorInversion;
-- magnification has no package, like com.android.server.accessibility.MagnificationController
local FRAMEWORK = { android = true, ['com.android.server.accessibility'] = true }

-- Whether packages of keyboards or accessibility services are visible, see XParam.isVisible.
-- These come from the services, because the package manager does not tell about apps
-- which are hidden by the package visibility rules, like a system keyboard. See service_filter too.
-- Kept for a few seconds, apps read the settings often, like for each text field, and the lists rarely change
function systemservices(param, source)
    local context = param:getApplicationContext()
    local cached = param:getValue('services.' .. source, context)
    if cached ~= nil and os.time() - cached.at < 10 then
        return cached.packages
    end
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
    param:putValue('services.' .. source, { at = os.time(), packages = packages }, context)
    return packages
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
    -- A setting without entries stays as it is, like an empty accessibility_shortcut_target_service of Android 14
    if value == '' then
        return value
    end
    local separator = (COMMAS[key] and ',' or ':')
    for entry in string.gmatch(value, '[^' .. separator .. ']+') do
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
                -- Whether the package manager tells about the app, and it is a system app or the app itself;
                -- an app hidden by the package visibility rules is not visible
                visible = param:isInstalledVisible(packageName)
            end
        end
        if visible then
            kept[#kept + 1] = entry
        end
    end
    local fake = table.concat(kept, separator)
    if fake == '' then
        fake = nil
        if key == 'default_input_method' then
            -- A device always has a keyboard: the first of the enabled ones which stays visible
            local ok, enabled = pcall(function()
                return param:unhooked(function()
                    return luajava.bindClass('android.provider.Settings$Secure'):getString(
                            param:getApplicationContext():getContentResolver(), 'enabled_input_methods')
                end)
            end)
            if ok and enabled ~= nil and enabled ~= value then
                local others = components(param, 'enabled_input_methods', enabled)
                fake = (others ~= nil and string.match(others, '^[^:;]+') or nil)
            end
        elseif key == 'sms_default_application' or key == 'dialer_default_application' then
            fake = systemdefault(param, key == 'dialer_default_application')
        end
    end
    return fake
end

-- Identifiers which manufacturers and SDKs keep in settings, which all apps can read: they appear missing, like on
-- a device without them (variant oaid), in any table, since ROMs move them between Global, Secure and System
local IDENTIFIERS = {
    -- The advertising identifier (OAID) of Huawei, its encrypted copy and its opt-out (HMS ads-identifier SDK);
    -- the other ids of the family match by their name (see identifier)
    pps_oaid = true, pps_oaid_c = true, pps_track_limit = true,
    oaid = true, oaid_limit_state = true, -- The OAID of Honor and its opt-out (Honor ads-identifier SDK)
    -- Other names of the OAID, VAID and AAID of the MSA SDKs and ROMs, like XPrivacyLua-EX lists them
    aaid = true, vaid = true, a_aid = true, old_aaid = true, new_aaid = true, ad_oaid = true, ad_vaid = true,
    ad_id = true, adid = true, ad_adid = true, mdm_uuid = true,
    advertising_id = true, limit_ad_tracking = true, -- The advertising identifier of Amazon Fire OS, with its opt-out
    mi_health_id = true, gcbooster_uuid = true, key_mqs_uuid = true, extm_uuid = true, -- Xiaomi
    ad_aaid = true, -- The identifier of the app of Xiaomi for advertising (AAID)
    op_security_uuid = true, -- OnePlus
    ['com.baidu.deviceid'] = true, ['com.baidu.deviceid.v2'] = true, ['com.baidu.uuid'] = true, -- Shared by SDKs of Baidu
    bd_setting_i = true, -- The device id of the SDKs of Baidu (mtj-sdk, lbsyun)
    dxCRMxhQkdGePGnp = true, mqBRboGZkQPcAkyk = true, -- Of older SDKs of Baidu, like XPrivacyLua-EX lists them
}

-- Whether a setting is an identifier: of IDENTIFIERS, or of the ids of Huawei (pps_ and a name ending with id, also
-- encrypted with _c, like pps_oaid_c and pps_vaid); not other settings of Huawei, like pps_version
function identifier(key)
    return IDENTIFIERS[key] ~= nil or string.match(key, '^pps_%l+id$') ~= nil or string.match(key, '^pps_%l+id_c$') ~= nil
end

-- The settings of the always-on VPN
local ALWAYSON = { always_on_vpn_app = true, always_on_vpn_lockdown = true, always_on_vpn_lockdown_whitelist = true }

-- Developer options, USB and wireless debugging and Stay awake (the power sources, 0 by default) appear off, and so do
-- the options which Settings turns off with developer options (DevelopmentSettingsDashboardFragment), like Show taps
-- and the app to debug
local DEVELOPER = {
    adb_enabled = '0', adb_wifi_enabled = '0', development_settings_enabled = '0', stay_on_while_plugged_in = '0',
    show_touches = '0', pointer_location = '0', debug_view_attributes = '0', wait_for_debugger = '0',
    always_finish_activities = '0', debug_app = NONE,
}

-- The other settings which hooks change
local HOOKED = {
    android_id = true, bluetooth_name = true, device_name = true, wifi_p2p_device_name = true, boot_count = true,
    low_power = true, low_power_sticky = true, system_locales = true, private_dns_specifier = true, private_dns_mode = true,
    bluetooth_address = true,
}

-- Whether hooks change the setting; the hooked methods are called for all settings, so check the name first
function hooked(key)
    return key ~= nil and (HOOKED[key] ~= nil or DEVELOPER[key] ~= nil or ALWAYSON[key] ~= nil or COMPONENTS[key] ~= nil or PERSONAL[key] ~= nil or
            identifier(key))
end

-- The fake languages, normalized, without tags which are no language (und) and without repeats,
-- which LocaleList does not allow
function fakes(param)
    local locale = luajava.bindClass('java.util.Locale')
    local fake = {}
    local seen = { und = true }
    for _, item in ipairs(split(param:getSetting('value.locale') or '')) do
        local tag = locale:forLanguageTag(item):toLanguageTag()
        if not seen[tag] then
            seen[tag] = true
            fake[#fake + 1] = tag
        end
    end
    if #fake == 0 then
        fake = { 'en-US' }
    end
    return fake
end

-- The items of a comma separated list
function split(text)
    local items = {}
    for item in string.gmatch(text, '[^,%s]+') do
        items[#items + 1] = item
    end
    return items
end

-- The fake value of a setting for a variant: true and the fake value, or false when it stays
function fakesetting(param, name, key, result)
    if name == 'android_id' and key == 'android_id' then
        local fake = param:getSetting('value.android_id')
        if fake == nil then
            -- Only if the boot values could not be read: value.android_id comes from Fake values (new each boot)
            fake = '0000000000000000'
        end
        -- Settings.Secure.getString calls the hooked getStringForUser
        if fake == result then
            return false
        end
        return true, fake
    elseif name == 'device_name' and key == 'device_name' then
        -- The name of the device, which users set, often with their own name: the model, which Android gives when
        -- the user did not set one, the fake Build.MODEL (see generic_unknown_value)
        if result == 'unknown' then
            return false
        end
        return true, 'unknown'
    elseif name == 'boot_count' and key == 'boot_count' then
        -- How often the device started, which Android counts from the first start: of a device started once
        if result == nil or result == '1' then
            return false
        end
        return true, '1'
    elseif (name == 'bluetooth_name' and key == 'bluetooth_name') or
            -- The name of the device for Wi-Fi Direct, which AOSP does not set
            (name == 'device_name' and key == 'wifi_p2p_device_name') or
            (name == 'oaid' and identifier(key)) then
        return true
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
    elseif name == 'adb' and DEVELOPER[key] ~= nil then
        local fake = DEVELOPER[key]
        if fake == NONE then
            fake = nil
        end
        -- An option which was never set stays unset, like on a device where it was never changed
        if result == nil or fake == result then
            return false
        end
        return true, fake
    elseif name == 'locale' and key == 'system_locales' then
        -- The languages the user chose, the fake ones, like Settings.System.getStringForUser of locale
        local fake = table.concat(fakes(param), ',')
        if result == '' or fake == result then
            return false
        end
        return true, fake
    elseif name == 'battery' and (key == 'low_power' or key == 'low_power_sticky') then
        -- Battery saver appears off, like PowerManager.isPowerSaveMode, also the one kept on after charging (sticky)
        if result == '0' then
            return false
        end
        return true, '0'
    elseif name == 'network' and (key == 'private_dns_specifier' or key == 'private_dns_mode') then
        -- The private DNS server, like abc123.dns.nextdns.io, which names the account of the user: none, like
        -- LinkProperties.getPrivateDnsServerName gives, the mode of a server by the hostname the automatic one
        if key == 'private_dns_specifier' then
            if result == nil then
                return false
            end
            return true, nil
        end
        if result ~= 'hostname' then
            return false
        end
        return true, 'opportunistic'
    elseif name == 'vpn' and ALWAYSON[key] ~= nil then
        -- The always-on VPN, which names its app before Android 12 and tells there is one later; not set on a device
        if result == nil then
            return false
        end
        return true, nil
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
    locale = { 'locale' },
    vpn = { 'vpn' },
    network = { 'network' },
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
function selected(param)
    -- The query of API 26, which the other queries call, has the selection in its Bundle of query arguments
    local bundle = param:getArgument(2)
    if bundle == nil then
        return nil, nil
    end
    local selection = bundle:getString('android:query-arg-sql-selection')
    local args = bundle:getStringArray('android:query-arg-sql-selection-args')
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

-- Puts the rows of the fake cursor in the result of a query, and returns the result then. ContentResolver and
-- ContentProviderClient return the cursor of the provider in a wrapper of their own (CursorWrapperInner), which releases
-- the provider when the app closes it: the wrapper stays, with the fake cursor in it (CursorWrapper.mCursor), so the result
-- is of the class of Android. The fields are listed like LuaJ lists them, which Android does not report to StrictMode
-- as a use of a hidden API, unlike getDeclaredField. shortcut: getWrappedCursor gives a MatrixCursor instead of the cursor
-- of the binder and getWindow none, upgrade to a cursor of a CursorWindow when apps check those.
function replace(param, result, fake)
    if param:isInstance(result, 'android.database.CursorWrapper') then
        local ok, done = pcall(function()
            -- Lua sees a Class as its static members, so the methods of Class get the class as the object
            local fields = luajava.bindClass('java.lang.Class').getDeclaredFields(luajava.bindClass('android.database.CursorWrapper'))
            for index = 1, fields['length'] do
                local field = fields[index]
                if field:getName() == 'mCursor' then
                    local inner = result:getWrappedCursor()
                    field:setAccessible(true)
                    field:set(result, fake)
                    inner:close()
                    return true
                end
            end
            return false
        end)
        if ok and done then
            return result
        end
    end
    -- A cursor of another class, or a wrapper which Android does not let change, is replaced by the fake one itself
    result:close()
    param:setResult(fake)
    return fake
end

-- The cursor of the same query with the name column last, for a query of all settings without it, which tells which value
-- is which; nil when it fails, like when the app cancelled the query
function named(param, columns)
    local projection = {}
    for index = 1, columns['length'] do
        projection[index] = columns[index]
    end
    projection[#projection + 1] = 'name'
    local ok, cursor = pcall(param.unhooked, param, function()
        return param:getThis():query(param:getArgument(0), projection, param:getArgument(2), param:getArgument(3))
    end)
    return (ok and cursor or nil)
end

-- The methods of the settings provider which read settings
local GETTERS = {
    GET_secure = true, GET_system = true, GET_global = true,
    LIST_secure = true, LIST_system = true, LIST_global = true,
}

-- Apps can read settings from the settings provider too, which Settings.*.getString use:
-- call(GET_secure, name) returns a bundle with the value, call(LIST_secure) all settings
-- and a query rows of names and values
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
        authority = (string.gsub(authority, '^.*@', ''))
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
    -- The rows come from the cursor of the provider, or from the same query with the name column for all settings
    -- without it; names are the settings of the rows a hook of another group already changed
    local source = result
    local names
    if segments:size() == 2 then
        pathKey = segments:get(1)
    elseif nameIndex < 0 then
        local selection
        pathKey, selection = selected(param)
        if selection == nil then
            if result:getCount() == 0 then
                return false
            end
            names = param:getValue('settings.names', result)
            if names == nil then
                source = named(param, columns)
                -- shortcut: which value is which cannot be told without the names, so there are none,
                -- upgrade when apps query all values with a cancelled or failing signal
                if source == nil then
                    replace(param, result, luajava.newInstance('android.database.MatrixCursor', columns))
                    return true
                end
                nameIndex = columns['length']
            end
        end
    end

    local fakecursor = luajava.newInstance('android.database.MatrixCursor', columns)
    local changed = false
    local kept = {}
    local position = source:getPosition()
    source:moveToPosition(-1)
    local row = 0
    while source:moveToNext() do
        row = row + 1
        local key = pathKey
        if names ~= nil then
            key = names[row]
        elseif nameIndex >= 0 then
            key = source:getString(nameIndex)
        end
        -- A setting which is not set has no row
        local values = {}
        local missing = false
        for index = 0, columns['length'] - 1 do
            local value
            local kind = source:getType(index)
            if kind == 1 then
                value = source:getLong(index)
            elseif kind == 2 then
                value = source:getDouble(index)
            elseif kind == 3 then
                value = source:getString(index)
            elseif kind == 4 then
                value = source:getBlob(index)
            end
            if index == valueIndex and value ~= nil and hooked(key) then
                local hit, fakevalue = fakeany(param, group, key, value)
                if hit then
                    changed = true
                    value = fakevalue
                    missing = (value == nil)
                end
            end
            values[index] = value
        end
        if not missing then
            kept[#kept + 1] = key
            local fakerow = fakecursor:newRow()
            for index = 0, columns['length'] - 1 do
                fakerow:add(values[index])
            end
        end
    end

    if source ~= result then
        param:unhooked(function() source:close() end)
    elseif not changed then
        result:moveToPosition(position)
    end
    if not changed then
        fakecursor:close()
        return false
    end
    local faked = replace(param, result, fakecursor)
    -- The hooks of the other groups get these rows, which have no names, and find their names here
    if source ~= result or names ~= nil then
        param:putValue('settings.names', kept, faked)
    end
    return true
end

function after(hook, param)
    -- The getters are called for all settings, so check the name first; the second argument of
    -- the provider hooks is the method of call (GET_secure, LIST_secure), the name of the setting for
    -- ContentProviderClient.call(method, arg, extras) or the projection of a query
    local key = param:getArgument(1)
    -- ContentProviderClient.call(LIST_secure, arg, extras) lists all settings, whatever the argument
    local method = param:getArgument(0)
    local listing = (type(method) == 'string' and string.sub(method, 1, 5) == 'LIST_')
    local getter = (not listing and type(key) == 'string' and string.sub(key, 1, 4) ~= 'GET_' and
            string.sub(key, 1, 5) ~= 'LIST_')
    if getter and not hooked(key) then
        return false
    end

    -- Hook names are like Function/variant
    local name = string.match(hook:getName(), '/(.*)$')
    local class = hook:getClassName()
    if class == 'android.content.ContentResolver' or class == 'android.content.ContentProviderClient' then
        return provider(hook, param, name)
    end
    -- A getter without a name, like Settings.Secure.getString(cr, null), is no call of the provider
    if not getter then
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
