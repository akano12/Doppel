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

-- The install sessions of the device tell which apps are installed or updated; the app sees only the sessions it
-- installs itself, like an app updating itself, which Android always lets the installer see
function mine(param, info)
    return info ~= nil and info:getInstallerPackageName() == param:getPackageName()
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
    -- PackageInstaller.getAllSessions
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
