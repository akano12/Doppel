-- This file is part of XPrivacyLua.

-- XPrivacyLua is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.

-- XPrivacyLua is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.

-- You should have received a copy of the GNU General Public License
-- along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

-- Copyright 2017-2019 Marcel Bokhorst (M66B)

-- Lookups which tell about other apps, like resolveActivity for an intent which only a banking app handles,
-- resolveContentProvider for the provider of a messenger or getNameForUid for the user ids of other apps,
-- find nothing, like the probes of packages (see package_probe). The app itself, apps sharing its user id
-- and system apps stay visible, because the Android framework looks them up in the app process.

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local method = hook:getMethodName()
    local packageName
    if method == 'getPackagesForUid' or method == 'getNameForUid' then
        local uid = param:getArgument(0)
        -- Its own user id and the user ids of the system, like 1000 for Android itself
        if uid == param:getUid() or uid % 100000 < 10000 then
            return false
        end
        if method == 'getPackagesForUid' then
            if result['length'] == 0 then
                return false
            end
            packageName = result[1]
        elseif string.find(result, ':', 1, true) then
            -- The name of a shared user id, like com.example.shared:10123, which no package name checks
            param:setResult(nil)
            return true, result
        else
            packageName = result
        end
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
