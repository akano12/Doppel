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
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

// Scripts call methods of objects of classes which declare a method with a type the ROM does not have,
// like ApplicationPackageManager of some Samsung ROMs with ArchivedPackageInfo of a later Android version,
// for which reflection on the class fails (see JavaClass.methodsOf)
public class LuaReflectionTest {
    public static class Base {
        public String resolve(String authority) {
            return "base:" + authority;
        }
    }

    public static class Missing {
    }

    public static class Rom extends Base {
        @Override
        public String resolve(String authority) {
            return "rom:" + authority;
        }

        public Missing archived() {
            return null;
        }
    }

    // Loads Base and Rom itself, like the framework classes of a ROM, without Missing
    private static class RomLoader extends ClassLoader {
        RomLoader() {
            super(LuaReflectionTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(Missing.class.getName()))
                throw new ClassNotFoundException(name);
            if (!name.equals(Base.class.getName()) && !name.equals(Rom.class.getName()))
                return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null)
                return loaded;
            try (InputStream is = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int n = is.read(buffer); n > 0; n = is.read(buffer))
                    bytes.write(buffer, 0, n);
                byte[] code = bytes.toByteArray();
                return defineClass(name, code, 0, code.length);
            } catch (Exception ex) {
                throw new ClassNotFoundException(name, ex);
            }
        }
    }

    @Test
    public void methodsOfClassesWithMissingTypesCanBeCalled() throws Exception {
        Class<?> rom = new RomLoader().loadClass(Rom.class.getName());
        // The condition of the ROM: reflection on the class itself fails
        try {
            rom.getDeclaredMethods();
            fail("Reflection should fail");
        } catch (LinkageError expected) {
        }

        Object manager = rom.getDeclaredConstructor().newInstance();
        Globals globals = JsePlatform.standardGlobals();
        globals.set("manager", CoerceJavaToLua.coerce(manager));
        // The method of the class it extends, which runs the method of the class, like resolveContentProvider
        LuaValue result = globals.load("return manager:resolve('contacts')").call();
        assertEquals("rom:contacts", result.tojstring());
    }

    // A Java byte[] a script gets or makes goes back to Java, like an empty MMS for Bundle.putByteArray
    // (see intent_createfromparcel)
    @Test
    public void byteArraysGoBackToJava() {
        Globals globals = JsePlatform.standardGlobals();
        globals.set("bytes", CoerceJavaToLua.coerce(new byte[3]));
        LuaValue result = globals.load("return luajava.bindClass('java.util.Arrays'):toString(bytes)").call();
        assertEquals("[0, 0, 0]", result.tojstring());
    }

    // An array of byte[] by the name of its class, like the empty messages of an SMS (see intent_createfromparcel)
    @Test
    public void byteArrayClassByName() {
        Globals globals = JsePlatform.standardGlobals();
        Object pdus = globals.load("return luajava.bindClass('java.lang.reflect.Array'):newInstance(luajava.bindClass('[B'), 0)")
                .call().touserdata();
        assertEquals(byte[][].class, pdus.getClass());
    }

    public interface Checker {
        boolean check(int value);
        long count();
        void run();
    }

    // A proxy without Lua functions for methods returning primitives: Proxy throws on null
    @Test
    public void proxyMethodsWithoutFunctions() {
        Globals globals = JsePlatform.standardGlobals();
        Checker checker = (Checker) globals.load(
                "return luajava.createProxy('" + Checker.class.getName() + "', {})").call().touserdata();
        assertEquals(false, checker.check(1));
        assertEquals(0L, checker.count());
        checker.run();
        assertEquals(System.identityHashCode(checker), checker.hashCode());
        assertEquals(true, checker.equals(checker));
        assertEquals(false, checker.equals(new Object()));
        assertEquals(true, checker.toString().startsWith("Proxy@"));
    }

    public interface Reader {
        String read() throws java.io.IOException;
    }

    public static String raise(String what) throws Exception {
        if ("io".equals(what))
            throw new java.io.IOException("io");
        if ("state".equals(what))
            throw new IllegalStateException("state");
        throw new Exception("checked");
    }

    private static Reader reader(String what) {
        return (Reader) JsePlatform.standardGlobals().load(
                "local test = luajava.bindClass('" + LuaReflectionTest.class.getName() + "')" +
                        " return luajava.createProxy('" + Reader.class.getName() + "'," +
                        " {read = function() " + (what == null ? "error('lua')" : "return test:raise('" + what + "')") + " end})")
                .call().touserdata();
    }

    // A Java exception thrown within a function of a proxy reaches the caller as itself, like a RemoteException of
    // a binder, which apps catch, when the interface method declares it or it is unchecked; else and for errors of
    // the script itself it stays a LuaError
    @Test
    public void proxyThrowsJavaExceptions() {
        try {
            reader("io").read();
            fail("No exception");
        } catch (java.io.IOException ex) {
            assertEquals("io", ex.getMessage());
        }
        try {
            reader("state").read();
            fail("No exception");
        } catch (Exception ex) {
            assertEquals(IllegalStateException.class, ex.getClass());
        }
        try {
            reader("checked").read();
            fail("No exception");
        } catch (Exception ex) {
            assertEquals(LuaError.class, ex.getClass());
        }
        try {
            reader(null).read();
            fail("No exception");
        } catch (Exception ex) {
            assertEquals(LuaError.class, ex.getClass());
            assertEquals(null, ex.getCause());
        }
    }

    // Scripts get no io, coroutine, package or file loading, and of os only the times
    @Test
    public void librariesOfScripts() {
        Globals globals = JsePlatform.standardGlobals();
        LuaValue result = globals.load("return io == nil and coroutine == nil and package == nil and require == nil" +
                " and dofile == nil and loadfile == nil and string.dump == nil" +
                " and os.execute == nil and os.getenv == nil and os.remove == nil and os.exit == nil" +
                " and type(os.time()) == 'number' and type(os.clock()) == 'number'" +
                " and os.difftime(2, 1) == 1 and os.date('%Y', 0) ~= nil" +
                " and load('return 1')() == 1 and bit32 ~= nil and luajava ~= nil").call();
        assertEquals(true, result.toboolean());
    }
}
