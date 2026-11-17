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

-- Collections.emptyList, for a method of which Android makes that list when there is nothing, like the subscriptions
-- of Android 12 for a caller who cannot read the phone state, where generic_empty would make an ArrayList, which is
-- another class and can be changed
function after(hook, param)
    local result = param:getResult()
    if result == nil or result:isEmpty() then
        return false
    end

    param:setResult(luajava.bindClass('java.util.Collections'):emptyList())
    return true
end
