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
    local listener = param:getArgument(hook:getParameterTypes()['length'] - 1)
    if listener ~= nil then
        param:putValue('nsd.blocked', true, listener)
    end
    param:setResult(nil)
    return true
end
