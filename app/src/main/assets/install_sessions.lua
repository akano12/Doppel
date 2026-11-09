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

-- The install sessions of the device tell which apps are installed or updated; the app sees the sessions it installs
-- itself, like an app updating itself, which Android always lets the installer see, and the sessions of visible apps,
-- like the app itself or a system app, by a visible installer, which name no hidden app
function mine(param, info)
    if info == nil then
        return false
    end
    local installer = info:getInstallerPackageName()
    if installer == param:getPackageName() then
        return true
    end
    local app = info:getAppPackageName()
    return app ~= nil and param:isPackageVisible(app) and (installer == nil or param:isPackageVisible(installer))
end

-- The callbacks of sessions (PackageInstaller.SessionCallbackDelegate): Android tells a callback about every session
-- of the device, the app hears only of the ones it installs itself, like the progress of updating itself. A session
-- which finished has no info anymore, so whose a session is is remembered from its events before, like the one of
-- making it, until it finished; the progress of an install has many events.
function before(hook, param)
    local method = hook:getMethodName()
    if string.sub(method, 1, 9) ~= 'onSession' then
        return false
    end
    local id = param:getArgument(0)
    local context = param:getApplicationContext()
    -- Per callback, so each callback of the app hears that a session finished
    local delegate = param:getThis()
    local key = 'install_sessions.' .. tostring(id)
    local own = param:getValue(key, delegate)
    if own == nil then
        -- The lookup of the script itself, without the hooks of the app
        own = mine(param, param:unhooked(function()
            return context:getPackageManager():getPackageInstaller():getSessionInfo(id)
        end))
        if method ~= 'onSessionFinished' then
            param:putValue(key, own, delegate)
        end
    elseif method == 'onSessionFinished' then
        param:putValue(key, nil, delegate)
    end
    if own then
        return false
    end
    param:setResult(nil)
    return true
end

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end
    -- PackageInstaller.getSessionInfo(id)
    if hook:getMethodName() == 'getSessionInfo' then
        if mine(param, result) then
            return false
        end
        param:setResult(nil)
        return true
    end
    -- PackageInstaller.getAllSessions and getStagedSessions
    local count = result:size()
    local kept = luajava.newInstance('java.util.ArrayList')
    for index = 0, count - 1 do
        local info = result:get(index)
        if mine(param, info) then
            kept:add(info)
        end
    end
    if kept:size() == count then
        return false
    end
    param:setResult(kept)
    return true, tostring(count), tostring(kept:size())
end
