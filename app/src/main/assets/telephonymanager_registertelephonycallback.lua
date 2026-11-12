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

-- Callbacks with cells are registered as they are, so Android calls them like on a device without Doppel: at the
-- registration and on changes, only with the location access it requires, on the executor of the app, together with
-- their other events, like the call state. The methods which get the cells get them without their location (nocells),
-- like a callback of listen (see telephonymanager_listen). Also the callback of a network scan (Android 8.1, hidden
-- there), which is the last parameter too. The parameter is not named hook, the function to hook a method is.

-- The listeners of TelephonyCallback (Android 12) which get cells, and their methods
local LISTENERS = {
    { 'CellInfoListener', 'onCellInfoChanged' },
    { 'CellLocationListener', 'onCellLocationChanged' },
    { 'RegistrationFailedListener', 'onRegistrationFailed' },
    { 'BarringInfoListener', 'onBarringInfoChanged' }
}

function before(h, param)
    -- The callback is the last parameter, also of the forms of Android 13 with a flag first
    local callback = param:getArgument(h:getParameterTypes()['length'] - 1)
    if callback == nil then
        return false
    end
    -- First, since before Android 12 the classes of TelephonyCallback do not exist
    if param:isInstance(callback, 'android.telephony.TelephonyScanManager$NetworkScanCallback') then
        hook(callback, 'onResults', nocells)
        return true
    end
    local restricted = false
    for _, listener in ipairs(LISTENERS) do
        if param:isInstance(callback, 'android.telephony.TelephonyCallback$' .. listener[1]) then
            hook(callback, listener[2], nocells)
            restricted = true
        end
    end
    -- Android sends the first location again at each registration (see nocells). After the hooks, since the callback
    -- is the key, of which hashCode and equals are of the app and can throw
    pcall(param.putValue, param, 'nocells', false, callback)
    return restricted
end

-- The first argument of a method of a callback without the location of the cells, the same in telephonymanager_listen:
-- no cells in a list, like getAllCellInfo answers, the cell location empty, like Android gives it before the first
-- cell, and the cell of a registration failure or of the barring info like Android gives it to apps without fine
-- location (system APIs, which apps may call). Null stays, which Android gives before the first cells up to Android 12.
-- If the argument cannot be made, the method does not run, rather than get the cells; neither does it for a second
-- empty location of a registration, which a device without Doppel does not send: it sends a location only when it
-- changes (TelephonyRegistry from Android 12, before that ServiceStateTracker.pollStateDone).
-- shortcut: Android sends the same location again after a switch of the user, and up to Android 11 when another app
-- or process asks for it, which the callback does not get, and a request of the app lets one through also when Android
-- sends none (an update already pending or continuous); send them if an app tells.
-- shortcut: a network scan gets no cells, also for apps to which Android gives the cells of their SIM's networks
-- without location, and an ArrayList where Android gives Arrays.asList; answer those if an app tells them apart.
function nocells(when, param)
    local value = param:getArgument(0)
    if when ~= 'before' or value == nil then
        return
    end
    local ok, without = pcall(function()
        if param:isInstance(value, 'java.util.List') then
            return luajava.newInstance('java.util.ArrayList')
        elseif param:isInstance(value, 'android.telephony.CellLocation') then
            -- Android sends a location only when it changes, and the callback gets the empty one always, so only one
            -- after each registration: more would tell when the device changes cells. Known by identity, since a
            -- delivery can run this twice, by the hooks of both forms of registerTelephonyCallback (Android 13) or
            -- through a method which calls the one of its super class. Up to Android 11 Android sends it again when an
            -- app asks for it, once to each callback, which a request of the app lets through for 10 s
            -- (requestLocationUpdate, see telephonymanager_listen)
            local callback = param:getThis()
            local delivery = luajava.bindClass('java.lang.System'):identityHashCode(value)
            local first = param:getValue('nocells', callback)
            if first and first ~= delivery then
                local requested = param:getValue('nocells.requested', param:getApplicationContext())
                if requested == nil or requested == param:getValue('nocells.requested', callback) or
                        luajava.bindClass('android.os.SystemClock'):elapsedRealtime() - requested > 10000 then
                    return nil
                end
                param:putValue('nocells.requested', requested, callback)
            end
            param:putValue('nocells', delivery, callback)
            -- The location is of this callback alone; invalid, it is the empty one of its class (CellLocation.getEmpty)
            value:setStateInvalid()
            return value
        elseif param:isInstance(value, 'android.telephony.BarringInfo') then
            return value:createLocationInfoSanitizedCopy()
        end
        return value:sanitizeLocationInfo()
    end)
    if ok and without ~= nil then
        param:setArgument(0, without)
    else
        param:setResult(nil)
    end
end
