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
import org.luaj.vm2.Varargs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// The user agent of a new WebView (assets/webview_constructor.lua): the hooked constructor runs once per WebView,
// the one all others call, so nothing is kept per WebView in the shared values of XParam
public class WebViewConstructorScriptTest {
    private static final String STUBS = String.join("\n",
            "scoped = 0",
            "hooked = 0",
            "function hook(object, method, fn) hooked = hooked + 1 end",
            "function webviewof(ua)",
            "  local settings = { ua = ua,",
            "    getUserAgentString = function(self) return self.ua end,",
            "    setUserAgentString = function(self, value) self.ua = value end }",
            "  return { settings = settings, getSettings = function(self) return settings end }",
            "end",
            "function paramof(this)",
            "  return { getThis = function(self) return this end,",
            "    getValue = function(self, name, scope) scoped = scoped + 1 return nil end,",
            "    putValue = function(self, name, value, scope) scoped = scoped + 1 end }",
            "end");

    @Test
    public void newWebViewKeepsNoValue() throws Exception {
        Varargs result = Scripts.run(Scripts.load("webview_constructor", STUBS),
                "local webview = webviewof('Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP4A.250105.002; wv)')\n" +
                        "local restricted = after({ getMethodName = function(self) return nil end }, paramof(webview))\n" +
                        "return restricted, webview.settings.ua, hooked, scoped");
        assertTrue(result.arg1().toboolean());
        assertEquals("Mozilla/5.0 (Linux; Android 15; K; wv)", result.arg(2).tojstring());
        assertEquals(1, result.arg(3).toint());
        assertEquals("shared values used", 0, result.arg(4).toint());
    }
}
