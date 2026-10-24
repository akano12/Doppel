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

-- Installed keyboards and accessibility services reveal installed apps and help to fingerprint devices.
-- Services of other apps are hidden, like for checks for specific packages (package_probe),
-- services of the system and of the app itself stay.

-- Whether a service is of a system app or of the app itself (see XParam.isVisible). The app of a service is part
-- of the service info, so this works for apps which are hidden by the package visibility rules too.
function visible(hook, param, item)
    local ai
    if hook:getClassName() == 'android.view.accessibility.AccessibilityManager' then
        ai = item:getResolveInfo().serviceInfo.applicationInfo -- AccessibilityServiceInfo
    else
        ai = item:getServiceInfo().applicationInfo -- InputMethodInfo
    end
    return param:isVisible(ai)
end

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    if hook:getMethodName() == 'getCurrentInputMethodInfo' then
        if visible(hook, param, result) then
            return false
        end
        param:setResult(nil)
        return true, result:getPackageName(), nil
    end

    local filtered = false
    local items = result:toArray()
    local list = luajava.newInstance('java.util.ArrayList')
    for index = 1, items['length'] do
        if visible(hook, param, items[index]) then
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
