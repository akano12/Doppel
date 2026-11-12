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

-- The events of the cells stay, so Android calls the listener like on a device without Doppel, at the registration
-- too, and the methods which get the cells get them without their location (variant of Read.Network, nocells, like
-- telephonymanager_registertelephonycallback); the call state, of which the listener gets the number of an incoming
-- call empty, like apps without the permission of the call log (call_log).
-- The parameter is not named hook, the function to hook a method is.

-- The events with cells and the methods of the listener which get them, and whether Android refuses the event to apps
-- without a location permission on every version
local EVENTS = {
    { 16, 'onCellLocationChanged', true }, -- LISTEN_CELL_LOCATION
    { 1024, 'onCellInfoChanged', true }, -- LISTEN_CELL_INFO
    { 0x40000000, 'onRegistrationFailed' }, -- LISTEN_REGISTRATION_FAILURE, of privileged apps
    -- LISTEN_BARRING_INFO, of privileged apps: the highest bit, which as an int is negative, like Android defines it
    { -2147483648, 'onBarringInfoChanged' }
}

function before(h, param)
    -- Up to Android 11 Android sends the cell location again when an app asks for it, also when it stays the same, to
    -- every listener: the time of the request lets the next location of each listener through (see nocells). First,
    -- since the methods have no arguments
    local name = h:getName()
    if name == 'CellLocation.requestLocationUpdate' or name == 'TelephonyManager.updateServiceLocation' then
        param:putValue('nocells.requested', luajava.bindClass('android.os.SystemClock'):elapsedRealtime(),
                param:getApplicationContext())
        return false
    end
    local events = param:getArgument(1)
    if string.match(name, '/(.*)$') == 'call_log' then
        local listener = param:getArgument(0)
        if listener == nil or bit32.band(events, 32) == 0 then -- call state
            return false
        end
        hook(listener, 'onCallStateChanged', nonumber)
        return true
    end

    -- The methods are hooked also without a location permission, which the app can be granted while the listener is
    -- registered: from Android 14 Android registers the registration failure and the barring info without one, and
    -- gives their cell with fine location (TelephonyRegistry.isLocationPermissionRequired, up to Android 13 all four)
    local listener = param:getArgument(0)
    local restricted = false
    local kept = events
    for _, event in ipairs(EVENTS) do
        if bit32.band(events, event[1]) ~= 0 then
            restricted = true
            -- If the method cannot be hooked the event is left out, so the cells do not arrive, except the cell
            -- location and the cell info of apps without a location permission, which Android refuses with a
            -- SecurityException. Subtracted, not bit32.bxor, which makes the negative int of the highest bit a number
            -- an int cannot hold
            if not pcall(hook, listener, event[2], nocells) and not (event[3] and unlocated(param)) then
                events = events - event[1]
            end
        end
    end
    if events ~= kept then
        param:setArgument(1, events)
    end
    -- Android sends the first location again at each registration (see nocells). After the hooks, since the listener
    -- is the key, of which hashCode and equals are of the app and can throw
    pcall(param.putValue, param, 'nocells', false, listener)
    return restricted
end

-- Whether the app has neither location permission
function unlocated(param)
    local context = param:getApplicationContext()
    return context:checkSelfPermission('android.permission.ACCESS_COARSE_LOCATION') ~= 0 and
            context:checkSelfPermission('android.permission.ACCESS_FINE_LOCATION') ~= 0
end

-- onCallStateChanged(state, number) of the listener, with the number empty
function nonumber(when, param)
    if when == 'before' then
        param:setArgument(1, '')
    end
end

-- The first argument of a method of the listener without the location of the cells, the same as nocells of
-- telephonymanager_registertelephonycallback, which tells what it gives
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
