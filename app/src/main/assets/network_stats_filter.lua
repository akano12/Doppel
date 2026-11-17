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

-- Usage access (PACKAGE_USAGE_STATS) gives the data usage of the device and of every uid of the user, the system too,
-- which tells which apps are installed and how the device is used. It reads as not granted (see package_probe), so
-- NetworkStatsManager answers like for the access level DEFAULT of NetworkStatsAccess, the one of an app without it on
-- Android 8.1 to 17, after the checks of Android, whose exceptions and nulls stay: the device summary and the history
-- of another uid (also of the system, of removed apps and of tethering) throw the SecurityException of
-- NetworkStatsCollection, the summary and details of all uids have only buckets of the app's own uid, and the total of
-- the user is the traffic of the app's own uid. Another uid with a tag throws Android's own SecurityException already.
-- The total of the user is summed by NetworkStats.getSummaryAggregate from the summary of all uids which
-- querySummaryForUser fetched, so it is made the app's own from that summary, without a second session, whose app-op
-- note would reach an OnOpNotedCallback of the app a second time. The device summary sums with it too, but at the end
-- of its enumeration, so it reads nothing there and stays for its own refusal.
-- shortcut: where the hook of getSummaryAggregate did not run, like where the boot image compiled it into
-- querySummaryForUser, an app with usage access gets its total from a second summary, whose note reaches its
-- OnOpNotedCallback a second time, with the frames of Doppel; upgrade by naming querySummaryForUser to deoptimize
-- once a device shows it.
-- shortcut: Android closes the NetworkStats of the device summary before it is refused, where without usage access it
-- throws before the close and leaves it to CloseGuard; upgrade when apps are seen counting leaked closables.
-- The buckets of a NetworkStats are iterated with getNextBucket; those of other uids are skipped. On Android
-- hasNextBucket is true when getNextBucket returns a bucket, so hasNextBucket reads ahead to the next bucket of the
-- app's uid, which the next getNextBucket hands out: a query of another uid has no buckets.
-- Android also answers true for the last uids of queryDetails when their histories are empty, after which
-- getNextBucket returns false; the read-ahead answers false there, which is accepted, since an app cannot tell why.
-- hasNextBucket hooks getNextBucket itself to hand it out, also for an app with the hook of getNextBucket switched off.
-- shortcut: where the hidden API rules refuse the fields of a bucket, the total of the user stays Android's, the traffic
-- of all uids, like the bucket read ahead is not handed out; upgrade when devices are seen refusing them.
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

-- Reads the next bucket of the app's own uid into the bucket, calling the real method so this hook does not recurse;
-- returns whether there is one and whether buckets of other uids were skipped
function nextVisible(param, stats, bucket)
    local skipped = false
    while param:unhooked(function() return stats:getNextBucket(bucket) end) == true do
        if bucket:getUid() == param:getUid() then
            return true, skipped
        end
        skipped = true
    end
    return false, skipped
end

-- The SecurityException of NetworkStatsCollection for the history of a uid the caller has no access to
function forbidden(param, uid)
    return luajava.newInstance('java.lang.SecurityException',
        'Network stats history of uid ' .. uid .. ' is forbidden for caller ' .. param:getUid())
end

-- The traffic of the app's own uid in a summary of all uids from where its enumeration stands, which is the total of
-- the user for the access level DEFAULT: received and sent bytes and packets, and whether buckets of other uids were in it
function ownTraffic(param, stats)
    local totals, skipped = { 0, 0, 0, 0 }, false
    local bucket = luajava.newInstance('android.app.usage.NetworkStats$Bucket')
    repeat
        local more, other = nextVisible(param, stats, bucket)
        skipped = skipped or other
        if more then
            totals[1] = totals[1] + bucket:getRxBytes()
            totals[2] = totals[2] + bucket:getRxPackets()
            totals[3] = totals[3] + bucket:getTxBytes()
            totals[4] = totals[4] + bucket:getTxPackets()
        end
    until not more
    return totals, skipped
