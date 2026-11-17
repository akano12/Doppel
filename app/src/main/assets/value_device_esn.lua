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

-- The electronic serial number of a CDMA phone, which privileged apps get by the hidden getEsn: none, like
-- getDeviceId gives for it (value_device_id); null and the "0" Android gives on GSM phones, which tells nothing, stay
function after(hook, param)
    local result = param:getResult()
    if result == nil or result == '0' or param:getException() ~= nil then
        return false
    end

    param:setResult(nil)
    return true, result
end
