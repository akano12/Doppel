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

import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// The identifier services of manufacturers (identifier_services): the OAID SDKs bind them or ask their provider
public class IdentifierServicesScriptTest {
    private static final String STUBS = String.join("\n",
            "function componentof(pkg, cls)",
            "  return { getPackageName = function(self) return pkg end, getClassName = function(self) return cls end }",
            "end",
            "luajava.newInstance = function(name, pkg, cls)",
            "  if name == 'java.lang.IllegalArgumentException' then return pkg end",
            "  if name == 'android.content.Intent' then return intentof(nil, nil, pkg) end",
            "  assert(name == 'android.content.ComponentName', name)",
            "  return componentof(pkg, cls)",
            "end",
            "function intentof(component, pkg, action)",
            "  return { component = component, pkg = pkg,",
            "    getComponent = function(self) return self.component end,",
            "    getPackage = function(self) return self.pkg end, getAction = function(self) return action end,",
            "    setSelector = function(self, value) self.selector = value end,",
            "    setComponent = function(self, value) self.component = value end,",
            "    setPackage = function(self, value)", // Throws like Android
            "      assert(value == nil or self.selector == nil, 'Cannot set package name when selector is already set')",
            "      self.pkg = value end,",
            "    clone = function(self)",
            "      local copy = intentof(self.component, self.pkg, action)",
            "      copy.selector = self.selector",
            "      return copy end }",
            "end",
            "function uriof(authority)",
            "  return { getAuthority = function(self) return authority end }",
            "end",
            // The Android version the app targets, like ApplicationInfo.targetSdkVersion
            "target = 34",
            // The classes of the services of a package offering an action, like PackageManager.queryIntentServices,
            // the number of lookups and the values kept for the process
            "offered = {}",
            "lookups = 0",
            "values = {}",
            "pm = { queryIntentServices = function(self, intent, flags)",
            "  assert(flags == 0x200 + 0x40000 + 0x80000, flags)",
            "  lookups = lookups + 1",
            "  local classes = offered[intent:getPackage() .. '/' .. intent:getAction()] or {}",
            "  return { size = function(self) return #classes end,",
            "    get = function(self, i) return { serviceInfo = { name = classes[i + 1] } } end }",
            "end }",
            // ApplicationInfo.flags by package, like PackageManager.getApplicationInfo, which throws for a package
            // not found, and the number of lookups
            "flags = {}",
            "infos = 0",
            "pm.getApplicationInfo = function(self, pkg, f)",
            "  assert(f == 0, f)",
            "  infos = infos + 1",
            "  if flags[pkg] == nil then error('NameNotFoundException') end",
            "  return { flags = flags[pkg] }",
            "end",
            "function paramof(arg)",
            "  return { result = 'real', set = false,",
            "    getApplicationContext = function(self)",
            "      return { getApplicationInfo = function(self) return { targetSdkVersion = target } end,",
            "        getPackageManager = function(self) return pm end }",
            "    end,",
            "    getResult = function(self) return self.result end,",
            "    getException = function(self) return self.ex end,",
            "    getThis = function(self) return self.this end,",
            "    getSetting = function(self, name) assert(name == 'package_probe.suffix', name) return '.x123456789' end,",
            "    getValue = function(self, name, scope) return values[name] end,",
            "    putValue = function(self, name, value, scope) values[name] = value end,",
            "    unhooked = function(self, f) return f() end,",
            "    getArgument = function(self, i) return arg end,",
            "    setArgument = function(self, i, v) self.sent = v end,",
            "    setResult = function(self, v) self.result = v; self.set = true end }",
            "end",
            "function hookof(method, class)",
            "  return { getMethodName = function(self) return method end,",
            "    getClassName = function(self) return class or 'android.content.ContentResolver' end }",
            "end",
            // Restricted, the result, the reported package, the package of the intent Android gets and of its component
            "function bind(intent, method)",
            "  local param = paramof(intent)",
            "  local restricted, old = before(hookof(method or 'bindService'), param)",
            "  local name = param.sent and param.sent:getComponent()",
            "  return restricted, param.result, old, param.sent and param.sent:getPackage(), name and name:getPackageName()",
            "end",
            // A call of a method of PackageManager with its result and arguments
            "function lookup(method, result, ...)",
            "  local param = paramof(nil)",
            "  param.result = result",
            "  param.args = { ... }",
            "  param.getArgument = function(self, i) return self.args[i + 1] end",
            "  param.setArgument = function(self, i, v) self.args[i + 1] = v end",
            "  return hookof(method, 'android.content.pm.PackageManager'), param",
            "end",
            "function acquire(arg, method)",
            "  local param = paramof(arg)",
            "  local restricted, old = before(hookof(method), param)",
            "  return restricted, param.set and param.result, old",
            "end");

