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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// The fake identifiers which are fixed or new each boot, see FakeIds
public class FakeIdsTest {
    private static final byte[] SECRET = "a secret of this boot, 32 bytes!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "the secret of the next boot.....".getBytes(StandardCharsets.UTF_8);

    // Random IMEIs end with a Luhn check digit
    @Test
    public void luhnCheckDigit() {
        assertEquals(8, FakeIds.luhn("49015420323751"));
        assertEquals(9, FakeIds.luhn("35693803564380"));
    }

    // The formats of the randomize button of Fake values, which the values new each boot have too
    @Test
    public void valuesHaveTheFormatOfTheIdentifier() {
        Random random = new Random(1);
        for (int i = 0; i < 200; i++) {
            assertTrue(FakeIds.generate(FakeIds.SERIAL, random).matches("[0-9A-Z]{12}"));
            assertTrue(FakeIds.generate(FakeIds.ANDROID_ID, random).matches("[0-9a-f]{16}"));
            assertTrue(FakeIds.generate(FakeIds.MEID, random).matches("[0-9A-F]{14}"));
            String imei = FakeIds.generate(FakeIds.IMEI, random);
            assertTrue(imei, imei.matches("[0-9]{15}"));
            assertEquals(imei, FakeIds.luhn(imei.substring(0, 14)), imei.charAt(14) - '0');
            assertTrue(FakeIds.derive(SECRET, 0, "app" + i, FakeIds.IMEI).matches("[0-9]{15}"));
        }
        assertNull(FakeIds.generate("value.email", random));
    }

    // The same for an app until the next boot, another for another app, user or boot, the same for all apps without one
    @Test
    public void valuesAreOfTheAppAndTheBoot() {
        String value = FakeIds.derive(SECRET, 0, "com.example", FakeIds.ANDROID_ID);
        assertEquals(value, FakeIds.derive(SECRET, 0, "com.example", FakeIds.ANDROID_ID));
        assertNotEquals(value, FakeIds.derive(SECRET, 0, "com.other", FakeIds.ANDROID_ID));
        assertNotEquals(value, FakeIds.derive(SECRET, 10, "com.example", FakeIds.ANDROID_ID));
        assertNotEquals(value, FakeIds.derive(OTHER, 0, "com.example", FakeIds.ANDROID_ID));
        assertNotEquals(value, FakeIds.derive(SECRET, 0, null, FakeIds.ANDROID_ID));
        // Each identifier is a value of its own, not one value in other formats
        assertNotEquals(FakeIds.derive(SECRET, 0, "com.example", FakeIds.IMEI).substring(0, 8),
                FakeIds.derive(SECRET, 0, "com.example", FakeIds.MEID).substring(0, 8));
    }

    @Test
    public void modes() {
        Map<String, String> global = new HashMap<>();
        Map<String, String> app = new HashMap<>();
        // Without settings: the serial is fixed (unknown), the others new each boot
        assertEquals(FakeIds.FIXED, FakeIds.getMode(FakeIds.SERIAL, global, app));
        assertEquals(FakeIds.BOOT, FakeIds.getMode(FakeIds.ANDROID_ID, global, app));
        assertEquals(FakeIds.BOOT, FakeIds.getMode(FakeIds.IMEI, null, null));

        // A value set without a mode is fixed, like before there were modes
        global.put(FakeIds.ANDROID_ID, "0123456789abcdef");
        assertEquals(FakeIds.FIXED, FakeIds.getMode(FakeIds.ANDROID_ID, global, app));
        global.put(FakeIds.ANDROID_ID + FakeIds.MODE, FakeIds.BOOT_ALL);
        assertEquals(FakeIds.BOOT_ALL, FakeIds.getMode(FakeIds.ANDROID_ID, global, app));

        // The app replaces all apps, with a mode or with a value; an unknown mode does not count
        app.put(FakeIds.ANDROID_ID + FakeIds.MODE, FakeIds.FIXED);
        assertEquals(FakeIds.FIXED, FakeIds.getMode(FakeIds.ANDROID_ID, global, app));
        app.clear();
        app.put(FakeIds.IMEI, "353918056224785");
        assertEquals(FakeIds.FIXED, FakeIds.getMode(FakeIds.IMEI, global, app));
        app.put(FakeIds.IMEI + FakeIds.MODE, "sometimes");
        assertEquals(FakeIds.FIXED, FakeIds.getMode(FakeIds.IMEI, global, app));
        assertNull(FakeIds.getOwnMode(FakeIds.MEID, app));
    }

    // Only the identifiers new each boot get a value, of the app or the same for all apps
    @Test
    public void bootValues() {
        Map<String, String> global = new HashMap<>();
        global.put(FakeIds.MEID + FakeIds.MODE, FakeIds.BOOT_ALL);
        Map<String, String> app = new HashMap<>();
        app.put(FakeIds.IMEI + FakeIds.MODE, FakeIds.FIXED);

        Map<String, String> one = FakeIds.getBootValues(SECRET, 0, "com.example", global, app);
        Map<String, String> other = FakeIds.getBootValues(SECRET, 0, "com.other", global, new HashMap<>());
        assertFalse(one.containsKey(FakeIds.SERIAL));
        assertFalse(one.containsKey(FakeIds.IMEI));
        assertTrue(other.containsKey(FakeIds.IMEI));
        assertNotEquals(one.get(FakeIds.ANDROID_ID), other.get(FakeIds.ANDROID_ID));
        assertEquals(one.get(FakeIds.MEID), other.get(FakeIds.MEID));
        assertEquals(FakeIds.derive(SECRET, 0, "com.example", FakeIds.ANDROID_ID), one.get(FakeIds.ANDROID_ID));
    }
}
