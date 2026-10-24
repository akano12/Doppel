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

-- Firebase Analytics, Firebase Crashlytics and Facebook collect by themselves when the app starts,
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
    { classes = { 'com.facebook.internal.FacebookInitProvider', 'com.facebook.FacebookSdk' },
      key = 'com.facebook.sdk.AutoLogAppEventsEnabled', value = false }
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

    local metaData = luajava.newInstance('android.os.Bundle')
    if result.metaData ~= nil then
        metaData:putAll(result.metaData)
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

    -- A copy, because the package manager can keep the application info in a cache
    local fake = luajava.newInstance('android.content.pm.ApplicationInfo', result)
    fake.metaData = metaData
    param:setResult(fake)
    return true, nil, table.concat(changed, ', ')
end
