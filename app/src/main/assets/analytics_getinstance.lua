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

-- SDKs which collect by themselves are turned off when the app gets their instance, once per instance:
-- apps get the instance for each event they log. Each SDK by its own opt-out, which is stable across versions;
-- a method missing in a version fails the call and the SDK stays as it is.
local METHODS = {
    ['com.appsflyer.AppsFlyerLib'] = function(param, sdk)
        -- stopTracking before version 6
        if type(sdk.stop) == 'function' then
            sdk:stop(true, param:getApplicationContext())
        else
            sdk:stopTracking(true, param:getApplicationContext())
        end
    end,
    ['com.google.firebase.analytics.FirebaseAnalytics'] = function(param, sdk)
        sdk:setAnalyticsCollectionEnabled(false)
        -- With ANALYTICS_STORAGE denied, getAppInstanceId gives null (the documentation); before version 18 no consent
        pcall(function()
            sdk:setConsent(denied(param))
        end)
    end,
    ['com.google.firebase.crashlytics.FirebaseCrashlytics'] = function(param, sdk)
        sdk:setCrashlyticsCollectionEnabled(false)
    end,
    -- Google Analytics v4 sends no hits, until the app starts again (the flag is not stored)
    ['com.google.android.gms.analytics.GoogleAnalytics'] = function(param, sdk)
        sdk:setAppOptOut(true)
    end,
    -- Amazon Publisher Services (also by Aps.initialize): the consent of the non-IAB API, which bids carry;
    -- static, it needs the instance first
    ['com.amazon.device.ads.AdRegistration'] = function(param, sdk)
        sdk:setConsentStatus(declined(param))
    end,
}

-- Firebase Analytics consent with all types denied, also types of newer versions
function denied(param)
    local types = param:findClass('com.google.firebase.analytics.FirebaseAnalytics$ConsentType'):values()
    local status = param:findClass('com.google.firebase.analytics.FirebaseAnalytics$ConsentStatus').DENIED
    local consent = luajava.newInstance('java.util.HashMap')
    for index = 1, types['length'] do
        consent:put(types[index], status)
    end
    return consent
end

function declined(param)
    return param:findClass('com.amazon.device.ads.AdRegistration$ConsentStatus').EXPLICIT_NO
end

-- Consent the app gives is replaced by none, like setAnalyticsCollectionEnabled: FirebaseAnalytics.setConsent,
-- and AdRegistration.setConsentStatus of Amazon (Aps has no consent setter of its own)
local CONSENT = { setConsent = denied, setConsentStatus = declined }

function before(hook, param)
    local consent = CONSENT[hook:getMethodName()]
    if consent == nil then
        return false
    end
    local fake = consent(param)
    if param:getArgument(0) == fake then
        return false
    end
    param:setArgument(0, fake)
    return true
end

function after(hook, param)
    local result = param:getResult()
    local stop = METHODS[hook:getClassName()]
    if result == nil or stop == nil or param:getValue('stopped', result) then
        return false
    end
    param:putValue('stopped', true, result)
    return (pcall(stop, param, result))
end
