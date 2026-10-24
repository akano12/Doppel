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
-- Copyright 2026 akano12

-- Lists of other apps keep only the app itself. The lists of components, like of the activities for an intent,
-- and of properties also keep system apps, like the lookups of single packages (see package_probe), because the
-- Android framework uses them in the app process, like for launch intents, text to speech and speech recognition.

function after(hook, param)
    -- Processes of the system manage the packages of all apps
    if param:isSystem() then
        return false
    end

    local list = param:getResult()
    if list == nil then
        return false
    end

    local filtered = false
    local name = string.gsub(hook:getName(), '%.%d+$', '')
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
        elseif name == 'LauncherApps.getActivityList' then
            ai = item:getApplicationInfo() -- LauncherActivityInfo
        elseif string.match(name, '^PackageManager%.query%a+Property$') then
            packageName = item:getPackageName() -- PackageManager.Property
        else
            uid = item.uid
        end

        local visible
        if ai ~= nil then
            visible = param:isVisible(ai)
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

    return filtered
end
