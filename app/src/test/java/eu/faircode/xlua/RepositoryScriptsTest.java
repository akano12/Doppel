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

import org.json.JSONObject;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Scripts of definitions of Downloads (repo/) with Lua stand-ins, like the scripts of the app (see Scripts)
public class RepositoryScriptsTest {
    // The class a script finds records the static methods called, like a class of the app (see XParam.findClass)
    private static final String STUBS = String.join("\n",
            "calls = {}",
            "function hookof(className)",
            "  return { getClassName = function(self) return className end }",
            "end",
            "function paramof(exception)",
            "  return {",
            "    getException = function(self) return exception end,",
            "    findClass = function(self, name)",
            "      local class = {}",
            "      for _, method in ipairs({ 'setOptedOut', 'setPrivacyOptedOut' }) do",
            "        class[method] = function(self, value) table.insert(calls, name .. '.' .. method .. '=' .. tostring(value)) end",
            "      end",
            "      return class",
            "    end }",
            "end");

    private static Globals load(int id) throws Exception {
        String json = new String(Files.readAllBytes(new File("../repo/hook_" + id + ".json").toPath()),
                StandardCharsets.UTF_8);
        Globals globals = JsePlatform.standardGlobals();
        globals.load(new JSONObject(json).getString("luaScript"), "hook_" + id).call();
        globals.load(STUBS, "stubs").call();
        return globals;
    }

    // Localytics opts out right after the app integrates it, of the package of the hooked class,
    // also of its builder and of the former package
    @Test
    public void localyticsOptsOut() throws Exception {
        String[][] cases = {
                {"169", "com.localytics.androidx.Localytics", "com.localytics.androidx.Localytics"},
                {"171", "com.localytics.androidx.Localytics$Builder", "com.localytics.androidx.Localytics"},
                {"81", "com.localytics.android.Localytics", "com.localytics.android.Localytics"}};
        for (String[] c : cases) {
            Globals globals = load(Integer.parseInt(c[0]));
            Varargs result = Scripts.run(globals,
                    "local restricted = after(hookof('" + c[1] + "'), paramof(nil))\n" +
                            "return restricted, table.concat(calls, ' ')");
            assertTrue(c[0], result.arg1().toboolean());
            assertEquals(c[0], c[2] + ".setPrivacyOptedOut=true " + c[2] + ".setOptedOut=true", result.arg(2).tojstring());
        }

        // Not when integrating failed
        Globals globals = load(169);
        Varargs failed = Scripts.run(globals,
                "return after(hookof('com.localytics.androidx.Localytics'), paramof('failed')), #calls");
        assertFalse(failed.arg1().toboolean());
        assertEquals(0, failed.arg(2).toint());
    }
}
