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

-- The location of a parcel, like of a location update, also of an extra of an intent,
-- becomes the location set, a coarse location or 0, 0. Only locations of the providers of the system: a location
-- the app made itself, like a place picked on a map, passed between its screens, stays.
function after(hook, param)
    local result = param:getResult()
    if result == nil or not fromsystem(param, result) then
        return false
    end

    local old = result:toString()

    local latitude = 0
    local longitude = 0
    local type = param:getSetting('location.type')
    local grid = nil
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
        grid = accuracy
    end

    if result:hasAccuracy() then
        local accuracy = result:getAccuracy()
        if accuracy > 0 then
            latitude, longitude = randomoffset(latitude, longitude, accuracy)
        end
    end

    result:setLatitude(latitude)
    result:setLongitude(longitude)
    -- A coarse location is not more accurate than its grid, like the coarse locations of Android, after the offset,
    -- so the grid result stays the same
    if grid ~= nil then
        result:setAccuracy(math.max(result:hasAccuracy() and result:getAccuracy() or 0, grid))
    end

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

-- The providers of Android and of Google Play services, and the others of the device
local PROVIDERS = { gps = true, network = true, fused = true, passive = true }

function fromsystem(param, location)
    local provider = location:getProvider()
    if provider == nil then
        return false
    end
    if PROVIDERS[provider] then
        return true
    end
    local ok, all = pcall(function()
        return param:getApplicationContext():getSystemService('location'):getAllProviders()
    end)
    return ok and all ~= nil and all:contains(provider)
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
