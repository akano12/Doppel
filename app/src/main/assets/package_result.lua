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

-- Calls which answer with an app, like the autofill service or the store which installed the app: nothing when that
-- app is hidden, which is what Android gives for an app the caller may not see (ComputerEngine.getInstallerPackageName).

function after(hook, param)
    local result = param:getResult()
    if result == nil or param:getException() ~= nil then
        return false
    end

    -- The certificate of the store which started the install, which Android gives only with the name of that store
    -- (ComputerEngine.getInstallSourceInfo), so none when that name is hidden: it would name the store
    if hook:getMethodName() == 'getInitiatingPackageSigningInfo' then
        local info = param:getThis()
        local initiating = param:unhooked(function()
            return info:getInitiatingPackageName()
        end)
        if initiating == nil or param:isPackageVisible(initiating) then
            return false
        end
        param:setResult(nil)
        return true, initiating
    end

    local packageName = (type(result) == 'string' and result or result:getPackageName())
    if param:isPackageVisible(packageName) then
        return false
    end
    param:setResult(nil)
    return true, packageName
end
