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

import java.io.File;
import java.io.FileInputStream;
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
    private boolean targetThrows; // The original method makes its exception itself, under the call

    @SuppressWarnings("unused")
    public String target(String a, int b) {
        if (targetThrows)
            throw new IllegalStateException("original", new IllegalArgumentException("cause"));
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
        StackTraceElement[] made = originalThrowable.getStackTrace();
        try {
            invoke("a", 1);
            fail("No exception");
        } catch (IllegalStateException thrown) {
            assertSame(originalThrowable, thrown);
            // Not made under the call, so it has no frames of the hook to lose
            assertArrayEquals(made, thrown.getStackTrace());
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

    // An exception the original method makes goes from the method to its caller, like on a device without Doppel,
    // and so does its cause
    @Test
    public void originalExceptionWithoutHookFrames() throws Throwable {
        targetThrows = true;
        backend.hook(method, new XBackend.Callback() {
        });
        StackTraceElement[] direct = new Throwable().getStackTrace();
        try {
            new Stub().target("a", 1);
            fail("No exception");
        } catch (IllegalStateException thrown) {
            for (Throwable ex : new Throwable[]{thrown, thrown.getCause()}) {
                StackTraceElement[] trace = ex.getStackTrace();
                assertEquals(getClass().getName() + ".target", trace[0].getClassName() + "." + trace[0].getMethodName());
                assertEquals("originalExceptionWithoutHookFrames", trace[1].getMethodName());
                assertArrayEquals(Arrays.copyOfRange(direct, 1, direct.length), Arrays.copyOfRange(trace, 2, trace.length));
            }
        }
    }

    // The frames of a hook on the rooted Android 14 emulator: LSPosed calls the original method by its obfuscated
    // HookBridge and chain, under the interceptor of Doppel, which the chain calls from the stub LSPlant made
    private static final StackTraceElement HOOK_BRIDGE = new StackTraceElement(
            "FDus.iyU.MFKg.xv.xLh.jysjwrGot.qXz.HookBridge", "invokeExactOriginalMethod", null, -2);
    private static final StackTraceElement PROCEED_ORIGINAL = new StackTraceElement("m", "proceed", "r8-map-id-5f3c", 82);
    private static final StackTraceElement PROCEED_HOOKER = new StackTraceElement("m", "proceed", "r8-map-id-5f3c", 149);
    private static final StackTraceElement DOPPEL = new StackTraceElement(
            "eu.faircode.xlua.ModernBackend", "lambda$hook$0", "ModernBackend.java", 63);
    private static final StackTraceElement DOPPEL_INTERCEPT = new StackTraceElement(
            "eu.faircode.xlua.ModernBackend$$ExternalSyntheticLambda0", "intercept", "D8$$SyntheticClass", 0);
    private static final StackTraceElement PROCEED_CHAIN = new StackTraceElement("m", "proceed", "r8-map-id-5f3c", 35);
    private static final StackTraceElement CALLBACK = new StackTraceElement("r0", "callback", "r8-map-id-5f3c", 24);
    private static final StackTraceElement[] CALLER = {
            new StackTraceElement("com.example.probe.MainActivity", "check", "MainActivity.java", 42),
            new StackTraceElement("android.app.Activity", "performCreate", "Activity.java", 8595),
    };

    private static StackTraceElement stub(String method) {
        return new StackTraceElement("java.io.Jinti", method, "Jinti.java", -1);
    }

    private static StackTraceElement[] frames(Object... parts) {
        List<StackTraceElement> frames = new ArrayList<>();
        for (Object part : parts)
            if (part instanceof StackTraceElement[])
                frames.addAll(Arrays.asList((StackTraceElement[]) part));
            else
                frames.add((StackTraceElement) part);
        return frames.toArray(new StackTraceElement[0]);
    }

    // The stack in the interceptor, below the frame which makes the exception of the original method
    private static StackTraceElement[] here(StackTraceElement[] trace, StackTraceElement interceptor) {
        int at = Arrays.asList(trace).indexOf(interceptor);
        StackTraceElement[] here = Arrays.copyOfRange(trace, at, trace.length);
        here[0] = new StackTraceElement(interceptor.getClassName(), interceptor.getMethodName(), interceptor.getFileName(), 78);
        return here;
    }

    @Test
    public void hookFramesOfDevice() throws Throwable {
        StackTraceElement[] top = {
                new StackTraceElement("java.io.UnixFileSystem", "canonicalize0", null, -2),
                new StackTraceElement("java.io.UnixFileSystem", "canonicalize", "UnixFileSystem.java", 151),
                new StackTraceElement("java.io.File", "getCanonicalPath", "File.java", 618),
        };
        StackTraceElement[] trace = frames(top, HOOK_BRIDGE, PROCEED_ORIGINAL, PROCEED_HOOKER, DOPPEL, DOPPEL_INTERCEPT,
                PROCEED_CHAIN, CALLBACK, stub("getCanonicalPath"), CALLER);
        Method member = File.class.getMethod("getCanonicalPath");
        assertArrayEquals(frames(top, CALLER), ModernBackend.unhooked(trace, here(trace, DOPPEL), member));

        // A release build renames the synthetic frames of Doppel, to the bridge a and the class a1
        StackTraceElement release = new StackTraceElement("eu.faircode.xlua.ModernBackend", "lambda$hook$0", "SourceFile", 63);
        trace = frames(top, HOOK_BRIDGE, PROCEED_ORIGINAL, PROCEED_HOOKER, release,
                new StackTraceElement("eu.faircode.xlua.ModernBackend", "a", "SourceFile", 0),
                new StackTraceElement("eu.faircode.xlua.a1", "intercept", "R8$$SyntheticClass", 0),
                PROCEED_CHAIN, CALLBACK, stub("getCanonicalPath"), CALLER);
        assertArrayEquals(frames(top, CALLER), ModernBackend.unhooked(trace, here(trace, release), member));

        // Without the stub only the frames of Doppel go
        trace = frames(top, HOOK_BRIDGE, PROCEED_ORIGINAL, PROCEED_HOOKER, DOPPEL, DOPPEL_INTERCEPT,
                PROCEED_CHAIN, CALLBACK, stub("other"), CALLER);
        assertArrayEquals(frames(top, PROCEED_CHAIN, CALLBACK, stub("other"), CALLER),
                ModernBackend.unhooked(trace, here(trace, DOPPEL), member));
    }

    @Test
    public void hookFramesOfConstructor() throws Throwable {
        StackTraceElement[] top = {
                new StackTraceElement("libcore.io.IoBridge", "open", "IoBridge.java", 574),
                new StackTraceElement("java.io.FileInputStream", "<init>", "FileInputStream.java", 160),
        };
        StackTraceElement[] trace = frames(top, HOOK_BRIDGE, PROCEED_ORIGINAL, PROCEED_HOOKER, DOPPEL, DOPPEL_INTERCEPT,
                PROCEED_CHAIN, CALLBACK, stub("constructor"), CALLER);
        assertArrayEquals(frames(top, CALLER),
                ModernBackend.unhooked(trace, here(trace, DOPPEL), FileInputStream.class.getConstructor(File.class)));
    }

    // A hooked method called by another one: the inner hook leaves the frames of the outer one, which removes them next
    @Test
    public void hookFramesOfNestedHooks() throws Throwable {
        StackTraceElement[] top = {
                new StackTraceElement("java.io.UnixFileSystem", "canonicalize0", null, -2),
                new StackTraceElement("java.io.File", "getCanonicalPath", "File.java", 618),
        };
        StackTraceElement outer = new StackTraceElement("java.io.File", "getCanonicalFile", "File.java", 643);
        StackTraceElement inner = new StackTraceElement(
                "eu.faircode.xlua.ModernBackend", "lambda$hook$0", "ModernBackend.java", 64); // Told apart by the line
        StackTraceElement[] trace = frames(top, HOOK_BRIDGE, PROCEED_ORIGINAL, PROCEED_HOOKER, inner, DOPPEL_INTERCEPT,
                PROCEED_CHAIN, CALLBACK, stub("getCanonicalPath"),
                outer, HOOK_BRIDGE, PROCEED_ORIGINAL, PROCEED_HOOKER, DOPPEL, DOPPEL_INTERCEPT,
                PROCEED_CHAIN, CALLBACK, stub("getCanonicalFile"), CALLER);
        StackTraceElement[] once = ModernBackend.unhooked(trace, here(trace, inner), File.class.getMethod("getCanonicalPath"));
        assertArrayEquals(frames(top, outer, HOOK_BRIDGE, PROCEED_ORIGINAL, PROCEED_HOOKER, DOPPEL, DOPPEL_INTERCEPT,
                PROCEED_CHAIN, CALLBACK, stub("getCanonicalFile"), CALLER), once);
        assertArrayEquals(frames(top, outer, CALLER),
                ModernBackend.unhooked(once, here(trace, DOPPEL), File.class.getMethod("getCanonicalFile")));
    }

    // An exception made by a script has no frame of the method, one made under an earlier call of the hook from
    // elsewhere in the app does not end with the frames below this call
    @Test
    public void hookFramesKeptOfOtherExceptions() throws Throwable {
        Method member = File.class.getMethod("getCanonicalPath");
        StackTraceElement[] script = frames(
                new StackTraceElement("org.luaj.vm2.lib.jse.LuajavaLib", "invoke", "LuajavaLib.java", 200),
                new StackTraceElement("eu.faircode.xlua.XCore$5", "before", "XCore.java", 563),
                DOPPEL, DOPPEL_INTERCEPT, PROCEED_CHAIN, CALLBACK, stub("getCanonicalPath"), CALLER);
        assertNull(ModernBackend.unhooked(script, here(script, DOPPEL), member));

        StackTraceElement[] trace = frames(new StackTraceElement("java.io.File", "getCanonicalPath", "File.java", 618),
                HOOK_BRIDGE, PROCEED_ORIGINAL, PROCEED_HOOKER, DOPPEL, DOPPEL_INTERCEPT,
                PROCEED_CHAIN, CALLBACK, stub("getCanonicalPath"), CALLER);
        StackTraceElement[] other = frames(new StackTraceElement("java.io.File", "getCanonicalPath", "File.java", 618),
                HOOK_BRIDGE, PROCEED_ORIGINAL, PROCEED_HOOKER, DOPPEL, DOPPEL_INTERCEPT, PROCEED_CHAIN, CALLBACK,
                stub("getCanonicalPath"), new StackTraceElement("com.example.probe.MainActivity", "onCreate", "MainActivity.java", 30),
                CALLER[1]);
        assertNull(ModernBackend.unhooked(other, here(trace, DOPPEL), member));
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

    // Stands in for the stub LSPlant names after the hooked method
    private class Stub {
        @SuppressWarnings("UnusedReturnValue")
        Object target(Object... args) throws Throwable {
            return intercept(args);
        }
    }

    // Stands in for the frame of the class D8 makes for the lambda of the interceptor, which the JVM hides
    private Object intercept(Object... args) throws Throwable {
        return invoke(args);
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
            if (targetThrows)
                return target((String) args[0], (int) args[1]);
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
