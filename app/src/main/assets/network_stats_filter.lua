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
    -- recurse
    local stats = param:getThis()
    local more = true
    while more and not param:isUidVisible(bucket:getUid()) do
        more = param:unhooked(function() return stats:getNextBucket(bucket) end) == true
    end
    if not more then
        -- getNextBucket leaves the bucket as it is when it returns false, and apps looping on hasNextBucket read it
        -- anyway: the last hidden bucket becomes one of removed apps (UID_REMOVED) without traffic. Its private
        -- fields are the same on all versions; skipping hidden entries in hasNextBucket would need the private
        -- state of NetworkStats, which differs between versions.
        pcall(function()
            for _, field in ipairs({ 'mRxBytes', 'mRxPackets', 'mTxBytes', 'mTxPackets', 'mTag' }) do
                bucket[field] = 0
            end
            bucket.mUid = -4
        end)
    end
    param:setResult(more)
    return true
end
