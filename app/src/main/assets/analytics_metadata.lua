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

-- Firebase Analytics, Crashlytics and Performance, Sentry and Facebook collect by themselves when the app starts,
-- unless the meta-data of the app turns this off, which they read from the application info of the app.
-- An SDK is recognized by classes which are kept in minified apps, like those named in the manifest.
local SDKS = {
    { classes = { 'com.google.firebase.analytics.connector.internal.AnalyticsConnectorRegistrar',
                  'com.google.android.gms.measurement.AppMeasurement' },
      -- Deactivated, unlike enabled, also applies when the app turned collection on before
      key = 'firebase_analytics_collection_deactivated', value = true },
    { classes = { 'com.google.firebase.crashlytics.CrashlyticsRegistrar',
                  'com.google.firebase.crashlytics.FirebaseCrashlytics' },
      -- Also stored by setCrashlyticsCollectionEnabled, which Crashlytics reads first, see stored
      key = 'firebase_crashlytics_collection_enabled', value = false, stored = 'getBoolean' },
    -- The traces of app starts, screens and network requests; deactivated cannot be turned on again by the app
    { classes = { 'com.google.firebase.perf.FirebasePerfRegistrar', 'com.google.firebase.perf.FirebasePerformance' },
      key = 'firebase_performance_collection_deactivated', value = true },
    -- Crashes, ANRs, breadcrumbs and performance spans; also when the app starts Sentry itself
    { classes = { 'io.sentry.android.core.SentryInitProvider', 'io.sentry.android.core.SentryAndroid' },
      key = 'io.sentry.enabled', value = false },
    -- Both also stored as JSON by the setters of FacebookSdk (5.0 and later), which Facebook reads first, see stored
    { classes = { 'com.facebook.internal.FacebookInitProvider', 'com.facebook.FacebookSdk' },
      key = 'com.facebook.sdk.AutoLogAppEventsEnabled', value = false, stored = 'getString' },
    -- The advertising id goes with the events, on when the key is absent
    { classes = { 'com.facebook.internal.FacebookInitProvider', 'com.facebook.FacebookSdk' },
      key = 'com.facebook.sdk.AdvertiserIDCollectionEnabled', value = false, stored = 'getString' }
}
local GET_META_DATA = 128

-- Whether the app contains the SDK, once per process
function contains(param, sdk)
    local scope = param:getApplicationContext()
    local name = 'analytics.' .. sdk.key
    local found = param:getValue(name, scope)
    if found == nil then
        found = false
        for _, class in ipairs(sdk.classes) do
            if pcall(param.findClass, param, class) then
                found = true
                break
            end
        end
        param:putValue(name, found, scope)
    end
    return found
end

-- Only the keys of stored settings run the script for the getters of the preferences, see stored
function filter(hook)
    local method = hook:getMethodName()
    if method ~= 'getBoolean' and method ~= 'getString' then
        return nil
    end
    local keys = {}
    for _, sdk in ipairs(SDKS) do
        if sdk.stored == method then
            keys[#keys + 1] = sdk.key
        end
    end
    return keys
end

-- A setting the app stored in an earlier run beats the meta-data, since the SDK reads it first: the boolean of
-- Crashlytics is answered with the fake value, and the JSON of Facebook, like {"value":true,"last_timestamp":0},
-- as not stored, so Facebook reads the fake meta-data. A stored setting equal to the fake stays.
-- shortcut: only getBoolean and getString of the preferences of Android by the key, not contains, getAll or
-- preferences of the app's own implementation, upgrade when an SDK reads the setting another way.
function stored(param, method)
    local key = param:getArgument(0)
    local result = param:getResult()
    for _, sdk in ipairs(SDKS) do
        if sdk.stored == method and sdk.key == key and contains(param, sdk) then
            if method == 'getBoolean' then
                -- A missing key gives the caller's default and another type throws, both like a device without Doppel
                if result == sdk.value or result == nil or not param:getThis():contains(key) then
                    return false
                end
                param:setResult(sdk.value)
            else
                if result == nil or not string.find(result, '"value":' .. tostring(not sdk.value), 1, true) then
                    return false
                end
                param:setResult(param:getArgument(1))
            end
            return true, nil, key .. '=' .. tostring(sdk.value)
        end
    end
    return false
end

-- A copy through a parcel, the way the package manager answers by binder: a bundle read back stays parcelled until it
-- is read, as its text shows, and an application info keeps what its copy constructor loses, like the application
-- classes by process from Android 13
function parcelled(object)
    local parcel = luajava.bindClass('android.os.Parcel'):obtain()
    parcel:writeParcelable(object, 0)
    parcel:setDataPosition(0)
    local copy = parcel:readParcelable(nil)
    parcel:recycle()
    return copy
end

function after(hook, param)
    local method = hook:getMethodName()
    if method == 'getBoolean' or method == 'getString' then
        return stored(param, method)
    end

    local result = param:getResult()
    if result == nil or param:getArgument(0) ~= param:getPackageName() then
        return false
    end

    -- An int or, from Android 13, ApplicationInfoFlags
    local flags = param:getArgument(1)
    if type(flags) ~= 'number' then
        flags = flags:getValue()
    end
    if math.floor(flags / GET_META_DATA) % 2 == 0 then
        return false
    end

    -- The application info of a package info, which getApplicationInfo gives itself
    local byPackage = method == 'getPackageInfo'
    local info = result
    if byPackage then
        info = result.applicationInfo
        if info == nil then
            return false
        end
    end

    -- A copy, which unlike putAll leaves the meta-data of the info parcelled, also the fake given to the other overload
    local metaData
    if info.metaData == nil then
        metaData = luajava.newInstance('android.os.Bundle')
    else
        metaData = luajava.newInstance('android.os.Bundle', info.metaData)
    end
    local changed = {}
    for _, sdk in ipairs(SDKS) do
        if contains(param, sdk) and
                not (metaData:containsKey(sdk.key) and metaData:getBoolean(sdk.key) == sdk.value) then
            metaData:putBoolean(sdk.key, sdk.value)
            changed[#changed + 1] = sdk.key .. '=' .. tostring(sdk.value)
        end
    end
    if #changed == 0 then
        return false
    end

    -- The same fake for the same info: from Android 11 the package manager answers from a cache, so the app gets the
    -- same object again, with the changes it made to it; a new info, like before Android 11, gets a new fake
    local fake = param:getValue('analytics.fake', result)
    if fake == nil then
        -- A copy, because the package manager can cache the info (see storage_stats_uid)
        fake = parcelled(result)
        if byPackage then
            fake.applicationInfo.metaData = parcelled(metaData)
        else
            fake.metaData = parcelled(metaData)
        end
        param:putValue('analytics.fake', fake, result)
    end
    param:setResult(fake)
    return true, nil, table.concat(changed, ', ')
end
