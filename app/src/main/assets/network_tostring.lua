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

-- The text of network objects contains the values their getters hide, so the same fake values replace them
-- in the text; the other fields stay:
-- WifiInfo: the network name, the address of the access point, the MAC and IP address, the id of the saved network,
-- the Passpoint provider and the multi-link information; its variant mac of Read.Identifiers only the MAC address
-- NetworkInfo: the extra info, which is the network name or the APN
-- LinkProperties: the addresses of the device, the DNS servers, the domains, the DHCP server, the routes and the
-- NAT64 prefix
-- NetworkRegistrationInfo, also in the text of ServiceState: the cell
-- WifiP2pDeviceList: the nearby Wi-Fi Direct devices
-- Values are replaced between their label and the next label, because network names can contain commas.

local NONE = '<none>'
-- The address Android shows to apps without permission, also for the access point
local HIDDEN_MAC = '02:00:00:00:00:00'
-- The fake access point of the getter (wifiinfo_ssid_bssid)
local FAKE_BSSID = 'a6:3e:91:5c:0d:72'

-- Replaces the value after each label up to nextLabel, like of the links stacked in LinkProperties,
-- unless it is one of the kept values
function replace(text, label, nextLabel, fake, kept)
    local restricted = false
    local start = 1
    while true do
        local _, last = string.find(text, label, start, true)
        if last == nil then
            break
        end
        local following = string.find(text, nextLabel, last + 1, true)
        if following == nil then
            break
        end
        local value = string.sub(text, last + 1, following - 1)
        if value ~= fake and not (kept ~= nil and kept[value]) then
            text = string.sub(text, 1, last) .. fake .. string.sub(text, following)
            restricted = true
        end
        start = last + 1
    end
    return text, restricted
end

function wifiinfo(text)
    local restricted = false
    local changed

    -- The network name comes first, like the getter "private" with quotes; before Android 12 the text has the name
    -- itself, without quotes, unless the Wi-Fi module of Android 11 was updated to one which prints getSSID, and the
    -- empty name, which Android 9 to 11 give apps without location permission and for which getSSID says
    -- <unknown ssid> too, stays.
    -- shortcut: the text alone cannot tell some names apart, so before Android 12 the fake differs from getSSID for
    -- them: a name of only zero bytes prints empty there too, while the getter fakes the hex getSSID gives for it; a
    -- name which itself starts with a quote gets the quoted fake; and on an updated Wi-Fi module the hex of a name which
    -- is no UTF-8 gets the bare one; upgrade when such a name shows up in the connection info
    if string.sub(text, 1, 6) == 'SSID: ' then
        local fake = '"private"'
        if string.sub(text, 7, 7) ~= '"' and luajava.bindClass('android.os.Build$VERSION').SDK_INT < 31 then
            fake = 'private'
        end
        text, changed = replace(text, 'SSID: ', ', BSSID: ', fake, { ['<unknown ssid>'] = true, [''] = true })
        restricted = restricted or changed
    end

    -- The access point, like the getter, which keeps the address Android shows to apps without location permission
    -- and the zeros of Android 8.1 when not connected
    text, changed = replace(text, ', BSSID: ', ', ', FAKE_BSSID,
        { [NONE] = true, [HIDDEN_MAC] = true, ['00:00:00:00:00:00'] = true })
    restricted = restricted or changed

    -- Other addresses, like the MAC address and the addresses of multi-link access points,
    -- as Android shows them to apps without permission
    local replaced = 0
    text = string.gsub(text, '%x%x:%x%x:%x%x:%x%x:%x%x:%x%x', function(mac)
        if mac == '00:00:00:00:00:00' or mac == HIDDEN_MAC or mac == FAKE_BSSID then
            return nil
        end
        replaced = replaced + 1
        return HIDDEN_MAC
    end)
    restricted = restricted or replaced > 0

    -- The IP address, like the getter (0), which Android has as no address, printed as null
    text, changed = replace(text, ', IP: ', ', ', 'null', nil)
    restricted = restricted or changed

    -- The id of the saved network, like the getter (0), which keeps -1
    text, changed = replace(text, ', Net ID: ', ', ', '0', { ['-1'] = true })
    restricted = restricted or changed

    -- Passpoint networks
    text, changed = replace(text, ', FQDN: ', ', Provider friendly name: ', NONE, nil)
    restricted = restricted or changed
    text, changed = replace(text, ', Provider friendly name: ', ', Requesting package name: ', NONE, nil)
    restricted = restricted or changed

    -- The network key, the network name with the security type like "MyNet"psk, follows the requesting
    -- package without a label of its own and is followed by the MLO information (Android 13 and later), else it
    -- ends the text; a network name can contain ', ', so the next field is no end
    local _, last = string.find(text, ', Requesting package name: ', 1, true)
    if last ~= nil then
        local package = string.match(text, '^' .. NONE, last + 1) or string.match(text, '^[%w_%.]+', last + 1)
        if package ~= nil then
            local first = last + string.len(package) + 1
            local following = string.find(text, 'MLO Information: ', first, true) or string.len(text) + 1
            local key = string.sub(text, first, following - 1)
            if key ~= '' and key ~= NONE then
                text = string.sub(text, 1, first - 1) .. NONE .. string.sub(text, following)
                restricted = true
            end
        end
    end

    -- The multi-link information of a Wi-Fi 7 access point, <none> like Android prints it without an MLD address, as
    -- the getters hide the address, the link id and the links; the vendor data of Android 15 and later follows
    local _, mld = string.find(text, ', AP MLD Address: ', 1, true)
    if mld ~= nil then
        local following = string.find(text, ', Vendor Data: ', mld + 1, true) or string.len(text) + 1
        local none = NONE .. ', AP MLO Link Id: ' .. NONE .. ', AP MLO Affiliated links: ' .. NONE
        if string.sub(text, mld + 1, following - 1) ~= none then
            text = string.sub(text, 1, mld) .. none .. string.sub(text, following)
            restricted = true
        end
    end

    return text, restricted
