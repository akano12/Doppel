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

-- The authority of a uri without the user, like 0@com.android.contacts, which apps can add for their own user
function authorityof(uri)
    local authority = uri:getAuthority()
    if authority == nil then
        return nil
    end
    return (string.gsub(authority, '^.*@', ''))
end

-- Whether the selection of an app stays within its own parentheses, so the starred filter around it applies:
-- like 1) OR (1 would undo it; quoted text and identifiers are skipped, comments and more statements refused,
-- and subqueries too, which could count the contacts which are not starred
function contained(where)
    local depth = 0
    local quote = nil
    local plain = {}
    for index = 1, string.len(where) do
        local c = string.sub(where, index, index)
        local next = string.sub(where, index + 1, index + 1)
        if quote ~= nil then
            if c == quote then
                quote = nil
            end
            c = ' '
        elseif c == "'" or c == '"' or c == '`' then
            quote = c
            c = ' '
        elseif c == '[' then
            quote = ']'
            c = ' '
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
        table.insert(plain, c)
    end
    if string.find(' ' .. string.lower(table.concat(plain)) .. ' ', '[^%w_]select[^%w_]') then
        return false
    end
    return (depth == 0 and quote == nil)
end

-- The first part of the path, like contacts of content://com.android.contacts/contacts/1
function prefixof(uri)
    local path = uri:getPath()
    if path == nil then
        return nil
    end
    return string.gmatch(path, '[^/]+')()
end

-- The setting of the queries of contacts which are filtered for (not) starred contacts, else nil:
-- before filters them and after keeps the filtered result, which would else be replaced. Only 0 and 1, like
-- contentresolver_open: another value, like of a restored backup, would be SQL in the selection.
function starredof(name, uri, param)
    if name == 'contacts' and authorityof(uri) == 'com.android.contacts' then
        local prefix = prefixof(uri)
        -- The stream items of a contact, whose view has no column starred
        if (prefix == 'contacts' or prefix == 'raw_contacts' or prefix == 'data') and
                not string.find(uri:getPath(), '/stream_items', 1, true) then
            local starred = param:getSetting('contacts.starred')
            if starred == '0' or starred == '1' then
                return starred
            end
        end
    end
    return nil
end

-- Whether a query is of the contacts like a contact (Contacts.AggregationSuggestions), for which the provider ignores
-- the selection: the column starred is added to the query, and after filters the rows by it
function suggestions(uri)
    return string.find(uri:getPath(), '^/contacts/.*/suggestions') ~= nil
end

-- Whether before added the column to the query in progress on this thread
local added = false

function before(hook, param)
    local uri = param:getArgument(0)
    if uri == nil then
        return false
    end

    -- Hook names are like Function/variant
    local name = string.match(hook:getName(), '^[^/]*/?(.*)$')
    local starred = starredof(name, uri, param)
    if starred == nil then
        return false
    end

    if suggestions(uri) then
        local projection = param:getArgument(1)
        added = false
        if projection ~= nil then
            local present = false
            for index = 1, projection['length'] do
                present = present or projection[index] == 'starred'
            end
            if not present then
                local arrayClass = luajava.bindClass('java.lang.reflect.Array')
                local columns = arrayClass:newInstance(luajava.bindClass('java.lang.String'), projection['length'] + 1)
                for index = 1, projection['length'] do
                    columns[index] = projection[index]
                end
                columns[projection['length'] + 1] = 'starred'
                param:setArgument(1, columns)
                added = true
            end
        end
        return false
    end

    -- The query of API 26, which the other queries call, has the selection in its Bundle of query arguments
    -- A copy: the app could read the filter back from its own Bundle, and a reused Bundle would filter it again
    local bundle = param:getArgument(2)
    if bundle == nil then
        bundle = luajava.newInstance('android.os.Bundle')
    else
        bundle = luajava.newInstance('android.os.Bundle', bundle)
    end
    param:setArgument(2, bundle)
    local where = bundle:getString('android:query-arg-sql-selection')
    local order = bundle:getString('android:query-arg-sql-sort-order')
    -- Without it the provider sorts by the sort columns, joined like this (ContentResolver.createSqlSortClause)
    local columns = bundle:getStringArray('android:query-arg-sort-columns')
    if order == nil and columns ~= nil then
        local names = {}
        for index = 1, columns['length'] do
            names[index] = tostring(columns[index])
        end
        order = table.concat(names, ', ')
    end

    -- A selection which could undo the filter selects nothing, its arguments are left out with it;
    -- likewise for a sort order with a subquery, which is left out too
    local unsafe = false
    if order ~= nil and not contained(order) then
        bundle:remove('android:query-arg-sql-sort-order')
        bundle:remove('android:query-arg-sort-columns')
        where = '0'
        unsafe = true
    elseif where == nil or where == '' then
        where = '(starred = ' .. starred .. ')'
    elseif contained(where) then
        where = '(starred = ' .. starred .. ') AND (' .. where .. ')'
    else
        where = '0'
        unsafe = true
    end

    bundle:putString('android:query-arg-sql-selection', where)
    if unsafe then
        bundle:remove('android:query-arg-sql-selection-args')
    end

    return false
end

-- The values of Gservices without the GSF id: of keys, also of all keys starting with a prefix,
-- like android_ of content://com.google.android.gsf.gservices/prefix; the rows are a key and a value
function gservices(param, cursor)
    local fake = luajava.newInstance('android.database.MatrixCursor', cursor:getColumnNames())
    local removed = false
    -- Gservices answers a query of exact keys with a row per key, the value null for a key it does not have,
    -- and one of a prefix (the path prefix) with the keys it has
    local exact = (prefixof(param:getArgument(0)) ~= 'prefix')
    while cursor:moveToNext() do
        if cursor:getString(0) == 'android_id' then
            removed = true
            if exact then
                local row = fake:newRow()
                row:add('android_id')
                row:add(nil)
            end
        else
            local row = fake:newRow()
            for index = 0, cursor:getColumnCount() - 1 do
                row:add(cursor:getString(index))
            end
        end
    end

    if not removed then
        fake:close()
        cursor:moveToPosition(-1)
        return false
    end
    cursor:close()
    param:setResult(fake)
    return true
end

-- The SIM cards of the telephony provider (content://telephony/siminfo), which apps could read: the ICCID, the card id
-- and the IMSI like the getters of SubscriptionInfo give them, empty, and the number the fake one; the other columns
-- with their own types
local SIMINFO = { icc_id = '', card_id = '', imsi = false, number = true }

function siminfo(param, cursor)
    local names = cursor:getColumnNames()
    local faked = false
    for index = 1, cursor:getColumnCount() do
        if SIMINFO[names[index]] ~= nil then
            faked = true
        end
    end
    if not faked then
        return false
    end
    local number = param:getSetting('value.phone_number') or ''
    local fake = luajava.newInstance('android.database.MatrixCursor', names)
    while cursor:moveToNext() do
        local row = fake:newRow()
        for index = 0, cursor:getColumnCount() - 1 do
            local column = SIMINFO[names[index + 1]]
            local type = cursor:getType(index)
            if column ~= nil and type ~= 0 then
                if column == true then
                    row:add(number)
                elseif column == false then
                    row:add(nil)
                else
                    row:add(column)
                end
            elseif type == 1 then
                row:add(luajava.newInstance('java.lang.Long', cursor:getLong(index)))
            elseif type == 2 then
                row:add(luajava.newInstance('java.lang.Double', cursor:getDouble(index)))
            elseif type == 4 then
                row:add(cursor:getBlob(index))
            elseif type == 3 then
                row:add(cursor:getString(index))
            else
                row:add(nil)
            end
        end
    end
    cursor:close()
    param:setResult(fake)
    return true
end

-- The rows of the suggestions which are (not) starred, without the column before added
function starredrows(param, cursor, starred)
    local names = cursor:getColumnNames()
    local count = names['length'] - (added and 1 or 0)
    local columns = luajava.bindClass('java.lang.reflect.Array'):newInstance(luajava.bindClass('java.lang.String'), count)
    for index = 1, count do
        columns[index] = names[index]
    end
    local fake = luajava.newInstance('android.database.MatrixCursor', columns)
    local column = cursor:getColumnIndex('starred')
    while cursor:moveToNext() do
        if cursor:getString(column) == starred then
            local row = fake:newRow()
            for index = 0, count - 1 do
                row:add(cursor:getString(index))
            end
        end
    end
    added = false
    cursor:close()
    param:setResult(fake)
    return true
end

function after(hook, param)
    local uri = param:getArgument(0)
    local cursor = param:getResult()
    if uri == nil or cursor == nil then
        return false
    end

    -- Hook names are like Function/variant
    local _, name = string.match(hook:getName(), '^([^/]*)/?(.*)$')
    local authority = authorityof(uri)

    -- The authority contacts is of the former contacts API (Contacts.People), served by the same provider;
    -- com.android.simphonebook is of the contacts on the SIM (SimPhonebookContract), like icc
    if (name == 'blockednumber' and authority == 'com.android.blockednumber') or
            (name == 'calendars' and authority == 'com.android.calendar') or
            (name == 'call_log' and authority == 'call_log') or
            (name == 'call_log' and authority == 'call_log_shadow') or
            (name == 'contacts' and authority == 'icc') or
            (name == 'contacts' and authority == 'com.android.simphonebook') or
            (name == 'contacts' and authority == 'contacts') or
            (name == 'contacts' and authority == 'com.android.contacts') or
            (name == 'gsf_id' and authority == 'com.google.android.gsf.gservices') or
            (name == 'oaid' and authority == 'com.vivo.vms.IdProvider') or
            (name == 'oaid' and authority == 'com.meizu.flyme.openidsdk') or
            (name == 'oaid' and authority == 'com.huawei.hwid.pps.apiprovider') or
            (name == 'oaid' and authority == 'com.miui.idprovider') or
            (name == 'fb_attribution' and authority == 'com.facebook.katana.provider.AttributionIdProvider') or
            (name == 'fb_attribution' and authority == 'com.facebook.wakizashi.provider.AttributionIdProvider') or
            (name == 'mmssms' and authority == 'mms') or
            (name == 'mmssms' and authority == 'sms') or
            (name == 'mmssms' and authority == 'mms-sms') or
            (name == 'mmssms' and authority == 'com.google.android.apps.messaging.shared.datamodel.BugleContentProvider') or
            (name == 'voicemail' and authority == 'com.android.voicemail') or
            (name == 'siminfo' and authority == 'telephony' and prefixof(uri) == 'siminfo') then

        if name == 'contacts' and authority == 'com.android.contacts' then
            if uri:getPath() == nil or prefixof(uri) == 'provider_status' then
                return false
            end
            local starred = starredof(name, uri, param)
            if starred ~= nil then
                if suggestions(uri) then
                    return starredrows(param, cursor, starred)
                end
                return true
            end
        end

        if name == 'gsf_id' then
            return gservices(param, cursor)
        end
        if name == 'siminfo' then
            return siminfo(param, cursor)
        end

        -- The real cursor holds the provider and its data until closed
        local fake = luajava.newInstance('android.database.MatrixCursor', cursor:getColumnNames())
        cursor:close()
        param:setResult(fake)
        return true
    else
        return false
    end
end
