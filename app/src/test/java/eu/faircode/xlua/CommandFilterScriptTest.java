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
import org.luaj.vm2.lib.TwoArgFunction;

import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The command filter of the shell commands script (variant commands, group Run.Commands) with Lua stand-ins for
// the hooks, the builder and the parameter, like ShellCommandsScriptTest
public class CommandFilterScriptTest {
    private static final String STUBS = String.join("\n",
            "function listof(items)",
            "  return { toArray = function(self) return arrayof(items) end }",
            "end",
            "function arrayof(items)",
            "  local array = { length = #items }",
            "  for index = 1, #items do array[index] = items[index] end",
            "  return array",
            "end",
            // The regular expressions of Java (see javamatches) and exceptions with their message
            "luajava = {",
            "  bindClass = function(name)",
            "    if name == 'java.util.regex.Pattern' then",
            "      return { matches = function(self, regex, text) return javamatches(regex, text) end }",
            "    end",
            "    assert(name == 'java.io.IOException', name)",
            "    return name",
            "  end,",
            "  new = function(class, message) return { class = class, message = message } end }",
            // ProcessBuilder.start with the command of the builder and the original command of the app, if another
            // variant changed it: whether the hook restricted, the exception and what it logs
            "function startof(words, settings, original)",
            "  local thrown",
            "  local builder = { command = function(self) return listof(words) end }",
            "  local param = {",
            "    getSetting = function(self, name) return settings[name] end,",
            "    getThis = function(self) return builder end,",
            "    getValue = function(self, name, scope)",
            "      return (name == 'shell.command' and scope == builder and original) and listof(original) or nil",
            "    end,",
            "    setResult = function(self, value) thrown = value end }",
            "  local hook = { getName = function(self) return 'ProcessBuilder.start/commands' end }",
            "  local restricted, line = before(hook, param)",
            "  return restricted, thrown and thrown.message, line",
            "end");

    private Globals globals;
    private String allowed;
    private String regex;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("shell_commands", STUBS);
        globals.set("javamatches", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue regex, LuaValue text) {
                return LuaValue.valueOf(Pattern.matches(regex.tojstring(), text.tojstring()));
            }
        });
        allowed = "getprop, grep,uname";
        regex = null;
    }

    @Test
    public void allowedProgramsRun() {
        for (String[] words : new String[][]{
                {"getprop", "ro.product.model"},
                {"/system/bin/getprop"},
                {"toybox", "uname", "-a"},
                {"env", "A=1", "-i", "getprop"},
                {"sh", "-c", "getprop ro.serialno"},
                {"/system/bin/sh", "-ec", "getprop | grep model; echo done"},
                {"sh", "-c", "if [ -x /system/bin/getprop ]; then\n  x=$(getprop a)\n  echo \"$x\"\nfi"},
                {"sh", "-c", "for p in a b; do getprop \"$p\"; done"},
                {"sh", "-c", "exec /system/bin/getprop x"},
                {"sh", "-c", "eval \"getprop x\""},
                {"sh", "-c", "A=1 getprop x 2>/dev/null"}})
            assertFalse(String.join(" ", words), refused(words));
    }

    @Test
    public void otherProgramsAreRefused() {
        for (String[] words : new String[][]{
                {"id"},
                {"su"},
                {"su", "-c", "getprop"},
                {"/system/xbin/which", "su"},
                {"toybox", "id"},
                {"env", "id"},
                {"sh"},
                {"sh", "-c"},
                {"sh", "/data/local/tmp/script.sh"},
                {"sh", "-c", "getprop a; id"},
                {"sh", "-c", "getprop a && cat /proc/cpuinfo"},
                {"sh", "-c", "getprop a | sed s/a/b/"},
                {"sh", "-c", "x=$(id)"},
                {"sh", "-c", "echo `id`"},
                {"sh", "-c", "exec id"},
                {"sh", "-c", "command -v su"},
                {"sh", "-c", "nohup nice -n 5 id"},
                {"sh", "-c", "busybox id"},
                {"sh", "-c", "$X -a"},
                {"sh", "-c", "\"$dir\"/bin/getprop"},
                {"sh", "-c", "sh -c 'getprop x'"},
                {"sh", "-c", "eval \"id\""},
                {"sh", "-c", "if true; then\n id\nfi"},
                // An assignment in front, also with $ or quotes in its value
                {"sh", "-c", "A=$HOME id"},
                {"sh", "-c", "LC_ALL=\"C\" su -c id"},
                {"sh", "-c", "X='a b' id"}})
            assertTrue(String.join(" ", words), refused(words));
    }

    // Refused commands fail like a program which cannot be run, Activity shows the command
    @Test
    public void refusedCommandsFail() {
        Varargs result = start(new String[]{"su", "-c", "id"}, null);
        assertTrue(result.arg(1).toboolean());
        assertEquals("Cannot run program \"su\": error=13, Permission denied", result.arg(2).tojstring());
        assertEquals("su -c id", result.arg(3).tojstring());

        // Without allowed commands, nothing runs
        allowed = null;
        assertTrue(refused("getprop"));
        // A program allowed by name, like su
        allowed = "su";
        assertFalse(refused("su", "-c", "id"));
        assertFalse(refused("su"));
    }

    // Whole commands matching the regular expression run too; an invalid one allows nothing
    @Test
    public void regexAllowsCommands() {
        regex = "logcat -d( .*)?";
        assertFalse(refused("logcat", "-d", "-v", "time"));
        assertTrue(refused("logcat"));
        regex = "(";
        assertTrue(refused("logcat", "-d"));
        assertFalse(refused("getprop"));
    }

    // The command of the app is checked, not the one the hooks of other variants made of it
    @Test
    public void originalCommandIsChecked() {
        String[] fake = {"/system/bin/sh", "-c", "__xlua_property() { id; }\ngetprop \"$@\"", "getprop"};
        assertFalse(start(fake, new String[]{"getprop"}).arg(1).toboolean());
        assertTrue(start(new String[]{"getprop"}, new String[]{"id"}).arg(1).toboolean());
    }

    // Lua to Java

    private boolean refused(String... words) {
        return start(words, null).arg(1).toboolean();
    }

    private Varargs start(String[] words, String[] original) {
        return globals.get("startof").invoke(LuaValue.varargsOf(new LuaValue[]{
                Scripts.strings(words), settings(), original == null ? LuaValue.NIL : Scripts.strings(original)}));
    }

    private LuaValue settings() {
        LuaValue settings = LuaValue.tableOf();
        if (allowed != null)
            settings.set("commands.allowed", allowed);
        if (regex != null)
            settings.set("commands.regex", regex);
        return settings;
    }
}
