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

-- Usage access (PACKAGE_USAGE_STATS) gives the usage statistics, events, event counts and configurations of the
-- device, which tell which apps were used when and how the device is used. It reads as not granted (see package_probe,
-- and storage_stats_uid for the storage stats), and every one of them answers like for an app without usage access:
-- the statistics, event counts and configurations get the empty list of Collections, which UsageStatsManager returns
-- when the service refuses, before the call, so the service notes no access. For the events the app asks for a time
-- far in the future, of which the service has none on any Android version, so the app gets the empty events of
-- UsageStatsManager itself, the same object on every call, after the same checks of the service; the real data never
-- reaches the app. Swapping the result afterwards would make a new object.
-- queryEventsForSelf needs no usage access and gives the app its own events, but with usage access each one also names
-- the app and activity of the task it ran in (the task root, like of an app which shared to it), which the app can read
-- from the parcel of the events, so for an app with usage access they ask for the far-future time too.
-- shortcut: an app with usage access gets none of its own events from queryEventsForSelf, which Android without access
-- gives, since rebuilding them without the task roots needs a hidden constructor or the parcel layout of each version;
-- upgrade when Doppel can call hidden APIs or when apps are seen comparing the events with their own activity.
-- shortcut: for the events (also the own ones with usage access), the standby buckets, the storage stats and the data
-- usage the service still notes the app op of usage access of an app which has it, which an OnOpNotedCallback of the
-- app receives (from Android 11); upgrade by dropping the note in the callback when apps are seen counting it.
-- The data usage of NetworkStatsManager answers like without usage access too (see network_stats_filter).
-- shortcut: a usage callback (registerUsageCallback) still counts the traffic of the device toward its threshold, which
-- the service does with usage access, where without it only the app's own traffic counts, and the hidden queries by a
-- NetworkTemplate (from Android 9) are not hooked; upgrade when apps are seen comparing the callback with their own
-- traffic or calling the hidden queries.

-- Whether the app has usage access, asked without the hooks of the app, which make it read as not granted: allowed by
-- the app op, or by the permission while the app op has its default mode, like UsageStatsService.hasPermission
function granted(param)
    local ok, access = pcall(param.unhooked, param, function()
        local context = param:getApplicationContext()
        local mode = context:getSystemService('appops'):checkOpNoThrow('android:get_usage_stats',
            luajava.bindClass('android.os.Process'):myUid(), context:getOpPackageName())
        return mode == 0 or mode == 3 and context:checkSelfPermission('android.permission.PACKAGE_USAGE_STATS') == 0
    end)
    return ok and access
end

function before(hook, param)
    -- Processes of the system see all apps
    if param:isSystem() then
        return false
    end
    local method = hook:getMethodName()
    -- Without usage access there are no task roots, so the events stay as they are
    if method == 'queryEventsForSelf' then
        if not granted(param) then
            return false
        end
        param:setArgument(0, 1e18)
        param:setArgument(1, 2e18)
        return true
    end
    -- queryUsageStats, queryEventStats and queryConfigurations
    if method ~= 'queryEvents' then
        param:setResult(luajava.bindClass('java.util.Collections'):emptyList())
        return true
    end
    if type(param:getArgument(0)) == 'number' then
        param:setArgument(0, 1e18)
        param:setArgument(1, 2e18)
        return true
    end

    -- The service throws for a null query itself, also without usage access
    if param:getArgument(0) == nil then
        return false
    end
    -- Without usage access the service answers before it checks the user of the query, so a query for another user
    -- gets the empty result too
    param:setArgument(0, luajava.newInstance('android.app.usage.UsageEventsQuery$Builder', 1e18, 2e18):build())
    return true
end
