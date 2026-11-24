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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
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
// for backups which list the built-in definitions they were made with, like the ones of this version;
// the definitions moved to another group or leaving out fewer packages since the backup (see XDatabase.MOVED)
// complete them also for backups without that list.
// Older versions, which do not know the list, restore the backups of this version too.
class Backup {
    private static final String TAG = "XLua.Backup";

    private static final int FORMAT = 1;

    // A file which is not a backup of this version, like exported definitions, which have a similar name;
    // the message is for the user (see Ui.error, which shows toString), the cause for the log
    static class FileException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        FileException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public String toString() {
            return getMessage();
        }
    }

    // What to ask before a restore or an import, see preview
    static class Preview {
        final List<String> replaced = new ArrayList<>(); // Built-in definitions which custom ones would replace
        final List<String> existing = new ArrayList<>(); // Custom definitions which others would replace
        final List<String> added = new ArrayList<>(); // Definitions which replace none of these
        boolean android; // Restrictions of system server
    }

    // A restore which failed, with what it had done put back: the message is for the user, see FileException
    static class RestoreException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        final int notUndone; // The steps which could not be put back

        RestoreException(String message, Throwable cause, int notUndone) {
            super(message, cause);
            this.notUndone = notUndone;
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
        int failed; // Settings and restrictions which cannot be read, like of an edited file, left out
        final List<String> replaced = new ArrayList<>(); // Built-in definitions which restored custom ones replace
        int apps;
        final List<String> missing = new ArrayList<>(); // Packages which are not installed
        int added; // Restrictions which were not there yet
        int renamed; // Restrictions added of definitions which have another name now
        int removed; // Restrictions of definitions which do not exist anymore
        int completed; // Restrictions added of definitions added, moved or leaving out fewer packages since the backup
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

        // The built-in definitions of this version, so a restore by a later version knows which are newer,
        // enabled and for this Android version only, so a restore adds the hooks enabled since and of a newer Android version
        JSONArray jbuiltins = new JSONArray();
        for (String id : builtinsForThisSdk(Data.getBuiltinIds(context), Data.getAllHooks(context, null)))
            jbuiltins.put(id);
        jroot.put("builtins", jbuiltins);

        // The moved definitions this version knows, so a restore by a later version completes the groups with the
        // ones moved since only, see completingMoved
        jroot.put("moved", moved());

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

    // The file is read once, by the screen which asks before a restore: what the user confirmed in the preview, which can
    // come from someone else, is what restore applies, not what the file holds when it is read again
    static JSONObject read(Context context, Uri uri) throws Throwable {
        return parse(context, Util.readText(context, uri));
    }

    static JSONObject parse(Context context, String text) {
        JSONObject jroot;
        try {
            jroot = new JSONObject(text);
        } catch (JSONException | StackOverflowError ex) {
            // A file which is cut off, or which is not JSON, or nests deeper than the parser reads
            throw new FileException(context.getString(R.string.msg_not_backup), ex);
        }
        if (!jroot.has("format"))
            throw new FileException(context.getString(R.string.msg_not_backup), null);
        if (jroot.optInt("format", 0) != FORMAT)
            throw new FileException(context.getString(R.string.msg_backup_format, jroot.optString("format")), null);
        return jroot;
    }

    // What a backup does beyond setting values, which is asked before a restore, since a backup can come from someone
    // else: a definition with the id of a built-in one runs its script in all apps with that hook, like an import,
    // the other definitions run theirs in the apps restricted with them,
    // and restrictions of android apply to all of system server, like restricting it by hand
    static Preview preview(Context context, Uri uri) throws Throwable {
        return preview(context, read(context, uri));
    }

    static Preview preview(Context context, JSONObject jroot) {
        return preview(jroot, Data.getBuiltinIds(context));
    }

    static Preview preview(JSONObject jroot, Set<String> builtins) {
        Preview preview = preview(jroot.optJSONArray("hooks"), builtins, Collections.emptySet());
        JSONArray jrestrictions = jroot.optJSONArray("restrictions");
        for (int i = 0; jrestrictions != null && i < jrestrictions.length(); i++) {
            JSONObject jrestriction = jrestrictions.optJSONObject(i);
            if (jrestriction != null && "android".equals(jrestriction.optString("package")))
                preview.android = true;
        }
        return preview;
    }

    // What the definitions of a backup or an imported file replace, a custom definition, also of a built-in one, first:
    // it is gone with its script, which an import asks about, see FragmentHooks.importHooks
    static Preview preview(JSONArray jhooks, Set<String> builtins, Set<String> custom) {
        Preview preview = new Preview();
        for (int i = 0; jhooks != null && i < jhooks.length(); i++) {
            JSONObject jhook = jhooks.optJSONObject(i);
            if (jhook == null)
                continue;
            String id = XHook.id(jhook.optString("collection"), jhook.optString("name"));
            if (custom.contains(id))
                preview.existing.add(id);
            else if (builtins.contains(id))
                preview.replaced.add(id);
            else
                preview.added.add(id);
        }
        return preview;
    }

    // The longest category and name, and the longest value of a setting, which a restore applies: a value of a megabyte, which
    // no setting of Doppel is, would not go through the binder to the service
    static final int MAX_NAME = 1024;
    static final int MAX_VALUE = 100 * 1000;

    // What the service does for a restore, and what it is asked to put back: in one place, so a restore which fails halfway
    // is tested with a service which fails
    interface Service {
        Set<String> builtinIds() throws Throwable;

        // The custom definitions as users edit them, and all settings of the user, which a failed restore puts back
        JSONArray customHooks() throws Throwable;

        List<String[]> settings() throws Throwable;

        List<XHook> allHooks() throws Throwable;

        List<String> collection() throws Throwable;

        XHook putCustom(JSONObject jhook) throws Throwable;

        // A null definition deletes a custom definition
        void putHook(String id, String definition) throws Throwable;

        void putSettings(List<String[]> settings) throws Throwable;

        // The uid of an installed package, null when it is not
        Integer uid(String packageName) throws Throwable;

        // The app as the screens list it, null when it is not listed, see XProvider.inSystemServerOnly
        XApp app(String packageName, int uid) throws Throwable;

        boolean inSystemServerOnly(String packageName) throws Throwable;

        // The number of assignments which were added or deleted
        int assign(ArrayList<String> ids, String packageName, int uid, boolean delete, boolean kill) throws Throwable;
    }

    private static class Live implements Service {
        private final Context context;

        Live(Context context) {
            this.context = context;
        }

        @Override
        public Set<String> builtinIds() {
            return Data.getBuiltinIds(context);
        }

        @Override
        public JSONArray customHooks() throws JSONException {
            return Data.getCustomHooks(context);
        }

        @Override
        public List<String[]> settings() {
            return Data.getAllSettings(context);
        }

        @Override
        public List<XHook> allHooks() {
            return Data.getAllHooks(context, null);
        }

        @Override
        public List<String> collection() {
            return Data.getCollection(context);
        }

        @Override
        public XHook putCustom(JSONObject jhook) throws JSONException {
            return Data.putCustom(context, jhook);
        }

        @Override
        public void putHook(String id, String definition) {
            Data.putHook(context, id, definition);
        }

        @Override
        public void putSettings(List<String[]> settings) {
            Data.putSettings(context, settings);
        }

        @Override
        public Integer uid(String packageName) throws Throwable {
            try {
                return Util.getApplicationInfo(context.getPackageManager(), packageName, 0).uid;
            } catch (PackageManager.NameNotFoundException ignored) {
                return null;
            }
        }

        @Override
        public XApp app(String packageName, int uid) {
            return Data.getApp(context, packageName, uid);
        }

        @Override
        public boolean inSystemServerOnly(String packageName) {
            try {
                PackageManager pm = context.getPackageManager();
                return XProvider.inSystemServerOnly(pm, Util.getApplicationInfo(pm, packageName, 0));
            } catch (PackageManager.NameNotFoundException ignored) {
                return false;
            }
        }

        @Override
        public int assign(ArrayList<String> ids, String packageName, int uid, boolean delete, boolean kill) {
            // Restored hooks do not count as restricted recently
            return Data.assignHooks(context, ids, packageName, uid, delete, kill, !delete);
        }
    }

    // What a backup has which can be applied, all found and checked before anything is written; what cannot be read is
    // left out and counted, the rest is restored
    static class Plan {
        final List<JSONObject> hooks = new ArrayList<>();
        final List<String[]> settings = new ArrayList<>(); // Category, name and value, which is null to delete it
        final List<Restriction> restrictions = new ArrayList<>();
        boolean hasRestrictions = false;
        JSONArray builtins; // As the backup has them, if it does
    }

    static class Restriction {
        final String packageName;
        final List<String> ids = new ArrayList<>();

        Restriction(String packageName) {
            this.packageName = packageName;
        }
    }

    static Plan plan(JSONObject jroot, Result result) {
        Plan plan = new Plan();

        // Definitions: one this version cannot read leaves the others; with a script which compiles, like an import,
        // else it would only fail in the apps
        JSONArray jhooks = jroot.optJSONArray("hooks");
        for (int i = 0; jhooks != null && i < jhooks.length(); i++)
            try {
                JSONObject jhook = jhooks.getJSONObject(i);
                ActivityHookEdit.check(jhook);
                plan.hooks.add(jhook);
            } catch (Throwable ex) {
                Log.w(TAG, "Custom definition not restored: " + ex);
                result.hooksFailed++;
            }

        JSONArray jsettings = jroot.optJSONArray("settings");
        for (int i = 0; jsettings != null && i < jsettings.length(); i++) {
            // One which cannot be read leaves the others, like a definition
            JSONObject jsetting = jsettings.optJSONObject(i);
            try {
                if (jsetting == null || jsetting.isNull("category") || jsetting.isNull("name"))
                    throw new IllegalArgumentException("Setting invalid");
                String category = jsetting.getString("category");
                String name = jsetting.getString("name");
                String value = (jsetting.isNull("value") ? null : jsetting.getString("value"));
                if (category.length() > MAX_NAME || name.length() > MAX_NAME || (value != null && value.length() > MAX_VALUE))
                    throw new IllegalArgumentException("Setting too long");
                plan.settings.add(new String[]{category, name, value});
            } catch (JSONException | IllegalArgumentException ex) {
                Log.w(TAG, "Setting not restored: " + ex);
                result.failed++;
            }
        }

        JSONArray jrestrictions = jroot.optJSONArray("restrictions");
        plan.hasRestrictions = (jrestrictions != null);
        for (int i = 0; jrestrictions != null && i < jrestrictions.length(); i++) {
            JSONObject jrestriction = jrestrictions.optJSONObject(i);
            JSONArray jids = (jrestriction == null ? null : jrestriction.optJSONArray("hooks"));
            if (jrestriction == null || jrestriction.isNull("package") || jids == null) {
                result.failed++;
                continue;
            }
            try {
                Restriction restriction = new Restriction(jrestriction.getString("package"));
                for (int j = 0; j < jids.length(); j++)
                    restriction.ids.add(jids.getString(j));
                plan.restrictions.add(restriction);
            } catch (JSONException ex) {
                Log.w(TAG, "Restriction not restored: " + ex);
                result.failed++;
            }
        }

        JSONArray jbuiltins = jroot.optJSONArray("builtins");
        if (jbuiltins != null)
            try {
                for (int i = 0; i < jbuiltins.length(); i++)
                    jbuiltins.getString(i);
                plan.builtins = jbuiltins;
            } catch (JSONException ex) {
                Log.w(TAG, "Built-in definitions of the backup not read: " + ex);
            }
        return plan;
    }

    // A step which puts back what a step of a restore did
    interface Undo {
        void run() throws Throwable;
    }

    // The steps in the reverse order of their making; one which fails does not keep the others, the number of those is
    // returned
    static int undo(Deque<Undo> steps) {
        int failed = 0;
        while (!steps.isEmpty())
            try {
                steps.pop().run();
            } catch (Throwable ex) {
                Log.w(TAG, "Not put back: " + ex);
                failed++;
            }
        return failed;
    }

    // Values with the same name are replaced, restrictions are added to the ones an app has;
    // apps of which restrictions were added are stopped when they are stopped automatically
    static Result restore(Context context, Uri uri) throws Throwable {
        return restore(context, read(context, uri));
    }

    static Result restore(Context context, JSONObject jroot) throws Throwable {
        return restore(context, new Live(context), jroot);
    }

    // Everything the file holds is checked before the first write, and what is written is put back when a later write
    // fails (the service gone, a call refused), so a restore is all or nothing: with the definitions already live and
    // some of the settings and restrictions replaced, the user would have a state which no backup has. Only the
    // apps stopped to apply the restrictions stay stopped, which starts them again.
    static Result restore(Context context, Service service, JSONObject jroot) throws Throwable {
        Result result = new Result();
        result.version = jroot.optInt("version", 0);
        Plan plan = plan(jroot, result);

        Deque<Undo> undo = new ArrayDeque<>();
        try {
            apply(service, plan, jroot, result, undo);
        } catch (Throwable ex) {
            Log.e(TAG, "Restore failed: " + ex);
            int failed = undo(undo);
            throw new RestoreException(failed == 0
                    ? context.getString(R.string.msg_restore_undone, Ui.describe(context, ex))
                    : context.getString(R.string.msg_restore_undone_partly, failed, Ui.describe(context, ex)), ex, failed);
        }
        return result;
    }

    private static void apply(Service service, Plan plan, JSONObject jroot, Result result, Deque<Undo> undo) throws Throwable {
        // Definitions first, so restrictions can refer to them
        if (!plan.hooks.isEmpty()) {
            Set<String> builtinIds = service.builtinIds();
            Map<String, JSONObject> custom = new HashMap<>();
            JSONArray jcustom = service.customHooks();
            for (int i = 0; i < jcustom.length(); i++) {
                JSONObject jhook = jcustom.getJSONObject(i);
                custom.put(XHook.id(jhook.optString("collection"), jhook.optString("name")), jhook);
            }
            for (JSONObject jhook : plan.hooks)
                try {
                    XHook hook = service.putCustom(jhook);
                    // Putting back a definition of the user, or deleting the one which replaced a built-in one
                    final String id = hook.getId();
                    final JSONObject was = custom.get(id);
                    undo.push(() -> {
                        if (was == null)
                            service.putHook(id, null);
                        else
                            service.putCustom(was);
                    });
                    result.hooks++;
                    if (builtinIds.contains(id))
                        result.replaced.add(id);
                } catch (Throwable ex) {
                    // The service can refuse a definition this version cannot use, like one of a class it does not have
                    Log.w(TAG, "Custom definition not restored: " + ex);
                    result.hooksFailed++;
                }
        }

        // Settings before restrictions, so restored apps are stopped according to their restored setting; in calls of
        // some at a time
        if (!plan.settings.isEmpty()) {
            Map<String, String> before = new HashMap<>();
            for (String[] setting : service.settings())
                before.put(setting[0] + "\n" + setting[1], setting[2]);
            for (List<String[]> chunk : chunks(plan.settings)) {
                // Registered before the call, which can fail after the service wrote them; putting back what is there is
                // harmless
                List<String[]> back = new ArrayList<>();
                for (String[] setting : chunk)
                    back.add(new String[]{setting[0], setting[1], before.get(setting[0] + "\n" + setting[1])});
                undo.push(() -> service.putSettings(back));
                service.putSettings(chunk);
                result.settings += chunk.size();
            }
        }

        if (!plan.hasRestrictions)
            return;

        // The definitions now, with the restored custom ones, and the built-in ones newer than the backup
        Map<String, XHook> hooks = new HashMap<>();
        for (XHook hook : service.allHooks())
            hooks.put(hook.getId(), hook);
        Set<String> builtins = service.builtinIds();
        Set<String> newer = new HashSet<>();
        if (plan.builtins != null) {
            Set<String> known = new HashSet<>();
            for (int i = 0; i < plan.builtins.length(); i++)
                known.add(renamed(plan.builtins.getString(i)));
            for (String id : builtins)
                if (!known.contains(id))
                    newer.add(id);
        }
        List<String> collection = service.collection();

        for (Restriction restriction : plan.restrictions) {
            String packageName = restriction.packageName;
            Integer uid = service.uid(packageName);
            if (uid == null) {
                result.missing.add(packageName);
                continue;
            }
            XApp app = service.app(packageName, uid);
            if (app == null) {
                // Installed, but not listed because its restrictions would never be installed
                if (!service.inSystemServerOnly(packageName))
                    result.missing.add(packageName);
                continue;
            }

            // The counts of renamed and newer definitions are of the restrictions the app did not have yet
            Set<String> existing = new HashSet<>();
            for (XAssignment assignment : app.assignments)
                existing.add(assignment.id);

            // Renamed definitions get the restrictions of their former name, removed ones are left out
            Set<String> ids = new LinkedHashSet<>();
            for (String id : restriction.ids) {
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
            for (String id : completingMoved(jroot, ids, hooks, packageName, collection))
                if (ids.add(id) && !existing.contains(id))
                    result.completed++;

            // Only the restrictions the app did not have are put back, with the usage of the others kept
            final ArrayList<String> added = new ArrayList<>(ids);
            added.removeAll(existing);
            final int appUid = uid;
            undo.push(() -> service.assign(added, packageName, appUid, true, false));

            // Only apps whose restrictions changed are stopped; persistent apps apply them after a restart
            int changed = service.assign(new ArrayList<>(ids), packageName, uid, false, app.stopsAutomatically());
            result.apps++;
            result.added += changed;
            if (changed > 0) {
                if (app.persistent)
                    result.reboot.add(app.getLabel());
                else if (app.forceStop)
                    result.stopped.add(app.getLabel());
            }

            // Without the definitions of the backup, the ones added since cannot be told from the ones
            // the user did not want; the groups which are partially restricted now are worth a look
            XApp restored = (plan.builtins == null ? service.app(packageName, uid) : null);
            if (restored != null)
                for (String group : groups)
                    if (restored.getAssignments(group).size() <
                            Data.getAvailable(new ArrayList<>(hooks.values()), restored, collection, group).size())
                        result.partial++;
        }
    }

    // The settings in parts which fit a call: a number of settings, and of characters, at most
    static final int CHUNK_SETTINGS = 100;
    static final int CHUNK_CHARS = 100 * 1000;

    static List<List<String[]>> chunks(List<String[]> settings) {
        List<List<String[]>> result = new ArrayList<>();
        List<String[]> chunk = new ArrayList<>();
        int chars = 0;
        for (String[] setting : settings) {
            int length = setting[0].length() + setting[1].length() + (setting[2] == null ? 0 : setting[2].length());
            if (!chunk.isEmpty() && (chunk.size() >= CHUNK_SETTINGS || chars + length > CHUNK_CHARS)) {
                result.add(chunk);
                chunk = new ArrayList<>();
                chars = 0;
            }
            chunk.add(setting);
            chars += length;
        }
        if (!chunk.isEmpty())
            result.add(chunk);
        return result;
    }

    // The built-in ids of which the definition, built-in or replaced by a custom one, can be assigned: enabled and for
    // this Android version, like the service tracks them (XProvider.enabledBuiltins), so a restore by a later version
    // which enables a hook adds it
    static List<String> builtinsForThisSdk(Collection<String> builtins, List<XHook> hooks) {
        Set<String> enabled = new HashSet<>(XProvider.enabledBuiltins(hooks));
        List<String> result = new ArrayList<>();
        for (String id : builtins)
            if (enabled.contains(id))
                result.add(id);
        return result;
    }

    // The id of a definition now, after the renames since a backup was made, which can follow each other,
    // see XDatabase.RENAMED
    static String renamed(String id) {
        String prefix = XHook.builtinId("");
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

    // The ids of the moved built-in definitions of this version, which a backup lists, see completingMoved
    static JSONArray moved() {
        return new JSONArray(XDatabase.movedIds());
    }

    // The built-in definitions moved to another group or leaving out fewer packages since a backup, which complete
    // the groups an app restricted, like the update which moved them does (see XProvider.assignMovedBuiltins): the
    // groups are told without the moved definitions, so restricting one in its former group does not restrict the
    // group it is in now, and one leaving out fewer packages goes only to an app it left out before (see
    // XDatabase.movedFor); moved since are the ones of XDatabase.MOVED which the backup does not list as moved. A
    // backup which lists its built-in definitions but not the moved ones, of 2.1 to 2.3, was made after the first
    // moves (see XDatabase.MOVED_UNLISTED), so one of them its user switched off stays off; one which lists neither,
    // like of 2.0, was made before all of them
    static List<String> completingMoved(JSONObject jroot, Collection<String> ids, Map<String, XHook> hooks,
                                        String packageName, List<String> collection) throws JSONException {
        List<String> movedIds = XDatabase.movedIds();
        Set<String> moved = new HashSet<>(movedIds);
        Set<String> groups = new HashSet<>();
        for (String id : ids)
            if (!moved.contains(id) && hooks.containsKey(id))
                groups.add(hooks.get(id).getGroup());
        JSONArray jmoved = jroot.optJSONArray("moved");
        if (jmoved != null)
            for (int i = 0; i < jmoved.length(); i++)
                moved.remove(renamed(jmoved.getString(i)));
        else if (jroot.optJSONArray("builtins") != null)
            moved.removeAll(movedIds.subList(0, XDatabase.MOVED_UNLISTED));
        List<String> result = new ArrayList<>();
        for (String id : completing(moved, groups, hooks, packageName, collection))
            if (XDatabase.movedFor(id, packageName))
                result.add(id);
        return result;
    }
}
