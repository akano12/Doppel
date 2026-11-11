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

-- The activity of HMS Location Kit of Huawei, like that of Play services: the identification is OTHERS (104), which
-- HMS gives when it cannot tell the activity, at 100, in a mutable list like the one of a parcel, at the time of the
-- real response; the conversions (transitions) are none. Of getDataFromIntent and of the creators, which make the
-- responses of the extras it reads, also when the app reads them itself. An intent without a response stays without
-- one.
function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    -- luajava.bindClass uses the thread context class loader, which cannot see the HMS classes of the app on all threads
    local ok, fake = pcall(function()
        if param:isInstance(result, 'com.huawei.hms.location.ActivityConversionResponse') then
            return luajava.new(param:findClass('com.huawei.hms.location.ActivityConversionResponse'))
        end
        local datas = luajava.newInstance('java.util.ArrayList')
        datas:add(luajava.new(param:findClass('com.huawei.hms.location.ActivityIdentificationData'), 104, 100))
        return luajava.new(param:findClass('com.huawei.hms.location.ActivityIdentificationResponse'),
                datas, result:getTime(), result:getElapsedTimeFromReboot())
    end)
    -- Without the members, which the rule Huawei documents for apps keeps (see hooks.json): no response, like an intent without
    -- one, rather than the real one; the creator keeps the real one, like that of Play services, since a creator never gives none
    if not ok then
        if hook:getMethodName() ~= 'getDataFromIntent' then
            return false
        end
        fake = nil
    end
    param:setResult(fake)
    return true, result, fake
end
