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

import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Network service discovery (assets/nsdmanager_listener.lua): Android always answers starting, so the blocked
// calls answer with a failure, after the call returned, on the handler of the manager and from there on the executor
// of the call; wrong arguments go on to Android, which throws for them itself, and the listener is in use until the
// answer, like Android keeps it until then. Discovery by a network request starts, and its listener stays in use.
public class NsdScriptTest {
    private static final String STUBS = String.join("\n",
            "function hookof(method, ...)",
            "  local types = arrayof(...)",
            "  return { getMethodName = function(self) return method end,",
            "    getParameterTypes = function(self) return types end }",
            "end",
            "values = setmetatable({}, { __mode = 'k' })",
            "context = {}",
            "function paramof(args)",
            "  return { args = args, set = false,",
            "    getArgument = function(self, index) return self.args[index] end,",
            "    getThis = function(self) return manager end,",
            "    getApplicationContext = function(self) return context end,",
            "    setResult = function(self, value) self.result = value self.set = true end,",
            "    putValue = function(self, name, value, scope)",
            "      values[scope] = values[scope] or {} values[scope][name] = value end,",
            "    getValue = function(self, name, scope) return values[scope] and values[scope][name] end }",
            "end",
            // Runnables posted to handlers, which the test runs, and the handler of the manager in its field mHandler,
            // which the hidden API rules may leave out (fields = arrayof()), and the live threads of the app
            "posted = {}",
            "function handlerstub(name)",
            "  return { post = function(self, runnable) posted[#posted + 1] = { name, runnable } end }",
            "end",
            "manager = { handler = handlerstub('manager') }",
            "fields = arrayof({ getName = function(self) return 'mHandler' end, setAccessible = function(self, on) end,",
            "  get = function(self, object) return object.handler end })",
            "threads = 0",
            "live = arrayof()",
            "function threadof(name)",
            "  return { getName = function(self) return name end, getLooper = function(self) return 'live ' .. name end }",
            "end",
            "sdk = 34",
            "luajava = { createProxy = function(name, object) assert(name == 'java.lang.Runnable') return object end,",
            "  bindClass = function(name)",
            "    if name == 'java.lang.Class' then",
            "      return { getDeclaredFields = function(class) assert(class == 'android.net.nsd.NsdManager') return fields end }",
            "    elseif name == 'android.os.Build$VERSION' then",
            "      return { SDK_INT = sdk }",
            "    elseif name == 'java.lang.Thread' then",
            "      return { getAllStackTraces = function(self)",
            "        return { keySet = function(self) return { toArray = function(self) return live end } end } end }",
            "    end",
            "    return name",
            "  end,",
            "  newInstance = function(name, value)",
            "    if name == 'android.os.HandlerThread' then",
            "      threads = threads + 1",
            "      return { start = function(self) self.started = true end,",
            "        getLooper = function(self) assert(self.started) return value .. ' ' .. threads end }",
            "    elseif name == 'android.os.Handler' then",
            "      return handlerstub(value)",
            "    end",
            "    return { class = name, message = value }",
            "  end }",
            // An executor which runs at once, like Runnable::run, and tells it ran
            "executor = { execute = function(self, runnable) answers[#answers + 1] = 'executor' runnable.run() end }",
            "answers = {}",
            "listener = {",
            "  onStartDiscoveryFailed = function(self, type, code) answers[#answers + 1] = 'discover ' .. type .. ' ' .. code end,",
            "  onResolveFailed = function(self, info, code)",
            "    answers[#answers + 1] = 'resolve ' .. info:getServiceName() .. ' ' .. code end,",
            "  onServiceInfoCallbackRegistrationFailed = function(self, code) answers[#answers + 1] = 'callback ' .. code end,",
            "  onDiscoveryStarted = function(self, type) answers[#answers + 1] = 'started ' .. type end,",
            "  onDiscoveryStopped = function(self, type) answers[#answers + 1] = 'stopped ' .. type end }",
            "tv = { getServiceName = function(self) return 'tv' end, getServiceType = function(self) return '_googlecast._tcp' end }",
            // Runs before() and the posted answers, and returns whether restricted, skipped, the answers before the
            // call returned, the posts, where and what was answered
            "function run(hook, param)",
            "  posted = {} answers = {}",
            "  local restricted = before(hook, param)",
            "  local early = #answers",
            "  for _, post in ipairs(posted) do post[2].run() end",
            "  return restricted, param.set, early, #posted, posted[1] and posted[1][1], table.concat(answers, ', ')",
            "end",
            "DISCOVER = { 'java.lang.String', 'int', 'android.net.nsd.NsdManager$DiscoveryListener' }",
            "RESOLVE = { 'android.net.nsd.NsdServiceInfo', 'android.net.nsd.NsdManager$ResolveListener' }",
            "REQUEST = { 'java.lang.String', 'int', 'android.net.NetworkRequest', 'java.util.concurrent.Executor',",
            "  'android.net.nsd.NsdManager$DiscoveryListener' }");

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
    public void discoveryIsAnsweredOnTheHandlerOfTheManager() {
        answered("return run(hookof('discoverServices', table.unpack(DISCOVER)), " +
                "paramof({ [0] = '_googlecast._tcp', [1] = 1, [2] = listener }))", "manager", "discover _googlecast._tcp 0");
    }

