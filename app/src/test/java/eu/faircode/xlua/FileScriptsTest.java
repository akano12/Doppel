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

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

// The files apps read which scripts give another text: the sizes of /proc/meminfo and the id of boot_id,
// and the times of the folders of the shared storage
public class FileScriptsTest {
    // A param of an opened file, which tells the text the app reads then
    private static final String STUBS = String.join("\n",
            "function hookof(method)",
            "  return { getMethodName = function(self) return method end }",
            "end",
            "function paramof(text)",
            "  return { text = text, set = false,",
            "    getException = function(self) return nil end,",
            "    getResult = function(self) return 'fd' end,",
            "    getPackageName = function(self) return 'test.app' end,",
            "    getFileText = function(self) return self.text end,",
            "    setFileText = function(self, value) self.text = value self.set = true end,",
            "    uuidOf = function(self, seed) return 'uuid:' .. seed end }",
            "end");

    private static final String MEMINFO = String.join("\n",
            "MemTotal:        2534120 kB",
            "MemFree:          147624 kB",
            "MemAvailable:     551824 kB",
            "Buffers:            3304 kB",
            "");

    // The sizes like the ones of ActivityManager.getMemoryInfo, in the format of the kernel
    @Test
    public void meminfo() throws Exception {
        Globals globals = Scripts.load("resources", STUBS);
        Varargs result = Scripts.run(globals,
                "local param = paramof([[" + MEMINFO + "]])\n" +
                        "local restricted, old, new = after(hookof('open'), param)\n" +
                        "return restricted, param.text, old, new");
        assertTrue(result.arg(1).toboolean());
        assertEquals(String.join("\n",
                "MemTotal:        3145728 kB",
                "MemFree:          146484 kB",
                "MemAvailable:     546875 kB",
                "Buffers:            3304 kB",
                ""), result.arg(2).tojstring());
        // The log has the bytes of the total and available memory, also beyond 32 bits
        assertEquals("2594938880 565067776", result.arg(3).tojstring());
        assertEquals("3221225472 560000000", result.arg(4).tojstring());
    }

