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

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Backup and restore of settings, custom hook definitions and restrictions.
// Restrictions are stored by package name, so a backup can be restored on another device.
class Backup {
    private static final int FORMAT = 1;

    // A file which is not a backup of this version, like exported definitions, which have a similar name;
    // the message is for the user (see Ui.error, which shows toString), the cause for the log
    static class FileException extends IllegalArgumentException {
        FileException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public String toString() {
            return getMessage();
        }
    }

    // Counts of settings, custom definitions and restricted apps
    static int[] save(Context context, Uri uri) throws Throwable {
        JSONObject jroot = new JSONObject();
        jroot.put("format", FORMAT);
        jroot.put("version", BuildConfig.VERSION_CODE);

        JSONArray jsettings = new JSONArray();
        for (String[] setting : Data.getAllSettings(context)) {
            JSONObject jsetting = new JSONObject();
            jsetting.put("category", setting[0]);
            jsetting.put("name", setting[1]);
            jsetting.put("value", setting[2]);
            jsettings.put(jsetting);
        }
        jroot.put("settings", jsettings);

        JSONArray jhooks = Data.getCustomHooks(context);
        jroot.put("hooks", jhooks);

        // Also assignments of hooks which are currently not available, like of a disabled collection
        JSONArray jrestrictions = new JSONArray();
        for (Map.Entry<String, List<String>> entry : Data.getAssignments(context).entrySet()) {
            JSONArray jids = new JSONArray();
            for (String id : entry.getValue())
                jids.put(id);
            JSONObject jrestriction = new JSONObject();
            jrestriction.put("package", entry.getKey());
            jrestriction.put("hooks", jids);
            jrestrictions.put(jrestriction);
        }
        jroot.put("restrictions", jrestrictions);

        Util.writeJson(context, uri, jroot.toString(2));

        return new int[]{jsettings.length(), jhooks.length(), jrestrictions.length()};
    }

    // Values with the same name are replaced, restrictions are added; apps are not stopped
    static int[] restore(Context context, Uri uri) throws Throwable {
        JSONObject jroot;
        try {
            jroot = new JSONObject(Util.readText(context, uri));
        } catch (JSONException ex) {
            throw new FileException(context.getString(R.string.msg_not_backup), ex);
        }
        if (!jroot.has("format"))
            throw new FileException(context.getString(R.string.msg_not_backup), null);
        if (jroot.optInt("format", 0) != FORMAT)
            throw new FileException(context.getString(R.string.msg_backup_format, jroot.optString("format")), null);

        // Definitions first, so restrictions can refer to them
        JSONArray jhooks = jroot.optJSONArray("hooks");
        int hooks = 0;
        if (jhooks != null)
            for (int i = 0; i < jhooks.length(); i++) {
                Data.putCustom(context, jhooks.getJSONObject(i));
                hooks++;
            }

        JSONArray jsettings = jroot.optJSONArray("settings");
        int settings = 0;
        if (jsettings != null)
            for (int i = 0; i < jsettings.length(); i++) {
                JSONObject jsetting = jsettings.getJSONObject(i);
                Data.putSetting(context,
                        jsetting.getString("category"),
                        jsetting.getString("name"),
                        jsetting.isNull("value") ? null : jsetting.getString("value"));
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
                Data.assignHooks(context, ids, packageName, uid, false, false, true);
                apps++;
            }
        }

        return new int[]{settings, hooks, apps};
    }
}
