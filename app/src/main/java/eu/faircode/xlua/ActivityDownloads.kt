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

import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.ArrayList
import java.util.Collections

// Community hook definitions of the repository (repo/), packaged with the app
class ActivityDownloads : ActivityBase() {
    companion object {
        private const val TAG = "XLua.Downloads"
        private const val STATE_OPEN = "open"
        private const val STATE_EXPANDED = "expanded"

        private const val REPO = "repo/"
    }

    // The repository and the definitions installed on the device, as the views had them
    private var data by mutableStateOf(DownloadsData())
    private var loading by mutableStateOf(true)

    // The open collection and the descriptions shown in full stay open when the screen is recreated
    private var open by mutableStateOf<String?>(null) // null: the first collection, empty: none
    private var expanded by mutableStateOf<Set<Int>>(emptySet())
    private var installRequest by mutableStateOf<InstallRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        open = savedInstanceState?.getString(STATE_OPEN)
        expanded = savedInstanceState?.getIntegerArrayList(STATE_EXPANDED)?.toSet() ?: emptySet()

        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DoppelTheme {
                    DownloadsScreen(
                        data = data,
                        loading = loading,
                        open = open,
                        expanded = expanded,
                        installRequest = installRequest,
                        dispatcher = onBackPressedDispatcher,
                        onBack = { onBackPressedDispatcher.onBackPressed() },
                        onOpenGroup = { open = it },
                        onExpandEntry = { id ->
                            expanded = if (id in expanded) expanded - id else expanded + id
                        },
                        onOpenEntry = { entry ->
                            startActivity(
                                ActivityHookEdit.getIntent(this@ActivityDownloads, entry.key)
                            )
                        },
                        onInstall = { confirmInstall(it) },
                        onRemove = { confirmRemove(it) },
                        onUpdateAll = { confirmUpdateAll() },
                        onInstallDone = { entry, restrict ->
                            installRequest = null
                            install(entry, restrict)
                        },
                        onDismissInstall = { installRequest = null }
                    )
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_OPEN, open)
        outState.putIntegerArrayList(STATE_EXPANDED, ArrayList(expanded))
    }

    // The first load, and again after it failed; the editor can change or delete an installed definition
    override fun onResume() {
        super.onResume()
        if (!data.loaded) {
            load()
            return
        }
        val context = applicationContext
        run(TAG, Ui.Task { showInstalled(context) })
    }

    // An error offers a retry, since the screen shows nothing without the list
    private fun load() {
        val context = applicationContext
        loading = true
        Ui.submit(Ui.Task {
            // The list has the time of the update, the definition files, as installed, the rest
            val entries = ArrayList<DownloadEntry>()
            val jlist = JSONObject(readAsset(context, REPO + "list.json")).getJSONArray("list")
            for (i in 0 until jlist.length()) {
                val jentry = jlist.getJSONObject(i)
                val id = jentry.getInt("id")
                val updated = jentry.optLong("updated") * 1000L

                val jhook = readDefinition(context, id)
                val jparams = jhook.getJSONArray("parameterTypes")
                val parameterTypes = Array(jparams.length()) { jparams.getString(it) }

                entries.add(
                    DownloadEntry(
                        id = id,
                        collection = jhook.getString("collection"),
                        group = jhook.getString("group"),
                        name = jhook.getString("name"),
                        author = jhook.optString("author"),
                        version = jhook.optInt("version"),
                        description = jhook.optNullableString("description"),
                        updated = updated,
                        className = jhook.getString("className"),
                        methodName = jhook.optNullableString("methodName"),
                        parameterTypes = parameterTypes
                    )
                )
            }
            Collections.sort(entries) { e1, e2 ->
                val c = e1.collection.compareTo(e2.collection, ignoreCase = true)
                if (c == 0) e1.name.compareTo(e2.name, ignoreCase = true) else c
            }

            val installed = getInstalled(context)
            val builtinIds = Data.getBuiltinIds(context)
            val collection = Data.getCollection(context)

            postUnlessGone {
                data = DownloadsData(entries, installed, builtinIds, collection, loaded = true)
                loading = false
            }
        }, { ex ->
            postUnlessGone { loading = false }
            Ui.error(this, TAG, ex, this::load)
        })
    }

    // All definitions by id, built-in and custom
    private fun getInstalled(context: Context): Map<String, XHook> {
        val installed = HashMap<String, XHook>()
        for (hook in Data.getAllHooks(context, null))
            installed[hook.id] = hook
        return installed
    }

    // The list after the installed definitions changed, from the background thread
    private fun showInstalled(context: Context) {
        val installed = getInstalled(context)
        postUnlessGone {
            data = data.copy(installed = installed)
        }
    }

    // A shared definition, by its entry of the list
    private fun readDefinition(context: Context, id: Int): JSONObject =
        JSONObject(readAsset(context, REPO + "hook_" + id + ".json"))

    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).use { Util.readText(it) }

    // Installing replaces a built-in definition with the same name and adds to definitions hooking the same member;
    // apps restricting the group of the definition can get it too, like for built-in definitions added by updates
    private fun confirmInstall(entry: DownloadEntry) {
        // An update or installing again replaces the definition, also changes made to it with the editor
        if (downloadState(entry, data.installed) != DownloadState.AVAILABLE) {
            Ui.areYouSure(
                this,
                getString(R.string.msg_update_sure, entry.key),
                R.string.title_update
            ) { install(entry, false) }
            return
        }

        val sb = StringBuilder()
        if (data.builtinIds.contains(entry.key))
            sb.append(getString(R.string.msg_install_replaces, entry.key))
        val overlaps = downloadOverlaps(entry, data.installed, data.collection)
        if (overlaps.isNotEmpty()) {
            if (sb.isNotEmpty())
                sb.append("\n\n")
            sb.append(getString(R.string.msg_install_overlaps))
            for (hook in overlaps)
                sb.append("\n• ").append(hook.id).append(" (")
                    .append(Groups.getTitle(this, hook.group)).append(')')
        }

        installRequest = InstallRequest(entry, sb.toString())
    }

    private fun install(entry: DownloadEntry, restrict: Boolean) {
        val context = applicationContext
        val enabled = data.collection.contains(entry.collection)
        run(TAG, Ui.Task {
            var apps = 0
            try {
                val jhook = readDefinition(context, entry.id)
                val hook = Data.putCustom(context, jhook)
                apps = if (restrict)
                    Data.assignRestricting(context, arrayListOf(hook.id))
                else
                    0
            } finally {
                // Also after an error, which can come after the definition was stored
                showInstalled(context)
            }

            // Hooks of disabled collections are not applied, also not when they are assigned already
            if (!enabled)
                Ui.message(
                    this,
                    getString(R.string.msg_installed_collection, entry.name, entry.collection),
                    getString(R.string.title_enable)
                ) { enableCollection(entry.collection) }
            else if (apps > 0)
                Ui.message(
                    this,
                    resources.getQuantityString(
                        R.plurals.msg_installed_restricted,
                        apps,
                        entry.name,
                        apps
                    )
                )
            else
                Ui.message(this, getString(R.string.msg_installed, entry.name))
        })
    }

    // Updating keeps the id of a definition, so the apps restricting it keep it
    private fun confirmUpdateAll() {
        val entries = data.all.filter { downloadState(it, data.installed) == DownloadState.UPDATE }
        if (entries.isEmpty())
            return
        val count = entries.size
        Ui.areYouSure(
            this,
            resources.getQuantityString(R.plurals.msg_update_all, count, count),
            R.string.title_update
        ) {
            val context = applicationContext
            run(TAG, Ui.Task {
                try {
                    for (entry in entries)
                        Data.putCustom(context, readDefinition(context, entry.id))
                } finally {
                    // Also after an error, which can come after some were updated
                    showInstalled(context)
                }
                Ui.message(
                    this,
                    resources.getQuantityString(R.plurals.msg_updated, count, count)
                )
            })
        }
    }

    // Deleting an installed definition restores the built-in definition it replaced
    private fun confirmRemove(entry: DownloadEntry) {
        val message = getString(
            if (data.builtinIds.contains(entry.key))
                R.string.msg_remove_restores
            else
                R.string.msg_remove_sure,
            entry.name
        )
        Ui.areYouSure(this, message, R.string.title_remove) {
            val context = applicationContext
            run(TAG, Ui.Task {
                try {
                    Data.putHook(context, entry.key, null)
                } finally {
                    // Also after an error, like a failed call of which the service removed the definition
                    showInstalled(context)
                }
                Ui.message(this, getString(R.string.msg_removed, entry.name))
            })
        }
    }

    private fun enableCollection(name: String) {
        val context = applicationContext
        val enabled = ArrayList(data.collection)
        if (!enabled.contains(name))
            enabled.add(name)
        run(TAG, Ui.Task {
            Data.putCollection(context, enabled)
            runOnUiThread {
                data = data.copy(collection = enabled)
            }
            Ui.message(this, getString(R.string.msg_collection_enabled, name))
        })
    }
}