end

function networkinfo(text)
    -- The extra info is (none), which Android prints when the getter returns nothing, like the hooked getter does,
    -- also for the empty one Android leaves most apps
    return replace(text, ', extra: ', ', failover: ', '(none)', nil)
end

-- The lists are empty, like the getters; the text of Android 10 and later has spaces inside the brackets.
-- A label starts a word, because ValidatedPrivateDnsAddresses ends with DnsAddresses;
-- the text starts with a brace, so there is a character before each label.
function emptylists(text, label)
    local replaced = 0
    text = string.gsub(text, '([^%w])' .. label .. ': %[([^%]]*)%]', function(before, items)
        local empty = ''
        if string.sub(items, 1, 1) == ' ' then
            empty = ' '
        end
        if items == empty then
            return nil
        end
        replaced = replaced + 1
        return before .. label .. ': [' .. empty .. ']'
    end)
    return text, replaced > 0
end

-- Removes the sections of the pattern, except for the character before them, which the pattern captures
function removesection(text, pattern)
    local removed
    text, removed = string.gsub(text, pattern, '%1')
    return text, removed > 0
end

function linkproperties(text)
    local restricted = false
    local changed

    -- Addresses of the device, DNS servers and routes, which contain the gateway
    for _, label in ipairs({ 'LinkAddresses', 'DnsAddresses', 'Routes' }) do
        text, changed = emptylists(text, label)
        restricted = restricted or changed
    end

    -- Other DNS servers, which have no public getter, Android leaves them out when there are none
    for _, label in ipairs({ 'PcscfAddresses', 'ValidatedPrivateDnsAddresses' }) do
        text, changed = removesection(text, '([^%w])' .. label .. ': %[[^%]]*%] ?')
        restricted = restricted or changed
    end

    -- The NAT64 prefix of the network (Android 10 and later), null like the getter, which Android leaves out then;
    -- it can end the text, so the character before it stays.
    -- shortcut: the proxy (HttpProxy) stays, like getHttpProxy and getDefaultProxy. Its host, port and exclusion list are
    -- also in the System properties (http.proxyHost and the others), which the app process sets from getDefaultProxy for
    -- its connections. A PAC URL is not: Android's code there only tests it for empty to pick its PAC selector, which
    -- asks the system, and WebView and Cronet use the local proxy instead, so one fake URL could replace it, but only in
    -- getHttpProxy, getDefaultProxy, getProxyForNetwork, the proxy change broadcast, global_proxy_pac_url and this text
    -- together; upgrade when a PAC URL is seen to name the network
    text, changed = removesection(text, '(.) Nat64Prefix: [^%s}]+')
    restricted = restricted or changed

    -- The domains are a list with spaces up to the MTU, which is always there, null like the getter
    text, changed = replace(text, 'Domains: ', ' MTU: ', 'null', nil)
    restricted = restricted or changed

    -- The DHCP server and the private DNS server name are null, like their getters;
    -- Android 10 and later leave them out then, Android 9 shows null
    text, changed = removesection(text, '( )ServerAddress: [^%s}]+ ?')
    restricted = restricted or changed
    if string.find(text, 'LinkAddresses: [ ', 1, true) then
        text, changed = removesection(text, '( )PrivateDnsServerName: [^%s}]+ ?')
    else
        text, changed = replace(text, 'PrivateDnsServerName: ', ' ', 'null', nil)
    end
    restricted = restricted or changed

    return text, restricted
end

function networkregistrationinfo(text)
    -- The cell, null like the getter and like Android shows it to apps without location permission
    return replace(text, ' cellIdentity=', ' voiceSpecificInfo=', 'null', nil)
end

-- Android 9 names it NetworkRegistrationState (hidden), which the text of ServiceState lists, also for apps without
-- a location permission, since listening to the service state needs none there
function networkregistrationstate(text)
    return replace(text, ' cellIdentity=', ' voiceSpecificStates=', 'null', nil)
end

function wifip2pdevicelist(text)
    -- The text of an empty list, like the getter
    return '', text ~= ''
end

-- WifiP2pGroup: without its clients, like getClientList; a client is a device of several lines, all of them come
-- before the interface
function wifip2pgroup(text)
    local fake = string.gsub(text, '\n Client: .-(\n interface: )', '%1')
    return fake, fake ~= text
end

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local fake, restricted
    local class = hook:getClassName()
    if class == 'android.net.wifi.WifiInfo' and string.match(hook:getName(), '/(.*)$') == 'mac' then
        -- Read.Identifiers: only the MAC address, like getMacAddress, for an app which is not restricted in Read.Network
        fake, restricted = replace(result, ', MAC: ', ', ', HIDDEN_MAC, { ['00:00:00:00:00:00'] = true })
    elseif class == 'android.net.wifi.WifiInfo' then
        fake, restricted = wifiinfo(result)
    elseif class == 'android.net.LinkProperties' then
        fake, restricted = linkproperties(result)
    elseif class == 'android.telephony.NetworkRegistrationState' then
        fake, restricted = networkregistrationstate(result)
    elseif class == 'android.telephony.NetworkRegistrationInfo' then
        fake, restricted = networkregistrationinfo(result)
    elseif class == 'android.net.wifi.p2p.WifiP2pDeviceList' then
        fake, restricted = wifip2pdevicelist(result)
    elseif class == 'android.net.wifi.p2p.WifiP2pGroup' then
        fake, restricted = wifip2pgroup(result)
    else
        fake, restricted = networkinfo(result)
    end
    if not restricted then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
