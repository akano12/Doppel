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

// The script of the settings (assets/settingssecure_getstring.lua) with Lua stand-ins, like LocationScriptTest:
// the settings which name other apps, the queries and the lists of the settings provider
public class SettingsScriptTest {
    private static final String STUBS = String.join("\n",
            Scripts.ARRAYOF,
            Scripts.APPOF,
            // A keyboard of a third party app, a system keyboard and accessibility services
            "local keyboards = arrayof(",
            "  { getServiceInfo = function(self) return { applicationInfo = appof('com.third.keyboard', 10100, false) } end },",
            "  { getServiceInfo = function(self) return { applicationInfo = appof('com.android.inputmethod.latin', 10050, true) } end })",
            "local services = arrayof(",
            "  { getResolveInfo = function(self)",
            "    return { serviceInfo = { applicationInfo = appof('com.third.reader', 10101, false) } } end },",
            "  { getResolveInfo = function(self)",
            "    return { serviceInfo = { applicationInfo = appof('com.google.android.marvin.talkback', 10051, true) } } end })",
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
            // Each call reads the lists, which the tests change
            "    getValue = function(self, name, scope) return nil end,",
            "    putValue = function(self, name, value, scope) end,",
            "    getPackageName = function(self) return 'com.example.app' end,",
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
            // A java.util.ArrayList as a Lua table, which the stand-in of luajava below hands out and the bundle of the test holds
            "function listof()",
            "  return { items = {},",
            "    add = function(self, value) self.items[#self.items + 1] = value end,",
            "    size = function(self) return #self.items end,",
            "    toArray = function(self) return arrayof(table.unpack(self.items)) end,",
            "    toString = function(self) return '[' .. table.concat(self.items, ', ') .. ']' end }",
            "end",
            "local java = luajava",
            "luajava = {",
            "  newInstance = function(name, action)",
            "    if name == 'android.content.Intent' then return { action = action } end",
            "    if name == 'android.database.MatrixCursor' then return cursorof(action, {}) end",
            "    assert(name == 'java.util.ArrayList', name)",
            "    return listof()",
            "  end,",
            "  bindClass = function(name)",
            "    if name == 'java.util.Locale' then return java.bindClass(name) end",
            "    assert(name == 'android.content.res.Resources', name)",
            "    return { getSystem = function(self) return resources end }",
            "  end }",
            // A cursor of the settings provider with rows of values of Cursor types (1 long, 3 string, 0 null),
            // and a MatrixCursor, as Lua sees them
            "function cursorof(columns, rows)",
            "  local cursor = { columns = columns, rows = rows, position = -1, closed = false }",
            "  function cursor:getColumnNames() return self.columns end",
            "  function cursor:getColumnIndex(name)",
            "    for index = 1, self.columns.length do if self.columns[index] == name then return index - 1 end end",
            "    return -1",
            "  end",
            "  function cursor:getCount() return #self.rows end",
            "  function cursor:getPosition() return self.position end",
            "  function cursor:moveToPosition(position) self.position = position return true end",
            "  function cursor:moveToNext() self.position = self.position + 1 return self.position < #self.rows end",
            "  function cursor:value(index) return self.rows[self.position + 1][index + 1] end",
            "  function cursor:getType(index)",
            "    local value = self:value(index)",
            "    return (value == nil and 0 or type(value) == 'number' and 1 or 3)",
            "  end",
            "  function cursor:getLong(index) return self:value(index) end",
            "  function cursor:getString(index) return self:value(index) end",
            "  function cursor:close() self.closed = true end",
            "  function cursor:newRow()",
            "    local row = { n = 0 }",
            "    table.insert(self.rows, row)",
            "    return { add = function(self, value) row.n = row.n + 1 row[row.n] = value end }",
            "  end",
            "  return cursor",
            "end",
            "function uriof(authority, ...)",
            "  local segments = { ... }",
            "  return { getAuthority = function(self) return authority end,",
            "    getPathSegments = function(self)",
            "      return { size = function(self) return #segments end,",
            "        get = function(self, index) return segments[index + 1] end }",
            "    end }",
            "end",
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
        globals = Scripts.load("settingssecure_getstring", STUBS);
    }

    private Varargs run(String lua) {
        return Scripts.run(globals, lua);
    }

    // The fake value of a setting of the applications variants, or "same" when it stays
    private String applications(String name, String key, String value) {
        return applications(name, key, value, 10200);
    }