private enum class DownloadFilter(@StringRes val title: Int) {
    ALL(R.string.title_all),
    AVAILABLE(R.string.title_not_installed),
    INSTALLED(R.string.title_installed),
    UPDATES(R.string.title_updates)
}

private enum class DownloadState { AVAILABLE, INSTALLED, UPDATE }

// optString with a null fallback, like the Java code read the optional texts
private fun JSONObject.optNullableString(name: String): String? {
    val value = opt(name)
    if (value == null || value === JSONObject.NULL)
        return null
    return value.toString()
}

private class DownloadEntry(
    val id: Int,
    val collection: String,
    val group: String,
    val name: String,
    val author: String,
    val version: Int,
    val description: String?,
    val updated: Long,
    val className: String,
    val methodName: String?,
    val parameterTypes: Array<String>
) {
    val key: String
        get() = XHook.id(collection, name)
}

private data class DownloadsData(
    val all: List<DownloadEntry> = emptyList(),
    val installed: Map<String, XHook> = emptyMap(),
    val builtinIds: Set<String> = emptySet(),
    val collection: List<String> = emptyList(),
    val loaded: Boolean = false
)

private data class InstallRequest(val entry: DownloadEntry, val message: String)

private data class DownloadCollection(
    val name: String,
    val entries: List<DownloadEntry>,
    val expanded: Boolean
)

