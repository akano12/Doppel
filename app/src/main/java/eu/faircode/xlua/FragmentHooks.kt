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
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// The group which shows its hooks, the others show only their row
private const val PREF_EXPANDED = "hooks_group"

private enum class HookFilter(@StringRes val title: Int) {
    ALL(R.string.title_all),
    CUSTOM(R.string.title_hook_custom),
    DISABLED(R.string.title_hook_disabled),
    UNAVAILABLE(R.string.title_hook_unavailable)
}

private data class HooksUi(
    val hooks: List<XHook> = emptyList(),
    val builtinIds: Set<String> = emptySet(),
    val collection: List<String> = emptyList(),
    val loading: Boolean = true
)

private data class HookGroup(
    val key: String,
    val title: String,
    val icon: Int,
    val hooks: List<XHook>
)

// All hook definitions: built-in, custom and custom replacing built-in, under their groups
class FragmentHooks : FragmentBase() {
    companion object {
        private const val TAG = "XLua.Hooks"

        private const val STATE_IMPORTING = "importing"
        // Definitions are a few KB each, so a larger file is not one, and reading it whole could run out of memory
        private const val MAX_IMPORT = 8 * 1024 * 1024

        // The tab shown, which an import reloads when it ends: the screen can be recreated meanwhile, like rotated,
        // and the new tab loaded before the definitions were stored
        @Volatile
        private var shown: FragmentHooks? = null
    }

