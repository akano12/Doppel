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
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The script of the settings (assets/settingssecure_getstring.lua) with Lua stand-ins, like LocationScriptTest:
// the settings which name other apps, the queries and the lists of the settings provider
public class SettingsScriptTest {
    private static final String STUBS = String.join("\n",
            Scripts.ARRAYOF,
            Scripts.APPOF,
            // Accessibility services of a third party app, of the system and of the app
            "local services = arrayof(",
            "  { getResolveInfo = function(self)",
            "    return { serviceInfo = { applicationInfo = appof('com.third.reader', 10101, false) } } end },",
            "  { getResolveInfo = function(self)",
            "    return { serviceInfo = { applicationInfo = appof('com.google.android.marvin.talkback', 10051, true) } } end },",
            "  { getResolveInfo = function(self)",
            "    return { serviceInfo = { applicationInfo = appof('com.example.app', 10200, false) } } end })",
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
            // The installed and the enabled keyboards and the spell checkers, which the tests set, as their services with
            // their apps, ids and subtypes, all auxiliary (of voice) or none (of the keyboard mode), one of English by
            // default; the list of the spell checkers of Android 12 (modern), else only the hidden array, which is null
            // when empty; the current spell checker and the version of Android
            "function serviceof(packageName, uid, system, id, auxiliary, subtypes, locale)",
            "  local ai = { packageName = packageName, uid = uid, system = system, flags = (system and 1 or 0) }",
            "  return { getServiceInfo = function(self) return { applicationInfo = ai } end,",
            "    getPackageName = function(self) return packageName end,",
            "    getId = function(self) return id end,",
            "    getSubtypeCount = function(self) return subtypes or 1 end,",
            "    getSubtypeAt = function(self, index)",
            "      assert(index >= 0 and index < (subtypes or 1), index)",
            "      return { isAuxiliary = function(self) return auxiliary == true end,",
            "        getMode = function(self) return (auxiliary and 'voice' or 'keyboard') end,",
            "        getLocale = function(self) return locale or 'en_US' end }",
            "    end }",
            "end",
            // A keyboard of a third party app and a system keyboard
            "installed = arrayof(serviceof('com.third.keyboard', 10100, false, 'com.third.keyboard/.Keyboard'),",
            "  serviceof('com.android.inputmethod.latin', 10050, true, 'com.android.inputmethod.latin/.LatinIME'))",
            "enabled = arrayof()",
            "spellcheckers = arrayof()",
            "modern = true",
            "current = nil",
            "sdk = 34",
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
            "      return { getInputMethodList = function(self) return { toArray = function(self) return installed end } end,",
            "        getEnabledInputMethodList = function(self) return { toArray = function(self) return enabled end } end }",
            "    end",
            "    if name == 'textservices' then",
            "      local manager = { getEnabledSpellCheckers = function(self) return spellcheckers end }",
            "      if modern then",
            "        manager.getEnabledSpellCheckerInfos = function(self)",
            "          return { toArray = function(self) return spellcheckers or arrayof() end }",
            "        end",
            "      end",
            "      return manager",
            "    end",
            "    local list = { toArray = function(self) return services end }",
            "    return { getInstalledAccessibilityServiceList = function(self) return list end,",
            // The enabled services, which the tests can set apart (see enabledof); enabled are the keyboards
            "      getEnabledAccessibilityServiceList = function(self, type) return enabledservices or list end }",
            "  end,",
            "  getContentResolver = function(self) return {} end,",
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
            "    getUid = function(self) return uid or 10200 end,",
            "    isSystem = function(self) return (uid or 10200) % 100000 < 10000 end,",
            // Like XParam: the system sees all apps
            "    isVisible = function(self, ai) return self:isSystem() or ai.uid == 10200 or ai.system end,",
            // Like XParam: the lookup without hooks, the app itself visible
            "    isInstalledVisible = function(self, name)",
            "      if name == 'com.example.app' then return true end",
            "      local pm = context:getPackageManager()",
            "      local ok, ai = pcall(pm.getApplicationInfo, pm, name, 0)",
            "      return (ok and ai ~= nil and (ai.uid == 10200 or ai.system == true))",
            "    end,",
            "    unhooked = function(self, fn) return fn() end,",
            // The cursors of the stand-ins are no CursorWrapper, the resolver is the one of the test
            "    isInstance = function(self, object, name) return false end,",
            "    getThis = function(self) return resolver end,",
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
            "    if name == 'java.util.Locale' or name == 'java.lang.Class' then return java.bindClass(name) end",
            // The stand-in of CursorWrapper, which declares the cursor it wraps
            "    if name == 'android.database.CursorWrapper' then return java.bindClass('eu.faircode.xlua.SettingsScriptTest$Wrapper') end",
            "    if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end",
            "    if name == 'android.provider.Settings$Secure' then",
            "      return { getString = function(self, resolver, key) assert(key == 'selected_spell_checker', key) return current end }",
            "    end",
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
        // A system keyboard stays enabled, see currentKeyboardOfHiddenAppIsTheOneAndroidChooses for the other cases
        run("enabled = arrayof(" + LATIN + ", " + HIDDEN_KEYBOARD + ")");
        assertEquals("com.android.inputmethod.latin/.LatinIME;123",
                applications("input_method", "enabled_input_methods",
                        "com.android.inputmethod.latin/.LatinIME;123:com.third.keyboard/.Keyboard;456"));
        assertEquals("com.android.inputmethod.latin/.LatinIME;5",
                applications("input_method", "input_methods_subtype_history",
                        "com.third.keyboard/.Keyboard;-1:com.android.inputmethod.latin/.LatinIME;5"));
        assertEquals("same", applications("input_method", "default_input_method", "com.android.inputmethod.latin/.LatinIME"));
        // Without spell checkers
        assertEquals("", applications("input_method", "selected_spell_checker", "com.third.keyboard/.SpellChecker"));
        assertEquals("same", applications("input_method", "selected_spell_checker",
                "com.android.inputmethod.latin/.spellcheck.SpellCheckerService"));
        // Not of the accessibility variant
        assertEquals("same", applications("accessibility", "default_input_method", "com.third.keyboard/.Keyboard"));
    }

