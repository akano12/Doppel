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

-- The error of Android when the camera service refuses a disabled camera,
-- with the message of the camera service; the text becomes CAMERA_DISABLED (1): ...
function before(hook, param)
    local message = 'Camera "' .. tostring(param:getArgument(0)) .. '" disabled by policy'
    local exception = luajava.bindClass('android.hardware.camera2.CameraAccessException')
    local fake = luajava.new(exception, 1, message) -- 1=disabled
    param:setResult(fake)
    return true
end
