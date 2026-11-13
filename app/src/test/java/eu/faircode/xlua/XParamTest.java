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

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;

import org.junit.Test;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.CoerceLuaToJava;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// Lua values set by scripts are converted to the types of the hooked methods,
// and scripts hide the packages of other apps
public class XParamTest {
    private static final int UID = 10123;

    @SuppressWarnings("unused")
    private static String field;

    @SuppressWarnings("unused")
    public static int count(int value, String text) {
        return value;
    }

    @SuppressWarnings("unused")
    public static void run() {
    }

    // Like NetworkStats.Bucket: private fields, a long among them, and constants
    private static class Bucket {
        @SuppressWarnings("unused")
        public static final int STATE_ALL = -1;
        private int uid;
        private long end;
    }

    // Like a bucket of a subclass an app passes
    private static class AppBucket extends Bucket {
    }

    // The fields of a class are copied exactly, a long past 2^53 too, which a Lua number is not, also into a subclass;
    // a class of which reflection reaches no fields fails, like the hidden API rules leave them out
    @Test
    public void copyFields() throws Exception {
        Bucket from = new Bucket();
        from.uid = 10200;
        from.end = (1L << 53) + 1;
        Bucket to = new AppBucket();
        XParam.copyFields(Bucket.class, from, to);
        assertEquals(10200, to.uid);
        assertEquals(9007199254740993L, to.end);
        try {
            XParam.copyFields(AppBucket.class, from, to);
            fail("Copied no fields");
        } catch (NoSuchFieldException ignored) {
        }
    }

    // Like a StructStatVfs, of which the fields are final, which ART lets reflection set once accessible; not final
    // here, since newer JVMs warn about it and will refuse it
    private static class StatVfs {
        public long f_fsid;

        StatVfs(long fsid) {
            f_fsid = fsid;
        }
    }

    private static long fake(long real, String seed, int bits) throws Throwable {
        StatVfs vfs = new StatVfs(real);
        long fake = XParam.fakeLong(vfs, StatVfs.class.getField("f_fsid"), seed, bits);
        assertEquals(fake, vfs.f_fsid);
        return fake;
    }

    // A fake long is exact past 2^53, which a Lua number is not: ids which differ in the low bits only, or of which
    // the sign is set, like the id of a file system of a UUID, give their own fakes, the same for the same id and seed,
    // and fit the width of a 32-bit process. In 64 bits they have the forms of bionic, which sign extends the low int:
    // bit 31 clear, or bit 31 set and the high half all ones; both come out. The real ids are of /data, /system and
    // /apex/com.android.art of an emulator of Android 14 (stat -f gives d3609fe8 04970d6b and so on).
    @Test
    public void fakeLong() throws Throwable {
        long one = fake((1L << 60) + 1, "id", 64);
        long two = fake((1L << 60) + 2, "id", 64);
        long negative = fake(0xffffffffd3609fe8L, "id", 64);
        assertNotEquals(one, two);
        assertNotEquals(one, negative);
        boolean set = false;
        boolean clear = false;
        for (long real : new long[]{0xffffffffd3609fe8L, 0xffffffff8cf62429L, 0x8b5c283902ba2bd9L, 1L << 40, 1})
            for (int i = 0; i < 16; i++) {
                long fake = fake(real, "id" + i, 64);
                assertTrue(Long.toHexString(fake), (fake & 0x80000000L) == 0 || (fake >>> 32) == 0xffffffffL);
                set |= (fake & 0x80000000L) != 0;
                clear |= (fake & 0x80000000L) == 0 && (fake >>> 32) != 0;
            }
        assertTrue(set && clear);
        assertEquals(one, fake((1L << 60) + 1, "id", 64));
        assertNotEquals(one, fake((1L << 60) + 1, "other", 64));
        assertNotEquals((1L << 60) + 1, one);
        long narrow = fake(0x04970d6bL, "id", 32);
        assertTrue(narrow > 0 && narrow < (1L << 32));
        assertNotEquals(narrow, fake(0x04970d6cL, "id", 32));
    }

    // Like a Lua number returned to Java code expecting an Object
    private static Object lua(double value) {
        return CoerceLuaToJava.coerce(LuaValue.valueOf(value), Object.class);
    }

