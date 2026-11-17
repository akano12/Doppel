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

    Copyright 2017-2019 Marcel Bokhorst (M66B)
    Copyright 2026 akano12
 */

package eu.faircode.xlua;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.Process;
import android.os.StrictMode;
import android.os.UserHandle;
import android.provider.Settings;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.pm.PackageInfoCompat;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Prototype;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

// Hook engine, independent of the hooking framework
class XCore {
    private static final String TAG = "XLua.Core";

    // The settings provider is loaded into system server
    static final String SETTINGS_PACKAGE = "com.android.providers.settings";

    // System property shared by all class loaders of a process
    private static final String PROP_ENGINE = "eu.faircode.xlua.engine";

    private static volatile XBackend backend = null;
    private static volatile int version = -1;

    // Usage reports are sent in batches, with the last report per hook and event, and the last error, at most once a
    // second. In apps the hooked call of any thread which comes once they are due sends them (see sendDue), so they
    // start no thread, which the app could list, and their frames are only inside a hooked call, under the frames of
    // its hook; a report with none sent in the second before is due right away. System server sends them on a thread of
    // its own, which no app can list, since its hooked methods can hold locks which the settings provider and the
    // notifications of the service take too.
    // shortcut: the reports of the last second before an app makes no more hooked calls wait for its next one, and
    // are lost when its process ends first; send them also when the last activity of the app stops if users miss them
    // shortcut: the hooked call which sends the reports waits for the service, also on the main thread, for a time not
    // measured on a device; measure it and send them by a call which does not wait if apps slow down
    private static final long REPORT_DELAY = TimeUnit.SECONDS.toNanos(1);
    private final Map<String, Map<String, Bundle>> queue = new HashMap<>();
    private volatile boolean queued = false;
    // By System.nanoTime, when the queue is due, and when reports were last sent
    private volatile long due = 0;
    private long sent = 0;
    private boolean anySent = false;
    private ScheduledExecutorService reporter = null;
    // The methods hooked with a script shared by the groups, each with the script, see installMethod
    private final Set<String> sharedHooks = ConcurrentHashMap.newKeySet();
    // The callers deoptimized in this process, by their class loader and name: the hooks of one method in each group
    // name the same ones, which would else each list the methods of their classes again, see deoptimize
    private static final Set<String> deoptimized = ConcurrentHashMap.newKeySet();

    XCore(XBackend backend) {
        XCore.backend = backend;
    }

    // The hooks assigned to "android" apply to all of system server, but not to the service and the code of this
    // class there, which look up packages and call the service themselves: a restriction like Get.Applications
    // would else hide the module and the apps from the service. See installMethod.
    // In apps the reports and the package lookups of XParam are neither restricted nor reported as calls of the app.
    private static final ThreadLocal<int[]> serviceCalls = ThreadLocal.withInitial(() -> new int[1]);

    static void enterService() {
        serviceCalls.get()[0]++;
    }

    static void leaveService() {
        serviceCalls.get()[0]--;
    }

    static boolean inService() {
        return (serviceCalls.get()[0] > 0);
    }

    // A framework can load both the modern and the legacy entry point:
    // only the first one to claim a process is allowed to hook
    static boolean claim(XBackend backend) {
        synchronized (System.class) {
            String engine = System.getProperty(PROP_ENGINE);
            if (engine == null) {
                System.setProperty(PROP_ENGINE, backend.getName());
                XLog.i(TAG, "Using " + backend.getName() + " API in " + Process.myPid());
                return true;
            }
            return engine.equals(backend.getName());
        }
    }

    // Before the code of an app runs, when all entry points have claimed: apps can read the system properties,
    // also apps without restrictions, which should get the answers of a device without Doppel
    static void release() {
        synchronized (System.class) {
            System.clearProperty(PROP_ENGINE);
        }
    }

    // Packages loaded into another app's process (WebView, GMS, etc.) are handled by the host app
    static boolean isApp(String packageName, boolean firstPackage) {
        return (firstPackage &&
                !"android".equals(packageName) &&
                !BuildConfig.APPLICATION_ID.equals(packageName));
    }

    static void log(Throwable ex) {
        if (!XLog.enabled())
            return;
        XLog.e(TAG, Log.getStackTraceString(ex));
        XBackend b = backend;
        if (b != null)
            try {
                b.log(ex);
            } catch (Throwable ignored) {
            }
    }

    static void log(String message) {
        if (!XLog.enabled())
            return;
        XLog.i(TAG, message);
        XBackend b = backend;
        if (b != null)
            try {
                b.log(message);
            } catch (Throwable ignored) {
            }
    }

    // System server
    void hookAndroid(ClassLoader classLoader) throws Throwable {
        // https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/am/ActivityManagerService.java
        Class<?> clsAM = Class.forName("com.android.server.am.ActivityManagerService", false, classLoader);

        backend.hookAll(clsAM, "systemReady", new XBackend.Callback() {
            @Override
            void before(XCall call) {
                enterService();
                try {
                    log("Preparing system");
                    Context context = findContext(call.thisObject);
                    hookPackage("android", Process.myUid(), context);
                } catch (Throwable ex) {
                    log(ex);
                } finally {
                    leaveService();
                }
            }

            @Override
            void after(XCall call) {
                enterService();
                try {
                    log("System ready");
                    Context context = findContext(call.thisObject);

                    // Store current module version, also when the module is removed for the owner but kept for a profile
                    PackageInfo pi = Util.getPackageInfo(context.getPackageManager(), BuildConfig.APPLICATION_ID,
                            ReceiverPackage.MATCH_ANY_USER);
                    version = (int) PackageInfoCompat.getLongVersionCode(pi);
                    XLog.i(TAG, "Module version " + version);
                    // The signers of the module which runs, so an app which takes its name after it was removed
                    // gets no answers, see XProvider.permitted
                    XProvider.moduleSigners = XProvider.getModuleSigners(context);

                    // The texts of notifications of the module version which runs, also after an update
                    try {
                        XNotify.loadResources(context);
                    } catch (Throwable ex) {
                        log(ex);
                    }

                    // Listen for removed users and profiles; the broadcast has no data, so a filter of its own
                    try {
                        registerReceiver(context, new IntentFilter(ReceiverPackage.ACTION_USER_REMOVED));
                        XLog.i(TAG, "Registered user listener");
                    } catch (Throwable ex) {
                        log(ex);
                    }

                    // Listen for package changes
                    IntentFilter ifPackageAdd = new IntentFilter();
                    ifPackageAdd.addAction(Intent.ACTION_PACKAGE_ADDED);
                    ifPackageAdd.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
                    ifPackageAdd.addDataScheme("package");
                    try {
                        // Also of users and profiles which are added later
                        // public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
                        //   IntentFilter filter, String broadcastPermission, Handler scheduler)
                        UserHandle all = (UserHandle) UserHandle.class.getField("ALL").get(null);
                        context.getClass().getMethod("registerReceiverAsUser", BroadcastReceiver.class,
                                        UserHandle.class, IntentFilter.class, String.class, Handler.class)
                                .invoke(context, new ReceiverPackage(), all, ifPackageAdd, null, null);
                        XLog.i(TAG, "Registered package listener for all users");
                    } catch (Throwable ex) {
                        XLog.w(TAG, "Registering for all users: " + ex);

                        // The existing users only; looked up here, so a change of these hidden methods
                        // does not prevent registering for all users
                        // public static UserManagerService getInstance()
                        ClassLoader loader = call.thisObject.getClass().getClassLoader();
                        Class<?> clsUM = Class.forName("com.android.server.pm.UserManagerService", false, loader);
                        Object um = clsUM.getDeclaredMethod("getInstance").invoke(null);
                        // public int[] getUserIds()
                        int[] userids = (int[]) um.getClass().getDeclaredMethod("getUserIds").invoke(um);
                        for (int userid : userids) {
                            XLog.i(TAG, "Registering package listener user=" + userid);
                            registerReceiver(Util.createContextForUser(context, userid), ifPackageAdd);
                        }
                    }
                } catch (Throwable ex) {
                    log(ex);
                } finally {
                    leaveService();
                }
            }

            @NonNull
            private Context findContext(Object am) throws Throwable {
                // Searching for context
                for (Class<?> cAm = am.getClass(); cAm != null; cAm = cAm.getSuperclass())
                    for (Field field : cAm.getDeclaredFields())
                        if (field.getType().equals(Context.class)) {
                            field.setAccessible(true);
                            XLog.i(TAG, "Context found in " + cAm + " as " + field.getName());
                            return (Context) field.get(am);
                        }
                throw new Throwable("Context not found");
            }
        });

        // Without this hook other listeners get Doppel's notifications, like before, but the service still runs
        try {
            XNotify.hideFromListeners(backend, classLoader);
        } catch (Throwable ex) {
            log(ex);
        }
    }