end

-- The traffic of the app's own uid in a second summary of all uids for the same network and time
function ownTotals(param)
    return param:unhooked(function()
        local stats = param:getThis():querySummary(param:getArgument(0), param:getArgument(1),
            param:getArgument(2), param:getArgument(3))
        local ok, totals = pcall(ownTraffic, param, stats)
        stats:close()
        if not ok then
            error(totals)
        end
        return totals
    end)
end

-- Sets the traffic of a bucket; false where reflection does not reach its fields. Without a summary nothing of the
-- device is told, so the traffic is none.
function setTraffic(bucket, ok, totals)
    if not ok then
        totals = { 0, 0, 0, 0 }
    end
    return (pcall(function()
        for i, name in ipairs({ 'mRxBytes', 'mRxPackets', 'mTxBytes', 'mTxPackets' }) do
            fieldOf(name):setLong(bucket, totals[i])
        end
    end))
end

-- Whether the app has usage access, asked without the hooks of the app, like in usageevents_query
function granted(param)
    local ok, access = pcall(param.unhooked, param, function()
        local context = param:getApplicationContext()
        local mode = context:getSystemService('appops'):checkOpNoThrow('android:get_usage_stats',
            luajava.bindClass('android.os.Process'):myUid(), context:getOpPackageName())
        return mode == 0 or mode == 3 and context:checkSelfPermission('android.permission.PACKAGE_USAGE_STATS') == 0
    end)
    return ok and access
end

-- The queries of NetworkStatsManager which answer differently for the access level DEFAULT, after Android answered
function query(param, method)
    if param:getException() ~= nil or param:getResult() == nil then
        return false
    end
    if method == 'querySummaryForDevice' then
        param:setResult(forbidden(param, -1)) -- UID_ALL
        return true
    end
    local bucket = param:getResult()
    if method == 'getSummaryAggregate' then
        param:putValue('own', true, bucket)
        local ok, totals, skipped = pcall(ownTraffic, param, param:getThis())
        if ok and not skipped then
            return false
        end
        return setTraffic(bucket, ok, totals)
    end
    if method == 'querySummaryForUser' then
        -- Without usage access the total of Android is the app's own already
        if param:getValue('own', bucket) ~= nil or not granted(param) then
            return false
        end
        return setTraffic(bucket, pcall(ownTotals, param))
    end
    -- queryDetailsForUid, queryDetailsForUidTag and queryDetailsForUidTagState: type, subscriber, begin, end, uid
    local uid = param:getArgument(4)
    if uid == param:getUid() then
        return false
    end
    param:setResult(forbidden(param, uid))
    return true, tostring(uid)
end

-- getNextBucket leaves the bucket as it is when it returns false, so before it fills a bucket of another uid, after
-- which there may be no bucket of the app's uid, the bucket is kept as it was
function before(h, param)
    if h:getMethodName() ~= 'getNextBucket' or param:isSystem() then
        return false
    end
    local stats = param:getThis()
    local bucket = param:getArgument(0)
    -- A bucket read ahead is handed out instead
    if bucket == nil or param:getValue('bucket', stats) ~= nil then
        return false
    end
    local saved = luajava.newInstance('android.app.usage.NetworkStats$Bucket')
    if pcall(copy, param, bucket, saved) then
        param:putValue('saved', saved, stats)
    end
    return false
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
    local method = h:getMethodName()
    if method ~= 'getNextBucket' and method ~= 'hasNextBucket' then
        return query(param, method)
    end
    local stats = param:getThis()

    if method == 'hasNextBucket' then
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

    local saved = param:getValue('saved', stats)
    param:putValue('saved', nil, stats)
    if not param:getResult() then
        return false
    end
    local bucket = param:getArgument(0)
    if bucket == nil or bucket:getUid() == param:getUid() then
        return false
    end
    local more = nextVisible(param, stats, bucket)
    if not more and saved ~= nil then
        pcall(copy, param, saved, bucket)
    end
    param:setResult(more)
    return true
end
