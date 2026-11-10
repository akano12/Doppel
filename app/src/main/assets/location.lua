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

-- Of the extras of a fix of the GNSS (GnssLocationProvider), which tell nothing of the place
local KEPT = { satellites = true, meanCn0 = true, maxCn0 = true }

-- The location of a parcel, like of a location update, also of an extra of an intent,
-- becomes the location set, a coarse location or 0, 0. Only complete locations of the providers of the system,
-- since Android delivers complete locations only (Location.isComplete): a location the app made itself, like a place
-- picked on a map, passed between its screens, stays.
-- shortcut: a complete location the app made itself with a provider of the system, like a copy of one, is faked too;
-- fake at the calls of the location service when apps need such copies unchanged.
function after(hook, param)
    local result = param:getResult()
    if result == nil or not complete(result) or not fromsystem(param, result) then
        return false
    end

    -- A key of each app, new each boot (FakeIds), so apps cannot compute the offsets; they depend on the time of the
    -- fix only, so the same location read again, like from a parcel the app wrote, gives the same fake
    local key = (param:getSetting('location.key') or '') .. '/'
    local time = result:getTime()
    local type = param:getSetting('location.type')
    if type == 'coarse' then
        -- Meters, 10 km by default, see ActivityValues
        local grid = tonumber(param:getSetting('location.accuracy'))
        if grid == nil or grid <= 0 then
            grid = 10000
        end
        return coarse(param, result, key, time, grid)
    end

    local old = result:toString()
    local latitude = 0
    local longitude = 0
    if type == 'set' then
        -- Settings are text, Location.setLatitude needs a number
        latitude = tonumber(param:getSetting('location.latitude'))
        longitude = tonumber(param:getSetting('location.longitude'))
        if latitude == nil or longitude == nil then
            latitude = 0
            longitude = 0
        end
    end

    -- Within the accuracy, drifting slowly like the fixes of a device which stays where it is
    if result:hasAccuracy() and result:getAccuracy() > 0 then
        local north, east = drift(param, key .. 'fine', time, 120000, false)
        latitude, longitude = move(latitude, longitude, north * result:getAccuracy(), east * result:getAccuracy())
    end
    result:setLatitude(latitude)
    result:setLongitude(longitude)

    -- The altitude and the movement are of the real location: those of a device at sea level which stays where it is,
    -- with the same accuracies; the extras only with what tells nothing of the place, like the number of satellites
    if result:hasAltitude() then
        result:setAltitude(0)
    end
    if result.hasMslAltitude ~= nil and result:hasMslAltitude() then
        result:setMslAltitudeMeters(0)
    end
    if result:hasSpeed() then
        result:setSpeed(0)
    end
    for _, name in ipairs({ 'removeBearing', 'removeBearingAccuracy' }) do
        if result[name] ~= nil then
            result[name](result)
        end
    end
    local extras = result:getExtras()
    if extras ~= nil then
        local keys = extras:keySet():toArray()
        for index = 1, keys['length'] do
            if not KEPT[keys[index]] then
                extras:remove(keys[index])
            end
        end
        if extras:isEmpty() then
            result:setExtras(nil)
        end
    end

    return true, old, result
end

-- Like Android makes coarse locations (LocationFudger.createCoarse): moved by a secret offset of about a quarter of
-- the grid, which drifts slowly, then the center of the cell of the grid, without altitude, movement and extras, so a
-- change of cell does not tell when the real location crosses a line of the grid, and averaging does not reveal it
function coarse(param, result, key, time, grid)
    local latitude, longitude = snaptogrid(result:getLatitude(), result:getLongitude(), grid)
    -- Already coarse, like read again from a parcel the app wrote, since a real location is never a center
    if math.abs(latitude - result:getLatitude()) < 1e-9 and math.abs(longitude - result:getLongitude()) < 1e-9 then
        return false
    end
    local old = result:toString()

    local north, east = drift(param, key .. 'coarse', time, 7 * 24 * 3600 * 1000, true)
    latitude, longitude = move(result:getLatitude(), result:getLongitude(), north * grid / 4, east * grid / 4)
    latitude, longitude = snaptogrid(latitude, longitude, grid)
    result:setLatitude(latitude)
    result:setLongitude(longitude)
    -- Not more accurate than the grid
    result:setAccuracy(math.max(result:hasAccuracy() and result:getAccuracy() or 0, grid))

    -- The methods of newer versions are only there from that version
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

function complete(location)
    return location:hasAccuracy() and location:getTime() ~= 0 and location:getElapsedRealtimeNanos() ~= 0
end

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
    longitude = math.floor(longitude / lonstep + 0.5) * lonstep
    -- Centers within -180 and 180, so a center is its own center: the cells at the date line are wider
    if longitude >= 180 then
        longitude = longitude - lonstep
    elseif longitude < -180 then
        longitude = longitude + lonstep
    end
    if longitude >= 180 or longitude < -180 then
        longitude = (longitude + 180) % 360 - 180 -- Cells wider than the world near the poles
    end
    return latitude, longitude
end

-- An offset north and east, in units of the radius, at a time: between the points of the periods before and after,
-- smoothly, so it moves slowly and steadily; within the circle of the radius, or normally distributed (a standard
-- deviation of one) like the offsets of Android
function drift(param, seed, time, period, normal)
    local index = math.floor(time / period)
    local fraction = time / period - index
    fraction = fraction * fraction * (3 - 2 * fraction)
    local north1, east1 = point(param, seed .. index, normal)
    local north2, east2 = point(param, seed .. (index + 1), normal)
    return north1 + (north2 - north1) * fraction, east1 + (east2 - east1) * fraction
end

-- A point which looks random, the same for the same seed
function point(param, seed, normal)
    local uuid = param:uuidOf(seed)
    local u = tonumber(string.sub(uuid, 1, 8), 16) / 4294967296
    local v = tonumber(string.sub(uuid, 25, 32), 16) / 4294967296
    local radius = (normal and math.sqrt(-2 * math.log(1 - u)) or math.sqrt(u))
    return radius * math.sin(2 * math.pi * v), radius * math.cos(2 * math.pi * v)
end

-- Moved by meters north and east
function move(latitude, longitude, north, east)
    local latoff = north / 111000 -- degrees
    local lonoff = east / 111000

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
