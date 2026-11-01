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

// Buckets of hidden uids are skipped when an app iterates a NetworkStats (assets/network_stats_filter.lua);
// stand-ins play the role of NetworkStats and its Bucket, which are Android classes
public class NetworkStatsScriptTest {
    private static final String STUBS = String.join("\n",
            // A NetworkStats with a fixed sequence of uids, iterated with getNextBucket like the real one:
            // the bucket is left as it is once the uids run out
            "function statsof(uids)",
            "  local stats = { uids = uids, pos = 0 }",
            "  function stats:getNextBucket(bucket)",
            "    self.pos = self.pos + 1",
            "    if self.pos > #self.uids then return false end",
            "    bucket.uid = self.uids[self.pos]",
            "    return true",
            "  end",
            "  return stats",
            "end",
            "function bucketof()",
            "  return { uid = -1, getUid = function(self) return self.uid end }",
            "end",
            "function paramof(stats, bucket, visible, system)",
            "  return { result = false, stats = stats, bucket = bucket,",
            "    isSystem = function(self) return system == true end,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value end,",
            "    getArgument = function(self, index) return self.bucket end,",
            "    getThis = function(self) return self.stats end,",
            "    isUidVisible = function(self, uid) return visible[uid] == true end,",
            "    unhooked = function(self, fn) return fn() end }",
            "end",
            // One full enumeration the way an app loops: call getNextBucket (which the hook wraps), let the hook
            // adjust the result and bucket, and read the bucket only while a bucket was returned
            "function seen(uids, visible, system)",
            "  local stats = statsof(uids)",
            "  local bucket = bucketof()",
            "  local param = paramof(stats, bucket, visible, system)",
            "  local list = {}",
            "  while true do",
            "    param.result = stats:getNextBucket(bucket)",
            "    after({}, param)",
            "    if not param.result then break end",
            "    list[#list + 1] = bucket.uid",
            "  end",
            "  return table.concat(list, ',')",
            "end",
            "function tail(uids, visible)",
            "  local stats = statsof(uids)",
            "  local bucket = bucketof()",
            "  local param = paramof(stats, bucket, visible, false)",
            "  param.result = stats:getNextBucket(bucket)",
            "  after({}, param)",
            "  return tostring(param.result), tostring(bucket.uid)",
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
    }

    // The known limit (a shortcut in the script): when the last buckets are all hidden, getNextBucket returns
    // false but leaves its stale uid in the bucket, which an app ignoring the boolean would read once
    @Test
    public void staleTailKnownLimit() {
        Varargs result = Scripts.run(globals, "return tail({ 10300 }, VISIBLE)");
        assertEquals("false", result.arg1().tojstring());
        assertEquals("10300", result.arg(2).tojstring());
    }
}
