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
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// The script of the hooks of ProcessBuilder.start, with a real builder and files, and a Lua stand-in for the parameter
public class CommandsBlockedScriptTest {
    private static final String STUBS = String.join("\n",
            "function start(builder, folder)",
            "  local thrown",
            "  local info = { nativeLibraryDir = folder }",
            "  local param = {",
            "    getThis = function(self) return builder end,",
            "    getApplicationContext = function(self)",
            "      return { getApplicationInfo = function(self) return info end }",
            "    end,",
            "    setResult = function(self, value) thrown = value end }",
            "  local restricted, line = before({}, param)",
            "  return restricted, thrown, line",
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
        ProcessBuilder builder = new ProcessBuilder(Arrays.asList(command)).directory(dir);
        return globals.get("start").invoke(CoerceJavaToLua.coerce(builder), CoerceJavaToLua.coerce(lib.getPath()));
    }

    // The exception which the hook makes ProcessBuilder.start throw, null when the command runs
    private IOException refused(File dir, String... command) {
        Varargs result = start(dir, command);
        if (!result.arg1().toboolean()) {
            assertTrue(result.arg(2).isnil());
            return null;
        }
        assertEquals(String.join(" ", command), result.arg(3).tojstring());
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

    @Test
    public void directoryIsInTheMessage() {
        Assume.assumeTrue(new File("/bin/sh").exists());
        assertEquals("Cannot run program \"sh\" (in directory \"" + files.getPath() + "\"): error=13, Permission denied",
                refused(files, "sh", "-c", "uname -a").getMessage());
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
        // Not by its name only, which PATH finds, nor by a path out of the folder
        assertTrue(refused(lib, "libtool.so") != null);
        assertTrue(refused(null, lib.getPath() + "/../arm64-x/libtool.so") != null);
        assertTrue(refused(null, lib.getPath() + "-x/libtool.so") != null);
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
    }
}
