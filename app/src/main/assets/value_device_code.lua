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

-- The type allocation code is the first 8 digits of the IMEI and the manufacturer code the first 8 digits of the MEID,
-- which Android gives without any permission: those of the fake IMEI and MEID, so they agree with getImei and getMeid;
-- null where Android gives null, like for a slot without a phone, and all zeros where the id is all zeros and its fake
-- is set, which value_device_id.lua keeps then as the none of a modem without it
-- shortcut: without a fake IMEI or MEID, their default, the codes are null like for a modem which reports no ids, while
-- getPhoneType, getActiveModemCount and getSimState, which need no permission either, stay those of the phone, which
-- a booted phone with a working modem does not give together; give the code of a type many phones share, or hide the
-- phone, if the user decides so
function after(hook, param)
    local result = param:getResult()
    if result == nil or param:getException() ~= nil then
        return false
    end
    local id
    if hook:getMethodName() == 'getManufacturerCode' then
        id = param:getSetting('value.meid')
    else
        id = param:getSetting('value.imei')
    end
    local fake = (id ~= nil and string.len(id) >= 8 and string.sub(id, 1, 8) or nil)
    if result == fake or (id ~= nil and string.match(result, '^0*$')) then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
