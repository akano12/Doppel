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

import android.app.ActivityManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.StrictMode;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
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
    private final static String TAG = "XLua.Provider";

    private final static Object lock = new Object();

    private static XDatabase db = null;
    private static Map<String, XHook> hooks = null;
    private static Map<String, XHook> builtins = null;

    static Uri getURI() {
        return Settings.System.CONTENT_URI;
    }

    private static void loadData(Context context) throws RemoteException {
        try {
            synchronized (lock) {
                if (db == null)
                    db = XDatabase.open();
                if (hooks == null)
                    loadHooks(context);
            }
        } catch (Throwable ex) {
            Log.e(TAG, Log.getStackTraceString(ex));
            throw new RemoteException(ex.getMessage());
        }
    }

    static Bundle call(Context context, String method, Bundle extras) throws RemoteException, IllegalArgumentException {
        loadData(context);

        StrictMode.ThreadPolicy originalPolicy = StrictMode.getThreadPolicy();
        try {
            StrictMode.allowThreadDiskReads();
            StrictMode.allowThreadDiskWrites();

            // Apps report their own usage, see report
            if (!"report".equals(method))
                enforcePermission(context);

            switch (method) {
                case "putHook":
                    return putHook(context, extras);
                case "assignHooks":
                    return assignHooks(context, extras);
                case "report":
                    return report(context, extras);
                case "getSetting":
                    return getSetting(extras);
                case "putSetting":
                    return putSetting(context, extras);
                case "initApp":
                    return initApp(context, extras);
                case "clearApp":
                    return clearApp(context, extras);
                case "clearData":
                    return clearData(extras);
                case "forceStop":
                    forceStop(context, extras.getString("packageName"), Util.getUserId(extras.getInt("uid")));
                    return new Bundle();
                default:
                    return null;
            }
        } catch (IllegalArgumentException | SecurityException | RemoteException ex) {
            throw ex;
        } catch (Throwable ex) {
            Log.e(TAG, Log.getStackTraceString(ex));
            throw new RemoteException(ex.getMessage());
        } finally {
            StrictMode.setThreadPolicy(originalPolicy);
        }
    }

    // Methods ending in 2 return parcels
    static Cursor query(Context context, String method, String[] selection) throws RemoteException {
        loadData(context);

        Cursor result;
        StrictMode.ThreadPolicy originalPolicy = StrictMode.getThreadPolicy();
        try {
            StrictMode.allowThreadDiskReads();
            StrictMode.allowThreadDiskWrites();

            // Apps read their own restrictions and settings
            switch (method) {
                case "getAssignedHooks2":
                case "getSettings":
                    break;
                default:
                    enforcePermission(context);
            }

            switch (method) {
                case "getHooks2":
                    result = getHooks(selection);
                    break;
                case "getApps2":
                    result = getApps(context, selection);
                    break;
                case "getAssignedHooks2":
                    result = getAssignedHooks(context, selection);
                    break;
                case "getAssignments":
                    result = getAssignments();
                    break;
                case "getSettings":
                    result = getSettings(context, selection);
                    break;
                case "getAllSettings":
                    result = getAllSettings();
                    break;
                case "getLog":
                    result = getLog(selection);
                    break;
                default:
                    result = null;
            }
        } catch (SecurityException | RemoteException ex) {
            throw ex;
        } catch (Throwable ex) {
            Log.e(TAG, Log.getStackTraceString(ex));
            throw new RemoteException(ex.getMessage());
        } finally {
            StrictMode.setThreadPolicy(originalPolicy);
        }

        if (result != null)
            result.moveToPosition(-1);
        return result;
    }

    // Hook definitions

    private static Bundle putHook(Context context, Bundle extras) throws Throwable {
        final String id = extras.getString("id");
        String definition = extras.getString("definition");
        if (id == null)
            throw new IllegalArgumentException("id missing");

        final XHook hook = (definition == null ? null : XHook.fromJSON(definition));
        if (hook != null) {
            hook.validate();
            if (!id.equals(hook.getId()))
                throw new IllegalArgumentException("id mismatch");
        }

        // Cache hook
        synchronized (lock) {
            if (hook == null) {
                if (hooks.containsKey(id) && hooks.get(id).isBuiltin())
                    throw new IllegalArgumentException("builtin");
                Log.i(TAG, "Deleting hook id=" + id);
                hooks.remove(id);
                if (builtins.containsKey(id)) {
                    Log.i(TAG, "Restoring builtin id=" + id);
                    // The class name is resolved already
                    hooks.put(id, builtins.get(id));
                } else
                    Log.w(TAG, "Builtin not found id=" + id);
            } else {
                hook.resolveClassName(context);
                if (hook.isBuiltin()) {
                    // Refresh the built-in definition, but keep a custom definition replacing it
                    builtins.put(id, hook);
                    if (!hooks.containsKey(id) || hooks.get(id).isBuiltin())
                        hooks.put(id, hook);
                } else {
                    Log.i(TAG, "Storing hook id=" + id);
                    hooks.put(id, hook);
                }
            }
        }

        // Persist custom definitions, built-in definitions are part of the app
        if (hook == null || !hook.isBuiltin())
            db.write(d -> {
                if (hook == null) {
                    if (d.delete("hook", "id = ?", new String[]{id}) < 0)
                        throw new Throwable("Error deleting hook");
                } else {
                    ContentValues cv = new ContentValues();
                    cv.put("id", id);
                    cv.put("definition", hook.toJSON());
                    if (d.insertWithOnConflict("hook", null, cv, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                        throw new Throwable("Error inserting hook");
                }
                return null;
            });

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
        synchronized (lock) {
            for (XHook hook : hooks.values())
                if (all || hook.isAvailable(null, collection))
                    hv.add(hook);
        }
        Collections.sort(hv, (h1, h2) -> h1.getId().compareTo(h2.getId()));

        MatrixCursor result = new MatrixCursor(new String[]{"blob"});
        for (XHook hook : hv)
            result.addRow(new Object[]{hook.marshall(lua)});
        return result;
    }

    // The definitions are kept only when all could be loaded, so a failure is retried on the next call
    private static void loadHooks(Context context) throws Throwable {
        final Map<String, XHook> hooks = new HashMap<>();
        final Map<String, XHook> builtins = new HashMap<>();

        // Built-in definitions
        PackageManager pm = context.getPackageManager();
        ApplicationInfo ai = pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0);
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

        // Restrictions keep working without the new assignments
        try {
            assignNewBuiltins();
        } catch (Throwable ex) {
            Log.e(TAG, Log.getStackTraceString(ex));
        }
    }

    // Built-in hooks added by an update are assigned to the apps which restrict their group,
    // like the screens of the app assign all hooks of a group
    private static void assignNewBuiltins() throws Throwable {
        final String[] where = new String[]{"-1", "service", "builtins"};
        db.write(d -> {
            Set<String> known = new HashSet<>();
            try (Cursor cursor = d.query("setting", new String[]{"value"},
                    "user = ? AND category = ? AND name = ?", where, null, null, null)) {
                if (cursor.moveToNext() && !cursor.isNull(0))
                    known.addAll(Arrays.asList(cursor.getString(0).split(",")));
            }

            List<String> added = new ArrayList<>();
            for (String id : builtins.keySet())
                if (!known.contains(id))
                    added.add(id);

            if (!added.isEmpty()) {
                // Groups restricted per app
                Map<String, Set<String>> restricted = new HashMap<>();
                Set<String> assigned = new HashSet<>();
                try (Cursor cursor = d.query("assignment", new String[]{"package", "uid", "hook"},
                        null, null, null, null, null)) {
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
                for (Map.Entry<String, Set<String>> entry : restricted.entrySet()) {
                    int uid = Integer.parseInt(entry.getKey().substring(0, entry.getKey().indexOf(':')));
                    String packageName = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
                    int userid = Util.getUserId(uid);
                    if (!collections.containsKey(userid))
                        collections.put(userid, readCollection(d, userid));
                    for (String id : added) {
                        // A custom definition can replace the built-in definition
                        XHook hook = hooks.get(id);
                        // Replacing an assignment would reset its usage data
                        if (hook != null && !assigned.contains(entry.getKey() + "/" + id) &&
                                entry.getValue().contains(hook.getGroup()) &&
                                hook.isAvailable(packageName, collections.get(userid))) {
                            Log.i(TAG, packageName + ":" + uid + "/" + id + " added (new built-in)");
                            insertAssignment(d, packageName, uid, id);
                            count++;
                        }
                    }
                }
                Log.i(TAG, "New built-in hooks=" + added.size() + " assigned=" + count);
            }

            if (!added.isEmpty() || known.size() != builtins.size())
                writeSetting(d, -1, "service", "builtins", TextUtils.join(",", builtins.keySet()));
            return null;
        });
    }

    private static List<String> readCollection(SQLiteDatabase d, int userid) {
        try (Cursor cursor = d.query("setting", new String[]{"value"},
                "user = ? AND category = 'global' AND name = 'collection'",
                new String[]{Integer.toString(userid)}, null, null, null)) {
            return Util.parseCollection(cursor.moveToNext() && !cursor.isNull(0) ? cursor.getString(0) : null);
        }
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
            PackageManager pm = Util.createContextForUser(context, userid).getPackageManager();
            for (ApplicationInfo ai : pm.getInstalledApplications(0))
                if (!BuildConfig.APPLICATION_ID.equals(ai.packageName) &&
                        (only == null || only.equals(ai.packageName)))
                    try {
                        int esetting = pm.getApplicationEnabledSetting(ai.packageName);
                        boolean enabled = (ai.enabled &&
                                (esetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
                                        esetting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED));
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
                        app.enabled = enabled;
                        app.persistent = persistent;
                        app.system = system;
                        app.forceStop = (!persistent && !system);
                        app.assignments = new ArrayList<>();
                        apps.put(app.packageName, app);
                    } catch (Throwable ex) {
                        Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
                    }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        Log.i(TAG, "Installed apps=" + apps.size() + " cuid=" + cuid);

        final List<String> collection = getCollection(userid);
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

            // Assigned hooks
            try (Cursor cursor = d.query("assignment",
                    new String[]{"package", "uid", "hook", "installed", "used", "restricted", "exception"},
                    "uid >= ? AND uid <= ?", getUidRange(userid), null, null, null)) {
                while (cursor.moveToNext()) {
                    String pkg = cursor.getString(0);
                    XApp app = apps.get(pkg);
                    if (app == null || app.uid != cursor.getInt(1))
                        continue;
                    String hookid = cursor.getString(2);
                    synchronized (lock) {
                        XHook hook = hooks.get(hookid);
                        if (hook != null && hook.isAvailable(pkg, collection)) {
                            XAssignment assignment = new XAssignment(hook);
                            assignment.installed = cursor.getLong(3);
                            assignment.used = cursor.getLong(4);
                            assignment.restricted = (cursor.getInt(5) == 1);
                            assignment.exception = cursor.getString(6);
                            app.assignments.add(assignment);
                        } else if (hook == null && BuildConfig.DEBUG)
                            Log.w(TAG, "Hook " + hookid + " not found (assignment)");
                    }
                }
            }
            return null;
        });

        MatrixCursor result = new MatrixCursor(new String[]{"blob"});
        for (XApp app : apps.values()) {
            Parcel parcel = Parcel.obtain();
            app.writeToParcel(parcel, 0);
            result.newRow().add(parcel.marshall());
            parcel.recycle();
        }
        return result;
    }

    private static Bundle assignHooks(Context context, Bundle extras) throws Throwable {
        final List<String> hookids = extras.getStringArrayList("hooks");
        final String packageName = extras.getString("packageName");
        final int uid = extras.getInt("uid");
        final boolean delete = extras.getBoolean("delete");
        boolean kill = extras.getBoolean("kill");

        db.write(d -> {
            List<String> groups = new ArrayList<>();
            for (String hookid : hookids) {
                XHook hook;
                synchronized (lock) {
                    hook = hooks.get(hookid);
                }
                if (hook != null && !groups.contains(hook.getGroup()))
                    groups.add(hook.getGroup());

                if (delete) {
                    Log.i(TAG, packageName + ":" + uid + "/" + hookid + " deleted");
                    if (d.delete("assignment", "hook = ? AND package = ? AND uid = ?",
                            new String[]{hookid, packageName, Integer.toString(uid)}) < 0)
                        throw new Throwable("Error deleting assignment");
                } else {
                    Log.i(TAG, packageName + ":" + uid + "/" + hookid + " added");
                    insertAssignment(d, packageName, uid, hookid);
                }
            }

            // Notify about usage again
            if (!delete)
                for (String group : groups)
                    if (d.delete("`group`", "package = ? AND uid = ? AND name = ?",
                            new String[]{packageName, Integer.toString(uid), group}) < 0)
                        throw new Throwable("Error deleting group");
            return null;
        });

        if (kill)
            forceStop(context, packageName, Util.getUserId(uid));

        return new Bundle();
    }

    private static void insertAssignment(SQLiteDatabase d, String packageName, int uid, String hookid) throws Throwable {
        ContentValues cv = new ContentValues();
        cv.put("package", packageName);
        cv.put("uid", uid);
        cv.put("hook", hookid);
        cv.put("installed", -1);
        cv.put("used", -1);
        cv.put("restricted", 0);
        cv.putNull("exception");
        if (d.insertWithOnConflict("assignment", null, cv, SQLiteDatabase.CONFLICT_REPLACE) < 0)
            throw new Throwable("Error inserting assignment");
    }

    private static Cursor getAssignedHooks(Context context, String[] selection) throws Throwable {
        if (selection == null || selection.length != 2)
            throw new IllegalArgumentException("selection invalid");

        final String packageName = selection[0];
        int uid = Integer.parseInt(selection[1]);
        enforceCallerOrPermission(context, uid);

        final List<String> collection = getCollection(Util.getUserId(uid));
        final MatrixCursor result = new MatrixCursor(new String[]{"blob", "used"});
        db.read(d -> {
            try (Cursor cursor = d.query("assignment", new String[]{"hook", "used"},
                    "package = ? AND uid = ?", new String[]{packageName, Integer.toString(uid)},
                    null, null, "hook")) {
                while (cursor.moveToNext()) {
                    String hookid = cursor.getString(0);
                    synchronized (lock) {
                        XHook hook = hooks.get(hookid);
                        if (hook == null) {
                            if (BuildConfig.DEBUG)
                                Log.w(TAG, "Hook " + hookid + " not found");
                        } else if (hook.isAvailable(packageName, collection))
                            result.newRow()
                                    .add(hook.marshall(true))
                                    .add(cursor.getString(1));
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
        final int restricted = data.getInt("restricted", 0);

        if (uid != Binder.getCallingUid())
            throw new SecurityException();

        Log.i(TAG, "Hook " + hookid + " pkg=" + packageName + ":" + uid + " event=" + event + toString(data));

        final XHook hook;
        synchronized (lock) {
            hook = hooks.get(hookid);
        }

        // A failing hook can fail on each call, so only its first error since the start is notified
        final boolean[] firstError = new boolean[]{false};

        boolean notify = Boolean.parseBoolean(getSettingValue(userid, packageName, "notify"));
        final boolean use = "use".equals(event);
        final boolean countGroup = (hook != null && use && restricted == 1 && notify);

        // Last use of the group before this use, -1 for the first use,
        // null when the app has no such assignment, like for a package of another app
        Long used = db.write(d -> {
            ContentValues cv = new ContentValues();
            if ("install".equals(event))
                cv.put("installed", time);
            else if (use) {
                cv.put("used", time);
                cv.put("restricted", restricted);
            }
            for (String key : new String[]{"exception", "old", "new"})
                if (data.containsKey(key))
                    cv.put(key, data.getString(key));
            if (data.containsKey("exception"))
                try (Cursor cursor = d.query("assignment", new String[]{"exception"},
                        "package = ? AND uid = ? AND hook = ?",
                        new String[]{packageName, Integer.toString(uid), hookid}, null, null, null)) {
                    firstError[0] = (cursor.moveToNext() && cursor.isNull(0));
                }
            if (cv.size() == 0 || d.update("assignment", cv, "package = ? AND uid = ? AND hook = ?",
                    new String[]{packageName, Integer.toString(uid), hookid}) != 1)
                return null;

            long last = -1;
            if (countGroup) {
                try (Cursor cursor = d.query("`group`", new String[]{"used"},
                        "package = ? AND uid = ? AND name = ?",
                        new String[]{packageName, Integer.toString(uid), hook.getGroup()},
                        null, null, null)) {
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
            if (hook != null && use && restricted == 1 && (hook.doNotify() || (notify && used < 0)))
                XNotify.notifyUsage(ctx, userid, uid, packageName, hook.getGroup(), hookid);

            if (firstError[0])
                XNotify.notifyException(ctx, userid, uid, packageName, hookid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return new Bundle();
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
                    "uid >= ? AND uid <= ?", range, null, null, "package, hook");
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
                    "restricted = 1 AND uid >= ? AND uid <= ?", range,
                    null, null, "used DESC");
            // Fill the cursor window while holding the lock
            cursor.getCount();
            return cursor;
        });
    }

    private static Bundle initApp(Context context, Bundle extras) throws Throwable {
        final String packageName = extras.getString("packageName");
        final int uid = extras.getInt("uid");
        boolean kill = extras.getBoolean("kill", false);
        int userid = Util.getUserId(uid);

        List<String> collection = getCollection(userid);
        final List<String> hookids = new ArrayList<>();
        synchronized (lock) {
            for (XHook hook : hooks.values())
                if (hook.isAvailable(packageName, collection))
                    hookids.add(hook.getId());
        }

        db.write(d -> {
            for (String hookid : hookids)
                insertAssignment(d, packageName, uid, hookid);
            return null;
        });

        if (kill)
            forceStop(context, packageName, userid);

        Log.i(TAG, "Init app pkg=" + packageName + " uid=" + uid + " assignments=" + hookids.size());
        return new Bundle();
    }

    private static Bundle clearApp(Context context, Bundle extras) throws Throwable {
        final String packageName = extras.getString("packageName");
        final int uid = extras.getInt("uid");
        boolean kill = extras.getBoolean("kill", false);
        final boolean full = extras.getBoolean("settings", false);
        final int userid = Util.getUserId(uid);

        final String[] where = new String[]{packageName, Integer.toString(uid)};
        long[] counts = db.write(d -> {
            // Last use of the groups, so the first use is notified again
            d.delete("`group`", "package = ? AND uid = ?", where);
            return new long[]{
                    d.delete("assignment", "package = ? AND uid = ?", where),
                    full ? d.delete("setting", "user = ? AND category = ?",
                            new String[]{Integer.toString(userid), packageName}) : 0
            };
        });

        if (kill)
            forceStop(context, packageName, userid);

        Log.i(TAG, "Cleared app pkg=" + packageName + " uid=" + uid +
                " assignments=" + counts[0] + " settings=" + counts[1]);
        return new Bundle();
    }

    // Restrictions and settings of a user, of all users for the owner
    private static Bundle clearData(Bundle extras) throws Throwable {
        final int userid = extras.getInt("user");
        Log.i(TAG, "Clearing data user=" + userid);

        db.write(d -> {
            if (userid == 0) {
                d.delete("assignment", null, null);
                d.delete("`group`", null, null);
                // The built-in hooks known to the service (user -1) are not data of a user
                d.delete("setting", "user >= 0", null);
            } else {
                String[] range = getUidRange(userid);
                d.delete("assignment", "uid >= ? AND uid <= ?", range);
                d.delete("`group`", "uid >= ? AND uid <= ?", range);
                d.delete("setting", "user = ?", new String[]{Integer.toString(userid)});
            }
            return null;
        });

        return new Bundle();
    }

    private static void forceStop(Context context, String packageName, int userid) throws Throwable {
        // Access activity manager as system user
        long ident = Binder.clearCallingIdentity();
        try {
            // public void forceStopPackageAsUser(String packageName, int userId)
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            Method mForceStop = am.getClass().getMethod("forceStopPackageAsUser", String.class, int.class);
            mForceStop.invoke(am, packageName, userid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // Settings

    private static List<String> getCollection(int userid) throws Throwable {
        return Util.parseCollection(getSettingValue(userid, "global", "collection"));
    }

    private static String getSettingValue(final int userid, final String category, final String name) throws Throwable {
        String value = db.read(d -> {
            try (Cursor cursor = d.query("setting", new String[]{"value"},
                    "user = ? AND category = ? AND name = ?",
                    new String[]{Integer.toString(userid), category, name},
                    null, null, null)) {
                return (cursor.moveToNext() && !cursor.isNull(0) ? cursor.getString(0) : null);
            }
        });
        if (BuildConfig.DEBUG)
            Log.d(TAG, "Get setting " + userid + ":" + category + ":" + name + "=" + value);
        return value;
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
        if (value == null) {
            if (d.delete("setting", "user = ? AND category = ? AND name = ?",
                    new String[]{Integer.toString(userid), category, name}) < 0)
                throw new Throwable("Error deleting setting");
        } else {
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
    private static Cursor getSettings(Context context, String[] selection) throws Throwable {
        if (selection == null || selection.length != 2)
            throw new IllegalArgumentException("selection invalid");

        final String category = selection[0];
        int uid = Integer.parseInt(selection[1]);
        enforceCallerOrPermission(context, uid);
        // Apps read the settings for all apps and of their own packages only
        if (!"global".equals(category) && !isPackageOf(context, category, uid))
            enforcePermission(context);
        final int userid = Util.getUserId(uid);

        return db.read(d -> {
            MatrixCursor result = new MatrixCursor(new String[]{"name", "value"});
            try (Cursor cursor = d.query("setting", new String[]{"name", "value"},
                    "user = ? AND category = ?", new String[]{Integer.toString(userid), category},
                    null, null, null)) {
                while (cursor.moveToNext())
                    result.addRow(new String[]{cursor.getString(0), cursor.getString(1)});
            }
            return result;
        });
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
        long ident = Binder.clearCallingIdentity();
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return (packages != null && Arrays.asList(packages).contains(packageName));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // For calls handled outside this class
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

    // System server and the app
    private static void enforcePermission(Context context) throws SecurityException {
        int cuid = Util.getAppId(Binder.getCallingUid());

        // Access package manager as system user
        long ident = Binder.clearCallingIdentity();
        try {
            if (cuid == Process.SYSTEM_UID)
                return;

            // Same signature
            PackageManager pm = context.getPackageManager();
            int uid = pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid;
            if (pm.checkSignatures(cuid, uid) == PackageManager.SIGNATURE_MATCH)
                return;

            throw new SecurityException("Signature error cuid=" + cuid);
        } catch (SecurityException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new SecurityException("Signature check failed cuid=" + cuid, ex);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
