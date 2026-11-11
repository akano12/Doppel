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

-- The contacts provider tells the account new contacts go to, the primary account of the user, usually the Google
-- email. ContactsContract.Settings.getDefaultAccount (API 33) returns a parcelled Account under key_default_account,
-- which is faked when the app unparcels it (see account_createfromparcel), like the eligible accounts of
-- queryEligibleDefaultAccounts. RawContacts.DefaultAccount.getDefaultAccountForNewContacts (API 36) instead puts the
-- name and type as plain strings, built into an Account without a parcel, so the real email leaks: it is replaced by
-- the fake name of the Account, the type stays, like account_createfromparcel. An account of a type of the app itself
-- keeps its name, as in getAccounts, so that the app still finds its own default account. The call reaches the
-- contacts provider through ContentProviderClient.call (acquireContentProviderClient(AUTHORITY_URI).call), the 3-arg
-- call delegating to the 4-arg one from Android 10, which carries the authority.

-- The authority without a user in front, like 0@com.android.contacts (see contentresolver_query)
function authorityof(value)
    if value == nil then
        return nil
    end
    return (string.gsub(value, '^.*@', ''))
end

-- The types of accounts of the app itself, like account_createfromparcel; read once, since the authenticators of the
-- app do not change while it runs
function owntypes(param)
    local context = param:getApplicationContext()
    local own = param:getValue('account.types', context)
    if own == nil then
        own = {}
        -- Without the hook of the types (see account_authenticators), which would report a call of the app
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

function after(hook, param)
    -- ContentProviderClient.call or ContentResolver.call(authority, method, arg, extras)
    if authorityof(param:getArgument(0)) ~= 'com.android.contacts' or
            param:getArgument(1) ~= 'queryDefaultAccountForNewContacts' then
        return false
    end
    local result = param:getResult()
    if result == nil then
        return false
    end
    local name = result:getString('account_name')
    if name == nil or owntypes(param)[result:getString('account_type')] then
        return false
    end
    local fake = fakeof(param, name, result:getString('account_type'))
    -- The bundle is the app's own copy, unparcelled from the reply of the provider
    result:putString('account_name', fake)
    return true, name, fake
end
