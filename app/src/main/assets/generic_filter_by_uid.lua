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

-- Copyright 2017-2019 Marcel Bokhorst (M66B)
-- Copyright 2026 akano12

-- Lists of other apps keep only the app itself. The lists of components, like of the activities for an intent,
-- and of properties also keep system apps, like the lookups of single packages (see package_probe), because the
-- Android framework uses them in the app process, like for launch intents, text to speech and speech recognition.

local MATCH_ALL = 0x20000 -- PackageManager

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

-- The next four functions are the same as in package_resolve, so a link lists the apps it resolves to (see best there)

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

-- The browser which gets the role of a hidden default browser on Android 12 and later (see package_resolve)
-- shortcut: the browsers of the link stand for those of http:, and the certificate of a default named with one is
-- not checked, which only an app with the name of that browser and another key would fail
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

-- PackageManager.getPreferredActivities fills two lists of the app, an activity and its filter at the same index, and
-- returns their number, which is 0 whatever it found on Android itself: the hidden apps are left out of both
function preferred(param, count)
    local filters, activities = param:getArgument(0), param:getArgument(1)
    local removed = 0
    for index = activities:size() - 1, 0, -1 do
        if not param:isPackageVisible(activities:get(index):getPackageName()) then
            filters:remove(index)
            activities:remove(index)
            removed = removed + 1
        end
    end
    if removed == 0 then
        return false
    end
    param:setResult(math.max(count - removed, 0))
    return true
end

-- Whether the user picked a document of a provider, which leaves the app a persisted grant; a provider can have
-- several authorities, separated by ;
function picked(param, authority)
    if authority == nil then
        return false
    end
    local permissions = param:getApplicationContext():getContentResolver():getPersistedUriPermissions()
    for index = 0, permissions:size() - 1 do
        local granted = permissions:get(index):getUri():getAuthority()
        if granted ~= nil and string.find(';' .. authority .. ';', ';' .. granted .. ';', 1, true) then
            return true
        end
    end
    return false
end

