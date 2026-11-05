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
import org.luaj.vm2.LuaValue;
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
            "persisted = {}", // The uris of ContentResolver.getPersistedUriPermissions
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
            "    getContentResolver = function(self)",
            "      return { getPersistedUriPermissions = function(self)",
            "        return { size = function(self) return #persisted end, get = function(self, index)",
            "          return { getUri = function(self) return persisted[index + 1] end } end } end }",
            "    end,",
            "    checkUriPermission = function(self, uri, pid, uid, mode)",
            "      return grants[uri:toString()] and 0 or -1 end }",
            "  return { args = args, result = nil, set = false, context = context,",
            "    getArgument = function(self, index) return self.args[index] end,",
            "    setArgument = function(self, index, value) self.args[index] = value end,",
            "    setResult = function(self, value) self.result = value self.set = true end,",
            "    isInstance = function(self, object, name)",
            "      return name == 'android.net.Uri' and type(object) == 'table' and object.getAuthority ~= nil end,",
            "    getApplicationContext = function(self) return self.context end,",
            "    isPackageVisible = function(self, name) return visible[name] ~= false end,",
            "    unhooked = function(self, fn) return fn() end,",
            "    putValue = function(self, name, value, scope) values[name] = value end,",
            "    getValue = function(self, name, scope) return values[name] end }",
            "end",
            "function componentof(pkg, cls)",
            "  return { getPackageName = function(self) return pkg end, getClassName = function(self) return cls end,",
            "    toShortString = function(self) return '{' .. pkg .. '/' .. cls .. '}' end }",
            "end",
            "local newInstance = luajava.newInstance",
            "luajava.newInstance = function(name, ...)",
            "  if name == 'android.content.ComponentName' then return componentof(...) end",
            "  return newInstance(name, ...)",
            "end",
            "function intentof(component, pkg)",
            "  return { component = component, pkg = pkg,",
            "    getComponent = function(self) return self.component end, getPackage = function(self) return self.pkg end,",
            "    getSelector = function(self) return self.selector end,",
            "    setSelector = function(self, value) self.selector = value end,",
            "    setComponent = function(self, value) self.component = value end,",
            "    setPackage = function(self, value)", // Throws like Android
            "      assert(value == nil or self.selector == nil, 'Cannot set package name when selector is already set')",
            "      self.pkg = value end,",
            "    clone = function(self)",
            "      local copy = intentof(self.component, self.pkg)",
            "      copy.selector = self.selector",
            "      return copy end,",
            "    toString = function(self) return 'Intent { pkg=' .. tostring(self.pkg) .. ' }' end }",
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
        // Also the message, which tells a missing provider apart: update says URI, the others URL
        for (String method : new String[]{"insert", "bulkInsert", "delete", "update"}) {
            Varargs answer = provider(globals, method, hidden);
            assertEquals(method, "java.lang.IllegalArgumentException", className(answer, 3));
            assertTrue(method, ((Throwable) answer.arg(3).touserdata()).getMessage()
                    .startsWith(method.equals("update") ? "Unknown URI " : "Unknown URL "));
        }
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

    private static Varargs document(Globals globals, String authority, String extras) {
        return Scripts.run(globals, "local param = paramof({ [0] = " + authority + ", [3] = " + extras + " })\n" +
                "return before(hookof('call'), param)");
    }

    // DocumentsContract goes by the authority of a document the user picked: from Android 10 with call and the uri
    // in the extras, on Android 8.1 and 9 with a client of the authority
    @Test
    public void documentsOfHiddenAppsTheUserPicked() throws Exception {
        Globals globals = Scripts.load("provider_hidden", STUBS);
        Scripts.run(globals, "grants['content://com.other.hidden.provider/document/1'] = true\n" +
                "grants['content://com.example.app.provider/own'] = true\n" +
                "function extrasof(uri)\n" +
                "  return { getParcelable = function(self, key) return key == 'uri' and uri or nil end }\n" +
                "end");
        String authority = "'com.other.hidden.provider'";
        String picked = "extrasof(uriof('content', 'com.other.hidden.provider', '/document/1'))";
        assertFalse(document(globals, authority, picked).arg(1).toboolean());
        assertFalse(document(globals, "'0@com.other.hidden.provider'", picked).arg(1).toboolean());

        // Not granted, of another provider, no uri: the provider of the hidden app is not found
        for (String extras : new String[]{"extrasof(uriof('content', 'com.other.hidden.provider', '/document/2'))",
                "extrasof(uriof('content', 'com.example.app.provider', '/own'))", "extrasof('text')", "extrasof(nil)", "nil"})
            assertTrue(extras, document(globals, authority, extras).arg(1).toboolean());

        // Only the client DocumentsContract asks for, with a grant which was kept
        assertTrue(provider(globals, "acquireUnstableContentProviderClient", authority).arg(1).toboolean());
        Scripts.run(globals, "persisted[1] = uriof('content', 'com.example.app.provider', '/own')");
        assertTrue(provider(globals, "acquireUnstableContentProviderClient", authority).arg(1).toboolean());
        Scripts.run(globals, "persisted[2] = uriof('content', 'com.other.hidden.provider', '/tree/1')");
        assertFalse(provider(globals, "acquireUnstableContentProviderClient", authority).arg(1).toboolean());
        assertTrue(provider(globals, "acquireContentProviderClient", authority).arg(1).toboolean());
    }

    private static Varargs component(Globals globals, String method, String args) {
        return Scripts.run(globals, "local param = paramof(" + args + ")\n" +
                "local restricted, pkg = before(hookof('" + method + "'), param)\n" +
                "return restricted, param.set, param.result, pkg");
    }

    // Binding, with the intent the script gives Android: restricted, skipped, the reported package, whether the intent
    // of the app stayed, and the package, the component package and the class of the intent Android gets
    private static Varargs bind(Globals globals, String method, String intent) {
        return Scripts.run(globals, "local intent = " + intent + "\n" +
                "local package, component = intent:getPackage(), intent:getComponent()\n" +
                "local param = paramof({ [0] = intent })\n" +
                "local restricted, pkg = before(hookof('" + method + "'), param)\n" +
                "local sent = param.args[0]\n" +
                "local name = sent:getComponent()\n" +
                "return restricted, param.set, pkg, sent ~= intent and intent:getPackage() == package and " +
                "intent:getComponent() == component, sent:getPackage(), name and name:getPackageName() or nil, " +
                "name and name:getClassName() or nil, sent:getSelector() == nil");
    }

    // Android fails a binding itself, with the connection registered, so unbindService does not throw
    @Test
    public void bindingsOfHiddenAppsFail() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        String hidden = "intentof(componentof('com.other.hidden', '.Service'), nil)";
        String hiddenPackage = "intentof(nil, 'com.other.hidden')";
        for (String method : new String[]{"bindService", "bindServiceAsUser"})
            for (String intent : new String[]{hidden, hiddenPackage}) {
                Varargs result = bind(globals, method, intent);
                assertTrue(method, result.arg(1).toboolean());
                assertFalse(method, result.arg(2).toboolean());
                assertEquals(method, "com.other.hidden", result.arg(3).tojstring());
                assertTrue(method, result.arg(4).toboolean());
                assertEquals(method, "com.other.hidden:hidden", result.arg(5).tojstring());
                assertTrue(method, result.arg(6).isnil());
            }

        // An isolated service needs a component, else Android throws for the instance, also of a missing app
        Varargs isolated = bind(globals, "bindIsolatedService", hidden);
        assertTrue(isolated.arg(1).toboolean());
        assertFalse(isolated.arg(2).toboolean());
        assertTrue(isolated.arg(4).toboolean());
        assertEquals("com.other.hidden:hidden", isolated.arg(5).tojstring());
        assertEquals("com.other.hidden:hidden", isolated.arg(6).tojstring());
        assertEquals(".Service", isolated.arg(7).tojstring());
        assertTrue(bind(globals, "bindIsolatedService", hiddenPackage).arg(6).isnil());

        // A selector, which Android refuses next to a package
        Scripts.run(globals, "function selected(intent, selector)\n" +
                "  intent.selector = selector\n" +
                "  return intent\n" +
                "end");
        for (String intent : new String[]{"selected(intentof(nil, nil), intentof(nil, 'com.other.hidden'))",
                "selected(" + hidden + ", intentof(nil, 'com.example.app'))"}) {
            Varargs result = bind(globals, "bindService", intent);
            assertTrue(intent, result.arg(1).toboolean());
            assertEquals(intent, "com.other.hidden:hidden", result.arg(5).tojstring());
            assertTrue(intent, result.arg(8).toboolean());
        }

        // The copy of identifier_services, which hooks the same call, is left to Android
        Scripts.run(globals, "visible['com.android.creator:hidden'] = false");
        assertFalse(bind(globals, "bindService", "intentof(nil, 'com.android.creator:hidden')").arg(1).toboolean());
    }

    @Test
    public void componentsOfHiddenAppsAreNotFound() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        String hidden = "intentof(componentof('com.other.hidden', '.Service'), nil)";
        String hiddenPackage = "intentof(nil, 'com.other.hidden')";

        for (String intent : new String[]{hidden, hiddenPackage}) {
            for (String method : new String[]{"startService", "startForegroundService", "sendBroadcast"}) {
                Varargs result = component(globals, method, "{ [0] = " + intent + " }");
                assertTrue(method, result.arg(1).toboolean());
                assertTrue(method, result.arg(2).toboolean());
                assertTrue(method, result.arg(3).isnil());
            }
        }

        // An ordered broadcast goes to a package which cannot exist, so a result receiver gets the answer of Android
        // without receivers; the intent of the app stays as it is
        Varargs ordered = Scripts.run(globals, "local intent = " + hidden + "\n" +
                "local param = paramof({ [0] = intent })\n" +
                "param.setArgument = function(self, index, value) self.args[index] = value end\n" +
                "local restricted = before(hookof('sendOrderedBroadcast'), param)\n" +
                "local sent = param.args[0]\n" +
                "return restricted, param.set, sent:getPackage(), sent:getComponent() == nil, " +
                "intent:getPackage(), intent:getComponent() ~= nil");
        assertTrue(ordered.arg(1).toboolean());
        assertFalse(ordered.arg(2).toboolean());
        assertEquals("com.other.hidden:hidden", ordered.arg(3).tojstring());
        assertTrue(ordered.arg(4).toboolean());
        assertTrue(ordered.arg(5).isnil());
        assertTrue(ordered.arg(6).toboolean());

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

    // The other ways to a service, a broadcast and a screen answer the same
    @Test
    public void otherOverloadsOfHiddenAppsAreNotFound() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        String hidden = "{ [0] = intentof(componentof('com.other.hidden', '.Service'), nil) }";

        Varargs result;
        for (String method : new String[]{"sendBroadcastAsUser", "sendBroadcastWithMultiplePermissions"}) {
            result = component(globals, method, hidden);
            assertTrue(method, result.arg(2).toboolean());
            assertTrue(method, result.arg(3).isnil());
        }
        // Android throws like for a screen which is not installed, through checkStartActivityResult
        result = component(globals, "startActivityIfNeeded", hidden);
        assertTrue(result.arg(1).toboolean());
        assertEquals("android.content.ActivityNotFoundException", className(result, 3));

        // A screen of a task of the app, with the intent as second argument
        result = component(globals, "startActivity", "{ [1] = intentof(componentof('com.other.hidden', '.Main'), nil) }");
        assertTrue(result.arg(1).toboolean());
        assertEquals("android.content.ActivityNotFoundException", className(result, 3));
        assertFalse(component(globals, "startActivity",
                "{ [1] = intentof(componentof('com.example.app', '.Main'), nil) }").arg(1).toboolean());

        for (String method : new String[]{"startServiceAsUser", "startForegroundServiceAsUser"}) {
            result = component(globals, method, hidden);
            assertTrue(method, result.arg(1).toboolean());
            assertTrue(method, result.arg(2).toboolean());
            assertTrue(method, result.arg(3).isnil());
        }

        // Stopping a service of a hidden app stops none, like of an app which is not installed
        for (String method : new String[]{"stopService", "stopServiceAsUser"}) {
            result = component(globals, method, hidden);
            assertTrue(method, result.arg(1).toboolean());
            assertEquals(method, LuaValue.FALSE, result.arg(3));
        }
    }

    // Each ordered broadcast goes to a package which cannot exist, so its result receiver is called
    @Test
    public void orderedBroadcastsReachNoReceiver() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        String run = "local param = paramof({ [0] = %s })\n" +
                "param.setArgument = function(self, index, value) self.args[index] = value end\n" +
                "local restricted = before(hookof('%s'), param)\n" +
                "return restricted, param.set, param.args[0]:getPackage()";
        for (String method : new String[]{"sendOrderedBroadcastAsUser", "sendStickyOrderedBroadcast",
                "sendStickyOrderedBroadcastAsUser"}) {
            Varargs result = Scripts.run(globals, String.format(run, "intentof(nil, 'com.other.hidden')", method));
            assertTrue(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
            assertEquals(method, "com.other.hidden:hidden", result.arg(3).tojstring());
        }
        // A selector, which Android refuses next to a package, else the script fails and the broadcast is sent
        Varargs selected = Scripts.run(globals, String.format(run, "(function()\n" +
                "  local intent = intentof(componentof('com.other.hidden', '.Receiver'), nil)\n" +
                "  intent.selector = intentof(nil, 'com.example.app')\n" +
                "  return intent\n" +
                "end)()", "sendOrderedBroadcast"));
        assertTrue(selected.arg(1).toboolean());
        assertEquals("com.other.hidden:hidden", selected.arg(3).tojstring());
        // Android refuses a sticky broadcast to a component itself, also of an app which is not installed
        Varargs sticky = Scripts.run(globals, String.format(run,
                "intentof(componentof('com.other.hidden', '.Receiver'), nil)", "sendStickyOrderedBroadcast"));
        assertFalse(sticky.arg(1).toboolean());
        assertTrue(sticky.arg(3).isnil());
    }

    // A sticky broadcast to a hidden package is not sent, one to a component Android refuses itself
    @Test
    public void stickyBroadcastsReachNoReceiver() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        for (String method : new String[]{"sendStickyBroadcast", "sendStickyBroadcastAsUser"}) {
            Varargs result = component(globals, method, "{ [0] = intentof(nil, 'com.other.hidden') }");
            assertTrue(method, result.arg(1).toboolean());
            assertTrue(method, result.arg(2).toboolean());
            assertTrue(method, result.arg(3).isnil());
            result = component(globals, method, "{ [0] = intentof(componentof('com.other.hidden', '.Receiver'), nil) }");
            assertFalse(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
        }
    }

    // The message of Android for a screen which is not found, of which Android 13 added the hint about the filter
    @Test
    public void screenNotFoundLikeTheVersion() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        String run = "luajava = { bindClass = function(name) return { SDK_INT = %d } end,\n" +
                "  newInstance = function(name, message) return { name = name, message = message } end }\n" +
                "return notfound(intentof(componentof('com.other.hidden', '.Main'), nil)).message, " +
                "notfound(intentof(nil, 'com.other.hidden')).message";
        String explicit = "Unable to find explicit activity class {com.other.hidden/.Main}; " +
                "have you declared this activity in your AndroidManifest.xml";
        Varargs result = Scripts.run(globals, String.format(run, 32));
        assertEquals(explicit + "?", result.arg(1).tojstring());
        assertEquals("No Activity found to handle Intent { pkg=com.other.hidden }", result.arg(2).tojstring());
        assertEquals(explicit + ", or does your intent not match its declared <intent-filter>?",
                Scripts.run(globals, String.format(run, 33)).arg(1).tojstring());
    }

    // An intent which names no package is resolved by its selector, which can name a hidden app
    @Test
    public void selectorOfHiddenApp() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        Varargs result = Scripts.run(globals,
                "local intent = intentof(nil, nil)\n" +
                        "intent.selector = intentof(nil, 'com.other.hidden')\n" +
                        "local param = paramof({ [4] = intent })\n" +
                        "local restricted, pkg = before(hookof('execStartActivity'), param)\n" +
                        "local own = intentof(nil, nil)\n" +
                        "own.selector = intentof(nil, 'com.android.settings')\n" +
                        "return restricted, pkg, before(hookof('execStartActivity'), paramof({ [4] = own }))");
        assertTrue(result.arg(1).toboolean());
        assertEquals("com.other.hidden", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
    }
}
