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
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Three scripts with Lua stand-ins for what they call: the copy of a cell of a cursor (contentresolver_query), the hidden
// columns of the cursor of the media provider (photo_location) and the read of a permission file (hardware_files)
public class CursorAndPermissionScriptsTest {
    // What MatrixCursor.RowBuilder.add(Object) takes
    public static class Sink {
        public final List<Object> cells = new ArrayList<>();

        public Sink add(Object value) {
            cells.add(value);
            return this;
        }
    }

    private static Object cell(Globals globals, String type, String value) {
        Varargs result = Scripts.run(globals,
                "local sink = luajava.newInstance('" + Sink.class.getName() + "')\n" +
                        "local cursor = { getType = function(self, index) return " + type + " end,\n" +
                        "  getLong = function(self, index) return " + value + " end,\n" +
                        "  getDouble = function(self, index) return " + value + " end }\n" +
                        "sink:add(cell(cursor, 0))\n" +
                        "return sink");
        return ((Sink) result.arg1().touserdata()).cells.get(0);
    }

    // An integer column stays an integer (Long) in the copy of the row: getType and getString of the copy are the ones
    // of the original, which a Double changes for a date in milliseconds
    @Test
    public void copiedCellsKeepTheirType() throws Exception {
        Globals globals = Scripts.load("contentresolver_query", null);
        assertEquals(1795327214305L, cell(globals, "1", "1795327214305"));
        assertEquals(2147483648L, cell(globals, "1", "2147483648"));
        assertEquals(-2147483649L, cell(globals, "1", "-2147483649"));
        assertEquals(5, cell(globals, "1", "5"));
        assertEquals(0.5, cell(globals, "2", "0.5"));
    }

    private static final String PHOTO_STUBS = String.join("\n",
            "function hookof(method)",
            "  return { getName = function(self) return method end, getMethodName = function(self) return method end,",
            "    getClassName = function(self) return 'android.content.ContentResolver' end,",
            "    getReturnType = function(self) return nil end }",
            "end",
            "function paramof(result, arguments)",
            "  local param = { result = result, args = arguments or {} }",
            "  param.getResult = function(self) return self.result end",
            "  param.setResult = function(self, value) self.result = value end",
            "  param.getArgument = function(self, index) return self.args[index] end",
            "  param.getException = function(self) return nil end",
            "  param.unhooked = function(self, f) return f() end",
            "  param.isInstance = function(self, object, name) return false end",
            "  return param",
            "end",
            "luajava.createProxy = function(interface, methods) return methods end",
            "luajava.newInstance = function(name, size) return { scratch = true } end",
            // The cursor of the provider, with its own errors for a closed one, one before the first row and no buffer
            "function cursor()",
            "  local names = { 'title', 'latitude', 'longitude' }",
            "  local real = { closed = false, position = -1 }",
            "  local function check(self)",
            "    if self.closed then error('StaleDataException') end",
            "    if self.position < 0 then error('CursorIndexOutOfBoundsException') end",
            "  end",
            "  real.getColumnIndex = function(self, n) for i, c in ipairs(names) do if c == n then return i - 1 end end return -1 end",
            "  real.getString = function(self, i) check(self) return tostring(52.5) end",
            "  real.getLong = function(self, i) check(self) return 52 end",
            "  real.getDouble = function(self, i) check(self) return 52.5 end",
            "  real.getType = function(self, i) check(self) return i == 0 and 3 or 2 end",
            "  real.isNull = function(self, i) check(self) return false end",
            "  real.copyStringToBuffer = function(self, i, buffer)",
            "    check(self)",
            "    if buffer == nil then error('IllegalArgumentException: CharArrayBuffer should not be null') end",
            "    self.copied = buffer",
            "    buffer.sizeCopied = 4",
            "  end",
            "  real.close = function(self) self.closed = true end",
            "  return real",
            "end",
            "function proxied(real)",
            "  local media = paramof(real, { [0] = { getAuthority = function(self) return 'media' end } })",
            "  assert(after(hookof('query'), media))",
            "  return media.result",
            "end");

