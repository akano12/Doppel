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
-- Copyright 2026 akano12

-- A result without transitions: apps check with hasResult, which is not hooked, that the intent has a result
function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    -- luajava.bindClass uses the thread context class loader,
    -- which cannot see the Play services classes of the app on all threads
    local classResult = param:findClass('com.google.android.gms.location.ActivityTransitionResult')
    local fake = luajava.new(classResult, luajava.newInstance('java.util.ArrayList'))

    param:setResult(fake)
    return true, result, fake
end
