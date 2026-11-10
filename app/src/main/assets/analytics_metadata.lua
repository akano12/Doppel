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

-- Firebase Analytics, Firebase Crashlytics, Firebase Performance and Facebook collect by themselves when the app starts,
-- unless the meta-data of the app turns this off, which they read from the application info of the app.
-- An SDK is recognized by classes which are kept in minified apps, like those named in the manifest.
local SDKS = {
    { classes = { 'com.google.firebase.analytics.connector.internal.AnalyticsConnectorRegistrar',
                  'com.google.android.gms.measurement.AppMeasurement' },
      -- Deactivated, unlike enabled, also applies when the app turned collection on before
      key = 'firebase_analytics_collection_deactivated', value = true },
    { classes = { 'com.google.firebase.crashlytics.CrashlyticsRegistrar',
                  'com.google.firebase.crashlytics.FirebaseCrashlytics' },
      key = 'firebase_crashlytics_collection_enabled', value = false },
    -- The traces of app starts, screens and network requests; deactivated cannot be turned on again by the app
    { classes = { 'com.google.firebase.perf.FirebasePerfRegistrar', 'com.google.firebase.perf.FirebasePerformance' },
      key = 'firebase_performance_collection_deactivated', value = true },
    { classes = { 'com.facebook.internal.FacebookInitProvider', 'com.facebook.FacebookSdk' },
      key = 'com.facebook.sdk.AutoLogAppEventsEnabled', value = false },
    -- The advertising id goes with the events, on when the key is absent
    { classes = { 'com.facebook.internal.FacebookInitProvider', 'com.facebook.FacebookSdk' },
      key = 'com.facebook.sdk.AdvertiserIDCollectionEnabled', value = false }
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

function after(hook, param)
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
    local byPackage = hook:getMethodName() == 'getPackageInfo'
    local info = result
    if byPackage then
        info = result.applicationInfo
        if info == nil then
            return false
        end
    end

    local metaData = luajava.newInstance('android.os.Bundle')
    if info.metaData ~= nil then
        metaData:putAll(info.metaData)
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

    local fake
    if byPackage then
        -- A copy through a parcel, because the package manager can cache the package info (see storage_stats_uid)
        local parcel = luajava.bindClass('android.os.Parcel'):obtain()
        parcel:writeParcelable(result, 0)
        parcel:setDataPosition(0)
        fake = parcel:readParcelable(nil)
        parcel:recycle()
        fake.applicationInfo.metaData = metaData
    else
        -- A copy, because the package manager can keep the application info in a cache
        fake = luajava.newInstance('android.content.pm.ApplicationInfo', result)
        fake.metaData = metaData
    end
    param:setResult(fake)
    return true, nil, table.concat(changed, ', ')
end
