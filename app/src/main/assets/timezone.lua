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

-- Only the property areas run the script of the hooks of opening files (see XCore.getFilter)
function filter(hook)
    if hook:getMethodName() == 'open' then
        return { '/dev/__properties__/*' }
    end
    return nil
end

-- TimeZone.setDefault: the system resets the default with null, at the start and on a time zone change, which gives the
-- zone of the device, so null gives the fake zone. A zone the app sets itself stays, like on a device without Doppel,
-- and its id is kept for the getters, which give back the zone the app set, even the zone of the device (the app knows
-- that one already); any other zone is replaced, like the real zone the process had before the hooks.
function before(hook, param)
    if hook:getMethodName() ~= 'setDefault' then
        return false
    end

    local zone = param:getArgument(0)
    if zone ~= nil then
        param:putValue('timezone.app', zone:getID(), param:getApplicationContext())
        return false
    end

    param:putValue('timezone.app', nil, param:getApplicationContext())
    local id = fakeid(param)
    param:setArgument(0, fakezone(param))
    return true, nil, id
end

function after(hook, param)
    local method = hook:getMethodName()
    if method == 'setDefault' then
        return false
    elseif method == 'open' then
        -- The property of the zone in the property area apps can read as a file, like SystemProperties.get
        -- (systemproperties_get): timezone_prop from Android 14, before that with other properties
        local id = fakeid(param)
        local old = param:setPropertyValues({ 'persist.sys.timezone' }, { id })[1]
        if old == nil then
            return false
        end
        return true, old, id
    end

    local result = param:getResult()
    local id = fakeid(param)
    if result ~= nil and (result:getID() == id
            or result:getID() == param:getValue('timezone.app', param:getApplicationContext())) then
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
