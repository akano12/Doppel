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

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;

import org.junit.Before;
import org.junit.Test;
import org.luaj.vm2.Prototype;
import org.luaj.vm2.compiler.LuaC;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// Hooks installed by the engine, with a backend which records them
public class XCoreTest {
    private final List<Member> members = new ArrayList<>();
    private final List<XBackend.Callback> callbacks = new ArrayList<>();
    private XCore core;
    private Context context;

    @SuppressWarnings("unused")
    public static class Target {
        public String get() {
            return "real";
        }

        public void inherited() {
        }

        public String open(String path) {
            return "real";
        }
    }

    public static class First extends Target {
    }

    public static class Second extends Target {
    }

    @Before
    public void setUp() {
        members.clear();
        callbacks.clear();
        core = new XCore(new XBackend() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public void hook(Member member, Callback callback) {
                members.add(member);
                callbacks.add(callback);
            }

            @Override
            public void log(String message) {
            }

            @Override
            public void log(Throwable ex) {
            }
        });

        ApplicationInfo ai = new ApplicationInfo();
        ai.uid = 10123;
        context = new ContextWrapper(null) {
            @Override
            public String getPackageName() {
                return "test.app";
            }

            @Override
            public ApplicationInfo getApplicationInfo() {
                return ai;
            }
        };
    }

    // Like XCore.install, without the class lookup through the class loader of an app
    private void install(String name, String script, Member member) throws Exception {
        XHook hook = XHook.fromJSON("{\"collection\": \"Test\", \"group\": \"Test.Group\", \"name\": \"" + name + "\"," +
                " \"author\": \"Test\", \"className\": \"" + Target.class.getName() + "\", \"parameterTypes\": []," +
                " \"minSdk\": 1, \"usage\": false, \"luaScript\": \"\"}");
        Prototype prototype = LuaC.instance.compile(
                new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)), "script");
        Method installMethod = XCore.class.getDeclaredMethod("installMethod",
                Context.class, XHook.class, Prototype.class, Map.class, Member.class);
        installMethod.setAccessible(true);
        installMethod.invoke(core, context, hook, prototype, new HashMap<String, String>(), member);
    }

    // Definitions for a range of app versions; an app with a versionCodeMajor has a version code beyond an int
    @Test
    public void apkVersions() throws Throwable {
        String json = "{\"collection\": \"Test\", \"group\": \"Test.Group\", \"name\": \"versions\"," +
                " \"author\": \"Test\", \"className\": \"java.lang.Object\", \"parameterTypes\": []," +
                " \"minSdk\": 1, \"luaScript\": \"\"";
        XHook all = XHook.fromJSON(json + "}");
        assertTrue(all.isAvailable(0));
        assertTrue(all.isAvailable(Integer.MAX_VALUE));
        assertTrue(all.isAvailable(1L << 32));
        XHook range = XHook.fromJSON(json + ", \"minApk\": 10, \"maxApk\": 20}");
        assertFalse(range.isAvailable(9));
        assertTrue(range.isAvailable(10));
        assertTrue(range.isAvailable(20));
        assertFalse(range.isAvailable(21));
        assertFalse(range.isAvailable(1L << 32));
    }

    @Test
    public void scriptRuns() throws Throwable {
        Method get = Target.class.getMethod("get");
        install("scriptRuns", "function before(hook, param) param:setResult('fake') return true end", get);
        assertEquals(1, callbacks.size());

        XCall call = new XCall(get, new Target(), new Object[0]);
        callbacks.get(0).before(call);
        assertEquals("fake", call.getResult());
    }

    // In system server the hooks of "android" do not apply to the service and the engine itself
    @Test
    public void notInService() throws Throwable {
        Method get = Target.class.getMethod("get");
        install("notInService", "function before(hook, param) param:setResult('fake') return true end", get);

        XCall call = new XCall(get, new Target(), new Object[0]);
        XCore.enterService();
        try {
            callbacks.get(0).before(call);
        } finally {
            XCore.leaveService();
        }
        assertNull(call.getResult());
        assertFalse(call.isReturnEarly());

        // Only while in the service
        callbacks.get(0).before(call);
        assertEquals("fake", call.getResult());
    }

    // A script with a filter runs for calls with one of its values as the first argument only
    @Test
    public void filteredScript() throws Throwable {
        Method open = Target.class.getMethod("open", String.class);
        install("filteredScript",
                "function filter(hook) return { '/proc/meminfo' } end\n" +
                        "function before(hook, param) param:setResult('fake') return true end", open);

        XCall other = new XCall(open, new Target(), new Object[]{"/data/file"});
        callbacks.get(0).before(other);
        assertFalse(other.isReturnEarly());

        XCall filtered = new XCall(open, new Target(), new Object[]{"/proc/meminfo"});
        callbacks.get(0).before(filtered);
        assertEquals("fake", filtered.getResult());

        // A null path is no value of the filter
        XCall none = new XCall(open, new Target(), new Object[]{null});
        callbacks.get(0).before(none);
        assertFalse(none.isReturnEarly());
    }

    // A value ending in * stands for all values starting with the text before it
    @Test
    public void prefixFilter() throws Throwable {
        Method open = Target.class.getMethod("open", String.class);
        install("prefixFilter",
                "function filter(hook) return { '/sys/*', '/proc/meminfo' } end\n" +
                        "function before(hook, param) param:setResult('fake') return true end", open);

        for (String path : new String[]{"/sys/devices/system/chip-id/unique_id", "/sys/", "/proc/meminfo"}) {
            XCall call = new XCall(open, new Target(), new Object[]{path});
            callbacks.get(0).before(call);
            assertEquals(path, "fake", call.getResult());
        }
        for (String path : new String[]{"/system/build.prop", "/sys", "/proc/meminfo2", "/data/sys/"}) {
            XCall call = new XCall(open, new Target(), new Object[]{path});
            callbacks.get(0).before(call);
            assertFalse(path, call.isReturnEarly());
        }
    }

    // Files are compared by their path, other objects not at all
    @Test
    public void filterValues() {
        assertEquals("/sdcard/DCIM", XCore.filterValue("/sdcard/DCIM"));
        // Like File.getPath, which has the separator of the platform the test runs on
        assertEquals(new java.io.File("/sdcard/DCIM").getPath(), XCore.filterValue(new java.io.File("/sdcard/DCIM/")));
        assertNull(XCore.filterValue(new Target()));
        assertNull(XCore.filterValue(null));
    }

    // Dynamic hooks of methods which objects inherit are installed once, in the class declaring the method
    @Test
    public void dynamicInheritedMethod() throws Throwable {
        Method get = Target.class.getMethod("get");
        install("dynamicInheritedMethod",
                "function after(h, param)\n" +
                        "  hook(param:getThis(), 'inherited', function(when, p) end)\n" +
                        "  hook(param:getThis(), 'missing', function(when, p) end)\n" +
                        "  return false\n" +
                        "end", get);
        assertEquals(1, members.size());

        XCall first = new XCall(get, new First(), new Object[0]);
        callbacks.get(0).after(first);
        assertEquals(2, members.size());
        assertEquals(Target.class.getMethod("inherited"), members.get(1));

        // An object of another subclass shares the hook, the same object does not hook again
        callbacks.get(0).after(new XCall(get, new Second(), new Object[0]));
        callbacks.get(0).after(first);
        assertEquals(2, members.size());
    }
}
