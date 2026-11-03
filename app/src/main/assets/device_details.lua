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

-- Small details of the device which together set it apart, with the values of a plain device:
-- - the heat (PowerManager): no thermal headroom known (NaN, like devices without the HAL), no throttling (0);
-- - the audio output: 48000 Hz and 256 frames per buffer, which most devices have; apps with low latency audio
--   size their buffers by the frames, which a multiple of the burst of the device keeps working;
-- - the vibrator: no amplitude control, effects and primitives unknown or not supported, no resonance (NaN),
--   which apps answer with a plain vibration;
-- - the certificates the user installed (AndroidCAStore, user: aliases), like of a VPN, proxy or ad blocker, are not
--   listed; which certificates connections trust stays.
local PROPERTIES = { ['android.media.property.OUTPUT_SAMPLE_RATE'] = '48000',
                     ['android.media.property.OUTPUT_FRAMES_PER_BUFFER'] = '256' }

-- The audio properties and the user certificates, so the script runs for these only (see XCore.getFilter)
function filter(hook)
    local method = hook:getMethodName()
    if method == 'getProperty' then
        local keys = {}
        for key, _ in pairs(PROPERTIES) do
            keys[#keys + 1] = key
        end
        return keys
    elseif method == 'containsAlias' or method == 'getCertificate' then
        return { 'user:*' }
    end
    return nil
end

-- A Java array of the length of another one, of a type, with one value
function filled(param, type, length, value)
    local array = luajava.bindClass('java.lang.reflect.Array'):newInstance(type, length)
    for index = 1, length do
        array[index] = value
    end
    return array
end

function after(hook, param)
    if param:getException() ~= nil then
        return false
    end
    local method = hook:getMethodName()
    local result = param:getResult()
    local fake
    if method == 'getThermalHeadroom' or method == 'getResonantFrequency' or method == 'getQFactor' then
        if result ~= result then
            return false
        end
        fake = 0 / 0
    elseif method == 'getCurrentThermalStatus' or method == 'areAllEffectsSupported' then
        -- THERMAL_STATUS_NONE, VIBRATION_EFFECT_SUPPORT_UNKNOWN
        fake = 0
    elseif method == 'getThermalHeadroomThresholds' then
        if result == nil or result:isEmpty() then
            return false
        end
        fake = luajava.newInstance('java.util.HashMap')
        param:setResult(fake)
        return true, tostring(result:size()), '0'
    elseif method == 'getProperty' then
        fake = PROPERTIES[param:getArgument(0)]
        if fake == nil or result == nil then
            return false
        end
    elseif method == 'hasAmplitudeControl' or method == 'areAllPrimitivesSupported' then
        fake = false
    elseif method == 'areEffectsSupported' or method == 'getPrimitiveDurations' then
        -- Of the effects asked for: unknown (0), of primitives: no duration (0)
        if result == nil then
            return false
        end
        param:setResult(filled(param, luajava.bindClass('java.lang.Integer').TYPE, result['length'], 0))
        return true
    elseif method == 'arePrimitivesSupported' then
        if result == nil then
            return false
        end
        param:setResult(filled(param, luajava.bindClass('java.lang.Boolean').TYPE, result['length'], false))
        return true
    elseif method == 'aliases' then
        -- The aliases of the certificates, without those of the user
        if result == nil then
            return false
        end
        local kept = luajava.newInstance('java.util.LinkedHashSet')
        local iterator = result:iterator()
        while iterator:hasNext() do
            local alias = iterator:next()
            if string.sub(alias, 1, 5) ~= 'user:' then
                kept:add(alias)
            end
        end
        if kept:size() == result:size() then
            return false
        end
        param:setResult(kept)
        return true, tostring(result:size()), tostring(kept:size())
    elseif method == 'userAliases' then
        if result == nil or result:isEmpty() then
            return false
        end
        param:setResult(luajava.newInstance('java.util.LinkedHashSet'))
        return true, tostring(result:size()), '0'
    elseif method == 'containsAlias' then
        fake = false
    elseif method == 'getCertificate' then
        fake = nil
    else
        return false
    end
    if result == fake then
        return false
    end
    param:setResult(fake)
    return true, tostring(result), tostring(fake)
end