    private var ui by mutableStateOf(HooksUi())
    private var hidden by mutableStateOf(false)
    private var importing: Uri? = null // The file of an import which waits for its confirmation, asked again after a recreation

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importHooks(it) } }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { exportHooks(it) } }

    override fun onResume() {
        shown = this
        hidden = isHidden
        super.onResume()
    }

    override fun onPause() {
        if (shown == this)
            shown = null
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        this.hidden = hidden
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        hidden = isHidden
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DoppelTheme {
                    HooksScreen(
                        data = ui,
                        hidden = hidden,
                        dispatcher = requireActivity().onBackPressedDispatcher,
                        onAdd = {
                            startActivity(android.content.Intent(requireContext(), ActivityHookEdit::class.java))
                        },
                        onDownloads = {
                            startActivity(android.content.Intent(requireContext(), ActivityDownloads::class.java))
                        },
                        onImport = {
                            Ui.pick(requireActivity(), importLauncher, Ui.MIME_JSON)
                        },
                        onExport = {
                            Ui.pick(requireActivity(), exportLauncher, Ui.datedFileName("hooks"))
                        },
                        onOpen = { id ->
                            startActivity(ActivityHookEdit.getIntent(requireContext(), id))
                        }
                    )
                }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (savedInstanceState != null && savedInstanceState.getString(STATE_IMPORTING) != null)
            importHooks(Uri.parse(savedInstanceState.getString(STATE_IMPORTING)))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (importing != null)
            outState.putString(STATE_IMPORTING, importing.toString())
    }

    override fun load() {
        val context = requireContext().applicationContext
        submit {
            try {
                val hooks = Data.getAllHooks(context, null)
                val ids = Data.getBuiltinIds(context)
                val collection = Data.getCollection(context)
                // In the order of the group titles shown, like Groups.sortByTitle;
                // by group next, so the hooks of a custom group with the title of another group stay together
                val titles = HashMap<String, String>()
                for (hook in hooks)
                    titles[hook.group] = Groups.getTitle(context, hook.group)
                val collator = Groups.collator()
                val sorted = hooks.sortedWith { h1, h2 ->
                    var c = collator.compare(titles[h1.group], titles[h2.group])
                    if (c == 0)
                        c = h1.group.compareTo(h2.group)
                    if (c == 0) h1.name.compareTo(h2.name, ignoreCase = true) else c
                }
                post {
                    ui = HooksUi(sorted, ids, collection, loading = false)
                }
            } catch (ex: Throwable) {
                post { ui = ui.copy(loading = false) }
                loadError(TAG, ex)
            }
        }
    }

    // A file with a definition or an array of definitions
    private fun importHooks(uri: Uri) {
        val activity = requireActivity()
        val context = activity.applicationContext
        run(TAG, Ui.Task {
            val text = Ui.readText(context, uri, MAX_IMPORT)
            if (text == null) {
                Ui.message(activity, activity.getString(R.string.msg_file_too_large, MAX_IMPORT / (1024 * 1024)))
                return@Task
            }
            val jarray: JSONArray
            try {
                jarray = if (text.trim().startsWith("["))
                    JSONArray(text)
                else
                    JSONArray().put(JSONObject(text))
            } catch (ex: JSONException) {
                Log.w(TAG, "Import: $ex")
                Ui.message(activity, activity.getString(R.string.msg_no_hooks_file))
                return@Task
            }
            // Backups and exported definitions are both JSON files with a similar name, see Ui.datedFileName
            for (i in 0 until jarray.length()) {
                val jhook = jarray.optJSONObject(i)
                if (jhook == null || !jhook.has("collection")) {
                    Ui.message(
                        activity,
                        activity.getString(
                            if (jhook != null && jhook.has("format"))
                                R.string.msg_import_backup
                            else
                                R.string.msg_no_hooks_file
                        )
                    )
                    return@Task
                }
                // Like the editor checks it; nothing is imported from a file with a definition which would fail
                try {
                    ActivityHookEdit.check(jhook)
                } catch (ex: Throwable) {
                    Log.w(TAG, "Import: $ex")
                    Ui.message(
                        activity,
                        activity.getString(
                            R.string.msg_error,
                            XHook.id(jhook.optString("collection"), jhook.optString("name")) + ": " + ex.message
                        )
                    )
                    return@Task
                }
            }
            // A definition with the id of a built-in one replaces it in all apps, which Downloads tells too,
            // and one with the id of a custom one replaces its script, which the editor asks about too
            val custom = HashSet<String>()
            for (hook in Data.getAllHooks(context, null))
                if (!hook.isBuiltin)
                    custom.add(hook.id)
            val preview = Backup.preview(jarray, Data.getBuiltinIds(context), custom)
            if (preview.replaced.isEmpty() && preview.existing.isEmpty())
                putImported(activity, jarray)
            else
                post {
                    val questions = ArrayList<String>()
                    if (preview.replaced.isNotEmpty())
                        questions.add(getString(R.string.msg_import_replaces, Ui.bullets(preview.replaced)))
                    if (preview.existing.isNotEmpty())
                        questions.add(getString(R.string.msg_import_existing, Ui.bullets(preview.existing)))
                    importing = uri
                    track(
                        Ui.areYouSure(
                            activity,
                            android.text.TextUtils.join("\n\n", questions),
                            R.string.menu_import
                        ) { run(TAG, Ui.Task { putImported(activity, jarray) }) },
                        Runnable { importing = null }
                    )
                }
        })
    }

    private fun putImported(activity: Activity, jarray: JSONArray) {
        val context = activity.applicationContext
        try {
            val imported = ArrayList<XHook>()
            for (i in 0 until jarray.length())
                imported.add(Data.putCustom(context, jarray.getJSONObject(i)))
            // Apps get the definitions when asked, like when installing from Downloads
            Ui.message(
                activity,
                activity.resources.getQuantityString(
                    R.plurals.msg_hooks_imported,
                    imported.size,
                    imported.size
                ),
                activity.getString(R.string.title_restrict_too)
            ) {
                run(TAG, Ui.Task {
                    Ui.message(activity, Data.restrictForGroups(context, imported))
                })
            }
        } finally {
            // Also the definitions imported before an error
            val tab = shown
            if (tab != null && tab !== this)
                tab.post(tab::load)
            post(this::load)
        }
    }

    // Custom definitions only; built-in definitions are part of the app
    private fun exportHooks(uri: Uri) {
        val activity = requireActivity()
        val context = activity.applicationContext
        run(TAG, Ui.Task {
            val jarray = Data.getCustomHooks(context)
            Util.writeJson(context, uri, jarray.toString(2))
            Ui.message(
                activity,
                activity.resources.getQuantityString(
                    R.plurals.msg_hooks_exported,
                    jarray.length(),
                    jarray.length()
                )
            )
        })
    }
}

// Where a hook applies, like android.os.Build#SERIAL or TelephonyManager.getImei(int)
private fun hookTarget(hook: XHook): String {
    val sb = StringBuilder(hook.className)
    val method = hook.methodName
    if (method != null && method.startsWith("#"))
        return sb.append(method).toString()
    sb.append('.').append(method ?: "<init>").append('(')
    val params = hook.parameterTypes
    for (i in params.indices) {
        if (i > 0)
            sb.append(", ")
        sb.append(params[i].substring(params[i].lastIndexOf('.') + 1))
    }
    return sb.append(')').toString()
}

