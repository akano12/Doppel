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
-- the fake email, the type stays, like account_createfromparcel. The call reaches the contacts provider through
-- ContentProviderClient.call (acquireContentProviderClient(AUTHORITY_URI).call), the 3-arg call delegating to the
-- 4-arg one from Android 10, which carries the authority.

-- The authority without a user in front, like 0@com.android.contacts (see contentresolver_query)
function authorityof(value)
    if value == nil then
        return nil
    end
    return (string.gsub(value, '^%d+@', ''))
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
    if name == nil then
        return false
    end
    local fake = param:getSetting('value.email')
    if fake == nil then
        fake = 'private@example.com'
    end
    -- The bundle is the app's own copy, unparcelled from the reply of the provider
    result:putString('account_name', fake)
    return true, name, fake
end
