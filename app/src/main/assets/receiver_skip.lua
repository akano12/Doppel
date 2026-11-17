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

-- Broadcasts which Android does not send on a device with the fake values do not reach the receivers of the app, per
-- variant: a change of the time zone (timezone), since the fake zone stays the same; Android sends it only when the
-- zone changed (AlarmManagerService); a change of the languages (locale), since the fake ones stay the same; Android
-- sends it only when the languages of the device changed (ActivityManagerService); a broadcast of Bluetooth about a
-- device (network), since the hooks of Bluetooth hide the devices around, of which a device without them gets none; the
-- connectivity broadcast of a VPN (vpn), which a device without a VPN does not send when it connects or disconnects; a
-- broadcast about another app (package), system apps too, like its install or removal, of which a device without the
-- app gets none; a broadcast of hardware whose features are hidden (features), which a device without the hardware
-- never has; a result of Play services, of the activity (activity) or a geofence (geofence), which a device without
-- activity recognition or location does not send. intent_createfromparcel, features and intent_results mark those
-- when they read them, which spares reading the extras of the intent here, before the class loader of the app is set;
-- a result of Play services which starts the app is read before the hooks are there, so a receiver of the manifest
-- looks for the names of the results in the parcel of an intent which is not marked, without reading the values. The
-- receiver is not called, and Android is told it finished, like after an onReceive which returned at once. The first
-- sticky broadcast of a receiver which registers is the state at that time, which a device without the change has
-- too, so it is delivered (as intent_createfromparcel made it), but not the one of hidden hardware (see STATELESS).
-- shortcut: Android still starts the process of an app which is not running for a receiver of its manifest, and from
-- Android 15 keeps that start with the broadcast, which ApplicationStartInfo (getHistoricalProcessStartReasons) shows
-- the app, and an ordered broadcast to a registered receiver, or from Android 14 one Android waits for, is still
-- delivered (with the fake zone, the stand-in device, the network below the VPN or the app itself for a hidden app, see
-- intent_createfromparcel, or with the results of Play services of intent_results); upgrade when Android itself can
-- leave out the app.
local ACTIONS = { timezone = 'android.intent.action.TIMEZONE_CHANGED', locale = 'android.intent.action.LOCALE_CHANGED' }
local MARKS = { network = 'bluetooth.device', vpn = 'vpn.connectivity', package = 'package.hidden',
                features = 'features.hidden', activity = 'activity.result', geofence = 'geofence.result' }
-- The variants of which a device without the hidden thing has no sticky broadcast either
local STATELESS = { features = true }
-- The names of the results of Play services, like in intent_results
local KEYS = {
    activity = { 'com.google.android.location.internal.EXTRA_ACTIVITY_RESULT',
                 'com.google.android.location.internal.EXTRA_ACTIVITY_RESULT_LIST',
                 'com.google.android.location.internal.EXTRA_ACTIVITY_TRANSITION_RESULT',
                 'com.google.android.location.internal.EXTRA_SLEEP_SEGMENT_RESULT',
                 'com.google.android.location.internal.EXTRA_SLEEP_CLASSIFY_RESULT' },
    geofence = { 'com.google.android.location.intent.extra.transition',
                 'com.google.android.location.intent.extra.geofence_list',
                 'com.google.android.location.intent.extra.triggering_location' },
}

function before(hook, param)
    -- Hook names are like Function/variant
    local variant = string.match(hook:getName(), '/(.*)$')
    local method = hook:getMethodName()
    local data = param:getArgument(0)
    if data == nil then
        return false
    end
    local intent = (method == 'handleReceiver' and data.intent or data)
    if intent == nil then
        return false
    end
    if MARKS[variant] ~= nil then
        if not param:getValue(MARKS[variant], intent) and
                not (method == 'handleReceiver' and KEYS[variant] ~= nil and result(intent, KEYS[variant])) then
            return false
        end
    elseif intent:getAction() ~= ACTIONS[variant] then
        return false
    end
    if method == 'handleReceiver' then
        -- A receiver of the manifest, which Android waits for: what handleReceiver does after onReceive (ReceiverData is
        -- the PendingResult of the broadcast); first, so a failure delivers the broadcast like before
        data:finish()
    elseif param:getArgument(4) or (param:getArgument(5) and not STATELESS[variant]) or
            (hook:getParameterTypes()['length'] == 10 and not param:getArgument(6)) then
        -- A registered receiver tells Android it finished (PendingResult.finish) only of an ordered broadcast and, from
        -- Android 14, when Android does not assume it delivered (assumeDelivered); else nothing goes back to Android, so
        -- leaving out ReceiverDispatcher.performReceive is the same as an onReceive which does nothing. The sticky
        -- argument is true only for the first sticky broadcast of a receiver which registers (initialSticky).
        return false
    end
    param:setResult(nil)
    return true
end

-- Whether an intent has one of the names in a copy of its extras (Intent.getExtras), which leaves those of the intent
-- in their parcel for the receiver
function result(intent, keys)
    local ok, found = pcall(function()
        local extras = intent:getExtras()
        return extras ~= nil and named(extras, keys)
    end)
    return ok and found
end

-- Whether the extras have one of the names, looked for in their parcel, which has them as UTF-16, without reading the
-- values, because a read before Android 13 reads all of them, which runs code of the app. A parcel with binders or
-- file descriptors cannot be marshalled: from Android 13 the names are looked up, since that reads no values.
function named(extras, keys)
    local parcel = luajava.bindClass('android.os.Parcel'):obtain()
    extras:writeToParcel(parcel, 0)
    local ok, bytes = pcall(function()
        return parcel:marshall()
    end)
    parcel:recycle()
    if not ok and luajava.bindClass('android.os.Build$VERSION').SDK_INT < 33 then
        return false
    end
    local all = (ok and luajava.newInstance('java.lang.String', bytes,
            luajava.bindClass('java.nio.charset.StandardCharsets').UTF_16LE) or nil)
    for _, key in ipairs(keys) do
        if (ok and string.find(all, key, 1, true) ~= nil) or (not ok and extras:containsKey(key)) then
            return true
        end
    end
    return false
end
