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

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.io.IOException;

// Database of the service in system server: hook assignments, settings and custom hook definitions
class XDatabase {
    private static final String TAG = "XLua.Database";

    interface Operation<T> {
        T run(SQLiteDatabase db) throws Throwable;
    }

    private interface Migration {
        void run(SQLiteDatabase db) throws Throwable;
    }

    // Migration n upgrades the database to version n + 1
    private static final Migration[] MIGRATIONS = new Migration[]{
            db -> {
                db.execSQL("CREATE TABLE assignment (package TEXT NOT NULL, uid INTEGER NOT NULL, hook TEXT NOT NULL," +
                        " installed INTEGER, used INTEGER, restricted INTEGER, exception TEXT)");
                db.execSQL("CREATE UNIQUE INDEX idx_assignment ON assignment(package, uid, hook)");
                db.execSQL("CREATE TABLE setting (user INTEGER, category TEXT NOT NULL, name TEXT NOT NULL, value TEXT)");
                db.execSQL("CREATE UNIQUE INDEX idx_setting ON setting(user, category, name)");
            },
            db -> {
                db.execSQL("CREATE TABLE hook (id TEXT NOT NULL, definition TEXT NOT NULL)");
                db.execSQL("CREATE UNIQUE INDEX idx_hook ON hook(id, definition)");
            },
            db -> {
                db.execSQL("ALTER TABLE assignment ADD COLUMN old TEXT");
                db.execSQL("ALTER TABLE assignment ADD COLUMN new TEXT");
                db.execSQL("CREATE INDEX idx_assignment_used ON assignment(used)");
            },
            db -> {
                // Unique hook ids
                Map<String, XHook> hooks = new HashMap<>();
                try (Cursor cursor = db.query("hook", new String[]{"definition"}, null, null, null, null, null)) {
                    while (cursor.moveToNext()) {
                        XHook hook = XHook.fromJSON(cursor.getString(0));
                        hooks.put(hook.getId(), hook);
                    }
                }
                Log.i(TAG, "Converting definitions=" + hooks.size());

                db.execSQL("DROP INDEX idx_hook");
                db.execSQL("DELETE FROM hook");
                db.execSQL("CREATE UNIQUE INDEX idx_hook ON hook(id)");
                for (XHook hook : hooks.values()) {
                    ContentValues cv = new ContentValues();
                    cv.put("id", hook.getId());
                    cv.put("definition", hook.toJSON());
                    if (db.insertWithOnConflict("hook", null, cv, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                        throw new Throwable("Error inserting hook");
                }
            },
            db -> {
                db.execSQL("CREATE TABLE `group` (package TEXT NOT NULL, uid INTEGER NOT NULL, name TEXT NOT NULL, used INTEGER)");
                db.execSQL("CREATE UNIQUE INDEX idx_group ON `group`(package, uid, name)");
            },
            db -> {
                // When a hook was assigned, for the apps restricted recently
                db.execSQL("ALTER TABLE assignment ADD COLUMN assigned INTEGER");
            },
            db -> {
                // Whether an app has assignments, see XProvider.answers
                db.execSQL("CREATE INDEX idx_assignment_uid ON assignment(uid)");
            }
    };

    // Hook names renamed by former versions: old name, new name; the ids of assignments have the collection
    // in front (see XHook.getId), which the renames of former versions left out, so they never applied
    static final String[][] RENAMED = new String[][]{
            {"TelephonyManager/getDeviceId", "TelephonyManager.getDeviceId"},
            {"TelephonyManager/getDeviceId/slot", "TelephonyManager.getDeviceId/slot"},
            {"TelephonyManager/getGroupIdLevel1", "TelephonyManager.getGroupIdLevel1"},
            {"TelephonyManager/getImei", "TelephonyManager.getImei"},
            {"TelephonyManager/getImei/slot", "TelephonyManager.getImei/slot"},
            {"TelephonyManager/getLine1Number", "TelephonyManager.getLine1Number"},
            {"TelephonyManager/getMeid", "TelephonyManager.getMeid"},
            {"TelephonyManager/getMeid/slot", "TelephonyManager.getMeid/slot"},
            {"TelephonyManager/getNetworkSpecifier", "TelephonyManager.getNetworkSpecifier"},
            {"TelephonyManager/getSimSerialNumber", "TelephonyManager.getSimSerialNumber"},
            {"TelephonyManager/getSubscriberId", "TelephonyManager.getSubscriberId"},
            {"TelephonyManager/getVoiceMailAlphaTag", "TelephonyManager.getVoiceMailAlphaTag"},
            {"TelephonyManager/getVoiceMailNumber", "TelephonyManager.getVoiceMailNumber"},
            {"TelephonyManager/getNetworkCountryIso", "TelephonyManager.getNetworkCountryIso"},
            {"TelephonyManager/getNetworkCountryIso.30", "TelephonyManager.getNetworkCountryIso.30"},
            {"TelephonyManager/getNetworkOperator", "TelephonyManager.getNetworkOperator"},
            {"TelephonyManager/getNetworkOperatorName", "TelephonyManager.getNetworkOperatorName"},
            {"TelephonyManager/getSimCountryIso", "TelephonyManager.getSimCountryIso"},
            {"TelephonyManager/getSimOperator", "TelephonyManager.getSimOperator"},
            {"TelephonyManager/getSimOperatorName", "TelephonyManager.getSimOperatorName"},
            {"SystemProperties.get", "SystemProperties.get/serial"},
            {"SystemProperties.get/default", "SystemProperties.get.default/serial"},
            // Settings.*.getString calls getStringForUser, which is hooked; Settings.System gives android_id from Secure
            {"Settings.Secure.getString", "Settings.Secure.getStringForUser/android_id"},
            {"Settings.Secure.getString/android_id", "Settings.Secure.getStringForUser/android_id"},
            {"Settings.System.getString/android_id", "Settings.Secure.getStringForUser/android_id"},
            {"Settings.Secure.getString/input_method", "Settings.Secure.getStringForUser/input_method"},
            {"Settings.Secure.getString/bluetooth_name", "Settings.Secure.getStringForUser/bluetooth_name"},
            {"Settings.Global.getString/device_name", "Settings.Global.getStringForUser/device_name"},
            // ContentResolver.query without a cancellation signal calls the hooked query with one (API 16 and later)
            {"ContentResolver.query1/calendars", "ContentResolver.query16/calendars"},
            {"ContentResolver.query1/call_log", "ContentResolver.query16/call_log"},
            {"ContentResolver.query1/contacts", "ContentResolver.query16/contacts"},
            {"ContentResolver.query1/blockednumber", "ContentResolver.query16/blockednumber"},
            {"ContentResolver.query1/mmssms", "ContentResolver.query16/mmssms"},
            {"ContentResolver.query1/voicemail", "ContentResolver.query16/voicemail"},
            {"ContentResolver.query1/gsf_id", "ContentResolver.query16/gsf_id"},
            {"ContentResolver.query1/fb_attribution", "ContentResolver.query16/fb_attribution"},
            {"ContentResolver.query1/oaid", "ContentResolver.query16/oaid"},
    };

    // Built-in hook names removed by this and former versions, without a definition which took over; their assignments
    // are removed unless a custom definition has the same id, like one of Downloads, see XProvider.removeObsoleteAssignments
    static final String[] REMOVED = new String[]{
            "ActivityManager.getRecentTasks", "ActivityManager.getRunningTasks",
            "ActivityThread.handleBindService/notification", "AppEventsLogger.activateApp/context",
            "AppEventsLogger.activateApp/context/id", "AppEventsLogger.deactivateApp/context",
            "AppEventsLogger.deactivateApp/context/id", "BlockGuardOs.open", "Bundle.get/location",
            "CameraManager2.registerAvailabilityCallback", "CameraManager2.registerTorchCallback",
            "Configuration.createFromParcel", "ContentProviderClient.query1/blockednumber",
            "ContentProviderClient.query1/calendars", "ContentProviderClient.query1/call_log",
            "ContentProviderClient.query1/contacts", "ContentProviderClient.query1/fb_attribution",
            "ContentProviderClient.query1/gsf_id", "ContentProviderClient.query1/mmssms",
            "ContentProviderClient.query1/oaid", "ContentProviderClient.query1/voicemail", "ContentResolver/query1",
            "ContentResolver/query16", "ContentResolver/query26", "Fabric.with/kits",
            "FlurryAgent.endTimedEvent/id", "FlurryAgent.endTimedEvent/id/parameters", "FlurryAgent.logEvent/id",
            "FlurryAgent.logEvent/id/parameters", "FlurryAgent.logEvent/id/parameters/timed",
            "FlurryAgent.logEvent/id/timed", "FlurryAgent.onEndSession", "FlurryAgent.onPageView",
            "FlurryAgent.onStartSession", "FlurryAgent.onStartSession/apikey", "FlurryAgent.setAge",
            "FlurryAgent.setGender", "FlurryAgent.setLocation", "GoogleAnalytics.getInstance",
            "GoogleAnalytics.setDryRun", "Intent.createFromParcel", "Intent.createFromParcel/activity_recognition",
            "MediaRecorder.start", "MediaRecorder.stop", "NetworkInfo.createFromParcel",
            "NetworkInterface.networkInterfaces", "NsdManager.registerServiceInfoCallback/request",
            "PlaceLikelihoodBuffer.get", "PlaceLikelihoodBuffer.getCount", "ProcessBuilder.command/array",
            "ProcessBuilder.command/list", "Runtime.exec/array", "Runtime.exec/array/env",
            "Runtime.exec/array/env/file", "Runtime.exec/cmd", "Runtime.exec/cmd/env", "Runtime.exec/cmd/env/file",
            "SystemProperties.get.default/build", "SystemProperties.get.default/vendor",
            "SystemProperties.get/build", "SystemProperties.get/vendor", "SystemProperties.getBoolean",
            "SystemProperties.getInt", "SystemProperties.getLong", "WebView.constructor.11",
            "WebView.constructor.15", "WebView.constructor.1a", "WebView.constructor.1b", "WebView.constructor.1c",
            "WebView.constructor.21", "WebView.constructor.23", "WebView.constructor.5"
    };

    private final SQLiteDatabase db;
    // Reads do not wait for each other, writes are serialized
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    private XDatabase(SQLiteDatabase db) {
        this.db = db;
    }

    static XDatabase open() throws Throwable {
        File dir = new File(Environment.getDataDirectory(), "system" + File.separator + "xlua");
        File file = new File(dir, "xlua.db");
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create " + dir);

        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        // Write-ahead logging gives a connection pool, so reads (on every app start) do not wait for each other
        db.enableWriteAheadLogging();
        Log.i(TAG, "Database file=" + file);

        // Owner and group (system): rwx, world: ---
        Util.setPermissions(dir.getAbsolutePath(), 0770, Process.SYSTEM_UID, Process.SYSTEM_UID);
        File[] files = dir.listFiles();
        if (files != null)
            for (File f : files)
                Util.setPermissions(f.getAbsolutePath(), 0770, Process.SYSTEM_UID, Process.SYSTEM_UID);

        XDatabase database = new XDatabase(db);
        try {
            database.upgrade();
        } catch (Throwable ex) {
            db.close();
            throw ex;
        }
        return database;
    }

    private void upgrade() throws Throwable {
        for (int i = 0; i < MIGRATIONS.length; i++) {
            final int version = i + 1;
            if (db.needUpgrade(version)) {
                Log.i(TAG, "Database upgrade version " + version);
                final Migration migration = MIGRATIONS[i];
                write(db -> {
                    migration.run(db);
                    db.setVersion(version);
                    return null;
                });
            }
        }
        Log.i(TAG, "Database version=" + db.getVersion());

        write(db -> {
            // An assignment with the new id can exist already, then the one with the old id is removed
            for (String[] renamed : RENAMED) {
                String[] from = new String[]{XHook.builtinId(renamed[0])};
                ContentValues cv = new ContentValues();
                cv.put("hook", XHook.builtinId(renamed[1]));
                long rows = db.updateWithOnConflict("assignment", cv, "hook = ?", from, SQLiteDatabase.CONFLICT_IGNORE);
                long removed = db.delete("assignment", "hook = ?", from);
                if (rows > 0 || removed > 0)
                    Log.i(TAG, "Renamed hook " + from[0] + " into " + cv.get("hook") + " rows=" + rows + " removed=" + removed);
            }

            // Hooks are installed again and errors reported again after a restart
            ContentValues cv = new ContentValues();
            cv.put("installed", -1);
            cv.putNull("exception");
            Log.i(TAG, "Reset assigned hook data count=" + db.update("assignment", cv, null, null));
            return null;
        });
    }

    // Reads do not need a transaction; returned cursors should be filled before returning
    <T> T read(Operation<T> operation) throws Throwable {
        lock.readLock().lock();
        try {
            return operation.run(db);
        } finally {
            lock.readLock().unlock();
        }
    }

    // A write in a transaction
    <T> T write(Operation<T> operation) throws Throwable {
        lock.writeLock().lock();
        try {
            db.beginTransaction();
            try {
                T result = operation.run(db);
                db.setTransactionSuccessful();
                return result;
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
