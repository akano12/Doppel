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

import org.junit.Test;
import org.luaj.vm2.Varargs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The boot offset: elapsedRealtime (and its nanos) read higher by the app's offset, so the boot time differs
// per app; an elapsed alarm has the offset taken back off so it still fires on time; /proc/stat and /proc/uptime
// kept matching. The offset (value.boot_offset) is 1800000 ms (30 min) here.
public class BootOffsetScriptTest {
    private static final String STUBS = String.join("\n",
            "function hookof(method)",
            "  return { getMethodName = function(self) return method end }",
            "end",
            // t: result, offset (string, the setting), args (1-based: arg0,arg1,...), text, opensfile
            "function paramof(t)",
            "  return { result = t.result, text = t.text, args = t.args or {}, set = false,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, v) self.result = v self.set = true end,",
            "    getException = function(self) return nil end,",
            "    getSetting = function(self, name) if name == 'value.boot_offset' then return t.offset end return nil end,",
            "    getArgument = function(self, i) return self.args[i + 1] end,",
            "    setArgument = function(self, i, v) self.args[i + 1] = v self.set = true end,",
            "    getFileText = function(self) return self.text end,",
            "    setFileText = function(self, v) self.text = v self.set = true end,",
            "    opens = function(self, file) return file == t.opensfile end }",
            "end");

    private static Varargs run(String script, String lua) throws Exception {
        return Scripts.run(Scripts.load(script, STUBS), lua);
    }

    // The time since boot read higher by the offset, in ms and in ns
    @Test
    public void elapsed() throws Exception {
        Varargs ms = run("boot_offset",
                "local p = paramof({ result = 1000, offset = '1800000' })\n" +
                        "local ok, old, new = after(hookof('elapsedRealtime'), p)\n" +
                        "return ok, old, new, tostring(p.result)");
        assertTrue(ms.arg(1).toboolean());
        assertEquals("1000", ms.arg(2).tojstring());
        assertEquals("1801000", ms.arg(3).tojstring());
        assertEquals("1801000", ms.arg(4).tojstring());

        Varargs ns = run("boot_offset",
                "local p = paramof({ result = 1000000000, offset = '1800000' })\n" +
                        "local ok = after(hookof('elapsedRealtimeNanos'), p)\n" +
                        "return ok, tostring(p.result)");
        assertTrue(ns.arg(1).toboolean());
        // 1e9 ns + 1800000 ms in ns (1.8e12)
        assertEquals("1801000000000", ns.arg(2).tojstring());

        // The process start takes the same ms offset, so elapsedRealtime minus it does not reveal it
        Varargs start = run("boot_offset",
                "local p = paramof({ result = 2000, offset = '1800000' })\n" +
                        "local ok = after(hookof('getStartElapsedRealtime'), p)\n" +
                        "return ok, tostring(p.result)");
        assertTrue(start.arg(1).toboolean());
        assertEquals("1802000", start.arg(2).tojstring());
    }

    // A location stamp is shifted onto the same clock (offset added), in ns and in ms; the age methods are not hooked,
    // they cancel the offset on their own (shifted clock minus shifted stamp)
    @Test
    public void location() throws Exception {
        Varargs nanos = run("boot_offset_loc",
                "local p = paramof({ result = 1000000000, offset = '1800000' })\n" +
                        "local ok = after(hookof('getElapsedRealtimeNanos'), p)\n" +
                        "return ok, tostring(p.result)");
        assertTrue(nanos.arg(1).toboolean());
        assertEquals("1801000000000", nanos.arg(2).tojstring());

        Varargs millis = run("boot_offset_loc",
                "local p = paramof({ result = 1000, offset = '1800000' })\n" +
                        "local ok, old, new = after(hookof('getElapsedRealtimeMillis'), p)\n" +
                        "return ok, old, new, tostring(p.result)");
        assertTrue(millis.arg(1).toboolean());
        assertEquals("1000", millis.arg(2).tojstring());
        assertEquals("1801000", millis.arg(3).tojstring());
        assertEquals("1801000", millis.arg(4).tojstring());

        // An app that stamps its own fix with the shifted clock has the offset taken back off, so the field is real
        Varargs setter = run("boot_offset_loc",
                "local p = paramof({ args = { 1801000000000 }, offset = '1800000' })\n" +
                        "local ok, old, new = before(hookof('setElapsedRealtimeNanos'), p)\n" +
                        "return ok, old, new, tostring(p.args[1])");
        assertTrue(setter.arg(1).toboolean());
        assertEquals("1801000000000", setter.arg(2).tojstring());
        assertEquals("1000000000", setter.arg(3).tojstring());
        assertEquals("1000000000", setter.arg(4).tojstring());

        // No offset: nothing changes
        Varargs none = run("boot_offset_loc",
                "local p = paramof({ result = 1000 })\n" +
                        "return after(hookof('getElapsedRealtimeMillis'), p), p.set");
        assertFalse(none.arg(1).toboolean());
        assertFalse(none.arg(2).toboolean());
    }

