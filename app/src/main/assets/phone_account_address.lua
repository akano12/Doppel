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

-- The phone accounts of the SIMs have the phone number as their address (tel:), like getLine1Number: the fake number
-- (value.phone_number), else none. The accounts of the app itself and other addresses, like sip: of calling apps, stay.
function after(hook, param)
    local result = param:getResult()
    if result == nil or result:getScheme() ~= 'tel' then
        return false
    end
    local handle = param:getThis():getAccountHandle()
    if handle ~= nil and handle:getComponentName():getPackageName() == param:getPackageName() then
        return false
    end

    local number = param:getSetting('value.phone_number')
    local fake = nil
    if number ~= nil then
        fake = luajava.bindClass('android.net.Uri'):fromParts('tel', number, nil)
    end
    param:setResult(fake)
    return true, result:toString(), number
end
