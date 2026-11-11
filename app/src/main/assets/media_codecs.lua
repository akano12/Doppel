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
-- is the real one again, and a real name, which the faked list does not have, opens no codec, and the exceptions of
-- both name the codec the app asked for. The metrics of a codec have the fake name too, and vendor parameters, named
-- after the vendor, are not there (generic hooks). Not covered is native code (NDK AMediaCodec).
local KEPT = { android = true, google = true, ffmpeg = true, hw = true }

-- MediaCodec.MetricsConstants.CODEC, and MediaPlayer.MetricsConstants.CODEC_VIDEO and CODEC_AUDIO of the codecs
-- a player decoded with
local METRICS_CODECS = { 'android.media.mediacodec.codec', 'android.media.mediaplayer.video.codec',
                         'android.media.mediaplayer.audio.codec' }

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

-- The name the app asked createByCodecName for, where another one went to Android, per thread like the mapping
local ASKED = nil

-- All occurrences of a text, without patterns: the characters which are magic in patterns are escaped
function replace(text, old, new)
    return (string.gsub(text, (string.gsub(old, '%W', '%%%0')), (string.gsub(new, '%%', '%%%%'))))
end

function before(hook, param)
    if hook:getMethodName() ~= 'createByCodecName' then
        return false
    end
    ASKED = nil
    local name = param:getArgument(0)
    if name == nil then
        return false
    end
    local fakes, reals = codecs(param)
    -- A fake name opens the real codec. Not reported: the app opens the codec it was told about
    if reals[name] ~= nil then
        ASKED = name
        param:setArgument(0, reals[name])
        return false
    end
    -- A real name of a faked codec, also of its secure variant which Android 9+ finds by the name without .secure,
    -- is a codec the faked list does not have: Android gets the fake name, which no codec of the device has (see
    -- mapping), and fails like for a name of no codec.
    -- shortcut: Android 9+ would open a real codec named like a secure fake name without .secure, which only a device
    -- with codecs of the vendor hw has, upgrade when one does
    local stem = string.match(name, '^(.+)%.secure$')
    local fake = fakes[name] or (stem ~= nil and fakes[stem] ~= nil and fakes[stem] .. '.secure') or nil
    if fake == nil or fake == name then
        return false
    end
    ASKED = name
    param:setArgument(0, fake)
    return true
end

function after(hook, param)
    if hook:getMethodName() == 'createByCodecName' then
        local asked = ASKED
        ASKED = nil
        local ex = param:getException()
        if asked ~= nil and ex ~= nil and ex:getMessage() ~= nil then
            rename(param, ex, replace(ex:getMessage(), param:getArgument(0), asked))
        end
        return false
    end
    if param:getException() ~= nil then
        return false
    end
    local result = param:getResult()
    if result == nil then
        return false
    end
    -- MediaCodec.getMetrics and MediaPlayer.getMetrics, a new bundle per call, have the names among their values
    if hook:getMethodName() == 'getMetrics' then
        local old, new
        for _, key in ipairs(METRICS_CODECS) do
            local name = result:getString(key)
            local fake = (name ~= nil and fakeof(param, name) or nil)
            if fake ~= nil then
                result:putString(key, fake)
                old = old or name
                new = new or fake
            end
        end
        if old == nil then
            return false
        end
        return true, old, new
    end
    local fake = fakeof(param, result)
    if fake == nil then
        return false
    end
    param:setResult(fake)
    return true, result, fake
end

-- The fake name of a codec, nil for a name which stays
function fakeof(param, name)
    if fakename(name) == name then
        return nil
    end
    local fakes, reals = codecs(param)
    -- Already fake, like the name findDecoderForFormat and MediaCodec.getName get from the hooked getName and
    -- getCanonicalName they call: a numbered one (hw2) would else become the fake name of another codec
    if reals[name] ~= nil then
        return nil
    end
    return fakes[name] or fakename(name)
end

-- The exception Android threw with the name it was given, which its message has, like "Failed to initialize %s",
-- with the name the app asked for: the same class, stack trace and cause; an exception without a constructor with
-- a message, like MediaCodec.CodecException, gets the message itself, which only Java can set (see XParam)
function rename(param, ex, message)
    if message == ex:getMessage() then
        return
    end
    local ok, fake = pcall(function()
        local made = luajava.new(ex:getClass(), message)
        if made:getMessage() ~= message then
            error('No constructor with a message')
        end
        made:setStackTrace(ex:getStackTrace())
        if ex:getCause() ~= nil then
            made:initCause(ex:getCause())
        end
        return made
    end)
    if ok then
        param:setResult(fake)
    else
        pcall(function()
            param:setMessage(ex, message)
        end)
    end
end
