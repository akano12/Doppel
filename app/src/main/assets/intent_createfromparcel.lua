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

-- Extras left out of broadcasts, per variant and action: the Bluetooth devices around and their names and the name of
-- the device, which the hooks of Bluetooth hide, of each action of Bluetooth (network); on Android 8.1 the access point of the Wi-Fi state and the
-- network name of the connectivity (network); the number of a call (call_log)
local BLUETOOTH = { 'android.bluetooth.device.extra.DEVICE', 'android.bluetooth.device.extra.NAME',
    'android.bluetooth.adapter.extra.LOCAL_NAME' }
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
        if removed then
            if action == 'android.net.wifi.CONFIGURED_NETWORKS_CHANGE' then
                extras:putBoolean('multipleChanges', true)
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
        for _, extra in ipairs({ 'charge_counter', 'max_charging_current', 'max_charging_voltage',
                                 'android.os.extra.CYCLE_COUNT' }) do
            extras:remove(extra)
        end
        intent:replaceExtras(extras)
        -- The values shown in the log are the level, which can stay, like 100, when only the other extras changed
        if level == old then
            return true
        end
        return true, tostring(old), tostring(level)
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
        local objectClass = luajava.bindClass('java.lang.Object')
        local arrayClass = luajava.bindClass('java.lang.reflect.Array')
        local objectArray = arrayClass:newInstance(objectClass, 0)
        intent:putExtra('pdus', objectArray)
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
        -- The new time zone of the device, which the fake one replaces, like timezone (setting value.timezone)
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
    else
        return false
    end
end
