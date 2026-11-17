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

-- LauncherApps posts the package changes it is told about to each callback of the app, by the CallbackMessageHandler
-- of the callback; Android 8.1 to 11 tell about every package, also of the profiles of the user. The app hears only of
-- its own packages of its own user, like on a device without other apps: its own package and the packages sharing its
-- user id, which Android always lets it see. An event of another package or user is not posted, and a list keeps only
-- the app's own packages, like Android 12 and later post nothing when the packages the app may see leave none. From
-- Android 15 QPR2 the settings of a user, like whether the entry of a private space is hidden, are posted as well,
-- only for the app's own user. Android 16 QPR2 and 17 write an added package to the app's own log before the handler
-- posts it, so the listener of LauncherApps, with the user first, drops it the same way before that. Processes of the
-- system manage the packages of all apps and hear of every change.
-- shortcut: a package sharing the app's user id which was just removed is no longer of the user id, so its removal is
-- not posted; upgrade by remembering the packages of the user id when an app of a shared user id needs it
function before(hook, param)
    if param:isSystem() then
        return false
    end
    local types = hook:getParameterTypes()
    -- The packages of the app's user id, looked up once an event names another package, without the hooks of the app,
    -- which would else restrict the lookup and report a call the app did not make
    local own = nil
    local function mine(name)
        if name == param:getPackageName() then
            return true
        end
        if own == nil then
            own = {}
            local packages = param:unhooked(function()
                return param:getApplicationContext():getPackageManager():getPackagesForUid(param:getUid())
            end)
            for index = 1, (packages == nil and 0 or packages['length']) do
                own[packages[index]] = true
            end
        end
        return (name ~= nil and own[name] == true)
    end
    -- The size of a list which keeps only the app's own packages, posted once the user, which follows it, is the app's
    local trimmed, left = nil, nil
    for index = 1, types['length'] do
        local kind = types[index]
        local value = param:getArgument(index - 1)
        if kind == 'android.os.UserHandle' then
            if value == nil or not value:equals(luajava.bindClass('android.os.Process'):myUserHandle()) then
                param:setResult(nil)
                return true, tostring(value)
            end
        elseif kind == 'java.lang.String' then
            if not mine(value) then
                param:setResult(nil)
                return true, tostring(value)
            end
        elseif kind == 'java.lang.String[]' then
            local kept = {}
            local count = (value == nil and 0 or value['length'])
            for i = 1, count do
                if mine(value[i]) then
                    kept[#kept + 1] = value[i]
                end
            end
            if #kept == 0 then
                param:setResult(nil)
                return true, tostring(count)
            end
            if #kept < count then
                local names = luajava.bindClass('java.lang.reflect.Array'):newInstance(
                        luajava.bindClass('java.lang.String'), #kept)
                for i = 1, #kept do
                    names[i] = kept[i]
                end
                param:setArgument(index - 1, names)
                trimmed, left = count, #kept
            end
        elseif kind == 'android.content.pm.LauncherUserInfo' then
            -- The info of a user names it by its serial number, which the app's own user has too
            local serial = param:unhooked(function()
                return param:getApplicationContext():getSystemService('user'):getSerialNumberForUser(
                        luajava.bindClass('android.os.Process'):myUserHandle())
            end)
            if value == nil or value:getUserSerialNumber() ~= serial then
                param:setResult(nil)
                return true
            end
        end
    end
    if trimmed ~= nil then
        return true, tostring(trimmed), tostring(left)
    end
    return false
end
