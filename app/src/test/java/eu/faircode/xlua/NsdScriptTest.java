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
import static org.junit.Assert.assertTrue;

// Network service discovery (assets/nsdmanager_listener.lua): Android always answers starting, so the blocked
// calls answer with a failure, on the executor of the call or else on the main thread, like wifi_discovery
public class NsdScriptTest {
    private static final String STUBS = String.join("\n",
            "function hookof(method, ...)",
            "  local types = arrayof(...)",
            "  return { getMethodName = function(self) return method end,",
            "    getParameterTypes = function(self) return types end }",
            "end",
            "values = setmetatable({}, { __mode = 'k' })",
            "function paramof(args)",
            "  return { args = args, set = false,",
            "    getArgument = function(self, index) return self.args[index] end,",
            "    setResult = function(self, value) self.result = value self.set = true end,",
            "    putValue = function(self, name, value, scope)",
            "      values[scope] = values[scope] or {} values[scope][name] = value end,",
            "    getValue = function(self, name, scope) return values[scope] and values[scope][name] end }",
            "end",
            // Runnables posted to the main thread and run by executors, run by the test
            "posted = {}",
            "luajava = { createProxy = function(name, object) assert(name == 'java.lang.Runnable') return object end,",
            "  bindClass = function(name) assert(name == 'android.os.Looper')",
            "    return { getMainLooper = function(self) return 'main' end } end,",
            "  newInstance = function(name, looper) assert(name == 'android.os.Handler')",
            "    return { post = function(self, runnable) posted[#posted + 1] = { looper, runnable } end } end }",
            "executor = { execute = function(self, runnable) posted[#posted + 1] = { 'executor', runnable } end }",
            "answers = {}",
            "listener = {",
            "  onStartDiscoveryFailed = function(self, type, code) answers[#answers + 1] = 'discover ' .. type .. ' ' .. code end,",
            "  onResolveFailed = function(self, info, code) answers[#answers + 1] = 'resolve ' .. info .. ' ' .. code end,",
            "  onServiceInfoCallbackRegistrationFailed = function(self, code) answers[#answers + 1] = 'callback ' .. code end }",
            // Runs before() and the posted answers, and returns whether restricted, skipped, where and what was answered
            "function run(hook, param)",
            "  local restricted = before(hook, param)",
            "  local early = #answers",
            "  for _, post in ipairs(posted) do post[2].run() end",
            "  return restricted, param.set, early, #posted, posted[1] and posted[1][1], answers[1]",
            "end");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("nsdmanager_listener", Scripts.ARRAYOF + "\n" + STUBS);
    }

    private void answered(String lua, String where, String answer) {
        Varargs result = Scripts.run(globals, lua);
        assertTrue(lua, result.arg(1).toboolean());
        assertTrue(lua, result.arg(2).toboolean());
        assertEquals(lua, 0, result.arg(3).toint());
        assertEquals(lua, 1, result.arg(4).toint());
        assertEquals(lua, where, result.arg(5).tojstring());
        assertEquals(lua, answer, result.arg(6).tojstring());
    }

    @Test
    public void discoveryIsAnsweredOnTheMainThread() {
        answered("return run(hookof('discoverServices', 'java.lang.String', 'int', 'android.net.nsd.NsdManager$DiscoveryListener'), " +
                "paramof({ [0] = '_googlecast._tcp', [1] = 1, [2] = listener }))", "main", "discover _googlecast._tcp 0");
    }

    @Test
    public void discoveryIsAnsweredOnItsExecutor() {
        answered("return run(hookof('discoverServices', 'java.lang.String', 'int', 'android.net.Network', " +
                        "'java.util.concurrent.Executor', 'android.net.nsd.NsdManager$DiscoveryListener'), " +
                        "paramof({ [0] = '_ipp._tcp', [1] = 1, [2] = nil, [3] = executor, [4] = listener }))",
                "executor", "discover _ipp._tcp 0");
        // The type of a request is in the request
        answered("posted = {} answers = {}\n" +
                        "local request = { getServiceType = function(self) return '_hap._tcp' end }\n" +
                        "return run(hookof('discoverServices', 'android.net.nsd.DiscoveryRequest', " +
                        "'java.util.concurrent.Executor', 'android.net.nsd.NsdManager$DiscoveryListener'), " +
                        "paramof({ [0] = request, [1] = executor, [2] = listener }))",
                "executor", "discover _hap._tcp 0");
    }

    @Test
    public void resolvingAndCallbacksAreAnswered() {
        answered("return run(hookof('resolveService', 'android.net.nsd.NsdServiceInfo', " +
                "'android.net.nsd.NsdManager$ResolveListener'), paramof({ [0] = 'tv', [1] = listener }))", "main", "resolve tv 0");
        answered("posted = {} answers = {}\n" +
                "return run(hookof('resolveService', 'android.net.nsd.NsdServiceInfo', 'java.util.concurrent.Executor', " +
                "'android.net.nsd.NsdManager$ResolveListener'), paramof({ [0] = 'tv', [1] = executor, [2] = listener }))", "executor", "resolve tv 0");
        answered("posted = {} answers = {}\n" +
                "return run(hookof('registerServiceInfoCallback', 'android.net.nsd.NsdServiceInfo', 'java.util.concurrent.Executor', " +
                "'android.net.nsd.NsdManager$ServiceInfoCallback'), paramof({ [0] = 'tv', [1] = executor, [2] = listener }))", "executor", "callback 0");
    }

    // Without a listener there is nothing to answer, the call is skipped still
    @Test
    public void noListenerNoAnswer() {
        Varargs result = Scripts.run(globals, "return run(hookof('discoverServices', 'java.lang.String', 'int', " +
                "'android.net.nsd.NsdManager$DiscoveryListener'), paramof({ [0] = '_ipp._tcp', [1] = 1 }))");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(0, result.arg(4).toint());
    }
}
