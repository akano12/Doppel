/*
    This file is part of Doppel.

    Doppel is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Doppel is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Doppel.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2026 akano12
 */

package eu.faircode.xlua;

import org.junit.Before;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

// The data usage answers like for an app without usage access (assets/network_stats_filter.lua): buckets of other uids
// are skipped when an app iterates a NetworkStats, the device summary and the history of another uid are refused, and
// the total of the user is the app's own traffic; stand-ins play the role of NetworkStatsManager, NetworkStats and its
// Bucket, which are Android classes, and of the hook engine
public class NetworkStatsScriptTest {
    private static final String STUBS = String.join("\n",
            // A NetworkStats with a fixed sequence of uids, iterated like the real one: the bucket is left as it is
            // once the uids run out, and without a bucket to fill getNextBucket returns false and stays where it is.
            // Java fills the fields of a bucket, also of a subclass, which Lua cannot write.
            "function statsof(uids)",
            "  local stats = { uids = uids, pos = 0 }",
            "  function stats:getNextBucket(bucket)",
            "    if bucket == nil or self.pos >= #self.uids then return false end",
            "    self.pos = self.pos + 1",
            "    local uid = self.uids[self.pos]",
            "    rawset(bucket, 'mUid', uid)",
            "    rawset(bucket, 'mRxBytes', uid * 2)",
            "    rawset(bucket, 'mTxBytes', uid * 4)",
            "    rawset(bucket, 'mDefaultNetworkStatus', uid * 3)",
            "    return true",
            "  end",
            "  function stats:hasNextBucket() return self.pos < #self.uids end",
            "  return stats",
            "end",
            // A bucket with the private fields of Android 9 and later, at their Java defaults, and one of a subclass
            // an app passes, whose fields Lua does not see
            "function bucketof()",
            "  return { mUid = 0, mTag = 0, mState = 0, mDefaultNetworkStatus = 0, mMetered = 0, mRoaming = 0,",
            "    mBeginTimeStamp = 0, mEndTimeStamp = 0, mRxBytes = 0, mRxPackets = 0, mTxBytes = 0, mTxPackets = 0,",
            "    getUid = function(self) return self.mUid end,",
            "    getRxBytes = function(self) return self.mRxBytes end, getRxPackets = function(self) return self.mRxPackets end,",
            "    getTxBytes = function(self) return self.mTxBytes end, getTxPackets = function(self) return self.mTxPackets end }",
            "end",
            "function subbucketof()",
            "  return setmetatable({}, { __index = bucketof(),",
            "    __newindex = function(t, name) error('cannot set ' .. name .. ' for userdata') end })",
            "end",
            // The fields of Bucket by reflection, which REFUSED tells the hidden API rules refuse
            "REFUSED = false",
            "function fieldstub(name)",
            "  if REFUSED then error('NoSuchFieldException: ' .. name) end",
            "  local set = function(self, bucket, value) rawset(bucket, name, value) end",
            "  return { setAccessible = function() end, setInt = set, setLong = set }",
            "end",
            // Java copies the fields of Bucket, which reflection reaches all or none of
            "function copyfields(self, class, from, to)",
            "  if REFUSED or class ~= 'android.app.usage.NetworkStats$Bucket' then error('NoSuchFieldException') end",
            "  for name, value in pairs(bucketof()) do",
            "    if type(value) ~= 'function' then rawset(to, name, from[name]) end",
            "  end",
            "end",
            // The classes the script binds
            "luajava = { bindClass = function(name)",
            "    if name == 'java.lang.Class' then return { getDeclaredField = function(cls, name) return fieldstub(name) end } end",
            "    if name == 'android.os.Process' then return { myUid = function(self) return 10200 end } end",
            "    return {}",
            "  end,",
            "  newInstance = function(name, message)",
            "    if name == 'java.lang.SecurityException' then return { exception = name, message = message } end",
            "    return bucketof()",
            "  end }",
            // The values the engine keeps per object
            "VALUES = {}",
            "function paramof(stats, bucket, visible, system)",
            "  return { result = false, stats = stats, bucket = bucket,",
            "    isSystem = function(self) return system == true end,",
            "    getUid = function(self) return 10200 end,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value end,",
            "    getArgument = function(self, index) return self.bucket end,",
            "    getThis = function(self) return self.stats end,",
            "    isUidVisible = function(self, uid) return visible[uid] == true end,",
            "    unhooked = function(self, fn) return fn() end,",
            "    copyFields = copyfields,",
            "    getValue = function(self, name, scope) return VALUES[scope] and VALUES[scope][name] end,",
            "    putValue = function(self, name, value, scope)",
            "      VALUES[scope] = VALUES[scope] or {}",
            "      VALUES[scope][name] = value",
            "    end }",
            "end",
            // NetworkStatsManager, of which querySummary has the buckets of SUMMARY, or fails with FAILED, and is closed;
            // QUERIED counts the calls
            "SUMMARY, FAILED, CLOSED, QUERIED = {}, false, false, 0",
            "MANAGER = { querySummary = function(self, type, subscriber, from, to)",
            "    QUERIED = QUERIED + 1",
            "    if FAILED then error('RemoteException') end",
            "    local stats = statsof(SUMMARY)",
            "    function stats:close() CLOSED = true end",
            "    return stats",
            "  end }",
            // The context of the app, with the mode of the app op of usage access (0 allowed, 1 ignored, 3 default)
            // and the permission (0 granted)
            "MODE, PERMISSION = 3, -1",
            "CONTEXT = { getOpPackageName = function(self) return 'com.example' end,",
            "  getSystemService = function(self, name)",
            "    assert(name == 'appops', name)",
            "    return { checkOpNoThrow = function(self, op, uid, package) return MODE end }",
            "  end,",
            "  checkSelfPermission = function(self, name) return PERMISSION end }",
            // A query of the app on NetworkStatsManager, after Android answered with the result or the exception; a hidden
            // method of NetworkStats gets the NetworkStats
            "function ask(method, args, result, exception, system, this)",
            "  local param = paramof(nil, nil, {}, system)",
            "  param.result = result",
            "  param.getArgument = function(self, index) return args[index + 1] end",
            "  param.getException = function(self) return exception end",
            "  param.getThis = function(self) return this or MANAGER end",
            "  param.getApplicationContext = function(self) return CONTEXT end",
            "  after({ getMethodName = function(self) return method end }, param)",
            "  return param.result",
            "end",
            // The methods the script hooks itself with the function hook, per class like the hook engine does
            "DYNAMIC = {}",
            "function hook(object, method, fn) DYNAMIC[method] = fn end",
            // A call of the app the way the hook engine makes it: the hooks before, of which one setting the result
            // skips the real method, the real method, then the hooks after; OFF lists the methods of which the user
            // switched the hook off for the app
            "OFF = {}",
            "function call(stats, method, bucket, visible, system)",
            "  local param = paramof(stats, bucket, visible, system)",
            "  local h = { getMethodName = function(self) return method end }",
            "  local on = not OFF[method]",
            "  param.result = nil",
            "  if on and before then before(h, param) end",
            "  if param.result == nil and DYNAMIC[method] then DYNAMIC[method]('before', param) end",
            "  if param.result == nil then param.result = stats[method](stats, bucket) end",
            "  if DYNAMIC[method] then DYNAMIC[method]('after', param) end",
            "  if on then after(h, param) end",
            "  return param.result",
            "end",
            // One full enumeration the way most apps loop: on getNextBucket, reading the bucket only while one was
            // returned, or on hasNextBucket, reading each bucket and noting when getNextBucket returned none; there
            // are never more buckets than uids, so a loop which does not end shows as one more
            "function appbucket() return bucketof() end",
            "function seen(uids, visible, system)",
            "  local stats = statsof(uids)",
            "  local bucket = appbucket()",
            "  local list = {}",
            "  while call(stats, 'getNextBucket', bucket, visible, system) do",
            "    list[#list + 1] = bucket:getUid()",
            "  end",
            "  return table.concat(list, ',')",
            "end",
            "function looped(uids, visible, system)",
            "  local stats = statsof(uids)",
            "  local bucket = appbucket()",
            "  local list = {}",
            "  while #list <= #uids and call(stats, 'hasNextBucket', nil, visible, system) do",
            "    list[#list + 1] = call(stats, 'getNextBucket', bucket, visible, system) and bucket:getUid() or 'false'",
            "  end",
            "  return table.concat(list, ',')",
            "end",
            // The app itself is 10200; the uids the app sees with usage access, which are skipped: another app (10300),
            // the system (1000), removed apps (UID_REMOVED, -4) and the device (UID_ALL, -1); the hook engine tells only
            // other apps as hidden
            "VISIBLE = { [10200] = true, [1000] = true, [-4] = true, [-1] = true }");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("network_stats_filter", STUBS);
    }

    private String seen(String uids) {
        return Scripts.run(globals, "return seen(" + uids + ", VISIBLE, false)").arg1().tojstring();
    }

    // Without usage access the summary and details of all uids have only the app's own uid, not the system's, removed
    // apps' or the device's
    @Test
    public void hiddenBucketsSkipped() {
        assertEquals("10200", seen("{ 10200, 10300 }"));
        assertEquals("10200", seen("{ 10300, 10200, 10300, -1 }"));
        assertEquals("10200,10200", seen("{ 1000, 10200, -4, 10200, -5 }"));
    }

    @Test
    public void visibleBucketsStay() {
        assertEquals("10200,10200", seen("{ 10200, 10200 }"));
    }

    @Test
    public void onlyHiddenLooksEmpty() {
        // A per-uid query of another uid, respecting the returned boolean, shows no traffic
        assertEquals("", seen("{ 10300 }"));
        assertEquals("", seen("{ 10300, 10300 }"));
        assertEquals("", seen("{ 1000, -1 }"));
    }

    @Test
    public void systemSeesAll() {
        assertEquals("10300,10200", Scripts.run(globals,
                "return seen({ 10300, 10200 }, VISIBLE, true)").arg1().tojstring());
        assertEquals("10300,10200", Scripts.run(globals,
                "return looped({ 10300, 10200 }, VISIBLE, true)").arg1().tojstring());
    }

    private String looped(String uids) {
        return Scripts.run(globals, "return looped(" + uids + ", VISIBLE, false)").arg1().tojstring();
    }

    // On Android hasNextBucket is true when getNextBucket returns a bucket, so a loop on hasNextBucket sees the same
    // buckets as one on getNextBucket, and a query of a hidden uid has none at all
    @Test
    public void hasNextBucketAgrees() {
        assertEquals("", looped("{ 10300 }"));
        assertEquals("", looped("{ 10300, 10300 }"));
        assertEquals("10200", looped("{ 10200, 10300 }"));
        assertEquals("10200", looped("{ 10300, 10200, 10300, -1 }"));
        assertEquals("10200,10200", looped("{ 10200, 1000, 10200 }"));
        assertEquals("", looped("{ }"));
    }

    // With the hook of getNextBucket switched off for the app, the hook of hasNextBucket still hands out the bucket
    // it read ahead, so a loop on hasNextBucket ends and loses no bucket
    @Test
    public void getNextBucketOff() {
        Scripts.run(globals, "OFF = { getNextBucket = true }");
        assertEquals("10200,10200", looped("{ 10300, 10200, 10300, 10200 }"));
        assertEquals("10200", looped("{ 10200, -1 }"));
        assertEquals("", looped("{ 10300 }"));
    }

    // A subclass of Bucket an app passes gets the bucket read ahead, and is kept as it was after the last other uid,
    // through the fields of Bucket itself
    @Test
    public void bucketSubclass() {
        Scripts.run(globals, "function appbucket() return subbucketof() end");
        assertEquals("10200,10200", looped("{ 10300, 10200, 10300, 10200 }"));
        assertEquals("10200,10200", seen("{ 10300, 10200, 10300, 10200 }"));
        Varargs tail = Scripts.run(globals,
                "local bucket = subbucketof()\n" +
                        "return call(statsof({ 10300 }), 'getNextBucket', bucket, VISIBLE), bucket:getUid()");
        assertEquals("false,0", join(tail));
    }

    // The bucket read ahead is handed out whole, once, also when hasNextBucket is asked again first or getNextBucket
    // gets no bucket to fill, which on Android returns false and leaves the enumeration where it is
    @Test
    public void bucketReadAheadHandedOutOnce() {
        Varargs result = Scripts.run(globals,
                "local stats = statsof({ 10300, 10200, 10300 })\n" +
                        "local bucket = bucketof()\n" +
                        "local has = call(stats, 'hasNextBucket', nil, VISIBLE)\n" +
                        "local again = call(stats, 'hasNextBucket', nil, VISIBLE)\n" +
                        "local none = call(stats, 'getNextBucket', nil, VISIBLE)\n" +
                        "local next = call(stats, 'getNextBucket', bucket, VISIBLE)\n" +
                        "local uid, rx, status = bucket.mUid, bucket.mRxBytes, bucket.mDefaultNetworkStatus\n" +
                        "local after = call(stats, 'hasNextBucket', nil, VISIBLE)\n" +
                        "local last = call(stats, 'getNextBucket', bucket, VISIBLE)\n" +
                        "return has, again, none, next, uid, rx, status, after, last, bucket.mUid");
        assertEquals("true,true,false,true,10200,20400,30600,false,false,10200", join(result));
    }

    // Where reflection does not reach the fields of a bucket, hasNextBucket stays as it is, since the bucket read ahead
    // could not be handed out
    @Test
    public void reflectionRefused() {
        assertEquals("10200", looped("{ 10200, 10300 }"));
        Scripts.run(globals, "REFUSED = true");
        assertEquals("10200,false", looped("{ 10200, 10300 }"));
    }

    private static String join(Varargs values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= values.narg(); i++)
            sb.append(i == 1 ? "" : ",").append(values.arg(i).tojstring());
        return sb.toString();
    }

    // When the last buckets are all of other uids, getNextBucket returns false and leaves the bucket as it was before
    // the call, like Android does when there is no bucket left
    @Test
    public void hiddenTailIsKept() {
        Varargs result = Scripts.run(globals,
                "local bucket = bucketof()\n" +
                        "bucket.mUid, bucket.mRxBytes, bucket.mTxBytes, bucket.mRxPackets, bucket.mTag = 10200, 5, 6, 7, 9\n" +
                        "local more = call(statsof({ 10300, -4 }), 'getNextBucket', bucket, VISIBLE)\n" +
                        "return more, bucket.mUid, bucket.mRxBytes, bucket.mTxBytes, bucket.mRxPackets, bucket.mTag");
        assertEquals("false,10200,5,6,7,9", join(result));
        // A visible bucket at the end stays
        Varargs visible = Scripts.run(globals,
                "local bucket = bucketof()\n" +
                        "return call(statsof({ 10200 }), 'getNextBucket', bucket, VISIBLE), bucket.mUid");
        assertEquals("true", visible.arg1().tojstring());
        assertEquals("10200", visible.arg(2).tojstring());
    }

    // Without usage access the device summary throws the SecurityException of NetworkStatsCollection for UID_ALL; a
    // null of an unknown network type, an exception of Android and the system stay
    @Test
    public void deviceSummaryForbidden() {
        assertEquals("java.lang.SecurityException,Network stats history of uid -1 is forbidden for caller 10200", join(
                Scripts.run(globals, "local e = ask('querySummaryForDevice', { 1, nil, 0, 100 }, bucketof())\n" +
                        "return e.exception, e.message")));
        assertEquals("nil", Scripts.run(globals,
                "return tostring(ask('querySummaryForDevice', { 99, nil, 0, 100 }, nil))").arg1().tojstring());
        assertEquals("true", Scripts.run(globals, "local b = bucketof()\n" +
                "return ask('querySummaryForDevice', { 1, nil, 0, 100 }, b, 'RemoteException') == b").arg1().tojstring());
        assertEquals("true", Scripts.run(globals, "local b = bucketof()\n" +
                "return ask('querySummaryForDevice', { 1, nil, 0, 100 }, b, nil, true) == b").arg1().tojstring());
    }

    // The history of another uid, the system's and the device's too, throws the same, by each of the queries of a uid;
    // the app's own uid stays, and so does Android's exception for another uid with a tag
    @Test
    public void otherUidForbidden() {
        assertEquals("Network stats history of uid 1000 is forbidden for caller 10200", Scripts.run(globals,
                "return ask('queryDetailsForUid', { 1, nil, 0, 100, 1000 }, statsof({ 1000 })).message").arg1().tojstring());
        assertEquals("Network stats history of uid 10300 is forbidden for caller 10200", Scripts.run(globals,
                "return ask('queryDetailsForUidTag', { 1, nil, 0, 100, 10300, 0 }, statsof({ 10300 })).message")
                .arg1().tojstring());
        assertEquals("Network stats history of uid -1 is forbidden for caller 10200", Scripts.run(globals,
                "return ask('queryDetailsForUidTagState', { 0, nil, 0, 100, -1, 0, -1 }, statsof({ -1 })).message")
                .arg1().tojstring());
        assertEquals("true,true", join(Scripts.run(globals, "local s = statsof({ 10200 })\n" +
                "return ask('queryDetailsForUid', { 1, nil, 0, 100, 10200 }, s) == s,\n" +
                "  ask('queryDetailsForUidTag', { 1, nil, 0, 100, 10300, 7 }, nil, 'SecurityException') == nil")));
    }

    // The total of the user is the traffic of the app's own uid in the summary querySummaryForUser fetched, which
    // getSummaryAggregate sums into the bucket Android made, whose uid and times stay, without a second summary, also
    // for an app with usage access; a summary of only the app's uid, like without usage access, and the device summary,
    // whose enumeration is at its end, stay as Android summed them, and without the summary there is no traffic
    @Test
    public void userTotalIsOwn() {
        Varargs total = Scripts.run(globals, "MODE = 0\n" +
                "local b = bucketof()\n" +
                "b.mUid, b.mRxBytes, b.mTxBytes, b.mRxPackets, b.mBeginTimeStamp = -1, 999, 999, 9, 50\n" +
                "local r = ask('getSummaryAggregate', {}, b, nil, false, statsof({ 10200, 1000, 10200, -4, 10300 }))\n" +
                "local u = ask('querySummaryForUser', { 1, nil, 0, 100 }, b)\n" +
                "return r == b, u == b, b.mUid, b.mRxBytes, b.mTxBytes, b.mRxPackets, b.mTxPackets, b.mBeginTimeStamp, QUERIED");
        assertEquals("true,true,-1,40800,81600,0,0,50,0", join(total));
        assertEquals("999,999,999,999", join(Scripts.run(globals, "local b, d = bucketof(), bucketof()\n" +
                "b.mRxBytes, b.mTxBytes, d.mRxBytes, d.mTxBytes = 999, 999, 999, 999\n" +
                "ask('getSummaryAggregate', {}, b, nil, false, statsof({ 10200, 10200 }))\n" +
                "local device = statsof({ 10300, 10200 })\n" +
                "device.pos = 2\n" +
                "ask('getSummaryAggregate', {}, d, nil, false, device)\n" +
                "return b.mRxBytes, b.mTxBytes, d.mRxBytes, d.mTxBytes")));
        assertEquals("0,0", join(Scripts.run(globals, "local b = bucketof()\n" +
                "b.mRxBytes, b.mTxBytes = 999, 999\n" +
                "ask('getSummaryAggregate', {}, b, nil, false, { getNextBucket = function() error('DeadObjectException') end })\n" +
                "return b.mRxBytes, b.mTxBytes")));
        // The null of an unknown network type stays, and so does the bucket where reflection does not reach the fields
        assertFalse(Scripts.run(globals, "return ask('querySummaryForUser', { 99, nil, 0, 100 }, nil)").arg1().toboolean());
        assertEquals("999,999", join(Scripts.run(globals, "REFUSED = true\n" +
                "local b = bucketof()\n" +
                "b.mRxBytes, b.mTxBytes = 999, 999\n" +
                "ask('getSummaryAggregate', {}, b, nil, false, statsof({ 10200, 10300 }))\n" +
                "return b.mRxBytes, b.mTxBytes")));
    }

    // Where the hook of getSummaryAggregate did not run, an app with usage access, by the app op or by the permission,
    // gets the traffic of its own uid from a second summary, which is closed, and none when it fails; without usage
    // access the total of Android is the app's own already and no second summary is made
    @Test
    public void userTotalWithoutAggregate() throws Exception {
        String ask = "SUMMARY, CLOSED = { 10200, 1000, 10200, -4, 10300 }, false\n" +
                "local b = bucketof()\n" +
                "b.mRxBytes, b.mTxBytes = 999, 999\n" +
                "ask('querySummaryForUser', { 1, nil, 0, 100 }, b)\n" +
                "return b.mRxBytes, b.mTxBytes, QUERIED, CLOSED";
        assertEquals("40800,81600,1,true", join(Scripts.run(globals, "MODE = 0\n" + ask)));
        assertEquals("40800,81600,2,true", join(Scripts.run(globals, "MODE, PERMISSION = 3, 0\n" + ask)));
        assertEquals("999,999,2,false", join(Scripts.run(globals, "MODE, PERMISSION = 1, 0\n" + ask)));
        assertEquals("999,999,2,false", join(Scripts.run(globals, "MODE, PERMISSION = 3, -1\n" + ask)));
        assertEquals("0,0,3,false", join(Scripts.run(globals, "MODE, FAILED = 0, true\n" + ask)));
        // The check of usage access is the one of the events
        assertEquals(Scripts.function("usageevents_query", "granted"), Scripts.function("network_stats_filter", "granted"));
    }
}
