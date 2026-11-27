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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.HashSet
import java.util.Locale

private const val TAG = "XLua.Apps"
private const val PREF_FILTER = "apps_filter"
private const val PREF_SORT = "apps_sort"
// Collapsed sections, comma separated; at first only all apps
private const val PREF_COLLAPSED = "apps_collapsed"

class FragmentApps : FragmentBase() {
    private lateinit var state: AppsState
    private var hiddenNow by mutableStateOf(false)
    // The row of the sheet of the groups of an app, while it is open
    private var groupsRow by mutableStateOf<AdapterApp.Row?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = AppsState(ApplicationEx.getPreferences(requireContext())).apply {
            savedInstanceState?.let { restore(it) }
        }
        hiddenNow = isHidden
    }

    override fun onSaveInstanceState(outState: Bundle) {
        state.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        hiddenNow = hidden
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DoppelTheme {
                    AppsScreen(
                        state = state,
                        hidden = hiddenNow,
                        dispatcher = requireActivity().onBackPressedDispatcher,
                        onRefresh = { state.refreshing = true; load() },
                        onApp = { app -> startActivity(ActivityApp.getIntent(context, app.packageName, app.uid)) },
                        onLaunch = { app -> Ui.launch(requireActivity(), app.packageName, app.getLabel()) },
                        onRestrictAll = ::confirmRestrictAll,
                        onGroups = ::showGroups,
                        onBulk = ::bulk
                    )
                    val sheetRow = groupsRow
                    if (sheetRow != null)
                        GroupsSheet(
                            activity = requireActivity(),
                            app = sheetRow.app,
                            hooks = state.hooks,
                            collection = state.collection,
                            unscoped = sheetRow.unscoped,
                            onBackground = { run(TAG, it) },
                            onChanged = { before, after, available ->
                                sheetChanged(sheetRow.app, before, after, available)
                            },
                            onMore = {
                                groupsRow = null
                                startActivity(
                                    ActivityApp.getIntent(context, sheetRow.app.packageName, sheetRow.app.uid)
                                )
                            },
                            onClosed = { groupsRow = null }
                        )
                }
            }
        }
    }

    override fun load() {
        val context = requireContext().applicationContext
        val started = state.changes
        submit {
            try {
                val hooks = Data.getHooks(context)
                val collection = Data.getCollection(context)
                val scope = Scope.getScope()
                val bound = Scope.available()
                val rows = Data.getApps(context).map { app ->
                    AdapterApp.Row.summarize(app, hooks, collection).apply {
                        unscoped = restricted > 0 && Scope.isMissing(app, scope)
                    }
                }
                Log.i(TAG, "Loaded apps=${rows.size} hooks=${hooks.size}")
                post {
                    if (state.changes != started || (!bound && Scope.available())) {
                        load()
                        return@post
                    }
                    state.hooks = hooks
                    state.collection = collection
                    state.scopeUnknown = scope == null
                    state.loaded = true
                    state.loading = false
                    state.refreshing = false
                    state.rows = rows
                }
            } catch (ex: Throwable) {
                post { state.loading = false; state.refreshing = false }
                loadError(TAG, ex)
            }
        }
    }

    override fun onResume() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(requireContext(), packageChangedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        Scope.setOnBound {
            if (state.loaded && state.scopeUnknown) {
                handlerReload()
            }
        }
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        Scope.setOnBound(null)
        requireContext().unregisterReceiver(packageChangedReceiver)
    }

    private val packageChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received $intent")
            handlerReload(delayed = true)
        }
    }

    // One load for a burst of changes, like a store updating many apps, instead of one load each
    private val handler = Handler(Looper.getMainLooper())

    private fun handlerReload(delayed: Boolean = false) {
        handler.removeCallbacks(reload)
        if (delayed) handler.postDelayed(reload, 500) else handler.post(reload)
    }

    private val reload = Runnable {
        if (isResumed && !isHidden) load()
    }

    override fun onDestroyView() {
        // Without telling the host, like the views of the sheet did; what changed is saved already
        groupsRow = null
        super.onDestroyView()
    }

    private fun confirmRestrictAll(row: AdapterApp.Row) {
        val app = row.app
        Ui.restrictAll(requireContext(), app) { restrictAll(app) }
    }

    private fun showGroups(row: AdapterApp.Row) {
        if (groupsRow != null) return
        groupsRow = row
    }

    // The sheet closed after changes, with the hooks assigned before and now
    private fun sheetChanged(app: XApp, before: Set<String>, after: Set<String>, available: List<XHook>) {
        val activity = requireActivity()
        val groups = available.mapTo(HashSet()) { it.group }
        val restricted = available.filter { after.contains(it.id) }.mapTo(HashSet()) { it.group }
        assigned(app, available, after)
        run(TAG) { post { load() } }
        val text = resources.getQuantityString(R.plurals.msg_groups_restricted, groups.size,
            app.getLabel(), restricted.size, groups.size)
        showUndo(activity, text, app, after, before, available)
        if (after.isNotEmpty()) run(TAG) { offerScope(activity, listOf(app)) }
    }

    private fun restrictAll(app: XApp) {
        val activity = requireActivity()
        val context = activity.applicationContext
        val available = Data.getAvailable(state.hooks, app, state.collection, null)
        val before = app.assignments.mapTo(HashSet()) { it.id }
        val after = available.mapTo(HashSet()) { it.id }
        val text = getString(R.string.msg_restricted_all, app.getLabel())
        assigned(app, available, after)
        run(TAG) {
            try {
                change(context, app, available, before, after)
                showUndo(activity, text, app, after, before, available)
                offerScope(activity, listOf(app))
            } finally { post { load() } }
        }
    }

    private fun showUndo(activity: Activity, text: String, app: XApp, after: Set<String>, before: Set<String>, available: List<XHook>) {
        Ui.message(activity, text, activity.getString(R.string.title_undo)) {
            assigned(app, available, before)
            run(TAG) {
                try { change(activity.applicationContext, app, available, after, before) }
                finally { post { load() } }
            }
        }
    }

    private fun assigned(app: XApp, available: List<XHook>, to: Set<String>) {
        state.changes++
        AdapterApp.setAssigned(app, available, to)
    }

    private fun change(context: Context, app: XApp, available: List<XHook>, from: Set<String>, to: Set<String>) {
        val add = ArrayList<String>()
        val remove = ArrayList<String>()
        for (hook in available) {
            val was = from.contains(hook.id)
            val now = to.contains(hook.id)
            if (now && !was) add.add(hook.id) else if (was && !now) remove.add(hook.id)
        }
        val kill = app.stopsAutomatically()
        if (remove.isNotEmpty()) Data.assignHooks(context, remove, app.packageName, app.uid, true, kill && add.isEmpty(), false)
        if (add.isNotEmpty()) Data.assignHooks(context, add, app.packageName, app.uid, false, kill, false)
    }

    private fun bulk() {
        val groups = Data.getGroupNames(state.hooks)
        if (groups.isEmpty()) return
        Groups.sortByTitle(requireContext(), groups)
        val titles = groups.map { Groups.getTitle(requireContext(), it) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.menu_bulk)
            .setItems(titles) { _, which ->
                val group = groups[which]
                val title = titles[which]
                val shown = state.shownRows
                val restricting = AdapterApp.getRestricting(shown.map { it.app }, group)
                if (restricting.isEmpty()) {
                    val adding = shown.map { it.app }.filterNot { it.isSystemServer() }
                    if (adding.isNotEmpty()) Ui.areYouSure(requireContext(),
                        resources.getQuantityString(R.plurals.msg_bulk_add_sure, adding.size, title, adding.size),
                        R.string.menu_add) { bulk(group, title, adding, true) }
                } else Ui.areYouSure(requireContext(),
                    resources.getQuantityString(R.plurals.msg_bulk_remove_sure, restricting.size, title, restricting.size, shown.size),
                    R.string.title_remove) { bulk(group, title, restricting, false) }
            }.show()
    }

    private fun bulk(group: String, title: String, apps: List<XApp>, assign: Boolean) {
        val activity = requireActivity()
        val context = activity.applicationContext
        val hooks = state.hooks
        val collection = state.collection
        run(TAG) {
            try {
                val done = ArrayList<Bulked>()
                for (app in apps) {
                    val available = Data.getAvailable(hooks, app, collection, group)
                    if (available.isNotEmpty()) {
                        val was = app.assignments.mapTo(HashSet()) { it.id }
                        val now = if (assign) available.mapTo(HashSet()) { it.id } else HashSet()
                        Data.assign(context, app, available, assign)
                        done.add(Bulked(app, available, was, now))
                    }
                }
                Ui.message(activity, activity.resources.getQuantityString(
                    if (assign) R.plurals.msg_bulk_added else R.plurals.msg_bulk_removed, done.size, title, done.size),
                    activity.getString(R.string.title_undo)) {
                    done.forEach { assigned(it.app, it.available, it.before) }
                    run(TAG) {
                        try { done.forEach { change(context, it.app, it.available, it.after, it.before) } }
                        finally { post { load() } }
                    }
                }
                if (assign) offerScope(activity, done.map { it.app })
            } finally { post { load() } }
        }
    }

    private fun offerScope(activity: Activity, apps: List<XApp>) {
        val scope = Scope.getScope()
        val missing = apps.filter { Scope.isMissing(it, scope) }
        if (missing.isEmpty()) return
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            MaterialAlertDialogBuilder(activity)
                .setMessage(resources.getQuantityString(R.plurals.msg_scope_offer, missing.size, missing.first().getLabel(), missing.size))
                .setPositiveButton(R.string.title_add_to_scope) { _, _ ->
                    Ui.message(activity, getString(R.string.msg_scope_requested))
                    run(TAG) {
                        Scope.add(missing.map { it.packageName }) { inScope, failure ->
                            if (inScope) missing.filter { it.stopsAutomatically() }.forEach {
                                runCatching { Data.forceStop(activity.applicationContext, it) }.onFailure { ex -> error(TAG, ex) }
                            } else if (failure != null) Ui.message(activity, getString(R.string.msg_scope_failed, failure))
                            post { load() }
                        }
                    }
                }
                .setNegativeButton(R.string.title_not_now, null)
                .show()
        }
    }

    private data class Bulked(val app: XApp, val available: List<XHook>, val before: Set<String>, val after: Set<String>)
}


