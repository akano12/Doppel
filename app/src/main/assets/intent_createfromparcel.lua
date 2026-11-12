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

-- The functions of the USB connection (UsbManager), which are extras of USB_STATE, like adb for USB debugging
local USB_FUNCTIONS = { 'mtp', 'ptp', 'rndis', 'midi', 'accessory', 'audio_source', 'ncm', 'uvc' }

-- The actions of the variant package with what they tell: a package which becomes the app itself (own),
-- lists of packages (lists) or the user id of a removed app (uid); the other intents go by without a lookup
local PACKAGE_ACTIONS = {}
for kind, actions in pairs({
    own = { 'PACKAGE_ADDED', 'PACKAGE_CHANGED', 'PACKAGE_DATA_CLEARED', 'PACKAGE_FIRST_LAUNCH', 'PACKAGE_FULLY_REMOVED',
            'PACKAGE_INSTALL', 'PACKAGE_NEEDS_VERIFICATION', 'PACKAGE_REMOVED', 'PACKAGE_REPLACED',
            'PACKAGE_RESTARTED', 'PACKAGE_UNSTOPPED', 'PACKAGE_VERIFIED', 'QUERY_PACKAGE_RESTART' },
    lists = { 'EXTERNAL_APPLICATIONS_AVAILABLE', 'EXTERNAL_APPLICATIONS_UNAVAILABLE', 'PACKAGES_SUSPENDED',
              'PACKAGES_UNSUSPENDED' },
    uid = { 'UID_REMOVED' } }) do
    for _, action in ipairs(actions) do
        PACKAGE_ACTIONS['android.intent.action.' .. action] = kind
    end
end

-- Extras left out of broadcasts, per variant and action: the names of the Bluetooth devices around and the name of
-- the device, which the hooks of Bluetooth hide, of each action of Bluetooth (network), whose device is a stand-in (see
-- DEVICE); on Android 8.1 the access point of the Wi-Fi state and the network name of the connectivity (network); the
-- number of a call (call_log)
local BLUETOOTH = { 'android.bluetooth.device.extra.NAME', 'android.bluetooth.adapter.extra.LOCAL_NAME' }
-- The device a broadcast of Bluetooth is about, which Android always sends with it, so receivers use it without a check:
-- a device of the address Android gives for hidden ones, and the broadcast is marked, so it does not reach the receivers
-- (see receiver_skip), like on a device without the devices around, which the hooks of Bluetooth hide
local DEVICE = 'android.bluetooth.device.extra.DEVICE'
local HIDDEN_ADDRESS = '02:00:00:00:00:00'
-- The active device of Headset and A2DP, of which Android sends a null one also without devices (see wiredcleared)
local HEADSET_ACTIVE = 'android.bluetooth.headset.profile.action.ACTIVE_DEVICE_CHANGED'
local A2DP_ACTIVE = 'android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED'
-- The legacy network type of a VPN (ConnectivityManager.TYPE_VPN)
local TYPE_VPN = 17
local EXTRAS = {
    network = {
        ['android.net.wifi.STATE_CHANGE'] = { 'bssid' },
        ['android.net.conn.CONNECTIVITY_CHANGE'] = { 'extraInfo' },
        -- The saved network that changed, with its name, of Android 8.1 to 10 for any app; without it, like the
        -- broadcast Android sends when several networks changed (multipleChanges)
        ['android.net.wifi.CONFIGURED_NETWORKS_CHANGE'] = { 'wifiConfiguration' },
    },
    call_log = {
        ['android.intent.action.PHONE_STATE'] = { 'incoming_number' },
        ['android.intent.action.NEW_OUTGOING_CALL'] = { 'android.intent.extra.PHONE_NUMBER' },
    },
}

-- The names and codes of the carrier in broadcasts (variant operator), which get the fakes of test_network: the names
-- of the network (plmn), of the SIM (spn) and for data that the status bar shows (ServiceStateTracker), by the extras
-- of Android 8.1 to 10 and of 11 and later, sticky for any app; the strings Android puts beside the ServiceState, whose
-- getters are hooked (TelephonyRegistry), sticky for any app up to Android 10, the raw names from Android 10
local OPERATOR = {
    ['android.provider.Telephony.SPN_STRINGS_UPDATED'] = { 'plmn', 'spn', 'spnData' },
    ['android.telephony.action.SERVICE_PROVIDERS_UPDATED'] = { 'android.telephony.extra.PLMN',
        'android.telephony.extra.SPN', 'android.telephony.extra.DATA_SPN' },
    ['android.intent.action.SERVICE_STATE'] = { 'operator-alpha-long', 'operator-alpha-short', 'operator-numeric',
        'data-operator-alpha-long', 'data-operator-alpha-short', 'data-operator-numeric', 'operator-alpha-long-raw',
        'operator-alpha-short-raw' },
}

