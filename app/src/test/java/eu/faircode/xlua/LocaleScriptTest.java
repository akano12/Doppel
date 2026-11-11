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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

// The fake languages of locale.lua: lists of the app first, then the fake ones; the LocaleList of Android
// is a stand-in (a stub in unit tests), java.util.Locale is the real one
public class LocaleScriptTest {
    private static final String STUBS = String.join("\n",
            "function hookof(class, method, count, name)",
            "  return { getName = function(self) return name or 'Hook/locale' end,",
            "    getClassName = function(self) return class end,",
            "    getMethodName = function(self) return method end,",
            "    getParameterTypes = function(self) return { length = count or 1 } end }",
            "end",
            "settings = { ['value.locale'] = 'en-US,fr-FR' }",
            "system = { 'de-DE', 'tr-TR' }",
            "function paramof(result, args)",
            "  return { result = result, args = args or {},",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value end,",
            "    getArgument = function(self, index) return self.args[index] end,",
            "    setArgument = function(self, index, value) self.args[index] = value end,",
            "    getSetting = function(self, name) return settings[name] end }",
            "end",
            "function listof(tags) local text = table.concat(tags, ',')",
            "  return { toLanguageTags = function(self) return text end } end",
            "function systemtags(param) return system end",
            // A call of the system by default, see bysystem
            "function frameof(class, method)",
            "  return { getClassName = function(self) return class end, getMethodName = function(self) return method end }",
            "end",
            "stack = { length = 1, [1] = frameof('android.app.ConfigurationController', 'updateLocaleListFromAppContext') }",
            "function callers() return stack end",
            "function ttslocales(param) return tts or '' end",
            "Locale = luajava.bindClass('java.util.Locale')");

    private static Globals load() throws Exception {
        return Scripts.load("locale", STUBS);
    }

    @Test
    public void listsKeepTheLanguagesOfTheApp() throws Exception {
        Globals globals = load();
        // The list of the system becomes the fake one, the language chosen for the app stays first
        Varargs result = Scripts.run(globals,
                "local param = paramof(nil, { [0] = listof({ 'de-DE', 'tr-TR' }) })\n" +
                        "local changed = before(hookof('android.os.LocaleList', 'setDefault'), param)\n" +
                        "local app = paramof(nil, { [0] = listof({ 'ja-JP', 'de-DE', 'tr-TR' }), [1] = 0 })\n" +
                        "before(hookof('android.os.LocaleList', 'setDefault', 2), app)\n" +
                        // The system chose tr-TR, which the app has texts for: the first fake one
                        "local index = paramof(nil, { [0] = listof({ 'de-DE', 'tr-TR' }), [1] = 1 })\n" +
                        "before(hookof('android.os.LocaleList', 'setDefault', 2), index)\n" +
                        "return changed, param.args[0]:toLanguageTags(), app.args[0]:toLanguageTags(), app.args[1],\n" +
                        "  index.args[0]:toLanguageTags(), index.args[1],\n" +
                        "  before(hookof('android.os.LocaleList', 'setDefault'), paramof(nil, { [0] = listof({ 'en-US', 'fr-FR' }) }))");
        assertTrue(result.arg(1).toboolean());
        assertEquals("en-US,fr-FR", result.arg(2).tojstring());
        assertEquals("ja-JP,en-US,fr-FR", result.arg(3).tojstring());
        assertEquals(0, result.arg(4).toint());
        assertEquals("en-US,fr-FR", result.arg(5).tojstring());
        assertEquals(0, result.arg(6).toint());
        assertFalse(result.arg(7).toboolean());

        // Without the list of the system all languages are replaced
        assertEquals("en-US,fr-FR", Scripts.run(globals, "return table.concat(merge({ 'ja-JP', 'de-DE' }, nil, { 'en-US', 'fr-FR' }), ',')")
                .arg1().tojstring());
    }

