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

// The consent of Unity Ads (unityads_consent): none before the SDK starts, by the setters of version 4 or the MetaData of
// former versions, and none the app gives by MetaData
public class UnityAdsScriptTest {
    private static final String STUBS = String.join("\n",
            "calls = {}",
            "committed = {}",
            // Version 4 with its setters, or a former version without them (setters = false)
            "setters = true",
            "ads = setmetatable({}, { __index = function(self, name)",
            "  if not setters then error('no method ' .. name) end",
            "  return function(class, value) table.insert(calls, name .. '=' .. tostring(value)) end",
            "end })",
            "metadataclass = {}",
            "luajava = { new = function(class, context)",
            "  assert(class == metadataclass and context == 'context')",
            "  local values = {}",
            "  return { set = function(self, key, value) values[key] = value; return true end,",
            "    commit = function(self) for key, value in pairs(values) do committed[key] = value end end }",
            "end }",
            "local scoped = {}",
            "function paramof(args)",
            "  return { args = args,",
            "    getApplicationContext = function(self) return 'context' end,",
            "    getValue = function(self, name, scope) return scoped[name] end,",
            "    putValue = function(self, name, value, scope) scoped[name] = value end,",
            "    findClass = function(self, name)",
            "      if name == 'com.unity3d.ads.UnityAds' then return ads end",
            "      assert(name == 'com.unity3d.ads.metadata.MetaData', name)",
            "      return metadataclass",
            "    end,",
            "    getArgument = function(self, i) return self.args[i] end,",
            "    setArgument = function(self, i, v) self.args[i] = v end }",
            "end",
            "function hookof(method)",
            "  return { getMethodName = function(self) return method end }",
            "end");

    private static Globals load() throws Exception {
        return Scripts.load("unityads_consent", STUBS);
    }

    // Version 4: the setters, once per process
    @Test
    public void noConsentBeforeTheStart() throws Exception {
        Globals globals = load();
        Varargs result = Scripts.run(globals, "local first = before(hookof('initialize'), paramof({}))\n" +
                "local second = before(hookof('initialize'), paramof({}))\n" +
                "table.sort(calls)\n" +
                "return first, second, table.concat(calls, ',')");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertEquals("setNonBehavioral=true,setUserConsent=false,setUserOptOut=true", result.arg(3).tojstring());
    }

    // Former versions: the keys of MetaData, each committed
    @Test
    public void noConsentByMetaDataOfFormerVersions() throws Exception {
        Varargs result = Scripts.run(load(), "setters = false\n" +
                "local restricted = before(hookof('initialize'), paramof({}))\n" +
                "return restricted, committed['gdpr.consent'], committed['privacy.consent'], committed['user.nonbehavioral']");
        assertTrue(result.arg(1).toboolean());
        assertEquals("false", result.arg(2).tojstring());
        assertEquals("false", result.arg(3).tojstring());
        assertEquals("true", result.arg(4).tojstring());
    }

    // The consent the app gives by MetaData is replaced by none; other keys stay
    @Test
    public void consentOfTheAppIsNone() throws Exception {
        Globals globals = load();
        String[][] cases = {
                {"'gdpr.consent'", "true", "true", "false"}, {"'privacy.consent'", "true", "true", "false"},
                {"'pipl.consent'", "1", "true", "false"}, {"'consent'", "true", "true", "false"},
                {"'gdpr.consent'", "false", "false", "false"},
                {"'user.nonbehavioral'", "false", "true", "true"}, {"'nonbehavioral'", "false", "true", "true"},
                {"'mediation.name'", "'admob'", "false", "admob"}};
        for (String[] c : cases) {
            Varargs result = Scripts.run(globals, "local param = paramof({ [0] = " + c[0] + ", [1] = " + c[1] + " })\n" +
                    "return before(hookof('set'), param), param.args[1]");
            assertEquals(c[0] + "=" + c[1], Boolean.parseBoolean(c[2]), result.arg(1).toboolean());
            assertEquals(c[0] + "=" + c[1], c[3], result.arg(2).tojstring());
        }
    }
}
