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

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
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
