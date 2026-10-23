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

-- The method is skipped, with the result of its return type (see the definition): false for a boolean method,
-- like a registration, 0 for a method returning a result code, of which 0 means success, nothing for a void method

function before(hook, param)
    local returnType = hook:getReturnType()
    if returnType == 'boolean' then
        param:setResult(false)
    elseif returnType == 'int' then
        param:setResult(0)
    else
        param:setResult(nil)
    end
    return true
end
