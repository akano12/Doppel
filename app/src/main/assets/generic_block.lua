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

-- The method is skipped, with the result of its return type (see the definition): false for a boolean method,
-- like a registration, 0 for a method returning a result code, of which 0 means success, nothing for a void method.
-- A Boolean is false too, because callers which unbox it would fail on null, like Clarity.initialize.

function before(hook, param)
    local returnType = hook:getReturnType()
    if returnType == 'boolean' or returnType == 'java.lang.Boolean' then
        param:setResult(false)
    elseif returnType == 'int' then
        param:setResult(0)
    else
        param:setResult(nil)
    end
    return true
end
