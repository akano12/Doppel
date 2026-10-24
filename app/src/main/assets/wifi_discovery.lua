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

function before(hook, param)
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
        -- WifiP2pManager: the listener, the last argument of all variants, runs on the looper of the channel,
        -- which is not visible, and which is mostly the main looper
        local listener = param:getArgument(hook:getParameterTypes()['length'] - 1)
        if listener ~= nil then
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
