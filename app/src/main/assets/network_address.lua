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
-- The local address of sockets is the address of the device too, see unbound, and so are the addresses of the
-- interfaces, see before.

-- Called by LinkProperties itself where it copies, parcels or compares itself: it reads its interface, routes and
-- addresses through the getters there, which must agree with its fields. Not where an app adds a route it got:
-- addRoute checks the interface of the route against the one the app set, which is the fake one then.
-- Only the frames of LinkProperties right below the hook count, the frames further down are of its caller.
function linkproperties()
    local frames = luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
    local inside = false
    for index = 1, frames['length'] do
        local frame = frames[index]
        local class = frame:getClassName()
        if class == 'android.net.LinkProperties' or class == 'android.net.LinkProperties$1' then
            inside = true
            local method = frame:getMethodName()
            if method == '<init>' or method == 'createFromParcel' or method == 'writeToParcel' or
                    method == 'equals' or string.find(method, '^isIdentical') or string.find(method, '^compare') then
                return true
            end
        elseif inside then
            return false
        end
    end
    return false
end

-- The commands of ioctl which give the address, the destination address, the broadcast address and the netmask of
-- an interface (SIOCGIFADDR and the others, see devinet_ioctl of Linux), the error of an interface without an IPv4
-- address, EADDRNOTAVAIL, and the size of an interface name with its final zero, IFNAMSIZ, the same on every Linux
local SIOCGIFADDR = 0x8915
local INTERFACE = { [0x8915] = true, [0x8917] = true, [0x8919] = true, [0x891b] = true }
local EADDRNOTAVAIL = 99
local IFNAMSIZ = 16

