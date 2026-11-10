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

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    -- The unknown activity at 100%, in a mutable list like Play services gives, at the time of the real result;
    -- luajava.bindClass uses the thread context class loader, which cannot see the Play services classes of the app on
    -- all threads
    local ok, fake = pcall(function()
        local classActivity = param:findClass('com.google.android.gms.location.DetectedActivity')
        local activities = luajava.newInstance('java.util.ArrayList')
        activities:add(luajava.new(classActivity, 4, 100))
        local classResult = param:findClass('com.google.android.gms.location.ActivityRecognitionResult')
        return luajava.new(classResult, activities, result:getTime(), result:getElapsedRealtimeMillis())
    end)
    -- Without the members, which R8 can remove from an app which does not use them: no result, like an intent without
    -- one, rather than the real one
    if not ok then
        fake = nil
    end
    param:setResult(fake)
    return true, result, fake
end