    // Only /proc/meminfo, the other hooks of the script have no filter
    @Test
    public void meminfoFilter() throws Exception {
        Globals globals = Scripts.load("resources", STUBS);
        Varargs result = Scripts.run(globals,
                "local paths = filter(hookof('open'))\n" +
                        "return #paths, paths[1], filter(hookof('statvfs')) == nil");
        assertEquals(1, result.arg(1).toint());
        assertEquals("/proc/meminfo", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
    }

    // Sizes which are rounded already stay as they are
    @Test
    public void meminfoRounded() throws Exception {
        Globals globals = Scripts.load("resources", STUBS);
        Varargs result = Scripts.run(globals,
                "local param = paramof('MemTotal:        3145728 kB\\nMemAvailable:     546875 kB\\n')\n" +
                        "return after(hookof('open'), param), param.set");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
    }

    // An id of the app, which the id of the kernel changes, and no id for a file without one
    @Test
    public void bootId() throws Exception {
        Globals globals = Scripts.load("boot_id", STUBS);
        Varargs result = Scripts.run(globals,
                "local param = paramof('7a73f969-e3f2-4b5d-9228-2eac60cccd8b\\n')\n" +
                        "local empty = paramof('')\n" +
                        "return after(nil, param), param.text, after(nil, empty), empty.set, filter(nil)[1]");
        assertTrue(result.arg(1).toboolean());
        assertEquals("uuid:7a73f969-e3f2-4b5d-9228-2eac60cccd8b\ntest.app\n", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertEquals("/proc/sys/kernel/random/boot_id", result.arg(5).tojstring());
    }

    // The Android classes the times script uses, as Lua sees them: objects made are tables of their arguments
    private static final String TIMES_STUBS = String.join("\n",
            "sdk = 34",
            "luajava = {",
            "  newInstance = function(name, ...) return { class = name, args = { ... } } end,",
            "  bindClass = function(name)",
            "    if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end",
            "    assert(name == 'android.os.Environment', name)",
            "    local root = { getPath = function(self) return '/storage/emulated/10' end }",
            "    return { getExternalStorageDirectory = function(self) return root end }",
            "  end }",
            "function hookof(method, class)",
            "  return { getMethodName = function(self) return method end,",
            "    getClassName = function(self) return class or 'libcore.io.BlockGuardOs' end }",
            "end",
            "function paramof(result, args)",
            "  return { result = result,",
            "    getArgument = function(self, index) return args[index + 1] end,",
            "    getException = function(self) return nil end,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value end }",
            "end",
            // An object of the system without the field, like the attributes of java.nio before the time made
            "function objectof(fields, has)",
            "  local class = { getDeclaredField = function(self, name) assert(has, name) return name end }",
            "  fields.getClass = function(self) return class end",
            "  return fields",
            "end",
            "function statof(time)",
            "  return { st_dev = 1, st_ino = 2, st_mode = 16889, st_nlink = 3, st_uid = 4, st_gid = 5, st_rdev = 6,",
            "    st_size = 7, st_atime = time, st_mtime = time, st_ctime = time, st_blksize = 8, st_blocks = 9 }",
            "end");

    // The folders of the shared storage of the user, with and without a slash, not the files in them
    @Test
    public void storageFolders() throws Exception {
        Globals globals = Scripts.load("storage_times", TIMES_STUBS);
        Varargs result = Scripts.run(globals,
                "local found = {}\n" +
                        "for _, path in ipairs(filter(nil)) do found[path] = true end\n" +
                        "return found['/storage/emulated/10'], found['/storage/emulated/10/DCIM/'],\n" +
                        "  found['/sdcard/Android/obb'], found['/storage/self/primary/Download'],\n" +
                        "  found['/storage/emulated'], found['/sdcard/DCIM/Camera'], found['/storage/emulated/0/DCIM']");
        for (int i = 1; i <= 5; i++)
            assertTrue(String.valueOf(i), result.arg(i).toboolean());
        assertTrue(result.arg(6).isnil());
        assertTrue(result.arg(7).isnil());
    }

    // Os.stat with the times of reproducible builds, in nanoseconds from Android 8.1, the rest as it is
    @Test
    public void storageStat() throws Exception {
        Globals globals = Scripts.load("storage_times", TIMES_STUBS);
        Varargs result = Scripts.run(globals,
                "local param = paramof(statof(1791913509))\n" +
                        "local restricted, old, new = after(hookof('stat'), param)\n" +
                        "local args = param.result.args\n" +
                        "local time = args[9]\n" +
                        "sdk = 26\n" +
                        "local older = paramof(statof(1791913509))\n" +
                        "after(hookof('lstat'), older)\n" +
                        "local faked = paramof(statof(1230768000))\n" +
                        "return restricted, old, new, param.result.class, #args, args[2], args[8], time.class,\n" +
                        "  time.args[1], time.args[2], args[10] == time and args[11] == time, older.result.args[9],\n" +
                        "  after(hookof('stat'), faked)");
        assertTrue(result.arg(1).toboolean());
        assertEquals("1791913509", result.arg(2).tojstring());
        assertEquals("1230768000", result.arg(3).tojstring());
        assertEquals("android.system.StructStat", result.arg(4).tojstring());
        assertEquals(13, result.arg(5).toint());
        assertEquals(2, result.arg(6).toint());
        assertEquals(7, result.arg(7).toint());
        assertEquals("android.system.StructTimespec", result.arg(8).tojstring());
        assertEquals(1230768000L, result.arg(9).tolong());
        assertEquals(0, result.arg(10).toint());
        assertTrue(result.arg(11).toboolean());
        assertEquals(1230768000L, result.arg(12).tolong());
        // Times which are faked already stay
        assertFalse(result.arg(13).toboolean());
    }

    // File.lastModified in milliseconds, 0 for a missing folder; java.nio with the time the folder was made,
    // of the attributes it reads the times into
    @Test
    public void storageLastModified() throws Exception {
        Globals globals = Scripts.load("storage_times", TIMES_STUBS);
        Varargs result = Scripts.run(globals,
                "local function nio(method) return hookof(method, 'sun.nio.fs.UnixNativeDispatcher') end\n" +
                        "local param = paramof(1791913509227)\n" +
                        "local missing = paramof(0)\n" +
                        "local attributes = objectof({ st_atime_sec = 1, st_atime_nsec = 2, st_mtime_sec = 3,\n" +
                        "  st_mtime_nsec = 4, st_ctime_sec = 5, st_ctime_nsec = 6, st_birthtime_sec = 7 }, true)\n" +
                        "local older = objectof({ st_atime_sec = 1, st_atime_nsec = 2, st_mtime_sec = 3,\n" +
                        "  st_mtime_nsec = 4, st_ctime_sec = 5, st_ctime_nsec = 6 }, false)\n" +
                        "return after(hookof('getLastModifiedTime'), param), param.result,\n" +
                        "  after(hookof('getLastModifiedTime'), missing), missing.result,\n" +
                        "  after(nio('stat'), paramof(nil, { 'path', attributes })), attributes.st_mtime_sec,\n" +
                        "  attributes.st_ctime_nsec,\n" +
                        "  attributes.st_birthtime_sec, after(nio('lstat'), paramof(nil, { 'path', older })), older.st_atime_sec,\n" +
                        "  older.st_birthtime_sec");
        assertTrue(result.arg(1).toboolean());
        assertEquals(1230768000000L, result.arg(2).tolong());
        assertFalse(result.arg(3).toboolean());
        assertEquals(0, result.arg(4).toint());
        assertTrue(result.arg(5).toboolean());
        assertEquals(1230768000L, result.arg(6).tolong());
        assertEquals(0, result.arg(7).toint());
        assertEquals(1230768000L, result.arg(8).tolong());
        assertTrue(result.arg(9).toboolean());
        assertEquals(1230768000L, result.arg(10).tolong());
        assertTrue(result.arg(11).isnil());
    }

    // Like an id of the kernel: version 4, the same for the same seed, different for another one
    @Test
    public void uuid() throws Throwable {
        String id = XParam.uuid("boot\ntest.app");
        UUID uuid = UUID.fromString(id);
        assertEquals(4, uuid.version());
        assertEquals(2, uuid.variant());
        assertEquals(id, XParam.uuid("boot\ntest.app"));
        assertNotEquals(id, XParam.uuid("boot\nother.app"));
    }
}
