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

-- The events of the cells, which the app does not get (variant of Read.Network); the call state, of which the listener
-- gets the number of an incoming call empty, like apps without the permission of the call log (call_log).
-- The parameter is not named hook, the function to hook a method is.
function before(h, param)
    local events = param:getArgument(1)
    if string.match(h:getName(), '/(.*)$') == 'call_log' then
        local listener = param:getArgument(0)
        if listener == nil or bit32.band(events, 32) == 0 then -- call state
            return false
        end
        hook(listener, 'onCallStateChanged', nonumber)
        return true
    end

    local restricted = false
    -- Subtracted, not bit32.bxor, which makes the negative int of the highest bit a number an int cannot hold
    if bit32.band(events, 16) ~= 0 then -- cell location
        restricted = true
        events = events - 16
    end
    if bit32.band(events, 1024) ~= 0 then -- cell info
        restricted = true
        events = events - 1024
    end
    if restricted then
        param:setArgument(1, events)
    end
    return restricted
end

-- onCallStateChanged(state, number) of the listener, with the number empty
function nonumber(when, param)
    if when == 'before' then
        param:setArgument(1, '')
    end
end
