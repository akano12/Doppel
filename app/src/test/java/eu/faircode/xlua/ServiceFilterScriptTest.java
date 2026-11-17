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
// see service_filter
public class ServiceFilterScriptTest {
    // A visible and a hidden service; accessibility services have their app in the resolve info,
    // the others in the service info, so a script reading the wrong one fails. The app has uid 10100, and
    // isVisible keeps the services of the system, like com.google.android.marvin.talkback; the accessibility service of
    // "android" has the uid of the system, like the one autofill reports in its compatibility mode
    private static final String STUBS = String.join("\n",
            Scripts.ARRAYOF,
            "function hookof(cls, method)",
            "  return { getClassName = function(self) return cls end, getMethodName = function(self) return method end }",
            "end",
            "function paramof(result)",
            "  return { result = result, set = false,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value self.set = true end,",
            "    getUid = function(self) return 10100 end,",
            "    isSystem = function(self) return system == true end,",
            "    isVisible = function(self, ai) return ai.packageName ~= 'com.other.hidden' end }",
            "end",
            "function serviceof(pkg)",
            "  local ai = { packageName = pkg }",
            "  return { getServiceInfo = function(self) return { applicationInfo = ai } end,",
            "    getPackageName = function(self) return pkg end }",
            "end",
            "function accessibilityof(pkg)",
            "  local ai = { packageName = pkg, uid = (pkg == 'com.example.app' and 10100 or pkg == 'android' and 1000 or 10200) }",
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
        // With the service which stays: of the system, or of the app itself for accessibility services
        String[][] lists = {
                {"service_filter", "android.view.inputmethod.InputMethodManager", "getInputMethodList", "serviceof",
                        "com.android.inputmethod"},
                {"service_filter", "android.view.inputmethod.InputMethodManager", "getEnabledInputMethodList", "serviceof",
                        "com.android.inputmethod"},
                {"service_filter", "android.view.accessibility.AccessibilityManager", "getEnabledAccessibilityServiceList",
                        "accessibilityof", "com.example.app"},
                {"service_filter", "android.view.accessibility.AccessibilityManager", "getInstalledAccessibilityServiceList",
                        "accessibilityof", "com.example.app"},
                {"service_filter", "android.view.textservice.TextServicesManager", "getEnabledSpellCheckerInfos", "serviceof",
                        "com.android.inputmethod"}};
        for (String[] list : lists) {
            Globals globals = Scripts.load(list[0], STUBS);
            String call = "filter('" + list[1] + "', '" + list[2] + "', ";
            String kept = list[3] + "('" + list[4] + "')";

            Varargs result = Scripts.run(globals, "local restricted, set, list, old, new = " + call +
                    "listof(" + kept + ", " + list[3] + "('com.other.hidden')))\n" +
                    "return restricted, set, packages('" + list[1] + "', list), old, new");
            assertTrue(list[2], result.arg(1).toboolean());
            assertTrue(list[2], result.arg(2).toboolean());
            assertEquals(list[2], list[4], result.arg(3).tojstring());
            // The log tells how many there were and how many are left
            assertEquals(list[2], "2", result.arg(4).tojstring());
            assertEquals(list[2], "1", result.arg(5).tojstring());
            // Accessibility services come in an unmodifiable list, like AccessibilityManager returns them
            Varargs kind = Scripts.run(globals, "local restricted, set, list = " + call +
                    "listof(" + kept + ", " + list[3] + "('com.other.hidden')))\n" +
                    "return pcall(function() list:add(list:get(0)) end)");
            assertEquals(list[2], !list[1].endsWith("AccessibilityManager"), kind.arg1().toboolean());

            // Nothing hidden or no list: the result stays
            result = Scripts.run(globals, "return " + call + "listof(" + kept + "))");
            assertFalse(list[2], result.arg(1).toboolean());
            assertFalse(list[2], result.arg(2).toboolean());
            assertFalse(list[2], Scripts.run(globals, "return " + call + "nil)").arg(1).toboolean());
        }
    }

