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
-- interfaces, see before. LinkProperties parcels, compares and hashes its fields, not its getters, see shown.

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
-- The redaction of the fields of a network which tell the location (NetworkCapabilities.REDACT_FOR_ACCESS_FINE_LOCATION,
-- hidden), which Android makes for apps without the location permission
local REDACT_FOR_ACCESS_FINE_LOCATION = 1

-- A copy of a link with only what its getters show: its interface, as the getter gives it with the fakes of the other
-- groups, like the one of a VPN, its MTU and its proxy. It is written to a parcel in place of the link, and compared
-- and hashed for it, so these agree with the getters, and a link with other addresses, servers or routes is the same.
-- It has no private DNS and no Wake-on-LAN, like isPrivateDnsActive and isWakeOnLanSupported answer.
-- shortcut: the copy has no TCP buffer sizes, stacked links, P-CSCF and validated private DNS servers, which only hidden
-- methods set, while the text of the link and its hidden getters still show them; upgrade by hiding them there too
-- should apps read them.
function shown(param, link)
    if not copyable() then
        return nil
    end
    local name, proxy = link:getInterfaceName(), link:getHttpProxy()
    if luajava.bindClass('android.os.Build$VERSION').SDK_INT == 28 then
        return pieshown(param, link, name, proxy)
    end
    local copy = luajava.newInstance('android.net.LinkProperties')
    copy:setInterfaceName(name)
    copy:setMtu(link:getMtu())
    copy:setHttpProxy(proxy)
    return copy
end

-- Android 9 has the constructor, getMtu and the setters of the copy only hidden, and calls of them show in the log of
-- the app and in its StrictMode reports, so there the copy is read by the creator of Android from a parcel written the
-- way Android 9 writes a link (see its LinkProperties.writeToParcel), with the MTU of the parcel of the link.
-- nil, so the link stays as it is, when the parcel of the link or of the copy does not read whole, as on a device
-- which writes other fields.
function pieshown(param, link, name, proxy)
    local parcel = luajava.bindClass('android.os.Parcel')
    local real, fake = parcel:obtain(), parcel:obtain()
    local ok, copy = pcall(function()
        param:unhooked(function()
            link:writeToParcel(real, 0)
        end)
        real:setDataPosition(0)
        real:readString() -- the interface
        for _ = 1, real:readInt() do
            real:readParcelable(nil) -- the addresses
        end
        for _ = 1, 2 do
            for _ = 1, real:readInt() do
                real:createByteArray() -- the DNS servers, then the validated private DNS servers
            end
        end
        real:readInt() -- private DNS
        real:readString() -- the private DNS server name
        real:readString() -- the domains
        local mtu = real:readInt()
        real:readString() -- the TCP buffer sizes
        for _ = 1, real:readInt() do
            real:readParcelable(nil) -- the routes
        end
        if real:readByte() == 1 then
            real:readParcelable(nil) -- the proxy
        end
        real:readList(luajava.newInstance('java.util.ArrayList'), nil) -- the stacked links
        if real:dataPosition() ~= real:dataSize() then
            return nil
        end

        fake:writeString(name)
        fake:writeInt(0) -- no addresses
        fake:writeInt(0) -- no DNS servers
        fake:writeInt(0) -- no validated private DNS servers
        fake:writeInt(0) -- no private DNS, as writeBoolean, hidden there too, writes it
        fake:writeString(nil)
        fake:writeString(nil)
        fake:writeInt(mtu)
        fake:writeString(nil)
        fake:writeInt(0) -- no routes
        if proxy == nil then
            fake:writeByte(0)
        else
            fake:writeByte(1)
            fake:writeParcelable(proxy, 0)
        end
        fake:writeList(luajava.newInstance('java.util.ArrayList'))
        fake:setDataPosition(0)
        local copy = luajava.bindClass('android.net.LinkProperties').CREATOR:createFromParcel(fake)
        if fake:dataPosition() ~= fake:dataSize() then
            return nil
        end
        return copy
    end)
    real:recycle()
    fake:recycle()
    -- The app gets the link as it is, the error goes to the log of the hook
    if not ok then
        error(copy, 0)
    end
    return copy
