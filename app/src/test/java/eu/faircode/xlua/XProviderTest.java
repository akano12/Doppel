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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// What hooked apps may ask the service without the permission of the app, see XProvider.APP_CALLS
public class XProviderTest {
    // A method added here can give any hooked app the data of other apps, like getAllSettings the fake values
    // set for each app: it must check the caller itself, like the ones listed do
    @Test
    public void hookedAppsAskForThemselvesOnly() {
        assertEquals(Collections.singleton("report"), XProvider.APP_CALLS);
        assertEquals(new HashSet<>(Arrays.asList("getAssignedHooks2", "getSettings", "getBootValues")),
                XProvider.APP_QUERIES);
    }

    // The built-in hooks an update or an upgrade of Android adds to the apps which restrict their group, see
    // assignNewBuiltins. A minSdk above the SDK version (0 in unit tests, see the android.jar stubs) stands for
    // a hook of a newer Android version.
    @Test
    public void newBuiltinsAfterAnUpgradeOfAndroid() throws Exception {
        List<XHook> builtins = Arrays.asList(hook("Now", android.os.Build.VERSION.SDK_INT, true), hook("Newer", android.os.Build.VERSION.SDK_INT + 1, true), hook("Off", android.os.Build.VERSION.SDK_INT, false));
        List<String> enabled = XProvider.enabledBuiltins(builtins);
        // A hook of a newer Android version cannot be assigned yet, so it is not known after this start
        assertEquals(Collections.singletonList(id("Now")), enabled);

        // The first start knows the hooks there are, nothing is new
        assertEquals(new HashSet<>(enabled), XProvider.knownBuiltins(enabled, null));

        // A marker of a former version, with all enabled hooks, adds nothing (and is rewritten, being another set)
        Set<String> known = XProvider.knownBuiltins(enabled, id("Now") + "," + id("Newer"));
        assertTrue(known.containsAll(enabled));

        // After the upgrade of Android the hook of the newer version is new, the hook known already is not
        List<String> upgraded = Arrays.asList(id("Now"), id("Newer"));
        Set<String> before = XProvider.knownBuiltins(enabled, String.join(",", enabled));
        assertEquals(Collections.singleton(id("Newer")), minus(upgraded, before));

        // Renamed hooks of a marker are known under their names of now
        assertTrue(XProvider.knownBuiltins(enabled, Util.COLLECTION_DEFAULT + ".TelephonyManager/getImei")
                .contains(Util.COLLECTION_DEFAULT + ".TelephonyManager.getImei"));
    }

    private static Set<String> minus(List<String> ids, Set<String> known) {
        Set<String> result = new HashSet<>(ids);
        result.removeAll(known);
        return result;
    }

    private static String id(String name) {
        return Util.COLLECTION_DEFAULT + "." + name;
    }

    private static XHook hook(String name, int minSdk, boolean enabled) throws Exception {
        return XHook.fromJSON("{\"collection\": \"" + Util.COLLECTION_DEFAULT + "\", \"group\": \"Test\"," +
                " \"name\": \"" + name + "\", \"author\": \"Test\", \"className\": \"android.location.Location\"," +
                " \"parameterTypes\": [], \"minSdk\": " + minSdk + ", \"enabled\": " + enabled + ", \"luaScript\": \"\"}");
    }
}
