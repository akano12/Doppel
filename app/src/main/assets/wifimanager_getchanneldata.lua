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

-- The channel data of the last scan counts the access points on each channel, which tells the networks around the
-- device: the answer has no channels, like Android counts them without scan results, the same as getScanResults.
-- The request itself runs, so Android checks the executor, the callback and the permission like without the hook
-- (NullPointerException, SecurityException) and answers on the executor of the app.
function before(hook, param)
    local callback = param:getArgument(1)
    if param:getArgument(0) == nil or callback == nil then
        return false
    end
    param:setArgument(1, luajava.createProxy('java.util.function.Consumer', {
        accept = function(channels)
            callback:accept(luajava.newInstance('java.util.ArrayList'))
        end
    }))
    return true
end
