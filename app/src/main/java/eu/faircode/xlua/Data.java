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
 */

package eu.faircode.xlua;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Client of the service, used by the screens of the app and by the hook engine in app processes.
// Calls block, so call them from a background thread.
final class Data {
    private static final String TAG = "XLua.Data";

    private Data() {
    }

    // The service runs a module of the same version as this app
    static boolean isAvailable(Context context) {
        return isAvailable(getVersion(context));
    }

    static boolean isAvailable(int version) {
        return (version == BuildConfig.VERSION_CODE);
    }

    // Version of the module of the service, -1 when the module is not running
    static int getVersion(Context context) {
        try {
            Bundle result = call(context, "getVersion", new Bundle());
            return (result == null ? -1 : result.getInt("version", -1));
        } catch (Throwable ex) {
            Log.e(TAG, Log.getStackTraceString(ex));
            return -1;
        }
    }

    private static Bundle call(Context context, String method, Bundle args) {
        return context.getContentResolver().call(XProvider.getURI(), "xlua", method, args);
    }

    private static Cursor query(Context context, String method, String[] selection) {
        return context.getContentResolver()
                .query(XProvider.getURI(), new String[]{"xlua." + method}, null, selection, null);
    }

    private static <T> List<T> query(Context context, String method, String[] selection, Parcelable.Creator<T> creator) {
        List<T> result = new ArrayList<>();
        try (Cursor cursor = query(context, method, selection)) {
            while (cursor != null && cursor.moveToNext())
                result.add(unmarshall(cursor.getBlob(0), creator));
        }
        return result;
    }

    static <T> T unmarshall(byte[] data, Parcelable.Creator<T> creator) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            return creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    // Hook definitions

    // Hook definitions available for the enabled collections, without scripts
    static List<XHook> getHooks(Context context) {
        return query(context, "getHooks2", new String[]{"false", "false"}, XHook.CREATOR);
    }

    // All hook definitions with their scripts, including disabled hooks and hooks for other Android versions
    static List<XHook> getAllHooks(Context context) {
        return query(context, "getHooks2", new String[]{"true", "true"}, XHook.CREATOR);
    }

    // Hooks assigned to an app, with their scripts
    static List<XHook> getAssignedHooks(Context context, String packageName, int uid) {
        return query(context, "getAssignedHooks2",
                new String[]{packageName, Integer.toString(uid)}, XHook.CREATOR);
    }

    // Ids of the definitions which are part of the app
    static Set<String> getBuiltinIds(Context context) throws Exception {
        Set<String> ids = new HashSet<>();
        for (XHook builtin : XHook.readHooks(context.getApplicationInfo().publicSourceDir))
            ids.add(builtin.getId());
        return ids;
    }

    // A null definition deletes a custom hook, which restores the built-in hook with the same id
    static void putHook(Context context, String id, String definition) {
        Bundle args = new Bundle();
        args.putString("id", id);
        args.putString("definition", definition);
        call(context, "putHook", args);
    }

    // Definition as shown to and edited by users
    static JSONObject toEditable(XHook hook) throws JSONException {
        JSONObject jhook = hook.toJSONObject();
        jhook.remove("builtin");
        jhook.remove("resolvedClassName");
        return jhook;
    }

    // Store a definition as custom definition, which replaces a built-in definition with the same id
    static XHook putCustom(Context context, JSONObject jhook) throws JSONException {
        jhook.put("builtin", false);
        jhook.remove("resolvedClassName");
        XHook hook = XHook.fromJSONObject(jhook);
        hook.validate();
        putHook(context, hook.getId(), hook.toJSON());
        return hook;
    }

    static List<String> getGroupNames(List<XHook> hooks) {
        List<String> groups = new ArrayList<>();
        for (XHook hook : hooks)
            if (!groups.contains(hook.getGroup()))
                groups.add(hook.getGroup());
        Collections.sort(groups);
        return groups;
    }

    // Apps and restrictions

    // Installed apps with their assigned hooks
    static List<XApp> getApps(Context context) {
        return query(context, "getApps2", null, XApp.CREATOR);
    }

    static XApp getApp(Context context, String packageName, int uid) {
        for (XApp app : query(context, "getApps2", new String[]{packageName}, XApp.CREATOR))
            if (uid < 0 || app.uid == uid)
                return app;
        return null;
    }

    // Hooks of an app which can be assigned, optionally of one group only
    static List<XHook> getAvailable(List<XHook> hooks, XApp app, List<String> collection, String group) {
        List<XHook> result = new ArrayList<>();
        for (XHook hook : hooks)
            if (hook.isAvailable(app.packageName, collection) &&
                    (group == null || group.equals(hook.getGroup())))
                result.add(hook);
        return result;
    }

