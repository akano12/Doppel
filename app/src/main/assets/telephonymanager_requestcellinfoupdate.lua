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

-- Android answers a request for the cell info once, with the cells or an error, so apps can wait for the answer;
-- the answer is without cells, like Android gives it when location is not allowed
function before(hook, param)
    local executor = param:getArgument(0)
    local callback = param:getArgument(1)
    if executor ~= nil and callback ~= nil then
        local answer = luajava.createProxy('java.lang.Runnable', {
            run = function()
                callback:onCellInfo(luajava.newInstance('java.util.ArrayList'))
            end
        })
        executor:execute(answer)
    end
    param:setResult(nil)
    return true
end
