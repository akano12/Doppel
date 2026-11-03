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

-- Speech recognition records the microphone, so listening does not start. Android always answers through the
-- listener of the recognizer, like with ERROR_INSUFFICIENT_PERMISSIONS (9) without the permission, so apps waiting
-- for the answer, like a button which shows it is listening, get this error, on the main thread like the answers
-- of Android. The listener the app sets is kept for this, weakly: the value of the recognizer would else keep the
-- recognizer, the listener and its activity forever; the recognizer holds the listener itself. Setting it is not
-- restricted.
function before(hook, param)
    local recognizer = param:getThis()
    if hook:getMethodName() == 'setRecognitionListener' then
        param:putValue('speech.listener',
                luajava.newInstance('java.lang.ref.WeakReference', param:getArgument(0)), recognizer)
        return false
    end

    param:setResult(nil)
    local reference = param:getValue('speech.listener', recognizer)
    local listener = (reference ~= nil and reference:get() or nil)
    if listener ~= nil then
        local runnable = luajava.createProxy('java.lang.Runnable', {
            run = function()
                listener:onError(9)
            end
        })
        local looper = luajava.bindClass('android.os.Looper'):getMainLooper()
        luajava.newInstance('android.os.Handler', looper):post(runnable)
    end
    return true
end