private fun matches(filter: HookFilter, hook: XHook): Boolean = when (filter) {
    HookFilter.ALL -> true
    HookFilter.CUSTOM -> !hook.isBuiltin
    HookFilter.DISABLED -> !hook.isEnabled
    HookFilter.UNAVAILABLE -> !hook.isForThisSdk
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HooksScreen(
    data: HooksUi,
    hidden: Boolean,
    dispatcher: OnBackPressedDispatcher,
    onAdd: () -> Unit,
    onDownloads: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onOpen: (String) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val largeText = density.fontScale > 1.3f
    val navSpace = rememberNavSpace()

    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    val filter = HookFilter.entries[filterIndex]
    val focusRequester = remember { FocusRequester() }
    var focusRequests by remember { mutableIntStateOf(0) }

    val closeSearch = {
        query = ""
        searchOpen = false
        keyboard?.hide()
    }
    val openSearch = {
        searchOpen = true
        focusRequests++
    }
    DisposableEffect(searchOpen, hidden) {
        val callback = object : OnBackPressedCallback(searchOpen && !hidden) {
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
    LaunchedEffect(searchOpen) {
        if (!searchOpen)
            keyboard?.hide()
    }

    val builtin = data.hooks.count { it.isBuiltin }
    val custom = data.hooks.count { !it.isBuiltin }
    val disabled = data.hooks.count { !it.isEnabled }
    val unavailable = data.hooks.count { !it.isForThisSdk }
    val groupCount = data.hooks.map { it.group }.toSet().size
    val counts = intArrayOf(data.hooks.size, custom, disabled, unavailable)

    val q = query.trim()
    val search = Ui.Query(q)
    val groupTexts = HashMap<String, Ui.Haystack>()
    val groups = ArrayList<HookGroup>()
    var currentKey: String? = null
    var current = ArrayList<XHook>()
    // The hooks of the group being collected, added when another group starts or at the end
    fun flush() {
        val key = currentKey ?: return
        if (current.isNotEmpty())
            groups.add(HookGroup(key, Groups.getTitle(context, key), Groups.getIcon(key), current))
    }
    for (hook in data.hooks) {
        if (!matches(filter, hook))
            continue
        // Groups by name and by the title shown, like 'call log' for Get.Call.log
        if (q.isNotEmpty() &&
            !search.`in`(hook.id) &&
            !groupTexts.getOrPut(hook.group) { Ui.Haystack(hook.group, Groups.getTitle(context, hook.group)) }.has(search) &&
            !search.`in`(hook.className)
        )
            continue
        if (hook.group != currentKey) {
            flush()
            currentKey = hook.group
            current = ArrayList()
        }
        current.add(hook)
    }
    flush()

    val preferences = remember { ApplicationEx.getPreferences(context) }
    var open by rememberSaveable {
        mutableStateOf(preferences.getString(PREF_EXPANDED, null))
    }
    val closed = remember { mutableStateListOf<String>() }
    LaunchedEffect(filterIndex, query) { closed.clear() }
    val filtering = filter != HookFilter.ALL || q.isNotEmpty()
    val first = groups.firstOrNull()?.key
    val isOpen: (String) -> Boolean = { key ->
        if (filtering) key !in closed else key == (open ?: first)
    }
    val toggle: (String) -> Unit = { key ->
        if (filtering) {
            if (!closed.remove(key))
                closed.add(key)
        } else {
            val value = if (isOpen(key)) "" else key
            open = value
            preferences.edit().putString(PREF_EXPANDED, value).apply()
        }
    }

    val summary = pluralStringResource(
        R.plurals.title_hooks_summary,
        groupCount,
        builtin,
        custom,
        groupCount
    )
    val compactSubtitle = stringResource(
        R.string.title_pair,
        stringResource(
            R.string.title_pair,
            pluralStringResource(R.plurals.title_hook_count, data.hooks.size, data.hooks.size),
            pluralStringResource(R.plurals.title_group_count, groupCount, groupCount)
        ),
        stringResource(filter.title)
    )

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

    var fabExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var previous = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { now ->
                val delta = (now.first - previous.first) * 10000 + (now.second - previous.second)
                if (delta > 0) fabExpanded = false
                else if (delta < 0) fabExpanded = true
                previous = now
            }
    }
    LaunchedEffect(filterIndex, query) { fabExpanded = true }

    val emptyText = when {
        data.hooks.isEmpty() -> R.string.title_no_hooks
        filter == HookFilter.CUSTOM && q.isEmpty() -> R.string.title_no_custom_hooks
        q.isEmpty() -> R.string.title_filter_empty
        else -> R.string.title_no_matches
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp + navSpace),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                HooksHeader(
                    searchOpen = searchOpen,
                    query = query,
                    title = stringResource(R.string.nav_hooks),
                    summary = summary,
                    filter = filter,
                    counts = counts,
                    focusRequester = focusRequester,
                    headerAlpha = 1f - compactAlpha,
                    onHeaderHeight = { height -> if (height != headerHeight) headerHeight = height },
                    onQuery = { query = it },
                    onFilter = { filterIndex = it.ordinal },
                    onDownloads = onDownloads,
                    onSearch = { if (searchOpen) closeSearch() else openSearch() },
                    onImport = onImport,
                    onExport = onExport
                )
            }

            groups.forEach { group ->
                item(key = group.key) {
                    HookGroupCard(
                        group = group,
                        expanded = isOpen(group.key),
                        builtinIds = data.builtinIds,
                        collection = data.collection,
                        onToggle = { toggle(group.key) },
                        onOpen = onOpen
                    )
                }
            }
        }

        if (compactAlpha > 0.01f)
            HooksCompactBar(
                title = stringResource(R.string.nav_hooks),
                subtitle = compactSubtitle,
                largeText = largeText,
                filter = filter,
                counts = counts,
                alpha = compactAlpha,
                onFilter = { filterIndex = it.ordinal },
                onSearch = {
                    scope.launch { listState.animateScrollToItem(0) }
                    if (!searchOpen)
                        openSearch()
                },
                onImport = onImport,
                onExport = onExport
            )

        if (data.loading)
            Loading(Modifier.align(Alignment.Center))
        else if (groups.isEmpty())
            EmptyMessage(stringResource(emptyText), Modifier.align(Alignment.Center))

        ExtendedFloatingActionButton(
            text = { Text(stringResource(R.string.menu_add)) },
            icon = {
                Icon(
                    painterResource(R.drawable.ic_add),
                    contentDescription = null
                )
            },
            onClick = onAdd,
            expanded = fabExpanded,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp + navSpace)
        )
    }
}