private sealed interface DownloadRow {
    data class Header(val collection: DownloadCollection) : DownloadRow
    data class Item(
        val entry: DownloadEntry,
        val first: Boolean,
        val last: Boolean
    ) : DownloadRow
}

// Built-in definitions do not count as installed, installing replaces them
private fun downloadState(entry: DownloadEntry, installed: Map<String, XHook>): DownloadState {
    val hook = installed[entry.key]
    if (hook == null || hook.isBuiltin)
        return DownloadState.AVAILABLE
    return if (hook.version < entry.version) DownloadState.UPDATE else DownloadState.INSTALLED
}

// Other active definitions hooking the same member: both run, one after the other,
// and can undo or break the changes of the other
private fun downloadOverlaps(
    entry: DownloadEntry,
    installed: Map<String, XHook>,
    collection: List<String>
): List<XHook> {
    val overlaps = installed.values.filter { hook ->
        hook.id != entry.key &&
                hook.isAvailable(null, collection) &&
                hook.className == entry.className &&
                hook.methodName == entry.methodName &&
                hook.parameterTypes.contentEquals(entry.parameterTypes)
    }
    return overlaps.sortedWith { h1, h2 -> h1.id.compareTo(h2.id, ignoreCase = true) }
}

@Composable
private fun DownloadsScreen(
    data: DownloadsData,
    loading: Boolean,
    open: String?,
    expanded: Set<Int>,
    installRequest: InstallRequest?,
    dispatcher: OnBackPressedDispatcher,
    onBack: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onExpandEntry: (Int) -> Unit,
    onOpenEntry: (DownloadEntry) -> Unit,
    onInstall: (DownloadEntry) -> Unit,
    onRemove: (DownloadEntry) -> Unit,
    onUpdateAll: () -> Unit,
    onInstallDone: (DownloadEntry, Boolean) -> Unit,
    onDismissInstall: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val largeText = density.fontScale > 1.3f

    // A search restored with the views is shown again, without the keyboard
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(query.isNotEmpty()) }
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    val closed = remember { mutableStateListOf<String>() }
    val focusRequester = remember { FocusRequester() }
    var focusRequests by remember { mutableIntStateOf(0) }

    val closeSearch = {
        closed.clear()
        query = ""
        searchOpen = false
        keyboard?.hide()
    }
    val selectFilter: (DownloadFilter) -> Unit = { option ->
        // Checking the chip which is checked changes nothing, like Ui.chips for the views
        if (option != filter) {
            closed.clear()
            filter = option
        }
    }
    val openSearch = {
        searchOpen = true
        focusRequests++
    }
    // Back closes the search first, like it did for the views
    DisposableEffect(searchOpen) {
        val callback = object : OnBackPressedCallback(searchOpen) {
            override fun handleOnBackPressed() {
                closeSearch()
            }
        }
        dispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    // The summary, the chips and the updates count all definitions, whatever the search
    var available = 0
    var installed = 0
    var updates = 0
    for (entry in data.all)
        when (downloadState(entry, data.installed)) {
            DownloadState.AVAILABLE -> available++
            DownloadState.INSTALLED -> installed++
            DownloadState.UPDATE -> {
                installed++
                updates++
            }
        }
    val collections = data.all.map { it.collection }.toSet().size
    val definitionsText = pluralStringResource(
        R.plurals.title_definition_count,
        data.all.size,
        data.all.size
    )
    val collectionsText = pluralStringResource(
        R.plurals.title_collection_count,
        collections,
        collections
    )
    val installedText = if (installed > 0)
        pluralStringResource(R.plurals.title_installed_count, installed, installed)
    else
        null
    val summary = if (data.all.isEmpty())
        null
    else
        listOfNotNull(definitionsText, collectionsText, installedText).joinToString(" · ")
    val filterTitle = stringResource(filter.title)
    val compactSubtitle = stringResource(R.string.title_pair, definitionsText, filterTitle)

    // One collection is open at a time, like the groups of the Hooks tab, at first the first one;
    // searching or filtering shows all collections with matches
    val first = data.all.firstOrNull()?.collection ?: ""
    val q = query.trim()
    val filtering = filter != DownloadFilter.ALL || q.isNotEmpty()
    val isOpen: (String) -> Boolean = { key ->
        if (filtering) key !in closed else key == (open ?: first)
    }

    val search = Ui.Query(q)
    val groups = ArrayList<DownloadCollection>()
    var currentName: String? = null
    var currentEntries = ArrayList<DownloadEntry>()
    for (entry in data.all) {
        val state = downloadState(entry, data.installed)
        if (filter == DownloadFilter.AVAILABLE && state != DownloadState.AVAILABLE)
            continue
        if (filter == DownloadFilter.INSTALLED && state == DownloadState.AVAILABLE)
            continue
        if (filter == DownloadFilter.UPDATES && state != DownloadState.UPDATE)
            continue
        if (q.isNotEmpty() &&
            !search.`in`(entry.key) &&
            !search.`in`(entry.group) &&
            !search.`in`(Groups.getTitle(context, entry.group)) &&
            !search.`in`(entry.author) &&
            !search.`in`(entry.description)
        )
            continue
        if (entry.collection != currentName) {
            val name = currentName
            if (name != null && currentEntries.isNotEmpty())
                groups.add(DownloadCollection(name, currentEntries, isOpen(name)))
            currentName = entry.collection
            currentEntries = ArrayList()
        }
        currentEntries.add(entry)
    }
    val lastName = currentName
    if (lastName != null && currentEntries.isNotEmpty())
        groups.add(DownloadCollection(lastName, currentEntries, isOpen(lastName)))

    val rows = ArrayList<DownloadRow>()
    for (group in groups) {
        rows.add(DownloadRow.Header(group))
        if (group.expanded)
            group.entries.forEachIndexed { index, entry ->
                rows.add(
                    DownloadRow.Item(
                        entry,
                        first = index == 0,
                        last = index == group.entries.lastIndex
                    )
                )
            }
    }

    // The list is empty when nothing matches the filter or the search
    val emptyText = when {
        !data.loaded -> 0
        rows.isNotEmpty() || data.all.isEmpty() -> 0
        filter == DownloadFilter.UPDATES && q.isEmpty() -> R.string.title_no_updates
        else -> R.string.title_no_matches
    }

    var headerHeight by remember { mutableIntStateOf(0) }
    val compactAlpha by remember {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> 1f
                headerHeight <= 0 -> 0f
                else -> ((listState.firstVisibleItemScrollOffset - headerHeight * 0.6f) /
                        (headerHeight * 0.4f)).coerceIn(0f, 1f)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.ime)
            )
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item(key = "header") {
                DownloadsHeader(
                    searchOpen = searchOpen,
                    query = query,
                    summary = summary,
                    filter = filter,
                    loaded = data.loaded,
                    all = data.all.size,
                    available = available,
                    installed = installed,
                    updates = updates,
                    focusRequester = focusRequester,
                    headerAlpha = 1f - compactAlpha,
                    onHeaderHeight = { height ->
                        if (height != headerHeight)
                            headerHeight = height
                    },
                    onQuery = {
                        // Another search opens all collections again
                        closed.clear()
                        query = it
                    },
                    onFilter = selectFilter,
                    onSearch = { if (searchOpen) closeSearch() else openSearch() },
                    onBack = onBack
                )
            }

            item(key = "notice") {
                DownloadsNotice(
                    Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 10.dp)
                )
            }

            if (updates > 0)
                item(key = "updates") {
                    UpdatesBanner(
                        updates = updates,
                        onUpdateAll = onUpdateAll,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                    )
                }

            if (emptyText != 0)
                item(key = "empty") {
                    EmptyMessage(
                        stringResource(emptyText),
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                }

            items(rows.size) { index ->
                when (val row = rows[index]) {
                    is DownloadRow.Header -> DownloadGroupRow(
                        collection = row.collection,
                        isFirst = index == 0,
                        isLast = index == rows.lastIndex,
                        enabled = data.collection.contains(row.collection.name),
                        installed = data.installed,
                        onToggle = {
                            if (filtering) {
                                if (!closed.remove(row.collection.name))
                                    closed.add(row.collection.name)
                            } else
                                onOpenGroup(
                                    if (isOpen(row.collection.name)) "" else row.collection.name
                                )
                        }
                    )

                    is DownloadRow.Item -> DownloadItemRow(
                        entry = row.entry,
                        first = row.first,
                        last = row.last,
                        isFirst = index == 0,
                        isLast = index == rows.lastIndex,
                        state = downloadState(row.entry, data.installed),
                        installedHook = data.installed[row.entry.key],
                        builtin = data.builtinIds.contains(row.entry.key),
                        overlaps = downloadOverlaps(
                            row.entry,
                            data.installed,
                            data.collection
                        ),
                        expanded = row.entry.id in expanded,
                        onOpen = {
                            if (downloadState(row.entry, data.installed) != DownloadState.AVAILABLE)
                                onOpenEntry(row.entry)
                            else
                                onExpandEntry(row.entry.id)
                        },
                        onInstall = { onInstall(row.entry) },
                        onRemove = { onRemove(row.entry) }
                    )
                }
            }
        }

        if (compactAlpha > 0.01f)
            DownloadsCompactBar(
                subtitle = compactSubtitle,
                largeText = largeText,
                filter = filter,
                loaded = data.loaded,
                all = data.all.size,
                available = available,
                installed = installed,
                updates = updates,
                alpha = compactAlpha,
                onBack = onBack,
                onFilter = selectFilter,
                onSearch = {
                    scope.launch { listState.animateScrollToItem(0) }
                    if (!searchOpen)
                        openSearch()
                }
            )

        if (loading) {
            Loading(Modifier.align(Alignment.Center))
        }
    }

    installRequest?.let { request ->
        InstallDialog(
            request = request,
            onDismiss = onDismissInstall,
            onInstall = { restrict -> onInstallDone(request.entry, restrict) }
        )
    }
}

