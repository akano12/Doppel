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

-- The time since boot (SystemClock.elapsedRealtime, and the process start on that clock,
-- Process.getStartElapsedRealtime) is the same for all apps, so the boot time, currentTimeMillis minus it,
-- is too, and links apps until the device restarts. Each app adds its own
-- offset (value.boot_offset, 10 to 120 minutes, new each boot), so it reads a boot time of its own.
-- The offset is added, not subtracted, so the clock stays positive. Durations (differences) are unchanged,
-- so timers keep working; alarms on this clock are corrected in boot_offset_alarm. uptimeMillis and the wall
-- clock are left alone (touching them breaks input events and TLS).
-- shortcut: elapsedRealtimeNanos loses precision past 2^53 ns (~104 days of uptime), good enough in practice.
function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end
    local offset = tonumber(param:getSetting('value.boot_offset'))
    if offset == nil then
        return false
    end
    if hook:getMethodName() == 'elapsedRealtimeNanos' then
        offset = offset * 1000000
    end
    local fake = result + offset
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
