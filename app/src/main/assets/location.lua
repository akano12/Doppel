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
-- Also the HWLocation of HMS Location Kit of Huawei, which has extra info instead of extras: HMS makes it in the app
-- of what HMS Core or its own location service answers, which the checks of the providers do not fit, and the ones of
-- its LocationResult, of a parcel, like of a PendingIntent of updates, and of LocationResult.create, which the parsers
-- of its updates end in (see hooks.json), so the Location copies HMS makes of them (LocationJsonUtil.convertLocation)
-- agree; the places the geocoder of HMS answers stay, like those of Android.
function after(hook, param)
    local class = hook:getClassName()
    if class == 'android.content.Intent' then
        -- Android gives an intent to a component with the class loader of the component; else that of the app
        if hook:getMethodName() == 'setExtrasClassLoader' then
            return intentafter(param, param:getThis(), param:getArgument(0), true)
        end
        return intentafter(param, param:getResult(), param:getApplicationContext():getClassLoader(), false)
    end
    local result = param:getResult()
    if result == nil then
        return false
    end
    if class == 'android.location.Location' then
        if not complete(result) or not fromsystem(param, result) then
            return false
        end
        return fake(param, result, false)
    end
    if hook:getMethodName() == 'parseLocationFromJsonObject' then
        if geocoded() then
            return false
        end
        return fake(param, result, true)
    end
    -- R8 can merge the creator of LocationResult with those of other classes of HMS in an app without the rule Huawei
    -- documents (-keep class com.huawei.hms.**{*;}), so the objects of those, like an HWLocation of the geocoder the
    -- app parcels, stay as they are
    if not param:isInstance(result, 'com.huawei.hms.location.LocationResult') then
        return false
    end
    local restricted = false
    local locations = result:getHWLocationList()
    for index = 0, (locations and locations:size() or 0) - 1 do
        restricted = fake(param, locations:get(index), true) or restricted
    end
    return restricted
end

-- The location set, coarse or 0, 0, in place
function fake(param, result, hms)
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
        return coarse(param, result, key, time, grid, hms)
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
    if (hms or result:hasAccuracy()) and result:getAccuracy() > 0 then
        local north, east = drift(param, key .. 'fine', time, 120000, false)
        latitude, longitude = move(latitude, longitude, north * result:getAccuracy(), east * result:getAccuracy())
    end
    result:setLatitude(latitude)
    result:setLongitude(longitude)

    -- The altitude and the movement are of the real location: those of a device at sea level which stays where it is,
    -- with the same accuracies; the extras only with what tells nothing of the place, like the number of satellites
    if hms or result:hasAltitude() then
        result:setAltitude(0)
    end
    if result.hasMslAltitude ~= nil and result:hasMslAltitude() then
        result:setMslAltitudeMeters(0)
    end
    if hms or result:hasSpeed() then
        result:setSpeed(0)
    end
    for _, name in ipairs({ 'removeBearing', 'removeBearingAccuracy' }) do
        if result[name] ~= nil then
            result[name](result)
        end
    end
    local extras
    if hms then
        result:setBearing(0)
        result:setBearingAccuracyDegrees(0)
        noaddress(result)
        extras = result:getExtraInfo()
    else
        extras = result:getExtras()
    end
    if extras ~= nil then
        local keys = extras:keySet():toArray()
        for index = 1, keys['length'] do
            if not KEPT[keys[index]] then
                extras:remove(keys[index])
            end
        end
        if extras:isEmpty() and not hms then
            result:setExtras(nil)
        end
    end

    return true, old, result
end

-- The address HMS adds to its location, like for getLastLocationWithAddress, is of the real place: empty, like the
-- fields HMS leaves out of an address it answers (optString), and none stays none
local ADDRESS = { 'CountryCode', 'CountryName', 'State', 'City', 'County', 'Street', 'FeatureName', 'PostalCode',
                  'Phone', 'Url' }

function noaddress(location)
    for _, name in ipairs(ADDRESS) do
        if location['get' .. name](location) ~= nil then
            location['set' .. name](location, '')
        end
    end
end

-- The frames on the stack (see geocoded)
function callers()
    return luajava.bindClass('java.lang.Thread'):currentThread():getStackTrace()
end

