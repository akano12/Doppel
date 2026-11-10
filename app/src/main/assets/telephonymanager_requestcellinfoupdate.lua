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

-- Android answers a request for the cell info once, with the cells or an error, so apps can wait for the answer;
-- the answer is without cells, like Android gives it when location is off, on a thread of its own, like Android
-- answers from a thread of the binder. Without a location permission the request runs, which Android refuses with
-- a SecurityException before it reads any cell, and so do requests without an executor or a callback.
function before(hook, param)
    local executor = param:getArgument(0)
    local callback = param:getArgument(1)
    if executor == nil or callback == nil or not located(param) then
        return false
    end
    -- Skip the request first: an executor which runs the answer directly can throw, like the app on no cells,
    -- and an error of the script would let the real request through; Android ignores errors of the answer as well
    param:setResult(nil)
    local answer = luajava.createProxy('java.lang.Runnable', {
        run = function()
            pcall(executor.execute, executor, luajava.createProxy('java.lang.Runnable', {
                run = function()
                    callback:onCellInfo(luajava.newInstance('java.util.ArrayList'))
                end
            }))
        end
    })
    luajava.newInstance('java.lang.Thread', answer):start()
    return true
end

-- Whether the app may ask for the location, coarse or fine, without which Android throws for cells
function located(param)
    local context = param:getApplicationContext()
    return context:checkSelfPermission('android.permission.ACCESS_COARSE_LOCATION') == 0 or
            context:checkSelfPermission('android.permission.ACCESS_FINE_LOCATION') == 0
end
