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

-- The authority of a uri without the user, like 0@com.android.contacts, which apps can add for their own user
function authorityof(uri)
    local authority = uri:getAuthority()
    if authority == nil then
        return nil
    end
    return (string.gsub(authority, '^%d+@', ''))
end

-- Whether the selection of an app stays within its own parentheses, so the starred filter around it applies:
-- like 1) OR (1 would undo it; quoted text and identifiers are skipped, comments and more statements refused
function contained(where)
    local depth = 0
    local quote = nil
    for index = 1, string.len(where) do
        local c = string.sub(where, index, index)
        local next = string.sub(where, index + 1, index + 1)
        if quote ~= nil then
            if c == quote then
                quote = nil
            end
        elseif c == "'" or c == '"' or c == '`' then
            quote = c
        elseif c == '[' then
            quote = ']'
        elseif c == '(' then
            depth = depth + 1
        elseif c == ')' then
            depth = depth - 1
            if depth < 0 then
                return false
            end
        elseif c == ';' or (c == '-' and next == '-') or (c == '/' and next == '*') then
            return false
        end
    end
    return (depth == 0 and quote == nil)
end

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
    local authority = authorityof(uri)
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

            -- A selection which could undo the filter selects nothing, its arguments are left out with it
            local unsafe = false
            if where == nil or where == '' then
                where = '(starred = ' .. starred .. ')'
            elseif contained(where) then
                where = '(starred = ' .. starred .. ') AND (' .. where .. ')'
            else
                where = '0'
                unsafe = true
            end

            if bundled then
                param:getArgument(2):putString('android:query-arg-sql-selection', where)
                if unsafe then
                    param:getArgument(2):remove('android:query-arg-sql-selection-args')
                end
            else
                param:setArgument(2, where)
                if unsafe then
                    param:setArgument(3, nil)
                end
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
    local authority = authorityof(uri)

    -- The authority contacts is of the former contacts API (Contacts.People), served by the same provider
    if (name == 'blockednumber' and authority == 'com.android.blockednumber') or
            (name == 'calendars' and authority == 'com.android.calendar') or
            (name == 'call_log' and authority == 'call_log') or
            (name == 'call_log' and authority == 'call_log_shadow') or
            (name == 'contacts' and authority == 'icc') or
            (name == 'contacts' and authority == 'contacts') or
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