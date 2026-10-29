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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

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
                if (userid > 0)
                    Data.clearData(context, userid, false);
                return;
            }

            String packageName = intent.getData().getSchemeSpecificPart();
            int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            Log.i(TAG, "Received " + intent + " uid=" + uid);

            int userid = Util.getUserId(uid);
            Context ctx = Util.createContextForUser(context, userid);

            if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                if (!replacing && !BuildConfig.APPLICATION_ID.equals(packageName)) {
                    // Initialize app
                    Data.clearApp(context, packageName, uid, false);
                    if (Data.getSettingBoolean(context, userid, "global", "restrict_new_apps"))
                        Data.initApp(context, packageName, uid);

                    if (Data.getSettingBoolean(context, userid, "global", "notify_new_apps"))
                        XNotify.notifyNewApp(ctx, userid, uid, packageName);
                }
            } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) {
                if (BuildConfig.APPLICATION_ID.equals(packageName))
                    // Removed by the owner: the data of all users, unless other users still have the app
                    Data.clearData(context, userid, userid == 0 && !installedForAnyUser(context));
                else {
                    Data.clearApp(context, packageName, uid, true);
                    XNotify.cancelApp(ctx, userid, uid);
                }
            }
        } catch (Throwable ex) {
            XCore.log(ex);
        }
    }

    // Hidden PackageManager.MATCH_ANY_USER: installed for any user, which system server may ask
    private static final int MATCH_ANY_USER = 0x00400000;

    private static boolean installedForAnyUser(Context context) {
        try {
            context.getPackageManager().getPackageInfo(BuildConfig.APPLICATION_ID, MATCH_ANY_USER);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }
}
