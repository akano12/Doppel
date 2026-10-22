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

-- Recording with a restricted source (see mediarecorder_setsource) does not start, pause, resume and stop;
-- Android throws for these methods when the recorder did not start, which would crash the app.
-- The hook of one group which skips the method skips the hooks of the other groups, so it handles all sources.
function before(hook, param)
    local this = param:getThis()
    if this == nil then
        return false
    end

    if param:getValue('source', this) == nil and param:getValue('camera', this) == nil then
        return false
    end

    -- A recorder can be reused with another source
    if hook:getMethodName() == 'stop' then
        param:putValue('source', nil, this)
        param:putValue('camera', nil, this)
    end

    param:setResult(nil)
    return true
end
