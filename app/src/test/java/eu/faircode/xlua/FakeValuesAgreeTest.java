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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// Scripts which fake the same value each have it, since scripts cannot share code: the fake time zone,
// the operator of the test network and the Wi-Fi network, in the getters, the system properties and the texts;
// the serial number, the security patch level, the MAC address, the user agent of WebView, the authority
// of a content URI and the files of /sys which do not exist
public class FakeValuesAgreeTest {
    // A hook with a name and a method, a param with a result, arguments and settings; fakeof runs after()
    // and returns the result then
    private static final String STUBS = String.join("\n",
            "function hookof(name, method, class)",
            "  return { getName = function(self) return name end,",
            "    getMethodName = function(self) return method end,",
            "    getClassName = function(self) return class end }",
            "end",
            "function paramof(result, args, settings)",
            "  return { result = result,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value end,",
            "    getArgument = function(self, index) return args[index + 1] end,",
            "    getSetting = function(self, name) return settings[name] end }",
            "end",
            "function fakeof(name, method, result, args, settings, class)",
            "  local param = paramof(result, args or {}, settings or {})",
            "  after(hookof(name, method, class), param)",
            "  return param.result",
            "end");

    private static Globals load(String script) throws Exception {
        return Scripts.load(script, STUBS);
    }

    private static String run(Globals globals, String lua) {
        return Scripts.run(globals, "return " + lua).arg1().tojstring();
    }

    // UTC without a time zone set, else the one set, for the default time zone, its property and the shell
    @Test
    public void timeZone() throws Exception {
        Globals timezone = load("timezone");
        Globals properties = load("systemproperties_get");
        Globals shell = load("shell_commands");
        for (String setting : new String[]{"nil", "''", "'Asia/Tokyo'"}) {
            String settings = "{ ['value.timezone'] = " + setting + " }";
            String expected = (setting.equals("'Asia/Tokyo'") ? "Asia/Tokyo" : "UTC");
            assertEquals(setting, expected, run(timezone, "fakeid(paramof(nil, {}, " + settings + "))"));
            assertEquals(setting, expected, run(shell, "fakezone(paramof(nil, {}, " + settings + "))"));
            assertEquals(setting, expected, run(properties, "fakeof('SystemProperties.get/timezone', 'get', " +
                    "'Europe/Berlin', { 'persist.sys.timezone' }, " + settings + ")"));
        }
    }

