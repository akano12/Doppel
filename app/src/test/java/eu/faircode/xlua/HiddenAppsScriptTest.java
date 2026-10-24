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

    Copyright 2026 akano12
 */

package eu.faircode.xlua;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The providers, services, screens and broadcasts of hidden apps, see provider_hidden and component_hidden
public class HiddenAppsScriptTest {
    // Apps: the app itself, a visible app and a hidden app, each with a provider; grants of uris to the app
    private static final String STUBS = String.join("\n",
            "visible = { ['com.example.app'] = true, ['com.android.settings'] = true, ['com.other.hidden'] = false }",
            "providers = { ['com.example.app.provider'] = 'com.example.app', ['settings'] = 'com.android.settings',",
            "  ['com.other.hidden.provider'] = 'com.other.hidden' }",
            "grants = {}",
            "lookups = 0",
            "values = {}", // Kept per process, like XParam.putValue with the context as scope
            "function uriof(scheme, authority, path)",
            "  return { getScheme = function(self) return scheme end, getAuthority = function(self) return authority end,",
            "    toString = function(self) return scheme .. '://' .. (authority or '') .. (path or '') end }",
            "end",
            "function hookof(method)",
            "  return { getMethodName = function(self) return method end }",
            "end",
            "function paramof(args)",
            "  local context = {",
            "    getPackageManager = function(self)",
            "      return { resolveContentProvider = function(self, authority, flags)",
            "        lookups = lookups + 1",
            "        local pkg = providers[authority]",
            "        return pkg and { packageName = pkg } or nil end }",
            "    end,",
            "    checkUriPermission = function(self, uri, pid, uid, mode)",
            "      return grants[uri:toString()] and 0 or -1 end }",
            "  return { args = args, result = nil, set = false, context = context,",
            "    getArgument = function(self, index) return self.args[index] end,",
            "    setResult = function(self, value) self.result = value self.set = true end,",
            "    getApplicationContext = function(self) return self.context end,",
            "    isPackageVisible = function(self, name) return visible[name] ~= false end,",
            "    unhooked = function(self, fn) return fn() end,",
            "    putValue = function(self, name, value, scope) values[name] = value end,",
            "    getValue = function(self, name, scope) return values[name] end }",
            "end",
            "function componentof(pkg, cls)",
            "  return { getPackageName = function(self) return pkg end,",
            "    toShortString = function(self) return '{' .. pkg .. '/' .. cls .. '}' end }",
            "end",
            "function intentof(component, pkg)",
            "  return { getComponent = function(self) return component end, getPackage = function(self) return pkg end,",
            "    toString = function(self) return 'Intent { pkg=' .. tostring(pkg) .. ' }' end }",
            "end");

    private static Varargs provider(Globals globals, String method, String target) {
        return Scripts.run(globals, "local param = paramof({ [0] = " + target + " })\n" +
                "local restricted, pkg = before(hookof('" + method + "'), param)\n" +
                "return restricted, param.set, param.result, pkg");
    }

    private static String className(Varargs result, int index) {
        return result.arg(index).isnil() ? "nil" : result.arg(index).checkuserdata().getClass().getName();
    }

