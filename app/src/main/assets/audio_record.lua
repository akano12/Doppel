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

-- An AudioRecord the app starts does not record and gives silence, like a client Android silences from Android 10
-- (the microphone used by another app, or off by its privacy toggle): the state is recording and the reads give
-- zeros at the pace of the sample rate, a blocking read waiting for its frames. Skipping only the start made every
-- read return 0 at once, so recording loops spun. Calls Android refuses, like a start of a record which is not
-- initialized or a read with bad arguments, go to Android, which answers them without recording.
local STATE_INITIALIZED = 1
local RECORDSTATE_RECORDING = 3
local READ_NON_BLOCKING = 1
local ENCODING_PCM_FLOAT = 4
local SAMPLE_BYTES = { [2] = 2, [3] = 1, [4] = 4, [21] = 3, [22] = 4 } -- PCM 16 bit, 8 bit, float, 24 bit, 32 bit
local ELEMENT_BYTES = { byte = 1, short = 2, float = 4, buffer = 1 }

function before(hook, param)
    local record = param:getThis()
    local method = hook:getMethodName()
    if record:getState() ~= STATE_INITIALIZED then
        return false
    end

    if method == 'startRecording' then
        param:putValue('audio.start', clock(), record)
        param:putValue('audio.frames', 0, record)
        param:setResult(nil)
        return true
    elseif method == 'stop' then
        param:putValue('audio.start', nil, record)
        param:setResult(nil)
        return true
    end

    local start = param:getValue('audio.start', record)
    if start == nil then
        return false
    end
    if method == 'getRecordingState' then
        param:setResult(RECORDSTATE_RECORDING)
        return true
    end

    -- read: AudioRecord.read/<byte|short|float|buffer>[/mode]
    local kind = string.match(hook:getName(), '/(%a+)')
    local data = param:getArgument(0)
    local offset, size, mode
    if kind == 'buffer' then
        offset = 0
        size = param:getArgument(1)
        mode = (hook:getParameterTypes()['length'] == 3 and param:getArgument(2) or 0)
        if data == nil or not data:isDirect() or size < 0 then
            return false
        end
        -- Android writes from the start of the buffer, at most its capacity
        size = math.min(size, data:capacity())
    else
        offset = param:getArgument(1)
        size = param:getArgument(2)
        mode = (hook:getParameterTypes()['length'] == 4 and param:getArgument(3) or 0)
        if data == nil or offset < 0 or size < 0 or offset + size > data['length'] then
            return false
        end
    end
    local float = (record:getAudioFormat() == ENCODING_PCM_FLOAT)
    if (mode ~= 0 and mode ~= READ_NON_BLOCKING) or (kind == 'float') ~= float and kind ~= 'buffer' then
        return false
    end

    local frameBytes = (SAMPLE_BYTES[record:getAudioFormat()] or 2) * record:getChannelCount()
    local rate = record:getSampleRate()
    local wanted = math.floor(size * ELEMENT_BYTES[kind] / frameBytes)
    local frames = param:getValue('audio.frames', record)
    -- The frames the microphone gave since the start, of which the app read frames
    local available = math.floor((clock() - start) * rate / 1000) - frames
    local count = wanted
    if mode == READ_NON_BLOCKING then
        count = math.max(0, math.min(wanted, available))
    elseif available < wanted then
        local wait = math.ceil((wanted - available) * 1000 / rate)
        pcall(function()
            luajava.bindClass('java.lang.Thread'):sleep(wait)
        end)
    end
    param:putValue('audio.frames', frames + count, record)

    -- Whole frames only, like Android reads them
    local elements = math.floor(count * frameBytes / ELEMENT_BYTES[kind])
    if elements > 0 then
        if kind == 'buffer' then
            local zeros = luajava.bindClass('java.lang.reflect.Array'):newInstance(
                    luajava.bindClass('java.lang.Byte').TYPE, elements)
            local copy = data:duplicate()
            copy:clear()
            copy:put(zeros, 0, elements)
        else
            luajava.bindClass('java.util.Arrays'):fill(data, offset, offset + elements, 0)
        end
    end
    param:setResult(elements)
    return true
end

-- Milliseconds since the boot, which do not jump with the clock
function clock()
    return luajava.bindClass('android.os.SystemClock'):elapsedRealtime()
end
