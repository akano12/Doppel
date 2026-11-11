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

-- Copyright 2026 akano12

-- Contacts and calendars have the name of their account as a column, like the address of a Google account: it is
-- the fake one of Read.Account (see account_createfromparcel), by a proxy of the cursor of the provider like
-- photo_location. The accounts of the app itself keep their names, also for its sync adapter, and so do the ones
-- which are no account of the device, like LOCAL calendars, the local account of contacts and, from Android 12, the
-- SIM accounts of contacts. Calendars have the address of their account as their owner, as the organizer and the
-- attendee of events and often as the name of a calendar too, which are faked when they are the name of an account,
-- with the fake of that account. The owner is not always an account: a delegated calendar has the address of another
-- person and a holiday calendar its id, which stay, like the organizers and attendees with them.
-- shortcut: contacts of types of vendors which are no accounts either, like vnd.sec.contact.phone, and SIM contacts
-- before Android 12, which has no list of them, are faked; upgrade with the accounts of the device from the service,
-- as the app sees only some of them
local NAMES = { 'account_name', 'accountName' }
local ADDRESSES = { 'ownerAccount', 'organizer', 'attendeeEmail', 'name', 'calendar_displayName' }

-- The types of accounts of the app itself, like account_createfromparcel
function owntypes(param)
    local context = param:getApplicationContext()
    local own = param:getValue('account.types', context)
    if own == nil then
        own = {}
        local auths = param:unhooked(function()
            return luajava.bindClass('android.accounts.AccountManager'):get(context):getAuthenticatorTypes()
        end)
        local packageName = param:getPackageName()
        for index = 1, auths['length'] do
            if auths[index].packageName == packageName then
                own[auths[index].type] = true
            end
        end
        param:putValue('account.types', own, context)
    end
    return own
end

