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

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

// Adds an app to Doppel's own LSPosed scope from the manager, so the user does not open the framework.
// The framework delivers its service to this app through ScopeProvider, which replaces the provider of the libxposed
// service library in the manifest; only a modern framework (the libxposed API) has it, so
// without one everything here is a no-op and the toggle is hidden (see ActivityApp). All calls but init are binder
// calls, not on the main thread.
// The scope is also told to the service in system server, which answers the apps with assignments only when they are in it
// (see XProvider.answers): when the framework connects, which is when the manager starts, after each change made here,
// and when a screen asks whether an app is in it, which catches a change made in the framework in the meantime. A scope
// changed in the framework only, with no screen of Doppel asking, is not known to the service until the manager starts
// again; an app added that way runs without hooks until then. A framework without the service, which has no scope to read,
// never tells it, so the service answers all apps with assignments, as before.
class Scope {
    private static final String TAG = "XLua.Scope";

    // The framework service, set when the framework connects; more than one framework could connect, the first is kept
    private static final AtomicReference<XposedService> service = new AtomicReference<>();

    // For the calls to the service in system server; the framework serves its delivery of the service in a call of its own,
    // in which the calls below would block, so they run on a thread of ours
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XLua.Scope");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Application application = null;
    private static volatile Set<String> told = null; // The scope last told to the service, see inScope
    // Run on the main thread when a framework connects: the Apps tab loads again, as it could not read the scope before
    private static volatile Runnable onBound = null;

    interface Callback {
        void done(boolean inScope, String error);
    }

    // Registered once at app start (ApplicationEx); a framework without the service never calls back
    static void init(Application app) {
        application = app;
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService s) {
                    service.compareAndSet(null, s);
                    executor.execute(() -> {
                        try {
                            Log.i(TAG, "Service bound: " + s.getFrameworkName());
                        } catch (Throwable ex) {
                            Log.w(TAG, "getFrameworkName: " + ex);
                        }
                        tell(s);
                        Runnable r = onBound;
                        if (r != null)
                            new Handler(Looper.getMainLooper()).post(r);
                    });
                }

                @Override
                public void onServiceDied(XposedService s) {
                    service.compareAndSet(s, null);
                }
            });
        } catch (Throwable ex) {
            Log.w(TAG, "No service: " + ex);
        }
    }

    static void setOnBound(Runnable r) {
        onBound = r;
    }

    // Whether the scope can be managed from here (a modern framework is connected)
    static boolean available() {
        return service.get() != null;
    }

    // Whether the app is in Doppel's scope; the service is told when it changed
    static boolean inScope(String packageName) {
        XposedService s = service.get();
        if (s == null)
            return false;
        try {
            List<String> scope = s.getScope();
            if (!new TreeSet<>(scope).equals(told))
                tell(scope);
            return scope.contains(packageName);
        } catch (Throwable ex) {
            Log.w(TAG, "getScope: " + ex);
            return false;
        }
    }

    // The packages in the scope, or null when it cannot be read (no modern framework); one call for many apps
    static Set<String> getScope() {
        XposedService s = service.get();
        if (s == null)
            return null;
        try {
            List<String> scope = s.getScope();
            if (!new TreeSet<>(scope).equals(told))
                tell(scope);
            return new TreeSet<>(scope);
        } catch (Throwable ex) {
            Log.w(TAG, "getScope: " + ex);
            return null;
        }
    }

    // Whether the app is outside the scope read by getScope (null: not known); not for system server or Doppel itself,
    // whose scope the toggle does not offer either
    static boolean isMissing(XApp app, Set<String> scope) {
        return (scope != null && !app.isSystemServer() && !BuildConfig.APPLICATION_ID.equals(app.packageName) &&
                !scope.contains(app.packageName));
    }

    // Tell the service the scope of the framework, after a change or when the framework connects; blocking
    private static void tell(XposedService s) {
        try {
            tell(s.getScope());
        } catch (Throwable ex) {
            Log.w(TAG, "getScope: " + ex);
        }
    }

    private static void tell(Collection<String> scope) {
        Application app = application;
        if (app == null)
            return;
        try {
            Set<String> sorted = new TreeSet<>(scope);
            Data.putScope(app, sorted);
            told = sorted;
            Log.i(TAG, "Scope told packages=" + sorted.size());
        } catch (Throwable ex) {
            // A service of an older version, which a restart of the device replaces, does not know the call
            Log.w(TAG, "Scope not told: " + ex);
        }
    }

    // Ask the framework to add the app to the scope; the framework asks the user, then the callback reports the result
    static void add(String packageName, Callback callback) {
        add(Collections.singletonList(packageName), callback);
    }

    // Also for several apps at once; inScope tells that all of them were added
    static void add(List<String> packageNames, Callback callback) {
        XposedService s = service.get();
        if (s == null) {
            callback.done(false, "no service");
            return;
        }
        try {
            s.requestScope(packageNames, new XposedService.OnScopeEventListener() {
                @Override
                public void onScopeRequestApproved(List<String> approved) {
                    // Before the caller stops the app, which then starts in the scope
                    tell(s);
                    callback.done(approved.containsAll(packageNames), null);
                }

                @Override
                public void onScopeRequestFailed(String message) {
                    callback.done(false, message);
                }
            });
        } catch (Throwable ex) {
            callback.done(false, ex.getMessage());
        }
    }

    // Remove the app from the scope
    static void remove(String packageName) {
        XposedService s = service.get();
        if (s == null)
            return;
        try {
            s.removeScope(Collections.singletonList(packageName));
        } catch (Throwable ex) {
            Log.w(TAG, "removeScope: " + ex);
        }
        // Also when removing failed, which the scope tells
        tell(s);
    }
}
