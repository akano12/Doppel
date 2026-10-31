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

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.fail;

// The check of a custom definition before the hook editor saves it or Import stores it, see ActivityHookEdit.check
public class HookCheckTest {
    private static JSONObject definition() throws Exception {
        return new JSONObject()
                .put("collection", "Custom")
                .put("group", "Test")
                .put("name", "Test")
                .put("author", "Test")
                .put("className", "android.os.Build")
                .put("methodName", "getSerial")
                .put("parameterTypes", new JSONArray())
                .put("minSdk", 1)
                .put("luaScript", "function after(hook, param)\n  return false\nend\n");
    }

    @Test
    public void validDefinitionPasses() throws Exception {
        ActivityHookEdit.check(definition());
    }

    // Only built-in definitions resolve '@name', a custom one would compile the text itself in each app
    @Test
    public void scriptReferenceFails() throws Exception {
        assertFails(definition().put("luaScript", "@hardware_files"));
    }

    @Test
    public void scriptWhichDoesNotCompileFails() throws Exception {
        assertFails(definition().put("luaScript", "function after(hook, param"));
    }

    @Test
    public void invalidExcludePatternFails() throws Exception {
        assertFails(definition().put("excludePackages", "["));
    }

    @Test
    public void missingMinSdkFails() throws Exception {
        JSONObject jhook = definition();
        jhook.remove("minSdk");
        assertFails(jhook);
    }

    private static void assertFails(JSONObject jhook) {
        try {
            ActivityHookEdit.check(jhook);
        } catch (Throwable expected) {
            return;
        }
        fail("Accepted " + jhook);
    }
}
