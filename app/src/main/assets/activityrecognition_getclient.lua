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

-- Play services sends the activity updates, transitions and sleep segments of a request at the moment it detects them,
-- so their arrival alone tells the app when the user moved or woke up, and for a request of a single transition also
-- what happened. The requests of the client succeed without registering, so nothing arrives, like on a device on which
-- Play services detects nothing. The client is the one getClient makes, from play-services-location 21 of a class
-- whose name changes with each version, or one the app made itself with the constructor of versions 11.6 to 20, of
-- which getClient calls the constructor too; the methods a version does not have are not hooked. The request of the
-- older ActivityRecognitionApi through a GoogleApiClient succeeds the same way.
-- shortcut: the task is complete when the request returns, where the one of Play services completes after its call to
-- Play services; return the task of the removal instead once a device with Play services shows that removing a
-- PendingIntent which is not registered succeeds, should apps check this.
-- The parameter is not named hook, the function to hook a method is.

-- Per request: the method which removes its registration, the argument of the PendingIntent, the one of the request
-- for transitions and the one of the interval, without which or with an interval below 0 the call fails like without
-- the hook, before anything registers; a sleep segment request of null is valid, for the default one.
-- shortcut: Play services builds without the feature of sleep segments, which play-services-location 21 declares for
-- the request, fail it, where the fake succeeds; ask the feature of the client should apps run on such old builds
local REQUESTS = {
    requestActivityUpdates = { remove = 'removeActivityUpdates', intent = 1, interval = 0 },
    requestActivityTransitionUpdates = { remove = 'removeActivityTransitionUpdates', intent = 1, request = 0 },
    requestSleepSegmentUpdates = { remove = 'removeSleepSegmentUpdates', intent = 0 },
}
local HMS_REQUESTS = {
    createActivityIdentificationUpdates = { remove = 'deleteActivityIdentificationUpdates', intent = 1 },
    createActivityConversionUpdates = { remove = 'deleteActivityConversionUpdates', intent = 1, request = 0 },
}

-- The results of isGooglePlayServicesAvailable, with the minimum version the client connects with too, for which the
-- library does not register the request: missing, to be updated, disabled and invalid; while Play services updates
-- itself the library tries again a few seconds later, so the request registers then
local UNAVAILABLE = { [1] = true, [2] = true, [3] = true, [9] = true }

-- Whether the app lacks the permission of activity recognition, which the request methods require, so Play services
-- refuses the request, which then goes on like without the hook: from Android 10 the one of Android, which Android
-- grants by itself to apps made for Android 9 and older which ask the one of Play services, before 10 the one of Play
-- services
local function denied(param, huawei)
    local context = param:getApplicationContext()
    local permission = (luajava.bindClass('android.os.Build$VERSION').SDK_INT >= 29 and
            'android.permission.ACTIVITY_RECOGNITION' or
            (huawei and 'com.huawei.hms.permission.ACTIVITY_RECOGNITION' or
                    'com.google.android.gms.permission.ACTIVITY_RECOGNITION'))
    return param:unhooked(function()
        return context:checkSelfPermission(permission) ~= 0
    end)
end

-- Whether Play services or the library refuses the request of the client, which then goes on like without the hook, so
-- it answers like on a device without Doppel: the app lacks the permission, or Play services cannot be used. The check
-- of Play services is not unhooked, so it agrees with what GoogleApiAvailability tells the app, also with Play services
-- hidden by Get.Applications; without the class, which R8 can remove, the request succeeds without registering.
-- shortcut: with Play services missing, to be updated or disabled the library shows Android's notification to fix that
-- and keeps the task pending, like without Doppel, and a request kept so registers once the user does so while the app
-- runs; remove it when the client connects again should users do this
local function refused(param)
    if denied(param) then
        return true
    end
    local context = param:getApplicationContext()
    local ok, status = pcall(function()
        local availability = param:findClass('com.google.android.gms.common.GoogleApiAvailability')
        return availability:getInstance():isGooglePlayServicesAvailable(context)
    end)
    return ok and UNAVAILABLE[status] == true
