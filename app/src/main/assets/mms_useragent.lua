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

-- The user agent and the profile of MMS, which messaging apps send to the carrier, can name the model of the device,
-- like SAMSUNG-SM-G991B and .../uaprof/SM-G991B.xml. Values other than the generic ones of Android become those,
-- which carriers accept from many devices, so MMS keep working; the generic ones and empty ones stay.
local GENERIC = {
    getMmsUserAgent = 'Android-Mms/2.0',
    getMmsUAProfUrl = 'http://www.google.com/oha/rdf/ua-profile-kila.xml',
}

function after(hook, param)
    local result = param:getResult()
    local fake = GENERIC[hook:getMethodName()]
    if result == nil or result == '' or fake == nil or result == fake then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
