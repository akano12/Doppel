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

-- Hide that a VPN is in use

local TRANSPORT_VPN = 4
local NET_CAPABILITY_NOT_VPN = 15
local TYPE_VPN = 17
local TYPE_WIFI = 1

function isvpninterface(name)
    if name == nil then
        return false
    end
    for _, prefix in ipairs({ 'tun', 'ppp', 'tap', 'ipsec', 'wg', 'utun', 'l2tp', 'pptp', 'vpn' }) do
        if string.sub(name, 1, string.len(prefix)) == prefix then
            return true
        end
    end
    return false
end

function after(hook, param)
    local method = hook:getMethodName()
    local result = param:getResult()

    if method == 'hasTransport' then
        if result and param:getArgument(0) == TRANSPORT_VPN then
            param:setResult(false)
            return true, 'true', 'false'
        end

    elseif method == 'hasCapability' then
        if not result and param:getArgument(0) == NET_CAPABILITY_NOT_VPN then
            param:setResult(true)
            return true, 'false', 'true'
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
            local arrayClass = luajava.bindClass('java.lang.reflect.Array')
            local intClass = luajava.bindClass('java.lang.Integer')
            local fake = arrayClass:newInstance(intClass.TYPE, length + 1)
            for index = 1, length do
                fake[index] = result[index]
            end
            fake[length + 1] = NET_CAPABILITY_NOT_VPN
            param:setResult(fake)
            return true
        end

    elseif method == 'toString' then
        -- Apps search the text for VPN
        if result ~= nil and string.find(result, 'VPN', 1, true) then
            local fake = string.gsub(result, '|VPN', '')
            fake = string.gsub(fake, 'VPN|', '')
            fake = string.gsub(fake, 'Transports: VPN', 'Transports: WIFI')
            if not string.find(fake, 'NOT_VPN', 1, true) then
                fake = string.gsub(fake, 'Capabilities: ', 'Capabilities: NOT_VPN&', 1)
            end
            if fake ~= result then
                param:setResult(fake)
                return true, result, fake
            end
        end

    elseif method == 'getNetworkInfo' then
        if result ~= nil and param:getArgument(0) == TYPE_VPN then
            param:setResult(nil)
            return true
        end

    elseif method == 'getAllNetworkInfo' then
        if result ~= nil then
            local keep = {}
            for index = 1, result['length'] do
                if result[index]:getType() ~= TYPE_VPN then
                    keep[#keep + 1] = result[index]
                end
            end
            if #keep < result['length'] then
                local infoClass = luajava.bindClass('android.net.NetworkInfo')
                local arrayClass = luajava.bindClass('java.lang.reflect.Array')
                local fake = arrayClass:newInstance(infoClass, #keep)
                for index = 1, #keep do
                    fake[index] = keep[index]
                end
                param:setResult(fake)
                return true
            end
        end

    elseif method == 'getType' then
        if result == TYPE_VPN then
            param:setResult(TYPE_WIFI)
            return true, tostring(result), tostring(TYPE_WIFI)
        end

    elseif method == 'getTypeName' then
        if result == 'VPN' then
            param:setResult('WIFI')
            return true, result, 'WIFI'
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

    elseif method == 'getInterfaceName' then
        -- LinkProperties of the active network, like tun0
        if isvpninterface(result) then
            param:setResult('wlan0')
            return true, result, 'wlan0'
        end

    elseif method == 'getByName' or method == 'getByIndex' then
        if result ~= nil and isvpninterface(result:getName()) then
            param:setResult(nil)
            return true, result:getName(), nil
        end
    end

    return false
end
