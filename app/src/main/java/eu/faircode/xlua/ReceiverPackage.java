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
                    Data.clearData(context, userid);
                else {
                    Data.clearApp(context, packageName, uid, true);
                    XNotify.cancelNewApp(ctx, userid, uid);
                }
            }
        } catch (Throwable ex) {
            XCore.log(ex);
        }
    }
}
