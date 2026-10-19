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

-- The text of WifiInfo contains the network name, the address of the access point, the MAC and IP address
-- and the Passpoint provider; the extra info in the text of NetworkInfo is the network name or the APN.
-- Their getters are restricted, so the same fake values replace them in the text; the other fields stay.
-- Values are replaced between their label and the next label, because network names can contain commas.

local NONE = '<none>'

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

function wifiinfo(text)
    local restricted = false
    local changed

    -- The network name comes first, like the getter "private" with quotes
    if string.sub(text, 1, 6) == 'SSID: ' then
        text, changed = replace(text, 'SSID: ', ', BSSID: ', '"private"', { ['<unknown ssid>'] = true })
        restricted = restricted or changed
    end

    -- The access point, like the getter
    text, changed = replace(text, ', BSSID: ', ', ', '00:00:00:00:00:00', { [NONE] = true })
    restricted = restricted or changed

    -- Other addresses, like the MAC address and the addresses of multi-link access points,
    -- as Android shows them to apps without permission
    local replaced = 0
    text = string.gsub(text, '%x%x:%x%x:%x%x:%x%x:%x%x:%x%x', function(mac)
        if mac == '00:00:00:00:00:00' or mac == '02:00:00:00:00:00' then
            return nil
        end
        replaced = replaced + 1
        return '02:00:00:00:00:00'
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

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    local fake, restricted
    if hook:getClassName() == 'android.net.wifi.WifiInfo' then
        fake, restricted = wifiinfo(result)
    else
        fake, restricted = networkinfo(result)
    end
    if not restricted then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
