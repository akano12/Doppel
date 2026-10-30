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

import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Keyboards, spell checkers and accessibility services of hidden apps are left out of what an app gets,
// see service_filter and spellchecker_filter
public class ServiceFilterScriptTest {
    // A visible and a hidden service; accessibility services have their app in the resolve info,
    // the others in the service info, so a script reading the wrong one fails
    private static final String STUBS = String.join("\n",
            Scripts.ARRAYOF,
            "function hookof(cls, method)",
            "  return { getClassName = function(self) return cls end, getMethodName = function(self) return method end }",
            "end",
            "function paramof(result)",
            "  return { result = result, set = false,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value self.set = true end,",
            "    isVisible = function(self, ai) return ai.packageName ~= 'com.other.hidden' end }",
            "end",
            "function serviceof(pkg)",
            "  local ai = { packageName = pkg }",
            "  return { getServiceInfo = function(self) return { applicationInfo = ai } end,",
            "    getPackageName = function(self) return pkg end }",
            "end",
            "function accessibilityof(pkg)",
            "  local ai = { packageName = pkg }",
            "  return { getResolveInfo = function(self) return { serviceInfo = { applicationInfo = ai } } end }",
            "end",
            "function listof(...)",
            "  local items = arrayof(...)",
            "  return { toArray = function(self) return items end }",
            "end",
            // What a filtered list holds: the packages, read like the script reads them
            "function packages(cls, list)",
            "  local result = {}",
            "  for index = 0, list:size() - 1 do",
            "    local item = list:get(index)",
            "    result[#result + 1] = (cls == 'android.view.accessibility.AccessibilityManager'",
            "      and item:getResolveInfo().serviceInfo.applicationInfo or item:getServiceInfo().applicationInfo).packageName",
            "  end",
            "  return table.concat(result, ',')",
            "end",
            "function filter(cls, method, result)",
            "  local param = paramof(result)",
            "  local restricted, old, new = after(hookof(cls, method), param)",
            "  return restricted, param.set, param.result, old, new",
            "end");

    @Test
    public void listsLeaveOutHiddenServices() throws Exception {
        String[][] lists = {
                {"service_filter", "android.view.inputmethod.InputMethodManager", "getInputMethodList", "serviceof"},
                {"service_filter", "android.view.inputmethod.InputMethodManager", "getEnabledInputMethodList", "serviceof"},
                {"service_filter", "android.view.accessibility.AccessibilityManager", "getEnabledAccessibilityServiceList", "accessibilityof"},
                {"service_filter", "android.view.accessibility.AccessibilityManager", "getInstalledAccessibilityServiceList", "accessibilityof"},
                {"spellchecker_filter", "android.view.textservice.TextServicesManager", "getEnabledSpellCheckerInfos", "serviceof"}};
        for (String[] list : lists) {
            Globals globals = Scripts.load(list[0], STUBS);
            String call = "filter('" + list[1] + "', '" + list[2] + "', ";

            Varargs result = Scripts.run(globals, "local restricted, set, list = " + call +
                    "listof(" + list[3] + "('com.android.inputmethod'), " + list[3] + "('com.other.hidden')))\n" +
                    "return restricted, set, packages('" + list[1] + "', list)");
            assertTrue(list[2], result.arg(1).toboolean());
            assertTrue(list[2], result.arg(2).toboolean());
            assertEquals(list[2], "com.android.inputmethod", result.arg(3).tojstring());

            // Nothing hidden or no list: the result stays
            result = Scripts.run(globals, "return " + call + "listof(" + list[3] + "('com.android.inputmethod')))");
            assertFalse(list[2], result.arg(1).toboolean());
            assertFalse(list[2], result.arg(2).toboolean());
            assertFalse(list[2], Scripts.run(globals, "return " + call + "nil)").arg(1).toboolean());
        }
    }

    @Test
    public void currentServiceOfHiddenAppIsNone() throws Exception {
        String[][] currents = {
                {"service_filter", "android.view.inputmethod.InputMethodManager", "getCurrentInputMethodInfo"},
                {"spellchecker_filter", "android.view.textservice.TextServicesManager", "getCurrentSpellCheckerInfo"},
                {"spellchecker_filter", "android.view.textservice.TextServicesManager", "getCurrentSpellChecker"}};
        for (String[] current : currents) {
            Globals globals = Scripts.load(current[0], STUBS);
            String call = "filter('" + current[1] + "', '" + current[2] + "', serviceof(";

            Varargs result = Scripts.run(globals, "return " + call + "'com.other.hidden'))");
            assertTrue(current[2], result.arg(1).toboolean());
            assertTrue(current[2], result.arg(2).toboolean());
            assertTrue(current[2], result.arg(3).isnil());
            assertEquals(current[2], "com.other.hidden", result.arg(4).tojstring());

            result = Scripts.run(globals, "return " + call + "'com.android.inputmethod'))");
            assertFalse(current[2], result.arg(1).toboolean());
            assertFalse(current[2], result.arg(2).toboolean());
        }
    }

    // Stands in for SpellCheckerInfo, which is final, see spellCheckersStayAnArrayOfTheirType
    public static class Checker {
        private final ServiceInfo info = new ServiceInfo();

        public Checker(String packageName) {
            info.applicationInfo = new ApplicationInfo();
            info.applicationInfo.packageName = packageName;
        }

        public ServiceInfo getServiceInfo() {
            return info;
        }
    }

    // getEnabledSpellCheckers returns SpellCheckerInfo[], so an array of any other type would fail in the app
    @Test
    public void spellCheckersStayAnArrayOfTheirType() throws Exception {
        Globals globals = Scripts.load("spellchecker_filter", STUBS + "\n" +
                "local bind = luajava.bindClass\n" +
                "luajava.bindClass = function(name)\n" +
                "  return bind(name == 'android.view.textservice.SpellCheckerInfo' and '" + Checker.class.getName() + "' or name)\n" +
                "end");
        Checker visible = new Checker("com.android.inputmethod");
        globals.set("checkers", CoerceJavaToLua.coerce(new Checker[]{visible, new Checker("com.other.hidden")}));

        Varargs result = Scripts.run(globals,
                "return filter('android.view.textservice.TextServicesManager', 'getEnabledSpellCheckers', checkers)");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertArrayEquals(new Checker[]{visible}, (Checker[]) result.arg(3).touserdata());
        assertEquals("2", result.arg(4).tojstring());
        assertEquals("1", result.arg(5).tojstring());

        // Nothing hidden: the array of the app stays
        Checker[] none = {visible};
        globals.set("checkers", CoerceJavaToLua.coerce(none));
        result = Scripts.run(globals,
                "return filter('android.view.textservice.TextServicesManager', 'getEnabledSpellCheckers', checkers)");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
    }
}
