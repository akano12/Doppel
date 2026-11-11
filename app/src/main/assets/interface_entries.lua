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
-- (vpn, see vpn) and the hardware addresses (mac, see mac_address), which go out of their entries while those stay.
-- Apps which may not use hidden APIs cannot call it; then reflection does not find the fields here either, so the
-- entries stay, like the original result.
-- Not covered is Libcore.rawOs, the native getifaddrs, which the hooked one calls.

-- The interfaces of VPNs, the same as vpn, which a unit test checks
function isvpninterface(name)
    -- tunl0 is the tunnel of the kernel for IP in IP, which every device has, VPN or not
    if type(name) ~= 'string' or string.sub(name, 1, 4) == 'tunl' then
        return false
    end
    for _, prefix in ipairs({ 'tun', 'ppp', 'tap', 'ipsec', 'wg', 'utun', 'l2tp', 'pptp', 'vpn' }) do
        if string.sub(name, 1, string.len(prefix)) == prefix then
            return true
        end
    end
    return false
end

-- Whether a variant leaves out an entry (true), or the entry which takes its place
local OUT = {
    network = function(entry)
        local address = entry.ifa_addr
        return address ~= nil and not address:isLoopbackAddress()
    end,
    vpn = function(entry)
        return isvpninterface(entry.ifa_name)
    end,
    -- The entry stays like that of an interface without a hardware address (lo or sit0): an interface without
    -- addresses (Wi-Fi off, dummy0 or p2p0) has only this entry, and NetworkInterface lists the names of the entries
    mac = function(entry)
        return entry.hwaddr ~= nil and luajava.new(entry:getClass(), entry.ifa_name, entry.ifa_flags, entry.ifa_addr,
            entry.ifa_netmask, entry.ifa_broadaddr, nil)
    end,
}

function after(hook, param)
    local out = OUT[string.match(hook:getName(), '/(.*)$')]
    local result = param:getResult()
    if out == nil or result == nil then
        return false
    end

    local kept, changed = {}, false
    for index = 1, result['length'] do
        local entry = result[index]
        local fake = out(entry)
        if fake then
            changed = true
        end
        if fake ~= true then
            kept[#kept + 1] = fake or entry
        end
    end
    if not changed then
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