    // A direct executor gets the answer after the call returned, from the handler of the manager
    @Test
    public void discoveryIsAnsweredOnItsExecutor() {
        answered("return run(hookof('discoverServices', 'java.lang.String', 'int', 'android.net.Network', " +
                        "'java.util.concurrent.Executor', 'android.net.nsd.NsdManager$DiscoveryListener'), " +
                        "paramof({ [0] = '_ipp._tcp', [1] = 1, [2] = nil, [3] = executor, [4] = listener }))",
                "manager", "executor, discover _ipp._tcp 0");
        // The type of a request is in the request
        answered("local request = { getServiceType = function(self) return '_hap._tcp' end }\n" +
                        "return run(hookof('discoverServices', 'android.net.nsd.DiscoveryRequest', " +
                        "'java.util.concurrent.Executor', 'android.net.nsd.NsdManager$DiscoveryListener'), " +
                        "paramof({ [0] = request, [1] = executor, [2] = listener }))",
                "manager", "executor, discover _hap._tcp 0");
    }

    @Test
    public void resolvingAndCallbacksAreAnswered() {
        answered("return run(hookof('resolveService', table.unpack(RESOLVE)), paramof({ [0] = tv, [1] = listener }))",
                "manager", "resolve tv 0");
        answered("return run(hookof('resolveService', 'android.net.nsd.NsdServiceInfo', 'java.util.concurrent.Executor', " +
                "'android.net.nsd.NsdManager$ResolveListener'), paramof({ [0] = tv, [1] = executor, [2] = listener }))",
                "manager", "executor, resolve tv 0");
        answered("return run(hookof('registerServiceInfoCallback', 'android.net.nsd.NsdServiceInfo', " +
                        "'java.util.concurrent.Executor', 'android.net.nsd.NsdManager$ServiceInfoCallback'), " +
                        "paramof({ [0] = tv, [1] = executor, [2] = listener }))",
                "manager", "executor, callback 0");
    }

    // Without the field of the handler, one thread with the name of the version answers all calls of the app
    @Test
    public void withoutTheHandlerAThreadAnswers() {
        Scripts.run(globals, "fields = arrayof()");
        answered("return run(hookof('discoverServices', table.unpack(DISCOVER)), " +
                "paramof({ [0] = '_ipp._tcp', [1] = 1, [2] = listener }))", "NsdManager 1", "discover _ipp._tcp 0");
        answered("return run(hookof('resolveService', table.unpack(RESOLVE)), paramof({ [0] = tv, [1] = listener }))",
                "NsdManager 1", "resolve tv 0");
        assertEquals(1, Scripts.run(globals, "return threads").toint(1));
        // Android 15 answers on ConnectivityThread
        answered("sdk = 35 context = {}\n" +
                "return run(hookof('resolveService', table.unpack(RESOLVE)), paramof({ [0] = tv, [1] = listener }))",
                "ConnectivityThread 2", "resolve tv 0");
    }

    // The thread NsdManager of the manager answers, else the shared ConnectivityThread of an updated network module,
    // which also runs network callbacks; no thread is started for them
    @Test
    public void withoutTheHandlerTheThreadOfTheManagerAnswers() {
        Scripts.run(globals, "fields = arrayof()");
        answered("live = arrayof(threadof('main'), threadof('ConnectivityThread'), threadof('NsdManager'))\n" +
                "return run(hookof('resolveService', table.unpack(RESOLVE)), paramof({ [0] = tv, [1] = listener }))",
                "live NsdManager", "resolve tv 0");
        answered("live = arrayof(threadof('ConnectivityThread')) context = {}\n" +
                "return run(hookof('resolveService', table.unpack(RESOLVE)), paramof({ [0] = tv, [1] = listener }))",
                "live ConnectivityThread", "resolve tv 0");
        assertEquals(0, Scripts.run(globals, "return threads").toint(1));
    }

