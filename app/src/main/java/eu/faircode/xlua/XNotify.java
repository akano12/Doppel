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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Method;

// Notifications of the service in system server, shown to the user of an app.
// The context is a context of the user, with the resources of system server.
final class XNotify {
    private static final String TAG = "XLua.Notify";

    private static final String CHANNEL = "xlua";
    private static final String TAG_NEW_APP = "xlua_new_app";

    private XNotify() {
    }

    // An app used a restricted function
    static void notifyUsage(Context context, int userid, int uid, String packageName, String group, String hookid)
            throws Throwable {
        Resources resources = getResources(context);
        Notification.Builder builder = getBuilder(context, false);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info);
        builder.setContentTitle(resources.getString(R.string.msg_usage, Groups.getTitle(resources, group)));
        builder.setContentText(getLabel(context, packageName));
        if (BuildConfig.DEBUG)
            builder.setSubText(hookid);
        builder.setContentIntent(getAppIntent(context, uid, packageName));
        notifyAsUser(context, "xlua_use_" + group, uid, builder.build(), userid);
    }

    // A hook of an app failed
    static void notifyException(Context context, int userid, int uid, String packageName, String hookid) throws Throwable {
        Resources resources = getResources(context);
        Notification.Builder builder = getBuilder(context, true);
        builder.setSmallIcon(android.R.drawable.ic_dialog_alert);
        builder.setContentTitle(resources.getString(R.string.msg_exception, hookid));
        builder.setContentText(getLabel(context, packageName));
        builder.setContentIntent(getAppIntent(context, uid, packageName));
        notifyAsUser(context, "xlua_exception", uid, builder.build(), userid);
    }

    // An app was installed
    static void notifyNewApp(Context context, int userid, int uid, String packageName) throws Throwable {
        Notification.Builder builder = getBuilder(context, true);
        builder.setSmallIcon(android.R.drawable.ic_dialog_alert);
        builder.setContentTitle(getResources(context).getString(R.string.msg_review_settings));
        builder.setContentText(getLabel(context, packageName));
        builder.setContentIntent(getAppIntent(context, uid, packageName));
        notifyAsUser(context, TAG_NEW_APP, uid, builder.build(), userid);
    }

    static void cancelNewApp(Context context, int userid, int uid) throws Throwable {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        // public void cancelAsUser(String tag, int id, UserHandle user)
        Method mCancelAsUser = nm.getClass().getDeclaredMethod(
                "cancelAsUser", String.class, int.class, UserHandle.class);
        mCancelAsUser.invoke(nm, TAG_NEW_APP, uid, Util.getUserHandle(userid));
        Log.i(TAG, "Cancelled " + TAG_NEW_APP + ":" + uid + " as " + userid);
    }

    // Resources of the module, not of system server
    private static Resources getResources(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getResourcesForApplication(BuildConfig.APPLICATION_ID);
    }

    private static CharSequence getLabel(Context context, String packageName) throws PackageManager.NameNotFoundException {
        PackageManager pm = context.getPackageManager();
        return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
    }

    @SuppressWarnings("deprecation") // Builder without channel and priority before Android 8
    private static Notification.Builder getBuilder(Context context, boolean high) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            builder = new Notification.Builder(context, CHANNEL);
        else {
            builder = new Notification.Builder(context);
            builder.setPriority(high ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT);
        }
        builder.setCategory(Notification.CATEGORY_STATUS);
        builder.setVisibility(Notification.VISIBILITY_SECRET);
        builder.setAutoCancel(true);
        return builder;
    }

    // Opens the restrictions of the app
    private static PendingIntent getAppIntent(Context context, int requestCode, String packageName) {
        Intent main = context.getPackageManager().getLaunchIntentForPackage(BuildConfig.APPLICATION_ID);
        if (main == null)
            return null;
        main.putExtra(ActivityMain.EXTRA_PACKAGE, packageName);
        return PendingIntent.getActivity(context, requestCode, main,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void notifyAsUser(Context context, String tag, int id, Notification notification, int userid) throws Throwable {
        NotificationManager nm = context.getSystemService(NotificationManager.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, getResources(context).getString(R.string.channel_privacy), NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
            nm.createNotificationChannel(channel);
        }

        // public void notifyAsUser(String tag, int id, Notification notification, UserHandle user)
        Method mNotifyAsUser = nm.getClass().getDeclaredMethod(
                "notifyAsUser", String.class, int.class, Notification.class, UserHandle.class);
        mNotifyAsUser.invoke(nm, tag, id, notification, Util.getUserHandle(userid));
        Log.i(TAG, "Notified " + tag + ":" + id + " as " + userid);
    }
}
