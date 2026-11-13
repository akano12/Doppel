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

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.security.net.config.NetworkSecurityConfigProvider;

import com.android.providers.settings.SettingsProvider;

import org.junit.Before;
import org.junit.Test;
import org.luaj.vm2.Prototype;
import org.luaj.vm2.compiler.LuaC;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.File;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// Hooks installed by the engine, with a backend which records them
public class XCoreTest {
    private static XBackend backend(String name) {
        return new XBackend() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public void hook(Member member, Callback callback) {
            }

            @Override
            public void log(String message) {
            }

            @Override
            public void log(Throwable ex) {
            }
        };
    }

    // The claim keeps two entry points from both hooking a process, and is gone before the code of the app runs
    @Test
    public void claimIsReleasedBeforeTheAppRuns() {
        XCore.release();
        assertTrue(XCore.claim(backend("first")));
        assertEquals("first", System.getProperty("eu.faircode.xlua.engine"));
        assertTrue(XCore.claim(backend("first")));
        assertFalse(XCore.claim(backend("second")));

        XCore.release();
        assertNull(System.getProperty("eu.faircode.xlua.engine"));
    }

    // A framework of the modern API which counts the hooks and the detaches of the module
    private final List<Executable> hooked = new ArrayList<>();
    private int detached = 0;

    private XLuaModule module() {
        XposedInterface.HookBuilder builder = (XposedInterface.HookBuilder) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookBuilder.class},
                (proxy, m, args) -> ("intercept".equals(m.getName()) ? null : proxy));
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedInterface.class},
                (proxy, m, args) -> {
                    if ("hook".equals(m.getName())) {
                        hooked.add((Executable) args[0]);
                        return builder;
                    }
                    if ("getApiVersion".equals(m.getName()))
                        return XposedInterface.API_102;
                    // The default value of the type, null for objects and void
                    return (m.getReturnType().isPrimitive() && m.getReturnType() != void.class
                            ? Array.get(Array.newInstance(m.getReturnType(), 1), 0) : null);
                });
        XLuaModule module = new XLuaModule();
        module.attachFramework(framework, () -> detached++);
        module.onModuleLoaded((XposedModuleInterface.ModuleLoadedParam) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedModuleInterface.ModuleLoadedParam.class},
                (proxy, m, args) -> ("getProcessName".equals(m.getName()) ? "test.app" : false)));
        return module;
    }

    private XposedModuleInterface.PackageReadyParam ready(String packageName, boolean first) {
        return (XposedModuleInterface.PackageReadyParam) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedModuleInterface.PackageReadyParam.class},
                (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "getPackageName":
                            return packageName;
                        case "isFirstPackage":
                            return first;
                        case "getClassLoader":
                        case "getDefaultClassLoader":
                            return getClass().getClassLoader();
                        default:
                            return null;
                    }
                });
    }

    // The modern entry point claims a process right before it hooks, not when it is loaded: the AppComponentFactory
    // of an app runs before onPackageReady and would read the claim; after the release it does not claim again
    @Test
    public void modernClaimWaitsForTheFirstHook() {
        XCore.release();
        XLuaModule module = module();
        assertNull(System.getProperty("eu.faircode.xlua.engine"));

        module.onPackageReady(ready("test.app", true));
        assertEquals("modern", System.getProperty("eu.faircode.xlua.engine"));
        assertFalse(hooked.isEmpty());

        XCore.release();
        module.onPackageReady(ready("test.app", true));
        module.onPackageReady(ready("com.google.android.webview", false));
        assertNull(System.getProperty("eu.faircode.xlua.engine"));
        assertEquals(0, detached);
    }

    // A process the legacy entry point claimed first is left to it, without any hook of the modern one
    @Test
    public void modernEntryLeavesALegacyClaim() {
        XCore.release();
        XLuaModule module = module();
        assertTrue(XCore.claim(backend("legacy")));

        module.onPackageReady(ready("test.app", true));
        module.onPackageReady(ready("test.app", true));
        assertTrue(hooked.isEmpty());
        assertEquals(1, detached);
        assertEquals("legacy", System.getProperty("eu.faircode.xlua.engine"));
        XCore.release();
    }

    // The application is made after the hook of Android 11 and later returned, so the constructor, static initializer
    // and attachBaseContext of the app do not run inside a hook; before Android 11 newApplication is hooked
    @Test
    public void applicationIsMadeOutsideTheHook() throws Throwable {
        core.hookApplication("test.app", getClass().getClassLoader());
        assertEquals(Collections.singletonList(
                NetworkSecurityConfigProvider.class.getDeclaredMethod("handleNewApplication", Context.class)), members);

        // Installed when the network security config was made, with the context of the application, and the claim
        // released before
        assertTrue(XCore.claim(backend("test")));
        XCall made = new XCall(members.get(0), null, new Object[]{context});
        callbacks.get(0).before(made);
        assertEquals("test", System.getProperty("eu.faircode.xlua.engine"));
        callbacks.get(0).after(made);
        assertNull(System.getProperty("eu.faircode.xlua.engine"));

        members.clear();
        core.hookApplication("test.app", new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (NetworkSecurityConfigProvider.class.getName().equals(name))
                    throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        });
        assertFalse(members.isEmpty());
        for (Member member : members)
            assertEquals("newApplication", member.getName());

        // Also when the framework cannot hook the method of Android 11, else the app would run without any hook
        List<String> names = new ArrayList<>();
        new XCore(new XBackend() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public void hook(Member member, Callback callback) {
                if (member.getDeclaringClass() == NetworkSecurityConfigProvider.class)
                    throw new IllegalStateException("Cannot hook");
                names.add(member.getName());
            }

            @Override
            public void log(String message) {
            }

            @Override
            public void log(Throwable ex) {
            }
        }).hookApplication("test.app", getClass().getClassLoader());
        assertFalse(names.isEmpty());
        for (String name : names)
            assertEquals("newApplication", name);
    }

    private static StackTraceElement frame(String cls, String method) {
        return new StackTraceElement(cls, method, null, -1);
    }

    private static List<String> frames(Throwable ex) {
        List<String> names = new ArrayList<>();
        for (StackTraceElement frame : ex.getStackTrace())
            names.add(frame.getClassName() + "." + frame.getMethodName());
        return names;
    }

    // Android 9 and later send the first five frames of a SecurityException of the settings provider to any app,
    // which end with the caller of the provider like without a hook, also after the hook of call or query
    @Test
    public void settingsErrorsWithoutHookFrames() throws Throwable {
        core.hookSettings(getClass().getClassLoader());
        assertEquals(2, members.size());
        String provider = SettingsProvider.class.getName();
        String[][] hooks = {{"call", "android.content.ContentProvider"}, {"query", "android.content.ContentProvider$Transport"}};
        for (int h = 0; h < hooks.length; h++) {
            SecurityException ex = new SecurityException("Permission denial");
            ex.setStackTrace(new StackTraceElement[]{
                    frame(provider, "enforceHasAtLeastOnePermission"),
                    frame(provider, "mutateGlobalSetting"),
                    frame(provider, hooks[h][0]),
                    frame("a.b.HookBridge", "invokeExactOriginalMethod"),
                    frame("m", "proceed"),
                    frame("eu.faircode.xlua.ModernBackend", "lambda$hook$0"),
                    frame("r0", "callback"),
                    frame(hooks[h][1], hooks[h][0]),
                    frame("android.os.Binder", "execTransact")});
            XCall call = new XCall(members.get(h), new SettingsProvider(), new Object[5]);
            call.setOutcome(null, ex);
            callbacks.get(h).after(call);
            assertEquals(Arrays.asList(provider + ".enforceHasAtLeastOnePermission", provider + ".mutateGlobalSetting",
                    provider + "." + hooks[h][0], hooks[h][1] + "." + hooks[h][0], "android.os.Binder.execTransact"),
                    frames(ex));
        }

        // Errors of the service itself have no frame of the provider and are kept as they are
        IllegalArgumentException ex = new IllegalArgumentException("selection invalid");
        List<String> own = frames(ex);
        XCall call = new XCall(members.get(1), new SettingsProvider(), new Object[5]);
        call.setThrowable(ex);
        callbacks.get(1).after(call);
        assertEquals(own, frames(ex));
    }

    // When the service fails, an app it did not answer as one with assignments gets the answer of the settings provider,
    // like without Doppel (an error for these columns); an app it did gets no answer, which its hooks report as an error
    @Test
    public void failedServiceAnswersAssignedAppsOnly() throws Throwable {
        core.hookSettings(getClass().getClassLoader());
        SettingsProvider provider = new SettingsProvider();
        // The package manager refuses, so the caller is neither system server nor the app (see permittedCallers)
        provider.context = new ContextWrapper(null) {
            @Override
            public PackageManager getPackageManager() {
                throw new SecurityException("Not the app");
            }
        };
        // A database which fails, like one which cannot be opened
        Constructor<XDatabase> broken = XDatabase.class.getDeclaredConstructor(SQLiteDatabase.class);
        broken.setAccessible(true);
        Field db = XProvider.class.getDeclaredField("db");
        db.setAccessible(true);
        db.set(null, broken.newInstance((Object) null));
        // The stubs of Android give the caller uid 0
        try {
            for (int h = 0; h < 2; h++) {
                Object[] args = (h == 0 ? new Object[]{XProvider.METHOD, "getAssignedHooks2", null}
                        : new Object[]{null, new String[]{XProvider.COLUMN + "getAssignedHooks2"}, null, null, null});
                XCall other = new XCall(members.get(h), provider, args);
                callbacks.get(h).before(other);
                assertFalse(other.isReturnEarly());

                XCore.answered(0, XProvider.CALLER_ASSIGNED);
                XCall assigned = new XCall(members.get(h), provider, args);
                callbacks.get(h).before(assigned);
                assertTrue(assigned.isReturnEarly());
                assertNull(assigned.getResult());
                XCore.answered(0, XProvider.CALLER_NONE);
            }
        } finally {
            XCore.answered(0, XProvider.CALLER_NONE);
            db.set(null, null);
        }
    }

    private final List<Member> members = new ArrayList<>();
    private final List<XBackend.Callback> callbacks = new ArrayList<>();
    private XCore core;
    private Context context;

    @SuppressWarnings("unused")
    public static class Target {
        public String get() {
            return "real";
        }

        public void inherited() {
        }

        public String open(String path) {
            return "real";
        }
    }

    public static class First extends Target {
    }

    public static class Second extends Target {
    }

    @Before
    public void setUp() {
        core = new XCore(new XBackend() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public void hook(Member member, Callback callback) {
                members.add(member);
                callbacks.add(callback);
            }

            @Override
            public void log(String message) {
            }

            @Override
            public void log(Throwable ex) {
            }
        });

        ApplicationInfo ai = new ApplicationInfo();
        ai.uid = 10123;
        context = new ContextWrapper(null) {
            @Override
            public String getPackageName() {
                return "test.app";
            }

            @Override
            public ApplicationInfo getApplicationInfo() {
                return ai;
            }
        };
    }

    // Like XCore.install, without the class lookup through the class loader of an app
    private void install(String name, String script, Member member) throws Exception {
        XHook hook = XHook.fromJSON("{\"collection\": \"Test\", \"group\": \"Test.Group\", \"name\": \"" + name + "\"," +
                " \"author\": \"Test\", \"className\": \"" + Target.class.getName() + "\", \"parameterTypes\": []," +
                " \"minSdk\": 1, \"usage\": false, \"luaScript\": \"\"}");
        Prototype prototype = LuaC.instance.compile(
                new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)), "script");
        Method installMethod = XCore.class.getDeclaredMethod("installMethod",
                Context.class, XHook.class, Prototype.class, Map.class, Member.class);
        installMethod.setAccessible(true);
        installMethod.invoke(core, context, hook, prototype, new HashMap<String, String>(), member);
    }

    // Definitions for a range of app versions; an app with a versionCodeMajor has a version code beyond an int
    @Test
    public void apkVersions() throws Throwable {
        String json = "{\"collection\": \"Test\", \"group\": \"Test.Group\", \"name\": \"versions\"," +
                " \"author\": \"Test\", \"className\": \"java.lang.Object\", \"parameterTypes\": []," +
                " \"minSdk\": 1, \"luaScript\": \"\"";
        XHook all = XHook.fromJSON(json + "}");
        assertTrue(all.isAvailable(0));
        assertTrue(all.isAvailable(Integer.MAX_VALUE));
        assertTrue(all.isAvailable(1L << 32));
        XHook range = XHook.fromJSON(json + ", \"minApk\": 10, \"maxApk\": 20}");
        assertFalse(range.isAvailable(9));
        assertTrue(range.isAvailable(10));
        assertTrue(range.isAvailable(20));
        assertFalse(range.isAvailable(21));
        assertFalse(range.isAvailable(1L << 32));
    }

    // Excluded packages by their patterns, compiled on first use, also of a copy, by threads at once
    @Test
    public void excludedPackages() throws Throwable {
        XHook hook = XHook.fromJSON(XHook.fromJSON("{\"collection\": \"Test\", \"group\": \"Test.Group\"," +
                " \"name\": \"excluded\", \"author\": \"Test\", \"className\": \"java.lang.Object\"," +
                " \"parameterTypes\": [], \"minSdk\": 0, \"excludePackages\": \"com\\\\.google\\\\..*, android\"," +
                " \"luaScript\": \"\"}").toJSON());
        List<String> collection = java.util.Collections.singletonList("Test");
        Thread[] threads = new Thread[8];
        final Throwable[] failed = new Throwable[1];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    assertFalse(hook.isAvailable("com.google.android.gms", collection));
                    assertFalse(hook.isAvailable("android", collection));
                    assertTrue(hook.isAvailable("com.example", collection));
                } catch (Throwable ex) {
                    failed[0] = ex;
                }
            });
            threads[i].start();
        }
        for (Thread thread : threads)
            thread.join();
        if (failed[0] != null)
            throw failed[0];
    }

    // A script which fails keeps its runtime, which is not made, and so run, again for each call
    @Test
    public void failingScriptKeepsRuntime() throws Throwable {
        Method get = Target.class.getMethod("get");
        install("failingScriptKeepsRuntime",
                "local calls = 0\n" +
                        "function before(hook, param)\n" +
                        "  calls = calls + 1\n" +
                        "  if calls == 1 then error('fails') end\n" +
                        "  param:setResult(tostring(calls))\n" +
                        "  return true\n" +
                        "end", get);

        XCall failed = new XCall(get, new Target(), new Object[0]);
        callbacks.get(0).before(failed);
        assertFalse(failed.isReturnEarly());

        // A new runtime would fail again
        XCall call = new XCall(get, new Target(), new Object[0]);
        callbacks.get(0).before(call);
        assertEquals("2", call.getResult());
    }

    // The first call on a new thread makes only the globals of the script, not the libraries, which all threads
    // share, so it costs about what later calls cost; the locals of the script stay per thread
    @Test
    public void librariesSharedByThreads() throws Throwable {
        Method get = Target.class.getMethod("get");
        install("librariesSharedByThreads",
                "local calls = 0\n" +
                        "function before(hook, param)\n" +
                        "  calls = calls + 1\n" +
                        "  param:setResult(tostring(string) .. ' ' .. calls)\n" +
                        "  return true\n" +
                        "end", get);

        XCall own = new XCall(get, new Target(), new Object[0]);
        callbacks.get(0).before(own);
        callbacks.get(0).before(own);
        String libraries = ((String) own.getResult()).split(" ")[1];
        assertTrue(own.getResult().toString(), own.getResult().toString().endsWith(" 2"));

        XCall other = new XCall(get, new Target(), new Object[0]);
        Thread thread = new Thread(() -> {
            try {
                callbacks.get(0).before(other);
            } catch (Throwable ex) {
                throw new RuntimeException(ex);
            }
        });
        thread.start();
        thread.join();
        assertEquals("table: " + libraries + " 1", other.getResult());
    }

    @Test
    public void scriptRuns() throws Throwable {
        Method get = Target.class.getMethod("get");
        install("scriptRuns", "function before(hook, param) param:setResult('fake') return true end", get);
        assertEquals(1, callbacks.size());

        XCall call = new XCall(get, new Target(), new Object[0]);
        callbacks.get(0).before(call);
        assertEquals("fake", call.getResult());
    }

    // In system server the hooks of "android" do not apply to the service and the engine itself
    @Test
    public void notInService() throws Throwable {
        Method get = Target.class.getMethod("get");
        install("notInService", "function before(hook, param) param:setResult('fake') return true end", get);

        XCall call = new XCall(get, new Target(), new Object[0]);
        XCore.enterService();
        try {
            callbacks.get(0).before(call);
        } finally {
            XCore.leaveService();
        }
        assertNull(call.getResult());
        assertFalse(call.isReturnEarly());

        // Only while in the service
        callbacks.get(0).before(call);
        assertEquals("fake", call.getResult());
    }

    // A script with a filter runs for calls with one of its values as the first argument only
    @Test
    public void filteredScript() throws Throwable {
        Method open = Target.class.getMethod("open", String.class);
        install("filteredScript",
                "function filter(hook) return { '/proc/meminfo' } end\n" +
                        "function before(hook, param) param:setResult('fake') return true end", open);

        XCall other = new XCall(open, new Target(), new Object[]{"/data/file"});
        callbacks.get(0).before(other);
        assertFalse(other.isReturnEarly());

        XCall filtered = new XCall(open, new Target(), new Object[]{"/proc/meminfo"});
        callbacks.get(0).before(filtered);
        assertEquals("fake", filtered.getResult());

        // Other forms of the path, which the file system reads the same
        for (String path : new String[]{"/proc/./meminfo", "/proc//meminfo", "//proc/meminfo",
                "/proc/self/root/proc/meminfo", "/proc/meminfo/."}) {
            XCall call = new XCall(open, new Target(), new Object[]{path});
            callbacks.get(0).before(call);
            assertEquals(path, "fake", call.getResult());
        }

        // A null path is no value of the filter
        XCall none = new XCall(open, new Target(), new Object[]{null});
        callbacks.get(0).before(none);
        assertFalse(none.isReturnEarly());
    }

    // A value ending in * stands for all values starting with the text before it
    @Test
    public void prefixFilter() throws Throwable {
        Method open = Target.class.getMethod("open", String.class);
        install("prefixFilter",
                "function filter(hook) return { '/sys/*', '/proc/meminfo' } end\n" +
                        "function before(hook, param) param:setResult('fake') return true end", open);

        for (String path : new String[]{"/sys/devices/system/chip-id/unique_id", "/sys/", "/proc/meminfo",
                "/sys/devices/system/./cpu/possible", "/proc/self/root/sys/devices",
                "/proc/self/task/1234/root/proc/self/root/sys/devices", "sys/devices", "proc/meminfo",
                "proc/./meminfo"}) {
            XCall call = new XCall(open, new Target(), new Object[]{path});
            callbacks.get(0).before(call);
            assertEquals(path, "fake", call.getResult());
        }
        for (String path : new String[]{"/system/build.prop", "/sys", "/proc/meminfo2", "/data/sys/", "proc/meminfo2"}) {
            XCall call = new XCall(open, new Target(), new Object[]{path});
            callbacks.get(0).before(call);
            assertFalse(path, call.isReturnEarly());
        }
    }

    // A value starting and ending with * stands for all values containing the text between
    @Test
    public void containsFilter() throws Throwable {
        Method open = Target.class.getMethod("open", String.class);
        install("containsFilter",
                "function filter(hook) return { '*sys/*' } end\n" +
                        "function before(hook, param) param:setResult('fake') return true end", open);

        for (String path : new String[]{"/sys/devices/system/chip-id/unique_id", "/proc/self/root/sys/block",
                "sys/devices", "/data/../sys/class"}) {
            XCall call = new XCall(open, new Target(), new Object[]{path});
            callbacks.get(0).before(call);
            assertEquals(path, "fake", call.getResult());
        }
        for (String path : new String[]{"/system/build.prop", "/sys", "/proc/meminfo"}) {
            XCall call = new XCall(open, new Target(), new Object[]{path});
            callbacks.get(0).before(call);
            assertFalse(path, call.isReturnEarly());
        }
    }

    // Like the scripts of the kernel version and the font configuration, of which /./ is in the middle
    @Test
    public void containsFilterOtherPaths() throws Throwable {
        Method open = Target.class.getMethod("open", String.class);
        install("containsFilterOtherPaths",
                "function filter(hook) return { '*kernel/osrelease*', '/system/etc/fonts.xml' } end\n" +
                        "function before(hook, param) param:setResult('fake') return true end", open);

        for (String path : new String[]{"/proc/sys/kernel/./osrelease", "/proc/sys/kernel//osrelease",
                "/system/etc/./fonts.xml", "/system//etc/fonts.xml", "/system/etc/fonts.xml"}) {
            XCall call = new XCall(open, new Target(), new Object[]{path});
            callbacks.get(0).before(call);
            assertEquals(path, "fake", call.getResult());
        }
        XCall other = new XCall(open, new Target(), new Object[]{"/system/etc/./fonts.xml2"});
        callbacks.get(0).before(other);
        assertFalse(other.isReturnEarly());
    }

    // Only the file system can resolve /../, so such paths run the script with any filter, and the link of the working
    // directory of an app leads to the root
    @Test
    public void parentFoldersRunTheScript() throws Throwable {
        Method open = Target.class.getMethod("open", String.class);
        install("parentFoldersRunTheScript",
                "function filter(hook) return { '/dev/su', '/vendor/etc/media_codecs.xml', '/sys/*' } end\n" +
                        "function before(hook, param) param:setResult('fake') return true end", open);

        for (String path : new String[]{"/vendor/etc/init/../media_codecs.xml", "/data/../dev/su", "../dev/su",
                "data/../dev/su", "/proc/self/cwd/dev/su", "/proc/self/task/1234/cwd/sys/block"}) {
            XCall call = new XCall(open, new Target(), new Object[]{path});
            callbacks.get(0).before(call);
            assertEquals(path, "fake", call.getResult());
        }
        for (String path : new String[]{"/vendor/etc/init/media_codecs.xml", "/proc/self/cwd/dev/su2", "/data/..su",
                "/data/su.."}) {
            XCall call = new XCall(open, new Target(), new Object[]{path});
            callbacks.get(0).before(call);
            assertFalse(path, call.isReturnEarly());
        }
    }

    // Without the settings of the service the hooks are installed still, with the settings read
    @Test
    public void settingsFail() {
        assertTrue(XCore.getSettings(context, "test.app", 10123).isEmpty());
    }

    // Files are compared by their path, other objects not at all
    @Test
    public void filterValues() {
        assertEquals("/sdcard/DCIM", XCore.filterValue("/sdcard/DCIM"));
        // Like File.getPath, which has the separator of the platform the test runs on
        assertEquals(new File("/sdcard/DCIM").getPath(), XCore.filterValue(new File("/sdcard/DCIM/")));
        assertNull(XCore.filterValue(new Target()));
        assertNull(XCore.filterValue(null));
    }

    // The getPath of a subclass of File is code of the app, which can fail: the method runs like without Doppel, which
    // reads the path itself or natively the field of File, and the script does not run
    @Test
    public void failingFilterValue() throws Throwable {
        Method open = Target.class.getMethod("open", String.class);
        install("failingFilterValue",
                "function filter(hook) return { '/sys/*' } end\n" +
                        "function before(hook, param) param:setResult('fake') return true end", open);

        File failing = new File("/sys/devices") {
            @Override
            public String getPath() {
                throw new IllegalStateException("app");
            }
        };
        XCall call = new XCall(open, new Target(), new Object[]{failing});
        callbacks.get(0).before(call);
        callbacks.get(0).after(call);
        assertFalse(call.isReturnEarly());
    }

    // A class of the same name in another class loader, like one of a plugin, is another class with its own hook
    @Test
    public void dynamicHookPerClassLoader() throws Throwable {
        Method get = Target.class.getMethod("get");
        install("dynamicHookPerClassLoader",
                "function after(h, param)\n" +
                        "  hook(param:getThis(), 'inherited', function(when, p) end)\n" +
                        "  return false\n" +
                        "end", get);
        callbacks.get(0).after(new XCall(get, new First(), new Object[0]));
        assertEquals(2, members.size());

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{Target.class.getProtectionDomain().getCodeSource().getLocation()}, null)) {
            Object other = loader.loadClass(First.class.getName()).getDeclaredConstructor().newInstance();
            callbacks.get(0).after(new XCall(get, other, new Object[0]));
            assertEquals(3, members.size());
            Class<?> declaring = ((Method) members.get(2)).getDeclaringClass();
            assertEquals(Target.class.getName(), declaring.getName());
            assertEquals(loader, declaring.getClassLoader());
        }
    }

    // Dynamic hooks of methods which objects inherit are installed once, in the class declaring the method
    @Test
    public void dynamicInheritedMethod() throws Throwable {
        Method get = Target.class.getMethod("get");
        install("dynamicInheritedMethod",
                "function after(h, param)\n" +
                        "  hook(param:getThis(), 'inherited', function(when, p) end)\n" +
                        "  hook(param:getThis(), 'missing', function(when, p) end)\n" +
                        "  return false\n" +
                        "end", get);
        assertEquals(1, members.size());

        XCall first = new XCall(get, new First(), new Object[0]);
        callbacks.get(0).after(first);
        assertEquals(2, members.size());
        assertEquals(Target.class.getMethod("inherited"), members.get(1));

        // An object of another subclass shares the hook, the same object does not hook again
        callbacks.get(0).after(new XCall(get, new Second(), new Object[0]));
        callbacks.get(0).after(first);
        assertEquals(2, members.size());
    }
}
