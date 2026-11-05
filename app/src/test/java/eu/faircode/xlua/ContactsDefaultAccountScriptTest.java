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
import org.luaj.vm2.Varargs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The default account for new contacts, returned as plain strings by queryDefaultAccountForNewContacts
// (assets/contacts_default_account.lua), has its name replaced with the fake email, its type kept
public class ContactsDefaultAccountScriptTest {
    private static final String STUBS = String.join("\n",
            "function bundleof(name, type)",
            "  local values = { account_name = name, account_type = type }",
            "  return { values = values,",
            "    getString = function(self, key) return self.values[key] end,",
            "    putString = function(self, key, value) self.values[key] = value end }",
            "end",
            "function paramof(authority, method, bundle, email)",
            "  local args = { [0] = authority, [1] = method }",
            "  return { result = bundle,",
            "    getArgument = function(self, index) return args[index] end,",
            "    getResult = function(self) return self.result end,",
            "    getSetting = function(self, key) if key == 'value.email' then return email end end }",
            "end",
            "function run(authority, method, name, type, email)",
            "  local bundle = (name == nil and type == nil) and nil or bundleof(name, type)",
            "  local param = paramof(authority, method, bundle, email)",
            "  local restricted, old, new = after({}, param)",
            "  local kept = bundle and bundle.values.account_type or nil",
            "  return restricted, old, new, bundle and bundle.values.account_name or nil, kept",
            "end");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("contacts_default_account", STUBS);
    }

    private Varargs run(String lua) {
        return Scripts.run(globals, "return run(" + lua + ")");
    }

    @Test
    public void nameFakedTypeKept() {
        Varargs result = run("'com.android.contacts', 'queryDefaultAccountForNewContacts', " +
                "'me@gmail.com', 'com.google', 'fake@example.com'");
        assertTrue(result.arg1().toboolean());
        assertEquals("me@gmail.com", result.arg(2).tojstring());
        assertEquals("fake@example.com", result.arg(3).tojstring());
        assertEquals("fake@example.com", result.arg(4).tojstring());
        assertEquals("com.google", result.arg(5).tojstring());
    }

    @Test
    public void defaultEmailWhenUnset() {
        for (String authority : new String[]{"'0@com.android.contacts'", "'+0@com.android.contacts'"}) {
            Varargs result = run(authority + ", 'queryDefaultAccountForNewContacts', 'me@gmail.com', 'com.google'");
            assertTrue(result.arg1().toboolean());
            assertEquals("private@example.com", result.arg(4).tojstring());
        }
    }

    @Test
    public void otherAuthorityOrMethodUntouched() {
        assertFalse(run("'com.android.settings', 'queryDefaultAccountForNewContacts', " +
                "'me@gmail.com', 'com.google', 'fake@example.com'").arg1().toboolean());
        assertFalse(run("'com.android.contacts', 'queryDefaultAccount', " +
                "'me@gmail.com', 'com.google', 'fake@example.com'").arg1().toboolean());
    }

    @Test
    public void localAccountHasNoName() {
        // A local or not-set default account puts no account_name in the bundle
        assertFalse(run("'com.android.contacts', 'queryDefaultAccountForNewContacts', nil, nil, 'fake@example.com'")
                .arg1().toboolean());
    }
}
