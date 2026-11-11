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
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The script of commands (commands_blocked) for Os.execv and execve and for a list of the app, with the paths of
// Android as texts, so unlike CommandsBlockedScriptTest it needs no links, which Windows does not allow every user
public class CommandsExecScriptTest {
    private static final String LIB = "/data/app/com.example/lib/arm64";
    private static final String LINK = "/data/user/0/com.example/files/tool";

    private static final String STUBS = String.join("\n",
            "local bind, make, instance = luajava.bindClass, luajava.new, luajava.newInstance",
            // Absolute paths exist but these, and the link of the app leads into its native library folder
            "local absent = { ['/system/bin/nothing'] = true, ['/system/bin/sh/x'] = true, ['/system/bin/long'] = true,",
            "  ['/system/bin/loop'] = true, ['/system/bin/io'] = true }",
            "local errors = { ['/system/bin/long'] = 'ENAMETOOLONG (File name too long)',",
            "  ['/system/bin/loop'] = 'ELOOP (Too many levels of symbolic links)', ['/system/bin/io'] = 'EIO (I/O error)' }",
            "local links = { ['" + LINK + "'] = '" + LIB + "/libtool.so' }",
            // Os.stat fails like Android for a path through a file or a / after one, all files here, and for the others
            "local os = { stat = function(self, path)",
            "  if string.find(path, '/system/bin/sh/', 1, true) == 1 or string.sub(path, -1) == '/' then",
            "    error('vm error: android.system.ErrnoException: stat failed: ENOTDIR (Not a directory)', 0)",
            "  end",
            "  if errors[path] then error('vm error: android.system.ErrnoException: stat failed: ' .. errors[path], 0) end",
            "end }",
            "luajava.bindClass = function(name)",
            "  if name == 'java.io.File' then return 'file' end",
            "  if name == 'android.system.Os' then return os end",
            "  if name == 'android.system.OsConstants' then return { ENOTDIR = 20, ENAMETOOLONG = 36, ELOOP = 40, EIO = 5 } end",
            "  return bind(name)",
            "end",
            "luajava.new = function(class, ...)",
            "  if class ~= 'file' then return make(class, ...) end",
            "  local path = ...",
            "  local absolute = string.sub(path, 1, 1) == '/'",
            "  return { isAbsolute = function(self) return absolute end,",
            "    getPath = function(self) return path end,",
            "    getAbsolutePath = function(self) return path end,",
            "    isDirectory = function(self) return false end,",
            "    getCanonicalPath = function(self) return links[path] or path end,",
            "    exists = function(self) return absolute and not absent[path] end }",
            "end",
            "luajava.newInstance = function(name, ...)",
            "  if name ~= 'android.system.ErrnoException' then return instance(name, ...) end",
            "  local method, errno = ...",
            "  return method .. ' ' .. errno",
            "end",
            "function run(method, builder, program)",
            "  local thrown",
            "  local info = { nativeLibraryDir = '" + LIB + "' }",
            "  local param = {",
            "    getThis = function(self) return builder end,",
            "    getArgument = function(self, index) return program end,",
            "    setArgument = function(self, index, value) program = value end,",
            "    getApplicationContext = function(self)",
            "      return { getApplicationInfo = function(self) return info end }",
            "    end,",
            "    setResult = function(self, value) thrown = value end }",
            "  local hook = { getMethodName = function(self) return method end }",
            "  local restricted, line = before(hook, param)",
            "  return restricted, thrown, line, program",
            "end",
            "function finish(builder)",
            "  return after({}, { getThis = function(self) return builder end, getException = function(self) end })",
            "end");

    // A list which shows the script other words than ProcessBuilder.start reads from it
    private static class Shown extends ArrayList<String> {
        private final Object[] shown;

        Shown(List<String> read, Object... shown) {
            super(read);
            this.shown = shown;
        }