    // Accessibility services of system apps are hidden too, since they tell how the user uses the device, like a
    // preinstalled screen reader, and so is the service of "android" which autofill reports in its compatibility mode,
    // which tells that the user has a password manager; processes of the system see them
    @Test
    public void accessibilityServicesOfSystemAppsAreHidden() throws Exception {
        Globals globals = Scripts.load("service_filter", STUBS);
        for (String method : new String[]{"getEnabledAccessibilityServiceList", "getInstalledAccessibilityServiceList"})
            for (String service : new String[]{"com.google.android.marvin.talkback", "android"}) {
                String call = "local restricted, set, list = filter('android.view.accessibility.AccessibilityManager', '" +
                        method + "', listof(accessibilityof('" + service + "')))\n" +
                        "return restricted, set, set and list:size()";
                Varargs result = Scripts.run(globals, call);
                assertTrue(method, result.arg(1).toboolean());
                assertTrue(method, result.arg(2).toboolean());
                assertEquals(method, 0, result.arg(3).toint());
                Scripts.run(globals, "system = true");
                assertFalse(method, Scripts.run(globals, call).arg1().toboolean());
                Scripts.run(globals, "system = false");
            }
    }

    // Accessibility and touch exploration are off for the app when only hidden services turn them on, like on a device
    // without them; the framework, whose views send their events only while accessibility is on, gets them on
    @Test
    public void accessibilityIsOffForOnlyHiddenServices() throws Exception {
        Globals globals = Scripts.load("service_filter", STUBS + "\n" + String.join("\n",
                // Enabled services by their package, flags and capabilities; with flag 4 a service asks for touch
                // exploration, which capability 2 lets it turn on
                "function enabledof(...)",
                "  local items = {}",
                "  for index, spec in ipairs({ ... }) do",
                "    items[index] = accessibilityof(spec[1])",
                "    items[index].flags = spec[2]",
                "    items[index].getCapabilities = function(self) return spec[3] or 0 end",
                "  end",
                "  enabled = listof(table.unpack(items))",
                "end",
                // Frames named like class.method; the stacks are those of LSPosed, which replaces the frame of the
                // hooked method by one of a class of its own with a random name
                "function stackof(...)",
                "  local frames = {}",
                "  for index, frame in ipairs({ 'eu.faircode.xlua.XCore$6.after', 'eu.faircode.xlua.ModernBackend.lambda$hook$0',",
                "      'm.proceed', 'android.view.accessibility.QuoaghiehZeiphoo.' .. method, ... }) do",
                "    local class, called = string.match(frame, '^(.*)%.([^.]*)$')",
                "    frames[index] = { getClassName = function(self) return class end,",
                "      getMethodName = function(self) return called end }",
                "  end",
                "  frames.length = #frames",
                "  stack = frames",
                "end",
                "walks, reads, lookups = 0, 0, 0",
                "function callers() walks = walks + 1 return stack end",
                // The classes of the app, also named like the framework, and of the hook engine are not of the boot class path
                "function boot(class) return not string.find(class, 'example', 1, true) and string.sub(class, 1, 12) ~= 'org.lsposed.' end",
                "function state(name, result)",
                "  local param = paramof(result)",
                "  param.getApplicationContext = function(self)",
                "    return { getSystemService = function(self, service)",
                "      assert(service == 'accessibility', service) lookups = lookups + 1",
                "      return { getEnabledAccessibilityServiceList = function(self, type)",
                "        assert(type == -1) reads = reads + 1 return enabled end }",
                "    end }",
                "  end",
                // The values are kept only for the test of the cache
                "  param.getValue = function(self, name, scope) return values and values[name] end",
                "  param.putValue = function(self, name, value, scope) if values then values[name] = value end end",
                "  param.unhooked = function(self, fn) return fn() end",
                "  local restricted = after(hookof('android.view.accessibility.AccessibilityManager', name), param)",
                "  return restricted, param.result",
                "end"));
        String enabled = "return state('isEnabled', true)", touch = "return state('isTouchExplorationEnabled', true)";

        // The app gets them off, also by reflection, also when the framework calls them by reflection for the app, like
        // android.util.Property and an ObjectAnimator of it, and from within a method of the framework of the same name
        Scripts.run(globals, "enabledof({ 'com.other.hidden', 4 })");
        for (String callers : new String[]{"'com.example.app.Main.onCreate'", "'java.lang.reflect.Method.invoke', " +
                "'com.example.app.Main.onCreate'", "'java.lang.reflect.Method.invoke', 'android.util.ReflectiveProperty.get', " +
                "'com.example.app.Main.onCreate'", "'java.lang.reflect.Method.invoke', 'android.util.ReflectiveProperty.get', " +
                "'android.animation.PropertyValuesHolder.setupSetterAndGetter', 'android.animation.ObjectAnimator.initAnimation', " +
                "'android.animation.ValueAnimator.start', 'android.view.Choreographer.doFrame'",
                "'com.example.app.Main.check', 'android.widget.ArrayAdapter.isEnabled', " +
                "'android.widget.ListView.layoutChildren'", "'android.example.Check.run'"})
            for (String call : new String[]{enabled, touch}) {
                Scripts.run(globals, "method = '" + (call.equals(enabled) ? "isEnabled" : "isTouchExplorationEnabled") +
                        "' stackof(" + callers + ")");
                Varargs result = Scripts.run(globals, call);
                assertTrue(callers, result.arg1().toboolean());
                assertFalse(callers, result.arg(2).toboolean());
            }
        // Also for a service of a system app alone, like a preinstalled screen reader, which asks for touch exploration
        // and can turn it on
        Scripts.run(globals, "enabledof({ 'com.google.android.marvin.talkback', 4, 2 })");
        for (String call : new String[]{enabled, touch}) {
            Scripts.run(globals, "method = '" + (call.equals(enabled) ? "isEnabled" : "isTouchExplorationEnabled") +
                    "' stackof('com.example.app.Main.onCreate')");
            Varargs result = Scripts.run(globals, call);
            assertTrue(call, result.arg1().toboolean());
            assertFalse(call, result.arg(2).toboolean());
        }
        // And for the service of "android" alone, which autofill reports in its compatibility mode, where it turns
        // accessibility on for the app (see the README limit)
        Scripts.run(globals, "enabledof({ 'android', 0 }) method = 'isEnabled' stackof('com.example.app.Main.onCreate')");
        Varargs compat = Scripts.run(globals, enabled);
        assertTrue(compat.arg1().toboolean());
        assertFalse(compat.arg(2).toboolean());
        // The framework gets them on, also from within a method of the app of the same name, and so does AccessibilityManager
        // itself, also for an event the app sends itself, by reflection too, which would throw "Accessibility off" for an app
        // whose listeners heard accessibility go on
        for (String callers : new String[]{"'android.view.View.sendAccessibilityEventInternal', 'com.example.app.Main.onCreate'",
                "'android.view.accessibility.AccessibilityManager.sendAccessibilityEvent', 'com.example.app.Main.onCreate'",
                "'android.view.accessibility.AccessibilityManager.interrupt', 'com.example.app.Main.onCreate'",
                "'android.view.accessibility.AccessibilityManager.sendAccessibilityEvent', 'java.lang.reflect.Method.invoke', " +
                        "'com.example.app.Main.onCreate'",
                "'android.view.accessibility.AccessibilityManager.sendAccessibilityEvent', " +
                        "'android.view.ViewRootImpl.requestSendAccessibilityEvent', 'android.view.View.sendAccessibilityEvent'",
                // The listeners, also of the framework, which keeps its windows reachable for the services by them
                "'android.view.accessibility.AccessibilityManager.notifyAccessibilityStateChanged', " +
                        "'android.view.accessibility.AccessibilityManager.setStateLocked', " +
                        "'android.view.accessibility.AccessibilityManager$1.setState', 'android.os.Binder.execTransact'",
                "'android.view.View.notifyViewAccessibilityStateChangedIfNeeded', 'com.example.app.Row.isEnabled', " +
                        "'com.example.app.Main.onCreate'"}) {
            Scripts.run(globals, "method = 'isEnabled' stackof(" + callers + ")");
            Varargs result = Scripts.run(globals, enabled);
            assertFalse(callers, result.arg1().toboolean());
            assertTrue(callers, result.arg(2).toboolean());
        }

        // AccessibilityManager asks while it is made, within the lookup of it by the context of the app, for which a lookup
        // of it would wait forever: it gets them as they are without a lookup, also when the app makes it by reflection, and
        // when it connects again to its service within another hooked method of it, below the frames of the hook engine
        String made = "'android.view.accessibility.AccessibilityManager.setStateLocked', " +
                "'android.view.accessibility.AccessibilityManager.tryConnectToServiceLocked', " +
                "'android.view.accessibility.AccessibilityManager.<init>', 'android.view.accessibility.AccessibilityManager.getInstance', ";
        for (String callers : new String[]{made + "'android.app.SystemServiceRegistry$8.createService', " +
                "'android.app.SystemServiceRegistry$CachedServiceFetcher.getService', " +
                "'android.app.SystemServiceRegistry.getSystemService', 'android.app.ContextImpl.getSystemService', " +
                "'android.content.ContextWrapper.getSystemService', " +
                "'com.example.app.App.onCreate'", made + "'java.lang.reflect.Method.invoke', 'com.example.app.App.onCreate'",
                "'android.view.accessibility.AccessibilityManager.setStateLocked', " +
                        "'android.view.accessibility.AccessibilityManager.tryConnectToServiceLocked', " +
                        "'android.view.accessibility.AccessibilityManager.getServiceLocked', " +
                        "'android.view.accessibility.AccessibilityManager.isTouchExplorationEnabled', " +
                        "'org.lsposed.lspd.impl.LSPosedBridge$NativeHooker.callback', 'com.example.app.Main.onCreate'"}) {
            Scripts.run(globals, "method = 'isEnabled' stackof(" + callers + ") lookups = 0");
            Varargs result = Scripts.run(globals, enabled);
            assertFalse(callers, result.arg1().toboolean());
            assertTrue(callers, result.arg(2).toboolean());
            assertEquals(callers, 0, Scripts.run(globals, "return lookups").arg1().toint());
        }

        // A service of the app itself keeps accessibility on, and touch exploration when it asks for it, without a walk of
        // the stack once the services are kept
        Scripts.run(globals, "method = 'isEnabled' stackof('com.example.app.Main.onCreate') " +
                "values, now = {}, 100 os.time = function() return now end");
        Scripts.run(globals, "enabledof({ 'com.other.hidden', 4 }, { 'com.example.app', 0 })");
        assertFalse(Scripts.run(globals, enabled).arg1().toboolean());
        Scripts.run(globals, "walks = 0");
        assertFalse(Scripts.run(globals, enabled).arg1().toboolean());
        assertEquals(0, Scripts.run(globals, "return walks").arg1().toint());
        // Also when the framework asks first, for which they are read again too, else each of its calls would walk the stack
        Scripts.run(globals, "stackof('android.view.View.sendAccessibilityEventInternal') values = {}");
        assertFalse(Scripts.run(globals, enabled).arg1().toboolean());
        Scripts.run(globals, "walks = 0");
        assertFalse(Scripts.run(globals, enabled).arg1().toboolean());
        assertEquals(0, Scripts.run(globals, "return walks").arg1().toint());
        Scripts.run(globals, "method = 'isTouchExplorationEnabled' stackof('com.example.app.Main.onCreate')");
        assertTrue(Scripts.run(globals, touch).arg1().toboolean());
        // Only with the capability, without which a service asking for it cannot turn it on
        Scripts.run(globals, "enabledof({ 'com.other.hidden', 4, 2 }, { 'com.example.app', 4 }) values = {}");
        assertTrue(Scripts.run(globals, touch).arg1().toboolean());
        Scripts.run(globals, "enabledof({ 'com.other.hidden', 0 }, { 'com.example.app', 4, 2 }) values = {}");
        assertFalse(Scripts.run(globals, touch).arg1().toboolean());
        Scripts.run(globals, "walks = 0");
        assertFalse(Scripts.run(globals, touch).arg1().toboolean());
        assertEquals(0, Scripts.run(globals, "return walks").arg1().toint());
        // No services, like for a UiAutomation, and off stay, also without a walk of the stack
        Scripts.run(globals, "enabledof() values = {}");
        assertFalse(Scripts.run(globals, touch).arg1().toboolean());
        Scripts.run(globals, "walks = 0");
        assertFalse(Scripts.run(globals, touch).arg1().toboolean());
        Scripts.run(globals, "enabledof({ 'com.other.hidden', 4 }) values = {}");
        assertFalse(Scripts.run(globals, "return state('isTouchExplorationEnabled', false)").arg1().toboolean());
        assertEquals(0, Scripts.run(globals, "return walks").arg1().toint());

        // The services are read once a second, so a change, like a hidden service bound after the list was empty,
        // shows within a second
        Scripts.run(globals, "method = 'isEnabled' stackof('com.example.app.Main.onCreate') " +
                "values, now, reads = {}, 100, 0 os.time = function() return now end enabledof()");
        assertFalse(Scripts.run(globals, enabled).arg1().toboolean());
        Scripts.run(globals, "enabledof({ 'com.other.hidden', 4 })");
        assertFalse(Scripts.run(globals, enabled).arg1().toboolean());
        assertEquals(1, Scripts.run(globals, "return reads").arg1().toint());
        Scripts.run(globals, "now = 101");
        assertTrue(Scripts.run(globals, enabled).arg1().toboolean());
        assertTrue(Scripts.run(globals, enabled).arg1().toboolean());
        assertEquals(2, Scripts.run(globals, "return reads").arg1().toint());
    }