    @Test
    public void providersOfHiddenAppsAreNotFound() throws Exception {
        Globals globals = Scripts.load("provider_hidden", STUBS);
        String hidden = "uriof('content', 'com.other.hidden.provider', '/profile')";

        // Each method answers like Android without a provider
        Varargs result = provider(globals, "query", hidden);
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).isnil());
        assertEquals("com.other.hidden", result.arg(4).tojstring());
        for (String method : new String[]{"getType", "getStreamTypes", "canonicalize", "acquireContentProviderClient"})
            assertTrue(method, provider(globals, method, hidden).arg(3).isnil());
        for (String method : new String[]{"insert", "bulkInsert", "delete", "update"})
            assertEquals(method, "java.lang.IllegalArgumentException", className(provider(globals, method, hidden), 3));
        assertEquals("java.lang.IllegalArgumentException", className(provider(globals, "call", hidden), 3));
        assertEquals("java.lang.IllegalArgumentException", className(provider(globals, "call", "'com.other.hidden.provider'"), 3));
        for (String method : new String[]{"openAssetFileDescriptor", "openTypedAssetFileDescriptor", "openTypedAssetFile"})
            assertEquals(method, "java.io.FileNotFoundException", className(provider(globals, method, hidden), 3));
        assertFalse(provider(globals, "refresh", hidden).arg(3).toboolean());
        // An authority alone, like of a content provider client, with the user in front
        assertTrue(provider(globals, "acquireContentProviderClient", "'0@com.other.hidden.provider'").arg(3).isnil());

        // The package of a provider is looked up once
        int lookups = Scripts.run(globals, "return lookups").arg1().toint();
        provider(globals, "query", hidden);
        assertEquals(lookups, Scripts.run(globals, "return lookups").arg1().toint());
    }

    @Test
    public void visibleGrantedAndUnknownProvidersStay() throws Exception {
        Globals globals = Scripts.load("provider_hidden", STUBS);
        for (String target : new String[]{
                "uriof('content', 'com.example.app.provider', '/own')", // The app itself
                "uriof('content', 'settings', '/secure')", // A system app
                "uriof('content', 'com.unknown.provider', '/x')", // No provider, Android answers itself
                "uriof('file', nil, '/sdcard/x')", // Not a provider
                "uriof('android.resource', 'com.other.hidden', '/raw/x')", // Served by the resolver itself
                "'settings'", "nil"}) {
            Varargs result = provider(globals, "query", target);
            assertFalse(target, result.arg(1).toboolean());
            assertFalse(target, result.arg(2).toboolean());
        }

        // A uri the sender granted to the app, like a shared photo, also by the authority with the user in front
        Scripts.run(globals, "grants['content://com.other.hidden.provider/photo/1'] = true\n" +
                "grants['content://0@com.other.hidden.provider/photo/1'] = true");
        assertFalse(provider(globals, "openAssetFileDescriptor",
                "uriof('content', 'com.other.hidden.provider', '/photo/1')").arg(1).toboolean());
        assertFalse(provider(globals, "query",
                "uriof('content', '0@com.other.hidden.provider', '/photo/1')").arg(1).toboolean());
        assertTrue(provider(globals, "query",
                "uriof('content', 'com.other.hidden.provider', '/photo/2')").arg(1).toboolean());
    }

    private static Varargs component(Globals globals, String method, String args) {
        return Scripts.run(globals, "local param = paramof(" + args + ")\n" +
                "local restricted, pkg = before(hookof('" + method + "'), param)\n" +
                "return restricted, param.set, param.result, pkg");
    }

    @Test
    public void componentsOfHiddenAppsAreNotFound() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        String hidden = "intentof(componentof('com.other.hidden', '.Service'), nil)";
        String hiddenPackage = "intentof(nil, 'com.other.hidden')";

        for (String intent : new String[]{hidden, hiddenPackage}) {
            Varargs result = component(globals, "bindService", "{ [0] = " + intent + " }");
            assertTrue(intent, result.arg(1).toboolean());
            assertFalse(intent, result.arg(3).toboolean());
            assertEquals("com.other.hidden", result.arg(4).tojstring());
            for (String method : new String[]{"startService", "startForegroundService", "sendBroadcast"}) {
                result = component(globals, method, "{ [0] = " + intent + " }");
                assertTrue(method, result.arg(1).toboolean());
                assertTrue(method, result.arg(2).toboolean());
                assertTrue(method, result.arg(3).isnil());
            }
        }

        // Screens through Instrumentation, with the intent as fifth argument, also one of several
        Varargs result = component(globals, "execStartActivity", "{ [4] = " + hidden + " }");
        assertTrue(result.arg(1).toboolean());
        assertEquals("android.content.ActivityNotFoundException", className(result, 3));
        result = component(globals, "execStartActivitiesAsUser",
                "{ [4] = { length = 2, [1] = intentof(componentof('com.example.app', '.Main'), nil), [2] = " + hiddenPackage + " } }");
        assertTrue(result.arg(1).toboolean());
        assertEquals("android.content.ActivityNotFoundException", className(result, 3));
    }

    @Test
    public void visibleAndImplicitComponentsStay() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        for (String intent : new String[]{
                "intentof(componentof('com.example.app', '.Service'), nil)", // The app itself
                "intentof(nil, 'com.android.settings')", // A system app
                "intentof(nil, nil)", // Resolved by Android
                "nil"}) {
            for (String method : new String[]{"bindService", "startService", "sendBroadcast"}) {
                Varargs result = component(globals, method, "{ [0] = " + intent + " }");
                assertFalse(method + " " + intent, result.arg(1).toboolean());
                assertFalse(method + " " + intent, result.arg(2).toboolean());
            }
            assertFalse(intent, component(globals, "execStartActivity", "{ [4] = " + intent + " }").arg(1).toboolean());
        }
        assertFalse(component(globals, "execStartActivitiesAsUser",
                "{ [4] = { length = 1, [1] = intentof(nil, 'com.android.settings') } }").arg(1).toboolean());
    }
}
