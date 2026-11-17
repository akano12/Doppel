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

-- Usage access (PACKAGE_USAGE_STATS) gives the storage used by another uid or package and by the whole user, which
-- tells which apps are installed; it reads as not granted (see package_probe), so these answer like for an app without
-- it, with the SecurityException of StorageStatsService. The call still goes to Android first, whose checks before
-- usage access stay: a uid or user of another user without INTERACT_ACROSS_USERS keeps its SecurityException, a
-- package which is not installed (also a hidden one, see package_probe) its NameNotFoundException, and an app without
-- usage access gets the exception of Android itself. An IOException, like of an unknown volume, comes after the check
-- of usage access and becomes its exception too. The app's own uid and the packages of that uid (its own and those
-- sharing its user id) need no usage access and stay.
-- shortcut: the app-op note which the service makes still reaches an OnOpNotedCallback of the app, upgrade by dropping
-- it there when apps are seen counting it

-- Whether a package is of the app's own uid, looked up without the hooks of the app like StorageStatsService does, also
-- uninstalled with its data kept; one which is not installed is not
function ownuid(param, name)
    local ok, uid = pcall(param.unhooked, param, function()
        return param:getApplicationContext():getPackageManager():getPackageUid(name, 0x2000) -- MATCH_UNINSTALLED_PACKAGES
    end)
    return ok and uid == luajava.bindClass('android.os.Process'):myUid()
end

-- The message of StorageStatsService for a caller without usage access: Context.enforceCallingOrSelfPermission up to
-- Android 10, its own text from Android 11
function denial(param)
    local uid = luajava.bindClass('android.os.Process'):myUid()
    if luajava.bindClass('android.os.Build$VERSION').SDK_INT < 30 then
        return 'StorageStatsService: Neither user ' .. uid .. ' nor current process has android.permission.PACKAGE_USAGE_STATS.'
    end
    return 'Caller does not have android.permission.PACKAGE_USAGE_STATS; callingPackage=' ..
        param:getApplicationContext():getOpPackageName() .. ', callingUid=' .. uid
end

function after(hook, param)
    -- Processes of the system see all apps
    local ex = param:getException()
    if param:isSystem() or (ex ~= nil and not param:isInstance(ex, 'java.io.IOException')) then
        return false
    end
    -- queryStatsForUid: volume, uid; queryStatsForPackage: volume, package, user
    local method = hook:getMethodName()
    if method == 'queryStatsForUid' and param:getArgument(1) == luajava.bindClass('android.os.Process'):myUid() or
            method == 'queryStatsForPackage' and (param:getArgument(1) == param:getPackageName() or
            ownuid(param, param:getArgument(1))) then
        return false
    end
    param:setResult(luajava.newInstance('java.lang.SecurityException', denial(param)))
    return true, tostring(param:getArgument(1))
end