@Composable
private fun HooksHeader(
    searchOpen: Boolean,
    query: String,
    title: String,
    summary: String,
    filter: HookFilter,
    counts: IntArray,
    focusRequester: FocusRequester,
    headerAlpha: Float,
    onHeaderHeight: (Int) -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (HookFilter) -> Unit,
    onDownloads: () -> Unit,
    onSearch: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = headerAlpha }
            .onGloballyPositioned { onHeaderHeight(it.size.height) }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 20.dp, top = 26.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (searchOpen)
                SearchField(
                    query = query,
                    hint = stringResource(R.string.title_search_hooks),
                    onQueryChange = onQuery,
                    modifier = Modifier.weight(1f),
                    focusRequester = focusRequester,
                    onSearch = { keyboard?.hide() }
                )
            else
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Text(
                        text = summary,
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            IconButton(onClick = onDownloads) {
                Icon(
                    painterResource(R.drawable.ic_download),
                    contentDescription = stringResource(R.string.title_downloads)
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
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        painterResource(R.drawable.ic_more),
                        contentDescription = stringResource(R.string.title_more)
                    )
                }
                HooksMenu(menu, { menu = false }, onImport, onExport)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HookFilter.entries.forEach { option ->
                val count = counts[option.ordinal]
                FilterChip(
                    selected = option == filter,
                    onClick = { onFilter(option) },
                    label = {
                        Text(stringResource(R.string.title_count, stringResource(option.title), count))
                    },
                    shape = CircleShape,
                    colors = hookChipColors(),
                    border = null
                )
            }
        }
    }
}

@Composable
private fun HooksMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_import)) },
            onClick = {
                onDismiss()
                onImport()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_export_custom)) },
            onClick = {
                onDismiss()
                onExport()
            }
        )
    }
}

