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
            "function infoof(installer, app)",
            "  return { getInstallerPackageName = function(self) return installer end,",
            "    getAppPackageName = function(self) return app end }",
            "end",
            // The app itself and system apps, like Play, are visible
            "VISIBLE = { ['com.app'] = true, ['com.android.vending'] = true, ['com.google.android.gms'] = true }",
            "function listof(...)",
            "  local items = { ... }",
            "  return { size = function(self) return #items end, get = function(self, i) return items[i + 1] end }",
            "end",
            "function paramof(result)",
            "  return { result = result, set = false,",
            "    getPackageName = function(self) return 'com.app' end,",
            "    isPackageVisible = function(self, name) return VISIBLE[name] == true end,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value self.set = true end }",
            "end",
            "function hookof(method) return { getMethodName = function(self) return method end } end",
            // An event of a session for the callback of the app: whether it is dropped
            "sessions, values = {}, {}",
            "function event(method, id, delegate)",
            "  local installer = { getSessionInfo = function(self, session) return sessions[session] end }",
            "  local context = { getPackageManager = function(self)",
            "    return { getPackageInstaller = function(self) return installer end } end }",
            "  local p = paramof(nil)",
            "  p.getArgument = function(self, index) return id end",
            "  p.getApplicationContext = function(self) return context end",
            // The values per callback (scope), the first one unless given
            "  local scope = delegate or 'callback'",
            "  p.getThis = function(self) return scope end",
            "  p.getValue = function(self, name, s) return values[s .. '|' .. name] end",
            "  p.putValue = function(self, name, value, s) values[s .. '|' .. name] = value end",
            "  p.unhooked = function(self, lookup) return lookup() end",
            "  return before(hookof(method), p), p.set",
            "end");

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

    // The callback of the app hears of its own sessions, also that they finished, when they have no info anymore
    @Test
    public void callbackHearsOfOwnSessionsOnly() {
        Varargs result = Scripts.run(globals,
                "sessions[1] = infoof('com.app') sessions[2] = infoof('com.android.vending')\n" +
                        "local own, ownSet = event('onSessionCreated', 1)\n" +
                        "local other, otherSet = event('onSessionProgressChanged', 2)\n" +
                        "local remembered = (values['callback|install_sessions.1'] == true and values['callback|install_sessions.2'] == false)\n" +
                        "sessions[1] = nil sessions[2] = nil\n" +
                        "local finished, finishedSet = event('onSessionFinished', 1)\n" +
                        "local again = event('onSessionFinished', 1)\n" +
                        "local otherFinished = event('onSessionFinished', 2)\n" +
                        "return own, ownSet, other, otherSet, finished, finishedSet, again, otherFinished, next(values) == nil,\n" +
                        "  remembered");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
        // Forgotten when they finished, the session of another app too
        assertTrue(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
        assertTrue(result.arg(9).toboolean());
        assertTrue(result.arg(10).toboolean());
        // The hooks of the lists and of the info do nothing before the call
        result = Scripts.run(globals, "local p = paramof(nil) return before(hookof('getSessionInfo'), p), p.set");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(2).toboolean());
    }

    // Two callbacks of the app both hear that its own session finished, each remembers the sessions itself
    @Test
    public void eachCallbackHearsOfOwnSessions() {
        Varargs result = Scripts.run(globals,
                "sessions[1] = infoof('com.app')\n" +
                        "event('onSessionCreated', 1, 'first') event('onSessionCreated', 1, 'second')\n" +
                        "sessions[1] = nil\n" +
                        "local first = event('onSessionFinished', 1, 'first')\n" +
                        "local second = event('onSessionFinished', 1, 'second')\n" +
                        "return first, second");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
    }

    // Sessions of visible apps by visible installers stay, like an update of the app or of a system app by Play;
    // those of hidden apps, or by a hidden installer, do not
    @Test
    public void sessionsOfVisibleAppsStay() {
        Varargs result = Scripts.run(globals,
                "local p = paramof(listof(infoof('com.android.vending', 'com.app'),\n" +
                        "  infoof('com.android.vending', 'com.google.android.gms'), infoof('com.android.vending', 'com.hidden'),\n" +
                        "  infoof('com.hidden.store', 'com.app'), infoof(nil, 'com.app')))\n" +
                        "return after(hookof('getAllSessions'), p), p.result:size()");
        assertTrue(result.arg1().toboolean());
        assertEquals(3, result.arg(2).toint());
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
