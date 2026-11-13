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
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

// The files apps read which scripts give another text: the sizes of /proc/meminfo and the id of boot_id,
// and the times of the folders of the shared storage; the files of /sys which do not exist for apps
public class FileScriptsTest {
    // A param of an opened file, which tells the text the app reads then
    private static final String STUBS = String.join("\n",
            "function hookof(method)",
            "  return { getMethodName = function(self) return method end }",
            "end",
            // The file opened is the one of the script, unless another is given
            "function paramof(text, other)",
            "  return { text = text, set = false,",
            "    opens = function(self, file) return other == nil end,",
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

    // SwapTotal, a share of the memory with zram, and CommitLimit, half of the memory plus SwapTotal, give back
    // the exact total, so they grow with the faked total and give back that one; the log stays the one of the total
    // and available memory
    @Test
    public void meminfoDerivedTotals() throws Exception {
        Globals globals = Scripts.load("resources", STUBS);
        Varargs result = Scripts.run(globals,
                "local param = paramof('MemTotal:        2534116 kB\\nSwapTotal:       1900580 kB\\nCommitLimit:     3167636 kB\\n')\n" +
                        "local restricted, old, new = after(hookof('open'), param)\n" +
                        "return restricted, param.text, old, new");
        assertTrue(result.arg(1).toboolean());
        assertEquals(String.join("\n",
                "MemTotal:        3145728 kB",
                "SwapTotal:       2359287 kB",
                "CommitLimit:     3932148 kB",
                ""), result.arg(2).tojstring());
        assertEquals("2594934784", result.arg(3).tojstring());
        assertEquals("3221225472", result.arg(4).tojstring());
    }

    // 32-bit kernels with high memory: HighTotal and LowTotal add up to MemTotal and HighFree and LowFree to MemFree,
    // also when faked; the low memory has the size the kernel maps, so the high memory takes what the total grew,
    // and the free memory lost is taken from the free high memory, then from the free low memory
    @Test
    public void meminfoHighMemory() throws Exception {
        Globals globals = Scripts.load("resources", STUBS);
        Varargs result = Scripts.run(globals,
                "local param = paramof([[" + String.join("\n",
                        "MemTotal:        2023424 kB",
                        "MemFree:          147624 kB",
                        "HighTotal:       1290240 kB",
                        "HighFree:           1000 kB",
                        "LowTotal:         733184 kB",
                        "LowFree:          146624 kB",
                        "") + "]])\n" +
                        "return after(hookof('open'), param), param.text");
        assertTrue(result.arg(1).toboolean());
        assertEquals(String.join("\n",
                "MemTotal:        2097152 kB",
                "MemFree:          146484 kB",
                "HighTotal:       1363968 kB",
                "HighFree:              0 kB",
                "LowTotal:         733184 kB",
                "LowFree:          146484 kB",
                ""), result.arg(2).tojstring());
        String[] lines = result.arg(2).tojstring().split("\n");
        long[] kb = new long[lines.length];
        for (int i = 0; i < lines.length; i++)
            kb[i] = Long.parseLong(lines[i].replaceAll("\\D", ""));
        assertEquals(kb[0], kb[2] + kb[4]);
        assertEquals(kb[1], kb[3] + kb[5]);
    }

    // The lines makers add, like the ones of Samsung, are left out; the lines of the kernel stay, also those with
    // other characters in the name and without kB
    @Test
    public void meminfoVendorLines() throws Exception {
        Globals globals = Scripts.load("resources", STUBS);
        Varargs result = Scripts.run(globals,
                "local param = paramof([[" + String.join("\n",
                        "MemTotal:        3145728 kB",
                        "Active(anon):     812340 kB",
                        "Committed_AS:   45123456 kB",
                        "RbinTotal:        409600 kB",
                        "RbinAlloced:           0 kB",
                        "RbinPool:              0 kB",
                        "RbinFree:              0 kB",
                        "RbinCached:            0 kB",
                        "ZeroedFree:        12345 kB",
                        "HugepagePool:          0 kB",
                        "IonTotalCache:     65536 kB",
                        "HugePages_Total:       0",
                        "Hugepagesize:       2048 kB",
                        "DirectMap4k:       12345 kB",
                        "") + "]])\n" +
                        "return after(hookof('open'), param), param.text");
        assertTrue(result.arg(1).toboolean());
        assertEquals(String.join("\n",
                "MemTotal:        3145728 kB",
                "Active(anon):     812340 kB",
                "Committed_AS:   45123456 kB",
                "HugePages_Total:       0",
                "Hugepagesize:       2048 kB",
                "DirectMap4k:       12345 kB",
                ""), result.arg(2).tojstring());
    }

    // Only /proc/meminfo, also by other paths (see XParam.opens), the other hooks of the script have no filter;
    // another file with meminfo in its path keeps its text
    @Test
    public void meminfoFilter() throws Exception {
        Globals globals = Scripts.load("resources", STUBS);
        Varargs result = Scripts.run(globals,
                "local paths = filter(hookof('open'))\n" +
                        "local other = paramof([[" + MEMINFO + "]], '/data/meminfo')\n" +
                        "return #paths, paths[1], filter(hookof('statvfs')) == nil, after(hookof('open'), other), other.set");
        assertEquals(1, result.arg(1).toint());
        assertEquals("*meminfo*", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
    }

    // A SecureDirectoryStream opens /proc/meminfo by the descriptor of its folder and the name (openat), which the filter
    // does not see: a name with meminfo is the file by the link of the descriptor opened, read without telling StrictMode;
    // another file named meminfo, other names, which are not looked up, and a link which cannot be read keep their text
    @Test
    public void meminfoOfASecureDirectoryStream() throws Exception {
        Globals globals = Scripts.load("resources", STUBS);
        Varargs result = Scripts.run(globals,
                "local links = { [60] = '/proc/meminfo', [61] = '/data/user/0/test.app/files/meminfo' }\n" +
                        "POLICY, READS = 'app', 0\n" +
                        "local make = luajava.newInstance\n" +
                        "luajava.bindClass = function(name)\n" +
                        "  if name == 'android.os.StrictMode' then\n" +
                        "    return { allowThreadDiskReads = function(self) local old = POLICY; POLICY = 'reads'; return old end,\n" +
                        "      setThreadPolicy = function(self, policy) POLICY = policy end }\n" +
                        "  end\n" +
                        "  assert(name == 'android.system.Os', name)\n" +
                        "  return { readlink = function(self, link)\n" +
                        "    assert(POLICY == 'reads', POLICY)\n" +
                        "    READS = READS + 1\n" +
                        "    return links[tonumber(string.match(link, '^/proc/self/fd/(%d+)$'))] or error('ErrnoException')\n" +
                        "  end }\n" +
                        "end\n" +
                        // The name as bytes, a text here
                        "luajava.newInstance = function(name, ...)\n" +
                        "  if name == 'java.lang.String' then return ... end\n" +
                        "  return make(name, ...)\n" +
                        "end\n" +
                        "local function run(name, fd)\n" +
                        "  local param = paramof([[" + MEMINFO + "]])\n" +
                        "  param.getArgument = function(self, index) return ({ [0] = 57, name, 0, 0 })[index] end\n" +
                        "  param.getResult = function(self) return fd end\n" +
                        "  param.unhooked = function(self, fn) return fn() end\n" +
                        "  return tostring(after(hookof('openat'), param)) .. ':' .. tostring(param.set)\n" +
                        "end\n" +
                        "return run('meminfo', 60), run('meminfo', 61), run('stat', 60), run('meminfo', 62), READS, POLICY,\n" +
                        "  filter(hookof('openat')) == nil");
        assertEquals("true:true", result.arg(1).tojstring());
        assertEquals("false:false", result.arg(2).tojstring());
        assertEquals("false:false", result.arg(3).tojstring());
        assertEquals("false:false", result.arg(4).tojstring());
        assertEquals(3, result.arg(5).toint());
        assertEquals("app", result.arg(6).tojstring());
        assertTrue(result.arg(7).toboolean());
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

    // The sizes of java.nio FileStore like the ones of File: the total rounded up, the free space down
    @Test
    public void fileStoreSizes() throws Exception {
        Globals globals = Scripts.load("resources", STUBS);
        Varargs result = Scripts.run(globals,
                "local function size(method, value)\n" +
                        "  local param = { result = value, getResult = function(self) return self.result end,\n" +
                        "    setResult = function(self, v) self.result = v end }\n" +
                        "  after(hookof(method), param)\n" +
                        "  return param.result\n" +
                        "end\n" +
                        "return size('getTotalSpace', 118000000000), size('getUsableSpace', 7917000000), " +
                        "size('getUnallocatedSpace', 7999000000)");
        assertEquals(128000000000L, result.arg(1).tolong());
        assertEquals(7900000000L, result.arg(2).tolong());
        assertEquals(7900000000L, result.arg(3).tolong());
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
        assertEquals("*boot_id*", result.arg(5).tojstring());
    }

    // Only the paths of descriptors are filtered, by their link, its stat and length; the other hooks have a descriptor
    // or none; XParam answers
    @Test
    public void fakedFiles() throws Exception {
        Globals globals = Scripts.load("faked_files", STUBS);
        Varargs result = Scripts.run(globals,
                "local param = { unfakeFile = function(self) return true end }\n" +
                        "local other = { unfakeFile = function(self) return false end }\n" +
                        "return filter(hookof('readlink'))[1], filter(hookof('stat'))[1], filter(hookof('getLength'))[1],\n" +
                        "  filter(hookof('getLastModifiedTime'))[1],\n" +
                        "  filter(hookof('fstat')), filter(hookof('available')), filter(hookof('size')),\n" +
                        "  after(hookof('fstat'), param), after(hookof('readlink'), other)");
        for (int i = 1; i <= 4; i++)
            assertEquals("*/fd/*", result.arg(i).tojstring());
        for (int i = 5; i <= 7; i++)
            assertTrue(result.arg(i).isnil());
        assertTrue(result.arg(8).toboolean());
        assertFalse(result.arg(9).toboolean());
    }

    // The Android classes the times script uses, as Lua sees them: objects made are tables of their arguments
    private static final String TIMES_STUBS = String.join("\n",
            "sdk = 34",
            "luajava = {",
            // A file gives the path CANONICAL has for it, which only a read of the disk can tell
            "  newInstance = function(name, ...)",
            "    local args = { ... }",
            "    if name == 'java.io.File' then",
            "      return { getCanonicalPath = function(self)",
            "        assert(POLICY == 'reads', POLICY)",
            "        return CANONICAL[args[1]] or args[1]",
            "      end }",
            "    end",
            "    return { class = name, args = args }",
            "  end,",
            "  bindClass = function(name)",
            "    if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end",
            // The clocks, which put the boot at BOOT seconds, read by the script itself
            "    if name == 'java.lang.System' then",
            "      return { currentTimeMillis = function(self) assert(POLICY == 'reads', POLICY); return BOOT * 1000 + 5000 end }",
            "    end",
            "    if name == 'android.os.SystemClock' then",
            "      return { elapsedRealtime = function(self) return 5000 end }",
            "    end",
            // A descriptor is a table of its number (see descriptorof), of which copies are counted in DUPS; LINKS are
            // the links of /proc/self/fd, DEVICES the file systems of paths, of /data without one, and POLICY the
            // thread policy of StrictMode
            "    if name == 'android.os.ParcelFileDescriptor' then",
            "      return { dup = function(self, fd) DUPS = DUPS + 1; return fd end }",
            "    end",
            "    if name == 'android.system.Os' then",
            "      return { readlink = function(self, path)",
            "        assert(POLICY == 'reads', POLICY)",
            "        return LINKS[path] or error('ErrnoException')",
            "      end,",
            "      stat = function(self, path)",
            "        assert(POLICY == 'reads', POLICY)",
            "        return { st_dev = DEVICES[path] or 1 }",
            "      end }",
            "    end",
            "    if name == 'android.os.StrictMode' then",
            "      return { allowThreadDiskReads = function(self) local old = POLICY; POLICY = 'reads'; return old end,",
            "        setThreadPolicy = function(self, policy) POLICY = policy end }",
            "    end",
            "    assert(name == 'android.os.Environment', name)",
            "    local root = { getPath = function(self) return '/storage/emulated/10' end }",
            "    return { getExternalStorageDirectory = function(self) return root end }",
            "  end }",
            "function hookof(method, class)",
            "  return { getMethodName = function(self) return method end,",
            "    getClassName = function(self) return class or 'libcore.io.BlockGuardOs' end }",
            "end",
            // The app with its own APK and the packages it can see, see apptime
            "OWN = '/data/app/~~own==/com.example-a==/base.apk'",
            "DUPS = 0",
            "POLICY = 'app'",
            "LINKS = {}",
            "DEVICES = {}",
            "CANONICAL = {}",
            "BOOT = 1799999000",
            "function descriptorof(number) return { number = number, ['getInt$'] = function(self) return self.number end } end",
            "PACKAGES = { ['com.google.android.gms'] = { firstInstallTime = 1720723580123, applicationInfo = { flags = 129 } },",
            "  ['com.other'] = { firstInstallTime = 5, applicationInfo = { flags = 0 } } }",
            "CONTEXT = { getApplicationInfo = function(self) return { sourceDir = OWN } end,",
            "  getPackageManager = function(self)",
            "    return { getPackageInfo = function(self, name, flags)",
            "      assert(flags == 0, flags)",
            "      return PACKAGES[name] or error('NameNotFoundException')",
            "    end }",
            "  end }",
            "function paramof(result, args)",
            "  return { result = result,",
            "    getArgument = function(self, index) return (args or {})[index + 1] end,",
            "    getApplicationContext = function(self) return CONTEXT end,",
            "    isSystem = function(self) return false end,",
            "    unhooked = function(self, f) return f() end,",
            "    getSetting = function(self, name) return 'abcd' end,",
            "    uuidOf = function(self, seed) return (string.find(seed, '^abcd') and 'fffffff0' or '00000000') .. '-0000' end,",
            "    getException = function(self) return nil end,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value end }",
            "end",
            // An object of the system, with or without the time made, like the attributes of java.nio; LuaJ gives
            // nil for a field the class does not have, like the table for a key it does not have
            "function objectof(fields, has)",
            "  return fields",
            "end",
            "function statof(time)",
            "  return { st_dev = 1, st_ino = 2, st_mode = 16889, st_nlink = 3, st_uid = 4, st_gid = 5, st_rdev = 6,",
            "    st_size = 7, st_atime = time, st_mtime = time, st_ctime = time, st_blksize = 8, st_blocks = 9,",
            "    st_atim = { args = { time, 1 } }, st_mtim = { args = { time, 2 } }, st_ctim = { args = { time, 3 } } }",
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

    // The shared storage finds names in any case: the folders in lower and upper case, and paths with /./, // or
    // /../, which are compared resolved; other paths with them keep their times
    @Test
    public void storageOtherPaths() throws Exception {
        Globals globals = Scripts.load("storage_times", TIMES_STUBS);
        Varargs result = Scripts.run(globals,
                "local found = {}\n" +
                        "for _, path in ipairs(filter(nil)) do found[path] = true end\n" +
                        "local function faked(path) return after(hookof('stat'), paramof(statof(1791913509), { path })) end\n" +
                        "return found['/sdcard/download'], found['/storage/emulated/10/DCIM/.'], found['/sdcard/ANDROID/DATA'],\n" +
                        "  found['*/./*'], found['*//*'], found['*/../*'],\n" +
                        "  faked('/sdcard/DOWNLOAD'), faked('/storage/emulated/10/dcim/'), faked('/sdcard/Download/.'),\n" +
                        "  faked('/sdcard//Download'), faked('/sdcard/Android/../Download'), faked('/storage/self/primary/android/data'),\n" +
                        "  faked('/sdcard/./Download/x.jpg'), faked('/data/user/0/test.app//files'), faked('/sdcard/DownLoadx')");
        for (int i = 1; i <= 12; i++)
            assertTrue(String.valueOf(i), result.arg(i).toboolean());
        for (int i = 13; i <= 15; i++)
            assertFalse(String.valueOf(i), result.arg(i).toboolean());

        // By the links of /proc to the root, also of a thread and of the working directory, which the filter runs it for
        result = Scripts.run(globals,
                "local function faked(path) return after(hookof('stat'), paramof(statof(1791913509), { path })) end\n" +
                        "return faked('/proc/self/root/sdcard/Download'), faked('/proc/self/cwd/sdcard/DCIM'),\n" +
                        "  faked('/proc/123/task/124/root/storage/emulated/10/Download'), faked('/proc/self/root/proc/self/root/sdcard/Music'),\n" +
                        "  faked('/proc/self/root/data/user/0/test.app')");
        for (int i = 1; i <= 4; i++)
            assertTrue("link " + i, result.arg(i).toboolean());
        assertFalse(result.arg(5).toboolean());
    }

    // Os.stat with the times of reproducible builds, in nanoseconds, the rest as it is
    @Test
    public void storageStat() throws Exception {
        Globals globals = Scripts.load("storage_times", TIMES_STUBS);
        Varargs result = Scripts.run(globals,
                "local param = paramof(statof(1791913509), { '/sdcard/Download' })\n" +
                        "local restricted, old, new = after(hookof('stat'), param)\n" +
                        "local args = param.result.args\n" +
                        "local time = args[9]\n" +
                        "local faked = paramof(statof(1230768000), { '/sdcard/Download' })\n" +
                        "return restricted, old, new, param.result.class, #args, args[2], args[8], time.class,\n" +
                        "  time.args[1], time.args[2], args[10] == time and args[11] == time,\n" +
                        "  after(hookof('stat'), faked)");
        assertTrue(result.arg(1).toboolean());
        assertEquals("1791913509", result.arg(2).tojstring());
        assertEquals("1230768000", result.arg(3).tojstring());
        assertEquals("android.system.StructStat", result.arg(4).tojstring());
        assertEquals(13, result.arg(5).toint());
        // The inode of the fake Android ID, not the real one
        assertEquals(0xfffffff + 1000, result.arg(6).tolong());
        assertEquals(7, result.arg(7).toint());
        assertEquals("android.system.StructTimespec", result.arg(8).tojstring());
        assertEquals(1230768000L, result.arg(9).tolong());
        assertEquals(0, result.arg(10).toint());
        assertTrue(result.arg(11).toboolean());
        // Times which are faked already stay
        assertFalse(result.arg(12).toboolean());
    }

    // The spellings of a folder of the shared storage have one inode, like Android gives them; a link read by lstat
    // keeps an inode of its own
    @Test
    public void storageInodes() throws Exception {
        Globals globals = Scripts.load("storage_times", TIMES_STUBS);
        Varargs result = Scripts.run(globals,
                "local function inode(method, path, mode)\n" +
                        "  local stat = statof(1791913509)\n" +
                        "  stat.st_mode = mode or 16889\n" +
                        "  local param = paramof(stat, { path })\n" +
                        "  param.uuidOf = function(self, seed)\n" +
                        "    local sum = 0\n" +
                        "    for i = 1, #seed do sum = sum + string.byte(seed, i) * i end\n" +
                        "    return string.format('%08x', sum) .. '-0000'\n" +
                        "  end\n" +
                        "  after(hookof(method), param)\n" +
                        "  return param.result.args[2]\n" +
                        "end\n" +
                        "return inode('stat', '/sdcard/Download'), inode('stat', '/storage/emulated/10/Download'),\n" +
                        "  inode('stat', '/sdcard/download'), inode('stat', '/sdcard/Music'),\n" +
                        "  inode('lstat', '/sdcard', 41471), inode('stat', '/sdcard')");
        long download = result.arg(1).tolong();
        assertEquals(download, result.arg(2).tolong());
        assertEquals(download, result.arg(3).tolong());
        assertTrue(download != result.arg(4).tolong());
        assertTrue(result.arg(5).tolong() != result.arg(6).tolong());
    }

    // File.lastModified in milliseconds, 0 for a missing folder; java.nio with the time the folder was made,
    // of the attributes it reads the times into
    @Test
    public void storageLastModified() throws Exception {
        Globals globals = Scripts.load("storage_times", TIMES_STUBS);
        Varargs result = Scripts.run(globals,
                "local function nio(method) return hookof(method, 'sun.nio.fs.UnixNativeDispatcher') end\n" +
                        "local param = paramof(1791913509227, { '/storage/emulated/10/DCIM' })\n" +
                        "local missing = paramof(0, { '/storage/emulated/10/DCIM' })\n" +
                        "local attributes = objectof({ st_atime_sec = 1, st_atime_nsec = 2, st_mtime_sec = 3,\n" +
                        "  st_mtime_nsec = 4, st_ctime_sec = 5, st_ctime_nsec = 6, st_birthtime_sec = 7 }, true)\n" +
                        "local older = objectof({ st_atime_sec = 1, st_atime_nsec = 2, st_mtime_sec = 3,\n" +
                        "  st_mtime_nsec = 4, st_ctime_sec = 5, st_ctime_nsec = 6 }, false)\n" +
                        "return after(hookof('getLastModifiedTime'), param), param.result,\n" +
                        "  after(hookof('getLastModifiedTime'), missing), missing.result,\n" +
                        "  after(nio('stat'), paramof(nil, { '/sdcard/Music', attributes })), attributes.st_mtime_sec,\n" +
                        "  attributes.st_ctime_nsec,\n" +
                        "  attributes.st_birthtime_sec, after(nio('lstat'), paramof(nil, { '/sdcard/Music', older })), older.st_atime_sec,\n" +
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

    // Os.fstat by the link of its descriptor: a folder of the shared storage like by Os.stat, with the inode of the same
    // folder, and the APKs of other apps; the APK of the app itself, a socket and a descriptor of which the link cannot
    // be read keep their answer, also a socket the link of which would name a folder, the link of which is not read.
    // The number is read from the descriptor of the app, which is never copied, since closing a copy of a file would
    // release the locks of the app on it, and the app has its thread policy back; LuaJ finds the number of a Java
    // object by its method getInt$. The filter has no paths for a descriptor, so the script runs for each call
    @Test
    public void storageFstat() throws Exception {
        Globals globals = Scripts.load("storage_times", TIMES_STUBS);
        globals.set("java", CoerceJavaToLua.coerce(new Descriptor()));
        Varargs result = Scripts.run(globals,
                "LINKS['/proc/self/fd/9'] = '/storage/emulated/10/DCIM'\n" +
                        "LINKS['/proc/self/fd/15'] = '/storage/emulated/10/Pictures'\n" +
                        "LINKS['/proc/self/fd/10'] = 'socket:[4711]'\n" +
                        "LINKS['/proc/self/fd/11'] = OWN\n" +
                        "LINKS['/proc/self/fd/12'] = '/data/app/~~x==/com.other-y==/base.apk'\n" +
                        "LINKS['/proc/self/fd/14'] = '/storage/emulated/10/Music'\n" +
                        "local function keyed(param)\n" +
                        "  param.uuidOf = function(self, seed)\n" +
                        "    local sum = 0\n" +
                        "    for i = 1, #seed do sum = sum + string.byte(seed, i) * i end\n" +
                        "    return string.format('%08x', sum) .. '-0000'\n" +
                        "  end\n" +
                        "  return param\n" +
                        "end\n" +
                        "local function fstat(number, mode)\n" +
                        "  local stat = statof(1791913509)\n" +
                        "  stat.st_mode = mode or stat.st_mode\n" +
                        "  local param = keyed(paramof(stat, { descriptorof(number) }))\n" +
                        "  if not after(hookof('fstat', 'android.system.Os'), param) then return 'real' end\n" +
                        "  return param.result.args[9].args[1], param.result.args[2]\n" +
                        "end\n" +
                        "local stat = keyed(paramof(statof(1791913509), { '/sdcard/DCIM' }))\n" +
                        "after(hookof('stat'), stat)\n" +
                        "local time, inode = fstat(9)\n" +
                        "local _, music = fstat(14)\n" +
                        "local socket = statof(1791913509)\n" +
                        "socket.st_mode = 49663\n" +
                        "local named = paramof(socket, { descriptorof(9) })\n" +
                        "local pictures = paramof(statof(1791913509), { java })\n" +
                        "after(hookof('fstat', 'android.system.Os'), pictures)\n" +
                        "return time, inode == stat.result.args[2], inode ~= music, fstat(10), fstat(11, 33188), fstat(12, 33188),\n" +
                        "  fstat(13), DUPS, POLICY, filter(hookof('fstat')), after(hookof('fstat', 'android.system.Os'), named),\n" +
                        "  pictures.result.args[9].args[1]");
        assertEquals(1230768000L, result.arg(1).tolong());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertEquals("real", result.arg(4).tojstring());
        assertEquals("real", result.arg(5).tojstring());
        assertEquals(1230768000L, result.arg(6).tolong());
        assertEquals("real", result.arg(7).tojstring());
        assertEquals(0, result.arg(8).toint());
        assertEquals("app", result.arg(9).tojstring());
        assertTrue(result.arg(10).isnil());
        assertFalse(result.arg(11).toboolean());
        assertEquals(1230768000L, result.arg(12).tolong());
    }

    // A FileDescriptor of Android as LuaJ sees it, with the hidden method which gives its number
    public static class Descriptor {
        @SuppressWarnings("unused")
        public int getInt$() {
            return 15;
        }
    }

    // The folders at the top of /data, which are made when the device is set up, by each spelling, and /data itself,
    // which keeps the inode of the root of its file system; the folder of the data of the apps and the paths below keep
    // their times, also /data/user and /data/user_de when they are the tmpfs of the process of an app for Android 11 and
    // later, which Android mounts with the data of apps kept apart
    @Test
    public void dataFolders() throws Exception {
        Globals globals = Scripts.load("storage_times", TIMES_STUBS);
        Varargs result = Scripts.run(globals,
                "DEVICES['/data/user'] = 2\n" +
                        "DEVICES['/data/user_de'] = 3\n" +
                        "local found = {}\n" +
                        "for _, path in ipairs(filter(nil)) do found[path] = true end\n" +
                        "local function faked(path) return after(hookof('stat'), paramof(statof(1791913509), { path })) end\n" +
                        "local modified = paramof(1791913509227, { '/data/fonts' })\n" +
                        "after(hookof('getLastModifiedTime'), modified)\n" +
                        "local user = paramof(1791913509227, { '/data/user' })\n" +
                        "after(hookof('getLastModifiedTime'), user)\n" +
                        "local data = paramof(statof(1791913509), { '/data/.' })\n" +
                        "after(hookof('lstat'), data)\n" +
                        "return found['/data/local'], found['/data/misc/'], found['/data/fonts/.'], found['/data/.'],\n" +
                        "  found['/data/data'], found['/data/local/tmp'], faked('/data/local'), faked('/data/misc/'),\n" +
                        "  faked('/data//system_ce'), faked('/data/data'), faked('/data/local/tmp'), faked('/data/user/0'),\n" +
                        "  faked('/data/user'), faked('/data/user_de/'), modified.result, user.result,\n" +
                        "  data.result.args[9].args[1], data.result.args[2], POLICY");
        for (int i = 1; i <= 4; i++)
            assertTrue(String.valueOf(i), result.arg(i).toboolean());
        for (int i = 5; i <= 6; i++)
            assertTrue(String.valueOf(i), result.arg(i).isnil());
        for (int i = 7; i <= 9; i++)
            assertTrue(String.valueOf(i), result.arg(i).toboolean());
        for (int i = 10; i <= 14; i++)
            assertFalse(String.valueOf(i), result.arg(i).toboolean());
        assertEquals(1230768000000L, result.arg(15).tolong());
        assertEquals(1791913509227L, result.arg(16).tolong());
        assertEquals(1230768000L, result.arg(17).tolong());
        assertEquals(2, result.arg(18).toint());
        assertEquals("app", result.arg(19).tojstring());
    }

    // A time of a folder at the top of /data from the current boot stays, since it tells the boot, not the setup, and the
    // folder gets the inode of the app, by stat, lstat, fstat and java.nio: all times of a folder changed since the boot,
    // the change of status of a folder init sets the mode of on each boot up to Android 10, with the time of its last
    // change faked. /data keeps its inode too, so all of it when all its times are of the boot, as File.lastModified
    @Test
    public void dataFoldersSinceBoot() throws Exception {
        Globals globals = Scripts.load("storage_times", TIMES_STUBS);
        Varargs result = Scripts.run(globals,
                "LINKS['/proc/self/fd/9'] = '/data/system'\n" +
                        "local function stat(method, path, atime, mtime, ctime)\n" +
                        "  local result = statof(mtime)\n" +
                        "  result.st_atime, result.st_atim = atime, { args = { atime, 1 } }\n" +
                        "  result.st_ctime, result.st_ctim = ctime, { args = { ctime, 3 } }\n" +
                        "  local param = paramof(result, { path })\n" +
                        "  if not after(hookof(method), param) then return 'real' end\n" +
                        "  local args = param.result.args\n" +
                        "  return args[9].args[1], args[10].args[1], args[11].args[1], args[11].args[2], args[2]\n" +
                        "end\n" +
                        "local data = stat('stat', '/data', BOOT + 30, BOOT + 30, BOOT + 30)\n" +
                        "local system = { stat('lstat', '/data/system/', BOOT + 10, BOOT + 30, BOOT + 30) }\n" +
                        "local booted = { stat('stat', '/data/misc', BOOT, BOOT, BOOT) }\n" +
                        "local older = { stat('stat', '/data/system', BOOT - 1, BOOT - 1, BOOT - 1) }\n" +
                        "local status = { stat('lstat', '/data/local', BOOT - 100, BOOT - 100, BOOT + 10) }\n" +
                        "local root = { stat('stat', '/data', BOOT - 100, BOOT + 30, BOOT + 30) }\n" +
                        "local modified = paramof((BOOT + 30) * 1000 + 227, { '/data/system' })\n" +
                        "local attributes = objectof({ st_ino = 2, st_mode = 16889, st_atime_sec = BOOT + 10,\n" +
                        "  st_mtime_sec = BOOT - 100, st_ctime_sec = BOOT + 30 }, true)\n" +
                        "local nio = after(hookof('stat', 'sun.nio.fs.UnixNativeDispatcher'),\n" +
                        "  paramof(nil, { '/data/misc', attributes }))\n" +
                        "local fstat = paramof(statof(BOOT + 30), { descriptorof(9) })\n" +
                        "after(hookof('fstat', 'android.system.Os'), fstat)\n" +
                        "return data, system[1], system[2], system[3], system[4], system[5], booted[2], older[2], older[3],\n" +
                        "  status[1], status[2], status[3], status[5], root[1], root[2], root[5],\n" +
                        "  after(hookof('getLastModifiedTime'), modified), modified.result, nio, attributes.st_atime_sec,\n" +
                        "  attributes.st_mtime_sec, attributes.st_ctime_sec, attributes.st_ino, fstat.result.args[10].args[1],\n" +
                        "  fstat.result.args[2], POLICY");
        long fake = 1230768000L, inode = 0xfffffff + 1000;
        // /data itself
        assertEquals("real", result.arg(1).tojstring());
        // lstat of /data/system/: the real times, each its own, and the inode of the app
        assertEquals(1799999010L, result.arg(2).tolong());
        assertEquals(1799999030L, result.arg(3).tolong());
        assertEquals(1799999030L, result.arg(4).tolong());
        assertEquals(3, result.arg(5).toint());
        assertEquals(inode, result.arg(6).tolong());
        // Changed in the second of the boot, and before it
        assertEquals(1799999000L, result.arg(7).tolong());
        assertEquals(fake, result.arg(8).tolong());
        assertEquals(fake, result.arg(9).tolong());
        // The mode set again on this boot, of Android 10 and before
        assertEquals(fake, result.arg(10).tolong());
        assertEquals(fake, result.arg(11).tolong());
        assertEquals(1799999010L, result.arg(12).tolong());
        assertEquals(inode, result.arg(13).tolong());
        // /data read last before the boot, with its own inode
        assertEquals(fake, result.arg(14).tolong());
        assertEquals(1799999030L, result.arg(15).tolong());
        assertEquals(2, result.arg(16).toint());
        assertFalse(result.arg(17).toboolean());
        assertEquals(1799999030227L, result.arg(18).tolong());
        // java.nio, each time by itself
        assertTrue(result.arg(19).toboolean());
        assertEquals(1799999010L, result.arg(20).tolong());
        assertEquals(fake, result.arg(21).tolong());
        assertEquals(1799999030L, result.arg(22).tolong());
        assertEquals(inode, result.arg(23).tolong());
        assertEquals(1799999030L, result.arg(24).tolong());
        assertEquals(inode, result.arg(25).tolong());
        assertEquals("app", result.arg(26).tojstring());
    }

    // Os.stat, File.lastModified and java.nio by the link of a descriptor of the app, also of its process by number, of
    // a thread and by /dev/fd, and by a path on through the descriptor of a folder: like by the path of the folder or
    // APK it leads to, with the same inode; lstat of the link itself, a socket and the APK of the app keep their answer.
    // The path is resolved by the file system, without a copy of a descriptor, and the app has its thread policy back
    @Test
    public void storageDescriptorPaths() throws Exception {
        Globals globals = Scripts.load("storage_times", TIMES_STUBS);
        Varargs result = Scripts.run(globals,
                "for _, link in ipairs({ '/proc/self/fd/9', '/dev/fd/9', '/proc/4711/fd/9', '/proc/self/task/4712/fd/9' }) do\n" +
                        "  CANONICAL[link] = '/storage/emulated/10/DCIM'\n" +
                        "end\n" +
                        "CANONICAL['/proc/self/fd/16/download/'] = '/storage/emulated/10/Download'\n" +
                        "CANONICAL['/proc/self/fd/9/..'] = '/storage/emulated/10'\n" +
                        "CANONICAL['/proc/self/fd/10'] = '/proc/4711/fd/socket:[4711]'\n" +
                        "CANONICAL['/proc/self/fd/11'] = OWN\n" +
                        "CANONICAL['/proc/self/fd/12'] = '/data/app/~~x==/com.other-y==/base.apk'\n" +
                        "local function keyed(param)\n" +
                        "  param.uuidOf = function(self, seed)\n" +
                        "    local sum = 0\n" +
                        "    for i = 1, #seed do sum = sum + string.byte(seed, i) * i end\n" +
                        "    return string.format('%08x', sum) .. '-0000'\n" +
                        "  end\n" +
                        "  return param\n" +
                        "end\n" +
                        "local function stat(method, path, mode)\n" +
                        "  local result = statof(1791913509)\n" +
                        "  result.st_mode = mode or result.st_mode\n" +
                        "  local param = keyed(paramof(result, { path }))\n" +
                        "  if not after(hookof(method), param) then return 'real' end\n" +
                        "  return param.result.args[9].args[1], param.result.args[2]\n" +
                        "end\n" +
                        "local found = {}\n" +
                        "for _, path in ipairs(filter(hookof('stat'))) do found[path] = true end\n" +
                        "local _, dcim = stat('stat', '/sdcard/DCIM')\n" +
                        "local _, download = stat('stat', '/sdcard/Download')\n" +
                        "local time, inode = stat('stat', '/proc/self/fd/9')\n" +
                        "local _, by = stat('lstat', '/proc/self/fd/16/download/')\n" +
                        "local modified = paramof(1791913509227, { '/dev/fd/9' })\n" +
                        "after(hookof('getLastModifiedTime'), modified)\n" +
                        "local attributes = objectof({ st_mode = 16889, st_atime_sec = 1, st_mtime_sec = 3, st_ctime_sec = 5 }, true)\n" +
                        "after(hookof('stat', 'sun.nio.fs.UnixNativeDispatcher'), paramof(nil, { '/proc/4711/fd/9', attributes }))\n" +
                        "return found['*/fd/*'], time, inode == dcim, by == download, stat('stat', '/proc/self/task/4712/fd/9'),\n" +
                        "  modified.result, attributes.st_mtime_sec, stat('lstat', '/proc/self/fd/9', 41471),\n" +
                        "  stat('stat', '/proc/self/fd/10'),\n" +
                        "  stat('stat', '/proc/self/fd/11', 33188), stat('stat', '/proc/self/fd/12', 33188), DUPS, POLICY,\n" +
                        "  stat('stat', '/proc/self/fd/9/..')");
        assertTrue(result.arg(1).toboolean());
        assertEquals(1230768000L, result.arg(2).tolong());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertEquals(1230768000L, result.arg(5).tolong());
        assertEquals(1230768000000L, result.arg(6).tolong());
        assertEquals(1230768000L, result.arg(7).tolong());
        for (int i = 8; i <= 10; i++)
            assertEquals(String.valueOf(i), "real", result.arg(i).tojstring());
        assertEquals(1230768000L, result.arg(11).tolong());
        assertEquals(0, result.arg(12).toint());
        assertEquals("app", result.arg(13).tojstring());
        // The folder the descriptor of DCIM is in, which only the file system can tell
        assertEquals(1230768000L, result.arg(14).tolong());
    }

    // The files of other apps in /data/app get the times of their package info: the time of the firmware for updates
    // of apps of the system, the time of reproducible builds for others, of packages which are gone and for
    // /data/app itself; the files of the app itself keep theirs, on Android 11 and later and before
    @Test
    public void appTimes() throws Exception {
        Globals globals = Scripts.load("storage_times", TIMES_STUBS);
        Varargs result = Scripts.run(globals,
                "local function seconds(path)\n" +
                        "  local param = paramof(statof(1791930132), { path })\n" +
                        "  if not after(hookof('stat'), param) then return 'real' end\n" +
                        "  return param.result.args[9].args[1] .. '.' .. param.result.args[9].args[2]\n" +
                        "end\n" +
                        "local modified = paramof(1791930132512, { '/data/app/~~g==/com.google.android.gms-z==/base.apk' })\n" +
                        "after(hookof('getLastModifiedTime'), modified)\n" +
                        "local found = {}\n" +
                        "for _, path in ipairs(filter(nil)) do found[path] = true end\n" +
                        "return seconds('/data/app/~~x==/com.other-y==/base.apk'),\n" +
                        "  seconds('/data/app/~~a-b==/com.google.android.gms-x-y_z==/split_config.en.apk'),\n" +
                        "  seconds('/data/app/~~x=='), seconds('/data/app'), seconds('/data/app/com.other-1/base.apk'),\n" +
                        "  seconds('/data/app/~~q==/com.gone-r==/base.apk'), seconds(OWN),\n" +
                        "  seconds('/data/app/~~own==/com.example-a==/split_config.arm64_v8a.apk'),\n" +
                        "  seconds('/data/app/~~own==/com.example-a==/oat/arm64/base.odex'), seconds('/data/app/~~own=='),\n" +
                        "  modified.result, found['/data/app'], found['/data/app/*']");
        assertEquals("1230768000.0", result.arg(1).tojstring());
        assertEquals("1720723580.123000000", result.arg(2).tojstring());
        assertEquals("1230768000.0", result.arg(3).tojstring());
        assertEquals("1230768000.0", result.arg(4).tojstring());
        assertEquals("1230768000.0", result.arg(5).tojstring());
        assertEquals("1230768000.0", result.arg(6).tojstring());
        for (int i = 7; i <= 10; i++)
            assertEquals(String.valueOf(i), "real", result.arg(i).tojstring());
        assertEquals(1720723580123L, result.arg(11).tolong());
        assertTrue(result.arg(12).toboolean());
        assertTrue(result.arg(13).toboolean());
    }

    // The lines of /proc/cpuinfo the kernel of Linux prints for each processor stay, others, like the Hardware and
    // Serial of kernels of vendors, are left out; a file with only known lines, like of x86, stays as it is
    @Test
    public void cpuinfo() throws Exception {
        Globals globals = Scripts.load("cpuinfo", STUBS);
        String core = "processor\t: %d\nBogoMIPS\t: 38.40\n" +
                "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop\n" +
                "CPU implementer\t: 0x51\nCPU architecture: 8\nCPU variant\t: 0xd\nCPU part\t: 0x805\nCPU revision\t: 14\n\n";
        String cores = String.format(core, 0) + String.format(core, 1);
        String snapdragon = cores + "Hardware\t: Qualcomm Technologies, Inc SM8150\nSerial\t\t: 00000000a1b2c3d4\n";
        String x86 = "processor\t: 0\nvendor_id\t: GenuineIntel\nmodel name\t: Android virtual processor\n" +
                "flags\t\t: fpu sse sse2\nbugs\t\t: spectre_v1\nbogomips\t: 5836.80\npower management:\n\n";
        Varargs result = Scripts.run(globals,
                "local param = paramof([[" + snapdragon + "]])\n" +
                        "local x86 = paramof([[" + x86 + "]])\n" +
                        "local other = paramof([[" + snapdragon + "]], true)\n" +
                        "local restricted, left = after(nil, param)\n" +
                        "return restricted, left, param.text, after(nil, x86), x86.set, after(nil, other), other.set,\n" +
                        "  filter(nil)[1]");
        // Without the lines of the cores and the chip, 8 processors like Runtime.availableProcessors
        StringBuilder eight = new StringBuilder();
        for (int i = 0; i < 8; i++)
            eight.append("processor\t: ").append(i).append("\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 " +
                    "atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop\nCPU architecture: 8\n\n");
        assertTrue(result.arg(1).toboolean());
        assertEquals("12", result.arg(2).tojstring());
        assertEquals(eight.toString(), result.arg(3).tojstring());
        // x86: the model and bogomips go, one processor becomes 8
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        assertEquals("*cpuinfo*", result.arg(8).tojstring());
    }

    // The hooks and params of the hardware files script, with the links of /sys as resolve sees them;
    // objects made are tables of their arguments
    private static final String HARDWARE_STUBS = String.join("\n",
            "luajava = {",
            "  newInstance = function(name, ...)",
            "    local args = { ... }",
            "    if name == 'java.io.File' then",
            "      return { getParent = function(self) return string.match(args[1], '^(.*)/[^/]*$') end }",
            "    end",
            "    return { class = name, args = args }",
            "  end,",
            "  bindClass = function(name)",
            "    assert(name == 'java.lang.reflect.Array', name)",
            "    return { newInstance = function(self, class, length) return { class = class, length = length } end }",
            "  end,",
            "  createProxy = function(interface, methods) return { interface = interface, accept = methods.accept } end }",
            "LINKS = {",
            "  ['/sys/block/mmcblk0/device/cid'] = '/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001/cid',",
            "  ['/sys/block/mmcblk0/device/type'] = '/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001/type',",
            "  ['/sys/class/sec/tsp'] = '/sys/devices/virtual/sec/tsp',",
            "  ['/sys/class/sec/tsp/fod_pos'] = '/sys/devices/virtual/sec/tsp/fod_pos' }",
            "function resolve(path) return LINKS[path] or path end",
            // Android 14, unless a test says otherwise
            "function sdkversion() return 34 end",
            "function hookof(group, class, method, returnType)",
            "  return { getGroup = function(self) return group end,",
            "    getClassName = function(self) return class or 'libcore.io.BlockGuardOs' end,",
            "    getMethodName = function(self) return method or 'open' end,",
            "    getReturnType = function(self) return returnType end }",
            "end",
            "function paramof(argument, result, second)",
            "  return { result = result, early = false,",
            "    getArgument = function(self, index) if index == 0 then return argument else return second end end,",
            "    setArgument = function(self, index, value) self.argument = { index = index, value = value } end,",
            "    getException = function(self) return nil end,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value self.early = true end,",
            "    findClass = function(self, name) return name end }",
            "end",
            "function namesof(...)",
            "  local names = { ... }",
            "  names['length'] = select('#', ...)",
            "  return names",
            "end");

    // A file the app could not reach gets the answer of the kernel: a fake one only for hidden names, like tun0 next
    // to all of /sys/class/net which SELinux denies, tells the hook is there; a file which does not exist is not hidden
    @Test
    public void hiddenFilesAreOnlyThoseTheAppCouldReach() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local present = { ['/system_ext/etc/build.prop'] = true, ['/system/build.prop'] = true,\n" +
                        "  ['/fstab.qcom'] = true, ['/fstab.ranchu'] = true }\n" +
                        "local readable = { ['/system_ext/etc/build.prop'] = true, ['/fstab.qcom'] = true }\n" +
                        "present['/sys/block/mmcblk0/device/cid'] = true\n" +
                        "present['/sys/devices/system/chip-id/unique_id'] = true\n" +
                        "readable['/sys/devices/system/chip-id/unique_id'] = true\n" +
                        "local file = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, path)\n" +
                        "  if name == 'java.io.File' then return { exists = function() return present[path] end,\n" +
                        "    canRead = function() return readable[path] == true end } end\n" +
                        "  return file(name, path)\n" +
                        "end\n" +
                        "local function param() return { unhooked = function(self, f) return f() end } end\n" +
                        "present['/proc/net/arp'] = true\n" +
                        "return hidden(hookof('Read.Network'), '/proc/net/arp', param()),\n" +
                        "  hidden(hookof('Read.VPN'), '/sys/class/net/tun0', param()),\n" +
                        "  hidden(hookof('Read.Debugging'), '/system/xbin/su', param()),\n" +
                        "  hidden(hookof('Use.Tracking'), '/system_ext/etc/build.prop', param()),\n" +
                        "  hidden(hookof('Use.Tracking'), '/system/build.prop', param()),\n" +
                        "  hidden(hookof('Use.Tracking'), '/fstab.qcom', param()),\n" +
                        "  hidden(hookof('Use.Tracking'), '/fstab.ranchu', param()),\n" +
                        "  hidden(hookof('Read.Identifiers'), '/sys/block/mmcblk0/device/cid', param()),\n" +
                        "  hidden(hookof('Read.Identifiers'), '/sys/devices/system/chip-id/unique_id', param())");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        // A property file every device has but apps cannot read, like /system/build.prop of Android 14 (EACCES)
        assertFalse(result.arg(5).toboolean());
        // fstab at the root: hidden when the app can read it, else EACCES as for build.prop
        assertTrue(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        // An id SELinux lets the app find but not read stays (EACCES), with the files of its folder; a readable one goes
        assertFalse(result.arg(8).toboolean());
        assertTrue(result.arg(9).toboolean());
    }

    // The files of /proc/net are denied the way SELinux can deny reading them, not missing: opening them and access
    // for reading fail with EACCES, by Os, java.io and java.nio, while access for whether they exist, stat and the other
    // calls answer for real; Read.VPN denies them too and keeps its folders of /sys missing. A folder of /proc/net, which
    // File.list still lists, and a file the app cannot read, like on Android 10 and later, get the real answer.
    @Test
    public void networkFilesAreDeniedNotMissing() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local present = { ['/proc/net/arp'] = true, ['/proc/net/route'] = true, ['/proc/net/dev'] = true,\n" +
                        "  ['/proc/self/net/dev'] = true, ['/proc/net/stat'] = true, ['/proc/net/tcp'] = true,\n" +
                        "  ['/sys/class/net/tun0'] = true }\n" +
                        "local readable = { ['/proc/net/arp'] = true, ['/proc/net/route'] = true, ['/proc/net/dev'] = true,\n" +
                        "  ['/proc/self/net/dev'] = true, ['/proc/net/stat'] = true }\n" +
                        "local new = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, path, ...)\n" +
                        "  if name == 'java.io.File' then return { exists = function() return present[path] == true end,\n" +
                        "    canRead = function() return readable[path] == true end,\n" +
                        "    isDirectory = function() return path == '/proc/net/stat' end } end\n" +
                        "  return new(name, path, ...)\n" +
                        "end\n" +
                        "local results = {}\n" +
                        "local function call(group, class, method, path, mode)\n" +
                        "  local p = paramof(path, nil, mode)\n" +
                        "  p.unhooked = function(self, fn) return fn() end\n" +
                        "  local restricted = before(hookof(group, class, method), p)\n" +
                        "  local value = p.result\n" +
                        "  if type(value) == 'table' then value = value.class .. ' ' .. table.concat(value.args, ' ') end\n" +
                        "  results[#results + 1] = (restricted and 'R' or '-') .. (p.early and 'E' or '-') ..\n" +
                        "    (p.argument and 'A' or '-') .. ' ' .. tostring(value)\n" +
                        "end\n" +
                        "local os, io, nio = 'libcore.io.BlockGuardOs', 'java.io.UnixFileSystem', 'sun.nio.fs.UnixNativeDispatcher'\n" +
                        "call('Read.Network', os, 'open', '/proc/net/arp', 0)\n" +
                        "call('Read.Network', nio, 'open', '/proc/net/route', 0)\n" +
                        "call('Read.Network', io, 'checkAccess', '/proc/net/arp', 4)\n" +
                        "call('Read.Network', io, 'checkAccess', '/proc/net/arp', 8)\n" +
                        "call('Read.Network', 'android.system.Os', 'access', '/proc/net/arp', 4)\n" +
                        "call('Read.Network', os, 'access', '/proc/net/arp', 0)\n" +
                        "call('Read.Network', nio, 'access', '/proc/net/arp', 4)\n" +
                        "call('Read.Network', os, 'open', '/proc/net/stat', 0)\n" +
                        "call('Read.Network', io, 'checkAccess', '/proc/net/stat', 4)\n" +
                        "call('Read.Network', os, 'open', '/proc/net/tcp', 0)\n" +
                        "call('Read.VPN', os, 'open', '/proc/net/dev', 0)\n" +
                        "call('Read.VPN', io, 'checkAccess', '/proc/self/net/dev', 4)\n" +
                        "call('Read.VPN', os, 'stat', '/proc/net/dev')\n" +
                        "call('Read.VPN', io, 'getLength', '/proc/net/dev')\n" +
                        "call('Read.VPN', io, 'checkAccess', '/proc/net/dev', 8)\n" +
                        "call('Read.VPN', os, 'open', '/proc/net/tcp', 0)\n" +
                        "call('Read.VPN', os, 'stat', '/sys/class/net/tun0')\n" +
                        "return table.concat(results, '|')");
        String[] expected = {
                "RE- android.system.ErrnoException open 13", "RE- sun.nio.fs.UnixException 13", "RE- false", "--- nil",
                "RE- android.system.ErrnoException access 13", "--- nil", "RE- sun.nio.fs.UnixException 13",
                "--- nil", "--- nil", "--- nil",
                "RE- android.system.ErrnoException open 13", "RE- false", "--- nil", "--- nil", "--- nil", "--- nil",
                // The folder of a VPN interface stays missing, by the real call with an empty path
                "R-A nil"};
        assertEquals(String.join("|", expected), result.arg1().tojstring());
    }

    // Of Read.VPN the names of the interfaces of VPNs in /proc/net and /proc/sys/net do not exist, like tun0 of
    // dev_snmp6, also under the file of tun0 (not ENOTDIR) and in listings, while the names of the other interfaces,
    // like wlan0, stay and are denied like the other files of /proc/net. Read.Network leaves tun0 to the hooks of
    // Read.VPN when those hide it, whichever runs first, and denies it when they do not. A file the app opened already,
    // like /proc/self/fd/9 of /proc/net/dev, gets the real answer for both groups, and a file which went away too.
    @Test
    public void vpnInterfaceNamesAreMissing() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local present = { ['/proc/net/dev_snmp6'] = true, ['/proc/net/dev_snmp6/tun0'] = true,\n" +
                        "  ['/proc/net/dev_snmp6/wlan0'] = true, ['/proc/net/xt_qtaguid/iface_stat/tun0/rx_bytes'] = true,\n" +
                        "  ['/proc/sys/net/ipv4/conf/tun0'] = true, ['/proc/sys/net/ipv6/neigh/tun0'] = true,\n" +
                        "  ['/proc/self/fd/9'] = true }\n" +
                        "local readable = { ['/proc/net/dev_snmp6'] = true, ['/proc/net/dev_snmp6/tun0'] = true,\n" +
                        "  ['/proc/net/dev_snmp6/wlan0'] = true, ['/proc/net/xt_qtaguid/iface_stat/tun0/rx_bytes'] = true,\n" +
                        "  ['/proc/self/fd/9'] = true }\n" +
                        "local folders = { ['/proc/net/dev_snmp6'] = true, ['/proc/sys/net/ipv4/conf/tun0'] = true,\n" +
                        "  ['/proc/sys/net/ipv6/neigh/tun0'] = true }\n" +
                        "LINKS['/proc/self/fd/9'] = '/proc/123/net/dev'\n" +
                        "local vpn, unhooked = true, false\n" +
                        "local new = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, path, ...)\n" +
                        "  if name == 'java.io.File' then return { exists = function() return present[path] == true end,\n" +
                        "    canRead = function() return readable[path] == true end,\n" +
                        "    isDirectory = function() return folders[path] == true end,\n" +
                        // The hooks of Read.VPN, when it is restricted, answer false for its names
                        "    isFile = function() return present[path] == true and not folders[path] and\n" +
                        "      (unhooked or not vpn or not string.find(path, 'tun0', 1, true)) end } end\n" +
                        "  return new(name, path, ...)\n" +
                        "end\n" +
                        "local results = {}\n" +
                        "local function call(group, class, method, path, mode, names)\n" +
                        "  local p = paramof(path, names, mode)\n" +
                        "  p.unhooked = function(self, fn) unhooked = true local value = fn() unhooked = false return value end\n" +
                        "  local restricted = (names and after or before)(hookof(group, class, method), p)\n" +
                        "  local value = p.result\n" +
                        "  if type(value) == 'table' and value.args then value = value.class .. ' ' .. table.concat(value.args, ' ')\n" +
                        "  elseif type(value) == 'table' then value = table.concat(value, ' ') end\n" +
                        "  results[#results + 1] = (restricted and 'R' or '-') .. (p.early and 'E' or '-') ..\n" +
                        "    (p.argument and 'A' or '-') .. ' ' .. tostring(value)\n" +
                        "end\n" +
                        "local os, io, nio = 'libcore.io.BlockGuardOs', 'java.io.UnixFileSystem', 'sun.nio.fs.UnixNativeDispatcher'\n" +
                        "call('Read.VPN', os, 'stat', '/proc/net/dev_snmp6/tun0')\n" +
                        "call('Read.VPN', io, 'checkAccess', '/proc/net/dev_snmp6/tun0', 8)\n" +
                        // File.isFile, which Read.Network asks the hooks of the app below
                        "call('Read.VPN', io, 'getBooleanAttributes0', '/proc/net/dev_snmp6/tun0')\n" +
                        "call('Read.VPN', os, 'open', '/proc/net/dev_snmp6/tun0', 0)\n" +
                        "call('Read.VPN', nio, 'open', '/proc/net/dev_snmp6/tun0', 0)\n" +
                        "call('Read.VPN', os, 'stat', '/proc/net/dev_snmp6/tun0/x')\n" +
                        "call('Read.VPN', os, 'open', '/proc/net/xt_qtaguid/iface_stat/tun0/rx_bytes', 0)\n" +
                        "call('Read.VPN', io, 'checkAccess', '/proc/sys/net/ipv4/conf/tun0', 8)\n" +
                        "call('Read.VPN', io, 'checkAccess', '/proc/sys/net/ipv6/neigh/tun0', 8)\n" +
                        "call('Read.VPN', io, 'list0', '/proc/sys/net/ipv4/conf/tun0')\n" +
                        "call('Read.VPN', io, 'list0', '/proc/net/dev_snmp6')\n" +
                        "call('Read.VPN', io, 'list0', '/proc/net/dev_snmp6', nil, namesof('lo', 'tun0', 'wlan0'))\n" +
                        "call('Read.VPN', os, 'stat', '/proc/net/dev_snmp6/wlan0')\n" +
                        "call('Read.VPN', os, 'open', '/proc/net/dev_snmp6/wlan0', 0)\n" +
                        "call('Read.VPN', os, 'open', '/proc/self/fd/9', 0)\n" +
                        "call('Read.Network', os, 'open', '/proc/self/fd/9', 0)\n" +
                        "call('Read.Network', os, 'open', '/proc/net/dev_snmp6/tun0', 0)\n" +
                        "vpn = false\n" +
                        "call('Read.Network', os, 'open', '/proc/net/dev_snmp6/tun0', 0)\n" +
                        "call('Read.Network', os, 'open', '/proc/net/dev_snmp6/wlan0', 0)\n" +
                        "present['/proc/net/dev_snmp6/wlan0'], readable['/proc/net/dev_snmp6/wlan0'] = nil, nil\n" +
                        "call('Read.Network', os, 'open', '/proc/net/dev_snmp6/wlan0', 0)\n" +
                        "local stream = paramof('/proc/net/dev_snmp6', nil, { accept = function(self, entry) return true end })\n" +
                        "stream.unhooked = function(self, fn) return fn() end\n" +
                        "before(hookof('Read.VPN', 'sun.nio.fs.UnixFileSystemProvider', 'newDirectoryStream'), stream)\n" +
                        "local accept = stream.argument.value.accept\n" +
                        "return table.concat(results, '|'), accept('/proc/net/dev_snmp6/tun0'), accept('/proc/net/dev_snmp6/lo')");
        String[] expected = {
                // Missing like a name no lookup finds: the real call with an empty path, false, UnixException ENOENT
                "R-A nil", "RE- false", "RE- 0", "R-A nil", "RE- sun.nio.fs.UnixException 2", "R-A nil", "R-A nil",
                "RE- false", "RE- false", "RE- nil",
                // The folder of the names lists for real, without tun0
                "--- nil", "RE- lo wlan0",
                // wlan0 is there and denied
                "--- nil", "RE- android.system.ErrnoException open 13",
                // The link of a descriptor
                "--- nil", "--- nil",
                // Read.Network with Read.VPN, without it, wlan0, and wlan0 gone
                "--- nil", "RE- android.system.ErrnoException open 13", "RE- android.system.ErrnoException open 13", "--- nil"};
        assertEquals(String.join("|", expected), result.arg1().tojstring());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
    }

    // The files of the system by the folders etc of partitions, not the same names in a folder of the app; the store
    // of certificates still reads the ones of the user; of the folders of apps only the names go
    @Test
    public void hiddenFilesOfTheSystem() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local tracking, features, apps = hookof('Use.Tracking'), hookof('Read.Features'), hookof('Get.Applications')\n" +
                        "return hidden(tracking, '/product/etc/build.prop'), hidden(tracking, '/vendor/etc/fstab.qcom'),\n" +
                        "  hidden(tracking, '/data/user/0/com.example/files/etc/build.prop'),\n" +
                        "  hidden(tracking, '/data/misc/user/0/cacerts-added'),\n" +
                        "  hidden(tracking, '/data/misc/user/0/cacerts-added/abcd1234.0'),\n" +
                        "  hidden(features, '/system/etc/sysconfig/google.xml'), hidden(features, '/system/product/etc/permissions'),\n" +
                        "  hidden(features, '/apex/com.android.foo/etc/permissions/a.xml'),\n" +
                        "  hidden(features, '/data/user/0/com.example/files/rootfs/etc/sysconfig/network'),\n" +
                        "  hidden(apps, '/system/etc/permissions/privapp-permissions-com.example.xml'),\n" +
                        "  hidden(apps, '/system/etc/permissions/platform.xml'), hidden(apps, '/system/priv-app/Example'),\n" +
                        "  unlisted('Get.Applications', '/system/priv-app'), unlisted('Get.Applications', '/product/app'),\n" +
                        "  unlisted('Get.Applications', '/system/product/priv-app'), unlisted('Use.Tracking', '/system/app'),\n" +
                        "  unlisted('Get.Applications', '/data/app'),\n" +
                        // The folder /odm/etc leads to without an odm partition, also where /vendor is a link to /system/vendor
                        "  hidden(tracking, '/vendor/odm/etc/build.prop'), hidden(features, '/vendor/odm/etc/permissions'),\n" +
                        "  hidden(features, '/system/vendor/odm/etc/sysconfig/a.xml'),\n" +
                        "  hidden(apps, '/vendor/odm/etc/permissions/privapp-permissions-com.example.xml'),\n" +
                        "  hidden(features, '/vendor/odm/app/etc/permissions')");
        for (int i : new int[]{1, 2, 4, 5, 6, 7, 8, 10, 13, 14, 15, 17, 18, 19, 20, 21})
            assertTrue("result " + i, result.arg(i).toboolean());
        for (int i : new int[]{3, 9, 11, 12, 16, 22})
            assertFalse("result " + i, result.arg(i).toboolean());
    }

    // The store of certificates finds a certificate of the user by its file, which connections need, but lists their
    // folder without them, like the accepted issuers of the trust manager read it (DirectoryCertificateSource of
    // UserCertificateSource), so they are not there; the app itself does not find the folder at all
    @Test
    public void certificatesOfTheUserAreFoundButNotListedByTheTrustStore() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local folder = '/data/misc/user/0/cacerts-added'\n" +
                        "local io = 'java.io.UnixFileSystem'\n" +
                        "local list, exists = hookof('Use.Tracking', io, 'list0'), hookof('Use.Tracking', io, 'checkAccess')\n" +
                        "local store = true\n" +
                        "function truststore() return store end\n" +
                        "local file = paramof(folder .. '/abcd1234.0')\n" +
                        "local found = before(exists, file)\n" +
                        "local listing = paramof(folder)\n" +
                        "local listed, early = before(list, listing), listing.early\n" +
                        "listing.result = namesof('abcd1234.0', '5678ef90.0')\n" +
                        "after(list, listing)\n" +
                        "store = false\n" +
                        "local own = paramof(folder)\n" +
                        "before(list, own)\n" +
                        "return found, file.early, listed, early, listing.result.class, listing.result.length,\n" +
                        "  own.early, own.result");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertEquals("java.lang.String", result.arg(5).tojstring());
        assertEquals(0, result.arg(6).toint());
        assertTrue(result.arg(7).toboolean());
        assertTrue(result.arg(8).isnil());
    }

