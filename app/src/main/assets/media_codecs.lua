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

-- The names of the codecs name the vendor of the chip, like c2.qti.avc.decoder (Qualcomm), c2.exynos.h264.decoder
-- (Samsung) and OMX.MTK.VIDEO.DECODER.AVC (MediaTek), which narrows down the model. The vendor becomes hw, like
-- c2.hw.avc.decoder; the codecs of Android itself (android, google) stay. What the codecs can do stays, since
-- players choose codecs by it. Media libraries get the real names, since they work around bugs of codecs by
-- their names (ExoPlayer, Media3); a fake name an app opens a codec with is the real one again.
-- Not covered is native code (NDK AMediaCodec).

local LIBRARIES = { 'com.google.android.exoplayer2.', 'androidx.media3.', 'androidx.media.' }

-- The fake name of a codec, the same for the same name, so that a fake name stays a fake name
function fakename(name)
    if name == nil then
        return nil
    end
    local first, vendor, rest = string.match(name, '^([^.]+)%.([^.]+)%.(.+)$')
    if first == nil or (first ~= 'c2' and first ~= 'OMX') then
        return name
    end
    local lower = string.lower(vendor)
    if lower == 'android' or lower == 'google' or lower == 'hw' then
        return name
    end
    return first .. '.hw.' .. rest
end

-- Whether a media library asks, which keeps the real names
function library()
    local frames = luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
    for index = 1, frames['length'] do
        local class = frames[index]:getClassName()
        for _, prefix in ipairs(LIBRARIES) do
            if string.sub(class, 1, string.len(prefix)) == prefix then
                return true
            end
        end
    end
    return false
end

-- The real name of a fake name, by the names of all codecs, else the name as it is
function realname(param, name)
    if fakename(name) ~= name then
        return name
    end
    local real = name
    param:unhooked(function()
        local list = luajava.newInstance('android.media.MediaCodecList', 1) -- ALL_CODECS
        local infos = list:getCodecInfos()
        for index = 1, infos['length'] do
            local candidate = infos[index]:getName()
            if candidate ~= name and fakename(candidate) == name then
                real = candidate
                break
            end
        end
    end)
    return real
end

function before(hook, param)
    -- MediaCodec.createByCodecName with a fake name
    if hook:getMethodName() ~= 'createByCodecName' then
        return false
    end
    local name = param:getArgument(0)
    if name == nil or not string.find(name, '.hw.', 1, true) then
        return false
    end
    local real = realname(param, name)
    if real == name then
        return false
    end
    param:setArgument(0, real)
    -- Not reported: the app opens the codec it was told about
    return false
end

function after(hook, param)
    if hook:getMethodName() == 'createByCodecName' or param:getException() ~= nil then
        return false
    end
    local result = param:getResult()
    local fake = fakename(result)
    if fake == nil or fake == result or library() then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end