    // A live thread with a public getLooper, like HandlerThread, which the stubs of android.jar cannot run
    public static class LooperThread extends Thread {
        private final CountDownLatch done = new CountDownLatch(1);

        LooperThread(String name) {
            super(name);
        }

        public String getLooper() {
            return "looper of " + getName();
        }

        @Override
        public void run() {
            try {
                done.await();
            } catch (InterruptedException ignored) {
            }
        }
    }

    // The real LuaJ finds the live thread by public methods only
    @Test
    public void threadOfTheManagerIsFoundByLuaJ() throws Exception {
        Globals real = Scripts.load("nsdmanager_listener", null);
        LooperThread shared = new LooperThread("ConnectivityThread");
        shared.start();
        try {
            assertEquals("looper of ConnectivityThread", Scripts.run(real, "return threadlooper()").tojstring());
            LooperThread own = new LooperThread("NsdManager");
            own.start();
            try {
                assertEquals("looper of NsdManager", Scripts.run(real, "return threadlooper()").tojstring());
            } finally {
                own.done.countDown();
                own.join();
            }
        } finally {
            shared.done.countDown();
            shared.join();
        }
    }

    // Android throws for these itself, before it asks the service: the call goes on, nothing is answered
    @Test
    public void wrongArgumentsGoOnToAndroid() {
        String[] calls = {
                "hookof('discoverServices', table.unpack(DISCOVER)), paramof({ [0] = '_ipp._tcp', [1] = 1 })",
                "hookof('discoverServices', table.unpack(DISCOVER)), paramof({ [0] = '', [1] = 1, [2] = listener })",
                "hookof('discoverServices', table.unpack(DISCOVER)), paramof({ [1] = 1, [2] = listener })",
                "hookof('discoverServices', table.unpack(DISCOVER)), paramof({ [0] = '_ipp._tcp', [1] = 2, [2] = listener })",
                "hookof('discoverServices', table.unpack(REQUEST)), " +
                        "paramof({ [0] = '_ipp._tcp', [1] = 1, [3] = executor, [4] = listener })",
                "hookof('discoverServices', 'android.net.nsd.DiscoveryRequest', 'java.util.concurrent.Executor', " +
                        "'android.net.nsd.NsdManager$DiscoveryListener'), paramof({ [0] = {}, [1] = executor })",
                "hookof('discoverServices', 'android.net.nsd.DiscoveryRequest', 'java.util.concurrent.Executor', " +
                        "'android.net.nsd.NsdManager$DiscoveryListener'), paramof({ [1] = executor, [2] = listener })",
                "hookof('resolveService', table.unpack(RESOLVE)), paramof({ [1] = listener })",
                "hookof('resolveService', table.unpack(RESOLVE)), paramof({ [0] = { getServiceName = function() return '' end, " +
                        "getServiceType = function() return '_ipp._tcp' end }, [1] = listener })",
                "hookof('resolveService', table.unpack(RESOLVE)), paramof({ [0] = { getServiceName = function() return 'tv' end, " +
                        "getServiceType = function() return nil end }, [1] = listener })",
        };
        for (String call : calls) {
            Varargs result = Scripts.run(globals, "return run(" + call + ")");
            assertFalse(call, result.arg(1).toboolean());
            assertFalse(call, result.arg(2).toboolean());
            assertEquals(call, 0, result.arg(4).toint());
        }
        // The listener is not marked in use by them
        answered("return run(hookof('resolveService', table.unpack(RESOLVE)), paramof({ [0] = tv, [1] = listener }))",
                "manager", "resolve tv 0");
    }

