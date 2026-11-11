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

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// No sync of another app runs or waits (assets/sync_activity.lua): the getters and the hidden status of an account say so,
// and the status listener hears of changes of the settings only; the syncs of the accounts of the app itself stay
public class SyncActivityScriptTest {
    private static final String STUBS = String.join("\n", Scripts.ARRAYOF,
            "function hookof(method)",
            "  return { getMethodName = function(self) return method end }",
            "end",
            // A param of the app com.example.dav with the arguments, a result or the class of an exception, which tells what
            // was set; its lookups must be unhooked
            "function paramof(arguments, result, exception)",
            "  local values = {}",
            "  return { arguments = arguments or {}, result = result, set = false,",
            "    getArgument = function(self, index) return self.arguments[index] end,",
            "    setArgument = function(self, index, value) self.arguments[index] = value self.set = true end,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value self.set = true end,",
            "    getException = function(self) return exception end,",
            "    getApplicationContext = function(self) return 'context' end,",
            "    getPackageName = function(self) return 'com.example.dav' end,",
            "    getValue = function(self, name, scope) return values[name] end,",
            "    putValue = function(self, name, value, scope) values[name] = value end,",
            "    unhooked = function(self, f) UNHOOKED = true local value = f() UNHOOKED = false return value end }",
            "end",
            // A java.util.List as Lua sees it, and a SyncInfo
            "function listof(...)",
            "  return { items = { ... }, size = function(self) return #self.items end,",
            "    get = function(self, index) return self.items[index + 1] end,",
            "    add = function(self, item) self.items[#self.items + 1] = item return true end,",
            "    isEmpty = function(self) return #self.items == 0 end }",
            "end",
            "function syncof(type, authority) return { account = { type = type }, authority = authority } end",
            // The authenticators (AUTHS, none by default), the accounts by type (ACCOUNTS), the current syncs of Android and
            // the syncs which run, by the name of the account and the authority (ACTIVE)
            "AUTHS = arrayof()",
            "OWN = arrayof({ packageName = 'com.example.dav', type = 'at.dav' },",
            "  { packageName = 'com.google.android.gms', type = 'com.google' })",
            "ACCOUNTS = {}",
            "ACTIVE = {}",
            "luajava = {",
            "  bindClass = function(name)",
            "    assert(UNHOOKED, 'hooked lookup')",
            "    if name == 'android.accounts.AccountManager' then",
            "      return { get = function(self, context) return { getAuthenticatorTypes = function(self) return AUTHS end,",
            "        getAccountsByType = function(self, type) return ACCOUNTS[type] or arrayof() end } end }",
            "    end",
            "    assert(name == 'android.content.ContentResolver', name)",
            "    return { getCurrentSyncs = function(self) return SYNCS end,",
            "      isSyncActive = function(self, account, authority) return ACTIVE[account.name .. ' ' .. authority] == true end }",
            "  end,",
            "  newInstance = function(name) assert(name == 'java.util.ArrayList', name) return listof() end }");

    private static Globals load() throws Exception {
        return Scripts.load("sync_activity", STUBS);
    }

    // A sync of an account which runs or waits is none; false stays, and the exception of Android too, like the
    // SecurityException without READ_SYNC_STATS and the IllegalArgumentException of a missing account
    @Test
    public void syncsOfAnAccountAreNone() throws Exception {
        Globals globals = load();
        for (String method : new String[]{"isSyncActive", "isSyncPending", "isSyncPendingAsUser"}) {
            Varargs result = Scripts.run(globals, "local param = paramof(nil, true)\n" +
                    "return before(hookof('" + method + "'), param), after(hookof('" + method + "'), param), param.set, param.result");
            assertFalse(method, result.arg(1).toboolean());
            assertTrue(method, result.arg(2).toboolean());
            assertTrue(method, result.arg(3).toboolean());
            assertFalse(method, result.arg(4).toboolean());

            for (String param : new String[]{"paramof(nil, false)", "paramof(nil, nil, 'java.lang.SecurityException')"}) {
                result = Scripts.run(globals, "local param = " + param + "\n" +
                        "return after(hookof('" + method + "'), param), param.set");
                assertFalse(method + " " + param, result.arg(1).toboolean());
                assertFalse(method + " " + param, result.arg(2).toboolean());
            }
        }
    }