    private static Varargs run(String lua) throws Exception {
        Globals globals = Scripts.load("identifier_services", STUBS);
        return Scripts.run(globals, lua);
    }

    // Each way the OAID SDKs bind the services, like gzu-liyujiang/Android_CN_OAID and the SDKs of Huawei and Honor;
    // Android fails the binding itself, with the connection registered, so unbindService does not throw
    @Test
    public void servicesOfManufacturersAreNotFound() throws Exception {
        String[][] intents = {
                {"componentof('com.samsung.android.deviceidservice', 'com.samsung.android.deviceidservice.DeviceIdService')", "nil", "nil"},
                {"nil", "'com.zui.deviceidservice'", "nil"},
                {"componentof('com.asus.msa.SupplementaryDID', 'com.asus.msa.SupplementaryDID.SupplementaryDIDService')", "nil", "'com.asus.msa.action.ACCESS_DID'"},
                {"componentof('com.coolpad.deviceidsupport', 'com.coolpad.deviceidsupport.DeviceIdService')", "nil", "nil"},
                {"componentof('com.mdid.msa', 'com.mdid.msa.service.MsaIdService')", "nil", "'com.bun.msa.action.bindto.service'"},
                {"nil", "'com.qiku.id'", "'qiku.service.action.id'"},
                {"componentof('com.heytap.openid', 'com.heytap.openid.IdentifyService')", "nil", "'action.com.heytap.openid.OPEN_ID_SERVICE'"},
                {"componentof('com.coloros.mcs', 'com.oplus.stdid.IdentifyService')", "nil", "'action.com.oplus.stdid.ID_SERVICE'"},
                {"nil", "'com.android.creator'", "'android.service.action.msa'"},
                {"nil", "'com.hihonor.id'", "'com.hihonor.id.HnOaIdService'"},
                {"nil", "'com.huawei.hwid'", "'com.uodis.opendevice.OPENIDS_SERVICE'"},
                {"nil", "'com.huawei.hwid.tv'", "'com.uodis.opendevice.OPENIDS_SERVICE'"},
                {"nil", "'com.huawei.hms'", "'com.uodis.opendevice.OPENIDS_SERVICE'"},
                {"nil", "'com.freeme.secureguard'", "'android.service.action.msa'"}};
        for (String method : new String[]{"bindService", "bindIsolatedService", "bindServiceAsUser"})
            for (String[] intent : intents) {
                Varargs result = run("return bind(intentof(" + String.join(", ", intent) + "), '" + method + "')");
                assertTrue(method + " " + String.join(" ", intent), result.arg(1).toboolean());
                assertEquals("real", result.arg(2).tojstring());
                assertEquals(result.arg(3).tojstring() + ":hidden", result.arg(4).tojstring());
                // An isolated service needs a component, else Android throws for the instance
                if (method.equals("bindIsolatedService") && !intent[0].equals("nil"))
                    assertEquals(result.arg(4).tojstring(), result.arg(5).tojstring());
                else
                    assertFalse(result.arg(5).toboolean());
            }
    }

    // An action without a package (an implicit intent), which Android resolves to the service offering it for apps
    // targeting Android 4.4 or earlier only; for the others it throws "Service Intent must be explicit" itself
    // (ContextImpl.validateServiceIntent), which stays, like on any device
    @Test
    public void implicitIntentsOfOldApps() throws Exception {
        String intent = "intentof(nil, nil, 'com.uodis.opendevice.OPENIDS_SERVICE')";
        Varargs current = run("return bind(" + intent + ")");
        assertFalse(current.arg(1).toboolean());
        assertEquals("real", current.arg(2).tojstring());
        Varargs old = run("target = 19\nreturn bind(" + intent + ")");
        assertTrue(old.arg(1).toboolean());
        assertEquals("com.huawei.hwid:hidden", old.arg(4).tojstring());
        assertTrue(run("target = 20\nreturn bind(" + intent + ")").arg(1).toboolean());
        assertFalse(run("target = 21\nreturn bind(" + intent + ")").arg(1).toboolean());
    }

