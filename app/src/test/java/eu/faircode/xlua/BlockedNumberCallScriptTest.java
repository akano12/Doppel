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

// A blocked number is not blocked when the provider asks (assets/blockednumber_call.lua), like the empty queries
public class BlockedNumberCallScriptTest {
    private static final String STUBS = String.join("\n",
            "function bundleof(blocked, status)",
            "  local values = { blocked = blocked, block_status = status }",
            "  return { values = values,",
            "    getBoolean = function(self, key, default) local v = self.values[key]; if v == nil then return default end; return v end,",
            "    containsKey = function(self, key) return self.values[key] ~= nil end,",
            "    putBoolean = function(self, key, value) self.values[key] = value end,",
            "    putInt = function(self, key, value) self.values[key] = value end }",
            "end",
            "function uriof(authority)",
            "  return { getAuthority = function(self) return authority end }",
            "end",
            // The hook name, the arguments of the call and the reply, which the script changes in place
            "function run(name, args, bundle)",
            "  local param = { result = bundle,",
            "    getArgument = function(self, index) return args[index + 1] end,",
            "    getResult = function(self) return self.result end }",
            "  local restricted = after({ getName = function(self) return name end }, param)",
            "  if bundle == nil then return restricted end",
            "  return restricted, bundle.values.blocked, bundle.values.block_status",
            "end");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("blockednumber_call", STUBS);
    }

    private Varargs run(String lua) {
        return Scripts.run(globals, "return run(" + lua + ")");
    }

    @Test
    public void blockedOfAndroid10AndLater() {
        for (String name : new String[]{"ContentResolver.call/blockednumber", "ContentProviderClient.call.authority/blockednumber"}) {
            Varargs result = run("'" + name + "', { 'com.android.blockednumber', 'is_blocked', '+15550100' }, bundleof(true, 1)");
            assertTrue(name, result.arg1().toboolean());
            assertFalse(name, result.arg(2).toboolean());
            assertEquals(name, 0, result.arg(3).toint());
        }
        // Of the own user, which an app can name
        assertTrue(run("'ContentResolver.call/blockednumber', { '0@com.android.blockednumber', 'is_blocked', '+15550100' }, " +
                "bundleof(true, 1)").arg1().toboolean());
    }

    @Test
    public void blockedOfAndroid8And9() {
        // The uri of BlockedNumberContract, and the client without the provider; no block_status before Android 10
        Varargs result = run("'ContentResolver.call.uri/blockednumber', " +
                "{ uriof('com.android.blockednumber'), 'is_blocked', '+15550100' }, bundleof(true, nil)");
        assertTrue(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).isnil());
        result = run("'ContentProviderClient.call/blockednumber', { 'is_blocked', '+15550100' }, bundleof(true, nil)");
        assertTrue(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).isnil());
    }

    @Test
    public void notBlockedOtherCallsAndErrorsUntouched() {
        Varargs result = run("'ContentResolver.call/blockednumber', { 'com.android.blockednumber', 'is_blocked', '+15550100' }, " +
                "bundleof(false, 0)");
        assertFalse(result.arg1().toboolean());
        assertEquals(0, result.arg(3).toint());
        // Other providers and methods, like whether the user can block numbers at all
        result = run("'ContentResolver.call/blockednumber', { 'settings', 'is_blocked', 'x' }, bundleof(true, 1)");
        assertFalse(result.arg1().toboolean());
        assertTrue(result.arg(2).toboolean());
        assertFalse(run("'ContentResolver.call.uri/blockednumber', { uriof('com.android.blockednumber'), " +
                "'can_current_user_block_numbers', nil }, bundleof(true, nil)").arg1().toboolean());
        assertFalse(run("'ContentProviderClient.call/blockednumber', { 'unblock', '+15550100' }, bundleof(true, nil)")
                .arg1().toboolean());
        assertFalse(run("'ContentResolver.call/blockednumber', { nil, 'is_blocked', 'x' }, bundleof(true, 1)").arg1().toboolean());
        // A call which threw, like the SecurityException of an app without the role, has no result
        assertFalse(run("'ContentResolver.call/blockednumber', { 'com.android.blockednumber', 'is_blocked', 'x' }, nil")
                .arg1().toboolean());
    }
}
