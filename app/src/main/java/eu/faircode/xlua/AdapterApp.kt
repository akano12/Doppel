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

    Copyright 2017-2019 Marcel Bokhorst (M66B)
    Copyright 2026 akano12
 */

package eu.faircode.xlua

import android.text.format.DateUtils
import java.text.Collator
import java.util.HashSet

// The parts of AdapterApp shared with the Compose list and its unit tests
internal class AdapterApp private constructor() {
    enum class Filter { USER, SYSTEM, ALL }
    enum class Section { ATTENTION, NEW, RECENT, ALL }
    enum class Sort { NAME, RECENT, INSTALLED }

    class Row {
        internal lateinit var app: XApp
        @JvmField var groups: Int = 0
        @JvmField var restricted: Int = 0
        @JvmField var used: Long = -1
        @JvmField var usedGroup: String? = null
        @JvmField var assigned: Long = -1
        @JvmField var error: Boolean = false
        @JvmField var unscoped: Boolean = false

        private var haystack: Ui.Haystack? = null

        fun matches(query: Ui.Query): Boolean {
            if (haystack == null)
                haystack = Ui.Haystack(app.packageName, app.label)
            return haystack!!.has(query)
        }

        fun getSection(now: Long): Section {
            if (error)
                return Section.ATTENTION
            if (!app.system && restricted == 0 && groups > 0 && app.installed > 0 && now - app.installed < INSTALLED)
                return Section.NEW
            val recent = getRecent()
            if (recent > 0 && now - recent < RECENT)
                return Section.RECENT
            return Section.ALL
        }

        fun getRecent(): Long = if (restricted == 0) used else maxOf(used, assigned)

        fun isRestricted(): Boolean = restricted > 0 && !unscoped

        fun canRestrictAll(): Boolean {
            return !error && restricted == 0 && groups > 0 && !app.isSystemServer()
        }

        companion object {
            @JvmStatic
            fun summarize(app: XApp, hooks: List<XHook>, collection: List<String>): Row {
                val row = Row()
                row.app = app
                val groups = HashSet<String>()
                for (hook in hooks)
                    if (hook.isAvailable(app.packageName, collection))
                        groups.add(hook.group)
                row.groups = groups.size

                val restricted = HashSet<String>()
                for (assignment in app.assignments) {
                    if (groups.contains(assignment.group))
                        restricted.add(assignment.group)
                    if (assignment.exception != null)
                        row.error = true
                    if (assignment.restricted && assignment.used > row.used) {
                        row.used = assignment.used
                        row.usedGroup = assignment.group
                    }
                    row.assigned = maxOf(row.assigned, assignment.assigned)
                }
                row.restricted = restricted.size
                return row
            }
        }
    }

    class Summary {
        @JvmField var total: Int = 0
        @JvmField var restricted: Int = 0
        @JvmField var review: Int = 0
        @JvmField var recent: Int = 0

        val percent: Int
            get() = if (total == 0) 0 else Math.round(100f * restricted / total)

        companion object {
            @JvmStatic
            fun of(rows: List<Row>, filter: Filter, now: Long): Summary {
                val summary = Summary()
                for (row in rows) {
                    val app = row.app
                    val included = when (filter) {
                        Filter.USER -> !app.system
                        Filter.SYSTEM -> app.system
                        Filter.ALL -> true
                    }
                    if (included) {
                        summary.total++
                        if (row.isRestricted()) summary.restricted++
                        when (row.getSection(now)) {
                            Section.ATTENTION, Section.NEW -> summary.review++
                            Section.RECENT -> summary.recent++
                            Section.ALL -> Unit
                        }
                    }
                }
                return summary
            }
        }
    }

    companion object {
        private const val INSTALLED = 7 * DateUtils.DAY_IN_MILLIS
        private const val RECENT = DateUtils.DAY_IN_MILLIS

        @JvmStatic
        fun getRestricting(apps: List<XApp>, group: String): List<XApp> =
            apps.filter { it.getAssignments(group).isNotEmpty() }

        @JvmStatic
        fun setAssigned(app: XApp, available: List<XHook>, to: Set<String>) {
            val ids = available.mapTo(HashSet()) { it.id }
            val assignments = ArrayList<XAssignment>()
            for (assignment in app.assignments)
                if (!ids.contains(assignment.id) || to.contains(assignment.id))
                    assignments.add(assignment)
            for (hook in available) {
                val assignment = XAssignment(hook)
                if (to.contains(hook.id) && !assignments.contains(assignment))
                    assignments.add(assignment)
            }
            app.assignments = assignments
        }

        @JvmStatic
        fun comparator(sort: Sort, collator: Collator): Comparator<Row> {
            val byName = Comparator<Row> { r1, r2 ->
                collator.compare(r1.app.getLabel(), r2.app.getLabel())
            }
            return when (sort) {
                Sort.RECENT -> Comparator { r1, r2 ->
                    val order = r2.getRecent().compareTo(r1.getRecent())
                    if (order == 0) byName.compare(r1, r2) else order
                }
                Sort.INSTALLED -> Comparator { r1, r2 ->
                    val order = r2.app.installed.compareTo(r1.app.installed)
                    if (order == 0) byName.compare(r1, r2) else order
                }
                Sort.NAME -> byName
            }
        }
    }
}