    // Other paths of a file, like apps open them to get around a filter of the plain path (see XParam.opens);
    // the links resolved by the file system are left to the device
    @Test
    public void pathsOfAFile() {
        String file = "/proc/meminfo";
        for (String path : new String[]{"/proc/meminfo", "//proc/meminfo", "/proc//meminfo", "/proc/./meminfo",
                "proc/meminfo", "/proc/self/root/proc/meminfo", "/proc/1234/root/proc/meminfo",
                "/proc/thread-self/root//proc/meminfo", "/proc/self/task/1234/root/proc/meminfo",
                "/proc/self/cwd/proc/meminfo", "/proc/self/task/1234/cwd/proc/meminfo"})
            assertTrue(path, XParam.isPath(path, file));
        for (String path : new String[]{"/proc/meminfo2", "/data/proc/meminfo", "/proc/self/meminfo", "/proc/vmstat"})
            assertFalse(path, XParam.isPath(path, file));
    }

    // The plain path for the filters of XCore, the path itself when plain already
    @Test
    public void plainPaths() {
        assertEquals("/sys/devices/system/cpu/possible", XParam.plainPath("/sys/devices/system/./cpu/possible"));
        assertEquals("/sys/devices/system/cpu", XParam.plainPath("/sys/devices/system/cpu/./."));
        assertEquals("/proc/sys/kernel/osrelease", XParam.plainPath("/proc/self/root/proc/sys/kernel//osrelease"));
        assertEquals("/", XParam.plainPath("/."));
        assertEquals("/data/.hidden", XParam.plainPath("/data/.hidden"));
        // Links of a thread and links after each other, which apps can use to get around a filter of the plain path
        assertEquals("/data/adb", XParam.plainPath("/proc/self/task/1234/root/data/adb"));
        assertEquals("/data/adb", XParam.plainPath("/proc/self/root/proc/thread-self/root/data/adb"));
        assertEquals("/proc/self/task/1234/stat", XParam.plainPath("/proc/self/task/1234/stat"));
        // The working directory of an app is the root; other folders of that name stay
        assertEquals("/dev/su", XParam.plainPath("/proc/self/cwd/dev/su"));
        assertEquals("/data/adb", XParam.plainPath("/proc/self/root/proc/self/cwd/data/adb"));
        assertEquals("/data/cwd/file", XParam.plainPath("/data/cwd/file"));
        // Only the file system can resolve /../, see the filters of XCore
        assertEquals("/vendor/etc/init/../media_codecs.xml", XParam.plainPath("/vendor/etc/init/../media_codecs.xml"));
        // The link to the root itself is the root
        assertEquals("/", XParam.plainPath("/proc/self/root"));
        assertEquals("/", XParam.plainPath("/proc/123/task/124/cwd"));
        assertEquals("/data/root", XParam.plainPath("/data/root"));
        String plain = "/proc/meminfo";
        assertTrue(plain == XParam.plainPath(plain));
    }

    // Before Android 11 a text larger than a pipe holds needs a file of the app, else it fails, which the hook reports
    @Test(expected = IllegalArgumentException.class)
    public void largeTextWithoutFolder() throws Throwable {
        XParam.oldFile(null, new byte[4097], false);
    }

    // So does a small text of a regular file as long as the file, like tablet_core_hardware.xml (3897 bytes on Android
    // 10) without the sensor features, which a pipe would turn into a FIFO of no length for fstat
    @Test(expected = IllegalArgumentException.class)
    public void smallTextOfAFileAsLongWithoutFolder() throws Throwable {
        XParam.oldFile(null, new byte[3897], true);
    }

    // A small text, like of boot_id, goes to a removed file of the app too when there is a folder, not to a pipe; the
    // stubs of Android open no file (null) and have no pipe (it would fail), so the text is empty, which writes nothing
    @Test
    public void smallTextInAFile() throws Throwable {
        File folder = Files.createTempDirectory("cache").toFile();
        try {
            assertNull(XParam.oldFile(folder, new byte[0], false));
            assertEquals(0, folder.list().length);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            folder.delete();
        }
    }

