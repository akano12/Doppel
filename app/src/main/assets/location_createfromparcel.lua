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

function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end
    local old = result:toString()

    local latitude = 0
    local longitude = 0
    local type = param:getSetting('location.type')
    if type == 'set' then
        -- Settings are text, Location.setLatitude needs a number
        latitude = tonumber(param:getSetting('location.latitude'))
        longitude = tonumber(param:getSetting('location.longitude'))
        if latitude == nil or longitude == nil then
            latitude = 0
            longitude = 0
        end
    elseif type == 'coarse' then
        -- Meters, 10 km by default, see ActivityValues
        local accuracy = tonumber(param:getSetting('location.accuracy'))
        if accuracy == nil or accuracy <= 0 then
            accuracy = 10000
        end
        local clatitude = param:getValue('latitude', hook)
        local clongitude = param:getValue('longitude', hook)
        if clatitude == nil or clongitude == nil then
            clatitude, clongitude = randomoffset(result:getLatitude(), result:getLongitude(), accuracy)
            param:putValue('latitude', clatitude, hook)
            param:putValue('longitude', clongitude, hook)
        end
        latitude = clatitude
        longitude = clongitude
    end

    if result:hasAccuracy() then
        local accuracy = result:getAccuracy()
        if accuracy > 0 then
            latitude, longitude = randomoffset(latitude, longitude, accuracy)
        end
    end

    result:setLatitude(latitude)
    result:setLongitude(longitude)
    return true, old, result:toString()
end

function randomoffset(latitude, longitude, radius)
    local r = radius / 111000 -- degrees

    local w = r * math.sqrt(math.random())
    local t = 2 * math.pi * math.random()
    local lonoff = w * math.cos(t)
    local latoff = w * math.sin(t)

    -- A degree of longitude is shorter away from the equator; at the poles all longitudes meet
    local scale = math.cos(math.rad(latitude))
    if scale > 0.01 then
        lonoff = lonoff / scale
    end

    return latitude + latoff, longitude + lonoff
end
