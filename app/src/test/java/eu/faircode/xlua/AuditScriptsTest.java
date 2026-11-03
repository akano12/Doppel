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

// The scripts of ways around the hooks which audits found: the kernel in /proc, speech recognition, the addresses
// of a VPN, blocked methods without a return type, the HDR luminance of the panel and the MMS user agent of the
// carrier configuration; Android classes are Lua stand-ins
public class AuditScriptsTest {
    private static final String STUBS = String.join("\n",
            "function hookof(method, types)",
            "  return { getMethodName = function(self) return method end,",
            "    getParameterTypes = function(self) return types end }",
            "end",
            "values = {}",
            "function paramof(result, args, this)",
            "  return { result = result, args = args or {}, set = false, path = nil, text = nil,",
            "    getResult = function(self) return self.result end,",
            "    setResult = function(self, value) self.result = value self.set = true end,",
            "    getArgument = function(self, index) return self.args[index] end,",
            "    getThis = function(self) return this end,",
            "    getException = function(self) return nil end,",
            "    isSystem = function(self) return false end,",
            "    unhooked = function(self, fn) unhooked = true local value = fn() unhooked = false return value end,",
            "    opens = function(self, file) return self.path == file end,",
            "    getFileText = function(self) return self.text end,",
            "    setFileText = function(self, text) self.text = text self.set = true end,",
            "    putValue = function(self, name, value, scope) values[name] = { scope = scope, value = value } end,",
            "    getValue = function(self, name, scope)",
            "      local entry = values[name]",
            "      return entry and entry.scope == scope and entry.value or nil end }",
            "end",
            "posted = {}",
            "luajava = {",
            "  newInstance = function(name, ...)",
            "    if name == 'java.lang.ref.WeakReference' then",
            "      return { referent = ..., get = function(self) return self.referent end }",
            "    end",
            "    if name == 'android.os.Handler' then",
            "      return { post = function(self, runnable) table.insert(posted, runnable) end }",
            "    end",
            "    return { class = name }",
            "  end,",
            "  createProxy = function(interface, methods) return methods end,",
            "  bindClass = function(name)",
            "    if name == 'android.os.Looper' then return { getMainLooper = function() return 'main' end } end",
            "    if name == 'java.lang.Thread' then",
            "      return { currentThread = function() return { getStackTrace = function() return FRAMES end } end }",
            "    end",
            "    return CLASSES[name]",
            "  end }",
            "FRAMES = { length = 0 }",
            "CLASSES = {}");

    private static Globals load(String script) throws Exception {
        return Scripts.load(script, STUBS);
    }

    // The kernel release and version of /proc like those of Os.uname and the shell, other files stay
    @Test
    public void kernelInProc() throws Exception {
        Globals globals = load("kernel_version");
        Varargs result = Scripts.run(globals,
                "local function read(path, text)\n" +
                        "  local param = paramof('fd')\n" +
                        "  param.path, param.text = path, text\n" +
                        "  local restricted = after(hookof('open'), param)\n" +
                        "  return tostring(restricted) .. ':' .. tostring(param.text)\n" +
                        "end\n" +
                        "return read('/proc/sys/kernel/osrelease', '6.6.50-android15-8-g8adecb593e9b\\n'),\n" +
                        "  read('/proc/sys/kernel/version', '#1 SMP PREEMPT Tue Oct 1 12:00:00 UTC 2024\\n'),\n" +
                        "  read('/proc/version', 'Linux version 6.6.50-android15-8-g8adecb593e9b (build@host) (clang) #1 SMP\\n'),\n" +
                        "  read('/proc/sys/kernel/osrelease', '6.6.50\\n'), read('/proc/self/version', 'x'), filter(nil)[1]");
        assertEquals("true:6.6.50\n", result.arg(1).tojstring());
        assertEquals("true:#1 SMP PREEMPT\n", result.arg(2).tojstring());
        assertEquals("true:Linux version 6.6.50 #1 SMP PREEMPT\n", result.arg(3).tojstring());
        // Faked already, and files of other paths
        assertEquals("false:6.6.50\n", result.arg(4).tojstring());
        assertEquals("false:x", result.arg(5).tojstring());
        assertEquals("*kernel/osrelease*", result.arg(6).tojstring());
    }

