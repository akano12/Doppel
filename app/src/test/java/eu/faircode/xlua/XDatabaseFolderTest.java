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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The database moves out of /data/system, which any app can search, without losing restrictions on the way
public class XDatabaseFolderTest {
    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private File old;
    private File dir;

    @Before
    public void folders() throws IOException {
        old = root.newFolder("system", "xlua");
        dir = new File(root.newFolder("system_de", "0"), "xlua");
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    // The write-ahead log can hold committed rows, so it comes along
    @Test
    public void movesAllFiles() throws IOException {
        write(new File(old, "xlua.db"), "db");
        write(new File(old, "xlua.db-wal"), "wal");
        assertEquals(dir, XDatabase.folder(old, dir));
        assertEquals("db", read(new File(dir, "xlua.db")));
        assertEquals("wal", read(new File(dir, "xlua.db-wal")));
        assertFalse(old.exists());
        assertFalse(new File(dir.getPath() + ".tmp").exists());
    }

    // A database in the new folder is the current one, also when the old folder could not be removed before
    @Test
    public void newDatabaseWins() throws IOException {
        assertTrue(dir.mkdir());
        write(new File(dir, "xlua.db"), "new");
        write(new File(old, "xlua.db"), "old");
        assertEquals(dir, XDatabase.folder(old, dir));
        assertEquals("new", read(new File(dir, "xlua.db")));
        assertFalse(old.exists());
    }

    // An interrupted copy is made again, without the files it left
    @Test
    public void staleFilesAreRemoved() throws IOException {
        File tmp = new File(dir.getPath() + ".tmp");
        assertTrue(tmp.mkdir());
        write(new File(tmp, "xlua.db"), "half");
        assertTrue(dir.mkdir());
        write(new File(dir, "xlua.db-wal"), "stale");
        write(new File(old, "xlua.db"), "db");
        assertEquals(dir, XDatabase.folder(old, dir));
        assertEquals("db", read(new File(dir, "xlua.db")));
        assertFalse(new File(dir, "xlua.db-wal").exists());
        assertFalse(tmp.exists());
        assertFalse(old.exists());
    }

    // A first start makes only the new folder
    @Test
    public void withoutOldFolder() {
        assertTrue(old.delete());
        assertEquals(dir, XDatabase.folder(old, dir));
        assertTrue(dir.isDirectory());
        assertFalse(old.exists());
    }

    // Without the folder of user 0 the old database stays in use and nothing is made in the place of vold's folder
    @Test
    public void failureKeepsOldFolder() throws IOException {
        File missing = new File(new File(root.getRoot(), "missing"), "xlua");
        write(new File(old, "xlua.db"), "db");
        assertEquals(old, XDatabase.folder(old, missing));
        assertEquals("db", read(new File(old, "xlua.db")));
        assertFalse(missing.getParentFile().exists());

        assertTrue(new File(old, "xlua.db").delete() && old.delete());
        assertEquals(old, XDatabase.folder(old, missing));
        assertFalse(missing.getParentFile().exists());
    }

    // Android deletes a corrupt database; a copy of it, with its log, stays, for the user to have it looked at
    @Test
    public void corruptDatabaseKeepsACopy() throws IOException {
        File db = new File(old, "xlua.db");
        write(db, "corrupt");
        write(new File(old, "xlua.db-wal"), "log");
        XDatabase.keepCorrupt(db, 1000);
        assertEquals("corrupt", read(new File(old, "xlua.db.corrupt-1000")));
        assertEquals("log", read(new File(old, "xlua.db.corrupt-1000-wal")));
        // Android deletes the files itself, which the copy does not
        assertEquals("corrupt", read(db));

        // No log is no copy of one, and a file which is gone is no reason to fail
        write(db, "second");
        assertTrue(new File(old, "xlua.db-wal").delete());
        XDatabase.keepCorrupt(db, 2000);
        assertEquals("second", read(new File(old, "xlua.db.corrupt-2000")));
        assertFalse(new File(old, "xlua.db.corrupt-2000-wal").exists());
        assertTrue(db.delete());
        XDatabase.keepCorrupt(db, 3000);
        assertFalse(new File(old, "xlua.db.corrupt-3000").exists());
    }

    // Only the last copies stay, the older ones with their logs go; other files of the folder stay
    @Test
    public void onlyTheLastCorruptCopiesStay() throws IOException {
        File db = new File(old, "xlua.db");
        write(db, "x");
        write(new File(old, "other.txt"), "other");
        for (long time : new long[]{1000, 3000, 2000, 10000, 4000}) {
            write(new File(old, "xlua.db-wal"), "log" + time);
            XDatabase.keepCorrupt(db, time);
        }
        // By the time in the name, not by name: 10000 sorts before 2000 as text
        assertTrue(new File(old, "xlua.db.corrupt-10000").exists());
        assertTrue(new File(old, "xlua.db.corrupt-4000").exists());
        assertEquals("log4000", read(new File(old, "xlua.db.corrupt-4000-wal")));
        for (long time : new long[]{1000, 2000, 3000}) {
            assertFalse(new File(old, "xlua.db.corrupt-" + time).exists());
            assertFalse(new File(old, "xlua.db.corrupt-" + time + "-wal").exists());
        }
        assertEquals(XDatabase.KEPT_CORRUPT,
                old.listFiles((dir, name) -> name.startsWith("xlua.db.corrupt-") && !name.endsWith("-wal")).length);
        assertEquals("other", read(new File(old, "other.txt")));
    }
}
