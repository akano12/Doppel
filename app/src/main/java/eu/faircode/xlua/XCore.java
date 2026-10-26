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
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.Process;
import android.os.UserHandle;
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
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Hook engine, independent of the hooking framework
class XCore {
    private static final String TAG = "XLua.Core";

    // The settings provider is loaded into system server
    static final String SETTINGS_PACKAGE = "com.android.providers.settings";

    // System property shared by all class loaders of a process
    private static final String PROP_ENGINE = "eu.faircode.xlua.engine";

    private static volatile XBackend backend = null;
    private static volatile int version = -1;

    // Usage reports are sent in batches, with the last report per hook and event, and the last error
    private final Map<String, Map<String, Bundle>> queue = new HashMap<>();
    private boolean flushing = false;
    private final ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XLua.Report");
        thread.setDaemon(true);
        return thread;
    });

    XCore(XBackend backend) {
        XCore.backend = backend;
    }

    // The hooks assigned to "android" apply to all of system server, but not to the service and the code of this
    // class there, which look up packages and call the service themselves: a restriction like Get.Applications
    // would else hide the module and the apps from the service. See installMethod.
    // In apps the reports and the package lookups of XParam are neither restricted nor reported as calls of the app.
    private static final ThreadLocal<int[]> serviceCalls = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };

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
                Log.i(TAG, "Using " + backend.getName() + " API in " + Process.myPid());
                return true;
            }
            return engine.equals(backend.getName());
        }
    }

    // Packages loaded into another app's process (WebView, GMS, etc.) are handled by the host app
    static boolean isApp(String packageName, boolean firstPackage) {
        return (firstPackage &&
                !"android".equals(packageName) &&
                !BuildConfig.APPLICATION_ID.equals(packageName));
    }

    static void log(Throwable ex) {
        Log.e(TAG, Log.getStackTraceString(ex));
        XBackend b = backend;
        if (b != null)
            try {
                b.log(ex);
            } catch (Throwable ignored) {
            }
    }

    static void log(String message) {
        Log.i(TAG, message);
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

                    // Store current module version
                    PackageInfo pi = context.getPackageManager().getPackageInfo(BuildConfig.APPLICATION_ID, 0);
                    version = (int) PackageInfoCompat.getLongVersionCode(pi);
                    Log.i(TAG, "Module version " + version);

                    // The texts of notifications of the module version which runs, also after an update
                    try {
                        XNotify.loadResources(context);
                    } catch (Throwable ex) {
                        log(ex);
                    }

                    // Listen for removed users and profiles; the broadcast has no data, so a filter of its own
                    try {
                        registerReceiver(context, new IntentFilter(ReceiverPackage.ACTION_USER_REMOVED));
                        Log.i(TAG, "Registered user listener");
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
                        Log.i(TAG, "Registered package listener for all users");
                    } catch (Throwable ex) {
                        Log.w(TAG, "Registering for all users: " + ex);

                        // The existing users only; looked up here, so a change of these hidden methods
                        // does not prevent registering for all users
                        // public static UserManagerService getInstance()
                        ClassLoader loader = call.thisObject.getClass().getClassLoader();
                        Class<?> clsUM = Class.forName("com.android.server.pm.UserManagerService", false, loader);
                        Object um = clsUM.getDeclaredMethod("getInstance").invoke(null);
                        // public int[] getUserIds()
                        int[] userids = (int[]) um.getClass().getDeclaredMethod("getUserIds").invoke(um);
                        for (int userid : userids) {
                            Log.i(TAG, "Registering package listener user=" + userid);
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
                            Log.i(TAG, "Context found in " + cAm + " as " + field.getName());
                            return (Context) field.get(am);
                        }
                throw new Throwable("Context not found");
            }
        });
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
                int caller = XProvider.answers(context);
                if (caller == XProvider.CALLER_NONE)
                    return;

                try {
                    call.setResult(XProvider.call(context, arg, extras, caller == XProvider.CALLER_PERMITTED));
                } catch (IllegalArgumentException ex) {
                    Log.i(TAG, "Error: " + ex.getMessage());
                    call.setThrowable(ex);
                } catch (SecurityException ex) {
                    // A refused app gets no result, like before
                    Log.w(TAG, "Call " + arg + ": " + ex.getMessage());
                    call.setResult(null);
                } catch (RemoteException ex) {
                    // An error of the service, which XProvider logged with its cause already
                    Log.w(TAG, "Call " + arg + ": " + ex.getMessage());
                    call.setResult(null);
                } catch (Throwable ex) {
                    log(ex);
                    call.setResult(null);
                }
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
                    if (projection == null || projection.length == 0 ||
                            projection[0] == null || !projection[0].startsWith(XProvider.COLUMN))
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
                int caller = XProvider.answers(context);
                if (caller == XProvider.CALLER_NONE)
                    return;

                try {
                    String method = column.split("\\.")[1];
                    call.setResult(XProvider.query(context, method, selection, caller == XProvider.CALLER_PERMITTED));
                } catch (IllegalArgumentException ex) {
                    Log.i(TAG, "Error: " + ex.getMessage());
                    call.setThrowable(ex);
                } catch (SecurityException ex) {
                    Log.w(TAG, "Query " + column + ": " + ex.getMessage());
                    call.setResult(null);
                } catch (RemoteException ex) {
                    // An error of the service, which XProvider logged with its cause already
                    Log.w(TAG, "Query " + column + ": " + ex.getMessage());
                    call.setResult(null);
                } catch (Throwable ex) {
                    log(ex);
                    call.setResult(null);
                }
            }
        });
    }

    // ContentProvider.getContext()
    private static Context getContext(Object provider) throws Throwable {
        return (Context) provider.getClass().getMethod("getContext").invoke(provider);
    }

    // First package of an app process
    void hookApplication(final String packageName, ClassLoader classLoader) throws Throwable {
        final int uid = Process.myUid();

        // Instrumentation.newApplication is called by LoadedApk.makeApplication(Inner) on all versions
        // https://android.googlesource.com/platform/frameworks/base/+/169aeafb2d97b810ae123ad036d0c58336961c55%5E%21/#F1
        Class<?> clsInstrumentation = Class.forName("android.app.Instrumentation", false, classLoader);
        backend.hookAll(clsInstrumentation, "newApplication", new XBackend.Callback() {
            private boolean made = false;

            @Override
            void after(XCall call) {
                try {
                    if (!made && call.getResult() instanceof Application) {
                        made = true;
                        Context context = (Application) call.getResult();

                        // Isolated processes (including app zygote) cannot access the provider
                        if (Util.isIsolated(uid)) {
                            Log.i(TAG, "Skipping isolated " + packageName + ":" + uid);
                            return;
                        }

                        hookPackage(packageName, uid, context);
                    }
                } catch (Throwable ex) {
                    log(ex);
                }
            }
        });
    }

    private void hookPackage(final String packageName, int uid, final Context context) throws Throwable {
        List<XHook> hooks;
        try {
            hooks = Data.getAssignedHooks(context, packageName, uid);
        } catch (IllegalArgumentException ignored) {
            // The settings provider refuses the query of an app without assignments, see XProvider.answers
            return;
        }
        if (hooks.isEmpty())
            return;

        // App settings replace global settings
        final Map<String, String> settings = new HashMap<>(Data.getSettings(context, "global"));
        settings.putAll(Data.getSettings(context, packageName));

        // Hooks with the same script share the compiled script
        Map<String, Prototype> scripts = new HashMap<>();

        PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        long versionCode = PackageInfoCompat.getLongVersionCode(pi);
        for (final XHook hook : hooks)
            try {
                if (!hook.isAvailable(versionCode))
                    continue;

                // Only install errors are reported, see XProvider.report
                install(context, hook, compile(hook, scripts), settings);
            } catch (Throwable ex) {
                if (hook.isOptional() &&
                        (ex instanceof NoSuchFieldException ||
                                ex instanceof NoSuchMethodException ||
                                ex instanceof ClassNotFoundException ||
                                ex instanceof NoClassDefFoundError))
                    Log.i(TAG, "Optional hook=" + hook.getId() + ": " + ex.getClass().getName() + ": " + ex.getMessage());
                else {
                    Log.e(TAG, hook.getId() + ": " + Log.getStackTraceString(ex));

                    // Report install error
                    Bundle data = new Bundle();
                    data.putString("exception", ex instanceof LuaError ? ex.getMessage() : Log.getStackTraceString(ex));
                    report(context, hook.getId(), null, "install", data);
                }
            }
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
        Class<?> cls = Class.forName(hook.getResolvedClassName(), false, loader);

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
        final Filter filter = getFilter(context, hook, script, settings);
        backend.hook(member, new XBackend.Callback() {
            // The Lua runtime of this hook per thread, without a lock on each call
            private final ThreadLocal<Globals> threadGlobals = new ThreadLocal<>();
            private final LuaValue luaHook = CoerceJavaToLua.coerce(hook);

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
                if (filter != null && (call.args == null || call.args.length == 0 ||
                        !filter.matches(filterValue(call.args[0]))))
                    return;
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

                    Varargs result = func.invoke(new LuaValue[]{
                            luaHook,
                            CoerceJavaToLua.coerce(new XParam(context, call, settings))
                    });
                    reportUse(context, hook, function, result);
                } catch (Throwable ex) {
                    threadGlobals.remove();
                    // Describing the call runs code of the app (toString), which must not fail the call of the app
                    try {
                        reportError(context, hook, function, ex,
                                "\nMethod:\n" + function + " " + member + "\n" + describe(call));
                    } catch (Throwable exex) {
                        Log.e(TAG, hook.getId() + " " + function + ": " + Log.getStackTraceString(exex));
                    }
                }
            }
        });
    }

    // The values of the first argument a script restricts, which its function filter returns for the hook,
    // like the paths of files a hook of opening files restricts; the other calls do not run the script,
    // so hooks of methods which are called very often cost (almost) nothing else; null without the function.
    // A value ending in * stands for all values starting with the text before it, like /sys/* for the files
    // of /sys, for a script which tells the paths apart itself. Files and paths of java.nio are compared
    // by their path, see filterValue.
    static Filter getFilter(Context context, XHook hook, Prototype script, Map<String, String> settings) {
        LuaValue func = getGlobals(context, hook, script, settings).get("filter");
        if (func.isnil())
            return null;
        LuaValue values = func.call(CoerceJavaToLua.coerce(hook));
        if (values.isnil())
            return null;
        Filter filter = new Filter();
        for (int i = 1; i <= values.length(); i++)
            filter.add(values.get(i).checkjstring());
        return filter;
    }

    static class Filter {
        private final Set<String> values = new HashSet<>();
        private final List<String> prefixes = new ArrayList<>();

        private void add(String value) {
            if (value.endsWith("*"))
                this.prefixes.add(value.substring(0, value.length() - 1));
            else
                this.values.add(value);
        }

        boolean matches(String value) {
            if (value == null)
                return false;
            if (this.values.contains(value))
                return true;
            for (String prefix : this.prefixes)
                if (value.startsWith(prefix))
                    return true;
            return false;
        }
    }

    // The value of an argument for a filter: texts, and the path of files and of paths of java.nio,
    // without running code of the app; null for other values
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

    private static String describe(Object value) {
        return (value == null ? "null" : value + " (" + value.getClass().getName() + ")");
    }

    // Scripts return whether they restricted something, optionally with the old and new value;
    // the data is what XProvider.report stores
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
        // like the service (see XProvider.report); the report stores them for the app
        if (BuildConfig.DEBUG)
            Log.e(TAG, message);
        else
            Log.e(TAG, hook.getId() + " " + function + ": " + Log.getStackTraceString(ex));

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

            if (!flushing) {
                flushing = true;
                reporter.schedule(() -> flush(context, packageName, uid), 1, TimeUnit.SECONDS);
            }
        }
    }

    private void flush(Context context, String packageName, int uid) {
        Log.i(TAG, "Processing event queue package=" + packageName + ":" + uid);

        List<Bundle> work = new ArrayList<>();
        synchronized (queue) {
            for (Map<String, Bundle> reports : queue.values())
                work.addAll(reports.values());
            queue.clear();
            flushing = false;
        }

        // The hooks of the app do not apply to its reports, see installMethod
        enterService();
        try {
            for (Bundle args : work)
                try {
                    Data.report(context, args);
                } catch (Throwable ex) {
                    log(ex);
                }
        } finally {
            leaveService();
        }
    }

    // The standard libraries, once per thread, because loading them takes much longer than running a script:
    // base, bit32, coroutine, io, math, os, package, string, table, luajava (and debug in debug builds)
    private static final ThreadLocal<Globals> libraries = new ThreadLocal<Globals>() {
        @Override
        protected Globals initialValue() {
            Globals globals = JsePlatform.standardGlobals();
            if (BuildConfig.DEBUG)
                globals.load(new DebugLib());
            return globals;
        }
    };

    // The globals of a hook on the current thread, with the functions the script defines
    private static Globals getGlobals(Context context, XHook hook, Prototype script, Map<String, String> settings) {
        LuaLocals globals = new LuaLocals(libraries.get());
        globals.rawset("log", new LuaLog(context.getPackageName(), context.getApplicationInfo().uid, hook.getId()));
        globals.rawset("hook", new LuaHook(context, hook.getId(), settings));
        new LuaClosure(script, globals).call();
        return globals;
    }

    // Scripts can define functions, but no other globals; the libraries are shared by the hooks of a thread
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
        // Dynamic hooks are installed on classes, so install each one only once,
        // also when objects of several subclasses inherit the method
        private static final Set<String> installed = new HashSet<>();

        private final Context context;
        private final String hookId;
        private final Map<String, String> settings;

        LuaHook(Context context, String hookId, Map<String, String> settings) {
            this.context = context;
            this.hookId = hookId;
            this.settings = settings;
        }

        @Override
        public Varargs invoke(final Varargs args) {
            Class<?> cls = args.arg(1).checkuserdata().getClass();
            String m = args.arg(2).checkjstring();
            final LuaValue fun = args.arg(3).checkfunction();

            String key = hookId + "/" + cls.getName() + "." + m;
            List<Method> methods = new ArrayList<>();
            synchronized (installed) {
                if (!installed.add(key))
                    return LuaValue.NIL;

                // Not an error, which would stop the script, which can hook other methods next
                List<Method> resolved = XResolver.resolveMethods(cls, m);
                if (resolved.isEmpty())
                    Log.w(TAG, "Dynamic hook " + key + " not found");
                for (Method method : resolved)
                    if (installed.add(hookId + "/" + method))
                        methods.add(method);
            }
            if (methods.isEmpty())
                return LuaValue.NIL;

            Log.i(TAG, "Dynamic hook " + cls.getName() + "." + m + " methods=" + methods.size());
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
                            Log.i(TAG, "Dynamic invoke " + call.method);
                        List<LuaValue> values = new ArrayList<>();
                        values.add(LuaValue.valueOf(when));
                        values.add(CoerceJavaToLua.coerce(new XParam(context, call, settings)));
                        values.addAll(xargs);
                        // The function is of the Lua runtime of the thread which installed the hook,
                        // which is not thread safe, see installMethod
                        synchronized (fun) {
                            fun.invoke(values.toArray(new LuaValue[0]));
                        }
                    } catch (Throwable ex) {
                        Log.e(TAG, "Dynamic hook " + hookId + " " + call.method + ": " + Log.getStackTraceString(ex));
                    }
                }
            };

            // The hooks which could not be installed are tried again on the next call
            for (int i = 0; i < methods.size(); i++)
                try {
                    backend.hook(methods.get(i), callback);
                } catch (Throwable ex) {
                    synchronized (installed) {
                        installed.remove(key);
                        for (int j = i; j < methods.size(); j++)
                            installed.remove(hookId + "/" + methods.get(j));
                    }
                    throw new LuaError(ex);
                }

            return LuaValue.NIL;
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
            Log.i(TAG, "Log " + packageName + ":" + uid + " " + hook + " " +
                    arg.toString() + " (" + arg.typename() + ")");
            return LuaValue.NIL;
        }
    }
}
