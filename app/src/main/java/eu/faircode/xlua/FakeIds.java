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

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// The fake identifiers of Fake values, which are fixed, the value set or the default, or new each boot: a value of
// its own for each developer, like Android gives the Android ID of the signing key, or the same for all apps, which
// the service derives from a secret of its own which is new on each boot (see XProvider.getBootValues), so apps
// cannot tell the values of other apps. The default Android ID, which every device has, is of each developer too,
// from a secret the service keeps, so it stays the same like the one Android keeps until a factory reset.
final class FakeIds {
    static final String SERIAL = "value.serial";
    static final String ANDROID_ID = "value.android_id";
    static final String IMEI = "value.imei";
    static final String MEID = "value.meid";
    static final String[] KEYS = {SERIAL, ANDROID_ID, IMEI, MEID};
    // Of the service only, not in Fake values: the seed of the fake DRM id, of each app, which stays like the Android ID
    static final String DRM_ID = "value.drm_id";
    // Of the service only too: the key of the offsets of fake locations (location.lua), of each app, new each boot like
    // the offsets of the coarse locations of Android, so apps cannot compute them; also of the offset of the sequence
    // number of the battery (intent_createfromparcel.lua)
    static final String LOCATION_KEY = "location.key";
    // Of the service only too: the suffix of the names under which package_probe.lua looks up hidden apps, of each app,
    // new each boot, so every process of the app uses the same names, like for a PendingIntent another process or a
    // later start of the app looks up again
    static final String PROBE_SUFFIX = "package_probe.suffix";

    // The setting of the mode of a value is its name with this suffix
    static final String MODE = ".mode";

    // The setting a mode setting is of, the setting itself for the others
    static String baseKey(String key) {
        return (key.endsWith(MODE) ? key.substring(0, key.length() - MODE.length()) : key);
    }
    static final String FIXED = "fixed";
    static final String BOOT = "boot"; // A value of its own for each app
    static final String BOOT_ALL = "boot_all"; // The same value for all apps

    static final String INHERIT = "inherit"; // An app takes the mode of all apps, see ActivityValues

    private FakeIds() {
    }

    // What Fake values writes for a value: the value entered when it differs from the saved one, unless the app takes
    // the mode of all apps, and the mode chosen when it differs from the saved mode; chosen is null without modes
    static void putChanges(Map<String, String> changed, String key, String chosen, String savedMode,
                           String value, Map<String, String> values) {
        if (!INHERIT.equals(chosen) && !Objects.equals(value, values.get(key))) {
            changed.put(key, value);
            // A value fixed only because it was set stays fixed, with its default, when it is cleared
            if (value == null && FIXED.equals(chosen) && values.get(key + MODE) == null)
                changed.put(key + MODE, FIXED);
        }
        if (chosen != null && !chosen.equals(savedMode)) {
            changed.put(key + MODE, INHERIT.equals(chosen) ? null : chosen);
            if (INHERIT.equals(chosen) && values.get(key) != null)
                changed.put(key, null);
        }
    }

    static boolean isId(String key) {
        for (String id : KEYS)
            if (id.equals(key))
                return true;
        return false;
    }

    // The mode saved for all apps, with its default, or saved for an app, INHERIT for none, see ActivityValues
    static String getSavedMode(String key, Map<String, String> values, boolean global) {
        if (global)
            return getMode(key, values, null);
        String own = getOwnMode(key, values);
        return (own == null ? INHERIT : own);
    }

    // The mode of an app, else of all apps; a value set without a mode is fixed, like before there were modes.
    // Without either all are fixed at their default: the serial unknown like Build.getSerial gives apps, no IMEI and
    // MEID like a device without a modem, and an Android ID of each developer which stays, see getBootValues.
    static String getMode(String key, Map<String, String> global, Map<String, String> app) {
        String mode = getOwnMode(key, app);
        if (mode == null)
            mode = getOwnMode(key, global);
        return (mode == null ? FIXED : mode);
    }

    // The mode of one scope, null when it has none
    static String getOwnMode(String key, Map<String, String> settings) {
        if (settings == null)
            return null;
        String mode = settings.get(key + MODE);
        if (FIXED.equals(mode) || BOOT.equals(mode) || BOOT_ALL.equals(mode))
            return mode;
        return (settings.get(key) == null ? null : FIXED);
    }

