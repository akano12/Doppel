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
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
            "function fork(program, dir, folder) return call('forkAndExec', {}, { [0] = program, [5] = dir }, folder) end");

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

    // The exception which the hook makes ProcessBuilder.start throw, null when the command runs
    private IOException refused(File dir, String... command) {
        return refused(new ProcessBuilder(Arrays.asList(command)).directory(dir));
    }

    private IOException refused(ProcessBuilder builder) {
        Varargs result = invoke(builder);
        if (!result.arg1().toboolean()) {
            assertTrue(result.arg(2).isnil());
            return null;
        }
        assertEquals(String.join(" ", builder.command()), result.arg(3).tojstring());
        return (IOException) result.arg(2).touserdata();
    }

    // A path as ProcessImpl.start passes it on to UNIXProcess.forkAndExec, a C string, or null
    private static LuaValue cstring(String path) {
        return (path == null ? LuaValue.NIL : CoerceJavaToLua.coerce((path + "\0").getBytes()));
    }

    // The exception which the hook makes forkAndExec throw, null when the program runs
    private IOException forked(String dir, String program) {
        Varargs result = globals.get("fork").invoke(cstring(program), cstring(dir), CoerceJavaToLua.coerce(lib.getPath()));
        if (!result.arg1().toboolean()) {
            assertTrue(result.arg(2).isnil());
            return null;
        }
        assertEquals(program, result.arg(3).tojstring());
        return (IOException) result.arg(2).touserdata();
    }

    // Programs which exist, here those of the computer running the test, fail like programs which may not run
    @Test
    public void commandsAreRefused() {
        Assume.assumeTrue(new File("/bin/sh").exists());
        IOException ex = refused(null, "sh", "-c", "getprop");
        assertEquals("Cannot run program \"sh\": error=13, Permission denied", ex.getMessage());
        assertEquals("error=13, Permission denied", ex.getCause().getMessage());
        assertTrue(ex.getCause() instanceof IOException);
        assertEquals("Cannot run program \"/bin/sh\": error=13, Permission denied",
                refused(null, "/bin/sh", "-c", "id").getMessage());
    }

    // Programs which do not exist fail like on Android, by name in PATH and by path, also relative to the directory
    @Test
    public void missingProgramsDoNotExist() throws Exception {
        for (String[] command : new String[][]{{"no_such_tool_12345"}, {"/nonexistent/bin/getprop", "ro.serialno"}})
            assertEquals(String.join(" ", command),
                    "Cannot run program \"" + command[0] + "\": error=2, No such file or directory",
                    refused(null, command).getMessage());
        assertEquals("Cannot run program \"./tool\" (in directory \"" + files.getPath() + "\"): error=2, No such file or directory",
                refused(files, "./tool").getMessage());
        assertTrue(new File(files, "tool").createNewFile());
        assertEquals("Cannot run program \"./tool\" (in directory \"" + files.getPath() + "\"): error=13, Permission denied",
                refused(files, "./tool").getMessage());
    }

    // ProcessImpl.start and the UNIXProcess constructor, which apps can call by reflection, pass on the exception of
    // forkAndExec as it is: the error of the exec alone; the C strings end at their NUL, else /bin/sh would be missing
    @Test
    public void forkIsRefused() throws Exception {
        Assume.assumeTrue(new File("/bin/sh").exists());
        IOException ex = forked(null, "sh");
        assertEquals("error=13, Permission denied", ex.getMessage());
        assertNull(ex.getCause());
        assertEquals("error=13, Permission denied", forked(null, "/bin/sh").getMessage());
        assertEquals("error=2, No such file or directory", forked(null, "no_such_tool_12345").getMessage());
        assertEquals("error=2, No such file or directory", forked(files.getPath(), "./tool").getMessage());
        assertTrue(new File(files, "tool").createNewFile());
        assertEquals("error=13, Permission denied", forked(files.getPath(), "./tool").getMessage());
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

    // Like Android, no program does not exist even as a folder, and a working directory which does not exist fails
    // before the program is looked for
    @Test
    public void noProgramAndNoDirectoryDoNotExist() {
        assertEquals("Cannot run program \"\": error=2, No such file or directory", refused(null, "").getMessage());
        assertEquals("Cannot run program \"\" (in directory \"" + files.getPath() + "\"): error=2, No such file or directory",
                refused(files, "").getMessage());
        Assume.assumeTrue(new File("/bin/sh").exists());
        File none = new File(temp.getRoot(), "none");
        assertEquals("Cannot run program \"sh\" (in directory \"" + none.getPath() + "\"): error=2, No such file or directory",
                refused(none, "sh", "-c", "id").getMessage());
    }

    @Test
    public void directoryIsInTheMessage() {
        Assume.assumeTrue(new File("/bin/sh").exists());
        assertEquals("Cannot run program \"sh\" (in directory \"" + files.getPath() + "\"): error=13, Permission denied",
                refused(files, "sh", "-c", "uname -a").getMessage());
    }

    // Like ProcessImpl.start, the files the builder redirects to are opened before the exec fails: written ones are
    // created, or truncated unless appended to, and one which does not open fails the start with its exception
    @Test
    public void redirectsOpenBeforeTheExec() throws Exception {
        Assume.assumeTrue(new File("/bin/sh").exists());
        File out = new File(files, "out");
        File log = new File(files, "log");
        Files.write(log.toPath(), "kept".getBytes());
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", "id").redirectOutput(out)
                .redirectError(ProcessBuilder.Redirect.appendTo(log));
        assertEquals("Cannot run program \"sh\": error=13, Permission denied", refused(builder).getMessage());
        assertTrue(out.exists());
        assertEquals("kept", new String(Files.readAllBytes(log.toPath())));
        Files.write(out.toPath(), "old".getBytes());
        assertTrue(refused(new ProcessBuilder("sh").redirectOutput(out)) != null);
        assertEquals(0, out.length());

        // Files which do not open fail like the Java running the test, which opens them before it starts a process:
        // the input first, a folder written to, and before a working directory which does not exist
        File none = new File(files, "none");
        for (ProcessBuilder failing : new ProcessBuilder[]{
                new ProcessBuilder("sh").redirectInput(none).redirectOutput(files),
                new ProcessBuilder("sh").redirectError(files).directory(none)}) {
            IOException ex = refused(failing);
            IOException stock = assertThrows(IOException.class, failing::start);
            assertEquals(stock.getMessage(), ex.getMessage());
            assertEquals(FileNotFoundException.class, ex.getCause().getClass());
            assertEquals(stock.getCause().getMessage(), ex.getCause().getMessage());
        }
        assertFalse(none.exists());
    }

    // The exception of a redirect which does not open on Android, which pcall gives as text: from Android 10 on
    // with the ErrnoException of open as cause, before with the message of OpenJDK only
    @Test
    public void unopenedRedirectsHaveTheirCause() {
        LuaValue unopened = globals.get("unopened");
        String message = "/data/user/0/com.example/files/in: open failed: ENOENT (No such file or directory)";
        FileNotFoundException ex = (FileNotFoundException) unopened.call(
                "vm error: java.io.FileNotFoundException: " + message).touserdata();
        assertEquals(message, ex.getMessage());
        assertTrue(ex.getCause() instanceof android.system.ErrnoException);
        message = "/data/user/0/com.example/files/in (No such file or directory)";
        ex = (FileNotFoundException) unopened.call("vm error: java.io.FileNotFoundException: " + message).touserdata();
        assertEquals(message, ex.getMessage());
        assertNull(ex.getCause());
        assertTrue(unopened.call("vm error: java.lang.SecurityException: denied").isnil());
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
        assertTrue(globals.get("start").invoke(CoerceJavaToLua.coerce(builder), CoerceJavaToLua.coerce(lib.getPath())).isnil(2));
        assertEquals(Arrays.asList(program.getCanonicalPath(), "-v"), builder.command());
        // With no link on the path the first word stays, since it is also the name of the program
        builder = new ProcessBuilder("./libtool.so").directory(real.getCanonicalFile());
        assertTrue(globals.get("start").invoke(CoerceJavaToLua.coerce(builder), CoerceJavaToLua.coerce(lib.getPath())).isnil(2));
        assertEquals(Arrays.asList("./libtool.so"), builder.command());
        // Not by its name only, which PATH finds, nor by a path out of the folder
        assertTrue(refused(lib, "libtool.so") != null);
        assertTrue(refused(null, lib.getPath() + "/../arm64-x/libtool.so") != null);
        assertTrue(refused(null, lib.getPath() + "-x/libtool.so") != null);
        // Nor by a path which the exec does not walk, whose canonical path drops .. after a folder which does not exist
        assertEquals("Cannot run program \"" + lib.getPath() + "/none/../libtool.so\": error=2, No such file or directory",
                refused(null, lib.getPath() + "/none/../libtool.so").getMessage());
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
