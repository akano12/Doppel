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
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.security.net.config.NetworkSecurityConfigProvider;

import com.android.providers.settings.SettingsProvider;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
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
import java.io.File;

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
            for (int sdk : new int[]{27, 28}) {
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

    private final List<Member> members = new ArrayList<>();
    private final List<XBackend.Callback> callbacks = new ArrayList<>();
    private final List<Member> deoptimized = new ArrayList<>();
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
            public boolean deoptimize(Member member) {
                deoptimized.add(member);
                return true;
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

    private void install(String name, String script, Member member, String attributes, boolean usage) throws Exception {
        XHook hook = XHook.fromJSON("{\"collection\": \"Test\", \"group\": \"Test.Group\", \"name\": \"" + name + "\"," +
                " \"author\": \"Test\", \"className\": \"" + Target.class.getName() + "\", \"parameterTypes\": []," +
                " \"minSdk\": 1, \"usage\": " + usage + ", \"luaScript\": \"\"" + attributes + "}");
        Prototype prototype = LuaC.instance.compile(
                new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)), "script");
        Method installMethod = XCore.class.getDeclaredMethod("installMethod",
                Context.class, XHook.class, Prototype.class, Map.class, Member.class);
        installMethod.setAccessible(true);
        installMethod.invoke(core, context, hook, prototype, new HashMap<String, String>(), member);
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

    // The reports of restricted calls start no thread, which the app could list: the first is sent right away, on the
    // thread of the hooked call, the next ones together, by the first hooked call once a second has passed
    @Test
    public void reportsStartNoThread() throws Throwable {
        List<String> sent = new ArrayList<>();
        ApplicationInfo ai = context.getApplicationInfo();
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

            // The reports are sent by the content resolver, without which they fail, like when the service refuses them
            @Override
            public ContentResolver getContentResolver() {
                sent.add(Thread.currentThread().getName() + " " + XCore.inService());
                return null;
            }
        };
        Method get = Target.class.getMethod("get");
        install("reportsStartNoThread", "local calls = 0\n" +
                "function before(hook, param) calls = calls + 1 return calls <= 2 end", get, "", true);
        Set<Thread> threads = new HashSet<>(Thread.getAllStackTraces().keySet());
        String self = Thread.currentThread().getName() + " true";

        callbacks.get(0).before(new XCall(get, new Target(), new Object[0]));
        assertEquals(Collections.singletonList(self), sent);
        callbacks.get(0).before(new XCall(get, new Target(), new Object[0]));
        assertEquals(1, sent.size());
        Thread.sleep(1100);
        callbacks.get(0).before(new XCall(get, new Target(), new Object[0]));
        assertEquals(Arrays.asList(self, self), sent);

        Set<Thread> started = new HashSet<>(Thread.getAllStackTraces().keySet());
        started.removeAll(threads);
        assertEquals(Collections.emptySet(), started);
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

    // Without the settings of the service the hooks are installed still, with the settings read and a suffix of the
    // substitute names of their own, new for each app
    @Test
    public void settingsFail() {
        Map<String, String> settings = XCore.getSettings(context, "test.app", 10123);
        assertEquals(settings.keySet().toString(), 1, settings.size());
        String suffix = settings.get(FakeIds.PROBE_SUFFIX);
        assertTrue(suffix, suffix.matches("\\.x[0-9]{9}"));
        assertNotEquals(suffix, XCore.getSettings(context, "test.app", 10123).get(FakeIds.PROBE_SUFFIX));
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
