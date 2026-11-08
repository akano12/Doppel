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
            // The app with its own APK and the packages it can see, see apptime
            "OWN = '/data/app/~~own==/com.example-a==/base.apk'",
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
                "local present = { ['/system_ext/etc/build.prop'] = true }\n" +
                        "local file = luajava.newInstance\n" +
                        "luajava.newInstance = function(name, path)\n" +
                        "  if name == 'java.io.File' then return { exists = function() return present[path] end,\n" +
                        "    canRead = function() return false end } end\n" +
                        "  return file(name, path)\n" +
                        "end\n" +
                        "local function param() return { unhooked = function(self, f) return f() end } end\n" +
                        "present['/proc/net/arp'] = true\n" +
                        "return hidden(hookof('Read.Network'), '/proc/net/arp', param()),\n" +
                        "  hidden(hookof('Read.VPN'), '/sys/class/net/tun0', param()),\n" +
                        "  hidden(hookof('Read.Debugging'), '/system/xbin/su', param()),\n" +
                        "  hidden(hookof('Use.Tracking'), '/system_ext/etc/build.prop', param())");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
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
                        "  unlisted('Get.Applications', '/data/app')");
        for (int i : new int[]{1, 2, 4, 5, 6, 7, 8, 10, 13, 14, 15, 17})
            assertTrue("result " + i, result.arg(i).toboolean());
        for (int i : new int[]{3, 9, 11, 12, 16})
            assertFalse("result " + i, result.arg(i).toboolean());
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
        // With paths with /./ and //, see hardwareFilterOfOtherPaths
        assertEquals(3, result.arg(2).toint());
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

    // Resolving, reading the link of and making a hidden file fail like for a file which does not exist;
    // making one fails like in /sys, or like in a folder which does not exist when the group hides the folder
    @Test
    public void hardwareLinksAndMaking() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local id = '/sys/devices/system/chip-id/unique_id'\n" +
                        "local function run(group, class, method, path)\n" +
                        "  local param = paramof(path)\n" +
                        "  before(hookof(group, class, method), param)\n" +
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
        assertEquals("android.system.ErrnoException:readlink", result.arg(3).tojstring());
        assertEquals("java.io.IOException:Permission denied", result.arg(4).tojstring());
        assertEquals("java.io.IOException:No such file or directory", result.arg(5).tojstring());
        // On a read-only partition the kernel answers EROFS before the permissions
        assertEquals("java.io.IOException:Read-only file system", result.arg(6).tojstring());
        assertEquals("java.io.IOException:Permission denied", result.arg(7).tojstring());
    }

    // The hooks see a file which does not exist: an error of the kernel, no attributes or no names
    @Test
    public void hardwareMissing() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        Varargs result = Scripts.run(globals,
                "local ids = 'Read.Identifiers'\n" +
                        "local id = '/sys/devices/system/chip-id/unique_id'\n" +
                        "local open = paramof(id)\n" +
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
                        "return restricted, old, open.result.class, open.result.args[1], open.result.args[2],\n" +
                        "  attributes.result, list.early, list.result == nil, exists.result == false, mode.result == 0,\n" +
                        "  stat.result.class, stat.result.args[1], before(hookof(ids), other), other.early");
        assertTrue(result.arg(1).toboolean());
        assertEquals("/sys/devices/system/chip-id/unique_id", result.arg(2).tojstring());
        assertEquals("android.system.ErrnoException", result.arg(3).tojstring());
        assertEquals("open", result.arg(4).tojstring());
        assertEquals(2, result.arg(5).toint());
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
                        "  hidden(hookof(ids), '/data/../sys/class/sec/sdinfo/data'), os.result.class, os.result.args[2]");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertEquals(0, result.arg(4).toint());
        assertFalse(result.arg(5).toboolean());
        // The hidden file matches without resolving, the other file is resolved once
        assertEquals(1, result.arg(6).toint());
        assertTrue(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
        assertTrue(result.arg(9).toboolean());
        // Os.access of apps throws like for a file which does not exist
        assertEquals("android.system.ErrnoException", result.arg(10).tojstring());
        assertEquals(2, result.arg(11).toint());
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
    // for a hidden folder nothing is done (opendir fails)
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
                        "  none.argument.value.accept('/sys/devices/system/chip-id/revision'),\n" +
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
    // paths with /./, // or (to su and Magisk) /../, which the script resolves, and for the folders of su, of which
    // File.list leaves su out
    @Test
    public void hardwareFilterOfOtherPaths() throws Exception {
        Globals globals = Scripts.load("hardware_files", HARDWARE_STUBS);
        String[][] cases = {
                {"Read.Debugging", "/system/./bin/./su", "/./sbin/./su", "/system//xbin/su", "/system/xbin", "/sbin",
                        "/vendor/../data/adb", "/vendor/../system/app/Superuser.apk"},
                {"Use.Tracking", "/proc//scsi/scsi", "/proc/bus/./input/devices", "/sys/devices/system/chip-id"},
                {"Read.Identifiers", "/sys/./devices/system/chip-id/unique_id", "/.//sys/block/mmcblk0/device/cid"},
                {"Read.Network", "/proc/./net/arp"}};
        for (String[] group : cases) {
            Varargs values = Scripts.run(globals, "return filter(hookof('" + group[0] + "'))").arg1().checktable().unpack();
            Object filter = xcoreFilter(values);
            for (int i = 1; i < group.length; i++)
                assertTrue(group[0] + " " + group[i], filterMatches(filter, group[i]));
            assertFalse(group[0], filterMatches(filter, "/data/user/0/test.app/files/x"));
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
                        "local chip = paramof('/sys/devices/system/chip-id', nil, nil)\n" +
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
                        "local fonts = paramof('/system/fonts', nil, nil)\n" +
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
