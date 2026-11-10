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
            // The packages which stay visible besides the app itself, and whether the process is of the system
            "function paramof(intent, visible, system, settings)",
            "  return { getResult = function(self) return intent end,",
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
            "  function intent:replaceExtras(bundle) self.extras = bundle.values end",
            "  function intent:getExtras()",
            "    if self.extras == nil then return nil end",
            "    local bundle = { values = {} }",
            "    for key, value in pairs(self.extras) do bundle.values[key] = value end",
            "    function bundle:containsKey(key) return self.values[key] ~= nil end",
            "    function bundle:remove(key) self.values[key] = nil end",
            "    function bundle:getInt(key, default) return self.values[key] or default end",
            "    function bundle:getString(key) return self.values[key] end",
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
                        "return after(hookof('Intent.createFromParcel/message'), paramof(intent)), intent.extras.pdus.length");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).toint());
    }

    // The messages for the default SMS app are like the received ones
    @Test
    public void deliveredMessagesAreEmpty() {
        Varargs sms = run(
                "local intent = intentof('android.provider.Telephony.SMS_DELIVER', { pdus = { length = 1, 'pdu' } })\n" +
                        "return after(hookof('Intent.createFromParcel/message'), paramof(intent)), intent.extras.pdus.length");
        assertTrue(sms.arg(1).toboolean());
        assertEquals(0, sms.arg(2).toint());

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
}
