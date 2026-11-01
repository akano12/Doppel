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

-- SDKs which collect by themselves are turned off when the app gets their instance, once per instance:
-- apps get the instance for each event they log. The method of each class, which AppsFlyer calls to stop
-- and Firebase Analytics and Crashlytics to turn collection on or off.
local METHODS = {
    ['com.appsflyer.AppsFlyerLib'] = 'stop',
    ['com.google.firebase.analytics.FirebaseAnalytics'] = 'setAnalyticsCollectionEnabled',
    ['com.google.firebase.crashlytics.FirebaseCrashlytics'] = 'setCrashlyticsCollectionEnabled',
}

function after(hook, param)
    local result = param:getResult()
    local name = METHODS[hook:getClassName()]
    if result == nil or name == nil or type(result[name]) ~= 'function' or param:getValue('stopped', result) then
        return false
    end
    param:putValue('stopped', true, result)

    if name == 'stop' then
        result:stop(true, param:getApplicationContext())
    else
        result[name](result, false)
    end
    return true
end
