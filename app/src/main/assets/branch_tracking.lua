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

-- Branch compares the switch with its own field, not with isTrackingDisabled, which is hooked to say disabled, so the
-- app's disableTracking(false), also by setConsumerProtectionAttributionLevel with a level other than NONE, disables
-- tracking instead: the field, the state Branch stores and the callback of version 5.11 and later then say disabled
-- too, and Branch clears its session and referrer data like when a user disables it. The switch is the second
-- argument, after the context, in every version.
function before(hook, param)
    if param:getArgument(1) then
        return false
    end
    param:setArgument(1, true)
    return true
end
