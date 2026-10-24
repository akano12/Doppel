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

-- The location of a parcel, like of a location update, and the extra location of a bundle,
-- like of a location update by intent, become the location set, a coarse location or 0, 0
function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    -- Bundle.get: other extras with the same name can be of any type
    if hook:getMethodName() == 'get' and (param:getException() ~= nil or param:getArgument(0) ~= 'location' or
            not param:isInstance(result, 'android.location.Location')) then
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
        -- The center of a cell of a grid of this size, like Android snaps locations for apps with coarse location:
        -- the same for all hooks, processes and starts of the app, so averaging does not reveal the location
        latitude, longitude = snaptogrid(result:getLatitude(), result:getLongitude(), accuracy)
    end

    if result:hasAccuracy() then
        local accuracy = result:getAccuracy()
        if accuracy > 0 then
            latitude, longitude = randomoffset(latitude, longitude, accuracy)
        end
    end

    result:setLatitude(latitude)
    result:setLongitude(longitude)

    -- The altitude, the movement and the extras, like the number of satellites, belong to the real location;
    -- the methods of newer versions are only there from that version
    for _, name in ipairs({ 'removeAltitude', 'removeVerticalAccuracy', 'removeMslAltitude', 'removeMslAltitudeAccuracy',
                            'removeSpeed', 'removeSpeedAccuracy', 'removeBearing', 'removeBearingAccuracy' }) do
        if result[name] ~= nil then
            result[name](result)
        end
    end
    result:setExtras(nil)

    return true, old, result
end

function snaptogrid(latitude, longitude, size)
    local step = size / 111000 -- degrees
    latitude = math.max(-90, math.min(90, math.floor(latitude / step + 0.5) * step))

    -- A degree of longitude is shorter away from the equator, the cells of a row have the same width;
    -- at the poles all longitudes meet
    local scale = math.max(math.cos(math.rad(latitude)), 0.01)
    local lonstep = step / scale
    longitude = (math.floor(longitude / lonstep + 0.5) * lonstep + 180) % 360 - 180
    return latitude, longitude
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

    -- Valid coordinates, also near the poles and the date line, which apps can check
    latitude = math.max(-90, math.min(90, latitude + latoff))
    longitude = (longitude + lonoff + 180) % 360 - 180
    return latitude, longitude
end
