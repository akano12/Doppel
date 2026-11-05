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
            "function hookof(method, class, group, types)",
            "  return { getMethodName = function(self) return method end,",
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

    @Test
    public void linkPropertiesTextHidesAddresses() throws Exception {
        Globals globals = load("network_tostring");
        // Android 10 and later, with a stacked link like of 464XLAT
        String text = "{InterfaceName: wlan0 LinkAddresses: [ fe80::1234/64,192.168.1.5/24 ]" +
                " DnsAddresses: [ /192.168.1.1,/fd00::1 ] UsePrivateDns: true PrivateDnsServerName: abc.dns.nextdns.io" +
                " ValidatedPrivateDnsAddresses: [45.90.28.0,45.90.30.0] Domains: corp.example home.lan MTU: 1500" +
                " ServerAddress: /192.168.1.1 TcpBufferSizes: 524288,1048576" +
                " Routes: [ fe80::/64 -> :: wlan0 mtu 0,0.0.0.0/0 -> 192.168.1.1 wlan0 mtu 0 ]" +
                " Stacked: [[ {InterfaceName: v4-wlan0 LinkAddresses: [ 192.0.0.4/32 ] DnsAddresses: [ ]" +
                " Domains: null MTU: 0 Routes: [ 0.0.0.0/0 -> 0.0.0.0 v4-wlan0 mtu 0 ]} ]]}";
        assertEquals("{InterfaceName: wlan0 LinkAddresses: [ ] DnsAddresses: [ ] UsePrivateDns: true" +
                        " Domains: null MTU: 1500 TcpBufferSizes: 524288,1048576 Routes: [ ]" +
                        " Stacked: [[ {InterfaceName: v4-wlan0 LinkAddresses: [ ] DnsAddresses: [ ]" +
                        " Domains: null MTU: 0 Routes: [ ]} ]]}",
                tostring(globals, "android.net.LinkProperties", text));

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
    }

    @Test
    public void wifiTexts() throws Exception {
        Globals globals = load("network_tostring");
        assertEquals("", tostring(globals, "android.net.wifi.p2p.WifiP2pDeviceList",
                "\nDevice: Anna's phone\n deviceAddress: 12:34:56:78:9a:bc"));
        // The access point like Android shows it without location permission, like the getter
        String wifi = tostring(globals, "android.net.wifi.WifiInfo",
                "SSID: \"Home\", BSSID: 12:34:56:78:9a:bc, MAC: 02:00:00:00:00:00, IP: /192.168.1.5, Security type: 2, " +
                        "Net ID: 3, Metered hint: false");
        assertEquals("SSID: \"private\", BSSID: 02:00:00:00:00:00, MAC: 02:00:00:00:00:00, IP: /0.0.0.0, " +
                "Security type: 2, Net ID: -1, Metered hint: false", wifi);
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
                        "    return after(hookof('getNetworkId'), id), id.result end)()");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertEquals("\"private\"", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertEquals("02:00:00:00:00:00", result.arg(6).tojstring());
        // The id of the saved network, -1 when not connected or hidden by Android
        assertFalse(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
        assertEquals(-1, result.arg(9).toint());
    }

    // The name as bytes is the same network as getSSID: a real name becomes the fake one, unknown and empty ones stay
    @Test
    public void wifiSsidAgreesWithGetSsid() throws Exception {
        Globals globals = load("wifiinfo_ssid_bssid");
        Varargs result = Scripts.run(globals,
                "local real = luajava\n" +
                        "luajava = setmetatable({ bindClass = function(name)\n" +
                        "  if name ~= 'android.net.wifi.WifiSsid' then return real.bindClass(name) end\n" +
                        "  return { fromBytes = function(self, bytes)\n" +
                        "    local text = '\"' .. tostring(real.newInstance('java.lang.String', bytes)) .. '\"'\n" +
                        "    return { toString = function() return text end } end } end }, { __index = real })\n" +
                        "local function ssidof(text) return { toString = function() return text end } end\n" +
                        "local home = paramof(ssidof('\"Home\"'))\n" +
                        "local ssid = paramof('\"Home\"')\n" +
                        "after(hookof('getSSID'), ssid)\n" +
                        "return after(hookof('getWifiSsid'), home), home.result:toString(), ssid.result,\n" +
                        "  after(hookof('getWifiSsid'), paramof(ssidof('<unknown ssid>'))),\n" +
                        "  after(hookof('getWifiSsid'), paramof(ssidof('')))");
        assertTrue(result.arg(1).toboolean());
        assertEquals(result.arg(3).tojstring(), result.arg(2).tojstring());
        assertFalse(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
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
                        "  bound('ioctlInetAddress', inet:getByName('255.255.255.0')),\n" +
                        "  bound('ioctlInetAddress', inet:getByName('127.0.0.1'))");
        assertEquals("true /0.0.0.0", result.arg(1).tojstring());
        assertEquals("true /0:0:0:0:0:0:0:0", result.arg(2).tojstring());
        assertEquals("true /0.0.0.0:4321", result.arg(3).tojstring());
        assertEquals("true /[0:0:0:0:0:0:0:0]:443", result.arg(4).tojstring());
        assertEquals("false /127.0.0.1", result.arg(5).tojstring());
        assertEquals("false /0.0.0.0", result.arg(6).tojstring());
        assertEquals("false /fe80:0:0:0:0:0:0:1", result.arg(7).tojstring());
        assertEquals("false /[0:0:0:0:0:0:0:1]:80", result.arg(8).tojstring());
        assertEquals("false unix", result.arg(9).tojstring());
        // The address, netmask or broadcast address of an interface by ioctl
        assertEquals("true /0.0.0.0", result.arg(10).tojstring());
        assertEquals("false /127.0.0.1", result.arg(11).tojstring());
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
            "context = { getSystemService = function(self, name) assert(name == 'connectivity') return cm end }");

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
                "local nc = paramof(caps, {}, nil, context)\n" +
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
        // The addresses of the tunnel are empty, like getLinkAddresses (see vpn_addresses)
        assertEquals("{InterfaceName: rmnet_data0 LinkAddresses: [ ]" +
                " Routes: [ 0.0.0.0/0 -> 0.0.0.0 rmnet_data0 mtu 0 ]}", result.arg(2).tojstring());
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
        assertEquals("{InterfaceName: wlan0 LinkAddresses: []  Routes: [0.0.0.0/0 -> 0.0.0.0 wlan0,]" +
                " DnsAddresses: [] Domains: null MTU: 0}", tostring(globals, "android.net.LinkProperties",
                "{InterfaceName: tun0 LinkAddresses: [10.8.0.2/32,]  Routes: [0.0.0.0/0 -> 0.0.0.0 tun0,]" +
                        " DnsAddresses: [10.8.0.1,] Domains: null MTU: 0}"));
        // Without an interface, like a new LinkProperties, there is nothing to hide
        String empty = "{LinkAddresses: [ ] DnsAddresses: [ ] Domains: null MTU: 0 Routes: [ ]}";
        assertEquals(empty, tostring(globals, "android.net.LinkProperties", empty));

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

    // A request for VPN networks, which Android matches itself, asks for LoWPAN networks instead; others stay. The request of the app does not show LoWPAN then, it hides it like VPN.
    @Test
    public void vpnRequestsFindNothing() throws Exception {
        Globals globals = load("vpn_request");
        Varargs result = Scripts.run(globals,
                "local function request(transport)\n" +
                        "  local param = { args = { [0] = transport },\n" +
                        "    getArgument = function(self, i) return self.args[i] end,\n" +
                        "    setArgument = function(self, i, v) self.args[i] = v end }\n" +
                        "  local restricted = before(hookof('addTransportType', 'android.net.NetworkRequest$Builder'), param)\n" +
                        "  return restricted, param.args[0]\n" +
                        "end\n" +
                        "local vpn, transport = request(4)\n" +
                        "local wifi, other = request(1)\n" +
                        "return vpn, transport, wifi, other");
        assertTrue(result.arg(1).toboolean());
        assertEquals(6, result.arg(2).toint());
        assertFalse(result.arg(3).toboolean());
        assertEquals(1, result.arg(4).toint());

        // The other hooks of Read.VPN do not run a before function for it
        Globals vpn = load("vpn");
        assertTrue(vpn.get("before").isnil());
        Scripts.run(vpn, NETWORKS);
        Varargs shown = Scripts.run(vpn,
                "local has = paramof(true, { [0] = 6 }, nil, context)\n" +
                        "after(hookof('hasTransport', 'android.net.NetworkCapabilities'), has)\n" +
                        "local text = paramof('[ Transports: LOWPAN Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED ]', {}, nil, context)\n" +
                        "after(hookof('toString', 'android.net.NetworkCapabilities'), text)\n" +
                        "return has.result, text.result");
        assertFalse(shown.arg(1).toboolean());
        assertFalse(shown.arg(2).tojstring(), shown.arg(2).tojstring().contains("LOWPAN"));
    }

    // NOT_VPN in its place in bit order, like a real network has it: after TRUSTED (14), before VALIDATED (16)
    @Test
    public void vpnNotVpnInBitOrder() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, Scripts.ARRAYOF + "\n" +
                "luajava = { bindClass = function(name)\n" +
                "  if name == 'java.lang.Integer' then return { TYPE = 'int' } end\n" +
                "  assert(name == 'java.lang.reflect.Array', name)\n" +
                "  return { newInstance = function(self, type, length) return { length = length } end }\n" +
                "end }");
        Varargs result = Scripts.run(globals,
                "local param = paramof(arrayof(12, 13, 14, 16, 18))\n" +
                        "after(hookof('getCapabilities'), param)\n" +
                        "local list = {}\n" +
                        "for index = 1, param.result.length do list[index] = param.result[index] end\n" +
                        "return table.concat(list, ',')");
        assertEquals("12,13,14,15,16,18", result.arg(1).tojstring());

        // Also without capabilities below or above it
        assertEquals("NOT_VPN&VALIDATED", tostring(globals, "android.net.NetworkCapabilities",
                "[ Transports: VPN Capabilities: VALIDATED UnderlyingNetworks: null]").replaceAll(".*Capabilities: (\\S*).*", "$1"));
        assertEquals("INTERNET&NOT_VPN", tostring(globals, "android.net.NetworkCapabilities",
                "[ Transports: VPN Capabilities: INTERNET UnderlyingNetworks: null]").replaceAll(".*Capabilities: (\\S*).*", "$1"));
    }

    // A VPN network lacks NOT_VPN, which it has here; other capabilities stay
    @Test
    public void vpnHasNotVpn() throws Exception {
        Varargs result = Scripts.run(load("vpn"),
                "local vpn = paramof(false, { [0] = 15 })\n" +
                        "local other = paramof(false, { [0] = 12 })\n" +
                        "local real = paramof(true, { [0] = 15 })\n" +
                        "return after(hookof('hasCapability'), vpn), vpn.result, after(hookof('hasCapability'), other),\n" +
                        "  other.set, after(hookof('hasCapability'), real), real.set");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
    }

    // The network info of the type VPN is not there, by its type or in the list of all networks; others stay
    @Test
    public void vpnNetworkInfoIsLeftOut() throws Exception {
        Globals globals = load("vpn");
        Scripts.run(globals, NETWORKS);
        Scripts.run(globals, Scripts.ARRAYOF + "\n" +
                "luajava = { bindClass = function(name)\n" +
                "  if name == 'android.net.NetworkInfo' then return 'NetworkInfo' end\n" +
                "  assert(name == 'java.lang.reflect.Array', name)\n" +
                "  return { newInstance = function(self, type, length) return { type = type, length = length } end }\n" +
                "end }");
        Varargs result = Scripts.run(globals,
                "local vpn = paramof(vpninfo, { [0] = 17 })\n" +
                        "local mobile = paramof(mobileinfo, { [0] = 0 })\n" +
                        "local all = paramof(arrayof(vpninfo, mobileinfo))\n" +
                        "local plain = paramof(arrayof(mobileinfo))\n" +
                        "return after(hookof('getNetworkInfo'), vpn), vpn.result, after(hookof('getNetworkInfo'), mobile),\n" +
                        "  mobile.set, after(hookof('getAllNetworkInfo'), all), all.result.type, all.result.length,\n" +
                        "  all.result[1] == mobileinfo, after(hookof('getAllNetworkInfo'), plain), plain.set");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertTrue(result.arg(5).toboolean());
        assertEquals("NetworkInfo", result.arg(6).tojstring());
        assertEquals(1, result.arg(7).toint());
        assertTrue(result.arg(8).toboolean());
        assertFalse(result.arg(9).toboolean());
        assertFalse(result.arg(10).toboolean());
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

    // Stopping with the listener of a blocked call does nothing, because Android would throw
    @Test
    public void nsdStopsBlockedListenersOnly() throws Exception {
        Globals globals = load("nsdmanager_listener");
        // The answers of blocked listeners need a looper, which app_process does not have, see HANDLERS
        Scripts.run(globals, HANDLERS);
        Varargs result = Scripts.run(globals,
                "local listener = {}\n" +
                        "local other = {}\n" +
                        "local discover = paramof(nil, { [0] = '_googlecast._tcp', [1] = 1, [2] = listener })\n" +
                        "local blocked = before(hookof('discoverServices', nil, nil, { length = 3 }), discover)\n" +
                        "local stop = paramof(nil, { [0] = listener })\n" +
                        "local stopped = before(hookof('stopServiceDiscovery'), stop)\n" +
                        "local again = paramof(nil, { [0] = listener })\n" +
                        "before(hookof('stopServiceDiscovery'), again)\n" +
                        "local real = paramof(nil, { [0] = other })\n" +
                        "before(hookof('stopServiceDiscovery'), real)\n" +
                        "return blocked, discover.set, stopped, stop.set, again.set, real.set");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertFalse(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
        assertFalse(result.arg(6).toboolean());
    }

    // The parameters of the first script keep the values of both, like XParam; the hooks of the groups run
    // in the given order, until one skips the method, like the backends
    private static final String RECORDER = String.join("\n",
            "function source(recorder, method, group, value)",
            "  before(hookof(method, nil, group), paramof(nil, { [0] = value }, recorder))",
            "end",
            "function call(recorder, method, groups)",
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
                        "  newInstance = function(name) return { isEmpty = function() return true end } end }\n" +
                        "local answer\n" +
                        "local executor = { execute = function(self, runnable) runnable:run() end }\n" +
                        "local callback = { onCellInfo = function(self, cells) answer = cells end }\n" +
                        "local param = paramof(nil, { [0] = executor, [1] = callback })\n" +
                        "return before(hookof('requestCellInfoUpdate'), param), param.set, answer:isEmpty()");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());

        // An app which fails on no cells, with a direct executor, does not let the real request through
        Varargs failing = Scripts.run(globals,
                "local executor = { execute = function(self, runnable) runnable:run() end }\n" +
                        "local callback = { onCellInfo = function(self, cells) error('no cells') end }\n" +
                        "local param = paramof(nil, { [0] = executor, [1] = callback })\n" +
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
                        "local frequency = paramof(nil, { [0] = 'channel', [1] = 2412, [2] = listener })\n" +
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

    @Test
    public void transitionResultIsEmpty() throws Exception {
        Globals globals = load("activitytransitionresult_extractresult");
        Varargs result = Scripts.run(globals,
                "classes['com.google.android.gms.location.ActivityTransitionResult'] = true\n" +
                        "luajava = { newInstance = function(name) assert(name == 'java.util.ArrayList') return {} end,\n" +
                        "  new = function(class, events) return { class = class, events = events } end }\n" +
                        "local param = paramof({})\n" +
                        "return after(hookof('extractResult'), param), param.result.class, #param.result.events");
        assertTrue(result.arg(1).toboolean());
        assertEquals("com.google.android.gms.location.ActivityTransitionResult", result.arg(2).tojstring());
        assertEquals(0, result.arg(3).toint());
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
