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
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// The vendored LuaJ (org.luaj): what scripts and apps get from the engine in the cases of errors and limits
public class LuaJEngineTest {
    public interface Callback {
        Object value();

        int count();

        boolean ok();

        void run() throws IOException;

        void quiet();
    }

    public static class Java {
        public static void fail() {
            throw new IllegalStateException("from java");
        }

        public static void io() throws IOException {
            throw new IOException("from java");
        }

        public static String type(Object value) {
            return value.getClass().getSimpleName();
        }
    }

    public static class Initialized {
        static int initialized;

        static {
            initialized++;
        }
    }

    private static Globals globals() {
        return JsePlatform.standardGlobals();
    }

    private static Varargs run(Globals globals, String lua) {
        return globals.load(lua, "test").invoke();
    }

    private static Callback proxy(String functions) {
        return (Callback) run(globals(), "return luajava.createProxy('" + Callback.class.getName() + "', { " + functions + " })")
                .arg1().touserdata();
    }

    private static final String JAVA = "luajava.bindClass('" + Java.class.getName() + "')";

    // A function of a proxy with a bug of its own does not throw into the app, which does not expect a LuaError:
    // the app gets what a method without a function gets
    @Test
    public void proxyErrorOfTheScriptGivesDefaults() {
        Callback callback = proxy("value = function() error('bug') end, count = function() return nil + 1 end, " +
                "ok = function() local t = nil return t.x end, run = function() error('bug') end, " +
                "quiet = function() error({}) end");
        assertNull(callback.value());
        assertEquals(0, callback.count());
        assertFalse(callback.ok());
        callback.quiet();
    }

    @Test
    public void proxyStackOverflowGivesDefaults() {
        Callback callback = proxy("count = function() local function f() return 1 + f() end return f() end, " +
                "value = function() local function f() return 1 + f() end return f() end");
        assertEquals(0, callback.count());
        assertNull(callback.value());
    }

    // A wrong type of the answer is a bug of the script too
    @Test
    public void proxyWrongAnswerGivesDefaults() {
        Callback callback = proxy("count = function() return {} end");
        assertEquals(0, callback.count());
    }