    // An intent with a selector, which Android refuses next to a package
    @Test
    public void intentWithSelector() throws Exception {
        Varargs result = run("local intent = intentof(nil, nil, 'com.uodis.opendevice.OPENIDS_SERVICE')\n" +
                "intent.selector = intentof(nil, 'com.example.app')\n" +
                "target = 19\n" +
                "return bind(intent)");
        assertTrue(result.arg(1).toboolean());
        assertEquals("com.huawei.hwid:hidden", result.arg(4).tojstring());
    }

    // Other services of the same packages stay, like the push service of ColorOS and the account of Honor
    @Test
    public void otherServicesStay() throws Exception {
        String[][] intents = {
                {"componentof('com.coloros.mcs', 'com.heytap.mcssdk.PushService')", "nil", "'com.coloros.mcs.action.RECEIVE_MCS_MESSAGE'"},
                {"nil", "'com.hihonor.id'", "'com.hihonor.id.ACCOUNT_SERVICE'"},
                {"nil", "'com.android.creator'", "'android.intent.action.MAIN'"},
                {"componentof('com.example.app', 'com.example.app.Service')", "nil", "nil"},
                {"nil", "nil", "'com.example.action.SERVICE'"}};
        for (String[] intent : intents) {
            Varargs result = run("return bind(intentof(" + String.join(", ", intent) + "))");
            assertFalse(String.join(" ", intent), result.arg(1).toboolean());
            assertEquals("real", result.arg(2).tojstring());
        }
        assertFalse(run("return bind(nil)").arg(1).toboolean());
    }

    // The identifier service bound by its class without the action, like with the class from resolveService, fails
    // like by the action: PackageManager tells, once per process, which classes of the package offer it; the other
    // classes stay
    @Test
    public void serviceByClassOfferingTheAction() throws Exception {
        String huawei = "com.huawei.hms.opendevice.OpenDeviceIdentifierService";
        String offer = "offered['com.hihonor.id/com.hihonor.id.HnOaIdService'] = { 'com.hihonor.id.OaIdService' }\n" +
                "offered['com.huawei.hwid/com.uodis.opendevice.OPENIDS_SERVICE'] = { 'com.example.Other', '" + huawei + "' }\n";
        String[] components = {"componentof('com.hihonor.id', 'com.hihonor.id.OaIdService')",
                "componentof('com.huawei.hwid', '" + huawei + "')"};
        for (String method : new String[]{"bindService", "bindIsolatedService"})
            for (String component : components) {
                Varargs result = run(offer + "return bind(intentof(" + component + ", nil, nil), '" + method + "')");
                assertTrue(method + " " + component, result.arg(1).toboolean());
                assertEquals(result.arg(3).tojstring() + ":hidden", result.arg(4).tojstring());
            }
        Varargs again = run(offer + "local first = bind(intentof(" + components[0] + ", nil, nil))\n" +
                "local second = bind(intentof(" + components[0] + ", nil, 'com.example.action'))\n" +
                "local other = bind(intentof(componentof('com.hihonor.id', 'com.hihonor.id.AccountService'), nil, nil))\n" +
                "return first, second, other, lookups");
        assertTrue(again.arg(1).toboolean());
        assertTrue(again.arg(2).toboolean());
        assertFalse(again.arg(3).toboolean());
        assertEquals(2, again.arg(4).toint());
        // A lookup which fails is not kept, so the next bind asks again
        Varargs failed = run("pm.queryIntentServices = function() lookups = lookups + 1; error('DeadSystemException') end\n" +
                "local first = bind(intentof(" + components[0] + ", nil, nil))\n" +
                "local second = bind(intentof(" + components[0] + ", nil, nil))\n" +
                "return first, second, lookups");
        assertFalse(failed.arg(1).toboolean());
        assertFalse(failed.arg(2).toboolean());
        assertEquals(2, failed.arg(3).toint());
    }