    // A folder in which no file can be made fails a text a pipe cannot take, like no folder
    @Test
    public void textsWithoutAUsableFolder() throws Throwable {
        File folder = File.createTempFile("cache", null);
        try {
            for (boolean sameSize : new boolean[]{false, true})
                try {
                    XParam.oldFile(folder, new byte[sameSize ? 3897 : 4097], sameSize);
                    fail("Made a file in " + folder);
                } catch (IOException expected) {
                }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            folder.delete();
        }
    }

    // A file of /proc tells the size 0 and one of /sys a page, whatever their text, so a fake tells the same; other files
    // tell their length, which the fake has then
    @Test
    public void sizeOfFakedFiles() {
        assertEquals(0, XParam.sizeOf(0, 37, 37));
        assertEquals(4096, XParam.sizeOf(4096, 4, 4));
        assertEquals(25000, XParam.sizeOf(30000, 30000, 25000));
    }

    // Like FIONREAD and FileInputStream.available of Android: the size less the position, none past the end
    @Test
    public void availableOfFakedFiles() {
        assertEquals(0, XParam.available(0, 0));
        assertEquals(0, XParam.available(0, 37));
        assertEquals(4096, XParam.available(4096, 0));
        assertEquals(4092, XParam.available(4096, 4));
        assertEquals(0, XParam.available(4096, 5000));
        assertEquals(25000, XParam.available(25000, 0));
    }

    // Fakes are told apart by device, inode and change time, so a file which gets the inode of a removed fake is no fake;
    // the fakes asked about last stay
    @Test
    public void fakedFilesKept() {
        String first = XParam.fakedKey(5, 1, 1700000000, 1);
        assertNotEquals(first, XParam.fakedKey(5, 1, 1700000000, 2));
        assertNotEquals(first, XParam.fakedKey(6, 1, 1700000000, 1));
        try {
            XParam.fakedFiles.put(first, new XParam.Faked(null, 0, "/proc/cpuinfo", "/memfd:file (deleted)"));
            String second = XParam.fakedKey(5, 2, 1700000000, 1);
            XParam.fakedFiles.put(second, new XParam.Faked(null, 0, "/proc/version", "/memfd:file (deleted)"));
            assertEquals("/proc/cpuinfo", XParam.fakedFiles.get(first).path);
            for (int i = 3; i <= XParam.FAKED_MAX + 1; i++)
                XParam.fakedFiles.put(XParam.fakedKey(5, i, 1700000000, 1), new XParam.Faked(null, 0, "/proc/meminfo", ""));
            assertEquals(XParam.FAKED_MAX, XParam.fakedFiles.size());
            assertEquals("/proc/cpuinfo", XParam.fakedFiles.get(first).path);
            assertNull(XParam.fakedFiles.get(second));
        } finally {
            XParam.fakedFiles.clear();
        }
    }

