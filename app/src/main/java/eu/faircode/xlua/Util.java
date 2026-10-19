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

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

class Util {
    private final static String TAG = "XLua.Util";

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

    // Enabled hook collections of the comma separated setting, by default the privacy collection
    static List<String> parseCollection(String value) {
        List<String> result = new ArrayList<>();
        if (value == null)
            result.add(COLLECTION_DEFAULT);
        else
            for (String name : value.split(","))
                if (!name.trim().isEmpty())
                    result.add(name.trim());
        return result;
    }
}