    private static final String HIDDEN_KEYBOARD = "serviceof('com.third.keyboard', 10100, false, 'com.third.keyboard/.Keyboard')";
    private static final String VOICE = "serviceof('com.google.android.voice', 10052, true, 'com.google.android.voice/.Voice', true)";
    private static final String LATIN =
            "serviceof('com.android.inputmethod.latin', 10050, true, 'com.android.inputmethod.latin/.LatinIME')";
    // A system keyboard without subtypes, which is no auxiliary one, and one of French
    private static final String PLAIN = "serviceof('com.android.plain', 10053, true, 'com.android.plain/.Plain', true, 0)";
    private static final String FRENCH = "serviceof('com.android.french', 10054, true, 'com.android.french/.French', false, 1, 'fr_FR')";
    private static final String SPELL_CHECKERS = "spellcheckers = arrayof(" +
            "serviceof('com.third.keyboard', 10100, false, 'com.third.keyboard/.SpellChecker'), " +
            "serviceof('com.example.app', 10200, false, 'com.example.app/.SpellChecker'), " +
            "serviceof('com.android.inputmethod.latin', 10050, true, 'com.android.inputmethod.latin/.spellcheck.SpellCheckerService'))";

    // The enabled keyboards as the setting names them, without subtypes
    private String enabledSetting() {
        return run("local ids = {}\n" +
                "for index = 1, enabled.length do ids[index] = enabled[index]:getId() end\n" +
                "return table.concat(ids, ':')").arg1().tojstring();
    }

    // The current keyboard of a hidden app becomes the one Android would choose without it: of the enabled ones,
    // searching from the last one, the first system keyboard of English which is not auxiliary, else the first system
    // keyboard which is not auxiliary the search met, else the first enabled one. The enabled ones get the installed
    // system keyboards with a keyboard subtype when only auxiliary ones are left, on Android 8.1 when none is left, like
    // Android enables them then; with none of those either the default is empty and none is enabled, like Android keeps
    // it. The current spell checker becomes the first visible one of a system app, not the one of the app itself, also
    // by the hidden method before Android 12, and its subtype none as of Android 9; with none left the setting is empty
    @Test
    public void currentKeyboardOfHiddenAppIsTheOneAndroidChooses() {
        String latin = "com.android.inputmethod.latin/.LatinIME";
        String voice = "com.google.android.voice/.Voice";
        String plain = "com.android.plain/.Plain";
        String french = "com.android.french/.French";
        String own = "com.example.app/.Keyboard";
        // The enabled keyboards, the version of Android, the current one and the enabled ones without the hidden ones
        String[][] cases = {
                {VOICE + ", " + LATIN, "34", latin, voice + ":" + latin},
                {VOICE + ", " + PLAIN + ", " + LATIN, "34", latin, voice + ":" + plain + ":" + latin},
                {LATIN + ", " + PLAIN + ", " + FRENCH, "34", latin, latin + ":" + plain + ":" + french},
                {PLAIN + ", " + FRENCH, "34", french, plain + ":" + french},
                {FRENCH + ", " + PLAIN, "34", plain, french + ":" + plain},
                {"serviceof('com.example.app', 10200, false, '" + own + "')", "34", own, own},
                {VOICE, "34", latin, voice + ":" + latin},
                {VOICE, "28", latin, voice + ":" + latin},
                {"", "34", latin, latin},
                {"", "28", latin, latin}};
        for (String[] item : cases) {
            String enabled = HIDDEN_KEYBOARD + (item[0].isEmpty() ? "" : ", " + item[0]);
            run("enabled = arrayof(" + enabled + ")\ninstalled = arrayof(" + enabled + ", " + LATIN + ")\nsdk = " + item[1]);
            String message = item[0] + " " + item[1];
            assertEquals(message, item[2], applications("input_method", "default_input_method", "com.third.keyboard/.Keyboard"));
            assertEquals(message, item[3], applications("input_method", "enabled_input_methods", enabledSetting()));
        }
        // Not the system keyboards which are auxiliary or have no keyboard subtype, and none without system keyboards
        run("installed = arrayof(" + HIDDEN_KEYBOARD + ", " + VOICE + ", " + PLAIN + ", " + LATIN + ")\n" +
                "enabled = arrayof(" + HIDDEN_KEYBOARD + ")\nsdk = 34");
        assertEquals(latin, applications("input_method", "enabled_input_methods", enabledSetting()));
        run("installed = arrayof(" + HIDDEN_KEYBOARD + ", " + VOICE + ", " + PLAIN + ")");
        assertEquals("", applications("input_method", "default_input_method", "com.third.keyboard/.Keyboard"));
        assertEquals(null, applications("input_method", "enabled_input_methods", enabledSetting()));

        run("installed = arrayof(" + HIDDEN_KEYBOARD + ", " + LATIN + ")\n" + SPELL_CHECKERS);
        for (boolean modern : new boolean[]{true, false}) {
            run("modern = " + modern);
            assertEquals("com.android.inputmethod.latin/.spellcheck.SpellCheckerService",
                    applications("input_method", "selected_spell_checker", "com.third.keyboard/.SpellChecker"));
        }
        run("spellcheckers = nil");
        assertEquals("", applications("input_method", "selected_spell_checker", "com.third.keyboard/.SpellChecker"));

        // The subtype goes with the current spell checker, which TextServicesManagerService resets
        // when it chooses another one; also when the current one cannot be read
        run("current = 'com.third.keyboard/.SpellChecker'");
        assertEquals("0", applications("input_method", "selected_spell_checker_subtype", "-1234"));
        assertEquals("same", applications("input_method", "selected_spell_checker_subtype", "0"));
        run("sdk = 28\ncurrent = 'com.android.inputmethod.latin/.spellcheck.SpellCheckerService'");
        assertEquals("same", applications("input_method", "selected_spell_checker_subtype", "-1234"));
        run("current = nil");
        assertEquals("same", applications("input_method", "selected_spell_checker_subtype", "-1234"));
        run("local bind = luajava.bindClass\n" +
                "luajava.bindClass = function(name) assert(name ~= 'android.provider.Settings$Secure') return bind(name) end");
        assertEquals("0", applications("input_method", "selected_spell_checker_subtype", "-1234"));
    }

