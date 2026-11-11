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

-- The software version of the IMEI is 2 digits the modem reports, which tell its firmware: the common first version
-- instead, not null, which Android gives only without a phone or without the permission, so a phone with an IMEI has
-- one like with a modem; null and the "0" Android gives on CDMA phones, which tells nothing, stay
function after(hook, param)
    local result = param:getResult()
    -- shortcut: one version for every device and boot, a setting of Fake values when its values become user-spoofable
    local fake = '01'
    -- Also the fake of the overload this one calls, like getDeviceSoftwareVersion() calls the one with a slot,
    -- which reported it already
    if result == nil or result == '0' or result == fake or param:getException() ~= nil then
        return false
    end

    param:setResult(fake)
    return true, result, fake
end
