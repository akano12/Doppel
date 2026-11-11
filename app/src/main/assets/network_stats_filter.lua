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
-- On Android hasNextBucket is true when getNextBucket returns a bucket, so hasNextBucket reads ahead to the next
-- bucket of a visible uid, which the next getNextBucket hands out: a query of a hidden uid has no buckets.
-- Android also answers true for the last uids of queryDetails when their histories are empty, after which
-- getNextBucket returns false; the read-ahead answers false there, which is accepted, since an app cannot tell why.
-- hasNextBucket hooks getNextBucket itself to hand it out, also for an app with the hook of getNextBucket switched off.
-- The parameter of after is not named hook, the function to hook a method is.

-- A private field of a bucket, looked up on Bucket itself: Lua sees only the fields the class of an object declares,
-- which are not these for a subclass of Bucket an app passes. Fails when reflection does not reach the field, which
-- the hidden API rules may refuse.
function fieldOf(name)
    local field = luajava.bindClass('java.lang.Class').getDeclaredField(
        luajava.bindClass('android.app.usage.NetworkStats$Bucket'), name)
    field:setAccessible(true)
    return field
end

-- Copies a bucket into another, in Java, so the times and counts, which are longs, stay exact
function copy(param, from, to)
    param:copyFields('android.app.usage.NetworkStats$Bucket', from, to)
end

-- Reads the next bucket of a visible uid into the bucket, calling the real method so this hook does not recurse;
-- returns whether there is one and whether buckets of hidden uids were skipped
function nextVisible(param, stats, bucket)
    local skipped = false
    while param:unhooked(function() return stats:getNextBucket(bucket) end) == true do
        if param:isUidVisible(bucket:getUid()) then
            return true, skipped
        end
        skipped = true
    end
    return false, skipped
end

-- Before getNextBucket, which hasNextBucket hooks: hands out the bucket hasNextBucket read ahead; without a bucket to
-- fill Android returns false and the enumeration stays where it is
function handOut(when, param)
    if when ~= 'before' then
        return
    end
    local stats = param:getThis()
    local kept = param:getValue('bucket', stats)
    local bucket = param:getArgument(0)
    if kept == nil or bucket == nil then
        return
    end
    -- Gone also when the copy fails, so hasNextBucket does not stay true
    param:putValue('bucket', nil, stats)
    copy(param, kept, bucket)
    param:setResult(true)
end

function after(h, param)
    -- Processes of the system see all apps
    if param:isSystem() then
        return false
    end
    local stats = param:getThis()

    if h:getMethodName() == 'hasNextBucket' then
        -- A bucket read ahead is the next one, also when Android has none after it
        if param:getValue('bucket', stats) ~= nil then
            param:setResult(true)
            return false
        end
        if not param:getResult() then
            return false
        end
        -- Without reflection on the fields of a bucket the one read ahead could not be handed out, and without the
        -- hook of getNextBucket nothing would; both before reading ahead, so a failure leaves Android's answer
        local kept = luajava.newInstance('android.app.usage.NetworkStats$Bucket')
        if not pcall(copy, param, kept, kept) then
            return false
        end
        hook(stats, 'getNextBucket', handOut)
        local more, skipped = nextVisible(param, stats, kept)
        if more then
            param:putValue('bucket', kept, stats)
        else
            param:setResult(false)
        end
        return skipped
    end

    if not param:getResult() then
        return false
    end
    local bucket = param:getArgument(0)
    if bucket == nil or param:isUidVisible(bucket:getUid()) then
        return false
    end
    local more = nextVisible(param, stats, bucket)
    if not more then
        -- getNextBucket leaves the bucket as it is when it returns false, so it holds the last hidden bucket, which an
        -- app may read anyway: it becomes one of removed apps (UID_REMOVED) without traffic
        pcall(function()
            for _, name in ipairs({ 'mRxBytes', 'mRxPackets', 'mTxBytes', 'mTxPackets' }) do
                fieldOf(name):setLong(bucket, 0)
            end
            fieldOf('mTag'):setInt(bucket, 0)
            fieldOf('mUid'):setInt(bucket, -4)
        end)
    end
    param:setResult(more)
    return true
end
