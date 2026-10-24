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

-- The addresses of the device, the gateway, DNS and DHCP servers and the domains
-- reveal the network the device is on and help to fingerprint it;
-- the private DNS server name can contain the account id of the user at a DNS service.
-- Loopback addresses stay, because apps communicate with local services with them.

function after(hook, param)
    local method = hook:getMethodName()
    local result = param:getResult()
    if result == nil then
        return false
    end

    if method == 'getIpAddress' then
        if result == 0 then
            return false
        end
        param:setResult(0)
        return true

    elseif method == 'getDhcpInfo' then
        param:setResult(luajava.newInstance('android.net.DhcpInfo'))
        return true

    elseif method == 'getLinkAddresses' or method == 'getDnsServers' or method == 'getRoutes' then
        if result:isEmpty() then
            return false
        end
        param:setResult(luajava.newInstance('java.util.ArrayList'))
        return true

    elseif method == 'getLocalHost' then
        -- The address of the host name of the device, which is normally localhost
        if result:isLoopbackAddress() then
            return false
        end
        local fake = luajava.bindClass('java.net.InetAddress'):getLoopbackAddress()
        param:setResult(fake)
        return true, result:getHostAddress(), fake:getHostAddress()

    elseif method == 'getDomains' or method == 'getDhcpServerAddress' or method == 'getPrivateDnsServerName' then
        -- Like without domains, DHCP and private DNS with a server name (strict mode)
        param:setResult(nil)
        return true

    elseif method == 'getInetAddresses' or method == 'getInterfaceAddresses' then
        local collections = luajava.bindClass('java.util.Collections')
        local items
        if method == 'getInetAddresses' then
            items = collections:list(result):toArray()
        else
            items = result:toArray()
        end

        local list = luajava.newInstance('java.util.ArrayList')
        local removed = false
        for index = 1, items['length'] do
            local address = items[index]
            if method == 'getInterfaceAddresses' then
                address = address:getAddress()
            end
            if address ~= nil and address:isLoopbackAddress() then
                list:add(items[index])
            else
                removed = true
            end
        end

        -- The original enumeration is consumed here, so always set a new result
        if method == 'getInetAddresses' then
            param:setResult(collections:enumeration(list))
        else
            param:setResult(list)
        end
        return removed
    end

    return false
end
