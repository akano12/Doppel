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

import org.junit.Before;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The script of received intents (assets/intent_createfromparcel.lua), like broadcasts, with Lua stand-ins
// for the intent and its extras, like LocationScriptTest; arrays are stand-ins, because their types are of
// java.lang (String, Integer.TYPE), which luajava cannot read on a desktop JVM, and the tests read them back as tables
public class IntentScriptTest {
    private static final String STUBS = String.join("\n",
            "function hookof(name)",
            "  return { getName = function(self) return name end }",
            "end",
            // The values scripts keep per scope, like XParam.putValue, which a test can share with another script
            "context = {}",
            "store = {}",
            // The packages which stay visible besides the app itself, and whether the process is of the system;
            // the result is the intent, or another value, which setResult replaces
            "function paramof(intent, visible, system, settings)",
            "  return { getResult = function(self) return intent end,",
            "    setResult = function(self, value) self.replaced = { value = value } end,",
            "    getApplicationContext = function(self) return context end,",
            "    putValue = function(self, name, value, scope) store[scope] = store[scope] or {} store[scope][name] = value end,",
            "    getValue = function(self, name, scope) return store[scope] and store[scope][name] end,",
            "    getPackageName = function(self) return 'com.example.app' end,",
            "    getUid = function(self) return 10001 end,",
            "    isPackageVisible = function(self, name)",
            "      return name == 'com.example.app' or (visible ~= nil and visible[name] == true) end,",
            "    isSystem = function(self) return system == true end,",
            "    getSetting = function(self, name) return settings and settings[name] end }",
            "end",
            // An intent with a copy of its extras, like Intent.getExtras, and the package of its data
            "function intentof(action, extras, package)",
            "  local intent = { action = action, extras = extras }",
            "  if package ~= nil then",
            "    intent.data = { getScheme = function(self) return 'package' end,",
            "      getSchemeSpecificPart = function(self) return package end }",
            "  end",
            "  function intent:getAction() return self.action end",
            "  function intent:getData() return self.data end",
            "  function intent:setData(uri) self.data = uri end",
            "  function intent:putExtra(name, value) self.extras[name] = value end",
            "  function intent:getStringExtra(name) return self.extras[name] end",
            "  function intent:replaceExtras(bundle) self.extras = bundle.values end",
            "  function intent:getExtras()",
            "    if self.extras == nil then return nil end",
            "    local bundle = { values = {} }",
            "    for key, value in pairs(self.extras) do bundle.values[key] = value end",
            "    function bundle:containsKey(key) return self.values[key] ~= nil end",
            "    function bundle:remove(key) self.values[key] = nil end",
            "    function bundle:getInt(key, default) return self.values[key] or default end",
            "    function bundle:getString(key) return self.values[key] end",
            "    function bundle:getBoolean(key, default) local v = self.values[key] if v == nil then return default end return v end",
            "    bundle.getStringArray = bundle.getString",
            "    bundle.getIntArray = bundle.getString",
            "    function bundle:put(key, value) self.values[key] = value end",
            "    bundle.putInt = bundle.put",
            "    bundle.putString = bundle.put",
            "    bundle.putBoolean = bundle.put",
            "    bundle.putStringArray = bundle.put",
            "    bundle.putIntArray = bundle.put",
            "    bundle.putByteArray = bundle.put",
            "    return bundle",
            "  end",
            "  return intent",
            "end",
            "luajava = { bindClass = function(name)",
            "  if name == 'android.net.Uri' then return { parse = function(self, text) return text end } end",
            "  if name == 'java.lang.reflect.Array' then",
            "    return { newInstance = function(self, type, length) return { type = type, length = length } end }",
            "  end",
            "  if name == 'java.lang.Integer' then return { TYPE = 'int' } end",
            "  if name == 'java.lang.Byte' then return { TYPE = 'byte' } end",
            // The texts of Android without service, by their names in the resources of the system
            "  if name == 'android.content.res.Resources' then",
            "    local ids = { lockscreen_carrier_default = 1, emergency_calls_only = 2 }",
            "    local texts = { 'No service', 'Emergency calls only' }",
            "    return { getSystem = function(self) return {",
            "      getIdentifier = function(self, name, type, package) return package == 'android' and ids[name] or 0 end,",
            "      getString = function(self, id) return texts[id] end } end }",
            "  end",
            "  return { name = name }",
            "end }");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("intent_createfromparcel", STUBS);
    }

    private Varargs run(String lua) {
        return Scripts.run(globals, lua);
    }

    // The changed components of a hidden app are the package of the app itself, like when all of it changed
    @Test
    public void changedPackageIsOwnPackage() {
        Varargs result = run(
                "local intent = intentof('android.intent.action.PACKAGE_CHANGED', {\n" +
                        "  ['android.intent.extra.UID'] = 10060,\n" +
                        "  ['android.intent.extra.changed_component_name'] = 'com.example.bank',\n" +
                        "  ['android.intent.extra.changed_component_name_list'] = { length = 1, 'com.example.bank' } },\n" +
                        "  'com.example.bank')\n" +
                        "local restricted = after(hookof('Intent.createFromParcel/package'), paramof(intent))\n" +
                        "local list = intent.extras['android.intent.extra.changed_component_name_list']\n" +
                        "return restricted, intent.data, intent.extras['android.intent.extra.UID'],\n" +
                        "  intent.extras['android.intent.extra.changed_component_name'], list.length, list[1]");
        assertTrue(result.arg(1).toboolean());
        assertEquals("package:com.example.app", result.arg(2).tojstring());
        assertEquals(10001, result.arg(3).toint());
        assertEquals("com.example.app", result.arg(4).tojstring());
        assertEquals(1, result.arg(5).toint());
        assertEquals("com.example.app", result.arg(6).tojstring());
    }

    // Broadcasts about the app itself, like about components it changed, and about system apps stay
    @Test
    public void visiblePackagesStay() {
        Varargs own = run(
                "local intent = intentof('android.intent.action.PACKAGE_CHANGED', {\n" +
                        "  ['android.intent.extra.UID'] = 10001,\n" +
                        "  ['android.intent.extra.changed_component_name'] = 'com.example.app.Alias',\n" +
                        "  ['android.intent.extra.changed_component_name_list'] = { length = 1, 'com.example.app.Alias' } },\n" +
                        "  'com.example.app')\n" +
                        "local data = intent.data\n" +
                        "local restricted = after(hookof('Intent.createFromParcel/package'), paramof(intent))\n" +
                        "local list = intent.extras['android.intent.extra.changed_component_name_list']\n" +
                        "return restricted, intent.data == data, intent.extras['android.intent.extra.changed_component_name'],\n" +
                        "  list.length, list[1]");
        assertFalse(own.arg(1).toboolean());
        assertTrue(own.arg(2).toboolean());
        assertEquals("com.example.app.Alias", own.arg(3).tojstring());
        assertEquals(1, own.arg(4).toint());
        assertEquals("com.example.app.Alias", own.arg(5).tojstring());

        Varargs system = run(
                "local intent = intentof('android.intent.action.PACKAGE_REPLACED',\n" +
                        "  { ['android.intent.extra.UID'] = 10050 }, 'com.google.android.webview')\n" +
                        "local param = paramof(intent, { ['com.google.android.webview'] = true })\n" +
                        "return after(hookof('Intent.createFromParcel/package'), param),\n" +
                        "  intent.data:getSchemeSpecificPart(), intent.extras['android.intent.extra.UID']");
        assertFalse(system.arg(1).toboolean());
        assertEquals("com.google.android.webview", system.arg(2).tojstring());
        assertEquals(10050, system.arg(3).toint());
    }

    // Processes of the system get the broadcasts about packages as they are, the other variants apply
    @Test
    public void systemProcessesGetPackages() {
        Varargs added = run(
                "local intent = intentof('android.intent.action.PACKAGE_ADDED',\n" +
                        "  { ['android.intent.extra.UID'] = 10060 }, 'com.example.bank')\n" +
                        "return after(hookof('Intent.createFromParcel/package'), paramof(intent, nil, true)),\n" +
                        "  intent.data:getSchemeSpecificPart(), intent.extras['android.intent.extra.UID']");
        assertFalse(added.arg(1).toboolean());
        assertEquals("com.example.bank", added.arg(2).tojstring());
        assertEquals(10060, added.arg(3).toint());

        Varargs suspended = run(
                "local intent = intentof('android.intent.action.PACKAGES_SUSPENDED', {\n" +
                        "  ['android.intent.extra.changed_package_list'] = { length = 1, 'com.example.bank' } })\n" +
                        "return after(hookof('Intent.createFromParcel/package'), paramof(intent, nil, true)),\n" +
                        "  intent.extras['android.intent.extra.changed_package_list'].length");
        assertFalse(suspended.arg(1).toboolean());
        assertEquals(1, suspended.arg(2).toint());

        Varargs usb = run(
                "local intent = intentof('android.hardware.usb.action.USB_STATE', { adb = true })\n" +
                        "return after(hookof('Intent.createFromParcel/debugging'), paramof(intent, nil, true))");
        assertTrue(usb.arg(1).toboolean());
    }

    // Android 15 tells registered receivers which app is started after it was stopped
    @Test
    public void unstoppedPackageIsOwnPackage() {
        Varargs result = run(
                "local intent = intentof('android.intent.action.PACKAGE_UNSTOPPED', { ['android.intent.extra.UID'] = 10060 })\n" +
                        "return after(hookof('Intent.createFromParcel/package'), paramof(intent)), intent.data,\n" +
                        "  intent.extras['android.intent.extra.UID']");
        assertTrue(result.arg(1).toboolean());
        assertEquals("package:com.example.app", result.arg(2).tojstring());
        assertEquals(10001, result.arg(3).toint());
    }

    // Settings asks receivers whether a package may be restarted, by its data and its list of packages
    @Test
    public void restartQueryIsOfOwnPackage() {
        Varargs result = run(
                "local intent = intentof('android.intent.action.QUERY_PACKAGE_RESTART', {\n" +
                        "  ['android.intent.extra.PACKAGES'] = { length = 1, 'com.example.bank' },\n" +
                        "  ['android.intent.extra.UID'] = 10060 }, 'com.example.bank')\n" +
                        "local restricted = after(hookof('Intent.createFromParcel/package'), paramof(intent))\n" +
                        "local packages = intent.extras['android.intent.extra.PACKAGES']\n" +
                        "return restricted, intent.data, packages.length, packages[1], intent.extras['android.intent.extra.UID']");
        assertTrue(result.arg(1).toboolean());
        assertEquals("package:com.example.app", result.arg(2).tojstring());
        assertEquals(1, result.arg(3).toint());
        assertEquals("com.example.app", result.arg(4).tojstring());
        assertEquals(10001, result.arg(5).toint());
    }

    @Test
    public void suspendedPackagesWithoutUids() {
        Varargs result = run(
                "local intent = intentof('android.intent.action.PACKAGES_SUSPENDED', {\n" +
                        "  ['android.intent.extra.changed_package_list'] = { length = 1, 'com.example.bank' },\n" +
                        "  ['android.intent.extra.changed_uid_list'] = { length = 1, 10060 } })\n" +
                        "local restricted = after(hookof('Intent.createFromParcel/package'), paramof(intent))\n" +
                        "local uids = intent.extras['android.intent.extra.changed_uid_list']\n" +
                        "return restricted, intent.extras['android.intent.extra.changed_package_list'].length,\n" +
                        "  uids.length, uids.type");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).toint());
        assertEquals(0, result.arg(3).toint());
        assertEquals("int", result.arg(4).tojstring());
    }

    // The visible packages of a list stay with their user ids, like the app itself
    @Test
    public void suspendedPackagesKeepVisibleOnes() {
        Varargs result = run(
                "local intent = intentof('android.intent.action.PACKAGES_SUSPENDED', {\n" +
                        "  ['android.intent.extra.changed_package_list'] = { length = 2, 'com.example.app', 'com.example.bank' },\n" +
                        "  ['android.intent.extra.changed_uid_list'] = { length = 2, 10001, 10060 } })\n" +
                        "local restricted = after(hookof('Intent.createFromParcel/package'), paramof(intent))\n" +
                        "local packages = intent.extras['android.intent.extra.changed_package_list']\n" +
                        "local uids = intent.extras['android.intent.extra.changed_uid_list']\n" +
                        "return restricted, packages.length, packages[1], uids.length, uids[1]");
        assertTrue(result.arg(1).toboolean());
        assertEquals(1, result.arg(2).toint());
        assertEquals("com.example.app", result.arg(3).tojstring());
        assertEquals(1, result.arg(4).toint());
        assertEquals(10001, result.arg(5).toint());
        // Only visible packages: nothing changes
        Varargs same = run(
                "local intent = intentof('android.intent.action.PACKAGES_UNSUSPENDED', {\n" +
                        "  ['android.intent.extra.changed_package_list'] = { length = 1, 'com.example.app' } })\n" +
                        "return after(hookof('Intent.createFromParcel/package'), paramof(intent))");
        assertFalse(same.arg(1).toboolean());
    }

    // With USB debugging off the USB state has the function mtp for charging only
    @Test
    public void usbStateWithoutDebugging() {
        Varargs charging = run(
                "local intent = intentof('android.hardware.usb.action.USB_STATE', { connected = true, adb = true })\n" +
                        "return after(hookof('Intent.createFromParcel/debugging'), paramof(intent)),\n" +
                        "  intent.extras.adb, intent.extras.mtp, intent.extras.connected");
        assertTrue(charging.arg(1).toboolean());
        assertTrue(charging.arg(2).isnil());
        assertTrue(charging.arg(3).toboolean());
        assertTrue(charging.arg(4).toboolean());

        Varargs ptp = run(
                "local intent = intentof('android.hardware.usb.action.USB_STATE', { ptp = true, adb = true })\n" +
                        "after(hookof('Intent.createFromParcel/debugging'), paramof(intent))\n" +
                        "return intent.extras.adb, intent.extras.mtp, intent.extras.ptp");
        assertTrue(ptp.arg(1).isnil());
        assertTrue(ptp.arg(2).isnil());
        assertTrue(ptp.arg(3).toboolean());

        Varargs off = run(
                "local intent = intentof('android.hardware.usb.action.USB_STATE', { mtp = true })\n" +
                        "return after(hookof('Intent.createFromParcel/debugging'), paramof(intent))");
        assertFalse(off.arg(1).toboolean());

        // The variant of the battery does not apply
        Varargs battery = run(
                "local intent = intentof('android.hardware.usb.action.USB_STATE', { adb = true })\n" +
                        "return after(hookof('Intent.createFromParcel/battery'), paramof(intent)), intent.extras.adb");
        assertFalse(battery.arg(1).toboolean());
        assertTrue(battery.arg(2).toboolean());
    }

    // Broadcasts of Bluetooth name the devices around, which the hooks of Bluetooth hide; on Android 8.1 the broadcasts
    // of the Wi-Fi state and the connectivity have the access point (bssid) and the network name (extraInfo)
    @Test
    public void networkExtrasAreLeftOut() {
        String device = "['android.bluetooth.device.extra.DEVICE']";
        Varargs bond = run(
                "local intent = intentof('android.bluetooth.device.action.BOND_STATE_CHANGED', { " + device + " = 'AA:BB',\n" +
                        "  ['android.bluetooth.device.extra.BOND_STATE'] = 12 })\n" +
                        "return after(hookof('Intent.createFromParcel/network'), paramof(intent)),\n" +
                        "  intent.extras" + device + ", intent.extras['android.bluetooth.device.extra.BOND_STATE']");
        assertTrue(bond.arg(1).toboolean());
        assertTrue(bond.arg(2).isnil());
        assertEquals(12, bond.arg(3).toint());
        Varargs found = run(
                "local intent = intentof('android.bluetooth.device.action.FOUND', { " + device + " = 'AA:BB',\n" +
                        "  ['android.bluetooth.device.extra.NAME'] = 'Headphones of Alice' })\n" +
                        "after(hookof('Intent.createFromParcel/network'), paramof(intent))\n" +
                        "return intent.extras" + device + ", intent.extras['android.bluetooth.device.extra.NAME']");
        assertTrue(found.arg(1).isnil());
        assertTrue(found.arg(2).isnil());
        // The name of the device, which BluetoothAdapter.getName hides
        Varargs name = run(
                "local intent = intentof('android.bluetooth.adapter.action.LOCAL_NAME_CHANGED',\n" +
                        "  { ['android.bluetooth.adapter.extra.LOCAL_NAME'] = 'Phone of Alice' })\n" +
                        "after(hookof('Intent.createFromParcel/network'), paramof(intent))\n" +
                        "return intent.extras['android.bluetooth.adapter.extra.LOCAL_NAME']");
        assertTrue(name.arg1().isnil());
        Varargs wifi = run(
                "local state = intentof('android.net.wifi.STATE_CHANGE', { bssid = '02:11:22:33:44:55', networkInfo = 'info' })\n" +
                        "local connectivity = intentof('android.net.conn.CONNECTIVITY_CHANGE', { extraInfo = 'Home', networkType = 1 })\n" +
                        "return after(hookof('Intent.createFromParcel/network'), paramof(state)), state.extras.bssid,\n" +
                        "  state.extras.networkInfo, after(hookof('Intent.createFromParcel/network'), paramof(connectivity)),\n" +
                        "  connectivity.extras.extraInfo, connectivity.extras.networkType");
        assertTrue(wifi.arg(1).toboolean());
        assertTrue(wifi.arg(2).isnil());
        assertEquals("info", wifi.arg(3).tojstring());
        assertTrue(wifi.arg(4).toboolean());
        assertTrue(wifi.arg(5).isnil());
        assertEquals(1, wifi.arg(6).toint());
        // The saved network that changed, like the broadcast of Android when several networks changed
        Varargs saved = run(
                "local changed = intentof('android.net.wifi.CONFIGURED_NETWORKS_CHANGE', { wifiConfiguration = 'Home',\n" +
                        "  multipleChanges = false, changeReason = 2 })\n" +
                        "return after(hookof('Intent.createFromParcel/network'), paramof(changed)), changed.extras.wifiConfiguration,\n" +
                        "  changed.extras.multipleChanges, changed.extras.changeReason");
        assertTrue(saved.arg(1).toboolean());
        assertTrue(saved.arg(2).isnil());
        assertTrue(saved.arg(3).toboolean());
        assertEquals(2, saved.arg(4).toint());
        // Nothing to leave out, and other variants
        assertFalse(run("return after(hookof('Intent.createFromParcel/network'),\n" +
                "  paramof(intentof('android.bluetooth.adapter.action.STATE_CHANGED', { ['android.bluetooth.adapter.extra.STATE'] = 12 })))")
                .arg1().toboolean());
        assertFalse(run("return after(hookof('Intent.createFromParcel/battery'),\n" +
                "  paramof(intentof('android.bluetooth.device.action.FOUND', { " + device + " = 'AA:BB' })))").arg1().toboolean());
    }

    // The number of a call in the broadcasts of the call state and of outgoing calls, like the call log
    @Test
    public void callNumbersAreLeftOut() {
        Varargs incoming = run(
                "local intent = intentof('android.intent.action.PHONE_STATE', { state = 'RINGING', incoming_number = '+3161234' })\n" +
                        "return after(hookof('Intent.createFromParcel/call_log'), paramof(intent)), intent.extras.incoming_number,\n" +
                        "  intent.extras.state");
        assertTrue(incoming.arg(1).toboolean());
        assertTrue(incoming.arg(2).isnil());
        assertEquals("RINGING", incoming.arg(3).tojstring());
        Varargs outgoing = run(
                "local intent = intentof('android.intent.action.NEW_OUTGOING_CALL', { ['android.intent.extra.PHONE_NUMBER'] = '+3161234' })\n" +
                        "return after(hookof('Intent.createFromParcel/call_log'), paramof(intent)),\n" +
                        "  intent.extras['android.intent.extra.PHONE_NUMBER']");
        assertTrue(outgoing.arg(1).toboolean());
        assertTrue(outgoing.arg(2).isnil());
        assertFalse(run("return after(hookof('Intent.createFromParcel/call_log'),\n" +
                "  paramof(intentof('android.intent.action.PHONE_STATE', { state = 'IDLE' })))").arg1().toboolean());
    }

    // The number of an outgoing call is also the result data of its broadcast (Telecom), which the app reads as none,
    // like the extra; the data the app set itself and none stay (see receiver_resultdata). An app which passes the
    // broadcast on with setResultData(getResultData()) so sets none, which cancels the call (shortcut there).
    @Test
    public void outgoingCallResultDataIsLeftOut() throws Exception {
        Globals receiver = Scripts.load("receiver_resultdata", STUBS);
        receiver.set("context", globals.get("context"));
        receiver.set("store", globals.get("store"));
        String read = "local param = paramof(%s)\n" +
                "return after(hookof('BroadcastReceiver.getResultData/call_log'), param), param.replaced ~= nil,\n" +
                "  param.replaced and param.replaced.value";
        assertFalse(Scripts.run(receiver, String.format(read, "'+3161234'")).arg1().toboolean());
        run("after(hookof('Intent.createFromParcel/call_log'), paramof(intentof('android.intent.action.NEW_OUTGOING_CALL',\n" +
                "  { ['android.intent.extra.PHONE_NUMBER'] = '+3161234' })))");
        Varargs number = Scripts.run(receiver, String.format(read, "'+3161234'"));
        assertTrue(number.arg(1).toboolean());
        assertTrue(number.arg(2).toboolean());
        assertTrue(number.arg(3).isnil());
        for (String data : new String[]{"'+3199999'", "nil"}) {
            Varargs other = Scripts.run(receiver, String.format(read, data));
            assertFalse(data, other.arg(1).toboolean());
            assertFalse(data, other.arg(2).toboolean());
        }
    }

    // The names of the carrier the status bar shows, by the extras of Android 11 and later and of 8.1 to 10, and the
    // strings beside the service state are those of the fake operator, like getNetworkOperatorName (see test_network);
    // empty names, the flags and the texts of Android without service stay
    @Test
    public void carrierOfBroadcastsIsFake() {
        String extra = "android.telephony.extra.";
        Varargs providers = run(
                "local intent = intentof('android.telephony.action.SERVICE_PROVIDERS_UPDATED', {\n" +
                        "  ['" + extra + "PLMN'] = 'T-Mobile', ['" + extra + "SPN'] = 'T-Mobile Wi-Fi Calling',\n" +
                        "  ['" + extra + "DATA_SPN'] = '', ['" + extra + "SHOW_PLMN'] = true })\n" +
                        "local restricted, old, new = after(hookof('Intent.createFromParcel/operator'), paramof(intent))\n" +
                        "return restricted, old, new, intent.extras['" + extra + "PLMN'], intent.extras['" + extra + "SPN'],\n" +
                        "  intent.extras['" + extra + "DATA_SPN'], intent.extras['" + extra + "SHOW_PLMN']");
        assertTrue(providers.arg(1).toboolean());
        assertEquals("T-Mobile,T-Mobile Wi-Fi Calling", providers.arg(2).tojstring());
        assertEquals("unknown,unknown", providers.arg(3).tojstring());
        assertEquals("unknown", providers.arg(4).tojstring());
        assertEquals("unknown", providers.arg(5).tojstring());
        assertEquals("", providers.arg(6).tojstring());
        assertTrue(providers.arg(7).toboolean());

        Varargs spn = run(
                "local intent = intentof('android.provider.Telephony.SPN_STRINGS_UPDATED',\n" +
                        "  { plmn = 'No service', spn = 'T-Mobile', spnData = 'T-Mobile' })\n" +
                        "return after(hookof('Intent.createFromParcel/operator'), paramof(intent)),\n" +
                        "  intent.extras.plmn, intent.extras.spn, intent.extras.spnData");
        assertTrue(spn.arg(1).toboolean());
        assertEquals("No service", spn.arg(2).tojstring());
        assertEquals("unknown", spn.arg(3).tojstring());
        assertEquals("unknown", spn.arg(4).tojstring());
        Varargs emergency = run(
                "local intent = intentof('android.provider.Telephony.SPN_STRINGS_UPDATED',\n" +
                        "  { plmn = 'Emergency calls only', spn = '' })\n" +
                        "return after(hookof('Intent.createFromParcel/operator'), paramof(intent)), intent.extras.plmn");
        assertFalse(emergency.arg(1).toboolean());
        assertEquals("Emergency calls only", emergency.arg(2).tojstring());

        Varargs state = run(
                "local intent = intentof('android.intent.action.SERVICE_STATE', { ['operator-numeric'] = '310260',\n" +
                        "  ['data-operator-numeric'] = '310260', ['operator-alpha-long'] = 'T-Mobile',\n" +
                        "  ['operator-alpha-short'] = 'TMO', ['data-operator-alpha-long'] = 'T-Mobile',\n" +
                        "  ['operator-alpha-long-raw'] = 'T-Mobile', voiceRegState = 0 })\n" +
                        "local restricted = after(hookof('Intent.createFromParcel/operator'), paramof(intent))\n" +
                        "local e = intent.extras\n" +
                        "return restricted, e['operator-numeric'], e['data-operator-numeric'], e['operator-alpha-long'],\n" +
                        "  e['operator-alpha-short'], e['data-operator-alpha-long'], e['operator-alpha-long-raw'],\n" +
                        "  e['data-operator-alpha-short'], e.voiceRegState");
        assertTrue(state.arg(1).toboolean());
        assertEquals("00101", state.arg(2).tojstring());
        assertEquals("00101", state.arg(3).tojstring());
        for (int i = 4; i <= 7; i++)
            assertEquals("unknown", state.arg(i).tojstring());
        assertTrue(state.arg(8).isnil());
        assertEquals(0, state.arg(9).toint());

        // The copy without the operator, which Android sends to apps without the location permission, and other
        // variants stay
        Varargs sanitized = run(
                "local intent = intentof('android.intent.action.SERVICE_STATE', { voiceRegState = 0 })\n" +
                        "return after(hookof('Intent.createFromParcel/operator'), paramof(intent))");
        assertFalse(sanitized.arg1().toboolean());
        Varargs other = run(
                "local intent = intentof('android.intent.action.SERVICE_STATE', { ['operator-numeric'] = '310260' })\n" +
                        "return after(hookof('Intent.createFromParcel/network'), paramof(intent)), " +
                        "intent.extras['operator-numeric']");
        assertFalse(other.arg(1).toboolean());
        assertEquals("310260", other.arg(2).tojstring());
    }

    // The battery level in steps of 10 percent of its scale, but at least 15 percent, like BatteryManager (see battery)
    @Test
    public void batteryLevelIsRounded() {
        for (int[] level : new int[][]{{57, 100, 60}, {12, 100, 15}, {3, 100, 15}, {0, 100, 15}, {114, 200, 120}, {9, 200, 30}}) {
            Varargs result = run(String.format(
                    "local intent = intentof('android.intent.action.BATTERY_CHANGED', { level = %d, scale = %d })\n" +
                            "after(hookof('Intent.createFromParcel/battery'), paramof(intent))\n" +
                            "return intent.extras.level", level[0], level[1]));
            assertEquals(level[0] + "/" + level[1], level[2], result.arg1().toint());
        }
    }

    // The details of charging have neutral values instead of none, since Android always sends them; the level of the
    // capacity follows the fake level; the battery saver going on is off
    @Test
    public void batteryDetailsAreNeutral() {
        Varargs result = run(
                "local intent = intentof('android.intent.action.BATTERY_CHANGED', { level = 4, scale = 100, charge_counter = 123456,\n" +
                        "  max_charging_current = 500000, ['android.os.extra.CYCLE_COUNT'] = 321, ['android.os.extra.CHARGING_STATUS'] = 4,\n" +
                        "  ['android.os.extra.CAPACITY_LEVEL'] = 1 })\n" +
                        "after(hookof('Intent.createFromParcel/battery'), paramof(intent))\n" +
                        "local e = intent.extras\n" +
                        "local saver = intentof('android.os.action.POWER_SAVE_MODE_CHANGING', { mode = true })\n" +
                        "local restricted = after(hookof('Intent.createFromParcel/battery'), paramof(saver))\n" +
                        "return e.level, e.charge_counter, e.max_charging_current, e['android.os.extra.CYCLE_COUNT'],\n" +
                        "  e['android.os.extra.CHARGING_STATUS'], e['android.os.extra.CAPACITY_LEVEL'], restricted, saver.extras.mode");
        assertEquals(15, result.arg(1).toint());
        assertEquals(600000, result.arg(2).toint());
        assertEquals(0, result.arg(3).toint());
        assertEquals(0, result.arg(4).toint());
        assertEquals(1, result.arg(5).toint());
        assertEquals(2, result.arg(6).toint());
        assertTrue(result.arg(7).toboolean());
        assertFalse(result.arg(8).toboolean());
        // A device without policies of charging (0) keeps it
        assertEquals(0, run("local intent = intentof('android.intent.action.BATTERY_CHANGED', { level = 50, scale = 100,\n" +
                "  ['android.os.extra.CHARGING_STATUS'] = 0 })\n" +
                "after(hookof('Intent.createFromParcel/battery'), paramof(intent))\n" +
                "return intent.extras['android.os.extra.CHARGING_STATUS']").arg1().toint());
    }

    // The notification of an MMS message has the sender and the subject
    @Test
    public void wapPushIsEmpty() {
        Varargs result = run(
                "local intent = intentof('android.provider.Telephony.WAP_PUSH_RECEIVED', {\n" +
                        "  data = 'pdu', header = 'wsp', address = '+31612345678', transactionId = 1 })\n" +
                        "local restricted = after(hookof('Intent.createFromParcel/message'), paramof(intent))\n" +
                        "return restricted, intent.extras.data.length, intent.extras.data.type,\n" +
                        "  intent.extras.header.length, intent.extras.address, intent.extras.transactionId");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).toint());
        assertEquals("byte", result.arg(3).tojstring());
        assertEquals(0, result.arg(4).toint());
        assertTrue(result.arg(5).isnil());
        assertEquals(1, result.arg(6).toint());
    }

    @Test
    public void dataMessageIsEmpty() {
        Varargs result = run(
                "local intent = intentof('android.intent.action.DATA_SMS_RECEIVED', { pdus = { length = 1, 'pdu' } })\n" +
                        "return after(hookof('Intent.createFromParcel/message'), paramof(intent)), intent.extras.pdus.length,\n" +
                        "  intent.extras.pdus.type.name");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).toint());
        // A byte[][], like the messages Android sends, which getSerializableExtra(name, byte[][].class) finds
        assertEquals("[B", result.arg(3).tojstring());
    }

    // The messages for the default SMS app are like the received ones
    @Test
    public void deliveredMessagesAreEmpty() {
        Varargs sms = run(
                "local intent = intentof('android.provider.Telephony.SMS_DELIVER', { pdus = { length = 1, 'pdu' } })\n" +
                        "return after(hookof('Intent.createFromParcel/message'), paramof(intent)), intent.extras.pdus.length,\n" +
                        "  intent.extras.pdus.type.name");
        assertTrue(sms.arg(1).toboolean());
        assertEquals(0, sms.arg(2).toint());
        assertEquals("[B", sms.arg(3).tojstring());

        Varargs wap = run(
                "local intent = intentof('android.provider.Telephony.WAP_PUSH_DELIVER', { data = 'pdu', address = '+31612345678' })\n" +
                        "return after(hookof('Intent.createFromParcel/message'), paramof(intent)), intent.extras.data.length,\n" +
                        "  intent.extras.address");
        assertTrue(wap.arg(1).toboolean());
        assertEquals(0, wap.arg(2).toint());
        assertTrue(wap.arg(3).isnil());
    }

    // The broadcast of a time zone change has the fake zone, UTC without a setting
    @Test
    public void timeZoneChangeIsFake() {
        Varargs fake = run(
                "local intent = intentof('android.intent.action.TIMEZONE_CHANGED', { ['time-zone'] = 'Europe/Amsterdam' })\n" +
                        "local restricted, old, new = after(hookof('Intent.createFromParcel/timezone'),\n" +
                        "  paramof(intent, nil, false, { ['value.timezone'] = 'America/New_York' }))\n" +
                        "return restricted, old, new, intent.extras['time-zone']");
        assertTrue(fake.arg(1).toboolean());
        assertEquals("Europe/Amsterdam", fake.arg(2).tojstring());
        assertEquals("America/New_York", fake.arg(3).tojstring());
        assertEquals("America/New_York", fake.arg(4).tojstring());

        Varargs utc = run(
                "local intent = intentof('android.intent.action.TIMEZONE_CHANGED', { ['time-zone'] = 'Europe/Amsterdam' })\n" +
                        "after(hookof('Intent.createFromParcel/timezone'), paramof(intent))\n" +
                        "return intent.extras['time-zone']");
        assertEquals("UTC", utc.arg1().tojstring());
    }

    // The broadcast of a time zone change, which Android sends only when the zone changed, does not reach the receivers,
    // since the fake zone stays the same (see receiver_skip): a receiver of the manifest is finished first, a registered
    // one only when nothing goes back to Android, not of an ordered broadcast or, from Android 14, of one Android does
    // not assume delivered; other broadcasts and other variants are delivered
    @Test
    public void timeZoneChangeIsNotDelivered() throws Exception {
        Globals skip = Scripts.load("receiver_skip", STUBS + "\n" + String.join("\n",
                "calls = {}",
                "function dispatchof(name, method, count)",
                "  return { getName = function(self) return name end, getMethodName = function(self) return method end,",
                "    getParameterTypes = function(self) return { length = count } end }",
                "end",
                "function callof(args)",
                "  return { getArgument = function(self, index) return args[index] end,",
                "    setResult = function(self, value) calls[#calls + 1] = 'skip' end }",
                "end",
                "function dataof(action)",
                "  return { intent = intentof(action, {}), finish = function(self) calls[#calls + 1] = 'finish' end }",
                "end",
                "function deliver(hook, args)",
                "  calls = {}",
                "  local skipped = before(hook, callof(args))",
                "  return skipped, table.concat(calls, ',')",
                "end",
                "zone = 'android.intent.action.TIMEZONE_CHANGED'"));
        String[][] cases = {
                {"dispatchof('ActivityThread.handleReceiver/timezone', 'handleReceiver', 1), { [0] = dataof(zone) }",
                        "true", "finish,skip"},
                {"dispatchof('ActivityThread.handleReceiver/timezone', 'handleReceiver', 1), " +
                        "{ [0] = dataof('android.intent.action.TIME_SET') }", "false", ""},
                {"dispatchof('ActivityThread.handleReceiver/timezone', 'handleReceiver', 1), { [0] = nil }", "false", ""},
                {"dispatchof('ActivityThread.handleReceiver/battery', 'handleReceiver', 1), { [0] = dataof(zone) }",
                        "false", ""},
                {"dispatchof('ReceiverDispatcher.performReceive/timezone', 'performReceive', 7), " +
                        "{ [0] = intentof(zone, {}), [4] = false, [5] = false }", "true", "skip"},
                {"dispatchof('ReceiverDispatcher.performReceive/timezone', 'performReceive', 7), " +
                        "{ [0] = intentof(zone, {}), [4] = true, [5] = false }", "false", ""},
                {"dispatchof('ReceiverDispatcher.performReceive/timezone', 'performReceive', 7), " +
                        "{ [0] = intentof('android.intent.action.SCREEN_ON', {}), [4] = false }", "false", ""},
                {"dispatchof('ReceiverDispatcher.performReceive.sender/timezone', 'performReceive', 10), " +
                        "{ [0] = intentof(zone, {}), [4] = false, [6] = true }", "true", "skip"},
                {"dispatchof('ReceiverDispatcher.performReceive.sender/timezone', 'performReceive', 10), " +
                        "{ [0] = intentof(zone, {}), [4] = false, [6] = false }", "false", ""},
                {"dispatchof('ReceiverDispatcher.performReceive.sender/timezone', 'performReceive', 10), " +
                        "{ [0] = intentof(zone, {}), [4] = true, [6] = true }", "false", ""},
        };
        for (String[] test : cases) {
            Varargs result = Scripts.run(skip, "return deliver(" + test[0] + ")");
            assertEquals(test[0], Boolean.parseBoolean(test[1]), result.arg(1).toboolean());
            assertEquals(test[0], test[2], result.arg(2).tojstring());
        }
    }
}
