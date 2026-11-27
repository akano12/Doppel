@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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

import android.app.Activity
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// The groups of an app from the Apps tab, like on the screen of the app but without its hooks: a switch per group
// by section, all of a section, Restrict all, and the screen of the app for single hooks and fake values.
// A group is restricted at once; closing the sheet tells the host what was assigned before, for Undo.
@Composable
internal fun GroupsSheet(
    activity: Activity,
    app: XApp,
    hooks: List<XHook>,
    collection: List<String>,
    unscoped: Boolean,
    onBackground: (Ui.Task) -> Unit,
    onChanged: (before: Set<String>, after: Set<String>, available: List<XHook>) -> Unit,
    onMore: () -> Unit,
    onClosed: () -> Unit
) {
    val context = LocalContext.current
    val model = remember(app, hooks, collection) { sheetModel(context, app, hooks, collection) }
    var assigned by remember(model) { mutableStateOf(model.assigned) }
    val before = remember(model) { model.assigned }
    // Of a change, shown when the sheet closes, since messages of the screen are below it
    var failure by remember(model) { mutableStateOf<String?>(null) }
    // See assign
    val changes = remember(model) { intArrayOf(0) }
    // Sections which are partly restricted show their groups, the rest start folded
    val folds = remember(model) {
        val open = mutableStateMapOf<Int, Boolean>()
        for (section in model.sections) {
            val restricted = section.groups.count { group -> group.hooks.any { it.id in model.assigned } }
            open[section.title] = restricted > 0 && restricted < section.groups.size
        }
        open
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Without telling the host, like when its view is gone: what changed was saved already
    fun close(more: Boolean) {
        // No Undo behind the app screen, which shows the changes; the list reloads when it is back
        if (more) {
            onMore()
            return
        }
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (assigned != before)
                onChanged(before, assigned.toSet(), model.available)
            failure?.let { Ui.message(activity, it) }
            onClosed()
        }
    }

    // One group, a section, or all groups at once: the sheet shows the change at once and the save follows, which is
    // undone here when it fails, and always replaced by what was saved, so a later change is not overwritten by an
    // earlier one
    fun assign(hooks: List<XHook>, value: Boolean) {
        val changed = hooks.filter { (it.id in assigned) != value }
        if (changed.isEmpty())
            return
        val ids = changed.map { it.id }
        assigned = if (value) assigned + ids else assigned - ids.toSet()
        val started = ++changes[0]
        val appContext = activity.applicationContext
        onBackground(Ui.Task {
            try {
                Data.assign(appContext, app, changed, value)
            } catch (ex: Throwable) {
                // Not left to the reload, which can fail too: Done would report a change which was not stored
                activity.runOnUiThread {
                    assigned = if (value) assigned - ids.toSet() else assigned + ids
                    failure = activity.getString(R.string.msg_error, Ui.describe(activity, ex))
                }
                throw ex
            } finally {
                // Also after an error, else the switches show the change which was not stored; a later change
                // reloads after itself
                val now = Data.getApp(appContext, app.packageName, app.uid)
                activity.runOnUiThread {
                    if (now != null && started == changes[0])
                        assigned = now.assignments.mapTo(HashSet()) { it.id }
                }
            }
        })
    }

    val total = model.groups.size
    val restricted = model.groups.count { group -> group.hooks.any { it.id in assigned } }
    val complete = model.available.all { it.id in assigned }

    ModalBottomSheet(
        onDismissRequest = { close(false) },
        sheetState = sheetState
    ) {
        // Most of the window, so the sections show; the list scrolls inside. The window can change size while the
        // sheet is open, like after a rotation, which does not recreate the screen, else Done would be cut off
        BoxWithConstraints {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(maxHeight * 0.85f)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(app.packageName, app.icon, 40.dp)
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Text(stringResource(R.string.title_groups_of), style = metaStyle())
                        Text(
                            app.getLabel(),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        stringResource(R.string.title_fraction, restricted, total),
                        Modifier
                            .padding(start = 12.dp)
                            .clearAndSetSemantics {},
                        style = MaterialTheme.typography.headlineSmall
                    )
                }

                // A part of the bar for each section, as wide as its groups, filled as far as they are restricted
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 12.dp, end = 20.dp)
                        .clearAndSetSemantics {},
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    for (section in model.sections) {
                        val count = section.groups.size
                        if (count == 0)
                            continue
                        val sectionRestricted = section.groups.count { group ->
                            group.hooks.any { it.id in assigned }
                        }
                        LinearProgressIndicator(
                            progress = { sectionRestricted / count.toFloat() },
                            modifier = Modifier
                                .weight(count.toFloat())
                                .height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {}
                        )
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 4.dp, end = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val countText = when {
                        restricted == 0 -> stringResource(R.string.title_not_restricted)
                        unscoped -> stringResource(R.string.title_not_in_scope_hint)
                        complete -> stringResource(R.string.title_all_groups_restricted)
                        else -> pluralStringResource(
                            R.plurals.title_groups_let_through,
                            total - restricted,
                            total - restricted
                        )
                    }
                    val described = stringResource(
                        R.string.title_described,
                        pluralStringResource(R.plurals.title_groups_restricted, total, restricted, total),
                        countText
                    )
                    Text(
                        countText,
                        Modifier
                            .weight(1f)
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = described
                            },
                        style = metaStyle()
                    )
                    OutlinedButton(
                        onClick = {
                            if (complete)
                                assign(model.available, false)
                            else
                                Ui.restrictAll(activity, app) { assign(model.available, true) }
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            stringResource(
                                if (complete) R.string.title_remove_all else R.string.title_restrict_all_groups
                            )
                        )
                    }
                }

                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(model.sections, key = { it.title }) { section ->
                        SectionCard(
                            section = section,
                            assigned = assigned,
                            open = folds[section.title] == true,
                            onOpen = { folds[section.title] = folds[section.title] != true },
                            onAssign = ::assign,
                            onRestrict = { sectionHooks ->
                                Ui.restrictAll(activity, app) { assign(sectionHooks, true) }
                            }
                        )
                    }
                }

                // The actions of the sheet, side by side and as wide as each other
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { close(true) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                    ) {
                        Text(stringResource(R.string.title_sheet_more))
                    }
                    Button(
                        onClick = { close(false) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                    ) {
                        Text(stringResource(R.string.title_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    section: SectionModel,
    assigned: Set<String>,
    open: Boolean,
    onOpen: () -> Unit,
    onAssign: (List<XHook>, Boolean) -> Unit,
    onRestrict: (List<XHook>) -> Unit
) {
    val title = stringResource(section.title)
    val restricted = section.groups.count { group -> group.hooks.any { it.id in assigned } }
    val complete = section.hooks.all { it.id in assigned }
    val partial = restricted > 0 && !complete
    val size = section.groups.size
    val state = when {
        restricted == size -> pluralStringResource(R.plurals.title_section_all_restricted, size, size)
        restricted == 0 -> stringResource(R.string.title_section_none_restricted)
        size - restricted == 1 -> stringResource(
            R.string.title_section_let_through,
            restricted,
            size,
            section.groups.first { group -> group.hooks.none { it.id in assigned } }.title
        )
        else -> stringResource(R.string.title_of, restricted, size)
    }
    val expanded = stringResource(if (open) R.string.title_expanded else R.string.title_collapsed)
    val described = stringResource(R.string.title_described, title, state)
    val describedSwitch = stringResource(
        if (complete) R.string.title_section_none_of else R.string.title_section_all_of,
        title
    )

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column {
            // The switch is on top of the end of the head, which leaves room for it
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = described
                            stateDescription = expanded
                        }
                        .clickable(onClick = onOpen)
                        .padding(start = 12.dp, top = 8.dp, end = 76.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painterResource(R.drawable.ic_expand),
                        null,
                        Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = if (open) 180f else 0f },
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 10.dp)
                    ) {
                        Text(
                            title,
                            Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            state,
                            Modifier.padding(top = 2.dp),
                            style = metaStyle(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                AppSwitch(
                    checked = restricted > 0,
                    partial = partial,
                    description = describedSwitch,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp),
                    onCheckedChange = {
                        if (complete) onAssign(section.hooks, false) else onRestrict(section.hooks)
                    }
                )
            }

            if (open)
                for (group in section.groups) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    GroupRow(group, assigned, onAssign)
                }
        }
    }
}

