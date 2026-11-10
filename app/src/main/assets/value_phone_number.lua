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

function after(hook, param)
    local result = param:getResult()
    -- Also an empty number, of a SIM without one, which stays empty like the reads which give it so
    if result == nil or result == '' then
        return false
    end

    local fake = param:getSetting('value.phone_number')
    -- Also the fake of the overload this one calls, like getLine1Number() calls getLine1Number(subId), which
    -- reported it already
    if result == fake then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
