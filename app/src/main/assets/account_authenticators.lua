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

-- The account types of the device name the apps offering them (packageName), which Android filters by the package
-- visibility rules from Android 11 only, and not for apps which see all packages: the types of hidden apps are left
-- out (see XParam.isPackageVisible), those of the system and of the app itself stay.
function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end
    local kept = {}
    for index = 1, result['length'] do
        if param:isPackageVisible(result[index].packageName) then
            kept[#kept + 1] = result[index]
        end
    end
    if #kept == result['length'] then
        return false
    end
    local arrayClass = luajava.bindClass('java.lang.reflect.Array')
    local fake = arrayClass:newInstance(param:findClass('android.accounts.AuthenticatorDescription'), #kept)
    for index = 1, #kept do
        fake[index] = kept[index]
    end
    param:setResult(fake)
    return true, tostring(result['length']), tostring(#kept)
end
