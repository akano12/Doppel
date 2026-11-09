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
-- hasAttribute is false for a GPS tag and getAttributeRange gives nothing for it,
-- MediaMetadataRetriever no location of videos. The location columns of MediaStore (latitude, longitude), which
-- Android 10 and later leave empty, are empty on Android 8.1 and 9 too. Not covered are apps reading the bytes of
-- photos themselves.
local METADATA_KEY_LOCATION = 23
local COLUMNS = { 'latitude', 'longitude' }
local NULL = {}

-- A query of the media provider with the location columns: the cursor of the provider behind a proxy, which gives
-- the location columns like for a photo without a location (null) and the rest, like the notification URI, the
-- types and the moving, as it is
function query(param)
    local uri = param:getArgument(0)
    local result = param:getResult()
    local authority = (uri ~= nil and uri:getAuthority() or nil)
    -- Without the user, like 0@media (see contentresolver_query)
    if authority == nil or result == nil or string.gsub(authority, '^.*@', '') ~= 'media' then
        return false
    end
    local hidden = {}
    local any = false
    for _, name in ipairs(COLUMNS) do
        local index = result:getColumnIndex(name)
        if index >= 0 then
            hidden[index] = true
            any = true
        end
    end
    if not any then
        return false
    end
    -- The functions of the proxy get the arguments of the method only; the others call the method of the cursor.
    -- shortcut: a long passes Lua as a double, exact up to 2^53, like dates and ids of media; upgrade for larger ones
    local methods = {}
    for method, value in pairs({ getString = NULL, getBlob = NULL, getDouble = 0, getFloat = 0, getLong = 0,
                                 getInt = 0, getShort = 0, getType = 0, isNull = true }) do
        methods[method] = function(index, ...)
            if hidden[index] then
                return (value ~= NULL and value or nil)
            end
            return result[method](result, index, ...)
        end
    end
    -- CharArrayBuffer of a hidden column: empty, like for null
    methods.copyStringToBuffer = function(index, buffer)
        if hidden[index] then
            buffer.sizeCopied = 0
            return
        end
        result:copyStringToBuffer(index, buffer)
    end
    setmetatable(methods, { __index = function(_, method)
        -- The proxy itself for equals and hashCode, like any object
        if method == 'equals' or method == 'hashCode' then
            return nil
        end
        return function(...)
            return result[method](result, ...)
        end
    end })
    param:setResult(luajava.createProxy('android.database.Cursor', methods))
    return true
end

-- Whether a tag of EXIF is of the location, like GPSLatitude, GPSAltitude and GPSDateStamp
function gps(tag)
    return tag ~= nil and string.sub(tag, 1, 3) == 'GPS'
end

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end
    local method = hook:getMethodName()
    if method == 'query' then
        return query(param)
    end
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
    elseif method == 'hasAttribute' then
        -- hasAttribute of a GPS tag is false, like a tag which is not there
        local tag = param:getArgument(0)
        if not gps(tag) or result == false then
            return false
        end
        param:setResult(false)
        return true, tag, nil
    else
        -- getAttribute, getAttributeBytes, getAttributeInt, getAttributeDouble and getAttributeRange of a GPS tag:
        -- like a tag which is not there, null or the default
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
