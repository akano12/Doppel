/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2017-2019 Marcel Bokhorst (M66B)
 */

package eu.faircode.xlua;

import org.junit.Before;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The script of the content providers (assets/contentresolver_query.lua) with Lua stand-ins,
// like LocationScriptTest: ways around the restrictions of contacts and other providers
public class ContentResolverScriptTest {
    private static final String STUBS = String.join("\n",
            "function urlof(authority, path)",
            "  return { getAuthority = function(self) return authority end,",
            "    getPath = function(self) return path end }",
            "end",
            "function hookof(name)",
            "  return { getName = function(self) return name end }",
            "end",
            // The arguments of ContentResolver.query(uri, projection, selection, args, order, cancel)
            "function paramof(uri, where, args, settings)",
            "  local arguments = { [0] = uri, [2] = where, [3] = args }",
            "  return { arguments = arguments,",
            "    getArgument = function(self, index) return arguments[index] end,",
            "    setArgument = function(self, index, value) arguments[index] = value end,",
            "    getSetting = function(self, name) return settings[name] end }",
            "end");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = JsePlatform.standardGlobals();
        String script = new String(Files.readAllBytes(new File("src/main/assets/contentresolver_query.lua").toPath()),
                StandardCharsets.UTF_8);
        globals.load(script, "contentresolver_query").call();
        globals.load(STUBS, "stubs").call();
    }

    private boolean contained(String where) {
        return globals.get("contained").call(LuaValue.valueOf(where)).toboolean();
    }

    @Test
    public void selectionsWithinParentheses() {
        assertTrue(contained("display_name = ?"));
        assertTrue(contained("(a = 1) OR (b = 2)"));
        assertTrue(contained("name = 'it''s (not) closed)'"));
        assertTrue(contained("\"weird)name\" = 1"));

        assertFalse(contained("1) OR (1"));
        assertFalse(contained("a = 1)"));
        assertFalse(contained("(a = 1"));
        assertFalse(contained("a = 'open"));
        assertFalse(contained("a = 1; DROP TABLE x"));
        assertFalse(contained("a = 1 --"));
        assertFalse(contained("a = 1 /* x */"));
    }

    @Test
    public void authorityWithoutUser() {
        assertEquals("com.android.contacts", globals.load(
                "return authorityof(urlof('0@com.android.contacts', '/contacts'))", "test").call().tojstring());
        assertEquals("contacts", globals.load(
                "return authorityof(urlof('contacts', '/people'))", "test").call().tojstring());
    }

    private Varargs starred(String authority, String where) {
        return globals.load(
                "local param = paramof(urlof('" + authority + "', '/contacts'), " + where + ", 'args', " +
                        "{ ['contacts.starred'] = '1' })\n" +
                        "before(hookof('ContentResolver.query16/contacts'), param)\n" +
                        "return param.arguments[2], param.arguments[3]", "test").invoke();
    }

    @Test
    public void starredFilterApplies() {
        assertEquals("(starred = 1)", starred("com.android.contacts", "nil").arg(1).tojstring());
        assertEquals("(starred = 1) AND (display_name = ?)",
                starred("com.android.contacts", "'display_name = ?'").arg(1).tojstring());
        // Also with the user in front of the authority
        assertEquals("(starred = 1) AND (display_name = ?)",
                starred("0@com.android.contacts", "'display_name = ?'").arg(1).tojstring());
    }

    // A selection like 1) OR (1 would select all contacts, it selects none
    @Test
    public void starredFilterCannotBeUndone() {
        Varargs result = starred("com.android.contacts", "'1) OR (1'");
        assertEquals("0", result.arg(1).tojstring());
        assertTrue(result.arg(2).isnil());
    }
}
