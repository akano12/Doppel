/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.
 */

package eu.faircode.xlua;

import org.junit.Test;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// The sections of the Apps tab, see AdapterApp.Row.getSection
public class AppSectionsTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 60 * 60 * 1000L;
    private static final long DAY = 24 * HOUR;

    // A user app with 29 groups, installed long ago, without restrictions
    private static AdapterApp.Row row() {
        XApp app = new XApp();
        app.packageName = "com.example";
        app.uid = 10123;
        app.installed = NOW - 30 * DAY;
        app.assignments = new ArrayList<>();
        AdapterApp.Row row = new AdapterApp.Row();
        row.app = app;
        row.groups = 29;
        return row;
    }

    @Test
    public void failedHookNeedsAttention() {
        AdapterApp.Row row = row();
        row.restricted = 29;
        row.assigned = NOW - HOUR;
        row.error = true;
        assertEquals(AdapterApp.Section.ATTENTION, row.getSection(NOW));
    }

    @Test
    public void newAppWithoutRestrictions() {
        AdapterApp.Row row = row();
        row.app.installed = NOW - 6 * DAY;
        assertEquals(AdapterApp.Section.NEW, row.getSection(NOW));

        row.app.installed = NOW - 8 * DAY;
        assertEquals(AdapterApp.Section.ALL, row.getSection(NOW));
    }

    // System apps are installed when a device is set up
    @Test
    public void newSystemAppIsNotNew() {
        AdapterApp.Row row = row();
        row.app.installed = NOW - HOUR;
        row.app.system = true;
        assertEquals(AdapterApp.Section.ALL, row.getSection(NOW));
    }

    // Restricting a new app makes it restricted recently
    @Test
    public void restrictedNewApp() {
        AdapterApp.Row row = row();
        row.app.installed = NOW - HOUR;
        row.restricted = 29;
        row.assigned = NOW - HOUR;
        assertEquals(AdapterApp.Section.RECENT, row.getSection(NOW));
    }

    @Test
    public void recentForOneDay() {
        AdapterApp.Row row = row();
        row.restricted = 3;
        row.used = NOW - 23 * HOUR;
        assertEquals(AdapterApp.Section.RECENT, row.getSection(NOW));

        row.used = NOW - 25 * HOUR;
        assertEquals(AdapterApp.Section.ALL, row.getSection(NOW));

        // Assigned by an update or a restored backup
        row.assigned = -1;
        assertEquals(AdapterApp.Section.ALL, row.getSection(NOW));

        row.assigned = NOW - HOUR;
        assertEquals(AdapterApp.Section.RECENT, row.getSection(NOW));
        assertEquals(NOW - HOUR, row.getRecent());
    }

    // Once all restrictions are removed, only a recent use counts
    @Test
    public void unrestrictedAppIgnoresAssignedTime() {
        AdapterApp.Row row = row();
        row.assigned = NOW - HOUR;
        assertEquals(AdapterApp.Section.ALL, row.getSection(NOW));
    }

    private static XHook hook(String group, String name) throws Exception {
        return XHook.fromJSON("{\"collection\": \"Privacy\", \"group\": \"" + group + "\", \"name\": \"" + name + "\"," +
                " \"author\": \"Test\", \"className\": \"android.location.Location\", \"parameterTypes\": []," +
                " \"minSdk\": 1, \"luaScript\": \"\"}");
    }

    // The apps restricting a group, which a bulk toggle removes it from: those with a hook of the group, not of another
    @Test
    public void restrictingAppsHaveAHookOfTheGroup() throws Exception {
        XHook location = hook("Get.Location", "LocationManager.getLastKnownLocation");
        XHook camera = hook("Use.Camera", "Camera.open");
        XApp restricting = row().app;
        restricting.assignments.add(new XAssignment(location));
        XApp other = row().app;
        other.packageName = "com.example.other";
        other.assignments.add(new XAssignment(camera));
        List<XApp> apps = Arrays.asList(restricting, other);

        assertEquals(Collections.singletonList(restricting), FragmentApps.getRestricting(apps, "Get.Location"));
        assertTrue(FragmentApps.getRestricting(apps, "Read.Clipboard").isEmpty());
    }

    // The overview counts the apps of the filter: with restrictions, to review (new or failed) and recent
    @Test
    public void overviewCountsTheFilter() {
        AdapterApp.Row restricted = row();
        restricted.restricted = 29;
        restricted.used = NOW - HOUR;
        AdapterApp.Row fresh = row();
        fresh.app.installed = NOW - HOUR;
        AdapterApp.Row system = row();
        system.app.system = true;
        system.restricted = 3;
        AdapterApp.Row idle = row();
        List<AdapterApp.Row> rows = Arrays.asList(restricted, fresh, system, idle);

        AdapterApp.Summary user = AdapterApp.Summary.of(rows, AdapterApp.Filter.USER, NOW);
        assertEquals(3, user.total);
        assertEquals(1, user.restricted);
        assertEquals(1, user.review);
        assertEquals(1, user.recent);
        assertEquals(33, user.getPercent());

        AdapterApp.Summary all = AdapterApp.Summary.of(rows, AdapterApp.Filter.ALL, NOW);
        assertEquals(4, all.total);
        assertEquals(2, all.restricted);
        assertEquals(50, all.getPercent());
        assertEquals(0, AdapterApp.Summary.of(Collections.emptyList(), AdapterApp.Filter.ALL, NOW).getPercent());
    }

    // All apps by name, by the last restriction or restricted call with the apps without one last, or by install time
    @Test
    public void allAppsAreSorted() {
        Collator collator = Collator.getInstance(Locale.ENGLISH);
        AdapterApp.Row older = row();
        older.app.label = "Beta";
        older.restricted = 2;
        older.used = NOW - 2 * DAY;
        AdapterApp.Row newer = row();
        newer.app.label = "alpha";
        newer.app.installed = NOW - DAY;
        newer.restricted = 2;
        newer.used = NOW - DAY;
        AdapterApp.Row never = row();
        never.app.label = "Gamma";
        List<AdapterApp.Row> rows = new ArrayList<>(Arrays.asList(older, newer, never));

        Collections.sort(rows, AdapterApp.comparator(AdapterApp.Sort.NAME, collator));
        assertEquals(Arrays.asList(newer, older, never), rows);
        Collections.sort(rows, AdapterApp.comparator(AdapterApp.Sort.RECENT, collator));
        assertEquals(Arrays.asList(newer, older, never), rows);
        Collections.sort(rows, AdapterApp.comparator(AdapterApp.Sort.INSTALLED, collator));
        assertEquals(Arrays.asList(newer, older, never), rows);

        // Equal times by name
        never.app.installed = older.app.installed;
        Collections.sort(rows, AdapterApp.comparator(AdapterApp.Sort.INSTALLED, collator));
        assertEquals(Arrays.asList(newer, older, never), rows);
    }

    // Random IMEIs end with a Luhn check digit
    @Test
    public void luhnCheckDigit() {
        assertEquals(8, ActivityValues.luhn("49015420323751"));
        assertEquals(9, ActivityValues.luhn("35693803564380"));
    }
}
