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

-- The version of the camera hardware, which makers may set from Android 9 (CameraCharacteristics.INFO_VERSION),
-- names the image processor and the sensors, which tell the model like Build.MODEL does, and apps read it without the
-- permission of the camera. It is not set, like on the many devices which do not set it: get gives null and getKeys
-- does not list it. The key is found by its name, since from Android 10 apps can make keys of their own, of any type:
-- these give null too, also where the real call threw.
-- Its tag also leaves the hidden list of the available keys (android.request.availableCharacteristicsKeys, which
-- getKeys reads and an app reads by a key it makes by name), as Android rewrites this list when it removes keys
-- itself (CameraMetadata::removePermissionEntries): a listed key without a value would tell it was removed.
-- shortcut: the sizes of the sensor and its pixel array and the focal lengths of the lens narrow down the model too;
-- they stay, since camera apps set up their captures by them, upgrade to the values of a common sensor if trackers
-- use them.
-- shortcut: native code (ACameraMetadata of the NDK) asks the camera service itself and gets the real version,
-- upgrade with native hooks.
local VERSION = 'android.info.version'
local KEYS = 'android.request.availableCharacteristicsKeys'
-- ANDROID_INFO_VERSION: section ANDROID_INFO (21) << 16, plus 1, fixed by the camera HAL
local VERSION_TAG = 0x150001

function after(hook, param)
    local exception = param:getException()
    local result = param:getResult()

    if hook:getMethodName() == 'get' then
        local key = param:getArgument(0)
        local name = key ~= nil and key:getName()
        -- A key of the version's name with a type Android cannot read it as (like Long) throws only when the version
        -- is set: without it CameraMetadataNative.getBase gives null before it looks for a marshaler of the type
        if name == VERSION then
            if exception == nil and result == nil then
                return false
            end
            param:setResult(nil)
            return true, result
        end
        if exception ~= nil or result == nil or name ~= KEYS then
            return false
        end
        -- Android's own key gives an int[], a key the app makes with Integer[] an Integer[]
        local boxed = param:isInstance(result, '[Ljava.lang.Integer;')
        if not boxed and not param:isInstance(result, '[I') then
            return false
        end
        local tags = {}
        for index = 1, result['length'] do
            if result[index] ~= VERSION_TAG then
                tags[#tags + 1] = result[index]
            end
        end
        if #tags == result['length'] then
            return false
        end
        local integer = luajava.bindClass('java.lang.Integer')
        local fake = luajava.bindClass('java.lang.reflect.Array'):newInstance(boxed and integer or integer.TYPE, #tags)
        for index = 1, #tags do
            fake[index] = tags[index]
        end
        param:setResult(fake)
        return true, tostring(result['length']), tostring(#tags)
    end

    -- getKeys, for when its own call of get does not reach the hook (inlined), else the list has no version already:
    -- Android makes the list once per object, so the one without the version is kept for the object too
    if exception ~= nil or result == nil then
        return false
    end
    local characteristics = param:getThis()
    local fake = param:getValue('camera.keys', characteristics)
    if fake == nil then
        local kept = luajava.newInstance('java.util.ArrayList')
        for index = 0, result:size() - 1 do
            local key = result:get(index)
            if key:getName() ~= VERSION then
                kept:add(key)
            end
        end
        if kept:size() == result:size() then
            return false
        end
        fake = luajava.bindClass('java.util.Collections'):unmodifiableList(kept)
        param:putValue('camera.keys', fake, characteristics)
    end
    param:setResult(fake)
    return true, tostring(result:size()), tostring(fake:size())
end