-- Whether a name of the network is a text of Android without service, No service or Emergency calls only, which names
-- no carrier and stays, like on a device of the test network without service
-- shortcut: a sticky broadcast of before a change of the language of the system has the text of the old language,
-- which becomes unknown; compare with the old language too when an app is seen to read it then
function noservice(text)
    local resources = luajava.bindClass('android.content.res.Resources'):getSystem()
    for _, name in ipairs({ 'lockscreen_carrier_default', 'emergency_calls_only' }) do
        local id = resources:getIdentifier(name, 'string', 'android')
        if id ~= 0 and resources:getString(id) == text then
            return true
        end
    end
    return false
end

-- The extras a variant leaves out of a broadcast, nil when none
function extrasof(name, action)
    local actions = EXTRAS[name]
    if actions == nil then
        return nil
    end
    if name == 'network' and string.sub(action, 1, 18) == 'android.bluetooth.' then
        return BLUETOOTH
    end
    return actions[action]
end

-- Whether a broadcast of Bluetooth with a null device is one which Android sends also without devices: a wired headset
-- which is attached clears the active device of Headset (Android 9 and later) and of A2DP (Android 9 and 10, which
-- broadcast it also without an active one; A2dpService.removeActiveDevice returns without one from Android 11), like
-- ActiveDeviceManager.wiredAudioDeviceConnected with its isWiredAudioHeadset. Android sends the other null devices only
-- after a device was active, like when headphones turn off, so those are marked too. The audio devices are the ones
-- the app gets, so with those of Record.Audio, which leave out a headset, it is marked, like on a device without one.
-- shortcut: an active device which goes away while a wired headset is attached still arrives, and the null active
-- device A2DP of Android 9 and 10 sends each time Bluetooth turns on or off is left out; upgrade if those can be told
-- apart from the ones of devices
function wiredcleared(action, param)
    if action ~= HEADSET_ACTIVE and
            (action ~= A2DP_ACTIVE or luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 30) then
        return false
    end
    local ok, wired = pcall(function()
        local info = luajava.bindClass('android.media.AudioDeviceInfo')
        local types = { [info.TYPE_WIRED_HEADSET] = true, [info.TYPE_WIRED_HEADPHONES] = true, [info.TYPE_USB_HEADSET] = true }
        local audio = param:getApplicationContext():getSystemService('audio')
        local devices = audio:getDevices(luajava.bindClass('android.media.AudioManager').GET_DEVICES_ALL)
        for index = 1, devices['length'] do
            if types[devices[index]:getType()] then
                return true
            end
        end
        return false
    end)
    return ok and wired
end

