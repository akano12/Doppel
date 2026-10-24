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

-- Recording with a restricted source (see mediarecorder_setsource) does not start, pause, resume and stop;
-- Android throws for these methods when the recorder did not start, which would crash the app.
-- Each group skips for its own source only, so the group of the source reports and notifies; the hook
-- which skips the method skips the hooks of the other groups, so stopping forgets the sources of all groups.
local KEYS = {
    ['Record.Audio'] = 'mediarecorder.audio',
    ['Record.Video'] = 'mediarecorder.video',
    ['Use.Camera'] = 'mediarecorder.camera'
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

    local key = KEYS[hook:getGroup()]
    if key == nil or param:getValue(key, this) == nil then
        return false
    end

    -- Stopping resets the recorder too
    if method == 'stop' then
        forget(param, this)
    end

    param:setResult(nil)
    return true
end

function forget(param, this)
    for _, key in pairs(KEYS) do
        param:putValue(key, nil, this)
    end
end
