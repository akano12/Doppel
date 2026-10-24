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
-- Copyright 2026 akano12

-- The recorder of a restricted source does not start, see mediarecorder_startstop,
-- which skips the methods of a recorder for the source of the group of the hook
local VIDEO_SOURCE_DEFAULT = 0
local VIDEO_SOURCE_CAMERA = 1

function before(hook, param)
    local this = param:getThis()
    if this == nil then
        return false
    end

    local source = param:getArgument(0)

    -- With the camera as source the media server opens the camera for the app (Use camera), also by default;
    -- the images of a surface source come from the app, like of the screen
    if hook:getGroup() == 'Use.Camera' then
        if source == VIDEO_SOURCE_DEFAULT or source == VIDEO_SOURCE_CAMERA then
            param:putValue('mediarecorder.camera', source, this)
        else
            param:putValue('mediarecorder.camera', nil, this)
        end
        return false
    end

    if hook:getMethodName() == 'setAudioSource' then
        param:putValue('mediarecorder.audio', source, this)
    else
        param:putValue('mediarecorder.video', source, this)
    end
    return false
end
