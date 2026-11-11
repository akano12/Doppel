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

-- The directories of contacts which the provider answers from its own tables, which have the column starred: the default
-- and the invisible one, also of the work profile (Directory.isRemoteDirectoryId)
local LOCAL = { ['0'] = true, ['1'] = true, ['1000000000'] = true, ['1000000001'] = true }

-- The setting of the queries of contacts which are filtered for (not) starred contacts, else nil:
-- before filters them and after keeps the filtered result, which would else be replaced. Only 0 and 1, like
-- contentresolver_open: another value, like of a restored backup, would be SQL in the selection.
-- A query of a remote directory, like of an Exchange account, is replaced: the provider passes it on to the provider of
-- the directory, which may ignore the selection, and its entries are none of the contacts the setting keeps.
function starredof(name, uri, param)
    if name == 'contacts' and authorityof(uri) == 'com.android.contacts' then
        local prefix = prefixof(uri)
        -- The stream items of a contact, whose view has no column starred; the phone lookups and entities have it
        if (prefix == 'contacts' or prefix == 'raw_contacts' or prefix == 'data' or prefix == 'raw_contact_entities' or
                prefix == 'phone_lookup' or prefix == 'phone_lookup_enterprise') and
                not string.find(uri:getPath(), '/stream_items', 1, true) then
            local starred = param:getSetting('contacts.starred')
            if (starred == '0' or starred == '1') and LOCAL[uri:getQueryParameter('directory') or '0'] then
                return starred
            end
        end
    end
    return nil
end

-- Whether a query of contacts is one for which the provider ignores the selection, of the contacts like a contact
-- (Contacts.AggregationSuggestions) and of a phone number (PhoneLookup, also of the work profile): the column starred
-- is added to the query, and after filters the rows by it.
-- shortcut: the provider filters after it chose the rows: it matches a number loosely only when no contact matches it
-- strictly, and it cuts the rows to the parameter limit, the suggestions to 5 without it, so a contact which is not
-- starred can hide a starred one, which the app could tell; upgrade when apps look up numbers or suggestions of
-- starred contacts that way.
function unselected(uri)
    local prefix = prefixof(uri)
    return string.find(uri:getPath(), '^/contacts/.*/suggestions') ~= nil or
            prefix == 'phone_lookup' or prefix == 'phone_lookup_enterprise'
end

-- Whether before added the column to the query in progress on this thread
local added = false

function before(hook, param)
    -- The builders of operations (assertion) have no arguments
    if hook:getMethodName() == 'build' then
        return false
    end
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

    if unselected(uri) then
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
            else
                row:add(cell(cursor, index))
            end
        end
    end
    cursor:close()
    param:setResult(fake)
    return true
end

-- The value of a column of the row of a cursor with its own type, for a copy of the row
function cell(cursor, index)
    local type = cursor:getType(index)
    if type == 1 then
        return luajava.newInstance('java.lang.Long', cursor:getLong(index))
    elseif type == 2 then
        return luajava.newInstance('java.lang.Double', cursor:getDouble(index))
    elseif type == 4 then
        return cursor:getBlob(index)
    elseif type == 3 then
        return cursor:getString(index)
    end
    return nil
end