end

-- Processes of Android itself, of uids below the first one of apps (10000), like phone, Bluetooth, the network stack
-- and settings, keep their links as they are: they send them to system server (NetworkAgent, the callbacks of
-- IpClient), which needs the real ones, else mobile data fails for the whole device, and they compare them to see
-- whether a link changed. An app cannot change its uid, while it could name a class of its own like one of those.
-- Android 8.1 checks no hidden APIs, Android 9 reads the copy from a parcel, see pieshown, and Android 10 makes the
-- methods of the copy public.
-- shortcut: a network agent of an app uid, like a carrier app with the permission, sends the copy; upgrade with a
-- check of the frames of NetworkAgent should such an app be restricted.
function copyable()
    return luajava.bindClass('android.os.Process'):myUid() % 100000 >= 10000
end

-- The parcel of a link is the one of its copy, written without the hooks, which would else hook its write too
function parcelled(param)
    local parcel = param:getArgument(0)
    local copy = (parcel ~= nil and shown(param, param:getThis()) or nil)
    if copy == nil then
        return false
    end
    param:unhooked(function()
        copy:writeToParcel(parcel, param:getArgument(1))
    end)
    param:setResult(nil)
    return true
end

-- The hidden ioctlInetAddress of libcore gives one of these values of the first IPv4 address of an interface. The
-- interfaces keep only their loopback addresses, like in NetworkInterface and getifaddrs (see interface_entries), so
-- for the others it fails like for an interface without an IPv4 address, with EADDRNOTAVAIL, also for the netmask and
-- the broadcast address, which are no addresses of the device but would tell it has one. Linux finds the interface by
-- the name before a colon and the address by the whole name, which is the label of an alias like wlan0:1, so the name
-- with a colon added finds the interface without an address: the real call fails then, with the exception, message
-- and stack trace of Android, after the checks of the descriptor, the command and the interface it makes before.
function before(hook, param)
    if hook:getMethodName() == 'writeToParcel' then
        return parcelled(param)
    end
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
        -- The info from WifiManager.getConnectionInfo and the network capabilities is made without the address, see
        -- wifishown, so its parcel, hashCode and equals have none either; other infos, like the one Read.VPN gives for
        -- a VPN over Wi-Fi, keep it in their parcel
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
            method == 'getAllRoutes' then
        if result:isEmpty() or linkproperties() then
            return false
        end
        param:setResult(emptylike(param, result, luajava.newInstance('java.util.ArrayList')))
        return true

    elseif method == 'getAllInterfaceNames' then
        -- The names of the interface and of its stacked links, like the one of the IPv4 of an IPv6 network (clat),
        -- as the copy has them: the one getInterfaceName gives
        if linkproperties() then
            return false
        end
        local list = luajava.newInstance('java.util.ArrayList')
        local name = param:getThis():getInterfaceName()
        if name ~= nil then
            list:add(name)
        end
        if list:equals(result) then
            return false
        end
        param:setResult(list)
        return true

    elseif method == 'isPrivateDnsActive' or method == 'isWakeOnLanSupported' then
        -- Like a link without private DNS and Wake-on-LAN, as its copy is; whether the network has a DNS server
        -- which speaks TLS tells about the network
        if not result or linkproperties() then
            return false
        end
        param:setResult(false)
        return true

    elseif method == 'getConnectionInfo' then
        local made = wifishown(param, result)
        if made == result then
            return false
        end
        param:setResult(made)
        return true

    elseif method == 'getTransportInfo' then
        -- The capabilities give the same info on each call, and so the same one made without the address
        local capabilities = param:getThis()
        local made = param:getValue('unaddressed', capabilities)
        if made == nil then
            if not param:isInstance(result, 'android.net.wifi.WifiInfo') then
                return false
            end
            made = wifishown(param, result)
            if made == result then
                return false
            end
            param:putValue('unaddressed', made, capabilities)
        end
        param:setResult(made)
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
        local fake, address, any = unbound(hook, param, result)
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
        local fake, address, any = unbound(hook, param, real)
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

    elseif method == 'hashCode' then
        local copy = shown(param, param:getThis())
        local fake = (copy ~= nil and param:unhooked(function()
            return copy:hashCode()
        end) or result)
        if fake == result then
            return false
        end
        param:setResult(fake)
        return true

    elseif method == 'equals' then
        -- Links which are the same are also the same as their getters show them
        local other = param:getArgument(0)
        if result or not param:isInstance(other, 'android.net.LinkProperties') then
            return false
        end
        local copy, copied = shown(param, param:getThis()), shown(param, other)
        if copy == nil or not param:unhooked(function()
            return copy:equals(copied)
        end) then
            return false
        end
        param:setResult(true)
        return true

    elseif string.find(method, '^compare') then
        -- The differences of the addresses, DNS servers, routes and interface names of two links, the hidden ones of
        -- Android 8.1, which apps reach by reflection there, as their getters show them; a missing link has none.
        -- shortcut: isProvisioned, hasIPv4Address, compareProvisioning and the other hidden checks of the provisioning
        -- of a link still read its addresses and routes; upgrade with them should apps check them.
        local copy, other = shown(param, param:getThis()), param:getArgument(0)
        if copy == nil then
            return false
        end
        local copied = (other ~= nil and shown(param, other) or nil)
        param:setResult(param:unhooked(function()
            return copy[method](copy, copied)
        end))
        return true

    elseif string.find(method, '^isIdentical') then
        -- The addresses, DNS servers and routes of two links, which their getters show none of
        if result or not copyable() then
            return false
        end
        param:setResult(true)
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
-- a server without sending anything: loopback and wildcard addresses stay, and multicast ones, which are the group the
-- app bound the socket to and no interface has; the others become the wildcard address the way Android gives it for a
-- socket bound to it. Link-local ones too, like fe80:: of a socket connected to ff02::1%wlan0 or to a peer on the link,
-- which the interfaces do not list either (getInetAddresses, ioctlInetAddress and interface_entries keep only loopback),
-- so a socket shows no address of the device the interfaces hide. Android makes the sockets of Java of IPv6,
-- also for IPv4 (see PlainSocketImpl, PlainDatagramSocketImpl and Net.socket), so their wildcard is ::, which Socket
-- and DatagramSocket give as InetAddress.anyLocalAddress, named ::, which InetSocketAddress also takes for no address;
-- the channels, their adaptors and getsockname give it as the kernel has it, without a name, for getsockname of the
-- family of its address, as the app can make a socket of IPv4 (Os.socket). Socket and DatagramSocket give an InetAddress,
-- channels and getsockname a SocketAddress, of which only those of IP change. The socket itself stays as it is.
-- Returns the fake, the real address and the fake one as text, or nil when the address stays.
-- shortcut: a connected socket gets the wildcard too, which Android gives only to a socket bound to it, as an answer
-- without an address of the device, like the other getters of Read.Network; upgrade with them should apps check it.
-- shortcut: a channel opened for IPv4 (StandardProtocolFamily.INET) gets :: where Android has 0.0.0.0, as nothing the
-- hook gets tells its family; upgrade should apps open such channels and check their address.
-- shortcut: getsockname takes the family from the address, so a socket of IPv6 with an address of IPv4, which libcore
-- gives as one of IPv4, gets 0.0.0.0 where Android has ::, as only a hidden call (SO_DOMAIN) tells the family of the
-- descriptor; upgrade should apps check getsockname of the sockets of Java.
function unbound(hook, param, result)
    local socket = param:isInstance(result, 'java.net.InetSocketAddress')
    local address = (socket and result:getAddress() or result)
    if not param:isInstance(address, 'java.net.InetAddress') or address:isLoopbackAddress() or
            address:isAnyLocalAddress() or address:isMulticastAddress() then
        return nil
    end
    local fake
    local class = hook:getClassName()
    if class == 'java.net.Socket' or class == 'java.net.DatagramSocket' then
        fake = luajava.newInstance('java.net.InetSocketAddress', 0):getAddress()
    else
        local ipv4 = (hook:getMethodName() == 'getsockname' and address:getAddress()['length'] == 4)
        fake = luajava.bindClass('java.net.InetAddress'):getByName(ipv4 and '0.0.0.0' or '::')
    end
    return (socket and luajava.newInstance('java.net.InetSocketAddress', fake, result:getPort()) or fake), address,
        fake:getHostAddress()