    private String applications(String name, String key, String value, int uid) {
        Varargs result = run("local changed, fake = fakesetting(paramof({}, {}, " + uid + "), '" + name + "', '" + key +
                "', '" + value + "')\n" +
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

    // More settings name apps by their components: screen savers, disabled print services, the default payment app
    // and the credential services; the Android code in apps does not read these, unlike voice_recognition_service and
    // tts_default_synth, which speech recognition and text to speech read and which stay
    @Test
    public void moreSettingsOfOtherAppsAreHidden() {
        for (String key : new String[]{"screensaver_components", "screensaver_default_component", "disabled_print_services",
                "nfc_payment_default_component", "credential_service", "credential_service_primary"}) {
            assertTrue(key, run("return hooked('" + key + "')").arg1().toboolean());
            assertEquals(key, null, applications("input_method", key, "com.third.hidden/.Service"));
        }
        assertFalse(run("return hooked('tts_default_synth')").arg1().toboolean());
    }

    // Screen savers are separated by commas, unlike the other lists (DreamManagerService.componentsFromString):
    // splitting at colons named the first one only, and decided for all by it
    @Test
    public void screenSaversAreSeparatedByCommas() {
        assertEquals("com.google.android.gms/.Dream",
                applications("input_method", "screensaver_components",
                        "com.google.android.gms/.Dream,com.third.hidden/.Dream"));
        assertEquals(null, applications("input_method", "screensaver_components",
                "com.third.hidden/.Dream,com.other.hidden/.Dream"));
        assertEquals("same", applications("input_method", "screensaver_components",
                "com.google.android.gms/.Dream,com.example.app/.Dream"));
    }

    // A setting without entries stays as it is, like an empty accessibility_shortcut_target_service of Android 14,
    // instead of turning into a missing one
    @Test
    public void emptySettingsStay() {
        assertEquals("same", applications("accessibility", "accessibility_shortcut_target_service", ""));
        assertEquals("same", applications("input_method", "enabled_notification_listeners", ""));
    }

    // The media button receiver names the last media app, with its user and type after commas
    @Test
    public void mediaButtonReceiverOfHiddenAppIsNone() {
        assertTrue(run("return hooked('media_button_receiver')").arg1().toboolean());
        assertEquals(null, applications("input_method", "media_button_receiver", "com.third.hidden/.MediaReceiver,0,1"));
        assertEquals("same", applications("input_method", "media_button_receiver",
                "com.google.android.gms/.MediaReceiver,0,1"));
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
        for (String key : new String[]{"font_scale", "animator_duration_scale", "accessibility_enabled", "touch_exploration_enabled",
                "http_proxy"})
            assertFalse(key, run("return hooked('" + key + "')").arg1().toboolean());
        assertTrue(run("return hooked('screen_off_timeout')").arg1().toboolean());
        assertFalse(run("return hooked(nil)").arg1().toboolean());
    }

    // Identifiers which manufacturers and SDKs keep in settings appear missing, like on a device without them, in any
    // table, since ROMs move them: of Huawei, Amazon Fire OS, Xiaomi, OnePlus and the SDKs of Baidu
    @Test
    public void identifiersOfManufacturersAreMissing() {
        for (String key : new String[]{"pps_oaid", "advertising_id", "limit_ad_tracking", "mi_health_id", "gcbooster_uuid",
                "key_mqs_uuid", "extm_uuid", "ad_aaid", "op_security_uuid", "com.baidu.deviceid", "com.baidu.deviceid.v2",
                "com.baidu.uuid", "pps_oaid_c", "pps_track_limit", "pps_vaid", "pps_aaid_c", "oaid", "oaid_limit_state",
                "aaid", "ad_id", "bd_setting_i", "dxCRMxhQkdGePGnp"}) {
            assertTrue(key, run("return hooked('" + key + "')").arg1().toboolean());
            assertEquals(key, null, applications("oaid", key, "4f3e2d1c-real"));
            // Also by the provider, like ContentResolver.call(GET_global) and queries, see fakeany
            assertTrue(key, run("return (fakeany(paramof({}, {}, 10123), 'identifiers', '" + key + "', 'real'))").arg1().toboolean());
        }
        // Not of other variants, and other settings stay, also of Huawei and generic ids
        for (String key : new String[]{"pps_version", "pps_id_x", "pps_oaid_d", "xpps_oaid", "uuid", "guid", "oaid2"})
            assertFalse(key, run("return hooked('" + key + "')").arg1().toboolean());
        assertEquals("same", applications("android_id", "mi_health_id", "4f3e2d1c-real"));
        assertEquals("same", applications("oaid", "android_id", "1234abcd1234abcd"));
    }

    // Stay awake of the developer options appears off, like adb_enabled: the power sources it is on for, 0 by default
    @Test
    public void stayAwakeIsOff() {
        assertTrue(run("return hooked('stay_on_while_plugged')").arg1().toboolean());
        assertEquals("0", applications("adb", "stay_on_while_plugged", "3"));
        assertEquals("same", applications("adb", "stay_on_while_plugged", "0"));
        assertEquals("0", applications("adb", "adb_enabled", "1"));
        assertEquals("same", applications("settings", "stay_on_while_plugged", "7"));
    }

    // The languages the user chose (system_locales) are the fake ones by the settings provider too, for Read.Locale
    @Test
    public void systemLocalesAreFake() {
        assertTrue(run("return hooked('system_locales')").arg1().toboolean());
        assertEquals("en-US", applications("locale", "system_locales", "nl-NL,en-GB"));
        assertEquals("same", applications("locale", "system_locales", "en-US"));
        assertEquals("same", applications("locale", "system_locales", ""));
        assertEquals("same", applications("settings", "system_locales", "nl-NL"));
        assertEquals("de-DE,fr", run("return select(2, fakesetting(paramof({}, { ['value.locale'] = 'de-DE, fr,de-DE' }), " +
                "'locale', 'system_locales', 'nl-NL'))").arg1().tojstring());
        assertTrue(run("return (fakeany(paramof({}, {}), 'locale', 'system_locales', 'nl-NL'))").arg1().toboolean());
    }

    // The name of the device for Wi-Fi Direct, which users set like the name of the device, often with their own name
    @Test
    public void wifiDirectNameIsMissing() {
        assertTrue(run("return hooked('wifi_p2p_device_name')").arg1().toboolean());
        assertEquals(null, applications("device_name", "wifi_p2p_device_name", "Pixel of Alice"));
        assertEquals(null, applications("device_name", "device_name", "Pixel of Alice"));
    }

    // Settings.Secure.getString calls the hooked getStringForUser, which already faked the value
    @Test
    public void androidIdIsFakedOnce() {
        assertEquals("same", applications("android_id", "android_id", "0000000000000000"));
        assertEquals("0000000000000000", applications("android_id", "android_id", "1234abcd1234abcd"));
    }

    // The setting of a query of content://settings/secure without the name column, and the selection,
    // which the query of API 26 has in its Bundle of query arguments
    private Varargs selected(String selection, String args) {
        return run("local queryArgs = { getString = function(self, key) return " + selection + " end,\n" +
                "  getStringArray = function(self, key) return " + args + " end }\n" +
                "return selected(paramof({ [2] = queryArgs }, {}))");
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

    // A query of the settings provider: whether restricted, the rows of the result as text, whether the cursor
    // of the provider was closed and whether the result is the cursor of the provider
    private Varargs query(String group, String uri, String columns, String rows, String queryArgs) {
        return run("local cursor = cursorof(arrayof(" + columns + "), { " + rows + " })\n" +
                "local param = paramof({ [0] = " + uri + ", [2] = " + queryArgs + " }, {})\n" +
                "param.getResult = function(self) return self.result end\n" +
                "param.setResult = function(self, value) self.result = value end\n" +
                "param.result = cursor\n" +
                "local restricted = after(hookof('ContentResolver.query/" + group + "', " +
                "'android.content.ContentResolver', 'query'), param)\n" +
                "local lines = {}\n" +
                "for _, row in ipairs(param.result.rows) do\n" +
                "  local values = {}\n" +
                "  for index = 1, row.n or #row do values[index] = tostring(row[index]) end\n" +
                "  lines[#lines + 1] = table.concat(values, ',')\n" +
                "end\n" +
                "return restricted, table.concat(lines, ';'), cursor.closed, param.result == cursor");
    }

    @Test
    public void queriesOfSettingsAreFaked() {
        String rows = "{ 1, 'android_id', '1234abcd1234abcd' }, { 2, 'bluetooth_name', nil }, { 3, 'adb_enabled', '1' }";
        Varargs result = query("identifiers", "uriof('settings', 'secure')", "'_id', 'name', 'value'", rows, "nil");
        assertTrue(result.arg1().toboolean());
        assertEquals("1,android_id,0000000000000000;2,bluetooth_name,nil;3,adb_enabled,1", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());

        result = query("debugging", "uriof('0@settings', 'secure')", "'_id', 'name', 'value'", rows, "nil");
        assertTrue(result.arg1().toboolean());
        assertEquals("1,android_id,1234abcd1234abcd;2,bluetooth_name,nil;3,adb_enabled,0", result.arg(2).tojstring());

        // Android parses the user like Integer.parseInt, which takes a sign
        result = query("identifiers", "uriof('+0@settings', 'secure')", "'_id', 'name', 'value'", rows, "nil");
        assertTrue(result.arg1().toboolean());
        assertEquals("1,android_id,0000000000000000;2,bluetooth_name,nil;3,adb_enabled,1", result.arg(2).tojstring());

        // Nothing to fake: the cursor of the provider stays, not closed
        result = query("battery", "uriof('settings', 'secure')", "'_id', 'name', 'value'", rows, "nil");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());

        // content://settings/secure/android_id: the name is in the path
        result = query("identifiers", "uriof('settings', 'secure', 'android_id')", "'value'",
                "{ '1234abcd1234abcd' }", "nil");
        assertTrue(result.arg1().toboolean());
        assertEquals("0000000000000000", result.arg(2).tojstring());

        // Without the name column, the selection tells the setting
        result = query("identifiers", "uriof('settings', 'secure')", "'value'", "{ '1234abcd1234abcd' }",
                "{ getString = function(self, key) return \"name='android_id'\" end," +
                        " getStringArray = function(self, key) return nil end }");
        assertTrue(result.arg1().toboolean());
        assertEquals("0000000000000000", result.arg(2).tojstring());

        // All values without their names: there are none
        result = query("identifiers", "uriof('settings', 'secure')", "'value'", "{ '1234abcd1234abcd' }, { '1' }", "nil");
        assertTrue(result.arg1().toboolean());
        assertEquals("", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
    }

    // call(authority, GET_secure, name, null) of ContentResolver returns a bundle with the value
    @Test
    public void callsOfSettingsAreFaked() {
        Varargs result = run("local values = { value = '1234abcd1234abcd' }\n" +
                "local bundle = { getString = function(self, key) return values[key] end,\n" +
                "  putString = function(self, key, value) values[key] = value end }\n" +
                "local param = paramof({ [0] = 'settings', [1] = 'GET_secure', [2] = 'android_id' }, {})\n" +
                "param.getResult = function(self) return bundle end\n" +
                "local restricted, old, new = after(hookof('ContentResolver.call/identifiers', " +
                "'android.content.ContentResolver', 'call'), param)\n" +
                "return restricted, old, values.value");
        assertTrue(result.arg1().toboolean());
        assertEquals("1234abcd1234abcd", result.arg(2).tojstring());
        assertEquals("0000000000000000", result.arg(3).tojstring());
    }

    // Settings.Secure.getString(cr, null) is not a query of the provider: the getter returns without an error
    @Test
    public void getterWithoutName() {
        Varargs result = run("local param = paramof({ [0] = {}, [1] = nil }, {})\n" +
                "param.getResult = function(self) return nil end\n" +
                "return after(hookof('Settings.Secure.getStringForUser/android_id', " +
                "'android.provider.Settings$Secure', 'getStringForUser'), param)");
        assertFalse(result.arg1().toboolean());
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

    // ContentProviderClient.call(LIST_secure, arg, extras) lists the settings whatever the argument
    @Test
    public void listsOfSettingsAreFakedByClient() {
        for (String arguments : new String[]{"[1] = 'x', [2] = nil", "[1] = nil, [2] = nil"}) {
            Varargs result = run("local bundle = bundleof('adb_enabled=1', 'android_id=1234abcd1234abcd')\n" +
                    "local param = paramof({ [0] = 'LIST_secure', " + arguments + " }, {})\n" +
                    "param.getResult = function(self) return bundle end\n" +
                    "local restricted, old = after(hookof('ContentProviderClient.call/identifiers', " +
                    "'android.content.ContentProviderClient', 'call'), param)\n" +
                    "local lines = bundle:getStringArrayList('result_settings_list')\n" +
                    "return restricted, old, lines:toString()");
            assertTrue(arguments, result.arg1().toboolean());
            assertEquals(arguments, "android_id", result.arg(2).tojstring());
            assertEquals(arguments, "[adb_enabled=1, android_id=0000000000000000]", result.arg(3).tojstring());
        }
    }
}
