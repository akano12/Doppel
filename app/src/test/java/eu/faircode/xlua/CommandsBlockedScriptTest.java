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

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

// The script of the hooks of ProcessBuilder.start and UNIXProcess.forkAndExec, with a real builder and files, and Lua
// stand-ins for the hook and the parameter
public class CommandsBlockedScriptTest {
    private static final String STUBS = String.join("\n",
            "function call(method, this, args, folder)",
            "  local thrown",
            "  local info = { nativeLibraryDir = folder }",
            "  local param = {",
            "    getThis = function(self) return this end,",
            "    getArgument = function(self, index) return args[index] end,",
            "    setArgument = function(self, index, value) args[index] = value end,",
            "    getApplicationContext = function(self)",
            "      return { getApplicationInfo = function(self) return info end }",
            "    end,",
            "    setResult = function(self, value) thrown = value end }",
            "  local restricted, line = before({ getMethodName = function(self) return method end }, param)",
            "  return restricted, thrown, line, args[0]",
            "end",
            "function start(builder, folder) return call('start', builder, {}, folder) end",
            "function fork(program, dir, folder) return call('forkAndExec', {}, { [0] = program, [5] = dir }, folder) end",
            "function finish(builder, ex)",
            "  local made",
            "  after({}, { getThis = function(self) return builder end, getException = function(self) return ex end,",
            "    setResult = function(self, value) made = value end })",
            "  return made",
            "end");

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Globals globals;
    private File lib;
    private File files;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("commands_blocked", STUBS);
        lib = new File(temp.getRoot(), "lib");
        files = temp.newFolder("files");
    }

    private Varargs start(File dir, String... command) {
        return invoke(new ProcessBuilder(Arrays.asList(command)).directory(dir));
    }

    private Varargs invoke(ProcessBuilder builder) {
        return globals.get("start").invoke(CoerceJavaToLua.coerce(builder), CoerceJavaToLua.coerce(lib.getPath()));
    }

    // The exception which ProcessBuilder.start throws with the hook, null when the command runs: the program run
    // instead fails for real, here on the computer running the test, and the hook only names the program of the app
    private IOException refused(File dir, String... command) {
        return refused(new ProcessBuilder(Arrays.asList(command)).directory(dir));
    }

    private IOException refused(ProcessBuilder builder) {
        List<String> command = new ArrayList<>(builder.command());
        Varargs result = invoke(builder);
        assertTrue(result.arg(2).isnil());
        if (!result.arg1().toboolean())
            return null;
        assertEquals(String.join(" ", command), result.arg(3).tojstring());
        Assume.assumeTrue(File.separatorChar == '/');
        IOException real = assertThrows(IOException.class, builder::start);
        IOException ex = (IOException) globals.get("finish").call(CoerceJavaToLua.coerce(builder),
                CoerceJavaToLua.coerce(real)).touserdata();
        assertEquals(command, builder.command());
        // The trace and the cause of Java, of its exec, without frames of the script
        assertArrayEquals(real.getStackTrace(), ex.getStackTrace());
        assertSame(real.getCause(), ex.getCause());
        for (Throwable thrown : new Throwable[]{ex, ex.getCause()})
            for (StackTraceElement frame : thrown.getStackTrace())
                assertFalse(frame.toString(), frame.getClassName().startsWith("org.luaj."));
        return ex;
    }

    // The message of start for the program of the app with the error of the real exec, which Java of the computer
    // running the test words its own way, like "Exec failed, error: 13 (Permission denied)" for error=13 of Android
    private static void assertRefused(String start, String error, IOException ex) {
        String exec = ex.getCause().getMessage();
        assertEquals(start + ": " + exec, ex.getMessage());
        assertTrue(exec, exec.contains(error));
    }

    // A path as ProcessImpl.start passes it on to UNIXProcess.forkAndExec, a C string, or null
    private static LuaValue cstring(String path) {
        return (path == null ? LuaValue.NIL : CoerceJavaToLua.coerce((path + "\0").getBytes()));
    }

    // The program which forkAndExec runs instead with the hook, a C string, null when the program runs
    private String forked(String dir, String program) {
        Varargs result = globals.get("fork").invoke(cstring(program), cstring(dir), CoerceJavaToLua.coerce(lib.getPath()));
        assertTrue(result.arg(2).isnil());
        if (!result.arg1().toboolean())
            return null;
        assertEquals(program, result.arg(3).tojstring());
        return new String((byte[]) result.arg(4).touserdata());
    }

    // Programs which exist, here those of the computer running the test, fail like programs which may not run
    @Test
    public void commandsAreRefused() {
        Assume.assumeTrue(new File("/bin/sh").exists());
        IOException ex = refused(null, "sh", "-c", "getprop");
        assertRefused("Cannot run program \"sh\"", "Permission denied", ex);
        assertTrue(ex.getCause() instanceof IOException);
        assertRefused("Cannot run program \"/bin/sh\"", "Permission denied", refused(null, "/bin/sh", "-c", "id"));
    }

    // Each program run instead fails with its errno by itself, here on the kernel of the computer running the test,
    // whose Java words the error its own way
    @Test
    public void programsRunInsteadFailWithTheirErrno() {
        Assume.assumeTrue(new File("/proc/self/fd").isDirectory());
        String[][] errors = {{"13", "Permission denied"}, {"2", "No such file or directory"}, {"20", "Not a directory"},
                {"36", "File name too long"}, {"40", "Too many levels of symbolic links"}};
        for (String[] error : errors) {
            String program = globals.get("instead").call(LuaValue.valueOf(Integer.parseInt(error[0]))).tojstring();
            IOException ex = assertThrows(IOException.class, () -> new ProcessBuilder(program).start());
            assertTrue(error[1] + ": " + ex.getMessage(), ex.getMessage().contains(error[1]));
        }
        assertTrue(globals.get("instead").call(LuaValue.valueOf(5)).isnil());
    }

    // The programs run instead fail like that by themselves, also in the hooks of the method beneath and of other
    // groups, and other exceptions of start keep their message
    @Test
    public void programsRunInsteadArePassedOn() {
        for (int errno : new int[]{13, 2, 20, 36, 40}) {
            String program = globals.get("instead").call(LuaValue.valueOf(errno)).tojstring();
            assertFalse(start(null, program).arg1().toboolean());
            assertNull(forked(null, program));
            assertNull(forked(files.getPath(), program));
        }
        ProcessBuilder builder = new ProcessBuilder("getprop");
        assertTrue(invoke(builder).arg1().toboolean());
        IOException other = new IOException("Cannot run program \"getprop\": error=7, Argument list too long");
        assertTrue(globals.get("finish").call(CoerceJavaToLua.coerce(builder), CoerceJavaToLua.coerce(other)).isnil());
        assertEquals(Arrays.asList("getprop"), builder.command());
    }

    // Programs which do not exist fail like on Android, by name in PATH and by path, also relative to the directory
    @Test
    public void missingProgramsDoNotExist() throws Exception {
        for (String[] command : new String[][]{{"no_such_tool_12345"}, {"/nonexistent/bin/getprop", "ro.serialno"}})
            assertRefused("Cannot run program \"" + command[0] + "\"", "No such file or directory", refused(null, command));
        String start = "Cannot run program \"./tool\" (in directory \"" + files.getPath() + "\")";
        assertRefused(start, "No such file or directory", refused(files, "./tool"));
        assertTrue(new File(files, "tool").createNewFile());
        assertRefused(start, "Permission denied", refused(files, "./tool"));
    }

    // ProcessImpl.start and the UNIXProcess constructor, which apps can call by reflection, pass on the exception of
    // forkAndExec as it is: the error of the exec alone, so it runs a program which fails like that, as a C string;
    // the C strings end at their NUL, else /bin/sh would be missing
    @Test
    public void forkIsRefused() throws Exception {
        Assume.assumeTrue(new File("/bin/sh").exists());
        assertEquals("/dev/null\0", forked(null, "sh"));
        assertEquals("/dev/null\0", forked(null, "/bin/sh"));
        assertEquals("/proc/self/fd/-1\0", forked(null, "no_such_tool_12345"));
        assertEquals("/proc/self/fd/-1\0", forked(files.getPath(), "./tool"));
        assertTrue(new File(files, "tool").createNewFile());
        assertEquals("/dev/null\0", forked(files.getPath(), "./tool"));
    }

    @Test
    public void forkOfProgramsOfTheAppRuns() throws Exception {
        Assume.assumeTrue(File.separatorChar == '/');
        assertTrue(lib.mkdir());
        File program = new File(lib, "libtool.so");
        assertTrue(program.createNewFile());
        assertNull(forked(null, program.getPath()));
        assertNull(forked(lib.getPath(), "./libtool.so"));
        // Not by its name only, which PATH finds
        assertTrue(forked(lib.getPath(), "libtool.so") != null);
        // By a link, which runs by the path checked, a C string again, so a link changed after the check runs no other
        File link = new File(files, "tool");
        Files.createSymbolicLink(link.toPath(), program.toPath());
        Varargs result = globals.get("fork").invoke(cstring(link.getPath()), LuaValue.NIL, CoerceJavaToLua.coerce(lib.getPath()));
        assertFalse(result.arg1().toboolean());
        assertArrayEquals((program.getCanonicalPath() + "\0").getBytes(), (byte[]) result.arg(4).touserdata());
        // With no link on the path the bytes of the app stay
        LuaValue bytes = cstring(program.getCanonicalPath());
        result = globals.get("fork").invoke(bytes, LuaValue.NIL, CoerceJavaToLua.coerce(lib.getPath()));
        assertTrue(result.arg(4).touserdata() == bytes.touserdata());
    }

    @Test
    public void forkOfNoProgramIsLeftToJava() {
        Varargs result = globals.get("fork").invoke(LuaValue.NIL, LuaValue.NIL, CoerceJavaToLua.coerce(lib.getPath()));
        assertFalse(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
    }

    // Like Android, no program does not exist even as a folder, and a working directory which does not work fails the
    // real exec before the program run instead, with the error of the kernel, here a folder which does not exist and a
    // file
    @Test
    public void noProgramAndNoDirectoryDoNotExist() throws Exception {
        assertRefused("Cannot run program \"\"", "No such file or directory", refused(null, ""));
        assertRefused("Cannot run program \"\" (in directory \"" + files.getPath() + "\")", "No such file or directory",
                refused(files, ""));
        Assume.assumeTrue(new File("/bin/sh").exists());
        File none = new File(temp.getRoot(), "none");
        assertRefused("Cannot run program \"sh\" (in directory \"" + none.getPath() + "\")", "No such file or directory",
                refused(none, "sh", "-c", "id"));
        File file = new File(files, "file");
        assertTrue(file.createNewFile());
        assertRefused("Cannot run program \"sh\" (in directory \"" + file.getPath() + "\")", "Not a directory",
                refused(file, "sh", "-c", "id"));
    }

    @Test
    public void directoryIsInTheMessage() {
        Assume.assumeTrue(new File("/bin/sh").exists());
        assertRefused("Cannot run program \"sh\" (in directory \"" + files.getPath() + "\")", "Permission denied",
                refused(files, "sh", "-c", "uname -a"));
    }

    // Java opens the files the builder redirects to before the program run instead fails: written ones are created,
    // or truncated unless appended to, and one which does not open fails the start with its own exception, which
    // names the program of the app like without the hook
    @Test
    public void redirectsOpenBeforeTheExec() throws Exception {
        Assume.assumeTrue(new File("/bin/sh").exists());
        File out = new File(files, "out");
        File log = new File(files, "log");
        Files.write(log.toPath(), "kept".getBytes());
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", "id").redirectOutput(out)
                .redirectError(ProcessBuilder.Redirect.appendTo(log));
        assertRefused("Cannot run program \"sh\"", "Permission denied", refused(builder));
        assertTrue(out.exists());
        assertEquals("kept", new String(Files.readAllBytes(log.toPath())));
        Files.write(out.toPath(), "old".getBytes());
        assertTrue(refused(new ProcessBuilder("sh").redirectOutput(out)) != null);
        assertEquals(0, out.length());

        File none = new File(files, "none");
        for (ProcessBuilder failing : new ProcessBuilder[]{
                new ProcessBuilder("sh").redirectInput(none).redirectOutput(files),
                new ProcessBuilder("sh").redirectError(files).directory(none)}) {
            IOException stock = assertThrows(IOException.class, failing::start);
            IOException ex = refused(failing);
            assertEquals(stock.getMessage(), ex.getMessage());
            assertEquals(FileNotFoundException.class, ex.getCause().getClass());
            assertEquals(stock.getCause().getMessage(), ex.getCause().getMessage());
        }
        assertFalse(none.exists());
    }

    @Test
    public void programsOfTheAppRun() throws Exception {
        // The script compares paths with /, which canonical paths of Windows do not have
        Assume.assumeTrue(File.separatorChar == '/');
        // Android links the native library folder of an app, like /data/app/<package>/lib/arm64
        File real = temp.newFolder("app", "lib", "arm64");
        Files.createSymbolicLink(lib.toPath(), real.toPath());
        File program = new File(lib, "libtool.so");
        assertTrue(program.createNewFile());
        assertNull(refused(null, program.getPath(), "-v"));
        // By a link into the folder, and relative to the working directory
        File link = new File(files, "tool");
        Files.createSymbolicLink(link.toPath(), program.toPath());
        assertNull(refused(null, link.getPath()));
        assertNull(refused(files, "./tool"));
        assertNull(refused(lib, "./libtool.so"));
        // Which runs by the path checked, so a link changed after the check does not run another program
        ProcessBuilder builder = new ProcessBuilder(link.getPath(), "-v").directory(files);
        assertTrue(invoke(builder).isnil(2));
        assertEquals(Arrays.asList(program.getCanonicalPath(), "-v"), builder.command());
        // With no link on the path the first word stays, since it is also the name of the program
        builder = new ProcessBuilder("./libtool.so").directory(real.getCanonicalFile());
        assertTrue(invoke(builder).isnil(2));
        assertEquals(Arrays.asList("./libtool.so"), builder.command());
        // Not by its name only, which PATH finds, nor by a path out of the folder
        assertTrue(refused(lib, "libtool.so") != null);
        assertTrue(refused(null, lib.getPath() + "/../arm64-x/libtool.so") != null);
        assertTrue(refused(null, lib.getPath() + "-x/libtool.so") != null);
        // Nor by a path which the exec does not walk, whose canonical path drops .. after a folder which does not exist
        assertRefused("Cannot run program \"" + lib.getPath() + "/none/../libtool.so\"", "No such file or directory",
                refused(null, lib.getPath() + "/none/../libtool.so"));
        // Nor with a / at the end, which java.io.File drops; the stand-in of Os.stat gives no ENOTDIR (CommandsExecScriptTest)
        assertTrue(refused(null, program.getPath() + "/") != null);
        // Nor a copy of a program in the files of the app
        File copy = new File(files, "getprop");
        Files.copy(program.toPath(), copy.toPath());
        assertTrue(refused(null, copy.getPath()) != null);
        assertTrue(refused(files, "./getprop") != null);
    }

    @Test
    public void invalidCommandsAreLeftToJava() {
        assertFalse(start(null).arg1().toboolean());
        assertFalse(start(null, "getprop", null).arg1().toboolean());
        assertFalse(start(null, "ls", "a\u0000b").arg1().toboolean());
    }
}
