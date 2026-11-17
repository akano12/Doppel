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

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// The fake identifiers which are fixed, kept or new each boot, see FakeIds
public class FakeIdsTest {
    private static final byte[] SECRET = "a secret of this boot, 32 bytes!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "the secret of the next boot.....".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEPT = "the secret the service keeps....".getBytes(StandardCharsets.UTF_8);

    // Random IMEIs end with a Luhn check digit
    @Test
    public void luhnCheckDigit() {
        assertEquals(8, FakeIds.luhn("49015420323751"));
        assertEquals(9, FakeIds.luhn("35693803564380"));
    }

    // The formats of the randomize button of Fake values, which the values new each boot have too: the IMEI of the
    // reporting body of most phones, a hexadecimal MEID with a regional code of A0 to FF
    @Test
    public void valuesHaveTheFormatOfTheIdentifier() {
        Random random = new Random(1);
        for (int i = 0; i < 200; i++) {
            assertTrue(FakeIds.generate(FakeIds.SERIAL, random).matches("[0-9A-Z]{12}"));
            assertTrue(FakeIds.generate(FakeIds.ANDROID_ID, random).matches("[0-9a-f]{16}"));
            String meid = FakeIds.generate(FakeIds.MEID, random);
            assertTrue(meid, meid.matches("[A-F][0-9A-F]{13}"));
            assertTrue(FakeIds.derive(SECRET, 0, "app" + i, FakeIds.MEID).matches("[A-F][0-9A-F]{13}"));
            String imei = FakeIds.generate(FakeIds.IMEI, random);
            assertTrue(imei, imei.matches("35[0-9]{13}"));
            assertEquals(imei, FakeIds.luhn(imei.substring(0, 14)), imei.charAt(14) - '0');
            assertTrue(FakeIds.derive(SECRET, 0, "app" + i, FakeIds.IMEI).matches("35[0-9]{13}"));
        }
        assertNull(FakeIds.generate("value.email", random));
    }

    // The characters are the bits of the HMAC, like the Android ID of Android, not of java.util.Random seeded with them
    @Test
    public void valuesAreTheBitsOfTheHmac() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        byte[] hash = mac.doFinal("0/com.example/value.android_id".getBytes(StandardCharsets.UTF_8));
        String expected = String.format("%016x", new BigInteger(1, hash).longValue());
        String value = FakeIds.derive(SECRET, 0, "com.example", FakeIds.ANDROID_ID);
        assertEquals(expected, value);

