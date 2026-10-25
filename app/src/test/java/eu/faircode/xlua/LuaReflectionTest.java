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
}
