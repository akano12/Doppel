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
-- the access point is hidden like Android does, see also network_tostring
function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    -- The name as bytes (Android 13), the same network as getSSID: unknown and empty names stay, like getSSID
    -- shows them as <unknown ssid>, the others become the fake name, else the two would tell different networks
    if hook:getMethodName() == 'getWifiSsid' then
        local text = result:toString()
        if text == '' or text == '<unknown ssid>' or text == '"private"' then
            return false
        end
        local name = 'private'
        local bytes = luajava.bindClass('java.lang.reflect.Array'):newInstance(luajava.bindClass('java.lang.Byte').TYPE, #name)
        for index = 1, #name do
            bytes[index] = string.byte(name, index)
        end
        param:setResult(luajava.bindClass('android.net.wifi.WifiSsid'):fromBytes(bytes))
        return true, text, '"private"'
    end

    -- The id of the saved network, -1 like Android shows it to apps without location permission
    if hook:getMethodName() == 'getNetworkId' then
        if result == -1 then
            return false
        end
        param:setResult(-1)
        return true, tostring(result), '-1'
    end

    local fake = '"private"'
    local hidden = '<unknown ssid>'
    if hook:getMethodName() == 'getBSSID' then
        fake = '02:00:00:00:00:00'
        hidden = fake
    end
    if result == hidden then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
