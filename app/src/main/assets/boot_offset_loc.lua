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

-- A location fix carries the time since boot it was taken at (Location.getElapsedRealtimeNanos / Millis). boot_offset
-- shifts that clock by the app's offset, so without this the stamp (real) and the clock (shifted) disagree and a fresh
-- fix reads the offset old, which a maps app would drop as stale. The stamp is put on the same shifted clock (offset
-- added). The age methods (getElapsedRealtimeAgeMillis) then come out right on their own: they are the shifted clock
-- minus this shifted stamp, so the offsets cancel; hooking them as well would take the offset off twice.
-- An app that builds its own fix stamps it with the shifted clock (setElapsedRealtimeNanos(elapsedRealtimeNanos())),
-- so the offset is taken back off here, the first argument, and the field keeps the real time for the getter to shift.
function before(hook, param)
    -- The getters have no argument
    if hook:getMethodName() ~= 'setElapsedRealtimeNanos' then
        return false
    end
    local offset = tonumber(param:getSetting('value.boot_offset'))
    if offset == nil then
        return false
    end
    local ns = param:getArgument(0)
    if ns == nil then
        return false
    end
    local real = ns - offset * 1000000
    param:setArgument(0, real)
    return true, tostring(ns), tostring(real)
end

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end
    local offset = tonumber(param:getSetting('value.boot_offset'))
    if offset == nil then
        return false
    end
    if hook:getMethodName() == 'getElapsedRealtimeNanos' then
        offset = offset * 1000000
    end
    local fake = result + offset
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