end

-- The Wi-Fi info made again by its creator from its parcel without its IPv4 address, nil when it stays; by the public
-- writeParcelable and readParcelable of Parcel, which name the class, as the creator of WifiInfo is hidden. WifiInfo writes
-- the address as a byte 1 and the byte array of the address (the ints 1, 4 and the bytes, which read as the int of
-- getIpAddress), after fields which differ between versions; the parcel without them has the byte 0 of no address
-- there instead. The info stays as it is when the address is not in its parcel exactly once, or the new one has an
-- address or does not write the same parcel back, as on a device with other fields.
function unaddressed(param, info)
    local address = param:unhooked(function()
        return info:getIpAddress()
    end)
    if address == 0 then
        return nil
    end
    local parcel = luajava.bindClass('android.os.Parcel')
    local real, fake, again = parcel:obtain(), parcel:obtain(), parcel:obtain()
    local ok, made = pcall(function()
        param:unhooked(function()
            real:writeParcelable(info, 0)
        end)
        local ints, at = parcelints(real), nil
        for index = 1, #ints - 2 do
            if ints[index] == 1 and ints[index + 1] == 4 and ints[index + 2] == address then
                if at ~= nil then
                    return nil
                end
                at = (index - 1) * 4
            end
        end
        if at == nil then
            return nil
        end
        fake:appendFrom(real, 0, at)
        fake:writeInt(0)
        fake:appendFrom(real, at + 12, real:dataSize() - at - 12)
        fake:setDataPosition(0)
        local made = param:unhooked(function()
            return fake:readParcelable(nil)
        end)
        if fake:dataPosition() ~= fake:dataSize() then
            return nil
        end
        local left = param:unhooked(function()
            again:writeParcelable(made, 0)
            return made:getIpAddress()
        end)
        local written, back = parcelints(fake), parcelints(again)
        if left ~= 0 or #back ~= #written then
            return nil
        end
        for index = 1, #written do
            if back[index] ~= written[index] then
                return nil
            end
        end
        return made
    end)
    real:recycle()
    fake:recycle()
    again:recycle()
    -- The app gets the info as it is, the error goes to the log of the hook
    if not ok then
        error(made, 0)
    end
    return made