    // The test network (MCC 001, MNC 01), the unknown country xx, unknown names and carrier id -1,
    // in the getters (test_network), the system properties and the texts of subscriptions,
    // service states and network registrations (operator_tostring)
    @Test
    public void operator() throws Exception {
        Globals getters = load("test_network");
        Globals properties = load("systemproperties_get");
        Globals texts = load("operator_tostring");

        String operator = run(getters, "fakeof(nil, 'getNetworkOperator', '310260')");
        assertEquals("00101", operator);
        assertEquals(operator, run(getters, "fakeof(nil, 'getSimOperator', '310260')"));
        assertEquals(operator, run(getters, "fakeof(nil, 'getOperatorNumeric', '310260')"));
        assertEquals(operator, run(getters, "fakeof(nil, 'getRegisteredPlmn', '310260')"));
        assertEquals(operator, run(getters, "fakeof(nil, 'getMccString', '310')") +
                run(getters, "fakeof(nil, 'getMncString', '260')"));
        assertEquals(Integer.parseInt(run(getters, "fakeof(nil, 'getMccString', '310')")),
                Integer.parseInt(run(getters, "fakeof(nil, 'getMcc', 310)")));
        assertEquals(Integer.parseInt(run(getters, "fakeof(nil, 'getMncString', '260')")),
                Integer.parseInt(run(getters, "fakeof(nil, 'getMnc', 260)")));
        for (String key : new String[]{"gsm.operator.numeric", "gsm.sim.operator.numeric"})
            assertEquals(key, operator, run(properties,
                    "fakeof('SystemProperties.get/operator', 'get', '310260', { '" + key + "' })"));

        String name = run(getters, "fakeof(nil, 'getNetworkOperatorName', 'T-Mobile')");
        for (String method : new String[]{"getSimOperatorName", "getOperatorAlphaLong", "getOperatorAlphaShort",
                "getCarrierName", "getDisplayName"})
            assertEquals(method, name, run(getters, "fakeof(nil, '" + method + "', 'T-Mobile')"));
        for (String key : new String[]{"gsm.operator.alpha", "gsm.sim.operator.alpha"})
            assertEquals(key, name, run(properties,
                    "fakeof('SystemProperties.get/operator', 'get', 'T-Mobile', { '" + key + "' })"));

        String country = run(getters, "fakeof(nil, 'getCountryIso', 'us')");
        for (String method : new String[]{"getNetworkCountryIso", "getSimCountryIso"})
            assertEquals(method, country, run(getters, "fakeof(nil, '" + method + "', 'us')"));
        for (String key : new String[]{"gsm.operator.iso-country", "gsm.sim.operator.iso-country"})
            assertEquals(key, country, run(properties,
                    "fakeof('SystemProperties.get/operator', 'get', 'us', { '" + key + "' })"));

        String carrier = run(getters, "fakeof(nil, 'getCarrierId', 1)");

        // The text of a subscription of Android 14: its names, country and carrier, and its codes (plmn)
        String subscription = "[SubscriptionInfo: id=1 carrierId=1 displayName=T-Mobile carrierName=T-Mobile" +
                " nameSource=0 iconTint=0 number=+15550100 dataRoaming=0 mcc=310 mnc=260 countryIso=us" +
                " isEmbedded=false ehplmns=[310260] hplmns=[310260] subscriptionType=0]";
        String named = run(texts, "fakeof('SubscriptionInfo.toString/operator', 'toString', '" + subscription + "'," +
                " nil, nil, 'android.telephony.SubscriptionInfo')");
        assertTrue(named, named.contains(" carrierId=" + carrier + " displayName=" + name + " carrierName=" + name +
                " ") && named.contains(" countryIso=" + country + " "));
        String coded = run(texts, "fakeof('SubscriptionInfo.toString/plmn', 'toString', '" + subscription + "'," +
                " nil, nil, 'android.telephony.SubscriptionInfo')");
        assertTrue(coded, coded.contains(" mcc=" + operator.substring(0, 3) + " mnc=" + operator.substring(3) + " "));

        String state = run(texts, "fakeof('ServiceState.toString/operator', 'toString'," +
                " '{mVoiceRegState=0(IN_SERVICE), mOperatorAlphaLong=T-Mobile, mOperatorAlphaShort=TMO," +
                " isManualNetworkSelection=false}', nil, nil, 'android.telephony.ServiceState')");
        assertTrue(state, state.contains("mOperatorAlphaLong=" + name + ", mOperatorAlphaShort=" + name + ","));
        String registration = run(texts, "fakeof('NetworkRegistrationInfo.toString/operator', 'toString'," +
                " 'NetworkRegistrationInfo{ domain=PS rRplmn=310260 isUsingCarrierAggregation=false}', nil, nil," +
                " 'android.telephony.NetworkRegistrationInfo')");
        assertTrue(registration, registration.contains(" rRplmn=" + operator + " "));
    }

