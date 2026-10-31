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

-- The switch is the last argument, like setAnalyticsCollectionEnabled(enabled) and
-- submitPolicyGrantResult(context, granted); also null of a Boolean, which can mean the default, like of the manifest,
-- which can be enabled
function before(hook, param)
    local index = hook:getParameterTypes()['length'] - 1
    local enabled = param:getArgument(index)
    if enabled == false then
        return false
    end
    param:setArgument(index, false)
    return true
end