@Composable
private fun HookGroupCard(
    group: HookGroup,
    expanded: Boolean,
    builtinIds: Set<String>,
    collection: List<String>,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit
) {
    val meta = hookGroupMeta(group.hooks)
    val description = stringResource(
        if (expanded) R.string.title_hide_hooks else R.string.title_show_hooks,
        group.title
    )
    val described = stringResource(R.string.title_described3, group.title, meta, description)
    val accent = themeColor(com.google.android.material.R.attr.colorSecondaryContainer)
    val accentText = themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
    val neutral = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
    val neutralText = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .heightIn(min = 58.dp)
                    .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 10.dp)
                    .semantics { contentDescription = described },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(if (expanded) accent else neutral, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(group.icon),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (expanded) accentText else neutralText
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = group.title,
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
                Icon(
                    painterResource(R.drawable.ic_expand),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(20.dp)
                        .rotate(if (expanded) 180f else 0f),
                    tint = if (expanded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            }

            if (expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    group.hooks.forEachIndexed { index, hook ->
                        if (index > 0)
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        HookRow(
                            hook = hook,
                            builtinIds = builtinIds,
                            collection = collection,
                            onClick = { onOpen(hook.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun hookGroupMeta(hooks: List<XHook>): String {
    val custom = hooks.count { !it.isBuiltin }
    val disabled = hooks.count { !it.isEnabled }
    val unavailable = hooks.count { !it.isForThisSdk }
    val parts = ArrayList<String>()
    parts.add(pluralStringResource(R.plurals.title_hook_count, hooks.size, hooks.size))
    if (custom > 0)
        parts.add(pluralStringResource(R.plurals.title_hook_custom_count, custom, custom))
    if (disabled > 0)
        parts.add(pluralStringResource(R.plurals.title_hook_disabled_count, disabled, disabled))
    if (unavailable > 0)
        parts.add(pluralStringResource(R.plurals.title_hook_unavailable_count, unavailable, unavailable))
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HookRow(
    hook: XHook,
    builtinIds: Set<String>,
    collection: List<String>,
    onClick: () -> Unit
) {
    val runs = hook.isAvailable(null, collection)
    val badges = ArrayList<Pair<String, Ui.Badge>>()
    // Built-in definitions, which most are, have no badge of their own
    if (!hook.isBuiltin)
        badges.add(
            stringResource(
                if (builtinIds.contains(hook.id)) R.string.title_hook_overridden
                else R.string.title_hook_custom
            ) to Ui.Badge.ACCENT
        )
    if (!hook.isEnabled)
        badges.add(stringResource(R.string.title_hook_disabled) to Ui.Badge.NEUTRAL)
    if (!hook.isForThisSdk)
        badges.add(stringResource(R.string.title_hook_unavailable) to Ui.Badge.WARNING)
    if (!collection.contains(hook.collection))
        badges.add(
            stringResource(R.string.title_hook_collection_off, hook.collection) to Ui.Badge.NEUTRAL
        )

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier
                .weight(1f)
        ) {
            Text(
                text = hook.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = if (runs) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = hookTarget(hook),
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
            if (badges.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clearAndSetSemantics {},
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    badges.forEach { (text, style) -> HookBadge(text, style) }
                }
            }
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(18.dp),
            tint = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun HookBadge(text: String, style: Ui.Badge) {
    Surface(
        color = themeColor(style.background),
        contentColor = themeColor(style.text),
        shape = CircleShape
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(start = 7.dp, top = 2.dp, end = 7.dp, bottom = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
        )
    }
}

@Composable
private fun hookChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
)

@Composable
private fun HooksCompactBar(
    title: String,
    subtitle: String,
    largeText: Boolean,
    filter: HookFilter,
    counts: IntArray,
    alpha: Float,
    onFilter: (HookFilter) -> Unit,
    onSearch: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    var filterMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
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
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
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
                            contentDescription = stringResource(R.string.title_filter)
                        )
                    }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        HookFilter.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            R.string.title_count,
                                            stringResource(option.title),
                                            counts[option.ordinal]
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
                Box {
                    IconButton(onClick = { moreMenu = true }) {
                        Icon(
                            painterResource(R.drawable.ic_more),
                            contentDescription = stringResource(R.string.title_more)
                        )
                    }
                    HooksMenu(moreMenu, { moreMenu = false }, onImport, onExport)
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}
