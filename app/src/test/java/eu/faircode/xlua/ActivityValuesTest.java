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

import static org.junit.Assert.assertEquals;

// Numbers typed with a decimal comma, like on German and French keyboards, are saved with a decimal point, which
// Double.parseDouble of the check and tonumber of the scripts read
public class ActivityValuesTest {
    @Test
    public void decimalComma() {
        assertEquals("48.137", ActivityValues.decimal("48,137"));
        assertEquals(-11.5, Double.parseDouble(ActivityValues.decimal("-11,5")), 0);
        assertEquals("10000", ActivityValues.decimal("10000"));
        assertEquals("1.5", ActivityValues.decimal("1.5"));
    }
}
