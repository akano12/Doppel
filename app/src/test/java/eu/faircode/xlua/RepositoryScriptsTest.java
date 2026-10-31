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

import org.json.JSONObject;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Scripts of definitions of Downloads (repo/) with Lua stand-ins, like the scripts of the app (see Scripts)
public class RepositoryScriptsTest {
    // The class a script finds records the static methods called, like a class of the app (see XParam.findClass)
    private static final String STUBS = String.join("\n",
            "calls = {}",
            "function hookof(className)",
            "  return { getClassName = function(self) return className end }",
            "end",
            "function paramof(exception)",
            "  return {",
            "    getException = function(self) return exception end,",
            "    findClass = function(self, name)",
            "      local class = {}",
            "      for _, method in ipairs({ 'setOptedOut', 'setPrivacyOptedOut' }) do",
            "        class[method] = function(self, value) table.insert(calls, name .. '.' .. method .. '=' .. tostring(value)) end",
            "      end",
            "      return class",
            "    end }",
            "end");

    private static Globals load(int id) throws Exception {
        return load(id, STUBS);
    }

    private static String script(int id) throws Exception {
        String json = new String(Files.readAllBytes(new File("../repo/hook_" + id + ".json").toPath()),
                StandardCharsets.UTF_8);
        return new JSONObject(json).getString("luaScript");
    }