private class AppsState(private val prefs: android.content.SharedPreferences) {
    fun restore(bundle: Bundle) {
        query = bundle.getString("apps_query", "")
        // The field is shown for the text restored with it
        searchOpen = query.isNotEmpty()
    }

    fun save(bundle: Bundle) {
        bundle.putString("apps_query", query)
    }
    var rows by mutableStateOf<List<AdapterApp.Row>>(emptyList())
    var hooks by mutableStateOf<List<XHook>>(emptyList())
    var collection by mutableStateOf<List<String>>(emptyList())
    var loaded by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var refreshing by mutableStateOf(false)
    var scopeUnknown by mutableStateOf(false)
    var changes by mutableIntStateOf(0)
    // A stored filter which no longer exists falls back to user apps, like the former filter restricted
    var filter by mutableStateOf(runCatching {
        AdapterApp.Filter.valueOf(prefs.getString(PREF_FILTER, AdapterApp.Filter.USER.name)!!)
    }.getOrDefault(AdapterApp.Filter.USER))
        private set
    var sort by mutableStateOf(runCatching {
        AdapterApp.Sort.valueOf(prefs.getString(PREF_SORT, AdapterApp.Sort.NAME.name)!!)
    }.getOrDefault(AdapterApp.Sort.NAME))
        private set
    val collapsed = mutableStateListOf<AdapterApp.Section>().apply {
        val saved = prefs.getString(PREF_COLLAPSED, AdapterApp.Section.ALL.name) ?: AdapterApp.Section.ALL.name
        saved.split(',').forEach { name -> AdapterApp.Section.entries.firstOrNull { it.name == name }?.let(::add) }
    }
    var query by mutableStateOf("")
    var searchOpen by mutableStateOf(false)
    var searchClosed by mutableStateOf<Set<AdapterApp.Section>>(emptySet())
    var shownRows by mutableStateOf<List<AdapterApp.Row>>(emptyList())
        private set