    // No offset set: nothing changes
    @Test
    public void elapsedNoOffset() throws Exception {
        Varargs r = run("boot_offset",
                "local p = paramof({ result = 1000 })\n" +
                        "return after(hookof('elapsedRealtime'), p), p.set");
        assertFalse(r.arg(1).toboolean());
        assertFalse(r.arg(2).toboolean());
    }

    // An elapsed alarm (type 2 or 3) has the offset taken off the trigger; a wall-clock alarm (type 0 or 1) is left
    @Test
    public void alarm() throws Exception {
        Varargs elapsed = run("boot_offset_alarm",
                "local p = paramof({ args = { 3, 5000000 }, offset = '1800000' })\n" +
                        "local ok, old, new = before(hookof('setExact'), p)\n" +
                        "return ok, old, new, tostring(p.args[2])");
        assertTrue(elapsed.arg(1).toboolean());
        assertEquals("5000000", elapsed.arg(2).tojstring());
        assertEquals("3200000", elapsed.arg(3).tojstring());
        assertEquals("3200000", elapsed.arg(4).tojstring());

        Varargs rtc = run("boot_offset_alarm",
                "local p = paramof({ args = { 0, 5000000 }, offset = '1800000' })\n" +
                        "return before(hookof('set'), p), p.set, tostring(p.args[2])");
        assertFalse(rtc.arg(1).toboolean());
        assertFalse(rtc.arg(2).toboolean());
        assertEquals("5000000", rtc.arg(3).tojstring());
    }

    // /proc/stat btime drops the offset (seconds); /proc/uptime gains it; another file is left
    @Test
    public void proc() throws Exception {
        Varargs stat = run("boot_offset_proc",
                "local p = paramof({ result = 'fd', offset = '1800000', opensfile = '/proc/stat',\n" +
                        "  text = 'cpu  1 2 3\\nbtime 1700000000\\nprocesses 100\\n' })\n" +
                        "return after(hookof('open'), p), p.text");
        assertTrue(stat.arg(1).toboolean());
        assertEquals("cpu  1 2 3\nbtime 1699998200\nprocesses 100\n", stat.arg(2).tojstring());

        Varargs uptime = run("boot_offset_proc",
                "local p = paramof({ result = 'fd', offset = '1800000', opensfile = '/proc/uptime',\n" +
                        "  text = '123456.78 98765.43\\n' })\n" +
                        "return after(hookof('open'), p), p.text");
        assertTrue(uptime.arg(1).toboolean());
        assertEquals("125256.78 98765.43\n", uptime.arg(2).tojstring());

        Varargs other = run("boot_offset_proc",
                "local p = paramof({ result = 'fd', offset = '1800000', opensfile = '/proc/stat',\n" +
                        "  text = 'no boot time here\\n' })\n" +
                        "return after(hookof('open'), p), p.set");
        assertFalse(other.arg(1).toboolean());
        assertFalse(other.arg(2).toboolean());
    }
}