end

-- The Wi-Fi info as the app gets it: without its IPv4 address (see unaddressed) and, from Android 12, also without the
-- network name, access point, network id and the other fields of the location, like Android gives it to apps without
-- the location permission (by the public makeCopy, which copies the address too, so it is made after unaddressed);
-- the getters give these unavailable values for every Wi-Fi info there (see wifiinfo_ssid_bssid), and so does its text
-- (network_tostring), so they agree with the parcel, hashCode, equals and text. Returns the info itself when it stays.
-- shortcut: on Android 8.1 to 11 the parcel of the info of apps with the location permission still has the real
-- network name, access point and id, which the getters hide, as there is no public copy which leaves them out (equals
-- and hashCode are by identity there); upgrade should apps with the permission read them there.
-- shortcut: the capabilities themselves, their parcel and equals and those of a VPN over Wi-Fi, which Read.VPN gives,
-- still have the info with the address and the network; upgrade should apps read them there.
function wifishown(param, info)
    local made = unaddressed(param, info) or info
    if luajava.bindClass('android.os.Build$VERSION').SDK_INT < 31 then
        return made
    end
    return param:unhooked(function()
        return made:makeCopy(REDACT_FOR_ACCESS_FINE_LOCATION)
    end)
end

-- The ints of a parcel, which Android aligns to 4 bytes
function parcelints(parcel)
    local ints = {}
    parcel:setDataPosition(0)
    for index = 1, math.floor(parcel:dataSize() / 4) do
        ints[index] = parcel:readInt()
    end
    return ints
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
