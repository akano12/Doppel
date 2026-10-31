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

-- Network service discovery finds the devices in the local network, so discovering, resolving and following
-- services does not start. Android throws for stopping with a listener that never started, which would crash
-- the app, so stopping with a listener of a call that did not start does nothing as well.
function before(hook, param)
    local method = hook:getMethodName()
    if method == 'stopServiceDiscovery' or method == 'stopServiceResolution' or
            method == 'unregisterServiceInfoCallback' then
        local listener = param:getArgument(0)
        if listener == nil or not param:getValue('nsd.blocked', listener) then
            return false
        end
        param:putValue('nsd.blocked', nil, listener)
        param:setResult(nil)
        return false
    end

    -- The listener is the last argument of all variants
    local types = hook:getParameterTypes()
    local count = types['length']
    local listener = param:getArgument(count - 1)
    param:setResult(nil)
    if listener == nil then
        return true
    end
    param:putValue('nsd.blocked', true, listener)

    -- Android always answers, so apps can wait for the answer; the answer is a failure (FAILURE_INTERNAL_ERROR),
    -- like wifi_discovery, on the executor of the call, else posted to the main thread
    local answer
    if method == 'discoverServices' then
        local serviceType = param:getArgument(0)
        if types[1] == 'android.net.nsd.DiscoveryRequest' then
            serviceType = serviceType:getServiceType()
        end
        answer = function()
            listener:onStartDiscoveryFailed(serviceType, 0)
        end
    elseif method == 'resolveService' then
        local info = param:getArgument(0)
        answer = function()
            listener:onResolveFailed(info, 0)
        end
    elseif method == 'registerServiceInfoCallback' then
        answer = function()
            listener:onServiceInfoCallbackRegistrationFailed(0)
        end
    else
        return true
    end

    local runnable = luajava.createProxy('java.lang.Runnable', { run = answer })
    if types[count - 1] == 'java.util.concurrent.Executor' then
        param:getArgument(count - 2):execute(runnable)
    else
        local looper = luajava.bindClass('android.os.Looper'):getMainLooper()
        luajava.newInstance('android.os.Handler', looper):post(runnable)
    end
    return true
end