-- HMS makes the places its geocoder answers of the same JSON as its locations, in parseHwLocationsFromJsonObject,
-- whose name the rule Huawei documents for apps keeps, like the names of the hooks (see hooks.json)
function geocoded()
    local frames = callers()
    for index = 1, frames['length'] do
        if frames[index]:getMethodName() == 'parseHwLocationsFromJsonObject' and
                frames[index]:getClassName() == 'com.huawei.hms.support.api.location.common.LocationJsonUtil' then
            return true
        end
    end
    return false
end

-- Like Android makes coarse locations (LocationFudger.createCoarse): moved by a secret offset of about a quarter of
-- the grid, which drifts slowly, then the center of the cell of the grid, without altitude, movement and extras, so a
-- change of cell does not tell when the real location crosses a line of the grid, and averaging does not reveal it
function coarse(param, result, key, time, grid, hms)
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
    result:setAccuracy(math.max((hms or result:hasAccuracy()) and result:getAccuracy() or 0, grid))

    -- The methods of newer versions are only there from that version
    for _, name in ipairs({ 'removeAltitude', 'removeVerticalAccuracy', 'removeMslAltitude', 'removeMslAltitudeAccuracy',
                            'removeSpeed', 'removeSpeedAccuracy', 'removeBearing', 'removeBearingAccuracy' }) do
        if result[name] ~= nil then
            result[name](result)
        end
    end
    if hms then
        -- An HWLocation has no remove methods: 0, like the values HMS leaves out of its answers
        for _, name in ipairs({ 'setAltitude', 'setVerticalAccuracyMeters', 'setSpeed',
                                'setSpeedAccuracyMetersPerSecond', 'setBearing', 'setBearingAccuracyDegrees' }) do
            result[name](result, 0)
        end
        noaddress(result)
        if result:getExtraInfo() ~= nil then
            result:getExtraInfo():clear()
        end
    else
        result:setExtras(nil)
    end
    return true, old, result
end

-- The keys of the location updates Android sends to a PendingIntent: the last location, and from Android 12 all
-- locations of a batch (LocationManager.KEY_LOCATION_CHANGED, KEY_LOCATIONS); and the last location of Play services,
-- if it still sends one besides its LocationResult (FusedLocationProviderApi.KEY_LOCATION_CHANGED)
local UPDATES = { 'location', 'locations', 'com.google.android.location.LOCATION' }

-- The extras of an intent stay parcelled until the app reads them, so a copy the app writes to a parcel and marshals
-- (Intent.getExtras, Bundle.writeToParcel) has the real location of an update without Location.CREATOR running; from
-- Android 13 also after the app read other extras, since a read leaves the parcelables it does not read in the parcel
-- (LazyValue). So the updates are read from a copy of the extras, which runs the hook of Location.CREATOR, and the
-- intent gets that copy parcelled again, so the app reads each extra like on a device without Doppel: parcelled until
-- its first read (Bundle.toString), and from Android 13 a batch also by a typed read (Location[]), which an untyped
-- read before it would make a Parcelable[]. This is done when the intent is read from its parcel, and once more when
-- Android sets the class loader of the component it gives the intent to (Intent.setExtrasClassLoader, on the main
-- thread, before the receiver, service or activity gets it), if its extras are still parcelled. The second is for an
-- update which starts the app: up to Android 14 its intent is read before the hooks are there (attachApplication),
-- for onNewIntent, which reads its intents with a CREATOR of its own (ReferrerIntent), and for an intent the first
-- could not fake, like one read before the hook of Location.CREATOR was there, or with extras of the app only the
-- class loader of the component finds. Extras without an update stay as they are, also ones with only another
-- location, like a place the app passes under its own key.
-- shortcut: before Android 13 a read reads all extras, so the parcelables of the app in extras with a location are
-- read by the hook before the app reads them, on the thread which reads the intent (a binder thread, or the main
-- thread for the second), and those of an update are written and read once more for each of the two (CREATOR and
-- writeToParcel of the app); read only the updates from the parcel when apps check that.
-- shortcut: Location.CREATOR runs for each copy and for the read of the app, so the log has an update two or three
-- times, except a coarse one; mark the fakes when the log must count updates.
-- shortcut: the updates of Play services stay parcelled (com.google.android.gms.location.EXTRA_LOCATION_RESULT and
-- its bytes): a LocationResult writes its locations without their class, so before Android 13 they are read only with
-- a location of Android in the same extras, and from Android 13 never; read those too when apps read their parcels.
-- shortcut: before Android 13 extras with binders or file descriptors stay as they are, like of an intent with a
-- Messenger, since their parcel cannot be marshalled to look for a location; look in the parcel without marshalling
-- when apps send updates with such extras.
-- shortcut: an activity which an update starts in an app that is not running gets its intent in
-- AppComponentFactory.instantiateActivity (Android 9 and later) before Android sets the class loader, still
-- parcelled; do it there too when apps read intents in their factory.
-- shortcut: the hooks are installed in the order of their names, so the ones of Intent before the one of
-- Location.CREATOR; an intent read in between that Android does not give to a component, or that it gives in between
-- too, keeps the real location; install the hook of Location.CREATOR first when this happens.
function intentafter(param, intent, loader, given)
    -- Extras just read from the parcel of the intent are parcelled; those of an intent given to a component may have
    -- been read or put by the app already
    local extras = (intent ~= nil and intent:getExtras() or nil)
    if extras == nil or (given and (not parcelled(extras) or seen(param, intent))) then
        return false
    end
    -- The class loader the extras keep, and the one to read them with: of the component, or of the app before Android
    -- sets it, since from Android 13 the values read later keep the one of the first read (see account_createfromparcel)
    local kept = extras:getClassLoader()
    extras:setClassLoader(loader)
    local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
    if not haslocation(extras, sdk) then
        return false
    end
    -- Extras the app cannot read or write either, like of a class it does not have, stay as they are
    local parcel = luajava.bindClass('android.os.Parcel'):obtain()
    local ok, bundle = pcall(function()
        local found = false
        for _, key in ipairs(UPDATES) do
            found = extras:get(key) ~= nil or found
        end
        if not found then
            return nil
        end
        extras:writeToParcel(parcel, 0)
        parcel:setDataPosition(0)
        return parcel:readBundle(kept)
    end)
    parcel:recycle()
    if ok and bundle ~= nil then
        intent:replaceExtras(bundle)
    end
    -- The hook of Location.CREATOR reports the fakes
    return false
