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

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The consent strings of the IAB in the preferences of the app (iab_consent): not stored, like in an app without a
// consent tool, for the getters by key and getAll, while the other keys stay
public class IabConsentScriptTest {
    private static final String STUBS = String.join("\n",
            "function paramof(args, result, this)",
            "  return { args = args, result = result, this = this,",
            // getAll has no arguments, for which XParam throws
            "    getArgument = function(self, i) assert(next(self.args) ~= nil, 'no arguments') return self.args[i] end,",
            "    getThis = function(self) return self.this end,",
            "    unhooked = function(self, fn) unhooked = true local value = fn() unhooked = false return value end,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value; self.set = true end }",
            "end",
            "function hookof(method)",
            "  return { getMethodName = function(self) return method end }",
            "end",
            // The preferences of the app, whose values getAll copies, read by the script only without the hooks
            "function prefsof(values)",
            "  local all = luajava.newInstance('java.util.HashMap')",
            "  for key, value in pairs(values) do all:put(key, value) end",
            "  return { getAll = function(self) assert(unhooked, 'hooked') return all end }",
            "end");

    private static Globals load() throws Exception {
        return Scripts.load("iab_consent", STUBS);
    }

    // The TCF, TCF version 1 and GPP keys give the caller's default and contains false, without the real call, also for
    // the getters of other types, which would throw; other keys, also those the filter lets through like ../, go to the
    // real call
    @Test
    public void consentStringsAreNotStored() throws Exception {
        Varargs result = Scripts.run(load(), "local function get(method, key, default)\n" +
                "  local param = paramof({ [0] = key, [1] = default })\n" +
                "  local restricted = before(hookof(method), param)\n" +
                "  return restricted, param.set, param.result\n" +
                "end\n" +
                "local r1, s1, v1 = get('getString', 'IABTCF_TCString', 'none')\n" +
                "local r2, s2, v2 = get('getInt', 'IABTCF_PolicyVersion', -1)\n" +
                "local r3, s3, v3 = get('contains', 'IABConsent_ConsentString')\n" +
                "local r4, s4, v4 = get('getString', 'IABGPP_HDR_GppString', nil)\n" +
                "local r5, s5 = get('getString', 'theme', 'dark')\n" +
                "local r6, s6 = get('contains', '../IABTCF_TCString')\n" +
                "local r7, s7 = get('getString', nil, 'none')\n" +
                "local r8, s8, v8 = get('getBoolean', 'IABConsent_CMPPresent', false)\n" +
                "local r9, s9, v9 = get('getLong', 'IABTCF_CmpSdkID', 7)\n" +
                "local r10, s10, v10 = get('getFloat', 'IABTCF_CmpSdkVersion', 0.5)\n" +
                "local set = luajava.newInstance('java.util.HashSet')\n" +
                "local r11, s11, v11 = get('getStringSet', 'IABGPP_GppSID', set)\n" +
                "return r1, v1, r2, v2, r3, s3, v3, r4, s4, v4, r5, s5, r6, s6, r7, s7,\n" +
                "  r8, v8, r9, v9, r10, v10, r11, v11 == set");
        assertTrue(result.arg(1).toboolean());
        assertEquals("none", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertEquals(-1, result.arg(4).toint());
        assertTrue(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        // A null default is the result, the real value is not read
        assertTrue(result.arg(8).toboolean());
        assertTrue(result.arg(9).toboolean());
        assertTrue(result.arg(10).isnil());
        for (int i = 11; i <= 16; i++)
            assertFalse("result " + i, result.arg(i).toboolean());
        assertTrue(result.arg(17).toboolean());
        assertFalse(result.arg(18).toboolean());
        assertTrue(result.arg(19).toboolean());
        assertEquals(7, result.arg(20).toint());
        assertTrue(result.arg(21).toboolean());
        assertEquals(0.5, result.arg(22).todouble(), 0);
        assertTrue(result.arg(23).toboolean());
        assertTrue(result.arg(24).toboolean());
    }

    // Whether GDPR applies is yes in the type stored, wherever the consent tool stored keys of the IAB, also only other
    // ones, so SDKs which find no consent string have no consent; getInt and getLong give 1 for any type stored, since
    // Amazon reads it only by getInt; contains and the other getters stay real where it is stored, and it is not there
    // without keys of the IAB
    @Test
    public void gdprAppliesWhereConsentToolStoredKeys() throws Exception {
        Varargs result = Scripts.run(load(), "local function get(method, values, default)\n" +
                "  local param = paramof({ [0] = 'IABTCF_gdprApplies', [1] = default }, nil, prefsof(values))\n" +
                "  local restricted = before(hookof(method), param)\n" +
                "  return restricted, param.result\n" +
                "end\n" +
                "local no = { IABTCF_gdprApplies = 0, IABTCF_TCString = 'CP...' }\n" +
                "local r1, v1 = get('getInt', no, -1)\n" +
                "local r2 = get('contains', no)\n" +
                "local r3 = get('getString', no, 'none')\n" +
                "local r4 = get('getLong', no, 5)\n" +
                "local r5 = get('getInt', { IABTCF_gdprApplies = 1 }, -1)\n" +
                "local r6, v6 = get('getString', { IABTCF_gdprApplies = '0' }, nil)\n" +
                "local r7, v7 = get('getInt', { IABTCF_gdprApplies = '0' }, -1)\n" +
                "local only = { IABTCF_TCString = 'CP...' }\n" +
                "local r8, v8 = get('contains', only)\n" +
                "local r9, v9 = get('getInt', only, -1)\n" +
                "local r10 = get('getString', only, 'none')\n" +
                "local none = { theme = 'dark' }\n" +
                "local r11 = get('contains', none)\n" +
                "local r12 = get('getInt', none, -1)\n" +
                "local r13, v13 = get('getInt', { IABTCF_gdprApplies = false, IABTCF_TCString = 'CP...' }, -1)\n" +
                "local r14 = get('getBoolean', { IABTCF_gdprApplies = '0' }, false)\n" +
                "return r1, v1, r2, r3, r4, r5, r6, v6, r7, r8, v8, r9, v9, r10, r11, r12, r13, v13, r14, v7");
        assertTrue(result.arg(1).toboolean());
        assertEquals(1, result.arg(2).toint());
        for (int i : new int[]{3, 4})
            assertFalse("result " + i, result.arg(i).toboolean());
        assertTrue(result.arg(5).toboolean()); // getLong 1 too, like getAll
        assertTrue(result.arg(6).toboolean());
        assertTrue(result.arg(7).toboolean());
        assertEquals("1", result.arg(8).tojstring());
        assertTrue(result.arg(9).toboolean()); // Text, which Amazon reads by getInt
        assertTrue(result.arg(10).toboolean());
        assertTrue(result.arg(11).toboolean());
        assertTrue(result.arg(12).toboolean());
        assertEquals(1, result.arg(13).toint());
        for (int i : new int[]{14, 15, 16})
            assertFalse("result " + i, result.arg(i).toboolean());
        assertTrue(result.arg(17).toboolean());
        assertEquals(1, result.arg(18).toint());
        assertFalse(result.arg(19).toboolean()); // Text, which getBoolean throws for
        assertEquals(1, result.arg(20).toint());
    }

    // getAll gives its copy without the keys of the IAB, which stay stored, but with GDPR applying as the number of the
    // IAB, which SDKs read from it, also where the tool stored text or a boolean; a map without them stays as it is
    @Test
    public void consentStringsAreNotInAll() throws Exception {
        Varargs result = Scripts.run(load(), "local all = luajava.newInstance('java.util.HashMap')\n" +
                "all:put('IABTCF_TCString', 'CP...')\n" +
                "all:put('IABTCF_PurposeConsents', '1111')\n" +
                "all:put('IABConsent_SubjectToGDPR', '1')\n" +
                "all:put('IABGPP_GppSID', '2')\n" +
                "all:put('theme', 'dark')\n" +
                "local param = paramof({}, all)\n" +
                "local restricted = after(hookof('getAll'), param)\n" +
                "local other = luajava.newInstance('java.util.HashMap')\n" +
                "other:put('theme', 'dark')\n" +
                "local no = luajava.newInstance('java.util.HashMap')\n" +
                "no:put('IABTCF_gdprApplies', 0)\n" +
                "no:put('IABTCF_TCString', 'CP...')\n" +
                "local yes = luajava.newInstance('java.util.HashMap')\n" +
                "yes:put('IABTCF_gdprApplies', 1)\n" +
                "local text = luajava.newInstance('java.util.HashMap')\n" +
                "text:put('IABTCF_gdprApplies', '0')\n" +
                "local bool = luajava.newInstance('java.util.HashMap')\n" +
                "bool:put('IABTCF_gdprApplies', false)\n" +
                "after(hookof('getAll'), paramof({}, text))\n" +
                "after(hookof('getAll'), paramof({}, bool))\n" +
                "return restricted, param.set, all:size(), all:get('theme'), after(hookof('getAll'), paramof({}, other)),\n" +
                "  other:size(), after(hookof('getAll'), paramof({}, nil)), before(hookof('getAll'), paramof({})),\n" +
                "  after(hookof('getString'), paramof({ [0] = 'IABTCF_TCString' }, 'CP...')), all,\n" +
                "  after(hookof('getAll'), paramof({}, no)), no, after(hookof('getAll'), paramof({}, yes)), yes:size(), text, bool");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean()); // The same copy
        assertEquals(2, result.arg(3).toint());
        assertEquals("dark", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());
        assertEquals(1, result.arg(6).toint());
        assertFalse(result.arg(7).toboolean());
        assertFalse(result.arg(8).toboolean());
        assertFalse(result.arg(9).toboolean());
        // An Integer, which Amazon checks for
        assertEquals(1, ((Map<?, ?>) result.arg(10).touserdata()).get("IABTCF_gdprApplies"));
        assertTrue(result.arg(11).toboolean());
        assertEquals(Collections.singletonMap("IABTCF_gdprApplies", 1), result.arg(12).touserdata());
        assertFalse(result.arg(13).toboolean()); // Stored as yes, only GDPR applying
        assertEquals(1, result.arg(14).toint());
        assertEquals(1, ((Map<?, ?>) result.arg(15).touserdata()).get("IABTCF_gdprApplies"));
        assertEquals(1, ((Map<?, ?>) result.arg(16).touserdata()).get("IABTCF_gdprApplies"));
    }

    // Only the keys of the IAB run the script for the getters by key, every call of getAll does
    @Test
    public void onlyConsentKeysRunTheScript() throws Exception {
        Varargs result = Scripts.run(load(), "return table.concat(filter(hookof('getString')), ','),\n" +
                "  table.concat(filter(hookof('contains')), ','), filter(hookof('getAll'))");
        assertEquals("IABTCF_*,IABConsent_*,IABGPP_*", result.arg(1).tojstring());
        assertEquals(result.arg(1).tojstring(), result.arg(2).tojstring());
        assertTrue(result.arg(3).isnil());
    }
}
