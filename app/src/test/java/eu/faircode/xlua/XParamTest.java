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

import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;

import org.junit.Test;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceLuaToJava;

import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Lua values set by scripts are converted to the types of the hooked methods,
// and scripts hide the packages of other apps
public class XParamTest {
    private static final int UID = 10123;

    @SuppressWarnings("unused")
    private static String field;
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

    private static XParam param() throws NoSuchFieldException {
        ApplicationInfo ai = app(UID, 0);
        return new XParam(new ContextWrapper(null) {
            @Override
            public String getPackageName() {
                return "test.app";
            }

            @Override
            public ApplicationInfo getApplicationInfo() {
                return ai;
            }
        }, XParamTest.class.getDeclaredField("field"), new HashMap<>());
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
}
