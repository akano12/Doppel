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
-- resolveContentProvider for the provider of a messenger, getNameForUid for the user ids of other apps or
-- getPermissionInfo for a permission or a permission group an app defines, find nothing, like the probes of packages
-- (see package_probe). The app itself, apps sharing its user id and system apps stay visible, because the Android
-- framework looks them up in the app process.

local SIGNATURE_UNKNOWN_PACKAGE = -4 -- PackageManager

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local method = hook:getMethodName()
    local packageName
    if method == 'getSyncAdapterTypes' then
        -- The apps which sync, like com.whatsapp and org.telegram.messenger: those of hidden apps are left out
        local kept = {}
        for index = 1, result['length'] do
            local name = syncpackage(param, result[index])
            if name == nil or param:isPackageVisible(name) then
                kept[#kept + 1] = result[index]
            end
        end
        if #kept == result['length'] then
            return false
        end
        local fake = luajava.bindClass('java.lang.reflect.Array'):newInstance(
                param:findClass('android.content.SyncAdapterType'), #kept)
        for index = 1, #kept do
            fake[index] = kept[index]
        end
        param:setResult(fake)
        return true, tostring(result['length']), tostring(#kept)
    elseif method == 'checkSignatures' then
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
    elseif method == 'resolveContentProvider' or method == 'resolveContentProviderAsUser' then
        packageName = result.packageName -- ProviderInfo
    elseif method == 'getPermissionInfo' or method == 'getPermissionGroupInfo' then
        -- A permission which a hidden app defines is missing, which Android throws for
        packageName = result.packageName
        if param:isPackageVisible(packageName) then
            return false
        end
        param:setResult(luajava.newInstance('android.content.pm.PackageManager$NameNotFoundException', param:getArgument(0)))
        return true, packageName
    elseif method == 'queryPermissionsByGroup' or method == 'getAllPermissionGroups' then
        -- The permissions which hidden apps define are left out
        local filtered = false
        local info = result:toArray()
        for index = info['length'], 1, -1 do
            if not param:isPackageVisible(info[index].packageName) then
                result:remove(index - 1)
                filtered = true
            end
        end
        return filtered
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
    -- An intent any app may handle, like a link, of which a hidden app is the default, like a browser: the chooser
    -- of Android, which Android itself gives when several apps can and none is the default, so a check whether an app
    -- can open it does not fail; the hidden app still opens it then, like Android does without the check
    if (method == 'resolveActivity' or method == 'resolveActivityAsUser') and implicit(param:getArgument(0)) then
        local chooser = resolver(param)
        if chooser ~= nil then
            param:setResult(chooser)
            return true, packageName, 'android'
        end
    end
    param:setResult(nil)
    return true, packageName
end

-- Without a package or a component, like a link
function implicit(intent)
    return intent ~= nil and intent:getComponent() == nil and intent:getPackage() == nil
end

-- The ResolveInfo of the chooser of Android, of the system app android, nil when it cannot be made
function resolver(param)
    local ok, result = pcall(function()
        return param:unhooked(function()
            local info = luajava.newInstance('android.content.pm.ActivityInfo')
            info.packageName = 'android'
            info.name = 'com.android.internal.app.ResolverActivity'
            info.exported = true
            info.applicationInfo = param:getApplicationContext():getPackageManager():getApplicationInfo('android', 0)
            local ri = luajava.newInstance('android.content.pm.ResolveInfo')
            ri.activityInfo = info
            return ri
        end)
    end)
    if ok then
        return result
    end
    return nil
end

-- The package of a sync adapter: its own (Android 10), else the one of its provider, nil when unknown
function syncpackage(param, sync)
    if sync['getPackageName'] ~= nil then
        return sync:getPackageName()
    end
    local ok, info = pcall(function()
        return param:unhooked(function()
            return param:getApplicationContext():getPackageManager():resolveContentProvider(sync.authority, 0)
        end)
    end)
    if ok and info ~= nil then
        return info.packageName
    end
    return nil
end
