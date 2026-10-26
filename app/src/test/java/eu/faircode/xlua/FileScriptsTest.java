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

    // The hooks and params of the hardware files script, with the links of /sys as resolve sees them;
    // objects made are tables of their arguments
    private static final String HARDWARE_STUBS = String.join("\n",
            "luajava = {",
            "  newInstance = function(name, ...) return { class = name, args = { ... } } end,",
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
                        "  hidden(hookof('Read.Network'), '/sys/devices/system/chip-id/unique_id')");
        assertEquals("*sys/*", result.arg(1).tojstring());
        assertEquals(1, result.arg(2).toint());
        boolean[] expected = {true, true, true, true, false, false, false, false,
                true, true, true, true, true, false, false, false,
                true, true, true, false, false};
        for (int i = 0; i < expected.length; i++)
            assertEquals(String.valueOf(i + 3), expected[i], result.arg(i + 3).toboolean());
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
                        "  attributes.result, list.early, list.result == nil, exists.result, mode.result,\n" +
                        "  stat.result.class, stat.result.args[1], before(hookof(ids), other), other.early");
        assertTrue(result.arg(1).toboolean());
        assertEquals("/sys/devices/system/chip-id/unique_id", result.arg(2).tojstring());
        assertEquals("android.system.ErrnoException", result.arg(3).tojstring());
        assertEquals("open", result.arg(4).tojstring());
        assertEquals(2, result.arg(5).toint());
        assertEquals(0, result.arg(6).toint());
        assertTrue(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
        assertFalse(result.arg(9).toboolean());
        assertEquals(0, result.arg(10).toint());
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

    // The shell hides the same files as the hooks, with patterns of its own (see shell_commands HIDDEN)
    @Test
    public void hardwareShellAgrees() throws Exception {
        Globals files = Scripts.load("hardware_files", HARDWARE_STUBS);
        Globals shell = Scripts.load("shell_commands", String.join("\n",
                "luajava = { newInstance = function(name, path)",
                "  return { getCanonicalPath = function(self) return path end } end }"));
        String[] paths = {
                "/sys/devices/system/chip-id", "/sys/devices/system/chip-id/unique_id",
                "/sys/devices/system/chip-id/lot_id", "/sys/devices/system/chip-id/product_id",
                "/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001/cid",
                "/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001/serial",
                "/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001/date",
                "/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001/manfid",
                "/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001/oemid",
                "/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001/name",
                "/sys/devices/platform/13500000.dwmmc2/mmc_host/mmc0/mmc0:0001/type",
                "/sys/devices/virtual/sec/sdinfo/data", "/sys/devices/virtual/sec/tsp",
                "/sys/devices/virtual/sec/tsp/raw_check", "/sys/devices/virtual/sec/tsp/fod_pos",
                "/sys/devices/virtual/sec/tsp/fod_info", "/sys/devices/virtual/sensors/light_sensor/light_cal",
                "/sys/devices/virtual/sensors/proximity_sensor/prox_cal",
                "/sys/devices/virtual/fingerprint/fingerprint", "/sys/devices/virtual/fingerprint/fingerprint/name",
                "/sys/devices/system/cpu/possible", "/sys/kernel/gpu/gpu_model"};
        String[][] groups = {{"Read.Identifiers", "identifiers"}, {"Use.Tracking", "tracking"},
                {"Read.Biometrics", "biometrics"}};
        for (String[] group : groups)
            for (String path : paths) {
                boolean hook = Scripts.run(files, "return hidden(hookof('" + group[0] + "'), '" + path + "')").arg1().toboolean();
                boolean command = Scripts.run(shell, "return hiddenfile('" + path + "', { " + group[1] + " = true })").arg1().toboolean();
                assertEquals(group[0] + " " + path, hook, command);
            }
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