    // The connection to a service with the interface of an identifier service gets no binder, like a service giving
    // none, also of a package not known; other services and the services of the app itself stay
    @Test
    public void connectionsToIdentifierInterfacesGetNoBinder() throws Exception {
        String connect = String.join("\n",
                "asked = 0",
                "function connect(pkg, descriptor)",
                "  local service = { getInterfaceDescriptor = function(self)",
                "    asked = asked + 1",
                "    if descriptor == nil then error('DeadObjectException') end",
                "    return descriptor end }",
                "  local param = paramof(nil)",
                "  param.args = { componentof(pkg, pkg .. '.Service'), service, false }",
                "  param.getPackageName = function(self) return 'com.example.app' end",
                "  param.getArgument = function(self, i) return self.args[i + 1] end",
                "  param.setArgument = function(self, i, v) self.args[i + 1] = v end",
                "  local restricted, old = before(hookof('doConnected'), param)",
                "  return restricted, param.args[2] == service, old",
                "end",
                // Preinstalled (FLAG_SYSTEM), updated since (FLAG_UPDATED_SYSTEM_APP, with FLAG_MULTIARCH, which is
                // negative as an int) and of the user
                "flags['com.new.vendor'] = 0x1",
                "flags['com.google.android.gms'] = 0x80 + 0x8000 - 0x80000000",
                "flags['com.example.app'] = 0x1",
                "flags['com.example.user'] = 0x8000 - 0x80000000");
        for (String descriptor : new String[]{"com.bun.lib.MsaIdInterface", "com.android.creator.IdsSupplier",
                "com.samsung.android.deviceidservice.IDeviceIdService", "com.hihonor.cloudservice.oaid.IOAIDService",
                "com.uodis.opendevice.aidl.OpenDeviceIdentifierService", "com.oplus.stdid.IStdID"}) {
            Varargs result = run(connect + "\nreturn connect('com.new.vendor', '" + descriptor + "')");
            assertTrue(descriptor, result.arg(1).toboolean());
            assertFalse(descriptor, result.arg(2).toboolean());
            assertEquals(descriptor, result.arg(3).tojstring());
        }
        String[][] others = {{"com.new.vendor", "'com.bun.lib.MsaIdInterfaceV2'"},
                {"com.google.android.gms", "'com.google.android.gms.ads.identifier.internal.IAdvertisingIdService'"},
                {"com.example.app", "'com.bun.lib.MsaIdInterface'"}, {"com.new.vendor", "nil"}};
        for (String[] other : others) {
            Varargs result = run(connect + "\nreturn connect('" + other[0] + "', " + other[1] + ")");
            assertFalse(other[1], result.arg(1).toboolean());
            assertTrue(other[1], result.arg(2).toboolean());
        }
        // An updated system app is asked too, its flags once per process
        Varargs updated = run(connect + "\nlocal first = connect('com.google.android.gms', 'com.bun.lib.MsaIdInterface')\n" +
                "return first, connect('com.google.android.gms', 'com.bun.lib.MsaIdInterface'), infos");
        assertTrue(updated.arg(1).toboolean());
        assertTrue(updated.arg(2).toboolean());
        assertEquals(1, updated.arg(3).toint());
        // An app of the user, also of the same developer, gets no call asking for the interface, like on a device
        // without Doppel; nor a package whose lookup fails, which is asked again on the next connection
        for (String pkg : new String[]{"com.example.user", "com.example.missing"}) {
            Varargs result = run(connect + "\nlocal first = connect('" + pkg + "', 'com.bun.lib.MsaIdInterface')\n" +
                    "local second = connect('" + pkg + "', 'com.bun.lib.MsaIdInterface')\n" +
                    "return first, second, asked, infos");
            assertFalse(pkg, result.arg(1).toboolean());
            assertFalse(pkg, result.arg(2).toboolean());
            assertEquals(pkg, 0, result.arg(3).toint());
            assertEquals(pkg, pkg.endsWith("user") ? 1 : 2, result.arg(4).toint());
        }
    }

