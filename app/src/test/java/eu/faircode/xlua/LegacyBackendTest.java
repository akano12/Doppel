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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

// The legacy backend must give the hooks the same before/after semantics as the modern one, see ModernBackendTest;
// the Xposed API of the unit tests runs a hooked method like XposedBridge
public class LegacyBackendTest {
    private LegacyBackend backend;
    private Method method;

    // Simulates the original method
    private Object[] originalArgs;
    private Object originalResult;
    private Throwable originalThrowable;

    @SuppressWarnings("unused")
    public String target(String a, int b) {
        return a + b;
    }

    @Before
    public void setUp() throws Exception {
        method = getClass().getMethod("target", String.class, int.class);
        originalResult = "original";
        XposedBridge.hooked = null;
        backend = new LegacyBackend();
    }

    private Object invoke(Object... args) throws Throwable {
        return XposedBridge.call(method, this, args, () -> {
            originalArgs = args.clone();
            if (originalThrowable instanceof Exception)
                throw (Exception) originalThrowable;
            return originalResult;
        });
    }

    @Test
    public void passThrough() throws Throwable {
        List<String> events = new ArrayList<>();
        backend.hook(method, new XBackend.Callback() {
            @Override
            void before(XCall call) {
                events.add("before");
                assertNull(call.getResult());
            }

            @Override
            void after(XCall call) {
                events.add("after");
                assertEquals("original", call.getResult());
                assertFalse(call.hasThrowable());
            }
        });
        assertEquals("original", invoke("a", 1));
        assertArrayEquals(new Object[]{"a", 1}, originalArgs);
        assertEquals(Arrays.asList("before", "after"), events);
    }

    @Test
    public void changeArgumentBefore() throws Throwable {
        backend.hook(method, new XBackend.Callback() {
            @Override
            void before(XCall call) {
                call.args[0] = "changed";
            }
        });
        invoke("a", 1);
        assertArrayEquals(new Object[]{"changed", 1}, originalArgs);
    }

    @Test
    public void resultBeforeSkipsOriginal() throws Throwable {
        List<String> events = new ArrayList<>();
        backend.hook(method, new XBackend.Callback() {
            @Override
            void before(XCall call) {
                call.setResult("fake");
            }

            @Override
            void after(XCall call) {
                events.add("after " + call.getResult());
            }
        });
        assertEquals("fake", invoke("a", 1));
        assertNull("Original method called", originalArgs);
        assertEquals(Arrays.asList("after fake"), events);
    }

    @Test
    public void resultAfter() throws Throwable {
        backend.hook(method, new XBackend.Callback() {
            @Override
            void after(XCall call) {
                call.setResult("fake");
            }
        });
        assertEquals("fake", invoke("a", 1));
        assertArrayEquals(new Object[]{"a", 1}, originalArgs);
    }

    @Test
    public void throwableBefore() throws Throwable {
        RuntimeException ex = new RuntimeException("privacy");
        backend.hook(method, new XBackend.Callback() {
            @Override
            void before(XCall call) {
                call.setThrowable(ex);
            }
        });
        try {
            invoke("a", 1);
            fail("No exception");
        } catch (RuntimeException thrown) {
            assertSame(ex, thrown);
        }
        assertNull("Original method called", originalArgs);
    }

    @Test
    public void originalThrowableVisibleAfter() throws Throwable {
        originalThrowable = new IllegalStateException("original");
        List<Throwable> seen = new ArrayList<>();
        backend.hook(method, new XBackend.Callback() {
            @Override
            void after(XCall call) {
                seen.add(call.getThrowable());
            }
        });
        try {
            invoke("a", 1);
            fail("No exception");
        } catch (IllegalStateException thrown) {
            assertSame(originalThrowable, thrown);
        }
        assertEquals(Arrays.asList(originalThrowable), seen);
    }

    @Test
    public void afterReplacesOriginalThrowable() throws Throwable {
        originalThrowable = new IllegalStateException("original");
        backend.hook(method, new XBackend.Callback() {
            @Override
            void after(XCall call) {
                call.setResult("recovered");
            }
        });
        assertEquals("recovered", invoke("a", 1));
    }

    @Test
    public void thisObject() throws Throwable {
        List<Object> seen = new ArrayList<>();
        backend.hook(method, new XBackend.Callback() {
            @Override
            void before(XCall call) {
                seen.add(call.thisObject);
                assertSame(method, call.method);
            }
        });
        invoke("a", 1);
        assertEquals(1, seen.size());
        assertSame(this, seen.get(0));
    }
}
