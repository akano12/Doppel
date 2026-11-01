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
import static org.junit.Assert.assertTrue;

// A hidden uid gets zero storage stats (assets/storage_stats_uid.lua); the zero StorageStats is built from a
// parcel of zero longs, stubbed here because Parcel and StorageStats are Android classes
public class StorageStatsUidScriptTest {
    private static final String STUBS = String.join("\n",
            // A parcel which counts the zero longs written and a StorageStats built from them
            "longs = 0",
            "luajava = { bindClass = function(name)",
            "  assert(name == 'android.os.Parcel', name)",
            "  return { obtain = function() return {",
            "    written = nil,",
            "    writeString = function(self, value) self.written = value end,",
            "    writeLong = function(self, value) longs = longs + 1 end,",
            "    setDataPosition = function(self, position) end,",
            "    readParcelable = function(self, loader) return self.written end,",
            "    recycle = function(self) end } end }",
            "end }",
            "function paramof(uid, visible, system)",
            "  return { result = nil, set = false,",
            "    isSystem = function(self) return system == true end,",
            "    isUidVisible = function(self, u) return visible[u] == true end,",
            "    getArgument = function(self, index) if index == 1 then return uid end end,",
            "    setResult = function(self, value) self.result = value self.set = true end }",
            "end",
            "function run(uid, visible, system)",
            "  longs = 0",
            "  local param = paramof(uid, visible, system)",
            "  local restricted = before({}, param)",
            "  return restricted, param.set, tostring(param.result), longs",
            "end",
            "VISIBLE = { [10200] = true }");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("storage_stats_uid", STUBS);
    }

    private Varargs run(String lua) {
        return Scripts.run(globals, "return run(" + lua + ")");
    }

    @Test
    public void hiddenUidGetsZeroStats() {
        Varargs result = run("10300, VISIBLE, false");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).toboolean());
        // readParcelable returns what writeString named, proving the class-name + zero-longs round trip
        assertEquals("android.app.usage.StorageStats", result.arg(3).tojstring());
        // At least as many longs as the most recent StorageStats reads
        assertTrue(result.arg(4).toint() >= 10);
    }

    @Test
    public void visibleUidUntouched() {
        Varargs result = run("10200, VISIBLE, false");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
    }

    @Test
    public void systemSeesAll() {
        Varargs result = run("10300, VISIBLE, true");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
    }
}
