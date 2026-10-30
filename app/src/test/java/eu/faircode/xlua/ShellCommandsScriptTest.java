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

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The shell commands script (assets/shell_commands.lua) with Lua stand-ins for the hooks, the builder and
// the parameter, like LocationScriptTest. Its tables are copies of those of systemproperties_get, features and
// biometrics, which are compared here. The shell functions it makes run with sh and stand-ins for getprop, pm,
// cmd and uname in place of /system/bin; Android runs mksh and toybox, which XLUA_TEST_BIN can point to
// (a directory with sh, sed, grep, printf, cat and readlink), else the tools of this computer are used.
public class ShellCommandsScriptTest {
    private static final String[] ALL = {"tracking", "identifiers", "timezone", "packages", "features", "biometrics",
            "resources"};
    private static final String SETTINGS = "{ ['value.serial'] = 'SERIAL1', ['value.timezone'] = 'Asia/Tokyo'," +
            " ['features.keep'] = 'android.hardware.nfc, android.hardware.camera*' }";
    // The value of the properties compared, a date for the security patch level
    private static final String REAL = "2025-05-05";

    private static final String STUBS = String.join("\n",
            "function contextof(packages)",
            "  local manager = { getPackagesForUid = function(self, uid) return packages end }",
            "  return { getPackageManager = function(self) return manager end }",
            "end",
            // values are shared by the hooks of a command, like the scoped values of XParam
            "function paramof(settings, values, this, args)",
            "  local context = contextof({ length = 2, 'com.example', 'com.example.shared' })",
            "  return {",
            "    getSetting = function(self, name) return settings[name] end,",
            "    getUid = function(self) return 10123 end,",
            "    getPackageName = function(self) return 'com.example' end,",
            "    getApplicationContext = function(self) return context end,",
            "    readFile = function(self, path) return files[path] end,",
            "    getThis = function(self) return this end,",
            "    getArgument = function(self, index) return args[index + 1] end,",
            "    setArgument = function(self, index, value) args[index + 1] = value end,",
            "    getValue = function(self, name, scope) return values[scope] and values[scope][name] end,",
            "    putValue = function(self, name, value, scope)",
            "      if values[scope] == nil then values[scope] = {} end",
            "      values[scope][name] = value",
            "    end }",
            "end",
            "function hookof(name)",
            "  return { getName = function(self) return name end }",
            "end",
            "function nameof(method, variant)",
            "  return method .. (variant == 'tracking' and '' or '/' .. variant)",
            "end",
            // Java lists, maps and arrays as Lua sees them, which the test reads back (see words); luajava cannot
            // make the classes of java.lang accessible on recent Java versions, so System and String are stand-ins too
            "function listof(items)",
            "  local list = { items = {}, n = 0 }",
            "  list.add = function(self, value) self.n = self.n + 1; self.items[self.n] = value end",
            "  list.toArray = function(self) return arrayof(self.items, self.n) end",
            "  for _, item in ipairs(items or {}) do list:add(item) end",
            "  return list",
            "end",
            "function arrayof(items, n)",
            "  local array = { length = n or #items }",
            "  for index = 1, array.length do array[index] = items[index] end",
            "  return array",
            "end",
            "function mapof(entries)",
            "  return { entries = entries,",
            "    get = function(self, key) return self.entries[key] end,",
            "    put = function(self, key, value) self.entries[key] = value end,",
            "    remove = function(self, key) self.entries[key] = nil end }",
            "end",
            // The files as the app reads them, with the hooks opening files, see XParam.readFile
            "files = { ['/proc/meminfo'] = 'MemTotal:        3145728 kB\\nMemFree:          146484 kB\\n',",
            "  ['/proc/sys/kernel/random/boot_id'] = '0c9e2b41-5f7d-4a8e-9b1c-3d6f8a2e4b7c\\n',",
            "  ['/proc/uptime'] = '123456.78 98765.43\\n',",
            "  ['/proc/stat'] = 'cpu  1 2 3\\nbtime 1699998200\\nprocesses 100\\n' }",
            "luajava = {",
            "  newInstance = function(name, path)",
            // Files resolve their path on the file system of the test, see canonical
            "    if name == 'java.io.File' then",
            "      return { getCanonicalPath = function(self) return canonical(path) end }",
            "    end",
            "    assert(name == 'java.util.ArrayList', name)",
            "    return listof()",
            "  end }",
            // The hooks of the variants for a command like ProcessBuilder.start: the command started,
            // its time zone, the command and time zone of the builder afterwards and whether a hook restricted
            "function startof(words, variants, settings)",
            "  local values = {}",
            "  local builder = { list = listof(words), env = mapof({}),",
            "    command = function(self, value) if value == nil then return self.list end self.list = value end,",
            "    environment = function(self) return self.env end }",
            "  local restricted = false",
            "  for _, variant in ipairs(variants) do",
            "    local hook = hookof(nameof('ProcessBuilder.start', variant))",
            "    restricted = before(hook, paramof(settings, values, builder, {})) or restricted",
            "  end",
            "  local started = builder.list",
            "  local zone = builder.env:get('TZ')",
            "  for _, variant in ipairs(variants) do",
            "    local hook = hookof(nameof('ProcessBuilder.start', variant))",
            "    after(hook, paramof(settings, values, builder, {}))",
            "  end",
            "  return started, zone, builder.list, builder.env:get('TZ'), restricted",
            "end",
            // The fake value of the rules of getprop for a property, 'quarter' for the security patch level
            "function fakeof(key, settings, real)",
            "  local param = paramof(settings, {}, nil, {})",
            "  for _, variant in ipairs({ 'tracking', 'identifiers', 'timezone' }) do",
            "    for _, rule in ipairs(rules(param, variant)) do",
            "      for _, pattern in ipairs(rule[2]) do",
            "        local lua = string.gsub(pattern, '[%.%*%-]', { ['.'] = '%.', ['*'] = '.+', ['-'] = '%-' })",
            "        if string.match(key, '^' .. lua .. '$') then",
            "          return type(rule[1]) == 'table' and 'quarter' or rule[1]",
            "        end",
            "      end",
            "    end",
            "  end",
            "  return real",
            "end",
            "function patternsof(settings)",
            "  local result = {}",
            "  local param = paramof(settings, {}, nil, {})",
            "  for _, variant in ipairs({ 'tracking', 'identifiers', 'timezone' }) do",
            "    for _, rule in ipairs(rules(param, variant)) do",
            "      for _, pattern in ipairs(rule[2]) do result[#result + 1] = pattern end",
            "    end",
            "  end",
            "  return result",
            "end");

