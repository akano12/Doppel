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

-- The camera list of the app is empty, so every camera id is one the device does not have: the error of Android for
-- that, with the message of Android for the id the app asked for. Android words the message by version and method,
-- so it is taken once per process from the same method for an id no device has, which changes nothing; the methods
-- which need a callback take the message of getCameraCharacteristics.
local PROBE = '1234567' -- a number, since Android 8.1 and 9 parse the ids as numbers
local PROBES = {
    getTorchStrengthLevel = function(manager) return manager:getTorchStrengthLevel(PROBE) end,
    setTorchMode = function(manager) return manager:setTorchMode(PROBE, false) end,
    turnOnTorchWithStrengthLevel = function(manager) return manager:turnOnTorchWithStrengthLevel(PROBE, 1) end,
}

-- The errors of the camera service for an unknown id (ICameraService), which CameraManager throws with the error as
-- the cause: ERROR_ILLEGAL_ARGUMENT as IllegalArgumentException and ERROR_INVALID_OPERATION, the one of the torch
-- strength, as CameraAccessException with the reason CAMERA_ERROR
local ERROR_ILLEGAL_ARGUMENT, ERROR_INVALID_OPERATION = 3, 10

function before(hook, param)
    local method = hook:getMethodName()
    -- Android checks the id, the callback and the executor before it looks for the camera, so for a null one the method
    -- itself throws the error of Android, which no camera changes. The same for a null handler of openCamera on a thread
    -- without a looper; on a thread with one, Android opens the camera on its looper.
    if param:getArgument(0) == nil or (method == 'openCamera' and (param:getArgument(1) == nil or
            (param:getArgument(2) == nil and (hook:getName() == 'CameraManager2.openCamera/executor' or
                luajava.bindClass('android.os.Looper'):myLooper() == nil)))) then
        return false
    end

    local scope = param:getApplicationContext()
    local probe = PROBES[method]
    if probe == nil then
        method = 'getCameraCharacteristics'
        probe = function(manager) return manager:getCameraCharacteristics(PROBE) end
    end
    local thrown = param:getValue('camera.unknown.' .. method, scope)
    if thrown == nil then
        local manager = param:getThis()
        local ok, err = pcall(function()
            return param:unhooked(function()
                return probe(manager)
            end)
        end)
        thrown = (ok and '' or tostring(err))
        -- Only an error which names the probe id holds for the process; another, like of a camera service which is
        -- not up yet, is asked again at the next call
        if string.find(thrown, PROBE, 1, true) ~= nil then
            param:putValue('camera.unknown.' .. method, thrown, scope)
        end
    end

    -- The exception of the probe as its text has it; the reason of a CameraAccessException starts its message
    local reason = nil
    local message = string.match(thrown, 'java%.lang%.IllegalArgumentException: (.*)$')
    if message == nil then
        reason, message = string.match(thrown, 'android%.hardware%.camera2%.CameraAccessException: %u[%u_]* %((%d+)%): (.*)$')
    end
    message = message or ('Camera id ' .. PROBE .. ' does not match any currently connected camera device')

    -- The id is replaced by plain text, so ids with % or other pattern characters are safe
    local at, to = string.find(message, PROBE, 1, true)
    if at ~= nil then
        message = string.sub(message, 1, at - 1) .. tostring(param:getArgument(0)) .. string.sub(message, to + 1)
    end
    local result
    if reason == nil then
        result = luajava.newInstance('java.lang.IllegalArgumentException', message)
    else
        result = luajava.newInstance('android.hardware.camera2.CameraAccessException', tonumber(reason), message)
    end
    -- Only the camera service names the id, its ServiceSpecificException has the same text; CameraManager throws
    -- without a cause when the device has no camera service. The hidden API rules let every app make the class, a
    -- system API from Android 10 and on the light greylist of Android 9, where LuaJ takes the constructor from the
    -- list of the constructors of the class, which Android does not report as a use of a hidden API (see location). A
    -- failure to make it must not fail the hook, which would let the real call through.
    if at ~= nil then
        local ok, cause = pcall(luajava.newInstance, 'android.os.ServiceSpecificException',
            reason == nil and ERROR_ILLEGAL_ARGUMENT or ERROR_INVALID_OPERATION, message)
        if ok then
            result:initCause(cause)
        end
    end
    param:setResult(result)
    return true
end
