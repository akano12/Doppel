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
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;

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

    // get(key, default) gives the default for a property which is not set or empty: that stays, and an empty fake
    // becomes the default, also null
    @Test
    public void propertiesWithDefault() throws Exception {
        Globals globals = load();
        Varargs result = Scripts.run(globals,
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
                        "return a, b, c, d, e, f, g, h");
        assertFalse(result.arg(1).toboolean());
        assertEquals("d", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertEquals("d", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertTrue(result.arg(6).isnil());
        assertEquals("en-US", result.arg(8).tojstring());
        // The same rule as systemproperties_get
        assertEquals(Scripts.function("systemproperties_get", "unset"), Scripts.function("locale", "unset"));
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
}