    // The current keyboard, the enabled keyboards and the current spell checker which InputMethodManager and
    // TextServicesManager give (see service_filter) are the ones the settings name, the same functions choose them;
    // none when the setting is empty or not set
    @Test
    public void currentServicesAgreeWithTheSettings() throws Exception {
        Globals filter = Scripts.load("service_filter", STUBS);
        String[][] calls = {
                {"android.view.inputmethod.InputMethodManager", "getCurrentInputMethodInfo", "default_input_method", HIDDEN_KEYBOARD},
                {"android.view.textservice.TextServicesManager", "getCurrentSpellCheckerInfo", "selected_spell_checker",
                        "serviceof('com.third.keyboard', 10100, false, 'com.third.keyboard/.SpellChecker')"}};
        String installed = "installed = arrayof(" + HIDDEN_KEYBOARD + ", " + VOICE + ", " + LATIN + ")\nsdk = 34\nmodern = true\n";
        for (String lists : new String[]{installed + "enabled = arrayof(" + HIDDEN_KEYBOARD + ", " + VOICE + ", " + LATIN + ")\n" +
                SPELL_CHECKERS, installed + "enabled = arrayof(" + HIDDEN_KEYBOARD + ", " + VOICE + ")\nmodern = false\n" + SPELL_CHECKERS,
                installed + "enabled = arrayof(" + HIDDEN_KEYBOARD + ", " + VOICE + ")\nsdk = 28",
                installed + "enabled = arrayof(" + HIDDEN_KEYBOARD + ")\nspellcheckers = nil",
                installed + "enabled = arrayof(" + HIDDEN_KEYBOARD + ")\ninstalled = arrayof(" + HIDDEN_KEYBOARD + ")"}) {
            run(lists);
            Scripts.run(filter, lists);
            for (String[] call : calls) {
                String setting = applications("input_method", call[2], "com.third.keyboard/.Hidden");
                Varargs current = Scripts.run(filter, "local param = paramof({}, {})\n" +
                        "param.getResult = function(self) return " + call[3] + " end\n" +
                        "param.setResult = function(self, value) self.result = value end\n" +
                        "assert(after(hookof('', '" + call[0] + "', '" + call[1] + "'), param))\n" +
                        "return param.result ~= nil and param.result:getId() or nil");
                assertEquals(lists + " " + call[1], setting.isEmpty() ? null : setting,
                        current.arg1().isnil() ? null : current.arg1().tojstring());
            }

            String setting = applications("input_method", "enabled_input_methods", enabledSetting());
            Varargs list = Scripts.run(filter, "local param = paramof({}, {})\n" +
                    "param.getResult = function(self) return { toArray = function(self) return enabled end } end\n" +
                    "param.setResult = function(self, value) self.result = value end\n" +
                    "assert(after(hookof('', 'android.view.inputmethod.InputMethodManager', 'getEnabledInputMethodList'), param))\n" +
                    "local ids = {}\n" +
                    "for index = 1, param.result:size() do ids[index] = param.result.items[index]:getId() end\n" +
                    "return table.concat(ids, ':')");
            assertEquals(lists, setting == null ? "" : setting, list.arg1().tojstring());
        }
    }

    // Only the services of the app itself stay: those of system apps, like a preinstalled screen reader, and the features
    // of the framework tell how the user uses the device too
    @Test
    public void accessibilityServicesOfOtherAppsAreHidden() {
        assertEquals("com.example.app/.Reader",
                applications("accessibility", "enabled_accessibility_services",
                        "com.third.reader/.Reader:com.example.app/.Reader:com.google.android.marvin.talkback/.TalkBackService"));
        assertEquals(null, applications("accessibility", "enabled_accessibility_services",
                "com.google.android.marvin.talkback/.TalkBackService"));
        // Also of a system app the package manager tells about, which has no service in the lists
        assertEquals(null, applications("accessibility", "accessibility_shortcut_target_service", "com.google.android.gms/.Reader"));
        assertEquals("com.example.app/.Reader",
                applications("accessibility", "accessibility_button_targets",
                        "com.android.server.accessibility.MagnificationController:com.third.reader/.Reader:" +
                                "com.android.server.accessibility/ColorInversion:com.example.app/.Reader"));
        assertEquals(null, applications("accessibility", "accessibility_button_targets",
                "com.android.server.accessibility.MagnificationController:com.android.server.accessibility/ColorInversion"));
        assertEquals(null, applications("accessibility", "touch_exploration_granted_accessibility_services",
                "com.third.reader/.Reader"));
        assertEquals("same", applications("input_method", "accessibility_shortcut_target_service",
                "com.third.reader/.Reader"));
    }

