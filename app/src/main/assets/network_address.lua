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

-- The addresses of the device, the gateway, DNS and DHCP servers and the domains
-- reveal the network the device is on and help to fingerprint it;
-- the private DNS server name can contain the account id of the user at a DNS service.
-- Loopback addresses stay, because apps communicate with local services with them.
-- The local address of sockets is the address of the device too, see getLocalAddress.

-- Called by LinkProperties itself, not counting the frame of the hooked method: it checks, compares, copies
-- and parcels its interface, routes and addresses through the getters, which must agree with its fields there
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
    local method = hook:getMethodName()
    local result = param:getResult()
    if result == nil then
        return false
    end

    if method == 'getIpAddress' then
        if result == 0 then
            return false
        end
        param:setResult(0)
        return true

    elseif method == 'getDhcpInfo' then
        param:setResult(luajava.newInstance('android.net.DhcpInfo'))
        return true

    elseif method == 'getLinkAddresses' or method == 'getDnsServers' or method == 'getRoutes' then
        if result:isEmpty() or linkproperties(method) then
            return false
        end
        param:setResult(luajava.newInstance('java.util.ArrayList'))
        return true

    elseif method == 'getLocalHost' then
        -- The address of the host name of the device, which is normally localhost
        if result:isLoopbackAddress() then
            return false
        end
        local fake = luajava.bindClass('java.net.InetAddress'):getLoopbackAddress()
        param:setResult(fake)
        return true, result:getHostAddress(), fake:getHostAddress()

    elseif method == 'getLocalAddress' or method == 'getLocalSocketAddress' or method == 'getsockname' or
            method == 'ioctlInetAddress' then
        -- The address a socket is bound to, which the system chose for its connection, like of a
        -- DatagramSocket connected to a server without sending anything; loopback, wildcard, link-local and
        -- multicast addresses stay, the others become the wildcard address of their family, like of a socket
        -- which is not bound. Socket and DatagramSocket give an InetAddress, channels and getsockname a
        -- SocketAddress, of which only those of IP change. The socket itself stays as it is.
        -- The hidden ioctlInetAddress of libcore gives the address, netmask or broadcast address of an interface.
        local socket = param:isInstance(result, 'java.net.InetSocketAddress')
        local address = (socket and result:getAddress() or result)
        if not param:isInstance(address, 'java.net.InetAddress') or address:isLoopbackAddress() or
                address:isAnyLocalAddress() or address:isLinkLocalAddress() or address:isMulticastAddress() then
            return false
        end
        local any = (address:getAddress()['length'] == 16 and '::' or '0.0.0.0')
        local fake = luajava.bindClass('java.net.InetAddress'):getByName(any)
        param:setResult(socket and luajava.newInstance('java.net.InetSocketAddress', fake, result:getPort()) or fake)
        return true, address:getHostAddress(), any

    elseif method == 'getDomains' or method == 'getDhcpServerAddress' or method == 'getPrivateDnsServerName' then
        -- Like without domains, DHCP and private DNS with a server name (strict mode)
        if linkproperties(method) then
            return false
        end
        param:setResult(nil)
        return true

    elseif method == 'getInetAddresses' or method == 'getInterfaceAddresses' then
        local collections = luajava.bindClass('java.util.Collections')
        local items
        if method == 'getInetAddresses' then
            items = collections:list(result):toArray()
        else
            items = result:toArray()
        end

        local list = luajava.newInstance('java.util.ArrayList')
        local removed = false
        for index = 1, items['length'] do
            local address = items[index]
            if method == 'getInterfaceAddresses' then
                address = address:getAddress()
            end
            if address ~= nil and address:isLoopbackAddress() then
                list:add(items[index])
            else
                removed = true
            end
        end

        -- The original enumeration is consumed here, so always set a new result
        if method == 'getInetAddresses' then
            param:setResult(collections:enumeration(list))
        else
            param:setResult(list)
        end
        return removed
    end

    return false
end
