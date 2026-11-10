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

-- MAC addresses are fixed hardware ids. Recent Android versions hide them from apps,
-- older versions and apps targeting them still get the real ones.
-- The fake values are those of Android: 02:00:00:00:00:00, as text and as the bytes of an interface, which
-- NetworkInterface gives when the address is not accessible; null stays null, like for an interface without one.

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    if hook:getMethodName() == 'getHardwareAddress' then
        if result['length'] == 6 and result[1] == 2 and result[2] == 0 and result[3] == 0 and result[4] == 0 and
                result[5] == 0 and result[6] == 0 then
            return false
        end
        local bytes = luajava.bindClass('java.lang.reflect.Array'):newInstance(luajava.bindClass('java.lang.Byte').TYPE, 6)
        bytes[1] = 2
        param:setResult(bytes)
        return true
    end

    local fake = '02:00:00:00:00:00'
    if result == fake then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
