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

// The storage of another uid or package and of the user answers like without usage access
// (assets/storage_stats_uid.lua), once Android answered; Process, Build and the exceptions are stand-ins
public class StorageStatsUidScriptTest {
    private static final String STUBS = String.join("\n",
            "sdk = 30",
            // The uids of the installed packages, of which com.example.shared shares the user id of the app, looked up
            // without the hooks of the app, also uninstalled with the data kept (MATCH_UNINSTALLED_PACKAGES)
            "UIDS = { ['com.example.app'] = 10001, ['com.example.shared'] = 10001, ['com.example.bank'] = 10123 }",
            "PM = { getPackageUid = function(self, name, flags)",
            "  assert(unhooked and flags == 0x2000)",
            "  if UIDS[name] == nil then error('NameNotFoundException') end",
            "  return UIDS[name] end }",
            "luajava = {",
            "  bindClass = function(name)",
            "    if name == 'android.os.Process' then return { myUid = function(self) return 10001 end } end",
            "    assert(name == 'android.os.Build$VERSION', name)",
            "    return { SDK_INT = sdk }",
            "  end,",
            "  newInstance = function(name, message) return { class = name, message = message } end }",
            "function run(method, args, system, exception)",
            "  local param = { args = args, result = nil, set = false,",
            "    getException = function(self) return exception end,",
            "    isSystem = function(self) return system == true end,",
            "    isInstance = function(self, object, name) return name == 'java.io.IOException' and object.io == true end,",
            "    getArgument = function(self, index) return self.args[index] end,",
            "    getPackageName = function(self) return 'com.example.app' end,",
            "    unhooked = function(self, fn) unhooked = true local value = fn() unhooked = false return value end,",
            "    getApplicationContext = function(self)",
            "      return { getOpPackageName = function(self) return 'com.example.app' end,",
            "        getPackageManager = function(self) return PM end } end,",
            "    setResult = function(self, value) self.result = value self.set = true end }",
            "  local restricted = after({ getMethodName = function(self) return method end }, param)",
            "  return restricted, param.set, param.result and param.result.class, param.result and param.result.message",
            "end");

    private static final String DENIED = "Caller does not have android.permission.PACKAGE_USAGE_STATS; " +
            "callingPackage=com.example.app, callingUid=10001";

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("storage_stats_uid", STUBS);
    }

    private Varargs run(String lua) {
        return Scripts.run(globals, "return run(" + lua + ")");
    }

    // Every uid but the app's own, of apps and of the system, gets the SecurityException of StorageStatsService
    @Test
    public void otherUidIsRefused() {
        for (int uid : new int[]{10123, 1000}) {
            Varargs result = run("'queryStatsForUid', { [0] = 'uuid', [1] = " + uid + " }");
            assertTrue(result.arg1().toboolean());
            assertEquals("java.lang.SecurityException", result.arg(3).tojstring());
            assertEquals(DENIED, result.arg(4).tojstring());
        }
        assertFalse(run("'queryStatsForUid', { [0] = 'uuid', [1] = 10001 }").arg(2).toboolean());
    }

    // Up to Android 10 the message is that of Context.enforceCallingOrSelfPermission
    @Test
    public void messageOfAndroid10() {
        Varargs result = Scripts.run(globals, "sdk = 29\nreturn run('queryStatsForUid', { [0] = 'uuid', [1] = 10123 })");
        assertEquals("StorageStatsService: Neither user 10001 nor current process has android.permission.PACKAGE_USAGE_STATS.",
                result.arg(4).tojstring());
    }

    // Another package and the user are refused too, the app's own package and a package sharing its user id stay
    @Test
    public void otherPackageAndUserAreRefused() {
        assertEquals(DENIED, run("'queryStatsForPackage', { [0] = 'uuid', [1] = 'com.example.bank', [2] = 'user' }")
                .arg(4).tojstring());
        assertFalse(run("'queryStatsForPackage', { [0] = 'uuid', [1] = 'com.example.app', [2] = 'user' }").arg(2).toboolean());
        assertFalse(run("'queryStatsForPackage', { [0] = 'uuid', [1] = 'com.example.shared', [2] = 'user' }").arg(2).toboolean());
        assertEquals(DENIED, run("'queryStatsForUser', { [0] = 'uuid', [1] = 'user' }").arg(4).tojstring());
        assertEquals(DENIED, run("'queryExternalStatsForUser', { [0] = 'uuid', [1] = 'user' }").arg(4).tojstring());
    }

    // Android checks the user and the package before usage access, so their exceptions, like that of a caller without
    // usage access, reach the app as they are; an IOException, like of an unknown volume, comes after the check
    @Test
    public void exceptionOfAndroidKept() {
        assertTrue(globals.get("before").isnil());
        for (String exception : new String[]{"{ class = 'SecurityException' }", "{ class = 'NameNotFoundException' }"}) {
            Varargs result = run("'queryStatsForPackage', { [0] = 'uuid', [1] = 'com.example.bank' }, false, " + exception);
            assertFalse(result.arg1().toboolean());
            assertFalse(result.arg(2).toboolean());
        }
        Varargs io = run("'queryStatsForUid', { [0] = 'uuid', [1] = 10123 }, false, { io = true }");
        assertTrue(io.arg(2).toboolean());
        assertEquals(DENIED, io.arg(4).tojstring());
    }

    @Test
    public void systemSeesAll() {
        Varargs result = run("'queryStatsForUid', { [0] = 'uuid', [1] = 10123 }, true");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
    }
}