    // Until the answer the listener is in use: starting again throws like Android, stopping does nothing visible;
    // after the answer stopping goes on to Android, which throws for the listener it forgot, and the listener is free
    @Test
    public void listenerIsInUseUntilTheAnswer() {
        Varargs result = Scripts.run(globals,
                "posted = {} answers = {}\n" +
                        "before(hookof('discoverServices', table.unpack(DISCOVER)), " +
                        "paramof({ [0] = '_ipp._tcp', [1] = 1, [2] = listener }))\n" +
                        "local again = paramof({ [0] = tv, [1] = listener })\n" +
                        "local refused = before(hookof('resolveService', table.unpack(RESOLVE)), again)\n" +
                        "local stop = paramof({ [0] = listener })\n" +
                        "local stopped = before(hookof('stopServiceDiscovery'), stop)\n" +
                        "local twice = paramof({ [0] = listener })\n" +
                        "before(hookof('stopServiceDiscovery'), twice)\n" +
                        "local other = paramof({ [0] = {} })\n" +
                        "before(hookof('stopServiceResolution'), other)\n" +
                        "local none = paramof({})\n" +
                        "before(hookof('unregisterServiceInfoCallback'), none)\n" +
                        "local waiting = #posted\n" +
                        "for _, post in ipairs(posted) do post[2].run() end\n" +
                        "local late = paramof({ [0] = listener })\n" +
                        "before(hookof('stopServiceDiscovery'), late)\n" +
                        "return refused, again.result.class, again.result.message, stopped, stop.set, twice.set, other.set, " +
                        "none.set, waiting, table.concat(answers, ', '), late.set");
        assertTrue(result.arg(1).toboolean());
        assertEquals("java.lang.IllegalArgumentException", result.arg(2).tojstring());
        assertEquals("listener already in use", result.arg(3).tojstring());
        assertFalse(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        assertFalse(result.arg(8).toboolean());
        assertEquals(1, result.arg(9).toint());
        assertEquals("discover _ipp._tcp 0", result.arg(10).tojstring());
        assertFalse(result.arg(11).toboolean());
        // Free again after the answer
        answered("return run(hookof('resolveService', table.unpack(RESOLVE)), paramof({ [0] = tv, [1] = listener }))",
                "manager", "resolve tv 0");
    }

    // Android follows the networks of a request itself and answers that discovery started; it never forgets the
    // listener, so every stop answers that discovery stopped and starting with it again throws
    @Test
    public void discoveryByARequestStartsAndStops() {
        answered("return run(hookof('discoverServices', table.unpack(REQUEST)), " +
                        "paramof({ [0] = '_ipp._tcp', [1] = 1, [2] = 'request', [3] = executor, [4] = listener }))",
                "manager", "executor, started _ipp._tcp");
        for (int stop = 0; stop < 2; stop++) {
            Varargs result = Scripts.run(globals,
                    "return run(hookof('stopServiceDiscovery', 'android.net.nsd.NsdManager$DiscoveryListener'), " +
                            "paramof({ [0] = listener }))");
            assertFalse(result.arg(1).toboolean());
            assertTrue(result.arg(2).toboolean());
            assertEquals(0, result.arg(3).toint());
            assertEquals(1, result.arg(4).toint());
            assertEquals("manager", result.arg(5).tojstring());
            assertEquals("executor, stopped _ipp._tcp", result.arg(6).tojstring());
        }
        Varargs again = Scripts.run(globals,
                "local again = paramof({ [0] = '_ipp._tcp', [1] = 1, [2] = 'request', [3] = executor, [4] = listener })\n" +
                        "local restricted = before(hookof('discoverServices', table.unpack(REQUEST)), again)\n" +
                        "return restricted, again.result.class, again.result.message");
        assertTrue(again.arg(1).toboolean());
        assertEquals("java.lang.IllegalArgumentException", again.arg(2).tojstring());
        assertEquals("listener already in use", again.arg(3).tojstring());
        // A stop before the answer comes after it, on the same handler
        Varargs early = Scripts.run(globals,
                "posted = {} answers = {}\n" +
                        "local other = setmetatable({}, { __index = listener })\n" +
                        "before(hookof('discoverServices', table.unpack(REQUEST)), " +
                        "paramof({ [0] = '_hap._tcp', [1] = 1, [2] = 'request', [3] = executor, [4] = other }))\n" +
                        "before(hookof('stopServiceDiscovery'), paramof({ [0] = other }))\n" +
                        "for _, post in ipairs(posted) do post[2].run() end\n" +
                        "return table.concat(answers, ', ')");
        assertEquals("executor, started _hap._tcp, executor, stopped _hap._tcp", early.arg(1).tojstring());
    }

    // Both scripts find the handler the same way
    @Test
    public void handlerFieldIsTheSameAsWifiDiscovery() throws Exception {
        assertEquals(Scripts.function("nsdmanager_listener", "handlerfield"), Scripts.function("wifi_discovery", "handlerfield"));
    }
}
