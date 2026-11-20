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

-- The packages installed, updated or removed since the device started, without those of other apps,
-- like the package lists are filtered (see generic_filter_by_uid). When none is left, Android 9 to 12 answer null,
-- since they build the list for the caller and return null for an empty one, and Android 13 and later the changes
-- with an empty list, since they take the names the caller may not see out of the changes they return
-- (PackageManagerService.getChangedPackages).

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    -- Removed packages are not visible either
    local names = result:getPackageNames()
    local kept = luajava.newInstance('java.util.ArrayList')
    for index = 1, names:size() do
        local packageName = names:get(index - 1)
        if param:isPackageVisible(packageName) then
            kept:add(packageName)
        end
    end
    if kept:size() == names:size() then
        return false
    end

    if kept:isEmpty() and luajava.bindClass('android.os.Build$VERSION').SDK_INT < 33 then
        param:setResult(nil)
    else
        param:setResult(luajava.newInstance('android.content.pm.ChangedPackages', result:getSequenceNumber(), kept))
    end
    return true
end
