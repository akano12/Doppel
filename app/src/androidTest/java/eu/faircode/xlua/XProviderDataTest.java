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
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

// What the service writes for the calls of hooked apps and at boot, on a database of its own like XDatabaseTest;
// SQLite and Bundle are part of Android, so this runs on a device, without the module
@RunWith(AndroidJUnit4.class)
public class XProviderDataTest {
    private static final String PKG = "com.example";
    private static final int UID = 10123;

    private File file;
    private XDatabase db;

    @Before
    public void setUp() throws Throwable {
        file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "xlua-provider-test.db");
        SQLiteDatabase.deleteDatabase(file);
        db = XDatabase.open(file);
    }

    @After
    public void tearDown() {
        if (db != null)
            db.close();
        SQLiteDatabase.deleteDatabase(file);
    }

    // Any app can report, so each report of a call must be of the caller and complete, else none is stored
    @Test
    public void reportsOfTheCallerOnly() {
        Map<String, XHook> hooks = hooks(hook("A", "G"));
        Bundle own = report(UID, "A", "use", new Bundle());
        Bundle other = report(UID + 1, "A", "use", new Bundle());
        Bundle invalid = report(UID, "A", "use", null);

        assertEquals(1, XProvider.Report.all(Collections.singletonList(own), UID, hooks).size());
        assertThrows(SecurityException.class, () -> XProvider.Report.all(Arrays.asList(own, other), UID, hooks));
        assertThrows(SecurityException.class, () -> XProvider.Report.all(Arrays.asList(other, own), UID, hooks));
        assertThrows(IllegalArgumentException.class, () -> XProvider.Report.all(Arrays.asList(own, invalid), UID, hooks));
    }

    // The text of a report is limited; a failing hook is notified once, until the reset of the next start
    @Test
    public void reportsLimitTextAndNotifyTheFirstErrorOnly() throws Throwable {
        Map<String, XHook> hooks = hooks(hook("A", "G"));
        db.write(d -> {
            assign(d, PKG, UID, id("A"), -1);
            return null;
        });

        Bundle data = new Bundle();
        data.putString("exception", repeat(20000));
        data.putString("old", repeat(2000));
        XProvider.Report first = write(report(UID, "A", "use", data), hooks);
        assertTrue(first.firstError);
        XProvider.Report second = write(report(UID, "A", "use", data), hooks);
        assertFalse(second.firstError);
        db.read(d -> {
            try (Cursor c = d.rawQuery("SELECT exception, old FROM assignment", null)) {
                assertTrue(c.moveToFirst());
                assertEquals(16384 + 1, c.getString(0).length());
                assertEquals(1024 + 1, c.getString(1).length());
            }
            return null;
        });

        // No assignment, like for the package of another app: nothing stored, nothing notified
        XProvider.Report none = write(report(UID, "B", "use", data), hooks);
        assertNull(none.used);
        assertFalse(none.firstError);
    }

    // With notifications on, the first restricted use of a group is notified, the next one not
    @Test
    public void reportsNotifyTheFirstUseOfAGroup() throws Throwable {
        Map<String, XHook> hooks = hooks(hook("A", "G"));
        db.write(d -> {
            assign(d, PKG, UID, id("A"), -1);
            setting(d, 0, PKG, "notify", "true");
            return null;
        });
        Bundle data = new Bundle();
        data.putInt("restricted", 1);
        XProvider.Report first = write(report(UID, "A", "use", data), hooks);
        assertEquals(-1L, (long) first.used);
        assertTrue(first.notifiesUse());
        XProvider.Report second = write(report(UID, "A", "use", data), hooks);
        assertEquals(5L, (long) second.used);
        assertFalse(second.notifiesUse());
    }

    // New hooks go to the apps which restrict their group, of the user asked for, of the enabled collections;
    // assignments there are already are kept and the hooks the map leaves out, like moved ones, do not count
    @Test
    public void assignsToTheAppsRestrictingTheGroup() throws Throwable {
        XHook a = hook("A", "G");
        XHook b = hook("B", "G");
        XHook c = hook("C", "H");
        XHook moved = hook("M", "G");
        XHook other = XHook.fromJSON(json("Other", "O", "G"));
        Map<String, XHook> hooks = hooks(a, b, c);
        int profile = Util.getUserUid(10, 10200);
        db.write(d -> {
            assign(d, "app.g", UID, a.getId(), 1);
            assign(d, "app.h", UID + 1, c.getId(), 1);
            assign(d, "app.moved", UID + 2, moved.getId(), 1);
            assign(d, "app.has", UID + 3, a.getId(), 1);
            assign(d, "app.has", UID + 3, b.getId(), 7);
            assign(d, "app.profile", profile, a.getId(), 1);
            return null;
        });

        int[] counts = db.write(d -> XProvider.assignToRestricting(d, hooks, Arrays.asList(b, other), 0, false));
        assertEquals(1, counts[0]);
        assertEquals(1, counts[1]);
        assertTrue(assigned("app.g", UID, b.getId()));
        assertFalse(assigned("app.h", UID + 1, b.getId()));
        assertFalse(assigned("app.moved", UID + 2, b.getId()));
        assertFalse(assigned("app.profile", profile, b.getId()));
        assertFalse(assigned("app.g", UID, other.getId()));
        // Kept, not replaced
        db.read(d -> {
            try (Cursor cursor = d.rawQuery("SELECT assigned FROM assignment WHERE package = 'app.has' AND hook = ?",
                    new String[]{b.getId()})) {
                assertTrue(cursor.moveToFirst());
                assertEquals(7, cursor.getLong(0));
            }
            return null;
        });

        // Any collection, all users
        counts = db.write(d -> XProvider.assignToRestricting(d, hooks, Collections.singletonList(other), null, true));
        assertEquals(3, counts[0]);
        assertTrue(assigned("app.profile", profile, other.getId()));
        assertFalse(assigned("app.h", UID + 1, other.getId()));
    }

    // Apps and users removed while the service did not listen lose their restrictions and settings at boot,
    // like clearApp with its settings and clearData; others keep theirs
    @Test
    public void removedAppsAndUsersLoseTheirSettings() throws Throwable {
        int profile = Util.getUserUid(10, UID);
        db.write(d -> {
            assign(d, PKG, UID, id("A"), 1);
            assign(d, "kept.app", UID + 1, id("A"), 1);
            assign(d, PKG, profile, id("A"), 1);
            setting(d, 0, PKG, "value.email", "a");
            setting(d, 0, "kept.app", "value.email", "b");
            setting(d, 0, "global", "collection", "Privacy");
            setting(d, 10, PKG, "value.email", "c");
            setting(d, 10, "global", "collection", "Privacy");
            setting(d, -1, "service", "builtins_enabled", "x");

            XProvider.removeData(d, Collections.singletonList(new String[]{PKG, Integer.toString(UID)}),
                    Collections.singletonList(10));
            return null;
        });
        assertEquals(Arrays.asList("kept.app:" + (UID + 1)), rows("SELECT package || ':' || uid FROM assignment"));
        assertEquals(Arrays.asList("-1:service", "0:global", "0:kept.app"),
                rows("SELECT user || ':' || category FROM setting ORDER BY user, category"));
    }

    private XProvider.Report write(Bundle report, Map<String, XHook> hooks) throws Throwable {
        XProvider.Report result = XProvider.Report.all(Collections.singletonList(report), UID, hooks).get(0);
        db.write(d -> {
            result.write(d);
            return null;
        });
        return result;
    }

    private boolean assigned(String pkg, int uid, String hook) throws Throwable {
        return db.read(d -> {
            try (Cursor c = d.rawQuery("SELECT 1 FROM assignment WHERE package = ? AND uid = ? AND hook = ?",
                    new String[]{pkg, Integer.toString(uid), hook})) {
                return c.moveToFirst();
            }
        });
    }

    private List<String> rows(String sql) throws Throwable {
        return db.read(d -> {
            List<String> result = new ArrayList<>();
            try (Cursor c = d.rawQuery(sql, null)) {
                while (c.moveToNext())
                    result.add(c.getString(0));
            }
            return result;
        });
    }

    private static void assign(SQLiteDatabase d, String pkg, int uid, String hook, long assigned) {
        ContentValues cv = new ContentValues();
        cv.put("package", pkg);
        cv.put("uid", uid);
        cv.put("hook", hook);
        cv.put("assigned", assigned);
        cv.put("installed", -1);
        cv.put("used", -1);
        cv.put("restricted", 0);
        d.insertOrThrow("assignment", null, cv);
    }

    private static void setting(SQLiteDatabase d, int user, String category, String name, String value) {
        ContentValues cv = new ContentValues();
        cv.put("user", user);
        cv.put("category", category);
        cv.put("name", name);
        cv.put("value", value);
        d.insertOrThrow("setting", null, cv);
    }

    // Reported at time 5
    private static Bundle report(int uid, String name, String event, Bundle data) {
        Bundle args = new Bundle();
        args.putString("hook", id(name));
        args.putString("packageName", PKG);
        args.putInt("uid", uid);
        args.putString("event", event);
        args.putLong("time", 5);
        args.putBundle("data", data);
        return args;
    }

    private static Map<String, XHook> hooks(XHook... hooks) {
        Map<String, XHook> result = new HashMap<>();
        for (XHook hook : hooks)
            result.put(hook.getId(), hook);
        return result;
    }

    private static String id(String name) {
        return Util.COLLECTION_DEFAULT + "." + name;
    }

    private static XHook hook(String name, String group) {
        try {
            return XHook.fromJSON(json(Util.COLLECTION_DEFAULT, name, group));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String json(String collection, String name, String group) {
        return "{\"collection\": \"" + collection + "\", \"group\": \"" + group + "\"," +
                " \"name\": \"" + name + "\", \"author\": \"Test\", \"className\": \"android.location.Location\"," +
                " \"parameterTypes\": [], \"minSdk\": 26, \"enabled\": true, \"luaScript\": \"\"}";
    }

    private static String repeat(int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, 'x');
        return new String(chars);
    }
}
