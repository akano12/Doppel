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

-- WallpaperManager.getWallpaperInfo tells which app provides the live wallpaper. When that app is hidden from the
-- app asking (see package_probe), there is no live wallpaper, like a still image is set: the info is nil.

function after(hook, param)
    -- Processes of the system see all apps
    if param:isSystem() then
        return false
    end
    local info = param:getResult()
    if info == nil then
        return false
    end
    local packageName = info:getPackageName()
    if param:isPackageVisible(packageName) then
        return false
    end
    param:setResult(nil)
    return true, packageName
end
