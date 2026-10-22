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

-- The usage events of the device tell which apps were used when, like the usage statistics.
-- The events are read from the result, so the app gets them as already read, like when there are none.

function after(hook, param)
    local events = param:getResult()
    if events == nil or not events:hasNextEvent() then
        return false
    end

    local event = luajava.newInstance('android.app.usage.UsageEvents$Event')
    while events:hasNextEvent() do
        events:getNextEvent(event)
    end
    return true
end
