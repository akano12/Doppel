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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// The script of the content providers (assets/contentresolver_query.lua) with Lua stand-ins,
// like LocationScriptTest: ways around the restrictions of contacts and other providers;
// cursors are stand-ins too, because the Android classes are stubs in unit tests
public class ContentResolverScriptTest {
    private static final String STUBS = String.join("\n",
            // The parameters of the query of a uri by name, a list for one which is there more than once
            "function urlof(authority, path, query)",
            "  query = query or {}",
            "  return { getAuthority = function(self) return authority end,",
            "    getPath = function(self) return path end,",
            "    getQueryParameter = function(self, name)",
            "      local value = query[name]",
            "      if type(value) == 'table' then return value[1] end",
            "      return value",
            "    end,",
            "    getQueryParameters = function(self, name)",
            "      local values = query[name] or {}",
            "      if type(values) ~= 'table' then values = { values } end",
            "      return { size = function(self) return #values end, get = function(self, index) return values[index + 1] end }",
            "    end }",
            "end",
            "function hookof(name)",
            "  return { getName = function(self) return name end,",
            "    getMethodName = function(self) return string.match(name, '([^./]+)/') end }",
            "end",
            // The query arguments in a Bundle, as ContentResolver.query(uri, projection, queryArgs, cancel) has them
            "function bundleof(where, args)",
            "  local values = { ['android:query-arg-sql-selection'] = where,",
            "    ['android:query-arg-sql-selection-args'] = args }",
            "  return { values = values,",
            "    getString = function(self, key) return values[key] end,",
            "    getStringArray = function(self, key) return values[key] end,",
            "    containsKey = function(self, key) return values[key] ~= nil end,",
            "    putString = function(self, key, value) values[key] = value end,",
            "    putStringArray = function(self, key, value) values[key] = value end,",
            "    putIntArray = function(self, key, value) values[key] = value end,",
            "    remove = function(self, key) values[key] = nil end }",
            "end",
            "function paramof(uri, where, args, settings)",
            "  local arguments = { [0] = uri, [2] = bundleof(where, args) }",
            "  return { arguments = arguments,",
            "    getArgument = function(self, index) return arguments[index] end,",
            "    setArgument = function(self, index, value) arguments[index] = value end,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value end,",
            "    getSetting = function(self, name) return settings[name] end,",
            "    getThis = function(self) return self.this end,",
            "    unhooked = function(self, run) return run() end,",
            // Like XParam.uuidOf: hex which looks random, the same for the same seed
            "    uuidOf = function(self, seed)",
            "      local hash = 7",
            "      for index = 1, #seed do hash = (hash * 31 + string.byte(seed, index)) % 4294967296 end",
            "      return string.format('%08x-0000-4000-8000-000000000000', hash)",
            "    end }",
            "end",
            // A cursor of a provider with rows of strings, and a MatrixCursor, as Lua sees them
            "function cursorof(columns, rows)",
            "  local cursor = { columns = columns, rows = rows, position = -1, closed = false }",
            "  function cursor:getColumnNames() return self.columns end",
            "  function cursor:getColumnCount() return #self.columns end",
            "  function cursor:getCount() return #self.rows end",
            "  function cursor:getColumnIndex(name)",
            "    for index = 1, #self.columns do if self.columns[index] == name then return index - 1 end end",
            "    return -1",
            "  end",
            "  function cursor:moveToNext() self.position = self.position + 1 return self.position < #self.rows end",
            "  function cursor:moveToPosition(position) self.position = position return true end",
            "  function cursor:getString(index) return self.rows[self.position + 1][index + 1] end",
            // Types like Cursor.getType: 0 null, 1 integer, 3 string
            "  function cursor:getType(index)",
            "    local value = self.rows[self.position + 1][index + 1]",
            "    if value == nil then return 0 elseif type(value) == 'number' then return 1 else return 3 end",
            "  end",
            "  function cursor:getLong(index) return self.rows[self.position + 1][index + 1] end",
            "  function cursor:close() self.closed = true end",
            // The extras of the provider, nil for none, and those a MatrixCursor was given
            "  function cursor:getExtras() return self.extras end",
            "  function cursor:setExtras(extras) self.extras = extras end",
            "  return cursor",
            "end",
            // A new Bundle, also a copy of one, and a MatrixCursor
            "luajava = { newInstance = function(name, columns)",
            "  if name == 'android.os.Bundle' then",
            "    local copy = bundleof()",
            "    if columns ~= nil then for key, value in pairs(columns.values) do copy.values[key] = value end end",
            "    return copy",
            "  end",
            "  if name == 'java.lang.Long' or name == 'java.lang.Double' then return columns end",
            "  assert(name == 'android.database.MatrixCursor', name)",
            "  local matrix = cursorof(columns, {})",
            "  function matrix:newRow()",
            "    local row = {}",
            "    table.insert(self.rows, row)",
            // By position, so a null value keeps its column like MatrixCursor.RowBuilder.add(null)
            "    local count = 0",
            "    return { add = function(self, value) count = count + 1 row[count] = value end }",
            "  end",
            "  return matrix",
            "end }",
            // Arrays of Java made by java.lang.reflect.Array, with their length
            "luajava.bindClass = function(name)",
            "  return { newInstance = function(self, class, length) return { length = length } end }",
            "end");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("contentresolver_query", Scripts.ARRAYOF + "\n" + STUBS);
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

