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

import android.text.InputFilter;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ActivityValuesTest {
    // Numbers typed with a decimal comma, like on German and French keyboards, are saved with a decimal point, which
    // Double.parseDouble of the check and tonumber of the scripts read
    @Test
    public void decimalComma() {
        assertEquals("48.137", ActivityValues.decimal("48,137"));
        assertEquals(-11.5, Double.parseDouble(ActivityValues.decimal("-11,5")), 0);
        assertEquals("10000", ActivityValues.decimal("10000"));
        assertEquals("1.5", ActivityValues.decimal("1.5"));
    }

    // Identifiers have the form of real ones: the serial number of the compatibility test of Android, an IMEI of 15
    // digits with its Luhn check digit, a MEID of 14 hexadecimal digits, a phone number of E.164
    @Test
    public void identifierForms() {
        assertTrue(ActivityValues.isValid(FakeIds.SERIAL, "R58M41XKQ2W"));
        assertTrue(ActivityValues.isValid(FakeIds.SERIAL, "abc123"));
        assertFalse(ActivityValues.isValid(FakeIds.SERIAL, "ABC12"));
        assertFalse(ActivityValues.isValid(FakeIds.SERIAL, "ABCDEFGHIJ0123456789X"));
        assertFalse(ActivityValues.isValid(FakeIds.SERIAL, "R58M-41XKQ2W"));

        assertTrue(ActivityValues.isValid(FakeIds.IMEI, "490154203237518"));
        assertFalse(ActivityValues.isValid(FakeIds.IMEI, "490154203237519"));
        assertFalse(ActivityValues.isValid(FakeIds.IMEI, "49015420323751"));
        assertFalse(ActivityValues.isValid(FakeIds.IMEI, "4901542032375180"));

        assertTrue(ActivityValues.isValid(FakeIds.MEID, "A0123456789ABC"));
        assertTrue(ActivityValues.isValid(FakeIds.MEID, "a0123456789abc"));
        assertFalse(ActivityValues.isValid(FakeIds.MEID, "A0123456789AB"));
        assertFalse(ActivityValues.isValid(FakeIds.MEID, "A0123456789ABG"));

        assertTrue(ActivityValues.isValid(FakeIds.ANDROID_ID, "078e0d3ba5c4ee62"));
        assertFalse(ActivityValues.isValid(FakeIds.ANDROID_ID, "078e0d3ba5c4ee621"));

        assertTrue(ActivityValues.isValid("value.phone_number", "+31612345678"));
        assertTrue(ActivityValues.isValid("value.phone_number", "0612345678"));
        assertFalse(ActivityValues.isValid("value.phone_number", "+3161234567890123"));
        assertFalse(ActivityValues.isValid("value.phone_number", "06-12345678"));

        assertTrue(ActivityValues.isValid("value.email", "private@example.com"));
        assertFalse(ActivityValues.isValid("value.email", "private"));
    }

    // The random button makes values which the checks take
    @Test
    public void generatedPass() {
        Random random = new Random(1);
        for (int i = 0; i < 1000; i++)
            for (String key : FakeIds.KEYS)
                assertTrue(key, ActivityValues.isValid(key, FakeIds.generate(key, random)));
    }

    // The fields take only the characters of their form, also of a paste, in the case of the form
    @Test
    public void fieldCharacters() {
        InputFilter hexUpper = ActivityValues.chars("0123456789abcdefABCDEF", true);
        assertEquals("A0FF", hexUpper.filter("a0-ff", 0, 5, null, 0, 0).toString());
        assertNull(hexUpper.filter("A0FF", 0, 4, null, 0, 0));
        InputFilter digits = ActivityValues.chars("0123456789", null);
        assertEquals("49015", digits.filter("49 01 5x", 0, 8, null, 0, 0).toString());
    }
}