    // Accessibility and touch exploration are off when only hidden services turn them on, like AccessibilityManager tells
    // the app (see service_filter); a visible service which turns them on, or none, like for a UiAutomation, keeps them
    @Test
    public void accessibilityIsOffForOnlyHiddenServices() {
        // Enabled services by their package, whether they are of the system, their flags and capabilities; with flag 4 a
        // service asks for touch exploration, which capability 2 lets it turn on
        run("function enabledof(...)\n" +
                "  local items = {}\n" +
                "  for index, spec in ipairs({ ... }) do\n" +
                "    items[index] = { flags = spec[3], getCapabilities = function(self) return spec[4] or 0 end,\n" +
                "      getResolveInfo = function(self)\n" +
                "        return { serviceInfo = { applicationInfo = appof(spec[1], spec[1] == 'com.example.app' and 10200 or 10101,\n" +
                "          spec[2]) } } end }\n" +
                "  end\n" +
                "  local array = arrayof(table.unpack(items))\n" +
                "  enabledservices = { toArray = function(self) return array end }\n" +
                "end");
        assertTrue(run("return hooked('accessibility_enabled') and hooked('touch_exploration_enabled')").arg1().toboolean());
        run("enabledof({ 'com.third.reader', false, 4 })");
        assertEquals("0", applications("accessibility", "accessibility_enabled", "1"));
        assertEquals("0", applications("accessibility", "touch_exploration_enabled", "1"));
        assertEquals("same", applications("accessibility", "accessibility_enabled", "0"));
        // Of the accessibility variant only; apps given the permission to change secure settings get them off too, since
        // Android sets them again from the services it binds, while the system sees all services
        assertEquals("same", applications("input_method", "accessibility_enabled", "1"));
        run("permissions['android.permission.WRITE_SECURE_SETTINGS'] = true");
        assertEquals("0", applications("accessibility", "accessibility_enabled", "1"));
        assertEquals("0", applications("accessibility", "touch_exploration_enabled", "1"));
        run("permissions = {}");
        assertEquals("same", applications("accessibility", "accessibility_enabled", "1", 1000));

        // Also for a service of a system app alone, like a preinstalled screen reader, which asks for touch exploration
        // and can turn it on
        run("enabledof({ 'com.google.android.marvin.talkback', true, 4, 2 })");
        assertEquals("0", applications("accessibility", "accessibility_enabled", "1"));
        assertEquals("0", applications("accessibility", "touch_exploration_enabled", "1"));

        // A service of the app itself keeps accessibility on, and touch exploration when it asks for it
        run("enabledof({ 'com.third.reader', false, 4 }, { 'com.example.app', false, 0 })");
        assertEquals("same", applications("accessibility", "accessibility_enabled", "1"));
        assertEquals("0", applications("accessibility", "touch_exploration_enabled", "1"));
        run("enabledof({ 'com.third.reader', false, 0 }, { 'com.example.app', false, 4, 2 })");
        assertEquals("same", applications("accessibility", "touch_exploration_enabled", "1"));
        // Only with the capability, without which a service asking for it cannot turn it on
        run("enabledof({ 'com.third.reader', false, 4, 2 }, { 'com.example.app', false, 4 })");
        assertEquals("0", applications("accessibility", "touch_exploration_enabled", "1"));
        run("enabledof()");
        assertEquals("same", applications("accessibility", "accessibility_enabled", "1"));
        assertEquals("same", applications("accessibility", "touch_exploration_enabled", "1"));
    }

