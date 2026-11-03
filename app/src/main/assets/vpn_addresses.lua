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

-- The LinkProperties of a VPN, which apps get for the active network with the interface of the network under it
-- (see vpn), still have the addresses of the tunnel, like 10.8.0.2/32, and the DNS servers of the VPN, which tell
-- there is a VPN: they are left out, like Read.Network leaves them out for all networks (see network_address).
-- LinkProperties gets the real ones when it checks, compares, copies or parcels itself.

-- Whether a network interface is of a VPN, like vpn, which a test compares
function isvpninterface(name)
    if name == nil then
        return false
    end
    for _, prefix in ipairs({ 'tun', 'ppp', 'tap', 'ipsec', 'wg', 'utun', 'l2tp', 'pptp', 'vpn' }) do
        if string.sub(name, 1, string.len(prefix)) == prefix then
            return true
        end
    end
    return false
end

-- Called by LinkProperties itself, not counting the frame of the hooked method, like network_address
function linkproperties(method)
    local frames = luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
    for index = 1, frames['length'] do
        local frame = frames[index]
        if frame:getClassName() == 'android.net.LinkProperties' and frame:getMethodName() ~= method then
            return true
        end
    end
    return false
end

function after(hook, param)
    local result = param:getResult()
    if result == nil or result:isEmpty() then
        return false
    end
    -- The real interface, which the hook of vpn would fake
    local properties = param:getThis()
    local name = param:unhooked(function()
        return properties:getInterfaceName()
    end)
    if not isvpninterface(name) or linkproperties(hook:getMethodName()) then
        return false
    end
    param:setResult(luajava.newInstance('java.util.ArrayList'))
    return true
end