end

-- Whether the extras are still in their parcel (BaseBundle.mParcelledData): else the app put or read them, and a look
-- would run its code (writeToParcel) and parcel again what it has read, which gives new objects at its next read. The
-- fields are listed like LuaJ lists them, which Android does not report as a use of a hidden API, unlike
-- getDeclaredField (see settingssecure_getstring); without the field, like if a version hides it, an intent given to
-- a component is left as it is.
function parcelled(extras)
    local ok, parcel = pcall(function()
        -- Lua sees a Class as its static members, so the methods of Class get the class as the object
        local fields = luajava.bindClass('java.lang.Class').getDeclaredFields(luajava.bindClass('android.os.BaseBundle'))
        for index = 1, fields['length'] do
            local field = fields[index]
            if field:getName() == 'mParcelledData' then
                field:setAccessible(true)
                return field:get(extras)
            end
        end
        return nil
    end)
    return ok and parcel ~= nil
end

-- Whether the intent was given already, for when apps set the class loader again, and marks it; not when it is read
-- from its parcel, so the pass when Android gives it can fake what that read could not. Only of the class Intent
-- itself, since the marks are found by equality, which a ReferrerIntent (onNewIntent) has by value from Android 14,
-- the same for each update to an activity, and an intent of a class of the app may have too.
function seen(param, intent)
    -- Lua sees a Class as its static members, so the classes are compared, not their names
    if intent:getClass() ~= luajava.bindClass('android.content.Intent') then
        return false
    end
    if param:getValue('location.intent', intent) ~= nil then
        return true
    end
    param:putValue('location.intent', true, intent)
    return false
end

-- Whether the extras have a location, looked for by the name of its class in their parcel, which has its texts as
-- UTF-16, without reading them, because a read before Android 13 reads all extras, which runs code of the app. A parcel
-- with binders or file descriptors cannot be marshalled: from Android 13 the updates are read, since that reads no
-- other parcelables.
function haslocation(extras, sdk)
    local parcel = luajava.bindClass('android.os.Parcel'):obtain()
    extras:writeToParcel(parcel, 0)
    local ok, bytes = pcall(function()
        return parcel:marshall()
    end)
    parcel:recycle()
    if not ok then
        return sdk >= 33
    end
    local text = luajava.newInstance('java.lang.String', bytes,
            luajava.bindClass('java.nio.charset.StandardCharsets').UTF_16LE)
    return string.find(text, 'android.location.Location', 1, true) ~= nil
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
