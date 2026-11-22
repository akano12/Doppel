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

import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// Lists separated by commas, like the collections, the excluded packages and the types of parameters
public class UtilTest {
    @Rule
    public final SdkRule sdk = new SdkRule();

    @Test
    public void splitTrimsAndSkipsEmptyItems() {
        assertEquals(Arrays.asList("a", "b c", "d"), Util.split(" a, b c ,,d, "));
        assertEquals(Collections.emptyList(), Util.split(" , "));
        assertEquals(Collections.emptyList(), Util.split(""));
    }

    // Without a setting, the default collection; with one, its collections
    @Test
    public void collections() {
        assertEquals(Collections.singletonList(Util.COLLECTION_DEFAULT), Util.parseCollection(null));
        assertEquals(Arrays.asList("Privacy", "Fif"), Util.parseCollection("Privacy, Fif"));
    }

    // A file chosen by the user is read up to a size, an error says what to do beyond it; a stream of an asset is not limited
    @Test
    public void filesAreReadUpToTheirLimit() throws Exception {
        byte[] text = "{\"format\": 1, \"x\": \"\u00e9\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(new String(text, java.nio.charset.StandardCharsets.UTF_8),
                Util.readText(new java.io.ByteArrayInputStream(text), text.length));
        assertEquals("", Util.readText(new java.io.ByteArrayInputStream(new byte[0]), 0));
        try {
            Util.readText(new java.io.ByteArrayInputStream(text), text.length - 1);
            fail();
        } catch (Util.FileTooLargeException expected) {
        }
        // Past the limit in the last of many reads, and without the whole file ever being read
        java.io.InputStream endless = new java.io.InputStream() {
            long read = 0;

            @Override
            public int read() {
                read++;
                if (read > 10L * Util.MAX_FILE)
                    throw new AssertionError("Read past the limit");
                return 'x';
            }
        };
        try {
            Util.readText(endless, Util.MAX_FILE);
            fail();
        } catch (Util.FileTooLargeException expected) {
        }
        // The limit is for a backup, not a script of the app, which has none
        assertEquals(1, Util.readText(new java.io.ByteArrayInputStream(new byte[]{'x'})).length());
        assertTrue(Util.MAX_FILE > 8 * 1024 * 1024);
    }

    // Android 13 and later ask the package manager with typed flags, earlier versions with an int; with no package manager
    // to answer, the signature of the failed call, in the message of the NullPointerException, shows the branch taken
    @Test
    public void packageFlagsAreTypedFromAndroid13() throws Exception {
        for (int version : new int[]{28, 32, 33, 37}) {
            sdk.set(version);
            try {
                Util.getPackageInfo(null, "app", 0);
                fail();
            } catch (NullPointerException ex) {
                assertEquals(String.valueOf(version), version >= 33, ex.getMessage().contains("PackageInfoFlags"));
            }
        }
    }
}