end

-- If HMS Core is known to be unavailable, preserve the SDK's own failure and resolution behavior.
local function huaweiRefused(param)
    if denied(param, true) then
        return true
    end
    local context = param:getApplicationContext()
    local ok, status = pcall(function()
        local availability = param:findClass('com.huawei.hms.api.HuaweiApiAvailability')
        return availability:getInstance():isHuaweiMobileServicesAvailable(context)
    end)
    return not ok or status ~= 0
end

function after(h, param)
    local name = h:getMethodName()
    local class = h:getClassName()
    local requests
    if class == 'com.google.android.gms.location.ActivityRecognition' or
            class == 'com.google.android.gms.location.ActivityRecognitionClient' then
        if name ~= 'getClient' and name ~= nil then
            return false
        end
        requests = REQUESTS
    elseif class == 'com.huawei.hms.location.ActivityIdentification' or
            class == 'com.huawei.hms.location.ActivityIdentificationService' then
        if name ~= 'getService' and name ~= nil then
            return false
        end
        requests = HMS_REQUESTS
    else
        return false
    end
    local client = ((name == 'getClient' or name == 'getService') and param:getResult() or param:getThis())
    if client == nil then
        return false
    end
    for method in pairs(requests) do
        hook(client, method, unregistered, method)
    end
    return true
end

-- A registration of the same PendingIntent which Play services kept from before the restriction is removed, so it sends
-- nothing either: force stopping the app cancels its PendingIntents only from Android 15 with the flag stayStopped
function unregistered(when, param, method)
    local request = REQUESTS[method] or HMS_REQUESTS[method]
    local huawei = HMS_REQUESTS[method] ~= nil
    local intent = param:getArgument(request.intent)
    if when ~= 'before' or intent == nil or (request.request ~= nil and param:getArgument(request.request) == nil) or
            (not huawei and request.interval ~= nil and param:getArgument(request.interval) < 0) or
            (huawei and huaweiRefused(param)) or (not huawei and refused(param)) then
        return
    end
    local client = param:getThis()
    pcall(param.unhooked, param, function()
        client[request.remove](client, intent)
    end)
    if huawei then
        local source = luajava.new(param:findClass('com.huawei.hmf.tasks.TaskCompletionSource'))
        source:setResult(nil)
        param:setResult(source:getTask())
    else
        param:setResult(param:findClass('com.google.android.gms.tasks.Tasks'):forResult(nil))
    end
end

-- The request of activity updates of the older ActivityRecognitionApi, once its GoogleApiClient is connected to the API,
-- which shows that Play services can be used, so only the permission is checked: before, the call throws like without
-- the hook, and so does an interval below 0 or no PendingIntent. The client of play-services-location 17 and older
-- passes the requests its hook leaves to the library on to this API, with a client without a connection to ask, so
-- they go on too.
-- The result goes to the callbacks on the looper of the client, like the one of Play services; play-services-base 11.6
-- has no method with the client, and Lua calls the one without, whose looper is the main one, the default of a client.
-- shortcut: while the connection is suspended, after Play services stopped, the client queues the request, which then
-- registers once it connects again; accept it then too should apps request in that moment
function before(h, param)
    if h:getMethodName() ~= 'ActivityRecognitionApi:requestActivityUpdates' then
        return false
    end
    local client, interval, intent = param:getArgument(0), param:getArgument(1), param:getArgument(2)
    if client == nil or interval < 0 or intent == nil or denied(param, false) then
        return false
    end
    local api = param:findClass('com.google.android.gms.location.ActivityRecognition').API
    local ok, connected = pcall(function()
        return client:hasConnectedApi(api)
    end)
    if not ok or not connected then
        return false
    end
    local status = luajava.new(param:findClass('com.google.android.gms.common.api.Status'), 0)
    local result = param:findClass('com.google.android.gms.common.api.PendingResults'):immediatePendingResult(status, client)
    local this = param:getThis()
    pcall(param.unhooked, param, function()
        this:removeActivityUpdates(client, intent)
    end)
    param:setResult(result)
    return true
end