    // A fake of the fake of an earlier hook of the same open, like of two properties of one property area, answers like
    // the file the app opened, with the size that file tells; the earlier fake, which is closed, is gone
    @Test
    public void fakesOfFakes() {
        String area = "/dev/__properties__/u:object_r:exported_system_prop:s0";
        String memfd = "/memfd:file (deleted)";
        StructStat stat = new StructStat(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        try {
            XParam.addFaked("1:2:3.4", new XParam.Faked(stat, 131072, area, null), "5:6:7.8", memfd, 131072, 131072);
            XParam.addFaked("5:6:7.8", new XParam.Faked(null, 131072, memfd, null), "5:9:7.9", memfd, 131072, 131072);
            XParam.Faked faked = XParam.fakedFiles.get("5:9:7.9");
            assertSame(stat, faked.stat);
            assertEquals(area, faked.path);
            assertEquals(131072, faked.size);
            assertEquals(memfd, faked.link);
            assertNull(XParam.fakedFiles.get("5:6:7.8"));
            // A file of /proc tells 0, whatever the fakes; another file the length of the last fake
            XParam.addFaked("1:3:3.4", new XParam.Faked(null, 0, "/proc/cpuinfo", null), "5:10:8.1", memfd, 900, 950);
            XParam.addFaked("5:10:8.1", new XParam.Faked(null, 950, memfd, null), "5:11:8.2", memfd, 950, 990);
            assertEquals("/proc/cpuinfo", XParam.fakedFiles.get("5:11:8.2").path);
            assertEquals(0, XParam.fakedFiles.get("5:11:8.2").size);
            XParam.addFaked("1:4:3.4", new XParam.Faked(null, 30000, "/system/etc/fonts.xml", null), "5:12:8.3", memfd,
                    30000, 25000);
            XParam.addFaked("5:12:8.3", new XParam.Faked(null, 25000, memfd, null), "5:13:8.4", memfd, 25000, 20000);
            assertEquals(20000, XParam.fakedFiles.get("5:13:8.4").size);
        } finally {
            XParam.fakedFiles.clear();
        }
    }

    // Methods with the names of the hooked ones of Android which the tests cannot reach, see unfakedCalls
    @SuppressWarnings("unused")
    private static class Hooked {
        long size(FileDescriptor fd) {
            return 0;
        }

        long getLength(File file) {
            return 0;
        }

        long getLastModifiedTime(File file) {
            return 0;
        }

        void open(String path) {
        }
    }

    // The calls about a descriptor of a fake, or about its path, answer like about the file the fake replaced, a file of
    // /sys here; not those about other files, paths which lead to no file, links of the app to the link of the fake
    // and other methods
    @Test
    public void unfakedCalls() throws Exception {
        File file = File.createTempFile("faked", null);
        try (FileInputStream in = new FileInputStream(file); RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            FileDescriptor other = new FileDescriptor();
            Map<Object, String> keys = new HashMap<>();
            keys.put(in.getFD(), "fake");
            keys.put(raf.getFD(), "fake");
            keys.put("/proc/self/fd/57", "fake");
            keys.put(other, "other");
            XParam.Keys keyOf = (f) -> {
                if (!keys.containsKey(f))
                    throw new ErrnoException("stat", 2);
                return keys.get(f);
            };
            String link = "/memfd:file (deleted)";
            // The stubs of Android give a stat of zeros, so a modification time of 0
            StructStat stat = new StructStat(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            XParam.fakedFiles.put("fake", new XParam.Faked(stat, 4096, "/sys/devices/system/cpu/possible", link));
            Method available = FileInputStream.class.getMethod("available");
            Method length = RandomAccessFile.class.getMethod("length");
            Method size = Hooked.class.getDeclaredMethod("size", FileDescriptor.class);
            Method getLength = Hooked.class.getDeclaredMethod("getLength", File.class);
            Method getLastModifiedTime = Hooked.class.getDeclaredMethod("getLastModifiedTime", File.class);
            Method readlink = Os.class.getMethod("readlink", String.class);
            Method open = Hooked.class.getDeclaredMethod("open", String.class);
            assertEquals(4096, XParam.unfaked(new XCall(available, in, new Object[0]), 4, keyOf));
            assertEquals(4096L, XParam.unfaked(new XCall(length, raf, new Object[0]), 4L, keyOf));
            assertEquals(4096L, XParam.unfaked(new XCall(size, null, new Object[]{in.getFD()}), 4L, keyOf));
            assertNull(XParam.unfaked(new XCall(size, null, new Object[]{other}), 4L, keyOf));
            assertEquals(4096L, XParam.unfaked(new XCall(getLength, null, new Object[]{new File("/proc/self/fd/57")}), 4L, keyOf));
            assertNull(XParam.unfaked(new XCall(getLength, null, new Object[]{new File("/proc/self/fd/58")}), 0L, keyOf));
            File fd57 = new File("/proc/self/fd/57");
            assertEquals(0L, XParam.unfaked(new XCall(getLastModifiedTime, null, new Object[]{fd57}), 1700000000000L, keyOf));
            assertNull(XParam.unfaked(new XCall(getLastModifiedTime, null, new Object[]{new File("/proc/self/fd/58")}),
                    1700000000000L, keyOf));
            Object[] path = new Object[]{"/proc/self/fd/57"};
            assertEquals("/sys/devices/system/cpu/possible", XParam.unfaked(new XCall(readlink, null, path), link, keyOf));
            assertEquals("/sys/devices/system/cpu/possible",
                    XParam.unfaked(new XCall(readlink, null, path), link.getBytes(StandardCharsets.UTF_8), keyOf));
            assertNull(XParam.unfaked(new XCall(readlink, null, path), "/proc/self/fd/57", keyOf));
            // The hook of a second group, like Read.Debugging next to Use.Tracking, gets the answer of the first and
            // leaves it as it is
            assertNull(XParam.unfaked(new XCall(readlink, null, path), "/sys/devices/system/cpu/possible", keyOf));
            assertEquals(4096L, XParam.unfaked(new XCall(length, raf, new Object[0]), 4096L, keyOf));
            assertEquals(0L, XParam.unfaked(new XCall(getLastModifiedTime, null, new Object[]{fd57}), 0L, keyOf));
            assertNull(XParam.unfaked(new XCall(open, null, path), 57, keyOf));
        } finally {
            XParam.fakedFiles.clear();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void wholeNumbers() {
        assertEquals(5000000000L, XParam.coerceValue(long.class, lua(5000000000d)));
        assertEquals(7L, XParam.coerceValue(long.class, lua(7)));
        assertEquals(7, XParam.coerceValue(int.class, lua(7)));
        assertEquals((short) 7, XParam.coerceValue(short.class, lua(7)));
        assertEquals((byte) 7, XParam.coerceValue(byte.class, lua(7)));
    }

    @Test
    public void floatingNumbers() {
        assertEquals(1.5d, XParam.coerceValue(double.class, lua(1.5)));
        assertEquals(1.5f, XParam.coerceValue(float.class, lua(1.5)));
        assertEquals(7d, XParam.coerceValue(double.class, lua(7)));
    }

    // Values which do not fit are left as they are, so XParam reports the wrong type
    @Test
    public void noLossyConversion() {
        assertEquals(Double.class, XParam.coerceValue(int.class, lua(1.5)).getClass());
        assertEquals(Double.class, XParam.coerceValue(int.class, lua(5000000000d)).getClass());
        assertEquals(Integer.class, XParam.coerceValue(byte.class, lua(300)).getClass());
    }

    @Test
    public void strings() {
        assertEquals(42, XParam.coerceValue(int.class, "42"));
        assertEquals(42L, XParam.coerceValue(long.class, "42"));
        assertEquals("42", XParam.coerceValue(String.class, "42"));
        // A text of Lua, which luaj passes as a String, for a method which takes the bytes of a path, like renameat
        Object path = CoerceLuaToJava.coerce(LuaValue.valueOf("/sys/a/cid~"), Object.class);
        assertArrayEquals("/sys/a/cid~".getBytes(StandardCharsets.UTF_8), (byte[]) XParam.coerceValue(byte[].class, path));
    }

    private static ApplicationInfo app(int uid, int flags) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.uid = uid;
        ai.flags = flags;
        return ai;
    }

    private static Context context(int uid) {
        ApplicationInfo ai = app(uid, 0);
        return new ContextWrapper(null) {
            @Override
            public String getPackageName() {
                return "test.app";
            }

            @Override
            public ApplicationInfo getApplicationInfo() {
                return ai;
            }
        };
    }

    private static XParam param() throws NoSuchFieldException {
        return param(UID);
    }

    private static XParam param(int uid) throws NoSuchFieldException {
        return new XParam(context(uid), XParamTest.class.getDeclaredField("field"), new HashMap<>());
    }

    private static XParam param(XCall call) {
        return new XParam(context(UID), call, new HashMap<>());
    }

    // A field cannot be read with an exception
    @Test
    public void fieldThrowable() throws Throwable {
        try {
            param().setResult(new IllegalStateException("blocked"));
            fail("Throwable for a field");
        } catch (IllegalArgumentException ignored) {
        }
    }

    // Arguments set by scripts must fit the parameters of the method
    @Test
    public void arguments() throws NoSuchMethodException {
        Method count = XParamTest.class.getMethod("count", int.class, String.class);
        XParam param = param(new XCall(count, null, new Object[]{1, "one"}));
        param.setArgument(0, lua(7));
        assertEquals(7, param.getArgument(0));
        param.setArgument(1, null);
        assertNull(param.getArgument(1));

        try {
            param.setArgument(0, null);
            fail("Null for a primitive");
        } catch (IllegalArgumentException ex) {
            assertEquals("Expected argument #0 int got null", ex.getMessage());
        }
        try {
            param.setArgument(1, lua(7));
            fail("Number for a string");
        } catch (IllegalArgumentException ignored) {
        }
        try {
            param.setArgument(2, null);
            fail("Argument out of range");
        } catch (ArrayIndexOutOfBoundsException ignored) {
        }
    }

    // Results set by scripts must fit the return type, a throwable is thrown instead of returned
    @Test
    public void results() throws Throwable {
        Method count = XParamTest.class.getMethod("count", int.class, String.class);
        XCall call = new XCall(count, null, new Object[]{1, "one"});
        XParam param = param(call);
        param.setResult(lua(7));
        assertEquals(7, param.getResult());
        assertTrue(call.isReturnEarly());

        try {
            param.setResult(null);
            fail("Null for a primitive");
        } catch (IllegalArgumentException ex) {
            assertEquals("Expected return int got null", ex.getMessage());
        }
        try {
            param.setResult(true);
            fail("Boolean for a number");
        } catch (IllegalArgumentException ignored) {
        }

        param.setResult(new IllegalStateException("blocked"));
        assertTrue(param.getException() instanceof IllegalStateException);
        assertNull(param.getResult());

        // A void method is skipped with a null result, which is the only result it can have
        XCall run = new XCall(XParamTest.class.getMethod("run"), null, new Object[0]);
        param = param(run);
        param.setResult(null);
        assertTrue(run.isReturnEarly());
        try {
            param.setResult(lua(7));
            fail("Value for void");
        } catch (IllegalArgumentException ignored) {
        }
    }

    // The message of an exception, which scripts cannot set themselves (see package_probe)
    @Test
    public void exceptionMessage() throws Throwable {
        Exception ex = new IllegalStateException("Package com.example.bank.x123456789 not found");
        param().setMessage(ex, "Package com.example.bank not found");
        assertEquals("Package com.example.bank not found", ex.getMessage());
    }

    @Test
    public void visibleApplications() throws NoSuchFieldException {
        XParam param = param();
        assertTrue(param.isVisible(null));
        assertTrue("Own uid", param.isVisible(app(UID, 0)));
        assertTrue("System", param.isVisible(app(1000, ApplicationInfo.FLAG_SYSTEM)));
        assertTrue("Updated system", param.isVisible(app(10001, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)));
        assertFalse("Other app", param.isVisible(app(10124, ApplicationInfo.FLAG_INSTALLED)));
    }

    // Uids which do not need a lookup
    @Test
    public void visibleUids() throws NoSuchFieldException {
        XParam param = param();
        assertTrue("Own uid", param.isUidVisible(UID));
        assertTrue("System", param.isUidVisible(1000));
        assertTrue("System of another user", param.isUidVisible(1001000));
        assertTrue("Root", param.isUidVisible(0));
    }

    // Processes with a system uid, like system server, see all apps; without lookups, which need a package manager
    @Test
    public void systemProcess() throws NoSuchFieldException {
        assertFalse(param().isSystem());
        for (int uid : new int[]{1000, 1001, 1001000}) {
            XParam param = param(uid);
            assertTrue(param.isSystem());
            assertTrue("Other app", param.isVisible(app(10124, ApplicationInfo.FLAG_INSTALLED)));
            assertTrue("Other package", param.isPackageVisible("other.app"));
            assertTrue("Other uid", param.isUidVisible(10124));
        }
    }

    @Test
    public void installedPackages() throws NoSuchFieldException {
        XParam param = param();
        assertTrue("Own package", param.isInstalledVisible("test.app"));
        assertFalse("No package", param.isInstalledVisible(null));
    }

    // The lookups of scripts do not run the hooks of the app, also when they fail
    @Test
    public void unhooked() throws NoSuchFieldException {
        XParam param = param();
        assertFalse(XCore.inService());
        LuaValue result = param.unhooked(new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(XCore.inService());
            }
        });
        assertTrue(result.toboolean());
        assertFalse(XCore.inService());

        try {
            param.unhooked(new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    throw new LuaError("lookup");
                }
            });
            fail("Error not passed on");
        } catch (LuaError ignored) {
        }
        assertFalse(XCore.inService());
    }

    // A property area like bionic builds it (prop_area::add and find_property): the header, the root, from Android 11
    // the backup of a value, then for each property the nodes of the parts of its name, each in the binary tree of its
    // siblings, shorter parts first, and the property with its serial, its value and its name, all aligned to 4 bytes
    private static ByteBuffer areaOf(boolean backup, String... properties) {
        ByteBuffer area = ByteBuffer.allocate(128 * 1024).order(ByteOrder.nativeOrder());
        area.putInt(8, 0x504f5250).putInt(12, 0xfc6ed0ab);
        int used = 20 + (backup ? 92 : 0);
        for (String property : properties) {
            String name = property.substring(0, property.indexOf('='));
            byte[] value = property.substring(name.length() + 1).getBytes(StandardCharsets.UTF_8);
            int node = 0;
            for (String part : name.split("\\.")) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                int link = 128 + node + 16;
                while (true) {
                    int child = area.getInt(link);
                    if (child == 0) {
                        area.putInt(link, used);
                        area.putInt(128 + used, bytes.length);
                        for (int i = 0; i < bytes.length; i++)
                            area.put(128 + used + 20 + i, bytes[i]);
                        node = used;
                        used += (20 + bytes.length + 1 + 3) & ~3;
                        break;
                    }
                    byte[] other = new byte[area.getInt(128 + child)];
                    for (int i = 0; i < other.length; i++)
                        other[i] = area.get(128 + child + 20 + i);
                    int order = (bytes.length != other.length ? Integer.compare(bytes.length, other.length) :
                            new String(bytes, StandardCharsets.UTF_8).compareTo(new String(other, StandardCharsets.UTF_8)));
                    if (order == 0) {
                        node = child;
                        break;
                    }
                    link = 128 + child + (order < 0 ? 8 : 12);
                }
            }
            area.putInt(128 + node + 4, used);
            area.putInt(128 + used, value.length << 24);
            for (int i = 0; i < value.length; i++)
                area.put(128 + used + 4 + i, value[i]);
            byte[] full = name.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < full.length; i++)
                area.put(128 + used + 96 + i, full[i]);
            used += (96 + full.length + 1 + 3) & ~3;
        }
        area.putInt(0, used);
        return area;
    }

    private static boolean holds(ByteBuffer area, String text) {
        return new String(area.array(), StandardCharsets.ISO_8859_1).contains(text);
    }

    // The value of a property in a property area, which apps can read as a file (/dev/__properties__), becomes the
    // fake one, without older values after it or in the backup; other properties, parts of names and files which are
    // no property area stay
    @Test
    public void propertyAreaValues() {
        ByteBuffer area = areaOf(false, "persist.sys.usb.config=adb", "persist.sys.timezone=America/Argentina/Buenos_Aires",
                "persist.sys.locale=de-DE", "sys.boot_completed=1", "persist.sys.a=b", "persist.sys.timezone_confidence=100",
                "ro.build.id=AP1A", "persist.sys.language=", "persist.sys.zzzzzzzz=z");
        byte[] other = areaOf(false, "persist.sys.usb.config=adb", "persist.sys.timezone=Europe/Kyiv",
                "persist.sys.locale=de-DE", "sys.boot_completed=1", "persist.sys.a=b", "persist.sys.timezone_confidence=100",
                "ro.build.id=AP1A", "persist.sys.language=", "persist.sys.zzzzzzzz=z").array();
        assertEquals("America/Argentina/Buenos_Aires",
                XParam.replaceProperty(area.array(), "persist.sys.timezone", "Europe/Kyiv", false));
        // The same bytes as an area made with the fake value, its length in the serial too
        assertArrayEquals(other, area.array());
        assertFalse(holds(area, "Buenos"));
        assertEquals("Europe/Kyiv", XParam.replaceProperty(area.array(), "persist.sys.timezone", "UTC", false));
        assertNull("Nothing changed", XParam.replaceProperty(area.array(), "persist.sys.timezone", "UTC", false));
        assertEquals("de-DE", XParam.replaceProperty(area.array(), "persist.sys.locale", "en-US", false));
        assertEquals("100", XParam.replaceProperty(area.array(), "persist.sys.timezone_confidence", "100x", false));
        assertEquals("z", XParam.replaceProperty(area.array(), "persist.sys.zzzzzzzz", "", false));
        assertNull("Empty", XParam.replaceProperty(area.array(), "persist.sys.zzzzzzzz", "y", false));
        assertNull("Empty", XParam.replaceProperty(area.array(), "persist.sys.language", "en", false));
        for (String missing : new String[]{"persist.sys", "persist.sys.timezon", "persist.sys.timezone.x", "persist.sys.b",
                "persist..timezone", "", "vendor.sys.timezone"})
            assertNull(missing, XParam.replaceProperty(area.array(), missing, "UTC", false));
        assertEquals("1", XParam.replaceProperty(area.array(), "sys.boot_completed", "0", false));
        assertEquals("adb", XParam.replaceProperty(area.array(), "persist.sys.usb.config", "mtp", false));
        assertEquals("AP1A", XParam.replaceProperty(area.array(), "ro.build.id", "UNKNOWN", false));
        assertNull("Too long", XParam.replaceProperty(area.array(), "ro.build.id", new String(new char[92]).replace('\0', 'x'), false));

        assertNull(XParam.replaceProperty(new byte[]{1, 2, 3}, "persist.sys.timezone", "UTC", false));
        assertNull(XParam.replaceProperty(new byte[128 * 1024], "persist.sys.timezone", "UTC", false));
        // A long value, of ro. properties only, is elsewhere
        ByteBuffer flagged = areaOf(false, "persist.sys.timezone=Europe/Kyiv");
        int info = new String(flagged.array(), StandardCharsets.ISO_8859_1).indexOf("persist.sys.timezone\0") - 96;
        flagged.putInt(info, flagged.getInt(info) | (1 << 16));
        assertNull("Long", XParam.replaceProperty(flagged.array(), "persist.sys.timezone", "UTC", false));

        // Android 11: the backup holds the value before the last change, which becomes the fake one, unless it is empty
        ByteBuffer backup = areaOf(true, "persist.sys.timezone=Europe/London");
        byte[] berlin = "Europe/Berlin".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < berlin.length; i++)
            backup.put(128 + 20 + i, berlin[i]);
        assertEquals("Europe/London", XParam.replaceProperty(backup.array(), "persist.sys.timezone", "UTC", true));
        assertFalse(holds(backup, "Berlin"));
        assertFalse(holds(backup, "London"));
        assertEquals("UTC", new String(backup.array(), 128 + 20, 3, StandardCharsets.UTF_8));
        assertEquals(0, backup.get(128 + 20 + 3));
        ByteBuffer clean = areaOf(true, "persist.sys.timezone=Europe/London");
        assertEquals("Europe/London", XParam.replaceProperty(clean.array(), "persist.sys.timezone", "UTC", true));
        assertEquals(0, clean.get(128 + 20));
    }

    // The properties of a property area by their whole names, which scripts compare with patterns, like of the IMEIs of
    // vendors, with their values, also empty ones; long values and files which are no property area give none
    @Test
    public void propertyAreaNames() {
        ByteBuffer area = areaOf(true, "ril.serialnumber=R58M1234", "gsm.sim.preiccid_0=89490", "persist.sys.imei1=4901542",
                "gsm.operator.numeric=310260,", "ril.iccid.sim1=", "gsm.operator.alpha=T-Mobile", "ril.a.b.c=d");
        int info = new String(area.array(), StandardCharsets.ISO_8859_1).indexOf("ril.a.b.c\0") - 96;
        area.putInt(info, area.getInt(info) | (1 << 16));
        String[] properties = XParam.listProperties(area.array());
        Map<String, String> found = new HashMap<>();
        for (int i = 0; i < properties.length; i += 2)
            assertNull(properties[i], found.put(properties[i], properties[i + 1]));
        Map<String, String> expected = new HashMap<>();
        expected.put("ril.serialnumber", "R58M1234");
        expected.put("gsm.sim.preiccid_0", "89490");
        expected.put("persist.sys.imei1", "4901542");
        expected.put("gsm.operator.numeric", "310260,");
        expected.put("ril.iccid.sim1", "");
        expected.put("gsm.operator.alpha", "T-Mobile");
        assertEquals(expected, found);
        assertEquals(0, XParam.listProperties(new byte[]{1, 2, 3}).length);
        assertEquals(0, XParam.listProperties(new byte[128 * 1024]).length);
    }
}
