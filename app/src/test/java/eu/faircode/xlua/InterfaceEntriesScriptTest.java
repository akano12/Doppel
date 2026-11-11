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
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The entries of getifaddrs (interface_entries), which NetworkInterface reads, like the hooks of NetworkInterface:
// the addresses of the device (Read.Network), the interfaces of VPNs (Read.VPN) and the hardware addresses (MAC)
public class InterfaceEntriesScriptTest {
    // Like Libcore.os.getifaddrs of an emulator with a VPN, without the fields reflection does not find
    private static final String STUBS = String.join("\n",
            "function addressof(text, loopback)",
            "  return { text = text, isLoopbackAddress = function(self) return loopback end }",
            "end",
            // The class of the entries; a Java array has no methods in Lua, so the stand-in has none either
            "component = {}",
            "function entryof(name, address, hw)",
            "  return { ifa_name = name, ifa_addr = address, hwaddr = hw, getClass = function(self) return component end }",
            "end",
            "function arrayof(entries)",
            "  local array = { length = #entries }",
            "  for index, entry in ipairs(entries) do array[index] = entry end",
            "  return array",
            "end",
            "luajava = { bindClass = function(name)",
            "  assert(name == 'java.lang.reflect.Array', name)",
            "  return { newInstance = function(self, type, length) assert(type == component) return { length = length } end }",
            "end, new = function(type, name, flags, address, netmask, broadcast, hw)",
            "  assert(type == component)",
            "  local entry = entryof(name, address, hw)",
            "  entry.ifa_flags, entry.ifa_netmask, entry.ifa_broadaddr = flags, netmask, broadcast",
            "  return entry",
            "end }",
            "function entries()",
            "  return arrayof({",
            "    entryof('tun0', addressof('192.0.2.1', false)), entryof('wlan0', addressof('10.0.2.16', false)),",
            "    entryof('wlan0', addressof('fec0::d2bf', false)), entryof('lo', addressof('127.0.0.1', true)),",
            "    entryof('lo', addressof('::1', true)), entryof('tun0', nil), entryof('wlan0', nil, { length = 6 }),",
            "    entryof('dummy0', nil, { length = 6 }), entryof('lo', nil) })",
            "end",
            "function run(variant, result)",
            "  local param = { result = result or entries(),",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value end }",
            "  local hook = { getName = function(self) return 'ForwardingOs.getifaddrs/' .. variant end }",
            "  local restricted = after(hook, param)",
            "  local kept = {}",
            "  for index = 1, param.result['length'] do",
            "    local entry = param.result[index]",
            "    kept[#kept + 1] = entry.ifa_name .. '=' .. (entry.ifa_addr and entry.ifa_addr.text or (entry.hwaddr and 'hw' or '-'))",
            "  end",
            "  return restricted, table.concat(kept, ','), param.result",
            "end");

    private static Varargs run(String variant) throws Exception {
        return Scripts.run(Scripts.load("interface_entries", STUBS), "return run('" + variant + "')");
    }

    // The addresses of the device go, loopback stays, like NetworkInterface.getInetAddresses (see network_address)
    @Test
    public void addressesOfTheDeviceAreLeftOut() throws Exception {
        Varargs result = run("network");
        assertTrue(result.arg(1).toboolean());
        assertEquals("lo=127.0.0.1,lo=::1,tun0=-,wlan0=hw,dummy0=hw,lo=-", result.arg(2).tojstring());
    }

    // The interfaces of VPNs go, like NetworkInterface.getNetworkInterfaces (see vpn)
    @Test
    public void interfacesOfVpnsAreLeftOut() throws Exception {
        Varargs result = run("vpn");
        assertTrue(result.arg(1).toboolean());
        assertEquals("wlan0=10.0.2.16,wlan0=fec0::d2bf,lo=127.0.0.1,lo=::1,wlan0=hw,dummy0=hw,lo=-", result.arg(2).tojstring());
    }

    // The hardware addresses go, like NetworkInterface.getHardwareAddress (see mac_address), and their entries stay:
    // an interface without addresses (dummy0, or wlan0 with Wi-Fi off) has only this one and is still listed
    @Test
    public void hardwareAddressesAreLeftOut() throws Exception {
        Varargs result = run("mac");
        assertTrue(result.arg(1).toboolean());
        assertEquals("tun0=192.0.2.1,wlan0=10.0.2.16,wlan0=fec0::d2bf,lo=127.0.0.1,lo=::1,tun0=-,wlan0=-,dummy0=-,lo=-",
                result.arg(2).tojstring());

        // With Wi-Fi off, wlan0 keeps its name and flags (down), only its hardware address goes
        Varargs off = Scripts.run(Scripts.load("interface_entries", STUBS), String.join("\n",
                "local link = entryof('wlan0', nil, { length = 6 })",
                "link.ifa_flags = 4098",
                "local restricted, kept, entries = run('mac', arrayof({ entryof('lo', addressof('127.0.0.1', true)), link }))",
                "return restricted, kept, entries[2].ifa_flags"));
        assertTrue(off.arg(1).toboolean());
        assertEquals("lo=127.0.0.1,wlan0=-", off.arg(2).tojstring());
        assertEquals(4098, off.arg(3).toint());
    }

    // Nothing to leave out, and fields which reflection does not find (hidden APIs not allowed to the app): all stay
    @Test
    public void otherwiseAllStays() throws Exception {
        Globals globals = Scripts.load("interface_entries", STUBS);
        for (String variant : new String[]{"network", "vpn", "mac"}) {
            Varargs only = Scripts.run(globals, "return run('" + variant + "', arrayof({ entryof('lo', addressof('127.0.0.1', true)) }))");
            assertFalse(variant, only.arg(1).toboolean());
            Varargs hidden = Scripts.run(globals, "return run('" + variant + "', arrayof({ { ifa_name = 'lo', getClass = function(self) return component end } }))");
            assertFalse(variant, hidden.arg(1).toboolean());
        }
    }

    // The interfaces of VPNs are the same as those of vpn
    @Test
    public void vpnInterfacesAgreeWithVpn() throws Exception {
        Globals entries = Scripts.load("interface_entries", STUBS);
        Globals vpn = Scripts.load("vpn", "");
        for (String name : new String[]{"tun0", "ppp0", "tap1", "ipsec0", "wg0", "utun2", "l2tp0", "pptp0", "vpn0",
                "wlan0", "rmnet_data0", "eth0", "lo", "dummy0", "tunl0"})
            assertEquals(name, vpn.get("isvpninterface").call(LuaValue.valueOf(name)).toboolean(),
                    entries.get("isvpninterface").call(LuaValue.valueOf(name)).toboolean());
        // The tunnel of the kernel for IP in IP, which every device has, is no VPN
        assertFalse(vpn.get("isvpninterface").call(LuaValue.valueOf("tunl0")).toboolean());
        assertTrue(vpn.get("isvpninterface").call(LuaValue.valueOf("tun0")).toboolean());
    }
}
