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

-- The other users and profiles of the device, like a work profile, a private space or a second user, tell how the
-- device is used and set it apart: the app sees its own user only, like on a device with one user.
-- UserManager.getUserProfiles and LauncherApps.getProfiles keep the user of the app, getUserCount is 1,
-- CrossProfileApps.getTargetUserProfiles has no profile to switch to, and the serial numbers of other users, which
-- getUserForSerialNumber and getSerialNumberForUser convert, are like those of users which do not exist.
-- UserManager.isUserRunning, isUserRunningOrStopping, isUserUnlocked, isQuietModeEnabled and getUserRestrictions
-- answer for a handle an app can make itself, like
-- UserHandle.of(10): they are asked about a user which does not exist, so the app gets the answer of Android for that.

-- The user id no device has, Android allows far fewer users
local NOUSER = 9999

local QUESTIONS = { isUserRunning = true, isUserRunningOrStopping = true, isUserUnlocked = true,
    isQuietModeEnabled = true, getUserRestrictions = true }

function before(hook, param)
    if not QUESTIONS[hook:getMethodName()] then
        return false
    end
    local user = param:getArgument(0)
    if user == nil or user:equals(luajava.bindClass('android.os.Process'):myUserHandle()) then
        return false
    end
    param:setArgument(0, luajava.bindClass('android.os.UserHandle'):getUserHandleForUid(NOUSER * 100000))
    return true, tostring(user)
end

function after(hook, param)
    local result = param:getResult()
    local method = hook:getMethodName()
    if result == nil or param:getException() ~= nil or QUESTIONS[method] then
        return false
    end
    if method == 'getUserCount' then
        if result <= 1 then
            return false
        end
        param:setResult(1)
        return true, tostring(result), '1'
    end

    local own = luajava.bindClass('android.os.Process'):myUserHandle()
    if method == 'getUserForSerialNumber' then
        if result:equals(own) then
            return false
        end
        param:setResult(nil)
        return true, tostring(result), nil
    elseif method == 'getSerialNumberForUser' then
        if result == -1 or param:getArgument(0):equals(own) then
            return false
        end
        param:setResult(-1)
        return true, tostring(result), '-1'
    end

    -- Lists of UserHandle
    local kept = luajava.newInstance('java.util.ArrayList')
    if method ~= 'getTargetUserProfiles' then
        local iterator = result:iterator()
        while iterator:hasNext() do
            local user = iterator:next()
            if user:equals(own) then
                kept:add(user)
            end
        end
    end
    if kept:size() == result:size() then
        return false
    end
    param:setResult(kept)
    return true, tostring(result:size()), tostring(kept:size())
end