function after(hook, param)
    -- Processes of the system manage the packages of all apps
    if param:isSystem() then
        return false
    end

    -- PackageManager.getHomeActivities fills a list of the app with the launchers and returns the default one, null
    -- without one: the hidden launchers are left out of the list, and a hidden default is none
    if hook:getName() == 'PackageManager.getHomeActivities' then
        local activities, home = param:getArgument(0), param:getResult()
        local removed = 0
        for index = (activities ~= nil and activities:size() or 0) - 1, 0, -1 do
            if not param:isVisible(activities:get(index).activityInfo.applicationInfo) then
                activities:remove(index)
                removed = removed + 1
            end
        end
        if home ~= nil and not param:isPackageVisible(home:getPackageName()) then
            param:setResult(nil)
            return true, home:getPackageName()
        end
        return removed > 0
    end

    local list = param:getResult()
    if list == nil then
        return false
    end

    -- The standby buckets of all apps, a map by package, which needs usage access only (UsageStatsService)
    if hook:getName() == 'UsageStatsManager.getAppStandbyBuckets' then
        local names = list:keySet():toArray()
        local kept = luajava.newInstance('java.util.HashMap')
        for index = 1, names['length'] do
            if param:isPackageVisible(names[index]) then
                kept:put(names[index], list:get(names[index]))
            end
        end
        if kept:size() == names['length'] then
            return false
        end
        param:setResult(kept)
        return true, tostring(names['length']), tostring(kept:size())
    end

    local filtered = false
    local name = string.gsub(hook:getName(), '%.%d+$', '')
    name = string.gsub(name, 'AsUser$', '') -- Hidden methods for a user, with the items of their public twin
    if name == 'PackageManager.getPreferredActivities' then
        return preferred(param, list)
    end
    local cuid = param:getUid()

    local info = list:toArray()
    for index = info['length'], 1, -1 do
        local item = info[index]

        local uid
        local ai -- The app of a component
        local packageName -- A package without its app
        if item == nil then
            uid = -1
        elseif name == 'PackageManager.getInstalledPackages' or
                name == 'PackageManager.getPackagesHoldingPermissions' or
                name == 'PackageManager.getPreferredPackages' then
            uid = item.applicationInfo.uid -- PackageInfo
        elseif name == 'PackageManager.queryIntentActivities' or
                name == 'PackageManager.queryIntentActivityOptions' or
                name == 'PackageManager.queryBroadcastReceivers' then
            ai = item.activityInfo.applicationInfo -- ResolveInfo
        elseif name == 'PackageManager.queryIntentContentProviders' then
            ai = item.providerInfo.applicationInfo -- ResolveInfo
        elseif name == 'PackageManager.queryIntentServices' then
            ai = item.serviceInfo.applicationInfo -- ResolveInfo
        elseif name == 'PackageManager.queryContentProviders' then
            ai = item.applicationInfo -- ProviderInfo
        elseif name == 'LauncherApps.getActivityList' or name == 'LauncherApps.getShortcutConfigActivityList' then
            ai = item:getApplicationInfo() -- LauncherActivityInfo
        elseif string.match(name, '^AppWidgetManager%.') then
            packageName = item.provider:getPackageName() -- AppWidgetProviderInfo
        elseif string.match(name, '^PackageManager%.query%a+Property$') then
            packageName = item:getPackageName() -- PackageManager.Property
        elseif name == 'SearchManager.getSearchablesInGlobalSearch' then
            packageName = item:getSearchActivity():getPackageName() -- SearchableInfo
        elseif name == 'SearchManager.getGlobalSearchActivities' then
            ai = item.activityInfo.applicationInfo -- ResolveInfo
        elseif name == 'ActivityManager.getRunningTasks' then
            -- RunningTaskInfo, like the task of the launcher, which Android 8.1 to 11 lists to any app
            local component = item.baseActivity or item.topActivity
            packageName = (component ~= nil and component:getPackageName() or param:getPackageName())
        elseif name == 'TelecomManager.getSelfManagedPhoneAccounts' then
            packageName = item:getComponentName():getPackageName() -- PhoneAccountHandle of a VoIP app
        elseif name == 'DevicePolicyManager.getActiveAdmins' then
            packageName = item:getPackageName() -- ComponentName, system admins stay like system apps
        elseif name == 'PackageManager.queryInstrumentation' then
            -- InstrumentationInfo: the test runner and the package it tests are both visible, or it is left out
            packageName = item.packageName
            if not param:isPackageVisible(item.targetPackage) then
                packageName = item.targetPackage
            end
        else
            uid = item.uid
        end

        local visible
        if ai ~= nil then
            -- A document the user picked stays a document (DocumentsContract.isDocumentUri), see provider_hidden
            visible = param:isVisible(ai) or (name == 'PackageManager.queryIntentContentProviders' and
                    picked(param, item.providerInfo.authority))
        elseif packageName ~= nil then
            visible = param:isPackageVisible(packageName)
        else
            visible = (uid == cuid)
        end
        if not visible then
            filtered = true
            list:remove(index - 1)
        end
    end

    -- The activities of a package which only a hidden app has: what Android answers for a package which is not
    -- installed, like Collections.EMPTY_LIST of Android 14, asked without the hooks under a name no app has
    if filtered and list:isEmpty() and (name == 'LauncherApps.getActivityList' or
            name == 'LauncherApps.getShortcutConfigActivityList') then
        local launcher, user = param:getThis(), param:getArgument(1)
        local ok, missing = pcall(function()
            return param:unhooked(function()
                if name == 'LauncherApps.getActivityList' then
                    return launcher:getActivityList('android.nonexistent.package', user)
                end
                return launcher:getShortcutConfigActivityList('android.nonexistent.package', user)
            end)
        end)
        if ok and missing ~= nil and missing:isEmpty() then
            param:setResult(missing)
        end
    end

    -- Android answers null when there are no device admins (DevicePolicyManagerService.getActiveAdmins) and no
    -- processes in error (ActivityManager.getProcessesInErrorState), not an empty list
    if filtered and (name == 'DevicePolicyManager.getActiveAdmins' or name == 'ActivityManager.getProcessesInErrorState') and
            list:isEmpty() then
        param:setResult(nil)
    end

    -- Of a link Android gives only the default browser unless asked for all, and only the apps approved for its host
    -- whatever the flags: when they are hidden, the apps of a device without them, which resolveActivity resolves the
    -- link to too: the browsers, of which on Android 12 and later the one which gets the role of a hidden default
    -- browser (the same steps as best of package_resolve, with its shortcuts)
    -- shortcut: queryIntentActivitiesAsUser asks for the own user, which is the user of nearly every call
    if filtered and name == 'PackageManager.queryIntentActivities' and list:isEmpty() then
        local pm, intent, flags = param:getThis(), param:getArgument(0), param:getArgument(1)
        local visible, hidden = visibleof(param, pm, intent, flags)
        if #visible == 0 and weblink(intent) then
            visible, hidden = visibleof(param, pm, unclaimed(intent), flags, true)
        end
        if hidden and visible[2] ~= nil and luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 31 then
            visible = fallback(param, visible)
        end
        for _, item in ipairs(visible) do
            list:add(item)
        end
    end

    return filtered
end
