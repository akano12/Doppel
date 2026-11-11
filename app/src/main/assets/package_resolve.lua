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
-- The lookups of a permission or a permission group by its name, the first argument (see before)
local LOOKUPS = { getPermissionInfo = true, getPermissionGroupInfo = true, queryPermissionsByGroup = true }

-- A permission or a permission group which a hidden app defines is looked up under a name which no app defines,
-- so Android itself throws, with its own stack trace, like package_probe does for packages; after gives the
-- exception the name the app asked for
function before(hook, param)
    local method = hook:getMethodName()
    if not LOOKUPS[method] then
        return false
    end
    local name = param:getArgument(0)
    if name == nil then
        return false
    end
    local ok, owner = pcall(function()
        return param:unhooked(function()
            if method == 'getPermissionInfo' then
                return param:getThis():getPermissionInfo(name, 0).packageName
            end
            return param:getThis():getPermissionGroupInfo(name, 0).packageName
        end)
    end)
    if not ok or owner == nil or param:isPackageVisible(owner) then
        return false
    end
    param:setArgument(0, name .. suffixof(param))
    return true, owner
end

function after(hook, param)
    local method = hook:getMethodName()
    -- The exception of Android for the name which no app defines has the name the app asked for: the same class,
    -- stack trace and cause, like package_probe
    local ex = param:getException()
    if LOOKUPS[method] and ex ~= nil then
        local substitute = param:getArgument(0)
        local suffix = suffixof(param)
        local message = ex:getMessage()
        if type(substitute) == 'string' and string.sub(substitute, -string.len(suffix)) == suffix and message ~= nil then
            message = replace(message, substitute, string.sub(substitute, 1, -string.len(suffix) - 1))
        end
        if message ~= ex:getMessage() then
            local fake = luajava.new(ex:getClass(), message)
            fake:setStackTrace(ex:getStackTrace())
            if ex:getCause() ~= nil then
                fake:initCause(ex:getCause())
            end
            param:setResult(fake)
        end
        return false
    end

    local result = param:getResult()
    if result == nil then
        return false
    end

    local packageName
    if method == 'getSyncAdapterPackagesForAuthorityAsUser' then
        -- The packages which sync an authority, of which the hidden ones are left out
        local kept = {}
        for index = 1, result['length'] do
            if param:isPackageVisible(result[index]) then
                kept[#kept + 1] = result[index]
            end
        end
        if #kept == result['length'] then
            return false
        end
        local fake = luajava.bindClass('java.lang.reflect.Array'):newInstance(luajava.bindClass('java.lang.String'), #kept)
        for index = 1, #kept do
            fake[index] = kept[index]
        end
        param:setResult(fake)
        return true, tostring(result['length']), tostring(#kept)
    elseif method == 'getSyncAdapterTypes' or method == 'getSyncAdapterTypesAsUser' then
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
    elseif method == 'getIsSyncable' then
        -- Whether an account syncs an authority: not syncable (0) for the sync adapter of a hidden app, like for an
        -- authority without one, which Android answers so (SyncManager.computeSyncable)
        local account = param:getArgument(0)
        local authority = param:getArgument(1)
        if result == 0 or account == nil or authority == nil then
            return false
        end
        local types = param:unhooked(function()
            return luajava.bindClass('android.content.ContentResolver'):getSyncAdapterTypes()
        end)
        for index = 1, (types ~= nil and types['length'] or 0) do
            local sync = types[index]
            if sync.authority == authority and sync.accountType == account.type then
                local name = syncpackage(param, sync)
                if name == nil or param:isPackageVisible(name) then
                    return false
                end
                param:setResult(0)
                return true, name, '0'
            end
        end
        return false
    elseif method == 'checkSignatures' or method == 'checkUidSignatures' then
        -- The user ids of two apps, like for a uid which is not in use; checkUidSignatures is the method of the binder
        -- proxy under checkSignatures, which apps can call by reflection
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
            -- The binder proxy, which apps can call by reflection, leaves the calls of PackageManager to its own hook:
            -- from Android 12 PackageManager keeps the answers of the proxy in a cache of the process, which must hold
            -- the real packages, because whether a user id stays visible depends on the call, like for an app calling
            -- this one (see XParam.isUidVisible)
            if hook:getClassName() == 'android.content.pm.IPackageManager$Stub$Proxy' and packagemanager(method) then
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
    elseif method == 'checkPermission' or method == 'checkComponentPermission' then
        -- A permission of a user id which is not in use is denied, which Android says for ones of hidden apps
        if result ~= 0 then
            return false
        end
        local permission = param:getArgument(0)
        local uid = param:getArgument(2) -- permission, pid, uid
        if method == 'checkComponentPermission' then
            -- Permission, uid, owning uid, exported: root, the system, no permission and the app which owns the component
            -- are granted before Android asks the package manager, also for a user id which is not in use
            uid = param:getArgument(1)
            local owning = param:getArgument(2)
            local app = uid % 100000 -- UserHandle.getAppId
            if app == 0 or app == 1000 or permission == nil or (owning >= 0 and app == owning % 100000) then
                return false
            end
        end
        if param:isUidVisible(uid) then
            -- A permission which a hidden app defines, like the own permission of a messenger: no app defines it
            local owner = (permission ~= nil and permissionowner(param, permission) or nil)
            if owner == nil or param:isPackageVisible(owner) then
                return false
            end
            param:setResult(-1) -- PackageManager.PERMISSION_DENIED
            return true, owner
        end
        param:setResult(-1) -- PackageManager.PERMISSION_DENIED
        return true, tostring(uid)
    elseif method == 'resolveContentProvider' or method == 'resolveContentProviderAsUser' then
        packageName = result.packageName -- ProviderInfo
    elseif method == 'getPermissionInfo' or method == 'getPermissionGroupInfo' then
        -- A permission which a hidden app defines is missing, which Android throws for; before has Android throw,
        -- this is for an app installed between the lookup of before and the call
        packageName = result.packageName
        if param:isPackageVisible(packageName) then
            return false
        end
        param:setResult(luajava.newInstance('android.content.pm.PackageManager$NameNotFoundException', param:getArgument(0)))
        return true, packageName
    elseif method == 'queryPermissionsByGroup' or method == 'getAllPermissionGroups' then
        -- The permissions which hidden apps define are left out; a group which a hidden app defines is missing, which
        -- Android throws for (see before)
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

-- The app which defines a permission, nil when none does; asked without the hooks of the app, once per process,
-- since Context.checkPermission runs for most permission checks of the app; the same function as in package_probe
function permissionowner(param, permission)
    local context = param:getApplicationContext()
    local key = 'permission.owner.' .. permission
    local owner = param:getValue(key, context)
    if owner == nil then
        local ok, name = pcall(function()
            return param:unhooked(function()
                return context:getPackageManager():getPermissionInfo(permission, 0).packageName
            end)
        end)
        owner = (ok and name or '')
        param:putValue(key, owner, context)
    end
    return (owner ~= '' and owner or nil)
end

-- The suffix of the names which no app defines, random for the process, so an app cannot ask for a name which looks
-- like a substitute; the same function and suffix as in package_probe, of the app and new each boot
function suffixof(param)
    local suffix = param:getSetting('package_probe.suffix')
    if suffix ~= nil then
        return suffix
    end
    local context = param:getApplicationContext()
    suffix = param:getValue('package_probe.suffix', context)
    if suffix == nil then
        suffix = '.x' .. math.random(100000000, 999999999)
        param:putValue('package_probe.suffix', suffix, context)
    end
    return suffix
end

-- All occurrences of a text, without patterns: the characters which are magic in patterns are escaped
function replace(text, old, new)
    return (string.gsub(text, (string.gsub(old, '%W', '%%%0')), (string.gsub(new, '%%', '%%%%'))))
end

-- Without a package or a component, like a link
function implicit(intent)
    return intent ~= nil and intent:getComponent() == nil and intent:getPackage() == nil
end

-- The visible apps of a query, only the browsers when asked; of all apps when none of them is visible, because of a
-- link Android gives only the default browser unless asked for all, which leaves none when it is hidden. The second
-- value is true for those of all apps, so the browsers of a link whose default browser is hidden.
function visibleof(param, pm, intent, flags, browsers)
    local visible = {}
    for pass, asked in ipairs({ flags, matchall(flags) }) do
        local all = param:unhooked(function()
            return pm:queryIntentActivities(intent, asked)
        end)
        for index = 0, all:size() - 1 do
            local info = all:get(index)
            if param:isPackageVisible(info.activityInfo.packageName) and (not browsers or info.handleAllWebDataURI) then
                visible[#visible + 1] = info
            end
        end
        if #visible > 0 then
            return visible, pass == 2
        end
    end
    return visible, false
end

-- The browsers of a link whose default browser is hidden, like on Android 12 and later without that browser, which
-- give the browser role to another one (BrowserRoleBehavior.getFallbackHolder): the only browser, else the only system
-- browser, else the default of the device, a system app or one named with its certificate; the link then resolves to
-- that browser, unless another one has a higher priority (CrossProfileIntentResolverEngine)
-- shortcut: the browsers of the link stand for those of http:, which a browser handles both, and the certificate of a
-- default named with one is not checked, which only an app with the name of that browser and another key would fail
function fallback(param, visible)
    local holders, browsers, system, top = {}, {}, {}, 0
    for _, info in ipairs(visible) do
        local name = info.activityInfo.packageName
        top = math.max(top, info.priority)
        if info.handleAllWebDataURI and info.activityInfo.exported then
            if holders[name] == nil then
                browsers[#browsers + 1] = name
                if info.activityInfo.applicationInfo.flags % 2 == 1 then -- ApplicationInfo.FLAG_SYSTEM
                    system[#system + 1] = name
                end
            end
            if holders[name] == nil or holders[name].priority < info.priority then
                holders[name] = info
            end
        end
    end
    local holder = (#browsers == 1 and browsers[1]) or (#system == 1 and system[1]) or nil
    if holder == nil then
        local resources = param:getApplicationContext():getResources()
        local id = resources:getIdentifier('config_defaultBrowser', 'string', 'android')
        local name, certificate = string.match(id ~= 0 and resources:getString(id) or '', '^([^:]*)(:?)')
        local info = holders[name]
        if info ~= nil and (certificate ~= '' or info.activityInfo.applicationInfo.flags % 2 == 1) then
            holder = name
        end
    end
    local info = (holder ~= nil and holders[holder] or nil)
    if info ~= nil and info.priority >= top then
        return { info }
    end
    return visible
end

-- With a link of the web, which Android resolves to the apps approved for its host, else to the browsers
function weblink(intent)
    local scheme = intent:getScheme()
    return scheme == 'http' or scheme == 'https'
end

-- The link for a host which no app claims, a name reserved for that (RFC 2606), which finds the browsers
function unclaimed(intent)
    local link = luajava.newInstance('android.content.Intent', intent)
    return link:setDataAndType(intent:getData():buildUpon():authority('example.invalid'):build(), intent:getType())
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
    local visible, hidden = visibleof(param, pm, intent, flags)
    -- A link which only hidden apps are approved for, like the verified links of a hidden app, of which Android gives
    -- only those, also when asked for all: without them it gives the browsers, the default one or all of them
    -- shortcut: Android 8.1-11 add the visible apps which claim the host without approval, and Android 12 and later
    -- give a visible app approved less than the hidden one; they need the package manager without the hidden apps
    if #visible == 0 and weblink(intent) then
        intent = unclaimed(intent)
        visible, hidden = visibleof(param, pm, intent, flags, true)
        chooser = nil
    end
    -- The default browser is hidden, whose role Android 12 and later give to another browser when it is not there
    -- shortcut: a visible app which claims the link besides a hidden default browser is left alone, without the browser
    -- Android would add; that needs the package manager without the hidden apps
    if hidden and visible[2] ~= nil and luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 31 then
        visible = fallback(param, visible)
    end
    local first, second = visible[1], visible[2]
    if second == nil or first.priority ~= second.priority or first.preferredOrder ~= second.preferredOrder or
            first.isDefault ~= second.isDefault then
        return first
    end
    -- Marked as a browser when all apps are, like Android does, which counted the hidden apps for a real chooser
    local browsers = true
    for index = 1, #visible do
        browsers = browsers and visible[index].handleAllWebDataURI == true
    end
    if chooser then
        if chooser.handleAllWebDataURI == browsers then
            return chooser
        end
        chooser = luajava.newInstance('android.content.pm.ResolveInfo', chooser)
        chooser.handleAllWebDataURI = browsers
        return chooser
    end
    -- The real chooser of the system, like resolving it by name gives, with the title of the action and the meta-data
    -- which show it in car mode, like Android does (chooseBestActivity)
    chooser = param:unhooked(function()
        return pm:resolveActivity(luajava.newInstance('android.content.Intent'):setClassName('android', RESOLVER), 0)
    end)
    chooser.handleAllWebDataURI = browsers
    pcall(function()
        chooser.activityInfo.labelRes = luajava.bindClass(RESOLVER):getLabelRes(intent:getAction())
    end)
    local meta = chooser.activityInfo.metaData or luajava.newInstance('android.os.Bundle')
    meta:putBoolean('android.dock_home', true) -- Intent.METADATA_DOCK_HOME
    -- Written and read again like the meta-data of Android comes with the chooser, which a Bundle keeps parcelled
    -- until the app reads it, so it prints like that one (BaseBundle.readFromParcelInner)
    local parcel = luajava.bindClass('android.os.Parcel'):obtain()
    parcel:writeBundle(meta)
    parcel:setDataPosition(0)
    chooser.activityInfo.metaData = parcel:readBundle()
    parcel:recycle()
    return chooser
end

-- Whether the method of ApplicationPackageManager is on the stack, which calls the proxy itself (from Android 12
-- through the cache of getPackagesForUid, so not right below the hook)
function packagemanager(method)
    local frames = luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
    for index = 1, frames['length'] do
        local frame = frames[index]
        if frame:getMethodName() == method and frame:getClassName() == 'android.app.ApplicationPackageManager' then
            return true
        end
    end
    return false
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
