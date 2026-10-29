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

import android.app.ActivityManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.StrictMode;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The service in system server, called through the settings provider (see XCore.hookSettings).
// Apps can report usage and read their own restrictions and settings, everything else
// is limited to system server and the app.
class XProvider {
    private static final String TAG = "XLua.Provider";

    // Selections of the rows of an app and of one of its assignments or groups; the arguments are the package name,
    // the uid as a string and the hook id or the group name
    private static final String WHERE_APP = "package = ? AND uid = ?";
    private static final String WHERE_ASSIGNMENT = "package = ? AND uid = ? AND hook = ?";
    private static final String WHERE_GROUP = "package = ? AND uid = ? AND name = ?";
    // Selection of the rows of the apps of a user, see getUidRange
    private static final String WHERE_USER = "uid >= ? AND uid <= ?";
    // Selection of a setting: the user, the category and the name
    private static final String WHERE_SETTING = "user = ? AND category = ? AND name = ?";

    // Loading and changing the definitions is serialized; the maps are replaced instead of changed,
    // so they are read without the lock, also during database calls
    private static final Object lock = new Object();

    private static XDatabase db = null;
    private static volatile Map<String, XHook> hooks = null;
    private static volatile Map<String, XHook> builtins = null;
    private static byte[] bootSecret = null; // See getBootSecret

    // Callers the service answers, see answers
    static final int CALLER_NONE = 0;
    static final int CALLER_ASSIGNED = 1; // An app with assignments, which can call for itself only
    static final int CALLER_PERMITTED = 2; // System server or the app, of which the permission was checked

    // The service answers calls of the settings provider with this method and queries of columns with this
    // prefix, see XCore.hookSettings and Data
    static final String METHOD = "xlua";
    static final String COLUMN = "xlua.";

    static Uri getURI() {
        return Settings.System.CONTENT_URI;
    }

    private static void loadData(Context context) throws RemoteException {
        boolean loaded = false;
        try {
            synchronized (lock) {
                if (db == null)
                    db = XDatabase.open();
                if (hooks == null) {
                    loadHooks(context);
                    loaded = true;
                }
            }
        } catch (Throwable ex) {
            throw failed(ex);
        }

        // Without holding the lock; restrictions keep working without the new assignments
        if (loaded)
            try {
                assignNewBuiltins();
                assignMovedBuiltins();
                removeObsoleteAssignments();
            } catch (Throwable ex) {
                Log.e(TAG, Log.getStackTraceString(ex));
            }
    }

    // Assignments of removed built-in definitions are dead rows, which getApps only reports as missing;
    // a custom definition with the same id, like one of Downloads, keeps its assignments,
    // also when it is stored but could not be loaded, see loadHooks
    private static void removeObsoleteAssignments() throws Throwable {
        final Map<String, XHook> all = hooks;
        db.write(d -> {
            for (String name : XDatabase.REMOVED) {
                String id = XHook.builtinId(name);
                if (all.containsKey(id))
                    continue;
                int rows = d.delete("assignment", "hook = ? AND hook NOT IN (SELECT id FROM hook)", new String[]{id});
                if (rows > 0)
                    Log.i(TAG, "Removed assignments of hook " + id + " rows=" + rows);
            }
            return null;
        });
    }

    // The groups of the definitions which are loaded, built-in and custom, see XNotify.cancelApp
    static Set<String> getGroups() {
        Set<String> groups = new HashSet<>();
        Map<String, XHook> current = hooks;
        if (current != null)
            for (XHook hook : current.values())
                groups.add(hook.getGroup());
        return groups;
    }

    private interface Answer<T> {
        T run() throws Throwable;
    }

    // The answer to a call or a query, with the database loaded and disk access allowed; illegal arguments
    // and refused callers are thrown as they are, other errors are logged, see failed
    private static <T> T answer(Context context, Answer<T> answer) throws RemoteException {
        loadData(context);

        StrictMode.ThreadPolicy originalPolicy = StrictMode.getThreadPolicy();
        try {
            StrictMode.allowThreadDiskReads();
            StrictMode.allowThreadDiskWrites();
            return answer.run();
        } catch (IllegalArgumentException | SecurityException | RemoteException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw failed(ex);
        } finally {
            StrictMode.setThreadPolicy(originalPolicy);
        }
    }

