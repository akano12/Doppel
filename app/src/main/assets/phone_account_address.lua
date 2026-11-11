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
-- (value.phone_number), else an empty one (tel:), which Android gives for a SIM without a number; never null, which
-- Android does not give for a SIM. The accounts of the app itself and other addresses, like sip: of calling apps, stay.
function after(hook, param)
    local result = param:getResult()
    local fake = fakeaddress(param, param:getThis(), result)
    if fake == nil then
        return false
    end
    param:setResult(fake)
    return true, result:toString(), fake:getSchemeSpecificPart()
end

-- The fake address of a phone account, nil when the address stays; the same function as in telephony_createfromparcel,
-- which a test compares
function fakeaddress(param, account, address)
    if address == nil or address:getScheme() ~= 'tel' then
        return nil
    end
    local handle = account:getAccountHandle()
    if handle ~= nil and handle:getComponentName():getPackageName() == param:getPackageName() then
        return nil
    end

    local number = param:getSetting('value.phone_number') or ''
    if address:getSchemeSpecificPart() == number then
        return nil
    end
    return luajava.bindClass('android.net.Uri'):fromParts('tel', number, nil)
end