-- The fake name of an account, like account_createfromparcel, which numbers the accounts of a type
function fakeof(param, name, type)
    local fake = param:getSetting('value.email')
    if fake == nil then
        fake = 'private@example.com'
    end
    local at = string.find(fake, '@', 1, true) or (#fake + 1)
    local head, tail = string.sub(fake, 1, at - 1), string.sub(fake, at)
    if #name >= #fake and string.sub(name, 1, #head) == head and string.sub(name, #name - #tail + 1) == tail and
            string.match(string.sub(name, #head + 1, #name - #tail), '^%d*$') then
        return name
    end
    if type == nil then
        return fake
    end
    -- The numbers of the names of each type, shared by the hooks of the process; new tables instead of a change,
    -- because other threads can read them at the same time.
    -- shortcut: two threads which see new names at the same moment can give them the same number, which needs a lock
    local context = param:getApplicationContext()
    local types = param:getValue('account.names', context) or {}
    local numbers = types[type] or {}
    if numbers[name] == nil then
        local count, copy = 0, {}
        for known, number in pairs(numbers) do
            copy[known] = number
            count = count + 1
        end
        -- Without the hook of Account, which would fake the names; a lookup which fails numbers in the order the
        -- process sees the names
        local ok, accounts = pcall(function()
            return param:unhooked(function()
                return luajava.bindClass('android.accounts.AccountManager'):get(context):getAccountsByType(type)
            end)
        end)
        for index = 1, (ok and accounts ~= nil and accounts['length'] or 0) do
            if copy[accounts[index].name] == nil then
                count = count + 1
                copy[accounts[index].name] = count
            end
        end
        if copy[name] == nil then
            copy[name] = count + 1
        end
        numbers = copy
        local all = {}
        for known, names in pairs(types) do
            all[known] = names
        end
        all[type] = numbers
        param:putValue('account.names', all, context)
    end
    if numbers[name] == 1 then
        return fake
    end
    return head .. numbers[name] .. tail
end

-- The names of the accounts of the app itself, which an app always sees, for columns without the type of their account
-- shortcut: an account of another app with the name of one of the app is kept too; upgrade if apps share names
function ownnames(param, own)
    local names = {}
    if next(own) ~= nil then
        local context = param:getApplicationContext()
        param:unhooked(function()
            local manager = luajava.bindClass('android.accounts.AccountManager'):get(context)
            for type in pairs(own) do
                local accounts = manager:getAccountsByType(type)
                for index = 1, accounts['length'] do
                    names[accounts[index].name] = false
                end
            end
        end)
    end
    return names
end

-- The local account of contacts, which is no account of the device, from Android 11: its name and its type
function localaccount(param)
    if luajava.bindClass('android.os.Build$VERSION').SDK_INT < 30 then
        return nil, nil
    end
    local contacts = luajava.bindClass('android.provider.ContactsContract$RawContacts')
    local context = param:getApplicationContext()
    return contacts:getLocalAccountName(context), contacts:getLocalAccountType(context)
end

-- The SIM accounts of contacts, which are no accounts of the device either, from Android 12, which lists them to any
-- reader of contacts (ContactsContract.SimContacts): their types and their names, from the provider without the hooks
function simaccounts(param)
    local types, names = {}, {}
    if luajava.bindClass('android.os.Build$VERSION').SDK_INT < 31 then
        return types, names
    end
    local context = param:getApplicationContext()
    -- A provider which fails leaves them unknown, so their names are faked like those of accounts
    param:unhooked(function()
        return pcall(function()
            local accounts = luajava.bindClass('android.provider.ContactsContract$SimContacts'):getSimAccounts(
                    context:getContentResolver())
            for index = 0, accounts:size() - 1 do
                local account = accounts:get(index)
                types[account:getAccountType()] = true
                names[account:getAccountName()] = true
            end
        end)
    end)
    return types, names
end

-- The names of the accounts of the calendars, from the provider without the hooks, for columns which do not tell the
-- type of their account and for the owners and addresses: the type of the account for those of accounts, which are
-- faked and numbered by it (true without a type), and false for those of calendars which keep them; a name of both is
-- faked. Owners count only as the name of their account, which an owner of its own calendar is.
-- shortcut: without the type column a name or address which a calendar that keeps it shares with an account, like the
-- address of a Google account as the name of a calendar of the app, is faked in the rows of that calendar too; upgrade
-- by reading the type of each row by its _id
function calendarnames(param, uri, keep)
    local names = {}
    local this = param:getThis()
    local calendars = luajava.bindClass('android.net.Uri'):parse('content://' .. uri:getAuthority() .. '/calendars')
    -- A provider which fails leaves the names unknown, so the names of accounts are still faked by their type, while
    -- the owners and addresses stay
    param:unhooked(function()
        return pcall(function()
            local cursor = this:query(calendars, nil, nil, nil)
            if cursor == nil then
                return
            end
            local nameIndex, typeIndex = cursor:getColumnIndex('account_name'), cursor:getColumnIndex('account_type')
            while cursor:moveToNext() do
                local name, type = cursor:getString(nameIndex), cursor:getString(typeIndex)
                if name ~= nil then
                    names[name] = names[name] or (not keep(type) and (type or true))
                end
            end
            cursor:close()
        end)
    end)
    return names
end

function after(hook, param)
    local uri = param:getArgument(0)
    local result = param:getResult()
    local authority = (uri ~= nil and uri:getAuthority() or nil)
    if authority == nil or result == nil then
        return false
    end
    authority = string.gsub(authority, '^.*@', '')
    if authority ~= 'com.android.contacts' and authority ~= 'com.android.calendar' then
        return false
    end
    local calendar = (authority == 'com.android.calendar')
    -- The columns which are faked: names of accounts, and addresses which may be of one in calendars
    local hidden = {}
    local any, addresses = false, false
    for _, name in ipairs(NAMES) do
        local index = result:getColumnIndex(name)
        if index >= 0 then
            hidden[index] = 'name'
            any = true
        end
    end
    if calendar then
        for _, name in ipairs(ADDRESSES) do
            local index = result:getColumnIndex(name)
            if index >= 0 then
                hidden[index] = 'address'
                addresses = true
            end
        end
    end
    if not any and not addresses then
        return false
    end
    local own = owntypes(param)
    local localName, localType = nil, nil
    local simTypes, simNames = {}, {}
    if not calendar then
        localName, localType = localaccount(param)
        simTypes, simNames = simaccounts(param)
    end
    -- Whether the accounts of a type keep their names: of the app itself, or no account of the device, like a calendar
    -- of type LOCAL (CalendarContract.ACCOUNT_TYPE_LOCAL), which the provider keeps without an account
    local function keep(type)
        return type ~= nil and (own[type] or (calendar and type == 'LOCAL') or type == localType or simTypes[type] ~= nil)
    end
    -- The type is account_type, or accountType for directories of contacts
    local typeIndex = result:getColumnIndex('account_type')
    if typeIndex < 0 then
        typeIndex = result:getColumnIndex('accountType')
    end
    -- The names which are known to be faked (with the type of their account in calendars) or kept (false), for columns
    -- which do not tell the type and for the addresses of calendars
    local names = nil
    if calendar and (addresses or typeIndex < 0) then
        names = calendarnames(param, uri, keep)
    elseif typeIndex < 0 then
        names = ownnames(param, own)
        if localName ~= nil then
            names[localName] = false
        end
        for name in pairs(simNames) do
            names[name] = false
        end
    end
    local methods = {}
    -- The fake of the value of a column, nil when it stays: of a name of an account which keeps no name, numbered by
    -- the type of its row, or of an owner or address in calendars which is the name of such an account, numbered by the
    -- type of that account. A row of a type which keeps its names keeps all of them, whatever other calendars have the
    -- same value.
    local function faked(index)
        local kind = hidden[index]
        local value = kind and result:getString(index)
        local type = typeIndex >= 0 and result:getString(typeIndex) or nil
        if not value or (typeIndex >= 0 and keep(type)) then
            return nil
        end
        local known = names and names[value]
        if known == false or (known == nil and kind ~= 'name') then
            return nil
        end
        if kind ~= 'name' or typeIndex < 0 then
            type = (known ~= true) and known or nil
        end
        return fakeof(param, value, type)
    end
    -- The functions of the proxy get the arguments of the method only; the others call the method of the cursor.
    -- The text of a column can be read as its bytes and into a CharArrayBuffer too.
    methods.getString = function(index)
        return faked(index) or result:getString(index)
    end
    -- The proxy returns a text as its bytes for byte[]; a java.lang.String would become a text of Lua again, which a
    -- StringBuilder does not. The bytes of a text end with a 0, like CursorWindow keeps them.
    methods.getBlob = function(index)
        local fake = faked(index)
        if fake ~= nil then
            return fake .. '\0'
        end
        return result:getBlob(index)
    end
    -- The chars go into the array of the buffer when it is long enough, else into a new one of at least 64 chars, like
    -- CursorWindow does; a buffer which is null gets the exception of the cursor
    methods.copyStringToBuffer = function(index, buffer)
        local fake = buffer ~= nil and faked(index)
        if not fake then
            result:copyStringToBuffer(index, buffer)
            return
        end
        local text = luajava.newInstance('java.lang.StringBuilder', fake)
        local size = text:length()
        local chars = buffer.data
        if chars == nil or chars['length'] < size then
            chars = luajava.bindClass('java.lang.reflect.Array'):newInstance(
                    luajava.bindClass('java.lang.Character').TYPE, math.max(size, 64))
            buffer.data = chars
        end
        text:getChars(0, size, chars, 0)
        buffer.sizeCopied = size
    end
    setmetatable(methods, { __index = function(_, method)
        -- The proxy itself for equals and hashCode, like any object
        if method == 'equals' or method == 'hashCode' then
            return nil
        end
        return function(...)
            return result[method](result, ...)
        end
    end })
    param:setResult(luajava.createProxy('android.database.Cursor', methods))
    return true
end
