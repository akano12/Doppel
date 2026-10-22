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

-- The transports in the text of NetworkCapabilities by the type of NetworkInfo
local TRANSPORTS = { [0] = 'CELLULAR', [1] = 'WIFI', [7] = 'BLUETOOTH', [9] = 'ETHERNET' }

-- The fake values of a hook can call the same hook, see underlying; the Lua runtime of a hook is per thread
-- and is discarded after an error (see XCore), so this tells that the call is nested
local nested = {}

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

function after(hook, param)
    if nested.running then
        return false
    end
    nested.running = true
    local restricted, old, new = restrict(hook, param)
    nested.running = nil
    return restricted, old, new
end

function restrict(hook, param)
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

    elseif method == 'getTransportInfo' then
        -- Android 12 and later describe a VPN network with VpnTransportInfo, which is not in the SDK;
        -- Lua sees a Class as its static members, so the class name cannot be read from Lua (see XParam.isInstance),
        -- and Android 10 and 11 do not have the class, so finding it fails there
        local ok, vpn = pcall(param.isInstance, param, result, 'android.net.VpnTransportInfo')
        if ok and vpn then
            param:setResult(nil)
            return true, result, nil
        end

    elseif method == 'toString' and hook:getClassName() == 'android.net.LinkProperties' then
        -- The interface name of a VPN, also in the routes, like getInterfaceName;
        -- the name is a word, like in "-> 0.0.0.0 tun0 mtu"
        if result ~= nil then
            local name = string.match(result, 'InterfaceName: (%S+)')
            if isvpninterface(name) then
                local other = underlyinginterface(param)
                local fake = string.gsub(result, '([^%w])' .. string.gsub(name, '%p', '%%%0') .. '([^%w])',
                        function(before, after)
                            return before .. other .. after
                        end)
                param:setResult(fake)
                return true, result, fake
            end
        end

    elseif method == 'toString' then
        -- Apps search the text for VPN, like the name of the VpnTransportInfo
        if result ~= nil then
            local fake = string.gsub(result, ' TransportInfo: <VpnTransportInfo{[^}]*}>', '')
            if string.find(fake, 'VPN', 1, true) then
                fake = string.gsub(fake, '|VPN', '')
                fake = string.gsub(fake, 'VPN|', '')
                if string.find(fake, 'Transports: VPN', 1, true) then
                    local type = underlyingtype(param)
                    fake = string.gsub(fake, 'Transports: VPN', 'Transports: ' .. (TRANSPORTS[type] or 'WIFI'))
                end
                if not string.find(fake, 'NOT_VPN', 1, true) then
                    fake = string.gsub(fake, 'Capabilities: ', 'Capabilities: NOT_VPN&', 1)
                end
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
                if realtype(param, result[index]) ~= TYPE_VPN then
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
        -- The type of the network the VPN runs over, like the capabilities of the VPN
        if result == TYPE_VPN and not param:getValue('vpn.real', param:getThis()) then
            local fake = underlyingtype(param)
            param:setResult(fake)
            return true, tostring(result), tostring(fake)
        end

    elseif method == 'getTypeName' then
        if result == 'VPN' then
            local _, fake = underlyingtype(param)
            param:setResult(fake)
            return true, result, fake
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
            local fake = underlyinginterface(param)
            param:setResult(fake)
            return true, result, fake
        end

    elseif method == 'getByName' or method == 'getByIndex' or method == 'getByInetAddress' then
        -- The address of a socket connected through the VPN is of the VPN interface
        if result ~= nil and isvpninterface(result:getName()) then
            param:setResult(nil)
            return true, result:getName(), nil
        end
    end

    return false
end
