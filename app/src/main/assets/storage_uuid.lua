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

-- The ids of SD cards and USB drives stay the same across apps and devices they are used in.
-- They are replaced by random ids in the same format, different each time the app starts,
-- but the same while it runs, because apps compare them. Internal storage has no own id.
-- Note that the paths of these volumes, like /storage/1A2B-3C4D, include the real id,
-- because apps need the real path to access the files.

-- Each hexadecimal digit random, in the case of the value
function randomlike(value)
    local lower = (string.find(value, '%l') ~= nil)
    return (string.gsub(value, '%x', function()
        local digit = string.format('%X', math.random(0, 15))
        if lower then
            digit = string.lower(digit)
        end
        return digit
    end))
end

function after(hook, param)
    local result = param:getResult()
    if result == nil or param:getThis():isPrimary() then
        return false
    end

    -- Kept per hook for the lifetime of the app process
    local real = tostring(result)
    local fake = param:getValue(real, hook)
    if fake == nil then
        if hook:getMethodName() == 'getStorageUuid' then
            fake = luajava.bindClass('java.util.UUID'):randomUUID()
        else
            fake = randomlike(real)
        end
        param:putValue(real, fake, hook)
    end

    param:setResult(fake)
    return true, real, tostring(fake)
end