    fun updateFilter(value: AdapterApp.Filter) {
        filter = value
        prefs.edit().putString(PREF_FILTER, value.name).apply()
    }

    fun updateSort(value: AdapterApp.Sort) {
        sort = value
        prefs.edit().putString(PREF_SORT, value.name).apply()
    }
    fun toggle(section: AdapterApp.Section) {
        if (!collapsed.remove(section)) collapsed.add(section)
        prefs.edit().putString(PREF_COLLAPSED, collapsed.joinToString(",") { it.name }).apply()
    }

    fun data(): List<AppListItem> {
        var q = query.trim()
        var restricted = false; var unrestricted = false; var system = false; var user = false
        while (q.isNotEmpty()) when (q.first()) {
            '!' -> { restricted = true; q = q.drop(1) }
            '?' -> { unrestricted = true; q = q.drop(1) }
            '#' -> { system = true; q = q.drop(1) }
            '@' -> { user = true; q = q.drop(1) }
            else -> break
        }
        val uid = q.toIntOrNull()
        val queryObject = Ui.Query(q)
        val result = rows.filter { row ->
            val app = row.app
            val passesFilter = when (filter) {
                AdapterApp.Filter.USER -> !app.system
                AdapterApp.Filter.SYSTEM -> app.system
                AdapterApp.Filter.ALL -> true
            }
            passesFilter && !(restricted && !row.isRestricted()) && !(unrestricted && row.isRestricted()) &&
                    !(system && !app.system) && !(user && app.system) &&
                    (q.isEmpty() || app.uid == uid || row.matches(queryObject))
        }
        shownRows = result
        val now = System.currentTimeMillis()
        val all = linkedMapOf<AdapterApp.Section, MutableList<AdapterApp.Row>>()
        AdapterApp.Section.entries.forEach { all[it] = mutableListOf() }
        result.forEach { row ->
            val section = row.getSection(now)
            if (section != AdapterApp.Section.ALL) all.getValue(section).add(row)
            all.getValue(AdapterApp.Section.ALL).add(row)
        }
        AdapterApp.Section.entries.forEach { section ->
            val rows = all.getValue(section)
            when (section) {
                AdapterApp.Section.ATTENTION -> rows.sortWith(AdapterApp.comparator(AdapterApp.Sort.NAME, Groups.collator()))
                AdapterApp.Section.NEW -> rows.sortByDescending { it.app.installed }
                AdapterApp.Section.RECENT -> rows.sortByDescending { it.getRecent() }
                AdapterApp.Section.ALL -> rows.sortWith(AdapterApp.comparator(sort, Groups.collator()))
            }
        }
        val searching = query.isNotBlank()
        val items = ArrayList<AppListItem>()
        AdapterApp.Section.entries.forEach { section ->
            val sectionRows = all.getValue(section)
            if (sectionRows.isEmpty()) return@forEach
            val open = if (searching) section !in searchClosed else section !in collapsed
            items.add(AppListItem.Header(section, sectionRows.size, open))
            if (open) sectionRows.forEachIndexed { index, row ->
                items.add(AppListItem.App(row, section, index == 0, index == sectionRows.lastIndex))
            }
        }
        return items
    }

