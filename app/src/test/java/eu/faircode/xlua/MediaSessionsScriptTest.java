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

// The app sees its own media sessions and not the ones of other apps (assets/media_sessions.lua)
public class MediaSessionsScriptTest {
    private static final String STUBS = String.join("\n",
            // A MediaController or a Session2Token of the session of a package, none when the session died
            "function sessionof(packageName) return { getPackageName = function(self) return packageName end } end",
            "function listof(...)",
            "  local items = { ... }",
            "  return { size = function(self) return #items end, get = function(self, i) return items[i + 1] end,",
            "    isEmpty = function(self) return #items == 0 end }",
            "end",
            "function paramof(result, argument)",
            "  return { result = result, set = false,",
            "    getPackageName = function(self) return 'com.app' end,",
            "    getArgument = function(self, index) return argument end,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value self.set = true end }",
            "end",
            "function hookof(method) return { getMethodName = function(self) return method end } end",
            // The packages of the sessions of the result, joined
            "function packages(list)",
            "  local names = {}",
            "  for i = 0, list:size() - 1 do names[#names + 1] = list:get(i):getPackageName() end",
            "  return table.concat(names, ',')",
            "end");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("media_sessions", STUBS);
    }

    // A notification listener gets its own sessions among the ones of all apps, in their order
    @Test
    public void ownSessionsStayAmongAll() {
        for (String method : new String[]{"getActiveSessions", "getSession2Tokens"}) {
            Varargs result = Scripts.run(globals,
                    "local p = paramof(listof(sessionof('com.music'), sessionof('com.app'), sessionof(nil), sessionof('com.app')))\n" +
                            "local restricted = after(hookof('" + method + "'), p)\n" +
                            "return restricted, p.set, packages(p.result)");
            assertTrue(method, result.arg(1).toboolean());
            assertTrue(method, result.arg(2).toboolean());
            assertEquals(method, "com.app,com.app", result.arg(3).tojstring());
        }
    }

    // Only own sessions, no sessions and no result stay as they are
    @Test
    public void ownOrNoSessionsStay() {
        for (String list : new String[]{"listof(sessionof('com.app'))", "listof()", "nil"}) {
            Varargs result = Scripts.run(globals, "local p = paramof(" + list + ")\n" +
                    "return after(hookof('getActiveSessions'), p), p.set");
            assertFalse(list, result.arg(1).toboolean());
            assertFalse(list, result.arg(2).toboolean());
        }
    }

    // Any app may ask for the sessions of its own package, the tokens of another package are hidden
    @Test
    public void sessionsOfOwnPackageStay() {
        Varargs result = Scripts.run(globals, "local p = paramof(listof('token'), 'com.app')\n" +
                "return after(hookof('getActiveSessionsForPackage'), p), p.set");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        result = Scripts.run(globals, "local p = paramof(listof('token'), 'com.music')\n" +
                "return after(hookof('getActiveSessionsForPackage'), p), p.set, p.result:isEmpty()");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
    }
}
