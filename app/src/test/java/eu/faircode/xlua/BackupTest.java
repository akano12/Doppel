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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// What a backup of an older version restores into the definitions of this version (Backup.renamed, Backup.completing)
public class BackupTest {
    private static final String PREFIX = Util.COLLECTION_DEFAULT + ".";
    private static final List<String> COLLECTION = Collections.singletonList(Util.COLLECTION_DEFAULT);

    // Renamed definitions get the restrictions of their former name, as the database upgrade gives them
    @Test
    public void renamedDefinitionsGetTheirNameNow() {
        assertEquals(PREFIX + "TelephonyManager.getImei", Backup.renamed(PREFIX + "TelephonyManager/getImei"));
        for (String[] rename : XDatabase.RENAMED) {
            String now = Backup.renamed(PREFIX + rename[0]);
            assertFalse(rename[0], now.equals(PREFIX + rename[0]));
            // Fully renamed, also when a name was renamed again later
            assertEquals(rename[0], now, Backup.renamed(now));
        }
    }

    // Other definitions keep their id, like custom ones of another collection
    @Test
    public void otherDefinitionsKeepTheirId() {
        assertEquals(PREFIX + "Location.createFromParcel", Backup.renamed(PREFIX + "Location.createFromParcel"));
        assertEquals("Custom.TelephonyManager/getImei", Backup.renamed("Custom.TelephonyManager/getImei"));
    }

    // Definitions added since the backup complete the groups it restricted, if the app can have them
    @Test
    public void newerDefinitionsCompleteRestrictedGroups() throws Exception {
        Map<String, XHook> hooks = new HashMap<>();
        for (XHook hook : Arrays.asList(
                hook("Read.Identifiers", "Old.identifier", null, true),
                hook("Read.Identifiers", "New.identifier", null, true),
                hook("Read.Identifiers", "New.disabled", null, false),
                hook("Read.Identifiers", "New.excluded", "com\\\\.example\\\\.app", true),
                hook("Get.Location", "New.location", null, true)))
            hooks.put(hook.getId(), hook);
        List<String> newer = Arrays.asList(PREFIX + "New.identifier", PREFIX + "New.disabled",
                PREFIX + "New.excluded", PREFIX + "New.location", PREFIX + "New.unknown");
        Set<String> groups = new HashSet<>(Collections.singletonList("Read.Identifiers"));

        List<String> completing = Backup.completing(newer, groups, hooks, "com.example.app", COLLECTION);
        assertEquals(Collections.singletonList(PREFIX + "New.identifier"), completing);

        // Nothing for an app without restricted groups, nothing of a collection which is not enabled
        assertTrue(Backup.completing(newer, new HashSet<>(), hooks, "com.example.app", COLLECTION).isEmpty());
        assertTrue(Backup.completing(newer, groups, hooks, "com.example.app",
                Collections.singletonList("Other")).isEmpty());
    }

    // Unit tests run with an SDK version of 0, see the android.jar stubs, so the definitions are for all versions
    private static XHook hook(String group, String name, String exclude, boolean enabled) throws Exception {
        return XHook.fromJSON("{\"collection\": \"" + Util.COLLECTION_DEFAULT + "\", \"group\": \"" + group + "\"," +
                " \"name\": \"" + name + "\", \"author\": \"Test\", \"className\": \"android.location.Location\"," +
                " \"parameterTypes\": [], \"minSdk\": 0, \"enabled\": " + enabled + "," +
                (exclude == null ? "" : " \"excludePackages\": \"" + exclude + "\",") +
                " \"luaScript\": \"\"}");
    }
}