    // The values the service derives for an app, by name, new each boot of the secret of the boot, the default Android
    // ID of the kept one; the identifiers are of the developer (see signer), which apps sharing a user id have too,
    // else of the app when its signatures were never read; the DRM id is of the app, like the one of Widevine
    static Map<String, String> getBootValues(byte[] secret, byte[] kept, int userid, String packageName,
                                             String developer, Map<String, String> global, Map<String, String> app) {
        Map<String, String> result = new HashMap<>();
        String scope = (developer == null ? packageName : developer);
        for (String key : KEYS) {
            String mode = getMode(key, global, app);
            if (BOOT.equals(mode))
                result.put(key, derive(secret, userid, scope, key));
            else if (BOOT_ALL.equals(mode))
                result.put(key, derive(secret, userid, null, key));
        }
        // A MEID new each boot like the IMEI is the IMEI without its check digit, like the MEID of phones with both,
        // so the manufacturer code and the type allocation code agree
        String meid = getMode(MEID, global, app);
        if (!FIXED.equals(meid) && meid.equals(getMode(IMEI, global, app)))
            result.put(MEID, result.get(IMEI).substring(0, 14));
        // An Android ID fixed without a value, of the app or of all apps, which apps would get as their settings are
        // merged, is the default: every device has one, of the developer, the same after each boot
        if (FIXED.equals(getMode(ANDROID_ID, global, app)) && app.get(ANDROID_ID) == null && global.get(ANDROID_ID) == null)
            result.put(ANDROID_ID, derive(kept, userid, scope, ANDROID_ID));
        // The seed of the DRM id is of the app in every mode of the Android ID and stays, like the id of Widevine: made of
        // the Android ID, which the app reads, the app could compute the DRM id and tell it is fake, and one for all apps
        // would link apps which stock never links
        result.put(DRM_ID, derive(kept, userid, packageName, DRM_ID));
        result.put(LOCATION_KEY, derive(secret, userid, packageName, LOCATION_KEY));
        result.put(PROBE_SUFFIX, derive(secret, userid, packageName, PROBE_SUFFIX));
        return result;
    }

    // The developer of an app: a digest of its signatures with their lengths, which Android derives the Android ID
    // of, so apps signed with the same key get the same identifiers; null without signatures
    static String signer(byte[][] signatures) {
        if (signatures == null || signatures.length == 0)
            return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] signature : signatures) {
                digest.update(ByteBuffer.allocate(4).putInt(signature.length).array());
                digest.update(signature);
            }
            return "signer:" + digits(new BigInteger(1, digest.digest()), 16, 64);
        } catch (Throwable ex) {
            throw new IllegalStateException(ex);
        }
    }

    // A value which looks random, the same for the same secret, user, scope (an app or a developer) and name; for all
    // apps without a scope. Its characters are the bits of the HMAC itself, like the Android ID of Android, not the
    // output of a generator seeded with it, whose 48 bits of state an app could find back.
    static String derive(byte[] secret, int userid, String scope, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return format(key, mac.doFinal((userid + "/" + (scope == null ? "" : scope) + "/" + key)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable ex) {
            throw new IllegalStateException(ex);
        }
    }

    // A value in the format of the identifier, like the randomize button of Fake values gives
    static String generate(String key, Random random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return format(key, bytes);
    }

    // A value in the format of the identifier made of 256 random bits
    private static String format(String key, byte[] bytes) {
        BigInteger bits = new BigInteger(1, bytes);
        switch (key) {
            case SERIAL:
                return digits(bits, 36, 12).toUpperCase(Locale.ROOT);
            case ANDROID_ID:
                return digits(bits, 16, 16);
            case DRM_ID:
            case LOCATION_KEY:
                return digits(bits, 16, 32);
            case IMEI:
                // The reporting body of most phones, 12 digits and a Luhn check digit
                // shortcut: the 6 digits after the reporting body are random, so the type allocation code is
                // of no model, upgrade if a fake IMEI which is set or new each boot must pass a lookup of its model
                String imei = "35" + digits(bits, 10, 12);
                return imei + luhn(imei);
            case MEID:
                // Hexadecimal MEIDs start with a regional code of A0 to FF
                BigInteger[] meid = bits.divideAndRemainder(BigInteger.valueOf(6));
                return "ABCDEF".charAt(meid[1].intValue()) + digits(meid[0], 16, 13).toUpperCase(Locale.ROOT);
            case PROBE_SUFFIX:
                return ".x" + digits(bits, 10, 9);
            default:
                return null;
        }
    }

    // The last digits of a number, with leading zeros: of 256 random bits all but evenly distributed
    private static String digits(BigInteger number, int radix, int length) {
        BigInteger limit = BigInteger.valueOf(radix).pow(length);
        return number.mod(limit).add(limit).toString(radix).substring(1);
    }

    static int luhn(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 0) {
                d *= 2;
                if (d > 9)
                    d -= 9;
            }
            sum += d;
        }
        return (10 - sum % 10) % 10;
    }
}