    // The folders named after hidden apps do not exist: their private data on Android 8.1 to 10, their folders data
    // and obb of the shared storage on Android 8.1 to 10 and media on all versions; the own folders, those of
    // visible apps and of apps which are not installed stay, like ones the app makes itself
    private static final String APPS_STUBS = HARDWARE_STUBS + "\n" + String.join("\n",
            "INSTALLED = { ['com.hidden'] = true, ['com.visible'] = true, ['test.app'] = true }",
            "function appsparam(argument, result)",
            "  local param = paramof(argument, result)",
            "  param.getPackageName = function(self) return 'test.app' end",
            "  param.isPackageVisible = function(self, name) return name == 'com.visible' end",
            "  param.unhooked = function(self, fn) return fn() end",
            "  param.getApplicationContext = function(self) return { getPackageManager = function(self) return {",
            "    getPackageInfo = function(self, name, flags)",
            "      assert(flags == 0x2000)",
            "      if not INSTALLED[name] then error('NameNotFoundException: ' .. name) end",
            "      return { packageName = name } end } end } end",
            "  return param",
            "end");

    @Test
    public void foldersOfHiddenAppsDoNotExist() throws Exception {
        Globals globals = Scripts.load("hardware_files", APPS_STUBS + "\nfunction sdkversion() return 29 end");
        Varargs result = Scripts.run(globals,
                "local apps = hookof('Get.Applications')\n" +
                        "local function h(path) return hidden(apps, path, appsparam(path)) end\n" +
                        "return h('/data/data/com.hidden'), h('/data/user/0/com.hidden/files/a.db'),\n" +
                        "  h('/data/user_de/0/com.hidden'), h('/data/misc/profiles/cur/0/com.hidden/primary.prof'),\n" +
                        "  h('/storage/emulated/0/Android/data/com.hidden'), h('/sdcard/Android/obb/com.hidden/main.obb'),\n" +
                        "  h('/storage/emulated/0/Android/media/com.hidden'), h('/storage/1A2B-3C4D/Android/data/com.hidden'),\n" +
                        // Stay: the own folders, a visible app, an app which is not installed, a name of no app
                        "  h('/data/user/0/test.app/files/a.db'), h('/storage/emulated/0/Android/data/com.visible'),\n" +
                        "  h('/storage/emulated/0/Android/data/com.notinstalled'), h('/storage/emulated/0/Android/data/.nomedia'),\n" +
                        "  h('/storage/emulated/0/Android/data'), h('/data/user/0/test.app/files/Android/data/com.hidden')");
        for (int i = 1; i <= 8; i++)
            assertTrue("result " + i, result.arg(i).toboolean());
        for (int i = 9; i <= 14; i++)
            assertFalse("result " + i, result.arg(i).toboolean());

        // Listing the folder of the apps of the shared storage leaves out the hidden ones
        Varargs listed = Scripts.run(globals,
                "local param = appsparam(nil, namesof('com.hidden', 'test.app', 'com.notinstalled', '.nomedia'))\n" +
                        "param.getArgument = function(self, index) return '/storage/emulated/0/Android/data' end\n" +
                        "local restricted = after(hookof('Get.Applications', 'java.io.UnixFileSystem', 'list0'), param)\n" +
                        "local names = {}\n" +
                        "for index = 1, param.result.length do names[#names + 1] = param.result[index] end\n" +
                        "return restricted, table.concat(names, ',')");
        assertTrue(listed.arg(1).toboolean());
        assertEquals("test.app,com.notinstalled,.nomedia", listed.arg(2).tojstring());

        // The filter passes the folders on these versions
        Object filter = xcoreFilter(Scripts.run(globals, "return filter(hookof('Get.Applications'))").arg1().checktable().unpack());
        assertTrue(filterMatches(filter, "/data/data/com.hidden"));
        assertTrue(filterMatches(filter, "/storage/emulated/0/Android/obb/com.hidden"));
        assertTrue(filterMatches(filter, "/storage/emulated/0/Android/media/com.hidden"));
    }

