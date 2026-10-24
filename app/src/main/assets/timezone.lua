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

-- Copyright 2026 akano12

-- Fake time zone: setting value.timezone (like Europe/Amsterdam), default UTC

function fakeid(param)
    local id = param:getSetting('value.timezone')
    if id == nil or id == '' then
        id = 'UTC'
    end
    return id
end

function fakezone(param)
    return luajava.bindClass('java.util.TimeZone'):getTimeZone(fakeid(param))
end

-- TimeZone.setDefault: keep the fake zone when the app or the system
-- (on a time zone change) sets or resets the default
function before(hook, param)
    if hook:getMethodName() ~= 'setDefault' then
        return false
    end

    local id = fakeid(param)
    local zone = param:getArgument(0)
    if zone ~= nil and zone:getID() == id then
        return false
    end

    param:setArgument(0, fakezone(param))
    if zone == nil then
        return true, nil, id
    else
        return true, zone:getID(), id
    end
end

function after(hook, param)
    local method = hook:getMethodName()
    if method == 'setDefault' then
        return false
    end

    local result = param:getResult()
    local id = fakeid(param)
    if result ~= nil and result:getID() == id then
        return false
    end

    local fake
    if hook:getClassName() == 'android.icu.util.TimeZone' then
        fake = luajava.bindClass('android.icu.util.TimeZone'):getTimeZone(id)
    else
        -- getDefault, or the process default field used by Calendar and Date
        fake = fakezone(param)
    end
    param:setResult(fake)

    if result == nil then
        return true, nil, id
    else
        return true, result:getID(), id
    end
end