        @Override
        public Object[] toArray() {
            return this.shown;
        }
    }

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("commands_blocked", STUBS);
    }

    private Varargs exec(String method, String program) {
        return globals.get("run").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf(method), LuaValue.NIL, program == null ? LuaValue.NIL : LuaValue.valueOf(program)}));
    }

    private Varargs start(ProcessBuilder builder) {
        return globals.get("run").invoke(LuaValue.valueOf("start"), CoerceJavaToLua.coerce(builder));
    }

    // What ProcessBuilder.start reads
    private static List<String> words(ProcessBuilder builder) {
        return Arrays.asList(builder.command().toArray(new String[0]));
    }

    // The program the exec runs instead, which fails with the errno of the program of the app, so Linux_execv and
    // Linux_execve of libcore throw their own exception, each with its own name
    private String instead(String method, String program) {
        Varargs result = exec(method, program);
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        assertEquals(program, result.arg(3).tojstring());
        return result.arg(4).tojstring();
    }

    // The program ProcessBuilder.start runs instead, which fails with the errno of the program of the app
    private String instead(ProcessBuilder builder) {
        Varargs result = start(builder);
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        return words(builder).get(0);
    }

    @Test
    public void execIsRefused() {
        for (String method : new String[]{"execv", "execve"})
            assertEquals("/dev/null", instead(method, "/system/bin/sh"));
        // A program which does not exist, like Android answers
        assertEquals("/proc/self/fd/-1", instead("execv", "/system/bin/nothing"));
        assertEquals("/dev/null", instead("execv", LIB + "-x/libtool.so"));
        // Which the hooks of Linux beneath Os, and of other groups, pass on
        assertFalse(exec("execv", "/dev/null").arg1().toboolean());
        assertFalse(exec("execve", "/proc/self/fd/-1").arg1().toboolean());
    }

    // Linux.execv and execve, beneath Os and Libcore.os, are methods of an object, unlike those of Os
    @Test
    public void execOfLinuxIsRefused() {
        Varargs result = globals.get("run").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("execv"), CoerceJavaToLua.coerce(new Object()), LuaValue.valueOf("/system/bin/sh")}));
        assertTrue(result.arg1().toboolean());
        assertEquals("/dev/null", result.arg(4).tojstring());
    }

    // Like execv of Linux, which searches no PATH (here no relative path exists) and fails for no path, and with the
    // error of a path through a file, of a name too long and of too many links (CommandsBlockedScriptTest runs them)
    @Test
    public void execFailsLikeTheKernel() {
        assertEquals("/proc/self/fd/-1", instead("execv", "sh"));
        assertEquals("/proc/self/fd/-1", instead("execve", ""));
        assertEquals("/dev/null/", instead("execv", "/system/bin/sh/x"));
        assertEquals("/dev/null/", instead(new ProcessBuilder("/system/bin/sh/x")));
        assertEquals("/" + "x".repeat(4095), instead("execv", "/system/bin/long"));
        assertEquals("/proc/self/root".repeat(21), instead(new ProcessBuilder("/system/bin/loop")));
        // A / after a file, which java.io.File drops, also after a program of the app, which then does not run
        for (String program : new String[]{"/system/bin/sh/", LIB + "/libtool.so/"}) {
            assertEquals("/dev/null/", instead("execv", program));
            assertEquals("/dev/null/", instead(new ProcessBuilder(program)));
        }
    }

    // An errno with no program which fails with it gets the exception of the script, with its frames
    @Test
    public void otherErrorsAreMade() {
        Varargs result = exec("execve", "/system/bin/io");
        assertTrue(result.arg1().toboolean());
        assertEquals("execve 5", result.arg(2).tojstring());
        assertEquals("/system/bin/io", result.arg(4).tojstring());
        ProcessBuilder builder = new ProcessBuilder("/system/bin/io").directory(new File("/system"));
        IOException ex = (IOException) start(builder).arg(2).touserdata();
        assertEquals("Cannot run program \"/system/bin/io\" (in directory \"/system\"): error=5, I/O error", ex.getMessage());
        assertEquals("error=5, I/O error", ex.getCause().getMessage());
        assertEquals(Arrays.asList("/system/bin/io"), words(builder));
    }

    @Test
    public void execOfProgramsOfTheAppRuns() {
        Varargs result = exec("execve", LIB + "/libtool.so");
        assertFalse(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
    }

    // A link runs the program it led to when checked, by its path, so changing the link after the check runs nothing else
    @Test
    public void programsOfTheAppRunByTheirPath() {
        Varargs result = exec("execv", LINK);
        assertFalse(result.arg1().toboolean());
        assertEquals(LIB + "/libtool.so", result.arg(4).tojstring());
        ProcessBuilder builder = new ProcessBuilder(LINK, "-v");
        assertFalse(start(builder).arg1().toboolean());
        assertEquals(Arrays.asList(LIB + "/libtool.so", "-v"), words(builder));
    }

    @Test
    public void execOfNoProgramIsLeftToJava() {
        Varargs result = exec("execv", null);
        assertFalse(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
    }

    // ProcessBuilder has command(List) and command(String...): the words stay apart
    @Test
    public void wordsStayApartInTheCopy() {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(Arrays.asList(LIB + "/libtool.so", "-v", "-x")));
        assertFalse(start(builder).arg1().toboolean());
        assertEquals(ArrayList.class, builder.command().getClass());
        assertEquals(Arrays.asList(LIB + "/libtool.so", "-v", "-x"), words(builder));
    }

    // The folder of the app shown to the script, another program for start: start reads the same as the script
    @Test
    public void startReadsWhatTheScriptRead() {
        ProcessBuilder builder = new ProcessBuilder(new Shown(Arrays.asList("/system/bin/sh", "-c", "id"), LIB + "/libtool.so"));
        assertFalse(start(builder).arg1().toboolean());
        assertEquals(Arrays.asList(LIB + "/libtool.so"), words(builder));
    }

    // After the start the builder has the list of the app again, so a change of it counts for the next start
    @Test
    public void appKeepsItsList() {
        List<String> command = new ArrayList<>(Arrays.asList(LIB + "/libtool.so", "-a"));
        ProcessBuilder builder = new ProcessBuilder(command);
        assertFalse(start(builder).arg1().toboolean());
        assertFalse(globals.get("finish").call(CoerceJavaToLua.coerce(builder)).toboolean());
        command.set(1, "-b");
        assertEquals(Arrays.asList(LIB + "/libtool.so", "-b"), words(builder));
        // Os.execv and execve have no builder
        assertFalse(exec("execv", "/system/bin/sh").isnil(1));
        assertFalse(globals.get("finish").call(LuaValue.NIL).toboolean());
    }

    // Start reads the program which fails instead of the one shown to the script
    @Test
    public void shownProgramIsRefused() {
        ProcessBuilder builder = new ProcessBuilder(new Shown(Arrays.asList(LIB + "/libtool.so"), "/system/bin/sh", "-c", "id"));
        assertTrue(start(builder).arg1().toboolean());
        assertEquals(Arrays.asList("/dev/null", "-c", "id"), words(builder));
    }
}
