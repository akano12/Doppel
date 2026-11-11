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

-- The usage events of the device tell which apps were used when, like the usage statistics.
-- The app asks for the events of a time far in the future, of which the service has none on any Android version,
-- so the app gets the empty result of Android itself, the same object on every call, after the same checks and
-- app-op note of the service; the real events never reach the app. Swapping the result afterwards would make a new
-- object, or a second query with a second note that apps can count with an OnOpNotedCallback.
-- The statistics per app of queryUsageStats are an empty list.

function before(hook, param)
    -- Processes of the system see all apps
    if hook:getMethodName() ~= 'queryEvents' or param:isSystem() then
        return false
    end
    if type(param:getArgument(0)) == 'number' then
        param:setArgument(0, 1e18)
        param:setArgument(1, 2e18)
        return true
    end

    -- The service throws for a null query itself
    if param:getArgument(0) == nil then
        return false
    end
    -- shortcut: a query made from a parcel for another user gets the empty result where Android throws
    -- SecurityException (normal apps cannot set the user), upgrade when apps are seen doing it, by writing the times
    -- over those in a parcel of the query of the app
    param:setArgument(0, luajava.newInstance('android.app.usage.UsageEventsQuery$Builder', 1e18, 2e18):build())
    return true
end

function after(hook, param)
    if hook:getMethodName() ~= 'queryUsageStats' or param:isSystem() then
        return false
    end
    local stats = param:getResult()
    if stats == nil or stats:isEmpty() then
        return false
    end
    param:setResult(luajava.newInstance('java.util.ArrayList'))
    return true
end
