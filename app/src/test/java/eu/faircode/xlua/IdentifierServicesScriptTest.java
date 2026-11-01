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

// The identifier services of manufacturers (identifier_services): the OAID SDKs bind them or ask their provider
public class IdentifierServicesScriptTest {
    private static final String STUBS = String.join("\n",
            "function componentof(pkg, cls)",
            "  return { getPackageName = function(self) return pkg end, getClassName = function(self) return cls end }",
            "end",
            "function intentof(component, pkg, action)",
            "  return { getComponent = function(self) return component end,",
            "    getPackage = function(self) return pkg end, getAction = function(self) return action end }",
            "end",
            "function uriof(authority)",
            "  return { getAuthority = function(self) return authority end }",
            "end",
            // The Android version the app targets, like ApplicationInfo.targetSdkVersion
            "target = 34",
            "function paramof(arg)",
            "  return { result = 'real', set = false,",
            "    getApplicationContext = function(self)",
            "      return { getApplicationInfo = function(self) return { targetSdkVersion = target } end }",
            "    end,",
            "    getArgument = function(self, i) return arg end,",
            "    setResult = function(self, v) self.result = v; self.set = true end }",
            "end",
            "function hookof(method)",
            "  return { getMethodName = function(self) return method end }",
            "end",
            "function bind(intent, method)",
            "  local param = paramof(intent)",
            "  local restricted, old = before(hookof(method or 'bindService'), param)",
            "  return restricted, param.result, old",
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

    // Each way the OAID SDKs bind the services, like gzu-liyujiang/Android_CN_OAID and the SDKs of Huawei and Honor
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
                assertFalse(result.arg(2).toboolean());
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
        assertFalse(old.arg(2).toboolean());
        assertTrue(run("target = 20\nreturn bind(" + intent + ")").arg(1).toboolean());
        assertFalse(run("target = 21\nreturn bind(" + intent + ")").arg(1).toboolean());
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

    // The connection to a service with the interface of an identifier service gets no binder, like a service giving
    // none, also of a package not known; other services and the services of the app itself stay
    @Test
    public void connectionsToIdentifierInterfacesGetNoBinder() throws Exception {
        String connect = String.join("\n",
                "function connect(pkg, descriptor)",
                "  local service = { getInterfaceDescriptor = function(self)",
                "    if descriptor == nil then error('DeadObjectException') end",
                "    return descriptor end }",
                "  local param = { args = { componentof(pkg, pkg .. '.Service'), service, false },",
                "    getPackageName = function(self) return 'com.example.app' end,",
                "    getArgument = function(self, i) return self.args[i + 1] end,",
                "    setArgument = function(self, i, v) self.args[i + 1] = v end }",
                "  local restricted, old = before(hookof('doConnected'), param)",
                "  return restricted, param.args[2] == service, old",
                "end");
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

    // The provider of Nubia, by its Uri or its name, like ContentResolver.call(authority, ...)
    @Test
    public void providersOfManufacturersAreNotFound() throws Exception {
        for (String method : new String[]{"acquireContentProviderClient", "acquireUnstableContentProviderClient", "call"}) {
            Varargs uri = run("return acquire(uriof('cn.nubia.identity'), '" + method + "')");
            assertTrue(method, uri.arg(1).toboolean());
            assertTrue(uri.arg(2).isnil());
            assertEquals("cn.nubia.identity", uri.arg(3).tojstring());
            assertTrue(method, run("return acquire('cn.nubia.identity', '" + method + "')").arg(1).toboolean());
            assertFalse(method, run("return acquire(uriof('com.android.contacts'), '" + method + "')").arg(1).toboolean());
            assertFalse(method, run("return acquire(nil, '" + method + "')").arg(1).toboolean());
        }
    }
}