        Random seeded = new Random(ByteBuffer.wrap(hash).getLong());
        StringBuilder old = new StringBuilder();
        for (int i = 0; i < 16; i++)
            old.append("0123456789abcdef".charAt(seeded.nextInt(16)));
        assertNotEquals(old.toString(), value);
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
    }

    // The default Android ID is of the developer like the one of Android and stays the same after a restart, since it is
    // of the secret the service keeps, not of the one of the boot; with a value set, for the app or for all apps, the
    // value is the one set, and chosen new each boot it is of the boot
    @Test
    public void defaultAndroidIdStays() {
        String developer = FakeIds.signer(new byte[][]{"certificate of a developer".getBytes(StandardCharsets.UTF_8)});
        String other = FakeIds.signer(new byte[][]{"certificate of another one".getBytes(StandardCharsets.UTF_8)});
        Map<String, String> none = new HashMap<>();
        Map<String, String> one = FakeIds.getBootValues(SECRET, KEPT, 0, "com.example.one", developer, none, none);
        Map<String, String> next = FakeIds.getBootValues(OTHER, KEPT, 0, "com.example.one", developer, none, none);
        assertTrue(one.get(FakeIds.ANDROID_ID).matches("[0-9a-f]{16}"));
        assertEquals(one.get(FakeIds.ANDROID_ID), next.get(FakeIds.ANDROID_ID));
        assertEquals(one.get(FakeIds.DRM_ID), next.get(FakeIds.DRM_ID));
        assertEquals(one.get(FakeIds.ANDROID_ID),
                FakeIds.getBootValues(SECRET, KEPT, 0, "com.example.two", developer, none, none).get(FakeIds.ANDROID_ID));
        assertNotEquals(one.get(FakeIds.ANDROID_ID),
                FakeIds.getBootValues(SECRET, KEPT, 0, "com.example.one", other, none, none).get(FakeIds.ANDROID_ID));
        assertNotEquals(one.get(FakeIds.ANDROID_ID),
                FakeIds.getBootValues(SECRET, OTHER, 0, "com.example.one", developer, none, none).get(FakeIds.ANDROID_ID));
        // The secret of the boot still changes the location key
        assertNotEquals(one.get(FakeIds.LOCATION_KEY), next.get(FakeIds.LOCATION_KEY));

        Map<String, String> set = new HashMap<>();
        set.put(FakeIds.ANDROID_ID, "0123456789abcdef");
        assertFalse(FakeIds.getBootValues(SECRET, KEPT, 0, "com.example.one", developer, set, none).containsKey(FakeIds.ANDROID_ID));
        assertEquals(one.get(FakeIds.DRM_ID),
                FakeIds.getBootValues(SECRET, KEPT, 0, "com.example.one", developer, none, set).get(FakeIds.DRM_ID));
        Map<String, String> boot = new HashMap<>();
        boot.put(FakeIds.ANDROID_ID + FakeIds.MODE, FakeIds.BOOT);
        assertNotEquals(FakeIds.getBootValues(SECRET, KEPT, 0, "com.example.one", developer, boot, none).get(FakeIds.ANDROID_ID),
                FakeIds.getBootValues(OTHER, KEPT, 0, "com.example.one", developer, boot, none).get(FakeIds.ANDROID_ID));
    }

    // A MEID new each boot like the IMEI is the IMEI without its check digit, like on phones with both, so the
    // manufacturer code and the type allocation code agree; else it is a hexadecimal MEID of its own
    @Test
    public void meidOfTheImei() {
        Map<String, String> none = new HashMap<>();
        for (String mode : new String[]{FakeIds.BOOT, FakeIds.BOOT_ALL}) {
            Map<String, String> global = new HashMap<>();
            global.put(FakeIds.IMEI + FakeIds.MODE, mode);
            global.put(FakeIds.MEID + FakeIds.MODE, mode);
            Map<String, String> values = FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, global, none);
            assertEquals(values.get(FakeIds.IMEI).substring(0, 14), values.get(FakeIds.MEID));
        }
        Map<String, String> global = new HashMap<>();
        global.put(FakeIds.IMEI + FakeIds.MODE, FakeIds.BOOT);
        global.put(FakeIds.MEID + FakeIds.MODE, FakeIds.BOOT_ALL);
        String meid = FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, global, none).get(FakeIds.MEID);
        assertTrue(meid, meid.matches("[A-F][0-9A-F]{13}"));
        assertFalse(FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, none, none).containsKey(FakeIds.MEID));
    }

    // The identifiers new each boot are of the developer, the signatures Android derives the Android ID of, so apps
    // signed with the same key agree like on Android, and apps of other developers do not; the DRM id, the location
    // key and the probe suffix stay of the app, and without signatures the identifiers are of the app too
    @Test
    public void identifiersAreOfTheDeveloper() {
        String developer = FakeIds.signer(new byte[][]{"certificate of a developer".getBytes(StandardCharsets.UTF_8)});
        String same = FakeIds.signer(new byte[][]{"certificate of a developer".getBytes(StandardCharsets.UTF_8)});
        String other = FakeIds.signer(new byte[][]{"certificate of another one".getBytes(StandardCharsets.UTF_8)});
        // The lengths are part of the digest, so two certificates are not one certificate of both
        assertNotEquals(FakeIds.signer(new byte[][]{"ab".getBytes(StandardCharsets.UTF_8), "c".getBytes(StandardCharsets.UTF_8)}),
                FakeIds.signer(new byte[][]{"a".getBytes(StandardCharsets.UTF_8), "bc".getBytes(StandardCharsets.UTF_8)}));
        assertNull(FakeIds.signer(new byte[0][]));

        Map<String, String> global = new HashMap<>();
        for (String key : FakeIds.KEYS)
            global.put(key + FakeIds.MODE, FakeIds.BOOT);
        Map<String, String> none = new HashMap<>();
        Map<String, String> one = FakeIds.getBootValues(SECRET, KEPT, 0, "com.example.one", developer, global, none);
        Map<String, String> two = FakeIds.getBootValues(SECRET, KEPT, 0, "com.example.two", same, global, none);
        Map<String, String> third = FakeIds.getBootValues(SECRET, KEPT, 0, "com.example.one", other, global, none);
        for (String key : FakeIds.KEYS) {
            assertEquals(key, one.get(key), two.get(key));
            assertNotEquals(key, one.get(key), third.get(key));
        }
        for (String key : new String[]{FakeIds.DRM_ID, FakeIds.LOCATION_KEY, FakeIds.PROBE_SUFFIX}) {
            assertNotEquals(key, one.get(key), two.get(key));
            assertEquals(key, one.get(key), third.get(key));
        }
        assertEquals(FakeIds.derive(SECRET, 0, "com.example.one", FakeIds.ANDROID_ID),
                FakeIds.getBootValues(SECRET, KEPT, 0, "com.example.one", null, global, none).get(FakeIds.ANDROID_ID));
    }

    @Test
    public void modes() {
        Map<String, String> global = new HashMap<>();
        Map<String, String> app = new HashMap<>();
        // Without settings all are fixed: the serial unknown, no IMEI and MEID, the Android ID of the developer
        assertEquals(FakeIds.FIXED, FakeIds.getMode(FakeIds.SERIAL, global, app));
        assertEquals(FakeIds.FIXED, FakeIds.getMode(FakeIds.ANDROID_ID, global, app));
        assertEquals(FakeIds.FIXED, FakeIds.getMode(FakeIds.IMEI, null, null));
        assertEquals(FakeIds.FIXED, FakeIds.getMode(FakeIds.MEID, global, app));
        assertFalse(FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, global, app).containsKey(FakeIds.IMEI));

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

    // Only the identifiers new each boot and the default Android ID get a value, of the app or the same for all apps
    // The seed of the DRM id is not shown in Fake values; it is of the app in every mode of the Android ID and stays
    @Test
    public void drmSeedIsOfTheApp() {
        Map<String, String> none = new HashMap<>();
        String one = FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, none, none).get(FakeIds.DRM_ID);
        assertEquals(32, one.length());
        assertNotEquals(one, FakeIds.getBootValues(SECRET, KEPT, 0, "com.other", null, none, none).get(FakeIds.DRM_ID));
        assertNotEquals(one, FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, none, none).get(FakeIds.ANDROID_ID));
        assertEquals(one, FakeIds.getBootValues(OTHER, KEPT, 0, "com.example", null, none, none).get(FakeIds.DRM_ID));
        assertFalse(FakeIds.isId(FakeIds.DRM_ID));

        // Whatever the mode of the Android ID: the same for all apps or new each boot, or a value set, which the app
        // reads, so it cannot compute the DRM id of it
        Map<String, String> all = new HashMap<>();
        all.put(FakeIds.ANDROID_ID + FakeIds.MODE, FakeIds.BOOT_ALL);
        Map<String, String> boot = new HashMap<>();
        boot.put(FakeIds.ANDROID_ID + FakeIds.MODE, FakeIds.BOOT);
        Map<String, String> fixed = new HashMap<>();
        fixed.put(FakeIds.ANDROID_ID + FakeIds.MODE, FakeIds.FIXED);
        fixed.put(FakeIds.ANDROID_ID, "44aa15730b248663");
        for (Map<String, String> global : Arrays.asList(all, boot, fixed)) {
            assertEquals(one, FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, global, none).get(FakeIds.DRM_ID));
            assertEquals(one, FakeIds.getBootValues(OTHER, KEPT, 0, "com.example", null, global, none).get(FakeIds.DRM_ID));
            assertNotEquals(one, FakeIds.getBootValues(SECRET, KEPT, 0, "com.other", null, global, none).get(FakeIds.DRM_ID));
        }
        assertNull(FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, fixed, none).get(FakeIds.ANDROID_ID));
    }

    // An Android ID fixed without a value is the default one, not zeros; a value for all apps applies to an app fixed
    // without one, like the settings merge (see XCore)
    @Test
    public void fixedAndroidIdWithoutValueIsOfTheApp() {
        Map<String, String> none = new HashMap<>();
        Map<String, String> fixed = new HashMap<>();
        fixed.put(FakeIds.ANDROID_ID + FakeIds.MODE, FakeIds.FIXED);
        String one = FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, fixed, none).get(FakeIds.ANDROID_ID);
        assertTrue(one, one.matches("[0-9a-f]{16}"));
        assertEquals(one, FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, none, none).get(FakeIds.ANDROID_ID));
        assertNotEquals(one, FakeIds.getBootValues(SECRET, KEPT, 0, "com.other", null, fixed, none).get(FakeIds.ANDROID_ID));
        assertEquals(one, FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, none, fixed).get(FakeIds.ANDROID_ID));

        Map<String, String> global = new HashMap<>();
        global.put(FakeIds.ANDROID_ID, "44aa15730b248663");
        assertNull(FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, global, fixed).get(FakeIds.ANDROID_ID));
        // Only the Android ID: the serial fixed without a value stays unknown, like Build.getSerial gives apps
        Map<String, String> serial = new HashMap<>();
        serial.put(FakeIds.SERIAL + FakeIds.MODE, FakeIds.FIXED);
        assertNull(FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, serial, none).get(FakeIds.SERIAL));
    }

    // The key of the location offsets is of each app, whatever the modes, and not shown in Fake values
    @Test
    public void locationKeyIsOfEachApp() {
        Map<String, String> fixed = new HashMap<>();
        fixed.put(FakeIds.ANDROID_ID + FakeIds.MODE, FakeIds.FIXED);
        String one = FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, fixed, fixed).get(FakeIds.LOCATION_KEY);
        assertEquals(32, one.length());
        assertNotEquals(one, FakeIds.getBootValues(SECRET, KEPT, 0, "com.other", null, fixed, fixed).get(FakeIds.LOCATION_KEY));
        assertNotEquals(one, FakeIds.getBootValues(SECRET, KEPT, 1, "com.example", null, fixed, fixed).get(FakeIds.LOCATION_KEY));
        assertFalse(FakeIds.isId(FakeIds.LOCATION_KEY));
    }

    // The suffix of the names package_probe looks hidden apps up under is of each app, the same for all its processes
    // during a boot, whatever the modes, and has the form of the random one a process makes without the service
    @Test
    public void probeSuffixIsOfEachApp() {
        Map<String, String> fixed = new HashMap<>();
        fixed.put(FakeIds.ANDROID_ID + FakeIds.MODE, FakeIds.FIXED);
        String one = FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, fixed, fixed).get(FakeIds.PROBE_SUFFIX);
        assertTrue(one, one.matches("\\.x[0-9]{9}"));
        assertEquals(one, FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, new HashMap<>(), new HashMap<>())
                .get(FakeIds.PROBE_SUFFIX));
        assertNotEquals(one, FakeIds.getBootValues(SECRET, KEPT, 0, "com.other", null, fixed, fixed).get(FakeIds.PROBE_SUFFIX));
        assertNotEquals(one, FakeIds.getBootValues(SECRET, KEPT, 1, "com.example", null, fixed, fixed).get(FakeIds.PROBE_SUFFIX));
        assertFalse(FakeIds.isId(FakeIds.PROBE_SUFFIX));
    }

    @Test
    public void bootValues() {
        Map<String, String> global = new HashMap<>();
        global.put(FakeIds.MEID + FakeIds.MODE, FakeIds.BOOT_ALL);
        global.put(FakeIds.IMEI + FakeIds.MODE, FakeIds.BOOT);
        Map<String, String> app = new HashMap<>();
        app.put(FakeIds.IMEI + FakeIds.MODE, FakeIds.FIXED);

        Map<String, String> one = FakeIds.getBootValues(SECRET, KEPT, 0, "com.example", null, global, app);
        Map<String, String> other = FakeIds.getBootValues(SECRET, KEPT, 0, "com.other", null, global, new HashMap<>());
        assertFalse(one.containsKey(FakeIds.SERIAL));
        assertFalse(one.containsKey(FakeIds.IMEI));
        assertTrue(other.containsKey(FakeIds.IMEI));
        assertNotEquals(one.get(FakeIds.ANDROID_ID), other.get(FakeIds.ANDROID_ID));
        assertEquals(one.get(FakeIds.MEID), other.get(FakeIds.MEID));
        assertEquals(FakeIds.derive(KEPT, 0, "com.example", FakeIds.ANDROID_ID), one.get(FakeIds.ANDROID_ID));
    }
}