    // Assign or remove hooks, which stops the app when it is stopped automatically
    static void assign(Context context, XApp app, List<XHook> hooks, boolean assign) {
        ArrayList<String> hookids = new ArrayList<>();
        for (XHook hook : hooks)
            hookids.add(hook.getId());
        assignHooks(context, hookids, app.packageName, app.uid, !assign, app.forceStop);
    }

    static void assignHooks(Context context, ArrayList<String> hookids,
                            String packageName, int uid, boolean delete, boolean kill) {
        Bundle args = new Bundle();
        args.putStringArrayList("hooks", hookids);
        args.putString("packageName", packageName);
        args.putInt("uid", uid);
        args.putBoolean("delete", delete);
        args.putBoolean("kill", kill);
        call(context, "assignHooks", args);
    }

    // Stop an app, so it starts with the current restrictions and values
    static void forceStop(Context context, XApp app) {
        Bundle args = new Bundle();
        args.putString("packageName", app.packageName);
        args.putInt("uid", app.uid);
        call(context, "forceStop", args);
    }

    // Hook ids assigned to each package of the current user, including hooks which are not available
    static Map<String, List<String>> getAssignments(Context context) {
        Map<String, List<String>> result = new HashMap<>();
        try (Cursor cursor = query(context, "getAssignments", null)) {
            while (cursor != null && cursor.moveToNext()) {
                String packageName = cursor.getString(0);
                if (!result.containsKey(packageName))
                    result.put(packageName, new ArrayList<>());
                result.get(packageName).add(cursor.getString(2));
            }
        }
        return result;
    }

    // Settings of the current user

    static String getSetting(Context context, String category, String name) {
        return getSetting(context, Util.getUserId(Process.myUid()), category, name);
    }

    static String getSetting(Context context, int user, String category, String name) {
        Bundle args = new Bundle();
        args.putInt("user", user);
        args.putString("category", category);
        args.putString("name", name);
        Bundle result = call(context, "getSetting", args);
        return (result == null ? null : result.getString("value"));
    }

    static boolean getSettingBoolean(Context context, String category, String name) {
        return getSettingBoolean(context, Util.getUserId(Process.myUid()), category, name);
    }

    static boolean getSettingBoolean(Context context, int user, String category, String name) {
        return Boolean.parseBoolean(getSetting(context, user, category, name));
    }

    static void putSetting(Context context, String category, String name, String value) {
        putSetting(context, category, name, value, false);
    }

    // Setting a null value deletes the setting; kill force stops the package of the category
    static void putSetting(Context context, String category, String name, String value, boolean kill) {
        Bundle args = new Bundle();
        args.putInt("user", Util.getUserId(Process.myUid()));
        args.putString("category", category);
        args.putString("name", name);
        args.putString("value", value);
        args.putBoolean("kill", kill);
        call(context, "putSetting", args);
    }

    static void putSettingBoolean(Context context, String category, String name, boolean value) {
        putSetting(context, category, name, Boolean.toString(value));
    }

    // Settings of a category (global or a package name) of the current user
    static Map<String, String> getSettings(Context context, String category) {
        Map<String, String> result = new HashMap<>();
        try (Cursor cursor = query(context, "getSettings", new String[]{category, Integer.toString(Process.myUid())})) {
            while (cursor != null && cursor.moveToNext())
                result.put(cursor.getString(0), cursor.getString(1));
        }
        return result;
    }

    // {category, name, value} of all settings of the current user
    static List<String[]> getAllSettings(Context context) {
        List<String[]> result = new ArrayList<>();
        try (Cursor cursor = query(context, "getAllSettings", null)) {
            while (cursor != null && cursor.moveToNext())
                result.add(new String[]{cursor.getString(0), cursor.getString(1), cursor.getString(2)});
        }
        return result;
    }

    // Enabled hook collections
    static List<String> getCollection(Context context) {
        return Util.parseCollection(getSetting(context, "global", "collection"));
    }

    static void putCollection(Context context, List<String> collection) {
        putSetting(context, "global", "collection",
                collection.isEmpty() ? null : TextUtils.join(",", collection));
    }

    // Restrictions and settings of the current user; custom definitions are kept
    static void clearData(Context context) {
        Bundle args = new Bundle();
        args.putInt("user", Util.getUserId(Process.myUid()));
        call(context, "clearData", args);
    }

    // Restrictions applied: package, uid, hook, used, old and new value
    static Cursor getLog(Context context) {
        return query(context, "getLog", null);
    }
}
