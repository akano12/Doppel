-- This file is part of XPrivacyLua.

-- XPrivacyLua is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.

-- XPrivacyLua is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.

-- You should have received a copy of the GNU General Public License
-- along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

-- Copyright 2017-2019 Marcel Bokhorst (M66B)

-- The errors of Android when the camera service refuses the camera, like when it is disabled
function before(hook, param)
    local message = 'Fail to connect to camera service'
    if hook:getMethodName() == 'getCameraInfo' then
        message = 'Fail to get camera info'
    end
    local fake = luajava.newInstance('java.lang.RuntimeException', message)
    param:setResult(fake)
    return true
end
