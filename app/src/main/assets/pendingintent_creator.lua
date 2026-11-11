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

-- The app which made a PendingIntent or an IntentSender the app was handed, like the alarm app of the next alarm
-- clock (AlarmManager.getNextAlarmClock().getShowIntent()), which Android tells any app up to Android 12L. When that
-- app is hidden it is unknown, like Android 13 and later answer for an app the caller may not see
-- (getInfoForIntentSender of ActivityManagerService): no package, the invalid uid -1, and the user the getter makes of
-- that uid. Sending the intent still starts the app.
-- shortcut: on Android 11 to 12L, which tell the creator to any app, a system app which the package visibility rules
-- hide from the app counts as hidden too (no package and uid -1 where Android tells them), since the app cannot look
-- up its flags; ask the service for them when that matters

local INVALID_UID = -1 -- Process

-- The version of Android
function sdkversion()
    return luajava.bindClass('android.os.Build$VERSION').SDK_INT
end

function after(hook, param)
    local result = param:getResult()
    if result == nil or result == INVALID_UID or param:getException() ~= nil then
        return false
    end

    -- Android hides the uid and the user with the package, so all go by the package
    local method = hook:getMethodName()
    local creator = result
    if method == 'getCreatorUid' or method == 'getCreatorUserHandle' then
        local sender = param:getThis()
        creator = param:unhooked(function()
            return sender:getCreatorPackage()
        end)
    end
    if creator == nil or param:isPackageVisible(creator) then
        return false
    end

    local fake
    if method == 'getCreatorUid' then
        fake = INVALID_UID
    elseif method == 'getCreatorUserHandle' and hook:getClassName() == 'android.app.PendingIntent' and
            sdkversion() >= 31 then
        -- PendingIntent of Android 12 and later makes a user of any uid (UserHandle.getUserHandleForUid), of -1 user 0;
        -- before, and IntentSender on all versions, there is none for a uid below 1
        fake = luajava.bindClass('android.os.UserHandle'):getUserHandleForUid(INVALID_UID)
    end
    param:setResult(fake)
    return true, creator, (fake ~= nil and tostring(fake) or nil)
end
