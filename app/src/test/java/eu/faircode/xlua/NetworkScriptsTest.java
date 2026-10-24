/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

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
            "    getPackageName = function(self) return 'com.example' end,",
            "    getApplicationContext = function(self) return context end,",
            "    findClass = function(self, name) assert(classes[name], name) return name end,",
            "    putValue = function(self, name, value, scope)",
            "      values[scope] = values[scope] or {} values[scope][name] = value end,",
            "    getValue = function(self, name, scope) return values[scope] and values[scope][name] end }",
            "end",
            "classes = {}");

    private static Globals load(String script) throws Exception {
        return Scripts.load(script, STUBS);
    }

    private static Varargs run(Globals globals, String lua) {
        return Scripts.run(globals, lua);
    }

    private static String tostring(Globals globals, String className, String text) {
        globals.set("text", LuaValue.valueOf(text));
        Varargs result = run(globals,
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
        Varargs none = run(globals, "local param = paramof('{LinkAddresses: [ ] DnsAddresses: [ ] Domains: null" +
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
                "SSID: \"Home\", BSSID: 12:34:56:78:9a:bc, MAC: 02:00:00:00:00:00, IP: /192.168.1.5, Security type: 2");
        assertEquals("SSID: \"private\", BSSID: 02:00:00:00:00:00, MAC: 02:00:00:00:00:00, IP: /0.0.0.0, " +
                "Security type: 2", wifi);
    }

    @Test
    public void wifiGettersKeepHiddenValues() throws Exception {
        Globals globals = load("wifiinfo_ssid_bssid");
        Varargs result = run(globals,
                "local unknown = paramof('<unknown ssid>')\n" +
                        "local hidden = paramof('02:00:00:00:00:00')\n" +
                        "local ssid = paramof('\"Home\"')\n" +
                        "local bssid = paramof('12:34:56:78:9a:bc')\n" +
                        "return after(hookof('getSSID'), unknown), after(hookof('getBSSID'), hidden),\n" +
                        "  after(hookof('getSSID'), ssid), ssid.result, after(hookof('getBSSID'), bssid), bssid.result");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertEquals("\"private\"", result.arg(4).tojstring());
        assertTrue(result.arg(5).toboolean());
        assertEquals("02:00:00:00:00:00", result.arg(6).tojstring());
    }

    @Test
    public void privateDnsServerNameIsHidden() throws Exception {
        Globals globals = load("network_address");
        Varargs result = run(globals, "local param = paramof('abc.dns.nextdns.io')\n" +
                "return after(hookof('getPrivateDnsServerName'), param), param.result, param.set");
        assertTrue(result.arg(1).toboolean());
        assertTrue(result.arg(2).isnil());
        assertTrue(result.arg(3).toboolean());
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
        run(globals, NETWORKS);
        Varargs result = run(globals,
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
        Varargs fallback = run(globals,
                "local type = paramof(17, {}, vpninfo, { getSystemService = function() error('denied') end })\n" +
                        "return after(hookof('getType'), type), type.result");
        assertTrue(fallback.arg(1).toboolean());
        assertEquals(1, fallback.arg(2).toint());
    }

    // The fake value of a hook calls the same hook, which then sees the real value
    @Test
    public void vpnNestedCallIsReal() throws Exception {
        Globals globals = load("vpn");
        run(globals, NETWORKS);
        Varargs result = run(globals,
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
        run(globals, NETWORKS);
        globals.set("caps", LuaValue.valueOf("[ Transports: WIFI|VPN Capabilities: INTERNET&TRUSTED&VALIDATED" +
                " TransportInfo: <VpnTransportInfo{type=1, sessionId=null, bypassable=false" +
                " longLivedTcpConnectionsExpensive=false}> UnderlyingNetworks: null]"));
        globals.set("lp", LuaValue.valueOf("{InterfaceName: tun0 LinkAddresses: [ 10.8.0.2/32 ]" +
                " Routes: [ 0.0.0.0/0 -> 0.0.0.0 tun0 mtu 0 ]}"));
        Varargs result = run(globals,
                "local nc = paramof(caps, {}, nil, context)\n" +
                        "after(hookof('toString', 'android.net.NetworkCapabilities'), nc)\n" +
                        "local text = paramof(lp, {}, nil, context)\n" +
                        "after(hookof('toString', 'android.net.LinkProperties'), text)\n" +
                        "local tun = paramof({ getName = function() return 'tun0' end })\n" +
                        "return nc.result, text.result,\n" +
                        "  after(hookof('getByInetAddress'), tun), tun.result");
        assertEquals("[ Transports: WIFI Capabilities: NOT_VPN&INTERNET&TRUSTED&VALIDATED UnderlyingNetworks: null]",
                result.arg(1).tojstring());
        assertEquals("{InterfaceName: rmnet_data0 LinkAddresses: [ 10.8.0.2/32 ]" +
                " Routes: [ 0.0.0.0/0 -> 0.0.0.0 rmnet_data0 mtu 0 ]}", result.arg(2).tojstring());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).isnil());
    }

    // The transport info is a Java object, which the script checks with XParam, because Lua sees a Class
    // as its static members; the loader of Android 10 and 11 does not have VpnTransportInfo
    @Test
    public void vpnTransportInfoIsHidden() throws Exception {
        // VpnTransportInfo is not in the SDK of the unit tests: the check of XParam says it is one
        Varargs vpn = run(load("vpn"),
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
        Varargs result = run(globals, "return after(hookof('getTransportInfo'), param)");
        assertFalse(result.arg1().toboolean());
        return call;
    }

    // Stopping with the listener of a blocked call does nothing, because Android would throw
    @Test
    public void nsdStopsBlockedListenersOnly() throws Exception {
        Globals globals = load("nsdmanager_listener");
        Varargs result = run(globals,
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
        run(globals, RECORDER);
        Varargs result = run(globals,
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
        run(globals, RECORDER);
        Varargs result = run(globals,
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
        run(globals, RECORDER);
        Varargs result = run(globals,
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
        Varargs result = run(globals,
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
        Varargs failing = run(globals,
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
        run(globals, HANDLERS);
        Varargs result = run(globals,
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
        Varargs result = run(globals,
                "luajava = { newInstance = function(name, message)\n" +
                        "  return { getMessage = function() return message end } end }\n" +
                        "local info = paramof()\n" +
                        "local open = paramof()\n" +
                        "before(hookof('getCameraInfo'), info)\n" +
                        "before(hookof('open'), open)\n" +
                        "return info.result:getMessage(), open.result:getMessage()");
        assertEquals("Fail to get camera info", result.arg(1).tojstring());
        assertEquals("Fail to connect to camera service", result.arg(2).tojstring());
    }

    @Test
    public void transitionResultIsEmpty() throws Exception {
        Globals globals = load("activitytransitionresult_extractresult");
        Varargs result = run(globals,
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
        run(globals, ANALYTICS);
        Varargs result = run(globals,
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
}
