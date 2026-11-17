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

// The SDKs analytics_start declines right before they start: LevelPlay (ironSource) and OneSignal once per process by
// the setters of their version, Bugsnag by the configuration of each client, the do not sell flag of LevelPlay, and the
// requests of OneSignal its consent does not hold back
public class AnalyticsStartScriptTest {
    private static final String STUBS = String.join("\n",
            "calls = {}",
            // An SDK class with the static setters given, which records each call; a missing one fails like in Java
            "function sdkof(name, methods)",
            "  local sdk = {}",
            "  for _, method in ipairs(methods) do",
            "    sdk[method] = function(self, ...) table.insert(calls, name .. '.' .. method .. '=' ..",
            "      table.concat({ tostring(select(1, ...)), select(2, ...) and tostring(select(2, ...)) or nil }, ',')) end",
            "  end",
            "  return setmetatable(sdk, { __index = function(self, method) error('no method ' .. method) end })",
            "end",
            "classes = {}",
            "local scoped = {}",
            "function paramof(args)",
            "  return { args = args or {}, set = false,",
            "    getApplicationContext = function(self) return 'context' end,",
            "    getValue = function(self, name, scope) assert(scope == 'context') return scoped[name] end,",
            "    putValue = function(self, name, value, scope) assert(scope == 'context') scoped[name] = value end,",
            "    findClass = function(self, name) return classes[name] or error('ClassNotFoundException ' .. name) end,",
            "    getArgument = function(self, i) return self.args[i] end,",
            "    setArgument = function(self, i, v) self.args[i] = v self.set = true end,",
            "    setResult = function(self, v) self.result = v end }",
            "end",
            "function hookof(method, class, types)",
            "  return { getMethodName = function(self) return method end, getClassName = function(self) return class end,",
            "    getParameterTypes = function(self) return types or {} end }",
            "end");

    private static final String LEVELPLAY = "com.unity3d.mediation.LevelPlay";
    private static final String IRONSOURCE = "com.ironsource.mediationsdk.IronSource";

    private static Globals load() throws Exception {
        return Scripts.load("analytics_start", STUBS);
    }

    // Version 9 by LevelPlay, once per process, also when IronSource starts after it
    @Test
    public void levelPlayIsDeclinedOnce() throws Exception {
        Varargs result = Scripts.run(load(), "classes['" + LEVELPLAY + "'] = sdkof('LevelPlay', { 'setConsent', 'setMetaData' })\n" +
                "classes['" + IRONSOURCE + "'] = sdkof('IronSource', { 'setConsent', 'setMetaData' })\n" +
                "local first = before(hookof('init', '" + LEVELPLAY + "'), paramof())\n" +
                "local again = before(hookof('init', '" + IRONSOURCE + "'), paramof())\n" +
                "return first, again, table.concat(calls, ' ')");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertEquals("LevelPlay.setConsent=false LevelPlay.setMetaData=do_not_sell,true", result.arg(3).tojstring());
    }

    // Version 8 and before: LevelPlay has no setters, IronSource has them
    @Test
    public void ironSourceIsDeclinedBeforeVersion9() throws Exception {
        Varargs result = Scripts.run(load(), "classes['" + LEVELPLAY + "'] = sdkof('LevelPlay', {})\n" +
                "classes['" + IRONSOURCE + "'] = sdkof('IronSource', { 'setConsent', 'setMetaData' })\n" +
                "return before(hookof('init', '" + LEVELPLAY + "'), paramof()), table.concat(calls, ' ')");
        assertTrue(result.arg(1).toboolean());
        assertEquals("IronSource.setConsent=false IronSource.setMetaData=do_not_sell,true", result.arg(2).tojstring());
        // Neither class: nothing restricted
        assertFalse(Scripts.run(load(), "return before(hookof('init', '" + IRONSOURCE + "'), paramof())").arg1().toboolean());
    }

    // The app's do not sell flag is true, by a key and value of any case; other keys stay
    @Test
    public void doNotSellOfTheAppIsTrue() throws Exception {
        Globals globals = load();
        String[][] cases = {
                // key, value, restricted, value after
                {"'do_not_sell'", "'false'", "true", "true"}, {"'DO_NOT_SELL'", "'no'", "true", "true"},
                {"'do_not_sell'", "'TRUE'", "false", "TRUE"}, {"'is_child_directed'", "'false'", "false", "false"},
                {"nil", "'false'", "false", "false"}};
        for (String[] c : cases) {
            Varargs result = Scripts.run(globals, "local param = paramof({ [0] = " + c[0] + ", [1] = " + c[1] + " })\n" +
                    "return before(hookof('setMetaData', '" + LEVELPLAY + "'), param), param.args[1]");
            assertEquals(c[0] + "=" + c[1], Boolean.parseBoolean(c[2]), result.arg(1).toboolean());
            assertEquals(c[0] + "=" + c[1], c[3], result.arg(2).tojstring());
        }
    }

