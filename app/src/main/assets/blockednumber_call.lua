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

-- The queries of the blocked numbers are empty (see contentresolver_query), so no number is blocked either, like
-- Android answers with an empty list: is_blocked (BlockedNumberContract.isBlocked) says blocked false and, from
-- Android 10, block_status STATUS_NOT_BLOCKED (0). The provider answers first, so an app which is not the default
-- dialer, SMS or carrier app still gets its SecurityException. From Android 10 ContentResolver.call(uri) and
-- ContentProviderClient.call(method, arg, extras) call the hooked call(authority, method, arg, extras); the client of
-- Android 8.1 and 9 does not tell the provider, and is_blocked is a method of the blocked numbers only.
-- shortcut: the client of Android 8.1 and 9 is matched by the method alone, upgrade when another provider has is_blocked
function after(hook, param)
    local client = (string.find(hook:getName(), 'ContentProviderClient.call/', 1, true) == 1)
    if param:getArgument(client and 0 or 1) ~= 'is_blocked' then
        return false
    end
    if not client then
        local target = param:getArgument(0)
        if target ~= nil and type(target) ~= 'string' then
            target = target:getAuthority()
        end
        -- Without the user, like 0@com.android.blockednumber
        if target == nil or string.gsub(target, '^.*@', '') ~= 'com.android.blockednumber' then
            return false
        end
    end
    local result = param:getResult()
    if result == nil or not result:getBoolean('blocked', false) then
        return false
    end
    -- The bundle is the app's own copy, unparcelled from the reply of the provider
    result:putBoolean('blocked', false)
    if result:containsKey('block_status') then
        result:putInt('block_status', 0)
    end
    return true
end