    // The identifier service of 360 OS from the ServiceManager is not found; the filter passes it only
    @Test
    public void systemServiceOfIdentifiersIsNotFound() throws Exception {
        for (String method : new String[]{"getService", "checkService"}) {
            Varargs result = run("local param = paramof('qikuid')\n" +
                    "local restricted, old = before(hookof('" + method + "'), param)\n" +
                    "return restricted, param.set and param.result, old, filter(hookof('" + method + "'))[1]");
            assertTrue(method, result.arg(1).toboolean());
            assertTrue(method, result.arg(2).isnil());
            assertEquals("qikuid", result.arg(3).tojstring());
            assertEquals("qikuid", result.arg(4).tojstring());
        }
        assertTrue(run("return filter(hookof('bindService')) == nil").arg1().toboolean());
    }

    // The provider of Nubia, by its Uri or its name, like ContentResolver.call(authority, ...), also by the hidden
    // methods giving the provider itself, of which query and insert get it
    @Test
    public void providersOfManufacturersAreNotFound() throws Exception {
        for (String method : new String[]{"acquireContentProviderClient", "acquireUnstableContentProviderClient",
                "acquireProvider", "acquireUnstableProvider", "call"}) {
            Varargs uri = run("return acquire(uriof('cn.nubia.identity'), '" + method + "')");
            assertTrue(method, uri.arg(1).toboolean());
            if (method.equals("call"))
                assertTrue(uri.arg(2).tojstring(), uri.arg(2).tojstring().startsWith("Unknown URI "));
            else
                assertTrue(uri.arg(2).isnil());
            assertEquals("cn.nubia.identity", uri.arg(3).tojstring());
            Varargs name = run("return acquire('cn.nubia.identity', '" + method + "')");
            assertTrue(method, name.arg(1).toboolean());
            // Android throws for a provider which does not exist, only the clients are null
            if (method.equals("call"))
                assertEquals("Unknown authority cn.nubia.identity", name.arg(2).tojstring());
            assertFalse(method, run("return acquire(uriof('com.android.contacts'), '" + method + "')").arg(1).toboolean());
            assertFalse(method, run("return acquire(nil, '" + method + "')").arg(1).toboolean());
        }
        // A call without a method goes to Android, which throws NullPointerException("method") before the lookup
        for (String target : new String[]{"uriof('cn.nubia.identity')", "'cn.nubia.identity'"}) {
            Varargs result = run("local target = " + target + "\n" +
                    "local param = paramof(target)\n" +
                    "param.getArgument = function(self, i) return (i == 0 and target or nil) end\n" +
                    "return before(hookof('call'), param), param.set");
            assertFalse(target, result.arg(1).toboolean());
            assertFalse(target, result.arg(2).toboolean());
        }
        // ActivityThread.acquireProvider(context, authority, user, stable), under the protected overloads with a context,
        // and IActivityManager.getContentProvider(thread, authority, user, stable) under that on Android 8.1 and 9:
        // the authority is the second argument; the context or thread, which has no authority, would fail as the first
        String[][] hooks = {{"acquireProvider", "android.app.ActivityThread"},
                {"getContentProvider", "android.app.IActivityManager$Stub$Proxy"}};
        for (String[] hook : hooks)
            for (String authority : new String[]{"cn.nubia.identity", "com.android.contacts"}) {
                Varargs result = run("local param = paramof(nil)\n" +
                        "param.getArgument = function(self, i) return (i == 1 and '" + authority + "' or {}) end\n" +
                        "return before(hookof('" + hook[0] + "', '" + hook[1] + "'), param), param.set, param.result");
                assertEquals(hook[0] + " " + authority, authority.startsWith("cn."), result.arg(1).toboolean());
                assertEquals(hook[0] + " " + authority, authority.startsWith("cn."), result.arg(2).toboolean());
                if (authority.startsWith("cn."))
                    assertTrue(result.arg(3).isnil());
            }
    }

    // The identifier service of Honor, which offers the action, and a sibling of the same package which does not
    private static final String HONOR = "offered['com.hihonor.id/com.hihonor.id.HnOaIdService'] = { 'com.hihonor.id.OaIdService' }\n";

    private static ServiceInfo service(String pkg, String cls) {
        ServiceInfo info = new ServiceInfo();
        info.packageName = pkg;
        info.name = cls;
        return info;
    }

    private static ResolveInfo resolved(String pkg, String cls) {
        ResolveInfo info = new ResolveInfo();
        info.serviceInfo = service(pkg, cls);
        return info;
    }

