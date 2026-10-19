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

function before(hook, param)
    local uri = param:getArgument(0)
    if uri == nil then
        return false
    end

    local path = uri:getPath()
    if path == nil then
        return false
    end

    -- Hook names are like Function/variant
    local func, name = string.match(hook:getName(), '^([^/]*)/?(.*)$')
    local bundled = (string.sub(func, -2) == '26')
    local authority = uri:getAuthority()
    if name == 'contacts' and authority == 'com.android.contacts' then
        local prefix = string.gmatch(path, '[^/]+')()
        if prefix == 'provider_status' then
            return false
        end

        local starred = param:getSetting('contacts.starred')
        if starred ~= nil and
                (prefix == 'contacts' or prefix == 'raw_contacts' or prefix == 'data') then
            local where
            if bundled then
                local bundle = param:getArgument(2)
                if bundle == nil then
                    bundle = luajava.newInstance('android.os.Bundle')
                    param:setArgument(2, bundle)
                end
                where = bundle:getString('android:query-arg-sql-selection')
            else
                where = param:getArgument(2)
            end

            if where == nil or where == '' then
                where = '(starred = ' .. starred .. ')'
            else
                where = '(starred = ' .. starred .. ') AND (' .. where .. ')'
            end

            if bundled then
                param:getArgument(2):putString('android:query-arg-sql-selection', where)
            else
                param:setArgument(2, where)
            end
        end
    end

    return false
end

function after(hook, param)
    local uri = param:getArgument(0)
    local cursor = param:getResult()
    if uri == nil or cursor == nil then
        return false
    end

    -- Hook names are like Function/variant
    local func, name = string.match(hook:getName(), '^([^/]*)/?(.*)$')
    local authority = uri:getAuthority()

    if (name == 'blockednumber' and authority == 'com.android.blockednumber') or
            (name == 'calendars' and authority == 'com.android.calendar') or
            (name == 'call_log' and authority == 'call_log') or
            (name == 'call_log' and authority == 'call_log_shadow') or
            (name == 'contacts' and authority == 'icc') or
            (name == 'contacts' and authority == 'com.android.contacts') or
            (name == 'gsf_id' and authority == 'com.google.android.gsf.gservices') or
            (name == 'oaid' and authority == 'com.vivo.vms.IdProvider') or
            (name == 'oaid' and authority == 'com.meizu.flyme.openidsdk') or
            (name == 'fb_attribution' and authority == 'com.facebook.katana.provider.AttributionIdProvider') or
            (name == 'fb_attribution' and authority == 'com.facebook.wakizashi.provider.AttributionIdProvider') or
            (name == 'mmssms' and authority == 'mms') or
            (name == 'mmssms' and authority == 'sms') or
            (name == 'mmssms' and authority == 'mms-sms') or
            (name == 'mmssms' and authority == 'com.google.android.apps.messaging.shared.datamodel.BugleContentProvider') or
            (name == 'voicemail' and authority == 'com.android.voicemail') then

        if name == 'contacts' and authority == 'com.android.contacts' then
            local path = uri:getPath()
            if path == nil then
                return false
            end

            local prefix = string.gmatch(path, '[^/]+')()
            if prefix == 'provider_status' then
                return false
            end

            local starred = param:getSetting('contacts.starred')
            if starred ~= nil and
                    (prefix == 'contacts' or prefix == 'raw_contacts' or prefix == 'data') then
                return true
            end
        end

        if name == 'gsf_id' then
            local args
            if string.sub(func, -2) == '26' then
                local bundle = param:getArgument(2)
                if bundle == nil then
                    return false
                end
                args = bundle:getStringArray('android:query-arg-sql-selection-args')
            else
                args = param:getArgument(3)
            end
            if args == nil then
                return false
            end

            local found = false
            for index = 1, args['length'] do
                if args[index] == 'android_id' then
                    found = true
                    break
                end
            end

            if not found then
                return false
            end
        end

        local fake = luajava.newInstance('android.database.MatrixCursor', cursor:getColumnNames())

        param:setResult(fake)
        return true
    else
        return false
    end
end