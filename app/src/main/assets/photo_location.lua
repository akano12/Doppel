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
-- the location of photos for apps without ACCESS_MEDIA_LOCATION; apps with it get the original file.
-- ExifInterface of Android, of AndroidX and of the support library gives no GPS tags (like a photo without them),
-- hasAttribute is false for a GPS tag and getAttributeRange gives nothing for it, and the XMP of the photo has its
-- GPS properties blanked, like the media provider does; tags the app set on the object itself stay, and so do tags
-- the media provider zeroed already. MediaMetadataRetriever gives no location of videos. The location columns of
-- MediaStore (latitude, longitude), which Android 10 and later leave empty, are empty on Android 8.1 and 9 too.
-- Not covered are apps reading the bytes of photos themselves, and the range of the XMP (getAttributeRange).
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

-- Whether the app set the tags on this object itself (see setAttribute), like a photo it writes, which stay
function own(param, ...)
    local this = param:getThis()
    for _, tag in ipairs({ ... }) do
        if this == nil or param:getValue('exif.' .. tag, this) == nil then
            return false
        end
    end
    return true
end

-- Whether the value of a tag is zeroed already, like the media provider zeroes the bytes of the GPS tags for apps
-- without ACCESS_MEDIA_LOCATION, which ExifInterface then gives as 0/0 or empty: such tags stay like Android gives them
function zeroed(param, tag, value)
    if value == nil then
        local this = param:getThis()
        if this == nil then
            return false
        end
        value = param:unhooked(function()
            return this:getAttribute(tag)
        end)
    end
    return type(value) == 'string' and string.find(value, '^[0/,%s%z]*$') ~= nil
end

-- The XMP with the values of its GPS properties blanked by spaces, like the media provider does, so its length and
-- offsets stay: elements, like <exif:GPSLatitude>52,30.12N</exif:GPSLatitude>, and attributes, like
-- exif:GPSLatitude="52,30.12N"
-- shortcut: the exif prefix only, which Adobe and Android write; upgrade for other prefixes of its namespace
function blankxmp(text)
    local function spaces(value)
        -- A space for each character, not each byte of the UTF-8 of Lua
        return string.rep(' ', #(string.gsub(value, '[\128-\191]', '')))
    end
    text = string.gsub(text, '(<exif:GPS(%w+)[^>]*>)(.-)(</exif:GPS%2>)', function(open, _, value, close)
        return open .. spaces(value) .. close
    end)
    text = string.gsub(text, '(exif:GPS%w+%s*=%s*)(["\'])(.-)%2', function(name, quote, value)
        return name .. quote .. spaces(value) .. quote
    end)
    return text
end

-- The XMP of getAttribute (text) or getAttributeBytes (bytes, of which each is a character of ISO 8859-1)
function xmp(param, result)
    if type(result) == 'string' then
        local fake = blankxmp(result)
        return (fake ~= result and fake or nil)
    end
    local latin = luajava.bindClass('java.nio.charset.StandardCharsets').ISO_8859_1
    local text = luajava.newInstance('java.lang.String', result, latin)
    local fake = blankxmp(text)
    if fake == text then
        return nil
    end
    local buffer = latin:encode(fake)
    return luajava.bindClass('java.util.Arrays'):copyOf(buffer:array(), buffer:limit())
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
    if method == 'setAttribute' then
        -- A tag the app set, which reads back on this object; null removes it
        local tag = param:getArgument(0)
        if tag ~= nil and param:getThis() ~= nil then
            param:putValue('exif.' .. tag, param:getArgument(1) ~= nil and true or nil, param:getThis())
        end
        return false
    elseif method == 'extractMetadata' then
        if param:getArgument(0) ~= METADATA_KEY_LOCATION or result == nil then
            return false
        end
        param:setResult(nil)
        return true, result, nil
    elseif method == 'getLatLong' then
        -- getLatLong(float[]) fills the array and returns whether there is a location; getLatLong() of AndroidX
        -- returns the array of doubles or null
        if result == nil or result == false or own(param, 'GPSLatitude', 'GPSLongitude') then
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
        if result == nil or result == -1 or own(param, 'GPSDateStamp') then
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
        if result == default or own(param, 'GPSAltitude') then
            return false
        end
        param:setResult(default)
        return true, tostring(result), tostring(default)
    elseif method == 'hasAttribute' then
        -- hasAttribute of a GPS tag is false, like a tag which is not there
        local tag = param:getArgument(0)
        if not gps(tag) or result == false or own(param, tag) or zeroed(param, tag) then
            return false
        end
        param:setResult(false)
        return true, tag, nil
    else
        -- getAttribute, getAttributeBytes, getAttributeInt, getAttributeDouble and getAttributeRange of a GPS tag:
        -- like a tag which is not there, null or the default
        local tag = param:getArgument(0)
        if tag == 'Xmp' and result ~= nil and (method == 'getAttribute' or method == 'getAttributeBytes') and
                not own(param, tag) then
            fake = xmp(param, result)
            if fake == nil then
                return false
            end
            param:setResult(fake)
            return true, tag, nil
        end
        if not gps(tag) or own(param, tag) or zeroed(param, tag, method == 'getAttribute' and result or nil) then
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
