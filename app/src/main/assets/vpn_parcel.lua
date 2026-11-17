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

-- The capabilities, network info and link properties of a VPN write their fields to a parcel, not the getters which
-- the hooks of vpn and vpn_addresses fake, so a parcel of them, like for an intent or a binder, or its bytes (marshall),
-- showed the VPN. Their parcel has the values of those getters instead, the other fields as Android wrote them. It is
-- read back with the CREATOR of the class first: a layout this script does not know, which does not read back without
-- the VPN, fails the hook, which then writes the real parcel. The capabilities and link properties of a VPN compare and
-- hash like that copy, so a copy read back from their parcel equals them, like without Doppel.
-- Not in system processes, like telephony, which compare their networks and give them to ConnectivityService, like IWLAN
-- on ipsec1, nor for a NetworkAgent of another process: a fake there would lose that network for the whole device.
-- shortcut: a request of the app for VPN networks, which vpn_request made one for LoWPAN, keeps LoWPAN in its parcel,
-- since that parcel is how ConnectivityService gets the request; upgrade should apps parcel their requests.

local TRANSPORT_VPN = 4
local NET_CAPABILITY_NOT_VPN = 15
local TYPE_VPN = 17

-- Whether a network interface is of a VPN, like vpn, which a test compares
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

-- Whether link properties or a route with the interface of a VPN, like tun0, are of a network of the device: those the
-- app makes itself and names like that, without such a network, keep what it set, like on Android. Kept for the object,
-- which keeps its interface; found without the hooks, which hide the VPN, and taken as one when that fails. The same
-- function as in vpn, vpn_addresses and vpn_parcel; a test compares them.
-- shortcut: an object the app makes with the name of a VPN which is up gets the fakes; upgrade by marking the objects
-- Android reads from its parcels if apps are seen to check that
function devicevpn(param, object, name)
    local known
    if object ~= nil then
        known = param:getValue('vpn.device', object)
    end
    if known == nil then
        local ok, found = pcall(param.unhooked, param, function()
            local cm = param:getApplicationContext():getSystemService('connectivity')
            local networks = cm:getAllNetworks()
            for index = 1, networks['length'] do
                local lp = cm:getLinkProperties(networks[index])
                if lp ~= nil and lp:getInterfaceName() == name then
                    return true
                end
            end
            return false
        end)
        known = (not ok or found)
        if object ~= nil then
            param:putValue('vpn.device', known, object)
        end
    end
    return known
end

-- Whether the object really is of a VPN, which the hooks show without it: capabilities with VPN, the network info of a
-- connected VPN and link properties of a VPN interface of the device, like tun0 (see devicevpn)
function isvpn(param, class, object)
    local name = param:unhooked(function()
        if class == 'android.net.NetworkCapabilities' then
            return object:hasTransport(TRANSPORT_VPN)
        elseif class == 'android.net.NetworkInfo' then
            return object:getType() == TYPE_VPN and object:isConnectedOrConnecting()
        end
        return object:getInterfaceName()
    end)
    if type(name) == 'boolean' then
        return name
    end
    return isvpninterface(name) and devicevpn(param, object, name)
end

-- Copies what was read of the real parcel since a position to the fake one, as Android wrote it
function copy(real, fake, from)
    fake:appendFrom(real, from, real:dataPosition() - from)
end

-- Capabilities: with NOT_VPN, the transports without VPN, or for a VPN without another one with the one of the network
-- under it, the transport info from Android 10 and the signal strength, like their hooked getters; Android 8.1 has no
-- forbidden capabilities. Through Lua a long is a double, exact for these bits.
function capabilities(param, nc, real, fake, flags, sdk)
    local values = real:readLong()
    if not param:unhooked(function()
        return nc:hasCapability(NET_CAPABILITY_NOT_VPN)
    end) then
        values = values + 2 ^ NET_CAPABILITY_NOT_VPN
    end
    fake:writeLong(values)
    if sdk >= 28 then
        fake:writeLong(real:readLong())
    end
    local transports = real:readLong() - 2 ^ TRANSPORT_VPN
    local transport = 0
    while transports == 0 and transport < 32 do
        if nc:hasTransport(transport) then
            transports = 2 ^ transport
        end
        transport = transport + 1
    end
    fake:writeLong(transports)
    -- The bandwidths and the network specifier
    local from = real:dataPosition()
    real:readInt()
    real:readInt()
    real:readParcelable(nil)
    copy(real, fake, from)
    if sdk >= 29 then
        real:readParcelable(nil)
        fake:writeParcelable(nc:getTransportInfo(), flags)
    end
    local signal = real:readInt()
    if sdk >= 29 then
        signal = nc:getSignalStrength()
    else
        -- The getter is hidden before Android 10, the hooked text has the signal strength the hooks show
        signal = tonumber(string.match(nc:toString(), ' SignalStrength: (%-?%d+)')) or signal
    end
    fake:writeInt(signal)
