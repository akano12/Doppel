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
-- together, see also network_tostring.
-- shortcut: the fake access point and id are the same on every device, like the name, upgrade when the user can set them
function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    -- The name as bytes (public from Android 13, hidden before, which apps reach by reflection), the same network as
    -- getSSID: unknown and empty names stay, like getSSID shows them as <unknown ssid>, the others become the fake
    -- name, else the two would tell different networks; before Android 13 its text has no quotes
    if hook:getMethodName() == 'getWifiSsid' then
        local text = result:toString()
        if text == '' or text == '<unknown ssid>' or text == '"private"' or text == 'private' then
            return false
        end
        local name = 'private'
        local class = luajava.bindClass('android.net.wifi.WifiSsid')
        if luajava.bindClass('android.os.Build$VERSION').SDK_INT < 33 then
            param:setResult(class:createFromAsciiEncoded(name))
        else
            local bytes = luajava.bindClass('java.lang.reflect.Array'):newInstance(luajava.bindClass('java.lang.Byte').TYPE, #name)
            for index = 1, #name do
                bytes[index] = string.byte(name, index)
            end
            param:setResult(class:fromBytes(bytes))
        end
        return true, text, '"private"'
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
        param:setResult(0)
        return true, tostring(result), '0'
    end

    -- The access point is a locally administered address, which belongs to no real access point, and the address
    -- Android shows to apps without location permission stays, like the zeros Android 8.1 shows when Wi-Fi is on but
    -- not connected, with <unknown ssid> and id -1
    local fake = '"private"'
    local hidden = '<unknown ssid>'
    if hook:getMethodName() == 'getBSSID' then
        if result == '00:00:00:00:00:00' then
            return false
        end
        fake = 'a6:3e:91:5c:0d:72'
        hidden = '02:00:00:00:00:00'
    end
    if result == hidden then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