    // What apps with assignments may call and query, for themselves only, which each method checks: their usage,
    // their hooks, their settings and their values new each boot. Anything else would let a hooked app read or change
    // the data of other apps, see XProviderTest.
    static final Set<String> APP_CALLS = Collections.singleton("report");
    static final Set<String> APP_QUERIES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("getAssignedHooks2", "getSettings", "getBootValues")));

    // Permitted: the permission of the caller was checked already, see answers
    static Bundle call(Context context, String method, Bundle extras, boolean permitted)
            throws RemoteException, IllegalArgumentException {
        return answer(context, () -> {
            if (!permitted && !APP_CALLS.contains(method))
                enforcePermission(context);

            switch (method) {
                case "putHook":
                    return putHook(context, extras);
                case "assignHooks":
                    return assignHooks(context, extras);
                case "assignRestricting":
                    return assignRestricting(extras);
                case "report":
                    return report(context, extras);
                case "getSetting":
                    return getSetting(extras);
                case "putSetting":
                    return putSetting(context, extras);
                case "initApp":
                    return initApp(extras);
                case "clearApp":
                    return clearApp(extras);
                case "clearData":
                    return clearData(extras);
                case "forceStop":
                    forceStop(context, extras.getString("packageName"), Util.getUserId(extras.getInt("uid")));
                    return new Bundle();
                default:
                    return null;
            }
        });
    }

    // Methods ending in 2 return parcels; permitted: the permission of the caller was checked already, see answers
    static Cursor query(Context context, String method, String[] selection, boolean permitted) throws RemoteException {
        Cursor result = answer(context, () -> {
            if (!permitted && !APP_QUERIES.contains(method))
                enforcePermission(context);

            switch (method) {
                case "getHooks2":
                    return getHooks(selection);
                case "getBuiltinIds":
                    return getBuiltinIds();
                case "getApps2":
                    return getApps(context, selection);
                case "getAssignedHooks2":
                    return getAssignedHooks(context, selection, permitted);
                case "getAssignments":
                    return getAssignments();
                case "getSettings":
                    return getSettings(context, selection, permitted);
                case "getBootValues":
                    return getBootValues(context, selection, permitted);
                case "getAllSettings":
                    return getAllSettings();
                case "getLog":
                    return getLog(selection);
                default:
                    return null;
            }
        });

        if (result != null)
            result.moveToPosition(-1);
        return result;
    }

    // An error of the service is logged here, the caller gets the message
    private static RemoteException failed(Throwable ex) {
        Log.e(TAG, Log.getStackTraceString(ex));
        return new RemoteException(ex.getMessage());
    }

    // Hook definitions

    private static Bundle putHook(Context context, Bundle extras) throws Throwable {
        final String id = extras.getString("id");
        String definition = extras.getString("definition");
        if (id == null)
            throw new IllegalArgumentException("id missing");

        // Only custom definitions are stored, built-in definitions are part of the app (see Data.putCustom)
        final XHook hook = (definition == null ? null : XHook.fromJSON(definition));
        if (hook != null) {
            hook.validate();
            if (!id.equals(hook.getId()))
                throw new IllegalArgumentException("id mismatch");
            if (hook.isBuiltin())
                throw new IllegalArgumentException("builtin");
            hook.resolveClassName(context);
        }

        // Only a custom definition can be deleted
        synchronized (lock) {
            XHook current = (hook == null ? hooks.get(id) : null);
            if (current != null && current.isBuiltin())
                throw new IllegalArgumentException("builtin");
        }

        // The database is written first, so a definition which could not be stored is not active either
        // A deleted definition which did not replace a built-in definition leaves no hook to assign:
        // its assignments would not be shown anymore, but still be backed up and count for answers
        final boolean orphan = (hook == null && !builtins.containsKey(id));
        db.write(d -> {
            if (hook == null) {
                d.delete("hook", "id = ?", new String[]{id});
                if (orphan) {
                    int rows = d.delete("assignment", "hook = ?", new String[]{id});
                    Log.i(TAG, "Deleted assignments hook=" + id + " rows=" + rows);
                }
            } else {
                ContentValues cv = new ContentValues();
                cv.put("id", id);
                cv.put("definition", hook.toJSON());
                if (d.insertWithOnConflict("hook", null, cv, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                    throw new Throwable("Error inserting hook");
            }
            return null;
        });

        // Cache hook
        synchronized (lock) {
            Map<String, XHook> next = new HashMap<>(hooks);
            if (hook == null) {
                Log.i(TAG, "Deleting hook id=" + id);
                next.remove(id);
                // A deleted custom definition can have replaced a built-in definition
                if (builtins.containsKey(id)) {
                    Log.i(TAG, "Restoring builtin id=" + id);
                    // The class name is resolved already
                    next.put(id, builtins.get(id));
                }
            } else {
                Log.i(TAG, "Storing hook id=" + id);
                next.put(id, hook);
            }
            hooks = next;
        }

        return new Bundle();
    }

    // Selection: whether to include definitions which are not available (disabled, other collection or Android version)
    // and whether to include the scripts, which most screens of the app do not need
    private static Cursor getHooks(String[] selection) throws Throwable {
        if (selection == null || selection.length != 2)
            throw new IllegalArgumentException("selection invalid");
        boolean all = Boolean.parseBoolean(selection[0]);
        boolean lua = Boolean.parseBoolean(selection[1]);
        List<String> collection = getCollection(Util.getUserId(Binder.getCallingUid()));

        List<XHook> hv = new ArrayList<>();
        for (XHook hook : hooks.values())
            if (all || hook.isAvailable(null, collection))
                hv.add(hook);
        Collections.sort(hv, (h1, h2) -> h1.getId().compareTo(h2.getId()));

        MatrixCursor result = new MatrixCursor(new String[]{"blob"});
        for (XHook hook : hv)
            result.addRow(new Object[]{hook.marshall(lua)});
        return result;
    }

    // Ids of the built-in definitions, which custom definitions can replace, of the module version which runs
    private static Cursor getBuiltinIds() {
        MatrixCursor result = new MatrixCursor(new String[]{"id"});
        for (String id : builtins.keySet())
            result.addRow(new Object[]{id});
        return result;
    }

    // The definitions are kept only when all could be loaded, so a failure is retried on the next call
    private static void loadHooks(Context context) throws Throwable {
        final Map<String, XHook> hooks = new HashMap<>();
        final Map<String, XHook> builtins = new HashMap<>();

        // Built-in definitions
        PackageManager pm = context.getPackageManager();
        ApplicationInfo ai = Util.getApplicationInfo(pm, BuildConfig.APPLICATION_ID, 0);
        for (XHook builtin : XHook.readHooks(ai.publicSourceDir)) {
            builtin.resolveClassName(context);
            builtins.put(builtin.getId(), builtin);
            hooks.put(builtin.getId(), builtin);
        }

        // Custom definitions; an invalid one should not disable all others
        db.read(d -> {
            try (Cursor cursor = d.query("hook", new String[]{"id", "definition"}, null, null, null, null, null)) {
                while (cursor.moveToNext())
                    try {
                        XHook hook = XHook.fromJSON(cursor.getString(1));
                        hook.validate();
                        hook.resolveClassName(context);
                        hooks.put(hook.getId(), hook);
                    } catch (Throwable ex) {
                        Log.e(TAG, "Invalid definition id=" + cursor.getString(0) + ": " + ex);
                    }
            }
            return null;
        });

        Log.i(TAG, "Loaded hook definitions hooks=" + hooks.size() + " builtins=" + builtins.size());
        XProvider.builtins = builtins;
        XProvider.hooks = hooks;
    }

    // Built-in hooks added by an update are assigned to the apps which restrict their group,
    // like the screens of the app assign all hooks of a group
    // Enabled built-in hooks are tracked, so a hook enabled by an update is assigned too.
    // Hooks which were known already are never assigned again: a hook switched off for an app stays off.
    // Without the current marker the former marker "builtins" tells which hooks were known, disabled ones too;
    // without both, like at the first start, the current hooks are known.
    private static void assignNewBuiltins() throws Throwable {
        final Map<String, XHook> all = hooks;
        final Collection<XHook> builtin = builtins.values();
        db.write(d -> {
            Set<String> known = new HashSet<>();
            List<String> enabled = new ArrayList<>();
            for (XHook hook : builtin)
                if (hook.isEnabled())
                    enabled.add(hook.getId());

            String current = readSetting(d, -1, "service", "builtins_enabled");
            String marker = (current == null ? readSetting(d, -1, "service", "builtins") : current);
            if (marker == null)
                known.addAll(enabled);
            else
                known.addAll(Util.split(marker));

            List<String> added = new ArrayList<>();
            for (String id : enabled)
                if (!known.contains(id))
                    added.add(id);

            if (!added.isEmpty()) {
                // A custom definition can replace the built-in definition
                List<XHook> targets = new ArrayList<>();
                for (String id : added)
                    if (all.containsKey(id))
                        targets.add(all.get(id));
                int[] counts = assignToRestricting(d, all, targets, null, false);
                Log.i(TAG, "New built-in hooks=" + added.size() + " assigned=" + counts[0]);
            }

            if (current == null || !new HashSet<>(enabled).equals(known)) {
                writeSetting(d, -1, "service", "builtins_enabled", TextUtils.join(",", enabled));
                writeSetting(d, -1, "service", "builtins", null);
            }
            return null;
        });
    }

    // Built-in hooks moved to another group (see XDatabase.MOVED) are assigned once to the apps which restrict
    // the group they moved to, else a fully restricted group would show as partially restricted after the update.
    // Which groups an app restricts is told without the moved hooks: an app restricting a moved hook in its former
    // group does not restrict the group it moved to.
    private static void assignMovedBuiltins() throws Throwable {
        final Map<String, XHook> all = hooks;
        db.write(d -> {
            String marker = readSetting(d, -1, "service", "builtins_moved");
            Set<String> done = new HashSet<>();
            if (marker != null)
                done.addAll(Util.split(marker));

            List<String> ids = new ArrayList<>();
            Map<String, XHook> others = new HashMap<>(all);
            for (String[] moved : XDatabase.MOVED) {
                String id = XHook.builtinId(moved[0]);
                ids.add(id);
                others.remove(id);
            }
            if (done.containsAll(ids))
                return null;

            // A custom definition can replace the built-in definition, then its group counts
            List<XHook> targets = new ArrayList<>();
            for (String id : ids)
                if (!done.contains(id) && all.containsKey(id))
                    targets.add(all.get(id));
            if (!targets.isEmpty()) {
                int[] counts = assignToRestricting(d, others, targets, null, false);
                Log.i(TAG, "Moved built-in hooks=" + targets.size() + " assigned=" + counts[0] + " apps=" + counts[1]);
            }
            writeSetting(d, -1, "service", "builtins_moved", TextUtils.join(",", ids));
            return null;
        });
    }

    // Assigns hooks to the apps which restrict a hook of the same group, of all users or of one user,
    // optionally also when the collection of a hook is not enabled (yet). Existing assignments are kept,
    // replacing one would reset its usage data. Returns the number of assignments and apps.
    private static int[] assignToRestricting(SQLiteDatabase d, Map<String, XHook> hooks, List<XHook> targets,
                                             Integer user, boolean anyCollection) throws Throwable {
        // Groups restricted per app
        Map<String, Set<String>> restricted = new HashMap<>();
        Set<String> assigned = new HashSet<>();
        String[] range = (user == null ? null : getUidRange(user));
        try (Cursor cursor = d.query("assignment", new String[]{"package", "uid", "hook"},
                range == null ? null : WHERE_USER, range, null, null, null)) {
            while (cursor.moveToNext()) {
                String key = cursor.getInt(1) + ":" + cursor.getString(0);
                assigned.add(key + "/" + cursor.getString(2));
                XHook hook = hooks.get(cursor.getString(2));
                if (hook == null)
                    continue;
                if (!restricted.containsKey(key))
                    restricted.put(key, new HashSet<>());
                restricted.get(key).add(hook.getGroup());
            }
        }

        Map<Integer, List<String>> collections = new HashMap<>();
        int count = 0;
        Set<String> apps = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : restricted.entrySet()) {
            int uid = Integer.parseInt(entry.getKey().substring(0, entry.getKey().indexOf(':')));
            String packageName = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            int userid = Util.getUserId(uid);
            if (!collections.containsKey(userid))
                collections.put(userid, readCollection(d, userid));
            for (XHook hook : targets) {
                List<String> collection = collections.get(userid);
                if (anyCollection && !collection.contains(hook.getCollection())) {
                    collection = new ArrayList<>(collection);
                    collection.add(hook.getCollection());
                }
                if (!assigned.contains(entry.getKey() + "/" + hook.getId()) &&
                        entry.getValue().contains(hook.getGroup()) &&
                        hook.isAvailable(packageName, collection)) {
                    Log.i(TAG, packageName + ":" + uid + "/" + hook.getId() + " added (restricted group)");
                    // Completing a restricted group does not make the app restricted recently
                    insertAssignment(d, packageName, uid, hook.getId(), -1);
                    count++;
                    apps.add(entry.getKey());
                }
            }
        }
        return new int[]{count, apps.size()};
    }

    // Hooks installed by the user are assigned to the apps of the user which restrict their group
    private static Bundle assignRestricting(Bundle extras) throws Throwable {
        final List<String> hookids = extras.getStringArrayList("hooks");
        final int userid = Util.getUserId(Binder.getCallingUid());
        final Map<String, XHook> all = hooks;
        final List<XHook> targets = new ArrayList<>();
        for (String id : hookids)
            if (all.containsKey(id))
                targets.add(all.get(id));
        int[] counts = db.write(d -> assignToRestricting(d, all, targets, userid, true));
        Log.i(TAG, "Assigned hooks=" + hookids + " assignments=" + counts[0] + " apps=" + counts[1]);

        Bundle result = new Bundle();
        result.putInt("apps", counts[1]);
        return result;
    }

    private static List<String> readCollection(SQLiteDatabase d, int userid) {
        return Util.parseCollection(readSetting(d, userid, "global", "collection"));
    }

    // Apps and restrictions

    // Installed apps of the user of the caller, optionally one package only
    private static Cursor getApps(Context context, String[] selection) throws Throwable {
        final String only = (selection != null && selection.length == 1 ? selection[0] : null);
        final Map<String, XApp> apps = new HashMap<>();

        int cuid = Binder.getCallingUid();
        final int userid = Util.getUserId(cuid);

        // Access package manager as system user
        long ident = Binder.clearCallingIdentity();
        try {
            // System server ("android") is shared by all users and uses the restrictions of the owner,
            // see XCore.hookAndroid, so other users cannot restrict it
            PackageManager pm = Util.createContextForUser(context, userid).getPackageManager();
            // One app (like its screen) is looked up, not found among all installed packages
            List<PackageInfo> packages;
            if (only == null)
                packages = Util.getInstalledPackages(pm, 0);
            else
                try {
                    packages = Collections.singletonList(Util.getPackageInfo(pm, only, 0));
                } catch (PackageManager.NameNotFoundException ignored) {
                    packages = Collections.emptyList();
                }
            for (PackageInfo pi : packages)
                if (pi.applicationInfo != null &&
                        !BuildConfig.APPLICATION_ID.equals(pi.packageName) &&
                        !(userid != 0 && "android".equals(pi.packageName)))
                    try {
                        ApplicationInfo ai = pi.applicationInfo;
                        boolean persistent = ((ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0 ||
                                "android".equals(ai.packageName));
                        boolean system = ((ai.flags &
                                (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);

                        XApp app = new XApp();
                        app.uid = ai.uid;
                        app.packageName = ai.packageName;
                        app.icon = ai.icon;
                        // A label with styling is not a String
                        app.label = pm.getApplicationLabel(ai).toString();
                        app.persistent = persistent;
                        app.system = system;
                        app.forceStop = (!persistent && !system);
                        app.installed = pi.firstInstallTime;
                        app.assignments = new ArrayList<>();
                        apps.put(app.packageName, app);
                    } catch (Throwable ex) {
                        Log.e(TAG, Log.getStackTraceString(ex));
                    }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        Log.i(TAG, "Installed apps=" + apps.size() + " cuid=" + cuid);

        final List<String> collection = getCollection(userid);
        final Map<String, XHook> all = hooks;
        db.read(d -> {
            // Force stop settings
            try (Cursor cursor = d.query("setting", new String[]{"category", "value"},
                    "user = ? AND name = 'forcestop'", new String[]{Integer.toString(userid)},
                    null, null, null)) {
                while (cursor.moveToNext()) {
                    XApp app = apps.get(cursor.getString(0));
                    if (app != null)
                        app.forceStop = Boolean.parseBoolean(cursor.getString(1));
                }
            }

            // Assigned hooks, of the one package only, which the index finds without reading all rows of the user
            String[] range = getUidRange(userid);
            try (Cursor cursor = d.query("assignment",
                    new String[]{"package", "uid", "hook", "installed", "used", "restricted", "exception", "assigned"},
                    only == null ? WHERE_USER : WHERE_USER + " AND package = ?",
                    only == null ? range : new String[]{range[0], range[1], only}, null, null, null)) {
                while (cursor.moveToNext()) {
                    String pkg = cursor.getString(0);
                    XApp app = apps.get(pkg);
                    if (app == null || app.uid != cursor.getInt(1))
                        continue;
                    String hookid = cursor.getString(2);
                    XHook hook = all.get(hookid);
                    if (hook != null && hook.isAvailable(pkg, collection)) {
                        XAssignment assignment = new XAssignment(hook);
                        assignment.installed = cursor.getLong(3);
                        assignment.used = cursor.getLong(4);
                        assignment.restricted = (cursor.getInt(5) == 1);
                        assignment.exception = cursor.getString(6);
                        assignment.assigned = (cursor.isNull(7) ? -1 : cursor.getLong(7));
                        app.assignments.add(assignment);
                    } else if (hook == null && BuildConfig.DEBUG)
                        Log.w(TAG, "Hook " + hookid + " not found (assignment)");
                }
            }
            return null;
        });

        MatrixCursor result = new MatrixCursor(new String[]{"blob"});
        for (XApp app : apps.values())
            result.newRow().add(app.marshall());
        return result;
    }

    private static Bundle assignHooks(Context context, Bundle extras) throws Throwable {
        final List<String> hookids = extras.getStringArrayList("hooks");
        final String packageName = extras.getString("packageName");
        final int uid = extras.getInt("uid");
        final boolean delete = extras.getBoolean("delete");
        boolean kill = extras.getBoolean("kill");
        // Restoring a backup does not make apps restricted recently,
        // and keeps the assignments the app has already, with their usage data
        final boolean restore = extras.getBoolean("restore");
        final long now = (restore ? -1 : System.currentTimeMillis());

        // Like a restored backup of another user, see getApps
        if (!delete && "android".equals(packageName) && Util.getUserId(uid) != 0) {
            Log.w(TAG, packageName + ":" + uid + " not assigned, system server uses the restrictions of the owner");
            return new Bundle();
        }

        final Map<String, XHook> all = hooks;
        int changed = db.write(d -> {
            int rows = 0;
            Set<String> existing = new HashSet<>();
            Set<String> stored = new HashSet<>();
            if (restore) {
                try (Cursor cursor = d.query("assignment", new String[]{"hook"}, WHERE_APP,
                        new String[]{packageName, Integer.toString(uid)}, null, null, null)) {
                    while (cursor.moveToNext())
                        existing.add(cursor.getString(0));
                }
                try (Cursor cursor = d.query("hook", new String[]{"id"}, null, null, null, null, null)) {
                    while (cursor.moveToNext())
                        stored.add(cursor.getString(0));
                }
            }

            List<String> groups = new ArrayList<>();
            for (String hookid : hookids) {
                if (existing.contains(hookid))
                    continue;

                // A backup can have definitions which this device does not have, like removed built-in ones,
                // which would be dead rows, see removeObsoleteAssignments
                if (restore && !all.containsKey(hookid) && !stored.contains(hookid)) {
                    Log.i(TAG, packageName + ":" + uid + "/" + hookid + " not restored, unknown hook");
                    continue;
                }

                XHook hook = all.get(hookid);
                if (hook != null && !groups.contains(hook.getGroup()))
                    groups.add(hook.getGroup());

                if (delete) {
                    Log.i(TAG, packageName + ":" + uid + "/" + hookid + " deleted");
                    rows += d.delete("assignment", WHERE_ASSIGNMENT,
                            new String[]{packageName, Integer.toString(uid), hookid});
                } else {
                    Log.i(TAG, packageName + ":" + uid + "/" + hookid + " added");
                    insertAssignment(d, packageName, uid, hookid, now);
                    rows++;
                }
            }

            // Notify about usage again
            if (!delete)
                for (String group : groups)
                    d.delete("`group`", WHERE_GROUP, new String[]{packageName, Integer.toString(uid), group});
            return rows;
        });

        // An app of which nothing changed, like at a second restore of a backup, keeps running
        if (kill && changed > 0)
            forceStop(context, packageName, Util.getUserId(uid));

        Bundle result = new Bundle();
        result.putInt("changed", changed);
        return result;
    }

    // Assigned: the time of a restriction by the user or of a new app, -1 for an automatic addition
    private static void insertAssignment(SQLiteDatabase d, String packageName, int uid, String hookid, long assigned) throws Throwable {
        ContentValues cv = new ContentValues();
        cv.put("package", packageName);
        cv.put("uid", uid);
        cv.put("hook", hookid);
        cv.put("assigned", assigned);
        cv.put("installed", -1);
        cv.put("used", -1);
        cv.put("restricted", 0);
        cv.putNull("exception");
        if (d.insertWithOnConflict("assignment", null, cv, SQLiteDatabase.CONFLICT_REPLACE) < 0)
            throw new Throwable("Error inserting assignment");
    }

    private static Cursor getAssignedHooks(Context context, String[] selection, boolean permitted) throws Throwable {
        if (selection == null || selection.length != 2)
            throw new IllegalArgumentException("selection invalid");

        final String packageName = selection[0];
        int uid = Integer.parseInt(selection[1]);
        if (!permitted)
            enforceCallerOrPermission(context, uid);

        final List<String> collection = getCollection(Util.getUserId(uid));
        final Map<String, XHook> all = hooks;
        // Many hooks share a script, like the dozens of hooks of settings, so each script is sent once only,
        // with the first hook which has it; each row tells the number of its script, see Data.getAssignedHooks
        final MatrixCursor result = new MatrixCursor(new String[]{"blob", "lua", "script"});
        final Map<String, Integer> scripts = new HashMap<>();
        db.read(d -> {
            try (Cursor cursor = d.query("assignment", new String[]{"hook"},
                    WHERE_APP, new String[]{packageName, Integer.toString(uid)},
                    null, null, "hook")) {
                while (cursor.moveToNext()) {
                    String hookid = cursor.getString(0);
                    XHook hook = all.get(hookid);
                    if (hook == null) {
                        if (BuildConfig.DEBUG)
                            Log.w(TAG, "Hook " + hookid + " not found");
                    } else if (hook.isAvailable(packageName, collection)) {
                        Integer script = scripts.get(hook.getLuaScript());
                        boolean first = (script == null);
                        if (first) {
                            script = scripts.size();
                            scripts.put(hook.getLuaScript(), script);
                        }
                        result.addRow(new Object[]{hook.marshall(false), first ? hook.getLuaScript() : null, script});
                    }
                }
            }
            return null;
        });
        return result;
    }

    // Usage and errors reported by the hooks of an app
    private static Bundle report(Context context, Bundle extras) throws Throwable {
        final String hookid = extras.getString("hook");
        final String packageName = extras.getString("packageName");
        final int uid = extras.getInt("uid");
        int userid = Util.getUserId(uid);
        String event = extras.getString("event");
        final long time = extras.getLong("time");
        final Bundle data = extras.getBundle("data");

        if (uid != Binder.getCallingUid())
            throw new SecurityException();
        // Any app can call, see call
        if (hookid == null || packageName == null || event == null || data == null)
            throw new IllegalArgumentException("report invalid");
        final int restricted = data.getInt("restricted", 0);

        // The data has the real values, like an IMEI, which release builds do not write to the system log
        Log.i(TAG, "Hook " + hookid + " pkg=" + packageName + ":" + uid + " event=" + event +
                (BuildConfig.DEBUG ? toString(data) : ""));

        final XHook hook = hooks.get(hookid);

        // A failing hook can fail on each call, so only its first error since the start is notified
        final boolean[] firstError = new boolean[]{false};

        boolean notify = Boolean.parseBoolean(getSettingValue(userid, packageName, "notify"));
        final boolean use = "use".equals(event);
        final boolean restrictedUse = (hook != null && use && restricted == 1);
        final boolean countGroup = (restrictedUse && notify);

        // Last use of the group before this use, -1 for the first use,
        // null when the app has no such assignment, like for a package of another app
        // An error keeps the last restriction of the hook, so the log keeps showing it:
        // the time of an error is stored as installed, which is the time of the last error
        final boolean error = data.containsKey("exception");
        final String[] where = new String[]{packageName, Integer.toString(uid), hookid};
        Long used = db.write(d -> {
            ContentValues cv = new ContentValues();
            if ("install".equals(event) || (use && error))
                cv.put("installed", time);
            else if (use) {
                cv.put("used", time);
                cv.put("restricted", restricted);
            }
            for (String key : new String[]{"exception", "old", "new"})
                if (data.containsKey(key))
                    cv.put(key, limit(data.getString(key), "exception".equals(key) ? MAX_EXCEPTION : MAX_VALUE));
            if (error)
                try (Cursor cursor = d.query("assignment", new String[]{"exception"},
                        WHERE_ASSIGNMENT, where, null, null, null)) {
                    firstError[0] = (cursor.moveToNext() && cursor.isNull(0));
                }
            if (cv.size() == 0 || d.update("assignment", cv, WHERE_ASSIGNMENT, where) != 1)
                return null;

            long last = -1;
            if (countGroup) {
                try (Cursor cursor = d.query("`group`", new String[]{"used"}, WHERE_GROUP,
                        new String[]{packageName, Integer.toString(uid), hook.getGroup()}, null, null, null)) {
                    if (cursor.moveToNext())
                        last = cursor.getLong(0);
                }

                cv.clear();
                cv.put("package", packageName);
                cv.put("uid", uid);
                cv.put("name", hook.getGroup());
                cv.put("used", time);
                if (d.insertWithOnConflict("`group`", null, cv, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                    throw new Throwable("Error inserting group");
            }
            return last;
        });

        if (used == null) {
            Log.w(TAG, "No assignment " + packageName + ":" + uid + "/" + hookid);
            return new Bundle();
        }

        long ident = Binder.clearCallingIdentity();
        try {
            Context ctx = Util.createContextForUser(context, userid);

            // The first use of a group, or each use for hooks which notify
            if (restrictedUse && (hook.doNotify() || (notify && used < 0)))
                XNotify.notifyUsage(ctx, userid, uid, packageName, hook.getGroup(), hookid);

            if (firstError[0])
                XNotify.notifyException(ctx, userid, uid, packageName, hookid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return new Bundle();
    }

    // Any app can report for itself, so the text stored in the database of system server is limited
    private static final int MAX_EXCEPTION = 16384;
    private static final int MAX_VALUE = 1024;

    private static String limit(String text, int length) {
        return (text == null || text.length() <= length ? text : text.substring(0, length) + "…");
    }

    @SuppressWarnings("deprecation") // Bundle.get: logging values of any type
    private static String toString(Bundle data) {
        StringBuilder sb = new StringBuilder();
        for (String key : data.keySet())
            sb.append(' ').append(key).append('=').append(data.get(key));
        return sb.toString();
    }

    // All assignments of the apps of the user of the caller, also of hooks which are not available, for backups
    private static Cursor getAssignments() throws Throwable {
        final String[] range = getUidRange(Util.getUserId(Binder.getCallingUid()));
        return db.read(d -> {
            Cursor cursor = d.query("assignment", new String[]{"package", "uid", "hook"},
                    WHERE_USER, range, null, null, "package, hook");
            // Fill the cursor window while holding the lock
            cursor.getCount();
            return cursor;
        });
    }

    // The last restriction of each hook of the apps of the user of the caller
    private static Cursor getLog(String[] selection) throws Throwable {
        if (selection != null)
            throw new IllegalArgumentException("selection invalid");

        final String[] range = getUidRange(Util.getUserId(Binder.getCallingUid()));
        return db.read(d -> {
            Cursor cursor = d.query("assignment",
                    new String[]{"package", "uid", "hook", "used", "old", "new"},
                    "restricted = 1 AND " + WHERE_USER, range,
                    null, null, "used DESC");
            // Fill the cursor window while holding the lock
            cursor.getCount();
            return cursor;
        });
    }

    // A new app, see ReceiverPackage, which is not running yet
    private static Bundle initApp(Bundle extras) throws Throwable {
        final String packageName = extras.getString("packageName");
        final int uid = extras.getInt("uid");
        int userid = Util.getUserId(uid);

        List<String> collection = getCollection(userid);
        final List<String> hookids = new ArrayList<>();
        for (XHook hook : hooks.values())
            if (hook.isAvailable(packageName, collection))
                hookids.add(hook.getId());

        final long now = System.currentTimeMillis();
        db.write(d -> {
            for (String hookid : hookids)
                insertAssignment(d, packageName, uid, hookid, now);
            return null;
        });

        Log.i(TAG, "Init app pkg=" + packageName + " uid=" + uid + " assignments=" + hookids.size());
        return new Bundle();
    }

    // An app which is installed or removed, see ReceiverPackage
    private static Bundle clearApp(Bundle extras) throws Throwable {
        final String packageName = extras.getString("packageName");
        final int uid = extras.getInt("uid");
        final boolean full = extras.getBoolean("settings", false);
        final int userid = Util.getUserId(uid);

        final String[] where = new String[]{packageName, Integer.toString(uid)};
        long[] counts = db.write(d -> {
            // Last use of the groups, so the first use is notified again
            d.delete("`group`", WHERE_APP, where);
            return new long[]{
                    d.delete("assignment", WHERE_APP, where),
                    full ? d.delete("setting", "user = ? AND category = ?",
                            new String[]{Integer.toString(userid), packageName}) : 0
            };
        });

        Log.i(TAG, "Cleared app pkg=" + packageName + " uid=" + uid +
                " assignments=" + counts[0] + " settings=" + counts[1]);
        return new Bundle();
    }

    // Restrictions and settings of a user, or of all users
    private static Bundle clearData(Bundle extras) throws Throwable {
        final int userid = extras.getInt("user");
        final boolean all = extras.getBoolean("all");
        Log.i(TAG, "Clearing data user=" + userid + " all=" + all);

        db.write(d -> {
            if (all) {
                d.delete("assignment", null, null);
                d.delete("`group`", null, null);
                // The built-in hooks known to the service (user -1) are not data of a user
                d.delete("setting", "user >= 0", null);
                // Custom definitions are shared by all users
                d.delete("hook", null, null);
            } else {
                String[] range = getUidRange(userid);
                d.delete("assignment", WHERE_USER, range);
                d.delete("`group`", WHERE_USER, range);
                d.delete("setting", "user = ?", new String[]{Integer.toString(userid)});
            }
            return null;
        });

        if (all)
            synchronized (lock) {
                hooks = new HashMap<>(builtins);
            }

        return new Bundle();
    }

    private static void forceStop(Context context, String packageName, int userid) throws Throwable {
        // Access activity manager as system user
        long ident = Binder.clearCallingIdentity();
        try {
            // public void forceStopPackageAsUser(String packageName, int userId)
            ActivityManager am = context.getSystemService(ActivityManager.class);
            Method mForceStop = am.getClass().getMethod("forceStopPackageAsUser", String.class, int.class);
            mForceStop.invoke(am, packageName, userid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // Settings

    private static List<String> getCollection(int userid) throws Throwable {
        return db.read(d -> readCollection(d, userid));
    }

    private static String getSettingValue(final int userid, final String category, final String name) throws Throwable {
        String value = db.read(d -> readSetting(d, userid, category, name));
        if (BuildConfig.DEBUG)
            Log.d(TAG, "Get setting " + userid + ":" + category + ":" + name + "=" + value);
        return value;
    }

    // The value of a setting, null when it is not set
    private static String readSetting(SQLiteDatabase d, int userid, String category, String name) {
        try (Cursor cursor = d.query("setting", new String[]{"value"}, WHERE_SETTING,
                new String[]{Integer.toString(userid), category, name}, null, null, null)) {
            return (cursor.moveToNext() && !cursor.isNull(0) ? cursor.getString(0) : null);
        }
    }

    private static Bundle getSetting(Bundle extras) throws Throwable {
        Bundle result = new Bundle();
        result.putString("value", getSettingValue(
                extras.getInt("user"), extras.getString("category"), extras.getString("name")));
        return result;
    }

    // A null value deletes the setting; kill force stops the package of the category
    private static Bundle putSetting(Context context, Bundle extras) throws Throwable {
        final int userid = extras.getInt("user");
        final String category = extras.getString("category");
        final String name = extras.getString("name");
        final String value = extras.getString("value");
        boolean kill = extras.getBoolean("kill", false);
        Log.i(TAG, "Put setting " + userid + ":" + category + " " + name + "=" + value);

        db.write(d -> {
            writeSetting(d, userid, category, name, value);
            return null;
        });

        if (kill)
            forceStop(context, category, userid);

        return new Bundle();
    }

    // A null value deletes the setting
    private static void writeSetting(SQLiteDatabase d, int userid, String category, String name, String value)
            throws Throwable {
        if (value == null)
            d.delete("setting", WHERE_SETTING, new String[]{Integer.toString(userid), category, name});
        else {
            ContentValues cv = new ContentValues();
            cv.put("user", userid);
            cv.put("category", category);
            cv.put("name", name);
            cv.put("value", value);
            if (d.insertWithOnConflict("setting", null, cv, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                throw new Throwable("Error inserting setting");
        }
    }

    // Selection arguments for the uids of the apps of a user
    private static String[] getUidRange(int userid) {
        return new String[]{
                Integer.toString(Util.getUserUid(userid, 0)),
                Integer.toString(Util.getUserUid(userid, Process.LAST_APPLICATION_UID))};
    }

    // Settings of a category (global or a package name) of an app
    private static Cursor getSettings(Context context, String[] selection, boolean permitted) throws Throwable {
        if (selection == null || selection.length != 2)
            throw new IllegalArgumentException("selection invalid");

        final String category = selection[0];
        int uid = Integer.parseInt(selection[1]);
        if (!permitted) {
            enforceCallerOrPermission(context, uid);
            // Apps read the settings for all apps and of their own packages only
            if (!"global".equals(category) && !isPackageOf(context, category, uid))
                enforcePermission(context);
        }
        MatrixCursor result = new MatrixCursor(new String[]{"name", "value"});
        for (Map.Entry<String, String> entry : readSettings(Util.getUserId(uid), category).entrySet())
            result.addRow(new String[]{entry.getKey(), entry.getValue()});
        return result;
    }

    // The fake identifiers of an app which are new each boot, see FakeIds; apps read their own only, like their settings,
    // so apps sharing a user id read each other's, and the values for all apps are the same for every app.
    // The secret they are derived from is the service's own, new each boot, which no app can read.
    private static Cursor getBootValues(Context context, String[] selection, boolean permitted) throws Throwable {
        if (selection == null || selection.length != 2)
            throw new IllegalArgumentException("selection invalid");

        final String packageName = selection[0];
        int uid = Integer.parseInt(selection[1]);
        if (!permitted) {
            enforceCallerOrPermission(context, uid);
            if (!isPackageOf(context, packageName, uid))
                enforcePermission(context);
        }
        final int userid = Util.getUserId(uid);

        Map<String, String> global = readSettings(userid, "global");
        Map<String, String> app = readSettings(userid, packageName);
        MatrixCursor result = new MatrixCursor(new String[]{"name", "value"});
        for (Map.Entry<String, String> entry : FakeIds.getBootValues(getBootSecret(), userid, packageName, global, app).entrySet())
            result.addRow(new String[]{entry.getKey(), entry.getValue()});
        return result;
    }

    private static Map<String, String> readSettings(int userid, String category) throws Throwable {
        return db.read(d -> {
            Map<String, String> result = new HashMap<>();
            try (Cursor cursor = d.query("setting", new String[]{"name", "value"},
                    "user = ? AND category = ?", new String[]{Integer.toString(userid), category},
                    null, null, null)) {
                while (cursor.moveToNext())
                    result.put(cursor.getString(0), cursor.getString(1));
            }
            return result;
        });
    }

    // Made once per start of system server, so once per boot
    private static synchronized byte[] getBootSecret() {
        if (bootSecret == null) {
            bootSecret = new byte[32];
            new SecureRandom().nextBytes(bootSecret);
        }
        return bootSecret;
    }

    // All settings of the user of the caller, for backup
    private static Cursor getAllSettings() throws Throwable {
        final int userid = Util.getUserId(Binder.getCallingUid());
        return db.read(d -> {
            MatrixCursor result = new MatrixCursor(new String[]{"category", "name", "value"});
            try (Cursor cursor = d.query("setting", new String[]{"category", "name", "value"},
                    "user = ?", new String[]{Integer.toString(userid)},
                    null, null, "category, name")) {
                while (cursor.moveToNext())
                    result.addRow(new String[]{cursor.getString(0), cursor.getString(1), cursor.getString(2)});
            }
            return result;
        });
    }

    // Permissions

    private static boolean isPackageOf(Context context, String packageName, int uid) {
        String[] packages = getPackagesForUid(context, uid);
        return (packages != null && Arrays.asList(packages).contains(packageName));
    }

    // Access package manager as system user
    private static String[] getPackagesForUid(Context context, int uid) {
        long ident = Binder.clearCallingIdentity();
        try {
            return context.getPackageManager().getPackagesForUid(uid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // The service answers system server, the app and apps with assignments, which are hooked. Other apps get
    // the answers of a device without Doppel, so they cannot detect it nor read the values of settings.
    // Apps with assignments can detect it: their own code can ask for their hooks and the global settings,
    // like the hooks in their process do, which the service cannot tell apart.
    // Assignments of a removed package do not count, its uid can be of another app now.
    static int answers(Context context) throws RemoteException {
        loadData(context);
        int cuid = Binder.getCallingUid();
        try {
            // System server and the app first: system server has the assignments of Android too
            if (hasPermission(context))
                return CALLER_PERMITTED;

            String[] packages = getPackagesForUid(context, cuid);
            if (packages != null && packages.length > 0) {
                final List<String> args = new ArrayList<>();
                args.add(Integer.toString(cuid));
                args.addAll(Arrays.asList(packages));
                final String where = "uid = ? AND package IN (" +
                        TextUtils.join(",", Collections.nCopies(packages.length, "?")) + ")";
                boolean assigned = db.read(d -> {
                    try (Cursor cursor = d.query("assignment", new String[]{"uid"}, where,
                            args.toArray(new String[0]), null, null, null, "1")) {
                        return cursor.moveToFirst();
                    }
                });
                if (assigned)
                    return CALLER_ASSIGNED;
            }

            return CALLER_NONE;
        } catch (Throwable ex) {
            throw failed(ex);
        }
    }

    // Whether the caller is system server or the app; also for getVersion, see XCore.hookSettings
    static boolean hasPermission(Context context) {
        try {
            enforcePermission(context);
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    // Hooked apps may read their own data only
    private static void enforceCallerOrPermission(Context context, int uid) throws SecurityException {
        if (Binder.getCallingUid() != uid)
            enforcePermission(context);
    }

    // System server and the app, for any user: a package name belongs to one app on the device.
    // Apps signed with the same key are not let in, the companion app of XPrivacyLua which they were for is gone.
    private static void enforcePermission(Context context) throws SecurityException {
        int callingUid = Binder.getCallingUid();
        if (Util.getAppId(callingUid) == Process.SYSTEM_UID ||
                isPackageOf(context, BuildConfig.APPLICATION_ID, callingUid))
            return;
        throw new SecurityException("Permission error cuid=" + callingUid);
    }
}