    fun summary(): AdapterApp.Summary = AdapterApp.Summary.of(rows, filter, System.currentTimeMillis())
}

private sealed interface AppListItem {
    data class Header(val section: AdapterApp.Section, val count: Int, val expanded: Boolean) : AppListItem
    data class App(val row: AdapterApp.Row, val section: AdapterApp.Section, val first: Boolean, val last: Boolean) : AppListItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsScreen(
    state: AppsState,
    hidden: Boolean,
    dispatcher: OnBackPressedDispatcher,
    onRefresh: () -> Unit,
    onApp: (XApp) -> Unit,
    onLaunch: (XApp) -> Unit,
    onRestrictAll: (AdapterApp.Row) -> Unit,
    onGroups: (AdapterApp.Row) -> Unit,
    onBulk: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val navSpace = rememberNavSpace()
    val largeText = density.fontScale > 1.3f
    val showOverview = remember(configuration) { resources.getBoolean(R.bool.show_overview) }

    val items = remember(
        state.rows, state.filter, state.sort, state.query,
        state.collapsed.toList(), state.searchClosed
    ) { state.data() }
    val summary = remember(state.rows, state.filter) { state.summary() }
    // The counts of the filter chips follow the filter, not the search
    val counts = remember(state.rows) {
        intArrayOf(
            state.rows.count { !it.app.system },
            state.rows.count { it.app.system },
            state.rows.size
        )
    }

    fun closeSearch() {
        state.query = ""
        state.searchOpen = false
        keyboard?.hide()
    }

    // Back closes the search first, like it did for the views
    DisposableEffect(state.searchOpen, hidden) {
        val callback = object : OnBackPressedCallback(state.searchOpen && !hidden) {
            override fun handleOnBackPressed() {
                closeSearch()
            }
        }
        dispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }

    // The keyboard opens when the search is opened, not when it is restored with the screen
    var wasOpen by remember { mutableStateOf(state.searchOpen) }
    LaunchedEffect(state.searchOpen) {
        if (state.searchOpen && !wasOpen) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        if (!state.searchOpen)
            keyboard?.hide()
        wasOpen = state.searchOpen
    }

    // The header scrolls away; the compact bar fades in over the last part of it, see AppsCompactBar
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
    ) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp + navSpace)
            ) {
                item(key = "header") {
                    AppsHeader(
                        state = state,
                        summary = summary,
                        counts = counts,
                        showOverview = showOverview,
                        focusRequester = focusRequester,
                        alpha = 1f - compactAlpha,
                        onHeight = { height -> if (height != headerHeight) headerHeight = height },
                        onOverview = {
                            (context as? ActivityMain)?.selectTab(R.id.nav_activity)
                        },
                        onToggleSearch = {
                            if (state.searchOpen)
                                closeSearch()
                            else
                                state.searchOpen = true
                        },
                        onSearchAction = { keyboard?.hide() },
                        onBulk = onBulk
                    )
                }
                items(
                    items = items,
                    key = { item ->
                        when (item) {
                            is AppListItem.Header -> "header-${item.section}"
                            is AppListItem.App -> "${item.section}-${item.row.app.packageName}:${item.row.app.uid}"
                        }
                    }
                ) { item ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        when (item) {
                            is AppListItem.Header -> SectionHeader(item) {
                                // A section closed while searching is not remembered, like in the Hooks tab
                                if (state.query.isNotBlank())
                                    state.searchClosed = if (item.section in state.searchClosed)
                                        state.searchClosed - item.section
                                    else
                                        state.searchClosed + item.section
                                else
                                    state.toggle(item.section)
                            }

                            is AppListItem.App -> AppRow(
                                item = item,
                                largeText = largeText,
                                onApp = onApp,
                                onLaunch = onLaunch,
                                onRestrictAll = onRestrictAll,
                                onGroups = onGroups
                            )
                        }
                    }
                }
            }
        }

        if (compactAlpha > 0.01f)
            AppsCompactBar(
                state = state,
                summary = summary,
                counts = counts,
                largeText = largeText,
                alpha = compactAlpha,
                onSearch = {
                    scope.launch { listState.animateScrollToItem(0) }
                    if (!state.searchOpen)
                        state.searchOpen = true
                },
                onBulk = onBulk
            )

        if (state.loading)
            Loading(Modifier.align(Alignment.Center))
        else if (state.loaded && items.isEmpty())
            EmptyMessage(
                stringResource(
                    if (state.query.isNotBlank()) R.string.title_no_matches else R.string.title_no_apps
                ),
                Modifier.align(Alignment.Center)
            )
    }
}

