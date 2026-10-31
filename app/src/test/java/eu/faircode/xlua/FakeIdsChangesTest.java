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

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// What Fake values writes for an identifier, its value and its mode, see FakeIds.putChanges
public class FakeIdsChangesTest {
    private static final String KEY = FakeIds.IMEI;
    private static final String MODE = KEY + FakeIds.MODE;

    // Like ActivityValues.getSavedMode
    private static Map<String, String> changes(boolean global, String chosen, String value, Map<String, String> values) {
        String saved = (global ? FakeIds.getMode(KEY, values, null) : FakeIds.getOwnMode(KEY, values));
        Map<String, String> changed = new HashMap<>();
        FakeIds.putChanges(changed, KEY, chosen, saved == null ? FakeIds.INHERIT : saved, value, values);
        return changed;
    }

    // Clearing a value fixed only because it was set keeps it fixed, else it would be new each boot
    @Test
    public void clearingImplicitlyFixedKeepsFixed() {
        Map<String, String> values = new HashMap<>();
        values.put(KEY, "123");
        Map<String, String> changed = changes(true, FakeIds.FIXED, null, values);
        assertEquals(2, changed.size());
        assertTrue(changed.containsKey(KEY));
        assertEquals(null, changed.get(KEY));
        assertEquals(FakeIds.FIXED, changed.get(MODE));

        // With a saved mode, the mode stays as it is
        values.put(MODE, FakeIds.FIXED);
        changed = changes(true, FakeIds.FIXED, null, values);
        assertEquals(1, changed.size());
        assertTrue(changed.containsKey(KEY));
    }

    // Like all apps removes the value of the app and its mode
    @Test
    public void inheritRemovesOwnValue() {
        Map<String, String> values = new HashMap<>();
        values.put(KEY, "123");
        Map<String, String> changed = changes(false, FakeIds.INHERIT, "123", values);
        assertEquals(2, changed.size());
        assertTrue(changed.containsKey(KEY));
        assertEquals(null, changed.get(KEY));
        assertTrue(changed.containsKey(MODE));
        assertEquals(null, changed.get(MODE));

        // Nothing to write when the app already takes the mode of all apps
        assertTrue(changes(false, FakeIds.INHERIT, null, new HashMap<>()).isEmpty());
    }

    // A value hidden behind a mode new each boot is still saved
    @Test
    public void valueBehindBootModeCompared() {
        Map<String, String> values = new HashMap<>();
        values.put(KEY, "123");
        values.put(MODE, FakeIds.BOOT);
        Map<String, String> changed = changes(false, FakeIds.BOOT, "456", values);
        assertEquals(1, changed.size());
        assertEquals("456", changed.get(KEY));
        assertTrue(changes(false, FakeIds.BOOT, "123", values).isEmpty());
    }

    // A value without modes, like the model, writes only its value
    @Test
    public void valueWithoutModes() {
        Map<String, String> changed = new HashMap<>();
        FakeIds.putChanges(changed, "value.model", null, null, "Pixel", new HashMap<>());
        assertEquals(1, changed.size());
        assertEquals("Pixel", changed.get("value.model"));
    }
}
