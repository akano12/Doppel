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
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// The providers, services, screens and broadcasts of hidden apps, see provider_hidden and component_hidden
public class HiddenAppsScriptTest {
    // Apps: the app itself, a visible app and a hidden app, each with a provider; grants of uris to the app
    private static final String STUBS = Scripts.ARRAYOF + "\n" + String.join("\n",
            "visible = { ['com.example.app'] = true, ['com.android.settings'] = true, ['com.other.hidden'] = false }",
            "providers = { ['com.example.app.provider'] = 'com.example.app', ['settings'] = 'com.android.settings',",
            "  ['com.other.hidden.provider'] = 'com.other.hidden' }",
            "grants = {}",
            "disabled = {}", // Authorities of disabled providers, which only MATCH_DISABLED_COMPONENTS finds
            "persisted = {}", // The uris of ContentResolver.getPersistedUriPermissions
            "lookups = 0",
            "target = 34", // The targetSdkVersion of the app
            "inForeground = true", // UserManager.isUserForeground of the app, from Android 12
            "local bindClass = luajava.bindClass", // The version and the uid of the app, when set
            "luajava.bindClass = function(name)",
            "  if name == 'android.os.Build$VERSION' and sdk then return { SDK_INT = sdk } end",
            "  if name == 'android.os.Process' and uid then",
            "    return { myUid = function(self) return uid end, myPid = function(self) return 1 end } end",
            "  return bindClass(name)",
            "end",
            "values = {}", // Kept per process, like XParam.putValue with the context as scope
            "function uriof(scheme, authority, path)",
            "  return { getScheme = function(self) return scheme end, getAuthority = function(self) return authority end,",
            "    toString = function(self) return scheme .. '://' .. (authority or '') .. (path or '') end }",
            "end",
            "function hookof(method, count)",
            "  return { getMethodName = function(self) return method end,",
            "    getParameterTypes = function(self) return { length = count or 1 } end }",
            "end",
            "function listof(...)", // A collection of uris
            "  local array = arrayof(...)",
            "  return { toArray = function(self) return array end }",
            "end",
            "function paramof(args)",
            "  local context = {",
            "    getPackageManager = function(self)",
            "      return { resolveContentProvider = function(self, authority, flags)",
            "        lookups = lookups + 1",
            "        local pkg = providers[authority]",
            "        if disabled[authority] and math.floor(flags / 0x200) % 2 == 0 then pkg = nil end",
            "        return pkg and { packageName = pkg } or nil end }",
            "    end,",
            "    getContentResolver = function(self)",
            "      return { getPersistedUriPermissions = function(self)",
            "        return { size = function(self) return #persisted end, get = function(self, index)",
            "          return { getUri = function(self) return persisted[index + 1] end } end } end }",
            "    end,",
            "    checkUriPermission = function(self, uri, pid, uid, mode)",
            "      return grants[uri:toString()] and 0 or -1 end,",
            "    getApplicationInfo = function(self) return { targetSdkVersion = target } end,",
            "    getSystemService = function(self, name)",
            "      return name == 'user' and { isUserForeground = function(self) return inForeground end } or nil end }",
            "  return { args = args, result = nil, set = false, context = context,",
            "    getArgument = function(self, index) return self.args[index] end,",
            "    setArgument = function(self, index, value) self.args[index] = value end,",
            "    setResult = function(self, value) self.result = value self.set = true end,",
            "    getException = function(self) return self.exception end,",
            "    isInstance = function(self, object, name)",
            "      return type(object) == 'table' and (name == 'android.net.Uri' and object.getAuthority ~= nil or",
            "        name == 'java.util.Collection' and object.toArray ~= nil) end,",
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
            Scripts.ARRAYOF,
            "local bindClass = luajava.bindClass",
            "luajava.bindClass = function(name)",
            "  if name == 'java.lang.reflect.Array' then",
            "    return { newInstance = function(self, type, length) return { length = length } end }",
            "  end",
            "  return bindClass(name)",
            "end",
            // An intent with its flags, ClipData and extras; a copy has the same extras, like the shallow copy of
            // the Bundle of Android
            "function intentof(component, pkg)",
            "  return { component = component, pkg = pkg, flags = 0, extras = {},",
            "    getComponent = function(self) return self.component end, getPackage = function(self) return self.pkg end,",
            "    getAction = function(self) return self.action end,",
            "    getSelector = function(self) return self.selector end,",
            "    setSelector = function(self, value) self.selector = value end,",
            "    setComponent = function(self, value) self.component = value end,",
            "    setPackage = function(self, value)", // Throws like Android
            "      assert(value == nil or self.selector == nil, 'Cannot set package name when selector is already set')",
            "      self.pkg = value end,",
            "    getFlags = function(self) return self.flags end,",
            "    addFlags = function(self, value) self.flags = bit32.bor(self.flags, value) end,",
            "    getClipData = function(self) return self.clip end,",
            "    setClipData = function(self, value) self.clip = value end,",
            "    hasExtra = function(self, name) return self.extras[name] ~= nil end,",
            "    putExtras = function(self, other) for k, v in pairs(other.extras) do self.extras[k] = v end end,",
            "    clone = function(self)",
            "      local copy = intentof(self.component, self.pkg)",
            "      copy.action, copy.flags, copy.clip = self.action, self.flags, self.clip",
            "      for k, v in pairs(self.extras) do copy.extras[k] = v end",
            "      copy.selector = self.selector and self.selector:clone()",
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
        // The hidden ContentResolver.acquireProvider and acquireUnstableProvider, by uri and by authority: no provider
        for (String method : new String[]{"acquireProvider", "acquireUnstableProvider"})
            for (String target : new String[]{hidden, "'com.other.hidden.provider'"}) {
                Varargs acquired = provider(globals, method, target);
                assertTrue(method, acquired.arg(1).toboolean());
                assertTrue(method, acquired.arg(3).isnil());
            }

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

    // A change observer or notification: restricted, what the app gets thrown and the reported package
    private static Varargs observe(Globals globals, String method, int count, String args) {
        return Scripts.run(globals, "local param = paramof(" + args + ")\n" +
                "local restricted, pkg = before(hookof('" + method + "', " + count + "), param)\n" +
                "return restricted, param.result, pkg");
    }

    // ContentService throws without a provider for apps targeting Android 8 and later, like for a hidden app, but goes
    // on for an exported provider of an app which is there
    @Test
    public void observersAndNotificationsOfHiddenProvidersFail() throws Exception {
        Globals globals = Scripts.load("provider_hidden", STUBS);
        String hidden = "uriof('content', 'com.other.hidden.provider', '/profile')";
        String message = "Failed to find provider com.other.hidden.provider for user 0; " +
                "expected to find a valid ContentProvider for this authority";
        String[][] calls = {{"registerContentObserver", "3", "{ [0] = " + hidden + ", [2] = {} }"},
                {"registerContentObserver", "3", "{ [0] = uriof('content', '0@com.other.hidden.provider', '/x'), [2] = {} }"},
                {"registerContentObserver", "4", "{ [0] = " + hidden + ", [2] = {}, [3] = 0 }"},
                {"notifyChange", "2", "{ [0] = " + hidden + " }"}, {"notifyChange", "3", "{ [0] = " + hidden + ", [2] = 1 }"},
                // Users which Android reads as the user of the app: written otherwise, and the current user
                {"notifyChange", "2", "{ [0] = uriof('content', '00@com.other.hidden.provider', '/x') }"},
                {"notifyChange", "3", "{ [0] = uriof('content', '+0@com.other.hidden.provider', '/x'), [2] = 1 }"},
                {"registerContentObserver", "3", "{ [0] = uriof('content', '-2@com.other.hidden.provider', '/x'), [2] = {} }"},
                {"registerContentObserver", "4", "{ [0] = " + hidden + ", [2] = {}, [3] = -2 }"},
                // The first hidden uri of several, after one which stays, and before another user, which Android
                // notifies after the users below it
                {"notifyChange", "3", "{ [0] = listof(uriof('content', 'settings', '/secure'), " + hidden + ") }"},
                {"notifyChange", "3", "{ [0] = listof(uriof('content', '10@settings', '/secure'), " + hidden + ") }"},
                {"notifyChange", "3", "{ [0] = listof(uriof('content', '10@settings', '/secure'), " +
                        "uriof('content', '-2@com.other.hidden.provider', '/x')) }"},
                // The hidden notifyChange for a user, and the authority of a uri of another scheme
                {"notifyChange", "4", "{ [0] = " + hidden + ", [3] = 0 }"},
                {"registerContentObserver", "3", "{ [0] = uriof('x', 'com.other.hidden.provider', '/p'), [2] = {} }"},
                {"notifyChange", "2", "{ [0] = uriof('x', 'com.other.hidden.provider', '/p') }"},
                {"notifyChange", "3", "{ [0] = listof(uriof('x', 'settings', '/a'), " + hidden + ") }"}};
        for (String[] call : calls) {
            Varargs result = observe(globals, call[0], Integer.parseInt(call[1]), call[2]);
            assertTrue(call[2], result.arg(1).toboolean());
            assertEquals(call[2], "java.lang.SecurityException", className(result, 2));
            assertEquals(call[2], message, ((Throwable) result.arg(2).touserdata()).getMessage());
            assertEquals("com.other.hidden", result.arg(3).tojstring());
        }

        // Android answers itself: without an observer, for another user, all users or one which is no number, for an
        // authority with the user which the hidden overload passes on, without a provider or for one before, also of a
        // user below the app's, for a missing uri, and of a visible or granted uri
        Scripts.run(globals, "grants['content://com.other.hidden.provider/photo/1'] = true");
        for (String[] call : new String[][]{{"registerContentObserver", "3", "{ [0] = " + hidden + " }"},
                {"registerContentObserver", "3", "{ [0] = uriof('content', '10@com.other.hidden.provider', '/x'), [2] = {} }"},
                {"registerContentObserver", "3", "{ [0] = uriof('content', 'abc@com.other.hidden.provider', '/x'), [2] = {} }"},
                {"registerContentObserver", "4", "{ [0] = " + hidden + ", [2] = {}, [3] = 10 }"},
                {"registerContentObserver", "4", "{ [0] = " + hidden + ", [2] = {}, [3] = -1 }"},
                {"notifyChange", "2", "{ [0] = uriof('content', '-1@com.other.hidden.provider', '/x') }"},
                {"notifyChange", "3", "{ [0] = listof(" + hidden + ", uriof('content', 'abc@settings', '/secure')) }"},
                {"registerContentObserver", "4", "{ [0] = uriof('content', '0@com.other.hidden.provider', '/x'), [2] = {}, [3] = 0 }"},
                {"notifyChange", "3", "{ [0] = listof(uriof('content', 'com.unknown.provider', '/x'), " + hidden + ") }"},
                {"notifyChange", "3", "{ [0] = listof(nil, " + hidden + ") }"},
                {"notifyChange", "2", "{ [0] = uriof('content', 'com.example.app.provider', '/own') }"},
                {"notifyChange", "2", "{ [0] = uriof('content', 'com.other.hidden.provider', '/photo/1') }"},
                {"notifyChange", "2", "{ }"},
                // An opaque uri has no authority, Android answers itself for it
                {"registerContentObserver", "3", "{ [0] = uriof('mailto', nil, 'x'), [2] = {} }"},
                {"notifyChange", "3", "{ [0] = listof(uriof('mailto', nil, 'x'), " + hidden + ") }"}})
            assertFalse(call[2], observe(globals, call[0], Integer.parseInt(call[1]), call[2]).arg(1).toboolean());

        // From Android 12 USER_CURRENT is the user of the app only while it is the current user, otherwise Android
        // refuses the user before it looks for a provider; before Android 12 only user 0 counts as current
        String[] current = {"{ [0] = uriof('content', '-2@com.other.hidden.provider', '/x'), [2] = {} }",
                "{ [0] = " + hidden + ", [2] = {}, [3] = -2 }"};
        Scripts.run(globals, "sdk = 31 inForeground = false");
        for (String args : current)
            assertFalse(args, observe(globals, "registerContentObserver", args.contains("[3]") ? 4 : 3, args).arg(1).toboolean());
        Scripts.run(globals, "inForeground = true");
        for (String args : current)
            assertTrue(args, observe(globals, "registerContentObserver", args.contains("[3]") ? 4 : 3, args).arg(1).toboolean());
        Scripts.run(globals, "sdk = 30 uid = 1010123"); // User 10
        for (String args : current)
            assertFalse(args, observe(globals, "registerContentObserver", args.contains("[3]") ? 4 : 3, args).arg(1).toboolean());
        assertTrue(observe(globals, "registerContentObserver", 3, calls[0][2]).arg(1).toboolean());
        Scripts.run(globals, "sdk = nil uid = nil");

        // Older targets go on without a provider too
        Scripts.run(globals, "target = 25");
        assertFalse(observe(globals, "registerContentObserver", 3, calls[0][2]).arg(1).toboolean());
        assertFalse(observe(globals, "notifyChange", 2, calls[3][2]).arg(1).toboolean());

        // ContentService finds disabled providers too, a query does not
        globals = Scripts.load("provider_hidden", STUBS);
        Scripts.run(globals, "disabled['com.other.hidden.provider'] = true");
        assertFalse(provider(globals, "query", hidden).arg1().toboolean());
        Varargs result = observe(globals, "registerContentObserver", 3, calls[0][2]);
        assertTrue(result.arg(1).toboolean());
        assertEquals(message, ((Throwable) result.arg(2).touserdata()).getMessage());
        assertFalse(provider(globals, "query", hidden).arg1().toboolean());
    }

    private static Varargs component(Globals globals, String method, String args) {
        return Scripts.run(globals, "local param = paramof(" + args + ")\n" +
                "local restricted, pkg = before(hookof('" + method + "'), param)\n" +
                "return restricted, param.set, param.result, pkg");
    }

    // A call with the intent as first argument and the intent the script gives Android: restricted, skipped,
    // the reported package, whether the intent of the app stayed, and the package, the component package and
    // the class of the intent Android gets, and whether it has no selector
    private static Varargs sent(Globals globals, String method, String intent) {
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
        // The component stays, renamed: an isolated service needs one, else Android throws for the instance,
        // also of a missing app
        for (String method : new String[]{"bindService", "bindServiceAsUser", "bindIsolatedService"}) {
            Varargs result = sent(globals, method, hidden);
            assertTrue(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
            assertEquals(method, "com.other.hidden", result.arg(3).tojstring());
            assertTrue(method, result.arg(4).toboolean());
            assertTrue(method, result.arg(5).isnil());
            assertEquals(method, "com.other.hidden:hidden", result.arg(6).tojstring());
            assertEquals(method, ".Service", result.arg(7).tojstring());

            result = sent(globals, method, hiddenPackage);
            assertTrue(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
            assertTrue(method, result.arg(4).toboolean());
            assertEquals(method, "com.other.hidden:hidden", result.arg(5).tojstring());
            assertTrue(method, result.arg(6).isnil());
        }

        // A selector next to a component, which Android does not look at, stays
        Scripts.run(globals, "function selected(intent, selector)\n" +
                "  intent.selector = selector\n" +
                "  return intent\n" +
                "end");
        Varargs selected = sent(globals, "bindService", "selected(" + hidden + ", intentof(nil, 'com.example.app'))");
        assertTrue(selected.arg(1).toboolean());
        assertEquals("com.other.hidden:hidden", selected.arg(6).tojstring());
        assertFalse(selected.arg(8).toboolean());

        // The copy of identifier_services, which hooks the same call, is left to Android
        Scripts.run(globals, "visible['com.android.creator:hidden'] = false");
        assertFalse(sent(globals, "bindService", "intentof(nil, 'com.android.creator:hidden')").arg(1).toboolean());
    }

    // Android gets a copy naming a package which cannot exist, so it makes its own checks first, like of protected
    // broadcasts and file descriptors, and then finds nothing, like for an app which is not installed
    @Test
    public void componentsOfHiddenAppsAreNotFound() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        String hidden = "intentof(componentof('com.other.hidden', '.Service'), nil)";
        String hiddenPackage = "intentof(nil, 'com.other.hidden')";

        for (String method : new String[]{"startService", "startForegroundService", "stopService", "sendBroadcast"}) {
            Varargs result = sent(globals, method, hidden);
            assertTrue(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
            assertTrue(method, result.arg(4).toboolean());
            assertEquals(method, "com.other.hidden:hidden", result.arg(6).tojstring());
            assertEquals(method, ".Service", result.arg(7).tojstring());
            result = sent(globals, method, hiddenPackage);
            assertTrue(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
            assertTrue(method, result.arg(4).toboolean());
            assertEquals(method, "com.other.hidden:hidden", result.arg(5).tojstring());
        }

        // Screens through Instrumentation, with the intent as fifth argument: a copy, so the code of the app which
        // Android calls on the way, like Activity.onProvideReferrer, sees the name it asked for
        Varargs screen = Scripts.run(globals, "local intent = " + hidden + "\n" +
                "local param = paramof({ [4] = intent })\n" +
                "local restricted, pkg = before(hookof('execStartActivity'), param)\n" +
                "local sent = param.args[4]\n" +
                "local during = intent:getComponent():getPackageName()\n" +
                "after(hookof('execStartActivity'), param)\n" +
                "return restricted, param.set, pkg, sent ~= intent, during, sent:getComponent():getPackageName(), " +
                "sent:getComponent():getClassName(), intent:getComponent():getPackageName()");
        assertTrue(screen.arg(1).toboolean());
        assertFalse(screen.arg(2).toboolean());
        assertEquals("com.other.hidden", screen.arg(3).tojstring());
        assertTrue(screen.arg(4).toboolean());
        assertEquals("com.other.hidden", screen.arg(5).tojstring());
        assertEquals("com.other.hidden:hidden", screen.arg(6).tojstring());
        assertEquals(".Service", screen.arg(7).tojstring());
        assertEquals("com.other.hidden", screen.arg(8).tojstring());

        // Several screens: a copy of the array, with a copy of each screen of a hidden app and the others as they
        // are, so Android starts the screens before the first hidden one
        Varargs screens = Scripts.run(globals, "local own = intentof(componentof('com.example.app', '.Main'), nil)\n" +
                "local intents = arrayof(own, " + hiddenPackage + ", " + hidden + ")\n" +
                "local param = paramof({ [4] = intents })\n" +
                "local restricted, pkg = before(hookof('execStartActivitiesAsUser'), param)\n" +
                "local sent = param.args[4]\n" +
                "local package, component = intents[2]:getPackage(), intents[3]:getComponent():getPackageName()\n" +
                "after(hookof('execStartActivitiesAsUser'), param)\n" +
                "return restricted, param.set, pkg, sent ~= intents and sent.length == 3 and sent[1] == own, " +
                "sent[2]:getPackage(), sent[3]:getComponent():getPackageName(), package, component");
        assertTrue(screens.arg(1).toboolean());
        assertFalse(screens.arg(2).toboolean());
        assertEquals("com.other.hidden", screens.arg(3).tojstring());
        assertTrue(screens.arg(4).toboolean());
        assertEquals("com.other.hidden:hidden", screens.arg(5).tojstring());
        assertEquals("com.other.hidden:hidden", screens.arg(6).tojstring());
        assertEquals("com.other.hidden", screens.arg(7).tojstring());
        assertEquals("com.other.hidden", screens.arg(8).tojstring());
    }

    // Android changes the intent of a screen while it starts it, which the app sees afterwards: the ClipData of a
    // share with its grant flags and the referrer of the activity, here written by the stand-in of Android on the
    // copy; a ClipData of the app itself stays the same object
    @Test
    public void screensGetWhatAndroidChanged() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        String run = "local intent = intentof(nil, 'com.other.hidden')\n" +
                "intent.action, intent.flags, intent.clip, intent.extras['android.intent.extra.TEXT'] = " +
                "'android.intent.action.SEND', 0x10000000, %s, 'hi'\n" +
                "local param = paramof({ [%s] = intent })\n" +
                "before(hookof('%s'), param)\n" +
                "local copy = param.args[%s]\n" +
                "copy.extras['android.intent.extra.REFERRER'] = 'android-app://com.example.app'\n" +
                "if copy.clip == nil then copy.clip = 'migrated' copy.flags = copy.flags + 1 end\n" +
                "after(hookof('%s'), param)\n" +
                "return intent.clip, intent.flags, intent.extras['android.intent.extra.REFERRER'], " +
                "intent.extras['android.intent.extra.TEXT'], intent:getPackage()";
        for (String[] call : new String[][]{{"4", "execStartActivity"}, {"0", "startActivityIfNeeded"},
                {"1", "startActivity"}}) {
            Varargs result = Scripts.run(globals, String.format(run, "nil", call[0], call[1], call[0], call[1]));
            assertEquals(call[1], "migrated", result.arg(1).tojstring());
            assertEquals(call[1], 0x10000001, result.arg(2).toint());
            assertEquals(call[1], "android-app://com.example.app", result.arg(3).tojstring());
            assertEquals(call[1], "hi", result.arg(4).tojstring());
            assertEquals(call[1], "com.other.hidden", result.arg(5).tojstring());

            result = Scripts.run(globals, String.format(run, "'own'", call[0], call[1], call[0], call[1]));
            assertEquals(call[1], "own", result.arg(1).tojstring());
            assertEquals(call[1], 0x10000000, result.arg(2).toint());
            assertEquals(call[1], "android-app://com.example.app", result.arg(3).tojstring());
        }

        // Each screen of a hidden app of several
        Varargs screens = Scripts.run(globals, "local own = intentof(componentof('com.example.app', '.Main'), nil)\n" +
                "local intents = arrayof(own, intentof(nil, 'com.other.hidden'))\n" +
                "local param = paramof({ [4] = intents })\n" +
                "before(hookof('execStartActivitiesAsUser'), param)\n" +
                "param.args[4][2].clip = 'migrated'\n" +
                "after(hookof('execStartActivitiesAsUser'), param)\n" +
                "return intents[2].clip, own.clip");
        assertEquals("migrated", screens.arg(1).tojstring());
        assertTrue(screens.arg(2).isnil());

        // A service or a broadcast keeps the intent of the app as it is
        Varargs service = Scripts.run(globals, "local intent = intentof(nil, 'com.other.hidden')\n" +
                "local param = paramof({ [0] = intent })\n" +
                "before(hookof('startService'), param)\n" +
                "param.args[0].clip = 'changed'\n" +
                "after(hookof('startService'), param)\n" +
                "return intent.clip");
        assertTrue(service.arg(1).isnil());
    }

    @Test
    public void visibleAndImplicitComponentsStay() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        for (String intent : new String[]{
                "intentof(componentof('com.example.app', '.Service'), nil)", // The app itself
                "intentof(nil, 'com.android.settings')", // A system app
                "intentof(nil, nil)", // Resolved by Android
                "nil"}) {
            for (String method : new String[]{"bindService", "startService", "sendBroadcast", "sendOrderedBroadcast"}) {
                Varargs result = component(globals, method, "{ [0] = " + intent + " }");
                assertFalse(method + " " + intent, result.arg(1).toboolean());
                assertFalse(method + " " + intent, result.arg(2).toboolean());
            }
            assertFalse(intent, component(globals, "execStartActivity", "{ [4] = " + intent + " }").arg(1).toboolean());
        }
        assertFalse(component(globals, "execStartActivitiesAsUser",
                "{ [4] = { length = 1, [1] = intentof(nil, 'com.android.settings') } }").arg(1).toboolean());
        assertFalse(component(globals, "execStartActivitiesAsUser", "{}").arg(1).toboolean());
    }

    // The other ways to a service, a broadcast and a screen answer the same
    @Test
    public void otherOverloadsOfHiddenAppsAreNotFound() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        String hidden = "intentof(componentof('com.other.hidden', '.Main'), nil)";
        for (String method : new String[]{"sendBroadcastAsUser", "sendBroadcastWithMultiplePermissions",
                "startServiceAsUser", "startForegroundServiceAsUser", "stopServiceAsUser"}) {
            Varargs result = sent(globals, method, hidden);
            assertTrue(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
            assertTrue(method, result.arg(4).toboolean());
            assertEquals(method, "com.other.hidden:hidden", result.arg(6).tojstring());
        }

        // A screen started by the activity itself, and one of a task of the app, with the intent as second argument:
        // a copy, like through Instrumentation
        for (String[] call : new String[][]{{"0", "startActivityIfNeeded"}, {"1", "startActivity"}}) {
            Varargs task = Scripts.run(globals, "local intent = " + hidden + "\n" +
                    "local param = paramof({ [" + call[0] + "] = intent })\n" +
                    "local restricted = before(hookof('" + call[1] + "'), param)\n" +
                    "local sent = param.args[" + call[0] + "]\n" +
                    "local during = intent:getComponent():getPackageName()\n" +
                    "after(hookof('" + call[1] + "'), param)\n" +
                    "return restricted, param.set, sent ~= intent, during, sent:getComponent():getPackageName()");
            assertTrue(call[1], task.arg(1).toboolean());
            assertFalse(call[1], task.arg(2).toboolean());
            assertTrue(call[1], task.arg(3).toboolean());
            assertEquals(call[1], "com.other.hidden", task.arg(4).tojstring());
            assertEquals(call[1], "com.other.hidden:hidden", task.arg(5).tojstring());
        }
        assertFalse(component(globals, "startActivity",
                "{ [1] = intentof(componentof('com.example.app', '.Main'), nil) }").arg(1).toboolean());
    }

    // An ordered broadcast keeps the intent of the app, which a result receiver gets back, with a permission which no
    // app holds, so Android skips the receivers of the hidden app; a sticky one cannot have a permission, so it is
    // a copy, like an intent naming the hidden app by its selector, which also goes to the receivers registered for
    // the intent itself, of any app
    @Test
    public void orderedBroadcastsReachNoReceiver() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        String run = "local intent = %s\n" +
                "local param = paramof({ [0] = intent, [1] = %s, [2] = 'com.example.PERMISSION' })\n" +
                "local restricted, pkg = before(hookof('%s'), param)\n" +
                "local sent = param.args[0]\n" +
                "return restricted, param.set, pkg, sent == intent, param.args[1], param.args[2], sent:getPackage(), " +
                "sent:getSelector() and sent:getSelector():getPackage() or nil, intent:getPackage()";
        String hidden = "intentof(componentof('com.other.hidden', '.Receiver'), nil)";
        String hiddenPackage = "intentof(nil, 'com.other.hidden')";

        // sendOrderedBroadcast(intent, permission, ...)
        for (String intent : new String[]{hidden, hiddenPackage}) {
            Varargs result = Scripts.run(globals, String.format(run, intent, "nil", "sendOrderedBroadcast"));
            assertTrue(intent, result.arg(1).toboolean());
            assertFalse(intent, result.arg(2).toboolean());
            assertEquals(intent, "com.other.hidden", result.arg(3).tojstring());
            assertTrue(intent, result.arg(4).toboolean());
            assertEquals(intent, "com.other.hidden:hidden", result.arg(5).tojstring());
            assertEquals(intent, "com.example.PERMISSION", result.arg(6).tojstring());
        }
        // sendOrderedBroadcast(intent, initialCode, permission, ...) and sendOrderedBroadcastAsUser(intent, user, permission, ...)
        for (String[] call : new String[][]{{"0", "sendOrderedBroadcast"}, {"{}", "sendOrderedBroadcastAsUser"}}) {
            Varargs result = Scripts.run(globals, String.format(run, hidden, call[0], call[1]));
            assertTrue(call[1], result.arg(4).toboolean());
            assertFalse(call[1], "com.other.hidden:hidden".equals(result.arg(5).tojstring()));
            assertEquals(call[1], "com.other.hidden:hidden", result.arg(6).tojstring());
        }

        for (String method : new String[]{"sendStickyOrderedBroadcast", "sendStickyOrderedBroadcastAsUser"}) {
            Varargs result = Scripts.run(globals, String.format(run, hiddenPackage, "nil", method));
            assertTrue(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
            assertFalse(method, result.arg(4).toboolean());
            assertTrue(method, result.arg(5).isnil());
            assertEquals(method, "com.other.hidden:hidden", result.arg(7).tojstring());
            assertEquals(method, "com.other.hidden", result.arg(9).tojstring());
        }

        Varargs selected = Scripts.run(globals, String.format(run, "(function()\n" +
                "  local intent = intentof(nil, nil)\n" +
                "  intent.selector = intentof(nil, 'com.other.hidden')\n" +
                "  return intent\n" +
                "end)()", "nil", "sendOrderedBroadcast"));
        assertTrue(selected.arg(1).toboolean());
        assertFalse(selected.arg(4).toboolean());
        assertTrue(selected.arg(5).isnil());
        assertTrue(selected.arg(7).isnil());
        assertEquals("com.other.hidden:hidden", selected.arg(8).tojstring());

        // Android sends a widget broadcast which names no component to the receivers of the app itself, which the
        // permission would skip, so it is a copy; one to a component of another app Android refuses itself
        for (String action : new String[]{"APPWIDGET_UPDATE", "APPWIDGET_CONFIGURE"}) {
            String widget = "(function()\n" +
                    "  local intent = %s\n" +
                    "  intent.action = 'android.appwidget.action." + action + "'\n" +
                    "  return intent\n" +
                    "end)()";
            Varargs result = Scripts.run(globals, String.format(run, String.format(widget, hiddenPackage), "nil",
                    "sendOrderedBroadcast"));
            assertTrue(action, result.arg(1).toboolean());
            assertFalse(action, result.arg(4).toboolean());
            assertTrue(action, result.arg(5).isnil());
            assertEquals(action, "com.other.hidden:hidden", result.arg(7).tojstring());
            assertEquals(action, "com.other.hidden", result.arg(9).tojstring());
            result = Scripts.run(globals, String.format(run, String.format(widget, hidden), "nil", "sendOrderedBroadcast"));
            assertTrue(action, result.arg(4).toboolean());
            assertEquals(action, "com.other.hidden:hidden", result.arg(5).tojstring());
        }
    }

    // A sticky broadcast goes to a package which cannot exist; one to a component Android refuses itself
    @Test
    public void stickyBroadcastsReachNoReceiver() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        // Removing one gets the same copy, which Android finds by Intent.filterEquals, which compares the package
        for (String method : new String[]{"sendStickyBroadcast", "sendStickyBroadcastAsUser", "removeStickyBroadcast",
                "removeStickyBroadcastAsUser"}) {
            Varargs result = sent(globals, method, "intentof(nil, 'com.other.hidden')");
            assertTrue(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
            assertEquals(method, "com.other.hidden:hidden", result.arg(5).tojstring());
            result = sent(globals, method, "intentof(componentof('com.other.hidden', '.Receiver'), nil)");
            assertTrue(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
            assertEquals(method, "com.other.hidden:hidden", result.arg(6).tojstring());
        }
    }

    // An exception of Android, like ActivityNotFoundException
    public static class ScreenException extends RuntimeException {
        public ScreenException(String message) {
            super(message);
        }
    }

    // An exception without a constructor with a message
    public static class SilentScreenException extends RuntimeException {
        public SilentScreenException() {
            super("No Activity found to handle Intent { pkg=com.other.hidden:hidden }");
        }
    }

    // The exception of Android for the renamed screen has the name the app asked for, with the same class, stack trace
    // and cause; the text of an intent leaves out the package in front of a class of the package of its component
    @Test
    public void exceptionsHaveTheNameOfTheApp() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        String explicit = "Unable to find explicit activity class {%s/com.other.hidden.Main}; " +
                "have you declared this activity in your AndroidManifest.xml?";
        String implicit = "No Activity found to handle Intent { act=x sel=cmp=%s} }";
        String run = "local param = paramof({ [4] = %s })\n" +
                "before(hookof('execStartActivity'), param)\n" +
                "param.exception = genuine\n" +
                "return after(hookof('execStartActivity'), param), param.result";

        ScreenException genuine = new ScreenException(String.format(explicit, "com.other.hidden:hidden"));
        genuine.initCause(new RuntimeException("Remote stack trace"));
        globals.set("genuine", CoerceJavaToLua.coerce(genuine));
        Varargs result = Scripts.run(globals, String.format(run,
                "intentof(componentof('com.other.hidden', 'com.other.hidden.Main'), nil)"));
        assertFalse(result.arg(1).toboolean()); // Reported by before
        Throwable fake = (Throwable) result.arg(2).touserdata();
        assertEquals(ScreenException.class, fake.getClass());
        assertEquals(String.format(explicit, "com.other.hidden"), fake.getMessage());
        assertArrayEquals(genuine.getStackTrace(), fake.getStackTrace());
        assertSame(genuine.getCause(), fake.getCause());

        globals.set("genuine", CoerceJavaToLua.coerce(
                new ScreenException(String.format(implicit, "com.other.hidden:hidden/com.other.hidden.Main"))));
        result = Scripts.run(globals, String.format(run, "(function()\n" +
                "  local intent = intentof(nil, nil)\n" +
                "  intent.selector = intentof(componentof('com.other.hidden', 'com.other.hidden.Main'), nil)\n" +
                "  return intent\n" +
                "end)()"));
        assertEquals(String.format(implicit, "com.other.hidden/.Main"),
                ((Throwable) result.arg(2).touserdata()).getMessage());

        // A name with :hidden the app asked for itself stays, and so does an exception without the name
        globals.set("genuine", CoerceJavaToLua.coerce(new ScreenException("No Activity found to handle Intent { pkg=no.such:hidden }")));
        Scripts.run(globals, "visible['no.such:hidden'] = false");
        assertTrue(Scripts.run(globals, String.format(run, "intentof(nil, 'no.such:hidden')")).arg(2).isnil());
        globals.set("genuine", CoerceJavaToLua.coerce(new ScreenException("file:///sdcard/a exposed beyond app")));
        assertTrue(Scripts.run(globals, String.format(run, "intentof(nil, 'com.other.hidden')")).arg(2).isnil());

        // An exception without a constructor with a message gets the message itself, which only Android allows
        // (java.lang is not open to the tests), so here it stays as it is, without an error of the script
        globals.set("genuine", CoerceJavaToLua.coerce(new SilentScreenException()));
        result = Scripts.run(globals, String.format(run, "intentof(nil, 'com.other.hidden')"));
        assertFalse(result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());

        // A call which fails in before, like a lookup of a package, takes its own frame in after, so the call around
        // it, through the same hook on the same thread, still gives the name back
        globals.set("genuine", CoerceJavaToLua.coerce(new ScreenException(String.format(explicit, "com.other.hidden:hidden"))));
        Varargs nested = Scripts.run(globals,
                "local outer = paramof({ [4] = intentof(componentof('com.other.hidden', 'com.other.hidden.Main'), nil) })\n" +
                        "before(hookof('execStartActivity'), outer)\n" +
                        "local inner = paramof({ [0] = intentof(nil, 'com.other.hidden') })\n" +
                        "inner.isPackageVisible = function(self, name) error('lookup failed') end\n" +
                        "local failed = not pcall(before, hookof('sendBroadcast'), inner)\n" +
                        "after(hookof('sendBroadcast'), inner)\n" +
                        "outer.exception = genuine\n" +
                        "after(hookof('execStartActivity'), outer)\n" +
                        "return failed, outer.result");
        assertTrue(nested.arg(1).toboolean());
        assertEquals(String.format(explicit, "com.other.hidden"), ((Throwable) nested.arg(2).touserdata()).getMessage());
    }

    @Test
    public void replaceLikePackageProbe() throws Exception {
        assertEquals(Scripts.function("package_probe", "replace"), Scripts.function("component_hidden", "replace"));
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
                        "local sent = param.args[4]\n" +
                        "after(hookof('execStartActivity'), param)\n" +
                        "local own = intentof(nil, nil)\n" +
                        "own.selector = intentof(nil, 'com.android.settings')\n" +
                        "return restricted, pkg, before(hookof('execStartActivity'), paramof({ [4] = own })), " +
                        "sent ~= intent and sent:getPackage() == nil, sent:getSelector():getPackage(), " +
                        "intent:getSelector():getPackage()");
        assertTrue(result.arg(1).toboolean());
        assertEquals("com.other.hidden", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertEquals("com.other.hidden:hidden", result.arg(5).tojstring());
        assertEquals("com.other.hidden", result.arg(6).tojstring());

        // A broadcast keeps the intent itself, without a package, so Android gives it to the receivers registered
        // for it, the app's own too, and finds no receivers of the manifest, which it resolves by the selector
        Varargs broadcast = Scripts.run(globals, "local intent = intentof(nil, nil)\n" +
                "intent.selector = intentof(nil, 'com.other.hidden')\n" +
                "local param = paramof({ [0] = intent })\n" +
                "local restricted = before(hookof('sendBroadcast'), param)\n" +
                "local sent = param.args[0]\n" +
                "return restricted, sent ~= intent, sent:getPackage(), sent:getSelector():getPackage(), " +
                "intent:getSelector():getPackage()");
        assertTrue(broadcast.arg(1).toboolean());
        assertTrue(broadcast.arg(2).toboolean());
        assertTrue(broadcast.arg(3).isnil());
        assertEquals("com.other.hidden:hidden", broadcast.arg(4).tojstring());
        assertEquals("com.other.hidden", broadcast.arg(5).tojstring());
    }

    // An intent read from a parcel can have a package next to a selector, which Android refuses to set: the copy has
    // no selector, and the intent of the app stays as it is
    @Test
    public void packageNextToSelector() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        String run = "local intent = intentof(nil, 'com.other.hidden')\n" +
                "intent.selector = intentof(nil, 'com.example.app')\n" +
                "local param = paramof({ [%s] = intent })\n" +
                "local restricted = before(hookof('%s'), param)\n" +
                "local sent = param.args[%s]\n" +
                "after(hookof('%s'), param)\n" +
                "return restricted, sent ~= intent, sent:getPackage(), sent:getSelector() == nil, intent:getPackage(), " +
                "intent:getSelector():getPackage()";
        for (String[] call : new String[][]{{"0", "sendBroadcast"}, {"0", "removeStickyBroadcast"},
                {"4", "execStartActivity"}}) {
            Varargs result = Scripts.run(globals, String.format(run, call[0], call[1], call[0], call[1]));
            assertTrue(call[1], result.arg(1).toboolean());
            assertTrue(call[1], result.arg(2).toboolean());
            assertEquals(call[1], "com.other.hidden:hidden", result.arg(3).tojstring());
            assertTrue(call[1], result.arg(4).toboolean());
            assertEquals(call[1], "com.other.hidden", result.arg(5).tojstring());
            assertEquals(call[1], "com.example.app", result.arg(6).tojstring());
        }
    }

    // A service intent with only a selector: Android throws "Service Intent must be explicit" for an app targeting
    // Android 5 or later, and resolves it by the selector for an older one
    @Test
    public void serviceWithOnlySelector() throws Exception {
        Globals globals = Scripts.load("component_hidden", STUBS);
        String intent = "(function()\n" +
                "  local intent = intentof(nil, nil)\n" +
                "  intent.selector = intentof(nil, 'com.other.hidden')\n" +
                "  return intent\n" +
                "end)()";
        String[] methods = {"startService", "startForegroundServiceAsUser", "stopService", "bindService",
                "bindIsolatedService"};
        for (String method : methods) {
            Varargs result = sent(globals, method, intent);
            assertFalse(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
        }
        Scripts.run(globals, "target = 19");
        for (String method : methods) {
            Varargs result = sent(globals, method, intent);
            assertTrue(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
            assertTrue(method, result.arg(4).toboolean());
            assertTrue(method, result.arg(5).isnil());
        }
    }
}