    // Stand-ins for the hooks of the scripts the tables are copied from
    private static final String PROPERTY_STUBS = String.join("\n",
            "function propertyof(name, key, result, settings)",
            "  local fake",
            "  local hook = { getName = function(self) return name end,",
            "    getMethodName = function(self) return 'get' end }",
            "  local param = { getArgument = function(self, index) return index == 0 and key or nil end,",
            "    getResult = function(self) return result end,",
            "    setResult = function(self, value) fake = value end,",
            "    getSetting = function(self, name) return settings[name] end }",
            "  return after(hook, param), fake",
            "end");
    private static final String FEATURE_STUBS = String.join("\n",
            "function featureof(name, settings)",
            "  local hook = { getName = function(self) return 'PackageManager.hasSystemFeature' end,",
            "    getMethodName = function(self) return 'hasSystemFeature' end }",
            "  local param = { getArgument = function(self, index) return name end,",
            "    getResult = function(self) return true end,",
            "    setResult = function(self, value) end,",
            "    getSetting = function(self, name) return settings[name] end }",
            "  return after(hook, param)",
            "end",
            // The classes on the stack (callers of features), a stand-in like in DeviceValuesScriptTest: those of
            // the app, like for the commands it runs; luajava cannot make java.lang.Thread accessible on recent Java
            "function callers() return { length = 0 } end");

