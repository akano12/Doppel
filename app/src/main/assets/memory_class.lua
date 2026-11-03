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

-- The memory an app may use (the heap of dalvik.vm.heapgrowthlimit and heapsize, which Android sets per model, like
-- 256 and 512 MB) is rounded down to a common step: an app plans for less memory than it may use, never for more.
-- ActivityManager.getMemoryClass and getLargeMemoryClass give megabytes, Runtime.maxMemory bytes; the properties
-- are like systemproperties_get (/resources), which a test compares.
local STEPS = { 128, 192, 256, 384, 512 }

-- Megabytes rounded down to a step; below the first step, the value as it is
function step(mb)
    local result = mb
    for _, value in ipairs(STEPS) do
        if mb >= value then
            result = value
        end
    end
    return result
end

function after(hook, param)
    local result = param:getResult()
    if result == nil or param:getException() ~= nil then
        return false
    end
    local fake
    if hook:getMethodName() == 'maxMemory' then
        local mb = math.floor(result / 1048576)
        -- Long.MAX_VALUE when there is no limit
        if mb >= 1048576 then
            return false
        end
        fake = step(mb) * 1048576
        if fake >= result then
            return false
        end
    else
        fake = step(result)
    end
    if fake == result then
        return false
    end
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
