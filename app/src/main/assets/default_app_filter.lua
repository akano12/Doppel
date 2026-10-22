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

-- The default SMS app and the default phone app are only told when they are of the system or the app itself,
-- else there is none, like for an app which is hidden by the package visibility rules (see package_probe);
-- the app itself can still tell whether it is the default

function after(hook, param)
    local result = param:getResult()
    if result == nil or result == param:getPackageName() then
        return false
    end

    -- The package manager does not tell about apps hidden by the package visibility rules
    local pm = param:getApplicationContext():getPackageManager()
    local ok, ai = pcall(pm.getApplicationInfo, pm, result, 0)
    if ok and ai ~= nil and param:isVisible(ai) then
        return false
    end
    param:setResult(nil)
    return true, result, nil
end
