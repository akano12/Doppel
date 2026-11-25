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

import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Process;
import android.os.RemoteException;
import android.security.net.config.NetworkSecurityConfigProvider;

import com.android.providers.settings.SettingsProvider;
import com.android.server.am.ActivityManagerService;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.io.File;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
        return module(false);
    }

    private XLuaModule module(boolean systemServer) {
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
                (proxy, m, args) -> ("getProcessName".equals(m.getName()) ? "test.app"
                        : "isSystemServer".equals(m.getName()) ? systemServer : false)));
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

    // The hooks are installed by the hook of Android 11 and later, which returns before the application is made;
    // newApplication is hooked too, which installs them before Android 11 and when the hook of Android 11 does not fire
    @Test
    public void applicationIsMadeOutsideTheHook() throws Throwable {
        List<Context> fetched = new ArrayList<>();
        XCore.fetcher = (c, p, u) -> {
            fetched.add(c);
            return null;
        };
        core.hookApplication("test.app", getClass().getClassLoader());
        assertEquals(NetworkSecurityConfigProvider.class.getDeclaredMethod("handleNewApplication", Context.class),
                members.get(0));
        assertTrue(members.size() > 1);
        for (Member member : members.subList(1, members.size()))
            assertEquals("newApplication", member.getName());
        int hooks = members.size();

        // Installed when the network security config was made, with the context of the application, and the claim
        // released before
        assertTrue(XCore.claim(backend("test")));
        XCall made = new XCall(members.get(0), null, new Object[]{context});
        callbacks.get(0).before(made);
        assertEquals("test", System.getProperty("eu.faircode.xlua.engine"));
        callbacks.get(0).after(made);
        assertNull(System.getProperty("eu.faircode.xlua.engine"));
        assertEquals(Collections.singletonList(context), fetched);

        // newApplication then does nothing: no release, no install, no log
        int installed = members.size();
        int logs = logged.size();
        assertTrue(XCore.claim(backend("test")));
        XCall application = new XCall(members.get(1), null, new Object[]{null, "test.App", context});
        callbacks.get(1).before(application);
        callbacks.get(1).after(application);
        assertEquals("test", System.getProperty("eu.faircode.xlua.engine"));
        assertEquals(1, fetched.size());
        assertEquals(installed, members.size());
        assertEquals(logs, logged.size());
        XCore.release();

        // Without the hook of Android 11 firing, newApplication installs them
        members.clear();
        callbacks.clear();
        fetched.clear();
        core.hookApplication("test.app", getClass().getClassLoader());
        assertEquals(hooks, members.size());
        assertTrue(XCore.claim(backend("test")));
        callbacks.get(1).before(new XCall(members.get(1), null, new Object[]{null, "test.App", context}));
        assertNull(System.getProperty("eu.faircode.xlua.engine"));
        assertEquals(Collections.singletonList(context), fetched);

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

    // The app's own code gets the answers of the settings provider: its call of the method of the service gets another
    // method, and its query of a column a sort order which the hook of the settings provider leaves to the settings
    // provider without asking the service; the engine's own, inside enterService, stay as they are
    @Test
    public void ownCodeOfTheAppGetsTheAnswersOfTheSettingsProvider() throws Throwable {
        core.hookSettings(getClass().getClassLoader());
        core.hideService(getClass().getClassLoader());
        assertEquals(4, members.size());
        assertEquals("android.content.ContentProviderProxy", members.get(2).getDeclaringClass().getName());
        assertEquals(Arrays.asList("query", "call"), Arrays.asList(members.get(2).getName(), members.get(3).getName()));

        // The stubs of Android have no Uri and an SDK_INT of 0, so the hook of call takes the way of Android 8.1 and 9,
        // by the binder of the settings provider which a query of the engine showed; all versions are tested below
        IBinder binder = new Binder();
        IInterface settings = () -> binder;
        IInterface another = Binder::new;
        try {
            XCore.enterService();
            try {
                XCore.markQuery(new Class<?>[0], new Object[0], "settings", settings);
            } finally {
                XCore.leaveService();
            }
            Object[] own = {null, XProvider.METHOD, "reports", null};
            callbacks.get(3).before(new XCall(members.get(3), settings, own));
            assertEquals(XCore.OWN, own[1]);
            Object[] other = {null, XProvider.METHOD, "reports", null};
            callbacks.get(3).before(new XCall(members.get(3), another, other));
            assertEquals(XProvider.METHOD, other[1]);
            Object[] get = {null, "GET_system", "x", null};
            callbacks.get(3).before(new XCall(members.get(3), settings, get));
            assertEquals("GET_system", get[1]);
            Object[] engine = {null, XProvider.METHOD, "reports", null};
            XCore.enterService();
            try {
                callbacks.get(3).before(new XCall(members.get(3), settings, engine));
            } finally {
                XCore.leaveService();
            }
            assertEquals(XProvider.METHOD, engine[1]);
        } finally {
            XCore.settingsBinder = null;
        }

        // Without a Uri the query is left as it is
        Object[] query = {null, null, new String[]{XProvider.COLUMN + "getAssignedHooks2"}, null, null};
        callbacks.get(2).before(new XCall(members.get(2), null, query));
        assertNull(query[3]);
        assertTrue(XCore.isSettings("settings"));
        assertTrue(XCore.isSettings("10@settings"));
        assertFalse(XCore.isSettings("com.example.settings"));
        assertFalse(XCore.isSettings(null));
        assertTrue(XCore.isColumn(new String[]{XProvider.COLUMN + "getAssignedHooks2", "name"}));
        assertFalse(XCore.isColumn(new String[]{"name", XProvider.COLUMN + "getAssignedHooks2"}));
        assertFalse(XCore.isColumn(new String[]{null}));
        assertFalse(XCore.isColumn(new String[0]));
        assertFalse(XCore.isColumn(null));

        boolean[] asked = {false};
        SettingsProvider provider = new SettingsProvider() {
            @Override
            public Context getContext() {
                asked[0] = true;
                throw new IllegalStateException("Service asked");
            }
        };
        String[] projection = {XProvider.COLUMN + "getAssignedHooks2"};
        String[] selection = {"test.app", "10123"};
        XCall marked = new XCall(members.get(1), provider, new Object[]{null, projection, null, selection, XCore.OWN});
        callbacks.get(1).before(marked);
        assertFalse(marked.isReturnEarly());
        assertFalse(asked[0]);
        XCall unmarked = new XCall(members.get(1), provider, new Object[]{null, projection, null, selection, null});
        callbacks.get(1).before(unmarked);
        assertTrue(asked[0]);
    }

    // The arguments of ContentProviderProxy.query of Android 8.1 to 10, of 11 and of 12 and later, with objects for the
    // hidden types: the code of the app gets a new Bundle for its query of a column of the service of the settings
    // provider, also when it had none, while other providers, other columns and the queries of the engine keep theirs,
    // and those of the engine show the binder of the settings provider. The stubs of Android keep nothing in a Bundle,
    // so the sort order in the copy is not seen here.
    @Test
    public void queriesOfTheServiceByTheAppAreMarkedOnAllVersions() {
        Class<?>[][] signatures = {
                {String.class, Uri.class, String[].class, Bundle.class, Object.class},
                {String.class, String.class, Uri.class, String[].class, Bundle.class, Object.class},
                {AttributionSource.class, Uri.class, String[].class, Bundle.class, Object.class}};
        String[] column = {XProvider.COLUMN + "getAssignedHooks2"};
        for (Class<?>[] types : signatures) {
            int extras = Arrays.asList(types).indexOf(Bundle.class);
            for (String authority : new String[]{"settings", "10@settings"}) {
                Bundle own = new Bundle();
                Object[] args = queryArgs(types, column, own);
                XCore.markQuery(types, args, authority, null);
                assertNotNull(args[extras]);
                assertNotSame(own, args[extras]);
                Object[] none = queryArgs(types, column, null);
                XCore.markQuery(types, none, authority, null);
                assertNotNull(none[extras]);
            }

            Bundle own = new Bundle();
            for (String authority : new String[]{"com.example.settings", null}) {
                Object[] other = queryArgs(types, column, own);
                XCore.markQuery(types, other, authority, null);
                assertSame(own, other[extras]);
            }
            for (String[] projection : new String[][]{{"name"}, null}) {
                Object[] name = queryArgs(types, projection, own);
                XCore.markQuery(types, name, "settings", null);
                assertSame(own, name[extras]);
            }

            IBinder binder = new Binder();
            Object[] engine = queryArgs(types, column, own);
            XCore.enterService();
            try {
                XCore.markQuery(types, engine, "com.example.settings", (IInterface) Binder::new);
                assertNull(XCore.settingsBinder);
                XCore.markQuery(types, engine, "settings", (IInterface) () -> binder);
                assertSame(binder, XCore.settingsBinder);
            } finally {
                XCore.leaveService();
                XCore.settingsBinder = null;
            }
            assertSame(own, engine[extras]);
        }
    }

    private static Object[] queryArgs(Class<?>[] types, String[] projection, Bundle extras) {
        Object[] args = new Object[types.length];
        args[Arrays.asList(types).indexOf(String[].class)] = projection;
        args[Arrays.asList(types).indexOf(Bundle.class)] = extras;
        return args;
    }

    // The arguments of ContentProviderProxy.call of Android 8.1 and 9, without the authority, of 10, of 11 and of 12 and
    // later: the method of the service which the code of the app calls of the settings provider is renamed, of another
    // provider not; on Android 8.1 and 9 the binder of the proxy tells the provider, once a query of the engine showed it
    @Test
    public void callsOfTheServiceByTheAppAreMarkedOnAllVersions() {
        IBinder binder = new Binder();
        IInterface provider = () -> binder;
        IInterface another = Binder::new;
        try {
            for (int sdk : new int[]{28}) {
                XCore.settingsBinder = null;
                Object[] early = {"app", XProvider.METHOD, "reports", null};
                XCore.markCall(early, sdk, provider);
                assertEquals(XProvider.METHOD, early[1]);

                XCore.settingsBinder = binder;
                Object[] own = {"app", XProvider.METHOD, "reports", null};
                XCore.markCall(own, sdk, provider);
                assertEquals(XCore.OWN, own[1]);
                for (Object proxy : new Object[]{another, null, "proxy"}) {
                    Object[] other = {"app", XProvider.METHOD, "reports", null};
                    XCore.markCall(other, sdk, proxy);
                    assertEquals(XProvider.METHOD, other[1]);
                }
                Object[] get = {"app", "GET_system", "x", null};
                XCore.markCall(get, sdk, provider);
                assertEquals("GET_system", get[1]);
            }

            // From Android 10 the authority tells the provider, whatever the binder
            Object[][] layouts = {{"app", "settings", XProvider.METHOD, "reports", null},
                    {"app", null, "10@settings", XProvider.METHOD, "reports", null},
                    {null, "settings", XProvider.METHOD, "reports", null},
                    {null, "settings", XProvider.METHOD, "reports", null}};
            int[] sdks = {29, 30, 31, 37};
            for (int i = 0; i < sdks.length; i++) {
                int method = layouts[i].length - 3;
                Object[] own = layouts[i].clone();
                XCore.markCall(own, sdks[i], another);
                assertEquals(XCore.OWN, own[method]);
                Object[] other = layouts[i].clone();
                other[method - 1] = "com.example.settings";
                XCore.markCall(other, sdks[i], provider);
                assertEquals(XProvider.METHOD, other[method]);
            }
        } finally {
            XCore.settingsBinder = null;
        }
    }

    // A refused or invalid call or query of an app with assignments gets the answer of the settings provider, like an app
    // outside the scope, and its reports get null like from the settings provider; system server and the app get the
    // error or no result, and an error of the service gives no result to both
    @Test
    public void assignedAppsGetTheAnswersOfTheSettingsProvider() throws Throwable {
        Method get = Target.class.getMethod("get");
        Exception[] errors = {new IllegalArgumentException("selection invalid"), new NumberFormatException("x"),
                new SecurityException("Permission error")};
        for (Exception error : errors) {
            XCall assigned = new XCall(get, new Target(), new Object[0]);
            XCore.serve(assigned, "Query", false, () -> {
                throw error;
            });
            assertFalse(error.toString(), assigned.isReturnEarly());
        }

        XCall reports = new XCall(get, new Target(), new Object[0]);
        XCore.serve(reports, "Call", false, () -> null);
        assertTrue(reports.isReturnEarly());
        assertNull(reports.getResult());

        XCall invalid = new XCall(get, new Target(), new Object[0]);
        IllegalArgumentException ex = new IllegalArgumentException("extras missing");
        XCore.serve(invalid, "Call", true, () -> {
            throw ex;
        });
        assertTrue(invalid.hasThrowable());
        assertSame(ex, invalid.getThrowable());

        XCall refused = new XCall(get, new Target(), new Object[0]);
        XCore.serve(refused, "Call", true, () -> {
            throw new SecurityException("Permission error");
        });
        assertTrue(refused.isReturnEarly());
        assertFalse(refused.hasThrowable());
        assertNull(refused.getResult());

        for (boolean permitted : new boolean[]{false, true}) {
            XCall failed = new XCall(get, new Target(), new Object[0]);
            XCore.serve(failed, "Query", permitted, () -> {
                throw new RemoteException("Database failed");
            });
            assertTrue(failed.isReturnEarly());
            assertFalse(failed.hasThrowable());
            assertNull(failed.getResult());
        }
    }

    private final List<Member> members = Collections.synchronizedList(new ArrayList<>());
    private final List<XBackend.Callback> callbacks = Collections.synchronizedList(new ArrayList<>());
    private final List<Member> deoptimized = new ArrayList<>();
    private final List<String> logged = Collections.synchronizedList(new ArrayList<>());
    // Run by the backend before it hooks a method, which tests make wait for other threads
    private volatile Runnable gate = null;
    // Whether the backend finds no method of a name to hook, like a system which has another one
    private volatile boolean noMethods = false;
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

        public String lstat(String path) {
            return "real";
        }

        public String stat(File file) {
            return "real";
        }

        // Like UnixFileSystemProvider.newDirectoryStream, declared with the interface of the paths of java.nio
        public String newDirectoryStream(Path dir, java.nio.file.DirectoryStream.Filter<? super Path> filter) {
            return "real";
        }
    }

    // Like BlockGuardOs, with the flags of open
    public static class Os {
        public String open(String path, int flags, int mode) {
            return "real";
        }
    }

    public static class First extends Target {
    }

    public static class Second extends Target {
    }

    private long[] startDelays;
    private long[] lateDelays;
    private XCore.Fetcher fetcher;
    private long reportDelay;
    private XCore.Sender sender;
    private XCore.Probe probe;
    private long mainDelay;
    private BooleanSupplier onMainThread;

    // The tries to get the hooks of an app and the delay of reports, which the tests do not wait for
    @After
    public void restoreRetries() {
        XCore.startDelays = startDelays;
        XCore.lateDelays = lateDelays;
        XCore.fetcher = fetcher;
        XCore.reportDelay = reportDelay;
        XCore.sender = sender;
        XCore.probe = probe;
        XCore.mainDelay = mainDelay;
        XCore.onMainThread = onMainThread;
        Gate.reset();
    }

    @Before
    public void setUp() {
        startDelays = XCore.startDelays;
        lateDelays = XCore.lateDelays;
        fetcher = XCore.fetcher;
        reportDelay = XCore.reportDelay;
        sender = XCore.sender;
        probe = XCore.probe;
        mainDelay = XCore.mainDelay;
        onMainThread = XCore.onMainThread;
        XCore.sender = (c, reports) -> sent.add(Thread.currentThread().getName() + " " + XCore.inService() + " reports");
        XCore.startDelays = new long[0];
        XCore.lateDelays = new long[0];
        core = new XCore(new XBackend() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public void hook(Member member, Callback callback) {
                Runnable run = gate;
                if (run != null)
                    run.run();
                members.add(member);
                callbacks.add(callback);
            }

            @Override
            public int hookAll(Class<?> cls, String name, Callback callback) throws Throwable {
                return (noMethods ? 0 : XBackend.super.hookAll(cls, name, callback));
            }

            @Override
            public boolean deoptimize(Member member) {
                deoptimized.add(member);
                return true;
            }

            @Override
            public void log(String message) {
                logged.add(message);
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

            @Override
            public ClassLoader getClassLoader() {
                return XCoreTest.class.getClassLoader();
            }
        };
    }

    // Like XCore.install, without the class lookup through the class loader of an app
    private void install(String name, String script, Member member) throws Exception {
        install(name, script, member, "");
    }

    private void install(String name, String script, Member member, String attributes) throws Exception {
        install(name, script, member, attributes, false);
    }

    private void install(String name, XCore.Script script, Member member, String attributes) throws Exception {
        install(name, script, member, attributes, false);
    }

    private void install(String name, String script, Member member, String attributes, boolean usage) throws Exception {
        install(name, new XCore.Script(script), member, attributes, usage);
    }

    private void install(String name, XCore.Script script, Member member, String attributes, boolean usage) throws Exception {
        XHook hook = XHook.fromJSON("{\"collection\": \"Test\", \"group\": \"Test.Group\", \"name\": \"" + name + "\"," +
                " \"author\": \"Test\", \"className\": \"" + Target.class.getName() + "\", \"parameterTypes\": []," +
                " \"minSdk\": 1, \"usage\": " + usage + ", \"luaScript\": \"\"" + attributes + "}");
        Method installMethod = XCore.class.getDeclaredMethod("installMethod",
                Context.class, XHook.class, XCore.Script.class, Map.class, Member.class);
        installMethod.setAccessible(true);
        try {
            installMethod.invoke(core, context, hook, script, new HashMap<String, String>(), member);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception)
                throw (Exception) ex.getCause();
            throw ex;
        }
    }

    // The callers a definition names are deoptimized once the hook is installed, all methods of the name or the
    // constructors; one which is not there, like of another Android version, is skipped and the hook stays
    @Test
    public void callersAreDeoptimized() throws Throwable {
        Method get = Target.class.getMethod("get");
        String callers = ", \"deoptimize\": [\"" + Target.class.getName() + ".open\", \"" + First.class.getName() + ".<init>\"," +
                " \"" + Target.class.getName() + ".missing\", \"missing.Class.open\", \"open\"]";
        install("callersAreDeoptimized", "", get, callers);
        assertEquals(Collections.singletonList(get), members);
        assertEquals(Arrays.asList(Target.class.getMethod("open", String.class), First.class.getDeclaredConstructor()),
                deoptimized);

        // Kept by an export and an import, and left out of a definition without callers
        JSONObject jhook = XHook.fromJSON("{\"collection\": \"Test\", \"group\": \"Test.Group\", \"name\": \"callers\"," +
                " \"author\": \"Test\", \"className\": \"java.lang.Object\", \"parameterTypes\": [], \"minSdk\": 1," +
                " \"luaScript\": \"\"" + callers + "}").toJSONObject();
        assertEquals(5, XHook.fromJSONObject(jhook).getDeoptimize().length);
        jhook.remove("deoptimize");
        assertFalse(XHook.fromJSONObject(jhook).toJSONObject().has("deoptimize"));
        assertEquals(0, XHook.fromJSONObject(jhook).getDeoptimize().length);
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

    // A script shared by the groups is installed once per method, so a call runs it once, not once per restricted group;
    // the hook of another method is installed too, and so are all hooks of a script which is not shared or says no
    @Test
    public void sharedScriptIsInstalledOncePerMethod() throws Throwable {
        Method get = Target.class.getMethod("get");
        Method open = Target.class.getMethod("open", String.class);
        String shared = "function shared(hook) return true end\n" +
                "function before(hook, param) param:setResult('fake') return true end";
        install("sharedFirst", shared, get);
        install("sharedSecond", shared, get);
        install("sharedOpen", shared, open);
        assertEquals(Arrays.asList(get, open), members);

        String apart = "function shared(hook) return false end";
        install("apartFirst", apart, open);
        install("apartSecond", apart, open);
        install("plainFirst", "", open);
        install("plainSecond", "", open);
        assertEquals(6, callbacks.size());

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

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    // A folder for the tests of links, which are skipped where links cannot be made, like on Windows without
    // Developer Mode or an administrator, where making one fails with "A required privilege is not held"
    private Path linkFolder() throws Exception {
        Path folder = temp.getRoot().toPath().toRealPath();
        Path probe = folder.resolve("probe");
        try {
            Files.createSymbolicLink(probe, folder);
        } catch (java.nio.file.FileSystemException | UnsupportedOperationException ex) {
            org.junit.Assume.assumeNoException("No symbolic links: " + ex.getMessage(), ex);
        }
        Files.delete(probe);
        return folder;
    }

    // A path through a link, like one the app made itself or a descriptor of a folder in /proc/self/fd, runs the script by
    // the path its links lead to, which the hooks of a call resolve once; a call which acts on a link itself, like lstat,
    // by the name in the folder the links before it lead to
    @Test
    public void linksRunTheScript() throws Throwable {
        Path folder = linkFolder();
        Path kernel = Files.createDirectory(folder.resolve("kernel"));
        Files.createFile(kernel.resolve("osrelease"));
        Path boot = Files.createFile(folder.resolve("boot_id"));
        Path other = Files.createFile(folder.resolve("other"));
        Path link = Files.createSymbolicLink(folder.resolve("link"), boot);
        Path descriptor = Files.createSymbolicLink(folder.resolve("descriptor"), kernel);
        Method open = Target.class.getMethod("open", String.class);
        Method lstat = Target.class.getMethod("lstat", String.class);
        String script = "function filter(hook) return { '*boot_id*', '*kernel/osrelease*' } end\n" +
                "function after(hook, param) param:setResult('fake') return true end";
        install("linksRunTheScript", script, open);
        install("linksRunTheScriptLstat", script, lstat);

        for (String path : new String[]{link.toString(), descriptor + "/osrelease", descriptor + "//osrelease"}) {
            XCall call = new XCall(open, new Target(), new Object[]{path});
            callbacks.get(0).before(call);
            callbacks.get(0).after(call);
            assertEquals(path, "fake", call.getResult());
        }
        // Not a file without links, nor a text which is no absolute path, like the name of a resource
        for (String path : new String[]{other.toString(), folder.toString().substring(1) + "/link"}) {
            XCall call = new XCall(open, new Target(), new Object[]{path});
            callbacks.get(0).before(call);
            callbacks.get(0).after(call);
            assertNull(path, call.getResult());
        }

        // lstat of the link itself is of the link, of a name through a link of a folder of the file
        XCall itself = new XCall(lstat, new Target(), new Object[]{link.toString()});
        callbacks.get(1).before(itself);
        callbacks.get(1).after(itself);
        assertNull(itself.getResult());
        XCall through = new XCall(lstat, new Target(), new Object[]{descriptor + "/osrelease"});
        callbacks.get(1).before(through);
        callbacks.get(1).after(through);
        assertEquals("fake", through.getResult());

        // A link changed during a call is read after the method as before it, and by the next call as it is then
        Path moved = Files.createSymbolicLink(folder.resolve("moved"), other);
        XCall first = new XCall(open, new Target(), new Object[]{moved.toString()});
        callbacks.get(0).before(first);
        Files.delete(moved);
        Files.createSymbolicLink(moved, boot);
        callbacks.get(0).after(first);
        assertNull(first.getResult());
        XCall next = new XCall(open, new Target(), new Object[]{moved.toString()});
        callbacks.get(0).before(next);
        callbacks.get(0).after(next);
        assertEquals("fake", next.getResult());

        // Also when a script replaced the path before the method, like with an empty one for a hidden file, so the hooks
        // after it compare no path at all
        install("linksRunTheScriptHidden", "function filter(hook) return { '*hidden*' } end\n" +
                "function before(hook, param) param:setArgument(0, '') return true end", open);
        Path hidden = Files.createFile(folder.resolve("hidden"));
        Files.delete(moved);
        Files.createSymbolicLink(moved, hidden);
        XCall blank = new XCall(open, new Target(), new Object[]{moved.toString()});
        callbacks.get(2).before(blank);
        callbacks.get(0).before(blank);
        callbacks.get(0).after(blank);
        callbacks.get(2).after(blank);
        assertEquals("", blank.args[0]);
        Files.delete(moved);
        Files.createSymbolicLink(moved, boot);
        XCall again = new XCall(open, new Target(), new Object[]{moved.toString()});
        callbacks.get(2).before(again);
        callbacks.get(0).before(again);
        callbacks.get(0).after(again);
        callbacks.get(2).after(again);
        assertEquals(moved.toString(), again.args[0]);
        assertEquals("fake", again.getResult());
    }

    // open with O_NOFOLLOW, or with O_CREAT and O_EXCL, acts on a link at the end of the path itself, like on Android, where
    // it fails with ELOOP or EEXIST, so a link of the app does not run the script, by its name in its folder; a file of a
    // value by its text still does, and so do other flags, of the platform, which are 0 in the tests
    @Test
    public void openWithoutFollowingLinks() throws Throwable {
        Path folder = linkFolder();
        Path boot = Files.createFile(folder.resolve("boot_id"));
        Path link = Files.createSymbolicLink(folder.resolve("link"), boot);
        Method open = Os.class.getMethod("open", String.class, int.class, int.class);
        install("openWithoutFollowingLinks", "function filter(hook) return { '*boot_id*' } end\n" +
                "function after(hook, param) param:setResult('fake') return true end", open);
        int noFollow = XCore.openNoFollow, exclusive = XCore.openExclusive;
        try {
            XCore.openNoFollow = 0x20000;
            XCore.openExclusive = 0x40 | 0x80;
            Object[][] calls = {
                    {link.toString(), 0x20001, null}, {link.toString(), 0xc1, null}, {link.toString(), 0x41, "fake"},
                    {link.toString(), 0x80, "fake"}, {boot.toString(), 0xc1, "fake"}};
            for (Object[] expected : calls) {
                XCall call = new XCall(open, new Os(), new Object[]{expected[0], expected[1], 0600});
                callbacks.get(0).before(call);
                callbacks.get(0).after(call);
                assertEquals(expected[0] + " " + expected[1], expected[2], call.getResult());
            }
        } finally {
            XCore.openNoFollow = noFollow;
            XCore.openExclusive = exclusive;
        }
        // Without the constants of the platform every link is followed
        XCall call = new XCall(open, new Os(), new Object[]{link.toString(), 0xc1, 0600});
        callbacks.get(0).before(call);
        callbacks.get(0).after(call);
        assertEquals("fake", call.getResult());
    }

    // A relative path of a file is from the root, which apps run in, so a link reached by it runs the script too, like
    // data/user/0/<app>/files/link of a link to /proc/sys/kernel/random/boot_id, also of a path of java.nio to a hook
    // declared with its interface; other relative texts are no paths (see linksRunTheScript)
    @Test
    public void relativeLinksRunTheScript() throws Throwable {
        Path folder = linkFolder();
        Path boot = Files.createFile(folder.resolve("boot_id"));
        Path link = Files.createSymbolicLink(folder.resolve("link"), boot);
        Path other = Files.createSymbolicLink(folder.resolve("other"), Files.createFile(folder.resolve("file")));
        Method stat = Target.class.getMethod("stat", File.class);
        Method list = Target.class.getMethod("newDirectoryStream", Path.class, java.nio.file.DirectoryStream.Filter.class);
        String script = "function filter(hook) return { '*boot_id*' } end\n" +
                "function after(hook, param) param:setResult('fake') return true end";
        install("relativeLinksRunTheScript", script, stat);
        install("relativeLinksRunTheScriptPath", script, list);
        for (Path path : new Path[]{link, other}) {
            String relative = path.toString().substring(1);
            XCall call = new XCall(stat, new Target(), new Object[]{new File(relative)});
            callbacks.get(0).before(call);
            callbacks.get(0).after(call);
            assertEquals(path.toString(), path == link ? "fake" : null, call.getResult());
            XCall listed = new XCall(list, new Target(), new Object[]{Path.of(relative), null});
            callbacks.get(1).before(listed);
            callbacks.get(1).after(listed);
            assertEquals(path.toString(), path == link ? "fake" : null, listed.getResult());
        }
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

    // What the scripts of the tests below call to meet other threads
    public static class Gate {
        public static volatile CountDownLatch inside = new CountDownLatch(1);
        public static volatile CountDownLatch release = new CountDownLatch(1);
        public static volatile boolean dynamicRan = false;
        public static volatile boolean ranDuringStatic = false;
        public static volatile int chunks = 0;
        public static volatile Runnable task = null;

        static void reset() {
            inside = new CountDownLatch(1);
            release = new CountDownLatch(1);
            dynamicRan = false;
            ranDuringStatic = false;
            chunks = 0;
            task = null;
        }

        // A script which runs its chunk
        public static void chunk() {
            chunks++;
        }

        // The function of a dynamic hook
        public static void ran() {
            dynamicRan = true;
        }

        // A function of a script which is in a call for a while, and tells whether a function of its runtime ran meanwhile
        public static void hold() throws InterruptedException {
            inside.countDown();
            Thread.sleep(300);
            ranDuringStatic = dynamicRan;
        }

        // A function of a script which waits for another thread to run something
        public static void joinTask() throws InterruptedException {
            Thread thread = new Thread(task);
            thread.start();
            thread.join(10000);
        }
    }

    private interface Task {
        void run() throws Throwable;
    }

    private final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

    // A thread of a test, whose failure the test finds with failures
    private Thread thread(Task task) {
        return new Thread(() -> {
            try {
                task.run();
            } catch (Throwable ex) {
                failures.add(ex);
            }
        });
    }

    private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++)
            Thread.sleep(50);
        return condition.getAsBoolean();
    }

    // The reports the engine sends, by the thread and whether it is without the hooks
    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());

    private Context contextWith(ContentResolver resolver) {
        ApplicationInfo ai = context.getApplicationInfo();
        return new ContextWrapper(null) {
            @Override
            public String getPackageName() {
                return "test.app";
            }

            @Override
            public ApplicationInfo getApplicationInfo() {
                return ai;
            }

            @Override
            public ClassLoader getClassLoader() {
                return XCoreTest.class.getClassLoader();
            }

            @Override
            public ContentResolver getContentResolver() {
                return resolver;
            }
        };
    }

    private void hookPackage(String packageName) throws Throwable {
        Method hookPackage = XCore.class.getDeclaredMethod("hookPackage", String.class, int.class, Context.class);
        hookPackage.setAccessible(true);
        try {
            hookPackage.invoke(core, packageName, 10123, context);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    // The reports of restricted calls are sent by the hooked call of a thread which is not the main thread, so no thread
    // of the engine exists in the process of an app: the main thread leaves them for another thread, unless they are much
    // older than the delay
    @Test
    public void reportsAreSentByAHookedThreadButTheMainThread() throws Throwable {
        XCore.reportDelay = TimeUnit.MILLISECONDS.toNanos(300);
        XCore.mainDelay = TimeUnit.SECONDS.toNanos(60);
        Thread main = Thread.currentThread();
        XCore.onMainThread = () -> Thread.currentThread() == main;
        Method get = Target.class.getMethod("get");
        install("reportsAreSentByAHookedThread", "function before(hook, param) return true end", get, "", true);
        Set<Thread> threads = new HashSet<>(Thread.getAllStackTraces().keySet());

        // The main thread makes the report and leaves it, also at its next call
        callbacks.get(0).before(new XCall(get, new Target(), new Object[0]));
        callbacks.get(0).before(new XCall(get, new Target(), new Object[0]));
        assertEquals(Collections.emptyList(), sent);

        // The next hooked call of another thread sends it, without the hooks
        Thread other = thread(() -> callbacks.get(0).before(new XCall(get, new Target(), new Object[0])));
        other.start();
        other.join();
        assertEquals(Collections.emptyList(), failures);
        assertEquals(Collections.singletonList(other.getName() + " true reports"), sent);

        // A report which waited much longer than the delay is sent by the main thread too
        XCore.mainDelay = TimeUnit.MILLISECONDS.toNanos(200);
        callbacks.get(0).before(new XCall(get, new Target(), new Object[0]));
        callbacks.get(0).before(new XCall(get, new Target(), new Object[0]));
        assertEquals(1, sent.size());
        Thread.sleep(700);
        callbacks.get(0).before(new XCall(get, new Target(), new Object[0]));
        assertEquals(Arrays.asList(other.getName() + " true reports", main.getName() + " true reports"), sent);

        Set<Thread> started = new HashSet<>(Thread.getAllStackTraces().keySet());
        started.removeAll(threads);
        assertEquals(Collections.emptySet(), started);
    }

    // A hook which fails on each call does not make and report an error for each call: one in the delay is enough, since
    // the report keeps the last error of a function of a hook
    @Test
    public void errorsAreReportedOncePerDelay() throws Throwable {
        XCore.reportDelay = TimeUnit.SECONDS.toNanos(30);
        Method get = Target.class.getMethod("get");
        install("errorsAreReportedOncePerDelay", "function before(hook, param) error('fails') end", get, "", true);

        callbacks.get(0).before(new XCall(get, new Target(), new Object[0]));
        assertTrue(waitFor(() -> sent.size() == 1));
        for (int i = 0; i < 3; i++)
            callbacks.get(0).before(new XCall(get, new Target(), new Object[0]));
        Field queue = XCore.class.getDeclaredField("queue");
        queue.setAccessible(true);
        assertEquals(Collections.emptyMap(), queue.get(core));
    }

    // A service which fails is asked again at the start of the app, a few times, and an app without assignments, which the
    // settings provider refuses, once
    @Test
    public void failedServiceIsAskedAgain() throws Throwable {
        XCore.startDelays = new long[]{0, 0};
        AtomicInteger tries = new AtomicInteger();
        XCore.fetcher = (c, p, u) -> {
            tries.incrementAndGet();
            throw new IllegalStateException("No answer of the service");
        };
        hookPackage("android");
        assertEquals(3, tries.get());

        tries.set(0);
        XCore.fetcher = (c, p, u) -> {
            tries.incrementAndGet();
            return null;
        };
        hookPackage("android");
        assertEquals(1, tries.get());
    }

    // No answer of the service, which Data tells by an exception, is no sign that the app has no assignments
    @Test
    public void noAnswerIsNotNoAssignments() {
        context = contextWith(new ContentResolver(null) {
        });
        try {
            XCore.assigned(context, "test.app", 10123);
            fail("App without hooks for a service which gave no answer");
        } catch (Throwable expected) {
            assertTrue(expected.toString(), expected instanceof IllegalStateException);
        }
    }

    // An app which the service did not answer at its start gets its hooks when it does, later, and the service is told:
    // until then the app ran with its real values
    @Test
    public void lateStartInstallsAndReports() throws Throwable {
        XCore.startDelays = new long[]{0};
        XCore.lateDelays = new long[]{20, 20};
        Method get = Target.class.getMethod("get");
        XHook hook = XHook.fromJSON("{\"collection\": \"Test\", \"group\": \"Test.Group\", \"name\": \"lateStart\"," +
                " \"author\": \"Test\", \"className\": \"" + Target.class.getName() + "\", \"methodName\": \"get\"," +
                " \"parameterTypes\": [], \"minSdk\": 0, \"luaScript\": \"function before(h, p) return false end\"}");
        AtomicInteger tries = new AtomicInteger();
        // Fails at the start (two tries) and at the first later try
        XCore.fetcher = (c, p, u) -> {
            if (tries.incrementAndGet() < 4)
                throw new IllegalStateException("No answer of the service");
            return new XCore.Assigned(Collections.singletonList(hook), new HashMap<>(), 1);
        };

        hookPackage("android");
        assertTrue(members.isEmpty());
        assertTrue(waitFor(() -> members.contains(get)));
        assertEquals(4, tries.get());
        assertTrue(waitFor(() -> sent.size() == 1));
        assertTrue(sent.get(0), sent.get(0).endsWith(" true reports"));
    }

    private static XCore.Probe probe(Callable<Integer> version, Runnable settings) {
        return new XCore.Probe() {
            @Override
            public int version(Context context) throws Exception {
                return version.call();
            }

            @Override
            public void settings(Context context) {
                settings.run();
            }
        };
    }

    // The engine does not read the parcels of a service of another version, which has the layout of the module before an
    // update, until the device restarts: not the one of a service which tells its version, nor the one of a service which
    // does not know the question, which the settings of all apps tell from an app without assignments
    @Test
    public void serviceOfAnotherVersionIsNotRead() throws Throwable {
        Runnable answered = () -> {
        };
        Runnable refused = () -> {
            throw new IllegalArgumentException("Supported SQL");
        };
        Callable<Integer> older = () -> BuildConfig.VERSION_CODE - 1;
        Callable<Integer> unknown = () -> {
            throw new IllegalArgumentException("Supported SQL");
        };

        XCore.probe = probe(older, answered);
        try {
            XCore.assigned(context, "test.app", 10123);
            fail("Service of another version");
        } catch (XCore.ServiceVersionException expected) {
        }
        XCore.probe = probe(unknown, answered);
        try {
            XCore.assigned(context, "test.app", 10123);
            fail("Service of before the question");
        } catch (XCore.ServiceVersionException expected) {
        }
        XCore.probe = probe(() -> {
            throw new SecurityException("Permission denial");
        }, answered);
        try {
            XCore.assigned(context, "test.app", 10123);
            fail("Service which refuses the question");
        } catch (XCore.ServiceVersionException expected) {
        }

        // An app without assignments gets the error of the settings provider for both
        XCore.probe = probe(unknown, refused);
        assertNull(XCore.assigned(context, "test.app", 10123));

        // The same version goes on to the hooks, which the stubs cannot read; system server is not asked
        XCore.probe = probe(() -> BuildConfig.VERSION_CODE, answered);
        try {
            XCore.assigned(context, "test.app", 10123);
            fail("Hooks without a service");
        } catch (Throwable expected) {
            assertFalse(expected.toString(), expected instanceof XCore.ServiceVersionException);
        }
        XCore.probe = probe(older, answered);
        try {
            XCore.assigned(context, "android", 1000);
            fail("Hooks without a service");
        } catch (Throwable expected) {
            assertFalse(expected.toString(), expected instanceof XCore.ServiceVersionException);
        }
    }

    // A service of another version is reported at once and not asked again, which does not change until it restarts
    @Test
    public void serviceOfAnotherVersionIsReportedOnce() throws Throwable {
        // The main thread leaves reports for a hooked call, which an app without hooks never makes: the thread of the engine
        // sends this one
        Thread main = Thread.currentThread();
        XCore.onMainThread = () -> Thread.currentThread() == main;
        XCore.startDelays = new long[]{0, 0};
        XCore.lateDelays = new long[]{20};
        AtomicInteger tries = new AtomicInteger();
        XCore.fetcher = (c, p, u) -> {
            tries.incrementAndGet();
            throw new XCore.ServiceVersionException("The service is of another version", null);
        };
        hookPackage("android");
        assertTrue(waitFor(() -> sent.size() == 1));
        assertFalse(sent.get(0), sent.get(0).startsWith(main.getName() + " "));
        Thread.sleep(200);
        assertEquals(1, sent.size());
        assertEquals(1, tries.get());
    }

    // The settings are all or none: an error of the service is an error of the start, not a part of the settings
    @Test
    public void settingsOfAFailingServiceAreAnError() {
        context = contextWith(new ContentResolver(null) {
        });
        try {
            XCore.getSettings(context, "test.app", 10123);
            fail("Settings of a service which gave no answer");
        } catch (IllegalStateException expected) {
        }
    }

    // Without the boot value of the service the hooks have a suffix of the substitute names of their own, new for each app
    @Test
    public void settingsHaveASuffixOfTheirOwn() {
        Map<String, String> settings = XCore.withProbeSuffix(new HashMap<>());
        assertEquals(settings.keySet().toString(), 1, settings.size());
        String suffix = settings.get(FakeIds.PROBE_SUFFIX);
        assertTrue(suffix, suffix.matches("\\.x[0-9]{9}"));
        assertNotEquals(suffix, XCore.withProbeSuffix(new HashMap<>()).get(FakeIds.PROBE_SUFFIX));

        Map<String, String> given = new HashMap<>();
        given.put(FakeIds.PROBE_SUFFIX, ".x1");
        assertEquals(".x1", XCore.withProbeSuffix(given).get(FakeIds.PROBE_SUFFIX));
    }

    // The hooks of the app are made when it is called first, unless the installation runs a function of its script, and the
    // script of a user is made at the installation, which reports its error
    @Test
    public void scriptsOfTheAppAreMadeWhenCalled() throws Throwable {
        Method get = Target.class.getMethod("get");
        String broken = "function before(hook, param) return ( end";
        install("builtinBroken", broken, get, ", \"builtin\": true");
        assertEquals(1, members.size());
        callbacks.get(0).before(new XCall(get, new Target(), new Object[0]));
        assertTrue(waitFor(() -> sent.size() == 1));
        try {
            install("customBroken", broken, get, "");
            fail("Script with an error");
        } catch (LuaError expected) {
        }

        // The chunk runs once per thread and hook, at the first call, or at the installation for a script with a filter
        String gate = "luajava.bindClass('eu.faircode.xlua.XCoreTest$Gate'):chunk()\n";
        String script = "function before(hook, param) param:setResult('x') return true end";
        install("builtinChunk", gate + script, get, ", \"builtin\": true");
        assertEquals(0, Gate.chunks);
        XBackend.Callback callback = callbacks.get(callbacks.size() - 1);
        callback.before(new XCall(get, new Target(), new Object[0]));
        callback.before(new XCall(get, new Target(), new Object[0]));
        assertEquals(1, Gate.chunks);
        Thread other = thread(() -> callback.before(new XCall(get, new Target(), new Object[0])));
        other.start();
        other.join();
        assertEquals(2, Gate.chunks);
        install("filterChunk", gate + "function filter(hook) return { '/proc/meminfo' } end\n" + script, get,
                ", \"builtin\": true");
        assertEquals(3, Gate.chunks);
        assertEquals(Collections.emptyList(), failures);
    }

    // The hooks of a built-in script with a filter share the runtime which the installation runs the filter in, which no
    // hook keeps: a hook makes its own runtime at its first call on a thread, so one never called has none; the filter
    // acts the same, and a script of a user keeps the runtime made for each of its hooks
    @Test
    public void installationKeepsNoRuntimes() throws Throwable {
        Gate.reset();
        Method open = Target.class.getMethod("open", String.class);
        String text = "luajava.bindClass('eu.faircode.xlua.XCoreTest$Gate'):chunk()\n" +
                "function filter(hook) return { '/proc/meminfo' } end\n" +
                "function before(hook, param) param:setResult('fake') return true end";
        XCore.Script script = new XCore.Script(text);
        for (String name : new String[]{"first", "second", "third"})
            install(name, script, open, ", \"builtin\": true");
        // One runtime for the three filters, none kept
        assertEquals(1, Gate.chunks);
        assertEquals(3, callbacks.size());

        // The filter answers as before
        XCall other = new XCall(open, new Target(), new Object[]{"/data/file"});
        callbacks.get(0).before(other);
        assertNull(other.getResult());
        assertEquals(1, Gate.chunks);
        XCall filtered = new XCall(open, new Target(), new Object[]{"/proc/meminfo"});
        callbacks.get(0).before(filtered);
        assertEquals("fake", filtered.getResult());
        // The first call of the hook made its runtime, once for the thread, the second call used it
        assertEquals(2, Gate.chunks);
        callbacks.get(0).before(new XCall(open, new Target(), new Object[]{"/proc/meminfo"}));
        assertEquals(2, Gate.chunks);

        // Each thread has a runtime of its own
        Thread thread = thread(() -> callbacks.get(0).before(new XCall(open, new Target(), new Object[]{"/proc/meminfo"})));
        thread.start();
        thread.join();
        assertEquals(3, Gate.chunks);

        // The scripts of users run for each hook at the installation, and the runtime stays that of the thread
        Gate.chunks = 0;
        XCore.Script mine = new XCore.Script(text);
        install("mineFirst", mine, open, "");
        install("mineSecond", mine, open, "");
        assertEquals(2, Gate.chunks);
        XCall call = new XCall(open, new Target(), new Object[]{"/proc/meminfo"});
        callbacks.get(callbacks.size() - 1).before(call);
        assertEquals("fake", call.getResult());
        assertEquals(2, Gate.chunks);
        assertEquals(Collections.emptyList(), failures);
    }

    // The classes of the hooks of an app are looked up once, also those which are missing, like of an SDK it does not have
    @Test
    public void missingClassesAreLookedUpOnce() throws Throwable {
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("missing.")) {
                    asked.add(name);
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        ApplicationInfo ai = context.getApplicationInfo();
        Context app = new ContextWrapper(null) {
            @Override
            public String getPackageName() {
                return "test.app";
            }

            @Override
            public ApplicationInfo getApplicationInfo() {
                return ai;
            }

            @Override
            public ClassLoader getClassLoader() {
                return loader;
            }
        };
        List<XHook> hooks = new ArrayList<>();
        for (String name : new String[]{"first", "second", "third"})
            hooks.add(XHook.fromJSON("{\"collection\": \"Test\", \"group\": \"Test.Group\", \"name\": \"" + name + "\"," +
                    " \"author\": \"Test\", \"className\": \"missing.Sdk\", \"methodName\": \"hooked\"," +
                    " \"parameterTypes\": [\"missing.Param\"], \"minSdk\": 0, \"optional\": true, \"luaScript\": \"\"}"));
        Method installAll = XCore.class.getDeclaredMethod("installAll", Context.class, XCore.Assigned.class);
        installAll.setAccessible(true);
        installAll.invoke(core, app, new XCore.Assigned(hooks, new HashMap<>(), 1));
        assertEquals(Collections.singletonList("missing.Sdk"), asked);
        assertTrue(members.isEmpty());
    }

    // A hook of a script of a definition which hooks the methods of an object it gets: the first call hooks, and the
    // function of the hook gets the modes the next calls set
    private static final String DYNAMIC =
            "local mode\n" +
                    "function before(h, param)\n" +
                    "  mode = param:getArgument(0)\n" +
                    "  if mode == 'hook' then\n" +
                    "    hook(param:getThis(), 'inherited', function(when, p)\n" +
                    "      if mode == 'use' then return true\n" +
                    "      elseif mode == 'none' then return false\n" +
                    "      elseif mode == 'nothing' then return\n" +
                    "      elseif mode == 'error' then error('boom') end\n" +
                    "    end)\n" +
                    "  end\n" +
                    "  return false\n" +
                    "end";

    // The callback of a dynamic hook is like that of a definition: what the function returns is its use, an error is
    // reported to the service and does not reach the app
    @Test
    public void dynamicCallbacksReportLikeOthers() throws Throwable {
        XCore.reportDelay = TimeUnit.MILLISECONDS.toNanos(50);
        Method open = Target.class.getMethod("open", String.class);
        Method inherited = Target.class.getMethod("inherited");
        install("dynamicCallbacksReportLikeOthers", DYNAMIC, open, "", true);
        XBackend.Callback fixed = callbacks.get(0);
        Target target = new First();
        fixed.before(new XCall(open, target, new Object[]{"hook"}));
        assertEquals(inherited, members.get(1));
        XBackend.Callback dynamic = callbacks.get(1);
        XCall call = new XCall(inherited, target, new Object[0]);

        // No restriction is no use, and nothing is no value which counts
        for (String mode : new String[]{"none", "nothing"}) {
            fixed.before(new XCall(open, target, new Object[]{mode}));
            dynamic.before(call);
        }
        Thread.sleep(200);
        assertEquals(0, sent.size());

        fixed.before(new XCall(open, target, new Object[]{"use"}));
        dynamic.before(call);
        assertEquals(1, sent.size());

        fixed.before(new XCall(open, target, new Object[]{"error"}));
        dynamic.before(call);
        assertFalse(call.isReturnEarly());
        // The error is sent by the next hooked call once the delay has passed
        Thread.sleep(100);
        fixed.before(new XCall(open, target, new Object[]{"none"}));
        assertEquals(2, sent.size());
    }

    // A dynamic hook is installed when the method is hooked: another thread which hooks the same method meanwhile waits
    // for it, and does not go on with a method which is not hooked yet
    @Test
    public void dynamicHookIsInstalledBeforeAnotherThreadGoesOn() throws Throwable {
        Method open = Target.class.getMethod("open", String.class);
        install("dynamicHookIsInstalledFirst",
                "function before(h, param) hook(param:getThis(), 'inherited', function(when, p) end) return false end", open);
        XBackend.Callback fixed = callbacks.get(0);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        gate = () -> {
            gate = null;
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                throw new IllegalStateException(ex);
            }
        };
        Thread first = thread(() -> fixed.before(new XCall(open, new First(), new Object[]{"a"})));
        first.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS));

        Thread second = thread(() -> fixed.before(new XCall(open, new Second(), new Object[]{"b"})));
        second.start();
        second.join(300);
        assertTrue("Went on before the method was hooked", second.isAlive());
        release.countDown();
        first.join(5000);
        second.join(5000);
        assertEquals(Collections.emptyList(), failures);
        // The method of the classes is hooked once
        assertEquals(Arrays.asList(open, Target.class.getMethod("inherited")), members);
    }

    // The functions of a runtime which hooks of its script run on other threads too do not run at the same time as the
    // function of the hook on its own thread: no state of the script is changed by both
    @Test
    public void staticAndDynamicCallsOfARuntimeExcludeEachOther() throws Throwable {
        Method open = Target.class.getMethod("open", String.class);
        install("staticAndDynamicExclude",
                "local gate = luajava.bindClass('eu.faircode.xlua.XCoreTest$Gate')\n" +
                        "function before(h, param)\n" +
                        "  local mode = param:getArgument(0)\n" +
                        "  if mode == 'hook' then\n" +
                        "    hook(param:getThis(), 'inherited', function(when, p) gate:ran() end)\n" +
                        "  elseif mode == 'hold' then\n" +
                        "    gate:hold()\n" +
                        "  end\n" +
                        "  return false\n" +
                        "end", open);
        XBackend.Callback fixed = callbacks.get(0);
        Target target = new First();
        fixed.before(new XCall(open, target, new Object[]{"hook"}));
        XBackend.Callback dynamic = callbacks.get(1);
        XCall call = new XCall(Target.class.getMethod("inherited"), target, new Object[0]);

        Thread other = thread(() -> {
            Gate.inside.await();
            dynamic.before(call);
        });
        other.start();
        fixed.before(new XCall(open, target, new Object[]{"hold"}));
        other.join(5000);
        assertEquals(Collections.emptyList(), failures);
        assertFalse("The dynamic function ran in the call of the static one", Gate.ranDuringStatic);
        assertTrue(Gate.dynamicRan);
    }

    // A script which waits for a thread which runs a function of its own runtime does not hang: the function waits for
    // the lock of the runtime for a limited time
    @Test
    public void callWaitingOnADynamicCallOfItsRuntimeDoesNotHang() throws Throwable {
        Method open = Target.class.getMethod("open", String.class);
        install("callWaitsOnDynamic",
                "local gate = luajava.bindClass('eu.faircode.xlua.XCoreTest$Gate')\n" +
                        "function before(h, param)\n" +
                        "  local mode = param:getArgument(0)\n" +
                        "  if mode == 'hook' then\n" +
                        "    hook(param:getThis(), 'inherited', function(when, p) gate:ran() end)\n" +
                        "  elseif mode == 'join' then\n" +
                        "    gate:joinTask()\n" +
                        "  end\n" +
                        "  return false\n" +
                        "end", open);
        XBackend.Callback fixed = callbacks.get(0);
        Target target = new First();
        fixed.before(new XCall(open, target, new Object[]{"hook"}));
        XBackend.Callback dynamic = callbacks.get(1);
        XCall call = new XCall(Target.class.getMethod("inherited"), target, new Object[0]);
        Gate.task = () -> {
            try {
                dynamic.before(call);
            } catch (Throwable ex) {
                failures.add(ex);
            }
        };

        fixed.before(new XCall(open, target, new Object[]{"join"}));
        assertEquals(Collections.emptyList(), failures);
        assertTrue(Gate.dynamicRan);
    }

    // The error function of xpcall is of the runtime of the script, not shared by the hooks of all threads: an error of
    // another hook does not call the handler
    @Test
    public void handlerOfXpcallStaysInItsRuntime() throws Throwable {
        Method get = Target.class.getMethod("get");
        Method open = Target.class.getMethod("open", String.class);
        install("xpcallInA",
                "local gate = luajava.bindClass('eu.faircode.xlua.XCoreTest$Gate')\n" +
                        "function before(h, param)\n" +
                        "  xpcall(function() gate:hold() error('boom-in-A') end, function(m) return 'A-HANDLER:' .. m end)\n" +
                        "  return false\n" +
                        "end", get);
        install("pcallInB",
                "function before(h, param)\n" +
                        "  local ok, message = pcall(function() error('boom-in-B') end)\n" +
                        "  param:setResult(message)\n" +
                        "  return false\n" +
                        "end", open);
        Thread other = thread(() -> callbacks.get(0).before(new XCall(get, new Target(), new Object[0])));
        other.start();
        assertTrue(Gate.inside.await(5, TimeUnit.SECONDS));

        XCall call = new XCall(open, new Target(), new Object[]{"b"});
        callbacks.get(1).before(call);
        other.join(5000);
        assertEquals(Collections.emptyList(), failures);
        assertTrue(String.valueOf(call.getResult()), call.getResult().toString().contains("boom-in-B"));
        assertFalse(String.valueOf(call.getResult()), call.getResult().toString().contains("A-HANDLER"));
    }

    // A chunk which a script loads gets the globals of the script, not those of the libraries, which all hooks share
    @Test
    public void loadedChunksGetTheGlobalsOfTheScript() throws Throwable {
        Method get = Target.class.getMethod("get");
        Method open = Target.class.getMethod("open", String.class);
        install("loadInA",
                "function before(h, param)\n" +
                        "  load('leaked = function() return 1 end')()\n" +
                        "  param:setResult(leaked ~= nil and 'own' or 'none')\n" +
                        "  return false\n" +
                        "end", get);
        install("readInB", "function before(h, param) param:setResult(tostring(leaked)) return false end", open);
        XCall own = new XCall(get, new Target(), new Object[0]);
        callbacks.get(0).before(own);
        assertEquals("own", own.getResult());
        XCall other = new XCall(open, new Target(), new Object[]{"b"});
        callbacks.get(1).before(other);
        assertEquals("nil", other.getResult());
    }

    // A script cannot change the libraries, which all hooks share, nor the metatable of strings, which all strings do
    @Test
    public void librariesCannotBeChanged() throws Throwable {
        // Other tests make strings of libraries of their own, the engine does it once
        XCore.protect(JsePlatform.standardGlobals());
        Method get = Target.class.getMethod("get");
        install("librariesCannotBeChanged",
                "function before(h, param)\n" +
                        "  local r = {}\n" +
                        "  r[1] = pcall(function() string.upper = function() return 'HACKED' end end)\n" +
                        "  r[2] = pcall(function() getmetatable('').__index.upper = function() return 'HACKED' end end)\n" +
                        "  r[3] = pcall(function() rawset(string, 'extra', 1) end)\n" +
                        "  r[4] = pcall(function() getmetatable(_G).__index.leak = 1 end)\n" +
                        "  r[5] = pcall(function() table.insert(string, 'x') end)\n" +
                        "  r[6] = pcall(function() setmetatable(string, {}) end)\n" +
                        "  param:setResult(tostring(r[1]) .. ' ' .. tostring(r[2]) .. ' ' .. tostring(r[3]) .. ' ' ..\n" +
                        "    tostring(r[4]) .. ' ' .. tostring(r[5]) .. ' ' .. tostring(r[6]) .. ' ' .. ('abc'):upper())\n" +
                        "  return false\n" +
                        "end", get);
        XCall call = new XCall(get, new Target(), new Object[0]);
        callbacks.get(0).before(call);
        assertEquals("false false false false false false ABC", call.getResult());
    }

    // The service is started when the system is ready: the hooks of "android" are got then
    @Test
    public void androidIsHookedWhenTheSystemIsReady() throws Throwable {
        List<String> asked = new ArrayList<>();
        XCore.fetcher = (c, p, u) -> {
            asked.add(p + ":" + u);
            return null;
        };
        core.hookAndroid(getClass().getClassLoader());
        Method ready = ActivityManagerService.class.getMethod("systemReady", Runnable.class, Object.class);
        assertEquals(ready, members.get(0));
        assertFalse(logged.toString(), logged.toString().contains("Service not started"));

        XCall call = new XCall(ready, new ActivityManagerService(context), new Object[2]);
        callbacks.get(0).before(call);
        assertEquals(Collections.singletonList("android:" + Process.myUid()), asked);
        // What the stubs cannot do fails inside the callback
        callbacks.get(0).after(call);
    }

    // A system without the method which tells that it is ready never starts the service: logged, which system server does
    @Test
    public void missingSystemReadyIsLogged() throws Throwable {
        noMethods = true;
        core.hookAndroid(getClass().getClassLoader());
        assertTrue(logged.toString(), logged.toString().contains("Service not started"));
    }

    private static XC_LoadPackage.LoadPackageParam loaded(String packageName, boolean first) {
        XC_LoadPackage.LoadPackageParam param = new XC_LoadPackage.LoadPackageParam();
        param.packageName = packageName;
        param.classLoader = XCoreTest.class.getClassLoader();
        param.isFirstApplication = first;
        return param;
    }

    private static void inSystemServer(boolean value) throws Exception {
        Field field = XLog.class.getDeclaredField("systemServer");
        field.setAccessible(true);
        field.setBoolean(null, value);
    }

    // The legacy entry point hooks what the package it is given needs: system server, the settings provider, the first
    // package of an app; not a package which an app loads for itself
    @Test
    public void legacyEntryHooksByPackage() throws Throwable {
        XCore.release();
        Field modern = XLua.class.getDeclaredField("modern");
        modern.setAccessible(true);
        Object before = modern.get(null);
        modern.set(null, Boolean.FALSE);
        try {
            XLua entry = new XLua();
            XposedBridge.hookedMembers.clear();
            entry.handleLoadPackage(loaded("android", true));
            assertEquals(ActivityManagerService.class.getMethod("systemReady", Runnable.class, Object.class),
                    XposedBridge.hookedMembers.get(0));
            assertEquals("legacy", System.getProperty("eu.faircode.xlua.engine"));

            XposedBridge.hookedMembers.clear();
            entry.handleLoadPackage(loaded(XCore.SETTINGS_PACKAGE, false));
            assertEquals(Arrays.asList(
                    SettingsProvider.class.getMethod("call", String.class, String.class, Bundle.class),
                    SettingsProvider.class.getMethod("query", Uri.class, String[].class, String.class, String[].class,
                            String.class)), XposedBridge.hookedMembers);

            XposedBridge.hookedMembers.clear();
            entry.handleLoadPackage(loaded("test.app", false));
            assertTrue(XposedBridge.hookedMembers.isEmpty());
            entry.handleLoadPackage(loaded("test.app", true));
            assertEquals(NetworkSecurityConfigProvider.class.getDeclaredMethod("handleNewApplication", Context.class),
                    XposedBridge.hookedMembers.get(0));
            assertTrue(XposedBridge.hookedMembers.size() > 1);
            for (Member member : XposedBridge.hookedMembers.subList(1, XposedBridge.hookedMembers.size()))
                assertEquals("newApplication", member.getName());
        } finally {
            modern.set(null, before);
            inSystemServer(false);
            XCore.release();
        }
    }

    // The modern entry point hooks system server when it starts, and the settings provider, which is loaded into it, but
    // not an app, when it runs there
    @Test
    public void modernEntryHooksSystemServer() throws Throwable {
        XCore.release();
        try {
            XLuaModule module = module(true);
            module.onSystemServerStarting((XposedModuleInterface.SystemServerStartingParam) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{XposedModuleInterface.SystemServerStartingParam.class},
                    (proxy, m, args) -> ("getClassLoader".equals(m.getName()) ? getClass().getClassLoader() : null)));
            assertEquals(ActivityManagerService.class.getMethod("systemReady", Runnable.class, Object.class), hooked.get(0));
            assertEquals("modern", System.getProperty("eu.faircode.xlua.engine"));

            hooked.clear();
            module.onPackageReady(ready(XCore.SETTINGS_PACKAGE, true));
            assertEquals(Arrays.asList(
                    SettingsProvider.class.getMethod("call", String.class, String.class, Bundle.class),
                    SettingsProvider.class.getMethod("query", Uri.class, String[].class, String.class, String[].class,
                            String.class)), hooked);

            hooked.clear();
            module.onPackageReady(ready("test.app", true));
            assertTrue(hooked.isEmpty());
        } finally {
            inSystemServer(false);
            XCore.release();
        }
    }
}
