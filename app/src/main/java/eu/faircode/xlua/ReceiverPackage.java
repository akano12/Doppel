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

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Package changes of all users and removed users, registered in system server (see XCore.hookAndroid)
public class ReceiverPackage extends BroadcastReceiver {
    private static final String TAG = "XLua.Receiver";

    // Hidden Intent.ACTION_USER_REMOVED and Intent.EXTRA_USER_HANDLE, sent to system server
    static final String ACTION_USER_REMOVED = "android.intent.action.USER_REMOVED";
    private static final String EXTRA_USER_HANDLE = "android.intent.extra.user_handle";

    // One thread, so the changes of a package are handled in order, like a removal before a new install
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "XLua.Receiver"));

    // The service writes to its database, which is not done on the main thread of system server
    @Override
    public void onReceive(final Context context, final Intent intent) {
        final PendingResult result = goAsync();
        executor.execute(() -> {
            // Without the hooks of system server, see XCore.enterService
            XCore.enterService();
            try {
                handle(context, intent);
            } finally {
                XCore.leaveService();
                result.finish();
            }
        });
    }

    private static void handle(Context context, Intent intent) {
        try {
            // The restrictions and settings of a removed user would else apply to a new user with the same id,
            // whose apps have the same uids; the system user cannot be removed
            if (ACTION_USER_REMOVED.equals(intent.getAction())) {
                int userid = intent.getIntExtra(EXTRA_USER_HANDLE, -1);
                Log.i(TAG, "Received " + intent + " user=" + userid);
                if (userid > 0) {
                    Data.clearData(context, userid, false);
                    XNotify.forgetUser(userid);
                }
                return;
            }

            String packageName = intent.getData().getSchemeSpecificPart();
            int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            Log.i(TAG, "Received " + intent + " uid=" + uid);

            int userid = Util.getUserId(uid);
            Context ctx = Util.createContextForUser(context, userid);

            if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                if (!replacing && !BuildConfig.APPLICATION_ID.equals(packageName) &&
                        added(context, packageName, uid) &&
                        Data.getSettingBoolean(context, userid, "global", "notify_new_apps"))
                    XNotify.notifyNewApp(ctx, userid, uid, packageName);
            } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) {
                if (BuildConfig.APPLICATION_ID.equals(packageName))
                    // Removed by the last user that had the app: the data of all users, like the custom definitions
                    Data.clearData(context, userid, !installedForAnyUser(context));
                else {
                    Data.clearApp(context, packageName, uid, true);
                    XNotify.cancelApp(ctx, userid, uid);
                }
            }
        } catch (Throwable ex) {
            XCore.log(ex);
        }
    }

    // Initialize a new app; false for an app which was removed with its data kept, like an archived app on
    // Android 15, which is added again with its restrictions, since a removal deletes them, see AssignTest;
    // all rows count, also of hooks which did not load or are not available, which clearApp would delete
    static boolean added(Context context, String packageName, int uid) {
        if (Data.hasAssignments(context, packageName, uid)) {
            Log.i(TAG, "Kept restrictions pkg=" + packageName + " uid=" + uid);
            return false;
        }
        Data.clearApp(context, packageName, uid, false);
        int userid = Util.getUserId(uid);
        if (Data.getSettingBoolean(context, userid, "global", "restrict_new_apps")) {
            Data.initApp(context, packageName, uid);
            // Opened right after it was installed, before this broadcast came, the app runs already without hooks,
            // which it asked for before they were assigned, so it is stopped, like when the user restricts an app;
            // an app which is not running is not, since a device without Doppel would not stop it. A failed stop
            // does not keep the notification of the new app.
            // shortcut: what the first run did stays, like identifiers it stored, and a process which asked for its
            // hooks after they were written, or which is not in the LSPosed scope, is stopped too; record the queries
            // the service refused to tell these apart when it matters
            try {
                if (runs(context.getSystemService(ActivityManager.class).getRunningAppProcesses(), packageName, uid) &&
                        Data.hasAssignments(context, packageName, uid)) {
                    ApplicationInfo ai = Util.getApplicationInfo(
                            Util.createContextForUser(context, userid).getPackageManager(), packageName, 0);
                    if (stopsAutomatically(ai.flags, Data.getSetting(context, userid, packageName, "forcestop"))) {
                        Log.i(TAG, "Stopping new app pkg=" + packageName + " uid=" + uid);
                        XApp app = new XApp();
                        app.packageName = packageName;
                        app.uid = uid;
                        Data.forceStop(context, app);
                    }
                }
            } catch (Throwable ex) {
                XCore.log(ex);
            }
        }
        return true;
    }

    // Whether a process of the app runs: a list which is null has none, and a process of another package with the same
    // uid is not of the app, which Android would not stop with it
    static boolean runs(List<ActivityManager.RunningAppProcessInfo> processes, String packageName, int uid) {
        if (processes != null)
            for (ActivityManager.RunningAppProcessInfo process : processes)
                if (process.uid == uid && process.pkgList != null && Arrays.asList(process.pkgList).contains(packageName))
                    return true;
        return false;
    }

    // Like when the user restricts an app: never a persistent app and, unless the user set so, no system app,
    // see XProvider.getApps and XApp.stopsAutomatically
    static boolean stopsAutomatically(int flags, String forceStop) {
        boolean persistent = ((flags & ApplicationInfo.FLAG_PERSISTENT) != 0);
        boolean system = ((flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
        return !persistent && (forceStop == null ? !system : Boolean.parseBoolean(forceStop));
    }

    // Hidden PackageManager.MATCH_ANY_USER (Android 8 and later): installed for any user, which system server may ask;
    // also see XProvider.loadHooks and XCore
    static final int MATCH_ANY_USER = 0x00400000;

    private static boolean installedForAnyUser(Context context) {
        try {
            Util.getPackageInfo(context.getPackageManager(), BuildConfig.APPLICATION_ID, MATCH_ANY_USER);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }
}
