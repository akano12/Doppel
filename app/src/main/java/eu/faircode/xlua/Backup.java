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
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Backup and restore of settings, custom hook definitions and restrictions.
// Restrictions are stored by package name, so a backup can be restored on another device.
// A backup of an older version restores into the definitions of this version: renamed definitions
// get the restrictions of their former name, removed ones are left out, and the definitions added since
// the backup complete the groups it restricted, like an update does for the groups restricted already,
// for backups which list the built-in definitions they were made with, like the ones of this version.
// Older versions, which do not know the list, restore the backups of this version too.
class Backup {
    private static final String TAG = "XLua.Backup";

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

    // What a restore did, see restore and FragmentSettings
    static class Result {
        int version; // The version code of the app which made the backup, 0 when unknown
        int settings;
        int hooks; // Custom definitions
        int hooksFailed; // Custom definitions this version cannot read, like of a newer version
        int apps;
        final List<String> missing = new ArrayList<>(); // Packages which are not installed
        int added; // Restrictions which were not there yet
        int renamed; // Restrictions added of definitions which have another name now
        int removed; // Restrictions of definitions which do not exist anymore
        int completed; // Restrictions added of definitions added since the backup
        int partial; // Groups partially restricted after a backup without its built-in definitions, like of 2.0
        final List<String> stopped = new ArrayList<>(); // Apps stopped to apply the restrictions
        final List<String> reboot = new ArrayList<>(); // Persistent apps, which apply them after a restart
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

        // The built-in definitions of this version, so a restore by a later version knows which are newer
        JSONArray jbuiltins = new JSONArray();
        for (String id : Data.getBuiltinIds(context))
            jbuiltins.put(id);
        jroot.put("builtins", jbuiltins);

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

