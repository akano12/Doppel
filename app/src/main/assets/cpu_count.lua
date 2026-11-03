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

-- The number of processors narrows down the chip, with their speeds and layout in /sys/devices/system/cpu, which
-- do not exist for the app (see hardware_files). It becomes 8, which most phones have: apps size their threads
-- by it, which more or fewer threads than processors do not break. Runtime.availableProcessors asks
-- Os.sysconf(_SC_NPROCESSORS_CONF), which compiled code of the framework can call without the hook, so it is
-- hooked too, and the processors possible, present and online of /sys/devices/system/cpu are 0-7, also by other
-- paths (see XParam.opens). The processors of /proc/cpuinfo are 8 too (see cpuinfo). Not covered are native code
-- (sysconf, nproc) and the folders of the processors (cpu0 and on), which stay as they are.
local COUNT = 8
local RANGE = '0-7\n'
local RANGES = { 'possible', 'present', 'online' }

function filter(hook)
    if hook:getMethodName() == 'open' then
        return { '*system/cpu/*' }
    end
    return nil
end

-- The files of the processors possible, present and online, as the range of 8
function ranges(param)
    if param:getResult() == nil then
        return false
    end
    local path = tostring(param:getArgument(0))
    for _, name in ipairs(RANGES) do
        if string.sub(path, -string.len(name) - 1) == '/' .. name and param:opens('/sys/devices/system/cpu/' .. name) then
            local text = param:getFileText()
            if text == RANGE then
                return false
            end
            param:setFileText(RANGE)
            return true, (string.gsub(text, '\n', '')), '0-7'
        end
    end
    return false
end

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end
    if hook:getMethodName() == 'open' then
        return ranges(param)
    end
    if hook:getMethodName() == 'sysconf' then
        local constants = luajava.bindClass('android.system.OsConstants')
        local name = param:getArgument(0)
        if name ~= constants._SC_NPROCESSORS_CONF and name ~= constants._SC_NPROCESSORS_ONLN then
            return false
        end
    end
    local result = param:getResult()
    if result == nil or result == COUNT then
        return false
    end
    param:setResult(COUNT)
    return true, tostring(result), tostring(COUNT)
end
