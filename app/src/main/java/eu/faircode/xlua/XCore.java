/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2017-2019 Marcel Bokhorst (M66B)
 */

package eu.faircode.xlua;

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
import java.util.WeakHashMap;
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
                try {
                    log("Preparing system");
                    Context context = findContext(call.thisObject);
                    hookPackage("android", Process.myUid(), context);
                } catch (Throwable ex) {
                    log(ex);
                }
            }

            @Override
            void after(XCall call) {
                try {
                    log("System ready");
                    Context context = findContext(call.thisObject);

                    // Store current module version
                    PackageInfo pi = context.getPackageManager().getPackageInfo(BuildConfig.APPLICATION_ID, 0);
                    version = (int) PackageInfoCompat.getLongVersionCode(pi);
                    Log.i(TAG, "Module version " + version);

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
                            Context ctx = Util.createContextForUser(context, userid);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                ctx.registerReceiver(new ReceiverPackage(), ifPackageAdd, Context.RECEIVER_NOT_EXPORTED);
                            else
                                ctx.registerReceiver(new ReceiverPackage(), ifPackageAdd);
                        }
                    }
                } catch (Throwable ex) {
                    log(ex);
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

    // Settings provider in system server, which hosts the XPrivacyLua service
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
                    if (!"xlua".equals(method))
                        return;

                    // Other apps get no result, like without XPrivacyLua, so they cannot detect it this way
                    if ("getVersion".equals(arg)) {
                        Bundle result = null;
                        if (XProvider.hasPermission(getContext(call.thisObject))) {
                            result = new Bundle();
                            result.putInt("version", version);
                        }
                        call.setResult(result);
                        return;
                    }

                    try {
                        call.setResult(XProvider.call(getContext(call.thisObject), arg, extras));
                    } catch (IllegalArgumentException ex) {
                        Log.i(TAG, "Error: " + ex.getMessage());
                        call.setThrowable(ex);
                    } catch (SecurityException ex) {
                        // A refused app gets no result, like before
                        Log.w(TAG, "Call " + arg + ": " + ex.getMessage());
                        call.setResult(null);
                    } catch (Throwable ex) {
                        log(ex);
                        call.setResult(null);
                    }
                } catch (Throwable ex) {
                    log(ex);
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
                            projection[0] == null || !projection[0].startsWith("xlua."))
                        return;

                    try {
                        String method = projection[0].split("\\.")[1];
                        call.setResult(XProvider.query(getContext(call.thisObject), method, selection));
                    } catch (SecurityException ex) {
                        Log.w(TAG, "Query " + projection[0] + ": " + ex.getMessage());
                        call.setResult(null);
                    } catch (Throwable ex) {
                        log(ex);
                        call.setResult(null);
                    }
                } catch (Throwable ex) {
                    log(ex);
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
        List<XHook> hooks = Data.getAssignedHooks(context, packageName, uid);

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

        // A method of an object in a static field: field:method
        String methodName = hook.getMethodName();
        if (methodName != null) {
            String[] m = methodName.split(":");
            if (m.length > 1) {
                Object obj = cls.getField(m[0]).get(null);
                cls = obj.getClass();
            }
            methodName = m[m.length - 1];
        }

        String[] p = hook.getParameterTypes();
        Class<?>[] paramTypes = new Class<?>[p.length];
        for (int i = 0; i < p.length; i++)
            paramTypes[i] = XResolver.resolveClass(p[i], loader);

        Class<?> returnType = (hook.getReturnType() == null ? null : XResolver.resolveClass(hook.getReturnType(), loader));

        if (methodName != null && methodName.startsWith("#")) {
            if (paramTypes.length > 0)
                throw new NoSuchFieldException("Field with parameters");
            Field field = XResolver.resolveField(cls, methodName.substring(1), returnType);
            field.setAccessible(true);
            installField(context, hook, script, settings, field);
        } else {
            Member member = XResolver.resolveMember(cls, methodName, paramTypes);
            if (methodName != null && returnType != null) {
                Class<?> memberReturnType = ((Method) member).getReturnType();
                if (!memberReturnType.isAssignableFrom(returnType))
                    throw new Throwable("Invalid return type " + memberReturnType + " got " + returnType);
            }
            installMethod(context, hook, script, settings, member);
        }
    }

    // Static fields are changed once, by the after function
    private void installField(Context context, XHook hook, Prototype script, Map<String, String> settings, Field field) {
        try {
            Globals globals = getGlobals(context, hook, settings);
            new LuaClosure(script, globals).call();

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
        backend.hook(member, new XBackend.Callback() {
            private final WeakHashMap<Thread, Globals> threadGlobals = new WeakHashMap<>();

            @Override
            void before(XCall call) {
                execute(call, "before");
            }

            @Override
            void after(XCall call) {
                execute(call, "after");
            }

            private void execute(XCall call, String function) {
                try {
                    // Initialize the Lua runtime once per thread
                    Globals globals;
                    synchronized (threadGlobals) {
                        Thread thread = Thread.currentThread();
                        globals = threadGlobals.get(thread);
                        if (globals == null) {
                            globals = getGlobals(context, hook, settings);
                            new LuaClosure(script, globals).call(); // Define functions
                            threadGlobals.put(thread, globals);
                        }
                    }

                    LuaValue func = globals.get(function);
                    if (func.isnil())
                        return;

                    Varargs result = func.invoke(new LuaValue[]{
                            CoerceJavaToLua.coerce(hook),
                            CoerceJavaToLua.coerce(new XParam(context, call, settings))
                    });
                    reportUse(context, hook, function, result);
                } catch (Throwable ex) {
                    synchronized (threadGlobals) {
                        threadGlobals.remove(Thread.currentThread());
                    }
                    reportError(context, hook, function, ex,
                            "\nMethod:\n" + function + " " + member + "\n" + describe(call));
                }
            }
        });
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
            data.putString("old", result.isnil(2) ? null : result.checkjstring(2));
            data.putString("new", result.isnil(3) ? null : result.checkjstring(3));
        }
        report(context, hook.getId(), function, "use", data);
    }

    private void reportError(Context context, XHook hook, String function, Throwable ex, String details) {
        String message = "Exception:\n" + Log.getStackTraceString(ex) + "\n" +
                "\nPackage:\n" + context.getPackageName() + ":" + context.getApplicationInfo().uid + "\n" +
                details;
        Log.e(TAG, message);

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

        // Errors first, so a restriction reported after them is what the log shows
        List<Bundle> work = new ArrayList<>();
        synchronized (queue) {
            for (Map.Entry<String, Map<String, Bundle>> entry : queue.entrySet())
                if (entry.getKey().endsWith(":error"))
                    work.addAll(entry.getValue().values());
            for (Map.Entry<String, Map<String, Bundle>> entry : queue.entrySet())
                if (!entry.getKey().endsWith(":error"))
                    work.addAll(entry.getValue().values());
            queue.clear();
            flushing = false;
        }

        for (Bundle args : work)
            try {
                context.getContentResolver().call(XProvider.getURI(), "xlua", "report", args);
            } catch (Throwable ex) {
                log(ex);
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

    // The globals of a hook on the current thread
    private static Globals getGlobals(Context context, XHook hook, Map<String, String> settings) {
        LuaLocals globals = new LuaLocals(libraries.get());
        globals.rawset("log", new LuaLog(context.getPackageName(), context.getApplicationInfo().uid, hook.getId()));
        globals.rawset("hook", new LuaHook(context, hook.getId(), settings));
        return globals;
    }

    // Scripts can define functions, but no other globals; the libraries are shared by the hooks of a thread
    private static class LuaLocals extends Globals {
        LuaLocals(Globals libraries) {
            // Same runtime, so errors have a traceback in debug builds
            this.STDOUT = libraries.STDOUT;
            this.STDERR = libraries.STDERR;
            this.finder = libraries.finder;
            this.baselib = libraries.baselib;
            this.package_ = libraries.package_;
            this.debuglib = libraries.debuglib;
            this.loader = libraries.loader;
            this.compiler = libraries.compiler;
            this.undumper = libraries.undumper;
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

    // hook(object, method, function, args...): hook the methods with a name of the class of an object
    private static class LuaHook extends VarArgFunction {
        // Dynamic hooks are installed on classes, so install each one only once
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
            args.arg(3).checkfunction();

            synchronized (installed) {
                if (!installed.add(hookId + "/" + cls.getName() + "." + m))
                    return LuaValue.NIL;
            }
            Log.i(TAG, "Dynamic hook " + cls.getName() + "." + m);
            final LuaValue fun = args.arg(3);
            final List<LuaValue> xargs = new ArrayList<>();
            for (int i = 4; i <= args.narg(); i++)
                xargs.add(args.arg(i));

            try {
                backend.hookAll(cls, m, new XBackend.Callback() {
                    @Override
                    void before(XCall call) {
                        execute("before", call);
                    }

                    @Override
                    void after(XCall call) {
                        execute("after", call);
                    }

                    // Errors must not reach the app, which the modern API would let through
                    private void execute(String when, XCall call) {
                        try {
                            if (BuildConfig.DEBUG)
                                Log.i(TAG, "Dynamic invoke " + call.method);
                            List<LuaValue> values = new ArrayList<>();
                            values.add(LuaValue.valueOf(when));
                            values.add(CoerceJavaToLua.coerce(new XParam(context, call, settings)));
                            values.addAll(xargs);
                            fun.invoke(values.toArray(new LuaValue[0]));
                        } catch (Throwable ex) {
                            Log.e(TAG, "Dynamic hook " + hookId + " " + call.method + ": " + Log.getStackTraceString(ex));
                        }
                    }
                });
            } catch (Throwable ex) {
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
