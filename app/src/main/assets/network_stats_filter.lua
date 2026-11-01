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

-- With usage access (PACKAGE_USAGE_STATS) the data usage of another app, by uid, tells it is installed.
-- NetworkStatsManager.querySummary and queryDetails (all uids) and the per-uid queries all return a NetworkStats,
-- iterated bucket by bucket with getNextBucket. Buckets of hidden uids are skipped, like the apps have no traffic:
-- the device totals of querySummaryForDevice and querySummaryForUser have the uid UID_ALL, which stays visible.

function after(hook, param)
    -- Processes of the system see all apps
    if param:isSystem() or not param:getResult() then
        return false
    end
    local bucket = param:getArgument(0)
    if bucket == nil or param:isUidVisible(bucket:getUid()) then
        return false
    end

    -- Advance to the next bucket of a visible uid, or to the end, calling the real method so this hook does not
    -- recurse. shortcut: getNextBucket leaves the bucket as it is when it returns false, so an app which ignores
    -- the returned boolean and reads the bucket after the last (hidden) one reads its stale data once; a cleaner
    -- fix needs the private state of NetworkStats, upgrade if that iteration pattern is seen in the wild.
    local stats = param:getThis()
    local more = true
    while more and not param:isUidVisible(bucket:getUid()) do
        more = param:unhooked(function() return stats:getNextBucket(bucket) end) == true
    end
    param:setResult(more)
    return true
end
