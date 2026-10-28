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

-- boot_offset adds the app's offset to the time since boot, so an alarm the app sets on that clock
-- (type ELAPSED_REALTIME_WAKEUP = 2 or ELAPSED_REALTIME = 3) would fire the offset too late. The alarm
-- service runs in the system with the real clock, so the offset is taken back off the trigger time here,
-- the second argument (set, setExact, setWindow, setRepeating and the like, which all start with type, time).
-- shortcut: assumes the trigger was computed from elapsedRealtime (the normal pattern); an absolute time
-- from another source would be shifted early by the offset.
function before(hook, param)
    local alarmtype = param:getArgument(0)
    if alarmtype ~= 2 and alarmtype ~= 3 then
        return false
    end
    local offset = tonumber(param:getSetting('value.boot_offset'))
    if offset == nil then
        return false
    end
    local trigger = param:getArgument(1)
    if trigger == nil then
        return false
    end
    param:setArgument(1, trigger - offset)
    return true, tostring(trigger), tostring(trigger - offset)
end
