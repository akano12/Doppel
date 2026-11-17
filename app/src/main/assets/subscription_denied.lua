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

-- The SecurityException the subscription service throws at a caller with the phone state permission who fails its
-- check of the phone state app op, like the other subscription methods of Android 8.1 to 12 answer (see hooks.json):
-- isActiveSubscriptionId and the hidden isActiveSubId of Android 10 to 12, which would tell that the SIM is there, and
-- the hidden getAvailableSubscriptionInfoList of Android 8.1 to 11, also by the proxy of the service up to Android 10.
-- A call which Android refused keeps its exception. Android never refuses the system and apps with
-- READ_PRIVILEGED_PHONE_STATE (TelephonyPermissions.checkReadPhoneState), whose code does not catch the exception:
-- for these three methods they get the answer of a device without a SIM, false or no list, next to the lists of a
-- failed check of the other methods, which on Android 9 are empty where such a device gives null, and the ids of
-- their subscriptions, which stay. The phone process, Settings and the system UI, which keep the SIMs for the whole
-- device, are not hooked (see hooks.json).
-- shortcut: Android makes the exception in Parcel.readException under the proxy of the service, so its first frames
-- differ, and it can carry the trace of the service as its cause; and a device without the subscription service
-- answers false or null instead; upgrade when apps are seen comparing the frames or such a device restricts the app.
function after(hook, param)
    if param:getException() ~= nil then
        return false
    end

    local name = hook:getMethodName()
    if privileged(param) then
        local fake = false
        if name == 'getAvailableSubscriptionInfoList' then
            fake = nil
        end
        if param:getResult() == fake then
            return false
        end
        param:setResult(fake)
        return true
    end

    local message = 'Requires READ_PHONE_STATE permission.'
    if name == 'getAvailableSubscriptionInfoList' then
        -- With two spaces, like Android
        message = 'Need READ_PHONE_STATE to call  getAvailableSubscriptionInfoList'
    end
    param:setResult(luajava.newInstance('java.lang.SecurityException', message))
    return true
end

-- Whether Android skips the check for the app: the system (see XParam.isSystem) or READ_PRIVILEGED_PHONE_STATE, asked
-- without the hooks of the app, which could else answer for the script; an error of the check counts as privileged,
-- since an exception would break such an app
function privileged(param)
    if param:isSystem() then
        return true
    end
    local ok, result = pcall(param.unhooked, param, function()
        return param:getApplicationContext():checkSelfPermission('android.permission.READ_PRIVILEGED_PHONE_STATE')
    end)
    return not ok or result == 0
end
