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
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Scanner;

// Backup and restore of settings, custom hook definitions and restrictions.
// Restrictions are stored by package name, so a backup can be restored on another device.
class Backup {
    private static final int FORMAT = 1;

    // Counts of settings, custom definitions and restricted apps
    static int[] save(Context context, Uri uri) throws Throwable {
        JSONObject jroot = new JSONObject();
        jroot.put("format", FORMAT);
        jroot.put("version", BuildConfig.VERSION_CODE);

        JSONArray jsettings = new JSONArray();
        for (String[] setting : XProvider.getAllSettings(context)) {
            JSONObject jsetting = new JSONObject();
            jsetting.put("category", setting[0]);
            jsetting.put("name", setting[1]);
            jsetting.put("value", setting[2]);
            jsettings.put(jsetting);
        }
        jroot.put("settings", jsettings);

        JSONArray jhooks = new JSONArray();
        for (XHook hook : XProvider.getAllHooks(context))
            if (!hook.isBuiltin())
                jhooks.put(ActivityHooks.toEditable(hook));
        jroot.put("hooks", jhooks);

        JSONArray jrestrictions = new JSONArray();
        try (Cursor cursor = context.getContentResolver().query(XProvider.getURI(),
                new String[]{"xlua.getApps2"}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                byte[] marshaled = cursor.getBlob(0);
                Parcel parcel = Parcel.obtain();
                XApp app;
                try {
                    parcel.unmarshall(marshaled, 0, marshaled.length);
                    parcel.setDataPosition(0);
                    app = XApp.CREATOR.createFromParcel(parcel);
                } finally {
                    parcel.recycle();
                }
                if (app.assignments.isEmpty())
                    continue;

                JSONArray jids = new JSONArray();
                for (XAssignment assignment : app.assignments)
                    jids.put(assignment.hook.getId());
                JSONObject jrestriction = new JSONObject();
                jrestriction.put("package", app.packageName);
                jrestriction.put("hooks", jids);
                jrestrictions.put(jrestriction);
            }
        }
        jroot.put("restrictions", jrestrictions);

        try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
            os.write(jroot.toString(2).getBytes(StandardCharsets.UTF_8));
        }

        return new int[]{jsettings.length(), jhooks.length(), jrestrictions.length()};
    }

    // Values with the same name are replaced, restrictions are added; apps are not stopped
    static int[] restore(Context context, Uri uri) throws Throwable {
        String text;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            text = new Scanner(is, "UTF-8").useDelimiter("\\A").next();
        }
        JSONObject jroot = new JSONObject(text);
        if (jroot.optInt("format", 0) != FORMAT)
            throw new IllegalArgumentException("Unsupported backup format " + jroot.optInt("format", 0));

        // Definitions first, so restrictions can refer to them
        JSONArray jhooks = jroot.optJSONArray("hooks");
        int hooks = 0;
        if (jhooks != null)
            for (int i = 0; i < jhooks.length(); i++) {
                ActivityHooks.putCustom(context, jhooks.getJSONObject(i));
                hooks++;
            }

        JSONArray jsettings = jroot.optJSONArray("settings");
        int settings = 0;
        if (jsettings != null)
            for (int i = 0; i < jsettings.length(); i++) {
                JSONObject jsetting = jsettings.getJSONObject(i);
                XProvider.putSetting(context,
                        jsetting.getString("category"),
                        jsetting.getString("name"),
                        jsetting.isNull("value") ? null : jsetting.getString("value"),
                        false);
                settings++;
            }

        JSONArray jrestrictions = jroot.optJSONArray("restrictions");
        int apps = 0;
        if (jrestrictions != null) {
            PackageManager pm = context.getPackageManager();
            for (int i = 0; i < jrestrictions.length(); i++) {
                JSONObject jrestriction = jrestrictions.getJSONObject(i);
                String packageName = jrestriction.getString("package");
                int uid;
                try {
                    uid = pm.getApplicationInfo(packageName, 0).uid;
                } catch (PackageManager.NameNotFoundException ignored) {
                    continue; // Not installed on this device
                }

                ArrayList<String> ids = new ArrayList<>();
                JSONArray jids = jrestriction.getJSONArray("hooks");
                for (int j = 0; j < jids.length(); j++)
                    ids.add(jids.getString(j));
                XProvider.assignHooks(context, ids, packageName, uid, false);
                apps++;
            }
        }

        return new int[]{settings, hooks, apps};
    }
}
