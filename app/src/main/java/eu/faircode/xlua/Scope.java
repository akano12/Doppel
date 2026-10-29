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

import android.os.Build;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

// Adds an app to Doppel's own LSPosed scope from the manager, so the user does not open the framework.
// The framework delivers its service to this app through the provider the libxposed service library declares in the
// manifest; only a modern framework (the libxposed API, Android 8.1 and later) has it, so without one everything here
// is a no-op and the toggle is hidden (see ActivityApp). All calls but init are binder calls, not on the main thread.
class Scope {
    private static final String TAG = "XLua.Scope";

    // The framework service, set when the framework connects; more than one framework could connect, the first is kept
    private static final AtomicReference<XposedService> service = new AtomicReference<>();

    interface Callback {
        void done(boolean inScope, String error);
    }

    // Registered once at app start (ApplicationEx); a framework without the service never calls back
    static void init() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return;
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService s) {
                    service.compareAndSet(null, s);
                    Log.i(TAG, "Service bound: " + s.getFrameworkName());
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

    // Whether the scope can be managed from here (a modern framework is connected)
    static boolean available() {
        return service.get() != null;
    }

    // Whether the app is in Doppel's scope
    static boolean inScope(String packageName) {
        XposedService s = service.get();
        if (s == null)
            return false;
        try {
            return s.getScope().contains(packageName);
        } catch (Throwable ex) {
            Log.w(TAG, "getScope: " + ex);
            return false;
        }
    }

    // Ask the framework to add the app to the scope; the framework asks the user, then the callback reports the result
    static void add(String packageName, Callback callback) {
        XposedService s = service.get();
        if (s == null) {
            callback.done(false, "no service");
            return;
        }
        try {
            s.requestScope(Collections.singletonList(packageName), new XposedService.OnScopeEventListener() {
                @Override
                public void onScopeRequestApproved(List<String> approved) {
                    callback.done(approved.contains(packageName), null);
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
    }
}
