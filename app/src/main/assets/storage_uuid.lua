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
-- The UUID of a volume is made of its fake id, like Android makes it of the real one, so the two agree.
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

-- The fake id of a volume for its real id, the same for both methods while the app runs
function fakeid(param, real)
    local scope = param:getApplicationContext()
    local fake = param:getValue('storage.uuid.' .. real, scope)
    if fake == nil then
        fake = randomlike(real)
        param:putValue('storage.uuid.' .. real, fake, scope)
    end
    return fake
end

-- Like StorageManager.convert: an id of FAT like 1A2B-3C4D after a fixed prefix, else the id itself
function storageuuid(id)
    local uuid = luajava.bindClass('java.util.UUID')
    if string.find(id, '^%x%x%x%x%-%x%x%x%x$') then
        return uuid:fromString('fafafafa-fafa-5afa-8afa-fafa' .. string.lower(string.gsub(id, '%-', '')))
    end
    local ok, result = pcall(function()
        return uuid:fromString(id)
    end)
    return (ok and result or uuid:randomUUID())
end

function after(hook, param)
    local result = param:getResult()
    local volume = param:getThis()
    if result == nil or volume:isPrimary() then
        return false
    end

    local fake
    if hook:getMethodName() == 'getStorageUuid' then
        -- The real id, without the hook of getUuid
        local id = param:unhooked(function()
            return volume:getUuid()
        end)
        if id == nil then
            return false
        end
        fake = storageuuid(fakeid(param, id))
    else
        fake = fakeid(param, result)
    end

    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
