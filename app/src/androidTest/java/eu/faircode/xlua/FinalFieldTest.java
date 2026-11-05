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

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

// The fallback for apps targeting Android 17, which is reachable from any test app
@RunWith(AndroidJUnit4.class)
public class FinalFieldTest {
    // Not compile time constants, which would be inlined
    private static final String TEXT = String.valueOf("real");
    private static final long NUMBER = Long.valueOf(1);
    private static final int SMALL = Integer.valueOf(1);
    private static final boolean FLAG = Boolean.valueOf(false);

    private static void check(String name, Object value) throws Exception {
        Field field = FinalFieldTest.class.getDeclaredField(name);
        field.setAccessible(true);
        XParam.setFinalField(field, value);
        assertEquals(value, field.get(null));
    }

    @Test
    public void writesEachKindOfField() throws Exception {
        check("TEXT", "fake");
        check("NUMBER", 2L);
        check("SMALL", 2);
        check("FLAG", true);
    }
}