@Composable
private fun AppsHeader(
    state: AppsState,
    summary: AdapterApp.Summary,
    counts: IntArray,
    showOverview: Boolean,
    focusRequester: FocusRequester,
    alpha: Float,
    onHeight: (Int) -> Unit,
    onOverview: () -> Unit,
    onToggleSearch: () -> Unit,
    onSearchAction: () -> Unit,
    onBulk: () -> Unit
) {
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    // Screen readers tell the order
    val sortDescription = stringResource(R.string.title_sorted_by, stringResource(sortTitle(state.sort)))

    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .onGloballyPositioned { onHeight(it.size.height) }
    ) {
        // The title at the height of the titles of the other tabs
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 20.dp, top = 26.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.searchOpen)
                SearchField(
                    query = state.query,
                    hint = stringResource(R.string.title_search_apps),
                    onQueryChange = {
                        state.query = it
                        // Another search opens all sections again
                        state.searchClosed = emptySet()
                    },
                    modifier = Modifier.weight(1f),
                    focusRequester = focusRequester,
                    onSearch = onSearchAction
                )
            else
                Text(
                    text = stringResource(R.string.nav_apps),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium
                )
            IconButton(onClick = onToggleSearch) {
                Icon(
                    painterResource(if (state.searchOpen) R.drawable.ic_close else R.drawable.ic_search),
                    contentDescription = stringResource(
                        if (state.searchOpen) R.string.title_close_search else R.string.menu_search
                    )
                )
            }
            Box {
                IconButton(onClick = { moreMenu = true }) {
                    Icon(
                        painterResource(R.drawable.ic_more),
                        contentDescription = stringResource(R.string.title_more)
                    )
                }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_bulk)) },
                        onClick = {
                            moreMenu = false
                            onBulk()
                        }
                    )
                }
            }
        }

        // How many apps of the filter are restricted and what there is to look at; opens the Activity tab
        if (showOverview)
            OverviewCard(summary, state.filter, onOverview)

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AdapterApp.Filter.entries.forEach { option ->
                    FilterChip(
                        selected = state.filter == option,
                        onClick = { state.updateFilter(option) },
                        label = {
                            // The count follows the first load; the chips have their title until then
                            Text(
                                if (state.loaded)
                                    stringResource(
                                        R.string.title_count,
                                        stringResource(filterTitle(option)),
                                        counts[option.ordinal]
                                    )
                                else
                                    stringResource(filterTitle(option))
                            )
                        },
                        shape = CircleShape,
                        border = null,
                        colors = filterChipColors()
                    )
                }
            }
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(painterResource(R.drawable.ic_sort), contentDescription = sortDescription)
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    AdapterApp.Sort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(sortTitle(option))) },
                            onClick = {
                                state.updateSort(option)
                                sortMenu = false
                            },
                            trailingIcon = if (state.sort == option) {
                                { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                            } else null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(summary: AdapterApp.Summary, filter: AdapterApp.Filter, onClick: () -> Unit) {
    val restricted = pluralStringResource(
        overviewTitle(filter), summary.total, summary.restricted, summary.total
    )
    val parts = buildList {
        if (summary.review > 0)
            add(pluralStringResource(R.plurals.title_overview_review, summary.review, summary.review))
        if (summary.recent > 0)
            add(pluralStringResource(R.plurals.title_overview_recent, summary.recent, summary.recent))
    }
    val meta = if (parts.isEmpty()) stringResource(R.string.title_overview_none) else parts.joinToString(" · ")
    // Screen readers hear what the card opens, like the card told the views
    val described = stringResource(
        R.string.title_described3,
        stringResource(R.string.title_overview),
        restricted,
        meta
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 10.dp, end = 16.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = described },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, end = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { summary.percent / 100f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 6.dp
                )
                Text("${summary.percent}%", style = MaterialTheme.typography.labelLarge)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(restricted, style = MaterialTheme.typography.titleMedium)
                Text(
                    meta,
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        }
    }
}

