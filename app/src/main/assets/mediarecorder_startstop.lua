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

-- Copyright 2017-2019 Marcel Bokhorst (M66B)
-- Copyright 2026 akano12

-- Recording with a restricted source (see mediarecorder_setsource) does not start, and the recorder answers like one
-- which gets no data: start, pause and resume return, and stop throws Android's error for a recording without data
-- (MPEG4Writer, AMRWriter, AACWriter and OggWriter report the empty track as malformed), after which the recorder
-- needs a reset; the writers of RTP, MPEG-2 TS and WebM stop without an error, so the output format is kept.
-- MPEG4Writer also reports each empty track to the error listener, see malformed.
-- Without data Android never reaches a maximum duration or file size either, so the info listener gets nothing.
-- StagefrightRecorder opens the camera at start, not at prepare, so with the camera as source start fails like on
-- a device without a camera (CameraSource initCheck, NO_INIT), whichever group restricts the recorder.
-- The real recorder stays prepared, so the state Android would have is kept here: start throws in another state
-- than prepared, and stop, pause and resume of a recorder which is not recording go to Android, which throws.
-- Each group acts for its own source only, so a group of the source reports and notifies; the hook which acts
-- skips the hooks of the other groups.
local KEYS = {
    ['Record.Audio'] = 'mediarecorder.audio',
    ['Record.Video'] = 'mediarecorder.video',
    ['Use.Camera'] = 'mediarecorder.camera'
}
local STATE = 'mediarecorder.state'
local FORMAT = 'mediarecorder.format'
local STOPS = {
    [7] = true, -- OUTPUT_FORMAT_RTP_AVP
    [8] = true, -- MPEG_2_TS
    [9] = true -- WEBM
}

function before(hook, param)
    local this = param:getThis()
    if this == nil then
        return false
    end

    -- A recorder is configured again after a reset, like a new one, so it can be reused with another source;
    -- the reset hooks of all groups run, because they do not skip the method
    local method = hook:getMethodName()
    if method == 'reset' then
        forget(param, this)
        return false
    end
    -- Android prepares the recorder and sets its output format itself, which after keeps
    if method == 'prepare' or method == 'setOutputFormat' then
        return false
    end

    local key = KEYS[hook:getGroup()]
    if key == nil or param:getValue(key, this) == nil then
        return false
    end

    local state = param:getValue(STATE, this)
    if method == 'start' then
        -- Android's start would record, so its refusal without a message (INVALID_OPERATION) is made here;
        -- the source is not used, so it is not reported.
        -- shortcut: only the prepare hooks tell that a recorder is prepared, so with all of them switched off for the
        -- app while a start hook is on, each start throws; fail soft if users are seen to switch them off alone
        if state ~= 'prepared' then
            param:setResult(luajava.newInstance('java.lang.IllegalStateException'))
            return false
        end
        if param:getValue(KEYS['Use.Camera'], this) ~= nil then
            param:putValue(STATE, 'failed', this)
            param:setResult(luajava.newInstance('java.lang.RuntimeException', 'start failed.'))
            return true
        end
        param:putValue(STATE, 'recording', this)
        param:setResult(nil)
        return true
    end

    if state ~= 'recording' then
        return false
    end
    -- shortcut: the output file stays empty, where Android's writers leave their header in it (like #!AMR or the
    -- ftyp box of MPEG-4), write the header if apps are seen to read the file of a failed recording
    if method == 'stop' then
        -- An output format the hooks did not see, like with them switched off, stops like before they were there
        local format = param:getValue(FORMAT, this)
        if format ~= nil and not STOPS[format] then
            -- DEFAULT, THREE_GPP and MPEG_4 are written by MPEG4Writer
            if format <= 2 then
                malformed(param, this)
            end
            param:putValue(STATE, 'failed', this)
            param:setResult(luajava.newInstance('java.lang.RuntimeException', 'stop failed.'))
            return true
        end
        -- After a stop the recorder can be set up again, which the real one, prepared still, would refuse
        forget(param, this)
        this:reset()
    end
    param:setResult(nil)
    return true
end

-- What Android did for the recorder, see before: it prepared it, so it can start, or set its output format
function after(hook, param)
    local this = param:getThis()
    if this == nil then
        return false
    end
    -- Android's own start, for a source no group restricts, leaves the recorder prepared no more
    local method = hook:getMethodName()
    if method == 'start' and param:getValue(STATE, this) == 'prepared' then
        param:putValue(STATE, nil, this)
    end
    if param:getException() ~= nil then
        return false
    end
    if method == 'prepare' then
        param:putValue(STATE, 'prepared', this)
    elseif method == 'setOutputFormat' then
        param:putValue(FORMAT, param:getArgument(0), this)
    end
    return false
end

-- MPEG4Writer reports each empty track as malformed (MEDIA_RECORDER_TRACK_ERROR_GENERAL of the number of the track
-- in the top four bits, ERROR_MALFORMED) while it stops, the video track first, as it was added first. Android posts
-- this on the recorder's own handler (postEventFromNative), which calls the error listener on its looper, and drops it
-- after a release or a reset, so it is posted there too.
-- shortcut: the hidden API rules give the handler (mEventHandler, max-target-p) only to apps for Android 9 and older,
-- newer ones get no onError, and only the tracks of restricted sources are known; upgrade by remembering the looper of
-- the constructor and hooking release, if apps are seen to check
function malformed(param, this)
    local handler = eventhandler(this)
    if handler == nil then
        return
    end
    local track = 0
    for _, key in ipairs({ KEYS['Record.Video'], KEYS['Record.Audio'] }) do
        if param:getValue(key, this) ~= nil then
            track = track + 1
            handler:sendMessage(handler:obtainMessage(100, track * 268435456 + 100, -1007))
        end
    end
end

-- The handler of the recorder, or nil. The fields are listed like LuaJ lists them, which Android does not report as a
-- use of a hidden API (see location), and which leaves out the fields the hidden API rules refuse.
function eventhandler(this)
    local ok, handler = pcall(function()
        -- Lua sees a Class as its static members, so the methods of Class get the class as the object
        local fields = luajava.bindClass('java.lang.Class').getDeclaredFields(luajava.bindClass('android.media.MediaRecorder'))
        for index = 1, fields['length'] do
            local field = fields[index]
            if field:getName() == 'mEventHandler' then
                field:setAccessible(true)
                return field:get(this)
            end
        end
        return nil
    end)
    if ok then
        return handler
    end
    return nil
end

function forget(param, this)
    for _, key in pairs(KEYS) do
        param:putValue(key, nil, this)
    end
    param:putValue(STATE, nil, this)
    param:putValue(FORMAT, nil, this)
end
