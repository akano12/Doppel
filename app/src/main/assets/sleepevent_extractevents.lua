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

-- The sleep events of Play services (SleepSegmentEvent and SleepClassifyEvent): none, in the list Play services
-- gives for an intent which has the events extra, an unmodifiable ArrayList, also when it is empty;
-- Collections.emptyList, which it gives only without the extra, would be another class while hasEvents is true
function after(hook, param)
    local result = param:getResult()
    if result == nil or result:isEmpty() then
        return false
    end
    local none = luajava.newInstance('java.util.ArrayList')
    param:setResult(luajava.bindClass('java.util.Collections'):unmodifiableList(none))
    return true, tostring(result:size()), '0'
end