    // settingssecure_getstring tells accessibility_enabled and touch_exploration_enabled, and which services stay, with the
    // same functions
    @Test
    public void accessibilityStateIsTheSameAsOfSettings() throws Exception {
        assertEquals(Scripts.function("service_filter", "onlyhidden"), Scripts.function("settingssecure_getstring", "onlyhidden"));
        assertEquals(Scripts.function("service_filter", "ownservice"), Scripts.function("settingssecure_getstring", "ownservice"));
    }

    // Without a list to choose another one from, here because the lookups fail; the one Android would choose is
    // tested with the settings, see SettingsScriptTest.currentServicesAgreeWithTheSettings
    @Test
    public void currentServiceOfHiddenAppIsNone() throws Exception {
        String[][] currents = {
                {"service_filter", "android.view.inputmethod.InputMethodManager", "getCurrentInputMethodInfo"},
                {"service_filter", "android.view.textservice.TextServicesManager", "getCurrentSpellCheckerInfo"},
                {"service_filter", "android.view.textservice.TextServicesManager", "getCurrentSpellChecker"},
                // For a user, and the spell checker of a session, which names the current one
                {"service_filter", "android.view.inputmethod.InputMethodManager", "getCurrentInputMethodInfoAsUser"},
                {"service_filter", "android.view.textservice.SpellCheckerSession", "getSpellChecker"}};
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
        Globals globals = Scripts.load("service_filter", STUBS + "\n" +
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