    private static ProviderInfo provider(String authority) {
        ProviderInfo info = new ProviderInfo();
        info.authority = authority;
        return info;
    }

    private static Globals globals(Object... values) throws Exception {
        Globals globals = Scripts.load("identifier_services", STUBS);
        for (int i = 0; i < values.length; i += 2)
            globals.set((String) values[i], CoerceJavaToLua.coerce(values[i + 1]));
        return globals;
    }

    // Lists of services leave out the identifier services, by their class or because they offer the action, also for a
    // user; their siblings, the services of the rows of whole packages and of other apps stay, and a list left as it was
    // reports nothing
    @Test
    public void serviceListsLeaveOutIdentifierServices() throws Exception {
        for (String method : new String[]{"queryIntentServices", "queryIntentServicesAsUser"}) {
            List<ResolveInfo> list = new ArrayList<>(Arrays.asList(resolved("com.hihonor.id", "com.hihonor.id.OaIdService"),
                    resolved("com.hihonor.id", "com.hihonor.id.AccountService"),
                    resolved("com.coloros.mcs", "com.oplus.stdid.IdentifyService"),
                    resolved("com.coloros.mcs", "com.heytap.mcssdk.PushService"),
                    resolved("com.samsung.android.deviceidservice", "com.samsung.android.deviceidservice.DeviceIdService"),
                    resolved("com.example.app", "com.example.app.Service")));
            List<ResolveInfo> kept = new ArrayList<>(Arrays.asList(list.get(1), list.get(3), list.get(4), list.get(5)));
            Globals globals = globals("list", list);
            Varargs result = Scripts.run(globals, HONOR + "return after(lookup('" + method + "', list))");
            assertTrue(method, result.arg(1).toboolean());
            assertEquals(kept, list);
            assertFalse(method, Scripts.run(globals, "return after(lookup('" + method + "', list))").arg1().toboolean());
            assertEquals(kept, list);
        }
    }

    // A lookup of a service resolving to an identifier service resolves to the next service of the same query, like
    // Android, which takes the first, or to none; other services stay
    @Test
    public void serviceLookupsSkipIdentifierServices() throws Exception {
        ResolveInfo honor = resolved("com.hihonor.id", "com.hihonor.id.OaIdService");
        ResolveInfo other = resolved("com.example.app", "com.example.app.Service");
        String pm = "apppm = { queryIntentServices = function(self, intent, flags)\n" +
                "  assert(intent == 'intent' and flags == 0, flags) return query end,\n" +
                "  queryIntentServicesAsUser = function(self, intent, flags, user)\n" +
                "  assert(intent == 'intent' and flags == 0 and user == 10, user) return query end }\n";
        for (String method : new String[]{"resolveService", "resolveServiceAsUser"})
            for (List<ResolveInfo> query : Arrays.asList(Arrays.asList(honor, other), Arrays.asList(honor))) {
                Globals globals = globals("honor", honor, "query", query);
                Varargs result = Scripts.run(globals, HONOR + pm +
                        "local hook, param = lookup('" + method + "', honor, 'intent', 0, 10)\n" +
                        "param.this = apppm\n" +
                        "local restricted, old = after(hook, param)\n" +
                        "return restricted, param.result, old");
                assertTrue(method, result.arg(1).toboolean());
                if (query.size() > 1)
                    assertSame(other, result.arg(2).touserdata());
                else
                    assertTrue(result.arg(2).isnil());
                assertEquals("com.hihonor.id", result.arg(3).tojstring());
            }
        Globals globals = globals("other", other);
        assertFalse(Scripts.run(globals, HONOR + "return after(lookup('resolveService', other, 'intent', 0))").arg1().toboolean());
        assertFalse(Scripts.run(globals, "return after(lookup('resolveService', nil, 'intent', 0))").arg1().toboolean());
    }

    // Thrown for the class which no app has, like Android throws NameNotFoundException
    public static class Missing extends Exception {
        public Missing(String message) {
            super(message);
        }
    }