@Composable
private fun GroupRow(
    group: GroupModel,
    assigned: Set<String>,
    onAssign: (List<XHook>, Boolean) -> Unit
) {
    val count = group.hooks.count { it.id in assigned }
    val on = count > 0
    val partial = count > 0 && count < group.hooks.size
    val badge = if (on) Ui.Badge.ACCENT else Ui.Badge.NEUTRAL
    // The icon has the size of the group icons of the screen of the app, in the color of its state, and the touch
    // target of the row around it
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { onAssign(group.hooks, !on) }
            .padding(start = 10.dp, top = 11.dp, end = 10.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(themeColor(badge.background)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(group.icon),
                    null,
                    Modifier.size(18.dp),
                    tint = themeColor(badge.text)
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Text(
                group.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (group.description != null)
                Text(
                    group.description,
                    style = metaStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            if (partial)
                Text(
                    pluralStringResource(R.plurals.title_group_partial, group.hooks.size, count, group.hooks.size),
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelMedium
                )
        }
        AppSwitch(
            checked = on,
            partial = partial,
            description = group.title,
            onCheckedChange = { onAssign(group.hooks, !on) }
        )
    }
}

// The small caption above a group of rows
@Composable
private fun metaStyle() = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

// The groups of a section, by the order of the sections and of their titles, which the screen of the app uses too
internal fun groupsBySection(context: Context, groups: Collection<String>): Map<Int, List<String>> {
    val ordered = LinkedHashMap<Int, List<String>>()
    for (section in Groups.SECTIONS) {
        val names = groups.filter { Groups.getSection(it) == section }.toMutableList()
        if (names.isEmpty())
            continue
        Groups.sortByTitle(context, names)
        ordered[section] = names
    }
    return ordered
}

private class GroupModel(
    val name: String,
    val title: String,
    val description: String?,
    @DrawableRes val icon: Int,
    val hooks: List<XHook>
)

private class SectionModel(@StringRes val title: Int, val groups: List<GroupModel>) {
    val hooks: List<XHook> = groups.flatMap { it.hooks }
}

private class SheetModel(
    val available: List<XHook>,
    val groups: List<GroupModel>,
    val sections: List<SectionModel>,
    val assigned: Set<String>
)

// What the sheet shows, read once when it opens, like the views built it
private fun sheetModel(context: Context, app: XApp, hooks: List<XHook>, collection: List<String>): SheetModel {
    val available = Data.getAvailable(hooks, app, collection, null)
    val grouped = LinkedHashMap<String, MutableList<XHook>>()
    for (hook in available)
        grouped.getOrPut(hook.group) { mutableListOf() }.add(hook)
    val sections = groupsBySection(context, grouped.keys).map { (title, names) ->
        SectionModel(title, names.map { name ->
            GroupModel(
                name,
                Groups.getTitle(context, name),
                Groups.getDescription(context, name),
                Groups.getIcon(name),
                grouped[name].orEmpty()
            )
        })
    }
    return SheetModel(
        available,
        sections.flatMap { it.groups },
        sections,
        app.assignments.mapTo(HashSet()) { it.id }
    )
}
