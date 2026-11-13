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

-- Speech recognition records the microphone, so listening does not start. Android always answers through the
-- listener of the recognizer, on the main thread, so apps waiting for the answer, like a button which shows it is
-- listening, get one. Without RECORD_AUDIO the recognition service answers ERROR_INSUFFICIENT_PERMISSIONS (9).
-- With it the answer is that of a silent microphone, like a started AudioRecord gives silence: ready for speech,
-- then no speech; a cancel or destroy before that ends the session without an answer, like Android, and destroy
-- drops the listener, so a later start reaches none until one is set again. A second start of the same recognizer
-- during the session is refused like the recognition service does, ERROR_RECOGNIZER_BUSY (8) up to Android 13 and
-- ERROR_CLIENT (5) for a listener already in a session from Android 14. A start of another recognizer of the app gets
-- 8 on every version: the recognition service takes one session at a time up to Android 11 and by default from
-- Android 14, and from Android 12 to 13 the system server does so for each app and service.
-- shortcut: here all recognizers of the app share one session, also those of another service (on device, or a
-- component given to createSpeechRecognizer), a recognition service may allow more sessions from Android 14
-- (getMaxConcurrentSessionsCount, not checked for the Google app), and a cancel by another recognizer is ignored
-- although Android 12 to 13 answer it ERROR_CLIENT (5); upgrade when a device shows a difference.
-- shortcut: what follows ready for speech comes from the recognizer app, not AOSP, and was not checked on a device:
-- here ERROR_SPEECH_TIMEOUT (6) after five seconds without onRmsChanged, and stopListening leaves it running; check
-- with the Google app, which may answer ERROR_NO_MATCH (7) or another delay, when a device with it is at hand.
-- shortcut: callers in the background or with the app op denied get 9 from Android 11, and a device without a
-- selected recognition service answers ERROR_CLIENT (5); both get the silent microphone here, upgrade when the
-- process state and the selected service are known to scripts.
-- The listener the app sets is kept weakly and read when the answer comes, like Android reads its current one: the
-- value of the recognizer would else keep the recognizer, the listener and its activity forever; the recognizer holds
-- the listener itself. The value of a session is only its Handler, for the same reason. Setting the listener, cancel
-- and destroy are not restricted.
function before(hook, param)
    local recognizer = param:getThis()
    local method = hook:getMethodName()
    if method == 'setRecognitionListener' then
        param:putValue('speech.listener',
                luajava.newInstance('java.lang.ref.WeakReference', param:getArgument(0)), recognizer)
        return false
    end

    -- The session is kept for its recognizer, which alone ends it, and for the app, which has one at a time
    local context = param:getApplicationContext()
    local session = param:getValue('speech.session', recognizer)
    if method == 'cancel' or method == 'destroy' then
        if session ~= nil then
            session:removeCallbacksAndMessages(nil)
            param:putValue('speech.session', nil, recognizer)
            param:putValue('speech.session', nil, context)
        end
        if method == 'destroy' then
            param:putValue('speech.listener', nil, recognizer)
        end
        return false
    end

    param:setResult(nil)
    local handler = luajava.newInstance('android.os.Handler', luajava.bindClass('android.os.Looper'):getMainLooper())
    local function answer(delay, callback, ends)
        handler:postDelayed(luajava.createProxy('java.lang.Runnable', {
            run = function()
                if ends then
                    param:putValue('speech.session', nil, recognizer)
                    param:putValue('speech.session', nil, context)
                end
                local reference = param:getValue('speech.listener', recognizer)
                local listener = (reference ~= nil and reference:get() or nil)
                if listener ~= nil then
                    callback(listener)
                end
            end
        }), delay)
    end

    if context:checkSelfPermission('android.permission.RECORD_AUDIO') ~= 0 then
        answer(0, function(listener) listener:onError(9) end)
    elseif session ~= nil then
        local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
        answer(0, function(listener) listener:onError(sdk >= 34 and 5 or 8) end)
    elseif param:getValue('speech.session', context) ~= nil then
        answer(0, function(listener) listener:onError(8) end)
    else
        param:putValue('speech.session', handler, recognizer)
        param:putValue('speech.session', handler, context)
        answer(0, function(listener) listener:onReadyForSpeech(luajava.newInstance('android.os.Bundle')) end)
        answer(5000, function(listener) listener:onError(6) end, true)
    end
    return true
end
