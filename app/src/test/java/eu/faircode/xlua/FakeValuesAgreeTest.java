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
import static org.junit.Assert.assertTrue;

// Scripts which fake the same value each have it, since scripts cannot share code: the fake time zone,
// the operator of the test network and the Wi-Fi network, in the getters, the system properties and the texts
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
    }
}
