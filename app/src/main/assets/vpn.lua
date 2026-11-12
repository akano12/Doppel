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

-- Hide that a VPN is in use

local TRANSPORT_VPN = 4
local NET_CAPABILITY_NOT_VPN = 15
local TYPE_VPN = 17
local TYPE_WIFI = 1
local TRANSPORT_WIFI = 1
local NET_CAPABILITY_INTERNET = 12
-- Integer.MIN_VALUE, the signal strength of capabilities without one
local SIGNAL_STRENGTH_UNSPECIFIED = -2147483648

-- The transports of NetworkCapabilities, their number and their name in its text, by the type of NetworkInfo
local TRANSPORTS = { [0] = { 0, 'CELLULAR' }, [1] = { 1, 'WIFI' }, [7] = { 2, 'BLUETOOTH' }, [9] = { 3, 'ETHERNET' } }

-- The fake values of a hook can call the same hook, see underlying; the Lua runtime of a hook is per thread
-- and stays after an error (see XCore), so this tells that the call is nested
local nested = {}

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

-- The type of a network info, without the fake type of the getType hook
function realtype(param, info)
    param:putValue('vpn.real', true, info)
    local ok, type = pcall(info.getType, info)
    param:putValue('vpn.real', nil, info)
    if not ok then
        error(type, 0)
    end
    return type
end

-- The network a VPN runs over, like mobile data: Android reports it as the active network info for compatibility,
-- and its transport is in the capabilities of the VPN, so the fake values are of this network
function underlying(param)
    local cm = param:getApplicationContext():getSystemService('connectivity')
    local info = cm:getActiveNetworkInfo()
    if info == nil then
        return nil
    end
    local type = realtype(param, info)
    if type == TYPE_VPN then
        return nil
    end
    return info, type, cm
end

function underlyingtype(param)
    local ok, info, type = pcall(underlying, param)
    if ok and info ~= nil then
        return type, info:getTypeName()
    end
    return TYPE_WIFI, 'WIFI'
end

-- The transport of the network the VPN runs over, its number and its name
function underlyingtransport(param)
    local transport = TRANSPORTS[underlyingtype(param)] or TRANSPORTS[TYPE_WIFI]
    return transport[1], transport[2]
end

-- The interface of the network the VPN runs over, like wlan0 or rmnet_data0
function underlyinginterface(param)
    local ok, name = pcall(function()
        local info, type, cm = underlying(param)
        if info == nil then
            return nil
        end
        local networks = cm:getAllNetworks()
        for index = 1, networks['length'] do
            local other = cm:getNetworkInfo(networks[index])
            local lp = cm:getLinkProperties(networks[index])
            if other ~= nil and lp ~= nil and realtype(param, other) == type then
                local name = lp:getInterfaceName()
                if name ~= nil and not isvpninterface(name) then
                    return name
                end
            end
        end
        return nil
    end)
    if ok and name ~= nil then
        return name
    end
    return 'wlan0'
end

-- The LinkProperties of the network with that real interface, also for the default wlan0, found without the hooks
-- like the getters of vpn_addresses find the network under the VPN, so its text has what they give. The hooks would
-- list the VPN itself when it is the active network, and give it and other tunnels, like IWLAN on ipsec1, that name.
function propertiesof(param, name)
    return param:unhooked(function()
        local cm = param:getApplicationContext():getSystemService('connectivity')
        local networks = cm:getAllNetworks()
        for index = 1, networks['length'] do
            local lp = cm:getLinkProperties(networks[index])
            if lp ~= nil and lp:getInterfaceName() == name then
                return lp
            end
        end
        return nil
    end)
end

-- The names of the capabilities below NOT_VPN (15): the platform lists the names in bit order
local BELOW_NOT_VPN = {
    MMS = true, SUPL = true, DUN = true, FOTA = true, IMS = true, CBS = true, WIFI_P2P = true, IA = true,
    RCS = true, XCAP = true, EIMS = true, NOT_METERED = true, INTERNET = true, NOT_RESTRICTED = true, TRUSTED = true
}