@Composable
private fun SectionHeader(item: AppListItem.Header, onToggle: () -> Unit) {
    val text = stringResource(
        R.string.title_count,
        stringResource(sectionTitle(item.section)),
        item.count
    )
    val action = stringResource(if (item.expanded) R.string.title_collapse else R.string.title_expand)
    val described = stringResource(R.string.title_described, text, action)
    // Turning the arrow when the section is toggled, not when the header is shown
    val rotation by animateFloatAsState(
        targetValue = if (item.expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "expand"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = described
            }
            .padding(start = 4.dp, top = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall.copy(color = themeColor(sectionColor(item.section)))
        )
        Icon(
            painterResource(R.drawable.ic_expand),
            contentDescription = null,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(20.dp)
                .rotate(rotation),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppRow(
    item: AppListItem.App,
    largeText: Boolean,
    onApp: (XApp) -> Unit,
    onLaunch: (XApp) -> Unit,
    onRestrictAll: (AdapterApp.Row) -> Unit,
    onGroups: (AdapterApp.Row) -> Unit
) {
    val row = item.row
    val app = row.app
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    // The apps of a section form one rounded block: failed hooks on the error color, new apps on the warning color
    val (background, metaColor) = when (item.section) {
        AdapterApp.Section.ATTENTION -> themeColor(com.google.android.material.R.attr.colorErrorContainer) to
                themeColor(com.google.android.material.R.attr.colorOnErrorContainer)
        AdapterApp.Section.NEW -> themeColor(R.attr.colorWarningContainer) to
                themeColor(R.attr.colorOnWarningContainer)
        else -> scheme.surfaceContainerLowest to scheme.onSurfaceVariant
    }
    val shape = RoundedCornerShape(
        topStart = if (item.first) 20.dp else 0.dp,
        topEnd = if (item.first) 20.dp else 0.dp,
        bottomStart = if (item.last) 20.dp else 0.dp,
        bottomEnd = if (item.last) 20.dp else 0.dp
    )
    val appName = app.getLabel()
    val summary = when (item.section) {
        AdapterApp.Section.ATTENTION, AdapterApp.Section.NEW ->
            if (row.error) stringResource(R.string.title_review_error)
            else stringResource(R.string.msg_installed, Ui.ago(context, app.installed))
        AdapterApp.Section.RECENT ->
            // What was restricted last, else that the app was restricted, and when
            stringResource(
                R.string.title_pair,
                if (row.used > 0 && row.used >= row.assigned) Groups.getTitle(context, row.usedGroup)
                else stringResource(R.string.title_restrictions_added),
                Ui.ago(context, row.getRecent())
            )
        AdapterApp.Section.ALL -> when {
            row.restricted == 0 -> stringResource(R.string.title_not_restricted)
            row.used > 0 -> stringResource(
                R.string.title_pair,
                Groups.getTitle(context, row.usedGroup),
                Ui.ago(context, row.used)
            )
            else -> stringResource(R.string.title_no_calls)
        }
    }
    val coverage = when {
        row.restricted == 0 -> stringResource(R.string.title_not_restricted)
        row.unscoped -> stringResource(R.string.title_not_in_scope_hint)
        else -> pluralStringResource(R.plurals.title_groups_restricted, row.groups, row.restricted, row.groups)
    }
    val persistentLabel = stringResource(R.string.badge_persistent)
    val launchLabel = stringResource(R.string.menu_launch)
    val groupsLabel = stringResource(R.string.title_groups)
    val showGroupsLabel = stringResource(R.string.title_show_groups)
    val actionText = stringResource(
        if (row.canRestrictAll()) R.string.title_restrict_all_groups else R.string.title_review
    )
    // Screen readers tell which app the button is for
    val actionDescription = stringResource(R.string.title_described, actionText, appName)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
    ) {
        // Between the rows of a section, a shade of their background
        if (!item.first)
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp),
                color = lerp(background, scheme.onSurface, 0.08f)
            )
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 66.dp)
                .combinedClickable(
                    role = Role.Button,
                    onClick = { onApp(app) },
                    onLongClickLabel = launchLabel,
                    onLongClick = { onLaunch(app) }
                )
                .semantics {
                    // Screen readers offer the groups of the app too
                    customActions = listOf(
                        CustomAccessibilityAction(groupsLabel) {
                            onGroups(row)
                            true
                        }
                    )
                }
                .padding(start = 16.dp, top = 11.dp, end = 14.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(app.packageName, app.icon, 42.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        appName,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    // Both badges would leave little room for the name, and persistent apps are system apps
                    if (app.persistent)
                        AppBadge(
                            persistentLabel,
                            stringResource(
                                R.string.title_described,
                                persistentLabel,
                                stringResource(R.string.title_apply_reboot)
                            )
                        )
                    else if (app.system)
                        AppBadge(stringResource(R.string.title_filter_system))
                }
                Text(
                    summary,
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall.copy(color = metaColor),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.section == AdapterApp.Section.ATTENTION || item.section == AdapterApp.Section.NEW) {
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { if (row.canRestrictAll()) onRestrictAll(row) else onApp(app) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        actionText,
                        Modifier.semantics { contentDescription = actionDescription },
                        maxLines = if (largeText) 2 else 1
                    )
                }
            } else {
                Spacer(Modifier.width(2.dp))
                CoveragePill(row, coverage, showGroupsLabel) { onGroups(row) }
            }
        }
        if (item.last)
            Spacer(Modifier.height(2.dp))
    }
}