@Composable
private fun DownloadsHeader(
    searchOpen: Boolean,
    query: String,
    summary: String?,
    filter: DownloadFilter,
    loaded: Boolean,
    all: Int,
    available: Int,
    installed: Int,
    updates: Int,
    focusRequester: FocusRequester,
    headerAlpha: Float,
    onHeaderHeight: (Int) -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (DownloadFilter) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = headerAlpha }
            .onGloballyPositioned { onHeaderHeight(it.size.height) }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.title_back)
                )
            }
            if (searchOpen)
                SearchField(
                    query = query,
                    hint = stringResource(R.string.title_search_downloads),
                    onQueryChange = onQuery,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    focusRequester = focusRequester,
                    onSearch = { keyboard?.hide() }
                )
            else
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        text = stringResource(R.string.title_downloads),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    if (summary != null)
                        Text(
                            text = summary,
                            modifier = Modifier.padding(top = 2.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                }
            IconButton(onClick = onSearch) {
                Icon(
                    painterResource(if (searchOpen) R.drawable.ic_close else R.drawable.ic_search),
                    contentDescription = stringResource(
                        if (searchOpen) R.string.title_close_search else R.string.menu_search
                    )
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DownloadFilter.entries.forEach { option ->
                FilterChip(
                    selected = option == filter,
                    onClick = { onFilter(option) },
                    label = {
                        Text(
                            downloadFilterLabel(
                                option,
                                loaded,
                                all,
                                available,
                                installed,
                                updates
                            )
                        )
                    },
                    shape = CircleShape,
                    colors = downloadChipColors(),
                    border = null
                )
            }
        }
    }
}

