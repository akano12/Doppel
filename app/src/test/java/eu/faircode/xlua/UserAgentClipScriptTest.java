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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The client hints and the default user agent of a WebView (webview_constructor) and the answers of the clipboard
// (clipboard_getprimaryclip), with Lua stand-ins
public class UserAgentClipScriptTest {
    private static final String UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP4A.250105.002; wv)";

    // A WebView of an app with androidx.webkit: the client hints get the fake model, and a script is added at the start
    // of each page for every origin
    private static final String WEBVIEW = String.join("\n",
            "function hook(object, method, fn) end",
            "metadata, script = nil, nil",
            "real = { getModel = function(self) return model end }",
            "model = 'Pixel 9'",
            "classes = {",
            "  ['androidx.webkit.WebViewFeature'] = { isFeatureSupported = function(self, name)",
            "    return name == 'USER_AGENT_METADATA' or name == 'DOCUMENT_START_SCRIPT' end },",
            "  ['androidx.webkit.WebSettingsCompat'] = {",
            "    getUserAgentMetadata = function(self, settings) return real end,",
            "    setUserAgentMetadata = function(self, settings, value) metadata = value end },",
            "  ['androidx.webkit.UserAgentMetadata$Builder'] = 'builder',",
            "  ['androidx.webkit.WebViewCompat'] = { addDocumentStartJavaScript = function(self, webview, js, rules)",
            "    assert(webview.getSettings and rules == 'rules of *') script = js end } }",
            "luajava = { new = function(class, from)",
            "  assert(class == 'builder' and from == real)",
            "  local builder = {}",
            "  builder.setModel = function(self, model) self.model = model return self end",
            "  builder.build = function(self) return 'model=' .. self.model end",
            "  return builder",
            "end,",
            "bindClass = function(name)",
            "  assert(name == 'java.util.Collections', name)",
            "  return { singleton = function(self, rule) return 'rules of ' .. rule end }",
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
    public void clientHintsHaveFakeModel() throws Exception {
        Globals globals = Scripts.load("webview_constructor", WEBVIEW);
        Varargs result = Scripts.run(globals, "return construct(classes)");
        assertTrue(result.arg1().toboolean());
        assertEquals("Mozilla/5.0 (Linux; Android 15; unknown Build/unknown; wv)", result.arg(2).tojstring());
        assertEquals("model=unknown", result.arg(3).tojstring());

        // An empty model, of a build that is not a release, stays empty; the default one (null) becomes the fake one
        result = Scripts.run(globals, "metadata, model = nil, '' return construct(classes)");
        assertTrue(result.arg(3).isnil());
        result = Scripts.run(globals, "metadata, model = nil, nil return construct(classes)");
        assertEquals("model=unknown", result.arg(3).tojstring());
        Scripts.run(globals, "model = 'Pixel 9'");

        // Without androidx.webkit, or a WebView without client hints, only the user agent is changed
        result = Scripts.run(globals, "metadata = nil return construct({})");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(3).isnil());
        result = Scripts.run(globals, "metadata = nil\n" +
                "classes['androidx.webkit.WebViewFeature'] = { isFeatureSupported = function(self, name) return false end }\n" +
                "return construct(classes)");
        assertTrue(result.arg1().toboolean());
        assertTrue(result.arg(3).isnil());
    }

    // Pages of an app with androidx.webkit get a script before their own which leaves out the WebGL extensions naming
    // the graphics chip or its vendor, gives no WebGPU adapter and 8 processors, and gives the browser's text of these
    // functions to toString; without the feature, or without androidx.webkit, none is added
    @Test
    public void pagesGetScript() throws Exception {
        Globals globals = Scripts.load("webview_constructor", WEBVIEW);
        String js = Scripts.run(globals, "construct(classes) return script").arg1().tojstring();
        assertTrue(js, js.startsWith("(function () {") && js.endsWith("})();"));
        for (String name : new String[]{"WEBGL_DEBUG_RENDERER_INFO", "WEBGL_DEBUG_SHADERS", "WEBGL_COMPRESSED_TEXTURE_PVRTC",
                "WEBGL_RENDER_SHARED_EXPONENT", "NV_SHADER_NOPERSPECTIVE_INTERPOLATION", "WEBGL_POLYGON_MODE"})
            assertTrue(name, js.contains("'" + name + "'"));
        assertTrue(js, js.contains("apply(processors, this, []);\n          return 8;"));
        assertTrue(js, js.contains("return apply(resolve, promise, [null]);"));
        for (String replaced : new String[]{"Function.prototype.toString", "proto.getExtension", "proto.getSupportedExtensions",
                "concurrency.get", "GPU.prototype.requestAdapter"})
            assertTrue(replaced, js.contains(replaced + " = mask("));
        assertTrue(js, js.contains("try {") && js.contains("} catch (e) {"));
        // Pages can read the functions by toString in a frame without the script
        assertFalse(js, js.contains("//"));

        assertTrue(Scripts.run(globals, "script = nil\n" +
                "local supported = classes['androidx.webkit.WebViewFeature']\n" +
                "classes['androidx.webkit.WebViewFeature'] = {\n" +
                "  isFeatureSupported = function(self, name) return name ~= 'DOCUMENT_START_SCRIPT' end }\n" +
                "construct(classes)\n" +
                "classes['androidx.webkit.WebViewFeature'] = supported\n" +
                "return script").arg1().isnil());
        Varargs result = Scripts.run(globals, "script = nil local restricted, ua = construct({}) return script, restricted, ua");
        assertTrue(result.arg1().isnil());
        assertTrue(result.arg(2).toboolean());
        assertEquals("Mozilla/5.0 (Linux; Android 15; unknown Build/unknown; wv)", result.arg(3).tojstring());
    }

    // The page script does what it should when it runs: node runs it in two realms with stand-ins of the browser's
    // functions (webview_page_test.js), as a page that watches the lookups on objects the page can change which would
    // get a real value, like a getter on Array.prototype.constructor for the list or on then for the WebGPU adapter.
    // It fails without node, so a run without it does not pass silently.
    @Test
    public void pageScriptRunsInNode() throws Exception {
        Process version;
        try {
            version = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
        } catch (IOException ex) {
            version = null;
        }
        assertTrue("node is needed to run the page script", version != null && version.waitFor() == 0);

        Globals globals = Scripts.load("webview_constructor", WEBVIEW);
        String js = Scripts.run(globals, "construct(classes) return script").arg1().tojstring();
        File page = File.createTempFile("page", ".js");
        try {
            Files.write(page.toPath(), js.getBytes(StandardCharsets.UTF_8));
            File harness = new File(getClass().getResource("/webview_page_test.js").toURI());
            Process node = new ProcessBuilder("node", harness.getPath(), page.getPath()).redirectErrorStream(true).start();
            String out = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(out, 0, node.waitFor());
        } finally {
            page.delete();
        }
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
            assertEquals(ua, "Mozilla/5.0 (Linux; Android 15; unknown Build/unknown; wv)", Scripts.run(globals,
                    "local param = paramof(" + ua + ") setUserAgentString('before', param) return param.ua").arg1().tojstring());
    }

    // A clip which is not the app's own reads as no clip, like Android answers when there is none or the app has no
    // access to it; read(method, timestamp, text, label) runs a call like the hook does: before, then the real method
    // unless before answered, then after; the description the manager gives without the hooks is of the same clip.
    // Returns whether restricted, whether the real answer stayed and whether the real method was called.
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
            "    getLabel = function(self) return label or 'x' end,",
            "    getMimeTypeCount = function(self) return 1 end, getMimeType = function(self, i) return 'text/plain' end }",
            "end",
            "function clipof(timestamp, text, label)",
            "  local description = descriptionof(timestamp, label)",
            "  local item = { getText = function(self) return text or 'hello' end, getUri = function(self) return nil end }",
            "  return { getItemCount = function(self) return 1 end, getDescription = function(self) return description end,",
            "    getItemAt = function(self, i) assert(i == 0) return item end }",
            "end",
            "function read(method, timestamp, text, label, result)",
            "  local real = result",
            "  if real == nil and timestamp then",
            "    real = method == 'getPrimaryClip' and clipof(timestamp, text, label)",
            "      or descriptionof(timestamp, label)",
            "  end",
            "  if method == 'hasText' or method == 'hasPrimaryClip' then real = result end",
            "  local manager = { getPrimaryClipDescription = function(self)",
            "    assert(unhooked, 'hooked call') return timestamp and descriptionof(timestamp, label) end }",
            "  local param = paramof(nil, nil, manager)",
            "  local called = false",
            "  local restricted = before(hookof(method), param)",
            "  if not restricted then",
            "    called = true param.result = real restricted = after(hookof(method), param)",
            "  end",
            "  return restricted, param.result == real, called",
            "end");

    // Another app's clip is not asked for (no grant of its content uris, no notice of a paste): no clip, no
    // description, no clip with text; no clip stays no clip
    @Test
    public void foreignClipIsNoClip() throws Exception {
        Globals globals = Scripts.load("clipboard_getprimaryclip", CLIP);
        check(globals, "return read('getPrimaryClip', 1700000000000)", true, false, false);
        assertTrue(Scripts.run(globals, "local param = paramof(nil, nil, { getPrimaryClipDescription = function(self)" +
                " return descriptionof(5) end }) before(hookof('getPrimaryClip'), param) return param.result == nil")
                .arg1().toboolean());
        check(globals, "return read('getPrimaryClipDescription', 1700000000000)", true, false, true);
        assertTrue(Scripts.run(globals, "local param = paramof(descriptionof(5))" +
                " after(hookof('getPrimaryClipDescription'), param) return param.result == nil").arg1().toboolean());
        for (String method : new String[]{"hasText", "hasPrimaryClip"}) {
            check(globals, "return read('" + method + "', 1700000000000, nil, nil, true)", true, false, true);
            check(globals, "return read('" + method + "', 1700000000000, nil, nil, false)", false, true, true);
            check(globals, "return read('" + method + "', nil, nil, nil, false)", false, true, true);
            // A clip cleared between the answer and the description
            check(globals, "return read('" + method + "', nil, nil, nil, true)", true, false, true);
        }
        // No description: the call runs, its answer stays
        for (String method : new String[]{"getPrimaryClip", "getPrimaryClipDescription"})
            check(globals, "return read('" + method + "', nil)", false, true, true);
    }

    // Android stamps a clip with the time during the call of setPrimaryClip, so the clip of a time within the app's
    // own last call, which looks like the clip the app set, comes back as the app set it, like without Doppel; another
    // clip of that time is no clip, and getPrimaryClip of one with the own description but other items is compared
    // after the call
    @Test
    public void ownClipIsTheRealOne() throws Exception {
        Globals globals = Scripts.load("clipboard_getprimaryclip", CLIP + "\n" + String.join("\n",
                "clock = 0",
                "now = function() return clock end",
                "function set(from, to, clip, exception)",
                "  clock = from before(hookof('setPrimaryClip'), paramof(nil, nil, nil, clip))",
                "  clock = to return after(hookof('setPrimaryClip'), paramof(nil, exception, nil, clip))",
                "end"));
        check(globals, "return set(100, 110, clipof(0))", false);
        for (String method : new String[]{"getPrimaryClip", "getPrimaryClipDescription"}) {
            boolean description = method.equals("getPrimaryClipDescription");
            for (int own : new int[]{100, 105, 110})
                check(globals, "return read('" + method + "', " + own + ")", false, true, true);
            for (int other : new int[]{99, 111})
                check(globals, "return read('" + method + "', " + other + ")", true, false, description);
            // A clip which another app sets after Android stamped the app's own one, within the call
            check(globals, "return read('" + method + "', 105, nil, 'y')", true, false, description);
        }
        check(globals, "return read('getPrimaryClip', 105, 'other')", true, false, true);

        // There is a clip, with text or not, only for the app's own one
        for (String method : new String[]{"hasText", "hasPrimaryClip"}) {
            check(globals, "return read('" + method + "', 105, nil, nil, true)", false, true, true);
            check(globals, "return read('" + method + "', 105, nil, nil, false)", false, true, true);
            check(globals, "return read('" + method + "', 105, nil, 'y', true)", true, false, true);
            check(globals, "return read('" + method + "', 200, nil, nil, true)", true, false, true);
        }

        // A call which Android refuses, like for no clip, sets no clip, so the one before stays the own one
        check(globals, "return set(300, 310, clipof(0), 'refused')", false);
        check(globals, "return set(400, 410, nil, 'refused')", false);
        check(globals, "return read('getPrimaryClip', 305)", true, false, false);
        check(globals, "return read('getPrimaryClip', 405)", true, false, false);
        check(globals, "return read('getPrimaryClip', 105)", false, true, true);
    }

    private static void check(Globals globals, String lua, boolean... expected) {
        Varargs result = Scripts.run(globals, lua);
        for (int i = 0; i < expected.length; i++)
            assertEquals(lua + " #" + (i + 1), expected[i], result.arg(i + 1).toboolean());
    }
}