    // The exception of a Java method the function called reaches the app as itself when the method may throw it
    @Test
    public void proxyJavaExceptionsReachTheApp() {
        Callback callback = proxy("run = function() " + JAVA + ":io() end, quiet = function() " + JAVA + ":fail() end, " +
                "value = function() " + JAVA + ":io() end");
        try {
            callback.run();
            fail("IOException");
        } catch (IOException expected) {
            assertEquals("from java", expected.getMessage());
        }
        try {
            callback.quiet();
            fail("IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals("from java", expected.getMessage());
        }
        // A checked exception the method does not declare is not thrown at the app
        assertNull(callback.value());
    }

    @Test
    public void proxyWorks() {
        Callback callback = proxy("count = function() return 7 end, ok = function() return true end");
        assertEquals(7, callback.count());
        assertTrue(callback.ok());
    }

    @Test
    public void formatOfLongs() {
        Varargs result = run(globals(), "return string.format('%d', 2^31), string.format('%d', 3e9), " +
                "string.format('%i', -2^40), string.format('%5d|%-5d|%05d', 42, 42, 42), string.format('%d', 2^53)");
        assertEquals("2147483648", result.arg(1).tojstring());
        assertEquals("3000000000", result.arg(2).tojstring());
        assertEquals("-1099511627776", result.arg(3).tojstring());
        assertEquals("   42|42   |00042", result.arg(4).tojstring());
        assertEquals("9007199254740992", result.arg(5).tojstring());
    }

    @Test
    public void formatOfOtherTypesAsText() {
        Varargs result = run(globals(), "return string.format('%s %s %s %s', true, false, nil, 12), " +
                "string.format('%s', setmetatable({}, { __tostring = function() return 'custom' end })), " +
                "string.format('%s', {}), pcall(string.format, '%s')");
        assertEquals("true false nil 12", result.arg(1).tojstring());
        assertEquals("custom", result.arg(2).tojstring());
        assertTrue(result.arg(3).tojstring().startsWith("table: "));
        // A missing argument is still an error
        assertFalse(result.arg(4).toboolean());
    }

    @Test
    public void repeatedStrings() {
        Varargs result = run(globals(), "return ('ab'):rep(0), ('ab'):rep(-3), (''):rep(1000000), ('ab'):rep(3), " +
                "pcall(string.rep, 'abcd', 2^30)");
        assertEquals("", result.arg(1).tojstring());
        assertEquals("", result.arg(2).tojstring());
        assertEquals("", result.arg(3).tojstring());
        assertEquals("ababab", result.arg(4).tojstring());
        // The size overflows an int: an error of Lua, not an empty string or a failure of the VM
        assertFalse(result.arg(5).toboolean());
        assertEquals("resulting string too large", result.arg(6).tojstring());
        result = run(globals(), "return pcall(string.rep, 'abc', 1431655766)");
        assertFalse(result.arg1().toboolean());
        assertEquals("resulting string too large", result.arg(2).tojstring());
    }

    @Test
    public void unpackIsLimited() {
        Varargs result = run(globals(), "return pcall(table.unpack, {}, 1, 1e9)");
        assertFalse(result.arg1().toboolean());
        assertTrue(result.arg(2).tojstring().startsWith("too many results to unpack"));
        result = run(globals(), "return select('#', table.unpack({ 1, 2, 3 })), select('#', table.unpack({}, 5, 1)), " +
                "pcall(table.unpack, {}, -2147483647, 2147483647)");
        assertEquals(3, result.arg(1).toint());
        assertEquals(0, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
    }

    // A number which is a string stays one in a table, also at a key which is no integer
    @Test
    public void tableValuesKeepTheirType() {
        Varargs result = run(globals(), "local t = {} t[1.5] = 1 t[1.5] = '5' return type(t[1.5]), t[1.5]");
        assertEquals("string", result.arg(1).tojstring());
    }

    @Test
    public void twelveHourClock() {
        Varargs result = run(globals(), "local function at(h) return os.date('%I', os.time{ year = 2020, month = 3, day = 4, hour = h }) end\n" +
                "return at(0), at(1), at(11), at(12), at(13), at(23), " +
                "os.date('%y', os.time{ year = 2005, month = 3, day = 4, hour = 5 }), " +
                "os.date('%y', os.time{ year = 2099, month = 3, day = 4, hour = 5 })");
        String[] expected = {"12", "01", "11", "12", "01", "11", "05", "99"};
        for (int i = 0; i < expected.length; i++)
            assertEquals("argument " + (i + 1), expected[i], result.arg(i + 1).tojstring());
    }

    @Test
    public void fourByteUtf8() throws Exception {
        byte[] smiley = {(byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80};
        assertEquals("😀", LuaString.valueOf(smiley).tojstring());
        // Among other characters
        byte[] mixed = "aé€".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[mixed.length + 4 + 1];
        System.arraycopy(mixed, 0, all, 0, mixed.length);
        System.arraycopy(smiley, 0, all, mixed.length, 4);
        all[all.length - 1] = 'z';
        assertEquals("aé€😀z", LuaString.valueOf(all).tojstring());
        // A literal of a script, which reaches Java as the character
        byte[] head = "return '".getBytes(StandardCharsets.US_ASCII);
        byte[] source = new byte[head.length + 4 + 1];
        System.arraycopy(head, 0, source, 0, head.length);
        System.arraycopy(smiley, 0, source, head.length, 4);
        source[source.length - 1] = '\'';
        Globals globals = globals();
        LuaValue literal = globals.load(new ByteArrayInputStream(source), "test", "t", globals).call();
        assertEquals("😀", literal.tojstring());
        // Strings from Java, with a surrogate pair, still round trip
        assertEquals("😀", LuaValue.valueOf("😀").tojstring());
        // A truncated sequence does not fail
        assertEquals(1, LuaString.valueOf(new byte[]{(byte) 0xF0, (byte) 0x9F}).tojstring().length());
    }

    @Test
    public void tooManyLocalsIsAnErrorOfLua() {
        StringBuilder lua = new StringBuilder();
        for (int i = 0; i < 201; i++)
            lua.append("local a").append(i).append(" = ").append(i).append("\n");
        try {
            globals().load(lua.toString(), "test");
            fail("LuaError");
        } catch (LuaError ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("local variables"));
        }
    }

    // A Java function called by a tail call fails like the same call without return: with a LuaError
    @Test
    public void tailCalledJavaFunctionRaisesLuaError() {
        Globals globals = globals();
        try {
            run(globals, "return ('abc'):find('[a')");
            fail("LuaError");
        } catch (LuaError expected) {
        }
        try {
            run(globals, "local s = ('abc'):find('[a')");
            fail("LuaError");
        } catch (LuaError expected) {
        }
        Varargs result = run(globals, "return pcall(function() return ('abc'):find('[a') end)");
        assertFalse(result.arg1().toboolean());
    }

    @Test
    public void pcallCatchesStackOverflow() {
        Globals globals = globals();
        Varargs result = run(globals, "local function f() return 1 + f() end return pcall(f)");
        assertFalse(result.arg1().toboolean());
        assertEquals("stack overflow", result.arg(2).tojstring());
        result = run(globals, "local function f() return 1 + f() end return xpcall(f, function(m) return m end)");
        assertFalse(result.arg1().toboolean());
        assertEquals("stack overflow", result.arg(2).tojstring());
        // The runtime still works
        assertEquals(3, run(globals, "return 1 + 2").arg1().toint());
    }

    public static class CountingLoader extends ClassLoader {
        final List<String> loaded = new ArrayList<>();

        CountingLoader() {
            super(LuaJEngineTest.class.getClassLoader());
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            loaded.add(name);
            return super.loadClass(name);
        }

        int count(String name) {
            int count = 0;
            for (String loaded : this.loaded)
                if (loaded.equals(name))
                    count++;
            return count;
        }
    }

    // The classes of luajava.bindClass and newInstance are looked up once per class loader and name
    @Test
    public void classesAreLookedUpOnce() {
        CountingLoader loader = new CountingLoader();
        Thread thread = Thread.currentThread();
        ClassLoader before = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            Globals globals = globals();
            String name = Initialized.class.getName();
            run(globals, "luajava.bindClass('" + name + "') luajava.bindClass('" + name + "') luajava.newInstance('" +
                    name + "')");
            assertEquals(1, loader.count(name));
            assertEquals(1, Initialized.initialized);
            // A failure is not cached: the same error again
            for (int i = 0; i < 2; i++)
                try {
                    run(globals, "luajava.bindClass('no.such.Class')");
                    fail("LuaError");
                } catch (LuaError expected) {
                }
            assertEquals(2, loader.count("no.such.Class"));
            // Another class loader is asked on its own
            CountingLoader other = new CountingLoader();
            thread.setContextClassLoader(other);
            run(globals, "luajava.bindClass('" + name + "')");
            assertEquals(1, other.count(name));
            assertEquals(1, loader.count(name));
        } finally {
            thread.setContextClassLoader(before);
        }
    }

    // A long of Java beyond int is a Long again for a parameter of Object, like an integer column of a cursor;
    // the others stay as before
    @Test
    public void longsStayLongs() {
        Varargs result = run(globals(), "local J = " + JAVA + "\n" +
                "local Long = 'java.lang.Long'\n" +
                "return J:type(luajava.newInstance(Long, 1795327214305)), J:type(luajava.newInstance(Long, 5)),\n" +
                "  J:type(luajava.newInstance(Long, 2147483648)), J:type(luajava.newInstance(Long, 9007199254740993)),\n" +
                "  J:type(3e9), J:type(5), J:type(0.5), J:type(2^53 + 2), J:type(luajava.newInstance(Long, 1795327214305) + 1)");
        String[] expected = {"Long", "Integer", "Long", "Long", "Double", "Integer", "Double", "Double", "Double"};
        for (int i = 0; i < expected.length; i++)
            assertEquals("argument " + (i + 1), expected[i], result.arg(i + 1).tojstring());
    }
}