end

-- Network info: the type, subtype, their names, roaming, reason and extra info of the network under the VPN, like their
-- hooked getters; the states, failover and availability as Android wrote them
function networkinfo(info, real, fake)
    real:readInt()
    real:readInt()
    real:readString()
    real:readString()
    fake:writeInt(info:getType())
    fake:writeInt(info:getSubtype())
    fake:writeString(info:getTypeName())
    fake:writeString(info:getSubtypeName())
    local from = real:dataPosition()
    real:readString()
    real:readString()
    real:readInt()
    real:readInt()
    copy(real, fake, from)
    real:readInt()
    real:readString()
    real:readString()
    fake:writeInt(info:isRoaming() and 1 or 0)
    fake:writeString(info:getReason())
    fake:writeString(info:getExtraInfo())
end

-- Reads past a list of addresses of LinkProperties, or count addresses: their bytes, and from Android 11 whether an IPv6
-- address has a scope and the scope
function skipaddresses(parcel, sdk, count)
    for _ = 1, count or parcel:readInt() do
        local bytes = parcel:createByteArray()
        if sdk >= 30 and bytes ~= nil and bytes['length'] == 16 and parcel:readInt() ~= 0 then
            parcel:readInt()
        end
    end
end

function writeaddresses(parcel, sdk, addresses)
    parcel:writeInt(addresses:size())
    for index = 0, addresses:size() - 1 do
        local address = addresses:get(index)
        local bytes = address:getAddress()
        parcel:writeByteArray(bytes)
        if sdk >= 30 and bytes['length'] == 16 then
            local scope = address:getScopeId()
            parcel:writeInt(scope ~= 0 and 1 or 0)
            if scope ~= 0 then
                parcel:writeInt(scope)
            end
        end
    end
end

-- Reads past a list of parcelables, like the addresses and routes of LinkProperties: their count, then each
function skipparcelables(parcel)
    for _ = 1, parcel:readInt() do
        parcel:readParcelable(nil)
    end
end

function writeparcelables(parcel, list, flags)
    parcel:writeInt(list:size())
    for index = 0, list:size() - 1 do
        parcel:writeParcelable(list:get(index), flags)
    end
end

-- Link properties: the interface, addresses, DNS servers, search domains, MTU, routes and HTTP proxy of the network under
-- the VPN, like their hooked getters (see vpn_addresses); the private DNS from Android 9, the P-CSCF servers from 10, the
-- DHCP server from 11, the TCP buffer sizes and the fields after the proxy, like the NAT64 prefix and the stacked links,
-- as Android wrote them, which stay the VPN's in their getters too.
function properties(lp, real, fake, flags, sdk)
    real:readString()
    skipparcelables(real)
    skipaddresses(real, sdk)
    fake:writeString(lp:getInterfaceName())
    writeparcelables(fake, lp:getLinkAddresses(), flags)
    writeaddresses(fake, sdk, lp:getDnsServers())
    local from = real:dataPosition()
    if sdk >= 28 then
        skipaddresses(real, sdk)
        real:readInt()
        real:readString()
    end
    if sdk >= 29 then
        skipaddresses(real, sdk)
    end
    copy(real, fake, from)
    real:readString()
    fake:writeString(lp:getDomains())
    from = real:dataPosition()
    if sdk >= 30 then
        skipaddresses(real, sdk, 1)
    end
    copy(real, fake, from)
    local mtu = real:readInt()
    if sdk >= 29 then
        mtu = lp:getMtu()
    else
        -- The getter is hidden before Android 10, the hooked text has the MTU the hooks show, the first one before
        -- those of the stacked links
        mtu = tonumber(string.match(lp:toString(), '[^%w]MTU: (%-?%d+)')) or mtu
    end
    fake:writeInt(mtu)
    from = real:dataPosition()
    real:readString()
    copy(real, fake, from)
    skipparcelables(real)
    writeparcelables(fake, lp:getRoutes(), flags)
    if real:readInt() ~= 0 then
        real:readParcelable(nil)
    end
    local proxy = lp:getHttpProxy()
    fake:writeInt(proxy and 1 or 0)
    if proxy then
        fake:writeParcelable(proxy, flags)
    end
