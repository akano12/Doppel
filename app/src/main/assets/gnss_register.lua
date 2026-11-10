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

-- The registrations for the raw data of the GNSS (status, NMEA, measurements, navigation messages, the old GpsStatus)
-- are skipped, with the answer of Android, which then sends nothing more, like for a device without a fix. Where
-- Android throws or refuses, which it does before it registers, the call runs: without ACCESS_FINE_LOCATION (false up
-- to Android 11, a SecurityException from Android 12), for a null callback, executor or request
-- (IllegalArgumentException), on a thread without a Looper where Android makes a Handler (RuntimeException), for
-- addGpsStatusListener of an app for a newer Android (UnsupportedOperationException), and up to Android 11 without
-- the GNSS (false). Measurement and navigation callbacks get the status Android sends on a registration.
function before(hook, param)
    local sdk = luajava.bindClass('android.os.Build$VERSION').SDK_INT
    local context = param:getApplicationContext()
    local method = hook:getMethodName()

    local types = hook:getParameterTypes()
    local callback, executor, handler
    local hasExecutor, hasHandler = false, false
    for index = 1, types['length'] do
        local value = param:getArgument(index - 1)
        if types[index] == 'java.util.concurrent.Executor' then
            executor, hasExecutor = value, true
        elseif types[index] == 'android.os.Handler' then
            handler, hasHandler = value, true
        elseif value == nil then
            return false -- The callback or the request
        else
            callback = value
        end
    end
    if hasExecutor and executor == nil then
        return false
    end
    if context:checkSelfPermission('android.permission.ACCESS_FINE_LOCATION') ~= 0 then
        return false
    end
    if method == 'addGpsStatusListener' then
        local target = context:getApplicationInfo().targetSdkVersion
        if (sdk >= 31 and target > 30) or (sdk == 30 and target > 29) then
            return false
        end
    end
    -- From Android 11 these make a Handler on the thread of the call: of the status and NMEA without a Handler too,
    -- the measurements and navigation messages without one run on the thread of the system instead
    local direct = (method == 'registerGnssMeasurementsCallback' or method == 'registerGnssNavigationMessageCallback')
    if sdk >= 30 and not hasExecutor and handler == nil and (hasHandler or not direct) and
            luajava.bindClass('android.os.Looper'):myLooper() == nil then
        return false
    end
    local manager = context:getSystemService('location')
    local gnss = manager:getAllProviders():contains('gps')
    if sdk < 31 and not gnss then
        return false
    end

    param:setResult(true)
    if direct and gnss then
        -- STATUS_READY; up to Android 11 STATUS_LOCATION_DISABLED (2) while the location is off
        local status = ((sdk < 31 and not manager:isProviderEnabled('gps')) and 2 or 1)
        local runnable = luajava.createProxy('java.lang.Runnable', {
            run = function()
                callback:onStatusChanged(status)
            end
        })
        if executor ~= nil then
            executor:execute(runnable)
        elseif handler ~= nil then
            handler:post(runnable)
        elseif direct and sdk >= 30 then
            luajava.newInstance('java.lang.Thread', runnable):start()
        else
            luajava.newInstance('android.os.Handler'):post(runnable)
        end
    end
    return true
end
