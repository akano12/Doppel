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
import org.luaj.vm2.lib.jse.CoerceLuaToJava;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

// The class caches of LuaJ must not keep the class loader of a plugin or a dynamic feature alive, and must still hit for process-long loaders
public class LuaClassCacheTest {
    public static class Probe {
        public int one() {
            return 1;
        }

        public Probe self(Probe other) {
            return other;
        }
    }

    // defines Probe itself, so the class belongs to this loader alone
    private static final class Loader extends ClassLoader {
        Loader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(Probe.class.getName()))
                return super.loadClass(name, resolve);
            synchronized (this) {
                Class<?> c = findLoadedClass(name);
                if (c == null)
                    try (InputStream in = LuaClassCacheTest.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        byte[] b = in.readAllBytes();
                        c = defineClass(name, b, 0, b.length);
                    } catch (IOException ex) {
                        throw new ClassNotFoundException(name, ex);
                    }
                return c;
            }
        }
    }

    // the Lua paths of a script: an instance, a method call, a parameter of the class, the class itself, bindClass of its name
    private static WeakReference<ClassLoader> touch() throws Exception {
        Loader loader = new Loader(LuaClassCacheTest.class.getClassLoader());
        Class<?> c = loader.loadClass(Probe.class.getName());
        Object o = c.getDeclaredConstructor().newInstance();
        Globals g = JsePlatform.standardGlobals();
        g.set("o", CoerceJavaToLua.coerce(o));
        g.set("c", CoerceJavaToLua.coerce(c));
        assertEquals(1, g.load("return o:one()").call().toint());
        g.set("p", g.load("return o:self(o)").call());
        assertSame(o, CoerceLuaToJava.coerce(g.get("p"), c));
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(loader);
        try {
            assertNotNull(g.load("return luajava.bindClass('" + Probe.class.getName() + "')").call());
        } finally {
            t.setContextClassLoader(old);
        }
        return new WeakReference<ClassLoader>(loader);
    }

    @Test
    public void loaderOfAPluginIsCollected() throws Exception {
        WeakReference<ClassLoader> ref = touch();
        long end = System.currentTimeMillis() + 10000;
        while (ref.get() != null && System.currentTimeMillis() < end) {
            System.gc();
            Thread.sleep(50);
        }
        assertNull("a cache keeps the class loader", ref.get());
    }

    // as on Android: the class of the app comes from a loader which is no ancestor of the system loader nor of the one of LuaJ
    @Test
    public void classesOfAnUnrelatedLoaderStayCachedWhileInUse() throws Exception {
        Loader app = new Loader(null);
        Class<?> c = app.loadClass(Probe.class.getName());
        LuaValue first = CoerceJavaToLua.coerce(c.getDeclaredConstructor().newInstance());
        LuaValue again = CoerceJavaToLua.coerce(c.getDeclaredConstructor().newInstance());
        assertSame(first.get("one"), again.get("one"));
        assertSame(CoerceJavaToLua.coerce(c), CoerceJavaToLua.coerce(c));
        assertSame(first.get("self"), again.get("self"));
    }

    // numbers only, run by hand: MEASURE=1
    @Test
    public void measure() throws Exception {
        if (System.getenv("MEASURE") == null)
            return;
        Globals g = JsePlatform.standardGlobals();
        LuaValue f = g.load("local o = ... return o:one()");
        for (int r = 0; r < 5; r++) {
            long t = System.nanoTime();
            int n = 0;
            for (int i = 0; i < 1000000; i++)
                n += f.call(CoerceJavaToLua.coerce(new Probe())).toint();
            System.out.println("MEASURE " + (System.nanoTime() - t) / 1000000 + " ns per coerce+call " + n);
        }
    }
}
