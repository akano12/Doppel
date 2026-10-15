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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;
import android.util.TypedValue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.security.MessageDigest;


class Util {
    private final static String TAG = "XLua.Util";

    private static final int PER_USER_RANGE = 100000;
    private static final int FIRST_ISOLATED_UID = 99000;
    private static final int LAST_ISOLATED_UID = 99999;

    static void setPermissions(String path, int mode, int uid, int gid) {
        try {
            Class<?> fileUtils = Class.forName("android.os.FileUtils");
            Method setPermissions = fileUtils
                    .getMethod("setPermissions", String.class, int.class, int.class, int.class);
            setPermissions.invoke(null, path, mode, uid, gid);
        } catch (Throwable ex) {
            Log.e(TAG, Log.getStackTraceString(ex));
        }
    }

    // Same arithmetic as the hidden UserHandle.getAppId/getUserId/getUid,
    // which avoids hidden API access from app processes

    static int getAppId(int uid) {
        return uid % PER_USER_RANGE;
    }

    static int getUserId(int uid) {
        return uid / PER_USER_RANGE;
    }

    static int getUserUid(int userid, int appid) {
        return userid * PER_USER_RANGE + (appid % PER_USER_RANGE);
    }

    static boolean isIsolated(int uid) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            return Process.isIsolated(); // Includes app zygote processes
        int appid = getAppId(uid);
        return (appid >= FIRST_ISOLATED_UID && appid <= LAST_ISOLATED_UID);
    }

    static UserHandle getUserHandle(int userid) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            return UserHandle.getUserHandleForUid(getUserUid(userid, 0));
        try {
            // public UserHandle(int h)
            Constructor<UserHandle> ctor = UserHandle.class.getConstructor(int.class);
            return ctor.newInstance(userid);
        } catch (Throwable ex) {
            Log.e(TAG, Log.getStackTraceString(ex));
            return Process.myUserHandle();
        }
    }

    @SuppressWarnings("deprecation")
    static long getVersionCode(PackageInfo pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            return pi.getLongVersionCode();
        else
            return pi.versionCode;
    }

    @SuppressWarnings("deprecation")
    static byte[] getSha1Fingerprint(Context context, String packageName) throws Throwable {
        PackageManager pm = context.getPackageManager();
        Signature signature;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo si = packageInfo.signingInfo;
            Signature[] signatures = (si.hasMultipleSigners()
                    ? si.getApkContentsSigners() : si.getSigningCertificateHistory());
            signature = signatures[0];
        } else {
            PackageInfo packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
            signature = packageInfo.signatures[0];
        }
        MessageDigest digest = MessageDigest.getInstance("SHA1");
        return digest.digest(signature.toByteArray());
    }

    static Context createContextForUser(Context context, int userid) throws Throwable {
        // public UserHandle(int h)
        Class<?> clsUH = Class.forName("android.os.UserHandle");
        Constructor<?> cUH = clsUH.getDeclaredConstructor(int.class);
        UserHandle uh = (UserHandle) cUH.newInstance(userid);

        // public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
        Method c = context.getClass().getDeclaredMethod("createPackageContextAsUser", String.class, int.class, UserHandle.class);
        return (Context) c.invoke(context, "android", 0, uh);
    }

    @SuppressWarnings("deprecation")
    static Notification.Builder getNotificationBuilder(Context context, boolean high) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            builder = new Notification.Builder(context, XProvider.cChannelName);
        else {
            builder = new Notification.Builder(context);
            builder.setPriority(high ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT);
        }
        builder.setCategory(Notification.CATEGORY_STATUS);
        builder.setVisibility(Notification.VISIBILITY_SECRET);
        builder.setAutoCancel(true);
        return builder;
    }

    // Intent to open the main activity searching for a package
    static PendingIntent getSearchIntent(Context context, int requestCode, String packageName) {
        Intent main = context.getPackageManager().getLaunchIntentForPackage(BuildConfig.APPLICATION_ID);
        if (main == null)
            return null;
        main.putExtra(ActivityMain.EXTRA_SEARCH_PACKAGE, packageName);
        return PendingIntent.getActivity(context, requestCode, main,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void notifyAsUser(Context context, String tag, int id, Notification notification, int userid) throws Throwable {
        NotificationManager nm = context.getSystemService(NotificationManager.class);

        // Create notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PackageManager pm = context.getPackageManager();
            Resources resources = pm.getResourcesForApplication(BuildConfig.APPLICATION_ID);
            NotificationChannel channel = new NotificationChannel(
                    XProvider.cChannelName, resources.getString(R.string.channel_privacy), NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
            nm.createNotificationChannel(channel);
        }

        // public void notifyAsUser(String tag, int id, Notification notification, UserHandle user)
        Method mNotifyAsUser = nm.getClass().getDeclaredMethod(
                "notifyAsUser", String.class, int.class, Notification.class, UserHandle.class);
        mNotifyAsUser.invoke(nm, tag, id, notification, Util.getUserHandle(userid));
        Log.i(TAG, "Notified " + tag + ":" + id + " as " + userid);
    }

    static void cancelAsUser(Context context, String tag, int id, int userid) throws Throwable {
        NotificationManager nm = context.getSystemService(NotificationManager.class);

        // public void cancelAsUser(String tag, int id, UserHandle user)
        Method mCancelAsUser = nm.getClass().getDeclaredMethod(
                "cancelAsUser", String.class, int.class, UserHandle.class);
        mCancelAsUser.invoke(nm, tag, id, Util.getUserHandle(userid));
        Log.i(TAG, "Cancelled " + tag + ":" + id + " as " + userid);
    }

    public static int resolveColor(Context context, int attr) {
        TypedValue typedValue = new TypedValue();
        Resources.Theme theme = context.getTheme();
        theme.resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }
}
