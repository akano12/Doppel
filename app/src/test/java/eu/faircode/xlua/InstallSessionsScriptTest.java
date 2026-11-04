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

// The app sees only the install sessions it installs itself, like an app updating itself (assets/install_sessions.lua)
public class InstallSessionsScriptTest {
    private static final String STUBS = String.join("\n",
            "function infoof(installer)",
            "  return { getInstallerPackageName = function(self) return installer end }",
            "end",
            "function listof(...)",
            "  local items = { ... }",
            "  return { size = function(self) return #items end, get = function(self, i) return items[i + 1] end }",
            "end",
            "function paramof(result)",
            "  return { result = result, set = false,",
            "    getPackageName = function(self) return 'com.app' end,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value self.set = true end }",
            "end",
            "function hookof(method) return { getMethodName = function(self) return method end } end");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("install_sessions", STUBS);
    }

    @Test
    public void ownSessionStays() {
        Varargs result = Scripts.run(globals, "local p = paramof(infoof('com.app'))\n" +
                "return after(hookof('getSessionInfo'), p), p.set");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
    }

    @Test
    public void sessionOfAnotherInstallerIsHidden() {
        Varargs result = Scripts.run(globals, "local p = paramof(infoof('com.android.vending'))\n" +
                "return after(hookof('getSessionInfo'), p), p.result == nil");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).toboolean());
    }

    @Test
    public void allSessionsKeepOnlyTheOwnOnes() {
        Varargs result = Scripts.run(globals,
                "local p = paramof(listof(infoof('com.app'), infoof('com.android.vending'), infoof(nil)))\n" +
                        "return after(hookof('getAllSessions'), p), p.result:size()");
        assertTrue(result.arg1().toboolean());
        assertEquals(1, result.arg(2).toint());
        // Only own sessions: nothing changes
        result = Scripts.run(globals, "local p = paramof(listof(infoof('com.app')))\n" +
                "return after(hookof('getAllSessions'), p), p.set");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
    }
}