    // The network name, the access point, the MAC address and the IP address of the getters
    // (wifiinfo_ssid_bssid, mac_address, network_address) are the ones of the text of WifiInfo (network_tostring)
    @Test
    public void wifi() throws Exception {
        String ssid = run(load("wifiinfo_ssid_bssid"), "fakeof(nil, 'getSSID', '\"Home\"')");
        String bssid = run(load("wifiinfo_ssid_bssid"), "fakeof(nil, 'getBSSID', 'aa:bb:cc:dd:ee:ff')");
        String mac = run(load("mac_address"), "fakeof(nil, 'getMacAddress', '11:22:33:44:55:66')");
        String ip = run(load("network_address"), "fakeof(nil, 'getIpAddress', 167772674)");
        assertEquals(0, Integer.parseInt(ip));

        String text = run(load("network_tostring"), "wifiinfo('SSID: \"Home\", BSSID: aa:bb:cc:dd:ee:ff," +
                " MAC: 11:22:33:44:55:66, IP: /10.0.2.16, Security type: 0')");
        assertTrue(text, text.startsWith("SSID: " + ssid + ", BSSID: " + bssid + ", MAC: " + mac + "," +
                " IP: /0.0.0.0,"));

        // The Bluetooth address of the settings is a MAC address too, like BluetoothAdapter.getAddress
        assertEquals(mac, run(load("settingssecure_getstring"), "fakeof('Settings.Secure.getStringForUser/mac'," +
                " 'getStringForUser', '11:22:33:44:55:66', { nil, 'bluetooth_address' }, nil," +
                " 'android.provider.Settings$Secure')"));
    }

    // unknown without a serial number set, else the one set, for Build.getSerial, the properties and getprop
    @Test
    public void serial() throws Exception {
        Globals getter = load("value_serial");
        Globals properties = load("systemproperties_get");
        Globals shell = load("shell_commands");
        for (String setting : new String[]{"nil", "'ABC123'"}) {
            String settings = "{ ['value.serial'] = " + setting + " }";
            String expected = run(getter, "fakeof(nil, 'getSerial', 'R58M1234', {}, " + settings + ")");
            assertEquals(setting, setting.equals("nil") ? "unknown" : "ABC123", expected);
            assertEquals(setting, expected, run(properties, "fakeof('SystemProperties.get/serial', 'get'," +
                    " 'R58M1234', { 'ro.serialno' }, " + settings + ")"));
            assertEquals(setting, expected, run(shell, "rules(paramof(nil, {}, " + settings + "), 'identifiers')[1][1]"));
        }
    }

    // The first day of the quarter, for Build.VERSION.SECURITY_PATCH and its property; other texts stay
    @Test
    public void securityPatch() throws Exception {
        Globals field = load("security_patch");
        Globals properties = load("systemproperties_get");
        for (String date : new String[]{"2025-01-01", "2025-03-31", "2025-05-05", "2025-07-01", "2025-12-31",
                "2025-5-5", "unknown"}) {
            String expected = run(field, "fakeof(nil, nil, '" + date + "')");
            assertEquals(date, expected, run(properties, "fakeof('SystemProperties.get/device', 'get', '" + date +
                    "', { 'ro.build.version.security_patch' })"));
        }
        assertEquals("2025-04-01", run(field, "fakeof(nil, nil, '2025-05-05')"));
    }

    // The model and build in the user agent of WebView become K, like Chrome does, for the default user agent
    // and for the ones the app sets
    @Test
    public void userAgent() throws Exception {
        Globals getter = load("webview_useragent");
        Globals constructor = load("webview_constructor");
        for (String ua : new String[]{
                "Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP4A.250105.002; wv) AppleWebKit/537.36",
                "Mozilla/5.0 (Linux; Android 10; SM-G973F Build/QP1A.190711.020) AppleWebKit/537.36",
                "Mozilla/5.0 (Linux; Android 15; K; wv) AppleWebKit/537.36"}) {
            String expected = run(getter, "fakeof(nil, 'getDefaultUserAgent', '" + ua + "')");
            assertEquals(ua, expected, run(constructor, "reduce('" + ua + "')"));
        }
    }

    // The authority of a content URI without the user in front, like 10@com.android.contacts,
    // for opening and querying providers and for the hidden providers
    @Test
    public void authority() throws Exception {
        String uri = "function uriof(authority)\n" +
                "  return { getScheme = function(self) return 'content' end,\n" +
                "    getAuthority = function(self) return authority end }\n" +
                "end";
        Globals open = Scripts.load("contentresolver_open", STUBS + "\n" + uri);
        Globals query = Scripts.load("contentresolver_query", STUBS + "\n" + uri);
        Globals hidden = Scripts.load("provider_hidden", STUBS + "\n" + uri);
        for (String authority : new String[]{"'com.android.contacts'", "'10@com.android.contacts'", "nil"}) {
            String expected = run(open, "tostring(authorityof(uriof(" + authority + ")))");
            assertEquals(authority, expected, run(query, "tostring(authorityof(uriof(" + authority + ")))"));
            assertEquals(authority, expected, run(hidden, "tostring(authorityof(uriof(" + authority + ")))"));
        }
        assertEquals("com.android.contacts", run(open, "authorityof(uriof('10@com.android.contacts'))"));
    }

