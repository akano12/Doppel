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

-- A Visualizer, which Android lets only apps with RECORD_AUDIO make, captures the audio the device plays, like a
-- recording of it, so the app gets the silence Android gives when nothing plays, also for its own players: a waveform
-- of 0x80 (unsigned 8 bit), an FFT of zeros and a peak and RMS of -9600 (-96 dB), the same from Android 8.1 to 17.
-- Calls Android refuses, like in the wrong state or without a measurement mode, keep their exception or error code.
local SUCCESS = 0
local SILENCE = { [0] = -128, [1] = 0 } -- of the waveform (PCM capture) and of the FFT capture, by event
local MINIMUM_LEVEL = -9600

-- The captures of the listener, which the capture thread of Android posts to it, are an array of silence of Doppel:
-- Android copies the next capture into its own array while the listener may not have read the last one yet. Like
-- Android's, there is one per Visualizer (the weak reference of the first argument, the same each time) and kind of
-- capture, replaced when the size changes and refilled for each capture, which with the same silence cannot race.
-- The last argument is the array, an Object up to Android 10; the event that the server died has none.
function before(hook, param)
    if hook:getMethodName() ~= 'postEventFromNative' then
        return false
    end
    local index = hook:getParameterTypes()['length'] - 1
    local event = param:getArgument(1)
    local fill = SILENCE[event]
    local data = param:getArgument(index)
    if fill == nil or data == nil then
        return false
    end
    local name = 'visualizer.silence.' .. event
    local scope = param:getArgument(0)
    local silence = param:getValue(name, scope)
    if silence == nil or silence['length'] ~= data['length'] then
        silence = luajava.bindClass('java.lang.reflect.Array'):newInstance(
                luajava.bindClass('java.lang.Byte').TYPE, data['length'])
        param:putValue(name, silence, scope)
    end
    luajava.bindClass('java.util.Arrays'):fill(silence, fill)
    param:setArgument(index, silence)
    return true
end

-- getWaveForm and getFft write as many bytes as the capture size, getMeasurementPeakRms the peak and RMS
function after(hook, param)
    local method = hook:getMethodName()
    if method == 'postEventFromNative' or param:getException() ~= nil or param:getResult() ~= SUCCESS then
        return false
    end
    if method == 'getMeasurementPeakRms' then
        local measurement = param:getArgument(0)
        measurement.mPeak = MINIMUM_LEVEL
        measurement.mRms = MINIMUM_LEVEL
    else
        local data = param:getArgument(0)
        local size = math.min(data['length'], param:getThis():getCaptureSize())
        luajava.bindClass('java.util.Arrays'):fill(data, 0, size, SILENCE[method == 'getWaveForm' and 0 or 1])
    end
    return true
end
