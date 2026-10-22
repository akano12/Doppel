/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2017-2019 Marcel Bokhorst (M66B)
 */

package eu.faircode.xlua;

import org.junit.Before;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The script of the settings (assets/settingssecure_getstring.lua) with Lua stand-ins, like LocationScriptTest:
// the settings which name other apps, the queries and the lists of the settings provider
public class SettingsScriptTest {
    private static final String STUBS = String.join("\n",
            // Java arrays as Lua sees them
            "function arrayof(...)",
            "  local array = { ... }",
            "  array['length'] = select('#', ...)",
            "  return array",
            "end",
            "function appof(packageName, uid, system)",
            "  return { packageName = packageName, uid = uid, system = system }",
            "end",
            // A keyboard of a third party app, a system keyboard and accessibility services
            "local keyboards = arrayof(",
            "  { getServiceInfo = function(self) return { applicationInfo = appof('com.third.keyboard', 10100, false) } end },",
            "  { getServiceInfo = function(self) return { applicationInfo = appof('com.android.inputmethod.latin', 10050, true) } end })",
            "local services = arrayof(",
            "  { getResolveInfo = function(self) return { serviceInfo = { applicationInfo = appof('com.third.reader', 10101, false) } } end },",
            "  { getResolveInfo = function(self) return { serviceInfo = { applicationInfo = appof('com.google.android.marvin.talkback', 10051, true) } } end })",
            // The package manager does not tell about apps hidden by the package visibility rules
            "apps = { ['com.google.android.gms'] = appof('com.google.android.gms', 10010, true),",
            "  ['com.third.visible'] = appof('com.third.visible', 10102, false),",
            "  ['com.android.dialer'] = appof('com.android.dialer', 10011, true),",
            "  ['com.google.android.apps.messaging'] = appof('com.google.android.apps.messaging', 10012, true) }",
            // The apps which receive SMS: a third party app, the app itself and the SMS app of the system
            "local receivers = arrayof(",
            "  { activityInfo = { applicationInfo = apps['com.third.visible'] } },",
            "  { activityInfo = { applicationInfo = appof('com.example.app', 10200, false) } },",
            "  { activityInfo = { applicationInfo = apps['com.google.android.apps.messaging'] } })",
            // The permissions the app has, and the system dialer
            "permissions = {}",
            "dialer = 'com.android.dialer'",
            "local context = {",
            "  checkSelfPermission = function(self, name) return (permissions[name] and 0 or -1) end,",
            "  getSystemService = function(self, name)",
            "    if name == 'telecom' then",
            "      return { getSystemDialerPackage = function(self) return dialer end }",
            "    end",
            "    if name == 'input_method' then",
            "      return { getInputMethodList = function(self) return { toArray = function(self) return keyboards end } end }",
            "    end",
            "    local list = { toArray = function(self) return services end }",
            "    return { getInstalledAccessibilityServiceList = function(self) return list end,",
            "      getEnabledAccessibilityServiceList = function(self, type) return list end }",
            "  end,",
            "  getPackageManager = function(self)",
            "    return { getApplicationInfo = function(self, name, flags)",
            "        if apps[name] == nil then error('NameNotFoundException ' .. name) end",
            "        return apps[name]",
            "      end,",
            "      queryBroadcastReceivers = function(self, intent, flags)",
            "        assert(intent.action == 'android.provider.Telephony.SMS_DELIVER', intent.action)",
            "        return { toArray = function(self) return receivers end }",
            "      end }",
            "  end }",
            // The configuration of the system names an SMS app which is not installed
            "local resources = {",
            "  getIdentifier = function(self, name, type, package)",
            "    return (name == 'config_defaultSms' and type == 'string' and package == 'android' and 1 or 0)",
            "  end,",
            "  getString = function(self, id) assert(id == 1) return 'com.android.messaging' end }",
            "function paramof(arguments, settings, uid)",
            "  return {",
            "    getApplicationContext = function(self) return context end,",
            "    getPackageName = function(self) return 'com.example.app' end,",
            "    getUid = function(self) return uid or 10200 end,",
            "    isSystem = function(self) return (uid or 10200) % 100000 < 10000 end,",
            "    isVisible = function(self, ai) return ai.uid == 10200 or ai.system end,",
            // Like XParam: the lookup without hooks, the app itself visible
            "    isInstalledVisible = function(self, name)",
            "      if name == 'com.example.app' then return true end",
            "      local pm = context:getPackageManager()",
            "      local ok, ai = pcall(pm.getApplicationInfo, pm, name, 0)",
            "      return (ok and ai ~= nil and (ai.uid == 10200 or ai.system == true))",
            "    end,",
            "    unhooked = function(self, fn) return fn() end,",
            "    getArgument = function(self, index) return arguments[index] end,",
            "    getSetting = function(self, name) return settings[name] end }",
            "end",
            "function hookof(name, class, method)",
            "  return { getName = function(self) return name end,",
            "    getClassName = function(self) return class end,",
            "    getMethodName = function(self) return method end }",
            "end",
            // A java.util.ArrayList, Java classes of the JDK cannot be used by luajava on a desktop JVM
            "function listof()",
            "  return { items = {},",
            "    add = function(self, value) self.items[#self.items + 1] = value end,",
            "    size = function(self) return #self.items end,",
            "    toArray = function(self) return arrayof(table.unpack(self.items)) end,",
            "    toString = function(self) return '[' .. table.concat(self.items, ', ') .. ']' end }",
            "end",
            "luajava = {",
            "  newInstance = function(name, action)",
            "    if name == 'android.content.Intent' then return { action = action } end",
            "    assert(name == 'java.util.ArrayList', name)",
            "    return listof()",
            "  end,",
            "  bindClass = function(name)",
            "    assert(name == 'android.content.res.Resources', name)",
            "    return { getSystem = function(self) return resources end }",
            "  end }",
            // The bundle of call(LIST_secure)
            "function bundleof(...)",
            "  local list = listof()",
            "  for _, line in ipairs({ ... }) do list:add(line) end",
            "  local bundle = { lists = { result_settings_list = list } }",
            "  bundle.getStringArrayList = function(self, name) return self.lists[name] end",
            "  bundle.putStringArrayList = function(self, name, value) self.lists[name] = value end",
            "  return bundle",
            "end");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = JsePlatform.standardGlobals();
        String script = new String(Files.readAllBytes(new File("src/main/assets/settingssecure_getstring.lua").toPath()),
                StandardCharsets.UTF_8);
        globals.load(script, "settingssecure_getstring").call();
        globals.load(STUBS, "stubs").call();
    }

    private Varargs run(String lua) {
        return globals.load(lua, "test").invoke();
    }

    // The fake value of a setting of the applications variants, or "same" when it stays
    private String applications(String name, String key, String value) {
        return applications(name, key, value, 10200);
    }

    private String applications(String name, String key, String value, int uid) {
        Varargs result = run("local changed, fake = fakesetting(paramof({}, {}, " + uid + "), '" + name + "', '" + key + "', '" + value + "')\n" +
                "if not changed then return 'same' end\n" +
                "return fake");
        return (result.arg1().isnil() ? null : result.arg1().tojstring());
    }

    @Test
    public void keyboardsOfOtherAppsAreHidden() {
        assertEquals(null, applications("input_method", "default_input_method", "com.third.keyboard/.Keyboard"));
        assertEquals("com.android.inputmethod.latin/.LatinIME;123",
                applications("input_method", "enabled_input_methods",
                        "com.android.inputmethod.latin/.LatinIME;123:com.third.keyboard/.Keyboard;456"));
        assertEquals("com.android.inputmethod.latin/.LatinIME;5",
                applications("input_method", "input_methods_subtype_history",
                        "com.third.keyboard/.Keyboard;-1:com.android.inputmethod.latin/.LatinIME;5"));
        assertEquals(null, applications("input_method", "selected_spell_checker", "com.third.keyboard/.SpellChecker"));
        assertEquals("same", applications("input_method", "selected_spell_checker",
                "com.android.inputmethod.latin/.spellcheck.SpellCheckerService"));
        // Not of the accessibility variant
        assertEquals("same", applications("accessibility", "default_input_method", "com.third.keyboard/.Keyboard"));
    }

    @Test
    public void accessibilityServicesOfOtherAppsAreHidden() {
        assertEquals("com.google.android.marvin.talkback/.TalkBackService",
                applications("accessibility", "enabled_accessibility_services",
                        "com.third.reader/.Reader:com.google.android.marvin.talkback/.TalkBackService"));
        // Features of the framework itself stay
        assertEquals("com.android.server.accessibility.MagnificationController:com.android.server.accessibility/ColorInversion",
                applications("accessibility", "accessibility_button_targets",
                        "com.android.server.accessibility.MagnificationController:com.third.reader/.Reader:" +
                                "com.android.server.accessibility/ColorInversion"));
        assertEquals(null, applications("accessibility", "touch_exploration_granted_accessibility_services",
                "com.third.reader/.Reader"));
        assertEquals("same", applications("input_method", "accessibility_shortcut_target_service",
                "com.third.reader/.Reader"));
    }

    @Test
    public void otherAppsNamedBySettingsAreHidden() {
        // Hidden by the package visibility rules, a system app and the app itself
        assertEquals("com.google.android.gms/.Listener:com.example.app/.Listener",
                applications("input_method", "enabled_notification_listeners",
                        "com.third.hidden/.Listener:com.google.android.gms/.Listener:com.example.app/.Listener"));
        // Visible, but not of the system
        assertEquals("com.google.android.gms",
                applications("input_method", "enabled_notification_policy_access_packages",
                        "com.third.visible:com.google.android.gms"));
        assertEquals("same", applications("input_method", "sms_default_application", "com.example.app"));
        // Read by the framework to start speech recognition
        assertTrue(run("return hooked('voice_recognition_service')").arg1().isboolean());
        assertFalse(run("return hooked('voice_recognition_service')").arg1().toboolean());
    }

    // Apps expect a default SMS and phone app: the one of the system replaces a hidden one,
    // the configured one when installed, else a system app which can be the default
    @Test
    public void defaultAppsAreTheOnesOfTheSystem() {
        assertEquals("com.google.android.apps.messaging",
                applications("input_method", "sms_default_application", "com.third.visible"));
        assertEquals("com.android.dialer", applications("input_method", "dialer_default_application", "com.third.hidden"));
        run("apps['com.android.messaging'] = appof('com.android.messaging', 10013, true)");
        assertEquals("com.android.messaging", applications("input_method", "sms_default_application", "com.third.hidden"));
        // None of the system
        run("dialer = nil");
        assertEquals(null, applications("input_method", "dialer_default_application", "com.third.hidden"));
    }

    // Apps which can change the settings write back what they read, which would remove the hidden apps for good
    @Test
    public void appsWhichChangeSettingsGetThemAsTheyAre() {
        for (int uid : new int[]{1000, 1001, 1001000})
            for (String key : new String[]{"default_input_method", "selected_spell_checker", "sms_default_application"})
                assertEquals(key + " " + uid, "same", applications("input_method", key, "com.third.keyboard/.Keyboard", uid));
        assertEquals("same", applications("accessibility", "enabled_accessibility_services", "com.third.reader/.Reader", 1000));
        // Given the permission, like with adb
        run("permissions['android.permission.WRITE_SECURE_SETTINGS'] = true");
        assertEquals("same", applications("accessibility", "enabled_accessibility_services", "com.third.reader/.Reader"));
        assertEquals("same", applications("input_method", "default_input_method", "com.third.keyboard/.Keyboard"));
        // Other settings are faked still
        assertEquals("0000000000000000", applications("android_id", "android_id", "1234abcd1234abcd", 1000));
        run("permissions = {}");
        assertEquals(null, applications("input_method", "default_input_method", "com.third.keyboard/.Keyboard", 1010200));
    }

    @Test
    public void settingsWithFrameworkValuesAreNotFaked() {
        for (String key : new String[]{"font_scale", "animator_duration_scale", "accessibility_enabled", "touch_exploration_enabled"})
            assertFalse(key, run("return hooked('" + key + "')").arg1().toboolean());
        assertTrue(run("return hooked('screen_off_timeout')").arg1().toboolean());
        assertFalse(run("return hooked(nil)").arg1().toboolean());
    }

    // Settings.Secure.getString calls the hooked getStringForUser, which already faked the value
    @Test
    public void androidIdIsFakedOnce() {
        assertEquals("same", applications("android_id", "android_id", "0000000000000000"));
        assertEquals("0000000000000000", applications("android_id", "android_id", "1234abcd1234abcd"));
    }

    // The setting of a query of content://settings/secure without the name column, and the selection
    private Varargs selected(String selection, String args) {
        return run("return selected(hookof('ContentResolver.query16/identifiers'), paramof({ [2] = " + selection +
                ", [3] = " + args + " }, {}))");
    }

    @Test
    public void selectionsLikeTheSettingsProvider() {
        assertEquals("android_id", selected("'name=?'", "arrayof('android_id')").arg1().tojstring());
        assertEquals("android_id", selected("' ( name = ? ) '", "arrayof('android_id')").arg1().tojstring());
        assertEquals("android_id", selected("\"name='android_id'\"", "nil").arg1().tojstring());
        assertEquals("android_id", selected("'(name=\"android_id\")'", "nil").arg1().tojstring());
        // Between the first and the last quote, like the provider takes the name
        assertEquals("android_id", selected("[[name='x\"android_id']]", "nil").arg1().tojstring());
        assertEquals("android_id", selected("\"name='android_id'\"", "arrayof('other')").arg1().tojstring());

        // Not one setting
        Varargs none = selected("'name=?'", "arrayof('a', 'b')");
        assertTrue(none.arg1().isnil());
        assertEquals("name=?", none.arg(2).tojstring());
        assertTrue(selected("'name=?'", "nil").arg1().isnil());
        // All settings
        Varargs all = selected("nil", "nil");
        assertTrue(all.arg1().isnil());
        assertTrue(all.arg(2).isnil());
    }

    // call(authority, LIST_secure, null, null) of ContentResolver
    private Varargs list(String group, String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines)
            sb.append(sb.length() == 0 ? "" : ", ").append("'").append(line).append("'");
        return run("local bundle = bundleof(" + sb + ")\n" +
                "local param = paramof({ [0] = 'settings', [1] = 'LIST_secure' }, {})\n" +
                "param.getResult = function(self) return bundle end\n" +
                "local restricted, old = after(hookof('ContentResolver.call/" + group + "', " +
                "'android.content.ContentResolver', 'call'), param)\n" +
                "local lines = bundle:getStringArrayList('result_settings_list')\n" +
                "return restricted, old, lines:toString()");
    }

    @Test
    public void listsOfSettingsAreFaked() {
        Varargs result = list("identifiers", "adb_enabled=1", "android_id=1234abcd1234abcd", "proxy=a=b", "empty=null");
        assertTrue(result.arg1().toboolean());
        assertEquals("android_id", result.arg(2).tojstring());
        assertEquals("[adb_enabled=1, android_id=0000000000000000, proxy=a=b, empty=null]", result.arg(3).tojstring());

        // Settings which are not set on a new device are left out
        result = list("settings", "ringtone=content://media/1", "screen_off_timeout=30000", "font_scale=1.3");
        assertTrue(result.arg1().toboolean());
        assertEquals("[screen_off_timeout=60000, font_scale=1.3]", result.arg(3).tojstring());

        result = list("applications", "enabled_notification_listeners=com.third.hidden/.Listener:com.google.android.gms/.Listener");
        assertEquals("[enabled_notification_listeners=com.google.android.gms/.Listener]", result.arg(3).tojstring());

        result = list("battery", "low_power=0", "android_id=1234abcd1234abcd");
        assertFalse(result.arg1().toboolean());
        assertEquals("[low_power=0, android_id=1234abcd1234abcd]", result.arg(3).tojstring());
    }
}
