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

-- A request for VPN networks, like NetworkRequest.Builder().addTransportType(TRANSPORT_VPN) for a callback when a VPN
-- connects, which Android matches itself: for LoWPAN instead, which phones do not have, so it finds no network.
-- removeTransportType(TRANSPORT_VPN) removes it again. The hooks of vpn show the request with VPN in place of LoWPAN,
-- like the app made it (hasTransport, getTransportTypes, toString). NetworkCapabilities.Builder is a system API,
-- which apps cannot use.

local TRANSPORT_VPN = 4
local TRANSPORT_LOWPAN = 6

function before(hook, param)
    if param:getArgument(0) == TRANSPORT_VPN then
        param:setArgument(0, TRANSPORT_LOWPAN)
        return true, tostring(TRANSPORT_VPN), tostring(TRANSPORT_LOWPAN)
    end
    return false
end