    private static Globals load(int id, String stubs) throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        globals.load(script(id), "hook_" + id).call();
        globals.load(stubs, "stubs").call();
        return globals;
    }

    // Localytics opts out right after the app integrates it, of the package of the hooked class,
    // also of its builder and of the former package
    @Test
    public void localyticsOptsOut() throws Exception {
        String[][] cases = {
                {"169", "com.localytics.androidx.Localytics", "com.localytics.androidx.Localytics"},
                {"171", "com.localytics.androidx.Localytics$Builder", "com.localytics.androidx.Localytics"},
                {"81", "com.localytics.android.Localytics", "com.localytics.android.Localytics"}};
        for (String[] c : cases) {
            Globals globals = load(Integer.parseInt(c[0]));
            Varargs result = Scripts.run(globals,
                    "local restricted = after(hookof('" + c[1] + "'), paramof(nil))\n" +
                            "return restricted, table.concat(calls, ' ')");
            assertTrue(c[0], result.arg1().toboolean());
            assertEquals(c[0], c[2] + ".setPrivacyOptedOut=true " + c[2] + ".setOptedOut=true", result.arg(2).tojstring());
        }

        // Not when integrating failed
        Globals globals = load(169);
        Varargs failed = Scripts.run(globals,
                "return after(hookof('com.localytics.androidx.Localytics'), paramof('failed')), #calls");
        assertFalse(failed.arg1().toboolean());
        assertEquals(0, failed.arg(2).toint());
    }

    // The package hooks of Intika-Google-FCM: the app (uid 10100) and the packages the package manager knows.
    // The values of the scope are one map of the process, like XParam.putValue/getValue, shared by the globals
    // of each thread (store). The package manager calls during, which runs a call of another thread.
    private static final String FCM = String.join("\n",
            "PACKAGES = {",
            "  ['com.google.android.gms'] = { packageName = 'com.google.android.gms', applicationInfo = { uid = 10050 } },",
            "  ['com.example'] = { packageName = 'com.example', applicationInfo = { uid = 10200 } } }",
            "ipc = 0",
            "luajava = { bindClass = function(name) return name end, new = function(cls, text) return cls .. ': ' .. text end }",
            "function log() end",
            "function hookof(name) return { getName = function(self) return name end } end",
            "function paramof(args, result)",
            "  local pm = {}",
            "  function pm.getPackageInfo(self, name, flags) return pm.lookup(name) end",
            "  function pm.getApplicationInfo(self, name, flags) local p = pm.lookup(name) return p and p.applicationInfo end",
            "  function pm.lookup(name)",
            "    ipc = ipc + 1",
            "    if during then during() end",
            "    return PACKAGES[name]",
            "  end",
            "  return {",
            "    getResult = function(self) return result end,",
            "    setResult = function(self, value) self.result = value end,",
            "    getArgument = function(self, i) return args[i + 1] end,",
            "    getApplicationContext = function(self) return 'context' end,",
            "    getValue = function(self, name, scope) return store[name] end,",
            "    putValue = function(self, name, value, scope) store[name] = value end,",
            "    getUid = function(self) return 10100 end,",
            "    getThis = function(self) return pm end,",
            "    unhooked = function(self, f) return f() end }",
            "end");

    // [48] A lookup of gms by another thread while the hook of a thread looks up gms itself is hidden too:
    // values of the scope are of the whole process, not of the thread
    @Test
    public void fcmHidesDuringOwnLookup() throws Exception {
        String[][] cases = {
                {"150", "PackageManager.getPackageUid", "10050",
                        "147", "PackageManager.getPackageInfo/packageName", "PACKAGES['com.google.android.gms']"},
                {"140", "PackageManager.getApplicationEnabledSetting", "1",
                        "141", "PackageManager.getApplicationInfo", "PACKAGES['com.google.android.gms'].applicationInfo"}};
        for (String[] c : cases) {
            LuaTable store = new LuaTable();
            Globals a = load(Integer.parseInt(c[0]), FCM);
            Globals b = load(Integer.parseInt(c[3]), FCM);
            a.set("store", store);
            b.set("store", store);
            Scripts.run(b, "function other() restricted = after(hookof('" + c[4] + "'), " +
                    "paramof({ 'com.google.android.gms', 0 }, " + c[5] + ")) end");
            a.set("during", b.get("other"));
            Varargs result = Scripts.run(a,
                    "return after(hookof('" + c[1] + "'), paramof({ 'com.google.android.gms', 0 }, " + c[2] + "))");
            assertTrue(c[0], result.arg1().toboolean());
            assertTrue(c[3] + " during " + c[0] + " restricted=" + b.get("restricted"), b.get("restricted").toboolean());
        }
    }

    // [53] The hooks look up only gms and gsf themselves, not every package the app asks for
    @Test
    public void fcmLooksUpTargetsOnly() throws Exception {
        String[][] cases = {
                {"150", "PackageManager.getPackageUid", "10200"},
                {"145", "PackageManager.getPackageGids", "{}"},
                {"140", "PackageManager.getApplicationEnabledSetting", "1"},
                {"183", "PackageManager.getPackageUid.33", "10200"}};
        for (String[] c : cases) {
            Globals globals = load(Integer.parseInt(c[0]), FCM);
            globals.set("store", new LuaTable());
            Varargs result = Scripts.run(globals,
                    "return after(hookof('" + c[1] + "'), paramof({ 'com.example', 0 }, " + c[2] + ")), ipc");
            assertFalse(c[0], result.arg1().toboolean());
            assertEquals(c[0] + " lookups", 0, result.arg(2).toint());
        }
    }

    // [50] A blacklisted property is faked also when its value is the default the app gives,
    // else the app tests guesses of the value
    @Test
    public void propertyDefaultIsNoOracle() throws Exception {
        Globals globals = load(51, String.join("\n",
                "function log() end",
                "param = {",
                "  getResult = function(self) return 'orange' end,",
                "  setResult = function(self, value) self.result = value end,",
                "  getArgument = function(self, i) return ({ 'ro.boot.verifiedbootstate', 'orange' })[i + 1] end,",
                "  getApplicationContext = function(self) return 'context' end,",
                "  getValue = function(self) return nil end,",
                "  putValue = function(self) end,",
                "  getSetting = function(self) return 'ro.boot.' end }"));
        Varargs result = Scripts.run(globals, "return after({}, param), param.result");
        assertTrue("restricted", result.arg1().toboolean());
        assertEquals("Private", result.arg(2).tojstring());
    }

    // The definitions of get(key) and get(key, default) fake the same, so they have one script
    @Test
    public void propertyScriptsAreTheSame() throws Exception {
        assertEquals(script(51), script(52));
    }

    // The lists of apps and of services leave out gms and gsf only, read from the type of item of each method
    @Test
    public void fcmListsLeaveOutTargets() throws Exception {
        String stubs = Scripts.ARRAYOF + "\n" +
                "function hookof(name) return { getName = function(self) return name end } end\n" +
                "function listof(...)\n" +
                "  local items = arrayof(...)\n" +
                "  return { items = items, toArray = function(self) return arrayof(table.unpack(items)) end,\n" +
                "    remove = function(self, index) table.remove(items, index + 1) end }\n" +
                "end\n" +
                "function namesof(list, field)\n" +
                "  local names = {}\n" +
                "  for _, item in ipairs(list.items) do names[#names + 1] = field and item[field].packageName or item.packageName end\n" +
                "  return table.concat(names, ',')\n" +
                "end";
        String[][] cases = {
                {"143", "PackageManager.getInstalledApplications", null},
                {"180", "PackageManager.getInstalledPackages.33", null},
                {"184", "PackageManager.queryIntentServices.33", "serviceInfo"}};
        for (String[] c : cases) {
            String item = (c[2] == null ? "{ packageName = '%s' }" : "{ " + c[2] + " = { packageName = '%s' } }");
            Globals globals = load(Integer.parseInt(c[0]), stubs);
            Varargs result = Scripts.run(globals, "local list = listof(" +
                    String.format(item, "com.google.android.gms") + ", " + String.format(item, "com.example") + ", " +
                    String.format(item, "com.google.android.gsf") + ")\n" +
                    "local restricted = after(hookof('" + c[1] + "'), { getResult = function(self) return list end })\n" +
                    "local kept = listof(" + String.format(item, "com.example") + ")\n" +
                    "return restricted, namesof(list, " + (c[2] == null ? "nil" : "'" + c[2] + "'") + "),\n" +
                    "  after(hookof('" + c[1] + "'), { getResult = function(self) return kept end })");
            assertTrue(c[0], result.arg1().toboolean());
            assertEquals(c[0], "com.example", result.arg(2).tojstring());
            assertFalse(c[0], result.arg(3).toboolean());
        }
    }

    // Radar is blocked like the built-in definitions which block a method (generic_block), with the same body
    @Test
    public void radarIsGenericBlock() throws Exception {
        String radar = script(162).replace("\r\n", "\n");
        String block = Scripts.read("generic_block").replace("\r\n", "\n");
        assertEquals(block.substring(block.indexOf("\nfunction ")), radar.substring(radar.indexOf("\nfunction ")));
    }

    // [51] The MCC and MNC of the configuration are those of the test network which Use.Tracking fakes (test_network)
    @Test
    public void configurationHasTestNetwork() throws Exception {
        String network = Scripts.read("test_network");
        Matcher mcc = Pattern.compile("getMcc = (\\d+)").matcher(network);
        Matcher mnc = Pattern.compile("getMnc = (\\d+)").matcher(network);
        assertTrue(mcc.find() && mnc.find());

        Globals globals = load(161, "");
        Varargs result = Scripts.run(globals,
                "local c = { mcc = 310, mnc = 260 }\nreturn after({}, { getResult = function() return c end }), c.mcc, c.mnc");
        assertTrue(result.arg1().toboolean());
        assertEquals("mcc", Integer.parseInt(mcc.group(1)), result.arg(2).toint());
        assertEquals("mnc", Integer.parseInt(mnc.group(1)), result.arg(3).toint());

        // No SIM, which the test network leaves empty too
        Varargs none = Scripts.run(globals,
                "local c = { mcc = 0, mnc = 0 }\nreturn after({}, { getResult = function() return c end }), c.mcc");
        assertFalse(none.arg1().toboolean());
        assertEquals(0, none.arg(2).toint());
    }
}
