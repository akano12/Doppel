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

-- Discovering Wi-Fi Direct peers and services and Wi-Fi Aware services finds the devices nearby, so it does not
-- start. Android always answers, so apps can wait for the answer; the answer is a failure, like without permission,
-- posted like Android does, so an app which tries again on a failure does not call itself.
local ERROR = 0 -- WifiP2pManager.ERROR

-- Android 8.1-17 throw for a null channel, and the variants of Android 13 and later also for a device which does not
-- support them (UnsupportedOperationException, before the channel) and for a wrong frequency or a null config, before
-- they send anything; these calls run, so the app gets Android's own exception and nothing is discovered
function throws(method, param)
    if param:getArgument(0) == nil then
        return true
    end
    local manager = param:getThis()
    if method == 'discoverUsdBasedServices' then
        return not manager:isWiFiDirectR2Supported() or param:getArgument(1) == nil
    elseif method == 'discoverPeersOnSocialChannels' then
        return not manager:isChannelConstrainedDiscoverySupported()
    elseif method == 'discoverPeersOnSpecificFrequency' then
        return not manager:isChannelConstrainedDiscoverySupported() or param:getArgument(1) <= 0
    elseif method == 'startPeerDiscovery' then
        return not manager:isChannelConstrainedDiscoverySupported() or param:getArgument(1) == nil
    end
    return false
end

function before(hook, param)
    -- initialize starts nothing, its looper is kept in after
    if hook:getMethodName() == 'initialize' then
        return false
    end
    if hook:getMethodName() ~= 'subscribe' and throws(hook:getMethodName(), param) then
        return false
    end
    param:setResult(nil)

    local handler, answer
    if hook:getMethodName() == 'subscribe' then
        -- WifiAwareSession: the callback runs on the handler, or on the main thread without a handler
        local callback = param:getArgument(1)
        if callback ~= nil then
            handler = param:getArgument(2)
            answer = function()
                callback:onSessionConfigFailed()
            end
        end
    else
        -- WifiP2pManager: the listener, the last argument of all variants, runs on the handler of the channel, the
        -- first argument, which is on the looper the app gave to initialize (see after), else on the handler itself.
        -- shortcut: a channel not made by initialize, whose handler the hidden API rules refuse, answers on the main
        -- looper, upgrade when apps make channels another way.
        local listener = param:getArgument(hook:getParameterTypes()['length'] - 1)
        if listener ~= nil then
            local channel = param:getArgument(0)
            local looper = param:getValue('p2p.looper', channel)
            if looper ~= nil then
                handler = luajava.newInstance('android.os.Handler', looper)
            else
                handler = handlerfield('android.net.wifi.p2p.WifiP2pManager$Channel', channel)
            end
            answer = function()
                listener:onFailure(ERROR)
            end
        end
    end

    if answer ~= nil then
        if handler == nil then
            local looper = luajava.bindClass('android.os.Looper'):getMainLooper()
            handler = luajava.newInstance('android.os.Handler', looper)
        end
        handler:post(luajava.createProxy('java.lang.Runnable', { run = answer }))
    end
    return true
end

-- The looper the app gives to initialize, on which Android runs the listeners of the channel it returns, for before
function after(hook, param)
    if hook:getMethodName() == 'initialize' then
        local channel = param:getResult()
        if channel ~= nil then
            param:putValue('p2p.looper', param:getArgument(1), channel)
        end
    end
    return false
end

-- The private handler (mHandler) of an object of the class, on which Android calls its listeners, or nil. The fields
-- are listed like LuaJ lists them, which Android does not report as a use of a hidden API (see location), and which
-- leaves out the fields the hidden API rules refuse. The same in nsdmanager_listener.
function handlerfield(className, object)
    local ok, handler = pcall(function()
        -- Lua sees a Class as its static members, so the methods of Class get the class as the object
        local fields = luajava.bindClass('java.lang.Class').getDeclaredFields(luajava.bindClass(className))
        for index = 1, fields['length'] do
            local field = fields[index]
            if field:getName() == 'mHandler' then
                field:setAccessible(true)
                return field:get(object)
            end
        end
        return nil
    end)
    if ok then
        return handler
    end
    return nil
end
