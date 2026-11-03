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
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class LuaJavaMembersTest {
    // Objects of classes whose private members desktop Java does not open, like java.net.URI
    // (the tests open only java.io, java.util and java.lang.reflect), can be used like on Android
    @Test
    public void objectsOfClosedPackagesCanBeUsed() {
        Globals globals = JsePlatform.standardGlobals();
        LuaValue result = globals.load("return luajava.newInstance('java.net.URI', 'content://contacts/people'):getPath()").call();
        assertEquals("/people", result.tojstring());
    }

    public static class Missing {
    }

    public static class Top {
        public Missing top;
    }

    public static class Middle extends Top {
        public String middle = "middle";
    }

    public static class Bottom extends Middle {
        public Missing bottom;
    }

    // Loads Top, Middle and Bottom itself, like the framework classes of a ROM, without Missing
    private static class RomLoader extends ClassLoader {
        RomLoader() {
            super(LuaJavaMembersTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(Missing.class.getName()))
                throw new ClassNotFoundException(name);
            if (!name.equals(Top.class.getName()) && !name.equals(Middle.class.getName()) && !name.equals(Bottom.class.getName()))
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

    // Reflection on the fields fails for the class and for a class higher up (see JavaClass.fieldsOf),
    // the fields of the class between remain
    @Test
    public void fieldsBetweenClassesWithMissingTypesCanBeRead() throws Exception {
        ClassLoader loader = new RomLoader();
        Class<?> bottom = loader.loadClass(Bottom.class.getName());
        for (Class<?> k : new Class<?>[]{bottom, loader.loadClass(Top.class.getName())})
            try {
                k.getDeclaredFields();
                fail("Reflection should fail");
            } catch (LinkageError expected) {
            }

        Globals globals = JsePlatform.standardGlobals();
        globals.set("object", CoerceJavaToLua.coerce(bottom.getDeclaredConstructor().newInstance()));
        assertEquals("middle", globals.load("return object.middle").call().tojstring());
    }

    public static class Overloads {
        public String run(String... words) {
            return "varargs";
        }

        public String run() {
            return "fixed";
        }
    }

    // A call without arguments runs the method without varargs, which a varargs method ties with, in either order
    // of the methods of the class, like ProcessBuilder.command() and command(String...)
    @Test
    public void methodWithoutVarargsWinsATie() {
        Globals globals = JsePlatform.standardGlobals();
        globals.set("object", CoerceJavaToLua.coerce(new Overloads()));
        globals.set("builder", CoerceJavaToLua.coerce(new ProcessBuilder("getprop")));
        assertEquals("fixed", globals.load("return object:run()").call().tojstring());
        assertEquals("getprop", globals.load("return builder:command():get(0)").call().tojstring());
    }
}
