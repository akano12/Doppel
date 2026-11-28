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
import android.os.Looper;
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
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaThread;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

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
    // second. In apps the hooked call of any thread but the main thread which comes once they are due sends them (see
    // sendDue), so no thread of the engine exists in the process of an app, which the app could list, and its frames are
    // only inside a hooked call, under the frames of its hook; a report with none sent in the second before is due right
    // away. The main thread leaves them for the next hooked call of another thread, since the service can be slow, unless
    // they waited for mainDelay, so an app which does everything on its main thread still reports them. System server
    // sends them on a thread of its own, which no app can list, since its hooked methods can hold locks which the settings
    // provider and the notifications of the service take too. The only thread an app gets is that of the start which
    // failed (see startLater), since an app which runs without its hooks is the worse outcome.
    // shortcut: the reports of the last second before an app makes no more hooked calls wait for its next one, and are
    // lost when its process ends first; send them also when the last activity of the app stops if users miss them
    // shortcut: the hooked call which sends the reports waits for the service: a call with one report took 0.6 to
    // 2.3 ms (median) and up to about 10 ms on emulators (API 31 and 36), against 0.1 to 0.4 ms for a call the
    // settings provider answers itself; measure a full batch on a phone, and send them by a call which does not wait,
    // if apps slow down
    // Not final, like the next two, so tests do not wait
    static long reportDelay = TimeUnit.SECONDS.toNanos(1);
    static long mainDelay = TimeUnit.SECONDS.toNanos(30);
    static BooleanSupplier onMainThread = () -> {
        Looper main = Looper.getMainLooper();
        return (main != null && main.isCurrentThread());
    };
    private final Map<String, Map<String, Bundle>> queue = new HashMap<>();
    private volatile boolean queued = false;
    // By System.nanoTime, when the queue is due, and when reports were last sent
    private volatile long due = 0;
    private long sent = 0;
    private boolean anySent = false;
    private ScheduledExecutorService background = null;
    // By System.nanoTime, when the last error of a hook function was reported, see reportError
    private final Map<String, Long> errored = new ConcurrentHashMap<>();
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

    // When the app-start hook fires, see hookApplication, once all entry points have claimed: apps can read the system
    // properties, also apps without restrictions, which should get the answers of a device without Doppel. The app's
    // AppComponentFactory and the threads it starts can read the property before that, for a few ms (4 to 12 ms on
    // emulators)
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

    // Called by the catch of every callback, where an error would reach the hooked method, see ModernBackend
    static void log(Throwable ex) {
        if (!XLog.enabled())
            return;
        try {
            XLog.e(TAG, Log.getStackTraceString(ex));
            XBackend b = backend;
            if (b != null)
                b.log(ex);
        } catch (Throwable ignored) {
        }
    }

    static void log(String message) {
        if (!XLog.enabled())
            return;
        try {
            XLog.i(TAG, message);
            XBackend b = backend;
            if (b != null)
                b.log(message);
        } catch (Throwable ignored) {
        }
    }

    // System server
    void hookAndroid(ClassLoader classLoader) throws Throwable {
        // https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/am/ActivityManagerService.java
        Class<?> clsAM = Class.forName("com.android.server.am.ActivityManagerService", false, classLoader);

        int ready = backend.hookAll(clsAM, "systemReady", new XBackend.Callback() {
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

        // Logged, since the service would else never get its module version, signers and receivers, and the hooks of
        // "android" none, on a system whose method is another, without a sign of it
        if (ready == 0)
            log("Service not started: no systemReady method of " + clsAM.getName() + " hooked");

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
        if (Util.SDK >= Build.VERSION_CODES.TIRAMISU)
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
    // also of an app without assignments now, since the service answers its uid once it has some. Seen to fire on
    // Android 12 and 16.
    // shortcut: a call the app sends with IBinder.transact itself, which needs no access to hidden APIs, still reaches
    // the service at any time (a query by the source too), and so does a query or call of the app's AppComponentFactory
    // (Android 9 and later), which runs before the engine and needs access to hidden APIs for a ContentProviderProxy of
    // its own; upgrade to a token the code of the app cannot get
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
                        markCall(call.args, Util.SDK, call.thisObject);
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
    // on Android 9 it tells a call of the settings provider from one of another provider, see markCall
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

    // Android 9 and later send the first five frames of any exception of system server to the caller, at most once a
    // second (Parcel.writeException), also to apps outside the scope, where the frames of the hook would follow those of
    // the settings provider: they are left out, so its caller, a method of ContentProvider or its Transport, follows like
    // on a device without Doppel
    // In the callbacks of system server, which an error of would reach it, see ModernBackend
    private static void withoutHookFrames(XCall call) {
        try {
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
        } catch (Throwable ex) {
            log(ex);
        }
    }

    // Whom the service answers. When the service fails, like with a database which cannot be opened, which it logged and
    // told the user about, system server, the app and the apps the service last answered as apps with assignments get no
    // answer, which Data reports as an error, instead of the error of the settings provider, which hooked apps take for
    // having no assignments; all other apps get the error of the settings provider, like without Doppel.
    // shortcut: a restricted app the service did not answer since the boot gets the error of the settings provider, so
    // its process logs that it has no assigned hooks instead of the error and, unlike an app the service answered before,
    // whose engine tries again and installs the hooks late, it is not tried again; upgrade to assigned uids kept outside
    // the database if that matters
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
                    if (Process.isIsolated()) {
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
        // right before Instrumentation.newApplication makes the application, so the hooks are installed by a hook there
        // which returns before the code of the app runs. Seen to fire on API 30, 31, 35 and 36 under LSPosed 2.2.1; the
        // boot images of 31 and 36 call it through its entry point, as it has 90 to 92 code units, over the 32 which ART
        // inlines. Android 17 calls it through a new private method of LoadedApk, and has the class in Conscrypt, with the
        // same name and signature. newApplication is hooked too, so a hook here which is installed but never fires does
        // not leave the app without any hook: the first of the two which fires installs the hooks. The constructor,
        // static initializer and attachBaseContext of the app's Application run inside the hook of newApplication, also
        // when it does nothing, though its frames do not show in their stack traces under LSPosed 2.2.1 (API 31 and 36).
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/LoadedApk.java
        // shortcut: not seen to fire under Vector (which deoptimizes makeApplication(Inner) in every process,
        // VectorInlinedCallers.kt) or on Android 17, and whether Vector shows the frames of the hook of newApplication was
        // not seen either; probe both on such a device
        try {
            Class<?> clsConfig = XResolver.resolveClass("android.security.net.config.NetworkSecurityConfigProvider", classLoader);
            // The hooks are installed when it returned, so the network security config is made without them, like before
            backend.hook(clsConfig.getDeclaredMethod("handleNewApplication", Context.class), new XBackend.Callback() {
                @Override
                void after(XCall call) throws Throwable {
                    callback.before(call);
                }
            });
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
        } catch (Throwable ex) {
            // Logged; the hook of newApplication below installs the hooks then, else the app would run without any hook
            log(ex);
        }

        // shortcut: Android 9 and 10 call nothing between createAppContext and newApplication, so with the modern API
        // the Application is made inside the hook, though its frames do not show in the Application's stack traces under
        // LSPosed 2.2.1 (API 31 and 36, with only this hook installing); hook the end of createAppContext if that matters
        // Instrumentation.newApplication is called by LoadedApk.makeApplication(Inner) on all versions; on Android 11 and
        // later it does nothing once the hook of handleNewApplication fired
        // https://android.googlesource.com/platform/frameworks/base/+/169aeafb2d97b810ae123ad036d0c58336961c55%5E%21/#F1
        Class<?> clsInstrumentation = XResolver.resolveClass("android.app.Instrumentation", classLoader);
        backend.hookAll(clsInstrumentation, "newApplication", callback);
    }

    // The hooks assigned to an app, the settings they run with, and the version of the app, see assigned
    static final class Assigned {
        final List<XHook> hooks;
        final Map<String, String> settings;
        final long versionCode;

        Assigned(List<XHook> hooks, Map<String, String> settings, long versionCode) {
            this.hooks = hooks;
            this.settings = settings;
            this.versionCode = versionCode;
        }
    }

    // What the reports of the engine itself have as their hook, see startLater
    static final String ENGINE_HOOK = XProvider.ENGINE_HOOK;

    // The delays in milliseconds between the tries to get the hooks of an app while the service fails: the first ones keep
    // the start of the app waiting, which they delay by their sum at most, the later ones are tried in the background.
    // Not final, so tests do not wait.
    static long[] startDelays = {100, 300};
    static long[] lateDelays = {2000, 5000, 15000, 30000};

    // Gets the hooks of an app and its settings; not final, so tests can have the service answer
    interface Fetcher {
        Assigned get(Context context, String packageName, int uid) throws Throwable;
    }

    static Fetcher fetcher = XCore::assigned;

    // The service runs the module it started with until a reboot, so after an update its parcels have the layout of the
    // version before, which the engine of an app started since reads as those of its own: it asks the version first
    static final class ServiceVersionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        ServiceVersionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // What the engine asks the service to tell its version, and an app without assignments from a service of before that
    // query, which both get the error of the settings provider; not final, so tests answer
    interface Probe {
        int version(Context context) throws Throwable;

        // A query only an app with assignments gets answered, by any version of the service
        void settings(Context context) throws Throwable;
    }

    static Probe probe = new Probe() {
        @Override
        public int version(Context context) {
            return Data.getServiceVersion(context);
        }

        @Override
        public void settings(Context context) {
            Data.getSettings(context, "global");
        }
    };

    private void hookPackage(final String packageName, int uid, final Context context) throws Throwable {
        // Before the first query of the engine; an app whose code cannot be kept from the service still gets its hooks.
        // System server has no code of an app, and its settings provider is its own, reached without a proxy.
        if (!"android".equals(packageName))
            try {
                hideService(context.getClassLoader());
            } catch (Throwable ex) {
                log(ex);
            }

        Assigned assigned;
        try {
            assigned = fetch(context, packageName, uid);
        } catch (Throwable ex) {
            // The service failed, like with a database which it cannot read: the app would run with the real values for
            // its whole life, so the hooks are tried again, and reported once the service answers, see startLater
            log(new IllegalStateException("Hooks of " + packageName + ":" + uid + " not got", ex));
            if (ex instanceof ServiceVersionException)
                // Which the service keeps until it restarts, so it is reported now, and not tried again
                reportStart(context, packageName, uid, ex, "The service runs another version of the module than the app: " +
                        "the app runs with its real values until the device restarts");
            else
                startLater(context, packageName, uid, ex, 0);
            return;
        }
        if (assigned != null)
            installAll(context, assigned);
    }

    // The hooks of an app, null without assignments, tried again after the delays of the start while the service fails
    private static Assigned fetch(Context context, String packageName, int uid) throws Throwable {
        for (int attempt = 0; ; attempt++)
            try {
                return fetcher.get(context, packageName, uid);
            } catch (Throwable ex) {
                if (ex instanceof ServiceVersionException || attempt >= startDelays.length)
                    throw ex;
                XLog.w(TAG, "Hooks of " + packageName + ":" + uid + " again: " + ex);
                try {
                    Thread.sleep(startDelays[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
    }

    // The app runs without its hooks until the service answers: it is asked again in the background, after the late
    // delays, and the hooks are installed when it does, which then reports that they were late, as the service can only
    // be told once it works. An app which the service no longer has assignments for is left alone.
    // shortcut: the hooks are installed while the app runs, like those of a script which hooks a method of an object it
    // gets (see LuaHook), see hookApplication; a method run by another thread meanwhile did not fail on a device (API 30,
    // LSPosed 2.2.1: 1757 hooks installed late while two threads read a file every 250 ms, no exception, as LSPlant
    // suspends all threads for each hook); an app which is still without hooks after the last try stays so, and the
    // engine reports nothing then, as the service tells the user itself when it fails
    private void startLater(final Context context, final String packageName, final int uid, final Throwable failure,
                            final int attempt) {
        if (attempt >= lateDelays.length)
            return;
        try {
            schedule(() -> {
                Assigned assigned;
                try {
                    assigned = fetcher.get(context, packageName, uid);
                } catch (Throwable ex) {
                    log(ex);
                    startLater(context, packageName, uid, failure, attempt + 1);
                    return;
                }
                if (assigned == null)
                    return;
                try {
                    installAll(context, assigned);
                    reportStart(context, packageName, uid, failure, "The hooks of the app were installed late, after the " +
                            "service failed to give them: until then the app ran with its real values");
                } catch (Throwable ex) {
                    log(ex);
                }
            }, TimeUnit.MILLISECONDS.toNanos(lateDelays[attempt]));
        } catch (Throwable ex) {
            log(ex);
        }
    }

    // A report of the engine about the start of an app, for the service and the user
    private void reportStart(Context context, String packageName, int uid, Throwable failure, String text) {
        Bundle data = new Bundle();
        data.putString("exception", "Exception:\n" + Log.getStackTraceString(failure) + "\n" +
                "\nPackage:\n" + packageName + ":" + uid + "\n\n" + text + "\n");
        report(context, ENGINE_HOOK, null, "install", data);
        // An app without hooks makes no hooked call which would send it, also not after the start of the main thread
        try {
            schedule(() -> flush(context, packageName, uid), 0);
        } catch (Throwable ex) {
            log(ex);
        }
    }

    // The hooks assigned to an app with their settings and the version of the app, null when it has no assignments, which
    // the settings provider refuses the query of, see XProvider.answers; logged, since an illegal argument of the
    // service would else leave the app without hooks silently. Any other error of the service is thrown, also one after
    // the hooks, so an app never runs with a part of its settings.
    static Assigned assigned(Context context, String packageName, int uid) throws Throwable {
        enterService();
        try {
            // The service of system server is of the code it runs in
            if (!"android".equals(packageName) && !sameVersion(context))
                return null;
            List<XHook> hooks;
            try {
                hooks = Data.getAssignedHooks(context, packageName, uid);
            } catch (IllegalArgumentException ex) {
                XLog.i(TAG, "No assigned hooks: " + ex.getMessage());
                return null;
            }
            if (hooks.isEmpty())
                return null;
            Map<String, String> settings = getSettings(context, packageName, uid);
            PackageInfo pi = Util.getPackageInfo(context.getPackageManager(), context.getPackageName(), 0);
            return new Assigned(hooks, settings, PackageInfoCompat.getLongVersionCode(pi));
        } finally {
            leaveService();
        }
    }

    private void installAll(final Context context, Assigned assigned) {
        // Hooks with the same script share the compiled script, and the classes are looked up once, also those which are
        // missing, like of an SDK the app does not have, which all hooks of it name
        Map<String, Script> scripts = new HashMap<>();
        Map<String, Object> classes = new HashMap<>();
        for (final XHook hook : assigned.hooks)
            try {
                if (!hook.isAvailable(assigned.versionCode))
                    continue;

                // Only install errors are reported, see XProvider.reports
                install(context, hook, scripts.computeIfAbsent(hook.getLuaScript(), Script::new), assigned.settings, classes);
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
        // The hooks keep their script, which would keep the runtime of its filter
        for (Script script : scripts.values())
            script.evaluation = null;
    }

    // Whether the service runs the module of this version, which an app with assignments is told; false for an app
    // without, which the settings provider refuses, like a service of before the question, which only the settings of all
    // apps tell apart: an app with assignments gets those. A service of another version is an error, since the engine
    // would not read its parcels, see ServiceVersionException.
    static boolean sameVersion(Context context) throws Throwable {
        int service;
        try {
            service = probe.version(context);
        } catch (IllegalArgumentException | SecurityException ex) {
            try {
                probe.settings(context);
            } catch (IllegalArgumentException | SecurityException refused) {
                XLog.i(TAG, "No assigned hooks: " + refused.getMessage());
                return false;
            }
            throw new ServiceVersionException("The service is of a version before the question of its version", ex);
        }
        if (!Data.isAvailable(service))
            throw new ServiceVersionException("The service is of version " + service + ", the module of version " +
                    BuildConfig.VERSION_CODE, null);
        return true;
    }

    // App settings replace global settings, the identifiers the service derives both (see FakeIds); an error of the
    // service is thrown, see assigned
    static Map<String, String> getSettings(Context context, String packageName, int uid) {
        Map<String, String> settings = new HashMap<>();
        enterService();
        try {
            settings.putAll(Data.getSettings(context, "global"));
            settings.putAll(Data.getSettings(context, packageName));
            settings.putAll(Data.getBootValues(context, packageName, uid));
        } finally {
            leaveService();
        }
        return withProbeSuffix(settings);
    }

    // Without the boot value of the service the suffix of the substitute names is made here, once for all hooks
    // and threads, so two threads of a script cannot each make and keep a different one
    static Map<String, String> withProbeSuffix(Map<String, String> settings) {
        if (!settings.containsKey(FakeIds.PROBE_SUFFIX))
            settings.put(FakeIds.PROBE_SUFFIX, FakeIds.generate(FakeIds.PROBE_SUFFIX, new SecureRandom()));
        return settings;
    }

    // The text of a script and what is made of it when it is needed: compiled once for all hooks with the same text, by
    // the thread which needs it first, and an error of that, which is kept
    static final class Script {
        private static final Pattern DEFINES = Pattern.compile("function\\s+(filter|shared)\\b|\\b(filter|shared)\\s*=[^=]");

        private final String text;
        private Prototype prototype = null;
        private Exception failure = null;
        private Boolean hooks = null;
        private Boolean defines = null;
        // The runtime which the installation of the hooks of a built-in script runs its filter and shared in, see installMethod
        Globals evaluation = null;

        Script(String text) {
            this.text = text;
        }

        // Whether the text can define the function filter or shared, which the installation runs: as a function of that name
        // or by an assignment, also one in a comment or of a local, which only makes the installation run the script
        synchronized boolean definesFilterOrShared() {
            if (defines == null)
                defines = DEFINES.matcher(text).find();
            return defines;
        }

        synchronized Prototype get() throws Exception {
            if (prototype == null && failure == null)
                try {
                    prototype = LuaC.instance.compile(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "script");
                } catch (Exception ex) {
                    failure = ex;
                }
            if (failure != null)
                throw failure;
            return prototype;
        }

        // Whether the script names the function hook, which hooks methods of the objects it gets, see LuaHook
        synchronized boolean hooks() throws Exception {
            if (hooks == null)
                hooks = names(get(), LuaValue.valueOf("hook"));
            return hooks;
        }

        private static boolean names(Prototype prototype, LuaValue name) {
            for (LuaValue constant : prototype.k)
                if (name.raweq(constant))
                    return true;
            for (Prototype child : prototype.p)
                if (names(child, name))
                    return true;
            return false;
        }
    }

    // The class of a name, looked up once for the hooks of an app start, also when it is missing: the hooks of an SDK
    // which the app does not have each name its classes, which would else each make the lookup and fail again
    private static Class<?> resolveClass(String name, ClassLoader loader, Map<String, Object> classes)
            throws ClassNotFoundException {
        Object known = classes.get(name);
        if (known == null) {
            try {
                known = XResolver.resolveClass(name, loader);
            } catch (ClassNotFoundException ex) {
                known = String.valueOf(ex.getMessage());
            }
            classes.put(name, known);
        }
        if (known instanceof String)
            throw new ClassNotFoundException((String) known);
        return (Class<?>) known;
    }

    // Hook the method or field of the definition
    private void install(Context context, XHook hook, Script script, Map<String, String> settings,
                         Map<String, Object> classes) throws Throwable {
        ClassLoader loader = context.getClassLoader();
        Class<?> cls = resolveClass(hook.getResolvedClassName(), loader, classes);

        String[] names = hook.getParameterTypes();
        Class<?>[] paramTypes = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++)
            paramTypes[i] = resolveClass(names[i], loader, classes);
        Class<?> returnType = (hook.getReturnType() == null ? null : resolveClass(hook.getReturnType(), loader, classes));

        Member member = XResolver.resolveTarget(cls, hook.getMethodName(), paramTypes, returnType);
        if (member instanceof Field) {
            Field field = (Field) member;
            field.setAccessible(true);
            installField(context, hook, script, settings, field);
        } else
            installMethod(context, hook, script, settings, member);
    }

    // Static fields are changed once, by the after function
    private void installField(Context context, XHook hook, Script script, Map<String, String> settings, Field field) {
        try {
            Globals globals = getGlobals(context, hook, script, settings);

            LuaValue func = globals.get("after");
            if (func.isnil())
                return;

            Varargs result = run(globals, func, new LuaValue[]{
                    CoerceJavaToLua.coerce(hook),
                    CoerceJavaToLua.coerce(new XParam(context, field, settings))
            });
            reportUse(context, hook, "after", result);
        } catch (Throwable ex) {
            reportError(context, hook, "after", ex, "\nField:\n" + field + "\n");
        }
    }

    private void installMethod(final Context context, final XHook hook, final Script script,
                               final Map<String, String> settings, final Member member) throws Throwable {
        // The installation runs the functions filter and shared of a script, which most scripts do not have: the scripts
        // of the app which have not are compiled, and run for their functions, by the first call of the hook on a thread,
        // so only the work of the scripts of the hooks which the app never calls is deferred, which are most; each distinct
        // hooked method still costs the framework about 1 ms at the start (1.0-1.3 s for 1045 methods on an API 30
        // emulator with all groups); the scripts of users are run here, so their errors are reported at the installation
        final Globals installed;
        final Filter filter;
        // The functions filter and shared of a built-in script only read the hook, so one runtime of the script answers for
        // all its hooks, and is not kept: the first call of a hook on a thread makes the runtime of that thread, like on
        // any other, so a hook which is never called keeps no runtime, which was most of what the installed hooks retained
        final boolean evaluates = (hook.isBuiltin() && script.definesFilterOrShared() && !script.hooks());
        String key = null;
        boolean once = false;
        if (hook.isBuiltin() && !script.definesFilterOrShared()) {
            installed = null;
            filter = null;
        } else {
            // The runtime made for the filter of a script of a user is the one of this thread, so the script runs once here
            if (evaluates) {
                if (script.evaluation == null)
                    script.evaluation = getGlobals(context, hook, script, settings);
                installed = script.evaluation;
            } else
                installed = getGlobals(context, hook, script, settings);
            // A script whose function shared returns true acts the same for each group, like refusing programs, which each
            // group does whose fakes a program could read: of its hooks of one method only the first is installed, so a
            // call runs the script once and not once per restricted group, whose other hooks would find nothing left to do
            // and only cost time; its use is reported for the group of that hook only, like one of them reported it before
            LuaValue shared = installed.get("shared");
            once = (!shared.isnil() && shared.call(CoerceJavaToLua.coerce(hook)).toboolean());
            if (once) {
                // Made only here: the text of a script is large, so a key for each hook was most of what the start allocated
                key = member + "\n" + hook.getLuaScript();
                if (sharedHooks.contains(key))
                    return;
            }
            filter = getFilter(installed, hook, member);
        }
        backend.hook(member, new XBackend.Callback() {
            // The Lua runtime of this hook per thread, without a lock on each call
            private final ThreadLocal<Globals> threadGlobals = new ThreadLocal<>();
            private final LuaValue luaHook = CoerceJavaToLua.coerce(hook);

            {
                if (installed != null && !evaluates)
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
                    Varargs result = run(globals, func, args);
                    reportUse(context, hook, function, result);
                } catch (Throwable ex) {
                    // An error of the script leaves its runtime usable (the call stacks of DebugLib unwind in finally),
                    // so a hook which always fails does not make and run the runtime again for every call; only
                    // an error of the VM, like running out of memory, may have left it half way
                    if (ex instanceof VirtualMachineError)
                        threadGlobals.remove();
                    failed(context, hook, function, ex, member, call);
                }
            }
        });
        if (once)
            sharedHooks.add(key);
        deoptimize(context, hook);
    }

    // An error of a script, reported, in the callbacks of hooks which an error would reach the hooked method from, see
    // ModernBackend, so nothing here throws. Describing the call runs code of the app (toString), which must not fail the
    // call of the app, nor run the scripts of hooked methods as if the app called them.
    private void failed(Context context, XHook hook, String function, Throwable ex, Member member, XCall call) {
        try {
            enterService();
            try {
                reportError(context, hook, function, ex, "\nMethod:\n" + function + " " + member + "\n" + describe(call));
            } finally {
                leaveService();
            }
        } catch (Throwable exex) {
            try {
                if (XLog.enabled())
                    XLog.e(TAG, hook.getId() + " " + function + ": " + Log.getStackTraceString(exex));
            } catch (Throwable ignored) {
            }
        }
    }

    // How long a function waits for the lock of a runtime, see run, in milliseconds
    private static final long LOCK_WAIT = 1000;

    // Runs a function of a script with the StrictMode policy of the thread off, so what the script does, like looking at
    // a file, is no violation of the app, whose own call stays one, and, in a runtime the functions of which hooks of
    // its script run on other threads too, under the lock all of them take, which they wait for a limited time only:
    // a script can wait for a thread which runs a function of its own runtime, which a lock held for its whole run would
    // deadlock with; the function then runs without it, like before there was a lock.
    private static Varargs run(Globals globals, LuaValue func, LuaValue[] args) {
        StrictMode.ThreadPolicy policy = StrictMode.getThreadPolicy();
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX);
        ReentrantLock lock = (globals instanceof LuaLocals && ((LuaLocals) globals).dynamic ? ((LuaLocals) globals).lock : null);
        boolean locked = false;
        try {
            if (lock != null)
                try {
                    locked = lock.tryLock(LOCK_WAIT, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            return func.invoke(args);
        } finally {
            if (locked)
                lock.unlock();
            StrictMode.setThreadPolicy(policy);
        }
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
    // through the hooks of canonicalize0, which return in the service; no rule of the text of a path tells that it has
    // no link which leads to a value, as a link of the app itself or of the system does, like /etc or /sdcard, so only
    // the hooks of one call share the result, see Filter.resolved. A link changed between the hooks and the call is read
    // as the kernel finds it: the file system resolves the path again inside the call, so an app which swaps a link
    // between the hooks and the call gets the real file through a call which returns a descriptor (seen on an API 30
    // emulator: about 1 in 80 reads of boot_id), and param:opens resolves the path again after the call; the extra resolve
    // costs about 8 us per call against about 48-54 us of a hooked stat (API 30 emulator), once per call; upgrade when apps
    // are seen doing that: check the link of the descriptor in /proc/self/fd after the calls which return one (about 5 us
    // per open).
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
    // list0, getLastModifiedTime0 and getSpace0, and checkAccess0 and getLength0 of Android 9. From Android 10
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
        // A hook which fails on each call of a method called very often would else make all this for each call; the
        // report keeps one error per hook and function, see report, so one in each second is enough
        long now = System.nanoTime();
        String failing = hook.getId() + ":" + function;
        Long last = errored.get(failing);
        if (last != null && now - last < reportDelay)
            return;
        errored.put(failing, now);

        String message = "Exception:\n" + Log.getStackTraceString(ex) + "\n" +
                "\nPackage:\n" + context.getPackageName() + ":" + context.getApplicationInfo().uid + "\n" +
                details;
        // The details have the real arguments and result, which release builds do not write to the system log,
        // like the service (see XProvider.Report); the report stores them for the app
        if (BuildConfig.DEBUG)
            XLog.e(TAG, message);
        else if (XLog.enabled())
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
            queue.computeIfAbsent(key, k -> new HashMap<>()).put(hook, args);

            if (queued)
                return;
            long now = System.nanoTime();
            due = (anySent && now - sent < reportDelay ? sent + reportDelay : now);
            queued = true;
            if ("android".equals(packageName)) {
                try {
                    schedule(() -> flush(context, packageName, uid), due - now);
                } catch (Throwable ex) {
                    // The next report tries again
                    queued = false;
                }
                return;
            }
        }
        sendDue(context);
    }

    // The reports of an app, once they are due, on the thread of the hooked call, see queue; those of the main thread wait
    // for another thread, until they are much older. Nothing here fails the hooked call, which an error would reach.
    private void sendDue(Context context) {
        try {
            long now = System.nanoTime();
            if (queued && now - due >= 0 && !"android".equals(context.getPackageName()) &&
                    (now - due >= mainDelay || !onMainThread.getAsBoolean()))
                flush(context, context.getPackageName(), context.getApplicationInfo().uid);
        } catch (Throwable ex) {
            log(ex);
        }
    }

    // Runs a task on the thread of the engine, which system server has for its reports and an app for the start which
    // failed only, see queue; made by the caller, without the hooks, since a method which makes threads can be hooked
    private void schedule(Runnable task, long delay) {
        enterService();
        try {
            synchronized (this) {
                if (background == null) {
                    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
                        Thread thread = new Thread(runnable);
                        thread.setDaemon(true);
                        return thread;
                    });
                    // Idle, the thread ends, so an app which lists its threads finds one while a task runs and in the
                    // seconds after it only
                    executor.setKeepAliveTime(2, TimeUnit.SECONDS);
                    executor.allowCoreThreadTimeOut(true);
                    background = executor;
                }
                background.schedule(task, delay, TimeUnit.NANOSECONDS);
            }
        } finally {
            leaveService();
        }
    }

    // shortcut: a count, not a size; 20 stack traces of a few KB each fit, measure the parcel when traces grow
    static final int REPORTS_PER_CALL = 20;

    // Sends reports to the service; not final, so tests see them
    interface Sender {
        void send(Context context, ArrayList<Bundle> reports) throws Throwable;
    }

    static Sender sender = Data::reports;

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
                    sender.send(context, new ArrayList<>(work.subList(i, Math.min(work.size(), i + REPORTS_PER_CALL))));
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
    // of each thread would be slower than the next ones; the call stacks of DebugLib are per Java thread. The scripts see
    // them through a view which they cannot change, see protect, and have the state they keep of the running thread,
    // which the functions xpcall and load use, of their own, see LuaLocals.
    private static class Libraries {
        static final Globals globals = JsePlatform.standardGlobals();
        static final LuaValue view;

        static {
            if (BuildConfig.DEBUG)
                globals.load(new DebugLib());
            view = protect(globals);
        }
    }

    // The libraries as tables which a script cannot change, in which the library of strings is the one strings find their
    // methods in: its metatable is the one of all strings, which a script could else change by getmetatable(''), and which
    // the library hides by its field __metatable. A script can still call everything it can reach by luajava, like all
    // Java code, but not, by a mistake or on purpose, change what the other scripts find in the libraries.
    static LuaValue protect(Globals libraries) {
        ReadOnly view = new ReadOnly(libraries, new IdentityHashMap<>());
        LuaString.s_metatable = LuaValue.tableOf(new LuaValue[]{
                LuaValue.INDEX, view.get("string"), LuaValue.METATABLE, LuaValue.valueOf("protected")});
        return view;
    }

    // A copy of a table with copies of the tables in it, except the globals, which none of them can change
    private static final class ReadOnly extends LuaTable {
        private boolean built = false;

        ReadOnly(LuaTable source, Map<LuaTable, ReadOnly> copies) {
            copies.put(source, this);
            for (Varargs entry = source.next(NIL); !entry.arg1().isnil(); entry = source.next(entry.arg1())) {
                LuaValue value = entry.arg(2);
                if (value instanceof Globals)
                    continue;
                if (value instanceof LuaTable) {
                    ReadOnly copy = copies.get(value);
                    value = (copy != null ? copy : new ReadOnly((LuaTable) value, copies));
                }
                super.rawset(entry.arg1(), value);
            }
            built = true;
        }

        private void check() {
            if (built)
                throw new LuaError("attempt to change a library");
        }

        @Override
        public void set(int key, LuaValue value) {
            check();
            super.set(key, value);
        }

        @Override
        public void set(LuaValue key, LuaValue value) {
            check();
            super.set(key, value);
        }

        @Override
        public void rawset(int key, LuaValue value) {
            check();
            super.rawset(key, value);
        }

        @Override
        public void rawset(LuaValue key, LuaValue value) {
            check();
            super.rawset(key, value);
        }

        @Override
        public void hashset(LuaValue key, LuaValue value) {
            check();
            super.hashset(key, value);
        }

        @Override
        public LuaValue remove(int pos) {
            check();
            return super.remove(pos);
        }

        @Override
        public void insert(int pos, LuaValue value) {
            check();
            super.insert(pos, value);
        }

        @Override
        public void sort(LuaValue comparator) {
            check();
            super.sort(comparator);
        }

        @Override
        public LuaValue setmetatable(LuaValue metatable) {
            check();
            return super.setmetatable(metatable);
        }
    }

    // The globals of a hook on the current thread, with the functions the script defines
    private Globals getGlobals(Context context, XHook hook, Script script, Map<String, String> settings) throws Exception {
        Prototype prototype = script.get();
        LuaLocals globals = new LuaLocals(Libraries.globals, Libraries.view);
        globals.dynamic = script.hooks();
        globals.rawset("log", new LuaLog(context.getPackageName(), context.getApplicationInfo().uid, hook.getId()));
        globals.rawset("hook", new LuaHook(context, hook, settings, globals));
        new LuaClosure(prototype, globals).call();
        return globals;
    }

    // Scripts can define functions, but no other globals; the libraries are shared by all hooks, but not their state of
    // the running thread, which only the error function of xpcall is: the libraries use that of the globals they were
    // loaded into, so a script gets its own xpcall, which uses that of its own globals, and its own load, which loads
    // into its own globals. A runtime is made per hook and thread.
    static class LuaLocals extends Globals {
        // Whether hooks of the script run its functions on other threads, which have the lock of the runtime, see run
        volatile boolean dynamic = false;
        final ReentrantLock lock = new ReentrantLock();

        LuaLocals(Globals libraries, LuaValue view) {
            // Same debug library, so errors have a traceback in debug builds, of which the call stacks are per Java thread;
            // closures read only these fields of their globals, the libraries use the globals they were loaded into
            this.debuglib = libraries.debuglib;
            super.setmetatable(LuaValue.tableOf(new LuaValue[]{
                    LuaValue.INDEX, view, LuaValue.METATABLE, LuaValue.valueOf("protected")}));
            // Else _G would be the shared libraries, and its globals visible to the other hooks
            super.rawset(LuaValue.valueOf("_G"), this);
            super.rawset(LuaValue.valueOf("xpcall"), new XPcall(this));
            super.rawset(LuaValue.valueOf("load"), new Load(this, libraries.get("load")));
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

    // xpcall(f, handler, ...) like the library's, with the error function in the running thread of the globals of the
    // script, so a handler is called for errors of the functions of that script only, not of other hooks, on other threads
    private static final class XPcall extends VarArgFunction {
        private final Globals globals;

        XPcall(Globals globals) {
            this.globals = globals;
        }

        @Override
        public Varargs invoke(Varargs args) {
            final LuaThread thread = globals.running;
            final LuaValue previous = thread.errorfunc;
            thread.errorfunc = args.checkvalue(2);
            try {
                if (globals.debuglib != null)
                    globals.debuglib.onCall(this);
                try {
                    return varargsOf(TRUE, args.arg1().invoke(args.subargs(3)));
                } catch (LuaError le) {
                    LuaValue message = le.getMessageObject();
                    return varargsOf(FALSE, message != null ? message : NIL);
                } catch (Exception e) {
                    String message = e.getMessage();
                    return varargsOf(FALSE, valueOf(message != null ? message : e.toString()));
                } finally {
                    if (globals.debuglib != null)
                        globals.debuglib.onReturn();
                }
            } finally {
                thread.errorfunc = previous;
            }
        }
    }

    // load(chunk, name, mode, env) like the library's, with the globals of the script as the environment of a chunk which
    // has none, which the library's would give the shared globals
    private static final class Load extends VarArgFunction {
        private final Globals globals;
        private final LuaValue load;

        Load(Globals globals, LuaValue load) {
            this.globals = globals;
            this.load = load;
        }

        @Override
        public Varargs invoke(Varargs args) {
            return load.invoke(args.arg(4).isnil()
                    ? varargsOf(new LuaValue[]{args.arg(1), args.arg(2), args.arg(3), globals}) : args);
        }
    }

    // The dynamic hooks of a class, by hook id and method name or method; locked by itself. Installed on classes, so
    // install each one only once, also when objects of several subclasses inherit the method; by class, not name, since
    // another class loader can have a class of the same name, and weakly, so the set keeps no class the app drops
    private static final Map<Class<?>, Set<String>> dynamicHooks = new WeakHashMap<>();

    private static Set<String> dynamicHooks(Class<?> cls) {
        return dynamicHooks.computeIfAbsent(cls, c -> new HashSet<>());
    }

    // hook(object, method, function, args...): hook the methods with a name which the class of an object
    // declares or inherits from a super class
    private class LuaHook extends VarArgFunction {
        private final Context context;
        private final XHook hook;
        private final Map<String, String> settings;
        // The globals of the script on the installing thread, see run
        private final LuaLocals runtime;

        LuaHook(Context context, XHook hook, Map<String, String> settings, LuaLocals runtime) {
            this.context = context;
            this.hook = hook;
            this.settings = settings;
            this.runtime = runtime;
        }

        @Override
        public Varargs invoke(final Varargs args) {
            runtime.dynamic = true;
            Class<?> cls = args.arg(1).checkuserdata().getClass();
            String m = args.arg(2).checkjstring();
            final LuaValue fun = args.arg(3).checkfunction();
            final List<LuaValue> xargs = new ArrayList<>();
            for (int i = 4; i <= args.narg(); i++)
                xargs.add(args.arg(i));

            String key = hook.getId() + "/" + m;
            // Marked as installed when it is, so another thread which hooks the same method waits for the hook instead of
            // going on without it; the hooks which could not be installed are tried again on the next call
            synchronized (dynamicHooks) {
                if (dynamicHooks(cls).contains(key))
                    return LuaValue.NIL;

                // Not an error, which would stop the script, which can hook other methods next
                List<Method> methods = new ArrayList<>();
                List<Method> resolved = XResolver.resolveMethods(cls, m);
                if (resolved.isEmpty())
                    XLog.w(TAG, "Dynamic hook " + hook.getId() + "/" + cls.getName() + "." + m + " not found");
                for (Method method : resolved)
                    if (!dynamicHooks(method.getDeclaringClass()).contains(hook.getId() + "/" + method))
                        methods.add(method);

                if (!methods.isEmpty()) {
                    XLog.i(TAG, "Dynamic hook " + cls.getName() + "." + m + " methods=" + methods.size());
                    XBackend.Callback callback = callback(fun, xargs);
                    for (Method method : methods)
                        try {
                            backend.hook(method, callback);
                            dynamicHooks(method.getDeclaringClass()).add(hook.getId() + "/" + method);
                        } catch (Throwable ex) {
                            throw new LuaError(ex);
                        }
                }
                dynamicHooks(cls).add(key);
            }
            return LuaValue.NIL;
        }

        private XBackend.Callback callback(final LuaValue fun, final List<LuaValue> xargs) {
            return new XBackend.Callback() {
                @Override
                void before(XCall call) {
                    execute(call, "before");
                }

                @Override
                void after(XCall call) {
                    execute(call, "after");
                }

                // Like the callback of a hook of a definition: errors must not reach the app, which the modern API would
                // let through; what the function returns is its use, if it is a boolean, and the other values are none
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
                        // The function is of the globals of the script on the thread which installed the hook, whose
                        // locals are not thread safe for the other threads, nor for the functions of the script there
                        Varargs result = run(runtime, fun, values.toArray(new LuaValue[0]));
                        if (result.arg1().isboolean())
                            reportUse(context, hook, when, result);
                    } catch (Throwable ex) {
                        // The runtime is shared by all threads, so it is kept, unlike that of a thread of a hook
                        failed(context, hook, when, ex, call.method, call);
                    }
                }
            };
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
            // Nothing is made when nothing is logged, like a text of an object of the app, whose toString runs its code
            if (XLog.enabled()) {
                enterService();
                try {
                    XLog.i(TAG, "Log " + packageName + ":" + uid + " " + hook + " " +
                            arg.toString() + " (" + arg.typename() + ")");
                } finally {
                    leaveService();
                }
            }
            return LuaValue.NIL;
        }
    }
}
