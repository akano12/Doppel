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
            "local absent = { ['/system/bin/nothing'] = true, ['/system/bin/sh/x'] = true }",
            "local links = { ['" + LINK + "'] = '" + LIB + "/libtool.so' }",
            // Os.stat fails like Android for a path through a file or a / after one, all files here
            "local os = { stat = function(self, path)",
            "  if string.find(path, '/system/bin/sh/', 1, true) == 1 or string.sub(path, -1) == '/' then",
            "    error('vm error: android.system.ErrnoException: stat failed: ENOTDIR (Not a directory)', 0)",
            "  end",
            "end }",
            "luajava.bindClass = function(name)",
            "  if name == 'java.io.File' then return 'file' end",
            "  if name == 'android.system.Os' then return os end",
            "  if name == 'android.system.OsConstants' then return { ENOTDIR = 20 } end",
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
            "  return after({}, { getThis = function(self) return builder end })",
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

    // Like Linux_execv and Linux_execve of libcore, each with its own name
    @Test
    public void execIsRefused() {
        for (String method : new String[]{"execv", "execve"}) {
            Varargs result = exec(method, "/system/bin/sh");
            assertTrue(result.arg1().toboolean());
            assertEquals(method + " 13", result.arg(2).tojstring());
            assertEquals("/system/bin/sh", result.arg(3).tojstring());
        }
        assertTrue(exec("execv", "getprop").arg1().toboolean());
        // A program which does not exist, like Android answers
        assertEquals("execv 2", exec("execv", "/system/bin/nothing").arg(2).tojstring());
        assertTrue(exec("execv", LIB + "-x/libtool.so").arg1().toboolean());
    }

    // Linux.execv and execve, beneath Os and Libcore.os, are methods of an object, unlike those of Os
    @Test
    public void execOfLinuxIsRefused() {
        Varargs result = globals.get("run").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("execv"), CoerceJavaToLua.coerce(new Object()), LuaValue.valueOf("/system/bin/sh")}));
        assertTrue(result.arg1().toboolean());
        assertEquals("execv 13", result.arg(2).tojstring());
    }

    // Like execv of Linux, which searches no PATH and fails for no path, and with the error of a path through a file
    @Test
    public void execFailsLikeTheKernel() {
        assertEquals("execv 2", exec("execv", "sh").arg(2).tojstring());
        assertEquals("execve 2", exec("execve", "").arg(2).tojstring());
        assertEquals("execv 20", exec("execv", "/system/bin/sh/x").arg(2).tojstring());
        IOException ex = (IOException) start(new ProcessBuilder("/system/bin/sh/x")).arg(2).touserdata();
        assertEquals("Cannot run program \"/system/bin/sh/x\": error=20, Not a directory", ex.getMessage());
        // The working directory of the builder fails first, here a file
        ex = (IOException) start(new ProcessBuilder("sh").directory(new File("/system/bin/sh"))).arg(2).touserdata();
        assertEquals("Cannot run program \"sh\" (in directory \"/system/bin/sh\"): error=20, Not a directory",
                ex.getMessage());
        // A / after a file, which java.io.File drops, also after a program of the app, which then does not run
        for (String program : new String[]{"/system/bin/sh/", LIB + "/libtool.so/"}) {
            assertEquals("execv 20", exec("execv", program).arg(2).tojstring());
            ex = (IOException) start(new ProcessBuilder(program)).arg(2).touserdata();
            assertEquals("Cannot run program \"" + program + "\": error=20, Not a directory", ex.getMessage());
        }
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

    @Test
    public void shownProgramIsRefused() {
        ProcessBuilder builder = new ProcessBuilder(new Shown(Arrays.asList(LIB + "/libtool.so"), "/system/bin/sh"));
        assertTrue(start(builder).arg1().toboolean());
        assertEquals(Arrays.asList("/system/bin/sh"), words(builder));
    }
}