        // Subqueries, which could tell the contacts which are not starred, also in another case; quoted text and
        // names which contain the word stay
        assertFalse(contained("(SELECT count(*) FROM raw_contacts WHERE starred=0) > 3"));
        assertFalse(contained("a IN (select _id FROM x)"));
        assertFalse(contained("a=(\tSeLeCt\n1)"));
        assertTrue(contained("name = 'select' AND \"select\" = 1"));
        assertTrue(contained("selected = 1 AND unselect_time > 0"));
    }

    // A sort order with a subquery selects nothing and is left out, like such a selection
    @Test
    public void sortOrderWithSubquery() {
        Varargs result = Scripts.run(globals,
                "local param = paramof(urlof('com.android.contacts', '/contacts'), 'display_name = ?', 'args', " +
                        "{ ['contacts.starred'] = '1' })\n" +
                        "param.arguments[2].values['android:query-arg-sql-sort-order'] = " +
                        "'(SELECT count(*) FROM raw_contacts WHERE starred=0)'\n" +
                        "local safe = paramof(urlof('com.android.contacts', '/contacts'), nil, nil, " +
                        "{ ['contacts.starred'] = '1' })\n" +
                        "safe.arguments[2].values['android:query-arg-sql-sort-order'] = 'display_name COLLATE LOCALIZED ASC'\n" +
                        "local hook = hookof('ContentResolver.query26/contacts')\n" +
                        "before(hook, param)\n" +
                        "before(hook, safe)\n" +
                        "local values = param.arguments[2].values\n" +
                        "return values['android:query-arg-sql-selection'], values['android:query-arg-sql-sort-order'],\n" +
                        "  values['android:query-arg-sql-selection-args'],\n" +
                        "  safe.arguments[2].values['android:query-arg-sql-sort-order'],\n" +
                        "  safe.arguments[2].values['android:query-arg-sql-selection']");
        assertEquals("0", result.arg(1).tojstring());
        assertTrue(result.arg(2).isnil());
        assertTrue(result.arg(3).isnil());
        assertEquals("display_name COLLATE LOCALIZED ASC", result.arg(4).tojstring());
        assertEquals("(starred = 1)", result.arg(5).tojstring());
    }

    // Likewise for the sort columns, of which the provider makes the sort order when there is none
    @Test
    public void sortColumnsWithSubquery() {
        Varargs result = Scripts.run(globals,
                "local param = paramof(urlof('com.android.contacts', '/contacts'), nil, nil, { ['contacts.starred'] = '1' })\n" +
                        "param.arguments[2].values['android:query-arg-sort-columns'] = " +
                        "{ 'display_name', '(SELECT count(*) FROM raw_contacts WHERE starred=0)', length = 2 }\n" +
                        "local safe = paramof(urlof('com.android.contacts', '/contacts'), nil, nil, { ['contacts.starred'] = '1' })\n" +
                        "safe.arguments[2].values['android:query-arg-sort-columns'] = { 'display_name', '_id', length = 2 }\n" +
                        "local hook = hookof('ContentResolver.query26/contacts')\n" +
                        "before(hook, param)\n" +
                        "before(hook, safe)\n" +
                        "return param.arguments[2].values['android:query-arg-sql-selection'],\n" +
                        "  param.arguments[2].values['android:query-arg-sort-columns'],\n" +
                        "  safe.arguments[2].values['android:query-arg-sql-selection'],\n" +
                        "  safe.arguments[2].values['android:query-arg-sort-columns'] ~= nil");
        assertEquals("0", result.arg(1).tojstring());
        assertTrue(result.arg(2).isnil());
        assertEquals("(starred = 1)", result.arg(3).tojstring());
        assertTrue(result.arg(4).toboolean());
    }

    // Another value of the setting filters nothing, like in contentresolver_open: the contacts are empty
    @Test
    public void starredSettingOtherValue() {
        Varargs result = query("ContentResolver.query26/contacts", "com.android.contacts", "/contacts",
                "{ ['contacts.starred'] = 'yes' }");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertTrue(Scripts.run(globals, "local param = paramof(urlof('com.android.contacts', '/contacts'), nil, nil, " +
                "{ ['contacts.starred'] = 'yes' })\nbefore(hookof('ContentResolver.query26/contacts'), param)\n" +
                "return param.arguments[2].values['android:query-arg-sql-selection']").arg1().isnil());
    }

    @Test
    public void authorityWithoutUser() {
        assertEquals("com.android.contacts", globals.load(
                "return authorityof(urlof('0@com.android.contacts', '/contacts'))", "test").call().tojstring());
        assertEquals("contacts", globals.load(
                "return authorityof(urlof('contacts', '/people'))", "test").call().tojstring());
    }

    private Varargs starred(String authority, String where) {
        return Scripts.run(globals,
                "local param = paramof(urlof('" + authority + "', '/contacts'), " + where + ", 'args', " +
                        "{ ['contacts.starred'] = '1' })\n" +
                        "before(hookof('ContentResolver.query26/contacts'), param)\n" +
                        "return param.arguments[2]:getString('android:query-arg-sql-selection'),\n" +
                        "  param.arguments[2].values['android:query-arg-sql-selection-args']");
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

    // The Bundle of the app stays as it was, so the app cannot read the filter back
    // and a query again with the same Bundle does not filter the filter
    @Test
    public void starredFilterLeavesBundleOfApp() {
        Varargs result = Scripts.run(globals,
                "local param = paramof(urlof('com.android.contacts', '/contacts'), 'display_name LIKE ?', 'args', " +
                        "{ ['contacts.starred'] = '1' })\n" +
                        "local bundle = param.arguments[2]\n" +
                        "local hook = hookof('ContentResolver.query26/contacts')\n" +
                        "before(hook, param)\n" +
                        "param.arguments[2] = bundle\n" +
                        "before(hook, param)\n" +
                        "return bundle:getString('android:query-arg-sql-selection'),\n" +
                        "  param.arguments[2]:getString('android:query-arg-sql-selection')");
        assertEquals("display_name LIKE ?", result.arg(1).tojstring());
        assertEquals("(starred = 1) AND (display_name LIKE ?)", result.arg(2).tojstring());

        // Also its arguments, which a selection which could undo the filter leaves out
        Varargs unsafe = Scripts.run(globals,
                "local param = paramof(urlof('com.android.contacts', '/contacts'), '1) OR (1', 'args', " +
                        "{ ['contacts.starred'] = '1' })\n" +
                        "local bundle = param.arguments[2]\n" +
                        "before(hookof('ContentResolver.query26/contacts'), param)\n" +
                        "return bundle.values['android:query-arg-sql-selection-args']");
        assertEquals("args", unsafe.arg(1).tojstring());
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
        return Scripts.run(globals,
                "local hook = hookof('" + hook + "')\n" +
                        "local param = paramof(urlof('" + authority + "', '" + path + "'), nil, nil, " + settings + ")\n" +
                        "local cursor = cursorof({ '_id', 'display_name' }, { { '1', 'Alice' } })\n" +
                        "before(hook, param)\n" +
                        "param.result = cursor\n" +
                        "local restricted = after(hook, param)\n" +
                        "return restricted, param.result == cursor, cursor.closed, param.result:moveToNext()");
    }

    // after keeps the result which before filtered for starred contacts
    @Test
    public void starredResultStays() {
        Varargs result = query("ContentResolver.query26/contacts", "com.android.contacts", "/contacts",
                "{ ['contacts.starred'] = '1' }");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());

        // Also of the entities of the raw contacts, whose view has the column starred too
        Varargs entities = query("ContentResolver.query26/contacts", "com.android.contacts", "/raw_contact_entities",
                "{ ['contacts.starred'] = '1' }");
        assertTrue(entities.arg(1).toboolean());
        assertTrue(entities.arg(2).toboolean());
        assertEquals("(starred = 1)", Scripts.run(globals,
                "local param = paramof(urlof('com.android.contacts', '/raw_contact_entities'), nil, nil, " +
                        "{ ['contacts.starred'] = '1' })\nbefore(hookof('ContentResolver.query26/contacts'), param)\n" +
                        "return param.arguments[2].values['android:query-arg-sql-selection']").arg1().tojstring());
    }

    // A query of a remote directory, which the provider passes on to the provider of the directory, which may ignore the
    // selection, is replaced and not filtered; the default and the invisible directory, also of the work profile, are
    // the tables of the provider, which are filtered
    @Test
    public void starredRemoteDirectory() {
        String run = "local hook = hookof('ContentResolver.query26/contacts')\n" +
                "local param = paramof(urlof('com.android.contacts', '/contacts/filter/a', { directory = ...}), nil, nil, " +
                "{ ['contacts.starred'] = '1' })\n" +
                "before(hook, param)\n" +
                "local cursor = cursorof({ '_id' }, { { '1' } })\n" +
                "param.result = cursor\n" +
                "local restricted = after(hook, param)\n" +
                "return tostring(restricted) .. ',' .. tostring(param.result == cursor) .. ',' .. tostring(cursor.closed) ..\n" +
                "  ',' .. tostring(param.arguments[2].values['android:query-arg-sql-selection'])";
        for (String directory : new String[]{"5", "1000000002", ""})
            assertEquals(directory, "true,false,true,nil",
                    globals.load(run, "test").call(LuaValue.valueOf(directory)).tojstring());
        for (String directory : new String[]{"0", "1", "1000000000", "1000000001"})
            assertEquals(directory, "true,true,false,(starred = 1)",
                    globals.load(run, "test").call(LuaValue.valueOf(directory)).tojstring());
        assertEquals("true,true,false,(starred = 1)", globals.load(run, "test").call(LuaValue.NIL).tojstring());
    }

    // The phone lookup ignores the selection, so the column starred is added to the projection and the rows are filtered
    // by it; the provider adds the number after it when the projection has none, which stays
    @Test
    public void starredPhoneLookup() {
        Varargs result = Scripts.run(globals,
                "local hook = hookof('ContentResolver.query26/contacts')\n" +
                        "local param = paramof(urlof('com.android.contacts', '/phone_lookup/5551234'), nil, nil, " +
                        "{ ['contacts.starred'] = '1' })\n" +
                        "param.arguments[1] = arrayof('display_name', '_id')\n" +
                        "before(hook, param)\n" +
                        "local projection = param.arguments[1]\n" +
                        "local cursor = cursorof(arrayof('display_name', '_id', 'starred', 'number'),\n" +
                        "  { { 'Alice', 1, '1', '5551234' }, { 'Bob', 2, '0', '5551234' } })\n" +
                        "param.result = cursor\n" +
                        "local restricted = after(hook, param)\n" +
                        "local fake = param.result\n" +
                        "return restricted, cursor.closed, projection.length, projection[3],\n" +
                        "  param.arguments[2].values['android:query-arg-sql-selection'] == nil,\n" +
                        "  fake.columns.length, fake.columns[3], #fake.rows, fake.rows[1][1], fake.rows[1][2], fake.rows[1][3]");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(3, result.arg(3).toint());
        assertEquals("starred", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertEquals(3, result.arg(6).toint());
        assertEquals("number", result.arg(7).tojstring());
        assertEquals(1, result.arg(8).toint());
        assertEquals("Alice", result.arg(9).tojstring());
        // The values keep their types
        assertTrue(result.arg(10).isnumber());
        assertEquals("5551234", result.arg(11).tojstring());

        // Without a projection the provider gives all columns, starred too, which stays
        Varargs all = Scripts.run(globals,
                "local hook = hookof('ContentResolver.query26/contacts')\n" +
                        "local param = paramof(urlof('com.android.contacts', '/phone_lookup_enterprise/5551234'), nil, nil, " +
                        "{ ['contacts.starred'] = '0' })\n" +
                        "before(hook, param)\n" +
                        "param.result = cursorof(arrayof('display_name', 'starred'), { { 'Alice', '1' }, { 'Bob', '0' } })\n" +
                        "local restricted = after(hook, param)\n" +
                        "return restricted, param.result.columns.length, #param.result.rows, param.result.rows[1][1]");
        assertTrue(all.arg(1).toboolean());
        assertEquals(2, all.arg(2).toint());
        assertEquals(1, all.arg(3).toint());
        assertEquals("Bob", all.arg(4).tojstring());
    }

    // Other results are replaced by an empty cursor, the cursor of the provider is closed
    @Test
    public void resultIsReplaced() {
        for (String[] query : new String[][]{
                {"ContentResolver.query26/contacts", "com.android.contacts", "/contacts"},
                {"ContentResolver.query26/contacts", "0@com.android.contacts", "/data/phones"},
                {"ContentProviderClient.query26/contacts", "com.android.simphonebook", "/subid/1/adn"},
                {"ContentResolver.query26/mmssms", "mms", "/part"},
                {"ContentProviderClient.query26/calendars", "com.android.calendar", "/events"},
                // The places which callers sent with calls (CallLog.Locations), of the default dialer
                {"ContentResolver.query26/call_log", "call_composer_locations", "/1"},
                // The OAID providers of Huawei (HMS, the SDK falls back to it) and Xiaomi
                {"ContentResolver.query26/oaid", "com.huawei.hwid.pps.apiprovider", "/oaid/query"},
                {"ContentProviderClient.query26/oaid", "0@com.miui.idprovider", "/oaid"}}) {
            Varargs result = query(query[0], query[1], query[2], "{}");
            assertTrue(query[1], result.arg(1).toboolean());
            assertFalse(query[1], result.arg(2).toboolean());
            assertTrue(query[1], result.arg(3).toboolean());
            assertFalse(query[1], result.arg(4).toboolean());
        }

        // The provider status tells whether there are contacts at all
        Varargs status = query("ContentResolver.query26/contacts", "com.android.contacts", "/provider_status", "{}");
        assertFalse(status.arg(1).toboolean());
        assertTrue(status.arg(2).toboolean());

        // The directories of contacts, which a device always has, stay too, also in starred mode
        for (String[] query : new String[][]{
                {"ContentResolver.query26/contacts", "com.android.contacts", "/directories"},
                {"ContentProviderClient.query26/contacts", "0@com.android.contacts", "/directories_enterprise/1"}}) {
            Varargs kept = query(query[0], query[1], query[2], "{ ['contacts.starred'] = '1' }");
            assertFalse(query[2], kept.arg(1).toboolean());
            assertTrue(query[2], kept.arg(2).toboolean());
            assertFalse(query[2], kept.arg(3).toboolean());
        }
    }

    // The time zone properties of the calendar provider, which a device always has, stay, but in auto mode its zones of
    // the instances read as the fake zone of Read.Timezone, the zone property read hooked when it is not the one read
    // unhooked (America/Chicago), also of a query of one key or without the column key; the mode and the zones come
    // from the provider (America/New_York). The default zone of the app, which it may have set itself, is another one
    // and not used.
    // As a string of the rows, or kept.
    private String properties(String type, String zone, String projection, String rows) {
        Varargs result = Scripts.run(globals,
                "local bind = luajava.bindClass\n" +
                        "local inside = false\n" +
                        "luajava.bindClass = function(name)\n" +
                        "  if name == 'java.util.TimeZone' then\n" +
                        "    return { getDefault = function(self)\n" +
                        "      return { getID = function(self) return 'Pacific/Chatham' end } end }\n" +
                        "  elseif name == 'android.os.SystemProperties' then\n" +
                        "    return { get = function(self, key)\n" +
                        "      assert(key == 'persist.sys.timezone')\n" +
                        "      if inside then return 'America/Chicago' end\n" +
                        "      return '" + zone + "' end }\n" +
                        "  end\n" +
                        "  return bind(name)\n" +
                        "end\n" +
                        "local hook = hookof('ContentProviderClient.query26/calendars')\n" +
                        "local param = paramof(urlof('0@com.android.calendar', '/properties'), nil, nil, {})\n" +
                        "param.unhooked = function(self, run) inside = true local value = run() inside = false return value end\n" +
                        "local real = { { 'timezoneDatabaseVersion', '2024a' }, { 'timezoneType', '" + type + "' },\n" +
                        "  { 'timezoneInstances', 'America/New_York' }, { 'timezoneInstancesPrevious', 'Asia/Tokyo' } }\n" +
                        "local queried = nil\n" +
                        "param.this = { query = function(self, uri, ...) queried = uri return cursorof({ 'key', 'value' }, real) end }\n" +
                        "local cursor = cursorof(arrayof(" + projection + "), " + rows + ")\n" +
                        "param.result = cursor\n" +
                        "local restricted = after(hook, param)\n" +
                        "assert(queried == param.arguments[0])\n" +
                        "if not restricted then\n" +
                        "  assert(param.result == cursor and not cursor.closed and cursor.position == -1)\n" +
                        "  return 'kept'\n" +
                        "end\n" +
                        "assert(cursor.closed and param.result ~= cursor)\n" +
                        "local values = {}\n" +
                        "for _, row in ipairs(param.result.rows) do\n" +
                        "  local cells = {}\n" +
                        "  for index = 1, param.result.columns.length do cells[index] = tostring(row[index]) end\n" +
                        "  table.insert(values, table.concat(cells, '='))\n" +
                        "end\n" +
                        "return table.concat(values, ',')");
        return result.arg1().tojstring();
    }

    @Test
    public void calendarPropertiesOfFakeZone() {
        String all = "{ { 'timezoneDatabaseVersion', '2024a' }, { 'timezoneType', 'auto' }, { 'timezoneInstances', " +
                "'America/New_York' }, { 'timezoneInstancesPrevious', 'Asia/Tokyo' } }";
        assertEquals("timezoneDatabaseVersion=2024a,timezoneType=auto,timezoneInstances=Europe/Amsterdam," +
                "timezoneInstancesPrevious=Europe/Amsterdam", properties("auto", "Europe/Amsterdam", "'key', 'value'", all));
        // One key, key=?, the way the Calendar app reads them, also without the column key
        assertEquals("timezoneInstances=Europe/Amsterdam", properties("auto", "Europe/Amsterdam", "'key', 'value'",
                "{ { 'timezoneInstances', 'America/New_York' } }"));
        assertEquals("Europe/Amsterdam", properties("auto", "Europe/Amsterdam", "'value'", "{ { 'Asia/Tokyo' } }"));

        // Without Read.Timezone the zone property is the one of the device, also when the app set another default zone
        // itself and the provider has another zone, so nothing changes, the previous zone neither; nor without a zone
        // property, nor for a fake zone which the provider has already
        assertEquals("kept", properties("auto", "America/Chicago", "'key', 'value'", all));
        assertEquals("kept", properties("auto", "", "'key', 'value'", all));
        assertEquals("kept", properties("auto", "America/New_York", "'key', 'value'", all));
        // A home zone is one a calendar app chose
        assertEquals("kept", properties("home", "Europe/Amsterdam", "'key', 'value'",
                "{ { 'timezoneInstances', 'America/New_York' } }"));
        // Rows without a zone stay as they are
        assertEquals("kept", properties("auto", "Europe/Amsterdam", "'key', 'value'", "{ { 'timezoneType', 'auto' } }"));
    }

    // The empty cursor of the contacts provider has its extras, which it sets whatever its rows: the address book index,
    // as the empty arrays of no contacts, and the deferred snippeting of a filter; other providers set none
    @Test
    public void extrasOfContacts() {
        String run = "local hook = hookof(...)\n" +
                "local authority = select(2, ...)\n" +
                "local param = paramof(urlof(authority, '/contacts/filter/a', " +
                "{ ['android.provider.extra.ADDRESS_BOOK_INDEX'] = 'true' }), nil, nil, {})\n" +
                "local cursor = cursorof({ '_id' }, { { '1' } })\n" +
                "cursor.extras = bundleof()\n" +
                "cursor.extras.values['android.provider.extra.ADDRESS_BOOK_INDEX_TITLES'] = arrayof('A', 'B')\n" +
                "cursor.extras.values['android.provider.extra.ADDRESS_BOOK_INDEX_COUNTS'] = arrayof(1, 2)\n" +
                "cursor.extras.values['deferred_snippeting'] = true\n" +
                "param.result = cursor\n" +
                "local restricted = after(hook, param)\n" +
                "local extras = param.result.extras and param.result.extras.values or {}\n" +
                "local titles = extras['android.provider.extra.ADDRESS_BOOK_INDEX_TITLES']\n" +
                "local counts = extras['android.provider.extra.ADDRESS_BOOK_INDEX_COUNTS']\n" +
                "return restricted, cursor.closed, titles and titles.length, counts and counts.length,\n" +
                "  extras['deferred_snippeting'], cursor.extras.values['android.provider.extra.ADDRESS_BOOK_INDEX_TITLES'].length";
        Varargs contacts = globals.load(run, "test").invoke(LuaValue.varargsOf(
                LuaValue.valueOf("ContentProviderClient.query26/contacts"), LuaValue.valueOf("0@com.android.contacts")));
        assertTrue(contacts.arg(1).toboolean());
        assertTrue(contacts.arg(2).toboolean());
        // Arrays which are there, as nil would read as 0 too
        assertTrue(contacts.arg(3).isnumber());
        assertEquals(0, contacts.arg(3).toint());
        assertTrue(contacts.arg(4).isnumber());
        assertEquals(0, contacts.arg(4).toint());
        assertTrue(contacts.arg(5).toboolean());
        // The extras of the provider stay as they were
        assertEquals(2, contacts.arg(6).toint());

        // Not of other providers
        Varargs calendar = globals.load(run, "test").invoke(LuaValue.varargsOf(
                LuaValue.valueOf("ContentResolver.query26/calendars"), LuaValue.valueOf("com.android.calendar")));
        assertTrue(calendar.arg(1).toboolean());
        assertTrue(calendar.arg(3).isnil());
        assertTrue(calendar.arg(5).isnil());

        // Without the index there are none, and an error of the provider still leaves the empty cursor
        Varargs plain = Scripts.run(globals,
                "local hook = hookof('ContentResolver.query26/contacts')\n" +
                        "local param = paramof(urlof('com.android.contacts', '/contacts'), nil, nil, {})\n" +
                        "local cursor = cursorof({ '_id' }, { { '1' } })\n" +
                        "cursor.extras = bundleof()\n" +
                        "param.result = cursor\n" +
                        "after(hook, param)\n" +
                        "local failing = cursorof({ '_id' }, { { '1' } })\n" +
                        "function failing:getExtras() error('DeadObjectException') end\n" +
                        "local other = paramof(urlof('com.android.contacts', '/contacts'), nil, nil, {})\n" +
                        "other.result = failing\n" +
                        "local restricted = after(hook, other)\n" +
                        "return param.result.extras.values['android.provider.extra.ADDRESS_BOOK_INDEX_TITLES'] == nil,\n" +
                        "  restricted, other.result ~= failing, failing.closed, other.result:moveToNext()");
        assertTrue(plain.arg(1).toboolean());
        assertTrue(plain.arg(2).toboolean());
        assertTrue(plain.arg(3).toboolean());
        assertTrue(plain.arg(4).toboolean());
        assertFalse(plain.arg(5).toboolean());
    }

    // The rows of a query of aggregates like a provider without rows: of the projection of the app and the row of the
    // provider; as a string of the rows, nil for no row
    private String aggregates(String hook, String authority, String path, String query, String projection, int rows) {
        Varargs result = Scripts.run(globals,
                "local hook = hookof('" + hook + "')\n" +
                        "local param = paramof(urlof('" + authority + "', " + (path == null ? "nil" : "'" + path + "'") + ", " + query +
                        "), nil, nil, {})\n" +
                        "param.arguments[1] = arrayof(" + projection + ")\n" +
                        "local row = {}\n" +
                        "for index = 1, param.arguments[1].length do row[index] = 25 end\n" +
                        "local cursor = cursorof(param.arguments[1], " + (rows == 0 ? "{}" : "{ row }") + ")\n" +
                        "param.result = cursor\n" +
                        "local restricted = after(hook, param)\n" +
                        "local fake = param.result\n" +
                        "if not restricted or not cursor.closed or #fake.rows > 1 then return 'wrong' end\n" +
                        "if #fake.rows == 0 then return nil end\n" +
                        "local values = {}\n" +
                        "for index = 1, param.arguments[1].length do values[index] = tostring(fake.rows[1][index]) end\n" +
                        "return table.concat(values, ',')");
        return (result.arg1().isnil() ? null : result.arg1().tojstring());
    }

    // A count is one row also of no rows: the _count of the contacts provider and the aggregates SmsProvider passes to
    // SQLite, which an empty device answers with 0 and null
    @Test
    public void countOfNoRows() {
        String contacts = "ContentResolver.query26/contacts";
        String sms = "ContentProviderClient.query26/mmssms";
        assertEquals("0", aggregates(contacts, "com.android.contacts", "/contacts", "nil", "'_count'", 1));
        assertEquals("0", aggregates(contacts, "0@com.android.contacts", "/data/phones/", "{ directory = '0' }", "'_count'", 1));
        assertEquals("0,nil,0,nil", aggregates(sms, "sms", "/inbox", "nil",
                "'count(*)', 'MAX(date) AS last', 'total(read)', 'thread_id'", 1));
        assertEquals("0", aggregates(sms, "sms", null, "nil", "'COUNT (DISTINCT address)'", 1));

        // No row where the provider groups, passes the query on or makes the rows itself, for other columns, for the
        // scalar max of more values, and when the provider answered no rows without querying
        assertNull(aggregates(contacts, "com.android.contacts", "/contacts/frequent", "nil", "'_count'", 1));
        assertNull(aggregates(contacts, "com.android.contacts", "/data/emails", "{ remove_duplicate_entries = 'true' }",
                "'_count'", 1));
        assertNull(aggregates(contacts, "com.android.contacts", "/contacts", "{ directory = '5' }", "'_count'", 1));
        assertNull(aggregates(sms, "sms", "/icc", "nil", "'count(*)'", 1));
        assertNull(aggregates(sms, "mms", "/part", "nil", "'count(*)'", 1));
        assertNull(aggregates(sms, "sms", "/inbox", "nil", "'_id', 'body'", 1));
        assertNull(aggregates(sms, "sms", "/inbox", "nil", "'max(date, 0)'", 1));
        assertNull(aggregates(sms, "sms", "/inbox", "nil", "'count(*)'", 0));
    }

    // The thread of recipients (Telephony.Threads.getOrCreateThreadId) is a row, which the app needs, of a fake id: the
    // same for the same recipients, in any order, another one for others and of another fake Android ID
    @Test
    public void threadOfRecipients() {
        String thread = "local function thread(recipients, androidId, rows)\n" +
                "  local hook = hookof('ContentResolver.query26/mmssms')\n" +
                "  local param = paramof(urlof('mms-sms', '/threadID', { recipient = recipients }), nil, nil,\n" +
                "    { ['value.android_id'] = androidId })\n" +
                "  local cursor = cursorof(arrayof('_id'), rows or { { 42 } })\n" +
                "  param.result = cursor\n" +
                "  local restricted = after(hook, param)\n" +
                "  assert(restricted and cursor.closed and param.result ~= cursor)\n" +
                "  return param.result.rows[1] and param.result.rows[1][1]\n" +
                "end\n";
        Varargs result = Scripts.run(globals, thread +
                "return thread({ '+15551234' }, 'a'), thread({ '+15551234', 'b@example.com' }, 'a'),\n" +
                "  thread({ 'b@example.com', '+15551234', '+15551234' }, 'a'), thread({ '+15559999' }, 'a'),\n" +
                "  thread({ '+15551234' }, 'b'), thread({ '+15551234' }, 'a', {})");
        assertTrue(result.arg(1).isnumber());
        assertTrue(result.arg(1).todouble() >= 1 && result.arg(1).todouble() <= 0x100000);
        assertEquals(result.arg(2).todouble(), result.arg(3).todouble(), 0);
        assertFalse(result.arg(1).todouble() == result.arg(2).todouble());
        assertFalse(result.arg(1).todouble() == result.arg(4).todouble());
        assertFalse(result.arg(1).todouble() == result.arg(5).todouble());
        assertEquals(result.arg(1).todouble(), Scripts.run(globals, thread + "return thread({ '+15551234' }, 'a')")
                .arg1().todouble(), 0);
        // No thread, like the provider without recipients, stays without a row
        assertTrue(result.arg(6).isnil());
    }

    // The builder of an operation with the operation it built: an assert of a uri, or another operation
    private static final String BUILDER = String.join("\n",
            "function builderof(authority, path, assert)",
            "  local builder = { builds = 0 }",
            "  function builder:withSelection(selection, args) self.selection = selection self.args = args return self end",
            "  function builder:build()",
            "    self.builds = self.builds + 1",
            "    return { selection = self.selection, isAssertQuery = function(op) return assert end,",
            "      getUri = function(op) return urlof(authority, path) end }",
            "  end",
            "  return builder",
            "end",
            "function built(hook, builder)",
            "  local param = paramof(nil, nil, nil, {})",
            "  param.this = builder",
            "  param.result = builder:build()",
            "  function param:getArgument(index) error('no arguments') end",
            "  local skipped = before(hookof(hook), param)",
            "  return after(hookof(hook), param), param.result.selection, builder.args, builder.builds, skipped",
            "end");

    // An assert of a batch, whose query the provider runs itself, selects nothing, with a place for each argument of the
    // app; other operations, and asserts of other providers and of the provider status, stay
    @Test
    public void assertSelectsNothing() {
        Varargs result = Scripts.run(globals, BUILDER + "\n" +
                "return built('ContentProviderOperation.Builder.build/contacts', builderof('com.android.contacts', '/contacts', true))");
        assertTrue(result.arg(1).toboolean());
        String selection = result.arg(2).tojstring();
        assertTrue(selection, selection.startsWith("0 AND ? AND ?"));
        assertEquals(100, selection.length() - selection.replace("?", "").length());
        assertEquals(100, result.arg(3).get("length").toint());
        assertEquals("", result.arg(3).get(100).tojstring());
        assertEquals(2, result.arg(4).toint());
        assertFalse(result.arg(5).toboolean());

        for (String[] other : new String[][]{
                {"contacts", "com.android.contacts", "/provider_status", "true"},
                {"contacts", "com.android.contacts", "/contacts", "false"},
                {"contacts", "com.android.calendar", "/events", "true"},
                {"mmssms", "com.android.voicemail", "/voicemail", "true"},
                // The time zone properties of the calendar provider, which are not restricted
                {"calendars", "com.android.calendar", "/properties", "true"}}) {
            Varargs stays = Scripts.run(globals, BUILDER + "\n" +
                    "return built('ContentProviderOperation.Builder.build/" + other[0] + "', builderof('" + other[1] + "', '" +
                    other[2] + "', " + other[3] + "))");
            assertFalse(other[2], stays.arg(1).toboolean());
            assertTrue(other[2], stays.arg(2).isnil());
            assertEquals(other[2], 1, stays.arg(4).toint());
        }
        for (String[] restricted : new String[][]{
                {"voicemail", "com.android.voicemail", "/x"}, {"mmssms", "sms", "/x"}, {"mmssms", "mms-sms", "/x"},
                {"call_log", "call_log", "/x"}, {"calendars", "com.android.calendar", "/x"},
                {"blockednumber", "com.android.blockednumber", "/x"},
                // Also of providers which throw for any selection: without it they would count the real rows
                {"contacts", "com.android.simphonebook", "/subid/1/adn"}, {"mmssms", "mms-sms", "/search"},
                {"mmssms", "0@mms-sms", "/searchSuggest"}}) {
            assertTrue(restricted[1] + restricted[2], Scripts.run(globals, BUILDER + "\n" +
                    "return built('ContentProviderOperation.Builder.build/" + restricted[0] + "', builderof('" +
                    restricted[1] + "', '" + restricted[2] + "', true))").arg1().toboolean());
        }
    }

    // Gservices without the GSF id, also for the keys starting with a prefix
    @Test
    public void gservicesWithoutGsfId() {
        Varargs result = Scripts.run(globals,
                "local hook = hookof('ContentResolver.query26/gsf_id')\n" +
                        "local param = paramof(urlof('com.google.android.gsf.gservices', '/prefix'))\n" +
                        "local cursor = cursorof({ 'key', 'value' },\n" +
                        "  { { 'android_id', '1234567890' }, { 'android_other', 'value' } })\n" +
                        "param.result = cursor\n" +
                        "local restricted = after(hook, param)\n" +
                        "return restricted, cursor.closed, #param.result.rows, param.result.rows[1][1]");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(1, result.arg(3).toint());
        assertEquals("android_other", result.arg(4).tojstring());

        // Other keys stay as they are, with the cursor at the start
        Varargs other = Scripts.run(globals,
                "local hook = hookof('ContentResolver.query26/gsf_id')\n" +
                        "local param = paramof(urlof('com.google.android.gsf.gservices', '/'))\n" +
                        "local cursor = cursorof({ 'key', 'value' }, { { 'android_other', 'value' } })\n" +
                        "param.result = cursor\n" +
                        "local restricted = after(hook, param)\n" +
                        "return restricted, param.result == cursor, cursor.position, cursor.closed");
        assertFalse(other.arg(1).toboolean());
        assertTrue(other.arg(2).toboolean());
        assertEquals(-1, other.arg(3).toint());
        assertFalse(other.arg(4).toboolean());
    }

    // A query of exact keys gets a row per key from Gservices, with null for one it does not have, so the GSF id
    // is a row without a value instead of no row
    @Test
    public void gservicesExactKeyKeepsItsRow() {
        Varargs result = Scripts.run(globals,
                "local hook = hookof('ContentResolver.query26/gsf_id')\n" +
                        "local param = paramof(urlof('com.google.android.gsf.gservices', nil))\n" +
                        "local cursor = cursorof({ 'key', 'value' }, { { 'android_id', '1234567890' } })\n" +
                        "param.result = cursor\n" +
                        "local restricted = after(hook, param)\n" +
                        "local row = param.result.rows[1]\n" +
                        "return restricted, #param.result.rows, row[1], row[2] == nil");
        assertTrue(result.arg(1).toboolean());
        assertEquals(1, result.arg(2).toint());
        assertEquals("android_id", result.arg(3).tojstring());
        assertTrue(result.arg(4).toboolean());
    }

    // The SIM cards of the telephony provider: the ICCID, card id and IMSI empty like the getters give them, the number
    // the fake one, the other columns as they are with their types
    @Test
    public void simInfoWithoutIdentifiers() {
        Varargs result = Scripts.run(globals,
                "local hook = hookof('ContentResolver.query26/siminfo')\n" +
                        "local param = paramof(urlof('telephony', '/siminfo'), nil, nil, { ['value.phone_number'] = '+15550001111' })\n" +
                        "local cursor = cursorof({ '_id', 'icc_id', 'number', 'imsi', 'card_id', 'display_name' },\n" +
                        "  { { 1, '89014103211118510720', '+31612345678', '310260000000000', '89014103211118510720', 'SIM 1' } })\n" +
                        "param.result = cursor\n" +
                        "local restricted = after(hook, param)\n" +
                        "local row = param.result.rows[1]\n" +
                        "return restricted, cursor.closed, row[1], row[2], row[3], row[4] == nil, row[5], row[6]");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(1, result.arg(3).toint());
        assertEquals("", result.arg(4).tojstring());
        assertEquals("+15550001111", result.arg(5).tojstring());
        assertTrue(result.arg(6).toboolean());
        assertEquals("", result.arg(7).tojstring());
        assertEquals("SIM 1", result.arg(8).tojstring());

        // Another table of the provider, like the carriers, stays
        Varargs other = Scripts.run(globals,
                "local hook = hookof('ContentResolver.query26/siminfo')\n" +
                        "local param = paramof(urlof('telephony', '/carriers'), nil, nil, {})\n" +
                        "param.result = cursorof({ 'name' }, { { 'Carrier' } })\n" +
                        "return after(hook, param)");
        assertFalse(other.arg(1).toboolean());
    }
}