    // The files of /sys which do not exist for each group: the hooks of opening files (hardware_files,
    // Lua patterns) and cat run by the app (shell_commands, patterns of the shell) hide the same ones
    @Test
    public void hardwareFiles() throws Exception {
        Globals files = Scripts.load("hardware_files", STUBS + "\n" +
                "function grouphook(group)\n" +
                "  return { getGroup = function(self) return group end,\n" +
                "    getClassName = function(self) return 'libcore.io.BlockGuardOs' end,\n" +
                "    getMethodName = function(self) return 'open' end,\n" +
                "    getReturnType = function(self) return nil end }\n" +
                "end");
        Globals shell = load("shell_commands");
        String[][] variants = {{"Read.Identifiers", "identifiers"}, {"Use.Tracking", "tracking"},
                {"Read.Biometrics", "biometrics"}};
        String[] hidden = {
                "/sys/devices/system/chip-id/unique_id", "/sys/devices/system/chip-id/lot_id",
                "/sys/devices/system/chip-id/lot_id2", "/sys/devices/system/chip-id",
                "/sys/devices/system/chip-id/other",
                "/sys/bus/mmc/devices/mmc0:0001/cid", "/sys/bus/mmc/devices/mmc0:0001/serial",
                "/sys/bus/mmc/devices/mmc0:0001/date", "/sys/bus/mmc/devices/mmc0:0001/manfid",
                "/sys/bus/mmc/devices/mmc0:0001/oemid", "/sys/bus/mmc/devices/mmc0:0001/name",
                "/sys/class/sec/sdinfo/data",
                "/sys/class/scsi_host/host0/unique_number",
                "/sys/devices/platform/13100000.ufs/host0/scsi_host/host0/unique_number",
                "/sys/class/scsi_host/host0/man_id",
                "/sys/devices/platform/13100000.ufs/string_descriptors/serial_number",
                "/sys/devices/platform/13100000.ufs/string_descriptors/product_name",
                "/sys/devices/platform/13100000.ufs/string_descriptors/manufacturer_name",
                "/sys/devices/platform/13100000.ufs/string_descriptors/oem_id",
                "/sys/class/sensors/light_sensor/light_cal",
                "/sys/class/sec/tsp", "/sys/class/sec/tsp/cmd", "/sys/class/sec/tsp/fod_info",
                "/sys/class/sec/tsp/fod_pos", "/sys/class/fingerprint/fingerprint",
                "/sys/class/fingerprint/fingerprint/name",
                "/proc/self/root/sys/devices/system/chip-id/unique_id"};
        String[] stay = {
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "/sys/class/sec/tspx",
                "/sys/bus/mmc/devices/mmc0:0001/cidx", "/sys/class/fingerprint/fingerprintx",
                "/sys/devices/system/chip-idx/unique_id", "/sys/class/scsi_host/host0/unique_id",
                "/sys/devices/platform/13100000.ufs/string_descriptors/product_revision"};
        // Each file of a sample which does not exist is hidden for a group at least, the others for none
        for (String[] paths : new String[][]{hidden, stay})
            for (String path : paths) {
                boolean any = false;
                for (String[] variant : variants) {
                    String expected = run(files, "tostring(hidden(grouphook('" + variant[0] + "'), '" + path + "'))");
                    assertEquals(variant[1] + " " + path, expected,
                            run(shell, "tostring(hiddenfile('" + path + "', { " + variant[1] + " = true }))"));
                    any |= "true".equals(expected);
                }
                assertEquals(path, paths == hidden, any);
            }
    }
}