-- Names like INTERNET&VALIDATED with NOT_VPN in its place
function withnotvpn(names)
    local list = {}
    local added = false
    for name in string.gmatch(names, '[^&]+') do
        if not added and not BELOW_NOT_VPN[name] then
            list[#list + 1] = 'NOT_VPN'
            added = true
        end
        list[#list + 1] = name
    end
    if not added then
        list[#list + 1] = 'NOT_VPN'
    end
    return table.concat(list, '&')
end

-- The entry of the type VPN of a device without a VPN, which Android makes like for every type without a network
-- (makeFakeNetworkInfo of ConnectivityService), not a copy of the VPN's, which has the roaming of the network under it
-- from Android 11. Such an entry is BLOCKED where Android blocks the app there, from Android 12 for an app which may
-- not use metered networks, like with Data Saver in the background, also while a VPN which is not metered is connected
-- for it. The other entries of the list of all network infos (infos, else read here) tell which: those of the other
-- types without a network have its state, and a network is never DISCONNECTED, so one DISCONNECTED entry gives
-- DISCONNECTED; a network is BLOCKED only for an app blocked on metered networks, so else a BLOCKED entry gives BLOCKED.
-- shortcut: a list without another type without a network gives DISCONNECTED, or BLOCKED by a blocked metered network
-- before Android 12, where Android has DISCONNECTED; Wi-Fi Direct and Bluetooth make such lists rare, upgrade should
-- devices without them matter
function notconnected(param, infos)
    local states = luajava.bindClass('android.net.NetworkInfo$DetailedState')
    local ok, list = pcall(function()
        return infos or param:unhooked(function()
            return param:getThis():getAllNetworkInfo()
        end)
    end)
    local state = states.DISCONNECTED
    if ok and list ~= nil then
        for index = 1, list['length'] do
            local other = list[index]:getDetailedState()
            if other == states.DISCONNECTED then
                state = other
                break
            elseif other == states.BLOCKED then
                state = other
            end
        end
    end
    local info = luajava.newInstance('android.net.NetworkInfo', TYPE_VPN, 0, 'VPN', '')
    info:setIsAvailable(true)
    info:setDetailedState(state, nil, nil)
    return info
end

-- LoWPAN, which phones do not have, is what a request for VPN networks asks for instead (see vpn_request)
local TRANSPORT_LOWPAN = 6

-- Whether capabilities really have a transport: only the network of a VPN has VPN, and only a request of the app
-- for VPN networks has LoWPAN (see vpn_request), which then answers like the request the app made, with VPN.
-- Requests without either, also without NOT_VPN, are as the app made them.
-- shortcut: a request of the app for LoWPAN networks itself is one for VPN then, upgrade should apps ask for LoWPAN.
function hasreal(param, capabilities, transport)
    return param:unhooked(function()
        return capabilities:hasTransport(transport)
    end)
end

-- Whether VPN is the only transport of a network: Android 8.1 never gives the network of a VPN the transport of the
-- network under it, nor do later versions for a VPN which sets no networks under it (setUnderlyingNetworks)
function vpnonly(param, capabilities)
    return param:unhooked(function()
        if not capabilities:hasTransport(TRANSPORT_VPN) then
            return false
        end
        -- The transports are the bits of an int
        for transport = 0, 31 do
            if transport ~= TRANSPORT_VPN and capabilities:hasTransport(transport) then
                return false
            end
        end
        return true
    end)
end

-- A copy of the capabilities of the network of a VPN like the hooks show them, with NOT_VPN, and the transport of the
-- network under it in place of VPN, which NetworkRequest compares by their fields, not through the hooked getters.
-- The setters are hidden, so the copy is read back from a parcel of the capabilities, in which the first three longs
-- are the capabilities, the forbidden ones and the transports from Android 9 on; through Lua a long is a double,
-- exact for these bits. A copy which does not show the fakes, like for another order of the fields, fails.
function fakecopy(param, capabilities)
    local parcel = luajava.bindClass('android.os.Parcel'):obtain()
    local ok, copy = pcall(function()
        return param:unhooked(function()
            capabilities:writeToParcel(parcel, 0)
            parcel:setDataPosition(0)
            local values = parcel:readLong()
            parcel:readLong()
            local transports = parcel:readLong() - 2 ^ TRANSPORT_VPN
            if transports == 0 then
                transports = 2 ^ underlyingtransport(param)
            end
            if not capabilities:hasCapability(NET_CAPABILITY_NOT_VPN) then
                values = values + 2 ^ NET_CAPABILITY_NOT_VPN
            end
            parcel:setDataPosition(0)
            parcel:writeLong(values)
            parcel:setDataPosition(16)
            parcel:writeLong(transports)
            parcel:setDataPosition(0)
            local copy = luajava.bindClass('android.net.NetworkCapabilities').CREATOR:createFromParcel(parcel)
            if not copy:hasCapability(NET_CAPABILITY_NOT_VPN) or copy:hasTransport(TRANSPORT_VPN) then
                error('NetworkCapabilities parcel unknown')
            end
            return copy
        end)
    end)
    parcel:recycle()
    if not ok then
        error(copy, 0)
    end
    return copy
end

-- The network a VPN runs over and the connectivity manager, for calls without the hooks (see unhooked): the network of
-- the type of the active network info with internet, since the networks of IMS and MMS have the type of mobile data too
function underlyingnetwork(param)
    local info, type, cm = underlying(param)
    if info == nil then
        return nil
    end
    local networks = cm:getAllNetworks()
    for index = 1, networks['length'] do
        local other = cm:getNetworkInfo(networks[index])
        local capabilities = cm:getNetworkCapabilities(networks[index])
        if other ~= nil and capabilities ~= nil and other:getType() == type and
                not capabilities:hasTransport(TRANSPORT_VPN) and capabilities:hasCapability(NET_CAPABILITY_INTERNET) then
            return networks[index], cm
        end
    end
    return nil
end

-- The capabilities of the network a VPN runs over when it is Wi-Fi, whose transport info (WifiInfo, from Android 12)
-- and signal strength every Wi-Fi network has, and the capabilities of the VPN do not get from it
-- (applyUnderlyingCapabilities of ConnectivityService); nil for other networks, which have neither.
-- shortcut: each call reads the capabilities of that network again, so the transport info is a new object each time,
-- where capabilities of Android keep theirs; upgrade should apps compare them by identity
function wificapabilities(param)
    local ok, capabilities = pcall(param.unhooked, param, function()
        local network, cm = underlyingnetwork(param)
        local capabilities = network and cm:getNetworkCapabilities(network)
        if capabilities and capabilities:hasTransport(TRANSPORT_WIFI) then
            return capabilities
        end
        return nil
    end)
    return ok and capabilities or nil
end

-- The signal strength of those capabilities, nil without one; hidden before Android 10
function wifisignal(param, wifi)
    if wifi == nil then
        return nil
    end
    local ok, signal = pcall(param.unhooked, param, function()
        return wifi:getSignalStrength()
    end)
    if ok and signal ~= SIGNAL_STRENGTH_UNSPECIFIED then
        return signal
    end
    return nil
end

-- The text of capabilities without a VpnTransportInfo, and of a VPN over Wi-Fi with the transport info and the signal
-- strength of that network where Android prints them: in place of the VpnTransportInfo from Android 12, else after
-- the specifier, before the uids, the owner, the SSID and the requestor, or at the end, like Android 8.1 and 9
function wifitext(param, text, wifi)
    local added = ''
    if wifi then
        local ok, info = pcall(param.unhooked, param, function()
            return wifi:getTransportInfo()
        end)
        if ok and info ~= nil then
            -- Through its hooks, like in the text of the capabilities of the Wi-Fi network
            added = ' TransportInfo: <' .. info:toString() .. '>'
        end
        local signal = wifisignal(param, wifi)
        if signal ~= nil then
            added = added .. ' SignalStrength: ' .. signal
        end
    end
    local fake, count = string.gsub(text, ' TransportInfo: <VpnTransportInfo{[^}]*}>', function()
        return added
    end)
    if count > 0 or added == '' or string.find(fake, ' SignalStrength: ', 1, true) then
        return fake
    end
    local at = #fake
    for _, label in ipairs({ ' Uid: ', ' Uids: <', ' EstablishingAppUid: ', ' AllowedUids: <', ' OwnerUid: ',
                             ' AdministratorUids: ', ' AdminUids: ', ' RequestorUid: ', ' SSID: ', ' Private DNS is broken',
                             ' PrivateDnsBroken', ' SubscriptionIds: ', ' EnterpriseId: ', ' UnderlyingNetworks: ' }) do
        local found = string.find(fake, label, 1, true)
        if found ~= nil and found < at then
            at = found
        end
    end
    return string.sub(fake, 1, at - 1) .. added .. string.sub(fake, at)
end

-- An address of a VPN interface, like 10.8.0.2 of tun0, which a socket connected through the VPN has and no listed
-- interface has (see getNetworkInterfaces), as the address of the network the VPN runs over of its family, which a
-- socket there has, like on a device without a VPN; nil for other addresses.
-- shortcut: without an address of the family there, like for IPv4 through a VPN over mobile data with IPv6 only, whose
-- IPv4 address of 464XLAT is on a stacked link, or without the permission to read the networks, which sockets do not
-- need, the wildcard address, like Read.Network gives (see network_address); upgrade should apps check such sockets
function untunneled(param, address)
    if address:isLoopbackAddress() or address:isAnyLocalAddress() or address:isLinkLocalAddress() or
            address:isMulticastAddress() then
        return nil
    end
    return param:unhooked(function()
        local interface = luajava.bindClass('java.net.NetworkInterface'):getByInetAddress(address)
        if interface == nil or not isvpninterface(interface:getName()) then
            return nil
        end
        local length = address:getAddress()['length']
        local found, network, cm = pcall(underlyingnetwork, param)
        local properties = found and network and cm:getLinkProperties(network)
        if properties then
            local addresses = properties:getLinkAddresses()
            for index = 0, addresses:size() - 1 do
                local other = addresses:get(index):getAddress()
                if other:getAddress()['length'] == length and not other:isLinkLocalAddress() then
                    return other
                end
            end
        end
        return luajava.bindClass('java.net.InetAddress'):getByName(length == 16 and '::' or '0.0.0.0')
    end)
end

function after(hook, param)
    if nested.running then
        return false
    end
    nested.running = true
    -- The runtime stays after an error (see XCore), which must not leave the guard set
    local ok, restricted, old, new = pcall(restrict, hook, param)
    nested.running = nil
    if not ok then
        error(restricted, 0)
    end
    return restricted, old, new
end

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

function restrict(hook, param)
    local method = hook:getMethodName()
    local result = param:getResult()

    -- The index of a network interface by its name and back (Os), which NetworkInterface and /sys hide for VPNs:
    -- like for an interface which does not exist, 0 and null
    if method == 'if_nametoindex' then
        if result == 0 or param:getException() ~= nil or not isvpninterface(param:getArgument(0)) then
            return false
        end
        param:setResult(0)
        return true, tostring(result), '0'
    elseif method == 'if_indextoname' then
        if result == nil or param:getException() ~= nil or not isvpninterface(result) then
            return false
        end
        param:setResult(nil)
        return true, result, nil
    end

    if method == 'hasTransport' then
        local transport = param:getArgument(0)
        if transport == TRANSPORT_VPN or transport == TRANSPORT_LOWPAN then
            local fake = (transport == TRANSPORT_VPN and not result and hasreal(param, param:getThis(), TRANSPORT_LOWPAN))
            if result ~= fake then
                param:setResult(fake)
                return true, tostring(result), tostring(fake)
            end
        elseif not result and vpnonly(param, param:getThis()) and transport == underlyingtransport(param) then
            -- Like the text, the network of a VPN without another transport has the one of the network under it
            param:setResult(true)
            return true, 'false', 'true'
        end

    elseif method == 'hasCapability' then
        if not result and param:getArgument(0) == NET_CAPABILITY_NOT_VPN and
                hasreal(param, param:getThis(), TRANSPORT_VPN) then
            param:setResult(true)
            return true, 'false', 'true'
        end

    elseif method == 'canBeSatisfiedBy' then
        -- A request has NOT_VPN by default, which the network of a VPN lacks
        local capabilities = param:getArgument(0)
        if not result and capabilities ~= nil and hasreal(param, capabilities, TRANSPORT_VPN) then
            local request = param:getThis()
            local copy = fakecopy(param, capabilities)
            if param:unhooked(function()
                return request:canBeSatisfiedBy(copy)
            end) then
                param:setResult(true)
                return true, 'false', 'true'
            end
        end

    elseif method == 'getCapabilities' then
        -- The capabilities of a VPN network lack NOT_VPN
        if result ~= nil then
            local length = result['length']
            for index = 1, length do
                if result[index] == NET_CAPABILITY_NOT_VPN then
                    return false
                end
            end
            if not hasreal(param, param:getThis(), TRANSPORT_VPN) then
                return false
            end
            local arrayClass = luajava.bindClass('java.lang.reflect.Array')
            local intClass = luajava.bindClass('java.lang.Integer')
            local fake = arrayClass:newInstance(intClass.TYPE, length + 1)
            -- In bit order, like the platform lists them
            local shift = 0
            for index = 1, length do
                if shift == 0 and result[index] > NET_CAPABILITY_NOT_VPN then
                    fake[index] = NET_CAPABILITY_NOT_VPN
                    shift = 1
                end
                fake[index + shift] = result[index]
            end
            if shift == 0 then
                fake[length + 1] = NET_CAPABILITY_NOT_VPN
            end
            param:setResult(fake)
            return true
        end

    elseif method == 'getTransportTypes' then
        -- The transports of a VPN network lack VPN, or are the one of the network under it like for hasTransport,
        -- and a request of the app for VPN networks has VPN in place of LoWPAN, in bit order
        if result ~= nil then
            local old, kept, vpn, lowpan = {}, {}, false, false
            for index = 1, result['length'] do
                old[#old + 1] = result[index]
                if result[index] == TRANSPORT_VPN then
                    vpn = true
                elseif result[index] == TRANSPORT_LOWPAN then
                    lowpan = true
                else
                    kept[#kept + 1] = result[index]
                end
            end
            if vpn and #kept == 0 then
                kept[1] = underlyingtransport(param)
            elseif lowpan and not vpn then
                kept[#kept + 1] = TRANSPORT_VPN
                table.sort(kept)
            end
            if vpn or lowpan then
                local arrayClass = luajava.bindClass('java.lang.reflect.Array')
                local intClass = luajava.bindClass('java.lang.Integer')
                local fake = arrayClass:newInstance(intClass.TYPE, #kept)
                for index = 1, #kept do
                    fake[index] = kept[index]
                end
                param:setResult(fake)
                return true, table.concat(old, ','), table.concat(kept, ',')
            end
        end

    elseif method == 'getTransportInfo' then
        -- Android 12 and later describe a VPN network with VpnTransportInfo, which is not in the SDK;
        -- Lua sees a Class as its static members, so the class name cannot be read from Lua (see XParam.isInstance),
        -- and Android 10 and 11 do not have the class, so finding it fails there
        local ok, vpn = pcall(param.isInstance, param, result, 'android.net.VpnTransportInfo')
        if ok and vpn then
            -- A VPN over Wi-Fi has the WifiInfo of that network, which the app reads through its hooks; others have none
            local wifi = wificapabilities(param)
            local fake = (wifi ~= nil and param:unhooked(function()
                return wifi:getTransportInfo()
            end) or nil)
            param:setResult(fake)
            return true, result, fake
        end

    elseif method == 'getSignalStrength' then
        -- Like the transport info
        if result == SIGNAL_STRENGTH_UNSPECIFIED and hasreal(param, param:getThis(), TRANSPORT_VPN) then
            local fake = wifisignal(param, wificapabilities(param))
            if fake ~= nil then
                param:setResult(fake)
                return true, tostring(result), tostring(fake)
            end
        end

    elseif method == 'toString' and (hook:getClassName() == 'android.net.LinkProperties' or
            hook:getClassName() == 'android.net.RouteInfo') then
        -- The interface name of a VPN, also in the routes, like getInterfaceName;
        -- the name is a word, like in "-> 0.0.0.0 tun0 mtu", and ends the text of a route before Android 11
        if result ~= nil then
            local name = string.match(result, 'InterfaceName: (%S+)') or string.match(result, ' (%S+) mtu ') or
                    (hook:getClassName() == 'android.net.RouteInfo' and string.match(result, ' (%S+)$'))
            if isvpninterface(name) then
                local other = underlyinginterface(param)
                -- A space after the text makes a name at its end a word too
                local fake = string.gsub(result .. ' ', '([^%w])' .. string.gsub(name, '%p', '%%%0') .. '([^%w])',
                        function(before, after)
                            return before .. other .. after
                        end)
                fake = string.sub(fake, 1, -2)
                -- The addresses, DNS servers, routes, search domains and MTU are the ones in the text of the network under
                -- the VPN, found without the hooks and read through them like its getters (see vpn_addresses), so
                -- Read.Network still empties them; without it they are empty and the MTU is 0. The other fields stay
                -- the VPN's, like their getters. The first ones are of the network itself, the links stacked on it
                -- follow them. Functions put them in, since a text with % is no replacement.
                local under = ''
                if hook:getClassName() == 'android.net.LinkProperties' then
                    local ok, text = pcall(function()
                        local lp = propertiesof(param, other)
                        return lp and lp:toString()
                    end)
                    under = (ok and text) or ''
                end
                -- The text of Android 10 and later has spaces inside the brackets
                for _, label in ipairs({ 'LinkAddresses', 'DnsAddresses', 'Routes' }) do
                    local items = string.match(under, '[^%w]' .. label .. ': %[([^%]]*)%]')
                    fake = string.gsub(fake, '([^%w]' .. label .. ': %[)( ?)[^%]]*%]', function(head, empty)
                        return head .. (items or empty) .. ']'
                    end, 1)
                end
                local mtu = string.match(under, '[^%w]MTU: (%-?%d+)') or '0'
                fake = string.gsub(fake, '([^%w]MTU: )%-?%d+', function(head)
                    return head .. mtu
                end, 1)
                -- The search domains are separated by spaces and followed by the MTU
                local list = string.match(under, '[^%w]Domains: ([^:]-) MTU: ') or 'null'
                local domains
                fake, domains = string.gsub(fake, '([^%w]Domains: )[^:]-( MTU: )', function(head, tail)
                    return head .. list .. tail
                end, 1)
                if domains == 0 then
                    fake = string.gsub(fake, '([^%w]Domains: )%S+', '%1null')
                end
                -- The HTTP proxy goes too, like getHttpProxy; its text has spaces, like "[127.0.0.1] 8080 xl=",
                -- and the NAT64 prefix, the stacked links or the end of the text follow it
                local proxy = string.find(fake, ' HttpProxy: ', 1, true)
                if proxy ~= nil then
                    local rest = string.find(fake, ' Nat64Prefix: ', proxy, true) or
                            string.find(fake, ' Stacked: [', proxy, true) or string.len(fake)
                    fake = string.sub(fake, 1, proxy - 1) .. string.sub(fake, rest)
                end
                param:setResult(fake)
                return true, result, fake
            end
        end

    elseif method == 'toString' then
        -- Apps search the text for VPN, like the name of the VpnTransportInfo
        if result ~= nil then
            local capabilities = (hook:getClassName() == 'android.net.NetworkCapabilities' and param:getThis())
            local vpn = capabilities and hasreal(param, capabilities, TRANSPORT_VPN)
            -- Of a VPN over Wi-Fi with the transport info and signal strength of that network, like the getters
            local fake = wifitext(param, result, vpn and wificapabilities(param))
            -- Like getReason, what NetworkInfo prints without a reason
            fake = string.gsub(fake, ', reason: agentConnect,', ', reason: (unspecified),')
            local info = (hook:getClassName() == 'android.net.NetworkInfo' and param:getThis())
            if info and info:isConnectedOrConnecting() and realtype(param, info) == TYPE_VPN then
                -- The extra info and roaming of the network the VPN runs over, like the getters; the text has them from
                -- the fields, and the names of the type and subtype from the hooked getters
                local ok, other = pcall(underlying, param)
                if ok and other ~= nil then
                    local extra = other:getExtraInfo()
                    fake = string.gsub(fake, ', extra: [^,]*, failover: ', function()
                        return ', extra: ' .. (extra == nil and '(none)' or extra) .. ', failover: '
                    end, 1)
                    fake = string.gsub(fake, ', roaming: %a+%]$', ', roaming: ' .. tostring(other:isRoaming()) .. ']')
                end
            end
            if vpn then
                -- Android 8.1 lists the transports of the hooked getTransportTypes, later versions the real ones
                fake = string.gsub(fake, '|VPN', '')
                fake = string.gsub(fake, 'VPN|', '')
                if string.find(fake, 'Transports: VPN', 1, true) then
                    local _, name = underlyingtransport(param)
                    fake = string.gsub(fake, 'Transports: VPN', 'Transports: ' .. name)
                end
                if not string.find(fake, 'NOT_VPN', 1, true) then
                    fake = string.gsub(fake, 'Capabilities: ([%w_&]*)', function(names)
                        return 'Capabilities: ' .. withnotvpn(names)
                    end, 1)
                end
            elseif capabilities and hasreal(param, capabilities, TRANSPORT_LOWPAN) then
                -- A request of the app for VPN networks, of which vpn_request made one for LoWPAN, is one for VPN again;
                -- Android 8.1 lists the transports of the hooked getTransportTypes, which has VPN already
                fake = string.gsub(fake, 'LOWPAN', 'VPN')
            end
            if fake ~= result then
                param:setResult(fake)
                return true, result, fake
            end
        end

    elseif method == 'getNetworkInfo' then
        if result ~= nil and param:getArgument(0) == TYPE_VPN and result:isConnectedOrConnecting() then
            param:setResult(notconnected(param, nil))
            return true
        end

    elseif method == 'getAllNetworkInfo' then
        -- A device has an entry of the type VPN, not connected, also without one, so the entry stays
        if result ~= nil then
            local changed = false
            for index = 1, result['length'] do
                if realtype(param, result[index]) == TYPE_VPN and result[index]:isConnectedOrConnecting() then
                    result[index] = notconnected(param, result)
                    changed = true
                end
            end
            return changed
        end

    elseif method == 'getAllNetworks' then
        -- Without the network of a VPN, like getAllNetworkInfo, unless it is the active network of the app, which every
        -- device has in the list; the hooks show it like the network it runs over.
        -- shortcut: the network the VPN runs over stays too, so two networks have its transport and interface; leaving
        -- it out needs the lookups of the scripts without the hooks (underlyinginterface), upgrade should apps compare
        if result ~= nil then
            local cm = param:getThis()
            local keep = param:unhooked(function()
                local active = cm:getActiveNetwork()
                local keep = {}
                for index = 1, result['length'] do
                    local network = result[index]
                    local capabilities = cm:getNetworkCapabilities(network)
                    -- Lua compares Java objects by equals, which compares networks by their id
                    if capabilities == nil or not capabilities:hasTransport(TRANSPORT_VPN) or network == active then
                        keep[#keep + 1] = network
                    end
                end
                return keep
            end)
            if #keep < result['length'] then
                local arrayClass = luajava.bindClass('java.lang.reflect.Array')
                local fake = arrayClass:newInstance(luajava.bindClass('android.net.Network'), #keep)
                for index = 1, #keep do
                    fake[index] = keep[index]
                end
                param:setResult(fake)
                return true
            end
        end

    elseif method == 'getType' then
        -- The type of the network the VPN runs over, like the capabilities of the VPN
        if result == TYPE_VPN and not param:getValue('vpn.real', param:getThis()) and
                param:getThis():isConnectedOrConnecting() then
            local fake = underlyingtype(param)
            param:setResult(fake)
            return true, tostring(result), tostring(fake)
        end

    elseif method == 'getTypeName' then
        if result == 'VPN' and param:getThis():isConnectedOrConnecting() then
            local _, fake = underlyingtype(param)
            param:setResult(fake)
            return true, result, fake
        end

    elseif method == 'getSubtype' or method == 'getSubtypeName' or method == 'getExtraInfo' or method == 'isRoaming' then
        -- Like the type, of the network the VPN runs over, like mobile data with LTE and its APN, through its hooks
        local info = param:getThis()
        if not info:isConnectedOrConnecting() or realtype(param, info) ~= TYPE_VPN then
            return false
        end
        local ok, other = pcall(underlying, param)
        if not ok or other == nil then
            return false
        end
        local fake = other[method](other)
        if fake == result then
            return false
        end
        param:setResult(fake)
        return true, tostring(result), tostring(fake)

    elseif method == 'getReason' then
        -- Android 8.1 to 11 keep the reason of the connecting VPN in its NetworkInfo; other reasons tell no VPN
        if result == 'agentConnect' then
            param:setResult(nil)
            return true, result, nil
        end

    elseif method == 'getNetworkInterfaces' then
        -- NetworkInterface.networkInterfaces() is in the SDK stubs, but not in the Android runtime
        if result ~= nil then
            -- The original enumeration is consumed here, so always set a new result
            local collections = luajava.bindClass('java.util.Collections')
            local items = collections:list(result):toArray()
            local list = luajava.newInstance('java.util.ArrayList')
            local removed = false
            for index = 1, items['length'] do
                if isvpninterface(items[index]:getName()) then
                    removed = true
                else
                    list:add(items[index])
                end
            end
            param:setResult(collections:enumeration(list))
            return removed
        end

    elseif method == 'getInterfaceName' or method == 'getInterface' then
        -- LinkProperties of the active network, like tun0, and its routes (RouteInfo.getInterface);
        -- LinkProperties checks the routes against its own interface when it reads a parcel or copies (addRoute),
        -- which throws for a faked interface, so it gets the real ones
        if isvpninterface(result) and not linkproperties() then
            local fake = underlyinginterface(param)
            param:setResult(fake)
            return true, result, fake
        end

    elseif method == 'getLocalAddress' or method == 'getLocalSocketAddress' or method == 'getsockname' then
        -- The address of a socket connected through the VPN (see untunneled): Socket and DatagramSocket give an
        -- InetAddress, channels and getsockname a SocketAddress, of which only those of IP change, with their port
        local socket = param:isInstance(result, 'java.net.InetSocketAddress')
        local address = (socket and result:getAddress() or result)
        if result == nil or not param:isInstance(address, 'java.net.InetAddress') then
            return false
        end
        local fake = untunneled(param, address)
        if fake == nil then
            return false
        end
        param:setResult(socket and luajava.newInstance('java.net.InetSocketAddress', fake, result:getPort()) or fake)
        return true, address:getHostAddress(), fake:getHostAddress()

    elseif method == 'getByName' or method == 'getByIndex' or method == 'getByInetAddress' then
        -- The address of a socket connected through the VPN is of the VPN interface
        if result ~= nil and isvpninterface(result:getName()) then
            param:setResult(nil)
            return true, result:getName(), nil
        end
    end

    return false
end
