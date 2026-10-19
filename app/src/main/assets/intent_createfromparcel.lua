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
            action == 'android.intent.action.PACKAGE_VERIFIED') then
        local uriClass = luajava.bindClass('android.net.Uri')
        local uri = uriClass:parse('package:' .. param:getPackageName())
        intent:setData(uri)
        return true
    elseif name == 'package' and (action == 'android.intent.action.EXTERNAL_APPLICATIONS_AVAILABLE' or
            action == 'android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE' or
            action == 'android.intent.action.PACKAGES_SUSPENDED' or
            action == 'android.intent.action.PACKAGES_UNSUSPENDED') then
        local stringClass = luajava.bindClass('java.lang.String')
        local arrayClass = luajava.bindClass('java.lang.reflect.Array')
        local stringArray = arrayClass:newInstance(stringClass, 0)
        intent:putExtra('android.intent.extra.changed_package_list', stringArray)
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
    elseif name == 'message' and action == 'android.provider.Telephony.SMS_RECEIVED' then
        local objectClass = luajava.bindClass('java.lang.Object')
        local arrayClass = luajava.bindClass('java.lang.reflect.Array')
        local objectArray = arrayClass:newInstance(objectClass, 0)
        intent:putExtra('pdus', objectArray)
        return true
    else
        return false
    end
end
