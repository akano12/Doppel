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

-- With usage access (PACKAGE_USAGE_STATS) the storage used by a uid tells its apps are installed.
-- StorageStatsManager.queryStatsForUid of a hidden uid returns zero stats, like a uid without installed packages,
-- which is what the system returns anyway (see package_probe for queryStatsForPackage). The call still goes to
-- Android first, whose checks come before any package is looked up: a caller without usage access, a uid of another
-- user without INTERACT_ACROSS_USERS and an unknown volume keep the SecurityException or IOException of Android,
-- like for the uids of the system and of visible apps (StorageStatsService.queryStatsForUid). The zero StorageStats is
-- read back from a parcel holding its class name and zero longs, the same bytes writeParcelable produces; the
-- framework resolves its CREATOR, so neither the hidden empty constructor nor the non-public CREATOR class is
-- reached from Lua. The surplus longs are ignored, the reader of each Android version takes only the fields it has.

function after(hook, param)
    -- Processes of the system see all apps; the uid is the second argument (uuid, uid)
    if param:getException() ~= nil or param:isSystem() or param:isUidVisible(param:getArgument(1)) then
        return false
    end
    local parcel = luajava.bindClass('android.os.Parcel'):obtain()
    parcel:writeString('android.app.usage.StorageStats')
    for _ = 1, 16 do
        parcel:writeLong(0)
    end
    parcel:setDataPosition(0)
    local stats = parcel:readParcelable(nil)
    parcel:recycle()
    param:setResult(stats)
    return true, tostring(param:getArgument(1))
end
