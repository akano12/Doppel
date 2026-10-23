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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The script of the content providers (assets/contentresolver_query.lua) with Lua stand-ins,
// like LocationScriptTest: ways around the restrictions of contacts and other providers;
// cursors are stand-ins too, because luajava cannot make java.base classes accessible on a desktop JVM
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
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value end,",
            "    getSetting = function(self, name) return settings[name] end }",
            "end",
            // A cursor of a provider with rows of strings, and a MatrixCursor, as Lua sees them
            "function cursorof(columns, rows)",
            "  local cursor = { columns = columns, rows = rows, position = -1, closed = false }",
            "  function cursor:getColumnNames() return self.columns end",
            "  function cursor:getColumnCount() return #self.columns end",
            "  function cursor:moveToNext() self.position = self.position + 1 return self.position < #self.rows end",
            "  function cursor:moveToPosition(position) self.position = position return true end",
            "  function cursor:getString(index) return self.rows[self.position + 1][index + 1] end",
            "  function cursor:close() self.closed = true end",
            "  return cursor",
            "end",
            "luajava = { newInstance = function(name, columns)",
            "  assert(name == 'android.database.MatrixCursor', name)",
            "  local matrix = cursorof(columns, {})",
            "  function matrix:newRow()",
            "    local row = {}",
            "    table.insert(self.rows, row)",
            "    return { add = function(self, value) table.insert(row, value) end }",
            "  end",
            "  return matrix",
            "end }");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("contentresolver_query", STUBS);
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

    // The query of a provider with the cursor it returned: whether restricted, whether the cursor stays
    // and whether it was closed
    private Varargs query(String hook, String authority, String path, String settings) {
        return globals.load(
                "local hook = hookof('" + hook + "')\n" +
                        "local param = paramof(urlof('" + authority + "', '" + path + "'), nil, nil, " + settings + ")\n" +
                        "local cursor = cursorof({ '_id', 'display_name' }, { { '1', 'Alice' } })\n" +
                        "before(hook, param)\n" +
                        "param.result = cursor\n" +
                        "local restricted = after(hook, param)\n" +
                        "return restricted, param.result == cursor, cursor.closed, param.result:moveToNext()", "test").invoke();
    }

    // after keeps the result which before filtered for starred contacts
    @Test
    public void starredResultStays() {
        Varargs result = query("ContentResolver.query16/contacts", "com.android.contacts", "/contacts",
                "{ ['contacts.starred'] = '1' }");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
    }

    // Other results are replaced by an empty cursor, the cursor of the provider is closed
    @Test
    public void resultIsReplaced() {
        for (String[] query : new String[][]{
                {"ContentResolver.query16/contacts", "com.android.contacts", "/contacts"},
                {"ContentResolver.query16/contacts", "0@com.android.contacts", "/data/phones"},
                {"ContentProviderClient.query26/contacts", "com.android.simphonebook", "/subid/1/adn"},
                {"ContentResolver.query26/mmssms", "mms", "/part"}}) {
            Varargs result = query(query[0], query[1], query[2], "{}");
            assertTrue(query[1], result.arg(1).toboolean());
            assertFalse(query[1], result.arg(2).toboolean());
            assertTrue(query[1], result.arg(3).toboolean());
            assertFalse(query[1], result.arg(4).toboolean());
        }

        // The provider status tells whether there are contacts at all
        Varargs status = query("ContentResolver.query16/contacts", "com.android.contacts", "/provider_status", "{}");
        assertFalse(status.arg(1).toboolean());
        assertTrue(status.arg(2).toboolean());
    }

    // Gservices without the GSF id, also for the keys starting with a prefix
    @Test
    public void gservicesWithoutGsfId() {
        Varargs result = globals.load(
                "local hook = hookof('ContentResolver.query26/gsf_id')\n" +
                        "local param = paramof(urlof('com.google.android.gsf.gservices', '/prefix'))\n" +
                        "local cursor = cursorof({ 'key', 'value' },\n" +
                        "  { { 'android_id', '1234567890' }, { 'android_other', 'value' } })\n" +
                        "param.result = cursor\n" +
                        "local restricted = after(hook, param)\n" +
                        "return restricted, cursor.closed, #param.result.rows, param.result.rows[1][1]", "test").invoke();
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(1, result.arg(3).toint());
        assertEquals("android_other", result.arg(4).tojstring());

        // Other keys stay as they are, with the cursor at the start
        Varargs other = globals.load(
                "local hook = hookof('ContentResolver.query26/gsf_id')\n" +
                        "local param = paramof(urlof('com.google.android.gsf.gservices', '/'))\n" +
                        "local cursor = cursorof({ 'key', 'value' }, { { 'android_other', 'value' } })\n" +
                        "param.result = cursor\n" +
                        "local restricted = after(hook, param)\n" +
                        "return restricted, param.result == cursor, cursor.position, cursor.closed", "test").invoke();
        assertFalse(other.arg(1).toboolean());
        assertTrue(other.arg(2).toboolean());
        assertEquals(-1, other.arg(3).toint());
        assertFalse(other.arg(4).toboolean());
    }
}
