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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            // The cursors of the stand-ins are no CursorWrapper
            "    isInstance = function(self, object, name) return false end,",
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

    // What a hook of opening files writes into a property area with these names and values, which the app reads as a
    // file (see XParam.getProperties and setPropertyValues), as name=value, like the area lists them
    private static String area(Globals globals, String hook, String settings, String... properties) {
        StringBuilder listed = new StringBuilder();
        for (String property : properties)
            listed.append("'").append(property).append("', ");
        return run(globals, "(function()\n" +
                "  local param, set = paramof(nil, {}, " + settings + "), {}\n" +
                "  param.getProperties = function(self)\n" +
                "    local listed = { " + listed + "}\n" +
                "    listed['length'] = #listed\n" +
                "    return listed\n" +
                "  end\n" +
                "  param.setPropertyValues = function(self, names, values)\n" +
                "    for index = 1, #names do set[#set + 1] = names[index] .. '=' .. values[index] end\n" +
                "    return names\n" +
                "  end\n" +
                "  after(hookof('" + hook + "', 'open'), param)\n" +
                "  return table.concat(set, ' ')\n" +
                "end)()");
    }

    // The kernel release without the build, for Os.uname and the property os.version
    @Test
    public void kernel() throws Exception {
        String real = "6.6.50-android15-8-g8adecb593e9b";
        Globals uname = load("uname");
        Scripts.run(uname, "luajava = { newInstance = function(name, sysname, nodename, release, version, machine)\n" +
                "  return { release = release, nodename = nodename, version = version } end }");
        assertEquals("6.6.50", run(uname, "fakeof('Os.uname', 'uname', " +
                "{ sysname = 'Linux', release = '" + real + "', machine = 'aarch64' }).release"));
        Globals properties = load("system_properties");
        assertEquals("6.6.50", run(properties, "(function()\n" +
                "  local values = { ['os.version'] = '" + real + "' }\n" +
                "  fakeof('System.unchangeableProps', '#unchangeableProps', {\n" +
                "    get = function(self, key) return values[key] end,\n" +
                "    put = function(self, key, value) values[key] = value end })\n" +
                "  return values['os.version']\n" +
                "end)()"));
    }

    // UTC without a time zone set, else the one set, for the default time zone, its property and the
    // broadcast of a time zone change
    @Test
    public void timeZone() throws Exception {
        Globals timezone = load("timezone");
        Globals properties = load("systemproperties_get");
        Globals intent = load("intent_createfromparcel");
        Scripts.run(intent, "function intentof(zone)\n" +
                "  local values = { ['time-zone'] = zone }\n" +
                "  local bundle = { containsKey = function(self, key) return values[key] ~= nil end,\n" +
                "    getString = function(self, key) return values[key] end,\n" +
                "    putString = function(self, key, value) values[key] = value end }\n" +
                "  return { getAction = function(self) return 'android.intent.action.TIMEZONE_CHANGED' end,\n" +
                "    getExtras = function(self) return bundle end, replaceExtras = function(self, extras) end,\n" +
                "    zone = function(self) return values['time-zone'] end }\n" +
                "end");
        for (String setting : new String[]{"nil", "''", "'Asia/Tokyo'"}) {
            String settings = "{ ['value.timezone'] = " + setting + " }";
            String expected = (setting.equals("'Asia/Tokyo'") ? "Asia/Tokyo" : "UTC");
            assertEquals(setting, expected, run(timezone, "fakeid(paramof(nil, {}, " + settings + "))"));
            assertEquals(setting, expected, run(properties, "fakeof('SystemProperties.get/timezone', 'get', " +
                    "'Europe/Berlin', { 'persist.sys.timezone' }, " + settings + ")"));
            assertEquals(setting, expected, run(intent, "fakeof('Intent.createFromParcel/timezone', nil, " +
                    "intentof('Europe/Berlin'), {}, " + settings + "):zone()"));
            // The property in the property area the app reads as a file (see XParam.setPropertyValues)
            assertEquals(setting, "1 persist.sys.timezone=" + expected + " true Europe/Berlin " + expected, run(timezone,
                    "(function()\n" +
                    "  local param, set = paramof(nil, {}, " + settings + ")\n" +
                    "  param.setPropertyValues = function(self, names, values)\n" +
                    "    set = #names .. ' ' .. names[1] .. '=' .. values[1] return { 'Europe/Berlin' } end\n" +
                    "  local restricted, old, new = after(hookof('BlockGuardOs.open/timezone', 'open'), param)\n" +
                    "  return set .. ' ' .. tostring(restricted) .. ' ' .. old .. ' ' .. new\n" +
                    "end)()"));
        }
        // Only the property areas run the script for the hooks of opening files, the other hooks all calls
        assertEquals("/dev/__properties__/*", run(timezone, "filter(hookof('BlockGuardOs.open/timezone', 'open'))[1]"));
        assertEquals("nil", run(timezone, "filter(hookof('TimeZone.getDefault', 'getDefault'))"));
        assertEquals("false", run(timezone, "(function()\n" +
                "  local param = paramof(nil, {}, {})\n" +
                "  param.setPropertyValues = function(self, names, values) return {} end\n" +
                "  return after(hookof('BlockGuardOs.open/timezone', 'open'), param)\n" +
                "end)()"));
    }

    // The test network (MCC 001, MNC 01), no country like Android gives for MCC 001, unknown names and the carrier id
    // of the test network (1911),
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
        for (String key : new String[]{"gsm.operator.numeric", "gsm.sim.operator.numeric"}) {
            assertEquals(key, operator, run(properties,
                    "fakeof('SystemProperties.get/operator', 'get', '310260', { '" + key + "' })"));
            // The property areas the app reads as files, also with a list per SIM slot, like get
            assertEquals(key, key + "=" + operator, area(properties, "BlockGuardOs.open/operator", "{}", key, "310260"));
            String slots = run(properties, "fakeof('SystemProperties.get/operator', 'get', ',310260', { '" + key + "' })");
            assertEquals(key, ",00101", slots);
            assertEquals(key, key + "=" + slots, area(properties, "UnixNativeDispatcher.open/operator", "{}", key, ",310260"));
        }

        String name = run(getters, "fakeof(nil, 'getNetworkOperatorName', 'T-Mobile')");
        for (String method : new String[]{"getSimOperatorName", "getOperatorAlphaLong", "getOperatorAlphaShort",
                "getCarrierName", "getDisplayName"})
            assertEquals(method, name, run(getters, "fakeof(nil, '" + method + "', 'T-Mobile')"));
        for (String key : new String[]{"gsm.operator.alpha", "gsm.sim.operator.alpha"}) {
            assertEquals(key, name, run(properties,
                    "fakeof('SystemProperties.get/operator', 'get', 'T-Mobile', { '" + key + "' })"));
            assertEquals(key, key + "=" + name, area(properties, "BlockGuardOs.open/operator", "{}", key, "T-Mobile"));
        }

        String country = run(getters, "fakeof(nil, 'getCountryIso', 'us')");
        for (String method : new String[]{"getNetworkCountryIso", "getSimCountryIso"})
            assertEquals(method, country, run(getters, "fakeof(nil, '" + method + "', 'us')"));
        for (String key : new String[]{"gsm.operator.iso-country", "gsm.sim.operator.iso-country"}) {
            assertEquals(key, country, run(properties,
                    "fakeof('SystemProperties.get/operator', 'get', 'us', { '" + key + "' })"));
            assertEquals(key, key + "=" + country, area(properties, "BlockGuardOs.open/operator", "{}", key, "us"));
        }

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

    // The ICCID, the card string and the number of the getters (generic_empty_text, value_phone_number_nonnull)
    // are the ones of the text of a subscription (operator_tostring), with and without a number set
    @Test
    public void subscriptionIdentifiers() throws Exception {
        String iccId = run(load("generic_empty_text"), "fakeof(nil, 'getIccId', '89014103211118510720')");
        Globals number = load("value_phone_number_nonnull");
        Globals texts = load("operator_tostring");
        for (String setting : new String[]{"nil", "''", "'+15550100'"}) {
            String settings = "{ ['value.phone_number'] = " + setting + " }";
            String fake = run(number, "fakeof(nil, 'getNumber', '+15555215554', {}, " + settings + ")");
            String text = run(texts, "fakeof('SubscriptionInfo.toString/telephony', 'toString', '[SubscriptionInfo: id=1" +
                    " iccId=89014103211118510720 simSlotIndex=0 iconTint=0 number=+15555215554 dataRoaming=0" +
                    " cardString=89014103211118510720 cardId=0 countryIso=us]', {}, " + settings + "," +
                    " 'android.telephony.SubscriptionInfo')");
            assertTrue(text, text.contains(" iccId=" + iccId + " simSlotIndex=") && text.contains(" number=" + fake +
                    " dataRoaming=") && text.contains(" cardString=" + iccId + " cardId="));
        }
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
        // The address 0 of the getter is no address, which Android prints as null
        assertTrue(text, text.startsWith("SSID: " + ssid + ", BSSID: " + bssid + ", MAC: " + mac + "," +
                " IP: null,"));

        // The Bluetooth address of the settings is a MAC address too, like BluetoothAdapter.getAddress
        assertEquals(mac, run(load("settingssecure_getstring"), "fakeof('Settings.Secure.getStringForUser/mac'," +
                " 'getStringForUser', '11:22:33:44:55:66', { nil, 'bluetooth_address' }, nil," +
                " 'android.provider.Settings$Secure')"));
    }

    // unknown without a serial number set, else the one set, for Build.getSerial and the properties
    @Test
    public void serial() throws Exception {
        Globals getter = load("value_serial");
        Globals properties = load("systemproperties_get");
        for (String setting : new String[]{"nil", "'ABC123'"}) {
            String settings = "{ ['value.serial'] = " + setting + " }";
            String expected = run(getter, "fakeof(nil, 'getSerial', 'R58M1234', {}, " + settings + ")");
            assertEquals(setting, setting.equals("nil") ? "unknown" : "ABC123", expected);
            assertEquals(setting, expected, run(properties, "fakeof('SystemProperties.get/serial', 'get'," +
                    " 'R58M1234', { 'ro.serialno' }, " + settings + ")"));
            for (String hook : new String[]{"BlockGuardOs.open/serial", "UnixNativeDispatcher.open/serial"})
                assertEquals(setting, "ril.serialnumber=" + expected + " sys.serialnumber=" + expected,
                        area(properties, hook, settings, "ril.serialnumber", "R58M1234", "persist.sys.locale", "de-DE",
                                "sys.serialnumber", "R58M1234"));
        }
        // Only the property areas run the script for the hooks of opening files
        assertEquals("/dev/__properties__/*", run(properties, "filter(hookof('BlockGuardOs.open/serial', 'open'))[1]"));
        assertEquals("", area(properties, "BlockGuardOs.open/serial", "{}", "persist.sys.locale", "de-DE"));
    }

    // Serial numbers of manufacturers in properties, which some ROMs set, are the serial of Build.getSerial; the serial
    // number of the SIM card (ICC ID) in properties of vendors is empty, like getSimSerialNumber, in each case of its
    // name
    @Test
    public void serialsOfManufacturers() throws Exception {
        Globals properties = load("systemproperties_get");
        String settings = "{ ['value.serial'] = 'ABC123' }";
        for (String key : new String[]{"ro.boot.em.did", "persist.sys.lite.uid", "persist.radio.serialno",
                "persist.sys.oneplus.serialno"}) {
            assertEquals(key, "ABC123", run(properties, "fakeof('SystemProperties.get/serial', 'get', 'R58M1234', { '" +
                    key + "' }, " + settings + ")"));
        }
        for (String key : new String[]{"ril.iccid.sim1", "ril.iccid.sim2", "gsm.sim.preiccid_0", "persist.sys.tctPowerIccid",
                "persist.vendor.radio.cfu.iccid.1", "vendor.ril.ICCID"}) {
            assertEquals(key, "", run(properties, "fakeof('SystemProperties.get/serial', 'get', '8949012345678901234', { '" +
                    key + "' }, " + settings + ")"));
        }
        // The IMEI and MEID of vendors are empty like the ICC ID; names which only contain the letters stay
        for (String key : new String[]{"persist.sys.imei1", "gsm.device.imei0", "ro.ril.miui.imei0", "ril.IMEI",
                "persist.vendor.radio.meid", "imei"}) {
            assertEquals(key, "", run(properties, "fakeof('SystemProperties.get/serial', 'get', '490154203237518', { '" +
                    key + "' }, " + settings + ")"));
        }
        // The same in the property areas, which only tell these names, and the serials of manufacturers
        for (String key : new String[]{"ril.iccid.sim1", "gsm.sim.preiccid_0", "vendor.ril.ICCID", "persist.sys.imei1",
                "ril.IMEI", "persist.vendor.radio.meid"})
            assertEquals(key, key + "=", area(properties, "BlockGuardOs.open/serial", settings, key, "8949012345678901234"));
        assertEquals("persist.radio.serialno=ABC123", area(properties, "UnixNativeDispatcher.open/serial", settings,
                "persist.radio.serialno", "R58M1234", "persist.sys.timeinfo", "1700000000"));
        assertEquals("1700000000", run(properties, "fakeof('SystemProperties.get/serial', 'get', '1700000000', " +
                "{ 'persist.sys.timeinfo' }, " + settings + ")"));
        // Not of other variants, and other properties stay
        assertEquals("8949012345678901234", run(properties, "fakeof('SystemProperties.get/device', 'get', " +
                "'8949012345678901234', { 'ril.iccid.sim1' }, " + settings + ")"));
        assertEquals("R58M1234", run(properties, "fakeof('SystemProperties.get/serial', 'get', 'R58M1234', " +
                "{ 'persist.sys.locale' }, " + settings + ")"));
        // The digest of the key verifying the boot partitions, like the digest of vbmeta, names the manufacturer
        assertEquals("unknown", run(properties, "fakeof('SystemProperties.get/device', 'get', 'a1b2c3', " +
                "{ 'ro.boot.vbmeta.public_key_digest' })"));
    }

    // The first day of the quarter, for Build.VERSION.SECURITY_PATCH and its property; other texts stay
    @Test
    public void securityPatch() throws Exception {
        Globals globals = load("systemproperties_get");
        for (String date : new String[]{"2025-01-01", "2025-03-31", "2025-05-05", "2025-07-01", "2025-12-31",
                "2025-5-5", "unknown"}) {
            String expected = run(globals, "fakeof('Build.VERSION.SECURITY_PATCH', '#SECURITY_PATCH', '" + date + "')");
            assertEquals(date, expected, run(globals, "fakeof('SystemProperties.get/device', 'get', '" + date +
                    "', { 'ro.build.version.security_patch' })"));
        }
        assertEquals("2025-04-01", run(globals, "fakeof('Build.VERSION.SECURITY_PATCH', '#SECURITY_PATCH', '2025-05-05')"));
    }

    // The model and build in the user agent of WebView become K, like Chrome does, for the default user agent
    // and for the ones the app sets
    @Test
    public void userAgent() throws Exception {
        Globals globals = load("webview_constructor");
        String[][] agents = {
                {"Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP4A.250105.002; wv) AppleWebKit/537.36",
                        "Mozilla/5.0 (Linux; Android 15; K; wv) AppleWebKit/537.36"},
                {"Mozilla/5.0 (Linux; Android 10; SM-G973F Build/QP1A.190711.020) AppleWebKit/537.36",
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"},
                {"Mozilla/5.0 (Linux; Android 15; K; wv) AppleWebKit/537.36",
                        "Mozilla/5.0 (Linux; Android 15; K; wv) AppleWebKit/537.36"}};
        for (String[] ua : agents) {
            String expected = run(globals, "fakeof(nil, 'getDefaultUserAgent', '" + ua[0] + "')");
            assertEquals(ua[0], ua[1], expected);
            assertEquals(ua[0], expected, run(globals, "reduce('" + ua[0] + "')"));
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
        Globals identifiers = Scripts.load("identifier_services", STUBS + "\n" + uri);
        for (String authority : new String[]{"'com.android.contacts'", "'10@com.android.contacts'",
                "'+0@com.android.contacts'", "nil"}) {
            String expected = run(open, "tostring(authorityof(uriof(" + authority + ")))");
            assertEquals(authority, expected, run(query, "tostring(authorityof(uriof(" + authority + ")))"));
            assertEquals(authority, expected, run(hidden, "tostring(authorityof(uriof(" + authority + ")))"));
            assertEquals(authority, expected, run(identifiers, "tostring(authorityof(uriof(" + authority + ")))"));
        }
        // Names too, like ContentResolver.call(authority, ...), also of another user, like 0@cn.nubia.identity
        assertEquals("cn.nubia.identity", run(identifiers, "authorityof('0@cn.nubia.identity')"));
        assertEquals("com.android.contacts", run(open, "authorityof(uriof('10@com.android.contacts'))"));
        // Android parses everything before the last @ as a number, like Integer.parseInt, which takes a sign
        assertEquals("com.android.contacts", run(open, "authorityof(uriof('+0@com.android.contacts'))"));
    }

    // The languages are the same for the hooks (locale) and the settings provider (settingssecure_getstring), for each setting, also of tags without a region
    @Test
    public void localeIsTheSame() throws Exception {
        for (String function : new String[]{"fakes", "split"})
            assertEquals(function, Scripts.function("locale", function), Scripts.function("settingssecure_getstring", function));
        Globals locale = load("locale");
        Globals settings = load("settingssecure_getstring");
        for (String setting : new String[]{"{}", "{ ['value.locale'] = 'de-DE,fr' }", "{ ['value.locale'] = 'ja' }",
                "{ ['value.locale'] = 'zh-Hant-TW' }"}) {
            assertEquals(setting, run(locale, "fakeof('Settings.System.getStringForUser/locale', 'getStringForUser', " +
                            "'real', { nil, 'system_locales' }, " + setting + ")"),
                    run(settings, "select(2, fakesetting(paramof(nil, {}, " + setting + "), 'locale', 'system_locales', 'real'))"));
        }
    }

    // The folders of VPN interfaces which do not exist (hardware_files) are of the interfaces vpn leaves out
    @Test
    public void vpnInterfacesAreTheSame() throws Exception {
        assertEquals(Scripts.function("vpn", "isvpninterface"), Scripts.function("hardware_files", "isvpninterface"));
        assertEquals(Scripts.function("generic_empty", "emptylike"), Scripts.function("network_address", "emptylike"));
        assertEquals(Scripts.function("vpn", "isvpninterface"), Scripts.function("vpn_addresses", "isvpninterface"));
        assertEquals(Scripts.function("vpn", "linkproperties"), Scripts.function("vpn_addresses", "linkproperties"));
        assertEquals(Scripts.function("vpn", "linkproperties"), Scripts.function("network_address", "linkproperties"));
    }

    // 8 processors in /proc/cpuinfo (cpuinfo) and for Runtime.availableProcessors and the ranges of /sys (cpu_count)
    @Test
    public void processorsAreTheSame() throws Exception {
        String count = constantOf("cpu_count", "COUNT");
        assertEquals(count, constantOf("cpuinfo", "COUNT"));
        assertEquals("'0-" + (Integer.parseInt(count) - 1) + "\\n'", constantOf("cpu_count", "RANGE"));
    }

    // The partitions of the system whose fonts (hardware_files) and font configuration (font_config) are filtered
    @Test
    public void fontPartitionsAreTheSame() throws Exception {
        Globals files = load("hardware_files");
        Globals config = load("font_config");
        for (String partition : new String[]{"system", "product", "system_ext", "vendor", "odm", "oem", "my_bigball",
                "data", "apex", "mnt", "sdcard", "my"})
            assertEquals(partition, run(files, "tostring(brandfont('/" + partition + "/fonts/SamsungOne.ttf'))"),
                    run(config, "tostring(configpath('/" + partition + "/etc/fonts.xml'))"));
    }

    // The files of /sys which do not exist for some group (hardware_files), the others for none
    @Test
    public void hardwareFiles() throws Exception {
        // The folders named after apps depend on the apps installed, see FileScriptsTest.foldersOfHiddenAppsDoNotExist
        Globals files = Scripts.load("hardware_files", STUBS + "\n" +
                "function hiddenapp(param, name) return false end\n" +
                "function grouphook(group)\n" +
                "  return { getGroup = function(self) return group end,\n" +
                "    getClassName = function(self) return 'libcore.io.BlockGuardOs' end,\n" +
                "    getMethodName = function(self) return 'open' end,\n" +
                "    getReturnType = function(self) return nil end }\n" +
                "end");
        String[] groups = {"Read.Identifiers", "Use.Tracking", "Read.Biometrics", "Read.Network", "Read.Debugging",
                "Read.Features", "Get.Applications"};
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
                "/proc/self/root/sys/devices/system/chip-id/unique_id",
                "/proc/net/arp", "/proc/net/route", "/proc/self/net/if_inet6", "/proc/1/net/dev",
                "/proc/1/task/1/net/fib_trie",
                "/sys/devices/soc0/serial_number", "/sys/devices/soc0/soc_id", "/sys/devices/soc0",
                "/sys/bus/soc/devices/soc0/family", "/sys/block/sda/device/model", "/sys/class/block/sdb/device/vendor",
                "/sys/block/sda/device/rev", "/sys/devices/platform/soc/1d84000.ufshc/host0/target0:0:0/0:0:0:0/model",
                "/proc/scsi/scsi", "/proc/bus/input/devices", "/sys/class/camera/rear/rear_camfw",
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk",
                "/system/bin/magisk", "/debug_ramdisk/magisk", "/data/adb/magisk.db", "/proc/self/root/system/xbin/su",
                "/system/etc/permissions", "/vendor/etc/permissions/android.hardware.bluetooth.xml",
                "/product/etc/permissions/carrierwifi.xml", "/system/etc/sysconfig/google.xml",
                "/system/etc/permissions/privapp-permissions-meta.xml",
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "/sys/devices/system/cpu/cpu3/topology/core_id",
                "/sys/devices/system/cpu/modalias", "/sys/devices/system/cpu/cpu8", "/sys/devices/system/cpu/cpu9/online",
                "/sys/devices/system/cpu/cpu10", "/sys/devices/system/cpu/cpu127/online"};
        String[] stay = {
                "/sys/devices/system/cpux/online", "/sys/devices/system/cpu", "/sys/devices/system/cpu/cpu0",
                "/sys/devices/system/cpu/cpu7", "/sys/devices/system/cpu/cpu7/online",
                "/sys/class/sec/tspx",
                "/sys/bus/mmc/devices/mmc0:0001/cidx", "/sys/class/fingerprint/fingerprintx",
                "/sys/devices/system/chip-idx/unique_id", "/sys/class/scsi_host/host0/unique_id",
                "/sys/devices/platform/13100000.ufs/string_descriptors/product_revision",
                "/proc/sys/net/ipv4/ip_forward", "/proc/meminfo", "/proc/netx/arp",
                "/sys/devices/platform/soc/1d84000.ufshc", "/sys/block/sda/size", "/sys/block/sda/device/queue_depth",
                "/proc/bus/input/handlers", "/proc/cpuinfo", "/system/bin/sh", "/system/bin/sudo", "/sdcard/su",
                "/data/user/0/com.termux/files/usr/bin/su", "/system/xbin/sum", "/system/etc/hosts",
                "/system/etc/permissionsx"};
        // Each file of a sample which does not exist is hidden for a group at least, the others for none
        for (String[] paths : new String[][]{hidden, stay})
            for (String path : paths) {
                boolean any = false;
                for (String group : groups) {
                    any |= "true".equals(run(files, "tostring(hidden(grouphook('" + group + "'), '" + path + "'))"));
                }
                assertEquals(path, paths == hidden, any);
            }
    }

    // The files which opening hides (contentresolver_open) are of providers whose queries are hidden for the same
    // variant (contentresolver_query); the voicemails are of the variant mmssms for opening, of voicemail for queries
    @Test
    public void openedProvidersAreQueried() throws Exception {
        Globals query = Scripts.load("contentresolver_query", STUBS + "\n" +
                "luajava = { newInstance = function(name, columns) return 'fake' end }\n" +
                "function cursorof()\n" +
                "  return { getColumnNames = function(self) return {} end, close = function(self) end }\n" +
                "end\n" +
                "function uriof(authority)\n" +
                "  return { getAuthority = function(self) return authority end,\n" +
                "    getPath = function(self) return '/data/1' end }\n" +
                "end");
        Matcher pair = Pattern.compile("\\(name == '([^']*)' and authority == '([^']*)'\\)")
                .matcher(Scripts.function("contentresolver_open", "restricts"));
        int pairs = 0;
        while (pair.find()) {
            String authority = pair.group(2);
            String name = ("com.android.voicemail".equals(authority) ? "voicemail" : pair.group(1));
            assertEquals(name + " " + authority, "true", run(query, "tostring(after(hookof('ContentResolver.query/" +
                    name + "'), paramof(cursorof(), { uriof('" + authority + "') }, {})))"));
            pairs++;
        }
        assertEquals(5, pairs);
    }

    // The advertising id of Play services is zeros, from the client library and from the service
    @Test
    public void advertisingId() throws Exception {
        assertEquals(constantOf("adid_service", "ZEROS"),
                "'" + run(load("advertisingidclient$info_getid"), "fakeof('AdvertisingIdClient$Info.getId', 'getId', 'real')") + "'");
    }

    // The install times of other apps (ms) and the times of the folders of the storage (s) are the same time
    @Test
    public void installTimeIsTheTimeOfFolders() throws Exception {
        assertEquals(Long.parseLong(constantOf("storage_times", "FAKE")) * 1000,
                Long.parseLong(constantOf("packageinfo_createfromparcel", "FAKE")));
    }

    // The value of a constant of a script, like local NAME = 1 or local NAME = 'a', as it is written
    private static String constantOf(String script, String name) throws Exception {
        Matcher constant = Pattern.compile("\nlocal " + name + " = ('[^']*'|\\d+)\n").matcher(Scripts.read(script).replace("\r\n", "\n"));
        assertTrue(script + " " + name, constant.find());
        return constant.group(1);
    }
}