-- The rows which are (not) starred of a query whose selection the provider ignores, without the column before added,
-- which is not always the last one: the phone lookup adds the number after it when the projection has none
function starredrows(param, cursor, starred)
    local names = cursor:getColumnNames()
    local column = cursor:getColumnIndex('starred')
    local kept = {}
    for index = 0, names['length'] - 1 do
        if not (added and index == column) then
            table.insert(kept, index)
        end
    end
    local columns = luajava.bindClass('java.lang.reflect.Array'):newInstance(luajava.bindClass('java.lang.String'), #kept)
    for index = 1, #kept do
        columns[index] = names[kept[index] + 1]
    end
    local fake = luajava.newInstance('android.database.MatrixCursor', columns)
    while column >= 0 and cursor:moveToNext() do
        if cursor:getString(column) == starred then
            local row = fake:newRow()
            for _, index in ipairs(kept) do
                row:add(cell(cursor, index))
            end
        end
    end
    added = false
    cursor:close()
    param:setResult(fake)
    return true
end

-- Whether the hook of a group, by the variant of its name, restricts the provider of a uri. The authority contacts is
-- of the former contacts API (Contacts.People), served by the same provider; com.android.simphonebook is of the
-- contacts on the SIM (SimPhonebookContract), like icc. The provider status tells whether there are contacts at all.
-- The directories of contacts (ContactsContract.Directory) and the properties of the calendar provider (CalendarCache,
-- its time zone settings) are no contacts or events, and a device always has the default directories and the
-- properties, whose zones properties fakes. call_composer_locations has the places which callers sent with calls
-- (CallLog.Locations).
function restricted(name, uri)
    local authority = authorityof(uri)
    if name == 'contacts' and authority == 'com.android.contacts' then
        local prefix = prefixof(uri)
        return uri:getPath() ~= nil and prefix ~= 'provider_status' and prefix ~= 'directories' and
                prefix ~= 'directories_enterprise'
    end
    return (name == 'blockednumber' and authority == 'com.android.blockednumber') or
            (name == 'calendars' and authority == 'com.android.calendar' and prefixof(uri) ~= 'properties') or
            (name == 'call_log' and authority == 'call_log') or
            (name == 'call_log' and authority == 'call_log_shadow') or
            (name == 'call_log' and authority == 'call_composer_locations') or
            (name == 'contacts' and authority == 'icc') or
            (name == 'contacts' and authority == 'com.android.simphonebook') or
            (name == 'contacts' and authority == 'contacts') or
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
            (name == 'siminfo' and authority == 'telephony' and prefixof(uri) == 'siminfo')
end

-- What SQLite answers for an aggregate of no rows: a count 0, a total 0.0, the others null
local EMPTY = { count = 'integer', total = 'float', avg = 'null', max = 'null', min = 'null', sum = 'null', group_concat = 'null' }

-- What an aggregate of a projection, like count(*) or max(date) AS last, gives for no rows, else nil; _count is the
-- count of the contacts provider, for a projection of only it, and max and min of more values are not aggregates.
-- shortcut: a lone call with or without a plain alias, upgrade when apps project expressions of aggregates.
function aggregate(column)
    if column == '_count' then
        return 'integer'
    end
    local text = string.gsub(string.lower(tostring(column)), '%s+as%s+[%w_]+%s*$', '')
    local name, inner = string.match(text, '^%s*([%a_]+)%s*%(([^()]*)%)%s*$')
    if (name == 'max' or name == 'min') and string.find(inner, ',', 1, true) then
        return nil
    end
    return name and EMPTY[name]
end

-- The paths of the contacts provider whose queries are its tables, which it does not group, unlike contacts/frequent
-- or with remove_duplicate_entries, and does not pass to another directory
local TABLES = { ['/contacts'] = true, ['/raw_contacts'] = true, ['/raw_contact_entities'] = true, ['/data'] = true,
    ['/data/phones'] = true, ['/data/emails'] = true, ['/data/postals'] = true, ['/data/callables'] = true, ['/groups'] = true }

-- Whether a query is of a provider which passes an aggregate to SQLite without grouping: the tables of contacts and
-- the messages of sms, not the SIM cards, of which SmsProvider makes the rows itself. shortcut: other providers and
-- tables are emptied as before, upgrade when an app counts them.
function ungrouped(uri)
    local authority = authorityof(uri)
    if authority == 'com.android.contacts' then
        local duplicates = uri:getQueryParameter('remove_duplicate_entries')
        local directory = uri:getQueryParameter('directory')
        return TABLES[string.gsub(uri:getPath(), '/+$', '')] == true and
                (duplicates == nil or duplicates == '0' or string.lower(duplicates) == 'false') and
                (directory == nil or directory == '0' or directory == '1')
    elseif authority == 'sms' then
        local prefix = prefixof(uri)
        return prefix == nil or not (string.find(prefix, '^icc') or string.find(prefix, '^sim'))
    end
    return false
end

-- An aggregate without grouping, like a count, gives a row also of no rows: the projection of the app tells, and the row
-- of the provider, which has it unless the provider answered no rows without querying, which it does whatever it has
function aggregated(uri, projection, cursor, fake)
    if projection == nil or projection['length'] ~= cursor:getColumnCount() or not ungrouped(uri) then
        return
    end
    local values = {}
    local any = false
    for index = 1, projection['length'] do
        values[index] = aggregate(projection[index])
        any = any or values[index] ~= nil
    end
    if any and cursor:getCount() > 0 then
        local row = fake:newRow()
        for index = 1, projection['length'] do
            if values[index] == 'integer' then
                row:add(luajava.newInstance('java.lang.Long', 0))
            elseif values[index] == 'float' then
                row:add(luajava.newInstance('java.lang.Double', 0))
            else
                row:add(nil)
            end
        end
    end
end

local TITLES = 'android.provider.extra.ADDRESS_BOOK_INDEX_TITLES'
local COUNTS = 'android.provider.extra.ADDRESS_BOOK_INDEX_COUNTS'

-- The extras of a cursor of the contacts provider, which it sets whatever its rows: the address book index of a query
-- which asks for it (Contacts.EXTRA_ADDRESS_BOOK_INDEX), as the empty arrays of no contacts, and the deferred snippeting
-- of a filter, which depends on the query only. A copy: the provider in the process of the app could have cached it.
function extras(cursor, fake)
    local real = cursor:getExtras()
    if real == nil then
        return
    end
    local copy = luajava.newInstance('android.os.Bundle', real)
    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
    if copy:containsKey(TITLES) then
        copy:putStringArray(TITLES, arrayClass:newInstance(luajava.bindClass('java.lang.String'), 0))
    end
    if copy:containsKey(COUNTS) then
        copy:putIntArray(COUNTS, arrayClass:newInstance(luajava.bindClass('java.lang.Integer').TYPE, 0))
    end
    fake:setExtras(copy)
end

-- The thread of recipients (Telephony.Threads.getOrCreateThreadId), which the provider creates when there is none and
-- returns, and without which the app would throw: a fake one, the same for the same recipients in any order, of its
-- fake Android ID (see Fake values) like other fake numbers, so the app cannot tell whether a thread was there before.
-- shortcut: the thread changes with the fake Android ID, which is new each boot unless it is fixed, where the provider
-- keeps it across boots; upgrade with a secret of each app which stays and the app cannot read.
-- shortcut: a thread of its own for each way of writing a number, which the provider matches loosely, upgrade when an
-- app compares them.
function threadid(param, uri, cursor)
    local fake = luajava.newInstance('android.database.MatrixCursor', cursor:getColumnNames())
    if cursor:moveToNext() then
        local recipients = {}
        local seen = {}
        local list = uri:getQueryParameters('recipient')
        for index = 0, list:size() - 1 do
            local recipient = tostring(list:get(index))
            if not seen[recipient] then
                seen[recipient] = true
                table.insert(recipients, recipient)
            end
        end
        table.sort(recipients)
        local key = (param:getSetting('value.android_id') or '') .. '/threadID/' .. table.concat(recipients, '\n')
        local row = fake:newRow()
        row:add(luajava.newInstance('java.lang.Long', tonumber(string.sub(param:uuidOf(key), 1, 5), 16) + 1))
    end
    cursor:close()
    param:setResult(fake)
    return true
end

-- The selection arguments an assert can have: the builder keeps those of the app which a new selection does not replace
-- since Android 11, and their back references before, so the new selection has a place for this many and replaces them
-- with empty ones; SQLite takes fewer arguments than places, not more
local ARGUMENTS = 100

-- An assert of a batch (ContentProviderOperation.newAssertQuery), which the provider runs in its own process, around
-- the queries: a selection which selects nothing, so it counts and compares the rows of an empty provider, and throws
-- like it for an expected count or a column which is not there.
-- shortcut: the provider still answers its rows to an assert of a query of which it ignores the selection, like of a
-- phone number, and in starred mode an assert counts no starred contacts either; the SIM phonebook (SimPhonebookProvider)
-- and the search of messages (mms-sms/search and searchSuggest) throw IllegalArgumentException for the selection, where
-- stock answers every assert of the SIM phonebook and an assert of the search without values (with values, so with a
-- projection, stock throws it too), but without the selection they would count and compare the real rows; from
-- Android 11 an argument of the app from the 101st on, also a back reference, throws (Too many bind arguments), and
-- before it such a back reference throws ArrayIndexOutOfBoundsException; toString and
-- resolveSelectionArgsBackReferences of the operation show the new selection and its empty arguments; an operation made
-- from a Parcel is not built, so it stays; upgrade with a hook of applyBatch which answers like an empty provider when
-- apps assert or read those.
function assertion(name, param)
    local operation = param:getResult()
    local uri = operation and operation:getUri()
    if operation == nil or not operation:isAssertQuery() or uri == nil or not restricted(name, uri) then
        return false
    end
    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
    local args = arrayClass:newInstance(luajava.bindClass('java.lang.String'), ARGUMENTS)
    for index = 1, ARGUMENTS do
        args[index] = ''
    end
    local builder = param:getThis()
    builder:withSelection('0' .. string.rep(' AND ?', ARGUMENTS), args)
    param:setResult(param:unhooked(function()
        return builder:build()
    end))
    return true
end

-- The time zone settings of the calendar provider (CalendarContract.CalendarCache), rows of a key and a value. In auto
-- mode the zone of the instances is the one of the device, and the previous one that of when the provider made its
-- database or of the last home mode, so both read as the fake zone of Read.Timezone: the zone property of the device
-- (persist.sys.timezone), which it fakes, read hooked, when that differs from the one read unhooked, so without it
-- nothing changes. The default zone of the app is not used, since the app may have set it itself (TimeZone.setDefault).
-- In home mode they are the zone a calendar app chose, which stays. The mode and the zones are read from the provider,
-- unhooked, because the app may have selected or projected only some of the keys, like one key=? at a time, and the
-- values of those zones are replaced.
-- shortcut: a selection or a column with AS of the app still compares the real zones, and so does an assert of a batch,
-- at the cost of one more query of the provider; upgrade when apps read the zone that way.
-- shortcut: the previous zone stays the real one of before when the fake zone is the zone of the device, since
-- Read.Timezone cannot be told from no restriction then, and when the provider has the fake zone as its zone already,
-- which is left as it is; upgrade when apps read the previous zone.
function properties(param, uri, cursor)
    local provider = param:getThis()
    local real = param:unhooked(function()
        local values = {}
        local rows = provider:query(uri, nil, nil, nil)
        if rows ~= nil then
            local key = rows:getColumnIndex('key')
            local value = rows:getColumnIndex('value')
            while key >= 0 and value >= 0 and rows:moveToNext() do
                values[rows:getString(key)] = rows:getString(value)
            end
            rows:close()
        end
        return values
    end)
    if real['timezoneType'] ~= 'auto' or real['timezoneInstances'] == nil then
        return false
    end
    local function zoneof()
        return luajava.bindClass('android.os.SystemProperties'):get('persist.sys.timezone')
    end
    local ok, zone = pcall(zoneof)
    local okdevice, device = pcall(param.unhooked, param, zoneof)
    if not ok or not okdevice or zone == nil or zone == '' or zone == device or zone == real['timezoneInstances'] then
        return false
    end
    local zones = { [real['timezoneInstances']] = true }
    if real['timezoneInstancesPrevious'] ~= nil then
        zones[real['timezoneInstancesPrevious']] = true
    end

    local fake = luajava.newInstance('android.database.MatrixCursor', cursor:getColumnNames())
    local changed = false
    while cursor:moveToNext() do
        local row = fake:newRow()
        for index = 0, cursor:getColumnCount() - 1 do
            if cursor:getType(index) == 3 and zones[cursor:getString(index)] then
                row:add(zone)
                changed = true
            else
                row:add(cell(cursor, index))
            end
        end
    end
    if not changed then
        fake:close()
        cursor:moveToPosition(-1)
        return false
    end
    cursor:close()
    param:setResult(fake)
    return true
end

function after(hook, param)
    -- Hook names are like Function/variant
    local _, name = string.match(hook:getName(), '^([^/]*)/?(.*)$')
    if hook:getMethodName() == 'build' then
        return assertion(name, param)
    end

    local uri = param:getArgument(0)
    local cursor = param:getResult()
    -- The properties are not restricted, so asserts of batches of them stay as they are
    if uri ~= nil and cursor ~= nil and name == 'calendars' and authorityof(uri) == 'com.android.calendar' and
            prefixof(uri) == 'properties' then
        return properties(param, uri, cursor)
    end
    if uri == nil or cursor == nil or not restricted(name, uri) then
        return false
    end
    local authority = authorityof(uri)

    if name == 'contacts' and authority == 'com.android.contacts' then
        local starred = starredof(name, uri, param)
        if starred ~= nil then
            if unselected(uri) then
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
    if authority == 'mms-sms' and prefixof(uri) == 'threadID' then
        return threadid(param, uri, cursor)
    end

    -- The real cursor holds the provider and its data until closed
    local fake = luajava.newInstance('android.database.MatrixCursor', cursor:getColumnNames())
    aggregated(uri, param:getArgument(1), cursor, fake)
    -- Protected: the extras come from the provider, and an error would leave the real cursor to the app
    if authority == 'com.android.contacts' then
        pcall(extras, cursor, fake)
    end
    cursor:close()
    param:setResult(fake)
    return true
end