@Composable
private fun DownloadsCompactBar(
    subtitle: String,
    largeText: Boolean,
    filter: DownloadFilter,
    loaded: Boolean,
    all: Int,
    available: Int,
    installed: Int,
    updates: Int,
    alpha: Float,
    onBack: () -> Unit,
    onFilter: (DownloadFilter) -> Unit,
    onSearch: () -> Unit
) {
    var filterMenu by remember { mutableStateOf(false) }
    val filterDescription = stringResource(
        if (loaded) R.string.title_filter_by else R.string.title_filter,
        downloadFilterLabel(filter, loaded, all, available, installed, updates)
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
    ) {
        Column(Modifier.height(64.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.title_back)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.title_downloads),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!largeText)
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                }
                Box {
                    IconButton(onClick = { filterMenu = true }) {
                        Icon(
                            painterResource(R.drawable.ic_filter),
                            contentDescription = filterDescription
                        )
                    }
                    DropdownMenu(
                        expanded = filterMenu,
                        onDismissRequest = { filterMenu = false }
                    ) {
                        DownloadFilter.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        downloadFilterLabel(
                                            option,
                                            loaded,
                                            all,
                                            available,
                                            installed,
                                            updates
                                        )
                                    )
                                },
                                onClick = {
                                    filterMenu = false
                                    onFilter(option)
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = onSearch) {
                    Icon(
                        painterResource(R.drawable.ic_search),
                        contentDescription = stringResource(R.string.menu_search)
                    )
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun downloadFilterLabel(
    option: DownloadFilter,
    loaded: Boolean,
    all: Int,
    available: Int,
    installed: Int,
    updates: Int
): String {
    val title = stringResource(option.title)
    if (!loaded)
        return title
    val count = when (option) {
        DownloadFilter.ALL -> all
        DownloadFilter.AVAILABLE -> available
        DownloadFilter.INSTALLED -> installed
        DownloadFilter.UPDATES -> updates
    }
    return stringResource(R.string.title_count, title, count)
}

@Composable
private fun downloadChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
)

