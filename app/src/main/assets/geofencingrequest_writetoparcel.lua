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

-- Play services gets the geofences of the app as the parcel of a GeofencingRequest, which GeofencingClient and the older
-- GeofencingApi write for its binder. A copy of the request is written instead, with each fence at 0, 0 and a radius of
-- 0.1 m, which the device never enters, while the fences and the request the app keeps have the region it set and the library
-- checked, like without the hook. R8 renames the fields, so they are found by type: in play-services-location 11 to 21.4
-- the request has one list, of its fences, and each fence two doubles, the latitude and the longitude, and one float,
-- the radius; other fields fail the hook, which then writes the real request.
-- shortcut: a parcel the app writes itself, like for an extra of an intent, has the fake region too, which reading it
-- back with CREATOR shows; upgrade should apps do that.
-- shortcut: the device is always outside that fence, so a request with INITIAL_TRIGGER_EXIT gets its EXIT at once,
-- which stock gives only outside the region of the app, and from play-services-location 20.0.0 on the fences of
-- GeofencingEvent.getTriggeringGeofences read 0, 0 and 0.1, unlike the Geofence and GeofencingRequest of the app;
-- upgrade by clearing INITIAL_TRIGGER_EXIT in the copy, so the fake fence never alerts, or by giving the fences of the
-- event the region of the app back.
function before(hook, param)
    local parcel = param:getArgument(0)
    if parcel == nil then
        return false
    end
    local request = param:getThis()
    -- Without the hooks, as the writes of the copy would else run this one again
    param:unhooked(function()
        local copy = luajava.bindClass('android.os.Parcel'):obtain()
        request:writeToParcel(copy, 0)
        copy:setDataPosition(0)
        local fake = request.CREATOR:createFromParcel(copy)
        copy:recycle()
        local lists = fieldsof(fake, 'interface java.util.List')
        if #lists ~= 1 then
            error('Geofence list unknown')
        end
        local fences = lists[1]:get(fake)
        for index = 0, fences:size() - 1 do
            local fence = fences:get(index)
            local doubles = fieldsof(fence, 'double')
            local floats = fieldsof(fence, 'float')
            if #doubles ~= 2 or #floats ~= 1 then
                error('Geofence region unknown')
            end
            doubles[1]:setDouble(fence, 0)
            doubles[2]:setDouble(fence, 0)
            floats[1]:setFloat(fence, 0.1)
        end
        fake:writeToParcel(parcel, param:getArgument(1))
    end)
    param:setResult(nil)
    return true
end

-- The fields an object declares of a type, as Class.toString names it, made accessible
function fieldsof(object, type)
    local found = {}
    -- Lua sees a Class as its static members, so the methods of Class get the class as the object
    local fields = luajava.bindClass('java.lang.Class').getDeclaredFields(object:getClass())
    for index = 1, fields['length'] do
        local field = fields[index]
        if tostring(field:getType()) == type then
            field:setAccessible(true)
            found[#found + 1] = field
        end
    end
    return found
end