-- The hidden ioctlInetAddress of libcore gives one of these values of the first IPv4 address of an interface. The
-- interfaces keep only their loopback addresses, like in NetworkInterface and getifaddrs (see interface_entries), so
-- for the others it fails like for an interface without an IPv4 address, with EADDRNOTAVAIL, also for the netmask and
-- the broadcast address, which are no addresses of the device but would tell it has one. Linux finds the interface by
-- the name before a colon and the address by the whole name, which is the label of an alias like wlan0:1, so the name
-- with a colon added finds the interface without an address: the real call fails then, with the exception, message
-- and stack trace of Android, after the checks of the descriptor, the command and the interface it makes before.
function before(hook, param)
    if hook:getMethodName() ~= 'ioctlInetAddress' or not INTERFACE[param:getArgument(1)] then
        return false
    end
    local libcore, fd, name = param:getThis(), param:getArgument(0), param:getArgument(2)
    -- Libcore throws NullPointerException for no name, like without the hook
    if name == nil then
        return false
    end
    local ok, address = pcall(function()
        return param:unhooked(function()
            return libcore:ioctlInetAddress(fd, SIOCGIFADDR, name)
        end)
    end)
    if not ok then
        -- The kernel refuses the call (no such interface, no IPv4 address, a socket of another family), and so the
        -- call of the app; other errors are of the script and go to the log of the hook
        if string.find(tostring(address), 'android.system.ErrnoException', 1, true) then
            return false
        end
        error(address, 0)
    end
    if address == nil or address:isLoopbackAddress() then
        return false
    end
    if #name + 1 < IFNAMSIZ then
        param:setArgument(2, name .. ':')
    else
        -- shortcut: a name of 15 bytes or more has no room for the colon, so its error is made here, with the frames of the
        -- script in its stack trace; upgrade if apps compare the stack traces of such names
        param:setResult(luajava.newInstance('android.system.ErrnoException', 'ioctl', EADDRNOTAVAIL))
    end
    return true, address:getHostAddress(), 'EADDRNOTAVAIL'
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

    elseif method == 'getLinkAddresses' or method == 'getDnsServers' or method == 'getRoutes' or
            method == 'getAllAddresses' or method == 'getAddresses' or method == 'getAllLinkAddresses' or
            method == 'getAllRoutes' or method == 'getAllInterfaceNames' then
        if result:isEmpty() or linkproperties() then
            return false
        end
        param:setResult(emptylike(param, result, luajava.newInstance('java.util.ArrayList')))
        return true

    elseif method == 'getLocalHost' then
        -- The address of the host name of the device, which is normally localhost
        if result:isLoopbackAddress() then
            return false
        end
        local fake = luajava.bindClass('java.net.InetAddress'):getLoopbackAddress()
        param:setResult(fake)
        return true, result:getHostAddress(), fake:getHostAddress()

    elseif method == 'getLocalAddress' or method == 'getLocalSocketAddress' or method == 'getsockname' then
        local fake, address, any = unbound(param, result)
        if fake == nil then
            return false
        end
        param:setResult(fake)
        return true, address:getHostAddress(), any

    elseif method == 'toString' then
        -- The text of a socket channel, like java.nio.channels.SocketChannel[connected local=/192.168.1.5:41234
        -- remote=...], has the real local address, which libcore reads from its field, not through getLocalAddress;
        -- it gets the same fake, written by the same toString, so the two agree. A closed channel has no address,
        -- nor has one which is not bound.
        if not string.find(result, ' local=', 1, true) then
            return false
        end
        local channel = param:getThis()
        local ok, real = pcall(function()
            return param:unhooked(function()
                return channel:getLocalAddress()
            end)
        end)
        if not ok then
            -- Closed since its text was made; other errors are of the script and go to the log of the hook
            if string.find(tostring(real), 'ClosedChannelException', 1, true) then
                return false
            end
            error(real, 0)
        end
        if real == nil then
            return false
        end
        local fake, address, any = unbound(param, real)
        if fake == nil then
            return false
        end
        local text = ' local=' .. real:toString()
        local at = string.find(result, text, 1, true)
        if at == nil then
            return false
        end
        param:setResult(string.sub(result, 1, at - 1) .. ' local=' .. fake:toString() .. string.sub(result, at + #text))
        return true, address:getHostAddress(), any

    elseif method == 'getDomains' or method == 'getDhcpServerAddress' or method == 'getPrivateDnsServerName' then
        -- Like without domains, DHCP and private DNS with a server name (strict mode)
        if linkproperties() then
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

-- The address a socket is bound to, which the system chose for its connection, like of a DatagramSocket connected to
-- a server without sending anything: loopback, wildcard, link-local and multicast addresses stay, the others become
-- the wildcard address of their family, like of a socket which is not bound. Socket and DatagramSocket give an
-- InetAddress, channels and getsockname a SocketAddress, of which only those of IP change. The socket itself stays as
-- it is. Returns the fake, the real address and the fake one as text, or nil when the address stays.
function unbound(param, result)
    local socket = param:isInstance(result, 'java.net.InetSocketAddress')
    local address = (socket and result:getAddress() or result)
    if not param:isInstance(address, 'java.net.InetAddress') or address:isLoopbackAddress() or
            address:isAnyLocalAddress() or address:isLinkLocalAddress() or address:isMulticastAddress() then
        return nil
    end
    local any = (address:getAddress()['length'] == 16 and '::' or '0.0.0.0')
    local fake = luajava.bindClass('java.net.InetAddress'):getByName(any)
    return (socket and luajava.newInstance('java.net.InetSocketAddress', fake, result:getPort()) or fake), address, any
end

-- An empty collection like the one of Android: unmodifiable when Android's is, of the same kind; by the class all
-- unmodifiable ones of Collections extend, since luajava binds a class to its static members, not to its name
function emptylike(param, result, fake)
    local known, unmodifiable = pcall(function()
        return param:isInstance(result, 'java.util.Collections$UnmodifiableCollection')
    end)
    if not known or not unmodifiable then
        return fake
    end
    local collections = luajava.bindClass('java.util.Collections')
    if param:isInstance(result, 'java.util.Set') then
        return collections:unmodifiableSet(fake)
    elseif param:isInstance(result, 'java.util.List') then
        return collections:unmodifiableList(fake)
    end
    return collections:unmodifiableCollection(fake)
end