@Composable
private fun DownloadsNotice(modifier: Modifier) {
    Row(
        modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(R.drawable.ic_info),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.title_downloads_notice),
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun UpdatesBanner(
    updates: Int,
    onUpdateAll: () -> Unit,
    modifier: Modifier
) {
    val accent = MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .heightIn(min = 56.dp)
            .padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_update),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = accent
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = pluralStringResource(R.plurals.title_update_count, updates, updates),
                style = MaterialTheme.typography.titleMedium.copy(color = accent),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.title_updates_snapshot),
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall.copy(color = accent),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Button(
            onClick = onUpdateAll,
            modifier = Modifier.padding(start = 8.dp)
        ) {
            Text(stringResource(R.string.title_update_all))
        }
    }
}

@Composable
private fun DownloadGroupRow(
    collection: DownloadCollection,
    isFirst: Boolean,
    isLast: Boolean,
    enabled: Boolean,
    installed: Map<String, XHook>,
    onToggle: () -> Unit
) {
    var installedCount = 0
    var updates = 0
    for (entry in collection.entries)
        when (downloadState(entry, installed)) {
            DownloadState.AVAILABLE -> Unit
            DownloadState.INSTALLED -> installedCount++
            DownloadState.UPDATE -> {
                installedCount++
                updates++
            }
        }

    val definitionsText = pluralStringResource(
        R.plurals.title_definition_count,
        collection.entries.size,
        collection.entries.size
    )
    val installedText = if (installedCount > 0)
        pluralStringResource(R.plurals.title_installed_count, installedCount, installedCount)
    else
        null
    val updatesText = if (updates > 0)
        pluralStringResource(R.plurals.title_update_count, updates, updates)
    else
        null
    val offText = if (enabled) null else stringResource(R.string.title_collection_off)
    val meta = listOfNotNull(definitionsText, installedText, updatesText, offText)
        .joinToString(" · ")
    val description = stringResource(
        if (collection.expanded) R.string.title_hide_hooks else R.string.title_show_hooks,
        collection.name
    )
    val described = stringResource(
        R.string.title_described3,
        collection.name,
        meta,
        description
    )
    val accent = themeColor(com.google.android.material.R.attr.colorSecondaryContainer)
    val accentText = themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
    val neutral = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
    val neutralText = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(blockShape(isFirst, isLast))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(onClick = onToggle)
            .semantics { contentDescription = described }
    ) {
        Column {
            if (!isFirst)
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    thickness = 1.dp,
                    color = blockDividerColor()
                )
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(if (collection.expanded) accent else neutral, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_group_custom),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (collection.expanded) accentText else neutralText
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = collection.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = meta,
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!enabled)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = CircleShape,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Box(
                            Modifier
                                .heightIn(min = 26.dp)
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.title_off),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFeatureSettings = "tnum"
                                )
                            )
                        }
                    }
                Icon(
                    painterResource(R.drawable.ic_expand),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(20.dp)
                        .rotate(if (collection.expanded) 180f else 0f),
                    tint = if (collection.expanded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadItemRow(
    entry: DownloadEntry,
    first: Boolean,
    last: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    state: DownloadState,
    installedHook: XHook?,
    builtin: Boolean,
    overlaps: List<XHook>,
    expanded: Boolean,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val date = DateUtils.formatDateTime(
        context,
        entry.updated,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or
                DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_ABBREV_MONTH
    )
    val meta = buildAnnotatedString {
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
            append(Groups.getTitle(context, entry.group))
        }
        append(" · ")
        append(
            stringResource(
                R.string.title_download_meta,
                entry.author,
                entry.version,
                date
            )
        )
    }

    val badges = ArrayList<Pair<String, Ui.Badge>>()
    if (builtin)
        badges.add(stringResource(R.string.title_replaces_builtin) to Ui.Badge.WARNING)
    if (overlaps.isNotEmpty())
        badges.add(
            pluralStringResource(R.plurals.title_overlaps, overlaps.size, overlaps.size) to
                    Ui.Badge.WARNING
        )
    if (state == DownloadState.UPDATE && installedHook != null)
        badges.add(
            stringResource(
                R.string.title_version_change,
                installedHook.version,
                entry.version
            ) to Ui.Badge.NEUTRAL
        )

    val hasDescription = !entry.description.isNullOrEmpty()
    // Screen readers tell which definition the buttons are for
    val removeDescription = stringResource(
        R.string.title_described,
        stringResource(R.string.title_remove),
        entry.name
    )
    val installDescription = stringResource(
        R.string.title_described,
        stringResource(R.string.title_install),
        entry.name
    )
    val updateDescription = stringResource(
        R.string.title_described,
        stringResource(R.string.title_update),
        entry.name
    )
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(blockShape(isFirst, isLast))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(onClick = onOpen)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = if (last) 10.dp else 0.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(panelShape(first, last))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                if (!first)
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        thickness = 1.dp,
                        color = panelDividerColor()
                    )
                Column(Modifier.padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 4.dp)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = meta,
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (hasDescription)
                        Text(
                            text = entry.description.orEmpty().trim(),
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis
                        )

                    // The badges and the actions share a line; the badges wrap to the next line instead of being
                    // cut off, and the group stays, also without badges, to keep the actions at the end
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                            .heightIn(min = 40.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FlowRow(
                            modifier = Modifier
                                .weight(1f)
                                .clearAndSetSemantics {},
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            badges.forEach { (text, style) -> DownloadBadge(text, style) }
                        }

                        // Installed definitions can be removed, also when there is an update
                        if (state != DownloadState.AVAILABLE)
                            TextButton(
                                onClick = onRemove,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .semantics { contentDescription = removeDescription }
                            ) {
                                Text(stringResource(R.string.title_remove))
                            }
                        if (state == DownloadState.INSTALLED)
                            InstalledLabel()
                        if (state == DownloadState.AVAILABLE)
                            FilledTonalButton(
                                onClick = onInstall,
                                modifier = Modifier.semantics {
                                    contentDescription = installDescription
                                }
                            ) {
                                Text(stringResource(R.string.title_install))
                            }
                        if (state == DownloadState.UPDATE)
                            Button(
                                onClick = onInstall,
                                modifier = Modifier.semantics {
                                    contentDescription = updateDescription
                                }
                            ) {
                                Text(stringResource(R.string.title_update))
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledLabel() {
    Surface(
        color = themeColor(R.attr.colorSuccessContainer),
        contentColor = themeColor(R.attr.colorOnSuccessContainer),
        shape = CircleShape
    ) {
        Row(
            Modifier
                .height(28.dp)
                .padding(start = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = themeColor(R.attr.colorOnSuccessContainer)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.title_installed),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@Composable
private fun DownloadBadge(text: String, style: Ui.Badge) {
    Surface(
        color = themeColor(style.background),
        contentColor = themeColor(style.text),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(start = 7.dp, top = 2.dp, end = 7.dp, bottom = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontFamily = Mono,
                fontWeight = FontWeight.Normal
            )
        )
    }
}

@Composable
private fun InstallDialog(
    request: InstallRequest,
    onDismiss: () -> Unit,
    onInstall: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var restrict by remember(request) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.title_install_name, request.entry.name)
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (request.message.isNotEmpty())
                    Text(
                        text = request.message,
                        modifier = Modifier.padding(bottom = 8.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = restrict,
                            role = Role.Checkbox,
                            onValueChange = { restrict = it }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = restrict,
                        onCheckedChange = null,
                        modifier = Modifier.offset(x = (-8).dp)
                    )
                    Text(
                        text = stringResource(
                            R.string.title_install_restrict,
                            Groups.getTitle(context, request.entry.group)
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onInstall(restrict) }) {
                Text(stringResource(R.string.title_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

private fun blockShape(first: Boolean, last: Boolean) = RoundedCornerShape(
    topStart = if (first) 20.dp else 0.dp,
    topEnd = if (first) 20.dp else 0.dp,
    bottomStart = if (last) 20.dp else 0.dp,
    bottomEnd = if (last) 20.dp else 0.dp
)

private fun panelShape(first: Boolean, last: Boolean) = RoundedCornerShape(
    topStart = if (first) 14.dp else 0.dp,
    topEnd = if (first) 14.dp else 0.dp,
    bottomStart = if (last) 14.dp else 0.dp,
    bottomEnd = if (last) 14.dp else 0.dp
)

// The hairline between the rows of a block, a shade of their background
@Composable
private fun blockDividerColor(): Color =
    lerp(
        MaterialTheme.colorScheme.surfaceContainerLowest,
        MaterialTheme.colorScheme.onSurface,
        0.08f
    )

@Composable
private fun panelDividerColor(): Color =
    lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.onSurface,
        0.08f
    )