    // From Android 11 Android itself hides the private data and the folders data and obb of other apps
    @Test
    public void mediaFoldersOfHiddenAppsDoNotExistFromAndroid11() throws Exception {
        Globals globals = Scripts.load("hardware_files", APPS_STUBS + "\nfunction sdkversion() return 30 end");
        Varargs result = Scripts.run(globals,
                "local apps = hookof('Get.Applications')\n" +
                        "local function h(path) return hidden(apps, path, appsparam(path)) end\n" +
                        "return h('/storage/emulated/0/Android/media/com.hidden'), h('/data/data/com.hidden'),\n" +
                        "  h('/storage/emulated/0/Android/data/com.hidden')");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        Object filter = xcoreFilter(Scripts.run(globals, "return filter(hookof('Get.Applications'))").arg1().checktable().unpack());
        assertTrue(filterMatches(filter, "/storage/emulated/0/Android/media/com.hidden"));
        assertFalse(filterMatches(filter, "/data/user/0/test.app/files/a.db"));
    }

    // The permissions of the platform, which every AOSP build has, stay; those of the system apps of makers do not
    @Test
    public void platformPermissionsStay() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local apps = hookof('Get.Applications')\n" +
                        "return hidden(apps, '/system/etc/permissions/privapp-permissions-platform.xml'),\n" +
                        "  hidden(apps, '/system/etc/permissions/privapp-permissions-com.samsung.android.dialer.xml')");
        assertFalse(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
    }

    // For Get.Sensors the files of the sensor features do not exist, also by the link /etc, in the folders of the
    // SKU and by /vendor/odm/etc, which /odm/etc leads to without an odm partition, and are left out of listings; the
    // other feature files stay and are read without the sensor features, with the other and unavailable ones and as
    // long as before, also through the descriptor of their folder; files of the app with the same names stay as they
    // are, also in the shared storage, so do files opened for writing, which could not be read and would lose what
    // the app writes, and other groups
    @Test
    public void sensorFeatureFilesOfTheSystem() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "LINKS['/odm/etc/permissions/sku_X'] = '/vendor/odm/etc/permissions/sku_X'\n" +
                        "LINKS['/sdcard/etc/permissions/../../../vendor/etc/permissions/android.hardware.sensor.light.xml'] =\n" +
                        "  '/vendor/etc/permissions/android.hardware.sensor.light.xml'\n" +
                        "local sensors = hookof('Get.Sensors')\n" +
                        "local list = paramof('/vendor/etc/permissions', namesof('android.hardware.sensor.compass.xml',\n" +
                        "  'handheld_core_hardware.xml', 'android.hardware.wifi.xml'))\n" +
                        "after(hookof('Get.Sensors', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]'), list)\n" +
                        "local sku = paramof('/odm/etc/permissions/sku_X', namesof('android.hardware.sensor.barometer.xml',\n" +
                        "  'a.xml'))\n" +
                        "after(hookof('Get.Sensors', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]'), sku)\n" +
                        "local odm = paramof('/vendor/odm/etc/permissions', namesof('android.hardware.sensor.light.xml',\n" +
                        "  'a.xml'))\n" +
                        "after(hookof('Get.Sensors', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]'), odm)\n" +
                        "local canonical = missing(sensors, '/odm/etc/permissions/sku_X/android.hardware.sensor.barometer.xml')\n" +
                        "return hidden(sensors, '/vendor/etc/permissions/android.hardware.sensor.accelerometer.xml'),\n" +
                        "  hidden(sensors, '/etc/permissions/android.hardware.sensor.gyroscope.xml'),\n" +
                        "  hidden(sensors, '/vendor/etc/permissions/handheld_core_hardware.xml'),\n" +
                        "  hidden(sensors, '/vendor/etc/permissions/android.hardware.sensors.custom.xml'),\n" +
                        "  hidden(sensors, '/vendor/etc/permissions'),\n" +
                        "  hidden(sensors, '/data/user/0/com.example/files/etc/permissions/android.hardware.sensor.light.xml'),\n" +
                        "  hidden(sensors, '/vendor/etc/permissions/sku_X/sub/android.hardware.sensor.light.xml'),\n" +
                        "  list.result.length, list.result[1], list.result[2], sku.result.length, sku.result[1],\n" +
                        "  hidden(sensors, '/odm/etc/permissions/sku_X/android.hardware.sensor.barometer.xml'),\n" +
                        "  hidden(sensors, '/vendor/etc/permissions/sku_X/android.hardware.sensor.light.xml'),\n" +
                        "  hidden(sensors, '/product/etc/permissions/sku_X/android.hardware.sensor.gyroscope.xml'),\n" +
                        "  hidden(sensors, '/vendor/odm/etc/permissions/sku_X/android.hardware.sensor.barometer.xml'),\n" +
                        "  hidden(sensors, '/vendor/odm/etc/permissions/android.hardware.sensor.light.xml'),\n" +
                        "  canonical, hidden(sensors, canonical), odm.result.length, odm.result[1],\n" +
                        "  hidden(sensors, '/sdcard/etc/permissions/../../../vendor/etc/permissions/' ..\n" +
                        "    'android.hardware.sensor.light.xml'),\n" +
                        "  hidden(sensors, '/sdcard/etc/permissions/android.hardware.sensor.light.xml'),\n" +
                        "  hidden(sensors, '/proc/self/root/sdcard/etc/permissions/sku_X/android.hardware.sensor.light.xml')");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        for (int i = 3; i <= 7; i++)
            assertFalse("result " + i, result.arg(i).toboolean());
        assertEquals(2, result.arg(8).toint());
        assertEquals("handheld_core_hardware.xml", result.arg(9).tojstring());
        assertEquals("android.hardware.wifi.xml", result.arg(10).tojstring());
        assertEquals(1, result.arg(11).toint());
        assertEquals("a.xml", result.arg(12).tojstring());
        for (int i = 13; i <= 17; i++)
            assertTrue("result " + i, result.arg(i).toboolean());
        // The canonical path of a hidden /odm file, which a device without an odm partition has by a link to /vendor/odm
        assertEquals("/vendor/odm/etc/permissions/sku_X/android.hardware.sensor.barometer.xml", result.arg(18).tojstring());
        assertTrue(result.arg(19).toboolean());
        assertEquals(1, result.arg(20).toint());
        assertEquals("a.xml", result.arg(21).tojstring());
        assertTrue(result.arg(22).toboolean());
        assertFalse(result.arg(23).toboolean());
        assertFalse(result.arg(24).toboolean());

        String text = String.join("\n",
                "<permissions>",
                "    <feature name=\"android.hardware.location.network\" />",
                "    <feature name=\"android.hardware.sensor.accelerometer\" />",
                "    <feature name=\"android.hardware.sensor.compass\"/>",
                "    <feature name='android.hardware.sensor.gyroscope' notLowRam=\"true\" />",
                "    <feature name=\"android.hardware.sensors.custom\" />",
                "    <unavailable-feature name=\"android.hardware.sensor.light\" />",
                "    <feature name=\"android.hardware.touchscreen\" />",
                "</permissions>",
                "");
        Varargs read = Scripts.run(globals,
                "LINKS['/sdcard/etc/permissions/../../../vendor/etc/permissions/a.xml'] = '/vendor/etc/permissions/a.xml'\n" +
                        "LINKS['/proc/self/fd/57/handheld_core_hardware.xml'] = '/vendor/etc/permissions/handheld_core_hardware.xml'\n" +
                        "LINKS['/proc/self/fd/58/a.xml'] = '/data/user/0/com.example/files/etc/permissions/a.xml'\n" +
                        "local function opened(group, path, exception, flags)\n" +
                        "  local param = paramof(path, 'fd', flags or 0)\n" +
                        "  param.text = [[" + text + "]]\n" +
                        "  param.getException = function(self) return exception end\n" +
                        // As pread of a descriptor opened only for writing, EBADF
                        "  param.getFileText = function(self) assert(bit32.band(flags or 0, 3) ~= 1, 'EBADF') return self.text end\n" +
                        "  param.setFileText = function(self, value) self.text = value self.set = true end\n" +
                        "  local restricted, count = after(hookof(group, 'libcore.io.BlockGuardOs', 'open'), param)\n" +
                        "  return restricted, count, param.text, param.set == true\n" +
                        "end\n" +
                        "local restricted, count, read = opened('Get.Sensors', '/vendor/etc/permissions/handheld_core_hardware.xml')\n" +
                        "local own = '/data/user/0/com.example/files/etc/permissions/a.xml'\n" +
                        "return restricted, count, read, select(4, opened('Get.Sensors', own)),\n" +
                        "  select(4, opened('Read.Features', '/vendor/etc/permissions/handheld_core_hardware.xml')),\n" +
                        "  select(4, opened('Get.Sensors', '/vendor/etc/permissions/handheld_core_hardware.xml', 'ErrnoException')),\n" +
                        "  select(3, opened('Get.Sensors', '/vendor/etc/permissions/sku_X/handheld_core_hardware.xml')),\n" +
                        "  select(4, opened('Get.Sensors', '/odm/etc/sysconfig/sku_X/a.xml')),\n" +
                        "  select(4, opened('Get.Sensors', '/vendor/etc/permissions/sku_X/sub/a.xml')),\n" +
                        // O_RDWR, and O_WRONLY of which the text cannot be read
                        "  select(4, opened('Get.Sensors', '/vendor/etc/permissions/handheld_core_hardware.xml', nil, 2)),\n" +
                        "  select(4, opened('Get.Sensors', '/vendor/etc/permissions/handheld_core_hardware.xml', nil, 0x8041)),\n" +
                        "  select(4, opened('Get.Sensors', '/sdcard/etc/permissions/probe.xml')),\n" +
                        "  select(4, opened('Get.Sensors', '/sdcard/etc/permissions/../../../vendor/etc/permissions/a.xml')),\n" +
                        "  select(4, opened('Get.Sensors', '/vendor/odm/etc/permissions/a.xml')),\n" +
                        "  select(4, opened('Get.Sensors', '/proc/self/fd/57/handheld_core_hardware.xml')),\n" +
                        "  select(4, opened('Get.Sensors', '/proc/self/fd/58/a.xml'))");
        assertTrue(read.arg(1).toboolean());
        assertEquals("3", read.arg(2).tojstring());
        String expected = String.join("\n",
                "<permissions>",
                "    <feature name=\"android.hardware.location.network\" />",
                "    <feature name=\"android.hardware.sensors.custom\" />",
                "    <unavailable-feature name=\"android.hardware.sensor.light\" />",
                "    <feature name=\"android.hardware.touchscreen\" />",
                "</permissions>",
                "");
        assertEquals(expected + " ".repeat(text.length() - expected.length()), read.arg(3).tojstring());
        assertFalse(read.arg(4).toboolean());
        assertFalse(read.arg(5).toboolean());
        assertFalse(read.arg(6).toboolean());
        assertEquals(read.arg(3).tojstring(), read.arg(7).tojstring());
        assertTrue(read.arg(8).toboolean());
        for (int i = 9; i <= 12; i++)
            assertFalse("read " + i, read.arg(i).toboolean());
        for (int i = 13; i <= 15; i++)
            assertTrue("read " + i, read.arg(i).toboolean());
        assertFalse(read.arg(16).toboolean());

        Object filter = xcoreFilter(Scripts.run(globals, "return filter(hookof('Get.Sensors'))").arg1().checktable().unpack());
        for (String path : new String[]{"/vendor/etc/permissions", "/vendor/etc/permissions/handheld_core_hardware.xml",
                "/etc/permissions/android.hardware.sensor.light.xml", "/system/etc/sysconfig/a.xml", "/vendor/./etc/permissions",
                "/proc/self/fd/57/handheld_core_hardware.xml"})
            assertTrue(path, filterMatches(filter, path));
        assertFalse(filterMatches(filter, "/system/etc/hosts"));
        assertFalse(filterMatches(filter, "/sys/class/sensors/light_sensor/light_cal"));
    }

    // For Read.Biometrics the feature files of the fingerprint sensor and of face and iris unlock do not exist, also in
    // the folders of the SKU, and are left out of listings, like the files of the sensor in /sys; the other feature files
    // stay and are read without exactly the features hasSystemFeature hides (see biometrics); files of the shared
    // storage with the same names and other groups stay
    @Test
    public void biometricFeatureFilesOfTheSystem() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local bio = hookof('Read.Biometrics')\n" +
                        "local list = paramof('/vendor/etc/permissions', namesof('android.hardware.fingerprint.xml',\n" +
                        "  'android.hardware.biometrics.face.xml', 'handheld_core_hardware.xml'))\n" +
                        "after(hookof('Read.Biometrics', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]'), list)\n" +
                        "return hidden(bio, '/vendor/etc/permissions/android.hardware.fingerprint.xml'),\n" +
                        "  hidden(bio, '/etc/permissions/android.hardware.biometrics.face.xml'),\n" +
                        "  hidden(bio, '/odm/etc/permissions/sku_X/android.hardware.biometrics.iris.xml'),\n" +
                        "  hidden(bio, '/sys/class/fingerprint/fingerprint'),\n" +
                        "  hidden(bio, '/vendor/etc/permissions/handheld_core_hardware.xml'),\n" +
                        "  hidden(bio, '/sdcard/etc/permissions/android.hardware.fingerprint.xml'),\n" +
                        "  hidden(hookof('Get.Sensors'), '/vendor/etc/permissions/android.hardware.fingerprint.xml'),\n" +
                        "  list.result.length, list.result[1]");
        for (int i = 1; i <= 4; i++)
            assertTrue("result " + i, result.arg(i).toboolean());
        for (int i = 5; i <= 7; i++)
            assertFalse("result " + i, result.arg(i).toboolean());
        assertEquals(1, result.arg(8).toint());
        assertEquals("handheld_core_hardware.xml", result.arg(9).tojstring());

        String text = String.join("\n",
                "<permissions>",
                "    <feature name=\"android.hardware.fingerprint\" />",
                "    <feature name=\"android.hardware.biometrics.face\"/>",
                "    <feature name=\"android.hardware.fingerprint.extra\" />",
                "    <feature name=\"android.hardware.sensor.light\" />",
                "</permissions>",
                "");
        Varargs read = Scripts.run(globals,
                "local param = paramof('/vendor/etc/permissions/handheld_core_hardware.xml', 'fd', 0)\n" +
                        "param.text = [[" + text + "]]\n" +
                        "param.getFileText = function(self) return self.text end\n" +
                        "param.setFileText = function(self, value) self.text = value end\n" +
                        "local restricted, count = after(hookof('Read.Biometrics', 'libcore.io.BlockGuardOs', 'open'), param)\n" +
                        "return restricted, count, param.text");
        assertTrue(read.arg(1).toboolean());
        assertEquals("2", read.arg(2).tojstring());
        String expected = String.join("\n",
                "<permissions>",
                "    <feature name=\"android.hardware.fingerprint.extra\" />",
                "    <feature name=\"android.hardware.sensor.light\" />",
                "</permissions>",
                "");
        assertEquals(expected + " ".repeat(text.length() - expected.length()), read.arg(3).tojstring());

        Object filter = xcoreFilter(Scripts.run(globals, "return filter(hookof('Read.Biometrics'))").arg1().checktable().unpack());
        for (String path : new String[]{"/vendor/etc/permissions", "/vendor/etc/permissions/android.hardware.fingerprint.xml",
                "/odm/etc/sysconfig/a.xml", "/sys/class/fingerprint/fingerprint", "/vendor/./etc/permissions"})
            assertTrue(path, filterMatches(filter, path));
        assertFalse(filterMatches(filter, "/system/etc/hosts"));
    }

    // For Read.Features the folders of the shared libraries of the partitions list without the jars of the libraries of
    // makers which getSystemSharedLibraryNames hides (see features), by File.list and Files.newDirectoryStream and by
    // other forms of their paths; the jars of AOSP, of the platform and of Google on every device stay, and so do the
    // files themselves, which class loaders check. The names are read once, and only for those folders.
    @Test
    public void libraryJarsOfMakersAreLeftOutOfListings() throws Exception {
        assertEquals(Scripts.function("features", "vendor"), Scripts.function("hardware_files", "vendor"));
        for (String name : new String[]{"PLATFORM", "GOOGLE"}) {
            java.util.regex.Pattern table = java.util.regex.Pattern.compile("local " + name + " = \\{[^}]*\\}");
            java.util.regex.Matcher features = table.matcher(Scripts.read("features"));
            java.util.regex.Matcher files = table.matcher(Scripts.read("hardware_files"));
            assertTrue(name, features.find() && files.find());
            assertEquals(name, features.group(), files.group());
        }
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local calls = 0\n" +
                        "local function withnames(param)\n" +
                        "  param.unhooked = function(self, fn) return fn() end\n" +
                        "  param.getApplicationContext = function(self) return { getPackageManager = function(self)\n" +
                        "    return { getSystemSharedLibraryNames = function(self) calls = calls + 1\n" +
                        "      return namesof('com.google.android.dialer.support', 'com.google.android.maps', 'android.test.base',\n" +
                        "        'com.samsung.device') end } end } end\n" +
                        "  return param\n" +
                        "end\n" +
                        "local function listed(group, folder)\n" +
                        "  local param = withnames(paramof(folder, namesof('com.google.android.dialer.support.jar', 'framework.jar',\n" +
                        "    'com.google.android.maps.jar', 'android.test.base.jar', 'com.samsung.device.jar', 'oat')))\n" +
                        "  after(hookof(group, 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]'), param)\n" +
                        "  local names = {}\n" +
                        "  for index = 1, param.result.length do names[index] = param.result[index] end\n" +
                        "  return table.concat(names, ',')\n" +
                        "end\n" +
                        "local own = listed('Read.Features', '/data/user/0/test.app/files/framework') .. ' ' .. calls\n" +
                        "local stream = withnames(paramof('/product/framework', nil,\n" +
                        "  { accept = function(self, entry) return true end }))\n" +
                        "before(hookof('Read.Features', 'sun.nio.fs.UnixFileSystemProvider', 'newDirectoryStream'), stream)\n" +
                        "return listed('Read.Features', '/product/framework'), listed('Read.Features', '/system/./framework'),\n" +
                        "  listed('Read.Features', '/system/vendor/framework'), calls,\n" +
                        "  stream.argument.value.accept('/product/framework/com.google.android.dialer.support.jar'),\n" +
                        "  stream.argument.value.accept('/product/framework/framework.jar'),\n" +
                        "  listed('Use.Tracking', '/product/framework'),\n" +
                        "  own, calls");
        String kept = "framework.jar,com.google.android.maps.jar,android.test.base.jar,oat";
        String all = "com.google.android.dialer.support.jar,framework.jar,com.google.android.maps.jar,android.test.base.jar," +
                "com.samsung.device.jar,oat";
        assertEquals(kept, result.arg(1).tojstring());
        assertEquals(kept, result.arg(2).tojstring());
        assertEquals(kept, result.arg(3).tojstring());
        assertEquals(1, result.arg(4).toint());
        assertFalse(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
        assertEquals(all, result.arg(7).tojstring());
        assertEquals(all + " 0", result.arg(8).tojstring());
        assertEquals(1, result.arg(9).toint());

        Object filter = xcoreFilter(Scripts.run(globals, "return filter(hookof('Read.Features', 'java.io.UnixFileSystem', 'list0'))")
                .arg1().checktable().unpack());
        for (String folder : new String[]{"/system/framework", "/product/framework", "/system_ext/framework", "/vendor/framework",
                "/odm/framework", "/system/product/framework", "/vendor/odm/framework", "/system/./framework"})
            assertTrue(folder, filterMatches(filter, folder));
        assertFalse(filterMatches(filter, "/system/framework/framework.jar"));
        assertFalse(filterMatches(filter, "/data/user/0/test.app/files/framework"));
    }

    // Nothing is under a hidden permission file, for which the kernel would answer ENOTDIR and tell the file is there,
    // also by a link to its folder; under a file which stays, and under the files of Read.Network, which stay there,
    // the real call answers
    @Test
    public void pathsUnderHiddenFilesDoNotExist() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local file = '/system/etc/permissions/privapp-permissions-com.example.xml'\n" +
                        "local present = { [file] = true, ['/etc/permissions/privapp-permissions-com.example.xml'] = true,\n" +
                        "  ['/system/etc/permissions/platform.xml'] = true, ['/proc/net/arp'] = true }\n" +
                        "local new = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, path, ...)\n" +
                        "  if name == 'java.io.File' then return { exists = function() return present[path] == true end,\n" +
                        "    canRead = function() return present[path] == true end } end\n" +
                        "  return new(name, path, ...)\n" +
                        "end\n" +
                        "local function param(path)\n" +
                        "  local p = paramof(path)\n" +
                        "  p.unhooked = function(self, fn) return fn() end\n" +
                        "  return p\n" +
                        "end\n" +
                        "local apps = hookof('Get.Applications', 'libcore.io.BlockGuardOs', 'stat')\n" +
                        "local stat = param(file .. '/a')\n" +
                        "before(apps, stat)\n" +
                        "local function h(hook, path) return hidden(hook, path, param(path)) end\n" +
                        "return h(apps, file .. '/a'), h(apps, file .. '/.'), h(apps, file .. '/a/b'),\n" +
                        "  h(apps, '/etc/permissions/privapp-permissions-com.example.xml/a'),\n" +
                        "  stat.early, stat.argument.index, stat.argument.value,\n" +
                        "  h(apps, '/system/etc/permissions/platform.xml/a'), h(hookof('Read.Network'), '/proc/net/arp/a')");
        for (int i = 1; i <= 4; i++)
            assertTrue("result " + i, result.arg(i).toboolean());
        // The real call fails with an empty path, like for a missing file
        assertFalse(result.arg(5).toboolean());
        assertEquals(0, result.arg(6).toint());
        assertEquals("", result.arg(7).tojstring());
        assertFalse(result.arg(8).toboolean());
        assertFalse(result.arg(9).toboolean());
    }

    // The folders of system apps list without names by any form of their path, also by the compatibility links of
    // /system to the other partitions, which the filter passes too
    @Test
    public void foldersOfSystemAppsByOtherPaths() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "return unlisted('Get.Applications', '/system/app/.'), unlisted('Get.Applications', '/system//priv-app'),\n" +
                        "  unlisted('Get.Applications', '/system/./product/app'), unlisted('Get.Applications', '/system/vendor/app/'),\n" +
                        "  unlisted('Get.Applications', '/system/app/Example'), unlisted('Get.Applications', '/system/etc/.')");
        for (int i = 1; i <= 4; i++)
            assertTrue("result " + i, result.arg(i).toboolean());
        assertFalse(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
        Object filter = xcoreFilter(Scripts.run(globals, "return filter(hookof('Get.Applications'))").arg1().checktable().unpack());
        for (String folder : new String[]{"/system/product/app", "/system/system_ext/priv-app", "/system/vendor/app",
                "/system/app/.", "/system//priv-app"})
            assertTrue(folder, filterMatches(filter, folder));
        assertFalse(filterMatches(filter, "/system/app/Example/Example.apk"));
    }

    // A folder of system apps lists without names also through a descriptor of it, like /proc/self/fd/57 of
    // /system/priv-app, for File.list and Files.newDirectoryStream, and by a path with /../; resolve follows them only
    // with the param, like the real one; a descriptor of a folder of the app lists all names
    @Test
    public void foldersOfSystemAppsThroughDescriptors() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS + "\n" +
                "function resolve(path, param) if param == nil then return path end return LINKS[path] or path end");
        Varargs result = Scripts.run(globals,
                "LINKS['/proc/self/fd/57'] = '/system/priv-app'\n" +
                        "LINKS['/system/../system/priv-app'] = '/system/priv-app'\n" +
                        "LINKS['/proc/self/fd/9'] = '/data/user/0/test.app/files'\n" +
                        "local list = paramof('/proc/self/fd/57', namesof('Example', 'Other'))\n" +
                        "after(hookof('Get.Applications', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]'), list)\n" +
                        "local stream = paramof('/proc/self/fd/57', nil, { accept = function(self, entry) return true end })\n" +
                        "before(hookof('Get.Applications', 'sun.nio.fs.UnixFileSystemProvider', 'newDirectoryStream'), stream)\n" +
                        "return list.result.length, stream.argument.value.accept('/proc/self/fd/57/Example'),\n" +
                        "  unlisted('Get.Applications', '/system/../system/priv-app', list),\n" +
                        "  unlisted('Get.Applications', '/proc/self/fd/9', list)");
        assertEquals(0, result.arg(1).toint());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
    }

    // The properties at the root of a partition are hidden from Use.Tracking like the ones in etc, and the filter passes them
    @Test
    public void buildPropertiesAtTheRootAreHidden() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local tracking = hookof('Use.Tracking')\n" +
                        "return hidden(tracking, '/system/etc/build.prop'), hidden(tracking, '/system/build.prop'),\n" +
                        "  hidden(tracking, '/vendor/build.prop')");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        Object filter = xcoreFilter(Scripts.run(globals, "return filter(hookof('Use.Tracking'))").arg1().checktable().unpack());
        assertTrue(filterMatches(filter, "/system/build.prop"));
    }

    // The folders of etc list without the names the group hides: permissions and sysconfig for Read.Features in /system/etc
    // and /etc, which is a link to it, and fstab.* of /etc, which Use.Tracking hides when the app can read it
    @Test
    public void etcListingsLeaveOutHiddenNames() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local features = hookof('Read.Features', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]')\n" +
                        "local system = paramof('/system/etc', namesof('permissions', 'sysconfig', 'fonts.xml'))\n" +
                        "after(features, system)\n" +
                        "local etc = paramof('/etc', namesof('permissions', 'sysconfig', 'fonts.xml'))\n" +
                        "after(features, etc)\n" +
                        "local odm = paramof('/vendor/odm/etc', namesof('build.prop', 'permissions', 'group'))\n" +
                        "after(features, odm)\n" +
                        "LINKS['/odm/etc'] = '/vendor/odm/etc'\n" +
                        "local canonical = missing(features, '/odm/etc/permissions')\n" +
                        "return system.result.length, system.result[1], etc.result.length, etc.result[1],\n" +
                        "  odm.result.length, odm.result[1], odm.result[2], canonical, hidden(features, canonical)");
        assertEquals(1, result.arg(1).toint());
        assertEquals("fonts.xml", result.arg(2).tojstring());
        assertEquals(1, result.arg(3).toint());
        assertEquals("fonts.xml", result.arg(4).tojstring());
        // /vendor/odm/etc, which /odm/etc leads to without an odm partition, and is the canonical path of its files
        assertEquals(2, result.arg(5).toint());
        assertEquals("build.prop", result.arg(6).tojstring());
        assertEquals("group", result.arg(7).tojstring());
        assertEquals("/vendor/odm/etc/permissions", result.arg(8).tojstring());
        assertTrue(result.arg(9).toboolean());

        Globals tracking = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs readable = Scripts.run(tracking,
                "local file = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, path)\n" +
                        "  if name == 'java.io.File' then return { exists = function() return true end,\n" +
                        "    canRead = function() return path == '/etc/fstab.ranchu' or path == '/vendor/odm/etc/build.prop' end } end\n" +
                        "  return file(name, path)\n" +
                        "end\n" +
                        "local etc = paramof('/etc', namesof('fstab.ranchu', 'hosts'))\n" +
                        "etc.unhooked = function(self, fn) return fn() end\n" +
                        "after(hookof('Use.Tracking', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]'), etc)\n" +
                        "local odm = paramof('/vendor/odm/etc', namesof('build.prop', 'group'))\n" +
                        "odm.unhooked = function(self, fn) return fn() end\n" +
                        "after(hookof('Use.Tracking', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]'), odm)\n" +
                        "return etc.result.length, etc.result[1], odm.result.length, odm.result[1]");
        assertEquals(1, readable.arg(1).toint());
        assertEquals("hosts", readable.arg(2).tojstring());
        assertEquals(1, readable.arg(3).toint());
        assertEquals("group", readable.arg(4).tojstring());

        Object filter = xcoreFilter(Scripts.run(globals, "return filter(hookof('Read.Features'))").arg1().checktable().unpack());
        assertTrue(filterMatches(filter, "/etc"));
        assertTrue(filterMatches(filter, "/system/etc"));
        assertTrue(filterMatches(filter, "/vendor/odm/etc"));
        filter = xcoreFilter(Scripts.run(globals, "return filter(hookof('Use.Tracking'))").arg1().checktable().unpack());
        assertTrue(filterMatches(filter, "/vendor/odm/etc"));
    }

    // The folders etc of the partitions by the links of /system, of /oem, of the partitions my_ and of the APEX modules
    // leave out the hidden folders too, which do not exist by those paths either; a folder of the app does not run it
    @Test
    public void etcListingsOfEveryPathOfThePartitions() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Object filter = xcoreFilter(Scripts.run(globals, "return filter(hookof('Read.Features', 'java.io.UnixFileSystem', 'list0'))")
                .arg1().checktable().unpack());
        for (String folder : new String[]{"/system/vendor/etc", "/system/product/etc", "/system/system_ext/etc",
                "/system/vendor/odm/etc", "/oem/etc", "/my_product/etc", "/apex/com.android.foo/etc"}) {
            assertTrue(folder, filterMatches(filter, folder));
            Varargs result = Scripts.run(globals, "local list = paramof('" + folder + "', namesof('permissions', 'sysconfig', 'group'))\n" +
                    "local restricted = after(hookof('Read.Features', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]'), list)\n" +
                    "return restricted, list.result.length, list.result[1], hidden(hookof('Read.Features'), '" + folder + "/permissions')");
            assertTrue(folder, result.arg1().toboolean());
            assertEquals(folder, 1, result.arg(2).toint());
            assertEquals(folder, "group", result.arg(3).tojstring());
            assertTrue(folder, result.arg(4).toboolean());
        }
        assertFalse(filterMatches(filter, "/data/user/0/test.app/files/etc"));
    }

    // File.list of the root leaves out a readable fstab file, the names of the root are /name and not //name
    @Test
    public void fstabAtTheRootIsLeftOutOfListings() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local file = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, path)\n" +
                        "  if name == 'java.io.File' then return { canRead = function() return path == '/fstab.qcom' end } end\n" +
                        "  return file(name, path)\n" +
                        "end\n" +
                        "local list = paramof('/', namesof('fstab.qcom', 'system'))\n" +
                        "list.unhooked = function(self, fn) return fn() end\n" +
                        "after(hookof('Use.Tracking', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]'), list)\n" +
                        "return list.result.length, list.result[1]");
        assertEquals(1, result.arg(1).toint());
        assertEquals("system", result.arg(2).tojstring());
    }

    // Files.newDirectoryStream leaves out a matched file the app can read, and keeps one it cannot read, as File.list
    @Test
    public void hardwareListingOfUnreadableFiles() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local present = { ['/odm/etc/build.prop'] = true, ['/odm/etc/fstab.qcom'] = true }\n" +
                        "local readable = { ['/odm/etc/fstab.qcom'] = true }\n" +
                        "local file = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, path)\n" +
                        "  if name == 'java.io.File' then return { exists = function() return present[path] == true end,\n" +
                        "    canRead = function() return readable[path] == true end } end\n" +
                        "  return file(name, path)\n" +
                        "end\n" +
                        "local chip = paramof('/odm/etc', nil, { accept = function(self, entry) return true end })\n" +
                        "chip.unhooked = function(self, fn) return fn() end\n" +
                        "before(hookof('Use.Tracking', 'sun.nio.fs.UnixFileSystemProvider', 'newDirectoryStream'), chip)\n" +
                        "local accept = chip.argument.value.accept\n" +
                        "return accept('/odm/etc/build.prop'), accept('/odm/etc/fstab.qcom'), accept('/odm/etc/other.xml')");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
    }

    // Each group hides its files, by their path or the path their link leads to, the other files stay
    @Test
    public void hardwareFiles() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local ids = hookof('Read.Identifiers')\n" +
                        "local model = hookof('Use.Tracking')\n" +
                        "local finger = hookof('Read.Biometrics')\n" +
                        "return filter(ids)[1], #filter(ids),\n" +
                        "  hidden(ids, '/sys/devices/system/chip-id/unique_id'), hidden(ids, '/sys/devices/system/chip-id/lot_id'),\n" +
                        "  hidden(ids, '/sys/block/mmcblk0/device/cid'), hidden(ids, '/sys/class/sec/sdinfo/data'),\n" +
                        "  hidden(ids, '/sys/devices/system/chip-id/product_id'), hidden(ids, '/sys/block/mmcblk0/device/type'),\n" +
                        "  hidden(ids, '/sys/class/sec/tsp/fod_pos'), hidden(ids, '/sys/devices/system/cpu/present'),\n" +
                        "  hidden(model, '/sys/devices/system/chip-id/product_id'), hidden(model, '/sys/devices/system/chip-id'),\n" +
                        "  hidden(model, '/sys/class/sec/tsp'), hidden(model, '/sys/class/sec/tsp/raw_check'),\n" +
                        "  hidden(model, '/sys/class/sensors/light_sensor/light_cal'), hidden(model, '/sys/block/mmcblk0/device/cid'),\n" +
                        "  hidden(model, '/sys/class/fingerprint/fingerprint/name'), hidden(model, '/sys/devices/system'),\n" +
                        "  hidden(finger, '/sys/class/fingerprint/fingerprint/name'),\n" +
                        "  hidden(finger, '/sys/class/fingerprint/fingerprint'),\n" +
                        "  hidden(finger, '/sys/class/sec/tsp/fod_pos'), hidden(finger, '/sys/class/sec/tsp/raw_check'),\n" +
                        "  hidden(hookof('Read.Network'), '/sys/devices/system/chip-id/unique_id'),\n" +
                        "  filter(hookof('Read.Network'))[1], hidden(hookof('Read.Network'), '/proc/net/arp'),\n" +
                        "  hidden(hookof('Read.Network'), '/proc/sys/net/ipv4/ip_forward'),\n" +
                        "  hidden(hookof('Read.Network'), '/proc/thread-self/net/tcp')");
        assertEquals("*sys/*", result.arg(1).tojstring());
        // With paths with /./ and // and through descriptors, see hardwareFilterOfOtherPaths
        assertEquals(4, result.arg(2).toint());
        boolean[] expected = {true, true, true, true, false, false, false, false,
                true, true, true, true, true, false, false, false,
                true, true, true, false, false};
        for (int i = 0; i < expected.length; i++)
            assertEquals(String.valueOf(i + 3), expected[i], result.arg(i + 3).toboolean());
        // The files of /proc/net for Read.Network, not the settings of the network in /proc/sys
        assertEquals("*net/*", result.arg(24).tojstring());
        assertTrue(result.arg(25).toboolean());
        assertFalse(result.arg(26).toboolean());
        // By the link of the thread, which the pattern matches without resolving it
        assertTrue(result.arg(27).toboolean());
    }

    // The model, clocks and load of the GPU of Samsung, Adreno (by the links of kgsl into the device) and Mali;
    // other files of /sys/kernel and /sys/class stay
    @Test
    public void gpuFiles() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "LINKS['/sys/class/kgsl/kgsl-3d0/gpubusy'] = '/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpubusy'\n" +
                        "LINKS['/sys/class/devfreq/3d00000.qcom,kgsl-3d0/cur_freq'] = '/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/devfreq/3d00000.qcom,kgsl-3d0/cur_freq'\n" +
                        "LINKS['/sys/class/misc/mali0/device/utilization'] = '/sys/devices/platform/18500000.mali/utilization'\n" +
                        "LINKS['/sys/class/devfreq/gpufreq/cur_freq'] = '/sys/devices/platform/13000000.mali/devfreq/gpufreq/cur_freq'\n" +
                        "local results = {}\n" +
                        "for _, path in ipairs({ '/sys/kernel/gpu/gpu_clock', '/sys/kernel/gpu/gpu_model', '/sys/kernel/gpu',\n" +
                        "  '/sys/class/kgsl/kgsl-3d0/gpubusy', '/sys/class/devfreq/3d00000.qcom,kgsl-3d0/cur_freq',\n" +
                        "  '/sys/class/kgsl', '/sys/class/misc/mali0/device/utilization', '/sys/devices/platform/18500000.mali',\n" +
                        "  '/sys/class/devfreq/gpufreq/cur_freq',\n" +
                        "  '/sys/kernel/mm/transparent_hugepage/enabled', '/sys/class/devfreq', '/sys/class/misc/uinput' }) do\n" +
                        "  results[#results + 1] = hidden(hookof('Use.Tracking'), path) and 'T' or 'F'\n" +
                        "end\n" +
                        "return table.concat(results), hidden(hookof('Read.Identifiers'), '/sys/kernel/gpu/gpu_model')");
        assertEquals("TTTTTTTTT" + "FFF", result.arg(1).tojstring());
        assertFalse(result.arg(2).toboolean());
    }

    // The drivers of the GPU in the folders egl, which name it, but not ANGLE; the manifests of vintf of the vendor, also
    // the fragments and those at the root of the partitions of Android 8.1, the SELinux contexts of the vendor and the
    // configuration of the display panel; their folders, the other files of vintf and SELinux and the libraries of the
    // system stay, and listings leave the names out. A manifest the app cannot read stays (EACCES), like build.prop.
    @Test
    public void gpuDriversAndVendorConfiguration() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        String[] hidden = {"/vendor/lib64/egl/libGLESv2_adreno.so", "/vendor/lib/egl/libGLES_mali.so",
                "/vendor/lib64/egl/libEGL_emulation.so", "/system/vendor/lib64/egl/eglSubDriverAndroid.so",
                "/vendor/etc/vintf/manifest.xml", "/odm/etc/vintf/manifest_ssku.xml", "/vendor/etc/vintf/manifest",
                "/vendor/etc/vintf/manifest/vendor.qti.hardware.display.xml", "/system/vendor/etc/vintf/manifest.xml",
                "/vendor/etc/selinux/vendor_property_contexts", "/vendor/etc/selinux/nonplat_property_contexts",
                "/vendor/etc/displayconfig", "/vendor/etc/displayconfig/display_id_4619827259835644672.xml",
                "/vendor/manifest.xml", "/odm/manifest.xml", "/vendor/odm/etc/vintf/manifest.xml",
                "/system/vendor/odm/etc/vintf/manifest/vendor.qti.hardware.display.xml"};
        // The manifest of the framework, which every device has, of /system, its link /etc and of Android 8.1, and the
        // manifests of /system_ext and /product
        String[] stay = {"/vendor/lib64/egl", "/vendor/lib64/egl/libGLESv2_angle.so", "/system/lib64/libEGL.so",
                "/vendor/etc/vintf", "/vendor/etc/vintf/compatibility_matrix.xml", "/vendor/etc/selinux",
                "/vendor/etc/selinux/plat_sepolicy_vers.txt", "/system/etc/selinux/plat_property_contexts",
                "/data/user/0/com.example/files/vendor/lib64/egl/libGLESv2_adreno.so", "/data/manifest.xml",
                "/system/etc/vintf/manifest.xml", "/system/etc/vintf/manifest", "/etc/vintf/manifest.xml",
                "/system/etc/vintf/manifest/android.hidl.allocator@1.0-service.xml", "/system/manifest.xml",
                "/system_ext/etc/vintf/manifest.xml", "/product/etc/vintf/manifest.xml"};
        for (String path : hidden)
            assertTrue(path, Scripts.run(globals, "return hidden(hookof('Use.Tracking'), '" + path + "')").arg1().toboolean());
        for (String path : stay)
            assertFalse(path, Scripts.run(globals, "return hidden(hookof('Use.Tracking'), '" + path + "')").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return hidden(hookof('Read.Identifiers'), '/vendor/etc/vintf/manifest.xml')")
                .arg1().toboolean());
        Varargs result = Scripts.run(globals,
                "local hook = hookof('Use.Tracking', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]')\n" +
                        "local egl = paramof('/vendor/lib64/egl', namesof('libEGL_emulation.so', 'libGLESv2_angle.so',\n" +
                        "  'libGLESv2_emulation.so', 'libEGL_angle.so'))\n" +
                        "after(hook, egl)\n" +
                        "local etc = paramof('/vendor/etc', namesof('vintf', 'displayconfig', 'init'))\n" +
                        "after(hook, etc)\n" +
                        "local vendor = paramof('/vendor', namesof('manifest.xml', 'etc'))\n" +
                        "after(hook, vendor)\n" +
                        "local system = paramof('/system/etc/vintf', namesof('compatibility_matrix.xml', 'manifest.xml',\n" +
                        "  'manifest'))\n" +
                        "local listed = after(hook, system)\n" +
                        "return egl.result.length, egl.result[1], egl.result[2], etc.result.length, etc.result[1],\n" +
                        "  etc.result[2], vendor.result.length, vendor.result[1], listed, system.result.length,\n" +
                        "  system.result[2]");
        assertEquals(2, result.arg1().toint());
        assertEquals("libGLESv2_angle.so", result.arg(2).tojstring());
        assertEquals("libEGL_angle.so", result.arg(3).tojstring());
        assertEquals(2, result.arg(4).toint());
        assertEquals("vintf", result.arg(5).tojstring());
        assertEquals("init", result.arg(6).tojstring());
        assertEquals(1, result.arg(7).toint());
        assertEquals("etc", result.arg(8).tojstring());
        assertFalse(result.arg(9).toboolean());
        assertEquals(3, result.arg(10).toint());
        assertEquals("manifest.xml", result.arg(11).tojstring());
        Object filter = xcoreFilter(Scripts.run(globals, "return filter(hookof('Use.Tracking'))").arg1().checktable().unpack());
        for (String path : new String[]{"/vendor/lib64/egl", "/vendor/lib/egl/libGLES_mali.so", "/vendor/etc/vintf",
                "/vendor/etc/vintf/manifest/vendor.qti.hardware.display.xml", "/vendor/etc/selinux",
                "/vendor/etc/selinux/vendor_property_contexts", "/vendor/etc/displayconfig", "/vendor", "/vendor/manifest.xml",
                "/system/vendor/manifest.xml", "/proc/self/root/vendor/etc/vintf/manifest.xml", "/vendor/odm/etc/vintf"})
            assertTrue(path, filterMatches(filter, path));
        // Unreadable, like a version which denies apps the files of the vendor
        assertFalse(Scripts.run(globals, "local file = luajava.newInstance\n" +
                "luajava.newInstance = function(name, path)\n" +
                "  if name == 'java.io.File' then return { exists = function() return true end,\n" +
                "    canRead = function() return false end } end\n" +
                "  return file(name, path)\n" +
                "end\n" +
                "return hidden(hookof('Use.Tracking'), '/odm/etc/vintf/manifest.xml',\n" +
                "  { unhooked = function(self, f) return f() end })").arg1().toboolean());
    }

    // The tables of the process an app opens with Use.Tracking, by /proc or a descriptor: the maps and smaps without the
    // mappings of the libraries of the vendor and of the device of the GPU, ANGLE and the rest stay; the mount tables
    // with the partitions in /dev/block/by-name. Other files, a failed open, other groups and a text without them stay.
    private static final String TABLE_STUBS = String.join("\n",
            "LINKS['/proc/self/maps'] = '/proc/123/maps'",
            "LINKS['/proc/thread-self/smaps'] = '/proc/123/task/124/smaps'",
            "LINKS['/proc/self/root/proc/self/maps'] = '/proc/123/maps'",
            "LINKS['proc/self/maps'] = '/proc/123/maps'",
            "LINKS['/proc/mounts'] = '/proc/123/mounts'",
            "LINKS['/dev/fd/9/maps'] = '/proc/123/maps'",
            "function opened(path, text, group, failed)",
            "  local param = paramof(path, 'fd')",
            "  param.getFileText = function(self) return text end",
            "  param.setFileText = function(self, value) self.text = value end",
            "  if failed then param.getException = function(self) return 'ErrnoException' end end",
            "  local restricted, left = after(hookof(group or 'Use.Tracking'), param)",
            "  return restricted, left, param.text",
            "end");

    @Test
    public void tablesOfTheProcessWithoutTheHardware() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS + "\n" + TABLE_STUBS);
        String kept = String.join("\n",
                "5f8e1c000-5f8e1d000 r--p 00000000 fe:05 1234                      /system/bin/app_process64",
                "7a1b000000-7a1b001000 r--p 00000000 00:00 0                       [anon:dalvik-/system/framework/boot.art]",
                "7a1b001000-7a1b002000 rw-p 00000000 00:00 0",
                "7a1b002000-7a1b003000 r--p 00000000 fe:05 2345                    /system/lib64/libEGL.so",
                "7a1b003000-7a1b004000 r--p 00000000 fe:0f 3456                    /vendor/lib64/egl/libGLESv2_angle.so",
                "");
        String vendor = String.join("\n",
                "7a1c000000-7a1c001000 r--p 00000000 fe:0f 222                     /vendor/lib64/egl/libGLESv2_emulation.so",
                "7a1c001000-7a1c002000 r-xp 00001000 fe:0f 222                     /vendor/lib64/egl/libGLESv2_emulation.so",
                "7a1d000000-7a1d001000 r--p 00000000 fe:0f 333                     " +
                        "/vendor/lib64/hw/android.hardware.graphics.mapper@3.0-impl-ranchu.so",
                "7a1e000000-7a1e001000 r--p 00000000 fe:0f 444                     /vendor/lib64/libOpenglSystemCommon.so",
                "7a1f000000-7a1f001000 rw-s 00000000 00:05 555                     /dev/kgsl-3d0",
                "7a20000000-7a20001000 r--p 00000000 fe:05 666                     /system/lib/egl/libGLES_mali.so",
                "");
        String maps = kept + vendor;
        String smaps = "7a1c000000-7a1c001000 r--p 00000000 fe:0f 222   /vendor/lib64/egl/libEGL_emulation.so\n" +
                "Size:                  4 kB\nVmFlags: rd mr mw me\n" +
                "7a1b002000-7a1b003000 r--p 00000000 fe:05 2345   /system/lib64/libEGL.so\n" +
                "Size:                  4 kB\nVmFlags: rd mr mw me\n";
        for (String path : new String[]{"/proc/self/maps", "/proc/123/maps", "/proc/thread-self/smaps",
                "/proc/123/task/124/maps", "/proc/self/root/proc/self/maps", "proc/self/maps", "/dev/fd/9/maps"}) {
            Varargs result = Scripts.run(globals, "return opened('" + path + "', [[" + maps + "]])");
            assertTrue(path, result.arg1().toboolean());
            assertEquals(path, "6", result.arg(2).tojstring());
            assertEquals(path, kept, result.arg(3).tojstring());
        }
        Varargs result = Scripts.run(globals, "return opened('/proc/self/smaps', [[" + smaps + "]])");
        assertEquals("1", result.arg(2).tojstring());
        assertEquals("7a1b002000-7a1b003000 r--p 00000000 fe:05 2345   /system/lib64/libEGL.so\n" +
                "Size:                  4 kB\nVmFlags: rd mr mw me\n", result.arg(3).tojstring());

        String mounts = "/dev/block/pci/pci0000:00/0000:00:06.0/by-name/metadata /metadata ext4 rw,seclabel 0 0\n" +
                "/dev/block/dm-5 /vendor ext4 ro,seclabel 0 0\n" +
                "/dev/block/by-name/misc /misc ext4 rw 0 0\n";
        String mountinfo = "43 31 253:49 / /metadata rw,nosuid shared:24 - ext4 " +
                "/dev/block/platform/soc/1d84000.ufshc/by-name/metadata rw,seclabel\n" +
                "44 31 8:5 / /mnt/vendor/persist rw shared:25 - ext4 /dev/block/bootdevice/by-name/persist rw\n";
        String mountstats = "device /dev/block/platform/14700000.ufs/by-name/metadata mounted on /metadata with fstype ext4\n";
        result = Scripts.run(globals, "local a, b, c = opened('/proc/mounts', [[" + mounts + "]])\n" +
                "local d, e, f = opened('/proc/self/mountinfo', [[" + mountinfo + "]])\n" +
                "return a, b, c, d, e, f, select(3, opened('/proc/123/mountstats', [[" + mountstats + "]]))");
        assertTrue(result.arg1().toboolean());
        assertEquals("1", result.arg(2).tojstring());
        assertEquals("/dev/block/by-name/metadata /metadata ext4 rw,seclabel 0 0\n" +
                "/dev/block/dm-5 /vendor ext4 ro,seclabel 0 0\n/dev/block/by-name/misc /misc ext4 rw 0 0\n",
                result.arg(3).tojstring());
        assertEquals("2", result.arg(5).tojstring());
        assertEquals("43 31 253:49 / /metadata rw,nosuid shared:24 - ext4 /dev/block/by-name/metadata rw,seclabel\n" +
                "44 31 8:5 / /mnt/vendor/persist rw shared:25 - ext4 /dev/block/by-name/persist rw\n",
                result.arg(6).tojstring());
        assertEquals("device /dev/block/by-name/metadata mounted on /metadata with fstype ext4\n",
                result.arg(7).tojstring());

        // Not the tables: a file of the app, a failed open, another group, a text without the vendor
        result = Scripts.run(globals, "return opened('/data/user/0/test.app/files/maps/tile', [[" + maps + "]]),\n" +
                "  opened('/proc/self/maps', [[" + maps + "]], nil, true),\n" +
                "  opened('/proc/self/maps', [[" + maps + "]], 'Read.Identifiers'),\n" +
                "  opened('/proc/self/maps', [[" + kept + "]]), opened('/proc/self/mounts', 'tmpfs /dev tmpfs rw 0 0')");
        for (int i = 1; i <= 5; i++)
            assertFalse(String.valueOf(i), result.arg(i).toboolean());

        // Only the hooks of open of Use.Tracking run for them, and not as hidden files
        Object open = xcoreFilter(Scripts.run(globals, "return filter(hookof('Use.Tracking'))").arg1().checktable().unpack());
        Object stat = xcoreFilter(Scripts.run(globals, "return filter(hookof('Use.Tracking', nil, 'stat'))")
                .arg1().checktable().unpack());
        Object identifiers = xcoreFilter(Scripts.run(globals, "return filter(hookof('Read.Identifiers'))")
                .arg1().checktable().unpack());
        for (String path : new String[]{"/proc/self/maps", "/proc/self/smaps", "/proc/mounts", "proc/self/mountinfo",
                "/proc/self/task/124/mountstats"}) {
            assertTrue(path, filterMatches(open, path));
            assertFalse(path, filterMatches(stat, path));
            assertFalse(path, filterMatches(identifiers, path));
        }
        // Hidden files of the groups by a link of /proc, like the working directory, stay hidden with the same parts
        result = Scripts.run(globals, "LINKS['/proc/self/cwd/su/mounts'] = '/su/mounts'\n" +
                "LINKS['/proc/self/cwd/fstab.qcom'] = '/fstab.qcom'\n" +
                "return before(hookof('Use.Tracking'), paramof('/proc/self/maps')),\n" +
                "  before(hookof('Read.Debugging'), paramof('/proc/self/cwd/su/mounts')),\n" +
                "  before(hookof('Use.Tracking'), paramof('/proc/self/cwd/fstab.qcom/mounts'))");
        assertFalse(result.arg1().toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertFalse(Scripts.run(globals, "hidden = function() error('hidden') end\n" +
                "return before(hookof('Use.Tracking'), paramof('/data/user/0/test.app/files/maps/tile'))").arg1().toboolean());
    }

    // For Read.Debugging the mount tables leave out the mounts of Magisk: its tmpfs on /debug_ramdisk or /sbin, its
    // programs, the mirrors, modules and worker in .magisk, also as the source, the folders the worker mounts over, the
    // mounts in those, by the parent in mountinfo, also one listed later, and else by a later mount point under them,
    // and in mountinfo the files of the modules by their folder in /data; the other mounts, an earlier one under a
    // folder of Magisk, the folder of an app named magisk and a table without root stay. Only the hooks of open run
    // for the mount tables, not for the maps, and a table through the hooks of both groups loses both, in either order.
    @Test
    public void mountTablesWithoutMagisk() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS + "\n" + TABLE_STUBS);
        String kept = "/dev/block/dm-5 /vendor ext4 ro,seclabel 0 0\ntmpfs /dev tmpfs rw,seclabel,nosuid 0 0\n" +
                "/dev/block/dm-48 /data/user/0/com.example.magisk f2fs rw 0 0\n";
        String mounts = "/dev/block/dm-5 /vendor ext4 ro,seclabel 0 0\n" +
                "magisk /debug_ramdisk tmpfs rw,seclabel,relatime,mode=755 0 0\n" +
                "/dev/block/dm-0 /debug_ramdisk/.magisk/mirror/system_root ext4 ro,seclabel 0 0\n" +
                "/dev/block/dm-48 /debug_ramdisk/.magisk/modules f2fs rw,lazytime 0 0\n" +
                "worker /debug_ramdisk/.magisk/worker tmpfs rw,seclabel,relatime 0 0\n" +
                "worker /system/bin tmpfs ro,seclabel,relatime 0 0\n" +
                "/dev/block/dm-5 /system/bin/sh ext4 ro 0 0\n" +
                "magisk /system/bin/magisk tmpfs ro,seclabel,relatime 0 0\n" +
                "tmpfs /dev tmpfs rw,seclabel,nosuid 0 0\n" +
                "tmpfs /sbin tmpfs rw,seclabel,relatime,mode=755 0 0\n" +
                "/dev/block/dm-0 /sbin/.magisk/mirror/system ext4 ro 0 0\n" +
                "/sbin/.magisk/block/vendor /vendor/lib64/libexample.so ext4 ro 0 0\n" +
                "/dev/block/dm-48 /data/user/0/com.example.magisk f2fs rw 0 0\n";
        String mountinfo = "23 1 253:5 / /vendor ro,relatime shared:2 - ext4 /dev/block/dm-5 ro,seclabel\n" +
                "49 34 0:19 / /debug_ramdisk rw,relatime shared:13 - tmpfs magisk rw,seclabel,mode=755\n" +
                "50 49 0:20 / /debug_ramdisk/.magisk/worker rw,relatime shared:14 - tmpfs worker rw,seclabel\n" +
                "51 23 0:20 /system/bin /system/bin ro,relatime shared:14 - tmpfs worker rw,seclabel\n" +
                "52 23 253:48 /adb/modules/example/system/bin/tool /system/bin/tool ro,relatime shared:30 - f2fs " +
                "/dev/block/dm-48 rw\n" +
                "53 51 253:5 /system/bin/sh /system/bin/sh ro,relatime shared:2 - ext4 /dev/block/dm-5 ro\n" +
                "54 55 253:5 /system/xbin/tool /system/xbin/tool ro,relatime shared:2 - ext4 /dev/block/dm-5 ro\n" +
                "55 23 0:20 /system/xbin /system/xbin ro,relatime shared:14 - tmpfs worker rw,seclabel\n" +
                "60 1 253:48 / /data rw,nosuid shared:30 - f2fs /dev/block/dm-48 rw,lazytime\n";
        String mountstats = "device /dev/block/dm-5 mounted on /vendor with fstype ext4\n" +
                "device magisk mounted on /debug_ramdisk with fstype tmpfs\n" +
                "device worker mounted on /system/bin with fstype tmpfs\n";
        Varargs result = Scripts.run(globals, "local a, b, c = opened('/proc/mounts', [[" + mounts + "]], 'Read.Debugging')\n" +
                "local d, e, f = opened('/proc/self/mountinfo', [[" + mountinfo + "]], 'Read.Debugging')\n" +
                "local g, h, i = opened('/proc/123/task/124/mountstats', [[" + mountstats + "]], 'Read.Debugging')\n" +
                "return a, b, c, d, e, f, g, h, i");
        assertTrue(result.arg1().toboolean());
        assertEquals("10", result.arg(2).tojstring());
        assertEquals(kept, result.arg(3).tojstring());
        assertTrue(result.arg(4).toboolean());
        assertEquals("7", result.arg(5).tojstring());
        assertEquals("23 1 253:5 / /vendor ro,relatime shared:2 - ext4 /dev/block/dm-5 ro,seclabel\n" +
                "60 1 253:48 / /data rw,nosuid shared:30 - f2fs /dev/block/dm-48 rw,lazytime\n", result.arg(6).tojstring());
        assertTrue(result.arg(7).toboolean());
        assertEquals("2", result.arg(8).tojstring());
        assertEquals("device /dev/block/dm-5 mounted on /vendor with fstype ext4\n", result.arg(9).tojstring());

        // A mount under a folder of Magisk from before Magisk covered it stays
        result = Scripts.run(globals, "return opened('/proc/mounts', '/dev/block/dm-6 /system/bin/x ext4 ro 0 0\\n' ..\n" +
                "  'worker /system/bin tmpfs ro 0 0\\n/dev/block/dm-5 /system/bin/sh ext4 ro 0 0\\n', 'Read.Debugging')");
        assertEquals("2", result.arg(2).tojstring());
        assertEquals("/dev/block/dm-6 /system/bin/x ext4 ro 0 0\n", result.arg(3).tojstring());

        // Stay: a table without root, the maps, a failed open, and the mounts of Magisk for Use.Tracking
        result = Scripts.run(globals, "return opened('/proc/self/mounts', [[" + kept + "]], 'Read.Debugging'),\n" +
                "  opened('/proc/self/maps', '7a1f000000-7a1f001000 rw-s 00000000 00:05 555   /dev/kgsl-3d0\\n', 'Read.Debugging'),\n" +
                "  opened('/proc/mounts', [[" + mounts + "]], 'Read.Debugging', true),\n" +
                "  opened('/proc/mounts', [[" + mounts + "]], 'Use.Tracking')");
        for (int i = 1; i <= 4; i++)
            assertFalse(String.valueOf(i), result.arg(i).toboolean());

        // Both groups on one open, each replacing the text the other left
        String both = "/dev/block/platform/soc/1d84000.ufshc/by-name/metadata /metadata ext4 rw 0 0\n" +
                "magisk /debug_ramdisk tmpfs rw 0 0\n";
        result = Scripts.run(globals,
                "local a = select(3, opened('/proc/mounts', [[" + both + "]], 'Use.Tracking'))\n" +
                        "local b = select(3, opened('/proc/mounts', [[" + both + "]], 'Read.Debugging'))\n" +
                        "return select(3, opened('/proc/mounts', a, 'Read.Debugging')), select(3, opened('/proc/mounts', b))");
        assertEquals("/dev/block/by-name/metadata /metadata ext4 rw 0 0\n", result.arg1().tojstring());
        assertEquals("/dev/block/by-name/metadata /metadata ext4 rw 0 0\n", result.arg(2).tojstring());

        Object open = xcoreFilter(Scripts.run(globals, "return filter(hookof('Read.Debugging'))").arg1().checktable().unpack());
        Object stat = xcoreFilter(Scripts.run(globals, "return filter(hookof('Read.Debugging', nil, 'stat'))")
                .arg1().checktable().unpack());
        for (String path : new String[]{"/proc/mounts", "/proc/self/mounts", "proc/self/mountinfo", "/proc/self/task/124/mountstats"}) {
            assertTrue(path, filterMatches(open, path));
            assertFalse(path, filterMatches(stat, path));
        }
        assertFalse(filterMatches(open, "/proc/self/maps"));
        assertFalse(Scripts.run(globals, "hidden = function() error('hidden') end\n" +
                "return before(hookof('Read.Debugging'), paramof('/data/user/0/test.app/files/mounts'))").arg1().toboolean());
    }

    // The file stores of java.nio, which read the mount entries natively, name the partition like the mount table the
    // app reads itself, by name() and toString(); a failed call and a device without a controller stay, and no path is
    // looked at
    @Test
    public void fileStoresNameThePartitionLikeTheMountTable() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS + "\n" + TABLE_STUBS + "\n" +
                "function named(method, result, group, failed)\n" +
                "  local param = paramof(nil, result)\n" +
                "  if failed then param.getException = function(self) return 'SecurityException' end end\n" +
                "  local hook = hookof(group or 'Use.Tracking', 'sun.nio.fs.UnixFileStore', method, 'java.lang.String')\n" +
                "  local restricted, real, fake = after(hook, param)\n" +
                "  return restricted, real, fake, param.result\n" +
                "end");
        String device = "/dev/block/platform/soc/1d84000.ufshc/by-name/system";
        String table = Scripts.run(globals, "return select(3, opened('/proc/self/mounts', '" + device + " /system ext4 ro 0 0'))")
                .arg1().tojstring();
        Varargs result = Scripts.run(globals, "return named('name', '" + device + "')");
        assertTrue(result.arg1().toboolean());
        assertEquals(device, result.arg(2).tojstring());
        assertEquals(table.split(" ")[0], result.arg(4).tojstring());
        assertEquals("/dev/block/by-name/system", result.arg(4).tojstring());
        result = Scripts.run(globals, "return named('toString', '/system (" + device + ")')");
        assertTrue(result.arg1().toboolean());
        assertEquals("/system (/dev/block/by-name/system)", result.arg(4).tojstring());

        result = Scripts.run(globals, "return named('name', '" + device + "', nil, true), named('name', '/dev/block/dm-5'),\n" +
                "  named('toString', '/data (/dev/block/dm-48)')");
        for (int i = 1; i <= 3; i++)
            assertFalse(String.valueOf(i), result.arg(i).toboolean());
        assertTrue(Scripts.run(globals, "hidden = function() error('hidden') end\n" +
                "local hook = hookof('Use.Tracking', 'sun.nio.fs.UnixFileStore', 'name', 'java.lang.String')\n" +
                "return filter(hook) == nil and before(hook, paramof(nil)) == false").arg1().toboolean());
    }

    // A path with /../ counts by the file it leads to, which XCore lets through for every group, not by the folders it
    // passes, like one of the app with the name of a folder of /sys
    @Test
    public void parentFoldersCountByTheFile() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "LINKS['/data/user/0/app/files/soc1/../note'] = '/data/user/0/app/files/note'\n" +
                        "LINKS['/data/../sys/devices/soc0/soc_id'] = '/sys/devices/soc0/soc_id'\n" +
                        "local model = hookof('Use.Tracking')\n" +
                        "return hidden(model, '/data/user/0/app/files/soc1/../note'),\n" +
                        "  hidden(model, '/data/user/0/app/files/soc1/note'),\n" +
                        "  hidden(model, '/data/../sys/devices/soc0/soc_id')");
        assertFalse(result.arg(1).toboolean());
        // Without /../ the text of a path is all the script has
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
    }

    // The chip of the bus of systems on a chip, SCSI storage by its link to the device, input devices and storage
    // of /proc, the folders of VPN interfaces (by the names of vpn) and su and Magisk; only their files
    @Test
    public void hardwareModelsVpnAndRoot() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "LINKS['/sys/block/sda/device/model'] = '/sys/devices/platform/soc/1d84000.ufshc/host0/target0:0:0/0:0:0:0/model'\n" +
                        "LINKS['/sys/devices/soc0/../soc0/serial_number'] = '/sys/devices/soc0/serial_number'\n" +
                        "LINKS['/sys/class/net/tun0'] = '/sys/devices/virtual/net/tun0'\n" +
                        "LINKS['/sys/class/net/wlan0'] = '/sys/devices/platform/soc/18800000.qcom,icnss/net/wlan0'\n" +
                        "LINKS['/system/bin/su'] = '/debug_ramdisk/magisk'\n" +
                        "LINKS['/proc/self/root/system/xbin/su'] = '/system/xbin/su'\n" +
                        "local results = {}\n" +
                        "local function check(group, path) results[#results + 1] = hidden(hookof(group), path) end\n" +
                        "for _, path in ipairs({ '/sys/devices/soc0/serial_number', '/sys/bus/soc/devices/soc0/serial_number',\n" +
                        "  '/sys/devices/soc0/../soc0/serial_number', '/sys/devices/soc0/soc_id' }) do check('Read.Identifiers', path) end\n" +
                        "for _, path in ipairs({ '/sys/devices/soc0/soc_id', '/sys/devices/soc0', '/sys/devices/system/soc/soc0/id',\n" +
                        "  '/sys/block/sda/device/model', '/sys/class/block/sdb/device/vendor',\n" +
                        "  '/sys/devices/platform/soc/1d84000.ufshc/host0/target0:0:0/0:0:0:0/rev', '/proc/bus/input/devices',\n" +
                        "  '/proc/scsi/scsi', '/sys/class/camera/rear/rear_camfw', '/sys/devices/platform/soc/1d84000.ufshc',\n" +
                        "  '/sys/block/sda/size', '/sys/block/sda/device/queue_depth', '/proc/bus/input/handlers',\n" +
                        "  '/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq' }) do check('Use.Tracking', path) end\n" +
                        "for _, path in ipairs({ '/sys/class/net/tun0', '/sys/class/net/wg0/address', '/sys/devices/virtual/net/ppp0',\n" +
                        "  '/sys/class/net/wlan0', '/sys/class/net', '/sys/class/net/lo', '/storage/emulated/0/net/tunes' }) do\n" +
                        "  check('Read.VPN', path)\n" +
                        "end\n" +
                        "for _, path in ipairs({ '/system/xbin/su', '/system/bin/su', '/sbin/su', '/debug_ramdisk/magisk',\n" +
                        "  '/system/bin/magiskpolicy', '/system/app/Superuser.apk', '/proc/self/root/system/xbin/su',\n" +
                        "  '/system/bin/sh', '/data/user/0/com.termux/files/usr/bin/su', '/sdcard/su', '/system/bin/sudo' }) do\n" +
                        "  check('Read.Debugging', path)\n" +
                        "end\n" +
                        "local exists = paramof('/sys/class/net/tun0')\n" +
                        "before(hookof('Read.VPN', 'java.io.UnixFileSystem', 'checkAccess', 'boolean'), exists)\n" +
                        "local tracking, debugging = {}, {}\n" +
                        "for _, value in ipairs(filter(hookof('Use.Tracking'))) do tracking[value] = true end\n" +
                        "for _, value in ipairs(filter(hookof('Read.Debugging'))) do debugging[value] = true end\n" +
                        "return table.concat((function() local t = {} for i, v in ipairs(results) do t[i] = v and 'T' or 'F' end return t end)()),\n" +
                        "  exists.early, exists.result, filter(hookof('Read.VPN'))[1], tracking['*proc/scsi/*'], tracking['*sys/*'],\n" +
                        "  debugging['/system/xbin/*'], debugging['*magisk*']");
        assertEquals("TTTF" + "TTTTTTTTTFFFFT" + "TTTFFFF" + "TTTTTTTFFFF", result.arg(1).tojstring());
        assertTrue(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertEquals("*net/*", result.arg(4).tojstring());
        for (int i = 5; i <= 8; i++)
            assertTrue(String.valueOf(i), result.arg(i).toboolean());
    }

    // The details of the processors go, their folder and numbers stay (code counts the files of the folder); the
    // codec files name the chip, also in listings of their folders
    @Test
    public void hardwareProcessorsAndCodecs() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        String[] hidden = {"/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq", "/sys/devices/system/cpu/cpu7/cpufreq",
                "/sys/devices/system/cpu/cpufreq/policy4/scaling_available_frequencies",
                "/sys/devices/system/cpu/cpu3/topology/cluster_id", "/sys/devices/system/cpu/cpu0/cache/index2/size",
                "/sys/devices/system/cpu/cpu0/cpuidle/state1/name", "/sys/devices/system/cpu/cpu5/cpu_capacity",
                "/sys/devices/system/cpu/cpu0/regs/identification/midr_el1", "/sys/devices/system/cpu/modalias",
                "/sys/devices/system/cpu/vulnerabilities/spectre_v2", "/sys/devices/system/cpu/cpuidle/current_driver",
                "/vendor/etc/media_codecs.xml", "/vendor/etc/media_codecs_sm8550_vendor.xml",
                "/odm/etc/media_codecs_performance.xml", "/apex/com.android.media.swcodec/etc/media_codecs.xml"};
        String[] stay = {"/sys/devices/system/cpu", "/sys/devices/system/cpu/cpu0", "/sys/devices/system/cpu/possible",
                "/sys/devices/system/cpu/present", "/sys/devices/system/cpu/online", "/sys/devices/system/cpu/cpu0/online",
                "/vendor/etc/media_profiles_V1_0.xml", "/vendor/etc"};
        for (String path : hidden)
            assertTrue(path, Scripts.run(globals, "return hidden(hookof('Use.Tracking'), '" + path + "')").arg1().toboolean());
        for (String path : stay)
            assertFalse(path, Scripts.run(globals, "return hidden(hookof('Use.Tracking'), '" + path + "')").arg1().toboolean());
        Varargs result = Scripts.run(globals,
                "local hook = hookof('Use.Tracking', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]')\n" +
                        "local cpu = paramof('/sys/devices/system/cpu', namesof('cpu0', 'cpufreq', 'possible', 'modalias'))\n" +
                        "after(hook, cpu)\n" +
                        "local etc = paramof('/vendor/etc', namesof('media_codecs.xml', 'audio_policy.conf'))\n" +
                        "after(hook, etc)\n" +
                        "return cpu.result.length, cpu.result[1], cpu.result[2], etc.result.length, etc.result[1]");
        assertEquals(2, result.arg1().toint());
        assertEquals("cpu0", result.arg(2).tojstring());
        assertEquals("possible", result.arg(3).tojstring());
        assertEquals(1, result.arg(4).toint());
        assertEquals("audio_policy.conf", result.arg(5).tojstring());
        Object filter = xcoreFilter(Scripts.run(globals, "return filter(hookof('Use.Tracking'))").arg1().checktable().unpack());
        for (String path : new String[]{"/vendor/etc/media_codecs.xml", "/apex/com.android.media.swcodec/etc/media_codecs.xml",
                "/vendor/etc", "/system/etc"})
            assertTrue(path, filterMatches(filter, path));
    }

    // The file systems statvfs says are mounted read-only (ST_RDONLY, here with ST_RELATIME), by the folder
    private static final String READ_ONLY_STUBS = HARDWARE_STUBS + "\n" + String.join("\n",
            "READONLY = { ['/system/xbin'] = true, ['/system/etc/permissions'] = true, ['/etc/permissions'] = true,",
            "  ['/apex/com.android.foo/etc/permissions'] = true }",
            "local bind = luajava.bindClass",
            "luajava.bindClass = function(name)",
            "  if name ~= 'android.system.Os' then return bind(name) end",
            "  return { statvfs = function(self, folder) return { f_flag = READONLY[folder] and 1025 or 1024 } end }",
            "end",
            "function makingparam(path, flags)",
            "  local param = paramof(path, nil, flags)",
            "  param.unhooked = function(self, fn) return fn() end",
            "  return param",
            "end");

    // Resolving, reading the link of and making a hidden file fail like for a file which does not exist;
    // making one fails like in /sys, or like in a folder which does not exist when the group hides the folder
    @Test
    public void hardwareLinksAndMaking() throws Exception {
        Globals globals = Scripts.load("hardware_files", READ_ONLY_STUBS);
        Varargs result = Scripts.run(globals,
                "local id = '/sys/devices/system/chip-id/unique_id'\n" +
                        "local function run(group, class, method, path)\n" +
                        "  local param = makingparam(path)\n" +
                        "  before(hookof(group, class, method), param)\n" +
                        "  if not param.early then\n" +
                        "    return 'argument ' .. param.argument.index .. ':[' .. param.argument.value .. ']'\n" +
                        "  end\n" +
                        "  return param.result.class .. ':' .. tostring(param.result.args[1])\n" +
                        "end\n" +
                        "return run('Read.Identifiers', 'sun.nio.fs.UnixNativeDispatcher', 'realpath', id),\n" +
                        "  run('Read.Identifiers', 'sun.nio.fs.UnixNativeDispatcher', 'readlink', id),\n" +
                        "  run('Read.Identifiers', 'android.system.Os', 'readlink', id),\n" +
                        "  run('Read.Identifiers', 'java.io.UnixFileSystem', 'createFileExclusively', id),\n" +
                        "  run('Use.Tracking', 'java.io.UnixFileSystem', 'createFileExclusively', '/sys/devices/system/chip-id/product_id'),\n" +
                        "  run('Read.Debugging', 'java.io.UnixFileSystem', 'createFileExclusively', '/system/xbin/su'),\n" +
                        "  run('Read.Debugging', 'java.io.UnixFileSystem', 'createFileExclusively', '/data/local/su')");
        assertEquals("sun.nio.fs.UnixException:2", result.arg(1).tojstring());
        assertEquals("sun.nio.fs.UnixException:2", result.arg(2).tojstring());
        // The real call fails, with an empty path, like for a missing file (see hiddenFileAttributesAnswerLikeAMissingFile)
        assertEquals("argument 0:[]", result.arg(3).tojstring());
        assertEquals("java.io.IOException:Permission denied", result.arg(4).tojstring());
        assertEquals("java.io.IOException:No such file or directory", result.arg(5).tojstring());
        // On a read-only partition the kernel answers EROFS before the permissions
        assertEquals("java.io.IOException:Read-only file system", result.arg(6).tojstring());
        assertEquals("java.io.IOException:Permission denied", result.arg(7).tojstring());
    }

    // The folder of a VPN interface on Android 8.1, whose link and real path apps can read and whose files they can open
    // and make (EACCES), answers like an interface which does not exist (ENOENT), also for a name in it which does not
    // exist and by FileInputStream and FileOutputStream, which open natively there and throw FileNotFoundException with
    // the path and the error; another interface stays, and so does a folder the app cannot reach, like on Android 10
    // and later
    @Test
    public void vpnFoldersAnswerLikeMissingInterfaces() throws Exception {
        Globals globals = Scripts.load("hardware_files", READ_ONLY_STUBS);
        Varargs result = Scripts.run(globals,
                "PRESENT = { ['/sys/class/net/tun0'] = true, ['/sys/class/net/tun0/mtu'] = true,\n" +
                        "  ['/sys/devices/virtual/net/tun0'] = true, ['/sys/class/net/wlan0'] = true }\n" +
                        "local file = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, path)\n" +
                        "  local made = file(name, path)\n" +
                        "  if name == 'java.io.File' then made.exists = function() return PRESENT[path] == true end end\n" +
                        "  return made\n" +
                        "end\n" +
                        "local function run(class, method, path, flags)\n" +
                        "  local param = makingparam(path, flags)\n" +
                        "  before(hookof('Read.VPN', class, method), param)\n" +
                        "  if param.early and type(param.result) == 'table' then\n" +
                        "    return param.result.class .. ':' .. tostring(param.result.args[1])\n" +
                        "  elseif param.early then\n" +
                        "    return tostring(param.result)\n" +
                        "  end\n" +
                        "  local argument = param.argument\n" +
                        "  return argument and 'argument ' .. argument.index .. ':[' .. argument.value .. ']' or 'real'\n" +
                        "end\n" +
                        "local os, block, nio, io = 'android.system.Os', 'libcore.io.BlockGuardOs', 'sun.nio.fs.UnixNativeDispatcher',\n" +
                        "  'java.io.UnixFileSystem'\n" +
                        "local fis, fos = 'java.io.FileInputStream', 'java.io.FileOutputStream'\n" +
                        "local results = { run(os, 'readlink', '/sys/class/net/tun0'), run(nio, 'readlink', '/sys/class/net/tun0'),\n" +
                        "  run(nio, 'realpath', '/sys/class/net/tun0'),\n" +
                        "  run(block, 'open', '/sys/class/net/tun0/mtu', 0), run(nio, 'open', '/sys/class/net/tun0/mtu', 0),\n" +
                        // FileOutputStream of the folder makes a name in /sys/class/net (EACCES), of a file in it the folder is missing
                        "  run(fis, 'open0', '/sys/class/net/tun0/mtu'), run(fos, 'open0', '/sys/class/net/tun0', false),\n" +
                        "  run(fos, 'open0', '/sys/class/net/tun0/x', false),\n" +
                        "  run(nio, 'opendir', '/sys/devices/virtual/net/tun0'), run(io, 'getLastModifiedTime', '/sys/class/net/tun0'),\n" +
                        // Os.open with O_WRONLY | O_CREAT | O_TRUNC and File.createNewFile of a name which does not exist
                        "  run(block, 'open', '/sys/class/net/tun0/x', 577), run(io, 'createFileExclusively', '/sys/class/net/tun0/x'),\n" +
                        "  run(os, 'readlink', '/sys/class/net/wlan0'), run(io, 'createFileExclusively', '/sys/class/net/wlan0/x') }\n" +
                        "PRESENT = {}\n" +
                        "results[#results + 1] = run(block, 'open', '/sys/class/net/tun0/mtu', 0)\n" +
                        "results[#results + 1] = run(fis, 'open0', '/sys/class/net/tun0/mtu')\n" +
                        "return table.concat(results, '|')");
        String missing = "java.io.FileNotFoundException:/sys/class/net/tun0";
        assertEquals(String.join("|", "argument 0:[]", "sun.nio.fs.UnixException:2", "sun.nio.fs.UnixException:2",
                "argument 0:[]", "sun.nio.fs.UnixException:2", missing + "/mtu (No such file or directory)",
                missing + " (Permission denied)", missing + "/x (No such file or directory)",
                "sun.nio.fs.UnixException:2", "0", "argument 0:[]", "java.io.IOException:No such file or directory", "real", "real",
                "real", "real"), result.arg1().tojstring());
    }

    // Opening a hidden file to make it on a file system mounted read-only fails like for a file which does not exist
    // there, EROFS, which the kernel checks before the permissions, by any path to the folder; without O_CREAT, and
    // under a hidden file, the file is not there (ENOENT)
    @Test
    public void makingOnReadOnlyFileSystems() throws Exception {
        Globals globals = Scripts.load("hardware_files", READ_ONLY_STUBS);
        Varargs result = Scripts.run(globals,
                "local file = '/system/etc/permissions/privapp-permissions-com.example.xml'\n" +
                        "local function run(class, method, path, flags)\n" +
                        "  local param = makingparam(path, flags)\n" +
                        "  before(hookof('Get.Applications', class, method), param)\n" +
                        "  if not param.early then\n" +
                        "    return 'argument ' .. param.argument.index .. ':[' .. param.argument.value .. ']'\n" +
                        "  end\n" +
                        "  return param.result.class .. ':' .. tostring(param.result.args[#param.result.args])\n" +
                        "end\n" +
                        "local os, nio, io = 'libcore.io.BlockGuardOs', 'sun.nio.fs.UnixNativeDispatcher', 'java.io.UnixFileSystem'\n" +
                        // FileOutputStream (O_WRONLY | O_CREAT | O_TRUNC), RandomAccessFile rw (O_RDWR | O_CREAT), reading
                        "return run(os, 'open', file, 577), run(os, 'open', file, 66), run(os, 'open', file, 0),\n" +
                        "  run(nio, 'open', file, 577), run(os, 'open', file .. '/a', 577),\n" +
                        "  run(io, 'createFileExclusively', '/etc/permissions/privapp-permissions-com.example.xml'),\n" +
                        "  run(io, 'createFileExclusively', '/apex/com.android.foo/etc/permissions/privapp-permissions-a.xml')");
        assertEquals("android.system.ErrnoException:30", result.arg(1).tojstring());
        assertEquals("android.system.ErrnoException:30", result.arg(2).tojstring());
        assertEquals("argument 0:[]", result.arg(3).tojstring());
        assertEquals("sun.nio.fs.UnixException:30", result.arg(4).tojstring());
        assertEquals("argument 0:[]", result.arg(5).tojstring());
        assertEquals("java.io.IOException:Read-only file system", result.arg(6).tojstring());
        assertEquals("java.io.IOException:Read-only file system", result.arg(7).tojstring());
    }

    // Reading or changing the attributes of a hidden file and its mode run with an empty path, and linking it with a
    // name of its folder which does not exist, which Android answers like a missing file in its own order and with its
    // own exception, without the frames of the hook: libcore checks for null arguments first, Linux 6.11 and later
    // (6.13 for getxattr) the arguments, older kernels the path; files not hidden keep their path
    @Test
    public void hiddenFileAttributesAnswerLikeAMissingFile() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local function run(method, path)\n" +
                        "  local param = paramof(path or '/system/etc/permissions/privapp-permissions-com.example.xml')\n" +
                        "  local restricted = before(hookof('Get.Applications', 'android.system.Os', method), param)\n" +
                        "  if param.early then\n" +
                        "    return tostring(restricted) .. ':' .. param.result.class .. ':' .. param.result.args[1] .. ':' ..\n" +
                        "      param.result.args[2]\n" +
                        "  end\n" +
                        "  return tostring(restricted) .. ':' .. (param.argument and param.argument.index .. ':[' ..\n" +
                        "    param.argument.value .. ']' or 'unchanged')\n" +
                        "end\n" +
                        "return run('setxattr'), run('removexattr'), run('chmod'), run('listxattr'),\n" +
                        "  run('setxattr', '/system/etc/permissions/platform.xml'), run('link'), run('getxattr')");
        assertEquals("true:0:[]", result.arg(1).tojstring());
        assertEquals("true:0:[]", result.arg(2).tojstring());
        assertEquals("true:0:[]", result.arg(3).tojstring());
        assertEquals("true:0:[]", result.arg(4).tojstring());
        assertEquals("false:unchanged", result.arg(5).tojstring());
        assertTrue(result.arg(6).tojstring(),
                result.arg(6).tojstring().startsWith("true:0:[/system/etc/permissions/privapp-permissions-com.example.xml~"));
        assertEquals("true:0:[]", result.arg(7).tojstring());
    }

    // Making a folder or a pipe by the name of a hidden id fails like for a missing name in a folder of /sys, which
    // apps cannot write (EACCES), by Os and java.nio, and under a hidden file like in a folder which does not exist;
    // removing it and changing its mode fail like for a missing file (ENOENT)
    @Test
    public void makingByTheNameOfAHiddenFile() throws Exception {
        Globals globals = Scripts.load("hardware_files", READ_ONLY_STUBS);
        Varargs result = Scripts.run(globals,
                "local id = '/sys/devices/system/chip-id/unique_id'\n" +
                        "local function run(class, method, path)\n" +
                        "  local param = makingparam(path)\n" +
                        "  before(hookof('Read.Identifiers', class, method), param)\n" +
                        "  if not param.early then\n" +
                        "    return 'argument ' .. param.argument.index .. ':[' .. param.argument.value .. ']'\n" +
                        "  end\n" +
                        "  return param.result.class .. ':' .. table.concat(param.result.args, ':')\n" +
                        "end\n" +
                        "local os, nio = 'android.system.Os', 'sun.nio.fs.UnixNativeDispatcher'\n" +
                        "return run(os, 'mkdir', id), run(os, 'mkfifo', id), run(nio, 'mkdir', id), run(os, 'mkdir', id .. '/a'),\n" +
                        "  run(nio, 'mkdir', id .. '/a'), run(os, 'remove', id), run(nio, 'chmod', id)");
        assertEquals("android.system.ErrnoException:mkdir:13", result.arg(1).tojstring());
        assertEquals("android.system.ErrnoException:mkfifo:13", result.arg(2).tojstring());
        assertEquals("sun.nio.fs.UnixException:13", result.arg(3).tojstring());
        assertEquals("argument 0:[]", result.arg(4).tojstring());
        assertEquals("sun.nio.fs.UnixException:2", result.arg(5).tojstring());
        assertEquals("argument 0:[]", result.arg(6).tojstring());
        assertEquals("sun.nio.fs.UnixException:2", result.arg(7).tojstring());
    }

    // The same for the files Use.Tracking hides, like the folder of the speeds of the processors in /sys (EACCES) and a
    // font of a maker on the read-only /system, where Linux answers EROFS for making a name before the permissions and
    // for removing one before it looks the name up, so the real call answers that; the attributes like a missing file
    @Test
    public void makingByTheNameOfAHiddenFileOfTheModel() throws Exception {
        Globals globals = Scripts.load("hardware_files", READ_ONLY_STUBS);
        Varargs result = Scripts.run(globals,
                "READONLY['/system/fonts'] = true\n" +
                        "local cpufreq, font = '/sys/devices/system/cpu/cpufreq', '/system/fonts/SamsungOne-400.ttf'\n" +
                        "local function run(class, method, path)\n" +
                        "  local param = makingparam(path)\n" +
                        "  local restricted = before(hookof('Use.Tracking', class, method), param)\n" +
                        "  if not param.early then\n" +
                        "    return tostring(restricted) .. ' ' .. (param.argument and 'argument ' .. param.argument.index ..\n" +
                        "      ':[' .. param.argument.value .. ']' or 'unchanged')\n" +
                        "  end\n" +
                        "  return param.result.class .. ':' .. table.concat(param.result.args, ':')\n" +
                        "end\n" +
                        "local os, nio = 'android.system.Os', 'sun.nio.fs.UnixNativeDispatcher'\n" +
                        "return run(os, 'mkdir', cpufreq), run(nio, 'mkdir', cpufreq), run(os, 'mkdir', font), run(nio, 'mkdir', font),\n" +
                        "  run(os, 'mkfifo', font), run(os, 'listxattr', cpufreq), run(os, 'remove', cpufreq), run(os, 'remove', font),\n" +
                        "  run(nio, 'chmod', font), run(os, 'mkdir', '/system/fonts/Roboto-Regular.ttf')");
        assertEquals("android.system.ErrnoException:mkdir:13", result.arg(1).tojstring());
        assertEquals("sun.nio.fs.UnixException:13", result.arg(2).tojstring());
        assertEquals("android.system.ErrnoException:mkdir:30", result.arg(3).tojstring());
        assertEquals("sun.nio.fs.UnixException:30", result.arg(4).tojstring());
        assertEquals("android.system.ErrnoException:mkfifo:30", result.arg(5).tojstring());
        assertEquals("true argument 0:[]", result.arg(6).tojstring());
        assertEquals("true argument 0:[]", result.arg(7).tojstring());
        assertEquals("false unchanged", result.arg(8).tojstring());
        assertEquals("sun.nio.fs.UnixException:2", result.arg(9).tojstring());
        // A font of every Android stays
        assertEquals("false unchanged", result.arg(10).tojstring());
    }

    // The same for su and the files of Magisk on the read-only /system, where making a name, also a node by Files.copy
    // of a special file, gives EROFS and removing one too, which the real call answers, and for the files of the
    // fingerprint sensor in /sys (EACCES); the attributes, mode and owner like a missing file. The folders /data/adb
    // and /debug_ramdisk themselves stay, which AOSP makes, /debug_ramdisk from Android 10 (see FILES).
    // link, symlink and rename of su run with a stand-in like for the ids. FileInputStream and FileOutputStream of
    // Android 8.1 and 9, which open natively, throw FileNotFoundException with the path and the error of a missing file.
    @Test
    public void makingByTheNameOfSuAndOfTheFingerprintSensor() throws Exception {
        Globals globals = Scripts.load("hardware_files", READ_ONLY_STUBS);
        Varargs result = Scripts.run(globals,
                "READONLY['/system/bin'] = true\n" +
                        "local su, sensor = '/system/bin/su', '/sys/class/fingerprint/fingerprint'\n" +
                        "local function run(group, class, method, path)\n" +
                        "  local param = makingparam(path)\n" +
                        "  local restricted = before(hookof(group, class, method), param)\n" +
                        "  if not param.early then\n" +
                        "    return tostring(restricted) .. ' ' .. (param.argument and 'argument ' .. param.argument.index ..\n" +
                        "      ':[' .. param.argument.value .. ']' or 'unchanged')\n" +
                        "  end\n" +
                        "  return param.result.class .. ':' .. table.concat(param.result.args, ':')\n" +
                        "end\n" +
                        "local os, nio, debug, bio = 'android.system.Os', 'sun.nio.fs.UnixNativeDispatcher', 'Read.Debugging',\n" +
                        "  'Read.Biometrics'\n" +
                        "return run(debug, os, 'chmod', su), run(debug, os, 'listxattr', su), run(debug, os, 'remove', su),\n" +
                        "  run(debug, os, 'mkdir', su), run(debug, nio, 'mkdir', su), run(debug, nio, 'mknod', su),\n" +
                        "  run(debug, os, 'lchown', '/debug_ramdisk/magisk'), run(debug, os, 'mkdir', '/data/adb'),\n" +
                        "  run(debug, nio, 'mkdir', '/debug_ramdisk'), run(bio, os, 'mkdir', sensor), run(bio, nio, 'mknod', sensor),\n" +
                        "  run(bio, os, 'removexattr', sensor), run(bio, nio, 'chown', sensor .. '/name'),\n" +
                        "  run(debug, 'java.io.FileInputStream', 'open0', su), run(debug, 'java.io.FileOutputStream', 'open0', su),\n" +
                        "  run(bio, 'java.io.FileInputStream', 'open0', sensor .. '/name'),\n" +
                        "  run(bio, 'java.io.FileOutputStream', 'open0', sensor)");
        assertEquals("true argument 0:[]", result.arg(1).tojstring());
        assertEquals("true argument 0:[]", result.arg(2).tojstring());
        assertEquals("false unchanged", result.arg(3).tojstring());
        assertEquals("android.system.ErrnoException:mkdir:30", result.arg(4).tojstring());
        assertEquals("sun.nio.fs.UnixException:30", result.arg(5).tojstring());
        assertEquals("sun.nio.fs.UnixException:30", result.arg(6).tojstring());
        assertEquals("true argument 0:[]", result.arg(7).tojstring());
        assertEquals("false unchanged", result.arg(8).tojstring());
        assertEquals("false unchanged", result.arg(9).tojstring());
        assertEquals("android.system.ErrnoException:mkdir:13", result.arg(10).tojstring());
        assertEquals("sun.nio.fs.UnixException:13", result.arg(11).tojstring());
        assertEquals("true argument 0:[]", result.arg(12).tojstring());
        assertEquals("sun.nio.fs.UnixException:2", result.arg(13).tojstring());
        assertEquals("java.io.FileNotFoundException:/system/bin/su (No such file or directory)", result.arg(14).tojstring());
        assertEquals("java.io.FileNotFoundException:/system/bin/su (Read-only file system)", result.arg(15).tojstring());
        assertEquals("java.io.FileNotFoundException:/sys/class/fingerprint/fingerprint/name (No such file or directory)",
                result.arg(16).tojstring());
        assertEquals("java.io.FileNotFoundException:/sys/class/fingerprint/fingerprint (Permission denied)",
                result.arg(17).tojstring());

        Varargs linked = Scripts.run(globals,
                "local su, own = '/system/bin/su', '/data/user/0/test.app/files/a'\n" +
                        "local make = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, path)\n" +
                        "  if name ~= 'java.io.File' then return make(name, path) end\n" +
                        "  return { exists = function() return path == su end, toPath = function() return 'path:' .. path end }\n" +
                        "end\n" +
                        "local function run(class, method, first, second)\n" +
                        "  local args = { [0] = first, second }\n" +
                        "  local param = makingparam(first)\n" +
                        "  param.getArgument = function(self, index) return args[index] end\n" +
                        "  param.setArgument = function(self, index, value) args[index] = value end\n" +
                        "  local restricted = before(hookof('Read.Debugging', class, method), param)\n" +
                        "  return tostring(restricted) .. ' ' .. tostring(args[0]) .. ' ' .. tostring(args[1])\n" +
                        "end\n" +
                        "return run('android.system.Os', 'symlink', 'x', su),\n" +
                        "  run('sun.nio.fs.UnixNativeDispatcher', 'rename', own, su),\n" +
                        "  filter(hookof('Read.Debugging', 'android.system.Os', 'link')) == nil");
        assertEquals("true x /system/bin/su~", linked.arg(1).tojstring());
        assertEquals("true /data/user/0/test.app/files/a path:/system/bin/su~", linked.arg(2).tojstring());
        assertTrue(linked.arg(3).toboolean());
    }

    // link, symlink and rename of a hidden id run with a name of its folder which does not exist in place of the id, so
    // Android answers like for a missing name, in the order of Linux (EXDEV of link to another file system, EACCES in
    // /sys, ENOENT of rename); under a hidden file the stand-in is in a folder which does not exist. The hooks have no
    // filter, which sees only the first path; the target of symlink, which the kernel does not look up, and other paths
    // stay, and so does a null path, for which libcore throws
    @Test
    public void linkingAndRenamingAHiddenFile() throws Exception {
        Globals globals = Scripts.load("hardware_files", READ_ONLY_STUBS);
        Varargs result = Scripts.run(globals,
                "local id, own = '/sys/devices/system/chip-id/unique_id', '/data/user/0/test.app/files/a'\n" +
                        // A name with ~ which exists, so the next one is taken
                        "local present = { [id .. '~'] = true }\n" +
                        "local make = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, path)\n" +
                        "  if name ~= 'java.io.File' then return make(name, path) end\n" +
                        "  return { exists = function() return present[path] == true end,\n" +
                        "    toPath = function() return 'path:' .. path end }\n" +
                        "end\n" +
                        "local function run(class, method, first, second)\n" +
                        "  local args = { [0] = first, second }\n" +
                        "  local param = makingparam(first)\n" +
                        "  param.getArgument = function(self, index) return args[index] end\n" +
                        "  param.setArgument = function(self, index, value) args[index] = value end\n" +
                        "  local restricted, old = before(hookof('Read.Identifiers', class, method), param)\n" +
                        "  return tostring(restricted) .. ' ' .. tostring(old) .. ' ' .. tostring(args[0]) .. ' ' .. tostring(args[1])\n" +
                        "end\n" +
                        "local os, nio = 'android.system.Os', 'sun.nio.fs.UnixNativeDispatcher'\n" +
                        "local function nofilter(method) return filter(hookof('Read.Identifiers', os, method)) == nil end\n" +
                        "return run(os, 'symlink', 'x', id), run(nio, 'symlink', 'x', id), run(os, 'link', own, id),\n" +
                        "  run(os, 'link', id, own), run(nio, 'link', own, id), run(os, 'rename', id, '/sys/devices/system/chip-id/x'),\n" +
                        "  run(nio, 'rename', id, id .. '/a'), run(os, 'symlink', id, own), run(os, 'link', own, own .. 'b'),\n" +
                        "  run(os, 'link', own, nil), nofilter('link'), nofilter('symlink'), nofilter('rename'), nofilter('mkdir')");
        String id = "/sys/devices/system/chip-id/unique_id", own = "/data/user/0/test.app/files/a";
        assertEquals("true " + id + " x " + id + "~~", result.arg(1).tojstring());
        assertEquals("true " + id + " x path:" + id + "~~", result.arg(2).tojstring());
        assertEquals("true " + id + " " + own + " " + id + "~~", result.arg(3).tojstring());
        assertEquals("true " + id + " " + id + "~~ " + own, result.arg(4).tojstring());
        assertEquals("true " + id + " " + own + " path:" + id + "~~", result.arg(5).tojstring());
        assertEquals("true " + id + " " + id + "~~ /sys/devices/system/chip-id/x", result.arg(6).tojstring());
        assertEquals("true " + id + " path:" + id + "~~ path:" + id + "~~/a", result.arg(7).tojstring());
        assertEquals("false nil " + id + " " + own, result.arg(8).tojstring());
        assertEquals("false nil " + own + " " + own + "b", result.arg(9).tojstring());
        assertEquals("false nil " + own + " nil", result.arg(10).tojstring());
        for (int i = 11; i <= 13; i++)
            assertTrue("result " + i, result.arg(i).toboolean());
        assertFalse(result.arg(14).toboolean());
    }

    // Opening a hidden id with O_CREAT, like FileOutputStream, RandomAccessFile rw and Files.newOutputStream and
    // createFile do, fails like for a missing name in a folder of /sys (EACCES), not ENOENT, which Linux never gives for
    // a name it makes in a folder which exists; without O_CREAT, and in a hidden folder, the file is not there (ENOENT)
    @Test
    public void openingToMakeAHiddenFile() throws Exception {
        Globals globals = Scripts.load("hardware_files", READ_ONLY_STUBS);
        Varargs result = Scripts.run(globals,
                "local id = '/sys/devices/system/chip-id/unique_id'\n" +
                        "local function run(group, class, path, flags)\n" +
                        "  local param = makingparam(path, flags)\n" +
                        "  before(hookof(group, class, 'open'), param)\n" +
                        "  if not param.early then\n" +
                        "    return 'argument ' .. param.argument.index .. ':[' .. param.argument.value .. ']'\n" +
                        "  end\n" +
                        "  return param.result.class .. ':' .. table.concat(param.result.args, ':')\n" +
                        "end\n" +
                        "local ids, os, nio = 'Read.Identifiers', 'libcore.io.BlockGuardOs', 'sun.nio.fs.UnixNativeDispatcher'\n" +
                        // O_WRONLY | O_CREAT | O_TRUNC, O_RDWR | O_CREAT, O_WRONLY | O_CREAT | O_EXCL, O_RDONLY
                        "return run(ids, os, id, 577), run(ids, os, id, 66), run(ids, nio, id, 577), run(ids, nio, id, 193),\n" +
                        "  run(ids, os, id, 0), run('Use.Tracking', os, id, 577)");
        assertEquals("android.system.ErrnoException:open:13", result.arg(1).tojstring());
        assertEquals("android.system.ErrnoException:open:13", result.arg(2).tojstring());
        assertEquals("sun.nio.fs.UnixException:13", result.arg(3).tojstring());
        assertEquals("sun.nio.fs.UnixException:13", result.arg(4).tojstring());
        assertEquals("argument 0:[]", result.arg(5).tojstring());
        // Use.Tracking hides the folder of the chip too
        assertEquals("argument 0:[]", result.arg(6).tojstring());
    }

    // The hooks see a file which does not exist: an error of the kernel, no attributes or no names
    @Test
    public void hardwareMissing() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local ids = 'Read.Identifiers'\n" +
                        "local id = '/sys/devices/system/chip-id/unique_id'\n" +
                        "local open = paramof(id, nil, 0)\n" +
                        "local restricted, old = before(hookof(ids), open)\n" +
                        "local attributes = paramof(id)\n" +
                        "before(hookof(ids, 'java.io.UnixFileSystem', 'getBooleanAttributes0', 'int'), attributes)\n" +
                        "local list = paramof('/sys/class/sec/tsp', nil)\n" +
                        "before(hookof('Use.Tracking', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]'), list)\n" +
                        "local nio = 'sun.nio.fs.UnixNativeDispatcher'\n" +
                        "local exists = paramof(id)\n" +
                        "before(hookof(ids, nio, 'exists', 'boolean'), exists)\n" +
                        "local mode = paramof(id)\n" +
                        "before(hookof(ids, nio, 'stat', 'int'), mode)\n" +
                        "local stat = paramof(id)\n" +
                        "before(hookof(ids, nio, 'stat', 'void'), stat)\n" +
                        "local other = paramof('/sys/devices/system/chip-id/product_id')\n" +
                        "return restricted, old, open.early, open.argument.index, open.argument.value,\n" +
                        "  attributes.result, list.early, list.result == nil, exists.result == false, mode.result == 0,\n" +
                        "  stat.result.class, stat.result.args[1], before(hookof(ids), other), other.early");
        assertTrue(result.arg(1).toboolean());
        assertEquals("/sys/devices/system/chip-id/unique_id", result.arg(2).tojstring());
        // Opened by the real call with an empty path, which fails with the ENOENT of Android
        assertFalse(result.arg(3).toboolean());
        assertEquals(0, result.arg(4).toint());
        assertEquals("", result.arg(5).tojstring());
        assertEquals(0, result.arg(6).toint());
        assertTrue(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
        assertTrue(result.arg(9).toboolean());
        assertTrue(result.arg(10).toboolean());
        assertEquals("sun.nio.fs.UnixException", result.arg(11).tojstring());
        assertEquals(2, result.arg(12).toint());
        assertFalse(result.arg(13).toboolean());
        assertFalse(result.arg(14).toboolean());
    }

    // File.exists, canRead and lastModified see a file which does not exist too; the files are hidden by other
    // paths to /sys as well, and a path is resolved once per group
    @Test
    public void hardwareAccess() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local resolved = 0\n" +
                        "local original = resolve\n" +
                        "resolve = function(path) resolved = resolved + 1 return original(path) end\n" +
                        "local ids = 'Read.Identifiers'\n" +
                        "local id = '/sys/devices/system/chip-id/unique_id'\n" +
                        "local io = 'java.io.UnixFileSystem'\n" +
                        "local access = paramof(id)\n" +
                        "before(hookof(ids, io, 'checkAccess', 'boolean'), access)\n" +
                        "local modified = paramof(id)\n" +
                        "before(hookof(ids, io, 'getLastModifiedTime', 'long'), modified)\n" +
                        "local other = paramof('/sys/devices/system/cpu/possible')\n" +
                        "before(hookof(ids, io, 'checkAccess', 'boolean'), other)\n" +
                        "before(hookof(ids, io, 'checkAccess', 'boolean'), paramof('/sys/devices/system/cpu/possible'))\n" +
                        "local os = paramof(id)\n" +
                        "before(hookof(ids, 'android.system.Os', 'access', 'boolean'), os)\n" +
                        "return access.early, access.result, modified.early, modified.result, other.early, resolved,\n" +
                        "  hidden(hookof(ids), '/proc/self/root/sys/devices/system/chip-id/unique_id'),\n" +
                        "  hidden(hookof(ids), 'sys/devices/system/chip-id/unique_id'),\n" +
                        "  hidden(hookof(ids), '/data/../sys/class/sec/sdinfo/data'), os.early, os.argument.value");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertEquals(0, result.arg(4).toint());
        assertFalse(result.arg(5).toboolean());
        // The hidden file matches without resolving, the other file is resolved once, and so is each of its four
        // folders, which could be a hidden file the path goes on through
        assertEquals(5, result.arg(6).toint());
        assertTrue(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
        assertTrue(result.arg(9).toboolean());
        // Os.access of apps throws like for a file which does not exist, by the real call with an empty path
        assertFalse(result.arg(10).toboolean());
        assertEquals("", result.arg(11).tojstring());
    }

    // File.list of a folder with hidden files: without their names, with the link of a name resolved
    @Test
    public void hardwareList() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local hook = hookof('Read.Identifiers', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]')\n" +
                        "local chip = paramof('/sys/devices/system/chip-id', namesof('unique_id', 'product_id', 'lot_id'))\n" +
                        "local restricted, old, new = after(hook, chip)\n" +
                        "local device = paramof('/sys/block/mmcblk0/device', namesof('cid', 'type'))\n" +
                        "after(hook, device)\n" +
                        "local cpu = paramof('/sys/devices/system/cpu', namesof('cpu0', 'present'))\n" +
                        "return restricted, old, new, chip.result.class, chip.result.length, chip.result[1], chip.result[2],\n" +
                        "  device.result.length, device.result[1], after(hook, cpu), cpu.early,\n" +
                        "  after(hookof('Read.Identifiers'), chip)");
        assertTrue(result.arg(1).toboolean());
        assertEquals("3", result.arg(2).tojstring());
        assertEquals("1", result.arg(3).tojstring());
        assertEquals("java.lang.String", result.arg(4).tojstring());
        assertEquals(1, result.arg(5).toint());
        assertEquals("product_id", result.arg(6).tojstring());
        assertTrue(result.arg(7).isnil());
        assertEquals(1, result.arg(8).toint());
        assertEquals("type", result.arg(9).tojstring());
        assertFalse(result.arg(10).toboolean());
        assertFalse(result.arg(11).toboolean());
        // Other methods have nothing to do after the call
        assertFalse(result.arg(12).toboolean());
    }

    // File.getCanonicalPath of a hidden file: its name in its folder, like of a file which does not exist, not the
    // file a link leads to; the folder by its links, unless it is hidden too
    @Test
    public void hardwareCanonicalPath() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "LINKS['/system/bin/su'] = '/system/bin/magisk'\n" +
                        "LINKS['/sys/block/mmcblk0/device'] = '/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001'\n" +
                        "local function run(group, path)\n" +
                        "  local param = paramof(path)\n" +
                        "  local hook = hookof(group, 'java.io.UnixFileSystem', 'canonicalize0', 'java.lang.String')\n" +
                        "  return tostring(before(hook, param)) .. ' ' .. tostring(param.result)\n" +
                        "end\n" +
                        "return run('Read.Debugging', '/system/bin/su'), run('Read.Debugging', '/su'),\n" +
                        "  run('Read.Identifiers', '/sys/block/mmcblk0/device/cid'),\n" +
                        "  run('Use.Tracking', '/sys/class/sec/tsp/raw_check'),\n" +
                        "  run('Read.Identifiers', '/sys/devices/system/cpu/present'),\n" +
                        "  run('Read.Debugging', '/data/adb/..'), run('Read.Debugging', '/system/bin/sh')");
        assertEquals("true /system/bin/su", result.arg(1).tojstring());
        assertEquals("true /su", result.arg(2).tojstring());
        // The folder is not hidden, so its link is followed like for a file which is not there
        assertEquals("true /sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001/cid", result.arg(3).tojstring());
        // The folder is hidden too, so its link is not followed either
        assertEquals("true /sys/class/sec/tsp/raw_check", result.arg(4).tojstring());
        assertEquals("false nil", result.arg(5).tojstring());
        // Another folder, which Android resolves itself
        assertEquals("false nil", result.arg(6).tojstring());
        assertEquals("false nil", result.arg(7).tojstring());
    }

    // The path of a hidden folder followed by /. is the folder itself, which File.getCanonicalPath resolves the same way;
    // a path ending in /.. is the real answer
    @Test
    public void hardwareCanonicalPathOfFolderDot() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local function run(group, path)\n" +
                        "  local param = paramof(path)\n" +
                        "  local hook = hookof(group, 'java.io.UnixFileSystem', 'canonicalize0', 'java.lang.String')\n" +
                        "  return tostring(before(hook, param)) .. ' ' .. tostring(param.result)\n" +
                        "end\n" +
                        "return run('Use.Tracking', '/sys/class/sec/tsp/.'), run('Use.Tracking', '/sys/class/sec/tsp'),\n" +
                        "  run('Use.Tracking', '/sys/class/sec/tsp/..'), run('Read.Debugging', '/system/bin/.')");
        assertEquals(result.arg(2).tojstring(), result.arg(1).tojstring());
        assertEquals("false nil", result.arg(3).tojstring());
        assertEquals("false nil", result.arg(4).tojstring());
    }

    // Files.exists of Android 17 asks the provider, not the list of a folder
    @Test
    public void hardwareProviderExists() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local hook = hookof('Read.Identifiers', 'sun.nio.fs.UnixFileSystemProvider', 'exists', 'boolean')\n" +
                        "local hidden = paramof('/sys/devices/system/chip-id/unique_id')\n" +
                        "local restricted = before(hook, hidden)\n" +
                        "local other = paramof('/sys/devices/system/chip-id/product_id')\n" +
                        "return restricted, hidden.result == false, before(hook, other), other.early");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
    }

    // Files.newDirectoryStream: a filter in front of the one of the app leaves out the names of hidden files,
    // for a hidden folder nothing is done (opendir fails), and neither without a filter, for which Android throws
    // NullPointerException
    @Test
    public void hardwareListing() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local hook = hookof('Read.Identifiers', 'sun.nio.fs.UnixFileSystemProvider', 'newDirectoryStream',\n" +
                        "  'java.nio.file.DirectoryStream')\n" +
                        "local app = { accept = function(self, entry) return entry ~= '/sys/devices/system/chip-id/revision' end }\n" +
                        "local chip = paramof('/sys/devices/system/chip-id', nil, app)\n" +
                        "local restricted = before(hook, chip)\n" +
                        "local filter = chip.argument.value\n" +
                        "local none = paramof('/sys/devices/system/chip-id', nil, nil)\n" +
                        "before(hook, none)\n" +
                        "local tsp = paramof('/sys/class/sec/tsp', nil, app)\n" +
                        "return restricted, chip.early, chip.argument.index, filter.interface,\n" +
                        "  filter.accept('/sys/devices/system/chip-id/unique_id'),\n" +
                        "  filter.accept('/sys/devices/system/chip-id/product_id'),\n" +
                        "  filter.accept('/sys/devices/system/chip-id/revision'),\n" +
                        "  none.argument == nil,\n" +
                        "  before(hookof('Use.Tracking', 'sun.nio.fs.UnixFileSystemProvider', 'newDirectoryStream'), tsp),\n" +
                        "  tsp.argument == nil, tsp.early");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertEquals(1, result.arg(3).toint());
        assertEquals("java.nio.file.DirectoryStream$Filter", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
        assertFalse(result.arg(9).toboolean());
        assertTrue(result.arg(10).toboolean());
        assertFalse(result.arg(11).toboolean());
    }

    // The filter of the hooks (XCore.Filter, which compares the path as the app gives it) lets the script run for
    // paths with /./, // or (to su and Magisk) /../, and through the descriptor of a folder, which the script resolves,
    // and for the folders of su, of which File.list leaves su out
    @Test
    public void hardwareFilterOfOtherPaths() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        String[][] cases = {
                {"Read.Debugging", "/system/./bin/./su", "/./sbin/./su", "/system//xbin/su", "/system/xbin", "/sbin",
                        "/vendor/../data/adb", "/vendor/../system/app/Superuser.apk"},
                {"Use.Tracking", "/proc//scsi/scsi", "/proc/bus/./input/devices", "/sys/devices/system/chip-id",
                        "/proc/self/fd/57/vulnerabilities/meltdown"},
                {"Read.Identifiers", "/sys/./devices/system/chip-id/unique_id", "/.//sys/block/mmcblk0/device/cid",
                        "/proc/self/fd/57/cid", "/proc/thread-self/fd/57/cid", "/proc/123/task/124/fd/9/unique_id",
                        "/dev/fd/57/devices/system/chip-id/unique_id", "proc/self/fd/57/cid"},
                {"Read.Network", "/proc/./net/arp", "/proc/self/fd/7/tcp"}};
        for (String[] group : cases) {
            Varargs values = Scripts.run(globals, "return filter(hookof('" + group[0] + "'))").arg1().checktable().unpack();
            Object filter = xcoreFilter(values);
            for (int i = 1; i < group.length; i++)
                assertTrue(group[0] + " " + group[i], filterMatches(filter, group[i]));
            assertFalse(group[0], filterMatches(filter, "/data/user/0/test.app/files/x"));
        }
    }

    // A path through the descriptor of a folder, like /proc/self/fd/57/cid of the folder of the SD card, counts by the
    // file it leads to, each time: the app can close the descriptor and open another folder by the same number
    @Test
    public void hardwarePathsThroughDescriptors() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local ids = hookof('Read.Identifiers')\n" +
                        "LINKS['/proc/self/fd/57/cid'] = '/data/user/0/test.app/files/cid'\n" +
                        "LINKS['/dev/fd/58/devices/system/chip-id/unique_id'] = '/sys/devices/system/chip-id/unique_id'\n" +
                        "local first = hidden(ids, '/proc/self/fd/57/cid')\n" +
                        "LINKS['/proc/self/fd/57/cid'] = '/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001/cid'\n" +
                        "return first, hidden(ids, '/proc/self/fd/57/cid'),\n" +
                        "  hidden(ids, '/dev/fd/58/devices/system/chip-id/unique_id')");
        assertFalse(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
    }

    // The fonts of the maker through the descriptor of their folder are hidden like by their own path; the descriptor
    // of a file the app holds, like one another app shared with it, is the real file, which is open already, while a
    // path on through the descriptor of a folder still counts by the file it leads to
    @Test
    public void hardwareDescriptorsOfFontsAndOpenFiles() throws Exception {
        Globals globals = Scripts.load("hardware_files", APPS_STUBS + "\nfunction sdkversion() return 29 end");
        Varargs result = Scripts.run(globals,
                "LINKS['/proc/self/fd/57/SamsungOne.ttf'] = '/system/fonts/SamsungOne.ttf'\n" +
                        "LINKS['/proc/self/fd/57/Roboto-Regular.ttf'] = '/system/fonts/Roboto-Regular.ttf'\n" +
                        "for _, link in ipairs({ '/proc/self/fd/9', '/dev/fd/9', '/proc/self/task/123/fd/9', '/proc/self/fd/10/a' }) do\n" +
                        "  LINKS[link] = '/data/user/0/com.hidden/files/a'\n" +
                        "end\n" +
                        "local tracking, apps = hookof('Use.Tracking'), hookof('Get.Applications')\n" +
                        "local function h(hook, path) return hidden(hook, path, appsparam(path)) end\n" +
                        "local list = appsparam('/proc/self/fd/57', namesof('SamsungOne.ttf', 'Roboto-Regular.ttf'))\n" +
                        "after(hookof('Use.Tracking', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]'), list)\n" +
                        "return h(tracking, '/proc/self/fd/57/SamsungOne.ttf'), h(tracking, '/proc/self/fd/57/Roboto-Regular.ttf'),\n" +
                        "  list.result.length, list.result[1], h(apps, '/proc/self/fd/9'), h(apps, '/dev/fd/9'),\n" +
                        "  h(apps, '/proc/self/task/123/fd/9'), h(apps, '/proc/self/fd/10/a')");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertEquals(1, result.arg(3).toint());
        assertEquals("Roboto-Regular.ttf", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
    }

    // The cid of an SD card the user inserts while the app runs is hidden then, though its path was asked for before
    // the card was there, when it led nowhere; the answer for a file which exists is kept
    @Test
    public void hardwareFileWhichComesLater() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local present = { ['/sys/devices/system/cpu/present'] = true }\n" +
                        "local file = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, path)\n" +
                        "  if name == 'java.io.File' then return { exists = function() return present[path] == true end,\n" +
                        "    canRead = function() return present[path] == true end } end\n" +
                        "  return file(name, path)\n" +
                        "end\n" +
                        "local param = { unhooked = function(self, f) return f() end }\n" +
                        "local ids = hookof('Read.Identifiers')\n" +
                        "local counts = {}\n" +
                        "local original = matched\n" +
                        "matched = function(group, path, param)\n" +
                        "  counts[path] = (counts[path] or 0) + 1 return original(group, path, param)\n" +
                        "end\n" +
                        "local cid, cpu = '/sys/block/mmcblk1/device/cid', '/sys/devices/system/cpu/present'\n" +
                        "local before = hidden(ids, cid, param)\n" +
                        "LINKS[cid] = '/sys/devices/platform/11120000.dwmmc0/mmc_host/mmc1/mmc1:aaaa/cid'\n" +
                        "present[cid] = true\n" +
                        "return before, hidden(ids, cid, param), hidden(ids, cpu, param), hidden(ids, cpu, param),\n" +
                        "  counts[cid], counts[cpu]");
        assertFalse(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        // The cid was asked for again, since its first answer was not kept, the cpu file once
        assertEquals(2, result.arg(5).toint());
        assertEquals(1, result.arg(6).toint());
    }

    // A SecureDirectoryStream opens the files of its folder and reads their attributes by the descriptor of the folder
    // and their name, which count by the folder the descriptor leads to, or by themselves when absolute: a hidden id is
    // missing (ENOENT), and making it fails like in /sys (EACCES); the files of the app by the same calls, other files
    // and a descriptor which leads nowhere stay, and the hooks have no filter, which compares only paths
    @Test
    public void hardwareFilesOfASecureDirectoryStream() throws Exception {
        Globals globals = Scripts.load("hardware_files", READ_ONLY_STUBS);
        Varargs result = Scripts.run(globals,
                "local folders = { [57] = '/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001',\n" +
                        "  [58] = '/data/user/0/test.app/files/mmc0:0001' }\n" +
                        "local bind, make = luajava.bindClass, luajava.newInstance\n" +
                        "luajava.bindClass = function(name)\n" +
                        "  if name ~= 'android.system.Os' then return bind(name) end\n" +
                        "  return { statvfs = function(self, folder) return { f_flag = 1024 } end,\n" +
                        "    readlink = function(self, link)\n" +
                        "      return folders[tonumber(string.match(link, '^/proc/self/fd/(%d+)$'))]\n" +
                        "    end }\n" +
                        "end\n" +
                        // The name as bytes, a text here
                        "luajava.newInstance = function(name, ...)\n" +
                        "  if name == 'java.lang.String' then return ... end\n" +
                        "  return make(name, ...)\n" +
                        "end\n" +
                        "local function run(method, dfd, name, flags)\n" +
                        "  local param = makingparam(nil)\n" +
                        "  param.getArgument = function(self, index) return ({ [0] = dfd, name, flags or 0, 0 })[index] end\n" +
                        "  local hook = hookof('Read.Identifiers', 'sun.nio.fs.UnixNativeDispatcher', method)\n" +
                        "  local restricted, path = before(hook, param)\n" +
                        "  if not param.early then\n" +
                        "    return tostring(restricted)\n" +
                        "  end\n" +
                        "  return param.result.class .. ':' .. param.result.args[1] .. ':' .. path\n" +
                        "end\n" +
                        "return run('openat', 57, 'cid'), run('fstatat', 57, 'cid'), run('openat', 57, 'cid', 577),\n" +
                        "  run('openat', 57, 'type'), run('openat', 58, 'cid'), run('fstatat', 59, 'cid'),\n" +
                        "  run('openat', 59, '/sys/devices/system/chip-id/unique_id'),\n" +
                        "  filter(hookof('Read.Identifiers', 'sun.nio.fs.UnixNativeDispatcher', 'openat')) == nil,\n" +
                        "  filter(hookof('Read.Identifiers', 'sun.nio.fs.UnixNativeDispatcher', 'fstatat')) == nil");
        String cid = "/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001/cid";
        assertEquals("sun.nio.fs.UnixException:2:" + cid, result.arg(1).tojstring());
        assertEquals("sun.nio.fs.UnixException:2:" + cid, result.arg(2).tojstring());
        assertEquals("sun.nio.fs.UnixException:13:" + cid, result.arg(3).tojstring());
        assertEquals("false", result.arg(4).tojstring());
        assertEquals("false", result.arg(5).tojstring());
        assertEquals("false", result.arg(6).tojstring());
        assertEquals("sun.nio.fs.UnixException:2:/sys/devices/system/chip-id/unique_id", result.arg(7).tojstring());
        assertTrue(result.arg(8).toboolean());
        assertTrue(result.arg(9).toboolean());
    }

    // A SecureDirectoryStream removing a hidden id by its name gets ENOENT, like for a missing name in /sys; moving it
    // runs with a name of its folder which does not exist in place of the id, absolute, so Android answers in the order of
    // Linux, like EXDEV to the folder of the app; the files of the app stay, and the hooks have no filter
    @Test
    public void hardwareFilesRemovedAndMovedByASecureDirectoryStream() throws Exception {
        Globals globals = Scripts.load("hardware_files", READ_ONLY_STUBS);
        Varargs result = Scripts.run(globals,
                "local folders = { [57] = '/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001',\n" +
                        "  [58] = '/data/user/0/test.app/files/mmc0:0001' }\n" +
                        "local bind, make = luajava.bindClass, luajava.newInstance\n" +
                        "luajava.bindClass = function(name)\n" +
                        "  if name ~= 'android.system.Os' then return bind(name) end\n" +
                        "  return { statvfs = function(self, folder) return { f_flag = 1024 } end,\n" +
                        "    readlink = function(self, link)\n" +
                        "      return folders[tonumber(string.match(link, '^/proc/self/fd/(%d+)$'))]\n" +
                        "    end }\n" +
                        "end\n" +
                        "luajava.newInstance = function(name, ...)\n" +
                        "  if name == 'java.lang.String' then return ... end\n" +
                        // Stand-ins do not exist, ids can be read
                        "  if name == 'java.io.File' then\n" +
                        "    return { exists = function() return false end, canRead = function() return true end }\n" +
                        "  end\n" +
                        "  return make(name, ...)\n" +
                        "end\n" +
                        "local function run(method, ...)\n" +
                        "  local args = { [0] = ... }\n" +
                        "  for index, value in ipairs({ select(2, ...) }) do args[index] = value end\n" +
                        "  local param = makingparam(nil)\n" +
                        "  param.getArgument = function(self, index) return args[index] end\n" +
                        "  param.setArgument = function(self, index, value) args[index] = value end\n" +
                        "  local hook = hookof('Read.Identifiers', 'sun.nio.fs.UnixNativeDispatcher', method)\n" +
                        "  local restricted, path = before(hook, param)\n" +
                        "  if param.early then\n" +
                        "    return param.result.class .. ':' .. param.result.args[1] .. ':' .. path\n" +
                        "  end\n" +
                        "  return tostring(restricted) .. ' ' .. tostring(args[1]) .. ' ' .. tostring(args[3])\n" +
                        "end\n" +
                        "local function nofilter(method)\n" +
                        "  return filter(hookof('Read.Identifiers', 'sun.nio.fs.UnixNativeDispatcher', method)) == nil\n" +
                        "end\n" +
                        "return run('unlinkat', 57, 'cid', 0), run('unlinkat', 57, 'type', 0), run('unlinkat', 58, 'cid', 0),\n" +
                        "  run('renameat', 57, 'cid', 58, 'cid'), run('renameat', 58, 'cid', 57, 'cid'),\n" +
                        "  run('renameat', 58, 'cid', 58, 'b'), nofilter('unlinkat'), nofilter('renameat')");
        String cid = "/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001/cid";
        assertEquals("sun.nio.fs.UnixException:2:" + cid, result.arg(1).tojstring());
        assertEquals("false type nil", result.arg(2).tojstring());
        assertEquals("false cid nil", result.arg(3).tojstring());
        assertEquals("true " + cid + "~ cid", result.arg(4).tojstring());
        assertEquals("true cid " + cid + "~", result.arg(5).tojstring());
        assertEquals("false cid b", result.arg(6).tojstring());
        assertTrue(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
    }

    // The script compares the paths of openat and fstatat with the filter of the group itself, like XCore.Filter
    @Test
    public void hardwareFilterOfPathsOfDescriptors() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        String[] paths = {"/sys/devices/system/chip-id/unique_id", "/data/user/0/test.app/files/x", "/system/xbin/su",
                "/system/xbin", "/proc/self/root/sbin/su", "/vendor/../data/adb", "/system/etc/permissions/a.xml",
                "/storage/emulated/0/Android/media/com.example", "/etc", "/", "/proc/net/tcp", "/proc/self/fd/57/cid",
                "/system//xbin/su", "/data/misc/user/0/cacerts-added/a.0", "/system/fonts/Roboto.ttf", "system/xbin",
                "sys/devices/system/chip-id/unique_id", "../data/adb", "/proc/self/fd/9"};
        for (String group : new String[]{"Read.Identifiers", "Use.Tracking", "Read.Biometrics", "Read.Network", "Read.VPN",
                "Read.Debugging", "Read.Features", "Get.Applications"}) {
            Object filter = xcoreFilter(Scripts.run(globals, "return paths('" + group + "')").arg1().checktable().unpack());
            for (String path : paths)
                assertEquals(group + " " + path, filterMatches(filter, path),
                        Scripts.run(globals, "return filtered('" + group + "', '" + path + "')").arg1().toboolean());
        }
    }

    static Object xcoreFilter(Varargs values) throws Exception {
        Class<?> type = Class.forName("eu.faircode.xlua.XCore$Filter");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object filter = constructor.newInstance();
        java.lang.reflect.Method add = type.getDeclaredMethod("add", String.class);
        add.setAccessible(true);
        for (int i = 1; i <= values.narg(); i++)
            add.invoke(filter, values.arg(i).tojstring());
        return filter;
    }

    // Paths with /.. at the end, which only the file system resolves, run the scripts, which resolve them; the link to the
    // root itself is the root
    @Test
    public void filterOfParentFolders() throws Exception {
        Object filter = xcoreFilter(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf("/storage/emulated"), LuaValue.valueOf("/")}));
        assertTrue(filterMatches(filter, "/storage/emulated/0/Download/.."));
        assertTrue(filterMatches(filter, ".."));
        assertTrue(filterMatches(filter, "/proc/self/root"));
        assertFalse(filterMatches(filter, "/data/user/0/com.app/files"));
    }

    static boolean filterMatches(Object filter, String path) throws Exception {
        java.lang.reflect.Method matches = filter.getClass().getDeclaredMethod("matches", String.class);
        matches.setAccessible(true);
        return (Boolean) matches.invoke(filter, path);
    }

    // File.length and the space of a hidden file are 0, like of a file which does not exist
    @Test
    public void hardwareSizes() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local length = paramof('/sys/devices/system/chip-id/unique_id', 4096)\n" +
                        "before(hookof('Read.Identifiers', 'java.io.UnixFileSystem', 'getLength', 'long'), length)\n" +
                        "local space = paramof('/system/xbin/su', 4096, 0)\n" +
                        "before(hookof('Read.Debugging', 'java.io.UnixFileSystem', 'getSpace', 'long'), space)\n" +
                        "local list = paramof('/system/xbin', namesof('su', 'sh'))\n" +
                        "after(hookof('Read.Debugging', 'java.io.UnixFileSystem', 'list0', 'java.lang.String[]'), list)\n" +
                        "return length.early, length.result, space.early, space.result, list.result.length, list.result[1]");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).toint());
        assertTrue(result.arg(3).toboolean());
        assertEquals(0, result.arg(4).toint());
        assertEquals(1, result.arg(5).toint());
        assertEquals("sh", result.arg(6).tojstring());
    }

    // The filter of a listing may run on another thread: it does not change the cache of the thread of the hook
    @Test
    public void hardwareListingWithoutCache() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local hook = hookof('Read.Identifiers', 'sun.nio.fs.UnixFileSystemProvider', 'newDirectoryStream')\n" +
                        "local chip = paramof('/sys/devices/system/chip-id', nil, { accept = function(self, entry) return true end })\n" +
                        "before(hook, chip)\n" +
                        "local cached = 0\n" +
                        "local original = matched\n" +
                        "matched = function(group, path) cached = cached + 1 return original(group, path) end\n" +
                        "local filter = chip.argument.value\n" +
                        "local first = filter.accept('/sys/devices/system/chip-id/unique_id')\n" +
                        "filter.accept('/sys/devices/system/chip-id/unique_id')\n" +
                        "return first, cached");
        assertFalse(result.arg(1).toboolean());
        // Asked again each time, nothing is kept
        assertEquals(2, result.arg(2).toint());
    }

    // A font file of every Android is resolved once, for the fonts of makers and for the patterns, also in a listing
    @Test
    public void fontResolvedOnce() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local resolved = 0\n" +
                        "local original = resolve\n" +
                        "resolve = function(path) resolved = resolved + 1 return original(path) end\n" +
                        "local hook = hookof('Use.Tracking', 'sun.nio.fs.UnixFileSystemProvider', 'newDirectoryStream')\n" +
                        "local fonts = paramof('/system/fonts', nil, { accept = function(self, entry) return true end })\n" +
                        "before(hook, fonts)\n" +
                        "local start = resolved\n" +
                        "local roboto = fonts.argument.value.accept('/system/fonts/Roboto-Regular.ttf')\n" +
                        "return roboto, resolved - start, " +
                        "matched('Use.Tracking', '/system/fonts/SamsungOne-400.ttf')");
        assertTrue(result.arg(1).toboolean());
        assertEquals(1, result.arg(2).toint());
        assertTrue(result.arg(3).toboolean());
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
