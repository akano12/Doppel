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

package eu.faircode.xlua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Collator
import java.util.Locale

// The sections of the Apps tab, see AdapterApp.Row.getSection
class AppSectionsTest {
    private fun row(): AdapterApp.Row = AdapterApp.Row().apply {
        app = XApp().apply {
            packageName = "com.example"
            uid = 10123
            installed = NOW - 30 * DAY
            assignments = ArrayList()
        }
        groups = 29
    }

    @Test fun failedHookNeedsAttention() {
        val row = row().apply { restricted = 29; assigned = NOW - HOUR; error = true }
        assertEquals(AdapterApp.Section.ATTENTION, row.getSection(NOW))
    }

    @Test fun newAppWithoutRestrictions() {
        val row = row()
        row.app.installed = NOW - 6 * DAY
        assertEquals(AdapterApp.Section.NEW, row.getSection(NOW))
        row.app.installed = NOW - 8 * DAY
        assertEquals(AdapterApp.Section.ALL, row.getSection(NOW))
    }

    @Test fun newSystemAppIsNotNew() {
        val row = row()
        row.app.installed = NOW - HOUR
        row.app.system = true
        assertEquals(AdapterApp.Section.ALL, row.getSection(NOW))
    }

    @Test fun restrictedNewApp() {
        val row = row().apply { app.installed = NOW - HOUR; restricted = 29; assigned = NOW - HOUR }
        assertEquals(AdapterApp.Section.RECENT, row.getSection(NOW))
    }

    @Test fun recentForOneDay() {
        val row = row().apply { restricted = 3; used = NOW - 23 * HOUR }
        assertEquals(AdapterApp.Section.RECENT, row.getSection(NOW))
        row.used = NOW - 25 * HOUR
        assertEquals(AdapterApp.Section.ALL, row.getSection(NOW))
        row.assigned = -1
        assertEquals(AdapterApp.Section.ALL, row.getSection(NOW))
        row.assigned = NOW - HOUR
        assertEquals(AdapterApp.Section.RECENT, row.getSection(NOW))
        assertEquals(NOW - HOUR, row.getRecent())
    }

    @Test fun unrestrictedAppIgnoresAssignedTime() {
        val row = row().apply { assigned = NOW - HOUR }
        assertEquals(AdapterApp.Section.ALL, row.getSection(NOW))
    }

    private fun hook(group: String, name: String) = XHook.fromJSON(
        "{\"collection\": \"Privacy\", \"group\": \"$group\", \"name\": \"$name\"," +
                " \"author\": \"Test\", \"className\": \"android.location.Location\", \"parameterTypes\": []," +
                " \"minSdk\": 1, \"luaScript\": \"\"}"
    )

    @Test fun restrictingAppsHaveAHookOfTheGroup() {
        val location = hook("Get.Location", "LocationManager.getLastKnownLocation")
        val camera = hook("Use.Camera", "Camera.open")
        val restricting = row().app.apply { assignments.add(XAssignment(location)) }
        val other = row().app.apply { packageName = "com.example.other"; assignments.add(XAssignment(camera)) }
        val apps = listOf(restricting, other)
        assertEquals(listOf(restricting), AdapterApp.getRestricting(apps, "Get.Location"))
        assertTrue(AdapterApp.getRestricting(apps, "Read.Clipboard").isEmpty())
    }

    @Test fun assignedFollowsAChange() {
        val location = hook("Get.Location", "LocationManager.getLastKnownLocation")
        val camera = hook("Use.Camera", "Camera.open")
        val other = hook("Read.Clipboard", "ClipboardManager.getPrimaryClip")
        val app = row().app
        val kept = XAssignment(location).apply { used = NOW }
        app.assignments.add(kept)
        app.assignments.add(XAssignment(other))
        val available = listOf(location, camera)
        AdapterApp.setAssigned(app, available, setOf(location.id, camera.id))
        assertEquals(3, app.assignments.size)
        assertTrue(app.assignments.contains(XAssignment(camera)))
        assertEquals(NOW, app.assignments[app.assignments.indexOf(kept)].used)
        AdapterApp.setAssigned(app, available, emptySet())
        assertEquals(listOf(XAssignment(other)), app.assignments)
    }

    @Test fun overviewCountsTheFilter() {
        val restrictedRow = row().apply { restricted = 29; used = NOW - HOUR }
        val fresh = row().apply { app.installed = NOW - HOUR }
        val system = row().apply { app.system = true; restricted = 3 }
        val idle = row()
        val rows = listOf(restrictedRow, fresh, system, idle)
        val user = AdapterApp.Summary.of(rows, AdapterApp.Filter.USER, NOW)
        assertEquals(3, user.total)
        assertEquals(1, user.restricted)
        assertEquals(1, user.review)
        assertEquals(1, user.recent)
        assertEquals(33, user.percent)
        val all = AdapterApp.Summary.of(rows, AdapterApp.Filter.ALL, NOW)
        assertEquals(4, all.total)
        assertEquals(2, all.restricted)
        assertEquals(50, all.percent)
        assertEquals(0, AdapterApp.Summary.of(emptyList(), AdapterApp.Filter.ALL, NOW).percent)
    }

    @Test fun allAppsAreSorted() {
        val collator = Collator.getInstance(Locale.ENGLISH)
        val older = row().apply { app.label = "Beta"; restricted = 2; used = NOW - 2 * DAY }
        val newer = row().apply { app.label = "alpha"; app.installed = NOW - DAY; restricted = 2; used = NOW - DAY }
        val never = row().apply { app.label = "Gamma" }
        val rows = arrayListOf(older, newer, never)
        rows.sortWith(AdapterApp.comparator(AdapterApp.Sort.NAME, collator))
        assertEquals(listOf(newer, older, never), rows)
        rows.sortWith(AdapterApp.comparator(AdapterApp.Sort.RECENT, collator))
        assertEquals(listOf(newer, older, never), rows)
        rows.sortWith(AdapterApp.comparator(AdapterApp.Sort.INSTALLED, collator))
        assertEquals(listOf(newer, older, never), rows)
        never.app.installed = older.app.installed
        rows.sortWith(AdapterApp.comparator(AdapterApp.Sort.INSTALLED, collator))
        assertEquals(listOf(newer, older, never), rows)
    }

    companion object {
        private const val NOW = 1_800_000_000_000L
        private const val HOUR = 60 * 60 * 1000L
        private const val DAY = 24 * HOUR
    }
}