    // Protected broadcasts, which only the system sends, so the receiver is not exported, which Android 13
    // and later are told. ContextCompat.registerReceiver would register with a permission of the package
    // on earlier versions, which the package of system server does not declare.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static void registerReceiver(Context context, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(new ReceiverPackage(), filter, Context.RECEIVER_NOT_EXPORTED);
        else
            context.registerReceiver(new ReceiverPackage(), filter);
    }

    // Settings provider in system server, which hosts the Doppel service
    void hookSettings(ClassLoader classLoader) throws Throwable {
        // https://android.googlesource.com/platform/frameworks/base/+/master/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java
        Class<?> clsSet = Class.forName("com.android.providers.settings.SettingsProvider", false, classLoader);

        // Bundle call(String method, String arg, Bundle extras)
        Method mCall = clsSet.getMethod("call", String.class, String.class, Bundle.class);
        backend.hook(mCall, new XBackend.Callback() {
            @Override
            void before(XCall call) {
                try {
                    String method = (String) call.args[0];
                    String arg = (String) call.args[1];
                    Bundle extras = (Bundle) call.args[2];
                    if (!XProvider.METHOD.equals(method))
                        return;

                    enterService();
                    try {
                        call(call, getContext(call.thisObject), arg, extras);
                    } finally {
                        leaveService();
                    }
                } catch (Throwable ex) {
                    log(ex);
                }
            }

            private void call(XCall call, Context context, String arg, Bundle extras) throws Throwable {
                // Other apps get no result, like without Doppel, so they cannot detect it this way
                if ("getVersion".equals(arg)) {
                    Bundle result = null;
                    if (XProvider.hasPermission(context)) {
                        result = new Bundle();
                        result.putInt("version", version);
                    }
                    call.setResult(result);
                    return;
                }

                // Other apps get the answer of the settings provider, like without Doppel
                int caller = answers(call, context);
                if (caller == XProvider.CALLER_NONE)
                    return;

                boolean permitted = (caller == XProvider.CALLER_PERMITTED);
                serve(call, "Call " + arg, permitted, () -> XProvider.call(context, arg, extras, permitted));
            }

            @Override
            void after(XCall call) {
                withoutHookFrames(call);
            }
        });

        // Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder)
        Method mQuery = clsSet.getMethod("query", Uri.class, String[].class, String.class, String[].class, String.class);
        backend.hook(mQuery, new XBackend.Callback() {
            @Override
            void before(XCall call) {
                try {
                    String[] projection = (String[]) call.args[1];
                    String[] selection = (String[]) call.args[3];
                    // A query of the app's own code is the settings provider's, see hideService
                    if (!isColumn(projection) || OWN.equals(call.args[4]))
                        return;

                    enterService();
                    try {
                        query(call, getContext(call.thisObject), projection[0], selection);
                    } finally {
                        leaveService();
                    }
                } catch (Throwable ex) {
                    log(ex);
                }
            }

            private void query(XCall call, Context context, String column, String[] selection) throws Throwable {
                // Other apps get the answer of the settings provider, an error for these columns
                int caller = answers(call, context);
                if (caller == XProvider.CALLER_NONE)
                    return;

                // A column without a method, like xlua., gets no result too
                String method = column.substring(XProvider.COLUMN.length());
                boolean permitted = (caller == XProvider.CALLER_PERMITTED);
                serve(call, "Query " + column, permitted, () -> XProvider.query(context, method, selection, permitted));
            }

            @Override
            void after(XCall call) {
                withoutHookFrames(call);
            }
        });
    }

    // A query of the service has its column first, see Data.query
    static boolean isColumn(String[] projection) {
        return (projection != null && projection.length > 0 &&
                projection[0] != null && projection[0].startsWith(XProvider.COLUMN));
    }

    // The sort order of a query and the method of a call of the app's own code, which the settings provider ignores
    static final String OWN = XProvider.COLUMN + "own";

    // The service answers the engine only: a query of its columns or a call of its method by the code of the app, outside
    // enterService, is marked on its way to the settings provider, where hookSettings leaves it to the settings provider,
    // so the app gets the error or the null of a device without Doppel. Installed in every process the engine hooks,
    // also of an app without assignments now, since the service answers its uid once it has some.
    // shortcut: not yet seen to fire on a device; the app's AppComponentFactory (Android 9 and later), which runs before
    // the engine, and a query or call the app sends with IBinder.transact itself still get the answers of the service;
    // upgrade to a token the code of the app cannot get
    void hideService(ClassLoader classLoader) throws Throwable {
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/content/ContentProviderNative.java
        Class<?> cls = XResolver.resolveClass("android.content.ContentProviderProxy", classLoader);
        int queries = backend.hookAll(cls, "query", new XBackend.Callback() {
            @Override
            void before(XCall call) {
                try {
                    Class<?>[] types = ((Method) call.method).getParameterTypes();
                    Uri uri = null;
                    for (int i = 0; i < types.length; i++)
                        if (types[i] == Uri.class)
                            uri = (Uri) call.args[i];
                    markQuery(types, call.args, (uri == null ? null : uri.getAuthority()), call.thisObject);
                } catch (Throwable ex) {
                    log(ex);
                }
            }
        });
        int calls = backend.hookAll(cls, "call", new XBackend.Callback() {
            @Override
            void before(XCall call) {
                if (!inService())
                    try {
                        markCall(call.args, Build.VERSION.SDK_INT, call.thisObject);
                    } catch (Throwable ex) {
                        log(ex);
                    }
            }
        });
        // Logged, since the code of the app would else get the answers of the service without a sign of it
        if (queries == 0 || calls == 0)
            XLog.w(TAG, "Service not hidden: " + queries + " query and " + calls + " call methods hooked");
    }

    // The binder of the settings provider, which the engine's own queries reach, kept so that it stays the same object;
    // on Android 8.1 and 9 it tells a call of the settings provider from one of another provider, see markCall
    static volatile IBinder settingsBinder = null;

    // ContentProviderProxy.query has one Uri, one projection and one Bundle of arguments on all versions, see XCoreTest.
    // A query of the settings provider of a column of the service by the code of the app gets a copy of its arguments
    // with the sort order OWN, so the app's own Bundle stays as it was; the engine's own show the binder of the provider.
    static void markQuery(Class<?>[] types, Object[] args, String authority, Object proxy) {
        if (!isSettings(authority))
            return;
        if (inService()) {
            if (proxy instanceof IInterface)
                settingsBinder = ((IInterface) proxy).asBinder();
            return;
        }
        String[] projection = null;
        int extras = -1;
        for (int i = 0; i < types.length; i++)
            if (types[i] == String[].class)
                projection = (String[]) args[i];
            else if (types[i] == Bundle.class)
                extras = i;
        if (!isColumn(projection) || extras < 0)
            return;
        Bundle marked = (args[extras] == null ? new Bundle() : new Bundle((Bundle) args[extras]));
        marked.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, OWN);
        args[extras] = marked;
    }

    // ContentProviderProxy.call has the method third from the end, after the authority since Android 10; before, only
    // the binder of the proxy tells the settings provider, so a call of another provider, or one before the engine's
    // first query, stays as it was, like without Doppel
    static void markCall(Object[] args, int sdk, Object proxy) {
        int method = args.length - 3;
        if (method > 0 && XProvider.METHOD.equals(args[method]) && (sdk < Build.VERSION_CODES.Q
                ? settingsBinder != null && proxy instanceof IInterface && ((IInterface) proxy).asBinder() == settingsBinder
                : isSettings((String) args[method - 1])))
            args[method] = OWN;
    }

    // An authority with a user, like 10@settings, is of the same provider
    static boolean isSettings(String authority) {
        return (authority != null && Settings.AUTHORITY.equals(authority.substring(authority.lastIndexOf('@') + 1)));
    }

    // Android 9 and later send the first five frames of a SecurityException of system server to the caller, also to
    // apps outside the scope, where the frames of the hook would follow those of the settings provider: they are left
    // out, so its caller, a method of ContentProvider or its Transport, follows like on a device without Doppel
    private static void withoutHookFrames(XCall call) {
        if (!call.hasThrowable())
            return;
        Throwable ex = call.getThrowable();
        List<StackTraceElement> frames = new ArrayList<>(Arrays.asList(ex.getStackTrace()));
        String cls = call.method.getDeclaringClass().getName();
        for (int i = 0; i < frames.size(); i++)
            if (cls.equals(frames.get(i).getClassName()) && call.method.getName().equals(frames.get(i).getMethodName())) {
                for (int j = i + 1; j < frames.size(); j++)
                    if (frames.get(j).getClassName().startsWith("android.content.ContentProvider")) {
                        frames.subList(i + 1, j).clear();
                        ex.setStackTrace(frames.toArray(new StackTraceElement[0]));
                        break;
                    }
                return;
            }
    }

    // Whom the service answers. When the service fails, like with a database which cannot be opened, which it logged and
    // told the user about, system server, the app and the apps the service last answered as apps with assignments get no
    // answer, which Data reports as an error, instead of the error of the settings provider, which hooked apps take for
    // having no assignments; all other apps get the error of the settings provider, like without Doppel.
    // shortcut: a restricted app the service did not answer since the boot gets the error of the settings provider, so
    // its process logs that it has no assigned hooks instead of the error (it runs without hooks either way); upgrade to
    // assigned uids kept outside the database if that log matters
    private static int answers(XCall call, Context context) {
        int uid = Binder.getCallingUid();
        try {
            return answered(uid, XProvider.answers(context));
        } catch (RemoteException ignored) {
            if (wasAssigned(uid) || XProvider.hasPermission(context))
                call.setResult(null);
            return XProvider.CALLER_NONE;
        }
    }

    // The callers the service last answered as apps with assignments, by uid, see answers
    private static final Set<Integer> assignedCallers = ConcurrentHashMap.newKeySet();

    static int answered(int uid, int caller) {
        if (caller == XProvider.CALLER_ASSIGNED)
            assignedCallers.add(uid);
        else
            assignedCallers.remove(uid);
        return caller;
    }

    static boolean wasAssigned(int uid) {
        return assignedCallers.contains(uid);
    }

    // ContentProvider.getContext()
    private static Context getContext(Object provider) throws Throwable {
        return (Context) provider.getClass().getMethod("getContext").invoke(provider);
    }

    // First package of an app process
    // shortcut: the app's own AppComponentFactory (Android 9 and later) is made, and makes the class loader, before both
    // entry points run, so a Build field the factory copies keeps the real value; install the field hooks from the
    // modern API's onPackageLoaded (Android 10 and later only) once the assigned hooks can be got there without a context
    void hookApplication(final String packageName, ClassLoader classLoader) throws Throwable {
        final int uid = Process.myUid();

        // The hooks are installed before the application is made, with the context it gets: making it runs
        // attachBaseContext of the app, where apps can start threads, like Facebook does, and hooking methods
        // which other threads run meanwhile can make them fail, like with arguments which are null
        XBackend.Callback callback = new XBackend.Callback() {
            private boolean made = false;

            @Override
            void before(XCall call) {
                if (call.args != null)
                    for (Object arg : call.args)
                        if (arg instanceof Context) {
                            install((Context) arg);
                            break;
                        }
            }

            // Without a context argument, with the application made
            @Override
            void after(XCall call) {
                if (call.getResult() instanceof Application)
                    install((Application) call.getResult());
            }

            private void install(Context context) {
                try {
                    if (made)
                        return;
                    made = true;
                    release();

                    // Isolated processes (including app zygote) cannot access the provider; this is for legacy loaders
                    // that inject there. LSPosed does not even load modules there, so an isolated service of the app
                    // reads the real Build fields (README Limits).
                    if (Util.isIsolated(uid)) {
                        XLog.i(TAG, "Skipping isolated " + packageName + ":" + uid);
                        return;
                    }

                    hookPackage(packageName, uid, context);
                } catch (Throwable ex) {
                    log(ex);
                }
            }
        };

        // LoadedApk.makeApplication(Inner) of Android 11 and later gives the context to the network security config
        // right before Instrumentation.newApplication makes the application, so a hook there returns before the code of
        // the app runs. In a hook of newApplication the constructor, static initializer and attachBaseContext of the
        // app's Application run inside the hook, with its frames (eu.faircode.xlua.ModernBackend) on their stack.
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/LoadedApk.java
        // shortcut: this hook was not yet seen to fire on a device (Android 11 to 17), and one which is installed but never
        // fires leaves the app without any hook and without an error; the boot image cannot inline the call into
        // makeApplication(Inner) under Vector, which deoptimizes those methods in every process (VectorInlinedCallers.kt
        // on Vector master; the installed LSPosed 2.2.1 was not read); fall back to newApplication when a probe on a
        // device shows it does not fire
        try {
            Class<?> clsConfig = XResolver.resolveClass("android.security.net.config.NetworkSecurityConfigProvider", classLoader);
            // The hooks are installed when it returned, so the network security config is made without them, like before
            backend.hook(clsConfig.getDeclaredMethod("handleNewApplication", Context.class), new XBackend.Callback() {
                @Override
                void after(XCall call) throws Throwable {
                    callback.before(call);
                }
            });
            return;
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
        } catch (Throwable ex) {
            // Hooked like before Android 11, else the app would run without any hook
            log(ex);
        }

        // shortcut: Android 8.1 to 10 call nothing between createAppContext and newApplication, so with the modern API
        // (Android 8.1 to 10) the Application is made inside the hook; hook the end of createAppContext if that matters
        // Instrumentation.newApplication is called by LoadedApk.makeApplication(Inner) on all versions
        // https://android.googlesource.com/platform/frameworks/base/+/169aeafb2d97b810ae123ad036d0c58336961c55%5E%21/#F1
        Class<?> clsInstrumentation = XResolver.resolveClass("android.app.Instrumentation", classLoader);
        backend.hookAll(clsInstrumentation, "newApplication", callback);
    }

    private void hookPackage(final String packageName, int uid, final Context context) throws Throwable {
        // Before the first query of the engine; an app whose code cannot be kept from the service still gets its hooks.
        // System server has no code of an app, and its settings provider is its own, reached without a proxy.
        if (!"android".equals(packageName))
            try {
                hideService(context.getClassLoader());
            } catch (Throwable ex) {
                log(ex);
            }

        List<XHook> hooks;
        enterService();
        try {
            hooks = Data.getAssignedHooks(context, packageName, uid);
        } catch (IllegalArgumentException ex) {
            // The settings provider refuses the query of an app without assignments, see XProvider.answers;
            // logged, since an illegal argument of the service would else leave the app without hooks silently
            XLog.i(TAG, "No assigned hooks: " + ex.getMessage());
            return;
        } finally {
            leaveService();
        }
        if (hooks.isEmpty())
            return;

        final Map<String, String> settings = getSettings(context, packageName, uid);

        // Hooks with the same script share the compiled script
        Map<String, Prototype> scripts = new HashMap<>();

        PackageInfo pi = Util.getPackageInfo(context.getPackageManager(), context.getPackageName(), 0);
        long versionCode = PackageInfoCompat.getLongVersionCode(pi);
        for (final XHook hook : hooks)
            try {
                if (!hook.isAvailable(versionCode))
                    continue;

                // Only install errors are reported, see XProvider.reports
                install(context, hook, compile(hook, scripts), settings);
            } catch (Throwable ex) {
                if (hook.isOptional() &&
                        (ex instanceof NoSuchFieldException ||
                                ex instanceof NoSuchMethodException ||
                                ex instanceof ClassNotFoundException ||
                                ex instanceof NoClassDefFoundError))
                    XLog.i(TAG, "Optional hook=" + hook.getId() + ": " + ex.getClass().getName() + ": " + ex.getMessage());
                else {
                    XLog.e(TAG, hook.getId() + ": " + Log.getStackTraceString(ex));

                    // Report install error
                    Bundle data = new Bundle();
                    data.putString("exception", ex instanceof LuaError ? ex.getMessage() : Log.getStackTraceString(ex));
                    report(context, hook.getId(), null, "install", data);
                }
            }
    }

    // App settings replace global settings, the identifiers the service derives both (see FakeIds); the settings read
    // until an error of the service, logged, since without them the app would run without any hook
    static Map<String, String> getSettings(Context context, String packageName, int uid) {
        Map<String, String> settings = new HashMap<>();
        enterService();
        try {
            settings.putAll(Data.getSettings(context, "global"));
            settings.putAll(Data.getSettings(context, packageName));
            settings.putAll(Data.getBootValues(context, packageName, uid));
        } catch (Throwable ex) {
            log(new IllegalStateException("Settings of " + packageName + ":" + uid, ex));
        } finally {
            leaveService();
        }
        // Without the boot value of the service the suffix of the substitute names is made here, once for all hooks
        // and threads, so two threads of a script cannot each make and keep a different one
        if (!settings.containsKey(FakeIds.PROBE_SUFFIX))
            settings.put(FakeIds.PROBE_SUFFIX, FakeIds.generate(FakeIds.PROBE_SUFFIX, new SecureRandom()));
        return settings;
    }

    private static Prototype compile(XHook hook, Map<String, Prototype> scripts) throws Throwable {
        String script = hook.getLuaScript();
        Prototype compiled = scripts.get(script);
        if (compiled == null) {
            compiled = LuaC.instance.compile(
                    new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)), "script");
            scripts.put(script, compiled);
        }
        return compiled;
    }

    // Hook the method or field of the definition
    private void install(Context context, XHook hook, Prototype script, Map<String, String> settings) throws Throwable {
        ClassLoader loader = context.getClassLoader();
        Class<?> cls = XResolver.resolveClass(hook.getResolvedClassName(), loader);

        Class<?>[] paramTypes = XResolver.resolveClasses(hook.getParameterTypes(), loader);
        Class<?> returnType = (hook.getReturnType() == null ? null : XResolver.resolveClass(hook.getReturnType(), loader));

        Member member = XResolver.resolveTarget(cls, hook.getMethodName(), paramTypes, returnType);
        if (member instanceof Field) {
            Field field = (Field) member;
            field.setAccessible(true);
            installField(context, hook, script, settings, field);
        } else
            installMethod(context, hook, script, settings, member);
    }

    // Static fields are changed once, by the after function
    private void installField(Context context, XHook hook, Prototype script, Map<String, String> settings, Field field) {
        try {
            Globals globals = getGlobals(context, hook, script, settings);

            LuaValue func = globals.get("after");
            if (func.isnil())
                return;

            Varargs result = func.invoke(new LuaValue[]{
                    CoerceJavaToLua.coerce(hook),
                    CoerceJavaToLua.coerce(new XParam(context, field, settings))
            });
            reportUse(context, hook, "after", result);
        } catch (Throwable ex) {
            reportError(context, hook, "after", ex, "\nField:\n" + field + "\n");
        }
    }

    private void installMethod(final Context context, final XHook hook, final Prototype script,
                               final Map<String, String> settings, final Member member) throws Throwable {
        // The runtime made for the filter is the one of this thread, so the script runs once here
        final Globals installed = getGlobals(context, hook, script, settings);
        // A script whose function shared returns true acts the same for each group, like refusing programs, which each
        // group does whose fakes a program could read: of its hooks of one method only the first is installed, so a
        // call runs the script once and not once per restricted group, whose other hooks would find nothing left to do
        // and only cost time; its use is reported for the group of that hook only, like one of them reported it before
        LuaValue shared = installed.get("shared");
        String key = member + "\n" + hook.getLuaScript();
        boolean once = (!shared.isnil() && shared.call(CoerceJavaToLua.coerce(hook)).toboolean());
        if (once && sharedHooks.contains(key))
            return;
        final Filter filter = getFilter(installed, hook, member);
        backend.hook(member, new XBackend.Callback() {
            // The Lua runtime of this hook per thread, without a lock on each call
            private final ThreadLocal<Globals> threadGlobals = new ThreadLocal<>();
            private final LuaValue luaHook = CoerceJavaToLua.coerce(hook);

            {
                threadGlobals.set(installed);
            }

            @Override
            void before(XCall call) {
                execute(call, "before");
            }

            @Override
            void after(XCall call) {
                execute(call, "after");
            }

            private void execute(XCall call, String function) {
                if (inService())
                    return;
                sendDue(context);
                try {
                    if (filter != null && (call.args == null || call.args.length == 0 ||
                            !filter.matches(call.args, function.equals("after"))))
                        return;
                } catch (Throwable ex) {
                    // The getPath of the app's own subclass of File failed: the method answers like without Doppel,
                    // which reads the path itself, failing the same way, or natively the field of File
                    return;
                }
                try {
                    // Initialize the Lua runtime once per thread
                    Globals globals = threadGlobals.get();
                    if (globals == null) {
                        globals = getGlobals(context, hook, script, settings);
                        threadGlobals.set(globals);
                    }

                    LuaValue func = globals.get(function);
                    if (func.isnil())
                        return;

                    LuaValue[] args = new LuaValue[]{
                            luaHook,
                            CoerceJavaToLua.coerce(new XParam(context, call, settings))
                    };
                    // Not locked, also in debug builds: the call stacks of DebugLib are per Java thread, and a lock
                    // held for the whole script deadlocks with a dynamic hook of this runtime on a thread it waits on
                    Varargs result = func.invoke(args);
                    reportUse(context, hook, function, result);
                } catch (Throwable ex) {
                    // An error of the script leaves its runtime usable (the call stacks of DebugLib unwind in finally),
                    // so a hook which always fails does not make and run the runtime again for every call; only
                    // an error of the VM, like running out of memory, may have left it half way
                    if (ex instanceof VirtualMachineError)
                        threadGlobals.remove();
                    // Describing the call runs code of the app (toString), which must not fail the call of the app,
                    // nor run the scripts of hooked methods as if the app called them
                    enterService();
                    try {
                        reportError(context, hook, function, ex,
                                "\nMethod:\n" + function + " " + member + "\n" + describe(call));
                    } catch (Throwable exex) {
                        XLog.e(TAG, hook.getId() + " " + function + ": " + Log.getStackTraceString(exex));
                    } finally {
                        leaveService();
                    }
                }
            }
        });
        if (once)
            sharedHooks.add(key);
        deoptimize(context, hook);
    }

    // The callers the definition names run in the interpreter, so the hook runs also where the boot image compiled the
    // hooked method into them; which callers have it differs per Android version, so one which is not there is skipped
    private static void deoptimize(Context context, XHook hook) {
        for (String caller : hook.getDeoptimize())
            try {
                int dot = caller.lastIndexOf('.');
                Class<?> cls = XResolver.resolveClass(caller.substring(0, dot), context.getClassLoader());
                String name = caller.substring(dot + 1);
                if (!deoptimized.add(System.identityHashCode(cls.getClassLoader()) + "/" + caller))
                    continue;
                List<Member> members = new ArrayList<>(Arrays.asList(cls.getDeclaredConstructors()));
                members.addAll(Arrays.asList(cls.getDeclaredMethods()));
                int found = 0;
                for (Member member : members)
                    if (name.equals(member instanceof Method ? member.getName() : "<init>") &&
                            !Modifier.isAbstract(member.getModifiers())) {
                        found++;
                        if (!backend.deoptimize(member))
                            XLog.w(TAG, hook.getId() + ": cannot deoptimize " + member);
                    }
                if (found == 0)
                    XLog.i(TAG, hook.getId() + ": no caller " + caller);
            } catch (Throwable ex) {
                XLog.i(TAG, hook.getId() + ": deoptimize " + caller + ": " + ex);
            }
    }

    // The answer of the service to a call or query: an illegal argument is thrown to system server and the app,
    // which get no result when refused; an app with assignments gets the answer of the settings provider instead,
    // like an app outside the scope, as it would on a device without Doppel: null for a call, an illegal argument
    // for the column of a query. An error of the service, which XProvider logged with its cause already, gives
    // no result, which the engine reports, see XCoreTest.
    static void serve(XCall call, String what, boolean permitted, Callable<Object> answer) {
        try {
            call.setResult(answer.call());
        } catch (IllegalArgumentException | SecurityException ex) {
            XLog.w(TAG, what + ": " + ex);
            if (!permitted)
                return;
            if (ex instanceof IllegalArgumentException)
                call.setThrowable(ex);
            else
                call.setResult(null);
        } catch (RemoteException ex) {
            XLog.w(TAG, what + ": " + ex.getMessage());
            call.setResult(null);
        } catch (Throwable ex) {
            log(ex);
            call.setResult(null);
        }
    }

    // The values of the first argument a script restricts, which its function filter returns for the hook,
    // like the paths of files a hook of opening files restricts; the other calls do not run the script,
    // so hooks of methods which are called very often cost (almost) nothing else; null without the function.
    // A value ending in * stands for all values starting with the text before it, like /sys/* for the files
    // of /sys, and a value starting with * too for all values containing the text between, like *sys/* for
    // the files of /sys also by relative paths or with .., for a script which tells the paths apart itself.
    // Paths also match as plain absolute paths: relative to the root, without // and /./ and without links of /proc
    // to the root, see XParam.plainPath. A value with /../ always runs the script, which only the file system can
    // resolve (see XParam.opens), so scripts check such values themselves. Files and paths of java.nio are compared
    // by their path, see filterValue. A path which matches no value by its text matches by the path its links
    // lead to, like a link the app made itself to /proc/sys/kernel/random/boot_id, or /proc/self/fd/9/osrelease of a
    // descriptor of /proc/sys/kernel, which the scripts resolve too (see XParam.opens), also by a relative path, from the
    // root; of a call which acts on a link itself, like lstat, readlink, mkdir or open with O_NOFOLLOW or with O_CREAT and
    // O_EXCL, by the path of its name in the folder the links before it lead to, so a link of the app answers like on
    // Android, like with ELOOP or EEXIST. This holds for an absolute value of every hook; a relative value only counts
    // from the root for the first arguments which are paths of files (see Filter.files), other relative values, like the
    // names of resources of loadXmlResourceParser, are compared by their text only.
    // shortcut: each call of a path which no value matches by its text resolves the path once more, by getCanonicalPath
    // through the hooks of canonicalize0, which return in the service; the cost was not measured on a device, resolve
    // without those hooks if apps slow down. A link changed between the hooks and the call is read as the kernel finds it;
    // upgrade when apps are seen doing that.
    private static Filter getFilter(Globals globals, XHook hook, Member member) {
        LuaValue func = globals.get("filter");
        if (func.isnil())
            return null;
        LuaValue values = func.call(CoerceJavaToLua.coerce(hook));
        if (values.isnil())
            return null;
        Filter filter = new Filter(member);
        for (int i = 1; i <= values.length(); i++)
            filter.add(values.get(i).checkjstring());
        return filter;
    }

    // The calls of files which act on a link at the end of their path itself, not on the file it leads to
    private static final Set<String> NO_FOLLOW = new HashSet<>(Arrays.asList(
            "lstat", "readlink", "lchown", "remove", "mkdir", "mkfifo", "mknod", "createFileExclusively"));

    // The flags by which open acts on a link at the end of its path itself: O_NOFOLLOW, which differs between ARM and x86,
    // and O_CREAT with O_EXCL, which fails with EEXIST for any link (open(2)). Not final, since the android.jar of the tests
    // has 0 for all constants of OsConstants.
    static int openNoFollow = OsConstants.O_NOFOLLOW;
    static int openExclusive = OsConstants.O_CREAT | OsConstants.O_EXCL;

    private static class Filter {
        private final Set<String> values = new HashSet<>();
        private final List<String> prefixes = new ArrayList<>();
        private final List<String> parts = new ArrayList<>();
        // Whether the calls act on the file a link at the end of their path leads to, see NO_FOLLOW
        private final boolean follows;
        // Whether the second argument has the flags of open, like of BlockGuardOs.open and UnixNativeDispatcher.open
        private final boolean opens;
        // Whether a relative first argument is a path of the file system, from the root, which apps run in: a file, a
        // path of java.nio, also of the hooks declared with its interface, like UnixFileSystemProvider.newDirectoryStream,
        // or a text of the classes of files, other than the name of Linux.getenv; absolute values resolve for every hook
        private final boolean files;

        Filter(Member member) {
            Class<?>[] types = ((Executable) member).getParameterTypes();
            String method = member.getName();
            String cls = member.getDeclaringClass().getName();
            this.follows = !NO_FOLLOW.contains(method);
            this.opens = ("open".equals(method) && types.length > 1 && types[1] == int.class);
            this.files = (types.length > 0 && (types[0] == File.class || "sun.nio.fs.UnixPath".equals(types[0].getName()) ||
                    "java.nio.file.Path".equals(types[0].getName()) ||
                    (types[0] == String.class && !"getenv".equals(method) &&
                            (cls.startsWith("libcore.io.") || cls.startsWith("java.io.") || cls.startsWith("sun.nio.fs.") ||
                                    cls.equals("android.system.Os") || cls.equals("android.os.Process")))));
        }

        private void add(String value) {
            if (value.length() > 1 && value.startsWith("*") && value.endsWith("*"))
                this.parts.add(value.substring(1, value.length() - 1));
            else if (value.endsWith("*"))
                this.prefixes.add(value.substring(0, value.length() - 1));
            else
                this.values.add(value);
        }

        // Paths also by another form, like /proc/sys/kernel/./osrelease, which the file system reads the same, and by the
        // path their links lead to; after the method of a call by the path its hooks resolved before it
        boolean matches(Object[] args, boolean after) {
            // A hook after the method ends what the hooks of the call resolved, also when a script changed its value
            if (after)
                lastResolved.get().after = true;
            String value = filterValue(args[0]);
            if (value == null)
                return false;
            // Only the file system can resolve /.., also at the end, like /sdcard/Download/.., which is /sdcard
            if (value.contains("/../") || value.startsWith("../") || value.endsWith("/..") || value.equals(".."))
                return true;
            if (!value.startsWith("/")) {
                // Relative to the root, which apps run in, like data/adb, see XParam.isPath
                if (matchesText(XParam.plainPath("/" + value)) || matchesText(value))
                    return true;
                if (!files || value.isEmpty())
                    return false;
                value = "/" + value;
            }
            String plain = XParam.plainPath(value);
            if ((!plain.equals(value) && matchesText(plain)) || matchesText(value))
                return true;
            String resolved = (follows(args) ? resolved(plain, after) : inFolder(plain, after));
            return (resolved != null && !resolved.equals(plain) && matchesText(resolved));
        }

        // Whether a call acts on the file a link at the end of its path leads to; a flag of 0, like in the tests, is none
        private boolean follows(Object[] args) {
            if (!follows)
                return false;
            if (!opens || args.length < 2 || !(args[1] instanceof Integer))
                return true;
            int flags = (Integer) args[1];
            return ((flags & openNoFollow) == 0 && (openExclusive == 0 || (flags & openExclusive) != openExclusive));
        }

        // The path of the name in the folder the links before it lead to, null when the folder cannot be resolved
        private static String inFolder(String path, boolean after) {
            int slash = path.lastIndexOf('/');
            if (slash == 0)
                return path;
            String folder = resolved(path.substring(0, slash), after);
            return (folder == null ? null : (folder.equals("/") ? "" : folder) + path.substring(slash));
        }

        // The path a path leads to, resolved by the first hook of a call which needs it, before the method, and kept for
        // its other hooks, also after the method, until a hook of the next call runs before its method: the filters of
        // the many hooks of a call, like of open, resolve it once. Per thread, since the hooks of a call run on its thread,
        // first all before the method, then all after it.
        private static final ThreadLocal<Resolved> lastResolved = ThreadLocal.withInitial(Resolved::new);

        private static final class Resolved {
            private String path;
            private String canonical;
            private boolean after;
        }

        private static String resolved(String path, boolean after) {
            Resolved last = lastResolved.get();
            if (!path.equals(last.path) || (last.after && !after)) {
                last.path = path;
                last.canonical = XParam.canonicalPath(path);
            }
            last.after = after;
            return last.canonical;
        }

        private boolean matchesText(String value) {
            if (this.values.contains(value))
                return true;
            for (String prefix : this.prefixes)
                if (value.startsWith(prefix))
                    return true;
            for (String part : this.parts)
                if (value.contains(part))
                    return true;
            return false;
        }
    }

    // The value of an argument for a filter: texts, and the path of files and of paths of java.nio; null for other
    // values. The path of a subclass of File is what its getPath gives, which is code of the app and can fail.
    // shortcut: an app's subclass of File whose getPath gives another text than its field path is filtered, and restricted
    // by the scripts (tostring of the File), by what getPath gives, while the natives of UnixFileSystem read the field:
    // list0, getLastModifiedTime0 and getSpace0, and checkAccess0 and getLength0 of Android 8.1 and 9. From Android 10
    // checkAccess and getLength call getPath themselves, so the path the file system uses depends on the method and the
    // version. The field itself is not the blocker: LSPosed and Vector make the dex of the module trusted, so the hidden
    // API rules let the module read it. Each filtered hook also calls the app's getPath before and after the method, which
    // the app can count. Choose the path per method and version, for the filter and the scripts alike, if apps get around
    // the hooks of files this way
    static String filterValue(Object value) {
        if (value instanceof String)
            return (String) value;
        if (value instanceof File)
            return ((File) value).getPath();
        if (value != null && "sun.nio.fs.UnixPath".equals(value.getClass().getName()))
            return value.toString();
        return null;
    }

    private static String describe(XCall call) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nArguments:\n");
        if (call.args == null)
            sb.append("null\n");
        else
            for (int i = 0; i < call.args.length; i++)
                sb.append(i).append(": ").append(describe(call.args[i])).append('\n');
        sb.append("\nReturn:\n").append(describe(call.getResult())).append('\n');
        return sb.toString();
    }

    // The value of an argument or result without running code of the app, which toString of its own objects would,
    // inside enterService where all hooks are off: the text of plain values, else the class and identity
    private static String describe(Object value) {
        if (value == null)
            return "null";
        if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character)
            return value + " (" + value.getClass().getName() + ")";
        return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    // Scripts return whether they restricted something, optionally with the old and new value;
    // the data is what XProvider.Report.write stores
    private void reportUse(Context context, XHook hook, String function, Varargs result) {
        boolean restricted = result.arg1().checkboolean();
        if (!restricted || !hook.doUsage())
            return;

        Bundle data = new Bundle();
        data.putInt("restricted", 1);
        if (result.narg() > 1) {
            data.putString("old", toText(result.arg(2)));
            data.putString("new", toText(result.arg(3)));
        }
        report(context, hook.getId(), function, "use", data);
    }

    // Values of any type as text, like a number, a boolean or a Java object, which was applied already,
    // so it should not count as an error of the hook
    private static String toText(LuaValue value) {
        return (value.isnil() ? null : value.tojstring());
    }

    private void reportError(Context context, XHook hook, String function, Throwable ex, String details) {
        String message = "Exception:\n" + Log.getStackTraceString(ex) + "\n" +
                "\nPackage:\n" + context.getPackageName() + ":" + context.getApplicationInfo().uid + "\n" +
                details;
        // The details have the real arguments and result, which release builds do not write to the system log,
        // like the service (see XProvider.Report); the report stores them for the app
        if (BuildConfig.DEBUG)
            XLog.e(TAG, message);
        else
            XLog.e(TAG, hook.getId() + " " + function + ": " + Log.getStackTraceString(ex));

        Bundle data = new Bundle();
        data.putString("exception", message);
        report(context, hook.getId(), function, "use", data);
    }

    private void report(final Context context, String hook, String function, String event, Bundle data) {
        final String packageName = context.getPackageName();
        final int uid = context.getApplicationInfo().uid;

        Bundle args = new Bundle();
        args.putString("hook", hook);
        args.putString("packageName", packageName);
        args.putInt("uid", uid);
        args.putString("event", event);
        args.putLong("time", System.currentTimeMillis());
        args.putBundle("data", data);

        synchronized (queue) {
            // An error does not replace a restriction of the same function, nor the other way around
            String key = (function == null ? "*" : function) + ":" + event +
                    (data.containsKey("exception") ? ":error" : "");
            if (!queue.containsKey(key))
                queue.put(key, new HashMap<>());
            queue.get(key).put(hook, args);

            if (queued)
                return;
            long now = System.nanoTime();
            due = (anySent && now - sent < REPORT_DELAY ? sent + REPORT_DELAY : now);
            queued = true;
            if ("android".equals(packageName)) {
                if (reporter == null)
                    reporter = Executors.newSingleThreadScheduledExecutor(runnable -> {
                        Thread thread = new Thread(runnable);
                        thread.setDaemon(true);
                        return thread;
                    });
                reporter.schedule(() -> flush(context, packageName, uid), due - now, TimeUnit.NANOSECONDS);
                return;
            }
        }
        sendDue(context);
    }

    // The reports of an app, once they are due, on the thread of the hooked call, see queue
    private void sendDue(Context context) {
        if (queued && System.nanoTime() - due >= 0 && !"android".equals(context.getPackageName()))
            flush(context, context.getPackageName(), context.getApplicationInfo().uid);
    }

    // shortcut: a count, not a size; 20 stack traces of a few KB each fit, measure the parcel when traces grow
    static final int REPORTS_PER_CALL = 20;

    private void flush(Context context, String packageName, int uid) {
        ArrayList<Bundle> work = new ArrayList<>();
        synchronized (queue) {
            // Another thread sent them first
            if (!queued)
                return;
            for (Map<String, Bundle> reports : queue.values())
                work.addAll(reports.values());
            queue.clear();
            queued = false;
            sent = System.nanoTime();
            anySent = true;
        }
        XLog.i(TAG, "Processing event queue package=" + packageName + ":" + uid);

        // The hooks of the app do not apply to its reports, see installMethod, nor does the StrictMode policy of the
        // thread, which Android sends along with the call, so what the service does would come back as violations of
        // the app, with the frames of the service
        enterService();
        StrictMode.ThreadPolicy policy = StrictMode.getThreadPolicy();
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX);
        try {
            // Few calls, each one transaction of the service; in parts, since errors have stack traces and a call
            // is limited in size (1 MB for all calls of the app at once)
            for (int i = 0; i < work.size(); i += REPORTS_PER_CALL)
                try {
                    Data.reports(context, new ArrayList<>(work.subList(i, Math.min(work.size(), i + REPORTS_PER_CALL))));
                } catch (Throwable ex) {
                    // One line, like for a settings provider which cannot be reached: the service answers no report,
                    // also not a refused one of an app whose restrictions were all removed while it runs
                    XLog.w(TAG, "Report " + packageName + ":" + uid + ": " + ex);
                }
        } finally {
            StrictMode.setThreadPolicy(policy);
            leaveService();
        }
    }

    // The libraries of scripts (see JsePlatform, and debug in debug builds), once per process, made on first use:
    // loading them takes much longer than running a script, so with libraries per thread the first hooked call
    // of each thread would be slower than the next ones; the built-in scripts only read them, and the call stacks
    // of DebugLib are per Java thread
    // shortcut: a user's script which calls xpcall, sets globals in a chunk it loads or changes a library table
    // shares that with the other threads; make the error function of LuaThread per Java thread, like the call
    // stacks, and the library tables read-only if such scripts matter
    private static class Libraries {
        static final Globals globals = JsePlatform.standardGlobals();

        static {
            if (BuildConfig.DEBUG)
                globals.load(new DebugLib());
        }
    }

    // The globals of a hook on the current thread, with the functions the script defines
    private static Globals getGlobals(Context context, XHook hook, Prototype script, Map<String, String> settings) {
        LuaLocals globals = new LuaLocals(Libraries.globals);
        globals.rawset("log", new LuaLog(context.getPackageName(), context.getApplicationInfo().uid, hook.getId()));
        globals.rawset("hook", new LuaHook(context, hook.getId(), settings, globals));
        new LuaClosure(script, globals).call();
        return globals;
    }

    // Scripts can define functions, but no other globals; the libraries are shared by all hooks
    private static class LuaLocals extends Globals {
        LuaLocals(Globals libraries) {
            // Same runtime, so errors have a traceback in debug builds; closures read only these fields
            // of their globals, the libraries use the globals they were loaded into
            this.debuglib = libraries.debuglib;
            this.running = libraries.running;
            super.setmetatable(LuaValue.tableOf(new LuaValue[]{LuaValue.INDEX, libraries}));
            // Else _G would be the shared libraries, and its globals visible to the other hooks
            super.rawset(LuaValue.valueOf("_G"), this);
        }

        @Override
        public void set(int key, LuaValue value) {
            if (value.isfunction())
                super.set(key, value);
            else
                error("Globals not allowed: set " + value);
        }

        @Override
        public void rawset(int key, LuaValue value) {
            if (value.isfunction())
                super.rawset(key, value);
            else
                error("Globals not allowed: rawset " + value);
        }

        @Override
        public void rawset(LuaValue key, LuaValue value) {
            if (value.isfunction())
                super.rawset(key, value);
            else
                error("Globals not allowed: " + key + "=" + value);
        }
    }

    // hook(object, method, function, args...): hook the methods with a name which the class of an object
    // declares or inherits from a super class
    private static class LuaHook extends VarArgFunction {
        // Dynamic hooks are installed on classes, so install each one only once, also when objects of several
        // subclasses inherit the method; by class, not name, since another class loader can have a class of the
        // same name, and weakly, so the set keeps no class the app drops
        private static final Map<Class<?>, Set<String>> installed = new WeakHashMap<>();

        private final Context context;
        private final String hookId;
        private final Map<String, String> settings;
        // The globals of the script on the installing thread, see execute
        private final Globals runtime;

        LuaHook(Context context, String hookId, Map<String, String> settings, Globals runtime) {
            this.context = context;
            this.hookId = hookId;
            this.settings = settings;
            this.runtime = runtime;
        }

        @Override
        public Varargs invoke(final Varargs args) {
            Class<?> cls = args.arg(1).checkuserdata().getClass();
            String m = args.arg(2).checkjstring();
            final LuaValue fun = args.arg(3).checkfunction();

            String key = hookId + "/" + m;
            List<Method> methods = new ArrayList<>();
            synchronized (installed) {
                if (!installed(cls).add(key))
                    return LuaValue.NIL;

                // Not an error, which would stop the script, which can hook other methods next
                List<Method> resolved = XResolver.resolveMethods(cls, m);
                if (resolved.isEmpty())
                    XLog.w(TAG, "Dynamic hook " + hookId + "/" + cls.getName() + "." + m + " not found");
                for (Method method : resolved)
                    if (installed(method.getDeclaringClass()).add(hookId + "/" + method))
                        methods.add(method);
            }
            if (methods.isEmpty())
                return LuaValue.NIL;

            XLog.i(TAG, "Dynamic hook " + cls.getName() + "." + m + " methods=" + methods.size());
            final List<LuaValue> xargs = new ArrayList<>();
            for (int i = 4; i <= args.narg(); i++)
                xargs.add(args.arg(i));

            XBackend.Callback callback = new XBackend.Callback() {
                @Override
                void before(XCall call) {
                    execute(call, "before");
                }

                @Override
                void after(XCall call) {
                    execute(call, "after");
                }

                // Errors must not reach the app, which the modern API would let through
                private void execute(XCall call, String when) {
                    if (inService())
                        return;
                    try {
                        if (BuildConfig.DEBUG)
                            XLog.i(TAG, "Dynamic invoke " + call.method);
                        List<LuaValue> values = new ArrayList<>();
                        values.add(LuaValue.valueOf(when));
                        values.add(CoerceJavaToLua.coerce(new XParam(context, call, settings)));
                        values.addAll(xargs);
                        // The function is of the globals of the script on the thread which installed the hook,
                        // whose locals are not thread safe for other dynamic hooks of it
                        synchronized (runtime) {
                            fun.invoke(values.toArray(new LuaValue[0]));
                        }
                    } catch (Throwable ex) {
                        XLog.e(TAG, "Dynamic hook " + hookId + " " + call.method + ": " + Log.getStackTraceString(ex));
                    }
                }
            };

            // The hooks which could not be installed are tried again on the next call
            for (int i = 0; i < methods.size(); i++)
                try {
                    backend.hook(methods.get(i), callback);
                } catch (Throwable ex) {
                    synchronized (installed) {
                        installed(cls).remove(key);
                        for (int j = i; j < methods.size(); j++)
                            installed(methods.get(j).getDeclaringClass()).remove(hookId + "/" + methods.get(j));
                    }
                    throw new LuaError(ex);
                }

            return LuaValue.NIL;
        }

        // The dynamic hooks of a class, by hook id and method name or method; locked by installed
        private static Set<String> installed(Class<?> cls) {
            return installed.computeIfAbsent(cls, c -> new HashSet<>());
        }
    }

    // log(value): write a value to the Android log
    private static class LuaLog extends OneArgFunction {
        private final String packageName;
        private final int uid;
        private final String hook;

        LuaLog(String packageName, int uid, String hook) {
            this.packageName = packageName;
            this.uid = uid;
            this.hook = hook;
        }

        @Override
        public LuaValue call(LuaValue arg) {
            XLog.i(TAG, "Log " + packageName + ":" + uid + " " + hook + " " +
                    arg.toString() + " (" + arg.typename() + ")");
            return LuaValue.NIL;
        }
    }
}
