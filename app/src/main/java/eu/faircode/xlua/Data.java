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
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Data of the management screens, read from and written to the service.
// Calls block, so call them from a background thread.
final class Data {
    static final String COLLECTION_DEFAULT = "Privacy";

    private Data() {
    }

    // Hook definitions available for the enabled collections
    static List<XHook> getHooks(Context context) {
        return query(context, "xlua.getHooks2", XHook.CREATOR);
    }

    // Installed apps with their assigned hooks
    static List<XApp> getApps(Context context) {
        return query(context, "xlua.getApps2", XApp.CREATOR);
    }

    private static <T> List<T> query(Context context, String method, Parcelable.Creator<T> creator) {
        List<T> result = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver()
                .query(XProvider.getURI(), new String[]{method}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                byte[] marshaled = cursor.getBlob(0);
                Parcel parcel = Parcel.obtain();
                try {
                    parcel.unmarshall(marshaled, 0, marshaled.length);
                    parcel.setDataPosition(0);
                    result.add(creator.createFromParcel(parcel));
                } finally {
                    parcel.recycle();
                }
            }
        }
        return result;
    }

    static XApp getApp(Context context, String packageName, int uid) {
        for (XApp app : getApps(context))
            if (app.packageName.equals(packageName) && (uid < 0 || app.uid == uid))
                return app;
        return null;
    }

    // Enabled hook collections
    static List<String> getCollection(Context context) {
        List<String> result = new ArrayList<>();
        String collection = XProvider.getSetting(context, "global", "collection");
        if (collection == null)
            result.add(COLLECTION_DEFAULT);
        else
            for (String name : collection.split(","))
                if (!name.trim().isEmpty())
                    result.add(name.trim());
        return result;
    }

    static void putCollection(Context context, List<String> collection) {
        XProvider.putSetting(context, "global", "collection",
                collection.isEmpty() ? null : TextUtils.join(",", collection));
    }

    // Ids of the definitions which are part of the app
    static Set<String> getBuiltinIds(Context context) throws Exception {
        Set<String> ids = new HashSet<>();
        for (XHook builtin : XHook.readHooks(context, context.getApplicationInfo().publicSourceDir))
            ids.add(builtin.getId());
        return ids;
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
        Bundle args = new Bundle();
        args.putStringArrayList("hooks", hookids);
        args.putString("packageName", app.packageName);
        args.putInt("uid", app.uid);
        args.putBoolean("delete", !assign);
        args.putBoolean("kill", app.forceStop);
        context.getContentResolver().call(XProvider.getURI(), "xlua", "assignHooks", args);
    }

    // Stop an app, so it starts with the current restrictions and values
    static void forceStop(Context context, XApp app) {
        Bundle args = new Bundle();
        args.putStringArrayList("hooks", new ArrayList<String>());
        args.putString("packageName", app.packageName);
        args.putInt("uid", app.uid);
        args.putBoolean("delete", false);
        args.putBoolean("kill", true);
        context.getContentResolver().call(XProvider.getURI(), "xlua", "assignHooks", args);
    }

    static List<String> getGroupNames(List<XHook> hooks) {
        List<String> groups = new ArrayList<>();
        for (XHook hook : hooks)
            if (!groups.contains(hook.getGroup()))
                groups.add(hook.getGroup());
        Collections.sort(groups);
        return groups;
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
        XProvider.putHook(context, hook.getId(), hook.toJSON());
        return hook;
    }
}