    @Test
    public void otherAppsNamedBySettingsAreHidden() {
        // Hidden by the package visibility rules, a system app, which is hidden too, and the app itself
        assertEquals("com.example.app/.Listener",
                applications("input_method", "enabled_notification_listeners",
                        "com.third.hidden/.Listener:com.google.android.gms/.Listener:com.example.app/.Listener"));
        // Visible, but not of the system, and a system app: none left, like a setting which is not set
        assertEquals(null,
                applications("input_method", "enabled_notification_policy_access_packages",
                        "com.third.visible:com.google.android.gms"));
        assertEquals("same", applications("input_method", "sms_default_application", "com.example.app"));
        // The autofill service of a system app stays, like getAutofillServiceComponentName names it (see package_result)
        assertEquals("same", applications("input_method", "autofill_service",
                "com.google.android.gms/com.google.android.gms.autofill.service.AutofillService"));
        assertEquals(null, applications("input_method", "autofill_service", "com.third.hidden/.Autofill"));
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
        assertEquals("com.example.app/.Dream",
                applications("input_method", "screensaver_components",
                        "com.example.app/.Dream,com.third.hidden/.Dream"));
        assertEquals(null, applications("input_method", "screensaver_components",
                "com.third.hidden/.Dream,com.other.hidden/.Dream"));
        // The screen saver of a system app, like of the clock, which a device where none is chosen does not name
        assertEquals(null, applications("input_method", "screensaver_components",
                "com.google.android.deskclock/com.android.deskclock.Screensaver"));
        assertEquals("com.example.app/.Dream", applications("input_method", "screensaver_components",
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
        assertEquals(null, applications("input_method", "media_button_receiver",
                "com.google.android.gms/.MediaReceiver,0,1"));
        assertEquals("same", applications("input_method", "media_button_receiver",
                "com.example.app/.MediaReceiver,0,1"));
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
        assertEquals("", applications("input_method", "default_input_method", "com.third.keyboard/.Keyboard", 1010200));
    }

    @Test
    public void settingsWithFrameworkValuesAreNotFaked() {
        for (String key : new String[]{"font_scale", "animator_duration_scale", "http_proxy"})
            assertFalse(key, run("return hooked('" + key + "')").arg1().toboolean());
        assertTrue(run("return hooked('screen_off_timeout')").arg1().toboolean());
        assertFalse(run("return hooked(nil)").arg1().toboolean());
    }

    // Data roaming is off also for each SIM of a device with more than one, as data_roaming with the id of the
    // subscription, by the getters and in the list of the provider; other settings of the name stay
    @Test
    public void dataRoamingOfEachSimIsOff() {
        for (String key : new String[]{"data_roaming", "data_roaming1", "data_roaming12", "data_roaming-1"}) {
            assertTrue(key, run("return hooked('" + key + "')").arg1().toboolean());
            assertEquals(key, "0", applications("settings", key, "1"));
            assertEquals(key, "same", applications("settings", key, "0"));
        }
        for (String key : new String[]{"data_roaming_is_user_setting_key", "data_roamingx", "data_roaming1x", "xdata_roaming1"})
            assertFalse(key, run("return hooked('" + key + "')").arg1().toboolean());
        assertEquals("same", applications("adb", "data_roaming1", "1"));
        Varargs result = list("settings", "data_roaming=1", "data_roaming2=1", "mobile_data2=1");
        assertTrue(result.arg1().toboolean());
        assertEquals("data_roaming,data_roaming2", result.arg(2).tojstring());
        assertEquals("[data_roaming=0, data_roaming2=0, mobile_data2=1]", result.arg(3).tojstring());
    }

    // TelephonyManager.isDataRoamingEnabled, which has no arguments, is off like the setting; false and the exception
    // of Android, like the SecurityException without ACCESS_NETWORK_STATE, stay
    @Test
    public void dataRoamingOfTelephonyIsOff() {
        String call = "local param = paramof({}, {})\n" +
                "param.getArgument = function(self, index) error('ArrayIndexOutOfBoundsException: Argument #' .. index) end\n" +
                "param.getResult = function(self) return %s end\n" +
                "param.getException = function(self) return %s end\n" +
                "param.setResult = function(self, value) self.result = value end\n" +
                "local restricted = after(hookof('TelephonyManager.isDataRoamingEnabled/settings', " +
                "'android.telephony.TelephonyManager', 'isDataRoamingEnabled'), param)\n" +
                "return restricted, param.result";
        Varargs result = run(String.format(call, "true", "nil"));
        assertTrue(result.arg1().toboolean());
        assertFalse(result.arg(2).isnil());
        assertFalse(result.arg(2).toboolean());
        result = run(String.format(call, "false", "nil"));
        assertFalse(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
        result = run(String.format(call, "nil", "'java.lang.SecurityException'"));
        assertFalse(result.arg1().toboolean());
        assertTrue(result.arg(2).isnil());
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
        assertTrue(run("return hooked('stay_on_while_plugged_in')").arg1().toboolean());
        assertEquals("0", applications("adb", "stay_on_while_plugged_in", "3"));
        assertEquals("same", applications("adb", "stay_on_while_plugged_in", "0"));
        assertEquals("0", applications("adb", "adb_enabled", "1"));
        assertEquals("same", applications("settings", "stay_on_while_plugged_in", "7"));
        // The name Android has, not the one without _in
        assertFalse(run("return hooked('stay_on_while_plugged')").arg1().toboolean());
        // Battery saver is off, also the one kept on after charging
        assertEquals("0", applications("battery", "low_power_sticky", "1"));
        assertEquals("same", applications("battery", "low_power_sticky", "0"));
    }

    // The options which Settings turns off with developer options are off too, like Show taps, and no app waits to be
    // debugged; one which was never set stays unset
    @Test
    public void developerOptionsAreOff() {
        for (String key : new String[]{"show_touches", "pointer_location", "debug_view_attributes", "wait_for_debugger",
                "always_finish_activities"}) {
            assertTrue(key, run("return hooked('" + key + "')").arg1().toboolean());
            assertEquals(key, "0", applications("adb", key, "1"));
            assertEquals(key, "same", applications("adb", key, "0"));
        }
        assertEquals(null, applications("adb", "debug_app", "com.example.app"));
        assertEquals("same", run("local changed = fakesetting(paramof({}, {}, 10200), 'adb', 'show_touches', nil)\n" +
                "return changed and 'changed' or 'same'").arg1().tojstring());
        assertEquals("same", applications("settings", "show_touches", "1"));
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

    // The subtypes of the keyboards in the secure settings, named by their hashCode, are only those of the fake languages
    // or of none for Read.Locale, like the getters of InputMethodManager give them: of the enabled ones, of the history
    // with its entry, and the selected one becomes the first enabled one with a fake language, else none (-1). An id of
    // no subtype of the keyboard goes too, apps which can change the settings get them as they are, and without the
    // keyboards every id goes. The functions which tell the language of a subtype are those of locale.
    @Test
    public void keyboardSubtypesOfOtherLanguagesAreLeftOut() throws Exception {
        Varargs result = run(String.join("\n",
                "local function subtypeof(hash, tag)",
                "  return { hashCode = function(self) return hash end, getLanguageTag = function(self) return tag end,",
                "    getLocale = function(self) return '' end }",
                "end",
                "local en, de, none, fr = subtypeof(11, 'en-US'), subtypeof(-22, 'de-DE'), subtypeof(33, ''), subtypeof(44, 'fr-FR')",
                "local function keyboardof(id, ...)",
                "  local subtypes = { ... }",
                "  return { getId = function(self) return id end, getSubtypeCount = function(self) return #subtypes end,",
                "    getSubtypeAt = function(self, index) return subtypes[index + 1] end }",
                "end",
                "local keyboards = arrayof(keyboardof('com.a/.K', en, de), keyboardof('com.b/.K', none, fr))",
                "local enabled = arrayof(de, en)",
                "local manager = {",
                "  getInputMethodList = function(self)",
                "    if keyboards == nil then error('DeadObjectException') end",
                "    return { toArray = function(self) return keyboards end }",
                "  end,",
                "  getEnabledInputMethodSubtypeList = function(self, imi, implicit)",
                "    assert(imi == nil and implicit)",
                "    return { toArray = function(self) return enabled end }",
                "  end }",
                "local function setting(key, value, uid)",
                "  local param = paramof({}, {}, uid)",
                "  param.getApplicationContext = function(self)",
                "    return { getSystemService = function(self, name) assert(name == 'input_method', name) return manager end,",
                "      checkSelfPermission = function(self, name) return -1 end }",
                "  end",
                "  local changed, fake = fakesetting(param, 'locale', key, value)",
                "  return (changed and fake or 'same')",
                "end",
                "local all = {",
                "  setting('enabled_input_methods', 'com.a/.K;11;-22:com.b/.K;33;44:com.c/.K'),",
                "  setting('enabled_input_methods', 'com.a/.K;55;-22:com.b/.K;11;33'),",
                "  setting('enabled_input_methods', 'com.a/.K;11'),",
                "  setting('input_methods_subtype_history', 'com.a/.K;-22:com.b/.K;33:com.a/.K;-1'),",
                "  setting('selected_input_method_subtype', '-22'),",
                "  setting('selected_input_method_subtype', '11'),",
                "  setting('selected_input_method_subtype', '-1'),",
                "  setting('enabled_input_methods', 'com.a/.K;11;-22', 1000) }",
                "enabled = arrayof(de)",
                "all[#all + 1] = setting('selected_input_method_subtype', '-22')",
                "keyboards, enabled = nil, arrayof(de, en)",
                "all[#all + 1] = setting('enabled_input_methods', 'com.a/.K;11;-22')",
                "all[#all + 1] = setting('input_methods_subtype_history', 'com.a/.K;11:com.b/.K;-1')",
                "all[#all + 1] = setting('selected_input_method_subtype', '-22')",
                "return table.unpack(all)"));
        String[] expected = {"com.a/.K;11:com.b/.K;33:com.c/.K", "com.a/.K:com.b/.K;33", "same", "com.b/.K;33:com.a/.K;-1",
                "11", "same", "same", "same", "-1", "com.a/.K", "com.b/.K;-1", "11"};
        for (int i = 0; i < expected.length; i++)
            assertEquals("case " + (i + 1), expected[i], result.arg(i + 1).tojstring());
        assertTrue(run("return hooked('selected_input_method_subtype')").arg1().toboolean());
        assertEquals("same", applications("input_method", "selected_input_method_subtype", "-22"));
        assertEquals(Scripts.function("locale", "typed"), Scripts.function("settingssecure_getstring", "typed"));
        assertEquals(Scripts.function("locale", "subtypetag"), Scripts.function("settingssecure_getstring", "subtypetag"));
    }

    // The name of the device for Wi-Fi Direct, which users set like the name of the device, often with their own name
    @Test
    public void wifiDirectNameIsMissing() {
        assertTrue(run("return hooked('wifi_p2p_device_name')").arg1().toboolean());
        assertEquals(null, applications("device_name", "wifi_p2p_device_name", "Pixel of Alice"));
        // The name of the device is the fake model, which Android gives when the user set no name
        assertEquals("unknown", applications("device_name", "device_name", "Pixel of Alice"));
        assertEquals("same", applications("device_name", "device_name", "unknown"));
    }

    // The private DNS server, which names the account of the user, is none, like LinkProperties.getPrivateDnsServerName
    // gives it, and a mode by hostname is the automatic one; a mode which is off stays
    @Test
    public void privateDnsIsNone() {
        assertTrue(run("return hooked('private_dns_specifier')").arg1().toboolean());
        assertEquals(null, applications("network", "private_dns_specifier", "abc123.dns.nextdns.io"));
        assertEquals("opportunistic", applications("network", "private_dns_mode", "hostname"));
        assertEquals("same", applications("network", "private_dns_mode", "off"));
        assertEquals("same", applications("tracking", "private_dns_mode", "hostname"));
    }

    // The settings of fingerprint and face unlock, which a device without the hardware does not have, also by the
    // provider and by the name of Android 16 (fingerptint); the ones which are not set and other settings stay
    @Test
    public void biometricSettingsAreMissing() {
        for (String key : new String[]{"face_unlock_keyguard_enabled", "biometric_keyguard_enabled", "biometric_app_enabled",
                "face_unlock_re_enroll", "fingerptint_app_enabled", "face_keyguard_enabled", "screen_off_udfps_enabled"}) {
            assertTrue(key, run("return hooked('" + key + "')").arg1().toboolean());
            assertEquals(key, null, applications("biometrics", key, "1"));
            assertTrue(key, run("return (fakeany(paramof({}, {}), 'biometrics', '" + key + "', '0'))").arg1().toboolean());
        }
        assertEquals("same", run("local changed = fakesetting(paramof({}, {}), 'biometrics', 'face_unlock_app_enabled', nil)\n" +
                "return changed and 'changed' or 'same'").arg1().tojstring());
        assertEquals("same", applications("settings", "biometric_app_enabled", "1"));
        assertEquals("same", applications("biometrics", "adb_enabled", "1"));
        assertFalse(run("return hooked('fingerprint_app_enabled')").arg1().toboolean());
    }

    // How often the device started: of a device started once, which Android always counts
    @Test
    public void bootCountIsOne() {
        assertEquals("1", applications("boot_count", "boot_count", "287"));
        assertEquals("same", applications("boot_count", "boot_count", "1"));
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

        // The provider fills each value column of a projection which names it twice: each is faked, and a setting
        // which is missing has no row at all
        result = query("identifiers", "uriof('settings', 'secure', 'android_id')", "'value', 'value'",
                "{ '1234abcd1234abcd', '1234abcd1234abcd' }", "nil");
        assertTrue(result.arg1().toboolean());
        assertEquals("0000000000000000,0000000000000000", result.arg(2).tojstring());
        result = query("identifiers", "uriof('settings', 'secure')", "'name', 'value', 'value'",
                "{ 'android_id', '1234abcd1234abcd', '1234abcd1234abcd' }, { 'pps_oaid', '4f3e2d1c-real', '4f3e2d1c-real' }, " +
                        "{ 'adb_enabled', '1', '1' }", "nil");
        assertTrue(result.arg1().toboolean());
        assertEquals("android_id,0000000000000000,0000000000000000;adb_enabled,1,1", result.arg(2).tojstring());

        // Without the name column, the selection tells the setting
        result = query("identifiers", "uriof('settings', 'secure')", "'value'", "{ '1234abcd1234abcd' }",
                "{ getString = function(self, key) return \"name='android_id'\" end," +
                        " getStringArray = function(self, key) return nil end }");
        assertTrue(result.arg1().toboolean());
        assertEquals("0000000000000000", result.arg(2).tojstring());

        // All values without their names, when the query again with the names fails: there are none
        run("resolver = { query = function(self) error('OperationCanceledException') end }");
        result = query("identifiers", "uriof('settings', 'secure')", "'value'", "{ '1234abcd1234abcd' }, { '1' }", "nil");
        assertTrue(result.arg1().toboolean());
        assertEquals("", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
    }

    // A query of all settings without the name column, which the hook runs again with the names last, unhooked
    private void named(String rows) {
        run("resolver = { query = function(self, uri, projection, queryArgs, signal)\n" +
                "  queried = table.concat(projection, ',')\n" +
                "  again = cursorof(arrayof('_id', 'value', 'name'), { " + rows + " })\n" +
                "  return again\n" +
                "end }");
    }

    // Without the names, Android gives a row for each setting: the settings of other apps stay, like on a device without
    // the hook, instead of none
    @Test
    public void queriesOfAllValuesKeepTheirRows() {
        named("{ 1, '1', 'adb_enabled' }, { 2, '30000', 'screen_off_timeout' }");
        Varargs result = query("identifiers", "uriof('settings', 'secure')", "'_id', 'value'", "{ 1, '1' }, { 2, '30000' }", "nil");
        assertFalse(result.arg1().toboolean());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertEquals("_id,value,name", run("return queried").arg1().tojstring());
        assertTrue(run("return again.closed").arg1().toboolean());

        // Only the rows of the faked settings change, one which is missing has none
        named("{ 1, '1234abcd1234abcd', 'android_id' }, { 2, '4f3e2d1c-real', 'pps_oaid' }, { 3, '1', 'adb_enabled' }");
        result = query("identifiers", "uriof('settings', 'secure')", "'_id', 'value'",
                "{ 1, '1234abcd1234abcd' }, { 2, '4f3e2d1c-real' }, { 3, '1' }", "nil");
        assertTrue(result.arg1().toboolean());
        assertEquals("1,0000000000000000;3,1", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertTrue(run("return again.closed").arg1().toboolean());
    }

    // The hooks of each group fake the same query: the next ones get the names of the rows the first one changed,
    // which no longer match the rows of the provider
    @Test
    public void queriesOfAllValuesAreFakedByEachGroup() {
        String services = "'com.third.reader/.Reader:com.example.app/.Reader'";
        named("{ 1, '1234abcd1234abcd', 'android_id' }, { 2, " + services + ", 'enabled_accessibility_services' }, " +
                "{ 3, '1', 'adb_enabled' }");
        Varargs result = run("local cursor = cursorof(arrayof('_id', 'value'), { { 1, '1234abcd1234abcd' }, " +
                "{ 2, " + services + " }, { 3, '1' } })\n" +
                "local param = paramof({ [0] = uriof('settings', 'secure') }, {})\n" +
                "local values = {}\n" +
                "param.putValue = function(self, name, value, scope)\n" +
                "  values[scope] = values[scope] or {}\n" +
                "  values[scope][name] = value\n" +
                "end\n" +
                "param.getValue = function(self, name, scope) return values[scope] and values[scope][name] end\n" +
                "param.getResult = function(self) return self.result end\n" +
                "param.setResult = function(self, value) self.result = value end\n" +
                "param.result = cursor\n" +
                "local count = 0\n" +
                "local query = resolver.query\n" +
                "resolver.query = function(...) count = count + 1 return query(...) end\n" +
                "local restricted = {}\n" +
                "for _, group in ipairs({ 'identifiers', 'debugging', 'applications' }) do\n" +
                "  restricted[#restricted + 1] = tostring(after(hookof('ContentResolver.query/' .. group, " +
                "'android.content.ContentResolver', 'query'), param))\n" +
                "end\n" +
                "local lines = {}\n" +
                "for _, row in ipairs(param.result.rows) do lines[#lines + 1] = row[1] .. ',' .. row[2] end\n" +
                "return table.concat(restricted, ','), table.concat(lines, ';'), count");
        assertEquals("true,true,true", result.arg1().tojstring());
        assertEquals("1,0000000000000000;2,com.example.app/.Reader;3,0", result.arg(2).tojstring());
        assertEquals(1, result.arg(3).toint());
    }

    // Stand-ins of CursorWrapper, which declares the cursor it wraps, of the CursorWrapperInner of ContentResolver
    // and of a cursor, as Java objects for the reflection of the script
    public static class Rows {
        public boolean closed;

        public void close() {
            closed = true;
        }
    }

    public static class Wrapper {
        protected Rows mCursor; // not final: scripts set it, which the JDK warns about

        Wrapper(Rows cursor) {
            mCursor = cursor;
        }

        public Rows getWrappedCursor() {
            return mCursor;
        }
    }

    public static class Inner extends Wrapper {
        public boolean closed;

        Inner(Rows cursor) {
            super(cursor);
        }

        public void close() {
            closed = true;
            mCursor.close();
        }
    }

    // ContentResolver and ContentProviderClient wrap the cursor of the provider in a class of their own, which the app
    // can check: it stays, with the fake rows in it, and releases the provider when the app closes it, like on Android
    @Test
    public void fakeRowsAreInTheWrapperOfAndroid() {
        Rows rows = new Rows();
        Inner wrapper = new Inner(rows);
        Rows fake = new Rows();
        globals.set("wrapper", CoerceJavaToLua.coerce(wrapper));
        globals.set("fake", CoerceJavaToLua.coerce(fake));
        Varargs result = run("local param = paramof({}, {})\n" +
                "param.isInstance = function(self, object, name) return name == 'android.database.CursorWrapper' end\n" +
                "param.setResult = function(self, value) self.result = value end\n" +
                "return replace(param, wrapper, fake) == wrapper, param.result == nil");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals(fake, wrapper.getWrappedCursor());
        assertTrue(rows.closed);
        assertFalse(wrapper.closed);
        assertFalse(fake.closed);

        // A cursor of another class is replaced, like before
        Inner other = new Inner(new Rows());
        globals.set("wrapper", CoerceJavaToLua.coerce(other));
        result = run("local param = paramof({}, {})\n" +
                "param.setResult = function(self, value) self.result = value end\n" +
                "return replace(param, wrapper, fake) == fake, param.result == fake");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(other.closed);
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

    // The cache below the getters serves every table: the Android ID of the secure one is faked, the null of the others stays
    @Test
    public void cacheOfSettingsIsFaked() {
        String call = "local param = paramof({ [0] = {}, [1] = 'android_id', [2] = 0 }, { ['value.android_id'] = 'fake1234fake1234' })\n" +
                "param.getResult = function(self) return %s end\n" +
                "param.setResult = function(self, value) self.result = value end\n" +
                "local restricted = after(hookof('Settings.NameValueCache.getStringForUser/android_id', " +
                "'android.provider.Settings$NameValueCache', 'getStringForUser'), param)\n" +
                "return restricted, param.result";
        Varargs result = run(String.format(call, "'1234abcd1234abcd'"));
        assertTrue(result.arg1().toboolean());
        assertEquals("fake1234fake1234", result.arg(2).tojstring());
        assertFalse(run(String.format(call, "nil")).arg1().toboolean());
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

        result = list("applications", "enabled_notification_listeners=com.third.hidden/.Listener:com.google.android.gms/.Listener:" +
                "com.example.app/.Listener");
        assertEquals("[enabled_notification_listeners=com.example.app/.Listener]", result.arg(3).tojstring());
        // No keyboard is left: the system keyboard which Android enables then, and without one the default is empty,
        // like Android keeps it, and the list of the enabled ones is not set
        run("enabled = arrayof(" + HIDDEN_KEYBOARD + ")");
        result = list("applications", "default_input_method=com.third.keyboard/.Keyboard",
                "enabled_input_methods=com.third.keyboard/.Keyboard");
        assertEquals("[default_input_method=com.android.inputmethod.latin/.LatinIME, " +
                "enabled_input_methods=com.android.inputmethod.latin/.LatinIME]", result.arg(3).tojstring());
        run("installed = arrayof(" + HIDDEN_KEYBOARD + ")");
        result = list("applications", "default_input_method=com.third.keyboard/.Keyboard",
                "enabled_input_methods=com.third.keyboard/.Keyboard");
        assertEquals("[default_input_method=]", result.arg(3).tojstring());

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
