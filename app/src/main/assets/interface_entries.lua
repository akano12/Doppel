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

-- getifaddrs of libcore lists the interfaces with their addresses, an entry per address and one with the hardware
-- address of each interface. NetworkInterface reads it, and apps which may use the APIs hidden by Android can call it
-- themselves, which would bypass the hooks of NetworkInterface. Its entries are left out like those hooks leave out
-- what they hide: the addresses of the device, loopback stays (network, see network_address), the interfaces of VPNs
-- (vpn, see vpn) and the hardware addresses (mac, see mac_address). Apps which may not use hidden APIs cannot call it;
-- then reflection does not find the fields here either, so the entries stay, like the original result.
-- Not covered is Libcore.rawOs, the native getifaddrs, which the hooked one calls.

-- The interfaces of VPNs, the same as vpn, which a unit test checks
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

-- Whether a variant leaves out an entry
local OUT = {
    network = function(entry)
        local address = entry.ifa_addr
        return address ~= nil and not address:isLoopbackAddress()
    end,
    vpn = function(entry)
        return isvpninterface(entry.ifa_name)
    end,
    mac = function(entry)
        return entry.hwaddr ~= nil
    end,
}

function after(hook, param)
    local out = OUT[string.match(hook:getName(), '/(.*)$')]
    local result = param:getResult()
    if out == nil or result == nil then
        return false
    end

    local kept = {}
    for index = 1, result['length'] do
        if not out(result[index]) then
            kept[#kept + 1] = result[index]
        end
    end
    if #kept == result['length'] then
        return false
    end

    -- An array of StructIfaddrs, the class of the entries (a final class); a Java array has no methods in Lua
    local array = luajava.bindClass('java.lang.reflect.Array'):newInstance(result[1]:getClass(), #kept)
    for index, entry in ipairs(kept) do
        array[index] = entry
    end
    param:setResult(array)
    return true, tostring(result['length']), tostring(#kept)
end
