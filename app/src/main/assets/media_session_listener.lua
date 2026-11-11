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

-- The listener of the app is not added, so it never hears of the media sessions of other apps. The call still goes
-- to Android, with a listener which ignores the changes and is removed right after, so the app gets the exceptions
-- of Android: the SecurityException of an app which is neither a notification listener nor may control media, and
-- those of a missing listener, handler or executor. Android tells a new listener of the media key session at once,
-- on the executor of the app: the listener in between tells the app's listener that it is none (see
-- getMediaKeyEventSessionPackageName), an empty package from Android 14 and null on Android 13, like before any
-- session. Android posts it itself, so the executor runs once, like without the hook, and an exception of the app's
-- listener on an executor which runs at once leaves the call, like without the hook; the listener in between then
-- stays added, like the app's would.
-- shortcut: what Android 17 tells a new listener of the sessions of a package at once is unknown without its source,
-- so it is told nothing; check when the source is out.
function before(hook, param)
    local index = listenerIndex(hook)
    local listener = param:getArgument(index)
    if listener == nil then
        return false
    end
    local methods = {}
    if hook:getMethodName() == 'addOnMediaKeyEventSessionChangedListener' then
        local name = (luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 34 and '' or nil)
        local told = false
        -- Gets the session of Android, which the app's listener must not hear; only the first call, which Android
        -- posts at once, is passed on, since a listener in between which stays added would else tell the app's
        -- listener of each change of the session of other apps
        methods.onMediaKeyEventSessionChanged = function()
            if not told then
                told = true
                listener:onMediaKeyEventSessionChanged(name, nil)
            end
        end
    end
    local ignored = luajava.createProxy(hook:getParameterTypes()[index + 1], methods)
    param:putValue('media.listener', listener, ignored)
    param:setArgument(index, ignored)
    return true
end

function after(hook, param)
    local ignored = param:getArgument(listenerIndex(hook))
    local listener = (ignored ~= nil and param:getValue('media.listener', ignored) or nil)
    if listener == nil or param:getException() ~= nil then
        return false
    end
    local manager = param:getThis()
    manager['remove' .. string.sub(hook:getMethodName(), 4)](manager, ignored)
    return false
end

-- The index of the listener among the arguments, the first or the last
function listenerIndex(hook)
    local types = hook:getParameterTypes()
    for i = 1, types['length'] do
        if string.find(types[i], 'Listener', 1, true) then
            return i - 1
        end
    end
end