    // Values with the same name are replaced, restrictions are added to the ones an app has;
    // apps of which restrictions were added are stopped when they are stopped automatically
    static Result restore(Context context, Uri uri) throws Throwable {
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

        Result result = new Result();
        result.version = jroot.optInt("version", 0);

        // Definitions first, so restrictions can refer to them; one this version cannot read leaves the others
        JSONArray jhooks = jroot.optJSONArray("hooks");
        if (jhooks != null)
            for (int i = 0; i < jhooks.length(); i++)
                try {
                    Data.putCustom(context, jhooks.getJSONObject(i));
                    result.hooks++;
                } catch (Throwable ex) {
                    Log.w(TAG, "Custom definition not restored: " + ex);
                    result.hooksFailed++;
                }

        // Settings before restrictions, so restored apps are stopped according to their restored setting
        JSONArray jsettings = jroot.optJSONArray("settings");
        if (jsettings != null)
            for (int i = 0; i < jsettings.length(); i++) {
                JSONObject jsetting = jsettings.getJSONObject(i);
                Data.putSetting(context,
                        jsetting.getString("category"),
                        jsetting.getString("name"),
                        jsetting.isNull("value") ? null : jsetting.getString("value"));
                result.settings++;
            }

        JSONArray jrestrictions = jroot.optJSONArray("restrictions");
        if (jrestrictions == null)
            return result;

        // The definitions now, with the restored custom ones, and the built-in ones newer than the backup
        Map<String, XHook> hooks = new HashMap<>();
        for (XHook hook : Data.getAllHooks(context, false))
            hooks.put(hook.getId(), hook);
        Set<String> builtins = Data.getBuiltinIds(context);
        JSONArray jbuiltins = jroot.optJSONArray("builtins");
        Set<String> newer = new HashSet<>();
        if (jbuiltins != null) {
            Set<String> known = new HashSet<>();
            for (int i = 0; i < jbuiltins.length(); i++)
                known.add(renamed(jbuiltins.getString(i)));
            for (String id : builtins)
                if (!known.contains(id))
                    newer.add(id);
        }
        List<String> collection = Data.getCollection(context);

        PackageManager pm = context.getPackageManager();
        for (int i = 0; i < jrestrictions.length(); i++) {
            JSONObject jrestriction = jrestrictions.getJSONObject(i);
            String packageName = jrestriction.getString("package");
            int uid;
            try {
                uid = pm.getApplicationInfo(packageName, 0).uid;
            } catch (PackageManager.NameNotFoundException ignored) {
                result.missing.add(packageName);
                continue;
            }
            XApp app = Data.getApp(context, packageName, uid);
            if (app == null) {
                result.missing.add(packageName);
                continue;
            }

            // The counts of renamed and newer definitions are of the restrictions the app did not have yet
            Set<String> existing = new HashSet<>();
            for (XAssignment assignment : app.assignments)
                existing.add(assignment.hook.getId());

            // Renamed definitions get the restrictions of their former name, removed ones are left out
            Set<String> ids = new LinkedHashSet<>();
            JSONArray jids = jrestriction.getJSONArray("hooks");
            for (int j = 0; j < jids.length(); j++) {
                String id = jids.getString(j);
                String current = renamed(id);
                if (!hooks.containsKey(current)) {
                    result.removed++;
                    continue;
                }
                if (ids.add(current) && !current.equals(id) && !existing.contains(current))
                    result.renamed++;
            }

            // Definitions added since the backup complete the groups it restricted
            Set<String> groups = new HashSet<>();
            for (String id : ids)
                groups.add(hooks.get(id).getGroup());
            for (String id : completing(newer, groups, hooks, packageName, collection))
                if (ids.add(id) && !existing.contains(id))
                    result.completed++;

            // Only apps whose restrictions changed are stopped; persistent apps apply them after a restart
            int added = Data.assignHooks(context, new ArrayList<>(ids), packageName, uid,
                    false, app.forceStop && !app.persistent, true);
            result.apps++;
            result.added += added;
            if (added > 0) {
                if (app.persistent)
                    result.reboot.add(app.getLabel());
                else if (app.forceStop)
                    result.stopped.add(app.getLabel());
            }

            // Without the definitions of the backup, the ones added since cannot be told from the ones
            // the user did not want; the groups which are partially restricted now are worth a look
            XApp restored = (jbuiltins == null ? Data.getApp(context, packageName, uid) : null);
            if (restored != null)
                for (String group : groups) {
                    int available = 0;
                    for (XHook hook : hooks.values())
                        if (group.equals(hook.getGroup()) && hook.isAvailable(packageName, collection))
                            available++;
                    if (restored.getAssignments(group).size() < available)
                        result.partial++;
                }
        }

        return result;
    }

    // The id of a definition now, after the renames since a backup was made, which can follow each other,
    // see XDatabase.RENAMED
    static String renamed(String id) {
        String prefix = Util.COLLECTION_DEFAULT + ".";
        if (!id.startsWith(prefix))
            return id;
        String name = id.substring(prefix.length());
        // As many renames as there are, should a list ever rename in a circle
        for (int i = 0; i < XDatabase.RENAMED.length; i++) {
            String next = null;
            for (String[] rename : XDatabase.RENAMED)
                if (rename[0].equals(name)) {
                    next = rename[1];
                    break;
                }
            if (next == null)
                break;
            name = next;
        }
        return prefix + name;
    }

    // The built-in definitions added since a backup which complete the groups an app restricted,
    // like the update which added them does for the groups restricted then (see XProvider.assignToRestricting):
    // enabled, for this Android version and the app, of an enabled collection
    static List<String> completing(Collection<String> newer, Set<String> groups, Map<String, XHook> hooks,
                                   String packageName, List<String> collection) {
        List<String> result = new ArrayList<>();
        for (String id : newer) {
            XHook hook = hooks.get(id);
            if (hook != null && groups.contains(hook.getGroup()) && hook.isAvailable(packageName, collection))
                result.add(id);
        }
        return result;
    }
}
