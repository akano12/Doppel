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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

// Package changes of all users, registered in system server (see XCore.hookAndroid)
public class ReceiverPackage extends BroadcastReceiver {
    private static final String TAG = "XLua.Receiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String packageName = intent.getData().getSchemeSpecificPart();
            int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            Log.i(TAG, "Received " + intent + " uid=" + uid);

            int userid = Util.getUserId(uid);
            Context ctx = Util.createContextForUser(context, userid);

            Bundle args = new Bundle();
            args.putString("packageName", packageName);
            args.putInt("uid", uid);

            if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                if (!replacing && !packageName.startsWith(BuildConfig.APPLICATION_ID)) {
                    // Initialize app
                    call(context, "clearApp", args);
                    if (Data.getSettingBoolean(context, userid, "global", "restrict_new_apps"))
                        call(context, "initApp", args);

                    if (Data.getSettingBoolean(context, userid, "global", "notify_new_apps"))
                        XNotify.notifyNewApp(ctx, userid, uid, packageName);
                }
            } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) {
                if (BuildConfig.APPLICATION_ID.equals(packageName)) {
                    Bundle data = new Bundle();
                    data.putInt("user", userid);
                    call(context, "clearData", data);
                } else {
                    args.putBoolean("settings", true);
                    call(context, "clearApp", args);
                    XNotify.cancelNewApp(ctx, userid, uid);
                }
            }
        } catch (Throwable ex) {
            XCore.log(ex);
        }
    }

    private static void call(Context context, String method, Bundle args) {
        context.getContentResolver().call(XProvider.getURI(), "xlua", method, args);
    }
}
