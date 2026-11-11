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

// Listeners of media sessions (assets/media_session_listener.lua): the call goes to Android with a listener which
// ignores the changes, removed when Android added it, so the app gets the exceptions of Android and no sessions
public class MediaSessionListenerScriptTest {
    private static final String ACTIVE = "android.media.session.MediaSessionManager$OnActiveSessionsChangedListener";
    private static final String KEY = "android.media.session.MediaSessionManager$OnMediaKeyEventSessionChangedListener";

    private static final String STUBS = String.join("\n",
            "function hookof(method, ...)",
            "  local types = arrayof(...)",
            "  return { getMethodName = function(self) return method end,",
            "    getParameterTypes = function(self) return types end }",
            "end",
            "values = setmetatable({}, { __mode = 'k' })",
            "removed = {}",
            "manager = setmetatable({}, { __index = function(manager, name)",
            "  return function(self, listener) removed[#removed + 1] = { name, listener } end end })",
            "function paramof(args)",
            "  return { args = args,",
            "    getArgument = function(self, index) return self.args[index] end,",
            "    setArgument = function(self, index, value) self.args[index] = value end,",
            "    getThis = function(self) return manager end,",
            "    getException = function(self) return self.exception end,",
            "    putValue = function(self, name, value, scope)",
            "      values[scope] = values[scope] or {} values[scope][name] = value end,",
            "    getValue = function(self, name, scope) return values[scope] and values[scope][name] end }",
            "end",
            "sdk = 34",
            "luajava = { createProxy = function(name, object) object.proxied = name return object end,",
            "  bindClass = function(name) assert(name == 'android.os.Build$VERSION') return { SDK_INT = sdk } end }",
            "posted = {}",
            "executor = { execute = function(self, runnable) posted[#posted + 1] = runnable end }",
            "told = {}",
            "listener = { onMediaKeyEventSessionChanged = function(self, name, token)",
            "  told[#told + 1] = { name, token } end }",
            // Runs the call like the engine: before, Android with the arguments before left, after;
            // returns whether restricted, what Android got, which method removed what, and how much was posted.
            // Android tells a new listener of the media key session on its executor; the proxy gets no self
            "function call(hook, args, exception)",
            "  local param = paramof(args)",
            "  local restricted = before(hook, param)",
            "  local passed = param.args",
            "  param.exception = exception",
            "  if exception == nil and hook:getMethodName() == 'addOnMediaKeyEventSessionChangedListener' then",
            "    passed[0]:execute({ run = function()",
            "      passed[1].onMediaKeyEventSessionChanged('com.example.player', 'token') end })",
            "  end",
            "  local reported = after(hook, param)",
            "  assert(reported == false)",
            "  return restricted, passed, removed[1] and removed[1][1], removed[1] and removed[1][2], #posted",
            "end");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("media_session_listener", Scripts.ARRAYOF + "\n" + STUBS);
    }

    @Test
    public void activeSessionsGoToAndroidWithAnIgnoringListener() {
        Varargs result = Scripts.run(globals, "local restricted, passed, method, removed, posted = call(" +
                "hookof('addOnActiveSessionsChangedListener', '" + ACTIVE + "', 'android.content.ComponentName'), " +
                "{ [0] = listener, [1] = nil })\n" +
                "return restricted, passed[0] ~= listener, passed[0].proxied, method, removed == passed[0], posted");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(ACTIVE, result.arg(3).tojstring());
        assertEquals("removeOnActiveSessionsChangedListener", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertEquals(0, result.arg(6).toint());
    }

    // The listener is the last argument of the sessions of a package (Android 17), the first of Session2 tokens
    @Test
    public void listenersOfOtherPositionsAreRemovedBySameName() {
        Varargs result = Scripts.run(globals, "local restricted, passed, method, removed = call(" +
                "hookof('addOnActiveSessionsForPackageChangedListener', 'java.lang.String', 'java.util.concurrent.Executor', " +
                "'android.content.ComponentName', '" + ACTIVE + "'), { [0] = 'com.example', [1] = executor, [3] = listener })\n" +
                "return method, removed == passed[3], passed[0]");
        assertEquals("removeOnActiveSessionsForPackageChangedListener", result.arg(1).tojstring());
        assertTrue(result.arg(2).toboolean());
        assertEquals("com.example", result.arg(3).tojstring());
        result = Scripts.run(globals, "removed = {}\nlocal restricted, passed, method = call(" +
                "hookof('addOnSession2TokensChangedListener', " +
                "'android.media.session.MediaSessionManager$OnSession2TokensChangedListener', 'android.os.Handler'), " +
                "{ [0] = listener, [1] = 'handler' })\n" +
                "return method, passed[1]");
        assertEquals("removeOnSession2TokensChangedListener", result.arg(1).tojstring());
        assertEquals("handler", result.arg(2).tojstring());
    }

    // Android throws for an app which may not see the sessions and for a missing listener: nothing to remove
    @Test
    public void refusedCallsKeepTheExceptionOfAndroid() {
        Varargs result = Scripts.run(globals, "local restricted, passed, method = call(" +
                "hookof('addOnActiveSessionsChangedListener', '" + ACTIVE + "', 'android.content.ComponentName'), " +
                "{ [0] = listener }, 'SecurityException')\n" +
                "return restricted, method, #posted");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());
        result = Scripts.run(globals, "local restricted, passed, method = call(" +
                "hookof('addOnMediaKeyEventSessionChangedListener', 'java.util.concurrent.Executor', '" + KEY + "'), " +
                "{ [0] = executor }, 'NullPointerException')\n" +
                "return restricted, passed[1], method, #posted, #told");
        assertFalse(result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());
        assertTrue(result.arg(3).isnil());
        assertEquals(0, result.arg(4).toint());
        assertEquals(0, result.arg(5).toint());
    }

    // Android tells a new listener of the media key session on its executor, once, like without the hook,
    // and the listener in between tells the app's listener that it is none
    @Test
    public void mediaKeyListenerIsToldNoSession() {
        Varargs result = Scripts.run(globals, "local restricted, passed, method, removed, count = call(" +
                "hookof('addOnMediaKeyEventSessionChangedListener', 'java.util.concurrent.Executor', '" + KEY + "'), " +
                "{ [0] = executor, [1] = listener })\n" +
                "local early = #told\n" +
                "posted[1].run()\n" +
                "return restricted, method, removed == passed[1], count, early, #told, told[1][1], told[1][2], " +
                "passed[0] == executor");
        assertTrue(result.arg(1).toboolean());
        assertEquals("removeOnMediaKeyEventSessionChangedListener", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertEquals(1, result.arg(4).toint());
        assertEquals(0, result.arg(5).toint());
        assertEquals(1, result.arg(6).toint());
        assertEquals("", result.arg(7).tojstring());
        assertTrue(result.arg(8).isnil());
        assertTrue(result.arg(9).toboolean());
        // Android 13 starts without a package name
        result = Scripts.run(globals, "sdk = 33 posted = {} told = {}\n" +
                "call(hookof('addOnMediaKeyEventSessionChangedListener', 'java.util.concurrent.Executor', '" + KEY + "'), " +
                "{ [0] = executor, [1] = listener })\n" +
                "posted[1].run()\n" +
                "return #told, told[1][1]");
        assertEquals(1, result.arg(1).toint());
        assertTrue(result.arg(2).isnil());
    }

    // On an executor which runs at once an exception of the app's listener leaves the call, like without the hook;
    // the listener in between then stays added, but tells the app's listener nothing of later changes
    @Test
    public void mediaKeyListenerExceptionLeavesTheCall() {
        Varargs result = Scripts.run(globals, "executor.execute = function(self, runnable) runnable.run() end\n" +
                "listener.onMediaKeyEventSessionChanged = function(self, name, token)\n" +
                "  told[#told + 1] = { name, token } error('IllegalStateException') end\n" +
                "local args = { [0] = executor, [1] = listener }\n" +
                "local ok, error = pcall(call, hookof('addOnMediaKeyEventSessionChangedListener', " +
                "'java.util.concurrent.Executor', '" + KEY + "'), args)\n" +
                "args[1].onMediaKeyEventSessionChanged('com.example.other', 'token')\n" +
                "return ok, error, #told, #removed");
        assertFalse(result.arg(1).toboolean());
        assertTrue(result.arg(2).tojstring(), result.arg(2).tojstring().contains("IllegalStateException"));
        assertEquals(1, result.arg(3).toint());
        assertEquals(0, result.arg(4).toint());
    }

    // Hidden, for a user, up to Android 11, which the public ones call with the listener in between: the call goes
    // on to Android with one more listener in between, the one Android adds, which is removed first
    @Test
    public void activeSessionsOfUserGoToAndroidWithAnIgnoringListener() {
        Varargs result = Scripts.run(globals, "local outer = hookof('addOnActiveSessionsChangedListener', '" + ACTIVE +
                "', 'android.content.ComponentName')\n" +
                "local inner = hookof('addOnActiveSessionsChangedListener', '" + ACTIVE +
                "', 'android.content.ComponentName', 'int', 'android.os.Handler')\n" +
                "local param = paramof({ [0] = listener, [1] = 'component' })\n" +
                "local restricted = before(outer, param)\n" +
                "local restricted2, passed, method, first = call(inner, " +
                "{ [0] = param.args[0], [1] = 'component', [2] = 10, [3] = 'handler' })\n" +
                "after(outer, param)\n" +
                "return restricted and restricted2, passed[0] ~= param.args[0] and passed[0] ~= listener, passed[0].proxied, " +
                "method, first == passed[0], passed[1], passed[2], passed[3], #removed, removed[2][2] == param.args[0]");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(ACTIVE, result.arg(3).tojstring());
        assertEquals("removeOnActiveSessionsChangedListener", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertEquals("component", result.arg(6).tojstring());
        assertEquals(10, result.arg(7).toint());
        assertEquals("handler", result.arg(8).tojstring());
        // The one of the public call was not added; removing it is a no-op of Android
        assertEquals(2, result.arg(9).toint());
        assertTrue(result.arg(10).toboolean());
    }
}
