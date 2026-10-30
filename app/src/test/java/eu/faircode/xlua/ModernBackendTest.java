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

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

// The modern backend must provide the before/after semantics of the legacy Xposed API
public class ModernBackendTest {
    private XposedInterface.Hooker hooker;
    private Object exceptionMode; // Of the hook, see throwableBefore
    private ModernBackend backend;
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

        XposedInterface.HookBuilder builder = (XposedInterface.HookBuilder) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookBuilder.class},
                (proxy, m, args) -> {
                    if ("intercept".equals(m.getName())) {
                        hooker = (XposedInterface.Hooker) args[0];
                        return null;
                    }
                    if ("setExceptionMode".equals(m.getName()))
                        exceptionMode = args[0];
                    return proxy; // setPriority, setExceptionMode, setId
                });
        XposedInterface xposed = (XposedInterface) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedInterface.class},
                (proxy, m, args) -> {
                    if ("hook".equals(m.getName())) {
                        assertSame(method, args[0]);
                        return builder;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        backend = new ModernBackend(xposed);
    }

    private Object invoke(Object... args) throws Throwable {
        return hooker.intercept(new FakeChain(this, args));
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
                // Like legacy Xposed, after is called with the early result
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
        // The protective mode of the framework would swallow the exception and call the original method
        assertEquals(XposedInterface.ExceptionMode.PASSTHROUGH, exceptionMode);
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

    private class FakeChain implements XposedInterface.Chain {
        private final Object thisObject;
        private final Object[] args;

        FakeChain(Object thisObject, Object[] args) {
            this.thisObject = thisObject;
            this.args = args;
        }

        @Override
        public Executable getExecutable() {
            return method;
        }

        @Override
        public Object getThisObject() {
            return thisObject;
        }

        @Override
        public List<Object> getArgs() {
            return Arrays.asList(args.clone());
        }

        @Override
        public Object getArg(int index) {
            return args[index];
        }

        @Override
        public Object proceed() throws Throwable {
            return proceed(args);
        }

        @Override
        public Object proceed(Object[] args) throws Throwable {
            originalArgs = args.clone();
            if (originalThrowable != null)
                throw originalThrowable;
            return originalResult;
        }

        @Override
        public Object proceedWith(Object thisObject) throws Throwable {
            return proceed(args);
        }

        @Override
        public Object proceedWith(Object thisObject, Object[] args) throws Throwable {
            return proceed(args);
        }
    }
}
