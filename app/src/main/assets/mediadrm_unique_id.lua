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

-- Written in 2021 by LuigiVampa92, the author of the definition, for XPrivacyLua

function after(hook, param)
    local result = param:getResult()
    if result == nil or result.length == 0 then
        return false
    end

    -- The provisioning id of Widevine is a device id too, on newer devices the inverted bits of the device id
    local propertyString = param:getArgument(0)
    if propertyString ~= 'deviceUniqueId' and propertyString ~= 'provisioningUniqueId' then
        return false
    end

    -- A value of the service like the fake Android ID, per app and new each boot like it (see FakeIds): a constant, like
    -- zeros, would be one id which never changes, and libraries use this id when there is no GSF id; made of the Android
    -- ID an app could compute the id and tell it is fake. A fixed Android ID has no such value, so the id is made of it.
    local seed = param:getSetting('value.drm_id') or param:getSetting('value.android_id')
    local fake = fakeid(seed, 'deviceUniqueId', result.length)
    if propertyString == 'provisioningUniqueId' then
        -- Widevine of newer devices gives the inverted bits of the device id: the inverted fake device id then, which
        -- an app can check against it, else an id of its own
        local drm = param:getThis()
        local ok, device = pcall(function()
            return param:unhooked(function()
                return drm:getPropertyByteArray('deviceUniqueId')
            end)
        end)
        if ok and inverted(device, result) then
            -- The bytes are signed, like in Java, of which the inverted bits are -x - 1
            for index = 1, result.length do
                fake[index] = -fake[index] - 1
            end
        else
            fake = fakeid(seed, propertyString, result.length)
        end
    end
    param:setResult(fake)
    return true
end

-- The fake id of a property, made of the seed, zeros only if the boot values could not be read
function fakeid(seed, property, length)
    if seed == nil then
        local arrayClass = luajava.bindClass('java.lang.reflect.Array')
        return arrayClass:newInstance(luajava.bindClass('java.lang.Byte').TYPE, length)
    end
    local digest = luajava.bindClass('java.security.MessageDigest'):getInstance('SHA-256')
    return luajava.bindClass('java.util.Arrays'):copyOf(digest:digest(seed .. property), length)
end

-- Whether two ids are the inverted bits of each other: signed bytes of which each pair adds up to -1
function inverted(a, b)
    if a == nil or b == nil or a.length ~= b.length or a.length == 0 then
        return false
    end
    for index = 1, a.length do
        if a[index] + b[index] ~= -1 then
            return false
        end
    end
    return true
end
