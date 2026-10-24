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

-- Spell checkers of other apps are hidden, like the keyboards they usually come with (service_filter),
-- spell checkers of the system and of the app itself stay. The app of a spell checker is part of the
-- spell checker info, so this works for apps which are hidden by the package visibility rules too.
-- Spell checking in the app is not affected, the framework does not use these methods for it.

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local method = hook:getMethodName()
    if method == 'getCurrentSpellChecker' or method == 'getCurrentSpellCheckerInfo' then
        if param:isVisible(result:getServiceInfo().applicationInfo) then
            return false
        end
        param:setResult(nil)
        return true, result:getPackageName(), nil
    end

    -- getEnabledSpellCheckers returns an array, getEnabledSpellCheckerInfos a list
    local array = (method == 'getEnabledSpellCheckers')
    local items = (array and result or result:toArray())
    local kept = luajava.newInstance('java.util.ArrayList')
    for index = 1, items['length'] do
        if param:isVisible(items[index]:getServiceInfo().applicationInfo) then
            kept:add(items[index])
        end
    end
    if kept:size() == items['length'] then
        return false
    end
    if array then
        local infoClass = luajava.bindClass('android.view.textservice.SpellCheckerInfo')
        local arrayClass = luajava.bindClass('java.lang.reflect.Array')
        param:setResult(kept:toArray(arrayClass:newInstance(infoClass, 0)))
    else
        param:setResult(kept)
    end
    return true, tostring(items['length']), tostring(kept:size())
end