    private static boolean fails(Globals globals, String call, String error) {
        Varargs result = Scripts.run(globals, "local ok, message = pcall(function() return " + call + " end) return ok, message");
        return !result.arg1().toboolean() && result.arg(2).tojstring().contains(error);
    }

    // The hidden columns ask the real cursor first, which raises its own exception where stock does, and answer like a
    // null column otherwise; the buffer of the app is never given to the real cursor, so it gets no text of the column
    @Test
    public void hiddenColumnsBehaveLikeStock() throws Exception {
        Globals globals = Scripts.load("photo_location", PHOTO_STUBS + "\n" + Scripts.ARRAYOF);
        Scripts.run(globals, "c = proxied(cursor())");
        // Before the first row
        for (String call : new String[]{"c.getString(1)", "c.getLong(1)", "c.getDouble(2)", "c.getType(1)", "c.isNull(1)",
                "c.copyStringToBuffer(1, { sizeCopied = 9 })"})
            assertTrue(call, fails(globals, call, "CursorIndexOutOfBoundsException"));
        // On a row
        Scripts.run(globals, "real = cursor() real.position = 0 c = proxied(real)");
        Varargs result = Scripts.run(globals, "local buffer = { sizeCopied = 9 }\n" +
                "c.copyStringToBuffer(1, buffer)\n" +
                "return c.getString(1), c.getLong(1), c.getDouble(2), c.getType(1), c.isNull(2), buffer.sizeCopied, " +
                "real.copied ~= buffer, c.getString(0)");
        assertTrue(result.arg(1).isnil());
        assertEquals(0, result.arg(2).toint());
        assertEquals(0, result.arg(3).toint(), 0);
        assertEquals(0, result.arg(4).toint());
        assertTrue(result.arg(5).toboolean());
        assertEquals(0, result.arg(6).toint());
        assertTrue(result.arg(7).toboolean());
        assertEquals("52.5", result.arg(8).tojstring());
        // A missing buffer: the exception of the cursor, no error of Lua
        assertTrue(fails(globals, "c.copyStringToBuffer(1, nil)", "CharArrayBuffer should not be null"));
        assertTrue(fails(globals, "c.copyStringToBuffer(0, nil)", "CharArrayBuffer should not be null"));
        // A closed cursor
        Scripts.run(globals, "real:close()");
        for (String call : new String[]{"c.getString(1)", "c.getLong(2)", "c.getType(1)", "c.isNull(1)",
                "c.copyStringToBuffer(1, { sizeCopied = 9 })", "c.getString(0)"})
            assertTrue(call, fails(globals, call, "StaleDataException"));
    }

    // The permission file is read without the hooks of the app and without StrictMode, which is back after the read
    // also when it fails
    @Test
    public void permissionFileIsReadWithoutStrictMode() throws Exception {
        Globals globals = Scripts.load("hardware_files", null);
        Varargs result = Scripts.run(globals,
                "log = {}\n" +
                        "luajava.bindClass = function(name)\n" +
                        "  assert(name == 'android.os.StrictMode')\n" +
                        "  return { allowThreadDiskReads = function(self) log[#log + 1] = 'allow' return 'policy' end,\n" +
                        "    setThreadPolicy = function(self, policy) log[#log + 1] = 'restore ' .. policy end }\n" +
                        "end\n" +
                        "local function param(read)\n" +
                        "  return { unhooked = function(self, f) log[#log + 1] = 'unhooked' return f() end,\n" +
                        "    readFile = function(self, path) log[#log + 1] = 'read' return read() end,\n" +
                        "    getPackageName = function(self) return 'app' end }\n" +
                        "end\n" +
                        "local path = '/vendor/etc/permissions/carrierwifi.xml'\n" +
                        "local named = privappfile(path, param(function() return '<permissions package=\"com.other\"/>' end))\n" +
                        "local first = table.concat(log, ',')\n" +
                        "log = {}\n" +
                        "local broken = privappfile(path, param(function() error('unreadable') end))\n" +
                        "local second = table.concat(log, ',')\n" +
                        "return named, first, broken, second");
        assertTrue(result.arg(1).toboolean());
        assertEquals("unhooked,allow,read,restore policy", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
        assertEquals("unhooked,allow,read,restore policy", result.arg(4).tojstring());
    }
}
