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

-- The method is skipped like with generic_block, for methods of libraries in the app which R8 can make return void
-- when the app does not use the result, so the definition has no return type, like Clarity.initialize: false when
-- the method has a boolean result, since callers which unbox a Boolean would fail on null, else nothing.

function before(hook, param)
    if not pcall(param.setResult, param, false) then
        param:setResult(nil)
    end
    return true
end