    // Android 13: the language the user chose for the app is not a language of the device for the merge, also when
    // it is one, so it stays first; without the languages of the app those of the device stay
    @Test
    public void languageChosenForTheAppStaysFirst() throws Exception {
        Globals globals = Scripts.load("locale", null);
        Varargs result = Scripts.run(globals, "local real = systemtags\n" + STUBS + "\nsystemtags = real\n" +
                "local app = { 'de-DE' }\n" +
                "local manager = { getSystemLocales = function(self) return listof({ 'en-US', 'de-DE' }) end,\n" +
                "  getApplicationLocales = function(self) if app == nil then error('denied') end return listof(app) end }\n" +
                "local function paramwith(args)\n" +
                "  local param = paramof(nil, args)\n" +
                "  param.unhooked = function(self, fn) return fn() end\n" +
                "  param.getApplicationContext = function(self)\n" +
                "    return { getSystemService = function(self, name) return manager end } end\n" +
                "  return param\n" +
                "end\n" +
                "local luajavaReal = luajava\n" +
                "luajava = { bindClass = function(name)\n" +
                "  if name == 'android.os.Build$VERSION' then return { SDK_INT = 34 } end\n" +
                "  return luajavaReal.bindClass(name) end }\n" +
                // The configuration of the process: the languages of the app, then those of the device
                "local param = paramwith({ [0] = listof({ 'de-DE', 'en-US' }), [1] = 0 })\n" +
                "local changed = before(hookof('android.os.LocaleList', 'setDefault', 2), param)\n" +
                "local chosen = table.concat(systemtags(param), ',')\n" +
                "app = {}\n" +
                "local none = table.concat(systemtags(param), ',')\n" +
                "app = nil\n" +
                "local failed = table.concat(systemtags(param), ',')\n" +
                "luajava = luajavaReal\n" +
                "return changed, param.args[0]:toLanguageTags(), param.args[1], chosen, none, failed");
        assertTrue(result.arg(1).toboolean());
        assertEquals("de-DE,en-US,fr-FR", result.arg(2).tojstring());
        assertEquals(0, result.arg(3).toint());
        assertEquals("en-US", result.arg(4).tojstring());
        assertEquals("en-US,de-DE", result.arg(5).tojstring());
        assertEquals("en-US,de-DE", result.arg(6).tojstring());
    }

