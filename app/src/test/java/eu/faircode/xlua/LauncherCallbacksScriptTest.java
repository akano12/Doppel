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

// The callbacks of LauncherApps hear only of the app's own package of its own user (assets/launcher_callbacks.lua)
public class LauncherCallbacksScriptTest {
    private static final String STUBS = String.join("\n",
            Scripts.ARRAYOF,
            // A user handle compares by its id, like UserHandle.equals; the app runs as user 0
            "function userof(id) return { id = id, equals = function(self, other) return other.id == id end } end",
            "luajava = { bindClass = function(name)",
            "  if name == 'android.os.Process' then return { myUserHandle = function(self) return userof(0) end } end",
            "  if name == 'java.lang.reflect.Array' then",
            "    return { newInstance = function(self, class, length) local a = {} a['length'] = length return a end }",
            "  end",
            "  return name",
            "end }",
            // The app has user id 10123, which com.app.shared shares, and user 0 has serial number 0
            "SYSTEM = false",
            "context = {",
            "  getPackageManager = function(self) return { getPackagesForUid = function(self, uid)",
            "    if uid == 10123 then return arrayof('com.app', 'com.app.shared') end return nil end } end,",
            "  getSystemService = function(self, name) return { getSerialNumberForUser = function(self, user)",
            "    return user.id end } end }",
            // A post of the handler with its parameter types and arguments: whether it is restricted, skipped and the
            // packages it posts
            "function post(types, ...)",
            "  local args = { ... }",
            "  local p = { set = false,",
            "    isSystem = function(self) return SYSTEM end,",
            "    getUid = function(self) return 10123 end,",
            "    getApplicationContext = function(self) return context end,",
            "    unhooked = function(self, f) return f() end,",
            "    getPackageName = function(self) return 'com.app' end,",
            "    getArgument = function(self, index) return args[index + 1] end,",
            "    setArgument = function(self, index, value) args[index + 1] = value end,",
            "    setResult = function(self, value) self.set = true end }",
            "  local hook = { getParameterTypes = function(self) return types end }",
            "  local restricted = before(hook, p)",
            "  local names = args[1]",
            "  if type(names) == 'table' and names['length'] ~= nil then names = table.concat(names, ',', 1, names['length']) end",
            "  return restricted == true, p.set, names",
            "end",
            "ONE = arrayof('java.lang.String', 'android.os.UserHandle')",
            "LIST = arrayof('java.lang.String[]', 'android.os.UserHandle', 'boolean')",
            "PROGRESS = arrayof('android.os.UserHandle', 'java.lang.String', 'float')",
            "CONFIG = arrayof('android.content.pm.LauncherUserInfo')",
            "function infoof(serial) return { getUserSerialNumber = function(self) return serial end } end");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("launcher_callbacks", STUBS);
    }

    @Test
    public void ownPackageIsPosted() {
        Varargs result = Scripts.run(globals, "return post(ONE, 'com.app', userof(0))");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
    }

    // Like Android 8.1 to 11 tell about a system app or another app, which the app does not see
    @Test
    public void otherPackageIsNotPosted() {
        Varargs result = Scripts.run(globals, "return post(ONE, 'com.android.settings', userof(0))");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).toboolean());
    }

    // A package sharing the app's user id is the app's own, which Android always lets it see
    @Test
    public void packageOfOwnUidIsPosted() {
        Varargs result = Scripts.run(globals, "return post(ONE, 'com.app.shared', userof(0))");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        result = Scripts.run(globals, "return post(LIST, arrayof('com.other', 'com.app', 'com.app.shared'), userof(0), true)");
        assertTrue(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        assertEquals("com.app,com.app.shared", result.arg(3).tojstring());
        result = Scripts.run(globals, "return post(LIST, arrayof('com.app.shared'), userof(0), false)");
        assertFalse(result.arg1().toboolean());
        assertEquals("com.app.shared", result.arg(3).tojstring());
    }

    // Processes of the system manage the packages of all apps, like the lookups of the group let them
    @Test
    public void systemHearsOfEveryPackage() {
        Varargs result = Scripts.run(globals, "SYSTEM = true return post(ONE, 'com.other', userof(10))");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        result = Scripts.run(globals, "return post(LIST, arrayof('com.other', 'android'), userof(10), false)");
        assertFalse(result.arg(2).toboolean());
        assertEquals("com.other,android", result.arg(3).tojstring());
        result = Scripts.run(globals, "return post(CONFIG, infoof(11))");
        assertFalse(result.arg(2).toboolean());
    }

    // The app itself in a work profile is another app of another user, which tells the profile is there
    @Test
    public void ownPackageOfAnotherUserIsNotPosted() {
        Varargs result = Scripts.run(globals, "return post(ONE, 'com.app', userof(10))");
        assertTrue(result.arg(2).toboolean());
        result = Scripts.run(globals, "return post(PROGRESS, userof(10), 'com.app', 0.5)");
        assertTrue(result.arg(2).toboolean());
        result = Scripts.run(globals, "return post(PROGRESS, userof(0), 'com.app', 0.5)");
        assertFalse(result.arg(2).toboolean());
        result = Scripts.run(globals, "return post(PROGRESS, userof(0), 'com.other', 0.5)");
        assertTrue(result.arg(2).toboolean());
    }

    // The listener of Android 16 QPR2 and 17, which logs an added package before posting it, has the user first
    @Test
    public void listenerDropsOtherPackageBeforeLog() {
        String types = "arrayof('android.os.UserHandle', 'java.lang.String')";
        Varargs result = Scripts.run(globals, "return post(" + types + ", userof(0), 'com.app')");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        result = Scripts.run(globals, "return post(" + types + ", userof(0), 'com.example.bank')");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).toboolean());
        result = Scripts.run(globals, "return post(" + types + ", userof(10), 'com.app')");
        assertTrue(result.arg(2).toboolean());
    }

    // The settings of a user are posted for the app's own user only, so a private space does not show
    @Test
    public void userConfigOfOwnUserOnly() {
        Varargs result = Scripts.run(globals, "return post(CONFIG, infoof(0))");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        result = Scripts.run(globals, "return post(CONFIG, infoof(11))");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).toboolean());
        result = Scripts.run(globals, "return post(CONFIG, nil)");
        assertTrue(result.arg(2).toboolean());
    }

    // A list keeps the app's own package only, and is not posted without it, like Android 12 and later post nothing
    // when no visible package is left
    @Test
    public void listKeepsOwnPackage() {
        Varargs result = Scripts.run(globals, "return post(LIST, arrayof('com.other', 'com.app', 'android'), userof(0), true)");
        assertTrue(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
        assertEquals("com.app", result.arg(3).tojstring());

        result = Scripts.run(globals, "return post(LIST, arrayof('com.app'), userof(0), false)");
        assertFalse(result.arg1().toboolean());
        assertEquals("com.app", result.arg(3).tojstring());

        result = Scripts.run(globals, "return post(LIST, arrayof('com.other', 'android'), userof(0), false)");
        assertTrue(result.arg(2).toboolean());
        result = Scripts.run(globals, "return post(LIST, arrayof('com.app'), userof(10), false)");
        assertTrue(result.arg(2).toboolean());
        result = Scripts.run(globals, "return post(LIST, arrayof('com.app', 'com.other'), userof(10), false)");
        assertTrue(result.arg(2).toboolean());
    }

    // The suspension of Android 9 and later has the extras of the launcher between the packages and the user
    @Test
    public void suspensionKeepsOwnPackage() {
        String types = "arrayof('java.lang.String[]', 'android.os.Bundle', 'android.os.UserHandle')";
        Varargs result = Scripts.run(globals, "return post(" + types + ", arrayof('com.app', 'com.other'), {}, userof(0))");
        assertFalse(result.arg(2).toboolean());
        assertEquals("com.app", result.arg(3).tojstring());
        result = Scripts.run(globals, "return post(" + types + ", arrayof('com.other'), nil, userof(0))");
        assertTrue(result.arg(2).toboolean());
        result = Scripts.run(globals, "return post(" + types + ", arrayof('com.app', 'com.other'), {}, userof(10))");
        assertTrue(result.arg(2).toboolean());
    }
}
