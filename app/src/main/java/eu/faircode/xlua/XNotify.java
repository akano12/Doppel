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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

// Notifications of the service in system server, shown to the user of an app.
// The context is a context of the user, with the resources of system server.
final class XNotify {
    private static final String TAG = "XLua.Notify";

    // New apps and errors, which pop up; restrictions, which do not. The importance of a channel cannot be changed,
    // so the former channel of all notifications is the channel which pops up.
    private static final String CHANNEL_ALERTS = "xlua";
    private static final String CHANNEL_USAGE = "xlua_usage";

    // The tags of all notifications start with xlua_, the id is the uid of the app
    private static final String TAG_PREFIX = "xlua_";
    private static final String TAG_NEW_APP = TAG_PREFIX + "new_app";
    private static final String TAG_EXCEPTION = TAG_PREFIX + "exception";
    private static final String TAG_USAGE = TAG_PREFIX + "use_"; // + group

    private static final String APP_NAME = "Doppel"; // Like app_name, which is not translated

    // Resources of the module version which runs, see loadResources
    private static volatile Resources resources = null;

    private XNotify() {
    }

    // The code in system server keeps running after an update of the module until a reboot,
    // with the resource ids of its own version, which an update can change, so the resources are kept
    static void loadResources(Context context) throws PackageManager.NameNotFoundException {
        resources = context.getPackageManager().getResourcesForApplication(BuildConfig.APPLICATION_ID);
    }

    // An app used a restricted function; hooks which notify on each use update the notification without alerting
    static void notifyUsage(Context context, int userid, int uid, String packageName, String group, String hookid)
            throws Throwable {
        Resources resources = getResources(context);
        Notification.Builder builder = getBuilder(context, false);
        builder.setOnlyAlertOnce(true);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info);
        builder.setContentTitle(resources.getString(R.string.msg_usage, Groups.getTitle(resources, group)));
        builder.setContentText(getLabel(context, packageName));
        if (BuildConfig.DEBUG)
            builder.setSubText(hookid);
        builder.setContentIntent(getAppIntent(context, uid, packageName));
        notifyAsUser(context, TAG_USAGE + group, uid, builder.build(), userid);
    }

    // A hook of an app failed
    static void notifyException(Context context, int userid, int uid, String packageName, String hookid) throws Throwable {
        Resources resources = getResources(context);
        Notification.Builder builder = getBuilder(context, true);
        builder.setSmallIcon(android.R.drawable.ic_dialog_alert);
        builder.setContentTitle(resources.getString(R.string.msg_exception, hookid));
        builder.setContentText(getLabel(context, packageName));
        builder.setContentIntent(getAppIntent(context, uid, packageName));
        notifyAsUser(context, TAG_EXCEPTION, uid, builder.build(), userid);
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

    // An app was removed: its usage, error and new app notifications, which are notifications of system server
    static void cancelApp(Context context, int userid, int uid) throws Throwable {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        // public void cancelAsUser(String tag, int id, UserHandle user)
        Method mCancelAsUser = nm.getClass().getDeclaredMethod(
                "cancelAsUser", String.class, int.class, UserHandle.class);

        Set<String> tags = new HashSet<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P || userid == 0) {
            // The active notifications of system server of the user of the context, all tags of custom groups too
            for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                String tag = sbn.getTag();
                if (sbn.getId() == uid && tag != null && tag.startsWith(TAG_PREFIX))
                    tags.add(tag);
            }
        } else {
            // Before Android 9 these are the notifications of the user of system server, the owner,
            // so the tags of other users are of the groups of the definitions
            tags.add(TAG_NEW_APP);
            tags.add(TAG_EXCEPTION);
            for (String group : XProvider.getGroups())
                tags.add(TAG_USAGE + group);
        }

        for (String tag : tags)
            mCancelAsUser.invoke(nm, tag, uid, Util.getUserHandle(userid));
        Log.i(TAG, "Cancelled " + tags.size() + " tags of " + uid + " as " + userid);
    }

    // Resources of the module, not of system server
    private static Resources getResources(Context context) throws PackageManager.NameNotFoundException {
        Resources loaded = resources;
        return (loaded == null
                ? context.getPackageManager().getResourcesForApplication(BuildConfig.APPLICATION_ID)
                : loaded);
    }

    private static CharSequence getLabel(Context context, String packageName) throws PackageManager.NameNotFoundException {
        PackageManager pm = context.getPackageManager();
        return pm.getApplicationLabel(Util.getApplicationInfo(pm, packageName, 0));
    }

    private static Notification.Builder getBuilder(Context context, boolean high) {
        Notification.Builder builder = new Notification.Builder(context, high ? CHANNEL_ALERTS : CHANNEL_USAGE);
        builder.setCategory(Notification.CATEGORY_STATUS);
        builder.setVisibility(Notification.VISIBILITY_SECRET);
        builder.setAutoCancel(true);
        // The service posts as system server, which would show as Settings Storage; system server may name the app
        // (Notification.EXTRA_SUBSTITUTE_APP_NAME, a system API)
        Bundle extras = new Bundle();
        extras.putString("android.substName", APP_NAME);
        builder.addExtras(extras);
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

        Resources resources = getResources(context);
        NotificationChannel alerts = new NotificationChannel(CHANNEL_ALERTS,
                resources.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH);
        alerts.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
        nm.createNotificationChannel(alerts);

        NotificationChannel usage = new NotificationChannel(CHANNEL_USAGE,
                resources.getString(R.string.channel_usage), NotificationManager.IMPORTANCE_DEFAULT);
        usage.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
        nm.createNotificationChannel(usage);

        // public void notifyAsUser(String tag, int id, Notification notification, UserHandle user)
        Method mNotifyAsUser = nm.getClass().getDeclaredMethod(
                "notifyAsUser", String.class, int.class, Notification.class, UserHandle.class);
        mNotifyAsUser.invoke(nm, tag, id, notification, Util.getUserHandle(userid));
        Log.i(TAG, "Notified " + tag + ":" + id + " as " + userid);
    }
}
