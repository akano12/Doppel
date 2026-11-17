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
-- (see vpn), still have the addresses of the tunnel, like 10.8.0.2/32, the DNS servers of the VPN, its routes,
-- search domains and, from Android 11, the MTU of the tunnel, like 1420, which tell there is a VPN: they are the
-- ones of the network under it, like on a device without a VPN, through the hooks, so Read.Network leaves out its
-- addresses for all networks still (see network_address). Without that network they are left out, and the MTU is 0.
-- The HTTP proxy a VPN can set from Android 10 (VpnService.Builder.setHttpProxy), often on 127.0.0.1, is null, like
-- on a network without a proxy.
-- The text of the VPN has the same ones, from the network found the same way (see vpn), and so has its parcel (see
-- vpn_parcel). LinkProperties gets the real ones when it checks, compares, copies or parcels itself, apart from where
-- vpn_parcel fakes its parcel, hash or comparison.
-- shortcut: the other fields stay the VPN's, in their getters and the text alike: the DHCP server, the private DNS,
-- the P-CSCF servers, the TCP buffer sizes, the NAT64 prefix and the stacked links, so a VPN over IPv6-only mobile
-- data has no NAT64 prefix; the hidden getAll* getters do include the links stacked on the network under it.
-- Upgrade by delegating those getters too, with their part of the text, if apps are seen to check them.
-- shortcut: ConnectivityManager.getDefaultProxy, the http.proxyHost property and Proxy.getDefaultHost still give the
-- proxy of the VPN, since the app process sets the proxy of its connections from getDefaultProxy (ActivityThread),
-- so hiding it would break proxied networking; upgrade if apps are seen to check them for a VPN.

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

-- The LinkProperties of the network under the VPN, of which the hook of vpn gives the interface name for it, like
-- rmnet_data0; nil when the hook keeps the name of the tunnel, which would find the VPN itself again
function underlyingproperties(param, properties)
    local name = properties:getInterfaceName()
    if isvpninterface(name) then
        return nil
    end
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

function after(hook, param)
    local method = hook:getMethodName()
    local result = param:getResult()
    -- The real interface, which the hook of vpn would fake
    local properties = param:getThis()
    local name = param:unhooked(function()
        return properties:getInterfaceName()
    end)
    -- Also where LinkProperties parcels or compares itself while vpn_parcel fakes that, which marks it
    if not isvpninterface(name) or (linkproperties() and not param:getValue('vpn.parcel', properties)) or
            not devicevpn(param, properties, name) then
        return false
    end
    -- Also for an empty list or no search domains of the VPN, which the network under it can have; not for the HTTP
    -- proxy, which is null, like in the text of the VPN (see vpn)
    local ok, fake = pcall(function()
        local lp = (method ~= 'getHttpProxy' and underlyingproperties(param, properties))
        if not lp then
            error('No network under the VPN')
        end
        return lp[method](lp)
    end)
    if not ok then
        -- getDomains, the search domains as text, like corp.example, getHttpProxy, a ProxyInfo, and getMtu, a number
        if method == 'getDomains' or method == 'getHttpProxy' then
            fake = nil
        elseif method == 'getMtu' then
            fake = 0
        elseif result:isEmpty() then
            return false
        else
            fake = luajava.newInstance('java.util.ArrayList')
        end
    end
    if fake == result then
        return false
    end
    param:setResult(fake)
    return true
end
