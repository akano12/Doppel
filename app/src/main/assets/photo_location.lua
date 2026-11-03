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

-- Photos and videos keep where they were taken, which tells where someone lives, works and goes. Android removes
-- the location of photos for apps without ACCESS_MEDIA_LOCATION, unless they ask for the original
-- (MediaStore.setRequireOriginal): the app gets the photo without it then, like an app without the permission.
-- ExifInterface of Android, of AndroidX and of the support library gives no GPS tags (like a photo without them),
-- MediaMetadataRetriever no location of videos. Not covered are apps reading the bytes of photos themselves
-- and the location columns of MediaStore of Android 8.1 and 9.
local METADATA_KEY_LOCATION = 23

-- Whether a tag of EXIF is of the location, like GPSLatitude, GPSAltitude and GPSDateStamp
function gps(tag)
    return tag ~= nil and string.sub(tag, 1, 3) == 'GPS'
end

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end
    local method = hook:getMethodName()
    local result = param:getResult()
    local fake
    if method == 'setRequireOriginal' then
        -- The URI of the photo as it was, which the system gives without the location
        fake = param:getArgument(0)
        if fake == nil or result == nil or tostring(fake) == tostring(result) then
            return false
        end
        param:setResult(fake)
        return true, tostring(result), tostring(fake)
    elseif method == 'extractMetadata' then
        if param:getArgument(0) ~= METADATA_KEY_LOCATION or result == nil then
            return false
        end
        param:setResult(nil)
        return true, result, nil
    elseif method == 'getLatLong' then
        -- getLatLong(float[]) fills the array and returns whether there is a location; getLatLong() of AndroidX
        -- returns the array of doubles or null
        if result == nil or result == false then
            return false
        end
        if hook:getReturnType() == 'boolean' then
            param:setResult(false)
        else
            param:setResult(nil)
        end
        return true
    elseif method == 'getGpsDateTime' then
        -- -1 of Android, null of AndroidX, when there is no GPS time
        if result == nil or result == -1 then
            return false
        end
        if hook:getReturnType() == 'long' then
            param:setResult(-1)
        else
            param:setResult(nil)
        end
        return true
    elseif method == 'getAltitude' then
        local default = param:getArgument(0)
        if result == default then
            return false
        end
        param:setResult(default)
        return true, tostring(result), tostring(default)
    else
        -- getAttribute, getAttributeBytes, getAttributeInt and getAttributeDouble of a GPS tag: like a tag which
        -- is not there, null or the default
        local tag = param:getArgument(0)
        if not gps(tag) then
            return false
        end
        if method == 'getAttributeInt' or method == 'getAttributeDouble' then
            fake = param:getArgument(1)
        end
        if result == fake then
            return false
        end
        param:setResult(fake)
        return true, tag, nil
    end
end
