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

-- Copyright 2017-2019 Marcel Bokhorst (M66B)
-- Copyright 2026 akano12

-- The values Android shows without location permission and when not connected stay, they hide the network already;
-- the others become one fake network, a name with its access point and id, because Android hides or shows the three
-- together, see also network_tostring. From Android 12 they become the values Android gives apps without the location
-- permission, like the Wi-Fi info of the connection and of the network capabilities has them (see wifishown of
-- network_address), so every Wi-Fi info agrees with those, also the one of a VPN over Wi-Fi, and with their texts.
-- shortcut: the fake access point and id are the same on every device, like the name, upgrade when the user can set them
local REDACT_FOR_ACCESS_FINE_LOCATION = 1

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end
    local redacted = luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 31

    -- The name as bytes (public from Android 13, hidden before, which apps reach by reflection), the same network as
    -- getSSID: unknown and empty names stay, like getSSID shows them as <unknown ssid>, the others become the hidden
    -- name, or before Android 12 the fake one, whose text has no quotes there, else the two would tell different networks
    if hook:getMethodName() == 'getWifiSsid' then
        local text = result:toString()
        if text == '' or text == '<unknown ssid>' then
            return false
        end
        -- The one of the copy Android makes without the location, which the Wi-Fi module of the device decides:
        -- WifiSsid.createFromHex(null) on Android 12, null from 13
        if redacted then
            local hidden = param:unhooked(function()
                return param:getThis():makeCopy(REDACT_FOR_ACCESS_FINE_LOCATION):getWifiSsid()
            end)
            param:setResult(hidden)
            return true, text, tostring(hidden)
        end
        if text == 'private' then
            return false
        end
        param:setResult(luajava.bindClass('android.net.wifi.WifiSsid'):createFromAsciiEncoded('private'))
        return true, text, 'private'
    end

    -- The id of the link of a Wi-Fi 7 access point, -1 like Android has it without multi-link operation
    -- (MloLink.INVALID_MLO_LINK_ID), which the hooks of its MLD address (null) and its links (empty) tell
    if hook:getMethodName() == 'getApMloLinkId' then
        if result == -1 then
            return false
        end
        param:setResult(-1)
        return true, tostring(result), '-1'
    end

    -- The id of the saved network, the first one, like that of a device with one saved network; -1, which Android
    -- shows when not connected and from Android 10 to apps without location permission, stays, but Android 8.1 and 9
    -- show the id to every app, so the fake is not -1 there either
    if hook:getMethodName() == 'getNetworkId' then
        if result == -1 then
            return false
        end
        local fake = redacted and -1 or 0
        param:setResult(fake)
        return true, tostring(result), tostring(fake)
    end

    -- The access point is a locally administered address, which belongs to no real access point, and the address
    -- Android shows to apps without location permission stays, like the zeros Android 8.1 shows when Wi-Fi is on but
    -- not connected, with <unknown ssid> and id -1
    local hidden = '<unknown ssid>'
    local fake = redacted and hidden or '"private"'
    if hook:getMethodName() == 'getBSSID' then
        if result == '00:00:00:00:00:00' then
            return false
        end
        hidden = '02:00:00:00:00:00'
        fake = redacted and hidden or 'a6:3e:91:5c:0d:72'
    end
    if result == hidden then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
