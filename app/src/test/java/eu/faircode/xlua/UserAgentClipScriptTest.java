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

// The client hints and the default user agent of a WebView (webview_constructor) and the time of the fake clip
// (clipboard_getprimaryclip), with Lua stand-ins
public class UserAgentClipScriptTest {
    private static final String UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP4A.250105.002; wv)";

    // A WebView of an app with androidx.webkit: the client hints get an empty model
    private static final String WEBVIEW = String.join("\n",
            "function hook(object, method, fn) end",
            "metadata = nil",
            "classes = {",
            "  ['androidx.webkit.WebViewFeature'] = { isFeatureSupported = function(self, name) return name == 'USER_AGENT_METADATA' end },",
            "  ['androidx.webkit.WebSettingsCompat'] = {",
            "    getUserAgentMetadata = function(self, settings) return 'real' end,",
            "    setUserAgentMetadata = function(self, settings, value) metadata = value end },",
            "  ['androidx.webkit.UserAgentMetadata$Builder'] = 'builder' }",
            "luajava = { new = function(class, from)",
            "  assert(class == 'builder' and from == 'real')",
            "  local builder = {}",
            "  builder.setModel = function(self, model) self.model = model return self end",
            "  builder.build = function(self) return 'model=' .. self.model end",
            "  return builder",
            "end }",
            "function paramof(this, classes)",
            "  return { getThis = function(self) return this end,",
            "    findClass = function(self, name) local class = classes[name] assert(class, name) return class end }",
            "end",
            "function webviewof(ua)",
            "  local settings = { ua = ua,",
            "    getUserAgentString = function(self) return self.ua end,",
            "    setUserAgentString = function(self, value) self.ua = value end }",
            "  return { settings = settings, getSettings = function(self) return settings end }",
            "end",
            "function construct(classes)",
            "  local webview = webviewof('" + UA + "')",
            "  local restricted = after({ getMethodName = function(self) return nil end }, paramof(webview, classes))",
            "  return restricted, webview.settings.ua, metadata",
            "end");

    @Test
    public void clientHintsHaveNoModel() throws Exception {
        Globals globals = Scripts.load("webview_constructor", WEBVIEW);
        Varargs result = Scripts.run(globals, "return construct(classes)");
        assertTrue(result.arg1().toboolean());
        assertEquals("Mozilla/5.0 (Linux; Android 15; K; wv)", result.arg(2).tojstring());
        assertEquals("model=", result.arg(3).tojstring());

        // Without androidx.webkit, or a WebView without client hints, only the user agent is reduced
        result = Scripts.run(globals, "metadata = nil return construct({})");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(3).isnil());
        result = Scripts.run(globals, "metadata = nil\n" +
                "classes['androidx.webkit.WebViewFeature'] = { isFeatureSupported = function(self, name) return false end }\n" +
                "return construct(classes)");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(3).isnil());
    }

    // The default user agent which setUserAgentString(null) brings back is read without the hook of the app's calls
    @Test
    public void defaultUserAgentIsReadUnhooked() throws Exception {
        Globals globals = Scripts.load("webview_constructor", String.join("\n",
                "unhooked = false",
                "luajava = { bindClass = function(name)",
                "  assert(name == 'android.webkit.WebSettings', name)",
                "  return { getDefaultUserAgent = function(self, context) assert(unhooked, 'hooked call') return '" + UA + "' end }",
                "end }",
                "function paramof(ua)",
                "  local param = { ua = ua }",
                "  param.getArgument = function(self, index) return self.ua end",
                "  param.setArgument = function(self, index, value) self.ua = value end",
                "  param.getApplicationContext = function(self) return 'context' end",
                "  param.unhooked = function(self, fn) unhooked = true local value = fn() unhooked = false return value end",
                "  return param",
                "end"));
        for (String ua : new String[]{"nil", "''"})
            assertEquals(ua, "Mozilla/5.0 (Linux; Android 15; K; wv)", Scripts.run(globals,
                    "local param = paramof(" + ua + ") setUserAgentString('before', param) return param.ua").arg1().tojstring());
    }

    // The fake clip and its description get the time of the real clip, which made clips do not have
    private static final String CLIP = String.join("\n",
            "function hookof(method) return { getMethodName = function(self) return method end } end",
            "values, unhooked = {}, false",
            "function paramof(result, exception, this, argument)",
            "  local param = { result = result }",
            "  param.getArgument = function(self, index) assert(index == 0) return argument end",
            "  param.getResult = function(self) return self.result end",
            "  param.setResult = function(self, value) self.result = value end",
            "  param.getException = function(self) return exception end",
            "  param.getThis = function(self) return this end",
            "  param.getApplicationContext = function(self) return 'context' end",
            "  param.putValue = function(self, name, value, scope) assert(scope == 'context') values[name] = value end",
            "  param.getValue = function(self, name, scope) assert(scope == 'context') return values[name] end",
            "  param.unhooked = function(self, fn) unhooked = true local value = fn() unhooked = false return value end",
            "  return param",
            "end",
            "function descriptionof(timestamp, label)",
            "  return { timestamp = timestamp, getTimestamp = function(self) return self.timestamp end,",
            "    setTimestamp = function(self, value) self.timestamp = value end, getLabel = function(self) return label or 'x' end,",
            "    getMimeTypeCount = function(self) return 1 end, getMimeType = function(self, i) return 'text/plain' end }",
            "end",
            "function clipof(timestamp, text, label)",
            "  local description = descriptionof(timestamp, label)",
            "  local item = { getText = function(self) return text or 'hello' end, getUri = function(self) return nil end }",
            "  return { getItemCount = function(self) return 1 end, getDescription = function(self) return description end,",
            "    getItemAt = function(self, i) assert(i == 0) return item end,",
            "    newPlainText = function(self, label, text) return clipof(0) end }",
            "end",
            "luajava = { bindClass = function(name) return { newPlainText = function(self, label, text) return clipof(0) end } end }");

    @Test
    public void fakeClipHasTheTimeOfTheRealOne() throws Exception {
        Globals globals = Scripts.load("clipboard_getprimaryclip", CLIP);
        Varargs result = Scripts.run(globals, "local param = paramof(clipof(1700000000000))\n" +
                "local restricted = after(hookof('getPrimaryClip'), param)\n" +
                "return restricted, param.result:getDescription():getTimestamp()");
        assertTrue(result.arg1().toboolean());
        assertEquals(1700000000000L, result.arg(2).tolong());

        result = Scripts.run(globals, "local param = paramof(descriptionof(1700000000000))\n" +
                "local restricted = after(hookof('getPrimaryClipDescription'), param)\n" +
                "return restricted, param.result:getTimestamp()");
        assertTrue(result.arg1().toboolean());
        assertEquals(1700000000000L, result.arg(2).tolong());

        // A description without the hidden setter keeps no time, the clip is still faked
        result = Scripts.run(globals, "local param = paramof({ getTimestamp = function(self) return 5 end })\n" +
                "return after(hookof('getPrimaryClipDescription'), param)");
        assertTrue(result.arg1().toboolean());
        assertFalse(Scripts.run(globals, "return after(hookof('getPrimaryClipDescription'), paramof(nil))").arg1().toboolean());
    }

    // Android stamps a clip with the time during the call of setPrimaryClip, so the clip of a time within the app's
    // own last call, which looks like the clip the app set, comes back as the app set it, like without Doppel; another
    // clip of that time is faked; hasText of another clip has the text the fake clip has, while there is a clip
    @Test
    public void ownClipIsTheRealOne() throws Exception {
        Globals globals = Scripts.load("clipboard_getprimaryclip", CLIP + "\n" + String.join("\n",
                "clock = 0",
                "now = function() return clock end",
                "function set(from, to, clip, exception)",
                "  clock = from before(hookof('setPrimaryClip'), paramof(nil, nil, nil, clip))",
                "  clock = to return after(hookof('setPrimaryClip'), paramof(nil, exception, nil, clip))",
                "end",
                // Whether restricted and whether the real clip or description stayed
                "function read(method, timestamp, text, label)",
                "  local real = method == 'getPrimaryClipDescription' and descriptionof(timestamp, label)",
                "    or clipof(timestamp, text, label)",
                "  local param = paramof(real)",
                "  return after(hookof(method), param), param.result == real",
                "end",
                "function hastext(result, timestamp, label)",
                "  local manager = { getPrimaryClipDescription = function(self)",
                "    assert(unhooked, 'hooked call') return timestamp and descriptionof(timestamp, label) end }",
                "  local param = paramof(result, nil, manager)",
                "  return after(hookof('hasText'), param), param.result",
                "end"));
        check(globals, "return set(100, 110, clipof(0))", false);
        for (String method : new String[]{"getPrimaryClip", "getPrimaryClipDescription"}) {
            for (int own : new int[]{100, 105, 110})
                check(globals, "return read('" + method + "', " + own + ")", false, true);
            for (int other : new int[]{99, 111})
                check(globals, "return read('" + method + "', " + other + ")", true, false);
            // A clip which another app sets after Android stamped the app's own one, within the call
            check(globals, "return read('" + method + "', 105, nil, 'y')", true, false);
        }
        check(globals, "return read('getPrimaryClip', 105, 'other')", true, false);

        // The app's own clip of an image keeps no text, another one has the fake text; no clip has none
        check(globals, "return hastext(false, 105)", false, false);
        check(globals, "return hastext(false, 105, 'y')", true, true);
        check(globals, "return hastext(false, 200)", true, true);
        check(globals, "return hastext(true, 200)", false, true);
        check(globals, "return hastext(false, nil)", false, false);

        // A call which Android refuses, like for no clip, sets no clip, so the one before stays the own one
        check(globals, "return set(300, 310, clipof(0), 'refused')", false);
        check(globals, "return set(400, 410, nil, 'refused')", false);
        check(globals, "return read('getPrimaryClip', 305)", true, false);
        check(globals, "return read('getPrimaryClip', 405)", true, false);
        check(globals, "return read('getPrimaryClip', 105)", false, true);
    }

    private static void check(Globals globals, String lua, boolean... expected) {
        Varargs result = Scripts.run(globals, lua);
        for (int i = 0; i < expected.length; i++)
            assertEquals(lua + " #" + (i + 1), expected[i], result.arg(i + 1).toboolean());
    }
}