function after(hook, param)
    local intent = param:getResult()
    if intent == nil then
        return false
    end

    local action = intent:getAction()
    if action == nil then
        return false
    end

    -- Hook names are like Function/variant
    local name = string.match(hook:getName(), '/(.*)$')

    local remove = extrasof(name, action)
    if remove ~= nil then
        local extras = intent:getExtras()
        if extras == nil then
            return false
        end
        local removed = false
        for _, key in ipairs(remove) do
            if extras:containsKey(key) then
                extras:remove(key)
                removed = true
            end
        end
        -- A null device, like the active device of a profile when none is, stays null
        if remove == BLUETOOTH and extras:containsKey(DEVICE) then
            local device = extras:getParcelable(DEVICE)
            if device ~= nil then
                local adapter = luajava.bindClass('android.bluetooth.BluetoothAdapter'):getDefaultAdapter()
                if adapter == nil then
                    extras:remove(DEVICE)
                else
                    extras:putParcelable(DEVICE, adapter:getRemoteDevice(HIDDEN_ADDRESS))
                end
            end
            if device ~= nil or not wiredcleared(action, param) then
                param:putValue('bluetooth.device', true, intent)
                removed = true
            end
        end
        if removed then
            if action == 'android.net.wifi.CONFIGURED_NETWORKS_CHANGE' then
                extras:putBoolean('multipleChanges', true)
            elseif action == 'android.intent.action.NEW_OUTGOING_CALL' then
                -- Android sends the number also as the result data of the broadcast, which receiver_resultdata hides
                param:putValue('call.outgoing', intent:getStringExtra('android.intent.extra.PHONE_NUMBER'),
                        param:getApplicationContext())
            end
            intent:replaceExtras(extras)
        end
        return removed
    end

    -- Processes of the system manage the packages of all apps
    local kind = (name == 'package' and PACKAGE_ACTIONS[action] or nil)
    if name == 'package' and (kind == nil or param:isSystem()) then
        return false
    end

    if kind == 'own' then
        -- Packages which stay visible, like the app itself and system apps, stay, like their probes (see package_probe),
        -- also the components of the app which it changed itself
        local data = intent:getData()
        if data ~= nil and data:getScheme() == 'package' and param:isPackageVisible(data:getSchemeSpecificPart()) then
            return false
        end

        local uriClass = luajava.bindClass('android.net.Uri')
        local uri = uriClass:parse('package:' .. param:getPackageName())
        intent:setData(uri)

        -- The extras are of the app itself too, like the data: its user id, its package, like for PACKAGE_DATA_CLEARED,
        -- and for PACKAGE_CHANGED the changed components, which are the package when all of it changed;
        -- Bundle.putInt, because Lua would select Intent.putExtra(String, byte) for small numbers
        local extras = intent:getExtras()
        if extras ~= nil then
            if extras:containsKey('android.intent.extra.UID') then
                extras:putInt('android.intent.extra.UID', param:getUid())
            end
            if extras:containsKey('android.intent.extra.PACKAGE_NAME') then
                extras:putString('android.intent.extra.PACKAGE_NAME', param:getPackageName())
            end
            if extras:containsKey('android.intent.extra.changed_component_name') then
                extras:putString('android.intent.extra.changed_component_name', param:getPackageName())
            end
            -- The packages Settings asks about with QUERY_PACKAGE_RESTART
            if extras:containsKey('android.intent.extra.PACKAGES') then
                local own = luajava.bindClass('java.lang.reflect.Array'):newInstance(
                        luajava.bindClass('java.lang.String'), 1)
                own[1] = param:getPackageName()
                extras:putStringArray('android.intent.extra.PACKAGES', own)
            end
            if extras:containsKey('android.intent.extra.changed_component_name_list') then
                local stringClass = luajava.bindClass('java.lang.String')
                local arrayClass = luajava.bindClass('java.lang.reflect.Array')
                local stringArray = arrayClass:newInstance(stringClass, 1)
                stringArray[1] = param:getPackageName()
                extras:putStringArray('android.intent.extra.changed_component_name_list', stringArray)
            end
            intent:replaceExtras(extras)
        end
        return true
    elseif kind == 'lists' then
        -- The visible packages with their user ids, which are at the same index, empty when none is left,
        -- because receivers iterate them
        local extras = intent:getExtras()
        if extras == nil then
            return false
        end
        local packages = extras:getStringArray('android.intent.extra.changed_package_list')
        local uids = extras:getIntArray('android.intent.extra.changed_uid_list')
        local keptPackages, keptUids = {}, {}
        local count = (packages ~= nil and packages['length'] or 0)
        for index = 1, count do
            if param:isPackageVisible(packages[index]) then
                keptPackages[#keptPackages + 1] = packages[index]
                if uids ~= nil and index <= uids['length'] then
                    keptUids[#keptUids + 1] = uids[index]
                end
            end
        end
        if #keptPackages == count then
            return false
        end
        local arrayClass = luajava.bindClass('java.lang.reflect.Array')
        local names = arrayClass:newInstance(luajava.bindClass('java.lang.String'), #keptPackages)
        for index = 1, #keptPackages do
            names[index] = keptPackages[index]
        end
        extras:putStringArray('android.intent.extra.changed_package_list', names)
        if uids ~= nil then
            local ids = arrayClass:newInstance(luajava.bindClass('java.lang.Integer').TYPE, #keptUids)
            for index = 1, #keptUids do
                ids[index] = keptUids[index]
            end
            extras:putIntArray('android.intent.extra.changed_uid_list', ids)
        end
        intent:replaceExtras(extras)
        return true
    elseif kind == 'uid' then
        -- The user id of a removed app can come with its package
        local extras = intent:getExtras()
        if extras == nil or not extras:containsKey('android.intent.extra.PACKAGE_NAME') then
            return false
        end
        extras:remove('android.intent.extra.PACKAGE_NAME')
        intent:replaceExtras(extras)
        return true
    elseif name == 'battery' and action == 'android.intent.action.BATTERY_CHANGED' then
        -- The level in steps of 10 percent, but at least 15 percent like battery, and typical values instead of
        -- exact measurements, which change slowly and together identify a device across apps
        -- Bundle.putInt, because Lua would select Intent.putExtra(String, byte) for small numbers
        local extras = intent:getExtras()
        if extras == nil then
            return false
        end
        local old = extras:getInt('level', -1)
        local scale = extras:getInt('scale', 100)
        local level = old
        if old >= 0 and scale > 0 then
            level = math.max(math.floor(15 * scale / 100), math.floor(math.floor(old * 10 / scale + 0.5) * scale / 10))
            extras:putInt('level', level)
        end
        if extras:containsKey('voltage') then
            extras:putInt('voltage', 3800)
        end
        if extras:containsKey('temperature') then
            extras:putInt('temperature', 250)
        end
        -- The details of charging, neutral values instead of none, since Android always sends them: no charging
        -- current and voltage, the charge of a 4000 mAh battery at the level, no cycles, the default policy of
        -- charging (CHARGING_POLICY_DEFAULT, 1) instead of one the user chose, like adaptive or long life, while
        -- a device without policies (0) stays, and the level of the capacity by the level (BATTERY_CAPACITY_LEVEL_*)
        local percent = (scale > 0 and level * 100 / scale or 0)
        for key, value in pairs({ charge_counter = math.floor(percent * 40000), max_charging_current = 0,
                                  max_charging_voltage = 0, ['android.os.extra.CYCLE_COUNT'] = 0 }) do
            if extras:containsKey(key) then
                extras:putInt(key, value)
            end
        end
        if extras:getInt('android.os.extra.CHARGING_STATUS', 0) > 1 then
            extras:putInt('android.os.extra.CHARGING_STATUS', 1)
        end
        local capacity = extras:getInt('android.os.extra.CAPACITY_LEVEL', -1)
        if capacity > 0 then
            extras:putInt('android.os.extra.CAPACITY_LEVEL',
                    percent >= 100 and 5 or percent >= 80 and 4 or percent >= 20 and 3 or 2)
        end
        intent:replaceExtras(extras)
        -- The values shown in the log are the level, which can stay, like 100, when only the other extras changed
        if level == old then
            return true
        end
        return true, tostring(old), tostring(level)
    elseif name == 'battery' and action == 'android.os.action.POWER_SAVE_MODE_CHANGING' then
        -- The battery saver going on, which isPowerSaveMode hides: off
        local extras = intent:getExtras()
        if extras == nil or not extras:getBoolean('mode', false) then
            return false
        end
        extras:putBoolean('mode', false)
        intent:replaceExtras(extras)
        return true, 'true', 'false'
    elseif name == 'debugging' and action == 'android.hardware.usb.action.USB_STATE' then
        -- The sticky broadcast of the USB connection has the function adb when USB debugging is on,
        -- also without a cable; without USB debugging, charging only is the function mtp (UsbDeviceManager)
        local extras = intent:getExtras()
        if extras == nil or not extras:containsKey('adb') then
            return false
        end
        extras:remove('adb')
        local functions = false
        for _, usb in ipairs(USB_FUNCTIONS) do
            if extras:containsKey(usb) then
                functions = true
            end
        end
        if not functions then
            extras:putBoolean('mtp', true)
        end
        intent:replaceExtras(extras)
        return true
    elseif name == 'message' and (action == 'android.provider.Telephony.SMS_RECEIVED' or
            action == 'android.provider.Telephony.SMS_DELIVER' or
            action == 'android.intent.action.DATA_SMS_RECEIVED') then
        -- No messages, in the byte[][] of the messages Android sends (InboundSmsHandler), so the typed getter of
        -- Android 13 finds them
        -- shortcut: a broadcast without messages, which Android never sends, drop it once a hook of the dispatch exists
        local pdus = luajava.bindClass('java.lang.reflect.Array'):newInstance(luajava.bindClass('[B'), 0)
        intent:putExtra('pdus', pdus)
        return true
    elseif name == 'message' and (action == 'android.provider.Telephony.WAP_PUSH_RECEIVED' or
            action == 'android.provider.Telephony.WAP_PUSH_DELIVER') then
        -- The notification of an MMS message (data), with the sender and the subject, and the address of the push;
        -- empty, because receivers parse it (PduParser), which then finds nothing; the DELIVER actions go to the
        -- default SMS app only
        local extras = intent:getExtras()
        if extras == nil then
            return false
        end
        local arrayClass = luajava.bindClass('java.lang.reflect.Array')
        local byteType = luajava.bindClass('java.lang.Byte').TYPE
        for _, extra in ipairs({ 'data', 'header' }) do
            if extras:containsKey(extra) then
                extras:putByteArray(extra, arrayClass:newInstance(byteType, 0))
            end
        end
        extras:remove('address')
        intent:replaceExtras(extras)
        return true
    elseif name == 'timezone' and action == 'android.intent.action.TIMEZONE_CHANGED' then
        -- The new time zone of the device, which the fake one replaces, like timezone (setting value.timezone), for
        -- the broadcasts which still reach a receiver (see receiver_skip)
        local extras = intent:getExtras()
        if extras == nil or not extras:containsKey('time-zone') then
            return false
        end
        local id = param:getSetting('value.timezone')
        if id == nil or id == '' then
            id = 'UTC'
        end
        local old = extras:getString('time-zone')
        if old == id then
            return false
        end
        extras:putString('time-zone', id)
        intent:replaceExtras(extras)
        return true, old, id
    elseif name == 'operator' and OPERATOR[action] ~= nil then
        -- The codes 00101 and the names unknown, like getNetworkOperator and getNetworkOperatorName; empty and null
        -- values stay, like the operator of the copy Android sends to apps without the location permission
        local extras = intent:getExtras()
        if extras == nil then
            return false
        end
        local old, new = {}, {}
        for _, key in ipairs(OPERATOR[action]) do
            local value = extras:getString(key)
            local fake = (string.find(key, 'numeric', 1, true) and '00101' or 'unknown')
            local plmn = (key == 'plmn' or key == 'android.telephony.extra.PLMN')
            if value ~= nil and value ~= '' and value ~= fake and not (plmn and noservice(value)) then
                extras:putString(key, fake)
                old[#old + 1] = value
                new[#new + 1] = fake
            end
        end
        if #old == 0 then
            return false
        end
        intent:replaceExtras(extras)
        return true, table.concat(old, ','), table.concat(new, ',')
    elseif name == 'vpn' and action == 'android.net.conn.CONNECTIVITY_CHANGE' then
        -- A VPN which connects or disconnects has a broadcast of the type VPN of its own (LegacyTypeTracker), which a
        -- device without a VPN never sends. It becomes the broadcast of the network the VPN runs over connecting
        -- (makeGeneralIntent with the state of sendLegacyNetworkBroadcast), which is what the sticky broadcast is
        -- without a VPN; the network is the one Android reports as active, like vpn does. Its extras come from the
        -- getters of its info, so they agree with the hooks of the info, like a network name Read.Network hides. It is
        -- marked, so it reaches only a receiver which registers, as its first sticky broadcast (see receiver_skip).
        local extras = intent:getExtras()
        if extras == nil or extras:getInt('networkType', -1) ~= TYPE_VPN then
            return false
        end
        param:putValue('vpn.connectivity', true, intent)
        local ok, info = pcall(function()
            return param:getApplicationContext():getSystemService('connectivity'):getActiveNetworkInfo()
        end)
        if not ok or info == nil then
            -- The type of the info of the VPN, which the hooks of the info fake while it is connected
            -- shortcut: without the permission to read the active network or without one, the sticky broadcast of a VPN
            -- which disconnected, which a receiver reads when it registers, stays, since a connected network would tell
            -- the app it is online and the info of a VPN which is not connected keeps its type; upgrade once the type
            -- of the network below can be read after the VPN disconnected
            local vpn = extras:getParcelable('networkInfo')
            local networkType = (vpn ~= nil and vpn:getType() or TYPE_VPN)
            if networkType == TYPE_VPN then
                return false
            end
            extras:putInt('networkType', networkType)
            intent:replaceExtras(extras)
            return true, tostring(TYPE_VPN), tostring(networkType)
        end
        -- Connected without a reason, like sendLegacyNetworkBroadcast makes it; NetworkInfo.setDetailedState is in the SDK
        -- only from Android 11, so it is called only when it changes the info
        if not info:isConnected() or info:getReason() ~= nil then
            info:setDetailedState(luajava.bindClass('android.net.NetworkInfo$DetailedState').CONNECTED, nil, info:getExtraInfo())
        end
        local networkType = info:getType()
        extras:putParcelable('networkInfo', info)
        extras:putInt('networkType', networkType)
        for _, key in ipairs({ 'isFailover', 'reason', 'otherNetwork', 'noConnectivity' }) do
            extras:remove(key)
        end
        local extra = info:getExtraInfo()
        if extra == nil then
            extras:remove('extraInfo')
        else
            extras:putString('extraInfo', extra)
        end
        intent:replaceExtras(extras)
        return true, tostring(TYPE_VPN), tostring(networkType)
    else
        return false
    end
end
