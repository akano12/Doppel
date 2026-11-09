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
local MATCH_ALL = 0x20000 -- PackageManager
-- The chooser of Android, which resolveActivity gives when several apps can and none is the default
local RESOLVER = 'com.android.internal.app.ResolverActivity'

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
            -- The other packages of a shared user id with a visible app stay hidden, like for their lookups
            if method == 'getPackagesForUid' and result['length'] > 1 then
                local kept = {}
                for index = 1, result['length'] do
                    if param:isPackageVisible(result[index]) then
                        kept[#kept + 1] = result[index]
                    end
                end
                if #kept == result['length'] then
                    return false
                end
                local arrayClass = luajava.bindClass('java.lang.reflect.Array')
                local names = arrayClass:newInstance(luajava.bindClass('java.lang.String'), #kept)
                for index = 1, #kept do
                    names[index] = kept[index]
                end
                param:setResult(names)
                return true, tostring(result['length']), tostring(#kept)
            end
            return false
        end
        param:setResult(nil)
        return true, packageName
    elseif method == 'getNamesForUids' then
        -- A name per user id, null for one which is not in use, like Android gives for the user ids of hidden apps
        local uids = param:getArgument(0)
        if uids == nil then
            return false
        end
        local names = nil
        for index = 1, result['length'] do
            if result[index] ~= nil and not param:isUidVisible(uids[index]) then
                if names == nil then
                    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
                    names = arrayClass:newInstance(luajava.bindClass('java.lang.String'), result['length'])
                    for copy = 1, result['length'] do
                        names[copy] = result[copy]
                    end
                end
                names[index] = nil
            end
        end
        if names == nil then
            return false
        end
        param:setResult(names)
        return true
    elseif method == 'checkPermission' then
        -- A permission of a user id which is not in use is denied, which Android says for ones of hidden apps
        if result ~= 0 or param:isUidVisible(param:getArgument(2)) then
            return false
        end
        param:setResult(-1) -- PackageManager.PERMISSION_DENIED
        return true, tostring(param:getArgument(2))
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
        -- The permissions of a group which a hidden app defines: the group is missing, which Android throws for
        local group = (method == 'queryPermissionsByGroup' and param:getArgument(0) or nil)
        if group ~= nil then
            local ok, owner = pcall(function()
                return param:unhooked(function()
                    return param:getThis():getPermissionGroupInfo(group, 0).packageName
                end)
            end)
            if ok and owner ~= nil and not param:isPackageVisible(owner) then
                param:setResult(luajava.newInstance('android.content.pm.PackageManager$NameNotFoundException', group))
                return true, owner
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

    -- An intent any app may handle, like a link, of which a hidden app is the default or one of several: what
    -- Android resolves without the hidden apps, so the result agrees with queryIntentActivities (see best)
    local resolve = (method == 'resolveActivity' or method == 'resolveActivityAsUser')
    local chooser = (resolve and packageName == 'android' and result.activityInfo.name == RESOLVER)
    if resolve and implicit(param:getArgument(0)) then
        if chooser or not param:isPackageVisible(packageName) then
            local ok, fake = pcall(best, param, chooser and result)
            if not ok then
                fake = (chooser and result or nil)
            end
            if fake == result then
                return false
            end
            param:setResult(fake)
            return true, packageName, (fake ~= nil and fake.activityInfo.packageName or nil)
        end
    end

    -- A service any app may handle: the first visible one, which queryIntentServices also gives; also for a user
    if (method == 'resolveService' or method == 'resolveServiceAsUser') and implicit(param:getArgument(0)) and
            not param:isPackageVisible(packageName) then
        local pm, intent, flags = param:getThis(), param:getArgument(0), param:getArgument(1)
        local ok, all = pcall(function()
            return param:unhooked(function()
                return pm:queryIntentServices(intent, flags)
            end)
        end)
        for index = 0, (ok and all ~= nil) and all:size() - 1 or -1 do
            if param:isPackageVisible(all:get(index).serviceInfo.packageName) then
                param:setResult(all:get(index))
                return true, packageName, all:get(index).serviceInfo.packageName
            end
        end
    end

    -- The chooser between the screens of the app an intent names is of Android, but tells that app is there
    if chooser then
        packageName = param:getArgument(0):getPackage()
    end

    if param:isPackageVisible(packageName) then
        return false
    end
    param:setResult(nil)
    return true, packageName
end

-- Without a package or a component, like a link
function implicit(intent)
    return intent ~= nil and intent:getComponent() == nil and intent:getPackage() == nil
end

-- The visible apps of a query
function visibleof(param, pm, intent, flags)
    local all = param:unhooked(function()
        return pm:queryIntentActivities(intent, flags)
    end)
    local visible = {}
    for index = 0, all:size() - 1 do
        local info = all:get(index)
        if param:isPackageVisible(info.activityInfo.packageName) then
            visible[#visible + 1] = info
        end
    end
    return visible
end

-- Flags with MATCH_ALL, an int or from Android 13 ResolveInfoFlags, of which a long has more bits than bit32
function matchall(flags)
    local value = (type(flags) == 'number' and flags or flags:getValue())
    if math.floor(value / MATCH_ALL) % 2 == 0 then
        value = value + MATCH_ALL
    end
    if type(flags) == 'number' then
        return value
    end
    return luajava.bindClass('android.content.pm.PackageManager$ResolveInfoFlags'):of(value)
end

-- What Android resolves without the hidden apps (chooseBestActivity of the package manager): nothing, the only app,
-- the first one when it has a higher priority, else the chooser, because no app left is the default: the default
-- was hidden or there was none. chooser is the result when it was the chooser already.
-- shortcut: resolveActivityAsUser queries the apps of the own user, which is the user of nearly every call
function best(param, chooser)
    local pm = param:getThis()
    local intent = param:getArgument(0)
    local flags = param:getArgument(1)
    local visible = visibleof(param, pm, intent, flags)
    -- Of a link Android gives only the default browser unless asked for all, which leaves none when it is hidden
    if #visible == 0 then
        visible = visibleof(param, pm, intent, matchall(flags))
    end
    local first, second = visible[1], visible[2]
    if second == nil or first.priority ~= second.priority or first.preferredOrder ~= second.preferredOrder or
            first.isDefault ~= second.isDefault then
        return first
    end
    if chooser then
        return chooser
    end
    -- The real chooser of the system, like resolving it by name gives, with the title of the action and marked
    -- as a browser when all apps are, like Android does
    chooser = param:unhooked(function()
        return pm:resolveActivity(luajava.newInstance('android.content.Intent'):setClassName('android', RESOLVER), 0)
    end)
    local browsers = true
    for index = 1, #visible do
        browsers = browsers and visible[index].handleAllWebDataURI
    end
    chooser.handleAllWebDataURI = browsers
    pcall(function()
        chooser.activityInfo.labelRes = luajava.bindClass(RESOLVER):getLabelRes(intent:getAction())
    end)
    return chooser
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
