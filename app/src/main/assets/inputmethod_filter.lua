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

-- Installed keyboards reveal installed apps and help to fingerprint devices.
-- Keyboards of other apps are hidden, like for checks for specific packages (package_probe),
-- keyboards of the system and of the app itself stay.

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    if hook:getMethodName() == 'getCurrentInputMethodInfo' then
        if param:isPackageVisible(result:getPackageName()) then
            return false
        end
        param:setResult(nil)
        return true, result:getPackageName(), nil
    end

    local filtered = false
    local items = result:toArray()
    local list = luajava.newInstance('java.util.ArrayList')
    for index = 1, items['length'] do
        if param:isPackageVisible(items[index]:getPackageName()) then
            list:add(items[index])
        else
            filtered = true
        end
    end
    if filtered then
        param:setResult(list)
    end
    return filtered
end
