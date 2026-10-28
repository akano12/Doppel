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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// The fake identifiers of Fake values, which are fixed, the value set or the default, or new each boot: a value of
// its own for each app, or the same for all apps, which the service derives from a secret of its own which is new
// on each boot (see XProvider.getBootValues), so apps cannot tell the values of other apps
final class FakeIds {
    static final String SERIAL = "value.serial";
    static final String ANDROID_ID = "value.android_id";
    static final String IMEI = "value.imei";
    static final String MEID = "value.meid";
    static final String[] KEYS = {SERIAL, ANDROID_ID, IMEI, MEID};

    // Always on, not a Fake value: a per-app offset in ms added to the time since boot, so apps cannot link
    // by the boot time (now - elapsedRealtime), which is otherwise the same for all apps. New each boot.
    static final String BOOT_OFFSET = "value.boot_offset";

    // The setting of the mode of a value is its name with this suffix
    static final String MODE = ".mode";
    static final String FIXED = "fixed";
    static final String BOOT = "boot"; // A value of its own for each app
    static final String BOOT_ALL = "boot_all"; // The same value for all apps

    private FakeIds() {
    }

    static boolean isId(String key) {
        for (String id : KEYS)
            if (id.equals(key))
                return true;
        return false;
    }

    // The mode of an app, else of all apps; a value set without a mode is fixed, like before there were modes.
    // Without either the serial is fixed, its default is unknown like Build.getSerial, the others are new each boot.
    static String getMode(String key, Map<String, String> global, Map<String, String> app) {
        String mode = getOwnMode(key, app);
        if (mode == null)
            mode = getOwnMode(key, global);
        if (mode == null)
            mode = (SERIAL.equals(key) ? FIXED : BOOT);
        return mode;
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

    // The values new each boot for an app, by name
    static Map<String, String> getBootValues(byte[] secret, int userid, String packageName,
                                             Map<String, String> global, Map<String, String> app) {
        Map<String, String> result = new HashMap<>();
        for (String key : KEYS) {
            String mode = getMode(key, global, app);
            if (BOOT.equals(mode))
                result.put(key, derive(secret, userid, packageName, key));
            else if (BOOT_ALL.equals(mode))
                result.put(key, derive(secret, userid, null, key));
        }
        result.put(BOOT_OFFSET, derive(secret, userid, packageName, BOOT_OFFSET));
        return result;
    }

    // A value which looks random, the same for the same secret, user, app and name; for all apps without an app
    static String derive(byte[] secret, int userid, String packageName, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] hash = mac.doFinal((userid + "/" + (packageName == null ? "" : packageName) + "/" + key)
                    .getBytes(StandardCharsets.UTF_8));
            return generate(key, new Random(ByteBuffer.wrap(hash).getLong()));
        } catch (Throwable ex) {
            throw new IllegalStateException(ex);
        }
    }

    // A value in the format of the identifier, like the randomize button of Fake values gives
    static String generate(String key, Random random) {
        switch (key) {
            case SERIAL:
                return randomString(random, "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", 12);
            case ANDROID_ID:
                return randomString(random, "0123456789abcdef", 16);
            case IMEI:
                // 14 digits and a Luhn check digit
                String digits = randomString(random, "0123456789", 14);
                return digits + luhn(digits);
            case MEID:
                return randomString(random, "0123456789ABCDEF", 14);
            case BOOT_OFFSET:
                // 10 to 120 minutes in ms; wide enough that many devices share each boot time
                return Long.toString(600000L + random.nextInt(6600001));
            default:
                return null;
        }
    }

    private static String randomString(Random random, String alphabet, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++)
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return sb.toString();
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
