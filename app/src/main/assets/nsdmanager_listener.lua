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
-- services does not start, and answers a failure like Android. Android keeps the listener of a call until it
-- answers, so until then stopping with it does nothing visible and starting with it again throws; after the answer
-- Android has forgotten the listener and throws for stopping with it, itself. Discovery by a network request starts.
-- shortcut: Android keeps the listeners of registerService, which is not hooked, in the same map, so an object which
-- is a listener of both and registered gets a failure where Android throws that it is in use, upgrade when apps do.
function before(hook, param)
    local method = hook:getMethodName()
    if method == 'stopServiceDiscovery' or method == 'stopServiceResolution' or
            method == 'unregisterServiceInfoCallback' then
        -- Android sends the stop to the service and ignores its answer, which comes after the failure
        local listener = param:getArgument(0)
        if listener ~= nil and param:getValue('nsd.blocked', listener) then
            param:setResult(nil)
            -- Every stop of a discovery by a network request answers that it stopped, the listener stays in use
            local serviceType = param:getValue('nsd.request', listener)
            if serviceType ~= nil then
                answerlater(param, param:getValue('nsd.executor', listener), function()
                    listener:onDiscoveryStopped(serviceType)
                end)
            end
        end
        return false
    end

    -- The listener is the last argument of all variants
    local types = hook:getParameterTypes()
    local count = types['length']
    local listener = param:getArgument(count - 1)
    local first = param:getArgument(0)

    -- Android checks the arguments before it asks the service, so for wrong ones the call goes on and Android throws
    -- its own exception, on every version: no listener, no type, service or discovery request, an empty type, another
    -- protocol than DNS-SD, no network request, or a service without a name or a type
    if listener == nil or first == nil then
        return false
    end
    if types[1] == 'java.lang.String' then
        if first == '' or param:getArgument(1) ~= 1 or
                (types[3] == 'android.net.NetworkRequest' and param:getArgument(2) == nil) then
            return false
        end
    elseif types[1] == 'android.net.nsd.NsdServiceInfo' then
        local name, kind = first:getServiceName(), first:getServiceType()
        if name == nil or name == '' or kind == nil or kind == '' then
            return false
        end
    end
    if param:getValue('nsd.blocked', listener) then
        param:setResult(luajava.newInstance('java.lang.IllegalArgumentException', 'listener already in use'))
        return true
    end
    param:setResult(nil)
    param:putValue('nsd.blocked', true, listener)

    local executor
    if types[count - 1] == 'java.util.concurrent.Executor' then
        executor = param:getArgument(count - 2)
    end

    -- Android always answers, so apps can wait for the answer; the answer is a failure (FAILURE_INTERNAL_ERROR),
    -- like wifi_discovery
    local answer
    if method == 'discoverServices' then
        local serviceType = first
        if types[1] == 'android.net.nsd.DiscoveryRequest' then
            serviceType = first:getServiceType()
        end
        if types[3] == 'android.net.NetworkRequest' then
            -- Android follows the networks of the request itself, without the service, so this discovery always
            -- starts, and Android never forgets its listener: every stop answers that it stopped (see above), and
            -- starting with the listener again throws.
            -- shortcut: an app without ACCESS_NETWORK_STATE gets the start where Android throws a SecurityException
            -- for the network callback, upgrade when apps call this without the permission.
            param:putValue('nsd.request', serviceType, listener)
            param:putValue('nsd.executor', executor, listener)
            answerlater(param, executor, function()
                listener:onDiscoveryStarted(serviceType)
            end)
            return true
        end
        answer = function()
            listener:onStartDiscoveryFailed(serviceType, 0)
        end
    elseif method == 'resolveService' then
        answer = function()
            listener:onResolveFailed(first, 0)
        end
    elseif method == 'registerServiceInfoCallback' then
        answer = function()
            listener:onServiceInfoCallbackRegistrationFailed(0)
        end
    else
        return true
    end

    answerlater(param, executor, answer, listener)
    return true
end

-- Android answers after the call returned, on the handler of the manager, and from there on the executor of the
-- call, if there is one; it forgets the listener of a failure on the handler, just before
function answerlater(param, executor, answer, forget)
    nsdhandler(param):post(luajava.createProxy('java.lang.Runnable', { run = function()
        if forget ~= nil then
            param:putValue('nsd.blocked', nil, forget)
        end
        if executor == nil then
            answer()
        else
            executor:execute(luajava.createProxy('java.lang.Runnable', { run = answer }))
        end
    end }))
end

-- The handler on which Android answers: the one of the manager. The hidden API rules refuse its field to most apps
-- (max-target-o up to Android 12, then the network module), and then a handler on the thread of the manager answers:
-- its own thread NsdManager up to the network module of Android 14, and the shared ConnectivityThread from the one of
-- Android 15, also on Android 13 and 14 with an updated module. The manager started its thread when it was made, and
-- ConnectivityThread also runs network callbacks, so NsdManager goes first. Only without either a thread of the app
-- with the name of the version answers.
-- shortcut: up to the module of Android 14 the managers of all contexts answer on the thread of one of them, upgrade
-- when apps compare the threads of two managers.
function nsdhandler(param)
    local handler = handlerfield('android.net.nsd.NsdManager', param:getThis())
    if handler == nil then
        local context = param:getApplicationContext()
        handler = param:getValue('nsd.handler', context)
        if handler == nil then
            local looper = threadlooper()
            if looper == nil then
                local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
                local thread = luajava.newInstance('android.os.HandlerThread', sdk >= 35 and 'ConnectivityThread' or 'NsdManager')
                thread:start()
                looper = thread:getLooper()
            end
            handler = luajava.newInstance('android.os.Handler', looper)
            param:putValue('nsd.handler', handler, context)
        end
    end
    return handler
end

-- The looper of the live thread NsdManager, else of ConnectivityThread, or nil; both are HandlerThreads
function threadlooper()
    local ok, looper = pcall(function()
        local threads = luajava.bindClass('java.lang.Thread'):getAllStackTraces():keySet():toArray()
        local shared
        for index = 1, threads['length'] do
            local thread = threads[index]
            local name = thread:getName()
            if name == 'NsdManager' then
                return thread:getLooper()
            elseif name == 'ConnectivityThread' then
                shared = thread
            end
        end
        return shared and shared:getLooper()
    end)
    if ok then
        return looper
    end
    return nil
end

-- The private handler (mHandler) of an object of the class, on which Android calls its listeners, or nil. The fields
-- are listed like LuaJ lists them, which Android does not report as a use of a hidden API (see location), and which
-- leaves out the fields the hidden API rules refuse. The same in wifi_discovery.
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
