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

-- The errors of Android when the camera service refuses the camera, like when it is disabled
local EACCES = 13
local ENODEV = 19
local EPERM = 1
local NORMAL_CONNECT = -2 -- the HAL version of a normal connect
local HAL_1_0 = 0x100
local HAL_3_0 = 0x300

-- Up to Android 10 the camera service checks the CAMERA permission before the id, and the legacy connect gives its
-- refusal as -EPERM, for which the constructor has no message of its own; Android 11 refuses an id it does not have
-- before the permission (shouldRejectSystemCameraConnection)
-- shortcut: on Android 9 and 10 an app in the background with the permission gets the -EACCES of a disabled camera,
-- also checked before the id; upgrade when an app is seen to tell the two apart
function refused(param)
    return luajava.bindClass('android.os.Build$VERSION').SDK_INT <= 29 and
            param:getApplicationContext():checkSelfPermission('android.permission.CAMERA') ~= 0
end

function before(hook, param)
    local method = hook:getMethodName()
    -- Without cameras (see getNumberOfCameras) Camera.open finds no camera facing back and returns null
    if method == 'open' and hook:getParameterTypes()['length'] == 0 then
        param:setResult(nil)
        return true
    end
    -- The setup of every constructor (cameraInit, cameraInitVersion up to Android 11) returns the error of the camera
    -- service for an id it does not have, from which the constructor throws Android's own exception: -EACCES of a
    -- normal connect, -ENODEV of the legacy connect of the other HAL versions up to Android 11, or its -EPERM above
    if method == 'cameraInit' or method == 'cameraInitVersion' then
        local errno = EACCES
        if method == 'cameraInitVersion' and param:getArgument(1) ~= NORMAL_CONNECT then
            errno = refused(param) and EPERM or ENODEV
        end
        param:setResult(-errno)
        return true
    end
    local message = 'Fail to connect to camera service'
    -- Android checks the id against the number of cameras first, which is 0, on every version from Android 8.1 to 17
    if method == 'getCameraInfo' then
        message = 'Unknown camera ID'
    end
    -- openLegacy first refuses a HAL version Android does not support (below 1.0 up to Android 11, below 3.0 later), so
    -- Android throws that itself; else it ends in the setup above, with the error of the legacy connect up to Android 11
    -- (the one for -EPERM is Unknown camera error) and of the normal connect later
    if method == 'openLegacy' then
        local legacy = luajava.bindClass('android.os.Build$VERSION').SDK_INT <= 30
        if param:getArgument(1) < (legacy and HAL_1_0 or HAL_3_0) then
            return false
        end
        if legacy then
            message = refused(param) and 'Unknown camera error' or 'Camera initialization failed'
        end
    end
    local fake = luajava.newInstance('java.lang.RuntimeException', message)
    param:setResult(fake)
    return true
end
