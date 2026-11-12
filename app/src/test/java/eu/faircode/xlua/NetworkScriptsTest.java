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

import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// The scripts of network, VPN, recording and analytics hooks with Lua stand-ins for the hook, the parameter
// and the Android objects, like LocationScriptTest, because the Android classes are stubs in unit tests
public class NetworkScriptsTest {
    private static final String STUBS = String.join("\n",
            "function hookof(method, class, group, types, name)",
            "  return { getMethodName = function(self) return method end,",
            "    getName = function(self) return name or method end,",
            "    getClassName = function(self) return class end,",
            "    getGroup = function(self) return group end,",
            "    getParameterTypes = function(self) return types end }",
            "end",
            // Scoped values per object, like XParam
            "values = setmetatable({}, { __mode = 'k' })",
            "function paramof(result, args, this, context)",
            "  return { result = result, args = args or {}, set = false,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value self.set = true end,",
            "    getArgument = function(self, index) return self.args[index] end,",
            "    getException = function(self) return nil end,",
            "    getThis = function(self) return this end,",
            "    isInstance = function(self, object, name) return checks:isInstance(object, name) end,",
            "    getPackageName = function(self) return 'com.example' end,",
            "    getApplicationContext = function(self) return context end,",
            "    findClass = function(self, name) assert(classes[name], name) return name end,",
            "    putValue = function(self, name, value, scope)",
            "      values[scope] = values[scope] or {} values[scope][name] = value end,",
            "    getValue = function(self, name, scope) return values[scope] and values[scope][name] end }",
            "end",
            "classes = {}");

    private static Globals load(String script) throws Exception {
        Globals globals = Scripts.load(script, STUBS);
        globals.set("checks", CoerceJavaToLua.coerce(new GenericScriptsTest.Checks()));
        return globals;
    }

    private static String tostring(Globals globals, String className, String text) {
        globals.set("text", LuaValue.valueOf(text));
        Varargs result = Scripts.run(globals,
                "local param = paramof(text)\n" +
                        "local restricted = after(hookof('toString', '" + className + "'), param)\n" +
                        "return param.result, restricted");
        return result.arg(1).tojstring();
    }

    // The version of Android for the scripts, which the stubs of the unit tests do not have
    private static void sdk(Globals globals, int sdk) {
        Scripts.run(globals, "local real = luajava\n" +
                "luajava = setmetatable({ bindClass = function(name)\n" +
                "  if name == 'android.os.Build$VERSION' then return { SDK_INT = " + sdk + " } end\n" +
                "  return real.bindClass(name) end }, { __index = real })");
    }

    // Read.Identifiers: only the MAC address of the text of WifiInfo, like getMacAddress, the network stays to Read.Network
    @Test
    public void wifiInfoTextHidesTheMacForIdentifiers() throws Exception {
        Globals globals = load("network_tostring");
        globals.set("text", LuaValue.valueOf("SSID: \"Home\", BSSID: 12:34:56:78:9a:bc, MAC: 3c:28:6d:11:22:33, IP: /192.168.1.5"));
        Varargs result = Scripts.run(globals,
                "local param = paramof(text)\n" +
                        "local restricted = after(hookof('toString', 'android.net.wifi.WifiInfo', 'Read.Identifiers', nil, " +
                        "'WifiInfo.toString/mac'), param)\n" +
                        "return param.result, restricted");
        assertEquals("SSID: \"Home\", BSSID: 12:34:56:78:9a:bc, MAC: 02:00:00:00:00:00, IP: /192.168.1.5", result.arg(1).tojstring());
        assertTrue(result.arg(2).toboolean());
    }

    @Test
    public void linkPropertiesTextHidesAddresses() throws Exception {
        Globals globals = load("network_tostring");
        // Android 10 and later, with a stacked link like of 464XLAT
        String text = "{InterfaceName: wlan0 LinkAddresses: [ fe80::1234/64,192.168.1.5/24 ]" +
                " DnsAddresses: [ /192.168.1.1,/fd00::1 ] UsePrivateDns: true PrivateDnsServerName: abc.dns.nextdns.io" +
                " ValidatedPrivateDnsAddresses: [45.90.28.0,45.90.30.0] Domains: corp.example home.lan MTU: 1500" +
                " ServerAddress: /192.168.1.1 TcpBufferSizes: 524288,1048576" +
                " Routes: [ fe80::/64 -> :: wlan0 mtu 0,0.0.0.0/0 -> 192.168.1.1 wlan0 mtu 0 ]" +
                " Nat64Prefix: 64:ff9b::/96" +
                " Stacked: [[ {InterfaceName: v4-wlan0 LinkAddresses: [ 192.0.0.4/32 ] DnsAddresses: [ ]" +
                " Domains: null MTU: 0 Routes: [ 0.0.0.0/0 -> 0.0.0.0 v4-wlan0 mtu 0 ]} ]]}";
        assertEquals("{InterfaceName: wlan0 LinkAddresses: [ ] DnsAddresses: [ ] UsePrivateDns: true" +
                        " Domains: null MTU: 1500 TcpBufferSizes: 524288,1048576 Routes: [ ]" +
                        " Stacked: [[ {InterfaceName: v4-wlan0 LinkAddresses: [ ] DnsAddresses: [ ]" +
                        " Domains: null MTU: 0 Routes: [ ]} ]]}",
                tostring(globals, "android.net.LinkProperties", text));
        // The NAT64 prefix at the end, after the proxy, which stays
        assertEquals("{LinkAddresses: [ ] DnsAddresses: [ ] Domains: null MTU: 0 Routes: [ ] HttpProxy: [proxy.example] 8080}",
                tostring(globals, "android.net.LinkProperties", "{LinkAddresses: [ ] DnsAddresses: [ ] Domains: null MTU: 0" +
                        " Routes: [ ] HttpProxy: [proxy.example] 8080 Nat64Prefix: 2001:db8:64::/96}"));

        // Android 9 and before
        String old = "{InterfaceName: wlan0 LinkAddresses: [fe80::1234/64,192.168.1.5/24,]  " +
                "Routes: [0.0.0.0/0 -> 192.168.1.1 wlan0,] DnsAddresses: [192.168.1.1,] UsePrivateDns: false " +
                "PrivateDnsServerName: dns.example Domains: home.lan MTU: 0}";
        assertEquals("{InterfaceName: wlan0 LinkAddresses: []  Routes: [] DnsAddresses: [] UsePrivateDns: false " +
                        "PrivateDnsServerName: null Domains: null MTU: 0}",
                tostring(globals, "android.net.LinkProperties", old));

        // Nothing to hide
        Varargs none = Scripts.run(globals, "local param = paramof('{LinkAddresses: [ ] DnsAddresses: [ ] Domains: null" +
                " MTU: 0 Routes: [ ]}')\nreturn after(hookof('toString', 'android.net.LinkProperties'), param), param.set");
        assertFalse(none.arg(1).toboolean());
        assertFalse(none.arg(2).toboolean());
    }

    // The cell of each network registration, also in the text of a service state
    @Test
    public void registrationTextHidesCell() throws Exception {
        Globals globals = load("network_tostring");
        String info = "NetworkRegistrationInfo{ domain=%s transportType=WWAN registrationState=HOME" +
                " availableServices=[DATA] cellIdentity=%s voiceSpecificInfo=null dataSpecificInfo=null" +
                " nrState=NONE rRplmn=26201}";
        String cell = "CellIdentityLte:{ mCi=12345678 mPci=101 mTac=4321 mMcc=262 mMnc=01" +
                " mAlphaLong=Telekom mAdditionalPlmns={} mCsgInfo=null}";
        String text = "{mVoiceRegState=0(IN_SERVICE), mNetworkRegistrationInfos=[" +
                String.format(info, "CS", cell) + ", " + String.format(info, "PS", cell) + "]}";
        assertEquals("{mVoiceRegState=0(IN_SERVICE), mNetworkRegistrationInfos=[" +
                        String.format(info, "CS", "null") + ", " + String.format(info, "PS", "null") + "]}",
                tostring(globals, "android.telephony.NetworkRegistrationInfo", text));

        // Android 9 names it NetworkRegistrationState, with other labels
        String state = "NetworkRegistrationState{transportType=1 domain=PS regState=HOME accessNetworkTechnology=LTE" +
                " reasonForDenial=0 emergencyEnabled=false supportedServices=[2] cellIdentity=%s" +
                " voiceSpecificStates=null dataSpecificStates=null}";
        assertEquals(String.format(state, "null"),
                tostring(globals, "android.telephony.NetworkRegistrationState", String.format(state, cell)));
    }

    @Test
    public void wifiTexts() throws Exception {
        Globals globals = load("network_tostring");
        sdk(globals, 31);
        assertEquals("", tostring(globals, "android.net.wifi.p2p.WifiP2pDeviceList",
                "\nDevice: Anna's phone\n deviceAddress: 12:34:56:78:9a:bc"));
        // A group without its clients, like getClientList, of each version (AOSP oreo-mr1 to main)
        String owner = "network: DIRECT-xy-Phone\n isGO: true\n GO: Device: \n deviceAddress: 02:00:00:00:00:00\n status: 0";
        String client = "\n Client: Device: Anna's phone\n deviceAddress: 12:34:56:78:9a:bc\n wfdInfo: null";
        for (String rest : new String[]{"\n interface: p2p-wlan0-0\n networkId: -1",
                "\n interface: p2p-wlan0-0\n networkId: -1\n securityType: 0\n frequency: 2437\n vendorData: []"}) {
            assertEquals(owner + rest, tostring(globals, "android.net.wifi.p2p.WifiP2pGroup", owner + client + client + rest));
            assertEquals(owner + rest, tostring(globals, "android.net.wifi.p2p.WifiP2pGroup", owner + rest));
        }
        // The fake network of the getters, its name with its access point and id
        String wifi = tostring(globals, "android.net.wifi.WifiInfo",
                "SSID: \"Home\", BSSID: 12:34:56:78:9a:bc, MAC: 02:00:00:00:00:00, IP: /192.168.1.5, Security type: 2, " +
                        "Net ID: 3, Metered hint: false");
        assertEquals("SSID: \"private\", BSSID: a6:3e:91:5c:0d:72, MAC: 02:00:00:00:00:00, IP: null, " +
                "Security type: 2, Net ID: 0, Metered hint: false", wifi);
        // The network Android hides from apps without location permission stays hidden, the access point and the id too
        globals.set("text", LuaValue.valueOf("SSID: <unknown ssid>, BSSID: 02:00:00:00:00:00, MAC: 02:00:00:00:00:00, " +
                "Security type: 2, Net ID: -1, Metered hint: false"));
        Varargs hidden = Scripts.run(globals, "local param = paramof(text)\n" +
                "return after(hookof('toString', 'android.net.wifi.WifiInfo'), param), param.set");
        assertFalse(hidden.arg(1).toboolean());
        assertFalse(hidden.arg(2).toboolean());
        // Android 8.1 shows no access point as zeros when Wi-Fi is on but not connected, which stay like the getter
        String disconnected = tostring(globals, "android.net.wifi.WifiInfo", "SSID: , BSSID: 00:00:00:00:00:00, " +
                "MAC: 02:00:00:00:00:00, Supplicant state: DISCONNECTED, RSSI: -127, Link speed: -1Mbps, Frequency: -1MHz, " +
                "Net ID: -1, Metered hint: false, score: 0");
        assertTrue(disconnected, disconnected.contains(", BSSID: 00:00:00:00:00:00, "));
        // A network name can contain ', ', so it is hidden up to the MLO information or the end of the text
        String key = tostring(globals, "android.net.wifi.WifiInfo", "SSID: \"Home\", BSSID: 12:34:56:78:9a:bc, " +
                "Requesting package name: <none>\"Home, 5G\"psk");
        assertFalse(key, key.contains("5G"));
        String mlo = tostring(globals, "android.net.wifi.WifiInfo", "SSID: \"Home\", BSSID: 12:34:56:78:9a:bc, " +
                "Requesting package name: <none>\"Home, 5G\"pskMLO Information: , AP MLD Address: 00:11:22:33:44:55");
        assertFalse(mlo, mlo.contains("5G"));
        assertTrue(mlo, mlo.contains("MLO Information: "));
        // From Android 12 the text has getSSID, also its hex of a name which is no UTF-8, which the getter fakes
        assertTrue(tostring(globals, "android.net.wifi.WifiInfo", "SSID: 0x48e96d65, BSSID: 02:00:00:00:00:00, MAC: " +
                "02:00:00:00:00:00").startsWith("SSID: \"private\", BSSID: "));

        // Before Android 12 the name itself, without quotes, and the empty name of an app without location permission
        // stays, like getSSID keeps <unknown ssid> for it
        sdk(globals, 30);
        assertEquals("SSID: private, BSSID: a6:3e:91:5c:0d:72, MAC: 02:00:00:00:00:00, Supplicant state: COMPLETED, " +
                "RSSI: -50, Net ID: 0, Metered hint: false, score: 60", tostring(globals, "android.net.wifi.WifiInfo",
                "SSID: MyNet, BSSID: 12:34:56:78:9a:bc, MAC: 02:00:00:00:00:00, Supplicant state: COMPLETED, " +
                        "RSSI: -50, Net ID: 3, Metered hint: false, score: 60"));
        // An updated Wi-Fi module of Android 11 prints getSSID, with quotes
        assertTrue(tostring(globals, "android.net.wifi.WifiInfo", "SSID: \"MyNet\", BSSID: 02:00:00:00:00:00, MAC: " +
                "02:00:00:00:00:00").startsWith("SSID: \"private\", BSSID: "));
        for (String name : new String[]{"", "<unknown ssid>"}) {
            globals.set("text", LuaValue.valueOf("SSID: " + name + ", BSSID: 02:00:00:00:00:00, MAC: 02:00:00:00:00:00, " +
                    "Supplicant state: COMPLETED, RSSI: -50, Net ID: -1, Metered hint: false, score: 60"));
            Varargs kept = Scripts.run(globals, "local param = paramof(text)\n" +
                    "return after(hookof('toString', 'android.net.wifi.WifiInfo'), param), param.set");
            assertFalse(name, kept.arg(1).toboolean());
            assertFalse(name, kept.arg(2).toboolean());
        }
    }

    // The multi-link information is <none>, like Android prints it without an MLD address, which the getters hide with
    // the link id and the links; the vendor data of Android 15 follows it
    @Test
    public void wifiTextHidesLinks() throws Exception {
        Globals globals = load("network_tostring");
        String start = "SSID: \"private\", BSSID: 02:00:00:00:00:00, Requesting package name: <none><none>MLO Information: " +
                ", Is TID-To-Link negotiation supported by the AP: true, AP MLD Address: ";
        String none = "<none>, AP MLO Link Id: <none>, AP MLO Affiliated links: <none>";
        String links = "00:11:22:33:44:55, AP MLO Link Id: 1, AP MLO Affiliated links: [MloLink{6GHz, channel: 37, id: 1, " +
                "state: MLO_LINK_STATE_ACTIVE, RSSI: -50, AP MAC Address: 00:11:22:33:44:66}, MloLink{5GHz, channel: 36, id: 0}]";
        assertEquals(start + none, tostring(globals, "android.net.wifi.WifiInfo", start + links));
        assertEquals(start + none + ", Vendor Data: <none>",
                tostring(globals, "android.net.wifi.WifiInfo", start + links + ", Vendor Data: <none>"));
        // Without multi-link operation the text stays
        globals.set("text", LuaValue.valueOf(start + none));
        assertFalse(Scripts.run(globals, "return after(hookof('toString', 'android.net.wifi.WifiInfo'), paramof(text))")
                .arg1().toboolean());
    }