    // The form with a list of values gets a list holding true, also for a null list, by its declared type
    @Test
    public void doNotSellListOfTheAppIsTrue() throws Exception {
        Globals globals = load();
        String[][] cases = {
                // key, values (nil for a null list), restricted, values after
                {"'do_not_sell'", "{ 'false' }", "true", "[true]"}, {"'Do_Not_Sell'", "nil", "true", "[true]"},
                {"'do_not_sell'", "{ 'true', 'false' }", "true", "[true]"}, {"'do_not_sell'", "{ 'TRUE' }", "false", "[TRUE]"},
                {"'is_child_directed'", "{ 'false' }", "false", "[false]"}};
        for (String[] c : cases) {
            Varargs result = Scripts.run(globals, "local values = " + c[1] + "\n" +
                    "local list = nil\n" +
                    "if values then\n" +
                    "  list = luajava.newInstance('java.util.ArrayList')\n" +
                    "  for _, v in ipairs(values) do list:add(v) end\n" +
                    "end\n" +
                    "local param = paramof({ [0] = " + c[0] + ", [1] = list })\n" +
                    "local hook = hookof('setMetaData', '" + LEVELPLAY + "', { 'java.lang.String', 'java.util.List' })\n" +
                    "local restricted = before(hook, param)\n" +
                    "local after = param.args[1]\n" +
                    "return restricted, after:toString(), rawequal(after, list), pcall(function() after:add('x') end)");
            String name = c[0] + "=" + c[1];
            assertEquals(name, Boolean.parseBoolean(c[2]), result.arg(1).toboolean());
            assertEquals(name, c[3], result.arg(2).tojstring());
            assertEquals(name, !Boolean.parseBoolean(c[2]), result.arg(3).toboolean());
            // A list the SDK can change, like the app's
            assertTrue(name, result.arg(4).toboolean());
        }
    }

    // Consent required and not given, before OneSignal starts, once per process; version 4 by its former names
    @Test
    public void oneSignalRequiresConsentAndHasNone() throws Exception {
        String one = "com.onesignal.OneSignal";
        Varargs result = Scripts.run(load(), "classes['" + one + "'] = sdkof('OneSignal', { 'setConsentRequired', 'setConsentGiven' })\n" +
                "local first = before(hookof('initWithContext', '" + one + "'), paramof())\n" +
                "local again = before(hookof('initWithContextSuspend', '" + one + "'), paramof())\n" +
                "return first, again, table.concat(calls, ' ')");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertEquals("OneSignal.setConsentRequired=true OneSignal.setConsentGiven=false", result.arg(3).tojstring());

        result = Scripts.run(load(), "classes['" + one + "'] =\n" +
                "  sdkof('OneSignal', { 'setRequiresUserPrivacyConsent', 'provideUserConsent' })\n" +
                "return before(hookof('initWithContext', '" + one + "'), paramof()), table.concat(calls, ' ')");
        assertTrue(result.arg(1).toboolean());
        assertEquals("OneSignal.setRequiresUserPrivacyConsent=true OneSignal.provideUserConsent=false", result.arg(2).tojstring());
    }

    // The GET requests of OneSignal get the answer of those it holds back without consent, and the upload of its logs
    // fails like without a network; without the classes of the answers the requests stay as they are
    @Test
    public void oneSignalRequestsFail() throws Exception {
        // The real luajava with stand-ins of the response classes, so the constructors are found like in the SDK
        Varargs result = Scripts.run(load(),
                "classes['com.onesignal.core.internal.http.HttpResponse'] =\n" +
                "  luajava.bindClass('com.onesignal.core.internal.http.HttpResponse')\n" +
                "classes['com.onesignal.logger.LogHttpResponse'] = luajava.bindClass('com.onesignal.logger.LogHttpResponse')\n" +
                "local get = paramof({ [0] = 'apps/id/android_params.js' })\n" +
                "local sent = before(hookof('get', 'com.onesignal.core.internal.http.impl.HttpClient'), get)\n" +
                "local request = { getUrl = function(self) return 'https://api.onesignal.com:443/sdk/otel/v1/logs?a=1' end }\n" +
                "local log = paramof({ [0] = request })\n" +
                "local sender = 'com.onesignal.debug.internal.logging.logger.android.OneSignalLogHttpSender'\n" +
                "local logged = before(hookof('send', sender), log)\n" +
                "local r, l = get.result, log.result\n" +
                "return sent, r:getStatusCode(), r:getPayload() == nil and r:getThrowable() == nil and\n" +
                "  r:getRetryAfterSeconds() == nil and r:getRetryLimit() == nil,\n" +
                "  logged, l:getSuccess(), l:getCode(), l:getMessage()");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).toint());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
        assertEquals(-1, result.arg(6).toint());
        assertEquals("Unable to resolve host \"api.onesignal.com\": No address associated with hostname", result.arg(7).tojstring());

        result = Scripts.run(load(), "local param = paramof()\n" +
                "return before(hookof('get', 'com.onesignal.core.internal.http.impl.HttpClient'), param), param.result");
        assertFalse(result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());
    }

    // Each configuration a Bugsnag client starts with enables no release stage, by the real luajava
    @Test
    public void bugsnagEnablesNoReleaseStage() throws Exception {
        Varargs result = Scripts.run(load(), "local stages = {}\n" +
                "local config = { setEnabledReleaseStages = function(self, set) stages[#stages + 1] = set end }\n" +
                "local hook = hookof(nil, 'com.bugsnag.android.Client')\n" +
                "local first = before(hook, paramof({ [0] = 'context', [1] = config }))\n" +
                "local second = before(hook, paramof({ [0] = 'context', [1] = config }))\n" +
                "return first, second, #stages, stages[1]:isEmpty(), stages[1]:add('production'),\n" +
                "  before(hook, paramof({ [0] = 'context' })), before(hookof('start', 'com.example.Other'), paramof())");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(2, result.arg(3).toint());
        assertTrue(result.arg(4).toboolean());
        // A plain set, like the one an app gives
        assertTrue(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
    }
}
