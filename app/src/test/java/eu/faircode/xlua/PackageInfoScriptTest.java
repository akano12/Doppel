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

import org.junit.Before;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.Varargs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The install and update times of package infos read from a parcel (assets/packageinfo_createfromparcel.lua),
// with a Lua stand-in of the package info, like LocationScriptTest
public class PackageInfoScriptTest {
    private static final String FAKE = "1230768000000";

    private static final String STUBS = String.join("\n",
            "function infoof(packageName, flags, first, last)",
            "  return { packageName = packageName, applicationInfo = { flags = flags },",
            "    firstInstallTime = first, lastUpdateTime = last }",
            "end",
            "function paramof(info, system)",
            "  local param = { result = info }",
            "  function param:getResult() return self.result end",
            "  function param:getPackageName() return 'com.example.app' end",
            "  function param:isSystem() return system == true end",
            "  return param",
            "end",
            "function run(info, system)",
            "  local restricted, old, new = after({}, paramof(info, system))",
            "  return restricted, old, new, millis(info.firstInstallTime),",
            "    millis(info.lastUpdateTime)",
            "end");

    private Globals globals;

    @Before
    public void load() throws Exception {
        globals = Scripts.load("packageinfo_createfromparcel", STUBS);
    }

    private Varargs run(String lua) {
        return Scripts.run(globals, "return run(" + lua + ")");
    }

    @Test
    public void otherApp() {
        Varargs result = run("infoof('com.other.app', 0, 1760565949123, 1761400000456)");
        assertTrue(result.arg1().toboolean());
        assertEquals("com.other.app 1760565949123", result.arg(2).tojstring());
        assertEquals(FAKE, result.arg(3).tojstring());
        assertEquals(FAKE, result.arg(4).tojstring());
        assertEquals(FAKE, result.arg(5).tojstring());
    }

    // An int with the highest bit set, like FLAG_MULTIARCH, is negative; FLAG_SYSTEM is the lowest bit
    @Test
    public void negativeFlags() {
        Varargs result = run("infoof('com.other.app', -2147483648, 1760565949123, 1760565949123)");
        assertTrue(result.arg1().toboolean());
        result = run("infoof('com.other.app', -2147483647, 1760565949123, 1760565949123)");
        assertFalse(result.arg1().toboolean());
    }

    @Test
    public void ownTimesStay() {
        Varargs result = run("infoof('com.example.app', 0, 1760565949123, 1761400000456)");
        assertFalse(result.arg1().toboolean());
        assertEquals("1760565949123", result.arg(4).tojstring());
        assertEquals("1761400000456", result.arg(5).tojstring());
    }

    @Test
    public void systemAppsStay() {
        Varargs result = run("infoof('com.android.settings', 1, 1720734637000, 1720734637000)");
        assertFalse(result.arg1().toboolean());
        assertEquals("1720734637000", result.arg(4).tojstring());
    }

    // An update of a system app, FLAG_UPDATED_SYSTEM_APP, has the time it was installed on the device;
    // it gets the time of the firmware, like before the update
    @Test
    public void updatedSystemApps() {
        Varargs result = run("infoof('com.google.android.gms', 129, 1720734637000, 1761400000456)");
        assertTrue(result.arg1().toboolean());
        assertEquals("com.google.android.gms 1761400000456", result.arg(2).tojstring());
        assertEquals("1720734637000", result.arg(4).tojstring());
        assertEquals("1720734637000", result.arg(5).tojstring());
    }

    @Test
    public void systemProcessesSeeAll() {
        Varargs result = run("infoof('com.other.app', 0, 1760565949123, 1760565949123), true");
        assertFalse(result.arg1().toboolean());
        assertEquals("1760565949123", result.arg(4).tojstring());
    }

    // Without the application info, like for some flags, the times are of an app too
    @Test
    public void withoutApplicationInfo() {
        Varargs result = Scripts.run(globals, "local info = infoof('com.other.app', 0, 1760565949123, 1760565949123)\n" +
                "info.applicationInfo = nil\n" +
                "return run(info)");
        assertTrue(result.arg1().toboolean());
        assertEquals(FAKE, result.arg(4).tojstring());
    }

    @Test
    public void fakeStays() {
        Varargs result = run("infoof('com.other.app', 0, " + FAKE + ", " + FAKE + ")");
        assertFalse(result.arg1().toboolean());
    }
}
