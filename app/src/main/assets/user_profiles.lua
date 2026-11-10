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
-- device is used and set it apart: the app sees its own user only, like on a device with one user, and when it runs
-- in a profile also the parent of the profile.
-- UserManager.getUserProfiles and LauncherApps.getProfiles keep the user of the app, getUserCount is 1,
-- CrossProfileApps.getTargetUserProfiles has no profile to switch to, and the serial numbers of other users, which
-- getUserForSerialNumber and getSerialNumberForUser convert, are like those of users which do not exist.
-- UserManager.isUserRunning, isUserRunningOrStopping, isUserUnlocked, isQuietModeEnabled and getUserRestrictions
-- answer for a handle an app can make itself, like
-- UserHandle.of(10): they are asked about a user which does not exist, so the app gets the answer of Android for that.

-- The parent of the user of the app when the app runs in a profile, like a work profile, a private space or a clone,
-- else nil: a device with one user and that profile, since the profile cannot be there without its parent. From
-- Android 11 an app can tell it runs in a profile (isProfile), and its group lists the parent, the first user of
-- the group, whose profiles come after it.
-- shortcut: before Android 11 the app sees its own user only, also in a profile; upgrade with a hidden way to the group
function parentof(param, own)
    local ok, parent = pcall(function()
        local users = param:getApplicationContext():getSystemService('user')
        if not users:isProfile() then
            return nil
        end
        local group = param:unhooked(function()
            return users:getUserProfiles()
        end)
        local first
        local iterator = group:iterator()
        while iterator:hasNext() do
            local user = iterator:next()
            if not user:equals(own) and (first == nil or user:getIdentifier() < first:getIdentifier()) then
                first = user
            end
        end
        return first
    end)
    return (ok and parent or nil)
end

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

    local own = luajava.bindClass('android.os.Process'):myUserHandle()
    local parent = parentof(param, own)
    local function shown(user)
        return user:equals(own) or (parent ~= nil and user:equals(parent))
    end
    if method == 'getUserCount' then
        local count = (parent ~= nil and 2 or 1)
        if result <= count then
            return false
        end
        param:setResult(count)
        return true, tostring(result), tostring(count)
    end
    if method == 'getUserForSerialNumber' then
        if shown(result) then
            return false
        end
        param:setResult(nil)
        return true, tostring(result), nil
    elseif method == 'getSerialNumberForUser' then
        if result == -1 or shown(param:getArgument(0)) then
            return false
        end
        param:setResult(-1)
        return true, tostring(result), '-1'
    end

    -- Lists of UserHandle
    -- The profiles to switch to are the other ones of the group, of an app in a profile its parent
    local kept = luajava.newInstance('java.util.ArrayList')
    local iterator = result:iterator()
    while iterator:hasNext() do
        local user = iterator:next()
        if shown(user) and not (method == 'getTargetUserProfiles' and user:equals(own)) then
            kept:add(user)
        end
    end
    if kept:size() == result:size() then
        return false
    end
    param:setResult(kept)
    return true, tostring(result:size()), tostring(kept:size())
end
