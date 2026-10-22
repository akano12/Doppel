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

-- The functions of the USB connection (UsbManager), which are extras of USB_STATE, like adb for USB debugging
local USB_FUNCTIONS = { 'mtp', 'ptp', 'rndis', 'midi', 'accessory', 'audio_source', 'ncm', 'uvc' }

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

    if name == 'package' and (action == 'android.intent.action.PACKAGE_ADDED' or
            action == 'android.intent.action.PACKAGE_CHANGED' or
            action == 'android.intent.action.PACKAGE_DATA_CLEARED' or
            action == 'android.intent.action.PACKAGE_FIRST_LAUNCH' or
            action == 'android.intent.action.PACKAGE_FULLY_REMOVED' or
            action == 'android.intent.action.PACKAGE_INSTALL' or
            action == 'android.intent.action.PACKAGE_NEEDS_VERIFICATION' or
            action == 'android.intent.action.PACKAGE_REMOVED' or
            action == 'android.intent.action.PACKAGE_REPLACED' or
            action == 'android.intent.action.PACKAGE_RESTARTED' or
            action == 'android.intent.action.PACKAGE_UNSTOPPED' or
            action == 'android.intent.action.PACKAGE_VERIFIED') then
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
    elseif name == 'package' and (action == 'android.intent.action.EXTERNAL_APPLICATIONS_AVAILABLE' or
            action == 'android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE' or
            action == 'android.intent.action.PACKAGES_SUSPENDED' or
            action == 'android.intent.action.PACKAGES_UNSUSPENDED') then
        -- No packages and no user ids, empty, because receivers iterate them
        local extras = intent:getExtras()
        if extras == nil then
            return false
        end
        local arrayClass = luajava.bindClass('java.lang.reflect.Array')
        extras:putStringArray('android.intent.extra.changed_package_list',
                arrayClass:newInstance(luajava.bindClass('java.lang.String'), 0))
        if extras:containsKey('android.intent.extra.changed_uid_list') then
            extras:putIntArray('android.intent.extra.changed_uid_list',
                    arrayClass:newInstance(luajava.bindClass('java.lang.Integer').TYPE, 0))
        end
        intent:replaceExtras(extras)
        return true
    elseif name == 'package' and action == 'android.intent.action.UID_REMOVED' then
        -- The user id of a removed app can come with its package
        local extras = intent:getExtras()
        if extras == nil or not extras:containsKey('android.intent.extra.PACKAGE_NAME') then
            return false
        end
        extras:remove('android.intent.extra.PACKAGE_NAME')
        intent:replaceExtras(extras)
        return true
    elseif name == 'battery' and action == 'android.intent.action.BATTERY_CHANGED' then
        -- The level in steps of 10 percent and typical values instead of exact measurements,
        -- which change slowly and together identify a device across apps
        -- Bundle.putInt, because Lua would select Intent.putExtra(String, byte) for small numbers
        local extras = intent:getExtras()
        if extras == nil then
            return false
        end
        local old = extras:getInt('level', -1)
        local scale = extras:getInt('scale', 100)
        local level = old
        if old >= 0 and scale > 0 then
            level = math.floor(math.floor(old * 10 / scale + 0.5) * scale / 10)
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
            action == 'android.intent.action.DATA_SMS_RECEIVED') then
        local objectClass = luajava.bindClass('java.lang.Object')
        local arrayClass = luajava.bindClass('java.lang.reflect.Array')
        local objectArray = arrayClass:newInstance(objectClass, 0)
        intent:putExtra('pdus', objectArray)
        return true
    elseif name == 'message' and action == 'android.provider.Telephony.WAP_PUSH_RECEIVED' then
        -- The notification of an MMS message (data), with the sender and the subject, and the address of the push;
        -- empty, because receivers parse it (PduParser), which then finds nothing
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
    else
        return false
    end
end
