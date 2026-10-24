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

-- Lookups which tell about other apps, like resolveActivity for an intent which only a banking app handles,
-- resolveContentProvider for the provider of a messenger or getNameForUid for the user ids of other apps,
-- find nothing, like the probes of packages (see package_probe). The app itself, apps sharing its user id
-- and system apps stay visible, because the Android framework looks them up in the app process.

local SIGNATURE_UNKNOWN_PACKAGE = -4 -- PackageManager

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local method = hook:getMethodName()
    local packageName
    if method == 'checkSignatures' then
        -- The user ids of two apps, like for a uid which is not in use
        if result == SIGNATURE_UNKNOWN_PACKAGE or
                (param:isUidVisible(param:getArgument(0)) and param:isUidVisible(param:getArgument(1))) then
            return false
        end
        param:setResult(SIGNATURE_UNKNOWN_PACKAGE)
        return true, tostring(result), tostring(SIGNATURE_UNKNOWN_PACKAGE)
    elseif method == 'hasSigningCertificate' then
        -- Whether the app of a user id has a certificate
        if not result or param:isUidVisible(param:getArgument(0)) then
            return false
        end
        param:setResult(false)
        return true
    elseif method == 'getPackagesForUid' or method == 'getNameForUid' then
        if method == 'getPackagesForUid' then
            if result['length'] == 0 then
                return false
            end
            packageName = result[1]
        else
            packageName = result
        end
        -- Its own user id, the user ids of the system, like 1000 for Android itself, and the user ids
        -- with a visible app, also of a shared user id, which names like com.example.shared:10123 have
        if param:isUidVisible(param:getArgument(0)) then
            return false
        end
        param:setResult(nil)
        return true, packageName
    elseif method == 'resolveContentProvider' then
        packageName = result.packageName -- ProviderInfo
    else
        -- ResolveInfo of an activity or a service
        local info = result.activityInfo
        if info == nil then
            info = result.serviceInfo
        end
        if info == nil then
            return false
        end
        packageName = info.packageName
    end

    if param:isPackageVisible(packageName) then
        return false
    end
    param:setResult(nil)
    return true, packageName
end