    // A sync of an account of the app itself stays, like the one its sync adapter runs into com.android.contacts; an
    // account of another app, also of the authority of the app, and no account (any) are hidden
    @Test
    public void syncsOfOwnAccountsStay() throws Exception {
        Globals globals = load();
        for (String method : new String[]{"isSyncActive", "isSyncPending", "isSyncPendingAsUser"}) {
            Varargs result = Scripts.run(globals, "AUTHS = OWN\n" +
                    "local own = paramof({ [0] = { type = 'at.dav' }, [1] = 'com.android.contacts' }, true)\n" +
                    "local other = paramof({ [0] = { type = 'com.google' }, [1] = 'com.example.dav.provider' }, true)\n" +
                    "local any = paramof({ [1] = 'com.android.contacts' }, true)\n" +
                    "local hook = hookof('" + method + "')\n" +
                    "return after(hook, own), own.set, own.result, after(hook, other), other.result, after(hook, any), any.result");
            assertFalse(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
            assertTrue(method, result.arg(3).toboolean());
            assertTrue(method, result.arg(4).toboolean());
            assertFalse(method, result.arg(5).toboolean());
            assertTrue(method, result.arg(6).toboolean());
            assertFalse(method, result.arg(7).toboolean());
        }
    }

    // The periodic syncs of an account of another app are none, a list of its own like the one of the parcel; none, the
    // exception of a missing account and the syncs of an own account stay
    @Test
    public void periodicSyncsOfOtherAccountsAreNone() throws Exception {
        Globals globals = load();
        Varargs result = Scripts.run(globals, "AUTHS = OWN\n" +
                "local hook = hookof('getPeriodicSyncs')\n" +
                "local syncs = listof('daily')\n" +
                "local other = paramof({ [0] = { type = 'com.google' } }, syncs)\n" +
                "local own = paramof({ [0] = { type = 'at.dav' } }, syncs)\n" +
                "local none = paramof({ [0] = { type = 'com.google' } }, listof())\n" +
                "local missing = paramof({}, nil, 'java.lang.IllegalArgumentException')\n" +
                "return after(hook, other), other.result ~= syncs and other.result:isEmpty(), syncs:size(),\n" +
                "  after(hook, own), own.set, after(hook, none), none.set, after(hook, missing), missing.set");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(1, result.arg(3).toint());
        for (int i = 4; i <= 9; i++)
            assertFalse("value " + i, result.arg(i).toboolean());
    }

    // getCurrentSyncs lists the syncs of the accounts of the app itself only, getCurrentSync gives the first of them like
    // Android gives the first of the list, also when another sync started before; all own stays, none own is empty
    @Test
    public void currentSyncsAreOwnOnly() throws Exception {
        Globals globals = load();
        for (String method : new String[]{"getCurrentSyncs", "getCurrentSyncsAsUser"}) {
            Varargs result = Scripts.run(globals, "AUTHS = OWN\n" +
                    "local hook = hookof('" + method + "')\n" +
                    "local mixed = paramof({ [0] = 0 }, listof(syncof('com.google', 'com.android.contacts'),\n" +
                    "  syncof('at.dav', 'com.android.calendar'), syncof('*****', 'com.android.contacts')))\n" +
                    "local own = paramof({ [0] = 0 }, listof(syncof('at.dav', 'com.android.contacts')))\n" +
                    "local other = paramof({ [0] = 0 }, listof(syncof('com.google', 'com.android.contacts')))\n" +
                    "return after(hook, mixed), mixed.result:size(), mixed.result:get(0).authority,\n" +
                    "  after(hook, own), own.set, after(hook, other), other.result:size()");
            assertTrue(method, result.arg(1).toboolean());
            assertEquals(method, 1, result.arg(2).toint());
            assertEquals(method, "com.android.calendar", result.arg(3).tojstring());
            assertFalse(method, result.arg(4).toboolean());
            assertFalse(method, result.arg(5).toboolean());
            assertTrue(method, result.arg(6).toboolean());
            assertEquals(method, 0, result.arg(7).toint());
        }

        Varargs result = Scripts.run(globals, "AUTHS = OWN\n" +
                "local hook = hookof('getCurrentSync')\n" +
                "local first = syncof('at.dav', 'com.android.contacts')\n" +
                "local own = paramof(nil, first)\n" +
                "local other = syncof('com.google', 'com.android.contacts')\n" +
                "local before = paramof(nil, other)\n" +
                "SYNCS = listof(other, first)\n" +
                "local restricted = after(hook, before)\n" +
                "local alone = paramof(nil, other)\n" +
                "SYNCS = listof(other)\n" +
                "return after(hook, own), own.set, restricted, before.result == first, after(hook, alone), alone.result,\n" +
                "  after(hook, paramof(nil, nil))");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertTrue(result.arg(6).isnil());
        assertFalse(result.arg(7).toboolean());
    }

    // Without GET_ACCOUNTS Android gives each sync with the account *****: of an authority as many stay as accounts of
    // the app itself sync it now, the ones of other authorities go, and without accounts of its own all go
    @Test
    public void redactedSyncsOfOwnAccountsStay() throws Exception {
        Globals globals = load();
        String syncs = "listof(syncof('*****', 'com.android.contacts'), syncof('*****', 'com.android.calendar'),\n" +
                "  syncof('*****', 'com.android.calendar'))";
        Scripts.run(globals, "AUTHS = OWN\n" +
                "ACCOUNTS = { ['at.dav'] = arrayof({ type = 'at.dav', name = 'me' }, { type = 'at.dav', name = 'work' }) }\n" +
                "ACTIVE = { ['me com.android.calendar'] = true }");
        for (String method : new String[]{"getCurrentSyncs", "getCurrentSyncsAsUser"}) {
            Varargs result = Scripts.run(globals, "local hook = hookof('" + method + "')\n" +
                    "local mixed = paramof({ [0] = 0 }, " + syncs + ")\n" +
                    "local own = paramof({ [0] = 0 }, listof(syncof('*****', 'com.android.calendar')))\n" +
                    "return after(hook, mixed), mixed.result:size(), mixed.result:get(0).authority, after(hook, own), own.set");
            assertTrue(method, result.arg(1).toboolean());
            assertEquals(method, 1, result.arg(2).toint());
            assertEquals(method, "com.android.calendar", result.arg(3).tojstring());
            assertFalse(method, result.arg(4).toboolean());
            assertFalse(method, result.arg(5).toboolean());
        }

        // getCurrentSync gives the first of them
        Varargs result = Scripts.run(globals, "SYNCS = " + syncs + "\n" +
                "local param = paramof(nil, SYNCS:get(0))\n" +
                "return after(hookof('getCurrentSync'), param), param.result == SYNCS:get(1)");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());

        // An app without accounts of its own
        result = Scripts.run(globals, "AUTHS = arrayof()\n" +
                "local param = paramof({ [0] = 0 }, " + syncs + ")\n" +
                "return after(hookof('getCurrentSyncs'), param), param.result:size()");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).toint());
    }

    // The types of the own accounts are the ones account_createfromparcel keeps, by the same key
    @Test
    public void ownTypesAreTheSame() throws Exception {
        assertEquals(Scripts.function("account_createfromparcel", "owntypes"), Scripts.function("sync_activity", "owntypes"));
    }

    // The hidden status of an account does not wait, like isSyncPending; it stays when it does not wait, and no status
    // (an authority which never synced) stays none
    @Test
    public void statusOfAnAccountDoesNotWait() throws Exception {
        Globals globals = load();
        for (String method : new String[]{"getSyncStatus", "getSyncStatusAsUser"}) {
            Varargs result = Scripts.run(globals, "local status = { pending = true, lastSuccessTime = 5 }\n" +
                    "local param = paramof(nil, status)\n" +
                    "return after(hookof('" + method + "'), param), param.set, param.result == status, status.pending, " +
                    "status.lastSuccessTime");
            assertTrue(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
            assertTrue(method, result.arg(3).toboolean());
            assertFalse(method, result.arg(4).toboolean());
            assertEquals(method, 5, result.arg(5).toint());

            for (String param : new String[]{"paramof(nil, { pending = false })", "paramof(nil, nil)"}) {
                result = Scripts.run(globals, "local param = " + param + "\n" +
                        "return after(hookof('" + method + "'), param), param.set");
                assertFalse(method + " " + param, result.arg(1).toboolean());
                assertFalse(method + " " + param, result.arg(2).toboolean());
            }

            // An own account waits like it does
            result = Scripts.run(globals, "AUTHS = OWN\n" +
                    "local status = { pending = true }\n" +
                    "return after(hookof('" + method + "'), paramof({ [0] = { type = 'at.dav' } }, status)), status.pending");
            assertFalse(method, result.arg(1).toboolean());
            assertTrue(method, result.arg(2).toboolean());
        }
    }

    // The mask keeps SYNC_OBSERVER_TYPE_SETTINGS only, also of the hidden SYNC_OBSERVER_TYPE_ALL and with the sign bit
    // set; the handle, the result, stays the one of Android. An app with accounts of its own keeps the mask, it waits
    // for its own syncs by it.
    @Test
    public void statusListenerHearsOfSettingsOnly() throws Exception {
        Globals globals = load();
        int[][] masks = {{1, 1}, {0, 0}, {2, 0}, {4, 0}, {6, 0}, {7, 1}, {8, 0}, {0x7fffffff, 1}, {-1, 1}, {0x80000002, 0}};
        for (int[] mask : masks) {
            Varargs result = Scripts.run(globals, "local hook = hookof('addStatusChangeListener')\n" +
                    "local param = paramof({ [0] = " + mask[0] + " }, 'handle')\n" +
                    "return before(hook, param), param.arguments[0], after(hook, param), param.result");
            assertEquals("mask " + mask[0], mask[0] != mask[1], result.arg(1).toboolean());
            assertEquals("mask " + mask[0], mask[1], result.arg(2).toint());
            assertFalse("mask " + mask[0], result.arg(3).toboolean());
            assertEquals("mask " + mask[0], "handle", result.arg(4).tojstring());
        }

        Varargs result = Scripts.run(globals, "AUTHS = OWN\n" +
                "local param = paramof({ [0] = 6 }, 'handle')\n" +
                "return before(hookof('addStatusChangeListener'), param), param.arguments[0], param.set");
        assertFalse(result.arg(1).toboolean());
        assertEquals(6, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
    }
}
