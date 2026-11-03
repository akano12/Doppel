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
-- hooked too. The processors of /proc/cpuinfo stay (see cpuinfo). Not covered is native code (sysconf, nproc).
local COUNT = 8

function after(hook, param)
    if param:getException() ~= nil then
        return false
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