    @Test
    public void localesOfTheDeviceAreReplaced() throws Exception {
        Globals globals = load();
        Varargs result = Scripts.run(globals,
                "local device = paramof(nil, { [0] = Locale:forLanguageTag('de-DE') })\n" +
                        "local changed = before(hookof('java.util.Locale', 'setDefault'), device)\n" +
                        "local category = paramof(nil, { [0] = 'FORMAT', [1] = Locale:forLanguageTag('tr-TR') })\n" +
                        "before(hookof('java.util.Locale', 'setDefault', 2), category)\n" +
                        "return changed, device.args[0]:toLanguageTag(), category.args[1]:toLanguageTag(),\n" +
                        "  before(hookof('java.util.Locale', 'setDefault'), paramof(nil, { [0] = Locale:forLanguageTag('ja-JP') })),\n" +
                        "  before(hookof('java.util.Locale', 'setDefault'), paramof(nil, { [0] = Locale:forLanguageTag('fr-FR') }))");
        assertTrue(result.arg(1).toboolean());
        assertEquals("en-US", result.arg(2).tojstring());
        assertEquals("en-US", result.arg(3).tojstring());
        assertFalse(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
    }

    // What the app sets itself stays, a language of the device other than the first like any other, like on a device
    // without the hooks; only updateLocaleListFromAppContext of ActivityThread (to Android 11) and ConfigurationController
    // is the system
    @Test
    public void whatTheAppSetsStays() throws Exception {
        Globals globals = load();
        Varargs result = Scripts.run(globals,
                "local function locale(tag)\n" +
                        "  local param = paramof(nil, { [0] = Locale:forLanguageTag(tag) })\n" +
                        "  return before(hookof('java.util.Locale', 'setDefault'), param), param.args[0]:toLanguageTag()\n" +
                        "end\n" +
                        "local function list(tag)\n" +
                        "  local param = paramof(nil, { [0] = listof({ tag }) })\n" +
                        "  return before(hookof('android.os.LocaleList', 'setDefault'), param), param.args[0]:toLanguageTags()\n" +
                        "end\n" +
                        "stack = { length = 2, [1] = frameof('com.example.Main', 'updateLocaleListFromAppContext'),\n" +
                        "  [2] = frameof('android.app.Activity', 'onCreate') }\n" +
                        "local a, b = locale('tr-TR')\n" +
                        "local c, d = locale('ja-JP')\n" +
                        "local e, f = list('tr-TR')\n" +
                        "local g, h = list('ja-JP')\n" +
                        "stack = { length = 1, [1] = frameof('android.app.ActivityThread', 'updateLocaleListFromAppContext') }\n" +
                        "local i, j = list('tr-TR')\n" +
                        "return a, b, c, d, e, f, g, h, i, j");
        assertFalse(result.arg(1).toboolean());
        assertEquals("tr-TR", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
        assertEquals("ja-JP", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());
        assertEquals("tr-TR", result.arg(6).tojstring());
        assertFalse(result.arg(7).toboolean());
        assertEquals("ja-JP", result.arg(8).tojstring());
        assertTrue(result.arg(9).toboolean());
        assertEquals("en-US,fr-FR", result.arg(10).tojstring());
    }

    // A list of the app with all the languages of the device, in any order, like AppCompat before Android 13 makes from
    // the configuration, is faked, on a device with one language a list of that language alone too (the shortcut of
    // holds); a list with some of them, an empty one and an index out of the list stay
    @Test
    public void listsWithTheLanguagesOfTheDeviceAreFaked() throws Exception {
        Globals globals = load();
        Varargs result = Scripts.run(globals,
                "local function list(tags, index)\n" +
                        "  local param = paramof(nil, { [0] = listof(tags), [1] = index })\n" +
                        "  local changed = before(hookof('android.os.LocaleList', 'setDefault', index and 2 or 1), param)\n" +
                        "  return changed, param.args[0]:toLanguageTags()\n" +
                        "end\n" +
                        "stack = { length = 1, [1] = frameof('com.example.Main', 'onCreate') }\n" +
                        "local a, b = list({ 'es-ES', 'de-DE', 'tr-TR' })\n" +
                        "local c, d = list({ 'es-ES', 'tr-TR', 'de-DE' }, 0)\n" +
                        "local e, f = list({ 'tr-TR', 'de-DE' })\n" +
                        "local g, h = list({ 'es-ES', 'de-DE' })\n" +
                        "local i = list({})\n" +
                        "local j, k = list({ 'es-ES', 'de-DE', 'tr-TR' }, 3)\n" +
                        "system = { 'de-DE' }\n" +
                        "local m, n = list({ 'de-DE' })\n" +
                        "system = nil\n" +
                        "local l = list({ 'es-ES', 'de-DE', 'tr-TR' })\n" +
                        "return a, b, c, d, e, f, g, h, i, j, k, l, m, n");
        assertTrue(result.arg(1).toboolean());
        assertEquals("es-ES,en-US,fr-FR", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertEquals("es-ES,en-US,fr-FR", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertEquals("en-US,fr-FR", result.arg(6).tojstring());
        assertFalse(result.arg(7).toboolean());
        assertEquals("es-ES,de-DE", result.arg(8).tojstring());
        assertFalse(result.arg(9).toboolean());
        assertFalse(result.arg(10).toboolean());
        assertEquals("es-ES,de-DE,tr-TR", result.arg(11).tojstring());
        assertFalse(result.arg(12).toboolean());
        assertTrue(result.arg(13).toboolean());
        assertEquals("en-US,fr-FR", result.arg(14).tojstring());
    }

    // The first language of the device as the locale of the app, like apps set the locale of their configuration, is
    // faked like a list with all of them, also for a category, so LocaleList.getDefault, which starts with
    // Locale.getDefault, does not start with it; a frame of a hook named like the method is not LocaleList. Not when
    // LocaleList.setDefault sets the locale of the list it is given, nor without the languages of the device.
    @Test
    public void firstLanguageOfTheDeviceIsFaked() throws Exception {
        Globals globals = load();
        Varargs result = Scripts.run(globals,
                "local function locale(tag, category)\n" +
                        "  local args = { [0] = Locale:forLanguageTag(tag) }\n" +
                        "  if category then args = { [0] = 'FORMAT', [1] = Locale:forLanguageTag(tag) } end\n" +
                        "  local param = paramof(nil, args)\n" +
                        "  local changed = before(hookof('java.util.Locale', 'setDefault', category and 2 or 1), param)\n" +
                        "  return changed, args[category and 1 or 0]:toLanguageTag()\n" +
                        "end\n" +
                        "stack = { length = 2, [1] = frameof('LSPHooker_', 'setDefault'),\n" +
                        "  [2] = frameof('com.example.Main', 'onConfigurationChanged') }\n" +
                        "local a, b = locale('de-DE')\n" +
                        "local c, d = locale('de-DE', true)\n" +
                        "stack = { length = 2, [1] = frameof('android.os.LocaleList', 'setDefault'),\n" +
                        "  [2] = frameof('com.example.Main', 'onCreate') }\n" +
                        "local e, f = locale('de-DE')\n" +
                        "stack = { length = 1, [1] = frameof('com.example.Main', 'onConfigurationChanged') }\n" +
                        "system = nil\n" +
                        "local g, h = locale('de-DE')\n" +
                        "return a, b, c, d, e, f, g, h");
        assertTrue(result.arg(1).toboolean());
        assertEquals("en-US", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertEquals("en-US", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());
        assertEquals("de-DE", result.arg(6).tojstring());
        assertFalse(result.arg(7).toboolean());
        assertEquals("de-DE", result.arg(8).tojstring());
    }

    // The languages of the keyboards are only the fake ones; a language alone of a fake one and none count. The
    // keyboard in use gets its first enabled subtype with a fake language, else null, like a keyboard without
    // subtypes, and the one used last null; the voice keyboards keep the kind of map of their version.
    @Test
    public void keyboardsHaveTheFakeLanguages() throws Exception {
        Globals globals = load();
        Varargs result = Scripts.run(globals,
                "local function subtypeof(name, tag, locale)\n" +
                        "  return { name = name, getLanguageTag = function(self) return tag end,\n" +
                        "    getLocale = function(self) return locale end }\n" +
                        "end\n" +
                        "local function subtypes(...)\n" +
                        "  local items = { ... }\n" +
                        "  items.length = select('#', ...)\n" +
                        "  return { toArray = function(self) return items end }\n" +
                        "end\n" +
                        "local function names(list)\n" +
                        "  local all = {}\n" +
                        "  for index = 0, list:size() - 1 do all[#all + 1] = list:get(index).name end\n" +
                        "  return table.concat(all, ',')\n" +
                        "end\n" +
                        "local en, de = subtypeof('en', 'en-US', 'en_US'), subtypeof('de', 'de-DE', 'de_DE')\n" +
                        "local enabled\n" +
                        "local function call(method, result)\n" +
                        "  local param = paramof(result, { [1] = true })\n" +
                        "  param.getThis = function(self)\n" +
                        "    return { getEnabledInputMethodSubtypeList = function(self, imi, implicit) return enabled end }\n" +
                        "  end\n" +
                        "  param.unhooked = function(self, fn) return fn() end\n" +
                        "  local changed, old, new = after(hookof('android.view.inputmethod.InputMethodManager', method, 0), param)\n" +
                        "  return changed, param.result, old, new\n" +
                        "end\n" +
                        "local list = 'getEnabledInputMethodSubtypeList'\n" +
                        "local a, b, c, d = call(list, subtypes(en, de, subtypeof('fr', '', 'fr_FR'), subtypeof('none', '', ''),\n" +
                        "  subtypeof('french', 'fr', ''), subtypeof('british', 'en-GB', 'en_GB')))\n" +
                        "local kept = names(b)\n" +
                        "local added = pcall(function() b:add(en) end)\n" +
                        "local e = call(list, subtypes(en, subtypeof('fr', '', 'fr_FR')))\n" +
                        "enabled = subtypes(de, en)\n" +
                        "local f, g, h, i = call('getCurrentInputMethodSubtype', de)\n" +
                        "enabled = subtypes(de)\n" +
                        "local j, k = call('getCurrentInputMethodSubtype', de)\n" +
                        "local l = call('getCurrentInputMethodSubtype', en)\n" +
                        "enabled = nil\n" +
                        "local x, y = call('getCurrentInputMethodSubtype', de)\n" +
                        "local m, n = call('getLastInputMethodSubtype', de)\n" +
                        "local function mapof(...)\n" +
                        "  local entries = { ... }\n" +
                        "  entries.length = select('#', ...)\n" +
                        "  return { entrySet = function(self) return { toArray = function(self) return entries end } end }\n" +
                        "end\n" +
                        "local function entryof(key, value)\n" +
                        "  return { getKey = function(self) return key end, getValue = function(self) return value end }\n" +
                        "end\n" +
                        "local voice, other = { name = 'voice' }, { name = 'other' }\n" +
                        "local shortcut = 'getShortcutInputMethodsAndSubtypes'\n" +
                        "local real, sdk = luajava, 28\n" +
                        "luajava = { newInstance = real.newInstance, bindClass = function(name)\n" +
                        "  if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end\n" +
                        "  return real.bindClass(name) end }\n" +
                        "local o, p, q, r = call(shortcut, mapof(entryof(voice, subtypes(de, en)), entryof(other, subtypes(de))))\n" +
                        "local v, w = call(shortcut, mapof(entryof(voice, subtypes(de))))\n" +
                        "sdk = 34\n" +
                        "local s, t = call(shortcut, mapof(entryof(voice, subtypes(de))))\n" +
                        "local u = call(shortcut, mapof(entryof(voice, subtypes(en))))\n" +
                        "luajava = real\n" +
                        "return a, kept, c, d, added, e, f, g.name, h, i, j, k, l, m, n,\n" +
                        "  o, p, p:size(), names(p:get(voice)), q, r, s, t, u, v, w, x, y");
        // Of the enabled ones de-DE and en-GB are left out, in an ArrayList like the one of the system
        assertTrue(result.arg(1).toboolean());
        assertEquals("en,fr,none,french", result.arg(2).tojstring());
        assertEquals("6", result.arg(3).tojstring());
        assertEquals("4", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
        // The one in use
        assertTrue(result.arg(7).toboolean());
        assertEquals("en", result.arg(8).tojstring());
        assertEquals("de-DE", result.arg(9).tojstring());
        assertEquals("en-US", result.arg(10).tojstring());
        assertTrue(result.arg(11).toboolean());
        assertTrue(result.arg(12).isnil());
        assertFalse(result.arg(13).toboolean());
        // The one used last
        assertTrue(result.arg(14).toboolean());
        assertTrue(result.arg(15).isnil());
        // The voice keyboards: a HashMap without the keyboards left without a language up to Android 9, later an
        // empty map
        assertTrue(result.arg(16).toboolean());
        assertTrue(result.arg(17).touserdata() instanceof HashMap);
        assertEquals(1, result.arg(18).toint());
        assertEquals("en", result.arg(19).tojstring());
        assertEquals("3", result.arg(20).tojstring());
        assertEquals("1", result.arg(21).tojstring());
        assertTrue(result.arg(22).toboolean());
        assertSame(Collections.emptyMap(), result.arg(23).touserdata());
        assertFalse(result.arg(24).toboolean());
        assertTrue(result.arg(25).toboolean());
        assertTrue(result.arg(26).touserdata() instanceof HashMap);
        assertTrue(((Map<?, ?>) result.arg(26).touserdata()).isEmpty());
        // A failed lookup of the enabled ones gives null too
        assertTrue(result.arg(27).toboolean());
        assertTrue(result.arg(28).isnil());
    }

    // The properties, the setting system_locales and LocaleManager agree with the fake list
    @Test
    public void valuesAgree() throws Exception {
        Globals globals = load();
        Varargs result = Scripts.run(globals,
                "local function prop(key, value)\n" +
                        "  local param = paramof(value, { [0] = key })\n" +
                        "  after(hookof('android.os.SystemProperties', 'get'), param)\n" +
                        "  return param.result\n" +
                        "end\n" +
                        "local setting = paramof('de-DE,tr-TR', { [0] = 'resolver', [1] = 'system_locales' })\n" +
                        "after(hookof('android.provider.Settings$System', 'getStringForUser'), setting)\n" +
                        "local other = paramof('1', { [0] = 'resolver', [1] = 'screen_brightness' })\n" +
                        "local manager = paramof(listof(system))\n" +
                        "after(hookof('android.app.LocaleManager', 'getSystemLocales'), manager)\n" +
                        "return prop('persist.sys.locale', 'de-DE'), prop('ro.product.locale.language', 'de'),\n" +
                        "  prop('ro.product.locale.region', 'DE'), prop('ro.csc.country_code', 'Germany'),\n" +
                        "  prop('persist.sys.country', ''), prop('ro.product.model', 'Pixel'), setting.result,\n" +
                        "  after(hookof('android.provider.Settings$System', 'getStringForUser'), other),\n" +
                        "  manager.result:toLanguageTags()");
        assertEquals("en-US", result.arg(1).tojstring());
        assertEquals("en", result.arg(2).tojstring());
        assertEquals("US", result.arg(3).tojstring());
        assertEquals("", result.arg(4).tojstring());
        assertEquals("", result.arg(5).tojstring());
        assertEquals("Pixel", result.arg(6).tojstring());
        assertEquals("en-US,fr-FR", result.arg(7).tojstring());
        assertFalse(result.arg(8).toboolean());
        assertEquals("en-US,fr-FR", result.arg(9).tojstring());

        // Only the properties run the script of the property hooks
        Varargs keys = Scripts.run(globals, "local keys = {}\n" +
                "for _, key in ipairs(filter(hookof('android.os.SystemProperties', 'get'))) do keys[key] = true end\n" +
                "return keys['persist.sys.locale'], keys['ro.miui.region'], keys['ro.product.model'],\n" +
                "  filter(hookof('java.util.Locale', 'setDefault'))");
        assertTrue(keys.arg(1).toboolean());
        assertTrue(keys.arg(2).toboolean());
        assertTrue(keys.arg(3).isnil());
        assertTrue(keys.arg(4).isnil());
    }

    // The default language of text to speech is the first fake one, in the form of the engines, unless the user chose
    // one for text to speech; codes the language does not have give eng USA, like the engines
    @Test
    public void textToSpeechSpeaksTheFakeLanguage() throws Exception {
        Globals globals = load();
        Varargs result = Scripts.run(globals,
                "local function speech(language, country)\n" +
                        "  local param = paramof({ length = 3, [1] = language, [2] = country, [3] = '' })\n" +
                        "  local restricted = after(hookof('android.speech.tts.ITextToSpeechService$Stub$Proxy', " +
                        "'getClientDefaultLanguage', 0), param)\n" +
                        "  return restricted, param.result[1] .. '-' .. param.result[2] .. '-' .. param.result[3]\n" +
                        "end\n" +
                        "local a, b = speech('deu', 'DEU')\n" +
                        "local c = speech('eng', 'USA')\n" +
                        "tts = 'a:,com.google.android.tts:de-DE'\n" +
                        "local d, e = speech('deu', 'DEU')\n" +
                        // Use system language in the settings: an engine without a language
                        "tts = 'com.google.android.tts:'\n" +
                        "local h, i = speech('deu', 'DEU')\n" +
                        "tts = nil\n" +
                        "settings = { ['value.locale'] = 'zh-Hant-TW' }\n" +
                        "local _, f = speech('deu', 'DEU')\n" +
                        "settings = { ['value.locale'] = 'xx' }\n" +
                        "local _, g = speech('deu', 'DEU')\n" +
                        "return a, b, c, d, e, f, g, h, i");
        assertTrue(result.arg(1).toboolean());
        assertEquals("eng-USA-", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertEquals("deu-DEU-", result.arg(5).tojstring());
        assertEquals("zho-TWN-", result.arg(6).tojstring());
        assertEquals("eng-USA-", result.arg(7).tojstring());
        assertTrue(result.arg(8).toboolean());
        assertEquals("eng-USA-", result.arg(9).tojstring());
    }

    // get(key, default) gives the default for a property which is not set or empty: that stays, and an empty fake
    // becomes the default, also null
    @Test
    public void propertiesWithDefault() throws Exception {
        Globals globals = load();
        Varargs result = Scripts.run(globals,
                "local props = {}\n" +
                        "isset = function(param, key) return props[key] ~= nil end\n" +
                        "local function prop(key, value, default)\n" +
                        "  local param = paramof(value, { [0] = key, [1] = default })\n" +
                        "  local restricted = after(hookof('android.os.SystemProperties', 'get', 2, " +
                        "'SystemProperties.get.default/locale'), param)\n" +
                        "  return restricted, param.result\n" +
                        "end\n" +
                        "local a, b = prop('persist.sys.locale', 'd', 'd')\n" +
                        "local c, d = prop('ro.csc.sales_code', 'XEF', 'd')\n" +
                        "local e, f = prop('ro.csc.sales_code', 'XEF', nil)\n" +
                        "local g, h = prop('persist.sys.locale', 'de-DE', 'd')\n" +
                        // Set to the guessed default: still faked, else the default would confirm the guess
                        "props['persist.sys.locale'] = 'd'\n" +
                        "local i, j = prop('persist.sys.locale', 'd', 'd')\n" +
                        "return a, b, c, d, e, f, g, h, i, j");
        assertFalse(result.arg(1).toboolean());
        assertEquals("d", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertEquals("d", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertTrue(result.arg(6).isnil());
        assertEquals("en-US", result.arg(8).tojstring());
        assertTrue(result.arg(9).toboolean());
        assertEquals("en-US", result.arg(10).tojstring());
        // The same rule as systemproperties_get
        assertEquals(Scripts.function("systemproperties_get", "unset"), Scripts.function("locale", "unset"));
        assertEquals(Scripts.function("systemproperties_get", "isset"), Scripts.function("locale", "isset"));
    }

    // SystemProperties.get(key) calls native_get(key), which apps also reach by reflection: faked once
    @Test
    public void nativeGetIsFakedOnce() throws Exception {
        Varargs result = Scripts.run(load(),
                "local first = paramof('de-DE', { [0] = 'persist.sys.locale' })\n" +
                        "local restricted = after(hookof('android.os.SystemProperties', 'native_get', 1, " +
                        "'SystemProperties.native_get/locale'), first)\n" +
                        "local second = paramof(first.result, { [0] = 'persist.sys.locale' })\n" +
                        "return restricted, first.result, after(hookof('android.os.SystemProperties', 'get', 1, " +
                        "'SystemProperties.get/locale'), second)");
        assertTrue(result.arg(1).toboolean());
        assertEquals("en-US", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
    }

    // From Android 11 get(key) calls native_get(key), which calls native_get(key, ''), as get(key, default) calls
    // native_get(key, default) on all versions: the innermost hook fakes, the others see the fake; a property which is
    // not set gives the default, which stays
    @Test
    public void nativeGetWithDefaultIsFakedFirst() throws Exception {
        Varargs result = Scripts.run(load(),
                "local props = { ['persist.sys.locale'] = 'de-DE' }\n" +
                        "isset = function(param, key) return props[key] ~= nil end\n" +
                        "local hook = hookof('android.os.SystemProperties', 'native_get', 2, " +
                        "'SystemProperties.native_get.default/locale')\n" +
                        "local first = paramof('de-DE', { [0] = 'persist.sys.locale', [1] = '' })\n" +
                        "local restricted = after(hook, first)\n" +
                        "local native = after(hookof('android.os.SystemProperties', 'native_get', 1, " +
                        "'SystemProperties.native_get/locale'), paramof(first.result, { [0] = 'persist.sys.locale' }))\n" +
                        "local get = after(hookof('android.os.SystemProperties', 'get', 1, 'SystemProperties.get/locale'), " +
                        "paramof(first.result, { [0] = 'persist.sys.locale' }))\n" +
                        "local defaulted = paramof('de-DE', { [0] = 'persist.sys.locale', [1] = 'd' })\n" +
                        "local restrictedDefault = after(hook, defaulted)\n" +
                        "props = {}\n" +
                        "local unset = paramof('', { [0] = 'persist.sys.locale', [1] = '' })\n" +
                        "local kept = paramof('d', { [0] = 'persist.sys.locale', [1] = 'd' })\n" +
                        "return restricted, first.result, native, get, restrictedDefault, defaulted.result, " +
                        "after(hook, unset), unset.result, after(hook, kept), kept.result");
        assertTrue(result.arg(1).toboolean());
        assertEquals("en-US", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertEquals("en-US", result.arg(6).tojstring());
        assertFalse(result.arg(7).toboolean());
        assertEquals("", result.arg(8).tojstring());
        assertFalse(result.arg(9).toboolean());
        assertEquals("d", result.arg(10).tojstring());
    }

    // Once when installed: the defaults by LocaleList.setDefault, and user.locale
    @Test
    public void defaultsArePushedOnce() throws Exception {
        Globals globals = load();
        Varargs result = Scripts.run(globals,
                "local set, property\n" +
                        "local real = luajava\n" +
                        "local classes = {\n" +
                        "  ['android.os.LocaleList'] = { getDefault = function(self) return listof({ 'de-DE', 'tr-TR' }) end,\n" +
                        "    setDefault = function(self, list) set = list:toLanguageTags() end },\n" +
                        "  ['java.lang.System'] = { getProperty = function(self, key) return property or 'de-DE' end,\n" +
                        "    setProperty = function(self, key, value) property = value end } }\n" +
                        "luajava = { bindClass = function(name) return classes[name] or real.bindClass(name) end }\n" +
                        "local pushed = after(hookof('android.os.LocaleList', '#sLastExplicitlySetLocaleList', 0), paramof())\n" +
                        "luajava = real\n" +
                        "return pushed, set, property, fakes(paramof())[1]");
        assertTrue(result.arg(1).toboolean());
        assertEquals("en-US,fr-FR", result.arg(2).tojstring());
        assertEquals("en-US", result.arg(3).tojstring());
        assertEquals("en-US", result.arg(4).tojstring());
    }

    @Test
    public void defaultLanguage() throws Exception {
        Globals globals = load();
        Varargs result = Scripts.run(globals, "settings = {}\nreturn table.concat(fakes(paramof()), ','), fakeproperty(paramof(), 'x')");
        assertEquals("en-US", result.arg(1).tojstring());
        assertNull(result.arg(2).isnil() ? null : "set");

        // Normalized, without repeats (LocaleList throws) and tags which are no language
        assertEquals("de-DE,fr", Scripts.run(globals, "settings = { ['value.locale'] = 'de-DE, de-de,!!!,fr' }\n" +
                "return table.concat(fakes(paramof()), ',')").arg1().tojstring());
        assertEquals("en-US", Scripts.run(globals, "settings = { ['value.locale'] = '!!!' }\n" +
                "return table.concat(fakes(paramof()), ',')").arg1().tojstring());
    }

    // The language properties in the property areas the app reads as files get the values of get (see
    // XParam.setPropertyValues, which leaves out properties which are not there or empty); only the property areas run
    // the script for the hooks of opening files
    @Test
    public void propertyAreas() throws Exception {
        Globals globals = load();
        Varargs result = Scripts.run(globals,
                "local real = { ['persist.sys.locale'] = 'de-DE', ['ro.product.locale.region'] = 'DE',\n" +
                        "  ['ro.csc.sales_code'] = 'XEF' }\n" +
                        "local set = {}\n" +
                        "local param = paramof()\n" +
                        "param.setPropertyValues = function(self, names, values)\n" +
                        "  local olds = {}\n" +
                        "  for index, name in ipairs(names) do set[name] = values[index] olds[index] = real[name] end\n" +
                        "  return olds\n" +
                        "end\n" +
                        "local restricted, old, new = after(hookof('libcore.io.BlockGuardOs', 'open', 3, " +
                        "'BlockGuardOs.open/locale'), param)\n" +
                        "local olds, news = {}, {}\n" +
                        "for o in string.gmatch(old, '[^,]+') do olds[o] = true end\n" +
                        "for n in string.gmatch(new .. ',', '([^,]*),') do news[#news + 1] = n end\n" +
                        "table.sort(news)\n" +
                        "return restricted, olds['de-DE'] and olds['DE'] and olds['XEF'], table.concat(news, '|'),\n" +
                        "  set['persist.sys.locale'], set['persist.sys.language'], set['persist.sys.country'],\n" +
                        "  set['ro.csc.sales_code'], set['ro.miui.region'],\n" +
                        "  filter(hookof('libcore.io.BlockGuardOs', 'open'))[1], filter(hookof('android.os.LocaleList', 'setDefault'))");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertEquals("|US|en-US", result.arg(3).tojstring());
        assertEquals("en-US", result.arg(4).tojstring());
        assertEquals("en", result.arg(5).tojstring());
        assertEquals("US", result.arg(6).tojstring());
        assertEquals("", result.arg(7).tojstring());
        assertEquals("", result.arg(8).tojstring());
        assertEquals("/dev/__properties__/*", result.arg(9).tojstring());
        assertTrue(result.arg(10).isnil());

        assertFalse(Scripts.run(globals, "local param = paramof()\n" +
                "param.setPropertyValues = function(self, names, values) return {} end\n" +
                "return after(hookof('sun.nio.fs.UnixNativeDispatcher', 'open', 3, 'UnixNativeDispatcher.open/locale'), param)")
                .arg1().toboolean());
    }
}