    // getServiceInfo of an identifier service looks up a class no app has, so Android throws itself, and the exception
    // gets the name the app asked for, with the same class and stack trace; the lookup by the overload of Android 13
    // and later which the other one calls, of the class which no app has, stays
    @Test
    public void serviceInfoOfIdentifierServiceIsNotFound() throws Exception {
        String substitute = "com.hihonor.id.OaIdService.x123456789";
        Varargs before = Scripts.run(globals(), HONOR +
                "local hook, param = lookup('getServiceInfo', nil, componentof('com.hihonor.id', 'com.hihonor.id.OaIdService'), 0)\n" +
                "local restricted, old = before(hook, param)\n" +
                "local _, inner = lookup('getServiceInfo', nil, param.args[1], 0)\n" +
                "local _, sibling = lookup('getServiceInfo', nil, componentof('com.hihonor.id', 'com.hihonor.id.AccountService'), 0)\n" +
                "return restricted, old, param.args[1]:getPackageName(), param.args[1]:getClassName(), before(hook, inner)," +
                "  before(hook, sibling), sibling.args[1]:getClassName()");
        assertTrue(before.arg(1).toboolean());
        assertEquals("com.hihonor.id", before.arg(2).tojstring());
        assertEquals("com.hihonor.id", before.arg(3).tojstring());
        assertEquals(substitute, before.arg(4).tojstring());
        assertFalse(before.arg(5).toboolean());
        assertFalse(before.arg(6).toboolean());
        assertEquals("com.hihonor.id.AccountService", before.arg(7).tojstring());

        Missing thrown = new Missing("ComponentInfo{com.hihonor.id/" + substitute + "}");
        Globals globals = globals("thrown", thrown);
        Varargs after = Scripts.run(globals, "local hook, param = lookup('getServiceInfo', nil, componentof('com.hihonor.id', '" +
                substitute + "'), 0)\n" +
                "param.ex = thrown\n" +
                "return after(hook, param), param.result");
        assertFalse(after.arg(1).toboolean());
        Missing fake = (Missing) after.arg(2).touserdata();
        assertEquals("ComponentInfo{com.hihonor.id/com.hihonor.id.OaIdService}", fake.getMessage());
        assertArrayEquals(thrown.getStackTrace(), fake.getStackTrace());
        // Already renamed, by the overload it called, and of a class the app asked for itself
        globals.set("thrown", CoerceJavaToLua.coerce(fake));
        assertFalse(Scripts.run(globals, "local hook, param = lookup('getServiceInfo', nil, componentof('com.hihonor.id', '" +
                substitute + "'), 0)\n" +
                "param.ex = thrown\n" +
                "after(hook, param)\n" +
                "return param.set").arg1().toboolean());
        assertFalse(Scripts.run(globals, "local hook, param = lookup('getServiceInfo', nil, componentof('com.hihonor.id', " +
                "'com.hihonor.id.Other'), 0)\n" +
                "param.ex = thrown\n" +
                "after(hook, param)\n" +
                "return param.set").arg1().toboolean());
        // The other methods of PackageManager are not answered before Android, like getPackageInfo of a name of a provider
        Varargs other = Scripts.run(globals(), "local hook, param = lookup('getPackageInfo', nil, 'cn.nubia.identity', 0)\n" +
                "return before(hook, param), param.set");
        assertFalse(other.arg(1).toboolean());
        assertFalse(other.arg(2).toboolean());
    }

