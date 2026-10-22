-- This file is part of XPrivacyLua.

-- XPrivacyLua is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.

-- XPrivacyLua is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.

-- You should have received a copy of the GNU General Public License
-- along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

-- The text of network objects contains the values their getters hide, so the same fake values replace them
-- in the text; the other fields stay:
-- WifiInfo: the network name, the address of the access point, the MAC and IP address and the Passpoint provider
-- NetworkInfo: the extra info, which is the network name or the APN
-- LinkProperties: the addresses of the device, the DNS servers, the domains, the DHCP server and the routes
-- NetworkRegistrationInfo, also in the text of ServiceState: the cell
-- WifiP2pDeviceList: the nearby Wi-Fi Direct devices
-- Values are replaced between their label and the next label, because network names can contain commas.

local NONE = '<none>'
-- The address Android shows to apps without permission, also for the access point
local HIDDEN_MAC = '02:00:00:00:00:00'

-- Replaces the value after label up to nextLabel, unless it is one of the kept values
function replace(text, label, nextLabel, fake, kept)
    local _, last = string.find(text, label, 1, true)
    if last == nil then
        return text, false
    end
    local following = string.find(text, nextLabel, last + 1, true)
    if following == nil then
        return text, false
    end
    local value = string.sub(text, last + 1, following - 1)
    if value == fake or (kept ~= nil and kept[value]) then
        return text, false
    end
    return string.sub(text, 1, last) .. fake .. string.sub(text, following), true
end

-- Replaces the values after each label, like of the links stacked in LinkProperties, see replace
function replaceall(text, label, nextLabel, fake, kept)
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

    -- The network name comes first, like the getter "private" with quotes
    if string.sub(text, 1, 6) == 'SSID: ' then
        text, changed = replace(text, 'SSID: ', ', BSSID: ', '"private"', { ['<unknown ssid>'] = true })
        restricted = restricted or changed
    end

    -- The access point, like the getter
    text, changed = replace(text, ', BSSID: ', ', ', HIDDEN_MAC, { [NONE] = true })
    restricted = restricted or changed

    -- Other addresses, like the MAC address and the addresses of multi-link access points,
    -- as Android shows them to apps without permission
    local replaced = 0
    text = string.gsub(text, '%x%x:%x%x:%x%x:%x%x:%x%x:%x%x', function(mac)
        if mac == '00:00:00:00:00:00' or mac == HIDDEN_MAC then
            return nil
        end
        replaced = replaced + 1
        return HIDDEN_MAC
    end)
    restricted = restricted or replaced > 0

    -- The IP address, like the getter (0)
    text, changed = replace(text, ', IP: /', ', ', '0.0.0.0', nil)
    restricted = restricted or changed

    -- Passpoint networks
    text, changed = replace(text, ', FQDN: ', ', Provider friendly name: ', NONE, nil)
    restricted = restricted or changed
    text, changed = replace(text, ', Provider friendly name: ', ', Requesting package name: ', NONE, nil)
    restricted = restricted or changed

    -- The network key, the network name with the security type like "MyNet"psk, follows the requesting
    -- package without a label of its own and is followed by the MLO information or the next field
    local _, last = string.find(text, ', Requesting package name: ', 1, true)
    if last ~= nil then
        local package = string.match(text, '^' .. NONE, last + 1) or string.match(text, '^[%w_%.]+', last + 1)
        if package ~= nil then
            local first = last + string.len(package) + 1
            local following = string.find(text, 'MLO Information: ', first, true) or
                    string.find(text, ', ', first, true) or string.len(text) + 1
            local key = string.sub(text, first, following - 1)
            if key ~= '' and key ~= NONE then
                text = string.sub(text, 1, first - 1) .. NONE .. string.sub(text, following)
                restricted = true
            end
        end
    end

    return text, restricted
end

function networkinfo(text)
    -- The extra info is (none) like when the getter returns nothing, Android leaves it empty for most apps
    return replace(text, ', extra: ', ', failover: ', '(none)', { [''] = true })
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

    -- The domains are a list with spaces up to the MTU, which is always there, null like the getter
    text, changed = replaceall(text, 'Domains: ', ' MTU: ', 'null', nil)
    restricted = restricted or changed

    -- The DHCP server and the private DNS server name are null, like their getters;
    -- Android 10 and later leave them out then, Android 9 shows null
    text, changed = removesection(text, '( )ServerAddress: [^%s}]+ ?')
    restricted = restricted or changed
    if string.find(text, 'LinkAddresses: [ ', 1, true) then
        text, changed = removesection(text, '( )PrivateDnsServerName: [^%s}]+ ?')
    else
        text, changed = replaceall(text, 'PrivateDnsServerName: ', ' ', 'null', nil)
    end
    restricted = restricted or changed

    return text, restricted
end

function networkregistrationinfo(text)
    -- The cell, null like the getter and like Android shows it to apps without location permission
    return replaceall(text, ' cellIdentity=', ' voiceSpecificInfo=', 'null', nil)
end

function wifip2pdevicelist(text)
    -- The text of an empty list, like the getter
    return '', text ~= ''
end

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local fake, restricted
    local class = hook:getClassName()
    if class == 'android.net.wifi.WifiInfo' then
        fake, restricted = wifiinfo(result)
    elseif class == 'android.net.LinkProperties' then
        fake, restricted = linkproperties(result)
    elseif class == 'android.telephony.NetworkRegistrationInfo' then
        fake, restricted = networkregistrationinfo(result)
    elseif class == 'android.net.wifi.p2p.WifiP2pDeviceList' then
        fake, restricted = wifip2pdevicelist(result)
    else
        fake, restricted = networkinfo(result)
    end
    if not restricted then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
