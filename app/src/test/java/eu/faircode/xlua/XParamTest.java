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
 */

package eu.faircode.xlua;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;

import org.junit.Test;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.CoerceLuaToJava;

import java.lang.reflect.Method;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// Lua values set by scripts are converted to the types of the hooked methods,
// and scripts hide the packages of other apps
public class XParamTest {
    private static final int UID = 10123;

    @SuppressWarnings("unused")
    private static String field;

    @SuppressWarnings("unused")
    public static int count(int value, String text) {
        return value;
    }

    @SuppressWarnings("unused")
    public static void run() {
    }

    // Like a Lua number returned to Java code expecting an Object
    private static Object lua(double value) {
        return CoerceLuaToJava.coerce(LuaValue.valueOf(value), Object.class);
    }

    @Test
    public void wholeNumbers() {
        assertEquals(5000000000L, XParam.coerceValue(long.class, lua(5000000000d)));
        assertEquals(7L, XParam.coerceValue(long.class, lua(7)));
        assertEquals(7, XParam.coerceValue(int.class, lua(7)));
        assertEquals((short) 7, XParam.coerceValue(short.class, lua(7)));
        assertEquals((byte) 7, XParam.coerceValue(byte.class, lua(7)));
    }

    @Test
    public void floatingNumbers() {
        assertEquals(1.5d, XParam.coerceValue(double.class, lua(1.5)));
        assertEquals(1.5f, XParam.coerceValue(float.class, lua(1.5)));
        assertEquals(7d, XParam.coerceValue(double.class, lua(7)));
    }

    // Values which do not fit are left as they are, so XParam reports the wrong type
    @Test
    public void noLossyConversion() {
        assertEquals(Double.class, XParam.coerceValue(int.class, lua(1.5)).getClass());
        assertEquals(Double.class, XParam.coerceValue(int.class, lua(5000000000d)).getClass());
        assertEquals(Integer.class, XParam.coerceValue(byte.class, lua(300)).getClass());
    }

    @Test
    public void strings() {
        assertEquals(42, XParam.coerceValue(int.class, "42"));
        assertEquals(42L, XParam.coerceValue(long.class, "42"));
        assertEquals("42", XParam.coerceValue(String.class, "42"));
    }

    private static ApplicationInfo app(int uid, int flags) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.uid = uid;
        ai.flags = flags;
        return ai;
    }

    private static Context context(int uid) {
        ApplicationInfo ai = app(uid, 0);
        return new ContextWrapper(null) {
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

    private static XParam param() throws NoSuchFieldException {
        return param(UID);
    }

    private static XParam param(int uid) throws NoSuchFieldException {
        return new XParam(context(uid), XParamTest.class.getDeclaredField("field"), new HashMap<>());
    }

    private static XParam param(XCall call) {
        return new XParam(context(UID), call, new HashMap<>());
    }

    // A field cannot be read with an exception
    @Test
    public void fieldThrowable() throws Throwable {
        try {
            param().setResult(new IllegalStateException("blocked"));
            fail("Throwable for a field");
        } catch (IllegalArgumentException ignored) {
        }
    }

    // Arguments set by scripts must fit the parameters of the method
    @Test
    public void arguments() throws NoSuchMethodException {
        Method count = XParamTest.class.getMethod("count", int.class, String.class);
        XParam param = param(new XCall(count, null, new Object[]{1, "one"}));
        param.setArgument(0, lua(7));
        assertEquals(7, param.getArgument(0));
        param.setArgument(1, null);
        assertNull(param.getArgument(1));

        try {
            param.setArgument(0, null);
            fail("Null for a primitive");
        } catch (IllegalArgumentException ex) {
            assertEquals("Expected argument #0 int got null", ex.getMessage());
        }
        try {
            param.setArgument(1, lua(7));
            fail("Number for a string");
        } catch (IllegalArgumentException ignored) {
        }
        try {
            param.setArgument(2, null);
            fail("Argument out of range");
        } catch (ArrayIndexOutOfBoundsException ignored) {
        }
    }

    // Results set by scripts must fit the return type, a throwable is thrown instead of returned
    @Test
    public void results() throws Throwable {
        Method count = XParamTest.class.getMethod("count", int.class, String.class);
        XCall call = new XCall(count, null, new Object[]{1, "one"});
        XParam param = param(call);
        param.setResult(lua(7));
        assertEquals(7, param.getResult());
        assertTrue(call.isReturnEarly());

        try {
            param.setResult(null);
            fail("Null for a primitive");
        } catch (IllegalArgumentException ex) {
            assertEquals("Expected return int got null", ex.getMessage());
        }
        try {
            param.setResult(true);
            fail("Boolean for a number");
        } catch (IllegalArgumentException ignored) {
        }

        param.setResult(new IllegalStateException("blocked"));
        assertTrue(param.getException() instanceof IllegalStateException);
        assertNull(param.getResult());

        // A void method is skipped with a null result, which is the only result it can have
        XCall run = new XCall(XParamTest.class.getMethod("run"), null, new Object[0]);
        param = param(run);
        param.setResult(null);
        assertTrue(run.isReturnEarly());
        try {
            param.setResult(lua(7));
            fail("Value for void");
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Test
    public void visibleApplications() throws NoSuchFieldException {
        XParam param = param();
        assertTrue(param.isVisible(null));
        assertTrue("Own uid", param.isVisible(app(UID, 0)));
        assertTrue("System", param.isVisible(app(1000, ApplicationInfo.FLAG_SYSTEM)));
        assertTrue("Updated system", param.isVisible(app(10001, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)));
        assertFalse("Other app", param.isVisible(app(10124, ApplicationInfo.FLAG_INSTALLED)));
    }

    // Uids which do not need a lookup
    @Test
    public void visibleUids() throws NoSuchFieldException {
        XParam param = param();
        assertTrue("Own uid", param.isUidVisible(UID));
        assertTrue("System", param.isUidVisible(1000));
        assertTrue("System of another user", param.isUidVisible(1001000));
        assertTrue("Root", param.isUidVisible(0));
    }

    // Processes with a system uid, like system server, see all apps; without lookups, which need a package manager
    @Test
    public void systemProcess() throws NoSuchFieldException {
        assertFalse(param().isSystem());
        for (int uid : new int[]{1000, 1001, 1001000}) {
            XParam param = param(uid);
            assertTrue(param.isSystem());
            assertTrue("Other app", param.isVisible(app(10124, ApplicationInfo.FLAG_INSTALLED)));
            assertTrue("Other package", param.isPackageVisible("other.app"));
            assertTrue("Other uid", param.isUidVisible(10124));
        }
    }

    @Test
    public void installedPackages() throws NoSuchFieldException {
        XParam param = param();
        assertTrue("Own package", param.isInstalledVisible("test.app"));
        assertFalse("No package", param.isInstalledVisible(null));
    }

    // The lookups of scripts do not run the hooks of the app, also when they fail
    @Test
    public void unhooked() throws NoSuchFieldException {
        XParam param = param();
        assertFalse(XCore.inService());
        LuaValue result = param.unhooked(new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(XCore.inService());
            }
        });
        assertTrue(result.toboolean());
        assertFalse(XCore.inService());

        try {
            param.unhooked(new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    throw new LuaError("lookup");
                }
            });
            fail("Error not passed on");
        } catch (LuaError ignored) {
        }
        assertFalse(XCore.inService());
    }
}
