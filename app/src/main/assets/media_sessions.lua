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

-- The media sessions of other apps are hidden, the app's own stay, which Android lists to the app too: a notification
-- listener gets its own session among the ones of all apps, and any app may ask for the sessions of its own package.
-- Android gives a new ArrayList of the sessions, like the one of the sessions kept.
function after(hook, param)
    local result = param:getResult()
    if result == nil or result:isEmpty() then
        return false
    end
    local own = param:getPackageName()

    -- MediaSessionManager.getActiveSessionsForPackage: the tokens of the package asked for, which do not tell
    -- their package themselves
    -- shortcut: an ArrayList without the source of Android 17, use emptylike of generic_empty when its list is unmodifiable
    if hook:getMethodName() == 'getActiveSessionsForPackage' then
        if param:getArgument(0) == own then
            return false
        end
        param:setResult(luajava.newInstance('java.util.ArrayList'))
        return true
    end

    -- MediaSessionManager.getActiveSessions and getSession2Tokens: a MediaController and a Session2Token tell the
    -- package of their session; a session which died tells none and is dropped
    local count = result:size()
    local kept = luajava.newInstance('java.util.ArrayList')
    for index = 0, count - 1 do
        local session = result:get(index)
        if session ~= nil and session:getPackageName() == own then
            kept:add(session)
        end
    end
    if kept:size() == count then
        return false
    end
    param:setResult(kept)
    return true, tostring(count), tostring(kept:size())
end
