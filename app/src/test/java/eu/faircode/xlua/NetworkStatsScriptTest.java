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

// Buckets of hidden uids are skipped when an app iterates a NetworkStats (assets/network_stats_filter.lua);
// stand-ins play the role of NetworkStats and its Bucket, which are Android classes, and of the hook engine
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
            "    getUid = function(self) return self.mUid end }",
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
            "    return {}",
            "  end,",
            "  newInstance = function(name) return bucketof() end }",
            // The values the engine keeps per object
            "VALUES = {}",
            "function paramof(stats, bucket, visible, system)",
            "  return { result = false, stats = stats, bucket = bucket,",
            "    isSystem = function(self) return system == true end,",
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
            // Visible: the app itself (10200) and a device total (UID_ALL, -1); hidden: 10300
            "VISIBLE = { [10200] = true, [-1] = true }");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("network_stats_filter", STUBS);
    }

    private String seen(String uids) {
        return Scripts.run(globals, "return seen(" + uids + ", VISIBLE, false)").arg1().tojstring();
    }

    @Test
    public void hiddenBucketsSkipped() {
        assertEquals("10200", seen("{ 10200, 10300 }"));
        assertEquals("10200,-1", seen("{ 10300, 10200, 10300, -1 }"));
    }

    @Test
    public void visibleBucketsStay() {
        assertEquals("10200,-1", seen("{ 10200, -1 }"));
    }

    @Test
    public void onlyHiddenLooksEmpty() {
        // A per-uid query of a hidden uid, respecting the returned boolean, shows no traffic
        assertEquals("", seen("{ 10300 }"));
        assertEquals("", seen("{ 10300, 10300 }"));
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
        assertEquals("10200,-1", looped("{ 10300, 10200, 10300, -1 }"));
        assertEquals("10200,-1", looped("{ 10200, -1 }"));
        assertEquals("", looped("{ }"));
    }

    // With the hook of getNextBucket switched off for the app, the hook of hasNextBucket still hands out the bucket
    // it read ahead, so a loop on hasNextBucket ends and loses no bucket
    @Test
    public void getNextBucketOff() {
        Scripts.run(globals, "OFF = { getNextBucket = true }");
        assertEquals("10200,-1", looped("{ 10300, 10200, 10300, -1 }"));
        assertEquals("10200,-1", looped("{ 10200, -1 }"));
        assertEquals("", looped("{ 10300 }"));
    }

    // A subclass of Bucket an app passes gets the bucket read ahead, and the last hidden one cleared, through the
    // fields of Bucket itself
    @Test
    public void bucketSubclass() {
        Scripts.run(globals, "function appbucket() return subbucketof() end");
        assertEquals("10200,-1", looped("{ 10300, 10200, 10300, -1 }"));
        assertEquals("10200,-1", seen("{ 10300, 10200, 10300, -1 }"));
        Varargs tail = Scripts.run(globals,
                "local bucket = subbucketof()\n" +
                        "return call(statsof({ 10300 }), 'getNextBucket', bucket, VISIBLE), bucket:getUid()");
        assertEquals("false,-4", join(tail));
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

    // When the last buckets are all hidden, getNextBucket returns false and leaves the last one in the bucket, which
    // an app may read anyway: it becomes a bucket of removed apps (UID_REMOVED) without traffic
    @Test
    public void hiddenTailIsCleared() {
        Varargs result = Scripts.run(globals,
                "local bucket = bucketof()\n" +
                        "bucket.mRxBytes, bucket.mTxBytes, bucket.mRxPackets, bucket.mTxPackets, bucket.mTag = 5, 6, 7, 8, 9\n" +
                        "local more = call(statsof({ 10300 }), 'getNextBucket', bucket, VISIBLE)\n" +
                        "return more, bucket.mUid, bucket.mRxBytes + bucket.mTxBytes + bucket.mRxPackets +\n" +
                        "  bucket.mTxPackets + bucket.mTag");
        assertFalse(result.arg(1).toboolean());
        assertEquals(-4, result.arg(2).toint());
        assertEquals(0, result.arg(3).toint());
        // A visible bucket at the end stays
        Varargs visible = Scripts.run(globals,
                "local bucket = bucketof()\n" +
                        "return call(statsof({ 10200 }), 'getNextBucket', bucket, VISIBLE), bucket.mUid");
        assertEquals("true", visible.arg1().tojstring());
        assertEquals("10200", visible.arg(2).tojstring());
    }
}