    @Test
    public void wifiGettersKeepHiddenValues() throws Exception {
        Globals globals = load("wifiinfo_ssid_bssid");
        Varargs result = Scripts.run(globals,
                "local unknown = paramof('<unknown ssid>')\n" +
                        "local hidden = paramof('02:00:00:00:00:00')\n" +
                        "local ssid = paramof('\"Home\"')\n" +
                        "local bssid = paramof('12:34:56:78:9a:bc')\n" +
                        "return after(hookof('getSSID'), unknown), after(hookof('getBSSID'), hidden),\n" +
                        "  after(hookof('getSSID'), ssid), ssid.result, after(hookof('getBSSID'), bssid), bssid.result,\n" +
                        "  after(hookof('getNetworkId'), paramof(-1)), (function() local id = paramof(3)\n" +
                        "    return after(hookof('getNetworkId'), id), id.result end)(),\n" +
                        "  after(hookof('getBSSID'), paramof('00:00:00:00:00:00'))");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertEquals("\"private\"", result.arg(4).tojstring());
        // A fake access point and id with the fake name, because Android never hides only the access point and the id:
        // a locally administered address, which belongs to no real access point
        assertTrue(result.arg(5).toboolean());
        assertEquals("a6:3e:91:5c:0d:72", result.arg(6).tojstring());
        assertEquals(2, Integer.parseInt(result.arg(6).tojstring().substring(0, 2), 16) & 3);
        // The id of the saved network, -1 when not connected or hidden by Android stays, else the fake 0, also on
        // Android 8.1 and 9, which never hide it
        assertFalse(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
        assertEquals(0, result.arg(9).toint());
        // Android 8.1 shows no access point as zeros when Wi-Fi is on but not connected, with <unknown ssid> and id -1
        assertFalse(result.arg(10).toboolean());
    }

    // The link id of a Wi-Fi 7 access point is -1, like without multi-link operation, which its hidden MLD address and
    // links tell; -1 stays
    @Test
    public void wifiLinkIdIsInvalid() throws Exception {
        Globals globals = load("wifiinfo_ssid_bssid");
        Varargs result = Scripts.run(globals,
                "local link = paramof(1)\n" +
                        "return after(hookof('getApMloLinkId'), link), link.result, after(hookof('getApMloLinkId'), paramof(-1))");
        assertTrue(result.arg(1).toboolean());
        assertEquals(-1, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
    }

    // The name as bytes is the same network as getSSID: a real name becomes the fake one, unknown and empty ones stay
    @Test
    public void wifiSsidAgreesWithGetSsid() throws Exception {
        Globals globals = load("wifiinfo_ssid_bssid");
        Varargs result = Scripts.run(globals,
                "local real = luajava\n" +
                        "sdk = 33\n" +
                        "luajava = setmetatable({ bindClass = function(name)\n" +
                        "  if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end\n" +
                        "  if name ~= 'android.net.wifi.WifiSsid' then return real.bindClass(name) end\n" +
                        // Android 13 quotes the text, before it is the name itself
                        "  return { fromBytes = function(self, bytes)\n" +
                        "    local text = '\"' .. tostring(real.newInstance('java.lang.String', bytes)) .. '\"'\n" +
                        "    return { toString = function() return text end } end,\n" +
                        "    createFromAsciiEncoded = function(self, name) return { toString = function() return name end } end } end },\n" +
                        "  { __index = real })\n" +
                        "local function ssidof(text) return { toString = function() return text end } end\n" +
                        "local home = paramof(ssidof('\"Home\"'))\n" +
                        "local ssid = paramof('\"Home\"')\n" +
                        "after(hookof('getSSID'), ssid)\n" +
                        "return after(hookof('getWifiSsid'), home), home.result:toString(), ssid.result,\n" +
                        "  after(hookof('getWifiSsid'), paramof(ssidof('<unknown ssid>'))),\n" +
                        "  after(hookof('getWifiSsid'), paramof(ssidof(''))),\n" +
                        "  (function() sdk = 30 local old = paramof(ssidof('Home')) after(hookof('getWifiSsid'), old)\n" +
                        "    return old.result:toString() end)(), after(hookof('getWifiSsid'), paramof(ssidof('private')))");
        assertTrue(result.arg(1).toboolean());
        assertEquals(result.arg(3).tojstring(), result.arg(2).tojstring());
        assertFalse(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
        // Before Android 13 the hidden method, of a WifiSsid whose text has no quotes
        assertEquals("private", result.arg(6).tojstring());
        assertFalse(result.arg(7).toboolean());
    }

    // The local address of sockets: those of the device become the wildcard address of their family, also in a
    // socket address with its port; loopback, wildcard, link-local and other socket addresses stay
    @Test
    public void localAddressOfSocketsIsHidden() throws Exception {
        Globals globals = load("network_address");
        Varargs result = Scripts.run(globals,
                "local inet = luajava.bindClass('java.net.InetAddress')\n" +
                        "local function bound(method, value)\n" +
                        "  local param = paramof(value)\n" +
                        "  return tostring(after(hookof(method), param)) .. ' ' .. tostring(param.result)\n" +
                        "end\n" +
                        "local function socket(host, port)\n" +
                        "  return luajava.newInstance('java.net.InetSocketAddress', inet:getByName(host), port)\n" +
                        "end\n" +
                        "return bound('getLocalAddress', inet:getByName('192.168.1.5')),\n" +
                        "  bound('getLocalAddress', inet:getByName('2001:db8::5')),\n" +
                        "  bound('getLocalSocketAddress', socket('10.0.0.7', 4321)),\n" +
                        "  bound('getsockname', socket('2001:db8::5', 443)),\n" +
                        "  bound('getLocalAddress', inet:getByName('127.0.0.1')),\n" +
                        "  bound('getLocalAddress', inet:getByName('0.0.0.0')),\n" +
                        "  bound('getLocalAddress', inet:getByName('fe80::1')),\n" +
                        "  bound('getLocalSocketAddress', socket('::1', 80)),\n" +
                        "  bound('getsockname', 'unix'),\n" +
                        "  bound('ioctlInetAddress', inet:getByName('255.255.255.0'))");
        assertEquals("true /0.0.0.0", result.arg(1).tojstring());
        assertEquals("true /0:0:0:0:0:0:0:0", result.arg(2).tojstring());
        assertEquals("true /0.0.0.0:4321", result.arg(3).tojstring());
        assertEquals("true /[0:0:0:0:0:0:0:0]:443", result.arg(4).tojstring());
        assertEquals("false /127.0.0.1", result.arg(5).tojstring());
        assertEquals("false /0.0.0.0", result.arg(6).tojstring());
        assertEquals("false /fe80:0:0:0:0:0:0:1", result.arg(7).tojstring());
        assertEquals("false /[0:0:0:0:0:0:0:1]:80", result.arg(8).tojstring());
        assertEquals("false unix", result.arg(9).tojstring());
        // The values of an interface by ioctl are decided before the call, by the address of the interface
        assertEquals("false /255.255.255.0", result.arg(10).tojstring());
    }

    // The text of a socket channel has the same fake local address as getLocalAddress, written the same way; an
    // address which stays, a closed channel and one which is not bound stay as they are
    @Test
    public void localAddressInTextOfChannels() throws Exception {
        Globals globals = load("network_address");
        Varargs result = Scripts.run(globals,
                "local inet = luajava.bindClass('java.net.InetAddress')\n" +
                        "local function text(host, closed)\n" +
                        "  local bound = host and luajava.newInstance('java.net.InetSocketAddress', inet:getByName(host), 41234)\n" +
                        "  local channel = { getLocalAddress = function(self)\n" +
                        "    if closed then error('java.nio.channels.ClosedChannelException') end\n" +
                        "    return bound\n" +
                        "  end }\n" +
                        "  local param = paramof('java.nio.channels.SocketChannel[connected local=' ..\n" +
                        "    (bound and bound:toString() or '') .. ' remote=example.com/93.184.215.14:443]', {}, channel)\n" +
                        "  param.unhooked = function(self, call) return call() end\n" +
                        "  return tostring(after(hookof('toString'), param)) .. ' ' .. param.result\n" +
                        "end\n" +
                        "local broken = paramof('java.nio.channels.SocketChannel[connected local=/192.168.1.23:41234]', {}, {})\n" +
                        "broken.unhooked = function(self, call) return call() end\n" +
                        "return text('192.168.1.23'), text('2001:db8::5'), text('127.0.0.1'), text('192.168.1.23', true),\n" +
                        "  text(nil), pcall(after, hookof('toString'), broken)");
        String remote = " remote=example.com/93.184.215.14:443]";
        assertEquals("true java.nio.channels.SocketChannel[connected local=/0.0.0.0:41234" + remote, result.arg(1).tojstring());
        assertEquals("true java.nio.channels.SocketChannel[connected local=/[0:0:0:0:0:0:0:0]:41234" + remote,
                result.arg(2).tojstring());
        assertEquals("false java.nio.channels.SocketChannel[connected local=/127.0.0.1:41234" + remote, result.arg(3).tojstring());
        assertEquals("false java.nio.channels.SocketChannel[connected local=/192.168.1.23:41234" + remote,
                result.arg(4).tojstring());
        assertEquals("false java.nio.channels.SocketChannel[connected local=" + remote, result.arg(5).tojstring());
        // An error of the script goes to the log of the hook
        assertFalse(result.arg(6).toboolean());
    }

    // The values of an interface by ioctl (SIOCGIFADDR, SIOCGIFNETMASK, SIOCGIFBRDADDR, SIOCGIFDSTADDR) come from its
    // address: when that is not loopback the name gets a colon, so that Linux finds the interface but no address and
    // fails with EADDRNOTAVAIL; a name with no room for the colon gets that error from the script. Loopback, other
    // commands, interfaces without an IPv4 address and other methods stay as they are
    @Test
    public void addressesOfInterfacesByIoctlFail() throws Exception {
        Globals globals = load("network_address");
        Varargs result = Scripts.run(globals,
                "local inet = luajava.bindClass('java.net.InetAddress')\n" +
                        "local addresses = { lo = '127.0.0.1', wlan0 = '192.168.1.5', ['wlan0:1'] = '10.0.0.2',\n" +
                        "  abcdefghijklmno = '10.0.0.9' }\n" +
                        "local libcore = { ioctlInetAddress = function(self, fd, cmd, name)\n" +
                        "  assert(fd == 'fd' and cmd == 0x8915, cmd)\n" +
                        "  if addresses[name] == nil then error('android.system.ErrnoException: ioctl failed: EADDRNOTAVAIL') end\n" +
                        "  return inet:getByName(addresses[name])\n" +
                        "end }\n" +
                        "local function ioctl(cmd, name, method)\n" +
                        "  local param = paramof(nil, { [0] = 'fd', cmd, name }, libcore)\n" +
                        "  param.unhooked = function(self, call) return call() end\n" +
                        "  param.setArgument = function(self, index, value) self.args[index] = value end\n" +
                        "  local changed, old, new = before(hookof(method or 'ioctlInetAddress'), param)\n" +
                        "  return tostring(changed) .. ' ' .. tostring(param.args[2]) .. ' ' .. (param.set and 'thrown' or '-') ..\n" +
                        "    ' ' .. tostring(old) .. ' ' .. tostring(new), param.result\n" +
                        "end\n" +
                        "local broken = paramof(nil, { [0] = 'fd', 0x8915, 'wlan0' }, {})\n" +
                        "broken.unhooked = function(self, call) return call() end\n" +
                        "return pcall(before, hookof('ioctlInetAddress'), broken), ioctl(0x8915, 'wlan0'), ioctl(0x891b, 'wlan0'),\n" +
                        "  ioctl(0x8919, 'wlan0:1'), ioctl(0x8917, 'wlan0'), ioctl(0x8915, 'lo'), ioctl(0x891b, 'lo'),\n" +
                        "  ioctl(0x8915, 'dummy0'), ioctl(0x8927, 'wlan0'), ioctl(0x8915, nil),\n" +
                        "  ioctl(0x8915, 'wlan0', 'getLocalAddress'), ioctl(0x891b, 'abcdefghijklmno')");
        // An error of the script goes to the log of the hook
        assertFalse(result.arg(1).toboolean());
        assertEquals("true wlan0: - 192.168.1.5 EADDRNOTAVAIL", result.arg(2).tojstring());
        assertEquals("true wlan0: - 192.168.1.5 EADDRNOTAVAIL", result.arg(3).tojstring());
        assertEquals("true wlan0:1: - 10.0.0.2 EADDRNOTAVAIL", result.arg(4).tojstring());
        assertEquals("true wlan0: - 192.168.1.5 EADDRNOTAVAIL", result.arg(5).tojstring());
        assertEquals("false lo - nil nil", result.arg(6).tojstring());
        assertEquals("false lo - nil nil", result.arg(7).tojstring());
        assertEquals("false dummy0 - nil nil", result.arg(8).tojstring());
        assertEquals("false wlan0 - nil nil", result.arg(9).tojstring());
        assertEquals("false nil - nil nil", result.arg(10).tojstring());
        assertEquals("false wlan0 - nil nil", result.arg(11).tojstring());
        assertEquals("true abcdefghijklmno thrown 10.0.0.9 EADDRNOTAVAIL", result.arg(12).tojstring());
        assertTrue(result.arg(13).touserdata() instanceof android.system.ErrnoException);
    }

    @Test
    public void privateDnsServerNameIsHidden() throws Exception {
        Globals globals = load("network_address");
        Varargs result = Scripts.run(globals, "local param = paramof('abc.dns.nextdns.io')\n" +
                "return after(hookof('getPrivateDnsServerName'), param), param.result, param.set");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());
        assertTrue(result.arg(3).toboolean());
    }

    // The addresses of a network interface, which is how apps read the IP address of the device: only loopback stays.
    // The enumeration is consumed, so the result is a new one also when nothing is left out
    @Test
    public void addressesOfInterfacesKeepLoopback() throws Exception {
        Globals globals = load("network_address");
        Varargs result = Scripts.run(globals,
                "local inet = luajava.bindClass('java.net.InetAddress')\n" +
                        "local collections = luajava.bindClass('java.util.Collections')\n" +
                        "local function listof(...)\n" +
                        "  local list = luajava.newInstance('java.util.ArrayList')\n" +
                        "  for _, item in ipairs({ ... }) do list:add(item) end\n" +
                        "  return list\n" +
                        "end\n" +
                        "local function bound(host)\n" +
                        "  return { getAddress = function(self) return host and inet:getByName(host) end }\n" +
                        "end\n" +
                        "local loopback = inet:getByName('127.0.0.1')\n" +
                        "local device = paramof(collections:enumeration(listof(loopback, inet:getByName('192.168.1.5'),\n" +
                        "  inet:getByName('fe80::1'))))\n" +
                        "local plain = paramof(collections:enumeration(listof(loopback)))\n" +
                        "local bindings = paramof(listof(bound('192.168.1.5'), bound('127.0.0.1'), bound(nil)))\n" +
                        "local plainbindings = paramof(listof(bound('127.0.0.1')))\n" +
                        "local removed = after(hookof('getInetAddresses'), device)\n" +
                        "local kept = collections:list(device.result)\n" +
                        "local unchanged = after(hookof('getInetAddresses'), plain)\n" +
                        "return removed, kept:size(), kept:get(0):getHostAddress(),\n" +
                        "  unchanged, plain.set, collections:list(plain.result):size(),\n" +
                        "  after(hookof('getInterfaceAddresses'), bindings), bindings.result:size(),\n" +
                        "  bindings.result:get(0):getAddress():getHostAddress(),\n" +
                        "  after(hookof('getInterfaceAddresses'), plainbindings), plainbindings.result:size()");
        assertTrue(result.arg(1).toboolean());
        assertEquals(1, result.arg(2).toint());
        assertEquals("127.0.0.1", result.arg(3).tojstring());
        assertFalse(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertEquals(1, result.arg(6).toint());
        assertTrue(result.arg(7).toboolean());
        assertEquals(1, result.arg(8).toint());
        assertEquals("127.0.0.1", result.arg(9).tojstring());
        assertFalse(result.arg(10).toboolean());
        assertEquals(1, result.arg(11).toint());
    }

    // A VPN over mobile data, the networks of the connectivity manager as Lua sees them
    private static final String NETWORKS = String.join("\n",
            "function infoof(type, name) return { type = type, name = name,",
            "  getType = function(self) return self.type end,",
            "  isConnectedOrConnecting = function(self) return true end,",
            "  getTypeName = function(self) return self.name end } end",
            "function lpof(name) return { getInterfaceName = function(self) return name end } end",
            "vpninfo = infoof(17, 'VPN')",
            "mobileinfo = infoof(0, 'MOBILE')",
            "infos = { vpn = vpninfo, mobile = mobileinfo }",
            "lps = { vpn = lpof('tun0'), mobile = lpof('rmnet_data0') }",
            "cm = { getActiveNetworkInfo = function(self) return mobileinfo end,",
            "  getAllNetworks = function(self) return { length = 2, 'vpn', 'mobile' } end,",
            "  getNetworkInfo = function(self, network) return infos[network] end,",
            "  getLinkProperties = function(self, network) return lps[network] end }",
            "context = { getSystemService = function(self, name) assert(name == 'connectivity') return cm end }",
            // Capabilities with their real transports, which the script reads without the hooks
            "function capsof(...)",
            "  local transports = {}",
            "  for _, transport in ipairs({ ... }) do transports[transport] = true end",
            "  return { hasTransport = function(self, transport) return transports[transport] == true end }",
            "end",
            "local plain = paramof",
            "function paramof(...)",
            "  local param = plain(...)",
            "  param.unhooked = function(self, call) return call() end",
            "  return param",
            "end");

    @Test
    public void vpnFollowsUnderlyingNetwork() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        Varargs result = Scripts.run(globals,
                "local type = paramof(17, {}, vpninfo, context)\n" +
                        "local name = paramof('VPN', {}, vpninfo, context)\n" +
                        "local iface = paramof('tun0', {}, nil, context)\n" +
                        "return after(hookof('getType'), type), type.result,\n" +
                        "  after(hookof('getTypeName'), name), name.result,\n" +
                        "  after(hookof('getInterfaceName'), iface), iface.result");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).toint());
        assertTrue(result.arg(3).toboolean());
        assertEquals("MOBILE", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertEquals("rmnet_data0", result.arg(6).tojstring());

        // Without the connectivity manager, like without permission
        Varargs fallback = Scripts.run(globals,
                "local type = paramof(17, {}, vpninfo, { getSystemService = function() error('denied') end })\n" +
                        "return after(hookof('getType'), type), type.result");
        assertTrue(fallback.arg(1).toboolean());
        assertEquals(1, fallback.arg(2).toint());
    }

    // The index of the interface of a VPN by its name and back is like for an interface which does not exist
    @Test
    public void vpnInterfaceIndexIsMissing() throws Exception {
        Globals globals = load("vpn");
        Varargs result = Scripts.run(globals,
                "local byname = paramof(27, { [0] = 'tun0' })\n" +
                        "local wifi = paramof(12, { [0] = 'wlan0' })\n" +
                        "local byindex = paramof('tun0', { [0] = 27 })\n" +
                        "local tunnel = paramof('tunl0', { [0] = 2 })\n" +
                        "return after(hookof('if_nametoindex'), byname), byname.result, after(hookof('if_nametoindex'), wifi),\n" +
                        "  after(hookof('if_indextoname'), byindex), byindex.result, after(hookof('if_indextoname'), tunnel)");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).isnil());
        assertFalse(result.arg(6).toboolean());
    }

    // The fake value of a hook calls the same hook, which then sees the real value
    @Test
    public void vpnNestedCallIsReal() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        Varargs result = Scripts.run(globals,
                "local nested\n" +
                        "cm.getActiveNetworkInfo = function(self)\n" +
                        "  nested = after(hookof('getType'), paramof(17, {}, vpninfo, context))\n" +
                        "  return mobileinfo\n" +
                        "end\n" +
                        "local type = paramof(17, {}, vpninfo, context)\n" +
                        "return after(hookof('getType'), type), type.result, nested");
        assertTrue(result.arg(1).toboolean());
        assertEquals(0, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
    }

    // The addresses, DNS servers, search domains, MTU and routes in the text of the LinkProperties of a VPN are the ones of the
    // network under it, like its getters (see vpn_addresses), which find it the same way, by the real interface names; the other
    // fields stay the VPN's, like their getters, so the NAT64 prefix, stacked links and DHCP server of that network do not show.
    // The VPN itself and an IWLAN network on ipsec1, which the hooks name like the network under the VPN, are never that network.
    @Test
    public void vpnTextOfNetworkUnder() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        globals.set("mobile", LuaValue.valueOf("{InterfaceName: rmnet_data0 LinkAddresses: [ 2001:db8::7/64 ]" +
                " DnsAddresses: [ /2001:db8::1 ] Domains: null MTU: 1500 Routes: [ ::/0 -> fe80::1%rmnet_data0 rmnet_data0 mtu 1500 ]" +
                " Nat64Prefix: 64:ff9b::/96 Stacked: [[ {InterfaceName: v4-rmnet_data0 LinkAddresses: [ 192.0.0.4/32 ]" +
                " DnsAddresses: [ ] Domains: stacked.example MTU: 1480 Routes: [ 0.0.0.0/0 -> 0.0.0.0 v4-rmnet_data0 mtu 1480 ]} ]]}"));
        globals.set("wifi", LuaValue.valueOf("{InterfaceName: wlan0 LinkAddresses: [ 192.168.1.5/24 ]" +
                " DnsAddresses: [ /192.168.1.1 ] PrivateDnsServerName: dns.home Domains: home lan MTU: 0" +
                " ServerAddress: /192.168.1.1 TcpBufferSizes: 524288,1048576 Routes: [ 0.0.0.0/0 -> 192.168.1.1 wlan0 mtu 0 ]}"));
        globals.set("old", LuaValue.valueOf("{InterfaceName: wlan0 LinkAddresses: [192.168.1.5/24,]  Routes: [0.0.0.0/0 ->" +
                " 192.168.1.1 wlan0,] DnsAddresses: [192.168.1.1,] Domains: null MTU: 1500}"));
        Varargs result = Scripts.run(globals,
                // The hooks give the VPN and an IWLAN network the name of the network under the VPN, only the real names are
                // seen without them; a nested toString of the VPN gives its real text
                "local function faked(real, value)\n" +
                        "  return { getInterfaceName = function() return unhooked and real or hooked end,\n" +
                        "    toString = function() return value end }\n" +
                        "end\n" +
                        "hooked = 'rmnet_data0'\n" +
                        "local vpn = '{InterfaceName: tun0 LinkAddresses: [ 10.8.0.2/32 ] DnsAddresses: [ /10.8.0.1 ]' ..\n" +
                        "  ' UsePrivateDns: true Domains: corp.example MTU: 1420 Routes: [ 0.0.0.0/0 -> 0.0.0.0 tun0 mtu 1420 ]}'\n" +
                        "lps.vpn = faked('tun0', vpn)\n" +
                        "lps.ims = faked('ipsec1', '{InterfaceName: ipsec1 LinkAddresses: [ 2001:db8:5::2/64 ]' ..\n" +
                        "  ' DnsAddresses: [ /2001:db8:5::1 ] Domains: ims.example MTU: 1280 Routes: [ ::/0 -> :: ipsec1 mtu 1280 ]}')\n" +
                        // Read through the hooks, so Read.Network still empties the text
                        "lps.mobile.toString = function() assert(not unhooked, 'unhooked') return mobile end\n" +
                        "lps.wifi = lpof('wlan0')\n" +
                        "lps.wifi.toString = function() assert(not unhooked, 'unhooked') return wifi end\n" +
                        // The VPN is listed first, as the active network of the app
                        "cm.getAllNetworks = function(self) return { length = 3, 'vpn', 'mobile', 'wifi' } end\n" +
                        "local function text(value)\n" +
                        "  local param = paramof(value, {}, nil, context)\n" +
                        "  param.unhooked = function(self, call) unhooked = true local found = call() unhooked = false return found end\n" +
                        "  after(hookof('toString', 'android.net.LinkProperties'), param)\n" +
                        "  return param.result\n" +
                        "end\n" +
                        "local mobiletext = text(vpn)\n" +
                        "cm.getAllNetworks = function(self) return { length = 4, 'ims', 'vpn', 'mobile', 'wifi' } end\n" +
                        "local imsmobile = text(vpn)\n" +
                        // Without an active network info the hook of vpn names the VPN wlan0, which the getters find
                        "cm.getActiveNetworkInfo = function(self) return nil end\n" +
                        "hooked = 'wlan0'\n" +
                        "local imswifi = text(vpn)\n" +
                        "cm.getAllNetworks = function(self) return { length = 3, 'vpn', 'mobile', 'wifi' } end\n" +
                        "local wifitext = text(vpn)\n" +
                        "lps.wifi.toString = function() return old end\n" +
                        "local oldtext = text('{InterfaceName: tun0 LinkAddresses: [10.8.0.2/32,]' ..\n" +
                        "  '  Routes: [0.0.0.0/0 -> 0.0.0.0 tun0,] DnsAddresses: [10.8.0.1,] Domains: corp.example MTU: 0}')\n" +
                        "return mobiletext, wifitext, oldtext, imsmobile, imswifi");
        assertEquals("{InterfaceName: rmnet_data0 LinkAddresses: [ 2001:db8::7/64 ] DnsAddresses: [ /2001:db8::1 ] UsePrivateDns: true" +
                " Domains: null MTU: 1500 Routes: [ ::/0 -> fe80::1%rmnet_data0 rmnet_data0 mtu 1500 ]}", result.arg(1).tojstring());
        assertEquals("{InterfaceName: wlan0 LinkAddresses: [ 192.168.1.5/24 ] DnsAddresses: [ /192.168.1.1 ] UsePrivateDns: true" +
                " Domains: home lan MTU: 0 Routes: [ 0.0.0.0/0 -> 192.168.1.1 wlan0 mtu 0 ]}", result.arg(2).tojstring());
        // The text of Android 9 and before, without spaces inside the brackets
        assertEquals("{InterfaceName: wlan0 LinkAddresses: [192.168.1.5/24,]  Routes: [0.0.0.0/0 -> 192.168.1.1 wlan0,]" +
                " DnsAddresses: [192.168.1.1,] Domains: null MTU: 1500}", result.arg(3).tojstring());
        // An IWLAN network on ipsec1 listed first is not the network under the VPN either
        assertEquals(result.arg(1).tojstring(), result.arg(4).tojstring());
        assertEquals(result.arg(2).tojstring(), result.arg(5).tojstring());
    }

    @Test
    public void vpnTextsAndObjects() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        globals.set("caps", LuaValue.valueOf("[ Transports: WIFI|VPN Capabilities: INTERNET&TRUSTED&VALIDATED" +
                " TransportInfo: <VpnTransportInfo{type=1, sessionId=null, bypassable=false" +
                " longLivedTcpConnectionsExpensive=false}> UnderlyingNetworks: null]"));
        globals.set("lp", LuaValue.valueOf("{InterfaceName: tun0 LinkAddresses: [ 10.8.0.2/32 ]" +
                " Routes: [ 0.0.0.0/0 -> 0.0.0.0 tun0 mtu 0 ]}"));
        Varargs result = Scripts.run(globals,
                "local nc = paramof(caps, {}, capsof(1, 4), context)\n" +
                        "after(hookof('toString', 'android.net.NetworkCapabilities'), nc)\n" +
                        "local text = paramof(lp, {}, nil, context)\n" +
                        "after(hookof('toString', 'android.net.LinkProperties'), text)\n" +
                        "local tun = paramof({ getName = function() return 'tun0' end })\n" +
                        "local route = paramof('0.0.0.0/0 -> 10.8.0.1 tun0 mtu 1500', {}, nil, context)\n" +
                        "after(hookof('toString', 'android.net.RouteInfo'), route)\n" +
                        "local iface = paramof('tun0', {}, nil, context)\n" +
                        "after(hookof('getInterface', 'android.net.RouteInfo'), iface)\n" +
                        "return nc.result, text.result,\n" +
                        "  after(hookof('getByInetAddress'), tun), tun.result, route.result, iface.result");
        assertEquals("[ Transports: WIFI Capabilities: INTERNET&TRUSTED&NOT_VPN&VALIDATED UnderlyingNetworks: null]",
                result.arg(1).tojstring());
        // The addresses and routes of the tunnel are empty, like getLinkAddresses (see vpn_addresses)
        assertEquals("{InterfaceName: rmnet_data0 LinkAddresses: [ ] Routes: [ ]}", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).isnil());
        // The routes of the VPN network, which LinkProperties.getRoutes gives
        assertEquals("0.0.0.0/0 -> 10.8.0.1 rmnet_data0 mtu 1500", result.arg(5).tojstring());
        assertEquals("rmnet_data0", result.arg(6).tojstring());

        // Before Android 11 a route has no MTU, so the interface ends its text; other last words stay
        // (wlan0 without the connectivity manager)
        assertEquals("0.0.0.0/0 -> 10.8.0.1 wlan0",
                tostring(globals, "android.net.RouteInfo", "0.0.0.0/0 -> 10.8.0.1 tun0"));
        assertEquals("0.0.0.0/0 -> 10.8.0.1", tostring(globals, "android.net.RouteInfo", "0.0.0.0/0 -> 10.8.0.1"));
        assertEquals("::/0 unreachable", tostring(globals, "android.net.RouteInfo", "::/0 unreachable"));
        // The text of Android 9 and before, without spaces inside the brackets
        assertEquals("{InterfaceName: wlan0 LinkAddresses: []  Routes: []" +
                " DnsAddresses: [] Domains: null MTU: 0}", tostring(globals, "android.net.LinkProperties",
                "{InterfaceName: tun0 LinkAddresses: [10.8.0.2/32,]  Routes: [0.0.0.0/0 -> 0.0.0.0 tun0,]" +
                        " DnsAddresses: [10.8.0.1,] Domains: corp.example MTU: 0}"));
        // The search domains are the words between Domains: and MTU, all of them go; the MTU is 0, like getMtu
        String domains = tostring(globals, "android.net.LinkProperties", "{InterfaceName: tun0 LinkAddresses: [ 10.0.0.2/32 ]" +
                " DnsAddresses: [ ] Domains: corp.example intranet.example MTU: 1500 Routes: [ ]}");
        assertTrue(domains, domains.contains("Domains: null MTU: 0 Routes"));
        assertFalse(domains, domains.contains("intranet"));
        // Without an interface, like a new LinkProperties, there is nothing to hide
        String empty = "{LinkAddresses: [ ] DnsAddresses: [ ] Domains: null MTU: 0 Routes: [ ]}";
        assertEquals(empty, tostring(globals, "android.net.LinkProperties", empty));
        // The HTTP proxy of a VPN goes like getHttpProxy, with its spaces, at the end or before the NAT64 prefix;
        // the text is then the one of a network without a proxy, and a proxy of Wi-Fi stays
        String noProxy = "{InterfaceName: wlan0 LinkAddresses: [ ] DnsAddresses: [ ] Domains: null MTU: 0 Routes: [ ]";
        assertEquals(noProxy + "}", tostring(globals, "android.net.LinkProperties", "{InterfaceName: tun0" +
                " LinkAddresses: [ 10.8.0.2/32 ] DnsAddresses: [ 10.8.0.1 ] Domains: null MTU: 1500" +
                " Routes: [ 0.0.0.0/0 -> 0.0.0.0 tun0 mtu 1500 ] HttpProxy: [127.0.0.1] 8080 xl=}"));
        assertEquals(noProxy + " Nat64Prefix: 64:ff9b::/96}", tostring(globals, "android.net.LinkProperties",
                "{InterfaceName: tun0 LinkAddresses: [ ] DnsAddresses: [ ] Domains: null MTU: 1500 Routes: [ ]" +
                        " HttpProxy: PAC Script: http://wpad.corp.example/wpad.dat[localhost] -1 xl= Nat64Prefix: 64:ff9b::/96}"));
        String wifi = noProxy + " HttpProxy: [10.0.0.1] 3128 xl=localhost,*.corp.example}";
        assertEquals(wifi, tostring(globals, "android.net.LinkProperties", wifi));

        // The reason of a connecting VPN before Android 12, in the text as a NetworkInfo without a reason prints it;
        // other reasons stay
        String info = "[type: MOBILE[LTE], state: CONNECTED/CONNECTED, reason: %s, extra: (none), failover: false]";
        assertEquals(String.format(info, "(unspecified)"),
                tostring(globals, "android.net.NetworkInfo", String.format(info, "agentConnect")));
        assertEquals(String.format(info, "connected"),
                tostring(globals, "android.net.NetworkInfo", String.format(info, "connected")));
        Varargs reason = Scripts.run(globals,
                "local vpn = paramof('agentConnect')\n" +
                        "local other = paramof('connected')\n" +
                        "return after(hookof('getReason'), vpn), vpn.result, after(hookof('getReason'), other), other.result");
        assertTrue(reason.arg(1).toboolean());
        assertTrue(reason.arg(2).isnil());
        assertFalse(reason.arg(3).toboolean());
        assertEquals("connected", reason.arg(4).tojstring());
    }

    // The empty extra info Android leaves most apps is (none) in the text, like the hooked getter gives no extra info
    @Test
    public void emptyExtraInfoIsNone() throws Exception {
        Globals globals = load("network_tostring");
        assertEquals("[type: WIFI[], state: CONNECTED/CONNECTED, reason: (unspecified), extra: (none), failover: false]",
                Scripts.run(globals, "return (networkinfo('[type: WIFI[], state: CONNECTED/CONNECTED, reason: (unspecified), " +
                        "extra: , failover: false]'))").arg1().tojstring());
    }

    // The runtime of a script stays after an error (see XCore), so an error must not leave the guard of nested calls set
    @Test
    public void vpnHooksWorkAfterAnError() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        globals.set("lp", LuaValue.valueOf("{InterfaceName: tun0 LinkAddresses: [ ] Routes: [ ]}"));
        Varargs result = Scripts.run(globals,
                "local failing = paramof(nil, {}, nil, context)\n" +
                        "failing.getResult = function() error('failed') end\n" +
                        "local ok = pcall(after, hookof('toString', 'android.net.LinkProperties'), failing)\n" +
                        "local text = paramof(lp, {}, nil, context)\n" +
                        "after(hookof('toString', 'android.net.LinkProperties'), text)\n" +
                        "return ok, text.result");
        assertFalse(result.arg(1).toboolean());
        assertEquals("{InterfaceName: rmnet_data0 LinkAddresses: [ ] Routes: [ ]}", result.arg(2).tojstring());
    }

    // A stack of class.method frames for the scripts which look at who calls (see linkproperties)
    private static final String STACK = String.join("\n",
            "stack = {}",
            "local real = luajava",
            "luajava = setmetatable({ bindClass = function(name)",
            "  if name ~= 'java.lang.Thread' then return real.bindClass(name) end",
            "  return { currentThread = function() return { getStackTrace = function()",
            "    local frames = { length = #stack }",
            "    for i, frame in ipairs(stack) do",
            "      local cls, method = string.match(frame, '^(.*)%.([^.]+)$')",
            "      frames[i] = { getClassName = function() return cls end, getMethodName = function() return method end }",
            "    end",
            "    return frames end } end } end }, { __index = real })");

    // LinkProperties checks each route against its interface when it reads a parcel or copies itself (addRoute), which
    // throws for a faked route or interface and crashed apps when a VPN connected; apps reading them get the fake ones,
    // also when the frame of the hooked getter of LinkProperties itself is on the stack
    @Test
    public void vpnInterfaceRealForLinkProperties() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        Scripts.run(globals, STACK);
        Varargs result = Scripts.run(globals,
                "local function call(method, cls)\n" +
                        "  local param = paramof('tun0', {}, nil, context)\n" +
                        "  after(hookof(method, cls), param)\n" +
                        "  return param.result\n" +
                        "end\n" +
                        "stack = { 'java.lang.Thread.getStackTrace', 'android.net.LinkProperties.addRoute', 'android.net.LinkProperties$1.createFromParcel' }\n" +
                        "local route = call('getInterface', 'android.net.RouteInfo')\n" +
                        "stack = { 'java.lang.Thread.getStackTrace', 'android.net.LinkProperties.getInterfaceName', 'android.net.LinkProperties.<init>' }\n" +
                        "local copy = call('getInterfaceName', 'android.net.LinkProperties')\n" +
                        "stack = { 'java.lang.Thread.getStackTrace', 'android.net.LinkProperties.getInterfaceName', 'com.example.App.run' }\n" +
                        "local own = call('getInterfaceName', 'android.net.LinkProperties')\n" +
                        "stack = { 'java.lang.Thread.getStackTrace', 'android.net.LinkProperties.addRoute', 'android.net.LinkProperties.<init>' }\n" +
                        "local copied = call('getInterface', 'android.net.RouteInfo')\n" +
                        // An app rebuilding LinkProperties: setInterfaceName(lp.getInterfaceName()), the fake one, then
                        // addRoute(r) for each of lp.getRoutes(), which checks that the route has the same interface
                        "stack = { 'java.lang.Thread.getStackTrace', 'android.net.LinkProperties.addRoute', 'com.example.App.run'," +
                        " 'android.net.LinkProperties.<init>' }\n" +
                        "local added = call('getInterface', 'android.net.RouteInfo')\n" +
                        "stack = { 'java.lang.Thread.getStackTrace', 'android.net.LinkProperties.getInterfaceName'," +
                        " 'android.net.LinkProperties.getAllInterfaceNames', 'com.example.App.run' }\n" +
                        "local names = call('getInterfaceName', 'android.net.LinkProperties')\n" +
                        "stack = { 'java.lang.Thread.getStackTrace', 'com.example.App.run' }\n" +
                        "return route, copy, own, call('getInterface', 'android.net.RouteInfo'), copied, added, names");
        assertEquals("tun0", result.arg(1).tojstring());
        assertEquals("tun0", result.arg(2).tojstring());
        assertEquals("rmnet_data0", result.arg(3).tojstring());
        assertEquals("rmnet_data0", result.arg(4).tojstring());
        assertEquals("tun0", result.arg(5).tojstring());
        assertEquals(result.arg(3).tojstring(), result.arg(6).tojstring());
        assertEquals("rmnet_data0", result.arg(7).tojstring());
    }

    // LinkProperties compares and copies its routes, addresses and DNS servers through the getters (equals, the copy
    // constructor), which must give the real ones there, else lp.equals(lp) is false; apps get none
    @Test
    public void networkAddressesRealForLinkProperties() throws Exception {
        Globals globals = load("network_address");
        Scripts.run(globals, STACK);
        Varargs result = Scripts.run(globals,
                // The frame of the hooked getter is on the stack, then its caller
                "local function call(method, caller)\n" +
                        "  stack = { 'java.lang.Thread.getStackTrace', 'android.net.LinkProperties.' .. method, caller }\n" +
                        "  local param = paramof({ isEmpty = function() return false end })\n" +
                        "  return tostring(after(hookof(method), param))\n" +
                        "end\n" +
                        "return call('getRoutes', 'android.net.LinkProperties.isIdenticalRoutes'),\n" +
                        "  call('getDomains', 'android.net.LinkProperties.isIdenticalDnses'),\n" +
                        "  call('getDomains', 'com.example.App.run'), call('getRoutes', 'com.example.App.run')");
        assertEquals("false", result.arg(1).tojstring());
        assertEquals("false", result.arg(2).tojstring());
        assertEquals("true", result.arg(3).tojstring());
        assertEquals("true", result.arg(4).tojstring());

        // The hidden getters, which apps reach by reflection, and with which LinkProperties compares itself
        for (String method : new String[]{"getAllAddresses", "getAddresses", "getAllLinkAddresses", "getAllRoutes",
                "getAllInterfaceNames"}) {
            result = Scripts.run(globals,
                    "local param = paramof({ isEmpty = function() return false end })\n" +
                            "stack = { 'android.net.LinkProperties." + method + "', 'com.example.App.run' }\n" +
                            "local app = after(hookof('" + method + "'), param)\n" +
                            "stack = { 'android.net.LinkProperties." + method + "', 'android.net.LinkProperties.isIdenticalAddresses' }\n" +
                            "return app, after(hookof('" + method + "'), paramof({ isEmpty = function() return false end }))");
            assertTrue(method, result.arg(1).toboolean());
            assertFalse(method, result.arg(2).toboolean());
        }
    }

    // A request for VPN networks, which Android matches itself, asks for LoWPAN networks instead, also to remove it again;
    // others stay. The request of the app answers like the one it made then, with VPN in place of LoWPAN.
    @Test
    public void vpnRequestsFindNothing() throws Exception {
        Globals globals = load("vpn_request");
        Varargs result = Scripts.run(globals,
                "local function request(transport, method)\n" +
                        "  local param = { args = { [0] = transport },\n" +
                        "    getArgument = function(self, i) return self.args[i] end,\n" +
                        "    setArgument = function(self, i, v) self.args[i] = v end }\n" +
                        "  local restricted = before(hookof(method or 'addTransportType', 'android.net.NetworkRequest$Builder'), param)\n" +
                        "  return restricted, param.args[0]\n" +
                        "end\n" +
                        "local vpn, transport = request(4)\n" +
                        "local wifi, other = request(1)\n" +
                        "local removed, again = request(4, 'removeTransportType')\n" +
                        "return vpn, transport, wifi, other, removed, again");
        assertTrue(result.arg(1).toboolean());
        assertEquals(6, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
        assertEquals(1, result.arg(4).toint());
        assertTrue(result.arg(5).toboolean());
        assertEquals(6, result.arg(6).toint());

        // The other hooks of Read.VPN do not run a before function for it
        Globals vpn = load("vpn");
        assertTrue(vpn.get("before").isnil());
        Scripts.run(vpn, NETWORKS);
        // NetworkRequest.Builder().addTransportType(TRANSPORT_VPN).removeCapability(NET_CAPABILITY_NOT_VPN).build(),
        // which has LoWPAN, on a device without a VPN
        Varargs shown = Scripts.run(vpn,
                "local function call(this, method, result, arg)\n" +
                        "  local param = paramof(result, { [0] = arg }, this, context)\n" +
                        "  after(hookof(method, 'android.net.NetworkCapabilities'), param)\n" +
                        "  return param.result\n" +
                        "end\n" +
                        "local request = capsof(6)\n" +
                        "return call(request, 'hasTransport', false, 4), call(request, 'hasTransport', true, 6),\n" +
                        "  call(request, 'hasTransport', false, 1), call(request, 'hasCapability', false, 15),\n" +
                        "  call(request, 'toString', '[ Transports: LOWPAN Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED ]'),\n" +
                        // removeCapability(NET_CAPABILITY_NOT_VPN) alone, and its text, stay
                        "  call(capsof(), 'hasCapability', false, 15),\n" +
                        "  call(capsof(), 'toString', '[ Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED ]'),\n" +
                        // Android 8.1 lists the transports of the hooked getTransportTypes, which has VPN already
                        "  call(request, 'toString', '[ Transports: VPN Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED ]')");
        assertTrue(shown.arg(1).toboolean());
        assertFalse(shown.arg(2).toboolean());
        assertFalse(shown.arg(3).toboolean());
        assertFalse(shown.arg(4).toboolean());
        assertEquals("[ Transports: VPN Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED ]", shown.arg(5).tojstring());
        assertFalse(shown.arg(6).toboolean());
        assertEquals("[ Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED ]", shown.arg(7).tojstring());
        assertEquals("[ Transports: VPN Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED ]", shown.arg(8).tojstring());
    }

    // NOT_VPN in its place in bit order, like a real network has it: after TRUSTED (14), before VALIDATED (16)
    @Test
    public void vpnNotVpnInBitOrder() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        Scripts.run(globals, Scripts.ARRAYOF + "\n" +
                "luajava = { bindClass = function(name)\n" +
                "  if name == 'java.lang.Integer' then return { TYPE = 'int' } end\n" +
                "  assert(name == 'java.lang.reflect.Array', name)\n" +
                "  return { newInstance = function(self, type, length) return { length = length } end }\n" +
                "end }");
        Varargs result = Scripts.run(globals,
                "local function capabilities(this, ...)\n" +
                        "  local param = paramof(arrayof(...), {}, this, context)\n" +
                        "  after(hookof('getCapabilities'), param)\n" +
                        "  local list = {}\n" +
                        "  for index = 1, param.result.length do list[index] = param.result[index] end\n" +
                        "  return table.concat(list, ','), param.set\n" +
                        "end\n" +
                        "local vpn = capabilities(capsof(1, 4), 12, 13, 14, 16, 18)\n" +
                        // A request of the app without NOT_VPN, also one for VPN networks, and one after clearCapabilities
                        "local request, changed = capabilities(capsof(6), 12, 13, 14)\n" +
                        "local cleared, emptied = capabilities(capsof())\n" +
                        "return vpn, request, changed, cleared, emptied");
        assertEquals("12,13,14,15,16,18", result.arg(1).tojstring());
        assertEquals("12,13,14", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
        assertEquals("", result.arg(4).tojstring());
        assertFalse(result.arg(5).toboolean());

        // Also without capabilities below or above it
        assertEquals("NOT_VPN&VALIDATED", vpntostring(globals,
                "[ Transports: VPN Capabilities: VALIDATED UnderlyingNetworks: null]").replaceAll(".*Capabilities: (\\S*).*", "$1"));
        assertEquals("INTERNET&NOT_VPN", vpntostring(globals,
                "[ Transports: VPN Capabilities: INTERNET UnderlyingNetworks: null]").replaceAll(".*Capabilities: (\\S*).*", "$1"));
    }

    // The text of the capabilities of a VPN over mobile data, with only the transport VPN like on Android 8.1
    private static String vpntostring(Globals globals, String text) {
        globals.set("text", LuaValue.valueOf(text));
        return Scripts.run(globals,
                "local param = paramof(text, {}, capsof(4), context)\n" +
                        "after(hookof('toString', 'android.net.NetworkCapabilities'), param)\n" +
                        "return param.result").arg1().tojstring();
    }

    // The transports of a VPN network lack VPN, which hasTransport denies too; other transports stay. A request of the app
    // for VPN networks, which vpn_request made for LoWPAN, has VPN again, in bit order. A VPN with only that transport,
    // like on Android 8.1, has the one of the network under it, here mobile data, like its text.
    @Test
    public void vpnTransportsLackVpn() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        Scripts.run(globals, Scripts.ARRAYOF + "\n" +
                "luajava = { bindClass = function(name)\n" +
                "  if name == 'java.lang.Integer' then return { TYPE = 'int' } end\n" +
                "  assert(name == 'java.lang.reflect.Array', name)\n" +
                "  return { newInstance = function(self, type, length) return { length = length } end }\n" +
                "end }");
        Varargs result = Scripts.run(globals,
                "local function types(values)\n" +
                        "  local param = paramof(arrayof(table.unpack(values)), {}, nil, context)\n" +
                        "  local restricted, old, new = after(hookof('getTransportTypes'), param)\n" +
                        "  local list = {}\n" +
                        "  for index = 1, param.result.length do list[index] = param.result[index] end\n" +
                        "  return restricted, old, new, table.concat(list, ',')\n" +
                        "end\n" +
                        "local r1, o1, n1, l1 = types({ 1, 4 })\n" +
                        "local r2, o2, n2, l2 = types({ 0, 6, 4 })\n" +
                        "local r3, o3, n3, l3 = types({ 1 })\n" +
                        "local _, _, _, request = types({ 6 })\n" +
                        "local _, _, _, ordered = types({ 1, 5, 6 })\n" +
                        "local _, _, _, only = types({ 4 })\n" +
                        "return r1, o1, n1, l1, r2, o2, n2, l2, r3, l3, request, ordered, only");
        assertTrue(result.arg(1).toboolean());
        assertEquals("1,4", result.arg(2).tojstring());
        assertEquals("1", result.arg(3).tojstring());
        assertEquals("1", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertEquals("0,6,4", result.arg(6).tojstring());
        assertEquals("0", result.arg(7).tojstring());
        assertEquals("0", result.arg(8).tojstring());
        assertFalse(result.arg(9).toboolean());
        assertEquals("1", result.arg(10).tojstring());
        assertEquals("4", result.arg(11).tojstring());
        assertEquals("1,4,5", result.arg(12).tojstring());
        assertEquals("0", result.arg(13).tojstring());

        // hasTransport of the same networks, with the text of Android 8.1, which lists the transports of the hooked
        // getTransportTypes, and of later versions for a VPN without networks under it
        Varargs has = Scripts.run(globals,
                "local function call(this, method, result, arg)\n" +
                        "  local param = paramof(result, { [0] = arg }, this, context)\n" +
                        "  after(hookof(method, 'android.net.NetworkCapabilities'), param)\n" +
                        "  return param.result\n" +
                        "end\n" +
                        "local only, wifi = capsof(4), capsof(1, 4)\n" +
                        "return call(only, 'hasTransport', false, 0), call(only, 'hasTransport', false, 1),\n" +
                        "  call(only, 'hasTransport', true, 4), call(wifi, 'hasTransport', false, 0),\n" +
                        "  call(wifi, 'hasTransport', true, 1),\n" +
                        "  call(only, 'toString', '[ Transports: CELLULAR Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED&VALIDATED]'),\n" +
                        "  call(only, 'toString', '[ Transports: VPN Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED&VALIDATED]')");
        assertTrue(has.arg(1).toboolean());
        assertFalse(has.arg(2).toboolean());
        assertFalse(has.arg(3).toboolean());
        assertFalse(has.arg(4).toboolean());
        assertTrue(has.arg(5).toboolean());
        assertEquals("[ Transports: CELLULAR Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VPN&VALIDATED]", has.arg(6).tojstring());
        assertEquals(has.arg(6).tojstring(), has.arg(7).tojstring());
    }

    // A VPN network lacks NOT_VPN, which it has here; other capabilities stay
    @Test
    public void vpnHasNotVpn() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        Varargs result = Scripts.run(globals,
                "local vpn = paramof(false, { [0] = 15 }, capsof(1, 4))\n" +
                        "local other = paramof(false, { [0] = 12 }, capsof(1, 4))\n" +
                        "local real = paramof(true, { [0] = 15 }, capsof(1))\n" +
                        "return after(hookof('hasCapability'), vpn), vpn.result, after(hookof('hasCapability'), other),\n" +
                        "  other.set, after(hookof('hasCapability'), real), real.set");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
    }

    // NetworkRequest compares the fields of the capabilities, so the network of a VPN, which lacks NOT_VPN, is compared
    // as a copy like the hooks show it, read back from its parcel with NOT_VPN and without VPN
    @Test
    public void vpnRequestSatisfiedLikeWithoutVpn() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        Scripts.run(globals, String.join("\n",
                "function bitsof(...) local bits = 0 for _, bit in ipairs({ ... }) do bits = bits + 2 ^ bit end return bits end",
                "function has(bits, bit) return math.floor(bits / 2 ^ bit) % 2 == 1 end",
                "function ncof(values, transports) return { values = values, transports = transports,",
                "  hasTransport = function(self, transport) return has(self.transports, transport) end,",
                "  hasCapability = function(self, capability) return has(self.values, capability) end,",
                "  writeToParcel = function(self, parcel, flags)",
                "    parcel:writeLong(self.values) parcel:writeLong(0) parcel:writeLong(self.transports) end } end",
                // The longs of a parcel by their position
                "function parcelof() return { longs = {}, position = 0,",
                "  writeLong = function(self, value) self.longs[self.position] = value self.position = self.position + 8 end,",
                "  readLong = function(self) self.position = self.position + 8 return self.longs[self.position - 8] end,",
                "  setDataPosition = function(self, position) self.position = position end,",
                "  recycle = function(self) self.recycled = true end } end",
                "creator = { createFromParcel = function(self, parcel)",
                "  local values = parcel:readLong() parcel:readLong() return ncof(values, parcel:readLong()) end }",
                "luajava = { bindClass = function(name)",
                "  if name == 'android.os.Parcel' then return { obtain = function(self) parcel = parcelof() return parcel end } end",
                "  assert(name == 'android.net.NetworkCapabilities', name)",
                "  return { CREATOR = creator } end }",
                // Required capabilities and any of the transports, like NetworkCapabilities.satisfiedByNetworkCapabilities
                "function requestof(values, transports) return { canBeSatisfiedBy = function(self, nc)",
                "  for _, value in ipairs(values) do if not nc:hasCapability(value) then return false end end",
                "  for _, transport in ipairs(transports) do if nc:hasTransport(transport) then return true end end",
                "  return #transports == 0 end } end",
                "function satisfied(request, nc, result)",
                "  local param = paramof(result or false, { [0] = nc }, request, context)",
                "  local ok = pcall(after, hookof('canBeSatisfiedBy', 'android.net.NetworkRequest'), param)",
                "  return param.result, param.set, ok",
                "end",
                // INTERNET, NOT_RESTRICTED, TRUSTED and NOT_VPN, the default of NetworkRequest.Builder
                "default = requestof({ 12, 13, 14, 15 }, {})",
                "vpn = ncof(bitsof(12, 13, 14, 16), bitsof(1, 4))"));
        Varargs result = Scripts.run(globals,
                "local fake = satisfied(default, vpn)\n" +
                        "local recycled = parcel.recycled\n" +
                        // A request for VPN networks, which vpn_request made one for LoWPAN, still finds none
                        "local request, set = satisfied(requestof({ 12, 13, 14 }, { 6 }), vpn)\n" +
                        // A VPN without another transport, like on Android 8.1, is on mobile data here
                        "local mobile = satisfied(requestof({ 12, 13, 14, 15 }, { 0 }), ncof(bitsof(12, 13, 14, 16), bitsof(4)))\n" +
                        // Other networks, and the answers which are true, stay
                        "local _, other = satisfied(requestof({ 11 }, {}), ncof(bitsof(12, 13, 14, 15), bitsof(1)))\n" +
                        "local _, real = satisfied(default, vpn, true)\n" +
                        "return fake, recycled, request, set, mobile, other, real");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());

        // A parcel of another order of the fields, of which the copy does not show the fakes, fails the hook
        // and leaves the answer of Android
        Varargs other = Scripts.run(globals,
                "creator.createFromParcel = function(self, parcel) return vpn end\n" +
                        "local result, set, ok = satisfied(default, vpn)\n" +
                        "return result, set, ok, parcel.recycled");
        assertFalse(other.arg(1).toboolean());
        assertFalse(other.arg(2).toboolean());
        assertFalse(other.arg(3).toboolean());
        assertTrue(other.arg(4).toboolean());
    }

    // The network info of the type VPN stays, not connected, like on a device without a VPN, by its type or in the
    // list of all networks: a new one like Android makes for a type without a network, in the state of the other types
    // without a network, BLOCKED for an app blocked on metered networks from Android 12, also with only BLOCKED
    // networks, and DISCONNECTED without a list; others stay as they are
    @Test
    public void vpnNetworkInfoIsNotConnected() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        Scripts.run(globals, Scripts.ARRAYOF + "\n" +
                "luajava = { newInstance = function(name, ...)\n" +
                "    assert(name == 'android.net.NetworkInfo')\n" +
                "    return { made = table.concat({ ... }, ','),\n" +
                "      setIsAvailable = function(self, available) self.available = available end,\n" +
                "      setDetailedState = function(self, state, reason, extra) self.state = state self.extra = extra end }\n" +
                "  end,\n" +
                "  bindClass = function(name)\n" +
                "    assert(name == 'android.net.NetworkInfo$DetailedState')\n" +
                "    return { DISCONNECTED = 'DISCONNECTED', BLOCKED = 'BLOCKED' }\n" +
                "  end }\n" +
                "function stateof(info, state) info.getDetailedState = function(self) return state end return info end\n" +
                "stateof(vpninfo, 'CONNECTED')\n" +
                "stateof(mobileinfo, 'CONNECTED')\n" +
                "function placeholder(state) return stateof(infoof(13, 'WIFI_P2P'), state) end\n" +
                "function manager(list) return { getAllNetworkInfo = function(self) return list end } end");
        Varargs result = Scripts.run(globals,
                "local vpn = paramof(vpninfo, { [0] = 17 }, manager(arrayof(vpninfo, mobileinfo, placeholder('DISCONNECTED'))))\n" +
                        "local mobile = paramof(mobileinfo, { [0] = 0 })\n" +
                        "local all = paramof(arrayof(vpninfo, mobileinfo, placeholder('BLOCKED')))\n" +
                        "local plain = paramof(arrayof(mobileinfo))\n" +
                        "return after(hookof('getNetworkInfo'), vpn), vpn.result.state, vpn.result.made, vpn.result.available,\n" +
                        "  vpn.result.extra, after(hookof('getNetworkInfo'), mobile), mobile.set,\n" +
                        "  after(hookof('getAllNetworkInfo'), all), all.result[1].state, all.result[1].made,\n" +
                        "  all.result[2] == mobileinfo, after(hookof('getAllNetworkInfo'), plain), plain.set");
        assertTrue(result.arg(1).toboolean());
        assertEquals("DISCONNECTED", result.arg(2).tojstring());
        assertEquals("17,0,VPN,", result.arg(3).tojstring());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).isnil());
        assertFalse(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
        assertEquals("BLOCKED", result.arg(9).tojstring());
        assertEquals("17,0,VPN,", result.arg(10).tojstring());
        assertTrue(result.arg(11).toboolean());
        assertFalse(result.arg(12).toboolean());
        assertFalse(result.arg(13).toboolean());

        // A blocked metered network before Android 12, whose types without a network are DISCONNECTED, a list with only
        // a blocked network, and a list which fails, which still hides the VPN
        Varargs other = Scripts.run(globals,
                "local metered = stateof(infoof(0, 'MOBILE'), 'BLOCKED')\n" +
                        "local older = paramof(arrayof(vpninfo, metered, placeholder('DISCONNECTED')))\n" +
                        "local blocked = paramof(vpninfo, { [0] = 17 }, manager(arrayof(metered, vpninfo)))\n" +
                        "local failed = paramof(vpninfo, { [0] = 17 }, { getAllNetworkInfo = function() error('denied') end })\n" +
                        "return after(hookof('getAllNetworkInfo'), older), older.result[1].state,\n" +
                        "  after(hookof('getNetworkInfo'), blocked), blocked.result.state,\n" +
                        "  after(hookof('getNetworkInfo'), failed), failed.result.state");
        assertTrue(other.arg(1).toboolean());
        assertEquals("DISCONNECTED", other.arg(2).tojstring());
        assertTrue(other.arg(3).toboolean());
        assertEquals("BLOCKED", other.arg(4).tojstring());
        assertTrue(other.arg(5).toboolean());
        assertEquals("DISCONNECTED", other.arg(6).tojstring());
    }

    // The subtype, extra info and roaming of the network info of a connected VPN, in its getters and its text, are the
    // ones of the network under it, like its type; other network infos stay, and so does a VPN without that network
    @Test
    public void vpnNetworkInfoHasValuesOfNetworkUnder() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        Scripts.run(globals, String.join("\n",
                "mobileinfo.getSubtype = function() return 13 end",
                "mobileinfo.getSubtypeName = function() return 'LTE' end",
                "mobileinfo.getExtraInfo = function() return 'internet' end",
                "mobileinfo.isRoaming = function() return true end",
                "function getter(method, result, info)",
                "  local param = paramof(result, {}, info, context)",
                "  return after(hookof(method, 'android.net.NetworkInfo'), param), param.result",
                "end"));
        Varargs result = Scripts.run(globals,
                "local subtype, sub = getter('getSubtype', 0, vpninfo)\n" +
                        "local named, name = getter('getSubtypeName', '', vpninfo)\n" +
                        "local extra, info = getter('getExtraInfo', nil, vpninfo)\n" +
                        "local roams, roaming = getter('isRoaming', false, vpninfo)\n" +
                        "return subtype, sub, named, name, extra, info, roams, roaming, getter('getSubtype', 13, mobileinfo)");
        assertTrue(result.arg(1).toboolean());
        assertEquals(13, result.arg(2).toint());
        assertTrue(result.arg(3).toboolean());
        assertEquals("LTE", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertEquals("internet", result.arg(6).tojstring());
        assertTrue(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
        assertFalse(result.arg(9).toboolean());

        // The text has the extra info and roaming from the fields, the names from the hooked getters
        String text = "[type: MOBILE[LTE], state: CONNECTED/CONNECTED, reason: (unspecified), extra: %s, failover: false," +
                " available: true, roaming: %s]";
        globals.set("text", LuaValue.valueOf(String.format(text, "", "false")));
        Varargs shown = Scripts.run(globals, "local param = paramof(text, {}, vpninfo, context)\n" +
                "return after(hookof('toString', 'android.net.NetworkInfo'), param), param.result");
        assertTrue(shown.arg1().toboolean());
        assertEquals(String.format(text, "internet", "true"), shown.arg(2).tojstring());

        Varargs alone = Scripts.run(globals, "cm.getActiveNetworkInfo = function() return nil end\n" +
                "return getter('getSubtypeName', '', vpninfo)");
        assertFalse(alone.arg1().toboolean());
    }

    // The network of a VPN is listed when it is the active network of the app, which Lua compares by equals, like
    // Network compares, else it is left out; the other networks stay
    @Test
    public void vpnActiveNetworkIsListed() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        Varargs result = Scripts.run(globals, String.join("\n",
                "local real = luajava",
                "luajava = setmetatable({ bindClass = function(name)",
                "  if name ~= 'java.lang.reflect.Array' then return real.bindClass(name) end",
                "  return { newInstance = function(self, class, length) return { length = length } end } end },",
                "  { __index = real })",
                "local function network(name) return luajava.newInstance('java.io.File', name) end",
                "local caps = { vpn = capsof(0, 4), mobile = capsof(0) }",
                "cm.getNetworkCapabilities = function(self, network) return caps[network:getPath()] end",
                "local function all(active)",
                "  cm.getActiveNetwork = function(self) return active end",
                "  local param = paramof({ length = 2, network('mobile'), network('vpn') }, {}, cm, context)",
                "  local restricted = after(hookof('getAllNetworks'), param)",
                "  local names = {}",
                "  for index = 1, param.result['length'] do names[index] = param.result[index]:getPath() end",
                "  return tostring(restricted) .. ' ' .. table.concat(names, ',')",
                "end",
                "return all(network('vpn')), all(network('mobile')), all(nil)"));
        assertEquals("false mobile,vpn", result.arg(1).tojstring());
        assertEquals("true mobile", result.arg(2).tojstring());
        assertEquals("true mobile", result.arg(3).tojstring());
    }

    // The interfaces of a VPN are not listed. The enumeration is consumed, so the result is a new one also without them
    @Test
    public void vpnInterfacesAreNotListed() throws Exception {
        Varargs result = Scripts.run(load("vpn"),
                "local collections = luajava.bindClass('java.util.Collections')\n" +
                        "local function interfaces(...)\n" +
                        "  local list = luajava.newInstance('java.util.ArrayList')\n" +
                        "  for _, name in ipairs({ ... }) do list:add({ getName = function(self) return name end }) end\n" +
                        "  return collections:enumeration(list)\n" +
                        "end\n" +
                        "local vpn = paramof(interfaces('tun0', 'wlan0', 'ppp0'))\n" +
                        "local plain = paramof(interfaces('lo', 'wlan0'))\n" +
                        "local removed = after(hookof('getNetworkInterfaces'), vpn)\n" +
                        "local kept = collections:list(vpn.result)\n" +
                        "return removed, kept:size(), kept:get(0):getName(), after(hookof('getNetworkInterfaces'), plain),\n" +
                        "  plain.set, collections:list(plain.result):size()");
        assertTrue(result.arg(1).toboolean());
        assertEquals(1, result.arg(2).toint());
        assertEquals("wlan0", result.arg(3).tojstring());
        assertFalse(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertEquals(2, result.arg(6).toint());
    }

    // The local address of a socket through a VPN, of its tunnel, is the address of the network under it of the same
    // family, with the port, or the wildcard address without one; other addresses stay
    @Test
    public void vpnSocketAddressIsOfNetworkUnder() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        Varargs result = Scripts.run(globals, String.join("\n",
                "local inet = luajava.bindClass('java.net.InetAddress')",
                "local interfaces = { ['10.8.0.2'] = 'tun0', ['fd00:0:0:0:0:0:0:2'] = 'tun0', ['100.64.12.7'] = 'rmnet_data0' }",
                "local real = luajava",
                "luajava = setmetatable({ bindClass = function(name)",
                "  if name ~= 'java.net.NetworkInterface' then return real.bindClass(name) end",
                "  return { getByInetAddress = function(self, address)",
                "    local found = interfaces[address:getHostAddress()]",
                "    return found and { getName = function() return found end } end } end }, { __index = real })",
                "local addresses = { '100.64.12.7', 'fe80::7' }",
                "lps.mobile.getLinkAddresses = function(self)",
                "  return { size = function() return #addresses end, get = function(_, index)",
                "    return { getAddress = function() return inet:getByName(addresses[index + 1]) end } end }",
                "end",
                "local caps = { vpn = capsof(0, 4), mobile = capsof(0) }",
                "caps.mobile.hasCapability = function(self, capability) return capability == 12 end",
                "cm.getNetworkCapabilities = function(self, network) return caps[network] end",
                "local function bound(method, value)",
                "  local param = paramof(value, {}, nil, context)",
                "  return tostring(after(hookof(method), param)) .. ' ' .. tostring(param.result)",
                "end",
                "local function socket(host, port)",
                "  return luajava.newInstance('java.net.InetSocketAddress', inet:getByName(host), port)",
                "end",
                "local before = { bound('getLocalAddress', inet:getByName('10.8.0.2')), bound('getsockname', socket('10.8.0.2', 4321)),",
                "  bound('getLocalSocketAddress', socket('fd00::2', 443)), bound('getLocalAddress', inet:getByName('100.64.12.7')),",
                "  bound('getLocalAddress', inet:getByName('127.0.0.1')), bound('getsockname', 'unix') }",
                "addresses[#addresses + 1] = '2001:db8::7'",
                "local ipv6 = bound('getLocalSocketAddress', socket('fd00::2', 443))",
                // Without the permission to read the networks, which a socket does not need
                "context = { getSystemService = function() error('denied') end }",
                "return before[1], before[2], before[3], before[4], before[5], before[6], ipv6,",
                "  bound('getLocalAddress', inet:getByName('10.8.0.2'))"));
        assertEquals("true /100.64.12.7", result.arg(1).tojstring());
        assertEquals("true /100.64.12.7:4321", result.arg(2).tojstring());
        // No IPv6 address on the network under the VPN
        assertEquals("true /[0:0:0:0:0:0:0:0]:443", result.arg(3).tojstring());
        assertEquals("false /100.64.12.7", result.arg(4).tojstring());
        assertEquals("false /127.0.0.1", result.arg(5).tojstring());
        assertEquals("false unix", result.arg(6).tojstring());
        assertEquals("true /[2001:db8:0:0:0:0:0:7]:443", result.arg(7).tojstring());
        assertEquals("true /0.0.0.0", result.arg(8).tojstring());
    }

    // The transport info is a Java object, which the script checks with XParam, because Lua sees a Class
    // as its static members; the loader of Android 10 and 11 does not have VpnTransportInfo
    @Test
    public void vpnTransportInfoIsHidden() throws Exception {
        // VpnTransportInfo is not in the SDK of the unit tests: the check of XParam says it is one
        Varargs vpn = Scripts.run(load("vpn"),
                "local param = { result = {},\n" +
                        "  getResult = function(self) return self.result end,\n" +
                        "  setResult = function(self, value) self.result = value end,\n" +
                        "  isInstance = function(self, object, name) return name == 'android.net.VpnTransportInfo' end }\n" +
                        "return after(hookof('getTransportInfo'), param), param.result");
        assertTrue(vpn.arg1().toboolean());
        assertTrue(vpn.arg(2).isnil());

        ClassLoader loader = NetworkScriptsTest.class.getClassLoader();
        ClassLoader old = new ClassLoader(loader) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("android.net.VpnTransportInfo"))
                    throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        TransportInfo wifi = new TransportInfo() {
        };

        XCall other = transportInfo(wifi, loader);
        assertFalse(other.isReturnEarly());
        assertSame(wifi, other.getResult());

        XCall android11 = transportInfo(wifi, old);
        assertFalse(android11.isReturnEarly());
        assertSame(wifi, android11.getResult());

        XCall none = transportInfo(null, loader);
        assertFalse(none.isReturnEarly());
    }

    // Other transport infos, through XParam, stay
    private static XCall transportInfo(TransportInfo info, ClassLoader loader) throws Exception {
        Globals globals = load("vpn");
        XCall call = new XCall(NetworkCapabilities.class.getMethod("getTransportInfo"), null, new Object[0]);
        call.setOutcome(info, null);
        // Debug builds log the package and uid of the app
        ApplicationInfo ai = new ApplicationInfo();
        ai.uid = 10200;
        globals.set("param", CoerceJavaToLua.coerce(new XParam(new ContextWrapper(null) {
            @Override
            public ClassLoader getClassLoader() {
                return loader;
            }

            @Override
            public ApplicationInfo getApplicationInfo() {
                return ai;
            }

            @Override
            public String getPackageName() {
                return "com.example.app";
            }
        }, call, new HashMap<>())));
        Varargs result = Scripts.run(globals, "return after(hookof('getTransportInfo'), param)");
        assertFalse(result.arg1().toboolean());
        return call;
    }

    // A VPN over Wi-Fi has the transport info (WifiInfo, from Android 12) and the signal strength of that network, in the
    // getters and in the text where each version prints them; a VPN over mobile data has neither, like mobile data
    @Test
    public void vpnOverWifiHasItsTransportInfo() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        Scripts.run(globals, String.join("\n",
                "wifiinfo = infoof(1, 'WIFI')",
                "infos.wifi = wifiinfo",
                "transport = { toString = function(self) return 'SSID: <unknown ssid>, RSSI: -55' end }",
                "local wifi = capsof(1)",
                "wifi.hasCapability = function(self, capability) return capability == 12 end",
                "wifi.getTransportInfo = function(self) return transport end",
                "wifi.getSignalStrength = function(self) return -55 end",
                "local mobile = capsof(0)",
                "mobile.hasCapability = wifi.hasCapability",
                "mobile.getTransportInfo = function(self) return nil end",
                "mobile.getSignalStrength = function(self) return -2147483648 end",
                "caps = { vpn = capsof(1, 4), wifi = wifi, mobile = mobile }",
                "cm.getAllNetworks = function(self) return { length = 3, 'vpn', 'mobile', 'wifi' } end",
                "cm.getNetworkCapabilities = function(self, network) return caps[network] end",
                "function over(info) cm.getActiveNetworkInfo = function(self) return info end end",
                "function getter(method, result, this)",
                "  local param = paramof(result, {}, this or caps.vpn, context)",
                "  param.isInstance = function(self, object, name)",
                "    return object == 'vpninfo' and name == 'android.net.VpnTransportInfo' end",
                "  return after(hookof(method), param), param.result",
                "end",
                "function text(value, this)",
                "  local param = paramof(value, {}, this or caps.vpn, context)",
                "  after(hookof('toString', 'android.net.NetworkCapabilities'), param)",
                "  return param.result",
                "end"));
        Varargs wifi = Scripts.run(globals, "over(wifiinfo)\n" +
                "local info, set = getter('getTransportInfo', 'vpninfo')\n" +
                "local signal, strength = getter('getSignalStrength', -2147483648)\n" +
                "return info, set == transport, signal, strength, getter('getSignalStrength', -2147483648, caps.wifi)");
        assertTrue(wifi.arg(1).toboolean());
        assertTrue(wifi.arg(2).toboolean());
        assertTrue(wifi.arg(3).toboolean());
        assertEquals(-55, wifi.arg(4).toint());
        assertFalse(wifi.arg(5).toboolean());

        // Android 12 and later print the transport info and the signal strength in place of the VpnTransportInfo
        String vpn = " TransportInfo: <VpnTransportInfo{type=1, sessionId=null, bypassable=false" +
                " longLivedTcpConnectionsExpensive=false}>";
        String android14 = "[ Transports: %s Capabilities: INTERNET&TRUSTED%s&VALIDATED LinkUpBandwidth>=1000Kbps%s" +
                " UnderlyingNetworks: Null]";
        assertEquals(String.format(android14, "WIFI", "&NOT_VPN", " TransportInfo: <SSID: <unknown ssid>, RSSI: -55>" +
                " SignalStrength: -55"), text(globals, String.format(android14, "WIFI|VPN", "", vpn), null));
        // Android 10 and 11, whose WifiInfo is no transport info, print the signal strength before the requestor,
        // Android 8.1 and 9 at the end
        globals.set("transport", LuaValue.NIL);
        assertEquals("[ Transports: WIFI Capabilities: INTERNET&TRUSTED&NOT_VPN&VALIDATED SignalStrength: -55" +
                        " RequestorUid: -1 RequestorPackageName: null]",
                text(globals, "[ Transports: WIFI|VPN Capabilities: INTERNET&TRUSTED&VALIDATED RequestorUid: -1" +
                        " RequestorPackageName: null]", null));
        assertEquals("[ Transports: WIFI Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VPN SignalStrength: -55]",
                text(globals, "[ Transports: VPN Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED]", "capsof(4)"));

        Varargs mobile = Scripts.run(globals, "over(mobileinfo)\n" +
                "caps.vpn = capsof(0, 4)\n" +
                "local info, set = getter('getTransportInfo', 'vpninfo')\n" +
                "return info, set, getter('getSignalStrength', -2147483648)");
        assertTrue(mobile.arg(1).toboolean());
        assertTrue(mobile.arg(2).isnil());
        assertFalse(mobile.arg(3).toboolean());
        assertEquals(String.format(android14, "CELLULAR", "&NOT_VPN", ""),
                text(globals, String.format(android14, "CELLULAR|VPN", "", vpn), null));
    }

    // The text of capabilities through the hook, of the capabilities of a VPN unless others are given as Lua
    private static String text(Globals globals, String text, String capabilities) {
        globals.set("value", LuaValue.valueOf(text));
        return Scripts.run(globals, "return text(value, " + (capabilities == null ? "nil" : capabilities) + ")")
                .arg1().tojstring();
    }

    // The parameters of the first script keep the values of both, like XParam; the hooks of the groups run
    // in the given order, until one skips the method, like the backends
    private static final String RECORDER = String.join("\n",
            "function source(recorder, method, group, value)",
            "  before(hookof(method, nil, group), paramof(nil, { [0] = value }, recorder))",
            "end",
            "function call(recorder, method, groups)",
            "  recorder.reset = function(self) self.resets = (self.resets or 0) + 1 end",
            "  for _, group in ipairs(groups) do",
            "    local param = paramof(nil, {}, recorder)",
            "    local restricted = control.before(control.hookof(method, nil, group), param)",
            "    if param.set then return group, restricted end",
            "  end",
            "end",
            "ALL = { 'Use.Camera', 'Record.Video', 'Record.Audio' }");

    // The media server opens the camera itself for the camera source; pause and resume would throw like stop
    @Test
    public void recorderWithCameraDoesNotStart() throws Exception {
        Globals globals = load("mediarecorder_setsource");
        globals.set("control", load("mediarecorder_startstop"));
        Scripts.run(globals, RECORDER);
        Varargs result = Scripts.run(globals,
                "local camera, screen = {}, {}\n" +
                        "source(camera, 'setVideoSource', 'Use.Camera', 1)\n" +
                        "source(screen, 'setVideoSource', 'Use.Camera', 2)\n" +
                        "local camerao = { 'Use.Camera' }\n" +
                        "local started, paused, stopped = call(camera, 'start', camerao), call(camera, 'pause', camerao),\n" +
                        "  call(camera, 'stop', camerao)\n" +
                        "return started, paused, stopped, call(camera, 'start', camerao), call(screen, 'start', camerao)");
        assertEquals("Use.Camera", result.arg(1).tojstring());
        assertEquals("Use.Camera", result.arg(2).tojstring());
        assertEquals("Use.Camera", result.arg(3).tojstring());
        assertTrue(result.arg(4).isnil());
        assertTrue(result.arg(5).isnil());
    }

    // The group of the restricted source reports and notifies, whichever hook runs first
    @Test
    public void recorderIsBlockedByGroupOfSource() throws Exception {
        Globals globals = load("mediarecorder_setsource");
        globals.set("control", load("mediarecorder_startstop"));
        Scripts.run(globals, RECORDER);
        Varargs result = Scripts.run(globals,
                "local voice, screen, video = {}, {}, {}\n" +
                        "source(voice, 'setAudioSource', 'Record.Audio', 1)\n" +
                        "source(screen, 'setVideoSource', 'Record.Video', 2)\n" +
                        "source(screen, 'setVideoSource', 'Use.Camera', 2)\n" +
                        "source(video, 'setVideoSource', 'Use.Camera', 1)\n" +
                        "source(video, 'setVideoSource', 'Record.Video', 1)\n" +
                        "source(video, 'setAudioSource', 'Record.Audio', 1)\n" +
                        "local voiceg, voicer = call(voice, 'start', ALL)\n" +
                        "local screeng = call(screen, 'start', ALL)\n" +
                        "local pauseg = call(voice, 'pause', ALL)\n" +
                        // The hook which skips stopping forgets the sources of the other groups
                        "local stopg = call(video, 'stop', { 'Record.Audio', 'Use.Camera', 'Record.Video' })\n" +
                        "return voiceg, voicer, screeng, pauseg, stopg, call(video, 'start', ALL)");
        assertEquals("Record.Audio", result.arg(1).tojstring());
        assertTrue(result.arg(2).toboolean());
        assertEquals("Record.Video", result.arg(3).tojstring());
        assertEquals("Record.Audio", result.arg(4).tojstring());
        assertEquals("Record.Audio", result.arg(5).tojstring());
        assertTrue(result.arg(6).isnil());
    }

    // A recorder is configured again after a reset, so an audio recording after a blocked camera recording starts
    @Test
    public void recorderResetForgetsSources() throws Exception {
        Globals globals = load("mediarecorder_setsource");
        globals.set("control", load("mediarecorder_startstop"));
        Scripts.run(globals, RECORDER);
        Varargs result = Scripts.run(globals,
                "local recorder = {}\n" +
                        "source(recorder, 'setVideoSource', 'Use.Camera', 1)\n" +
                        "local blocked = call(recorder, 'start', { 'Use.Camera' })\n" +
                        "local param = paramof(nil, {}, recorder)\n" +
                        "local reset = control.before(control.hookof('reset', nil, 'Use.Camera'), param)\n" +
                        "return blocked, reset, param.set, call(recorder, 'start', { 'Use.Camera' })");
        assertEquals("Use.Camera", result.arg(1).tojstring());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).isnil());
    }

    @Test
    public void cellInfoRequestIsAnswered() throws Exception {
        // Java proxies and lists as Lua sees them, see ContentResolverScriptTest
        Globals globals = load("telephonymanager_requestcellinfoupdate");
        Varargs result = Scripts.run(globals,
                "luajava = { createProxy = function(name, object) assert(name == 'java.lang.Runnable') return object end,\n" +
                        "  newInstance = function(name, runnable)\n" +
                        "    if name == 'java.lang.Thread' then return { start = function(self) thread = true runnable:run() end } end\n" +
                        "    return { isEmpty = function() return true end } end }\n" +
                        "function permitted(param, granted) param.getApplicationContext = function(self) return {\n" +
                        "  checkSelfPermission = function(self, name) return granted and 0 or -1 end } end return param end\n" +
                        "local answer\n" +
                        "local executor = { execute = function(self, runnable) runnable:run() end }\n" +
                        "local callback = { onCellInfo = function(self, cells) answer = cells end }\n" +
                        "local param = permitted(paramof(nil, { [0] = executor, [1] = callback }), true)\n" +
                        "local denied = permitted(paramof(nil, { [0] = executor, [1] = callback }), false)\n" +
                        "return before(hookof('requestCellInfoUpdate'), param), param.set, answer:isEmpty(), thread,\n" +
                        "  before(hookof('requestCellInfoUpdate'), denied), denied.set");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        // On a thread of its own, like Android answers from the binder
        assertTrue(result.arg(4).toboolean());
        // Without a location permission Android refuses the request itself
        assertFalse(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());

        // An app which fails on no cells, with a direct executor, does not let the real request through
        Varargs failing = Scripts.run(globals,
                "local executor = { execute = function(self, runnable) runnable:run() end }\n" +
                        "local callback = { onCellInfo = function(self, cells) error('no cells') end }\n" +
                        "local param = permitted(paramof(nil, { [0] = executor, [1] = callback }), true)\n" +
                        "return before(hookof('requestCellInfoUpdate'), param), param.set");
        assertTrue(failing.arg(1).toboolean());
        assertTrue(failing.arg(2).toboolean());
    }

    // Handlers and loopers as Lua sees them, which keep the posted answers
    private static final String HANDLERS = String.join("\n",
            "posted = {}",
            "function handlerof(name)",
            "  return { name = name, post = function(self, runnable) posted[#posted + 1] = { self.name, runnable } end }",
            "end",
            "luajava = { createProxy = function(name, object) assert(name == 'java.lang.Runnable') return object end,",
            "  bindClass = function(name) assert(name == 'android.os.Looper')",
            "    return { getMainLooper = function(self) return 'main' end } end,",
            "  newInstance = function(name, looper) assert(name == 'android.os.Handler') return handlerof(looper) end }");

    // Android answers discovering, later on the thread of the listener or the callback
    @Test
    public void wifiDiscoveryIsAnswered() throws Exception {
        Globals globals = load("wifi_discovery");
        Scripts.run(globals, HANDLERS);
        Varargs result = Scripts.run(globals,
                "local failed = {}\n" +
                        "local listener = { onFailure = function(self, reason) failed[#failed + 1] = reason end }\n" +
                        "local callback = { onSessionConfigFailed = function(self) failed[#failed + 1] = 'aware' end }\n" +
                        "local peers = paramof(nil, { [0] = 'channel', [1] = listener })\n" +
                        "local manager = { isChannelConstrainedDiscoverySupported = function(self) return true end }\n" +
                        "local frequency = paramof(nil, { [0] = 'channel', [1] = 2412, [2] = listener }, manager)\n" +
                        "local quiet = paramof(nil, { [0] = 'channel', [1] = nil })\n" +
                        "local aware = paramof(nil, { [0] = 'config', [1] = callback, [2] = handlerof('app') })\n" +
                        "local restricted = before(hookof('discoverPeers', nil, nil, { length = 2 }), peers)\n" +
                        "before(hookof('discoverPeersOnSpecificFrequency', nil, nil, { length = 3 }), frequency)\n" +
                        "before(hookof('discoverServices', nil, nil, { length = 2 }), quiet)\n" +
                        "before(hookof('subscribe', nil, nil, { length = 3 }), aware)\n" +
                        "local early = #failed\n" +
                        "for _, post in ipairs(posted) do post[2].run() end\n" +
                        "return restricted, peers.set, quiet.set, aware.set, early, #posted,\n" +
                        "  posted[1][1], posted[3][1], failed[1], failed[2], failed[3]");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertEquals(0, result.arg(5).toint());
        assertEquals(3, result.arg(6).toint());
        assertEquals("main", result.arg(7).tojstring());
        assertEquals("app", result.arg(8).tojstring());
        assertEquals(0, result.arg(9).toint());
        assertEquals(0, result.arg(10).toint());
        assertEquals("aware", result.arg(11).tojstring());
    }

    // The channel data has no channels, on the executor Android answers on, whatever the scan found; without an
    // executor or a callback the real call throws like Android
    @Test
    public void wifiChannelDataIsEmpty() throws Exception {
        Globals globals = load("wifimanager_getchanneldata");
        Varargs result = Scripts.run(globals,
                "GOT = {}\n" +
                        "local callback = { accept = function(self, channels) GOT[#GOT + 1] = channels end }\n" +
                        "local function request(executor, consumer)\n" +
                        "  local param = paramof(nil, { [0] = executor, [1] = consumer })\n" +
                        "  param.setArgument = function(self, index, value) self.args[index] = value end\n" +
                        "  return param, before(hookof('getChannelData'), param)\n" +
                        "end\n" +
                        "local param, restricted = request('executor', callback)\n" +
                        "local quiet, nocallback = request('executor', nil)\n" +
                        "local other, noexecutor = request(nil, callback)\n" +
                        "return restricted, param.args[1], nocallback, quiet.args[1], noexecutor, other.args[1] == callback");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).isnil());
        assertFalse(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
        // Android calls the proxy with the channels of the scan, the callback of the app gets none
        @SuppressWarnings("unchecked")
        java.util.function.Consumer<Object> proxy = (java.util.function.Consumer<Object>) result.arg(2).touserdata();
        proxy.accept(new java.util.ArrayList<>(java.util.Collections.singletonList("2437 MHz, 5 access points")));
        Varargs got = Scripts.run(globals, "return #GOT, GOT[1]");
        assertEquals(1, got.arg(1).toint());
        assertEquals(new java.util.ArrayList<>(), got.arg(2).touserdata());
        assertEquals(java.util.ArrayList.class, got.arg(2).touserdata().getClass());
    // The listener of Wi-Fi Direct runs on the handler of the channel (its field mHandler), on the looper the app gave
    }

    // to initialize; without the field, like when the hidden API rules leave it out, on the main looper
    @Test
    public void wifiDirectIsAnsweredOnTheHandlerOfTheChannel() throws Exception {
        Globals globals = load("wifi_discovery");
        Scripts.run(globals, HANDLERS);
        Varargs result = Scripts.run(globals,
                "local field = { getName = function(self) return 'mHandler' end, setAccessible = function(self, on) end,\n" +
                        "  get = function(self, channel) return channel.handler end }\n" +
                        "local fields = { length = 1, field }\n" +
                        "local bind = luajava.bindClass\n" +
                        "luajava.bindClass = function(name)\n" +
                        "  if name == 'java.lang.Class' then\n" +
                        "    return { getDeclaredFields = function(class)\n" +
                        "      assert(class == 'android.net.wifi.p2p.WifiP2pManager$Channel') return fields end }\n" +
                        "  elseif name == 'android.os.Looper' then return bind(name) end\n" +
                        "  return name\n" +
                        "end\n" +
                        "local failed = {}\n" +
                        "local listener = { onFailure = function(self, reason) failed[#failed + 1] = reason end }\n" +
                        "local channel = { handler = handlerof('thread') }\n" +
                        "before(hookof('discoverPeers', nil, nil, { length = 2 }), paramof(nil, { [0] = channel, [1] = listener }))\n" +
                        "fields = { length = 0 }\n" +
                        "before(hookof('discoverServices', nil, nil, { length = 2 }), paramof(nil, { [0] = channel, [1] = listener }))\n" +
                        "local early = #failed\n" +
                        "for _, post in ipairs(posted) do post[2].run() end\n" +
                        "return early, #posted, posted[1][1], posted[2][1], failed[1], failed[2]");
        assertEquals(0, result.arg(1).toint());
        assertEquals(2, result.arg(2).toint());
        assertEquals("thread", result.arg(3).tojstring());
        assertEquals("main", result.arg(4).tojstring());
        assertEquals(0, result.arg(5).toint());
        assertEquals(0, result.arg(6).toint());
    }

    // initialize keeps its channel and only records the looper the app gave, on which the listeners of the channel
    // then answer, also without the field of the handler
    @Test
    public void wifiDirectIsAnsweredOnTheLooperOfInitialize() throws Exception {
        Globals globals = load("wifi_discovery");
        Scripts.run(globals, HANDLERS);
        Varargs result = Scripts.run(globals,
                "luajava.bindClass = function(name) error('no reflection for ' .. name) end\n" +
                        "local channel = {}\n" +
                        "local initialize = paramof(channel, { [0] = 'context', [1] = 'thread', [2] = nil })\n" +
                        "local hook = hookof('initialize', nil, nil, { length = 3 })\n" +
                        "local started = before(hook, initialize)\n" +
                        "local recorded = after(hook, initialize)\n" +
                        "after(hook, paramof(nil, { [0] = 'context', [1] = 'other' }))\n" +
                        "local failed = {}\n" +
                        "local listener = { onFailure = function(self, reason) failed[#failed + 1] = reason end }\n" +
                        "before(hookof('discoverPeers', nil, nil, { length = 2 }), paramof(nil, { [0] = channel, [1] = listener }))\n" +
                        "local early = #failed\n" +
                        "for _, post in ipairs(posted) do post[2].run() end\n" +
                        "return started, initialize.set, recorded, initialize.result == channel, early, #posted, posted[1][1], failed[1]");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertEquals(0, result.arg(5).toint());
        assertEquals(1, result.arg(6).toint());
        assertEquals("thread", result.arg(7).tojstring());
        assertEquals(0, result.arg(8).toint());
    // Android throws before it sends anything for a null channel, a variant the device does not support, a wrong
    }

    // frequency and a null config, so these calls run and throw Android's own exception
    @Test
    public void wifiDiscoveryThrowsLikeAndroid() throws Exception {
        Globals globals = load("wifi_discovery");
        Scripts.run(globals, HANDLERS);
        Varargs result = Scripts.run(globals,
                "local function managerof(flexible, r2)\n" +
                        "  return { isChannelConstrainedDiscoverySupported = function(self) return flexible end,\n" +
                        "    isWiFiDirectR2Supported = function(self) return r2 end } end\n" +
                        "local flexible, r2, new = managerof(false, true), managerof(true, false), managerof(true, true)\n" +
                        "local listener = { onFailure = function(self, reason) end }\n" +
                        "local function call(method, manager, ...)\n" +
                        "  local args = { ... }\n" +
                        "  local param = paramof(nil, { [0] = args[1], [1] = args[2], [2] = args[3] }, manager)\n" +
                        "  local restricted = before(hookof(method, nil, nil, { length = select('#', ...) }), param)\n" +
                        "  return restricted or param.set\n" +
                        "end\n" +
                        "return call('discoverPeers', new, nil, listener),\n" +
                        "  call('discoverServices', new, nil, listener),\n" +
                        "  call('discoverPeersOnSocialChannels', flexible, 'channel', listener),\n" +
                        "  call('discoverPeersOnSocialChannels', new, nil, listener),\n" +
                        "  call('discoverPeersOnSpecificFrequency', flexible, 'channel', 2412, listener),\n" +
                        "  call('discoverPeersOnSpecificFrequency', new, 'channel', 0, listener),\n" +
                        "  call('startPeerDiscovery', flexible, 'channel', 'config', listener),\n" +
                        "  call('startPeerDiscovery', new, 'channel', nil, listener),\n" +
                        "  call('discoverUsdBasedServices', r2, 'channel', 'config', listener),\n" +
                        "  call('discoverUsdBasedServices', new, 'channel', nil, listener),\n" +
                        "  #posted,\n" +
                        "  call('discoverPeersOnSocialChannels', new, 'channel', listener),\n" +
                        "  call('startPeerDiscovery', new, 'channel', 'config', listener),\n" +
                        "  call('discoverUsdBasedServices', new, 'channel', 'config', listener),\n" +
                        "  call('discoverPeersOnSocialChannels', r2, 'channel', listener),\n" +
                        "  call('discoverUsdBasedServices', flexible, 'channel', 'config', listener),\n" +
                        "  #posted");
        for (int i = 1; i <= 10; i++)
            assertFalse("call " + i, result.arg(i).toboolean());
        assertEquals(0, result.arg(11).toint());
        assertTrue(result.arg(12).toboolean());
        assertTrue(result.arg(13).toboolean());
        assertTrue(result.arg(14).toboolean());
        // Each variant asks for its own feature
        assertTrue(result.arg(15).toboolean());
        assertTrue(result.arg(16).toboolean());
        assertEquals(5, result.arg(17).toint());
    }

    // A Bluetooth LE scan by a variant with its arguments, on an Android version, with the scanner of Bluetooth on for LE
    // or not, in a state, with the granted permissions and with filtering on the chip or not; returns whether it was
    // restricted, whether a result was set, the result, the last argument and the settings. The check of Android's
    // refusal runs once unprotected too, so a stand-in which a script calls and the test lacks fails the test.
    private static final String SCANS = String.join("\n",
            "SCANNER = 'android.bluetooth.le.BluetoothLeScanner'",
            "ADAPTER = 'android.bluetooth.BluetoothAdapter'",
            "local le = 'android.bluetooth.le.'",
            "VARIANTS = {",
            "  callback = { SCANNER, 'void', { le .. 'ScanCallback' } },",
            "  filters = { SCANNER, 'void', { 'java.util.List', le .. 'ScanSettings', le .. 'ScanCallback' } },",
            "  pending = { SCANNER, 'int', { 'java.util.List', le .. 'ScanSettings', 'android.app.PendingIntent' } },",
            "  le = { ADAPTER, 'boolean', { ADAPTER .. '$LeScanCallback' } },",
            "  uuids = { ADAPTER, 'boolean', { 'java.util.UUID[]', ADAPTER .. '$LeScanCallback' } } }",
            "SCAN = { ['android.permission.BLUETOOTH_SCAN'] = true }",
            "LEGACY = { ['android.permission.BLUETOOTH'] = true, ['android.permission.BLUETOOTH_ADMIN'] = true }",
            // ScanSettings, by default of all matches, without a delay, a low power mode and full results
            "function settingsof(callbackType, delay, mode, resultType)",
            "  return { getCallbackType = function(self) return callbackType or 1 end,",
            "    getReportDelayMillis = function(self) return delay or 0 end,",
            "    getScanMode = function(self) return mode or 0 end,",
            "    getScanResultType = function(self) return resultType or 0 end }",
            "end",
            // A list of ScanFilters, each 'empty', of something else or nil
            "function filtersof(...)",
            "  local filters = { n = select('#', ...), ... }",
            "  return { size = function(self) return filters.n end,",
            "    get = function(self, index)",
            "      local filter = filters[index + 1]",
            "      return filter and { equals = function(self, other) return filter == 'empty' and other == 'empty' end } end }",
            "end",
            "function scan(variant, args, sdk, on, state, granted, offload)",
            "  local adapter = { getBluetoothLeScanner = function(self) return on and 'scanner' or nil end,",
            "    getState = function(self) return state end,",
            "    isOffloadedFilteringSupported = function(self) return offload end }",
            "  luajava = { bindClass = function(name)",
            "      if name == 'android.os.Build$VERSION' then return { SDK_INT = sdk } end",
            "      assert(name == ADAPTER, name)",
            "      return { getDefaultAdapter = function(self) return adapter end } end,",
            "    newInstance = function(name)",
            "      if name == le .. 'ScanFilter$Builder' then return { build = function(self) return 'empty' end } end",
            "      assert(name == le .. 'ScanSettings$Builder', name)",
            "      return { setScanMode = function(self, mode) self.mode = mode return self end,",
            "        build = function(self) return settingsof(1, 0, self.mode, 0) end } end }",
            "  local context = { checkSelfPermission = function(self, name) return granted[name] and 0 or -1 end }",
            "  local class, returnType, types = table.unpack(VARIANTS[variant])",
            "  local hook = hookof('start', class, 'Read.Network', arrayof(table.unpack(types)))",
            "  hook.getReturnType = function(self) return returnType end",
            "  local function parameter()",
            "    local copy = {}",
            "    for index, value in pairs(args) do copy[index] = value end",
            "    local param = paramof(nil, copy, class == ADAPTER and adapter or 'scanner', context)",
            "    param.setArgument = function(self, index, value) self.args[index] = value end",
            "    return param",
            "  end",
            "  refuses(hook, parameter())",
            "  local param = parameter()",
            "  local restricted = before(hook, param)",
            "  return restricted, param.set, param.result, param.args[#types - 1], param.args[1]",
            "end");

    private static Varargs scan(Globals globals, String variant, String args, int sdk, boolean on, int state, String granted) {
        return scan(globals, variant, args, sdk, on, state, granted, false);
    }

    private static Varargs scan(Globals globals, String variant, String args, int sdk, boolean on, int state, String granted,
                                boolean offload) {
        return Scripts.run(globals, "return scan('" + variant + "', " + args + ", " + sdk + ", " + on + ", " + state + ", " +
                granted + ", " + offload + ")");
    }

    // A scan which Android starts is skipped with the answer of a started scan, which then finds nothing
    @Test
    public void bluetoothScansStartWithoutScanning() throws Exception {
        Globals globals = load("bluetooth_scan");
        Scripts.run(globals, Scripts.ARRAYOF + "\n" + SCANS);
        String[][] started = {{"callback", "{ [0] = 'cb' }", "nil"},
                {"filters", "{ [0] = 'filters', [1] = settingsof(), [2] = 'cb' }", "nil"},
                {"pending", "{ [1] = settingsof(), [2] = 'intent' }", "0"},
                // Android takes the default settings for a PendingIntent
                {"pending", "{ [2] = 'intent' }", "0"},
                {"le", "{ [0] = 'cb' }", "true"}, {"uuids", "{ [0] = 'uuids', [1] = 'cb' }", "true"}};
        for (String[] variant : started)
            for (int sdk : new int[]{27, 34}) {
                Varargs result = scan(globals, variant[0], variant[1], sdk, true, 12, sdk < 31 ? "LEGACY" : "SCAN");
                String name = variant[0] + " " + variant[1] + " " + sdk;
                assertTrue(name, result.arg(1).toboolean());
                assertTrue(name, result.arg(2).toboolean());
                assertEquals(name, variant[2], result.arg(3).tojstring());
                assertFalse(name, result.arg(4).isnil());
            }
        // While Bluetooth turns on or off, LE is on for the scanner from Android 11 (isLeEnabled)
        for (int state : new int[]{11, 13}) {
            Varargs result = scan(globals, "filters", "{ [1] = settingsof(), [2] = 'cb' }", 30, false, state, "LEGACY");
            assertTrue(result.arg(1).toboolean());
            assertTrue(result.arg(2).toboolean());
        }
        // A lookup which fails skips the scan, which the call would start
        Varargs result = Scripts.run(globals, "luajava = { bindClass = function(name) error('refused') end }\n" +
                "local param = paramof(nil, { [0] = 'cb' }, 'scanner', nil)\n" +
                "local hook = hookof('startScan', SCANNER, 'Read.Network', arrayof(VARIANTS.callback[3][1]))\n" +
                "hook.getReturnType = function(self) return 'void' end\n" +
                "return before(hook, param), param.set, param.result");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).isnil());
    }

    // Where Android throws or refuses before it scans, the call runs
    @Test
    public void bluetoothScansRefusedByAndroidRun() throws Exception {
        Globals globals = load("bluetooth_scan");
        Scripts.run(globals, Scripts.ARRAYOF + "\n" + SCANS);
        // Without the callback or the PendingIntent, or without the settings for a callback
        String[][] arguments = {{"callback", "{}"}, {"filters", "{ [1] = settingsof() }"}, {"filters", "{ [2] = 'cb' }"},
                {"pending", "{}"}, {"le", "{}"}, {"uuids", "{ [0] = 'uuids' }"}};
        for (String[] variant : arguments) {
            Varargs result = scan(globals, variant[0], variant[1], 34, true, 12, "SCAN");
            assertFalse(variant[0], result.arg(1).toboolean());
            assertFalse(variant[0], result.arg(2).toboolean());
        }
        // Bluetooth off for LE, also while it turns on for startLeScan and before Android 11 for the scanner, runs without
        // the callback, which cannot scan when Bluetooth turns on in the meantime
        String[][] off = {{"callback", "{ [0] = 'cb' }", "34", "10"}, {"pending", "{ [2] = 'intent' }", "34", "10"},
                {"filters", "{ [1] = settingsof(), [2] = 'cb' }", "29", "11"}, {"le", "{ [0] = 'cb' }", "34", "11"},
                {"uuids", "{ [0] = 'uuids', [1] = 'cb' }", "27", "10"}};
        for (String[] variant : off) {
            int sdk = Integer.parseInt(variant[2]);
            Varargs result = scan(globals, variant[0], variant[1], sdk, false, Integer.parseInt(variant[3]),
                    sdk < 31 ? "LEGACY" : "SCAN");
            String name = variant[0] + " " + variant[2] + " " + variant[3];
            assertFalse(name, result.arg(1).toboolean());
            assertFalse(name, result.arg(2).toboolean());
            assertTrue(name, result.arg(4).isnil());
        }
        // Without the permission Android checks first, with the arguments of the app
        String[][] denied = {{"callback", "{ [0] = 'cb' }", "34", "{}"}, {"le", "{ [0] = 'cb' }", "31", "LEGACY"},
                {"filters", "{ [1] = settingsof(), [2] = 'cb' }", "30", "{ ['android.permission.BLUETOOTH_ADMIN'] = true }"},
                {"pending", "{ [2] = 'intent' }", "27", "{ ['android.permission.BLUETOOTH'] = true }"},
                // Android 8.1 to 11 check BLUETOOTH for the state of Bluetooth, also for a PendingIntent
                {"pending", "{ [2] = 'intent' }", "27", "{ ['android.permission.BLUETOOTH_ADMIN'] = true }"},
                {"pending", "{ [2] = 'intent' }", "30", "{ ['android.permission.BLUETOOTH_ADMIN'] = true }"}};
        for (String[] variant : denied) {
            Varargs result = scan(globals, variant[0], variant[1], Integer.parseInt(variant[2]), true, 12, variant[3]);
            String name = variant[0] + " " + variant[2];
            assertFalse(name, result.arg(1).toboolean());
            assertFalse(name, result.arg(2).toboolean());
            assertFalse(name, result.arg(4).isnil());
        }
    }

    // Settings the chip cannot do, and for a PendingIntent what needs BLUETOOTH_PRIVILEGED, Android refuses before it scans,
    // so the call runs with the arguments of the app; what Android scans is still skipped
    @Test
    public void bluetoothScansRefusedForTheirSettingsRun() throws Exception {
        Globals globals = load("bluetooth_scan");
        Scripts.run(globals, Scripts.ARRAYOF + "\n" + SCANS);
        String privileged = "{ ['android.permission.BLUETOOTH_SCAN'] = true, ['android.permission.BLUETOOTH_PRIVILEGED'] = true }";
        // Variant, arguments, Android version, state, filtering on the chip, whether the call runs
        String[][] cases = {
                // Without filtering on the chip only callbacks of all matches without a delay
                {"filters", "{ [1] = settingsof(2), [2] = 'cb' }", "34", "12", "false", "run"},
                {"filters", "{ [1] = settingsof(8), [2] = 'cb' }", "34", "12", "false", "run"},
                {"pending", "{ [1] = settingsof(1, 1000), [2] = 'intent' }", "27", "12", "false", "run"},
                {"filters", "{ [1] = settingsof(1, 0, 2), [2] = 'cb' }", "34", "12", "false", "skip"},
                {"filters", "{ [1] = settingsof(1, 1000), [2] = 'cb' }", "34", "12", "true", "skip"},
                // Found or lost callbacks need filters which match something
                {"filters", "{ [1] = settingsof(2), [2] = 'cb' }", "34", "12", "true", "run"},
                {"pending", "{ [1] = settingsof(4), [2] = 'intent' }", "27", "12", "true", "run"},
                {"filters", "{ [0] = filtersof('address', 'empty'), [1] = settingsof(6), [2] = 'cb' }", "34", "12", "true", "run"},
                {"filters", "{ [0] = filtersof('address', nil), [1] = settingsof(2), [2] = 'cb' }", "34", "12", "true", "run"},
                {"filters", "{ [0] = filtersof('address'), [1] = settingsof(2), [2] = 'cb' }", "34", "12", "true", "skip"},
                // A PendingIntent while Bluetooth is not fully on, for LE only or turning on or off
                {"pending", "{ [2] = 'intent' }", "34", "10", "false", "run"},
                {"pending", "{ [1] = settingsof(), [2] = 'intent' }", "27", "10", "false", "run"},
                {"pending", "{ [2] = 'intent' }", "30", "11", "false", "run"},
                {"pending", "{ [2] = 'intent' }", "34", "13", "false", "run"},
                {"filters", "{ [1] = settingsof(), [2] = 'cb' }", "34", "10", "false", "skip"},
                {"callback", "{ [0] = 'cb' }", "34", "10", "false", "skip"},
                // Ambient discovery from Android 12 and batches of abbreviated results for a PendingIntent
                {"pending", "{ [1] = settingsof(1, 0, 3), [2] = 'intent' }", "31", "12", "false", "run"},
                {"pending", "{ [1] = settingsof(1, 5000, 0, 1), [2] = 'intent' }", "34", "12", "true", "run"},
                {"pending", "{ [1] = settingsof(1, 5000, 0, 0), [2] = 'intent' }", "34", "12", "true", "skip"},
                {"filters", "{ [1] = settingsof(1, 0, 3), [2] = 'cb' }", "34", "12", "false", "skip"}};
        for (String[] variant : cases) {
            int sdk = Integer.parseInt(variant[2]);
            int state = Integer.parseInt(variant[3]);
            String name = String.join(" ", variant);
            // Android gives the scanner for LE only and on, not while Bluetooth turns on or off
            Varargs result = scan(globals, variant[0], variant[1], sdk, state == 10 || state == 12, state, sdk < 31 ? "LEGACY" : "SCAN",
                    Boolean.parseBoolean(variant[4]));
            boolean runs = variant[5].equals("run");
            assertEquals(name, !runs, result.arg(1).toboolean());
            assertEquals(name, !runs, result.arg(2).toboolean());
            assertFalse(name, result.arg(4).isnil());
        }
        // While Bluetooth turns on from Android 12, the settings of ambient discovery keep the refusal of the service when
        // Bluetooth is on by the time it checks, while before they do not exist and in other states the settings stay
        Varargs result = scan(globals, "pending", "{ [2] = 'intent' }", 34, false, 11, "SCAN");
        assertEquals(3, result.arg(5).get("getScanMode").call(result.arg(5)).toint());
        assertTrue(scan(globals, "pending", "{ [2] = 'intent' }", 30, false, 11, "LEGACY").arg(5).isnil());
        assertTrue(scan(globals, "pending", "{ [2] = 'intent' }", 34, true, 10, "SCAN").arg(5).isnil());
        result = scan(globals, "pending", "{ [1] = settingsof(1, 0, 2), [2] = 'intent' }", 34, false, 13, "SCAN");
        assertEquals(2, result.arg(5).get("getScanMode").call(result.arg(5)).toint());
        // An app with BLUETOOTH_PRIVILEGED scans
        for (String args : new String[]{"{ [2] = 'intent' }", "{ [1] = settingsof(1, 0, 3), [2] = 'intent' }"}) {
            result = scan(globals, "pending", args, 34, true, 10, privileged);
            assertTrue(args, result.arg(1).toboolean());
            assertEquals(args, 0, result.arg(3).toint());
        }
    }

    @Test
    public void cameraErrorsAreAndroids() throws Exception {
        Globals globals = load("camera_open");
        Varargs result = Scripts.run(globals,
                "luajava = { newInstance = function(name, message)\n" +
                        "  return { getMessage = function() return message end } end }\n" +
                        "local info = paramof()\n" +
                        "local open = paramof()\n" +
                        "local default = paramof()\n" +
                        "before(hookof('getCameraInfo'), info)\n" +
                        "before(hookof('open', nil, nil, { length = 1 }), open)\n" +
                        "local restricted = before(hookof('open', nil, nil, { length = 0 }), default)\n" +
                        "return info.result:getMessage(), open.result:getMessage(), restricted, default.set, default.result");
        assertEquals("Fail to get camera info", result.arg(1).tojstring());
        assertEquals("Fail to connect to camera service", result.arg(2).tojstring());
        // Camera.open() finds no camera facing back without cameras, like Android
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).isnil());
    }

    // Of extractResult and of the creator, which R8 can merge with the creators of other classes, whose objects stay
    @Test
    public void transitionResultIsEmpty() throws Exception {
        Globals globals = load("activitytransitionresult_extractresult");
        Varargs result = Scripts.run(globals,
                "local class = 'com.google.android.gms.location.ActivityTransitionResult'\n" +
                        "classes[class] = true\n" +
                        "luajava = { newInstance = function(name) assert(name == 'java.util.ArrayList') return {} end,\n" +
                        "  new = function(class, events) return { class = class, events = events } end }\n" +
                        "local function resultof(object) local param = paramof(object)\n" +
                        "  param.isInstance = function(self, object, name) return object ~= nil and object.class == name end\n" +
                        "  return param end\n" +
                        "local param = resultof({ class = class })\n" +
                        "local creator = resultof({ class = class })\n" +
                        "local other = resultof({ class = 'com.google.android.gms.location.ActivityTransitionEvent' })\n" +
                        "return after(hookof('extractResult'), param), param.result.class, #param.result.events,\n" +
                        "  after(hookof('CREATOR:createFromParcel'), creator), #creator.result.events,\n" +
                        "  after(hookof('CREATOR:createFromParcel'), other), other.result.events,\n" +
                        "  after(hookof('extractResult'), resultof(nil))");
        assertTrue(result.arg(1).toboolean());
        assertEquals("com.google.android.gms.location.ActivityTransitionResult", result.arg(2).tojstring());
        assertEquals(0, result.arg(3).toint());
        assertTrue(result.arg(4).toboolean());
        assertEquals(0, result.arg(5).toint());
        assertFalse(result.arg(6).toboolean());
        assertTrue(result.arg(7).isnil());
        assertFalse(result.arg(8).toboolean());
    }

    // Bundles and application infos as Lua sees them
    private static final String ANALYTICS = String.join("\n",
            "function bundleof()",
            "  return { items = {},",
            "    putAll = function(self, other) for k, v in pairs(other.items) do self.items[k] = v end end,",
            "    containsKey = function(self, key) return self.items[key] ~= nil end,",
            "    getBoolean = function(self, key) return self.items[key] == true end,",
            "    putBoolean = function(self, key, value) self.items[key] = value end }",
            "end",
            "luajava = { newInstance = function(name, from)",
            "  if name == 'android.os.Bundle' then return bundleof() end",
            "  assert(name == 'android.content.pm.ApplicationInfo')",
            "  return { copy = true, metaData = from.metaData }",
            "end }",
            "context = {}");

    @Test
    public void analyticsAreOffInMetaData() throws Exception {
        Globals globals = load("analytics_metadata");
        Scripts.run(globals, ANALYTICS);
        Varargs result = Scripts.run(globals,
                "classes['com.google.firebase.crashlytics.CrashlyticsRegistrar'] = true\n" +
                        "local info = { metaData = bundleof() }\n" +
                        "info.metaData:putBoolean('com.google.android.geo.API_KEY', true)\n" +
                        "local param = paramof(info, { [0] = 'com.example', [1] = 128 }, nil, context)\n" +
                        "local restricted = after(hookof('getApplicationInfo'), param)\n" +
                        "local other = paramof(info, { [0] = 'com.other', [1] = 128 }, nil, context)\n" +
                        "local flags = paramof(info, { [0] = 'com.example', [1] = 0 }, nil, context)\n" +
                        "return restricted, param.result.copy, info.metaData.items.firebase_crashlytics_collection_enabled,\n" +
                        "  param.result.metaData.items.firebase_crashlytics_collection_enabled,\n" +
                        "  param.result.metaData.items['com.google.android.geo.API_KEY'],\n" +
                        "  param.result.metaData.items.firebase_analytics_collection_deactivated,\n" +
                        "  after(hookof('getApplicationInfo'), other), after(hookof('getApplicationInfo'), flags)");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).isnil()); // The cached original is not changed
        assertFalse(result.arg(4).isnil());
        assertFalse(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertTrue(result.arg(6).isnil()); // No Firebase Analytics in the app
        assertFalse(result.arg(7).toboolean());
        assertFalse(result.arg(8).toboolean());
    }

    // Firebase Performance sends traces of app starts, screens and network requests unless deactivated, Sentry its
    // crashes unless not enabled
    @Test
    public void performanceIsOffInMetaData() throws Exception {
        Globals globals = load("analytics_metadata");
        Scripts.run(globals, ANALYTICS);
        Varargs result = Scripts.run(globals,
                "classes['com.google.firebase.perf.FirebasePerfRegistrar'] = true\n" +
                        "classes['io.sentry.android.core.SentryInitProvider'] = true\n" +
                        "local info = { metaData = bundleof() }\n" +
                        "local param = paramof(info, { [0] = 'com.example', [1] = 128 }, nil, context)\n" +
                        "local restricted = after(hookof('getApplicationInfo'), param)\n" +
                        "return restricted, param.result.metaData.items.firebase_performance_collection_deactivated,\n" +
                        "  param.result.metaData.items.firebase_crashlytics_collection_enabled,\n" +
                        "  param.result.metaData.items['io.sentry.enabled']");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).isnil()); // No Crashlytics in the app
        assertFalse(result.arg(4).isnil());
        assertFalse(result.arg(4).toboolean());
    }

    // The package info of the app gets the meta-data as a copy through a parcel, the cached one stays as it is
    @Test
    public void analyticsAreOffInPackageInfo() throws Exception {
        Globals globals = load("analytics_metadata");
        Scripts.run(globals, ANALYTICS);
        Varargs result = Scripts.run(globals,
                "classes['com.google.firebase.analytics.connector.internal.AnalyticsConnectorRegistrar'] = true\n" +
                        "parcel = { copies = 0, writeParcelable = function(self, object) self.object = object end,\n" +
                        "  setDataPosition = function(self, position) end,\n" +
                        "  readParcelable = function(self, loader)\n" +
                        "    local info = self.object.applicationInfo\n" +
                        "    return { applicationInfo = { metaData = info.metaData } } end,\n" +
                        "  recycle = function(self) end }\n" +
                        "luajava.bindClass = function(name)\n" +
                        "  assert(name == 'android.os.Parcel', name)\n" +
                        "  return { obtain = function() return parcel end } end\n" +
                        "local info = { applicationInfo = { metaData = bundleof() } }\n" +
                        "info.applicationInfo.metaData:putBoolean('com.google.android.geo.API_KEY', true)\n" +
                        "local param = paramof(info, { [0] = 'com.example', [1] = 128 }, nil, context)\n" +
                        "local restricted = after(hookof('getPackageInfo'), param)\n" +
                        "local copy = param.result\n" +
                        "local other = paramof(info, { [0] = 'com.other', [1] = 128 }, nil, context)\n" +
                        "local flags = paramof(info, { [0] = 'com.example', [1] = 0 }, nil, context)\n" +
                        "return restricted, copy ~= info, copy.applicationInfo.metaData.items.firebase_analytics_collection_deactivated,\n" +
                        "  info.applicationInfo.metaData.items.firebase_analytics_collection_deactivated,\n" +
                        "  info.applicationInfo.metaData.items['com.google.android.geo.API_KEY'],\n" +
                        "  copy.applicationInfo.metaData.items['com.google.android.geo.API_KEY'],\n" +
                        "  after(hookof('getPackageInfo'), other), after(hookof('getPackageInfo'), flags)");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).isnil()); // The cached original is not changed
        assertTrue(result.arg(5).toboolean());
        assertTrue(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        assertFalse(result.arg(8).toboolean());
    }

    // Facebook logs events and sends the advertising id with them unless the meta-data turns them off
    @Test
    public void facebookIsOffInMetaData() throws Exception {
        Globals globals = load("analytics_metadata");
        Scripts.run(globals, ANALYTICS);
        Varargs result = Scripts.run(globals,
                "classes['com.facebook.FacebookSdk'] = true\n" +
                        "local info = { metaData = bundleof() }\n" +
                        "info.metaData:putBoolean('com.facebook.sdk.AdvertiserIDCollectionEnabled', true)\n" +
                        "local param = paramof(info, { [0] = 'com.example', [1] = 128 }, nil, context)\n" +
                        "local restricted = after(hookof('getApplicationInfo'), param)\n" +
                        "local items = param.result.metaData.items\n" +
                        "return restricted, items['com.facebook.sdk.AutoLogAppEventsEnabled'],\n" +
                        "  items['com.facebook.sdk.AdvertiserIDCollectionEnabled']");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).isnil());
        assertFalse(result.arg(2).toboolean());
        assertFalse(result.arg(3).isnil());
        assertFalse(result.arg(3).toboolean());
    }
}