    private static File bin;
    private Globals globals;
    private LuaValue settings;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("shell_commands", STUBS);
        globals.set("canonical", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue path) {
                try {
                    return LuaValue.valueOf(new File(path.tojstring()).getCanonicalPath());
                } catch (IOException ex) {
                    return path;
                }
            }
        });
        settings = globals.load("return " + SETTINGS, "settings").call();
    }

    // The fake value of SystemProperties.get for a property of value REAL, the real value when it is not faked;
    // the variants of the hooks which have a shell variant
    private String systemProperty(Globals properties, String key) {
        for (String variant : new String[]{"device", "serial", "operator", "timezone"}) {
            Varargs result = properties.get("propertyof").invoke(LuaValue.varargsOf(new LuaValue[]{
                    LuaValue.valueOf("SystemProperties.get/" + variant), LuaValue.valueOf(key),
                    LuaValue.valueOf(REAL), settings}));
            if (result.arg(1).toboolean())
                return result.arg(2).tojstring();
        }
        return REAL;
    }

    // The properties which either script names, with a * as a partition
    private Set<String> propertyNames() throws Exception {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'((?:ro|gsm|ril|sys|persist)\\.[A-Za-z0-9_.*-]+)'")
                .matcher(Scripts.read("systemproperties_get"));
        while (m.find())
            names.add(m.group(1).replace("*", "vendor"));
        LuaValue patterns = globals.get("patternsof").call(settings);
        for (int i = 1; i <= patterns.length(); i++)
            names.add(patterns.get(i).tojstring().replace("*", "vendor"));
        return names;
    }

    @Test
    public void propertiesAreTheSameAsSystemProperties() throws Exception {
        Globals properties = Scripts.load("systemproperties_get", PROPERTY_STUBS);
        String quarter = properties.get("quarter").call(LuaValue.valueOf(REAL)).tojstring();
        for (String key : propertyNames()) {
            String fake = globals.get("fakeof").call(LuaValue.valueOf(key), settings, LuaValue.valueOf(REAL))
                    .tojstring();
            assertEquals(key, systemProperty(properties, key), "quarter".equals(fake) ? quarter : fake);
        }
    }

    // getprop lists the same values as SystemProperties.get finds; empty values stay
    @Test
    public void getpropListsTheValuesOfSystemProperties() throws Exception {
        Globals properties = Scripts.load("systemproperties_get", PROPERTY_STUBS);
        StringBuilder listing = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        for (String key : propertyNames()) {
            listing.append('[').append(key).append("]: [").append(REAL).append("]\n");
            expected.append('[').append(key).append("]: [").append(systemProperty(properties, key)).append("]\n");
        }
        listing.append("[ro.product.model]: []\n[ro.sf.lcd_density]: [420]\n");
        expected.append("[ro.product.model]: []\n[ro.sf.lcd_density]: [420]\n");
        tools().properties(listing.toString());
        assertEquals(expected.toString(), run(start("getprop")).out);
    }

    @Test
    public void getpropPrintsFakeValues() throws Exception {
        tools().properties("[ro.product.model]: [Pixel 9]\n[ro.vendor.build.security_patch]: [2025-11-01]\n" +
                "[ro.serialno]: [ABC123]\n[persist.sys.timezone]: [Europe/Amsterdam]\n" +
                "[gsm.operator.alpha]: [T-Mobile]\n[ro.boot.serialno]: []\n[ro.sf.lcd_density]: [420]\n");
        assertEquals("unknown\n", run(start("getprop", "ro.product.model")).out);
        assertEquals("2025-10-01\n", run(start("getprop", "ro.vendor.build.security_patch")).out);
        assertEquals("SERIAL1\n", run(start("getprop", "ro.serialno")).out);
        assertEquals("Asia/Tokyo\n", run(start("getprop", "persist.sys.timezone", "UTC")).out);
        assertEquals("unknown\n", run(start("getprop", "gsm.operator.alpha")).out);
        // Not set: the default, like getprop
        assertEquals("none\n", run(start("getprop", "ro.boot.serialno", "none")).out);
        assertEquals("420\n", run(start("getprop", "ro.sf.lcd_density")).out);
        assertEquals("[ro.product.model]: [unknown]\n[ro.serialno]: [SERIAL1]\n[ro.boot.serialno]: []\n",
                run(start("sh", "-c", "getprop | grep -e product.model -e serialno")).out);
    }

    // USB debugging off, like the properties of Read.Debugging (see systemproperties_get)
    @Test
    public void debuggingIsOff() throws Exception {
        tools().properties("[init.svc.adbd]: [running]\n[persist.sys.usb.config]: [adb]\n" +
                "[sys.usb.config]: [mtp,adb]\n[sys.usb.state]: [adb,mtp]\n[ro.product.model]: [Pixel 9]\n");
        assertEquals("[init.svc.adbd]: [stopped]\n[persist.sys.usb.config]: [none]\n[sys.usb.config]: [mtp]\n" +
                        "[sys.usb.state]: [mtp]\n[ro.product.model]: [Pixel 9]\n",
                run(start(new String[]{"getprop"}, "debugging")).out);
        assertEquals("stopped\n", run(start(new String[]{"getprop", "init.svc.adbd"}, "debugging")).out);
        assertEquals("mtp\n", run(start(new String[]{"getprop", "sys.usb.config"}, "debugging")).out);
    }

    // The serial is a value of Read.Identifiers, like Build.SERIAL, and not of Use.Tracking
    @Test
    public void identifiersFakeTheSerial() throws Exception {
        tools().properties("[ro.product.model]: [Pixel 9]\n[ro.serialno]: [ABC123]\n");
        assertEquals("[ro.product.model]: [Pixel 9]\n[ro.serialno]: [SERIAL1]\n",
                run(start(new String[]{"getprop"}, "identifiers")).out);
        assertEquals("[ro.product.model]: [unknown]\n[ro.serialno]: [ABC123]\n",
                run(start(new String[]{"getprop"}, "tracking")).out);
        assertEquals("SERIAL1\n", run(start(new String[]{"getprop", "ro.serialno"}, "timezone", "identifiers")).out);
    }

    @Test
    public void packageListsShowOwnPackages() throws Exception {
        tools();
        String own = "package:com.example\npackage:com.example.shared\n";
        assertEquals(own, run(start("pm", "list", "packages")).out);
        assertEquals(own, run(start("pm", "list", "package")).out);
        assertEquals(own, run(start("pm", "-l")).out);
        assertEquals(own, run(start("cmd", "package", "list", "packages", "-3")).out);
        assertEquals(own, run(start("cmd", "-w", "package", "list", "packages")).out);
        assertEquals("package:/data/app/~~c==/com.example-d==/base.apk=com.example\n",
                run(start("pm", "-lf")).out);
        assertEquals("instrumentation:com.example/androidx.test.runner.AndroidJUnitRunner (target=com.example)\n",
                run(start("cmd", "package", "list", "instrumentation")).out);
        // Other commands stay
        assertEquals("permission:com.bank.permission.C2D_MESSAGE\n", run(start("pm", "list", "permissions")).out);
        assertEquals("cmd activity get-config\n", run(start("cmd", "activity", "get-config")).out);
    }

    // Like package_probe: own and system packages stay, also with the options of path
    @Test
    public void pathFindsVisiblePackages() throws Exception {
        tools();
        for (String[] args : new String[][]{
                {"pm", "path", "com.bank"}, {"pm", "path", "--user", "0", "com.bank"}, {"pm", "path", "--", "com.bank"},
                {"pm", "path", "com.bank", "x"}, {"pm", "path", "-ucom.bank"}, {"pm", "-p", "com.bank"},
                {"cmd", "package", "path", "--user", "current", "com.bank"}}) {
            Result result = run(start(args));
            assertEquals(String.join(" ", args), "", result.out);
            assertEquals(String.join(" ", args), 1, result.status);
        }
        assertEquals("package:/data/app/com.example/base.apk\n",
                run(start("pm", "path", "--user", "0", "com.example")).out);
        assertEquals("package:/data/app/com.android.settings/base.apk\n",
                run(start("pm", "path", "com.android.settings")).out);
    }

    // Like the queries of PackageManager, which find nothing of other apps
    @Test
    public void queriesFindNothingOfOtherApps() throws Exception {
        tools();
        assertEquals("No activities found\n",
                run(start("cmd", "package", "query-activities", "-a", "android.intent.action.MAIN")).out);
        assertEquals("No activities found\n",
                run(start("pm", "query-activities", "--brief", "-a", "android.intent.action.MAIN")).out);
        assertEquals("No services found\n", run(start("cmd", "package", "query-services", "-a", "x")).out);
        assertEquals("No receivers found\n", run(start("cmd", "package", "query-receivers", "-a", "x")).out);
        String own = run(start(new String[]{"cmd", "package", "query-activities", "-p", "com.example"})).out;
        assertTrue(own, own.startsWith("1 activities found:\n") && own.contains("packageName=com.example\n"));
        // resolve-activity like resolveActivity, which finds system apps too
        assertEquals("No activity found\n",
                run(start("cmd", "package", "resolve-activity", "-a", "android.intent.action.VIEW")).out);
        String system = run(start("cmd", "package", "resolve-activity", "-a", "android.intent.action.MAIN")).out;
        assertTrue(system, system.contains("packageName=com.android.settings\n"));

        // Dumps of any length, which the shell writes itself
        assertTrue(dump(LONG_OWN).length() > 128 * 1024);
        Result found = run(start("cmd", "package", "query-activities", "-a", "com.example.LONG"));
        assertEquals(dump(LONG_OWN), found.out);
        assertEquals(0, found.status);
        assertEquals("No activities found\n",
                run(start("cmd", "package", "query-activities", "-a", "com.bank.LONG")).out);
    }

    // The features of features and biometrics, for each and both variants
    @Test
    public void featuresAreTheSameAsHasSystemFeature() throws Exception {
        Globals features = Scripts.load("features", FEATURE_STUBS);
        Globals biometrics = Scripts.load("biometrics", FEATURE_STUBS);
        Set<String> names = new LinkedHashSet<>(Arrays.asList("android.hardware.wifi", "android.hardware.nfc",
                "android.hardware.nfc.hce", "android.hardware.camera", "android.hardware.camera.front",
                "android.software.webview", "android.hardware.vulkan.level",
                "com.google.android.feature.PIXEL_EXPERIENCE", "com.samsung.feature.samsung_experience_mobile"));
        for (String script : new String[]{"features", "biometrics"}) {
            Matcher m = Pattern.compile("'(android\\.(?:hardware|software)\\.[A-Za-z0-9_.]+)'")
                    .matcher(Scripts.read(script));
            while (m.find())
                names.add(m.group(1));
        }

        StringBuilder listing = new StringBuilder();
        for (String name : names)
            listing.append("feature:").append(name).append(name.endsWith(".level") ? "=1\n" : "\n");
        listing.append("feature:reqGlEsVersion=0x30000\n");
        tools().features(listing.toString());

        for (String[] variants : new String[][]{{"features"}, {"biometrics"}, {"features", "biometrics"}}) {
            StringBuilder expected = new StringBuilder();
            for (String name : names) {
                boolean hidden = false;
                for (String variant : variants) {
                    Globals other = ("features".equals(variant) ? features : biometrics);
                    hidden = hidden || other.get("featureof").call(LuaValue.valueOf(name), settings).toboolean();
                }
                if (!hidden)
                    expected.append("feature:").append(name).append(name.endsWith(".level") ? "=1\n" : "\n");

                Result has = run(start(new String[]{"pm", "has-feature", name}, variants));
                assertEquals(name, hidden ? "false\n" : "true\n", has.out);
                assertEquals(name, hidden ? 1 : 0, has.status);
            }
            expected.append("feature:reqGlEsVersion=0x30000\n");
            assertEquals(Arrays.toString(variants), expected.toString(),
                    run(start(new String[]{"cmd", "package", "list", "features"}, variants)).out);
        }
    }

    // The libraries of getSystemSharedLibraryNames (see features)
    @Test
    public void librariesAreTheSameAsSharedLibraries() throws Exception {
        Globals features = Scripts.load("features", FEATURE_STUBS);
        Set<String> names = new LinkedHashSet<>(Arrays.asList("android.test.base", "com.google.android.dialer.support",
                "com.qualcomm.qti.audio", "com.google.android.mapsx", "com.google.android.maps"));
        Matcher m = Pattern.compile("'([a-z]+(?:\\.[A-Za-z0-9_]+)*\\.?)'")
                .matcher(Scripts.read("features"));
        while (m.find())
            if (m.group(1).contains("."))
                names.add(m.group(1).endsWith(".") ? m.group(1) + "library" : m.group(1));

        StringBuilder listing = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        int index = 0;
        for (String name : names) {
            String path = (index++ % 2 == 0 ? "" : " path:/system/framework/" + name + ".jar");
            String line = "library:" + name + path + "\n";
            listing.append(line);
            if (!features.get("vendor").call(LuaValue.valueOf(name)).toboolean())
                expected.append(line);
        }
        tools().libraries(listing.toString());
        assertEquals(expected.toString(), run(start(new String[]{"pm", "list", "libraries"}, "features")).out);
        // Read.Biometrics has no libraries
        assertEquals(listing.toString(), run(start(new String[]{"pm", "list", "libraries"}, "biometrics")).out);
    }

    @Test
    public void kernelIsFaked() throws Exception {
        tools();
        assertEquals("Linux localhost 6.6.50 #1 SMP PREEMPT aarch64 Toybox\n", run(start("uname", "-a")).out);
        assertEquals("6.6.50\n", run(start("/system/bin/uname", "-r")).out);
        assertEquals("Linux version 6.6.50 #1 SMP PREEMPT\n", run(start("cat", "/proc/version")).out);
        // Other files stay
        assertEquals("cat /proc/cpuinfo", String.join(" ", command(start("cat", "/proc/cpuinfo"))));
    }

    // cat prints the files like the app reads them, of the variants restricting them only
    @Test
    public void filesAreFaked() throws Exception {
        tools();
        assertEquals("MemTotal:        3145728 kB\nMemFree:          146484 kB\n",
                run(start("cat", "/proc/meminfo")).out);
        assertEquals("0c9e2b41-5f7d-4a8e-9b1c-3d6f8a2e4b7c\n",
                run(start("cat", "/proc/sys/kernel/random/boot_id")).out);
        assertEquals("0c9e2b41-5f7d-4a8e-9b1c-3d6f8a2e4b7c\n",
                run(start("sh", "-c", "x=$(cat /proc/sys/kernel/random/boot_id); echo $x")).out);
        // The boot time of /proc/uptime and /proc/stat, faked by Use.Tracking like boot_id (see boot_offset_proc)
        assertEquals("123456.78 98765.43\n", run(start("cat", "/proc/uptime")).out);
        assertEquals("123456.78 98765.43\n", run(start("cat", "/proc/self/root/proc/uptime")).out);
        assertEquals("cpu  1 2 3\nbtime 1699998200\nprocesses 100\n", run(start("cat", "/proc/stat")).out);
        // Read.Resources does not restrict boot_id, /proc/uptime or /proc/stat, Use.Tracking not /proc/meminfo
        assertEquals("cat /proc/sys/kernel/random/boot_id",
                String.join(" ", command(start(new String[]{"cat", "/proc/sys/kernel/random/boot_id"}, "resources"))));
        assertEquals("cat /proc/uptime",
                String.join(" ", command(start(new String[]{"cat", "/proc/uptime"}, "resources"))));
        assertEquals("cat /proc/stat",
                String.join(" ", command(start(new String[]{"cat", "/proc/stat"}, "resources"))));
        assertEquals("cat /proc/meminfo",
                String.join(" ", command(start(new String[]{"cat", "/proc/meminfo"}, "tracking"))));
        // Also by other paths, and with other files, which are read by cat itself
        String fake = "MemTotal:        3145728 kB\nMemFree:          146484 kB\n";
        assertEquals(fake, run(start("cat", "/proc/self/root/proc/meminfo")).out);
        assertEquals(fake, run(start("sh", "-c", "cat //proc/meminfo")).out);
        assertEquals("0c9e2b41-5f7d-4a8e-9b1c-3d6f8a2e4b7c\n",
                run(start("cat", "/proc/self/root/proc/sys/kernel/random/boot_id")).out);
        File other = File.createTempFile("file", ".txt");
        try {
            Files.write(other.toPath(), "other\n".getBytes(StandardCharsets.UTF_8));
            assertEquals(fake + "other\n", run(start("cat", "/proc/meminfo", other.getAbsolutePath())).out);
            assertEquals("other\n",
                    run(start("sh", "-c", "cat /proc/meminfo >/dev/null; cat " + other.getAbsolutePath())).out);
            assertEquals("cat /proc/meminfo " + other.getAbsolutePath(), String.join(" ",
                    command(start(new String[]{"cat", "/proc/meminfo", other.getAbsolutePath()}, "tracking"))));
        } finally {
            other.delete();
        }
        // Other commands reading the files, and cat with options, read the same text
        assertEquals(fake, run(start("sh", "-c", "cat -v /proc/meminfo | head -2")).out);
        assertEquals("MemTotal:        3145728 kB\n", run(start("grep", "MemTotal", "/proc/meminfo")).out);
        assertEquals("0c9e2b41-5f7d-4a8e-9b1c-3d6f8a2e4b7c\n",
                run(start("head", "-1", "/proc/sys/kernel/random/boot_id")).out);
        assertEquals("0c9e2b41-5f7d-4a8e-9b1c-3d6f8a2e4b7c\n",
                run(start("cat", "--", "/proc/sys/kernel/random/boot_id")).out);
        assertEquals("123456.78 98765.43\n", run(start("sh", "-c", "dd if=/proc/uptime 2>/dev/null")).out);
    }

    // cat of the files of /sys which do not exist for the variants fails like for a missing file, also through
    // a link, the other files are read; the variants hide the files of their groups only
    @Test
    public void hardwareFilesAreMissing() throws Exception {
        tools();
        File root = Files.createTempDirectory("xlua-sys").toFile();
        try {
            File device = new File(root, "sys/devices/mmc0:0001");
            assertTrue(device.mkdirs());
            Files.write(new File(device, "cid").toPath(), "1501004d4b4e3237a0f2c3d4e5f6b700\n".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(device, "date").toPath(), "03/2024\n".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(device, "type").toPath(), "SD\n".getBytes(StandardCharsets.UTF_8));
            File block = new File(root, "sys/block/mmcblk0");
            assertTrue(block.mkdirs());
            Files.createSymbolicLink(new File(block, "device").toPath(), device.toPath());
            String cid = new File(block, "device/cid").getAbsolutePath();
            String date = new File(block, "device/date").getAbsolutePath();
            String type = new File(block, "device/type").getAbsolutePath();

            Result result = run(start("cat", cid));
            assertEquals("", result.out);
            assertEquals(1, result.status);
            assertEquals("SD\n", run(start("cat", type)).out);
            // Several files: the hidden one fails, the others are read
            assertEquals("SD\n1\n", run(start("sh", "-c", "cat " + cid + " " + type + " 2>/dev/null; echo $?")).out);
            // With pm in the same script, which has functions of its own for the features
            assertEquals("SD\n", run(start("sh", "-c", "pm list features >/dev/null; cat " + cid + " " + type +
                    " 2>/dev/null")).out);
            assertEquals("03/2024\n", run(start(new String[]{"cat", date}, "identifiers")).out);
            assertEquals("", run(start(new String[]{"cat", date}, "tracking")).out);
            assertEquals("1501004d4b4e3237a0f2c3d4e5f6b700\n", run(start(new String[]{"cat", cid}, "tracking")).out);
            // Read.Resources does not hide the files
            assertEquals("cat " + cid, String.join(" ", command(start(new String[]{"cat", cid}, "resources"))));

            // Several files and options, also for commands run directly, and other commands reading files
            assertEquals("SD\n", run(start("cat", cid, type)).out);
            Result options = run(start("cat", "-v", cid));
            assertEquals("", options.out);
            assertEquals(1, options.status);
            assertEquals("", run(start("head", "-c", "4", cid)).out);
            String two = run(start("head", "-c", "2", cid, type)).out;
            assertTrue(two, two.contains("SD") && !two.contains("1501"));
            assertFalse(run(start("dd", "if=" + cid)).out.contains("1501"));
            assertEquals("", run(start("sh", "-c", "head -c 4 " + cid)).out);
            // The other files of /sys are read as they are: the commands stay
            assertEquals("cat " + type, String.join(" ", command(start("cat", type))));
            assertEquals("head -c 2 " + type, String.join(" ", command(start("head", "-c", "2", type))));
            assertFalse(script(start("sh", "-c", "head -c 2 " + type)).contains("__xlua_files"));
            assertEquals("SD\n", run(start("head", "-n", "1", type)).out);
        } finally {
            delete(root);
        }
    }

    private static void delete(File file) {
        File[] files = (Files.isSymbolicLink(file.toPath()) ? null : file.listFiles());
        if (files != null)
            for (File child : files)
                delete(child);
        file.delete();
    }

    // Commands run with toybox, toolbox or env, as a script of sh with options and as the pm script
    @Test
    public void otherFormsAreFaked() throws Exception {
        tools().properties("[ro.product.model]: [Pixel 9]\n");
        for (String[] args : new String[][]{
                {"toybox", "getprop", "ro.product.model"}, {"/system/bin/toolbox", "getprop", "ro.product.model"},
                {"env", "A=1", "getprop", "ro.product.model"}, {"env", "-i", "getprop", "ro.product.model"},
                {"sh", "-e", "-c", "getprop ro.product.model"}, {"sh", "-ec", "getprop ro.product.model"},
                {"/system/bin/sh", "-c", "toybox getprop ro.product.model"},
                {"sh", "-c", "/bin/getprop ro.product.model"},
                {"sh", "-c", "x=$(/system/bin/getprop ro.product.model); echo $x"},
                {"sh", "-c", "echo `/bin/getprop ro.product.model`"},
                {"sh", "-c", "A=1 /system/bin/getprop ro.product.model"},
                {"sh", "-c", "true && \\\n /system/bin/getprop ro.product.model"},
                {"sh", "-c", "if true; then \"/system/bin/getprop\" ro.product.model; fi"},
                {"sh", "-c", "f() { ! /system/bin/getprop ro.product.model; }; f || :"},
                {"sh", "-c", "case 1 in *) /system/bin/getprop ro.product.model;; esac"},
                {"sh", "-c", "case $(echo 1) in (1) /system/bin/getprop ro.product.model;; esac"},
                {"sh", "-c", "eval /system/bin/getprop ro.product.model"},
                {"sh", "-c", "eval \"/system/bin/getprop ro.product.model\""},
                {"sh", "-c", "eval 'true; /system/bin/getprop ro.product.model'"},
                {"sh", "-c", "/system/bin/toybox getprop ro.product.model </dev/null"}})
            assertEquals(String.join(" ", args), "unknown\n", run(start(args)).out);
        assertEquals("package:com.example\npackage:com.example.shared\n",
                run(start("sh", "/system/bin/pm", "list", "packages")).out);
        // Not the commands of other directories
        assertTrue(script(start("sh", "-c", "/usr/bin/getprop x")).contains("/usr/bin/getprop x"));
    }

    // Only paths in the place of a command become the functions: the paths of arguments, like checks for a tool,
    // and paths with something in front, like a directory of a variable, stay
    @Test
    public void otherPathsStay() throws Exception {
        tools().properties("[ro.product.model]: [Pixel 9]\n");
        assertEquals("y\n", run(start("sh", "-c", "[ -x /system/bin/getprop ] && echo y")).out);
        assertEquals("Pixel 9\n", run(start("sh", "-c", "for p in /system/bin/getprop /vendor/bin/getprop; do" +
                " [ -x \"$p\" ] && { \"$p\" ro.product.model; break; }; done")).out);
        for (String text : new String[]{"${ANDROID_ROOT:-/system}/bin/getprop ro.product.model",
                "d=/system; \"$d\"/bin/getprop ro.product.model", "P=/system/bin/getprop; $P ro.product.model",
                "uname -r; ls /system/bin/cat", "uname -r; $(dirname /system/x)/bin/cat /proc/version",
                "echo /system/bin/getprop", "pm list packages 2>&1 /system/bin/pm", "ls -l /system/bin/toybox pm",
                "echo $(uname -r) /system/bin/getprop", "echo $((1)) /system/bin/getprop",
                "echo \"/system/bin/getprop x\"", "x=\"/system/bin/getprop x\"; echo $x"}) {
            String script = script(start("sh", "-c", text));
            assertTrue(script, script.endsWith("\n" + text));
        }
    }

    // date prints the time zone of its environment, which the builder gets for the start only
    @Test
    public void dateGetsTheFakeTimeZone() throws Exception {
        Varargs result = start(new String[]{"date", "+%Z"}, ALL);
        assertEquals("date +%Z", String.join(" ", command(result)));
        assertEquals("Asia/Tokyo", result.arg(2).tojstring());
        assertTrue(result.arg(4).isnil());
        assertTrue(result.arg(5).toboolean());
        assertTrue(start(new String[]{"date"}, "tracking").arg(2).isnil());
    }

    // The builder gets the command of the app back, apps can start it again
    @Test
    public void builderGetsItsCommandBack() throws Exception {
        Varargs result = start(new String[]{"getprop", "ro.product.model"}, ALL);
        assertEquals("/system/bin/sh", command(result).get(0));
        assertEquals("getprop ro.product.model", String.join(" ", words(result.arg(3))));
        // Commands without the commands of the variants stay
        Varargs other = start(new String[]{"ls", "-l"}, ALL);
        assertEquals("ls -l", String.join(" ", command(other)));
        assertFalse(other.arg(5).toboolean());
    }

    // Lua to Java

    private Varargs start(String... words) {
        return start(words, ALL);
    }

    private Varargs start(String[] words, String... variants) {
        return globals.get("startof").invoke(LuaValue.varargsOf(new LuaValue[]{
                Scripts.strings(words), Scripts.strings(variants), settings}));
    }

    private static List<String> command(Varargs started) {
        return words(started.arg(1));
    }

    // The words of a stand-in of a Java list or array
    private static List<String> words(LuaValue value) {
        LuaValue items = value.get("items");
        int n = (items.isnil() ? value.get("length").toint() : value.get("n").toint());
        if (items.isnil())
            items = value;
        List<String> result = new ArrayList<>();
        for (int i = 1; i <= n; i++)
            result.add(items.get(i).tojstring());
        return result;
    }

    // The script of sh -c of a started command
    private static String script(Varargs started) {
        return command(started).get(2);
    }

    // The shell and the stand-ins

    private static class Result {
        final String out;
        final int status;

        Result(String out, int status) {
            this.out = out;
            this.status = status;
        }
    }

    private static class Tools {
        Tools properties(String listing) throws Exception {
            write("properties.txt", listing);
            return this;
        }

        Tools features(String listing) throws Exception {
            write("features.txt", listing);
            return this;
        }

        Tools libraries(String listing) throws Exception {
            write("libraries.txt", listing);
            return this;
        }
    }

    // A tool the tests need: without it they are skipped, like on Windows, unless XLUA_TEST_BIN names the tools,
    // like on a device (see scripts/test-on-device.sh), where a run without the tests would look like a pass
    private static void need(String dir, String message, boolean present) {
        if (dir == null)
            Assume.assumeTrue(message, present);
        else
            assertTrue(message + " in " + dir, present);
    }

    // A directory in place of /system/bin with the tools and stand-ins, created once
    private static Tools tools() throws Exception {
        if (bin == null) {
            String dir = System.getenv("XLUA_TEST_BIN");
            File sh = locate(dir, "sh");
            need(dir, "No shell", sh != null);
            // All tools first, so a missing one skips each test, instead of leaving a directory without it
            String[] names = {"sh", "sed", "grep", "printf", "cat", "readlink", "head", "dd"};
            File[] files = new File[names.length];
            for (int i = 0; i < names.length; i++) {
                files[i] = locate(dir, names[i]);
                need(dir, "No " + names[i], files[i] != null);
            }
            bin = Files.createTempDirectory("xlua-bin").toFile();
            for (int i = 0; i < names.length; i++)
                Files.createSymbolicLink(new File(bin, names[i]).toPath(), files[i].getAbsoluteFile().toPath());

            String header = "#!" + sh.getAbsolutePath() + "\nd=" + bin.getAbsolutePath() + "\n";
            stand("getprop", header +
                    "[ $# -eq 0 ] && exec cat $d/properties.txt\n" +
                    "v=$(grep -F \"[$1]: [\" $d/properties.txt | sed -e 's/^\\[[^]]*\\]: \\[\\(.*\\)\\]$/\\1/')\n" +
                    "[ -n \"$v\" ] || v=$2\n" +
                    "printf '%s\\n' \"$v\"\n");
            stand("uname", header +
                    "case \"$1\" in\n" +
                    " -r) echo 6.6.50-android15-8-g8adecb593e9b ;;\n" +
                    " -n) echo realhost ;;\n" +
                    " -v) echo '#1 SMP PREEMPT Fri Jan 3 12:00:00 UTC 2025' ;;\n" +
                    " -a) echo 'Linux realhost 6.6.50-android15-8-g8adecb593e9b" +
                    " #1 SMP PREEMPT Fri Jan 3 12:00:00 UTC 2025 aarch64 Toybox' ;;\n" +
                    " *) echo Linux ;;\n" +
                    "esac\n");
            stand("cmd", header +
                    "[ \"$1\" = -w ] && shift\n" +
                    "if [ \"$1\" = package ]; then shift; exec $d/pm \"$@\"; fi\n" +
                    "echo \"cmd $*\"\n");
            stand("pm", header + PM);
            write("packages.txt", "package:android\npackage:com.android.settings\npackage:com.bank\n" +
                    "package:com.example\npackage:com.example.shared\n");
            write("packages-f.txt", "package:/system/framework/framework-res.apk=android\n" +
                    "package:/data/app/~~a==/com.bank-b==/base.apk=com.bank\n" +
                    "package:/data/app/~~c==/com.example-d==/base.apk=com.example\n");
            write("system.txt", "package:android\npackage:com.android.settings\n");
            write("instrumentation.txt",
                    "instrumentation:com.bank.test/androidx.test.runner.AndroidJUnitRunner (target=com.bank)\n" +
                            "instrumentation:com.example/androidx.test.runner.AndroidJUnitRunner (target=com.example)\n");
            write("long-own.txt", dump(LONG_OWN));
            write("long-other.txt", dump(LONG_OTHER));
            new Tools().properties("").features("").libraries("");
        }
        return new Tools();
    }

    // Dumps of queries longer than one argument of a program can be (128 KB), of the app and of the app and another one
    private static final String[] LONG_OWN = new String[600];
    private static final String[] LONG_OTHER = new String[600];

    static {
        for (int i = 0; i < LONG_OWN.length; i++) {
            LONG_OWN[i] = (i % 2 == 0 ? "com.example" : "com.example.shared");
            LONG_OTHER[i] = (i == LONG_OTHER.length - 1 ? "com.bank" : "com.example");
        }
    }

    // A dump of a query, like PackageManagerShellCommand prints ResolveInfo
    private static String dump(String... packages) {
        StringBuilder sb = new StringBuilder();
        sb.append(packages.length).append(" activities found:\n");
        for (int i = 0; i < packages.length; i++)
            sb.append("  Activity #").append(i).append(":\n")
                    .append("    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n")
                    .append("    ActivityInfo:\n")
                    .append("      name=").append(packages[i]).append(".Main\n")
                    .append("      packageName=").append(packages[i]).append('\n')
                    .append("      enabled=true exported=true directBootAware=false\n")
                    .append("      ApplicationInfo:\n")
                    .append("        packageName=").append(packages[i]).append('\n')
                    .append("        dataDir=/data/user/0/").append(packages[i]).append('\n');
        return sb.toString();
    }

    // A dump of resolve-activity, of one activity
    private static String resolved(String name) {
        return dump(name).replaceFirst("^1 activities found:\n  Activity #0:\n", "");
    }

    private static final String PM = String.join("\n",
            "case \"$1\" in",
            " list)",
            "  case \"$2\" in",
            "   package|packages)",
            "    case \"$3\" in",
            "     -s) cat $d/system.txt ;;",
            "     -f) cat $d/packages-f.txt ;;",
            "     *) cat $d/packages.txt ;;",
            "    esac ;;",
            "   features) cat $d/features.txt ;;",
            "   libraries) cat $d/libraries.txt ;;",
            "   instrumentation) cat $d/instrumentation.txt ;;",
            "   permissions) echo permission:com.bank.permission.C2D_MESSAGE ;;",
            "  esac ;;",
            " -l|-L) cat $d/packages.txt ;;",
            " -lf) cat $d/packages-f.txt ;;",
            " path|-p)",
            "  [ \"$1\" = path ] && shift",
            "  case \"$1\" in --user) shift 2 ;; -*) shift ;; esac",
            "  grep -qxF \"package:$1\" $d/packages.txt || exit 1",
            "  echo \"package:/data/app/$1/base.apk\" ;;",
            " query-*)",
            "  case \"$*\" in",
            "   *com.example.LONG*) cat $d/long-own.txt ;;",
            "   *com.bank.LONG*) cat $d/long-other.txt ;;",
            "   *'-p com.example'*) printf '%s' '" + dump("com.example") + "' ;;",
            "   *--brief*) printf '2 activities found:\\n  Activity #0:\\n    priority=0\\n    com.bank/.Main\\n" +
                    "  Activity #1:\\n    priority=0\\n    com.example/.Main\\n' ;;",
            "   *) printf '%s' '" + dump("com.bank", "com.example") + "' ;;",
            "  esac ;;",
            " resolve-activity)",
            "  case \"$*\" in",
            "   *VIEW*) printf '%s' '" + resolved("com.bank") + "' ;;",
            "   *) printf '%s' '" + resolved("com.android.settings") + "' ;;",
            "  esac ;;",
            " has-feature)",
            "  if grep -qE \"^feature:$2(=|\\$)\" $d/features.txt; then echo true; else echo false; exit 1; fi ;;",
            " *) echo \"pm $*\" ;;",
            "esac",
            "");

    // A tool of the directory, else of the path
    private static File locate(String dir, String name) {
        List<String> dirs = new ArrayList<>();
        if (dir != null)
            dirs.add(dir);
        else
            dirs.addAll(Arrays.asList(System.getenv("PATH").split(File.pathSeparator)));
        for (String d : dirs) {
            File file = new File(d, name);
            if (file.canExecute())
                return file;
        }
        return null;
    }

    private static void stand(String name, String script) throws Exception {
        File file = new File(bin, name);
        Files.write(file.toPath(), script.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setExecutable(true));
    }

    private static void write(String name, String text) throws Exception {
        Files.write(new File(bin, name).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static Result run(Varargs started) throws Exception {
        return run(command(started));
    }

    // Runs a started command with the directory in place of /system/bin; the path of the processes,
    // which ProcessBuilder does not use itself, finds the stand-ins too
    private static Result run(List<String> command) throws Exception {
        tools();
        List<String> words = new ArrayList<>();
        for (String word : command)
            words.add(word.replace("/system/bin/", bin.getAbsolutePath() + "/"));
        File program = locate(null, words.get(0));
        if (!words.get(0).contains("/"))
            words.set(0, new File(bin, words.get(0)).exists() ? new File(bin, words.get(0)).getAbsolutePath()
                    : program.getAbsolutePath());
        ProcessBuilder builder = new ProcessBuilder(words);
        builder.environment().put("PATH", bin.getAbsolutePath() + File.pathSeparator + System.getenv("PATH"));
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        String out = read(process.getInputStream());
        return new Result(out, process.waitFor());
    }

    @AfterClass
    public static void clean() {
        if (bin != null) {
            File[] files = bin.listFiles();
            if (files != null)
                for (File file : files)
                    file.delete();
            bin.delete();
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n = in.read(buffer); n > 0; n = in.read(buffer))
            out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