// How much of the app is restricted: all groups on the secondary color, some on the warning color, none in grey;
// screen readers get the words, and tapping it shows the groups of the app, see GroupsSheet
@Composable
private fun CoveragePill(
    row: AdapterApp.Row,
    description: String,
    showGroups: String,
    onClick: () -> Unit
) {
    val background = when {
        row.restricted == 0 -> MaterialTheme.colorScheme.surfaceContainerHigh
        row.unscoped -> themeColor(R.attr.colorWarningContainer)
        row.restricted >= row.groups -> MaterialTheme.colorScheme.secondaryContainer
        else -> themeColor(R.attr.colorWarningContainer)
    }
    val foreground = when {
        row.restricted == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        row.unscoped -> themeColor(R.attr.colorOnWarningContainer)
        row.restricted >= row.groups -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> themeColor(R.attr.colorOnWarningContainer)
    }
    val text = when {
        row.restricted == 0 -> stringResource(R.string.title_off)
        row.unscoped -> stringResource(R.string.title_not_in_scope)
        else -> String.format(Locale.getDefault(), "%d/%d", row.restricted, row.groups)
    }
    val described = stringResource(R.string.title_described, description, showGroups)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = described },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .heightIn(min = 26.dp)
                .clip(CircleShape)
                .background(background)
                .clearAndSetSemantics {}
                .padding(horizontal = 10.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = foreground,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@Composable
private fun filterChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
)