end

-- Whether a NetworkAgent gives its network to ConnectivityService, also from its inner classes and lambdas
function agent()
    local frames = luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
    for index = 1, frames['length'] do
        local class = frames[index]:getClassName()
        if class == 'android.net.NetworkAgent' or string.sub(class, 1, 25) == 'android.net.NetworkAgent$' then
            return true
        end
    end
    return false
end

-- The object read back from its fake parcel, which goes to dest when there is one. The object is marked while the
-- getters give the fields, so vpn and vpn_addresses fake them whatever frames the hook framework shows here, and its
-- own hooks here run as without them.
-- shortcut: the hash and comparison follow the network under the VPN, so the hash of an unchanged object changes when
-- that network does and each call asks ConnectivityManager; upgrade should apps keep VPN networks in hashed sets.
-- shortcut: the mark is for all threads, so another thread copying the same object meanwhile gets the fakes and its
-- hashCode the real one; upgrade to a mark per thread should apps share them between threads like that.
function fakeof(param, class, object, flags, dest)
    local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
    local parcels = luajava.bindClass('android.os.Parcel')
    local real = parcels:obtain()
    local fake = parcels:obtain()
    param:putValue('vpn.parcel', true, object)
    -- Only the last call writes to the parcel of the app, so a failure leaves it to the real write
    local ok, again = pcall(function()
        param:unhooked(function()
            object:writeToParcel(real, flags)
        end)
        real:setDataPosition(0)
        if class == 'android.net.NetworkCapabilities' then
            capabilities(param, object, real, fake, flags, sdk)
        elseif class == 'android.net.NetworkInfo' then
            networkinfo(object, real, fake)
        else
            properties(object, real, fake, flags, sdk)
        end
        local from = real:dataPosition()
        real:setDataPosition(real:dataSize())
        copy(real, fake, from)
        fake:setDataPosition(0)
        local again = param:unhooked(function()
            return luajava.bindClass(class).CREATOR:createFromParcel(fake)
        end)
        if fake:dataPosition() ~= fake:dataSize() or isvpn(param, class, again) then
            error(class .. ' parcel unknown')
        end
        if dest ~= nil then
            dest:appendFrom(fake, 0, fake:dataSize())
        end
        return again
    end)
    param:putValue('vpn.parcel', nil, object)
    real:recycle()
    fake:recycle()
    if not ok then
        error(again, 0)
    end
    return again
end

function before(hook, param)
    local method = hook:getMethodName()
    local class = hook:getClassName()
    local object = param:getThis()
    if param:isSystem() or param:getValue('vpn.parcel', object) then
        return false
    end

    if method == 'writeToParcel' then
        local dest = param:getArgument(0)
        if dest == nil or not isvpn(param, class, object) or agent() then
            return false
        end
        fakeof(param, class, object, param:getArgument(1), dest)
        param:setResult(nil)
        return true

    elseif method == 'hashCode' then
        if not isvpn(param, class, object) then
            return false
        end
        local again = fakeof(param, class, object, 0)
        param:setResult(param:unhooked(function()
            return again:hashCode()
        end))
        return true
    end

    -- equals: each side which is of a VPN like the copy of its parcel
    local other = param:getArgument(0)
    local vpn = isvpn(param, class, object)
    local othervpn = param:isInstance(other, class) and not param:getValue('vpn.parcel', other) and
            isvpn(param, class, other)
    if not vpn and not othervpn then
        return false
    end
    local left = (vpn and fakeof(param, class, object, 0) or object)
    local right = (othervpn and fakeof(param, class, other, 0) or other)
    param:setResult(param:unhooked(function()
        return left:equals(right)
    end))
    return true
end