    // A package leaves out the identifier services and the provider from its arrays of services and providers, null
    // when none is left, like Android answers for a package without one, also in the list of installed packages
    @Test
    public void packagesLeaveOutIdentifierServicesAndProvider() throws Exception {
        ServiceInfo account = service("com.hihonor.id", "com.hihonor.id.AccountService");
        ProviderInfo contacts = provider("com.example.contacts");
        PackageInfo honor = new PackageInfo();
        honor.packageName = "com.hihonor.id";
        honor.services = new ServiceInfo[]{service("com.hihonor.id", "com.hihonor.id.OaIdService"), account};
        honor.providers = new ProviderInfo[]{provider("com.example.first;cn.nubia.identity"), contacts};
        PackageInfo nubia = new PackageInfo();
        nubia.packageName = "cn.nubia.identity";
        nubia.providers = new ProviderInfo[]{provider("cn.nubia.identity")};
        PackageInfo samsung = new PackageInfo();
        samsung.packageName = "com.samsung.android.deviceidservice";
        ServiceInfo[] samsungServices = {service(samsung.packageName, "com.samsung.android.deviceidservice.DeviceIdService")};
        samsung.services = samsungServices;
        PackageInfo none = new PackageInfo();
        none.packageName = "com.hihonor.id";

        for (String method : new String[]{"getPackageInfo", "getPackageInfoAsUser"}) {
            honor.services = new ServiceInfo[]{service("com.hihonor.id", "com.hihonor.id.OaIdService"), account};
            honor.providers = new ProviderInfo[]{provider("com.example.first;cn.nubia.identity"), contacts};
            nubia.providers = new ProviderInfo[]{provider("cn.nubia.identity")};
            Globals globals = globals("honor", honor, "nubia", nubia, "samsung", samsung, "none", none);
            Varargs result = Scripts.run(globals, HONOR + "local first, old = after(lookup('" + method + "', honor))\n" +
                    "return first, old, after(lookup('" + method + "', honor)), after(lookup('" + method + "', nubia))," +
                    "  after(lookup('" + method + "', samsung)), after(lookup('" + method + "', none))");
            assertTrue(method, result.arg(1).toboolean());
            assertEquals("com.hihonor.id", result.arg(2).tojstring());
            assertArrayEquals(new ServiceInfo[]{account}, honor.services);
            assertArrayEquals(new ProviderInfo[]{contacts}, honor.providers);
            assertFalse(result.arg(3).toboolean());
            assertTrue(result.arg(4).toboolean());
            assertNull(nubia.providers);
            assertFalse(result.arg(5).toboolean());
            assertSame(samsungServices, samsung.services);
            assertFalse(result.arg(6).toboolean());
            assertNull(none.services);
        }

        for (String method : new String[]{"getInstalledPackages", "getInstalledPackagesAsUser"}) {
            honor.services = new ServiceInfo[]{service("com.hihonor.id", "com.hihonor.id.OaIdService")};
            honor.providers = null;
            ServiceInfo[] kept = samsung.services;
            List<PackageInfo> list = new ArrayList<>(Arrays.asList(samsung, honor, none));
            Globals globals = globals("list", list);
            Varargs result = Scripts.run(globals, HONOR + "return after(lookup('" + method + "', list))");
            assertTrue(method, result.arg(1).toboolean());
            assertEquals("com.hihonor.id", result.arg(2).tojstring());
            assertNull(honor.services);
            assertSame(kept, samsung.services);
            assertEquals(3, list.size());
            assertFalse(Scripts.run(globals, "return after(lookup('" + method + "', list))").arg1().toboolean());
        }
    }

    // The provider of Nubia is not found by its name, also with other authorities, nor for a user; Android looks the
    // name up as it is, so the checks it makes first, like of the user, stay
    @Test
    public void providerLookupFindsNoIdentifierProvider() throws Exception {
        for (String method : new String[]{"resolveContentProvider", "resolveContentProviderAsUser"}) {
            for (String authority : new String[]{"cn.nubia.identity", "com.example.first;cn.nubia.identity"}) {
                Globals globals = globals("info", provider(authority));
                Varargs result = Scripts.run(globals, "local hook, param = lookup('" + method + "', info, '" + authority + "', 0)\n" +
                        "local before = before(hook, param)\n" +
                        "local restricted, old = after(hook, param)\n" +
                        "return before, restricted, param.set, param.result, old");
                assertFalse(result.arg(1).toboolean());
                assertTrue(method + " " + authority, result.arg(2).toboolean());
                assertTrue(result.arg(3).toboolean());
                assertTrue(result.arg(4).isnil());
                assertEquals(authority, result.arg(5).tojstring());
            }
            ProviderInfo contacts = provider("com.android.contacts");
            Globals globals = globals("info", contacts);
            Varargs result = Scripts.run(globals, "local hook, param = lookup('" + method + "', info, 'com.android.contacts', 0)\n" +
                    "return after(hook, param), param.result");
            assertFalse(result.arg(1).toboolean());
            assertSame(contacts, result.arg(2).touserdata());
            assertFalse(Scripts.run(globals, "return after(lookup('" + method + "', nil, 'cn.nubia.identity', 0))").arg1().toboolean());
        }
        // The hooks of the services and the provider which are not of PackageManager have nothing to do after Android
        assertFalse(Scripts.run(globals(), "return after(hookof('bindService'), paramof(nil))").arg1().toboolean());
    }
}