    // Listening does not start; the listener the app set gets ERROR_INSUFFICIENT_PERMISSIONS on the main thread
    @Test
    public void speechAnswersWithError() throws Exception {
        Globals globals = load("speech_recognizer");
        Varargs result = Scripts.run(globals,
                "local recognizer = {}\n" +
                        "local errors = {}\n" +
                        "local listener = { onError = function(self, code) table.insert(errors, code) end }\n" +
                        "local set = paramof(nil, { [0] = listener }, recognizer)\n" +
                        "local kept = before(hookof('setRecognitionListener'), set)\n" +
                        "local start = paramof(nil, { [0] = 'intent' }, recognizer)\n" +
                        "local restricted = before(hookof('startListening'), start)\n" +
                        "local count = #errors\n" +
                        "posted[1].run()\n" +
                        "local other = paramof(nil, { [0] = 'intent' }, {})\n" +
                        "before(hookof('startListening'), other)\n" +
                        // Kept weakly, not the listener itself; once collected nothing is posted
                        "local reference = values['speech.listener'].value\n" +
                        "local weak = reference ~= listener and reference:get() == listener\n" +
                        "reference.referent = nil\n" +
                        "before(hookof('startListening'), paramof(nil, { [0] = 'intent' }, recognizer))\n" +
                        "return kept, set.set, restricted, start.set, count, errors[1], #posted, other.set, weak");
        assertFalse(result.arg(1).toboolean());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        // Posted, not called during startListening, like Android
        assertEquals(0, result.arg(5).toint());
        assertEquals(9, result.arg(6).toint());
        // Without a listener nothing is posted, listening still does not start
        assertEquals(1, result.arg(7).toint());
        assertTrue(result.arg(8).toboolean());
        assertTrue(result.arg(9).toboolean());
    }

    // The addresses and DNS servers of a VPN are left out, by its real interface; other networks and the calls of
    // LinkProperties itself keep them
    @Test
    public void vpnAddresses() throws Exception {
        Globals globals = load("vpn_addresses");
        Varargs result = Scripts.run(globals,
                "local function list() return { isEmpty = function() return false end } end\n" +
                        "local function properties(name)\n" +
                        "  return { getInterfaceName = function() assert(unhooked, 'hooked') return name end }\n" +
                        "end\n" +
                        "local vpn = paramof(list(), {}, properties('tun0'))\n" +
                        "local wifi = paramof(list(), {}, properties('wlan0'))\n" +
                        "local restricted = after(hookof('getLinkAddresses'), vpn)\n" +
                        "local kept = after(hookof('getDnsServers'), wifi)\n" +
                        "FRAMES = { length = 1, { getClassName = function() return 'android.net.LinkProperties' end,\n" +
                        "  getMethodName = function() return 'equals' end } }\n" +
                        "local own = after(hookof('getLinkAddresses'), paramof(list(), {}, properties('tun0')))\n" +
                        "return restricted, vpn.result.class, kept, wifi.set, own");
        assertTrue(result.arg(1).toboolean());
        assertEquals("java.util.ArrayList", result.arg(2).tojstring());
        assertFalse(result.arg(3).toboolean());
        assertFalse(result.arg(4).toboolean());
        assertFalse(result.arg(5).toboolean());
    }

    // Without a return type: false for a boolean result, else nothing, which the type check of XParam decides
    @Test
    public void blockWithoutReturnType() throws Exception {
        Globals globals = load("generic_block_untyped");
        Varargs result = Scripts.run(globals,
                "local function checked(accepts)\n" +
                        "  local param = paramof()\n" +
                        "  param.setResult = function(self, value)\n" +
                        "    if value ~= nil and not accepts then error('Invalid result') end\n" +
                        "    self.result = value self.set = true\n" +
                        "  end\n" +
                        "  return param\n" +
                        "end\n" +
                        "local boolean = checked(true)\n" +
                        "local void = checked(false)\n" +
                        "return before(hookof('initialize'), boolean), boolean.result, before(hookof('initialize'), void),\n" +
                        "  void.set, void.result");
        assertTrue(result.arg(1).toboolean());
        assertFalse(result.arg(2).isnil());
        assertFalse(result.arg(2).toboolean());
        assertTrue(result.arg(3).toboolean());
        assertTrue(result.arg(4).toboolean());
        assertTrue(result.arg(5).isnil());
    }

