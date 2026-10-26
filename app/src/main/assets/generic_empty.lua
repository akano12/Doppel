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

-- An empty collection or array of the kind of the result, which stays when it is empty already:
-- a list, a set or an array like String[], of the component type of the return type of the definition

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local fake
    if param:isInstance(result, 'java.util.Collection') then
        if result:isEmpty() then
            return false
        end
        fake = luajava.newInstance(param:isInstance(result, 'java.util.Set') and 'java.util.HashSet' or 'java.util.ArrayList')
    else
        if result.length == 0 then
            return false
        end
        -- The type of the array, like java.lang.String[], or [Ljava.lang.String; of the JVM
        local returnType = hook:getReturnType()
        local component = string.match(returnType, '^(.*)%[%]$') or string.match(returnType, '^%[L(.*);$')
        local arrayClass = luajava.bindClass('java.lang.reflect.Array')
        fake = arrayClass:newInstance(luajava.bindClass(component), 0)
    end
    param:setResult(fake)
    return true
end
