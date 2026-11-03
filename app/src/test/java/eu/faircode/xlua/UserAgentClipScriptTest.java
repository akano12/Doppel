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
            "function paramof(result)",
            "  local param = { result = result }",
            "  param.getResult = function(self) return self.result end",
            "  param.setResult = function(self, value) self.result = value end",
            "  return param",
            "end",
            "function descriptionof(timestamp)",
            "  return { timestamp = timestamp, getTimestamp = function(self) return self.timestamp end,",
            "    setTimestamp = function(self, value) self.timestamp = value end }",
            "end",
            "function clipof(timestamp)",
            "  local description = descriptionof(timestamp)",
            "  return { getItemCount = function(self) return 1 end, getDescription = function(self) return description end,",
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
}