    // The luminance of the panel becomes unknown, the HDR types stay; the product of the panel is not known
    @Test
    public void displayHdrAndProduct() throws Exception {
        Globals globals = load("display_panel");
        Varargs result = Scripts.run(globals,
                "local function hdrof(types, max, average, min)\n" +
                        "  return { types = types, getSupportedHdrTypes = function(self) return self.types end,\n" +
                        "    getDesiredMaxLuminance = function() return max end,\n" +
                        "    getDesiredMaxAverageLuminance = function() return average end,\n" +
                        "    getDesiredMinLuminance = function() return min end }\n" +
                        "end\n" +
                        "local written = {}\n" +
                        "local parcel = { writeInt = function(self, v) table.insert(written, v) end,\n" +
                        "  writeFloat = function(self, v) table.insert(written, v) end,\n" +
                        "  setDataPosition = function() end, recycled = false,\n" +
                        "  recycle = function(self) self.recycled = true end }\n" +
                        "CLASSES['android.os.Parcel'] = { obtain = function() return parcel end }\n" +
                        "CLASSES['android.view.Display$HdrCapabilities'] = { CREATOR = { createFromParcel = function(self, p)\n" +
                        "  local count = written[1]\n" +
                        "  local types = { length = count }\n" +
                        "  for i = 1, count do types[i] = written[1 + i] end\n" +
                        "  return hdrof(types, written[count + 2], written[count + 3], written[count + 4]) end } }\n" +
                        "local param = paramof(hdrof({ length = 2, 2, 3 }, 1000, 500, 0.0005))\n" +
                        "local restricted = after(hookof('getHdrCapabilities'), param)\n" +
                        "local fake = param.result\n" +
                        "local unknown = after(hookof('getHdrCapabilities'), paramof(hdrof({ length = 0 }, -1, -1, -1)))\n" +
                        "local product = paramof({})\n" +
                        "return restricted, fake.types.length, fake.types[2], fake:getDesiredMaxLuminance(),\n" +
                        "  fake:getDesiredMinLuminance(), parcel.recycled, unknown,\n" +
                        "  after(hookof('getDeviceProductInfo'), product), product.set, product.result,\n" +
                        "  after(hookof('getDeviceProductInfo'), paramof(nil))");
        assertTrue(result.arg(1).toboolean());
        assertEquals(2, result.arg(2).toint());
        assertEquals(3, result.arg(3).toint());
        assertEquals(-1, result.arg(4).toint());
        assertEquals(-1, result.arg(5).toint());
        assertTrue(result.arg(6).toboolean());
        assertFalse(result.arg(7).toboolean());
        assertTrue(result.arg(8).toboolean());
        assertTrue(result.arg(9).toboolean());
        assertTrue(result.arg(10).isnil());
        assertFalse(result.arg(11).toboolean());
    }

    // The configuration of CarrierConfigManager has the MMS user agent of the carrier too
    @Test
    public void carrierConfigMms() throws Exception {
        Globals globals = load("mms_useragent");
        for (String method : new String[]{"getConfig", "getConfigForSubId"}) {
            Varargs result = Scripts.run(globals,
                    "local values = { userAgent = 'SAMSUNG-SM-G991B', uaProfUrl = 'http://wap.samsungmobile.com/uaprof/SM-G991B.xml' }\n" +
                            "local bundle = { getString = function(self, key) return values[key] end,\n" +
                            "  putString = function(self, key, value) values[key] = value end }\n" +
                            "return after(hookof('" + method + "'), paramof(bundle)), values.userAgent, values.uaProfUrl");
            assertTrue(method, result.arg(1).toboolean());
            assertEquals(method, "Android-Mms/2.0", result.arg(2).tojstring());
            assertEquals(method, "http://www.google.com/oha/rdf/ua-profile-kila.xml", result.arg(3).tojstring());
        }
    }
}
