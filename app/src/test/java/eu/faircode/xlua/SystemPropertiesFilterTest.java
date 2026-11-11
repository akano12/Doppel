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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// The filter of the properties of the device (assets/systemproperties_get.lua) with the matching of the engine
// (XCore.Filter): the script runs only for the properties it can fake, not for all properties apps read
public class SystemPropertiesFilterTest {
    private static final String STUBS = String.join("\n",
            // The method of a name like SystemProperties.get/device
            "function hookof(name)",
            "  return { getName = function(self) return name end,",
            "    getMethodName = function(self) return string.match(name, '^[^.]+%.([^./]+)') end }",
            "end");

    @Test
    public void devicePropertiesAreFiltered() throws Exception {
        Globals globals = Scripts.load("systemproperties_get", STUBS);
        LuaValue values = Scripts.run(globals, "return filter(hookof('SystemProperties.get/device'))").arg1();
        assertFalse("no filter for the device properties", values.isnil());

        Class<?> type = Class.forName("eu.faircode.xlua.XCore$Filter");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object filter = constructor.newInstance();
        Method add = type.getDeclaredMethod("add", String.class);
        add.setAccessible(true);
        Method matches = type.getDeclaredMethod("matches", String.class);
        matches.setAccessible(true);
        for (int i = 1; i <= values.length(); i++)
            add.invoke(filter, values.get(i).checkjstring());

        // Each faked property of the device passes, a * being a partition
        String script = Scripts.read("systemproperties_get").replace("\r\n", "\n");
        int start = script.indexOf("local DEVICE = {");
        String device = script.substring(start, script.indexOf("\n}\n", start));
        Matcher m = Pattern.compile("'([a-z]+\\.[A-Za-z0-9_.*-]+)'").matcher(device);
        int keys = 0;
        while (m.find()) {
            String key = m.group(1).replace("*", "vendor");
            assertTrue(key, (Boolean) matches.invoke(filter, key));
            keys++;
        }
        // Else a changed format of the table would check nothing
        assertTrue("Keys=" + keys, keys > 50);
        // Properties the framework reads often do not run the script
        for (String key : new String[]{"debug.hwui.renderer", "persist.sys.locale", "log.tag.Foo"})
            assertFalse(key, (Boolean) matches.invoke(filter, key));
    }

    // USB debugging and the heap of apps: the script runs for the properties of the variant, not for those of the other
    @Test
    public void debuggingAndHeapPropertiesAreFiltered() throws Exception {
        Globals globals = Scripts.load("systemproperties_get", STUBS);
        Object debugging = FileScriptsTest.xcoreFilter(
                Scripts.run(globals, "return filter(hookof('SystemProperties.get/debugging'))").arg1().checktable().unpack());
        Object resources = FileScriptsTest.xcoreFilter(
                Scripts.run(globals, "return filter(hookof('SystemProperties.get/resources'))").arg1().checktable().unpack());
        for (String key : new String[]{"init.svc.adbd", "sys.usb.config", "sys.usb.state", "persist.sys.usb.config",
                "ro.debuggable", "ro.secure", "persist.logd.size", "persist.logd.size.main", "persist.logd.size.crash"}) {
            assertTrue(key, FileScriptsTest.filterMatches(debugging, key));
            assertFalse(key, FileScriptsTest.filterMatches(resources, key));
        }
        for (String key : new String[]{"dalvik.vm.heapgrowthlimit", "dalvik.vm.heapsize", "dalvik.vm.heapstartsize",
                "dalvik.vm.heapminfree", "dalvik.vm.heapmaxfree", "dalvik.vm.heaptargetutilization"}) {
            assertTrue(key, FileScriptsTest.filterMatches(resources, key));
            assertFalse(key, FileScriptsTest.filterMatches(debugging, key));
        }
    }
}
