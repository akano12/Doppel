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
-- c2.hw.avc.decoder, numbered (hw2, hw3) where two codecs would get the same name, like OMX.SEC.avc.dec and
-- OMX.Exynos.avc.dec; the codecs of Android itself (android, google) and of FFmpeg stay. Codecs of vendors which
-- ExoPlayer and Media3 take for software by their name before Android 10 (OMX.SEC.*.sw.*, the HEVC software decoder
-- of Qualcomm) become google, which they take for software too. What the codecs can do stays, since players choose
-- codecs by it, and so does the end of a name, like .secure. Media libraries get the fake names too, which turns off
-- their workarounds of bugs of codecs of vendors by name (ExoPlayer, Media3); a fake name an app opens a codec with
-- is the real one again. Not covered is native code (NDK AMediaCodec).
local KEPT = { android = true, google = true, ffmpeg = true, hw = true }

-- Whether ExoPlayer and Media3 take a codec of a vendor for software by its name (MediaCodecUtil.isSoftwareOnly)
function software(name)
    local lower = string.lower(name)
    return (string.sub(lower, 1, 8) == 'omx.sec.' and string.find(lower, '.sw.', 1, true) ~= nil) or
            lower == 'omx.qcom.video.decoder.hevcswvdec'
end

-- The fake name of a codec by its name alone, the same for the same name
function fakename(name)
    if name == nil then
        return nil
    end
    local first, vendor, rest = string.match(name, '^([^.]+)%.([^.]+)%.(.+)$')
    if first == nil or (first ~= 'c2' and first ~= 'OMX') or KEPT[string.lower(vendor)] then
        return name
    end
    if software(name) then
        return 'OMX.google.' .. rest
    end
    return first .. '.hw.' .. rest
end

-- The fake names of the names of all codecs, real to fake and back: numbered where two would get the same one,
-- and never the name of a real codec
function mapping(names)
    local taken = {}
    for _, name in ipairs(names) do
        taken[name] = true
    end
    local fakes = {}
    local reals = {}
    for _, name in ipairs(names) do
        if fakes[name] == nil then
            local fake = fakename(name)
            if fake ~= name then
                local first, rest = string.match(fake, '^([^.]+)%.[^.]+%.(.+)$')
                local number = 1
                while taken[fake] do
                    number = number + 1
                    fake = first .. '.hw' .. number .. '.' .. rest
                end
                taken[fake] = true
                reals[fake] = name
            end
            fakes[name] = fake
        end
    end
    return fakes, reals
end

-- The mapping of the codecs of the device, once per thread (the script runs per thread)
local FAKES = nil
local REALS = nil

function codecs(param)
    if FAKES == nil then
        local ok, names = pcall(function()
            local names = {}
            param:unhooked(function()
                local infos = luajava.newInstance('android.media.MediaCodecList', 1):getCodecInfos() -- ALL_CODECS
                for index = 1, infos['length'] do
                    names[#names + 1] = infos[index]:getName()
                end
            end)
            return names
        end)
        if not ok then
            return {}, {}
        end
        FAKES, REALS = mapping(names)
    end
    return FAKES, REALS
end

function before(hook, param)
    -- MediaCodec.createByCodecName with a fake name
    if hook:getMethodName() ~= 'createByCodecName' then
        return false
    end
    local name = param:getArgument(0)
    if name == nil then
        return false
    end
    local _, reals = codecs(param)
    local real = reals[name]
    if real == nil then
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
    if result == nil or fakename(result) == result then
        return false
    end
    local fakes = codecs(param)
    local fake = fakes[result] or fakename(result)
    param:setResult(fake)
    return true, result, fake
end
