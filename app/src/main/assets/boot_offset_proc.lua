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

-- The boot time is also in /proc/stat (btime, the second the device booted) and /proc/uptime (the seconds
-- since it booted). These are made to match the offset boot_offset adds to elapsedRealtime, so an app
-- reading both cannot tell them apart: btime drops the offset, uptime gains it. Also by another path
-- and by java.nio, like boot_id. shortcut: the offset is whole seconds here, so it can differ by under
-- a second from the millisecond clock, which does not matter at second resolution.
-- Wide, since the path is not normalized here, like /proc//stat or /proc/./uptime; opens checks the file
function filter(hook)
    return { '*/stat*', '*/uptime*' }
end

function after(hook, param)
    if param:getException() ~= nil or param:getResult() == nil then
        return false
    end
    local offset = tonumber(param:getSetting('value.boot_offset'))
    if offset == nil then
        return false
    end
    local seconds = math.floor(offset / 1000)

    if param:opens('/proc/stat') then
        local text = param:getFileText()
        if text == nil then
            return false
        end
        local new, n = string.gsub(text, '(btime%s+)(%d+)', function(pre, secs)
            return pre .. string.format('%d', tonumber(secs) - seconds)
        end)
        if n == 0 then
            return false
        end
        param:setFileText(new)
        return true

    elseif param:opens('/proc/uptime') then
        local text = param:getFileText()
        if text == nil then
            return false
        end
        -- The whole seconds only, the fraction stays as the kernel printed it (LuaJ ignores the precision of %f)
        local new, n = string.gsub(text, '^(%d+)', function(up)
            return string.format('%d', tonumber(up) + seconds)
        end, 1)
        if n == 0 then
            return false
        end
        param:setFileText(new)
        return true
    end

    return false
end
