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

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

// The Lua runtime of the tests of the scripts: a script of the app with the stubs of a test,
// defined in fresh globals like the hook engine does (see XCore.getGlobals)
final class Scripts {
    private Scripts() {
    }

    // Stand-ins of Java values which several tests share: an array as Lua sees it, with its length,
    // and an ApplicationInfo with the fields the scripts read
    static final String ARRAYOF = String.join("\n",
            "function arrayof(...)",
            "  local array = { ... }",
            "  array['length'] = select('#', ...)",
            "  return array",
            "end");
    static final String APPOF = String.join("\n",
            "function appof(packageName, uid, system)",
            "  return { packageName = packageName, uid = uid, system = system }",
            "end");

    static Globals load(String script, String stubs) throws IOException {
        Globals globals = JsePlatform.standardGlobals();
        globals.load(read(script), script).call();
        if (stubs != null)
            globals.load(stubs, "stubs").call();
        return globals;
    }

    // The text of a script of the app, with the line ends of the checkout
    static String read(String script) throws IOException {
        return new String(Files.readAllBytes(new File("src/main/assets/" + script + ".lua").toPath()),
                StandardCharsets.UTF_8);
    }

    // Strings as a Lua list
    static LuaValue strings(String[] values) {
        LuaValue[] lua = new LuaValue[values.length];
        for (int i = 0; i < values.length; i++)
            lua[i] = LuaValue.valueOf(values[i]);
        return LuaValue.listOf(lua);
    }

    // Runs Lua code in the globals and returns what it returns
    static Varargs run(Globals globals, String lua) {
        return globals.load(lua, "test").invoke();
    }
}
