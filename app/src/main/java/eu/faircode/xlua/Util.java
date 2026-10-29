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

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class Util {
    private static final String TAG = "XLua.Util";

    private static final int PER_USER_RANGE = 100000;
    private static final int FIRST_ISOLATED_UID = 99000;
    private static final int LAST_ISOLATED_UID = 99999;

    static final String COLLECTION_DEFAULT = "Privacy";

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
        return UserHandle.getUserHandleForUid(getUserUid(userid, 0));
    }

    static Context createContextForUser(Context context, int userid) throws Throwable {
        // public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
        Method c = context.getClass().getDeclaredMethod(
                "createPackageContextAsUser", String.class, int.class, UserHandle.class);
        return (Context) c.invoke(context, "android", 0, getUserHandle(userid));
    }

    // All text of a stream, which is not closed
    static String readText(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = is.read(buffer)) > 0)
            bos.write(buffer, 0, length);
        return bos.toString(StandardCharsets.UTF_8.name());
    }

    // All text of a file chosen by the user, without the byte order mark which some editors write
    static String readText(Context context, Uri uri) throws IOException {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null)
                throw new IOException("Cannot open " + uri);
            String text = readText(is);
            return (text.startsWith("\uFEFF") ? text.substring(1) : text);
        }
    }

    // JSON into a file chosen by the user; Android escapes slashes, which is valid, but hard to read in scripts.
    // Truncated, because the mode "w" of some providers keeps the end of a longer file which is replaced.
    static void writeJson(Context context, Uri uri, String json) throws IOException {
        try (OutputStream os = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (os == null)
                throw new IOException("Cannot open " + uri);
            os.write(json.replace("\\/", "/").getBytes(StandardCharsets.UTF_8));
        }
    }

    // Enabled hook collections of the comma separated setting, by default the privacy collection
    static List<String> parseCollection(String value) {
        if (value == null)
            return new ArrayList<>(Collections.singletonList(COLLECTION_DEFAULT));
        return split(value);
    }

    // The items of a list separated by commas, without the spaces around them and without empty items
    static List<String> split(String value) {
        List<String> result = new ArrayList<>();
        for (String item : value.split(","))
            if (!item.trim().isEmpty())
                result.add(item.trim());
        return result;
    }

    // The int flags of the package manager are deprecated since Android 13, the typed flags are the same
    @SuppressWarnings("deprecation")
    static PackageInfo getPackageInfo(PackageManager pm, String packageName, long flags)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags));
        return pm.getPackageInfo(packageName, (int) flags);
    }

    @SuppressWarnings("deprecation")
    static ApplicationInfo getApplicationInfo(PackageManager pm, String packageName, long flags)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags));
        return pm.getApplicationInfo(packageName, (int) flags);
    }

    @SuppressWarnings("deprecation")
    static List<PackageInfo> getInstalledPackages(PackageManager pm, long flags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags));
        return pm.getInstalledPackages((int) flags);
    }
}