@Composable
private fun AppsCompactBar(
    state: AppsState,
    summary: AdapterApp.Summary,
    counts: IntArray,
    largeText: Boolean,
    alpha: Float,
    onSearch: () -> Unit,
    onBulk: () -> Unit
) {
    var filterMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val filterText = stringResource(
        R.string.title_count,
        stringResource(filterTitle(state.filter)),
        counts[state.filter.ordinal]
    )
    val subtitle = stringResource(
        R.string.title_pair,
        stringResource(R.string.title_compact_apps, summary.restricted, summary.total),
        filterText
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
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // A small form of the overview, before the title
                Box(
                    Modifier
                        .size(32.dp)
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { summary.percent / 100f },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 4.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.nav_apps),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // With large text both lines do not fit in the fixed height of the bar
                    if (!largeText)
                        Text(
                            subtitle,
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
                            contentDescription = stringResource(R.string.title_filter_by, filterText)
                        )
                    }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        AdapterApp.Filter.entries.forEach { option ->
                            val text = stringResource(
                                R.string.title_count,
                                stringResource(filterTitle(option)),
                                counts[option.ordinal]
                            )
                            DropdownMenuItem(
                                text = { Text(text) },
                                onClick = {
                                    filterMenu = false
                                    state.updateFilter(option)
                                },
                                trailingIcon = if (state.filter == option) {
                                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                                } else null
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
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_bulk)) },
                            onClick = {
                                moreMenu = false
                                onBulk()
                            }
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

private fun filterTitle(value: AdapterApp.Filter) = when (value) {
    AdapterApp.Filter.USER -> R.string.title_filter_user
    AdapterApp.Filter.SYSTEM -> R.string.title_filter_system
    AdapterApp.Filter.ALL -> R.string.title_all
}

private fun sortTitle(value: AdapterApp.Sort) = when (value) {
    AdapterApp.Sort.NAME -> R.string.sort_name
    AdapterApp.Sort.RECENT -> R.string.sort_recent
    AdapterApp.Sort.INSTALLED -> R.string.sort_installed
}

private fun overviewTitle(value: AdapterApp.Filter) = when (value) {
    AdapterApp.Filter.USER -> R.plurals.title_overview_user
    AdapterApp.Filter.SYSTEM -> R.plurals.title_overview_system
    AdapterApp.Filter.ALL -> R.plurals.title_overview_all
}

private fun sectionTitle(value: AdapterApp.Section) = when (value) {
    AdapterApp.Section.ATTENTION -> R.string.section_apps_attention
    AdapterApp.Section.NEW -> R.string.section_apps_new
    AdapterApp.Section.RECENT -> R.string.section_apps_recent
    AdapterApp.Section.ALL -> R.string.section_apps_all
}

private fun sectionColor(value: AdapterApp.Section) = when (value) {
    AdapterApp.Section.ATTENTION -> androidx.appcompat.R.attr.colorError
    AdapterApp.Section.NEW -> R.attr.colorWarning
    else -> androidx.appcompat.R.attr.colorPrimary
}
