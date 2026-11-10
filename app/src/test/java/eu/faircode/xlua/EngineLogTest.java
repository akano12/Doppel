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

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// What the engine writes about the calls of an app: the arguments of a failed call without the code of the app,
// and the log, which apps can read, only in system server and debug builds
public class EngineLogTest {
    private static String describe(Object value) throws Exception {
        Method method = XCore.class.getDeclaredMethod("describe", Object.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }

    @Test
    public void argumentsWithoutCodeOfTheApp() throws Exception {
        Object own = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("code of the app ran");
            }
        };
        assertTrue(describe(own).startsWith(own.getClass().getName() + "@"));
        assertEquals("abc (java.lang.String)", describe("abc"));
        assertEquals("5 (java.lang.Integer)", describe(5));
        assertEquals("null", describe(null));
    }

    @Test
    public void logOfDebugBuildsAndSystemServer() {
        assertEquals(BuildConfig.DEBUG, XLog.enabled());
        XLog.inSystemServer();
        assertTrue(XLog.enabled());
    }
}
