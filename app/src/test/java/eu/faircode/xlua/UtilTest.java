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

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

// Lists separated by commas, like the collections, the excluded packages and the types of parameters
public class UtilTest {
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
}
