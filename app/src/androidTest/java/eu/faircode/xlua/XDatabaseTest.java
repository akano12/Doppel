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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// The migrations of the database of the service, which upgrade the database of every user on an update; SQLite is
// part of Android, so this runs on a device, in a file of its own instead of the database of the service
@RunWith(AndroidJUnit4.class)
public class XDatabaseTest {
    private File file;
    private XDatabase db;

    @Before
    public void setUp() {
        file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "xlua-test.db");
        SQLiteDatabase.deleteDatabase(file);
    }

    @After
    public void tearDown() {
        // Its files are deleted while it is open else
        if (db != null)
            db.close();
        SQLiteDatabase.deleteDatabase(file);
    }

    // A database of the first version, with its data, gets the tables, columns and indexes of now, keeps its data,
    // moves the assignments of renamed hooks and resets the install data
    @Test
    public void upgradesTheFirstVersion() throws Throwable {
        SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(file, null);
        old.execSQL("CREATE TABLE assignment (package TEXT NOT NULL, uid INTEGER NOT NULL, hook TEXT NOT NULL," +
                " installed INTEGER, used INTEGER, restricted INTEGER, exception TEXT)");
        old.execSQL("CREATE UNIQUE INDEX idx_assignment ON assignment(package, uid, hook)");
        old.execSQL("CREATE TABLE setting (user INTEGER, category TEXT NOT NULL, name TEXT NOT NULL, value TEXT)");
        old.execSQL("CREATE UNIQUE INDEX idx_setting ON setting(user, category, name)");
        old.execSQL("INSERT INTO assignment VALUES ('com.example', 10123, 'Privacy.Runtime.exec', 5, 6, 1, 'failed')");
        old.execSQL("INSERT INTO setting VALUES (0, 'global', 'collection', 'Privacy')");
        old.setVersion(1);
        old.close();

        db = XDatabase.open(file);
        db.read(d -> {
            assertEquals(8, d.getVersion());
            assertTrue(names(d, "table").containsAll(set("assignment", "setting", "hook", "group")));
            assertTrue(names(d, "index").containsAll(set("idx_assignment", "idx_setting", "idx_hook",
                    "idx_assignment_used", "idx_group", "idx_assignment_uid", "idx_assignment_hook")));

            // The columns added later, the renamed hook and the install data reset
            try (Cursor c = d.rawQuery("SELECT hook, installed, used, restricted, exception, old, new, assigned" +
                    " FROM assignment", null)) {
                assertEquals(1, c.getCount());
                c.moveToFirst();
                assertEquals("Privacy.ProcessBuilder.start", c.getString(0));
                assertEquals(-1, c.getLong(1));
                assertEquals(6, c.getLong(2));
                assertEquals(1, c.getInt(3));
                assertTrue(c.isNull(4));
                assertTrue(c.isNull(5) && c.isNull(6) && c.isNull(7));
            }
            try (Cursor c = d.rawQuery("SELECT value FROM setting WHERE name = 'collection'", null)) {
                assertTrue(c.moveToFirst());
                assertEquals("Privacy", c.getString(0));
            }
            return null;
        });
    }

    // A new database, like at the first start, gets the same tables as an upgraded one
    @Test
    public void createsANewDatabase() throws Throwable {
        db = XDatabase.open(file);
        db.read(d -> {
            assertEquals(8, d.getVersion());
            assertTrue(names(d, "table").containsAll(set("assignment", "setting", "hook", "group")));
            return null;
        });
    }

    // Settings no script reads anymore are removed on each start, of all users and apps; others are kept
    @Test
    public void removesObsoleteSettings() throws Throwable {
        db = XDatabase.open(file);
        db.write(d -> {
            d.execSQL("INSERT INTO setting VALUES (0, 'com.example', 'commands.allowed', 'getprop')");
            d.execSQL("INSERT INTO setting VALUES (10, 'global', 'commands.regex', '.*')");
            d.execSQL("INSERT INTO setting VALUES (0, 'com.example', 'value.email', 'a')");
            return null;
        });
        db.close();
        // Twice: nothing left to remove the second time
        for (int i = 0; i < 2; i++) {
            db = XDatabase.open(file);
            db.read(d -> {
                try (Cursor c = d.rawQuery("SELECT name FROM setting", null)) {
                    assertEquals(1, c.getCount());
                    c.moveToFirst();
                    assertEquals("value.email", c.getString(0));
                }
                return null;
            });
            db.close();
        }
        db = null;
    }

    // A failing write on each start, like on a full disk, leaves nothing half done and the database open, so restricted
    // apps keep their hooks
    @Test
    public void failedMaintenanceKeepsTheDatabase() throws Throwable {
        db = XDatabase.open(file);
        db.write(d -> {
            d.execSQL("INSERT INTO setting VALUES (0, 'com.example', 'commands.allowed', 'getprop')");
            d.execSQL("INSERT INTO assignment (package, uid, hook, installed) VALUES ('com.example', 10123, 'Privacy.Test.kept', 5)");
            // Fails the reset of the install data, after the obsolete setting was removed
            d.execSQL("CREATE TRIGGER fail BEFORE UPDATE ON assignment BEGIN SELECT RAISE(ABORT, 'full'); END");
            return null;
        });
        db.close();

        db = XDatabase.open(file);
        db.read(d -> {
            try (Cursor c = d.rawQuery("SELECT name FROM setting", null)) {
                assertEquals(1, c.getCount());
            }
            try (Cursor c = d.rawQuery("SELECT installed FROM assignment", null)) {
                assertTrue(c.moveToFirst());
                assertEquals(5, c.getLong(0));
            }
            return null;
        });
    }

    // Android deletes a corrupt database: a new one opens, and it is known, so the user can be told
    @Test
    public void corruptFileStartsEmptyAndIsKnown() throws Throwable {
        try (FileOutputStream out = new FileOutputStream(file)) {
            for (int i = 0; i < 1000; i++)
                out.write("this is not a database".getBytes(StandardCharsets.UTF_8));
        }

        XDatabase.lost = false;
        db = XDatabase.open(file);
        assertTrue(XDatabase.lost);
        XDatabase.lost = false;

        assertTrue(db.isOpen());
        db.read(d -> {
            try (Cursor c = d.rawQuery("SELECT name FROM setting", null)) {
                assertEquals(0, c.getCount());
            }
            return null;
        });
    }

    // The schema of each former version, as its migrations made it, see XDatabase.MIGRATIONS
    private static final String[][] SCHEMAS = new String[][]{
            {"CREATE TABLE assignment (package TEXT NOT NULL, uid INTEGER NOT NULL, hook TEXT NOT NULL," +
                    " installed INTEGER, used INTEGER, restricted INTEGER, exception TEXT)",
                    "CREATE UNIQUE INDEX idx_assignment ON assignment(package, uid, hook)",
                    "CREATE TABLE setting (user INTEGER, category TEXT NOT NULL, name TEXT NOT NULL, value TEXT)",
                    "CREATE UNIQUE INDEX idx_setting ON setting(user, category, name)"},
            {"CREATE TABLE hook (id TEXT NOT NULL, definition TEXT NOT NULL)",
                    "CREATE UNIQUE INDEX idx_hook ON hook(id, definition)"},
            {"ALTER TABLE assignment ADD COLUMN old TEXT",
                    "ALTER TABLE assignment ADD COLUMN new TEXT",
                    "CREATE INDEX idx_assignment_used ON assignment(used)"},
            {"DROP INDEX idx_hook", "CREATE UNIQUE INDEX idx_hook ON hook(id)"},
            {"CREATE TABLE `group` (package TEXT NOT NULL, uid INTEGER NOT NULL, name TEXT NOT NULL, used INTEGER)",
                    "CREATE UNIQUE INDEX idx_group ON `group`(package, uid, name)"},
            {"ALTER TABLE assignment ADD COLUMN assigned INTEGER"},
            {"CREATE INDEX idx_assignment_uid ON assignment(uid)"}
    };

    // A database of each former version keeps its restrictions and settings and gets exactly the tables and
    // indexes of a new one
    @Test
    public void upgradesEveryVersion() throws Throwable {
        db = XDatabase.open(file);
        String expected = db.read(XDatabaseTest::schema);
        db.close();
        db = null;
        assertTrue(expected, expected.contains("idx_assignment_hook"));

        for (int version = 1; version <= SCHEMAS.length; version++) {
            SQLiteDatabase.deleteDatabase(file);
            SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(file, null);
            for (int i = 0; i < version; i++)
                for (String sql : SCHEMAS[i])
                    old.execSQL(sql);
            old.execSQL("INSERT INTO assignment (package, uid, hook, installed, used, restricted)" +
                    " VALUES ('com.example', 10123, 'Privacy.Test.kept', 5, 6, 1)");
            old.execSQL("INSERT INTO setting VALUES (0, 'com.example', 'value.email', 'a')");
            old.setVersion(version);
            old.close();

            final int from = version;
            db = XDatabase.open(file);
            db.read(d -> {
                assertEquals("From " + from, 8, d.getVersion());
                assertEquals("From " + from, expected, schema(d));
                try (Cursor c = d.rawQuery("SELECT hook, used FROM assignment", null)) {
                    assertEquals("From " + from, 1, c.getCount());
                    c.moveToFirst();
                    assertEquals("Privacy.Test.kept", c.getString(0));
                    assertEquals(6, c.getLong(1));
                }
                try (Cursor c = d.rawQuery("SELECT value FROM setting WHERE name = 'value.email'", null)) {
                    assertTrue("From " + from, c.moveToFirst());
                    assertEquals("a", c.getString(0));
                }
                return null;
            });
            db.close();
            db = null;
        }
    }

    // Version 3 could have a custom definition more than once, with different texts, and texts which cannot be
    // read; the upgrade keeps one of each id and leaves out the others, else the database could never be opened
    @Test
    public void upgradeKeepsOneDefinitionOfEachId() throws Throwable {
        SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(file, null);
        for (int i = 0; i < 3; i++)
            for (String sql : SCHEMAS[i])
                old.execSQL(sql);
        String definition = "{\"collection\": \"Test\", \"group\": \"G\", \"name\": \"twice\", \"author\": \"%s\"," +
                " \"className\": \"android.location.Location\", \"parameterTypes\": [], \"minSdk\": 26, \"luaScript\": \"\"}";
        for (String sql : new String[]{String.format(definition, "One"), String.format(definition, "Two"), "{"}) {
            ContentValues cv = new ContentValues();
            cv.put("id", sql.equals("{") ? "Test.broken" : "Test.twice");
            cv.put("definition", sql);
            old.insertOrThrow("hook", null, cv);
        }
        old.setVersion(3);
        old.close();

        db = XDatabase.open(file);
        db.read(d -> {
            assertEquals(8, d.getVersion());
            try (Cursor c = d.rawQuery("SELECT id, definition FROM hook", null)) {
                assertEquals(1, c.getCount());
                c.moveToFirst();
                assertEquals("Test.twice", c.getString(0));
                assertEquals("Test.twice", XHook.fromJSON(c.getString(1)).getId());
            }
            return null;
        });
    }

    // The restriction of a renamed hook moves to the new name; an app which has the new one already keeps that one
    @Test
    public void renamedHooksKeepTheNewRestriction() throws Throwable {
        String from = XHook.builtinId(XDatabase.RENAMED[0][0]);
        String to = XHook.builtinId(XDatabase.RENAMED[0][1]);
        db = XDatabase.open(file);
        db.write(d -> {
            d.execSQL("INSERT INTO assignment (package, uid, hook, used) VALUES ('com.moved', 10123, ?, 1)", new Object[]{from});
            d.execSQL("INSERT INTO assignment (package, uid, hook, used) VALUES ('com.both', 10124, ?, 2)", new Object[]{from});
            d.execSQL("INSERT INTO assignment (package, uid, hook, used) VALUES ('com.both', 10124, ?, 3)", new Object[]{to});
            return null;
        });
        db.close();
        db = XDatabase.open(file);
        db.read(d -> {
            try (Cursor c = d.rawQuery("SELECT package, hook, used FROM assignment ORDER BY package", null)) {
                assertEquals(2, c.getCount());
                c.moveToFirst();
                assertEquals("com.both", c.getString(0));
                assertEquals(to, c.getString(1));
                assertEquals(3, c.getLong(2));
                c.moveToNext();
                assertEquals("com.moved", c.getString(0));
                assertEquals(to, c.getString(1));
                assertEquals(1, c.getLong(2));
            }
            return null;
        });
    }

    // The tables and indexes with their definitions, without those of SQLite and Android
    private static String schema(SQLiteDatabase d) {
        StringBuilder sb = new StringBuilder();
        try (Cursor c = d.rawQuery("SELECT type, name, sql FROM sqlite_master" +
                " WHERE name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY type, name", null)) {
            while (c.moveToNext())
                sb.append(c.getString(0)).append(' ').append(c.getString(1)).append(": ").append(c.getString(2)).append('\n');
        }
        return sb.toString();
    }

    // Set.of is of Android 11 and later
    private static Set<String> set(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    private static Set<String> names(SQLiteDatabase d, String type) {
        Set<String> names = new HashSet<>();
        try (Cursor c = d.rawQuery("SELECT name FROM sqlite_master WHERE type = ?", new String[]{type})) {
            while (c.moveToNext())
                names.add(c.getString(0));
        }
        return names;
    }
}
