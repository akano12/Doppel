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
import android.content.Intent
import android.graphics.Bitmap
import android.icu.text.RelativeDateTimeFormatter
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Calendar
import java.util.LinkedHashMap
import java.util.Locale

// What hooks did: the last restriction of each hook per app, and hooks which failed; of all apps or of one app.
// The screen is the Activity tab of the main screen and the log of one app, see ActivityLog.
class FragmentLog : FragmentBase() {
    private var only: String? = null
    private var onlyUid = -1
    private var label: String? = null
    private lateinit var state: LogState
    // Back closes the search of the tab shown only, like TitleSearch does for the views
    private var hiddenNow by mutableStateOf(false)

    // The log of one app, see ActivityLog
    companion object {
        private const val TAG = "XLua.Log"
        private const val ARG_PACKAGE = "package"
        private const val ARG_UID = "uid"
        private const val ARG_LABEL = "label"

        @JvmStatic
        fun forApp(packageName: String?, uid: Int, label: String?): FragmentLog {
            val args = Bundle()
            args.putString(ARG_PACKAGE, packageName)
            args.putInt(ARG_UID, uid)
            args.putString(ARG_LABEL, label)
            return FragmentLog().apply { arguments = args }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            only = args.getString(ARG_PACKAGE)
            onlyUid = args.getInt(ARG_UID, -1)
            label = args.getString(ARG_LABEL)
        }
        // Keeps the search and the rows shown in full when the screen is recreated, like the view state did
        state = ViewModelProvider(this)[LogState::class.java]
        hiddenNow = isHidden
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        hiddenNow = hidden
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val dispatcher = requireActivity().onBackPressedDispatcher
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DoppelTheme {
                    LogScreen(
                        only = only,
                        label = label,
                        state = state,
                        hidden = hiddenNow,
                        dispatcher = dispatcher,
                        onBack = { dispatcher.onBackPressed() },
                        onRefresh = {
                            state.refreshing = true
                            load()
                        }
                    )
                }
            }
        }
    }

    override fun load() {
        val context = requireContext()
        val appContext = context.applicationContext
        val only = this.only
        val onlyUid = this.onlyUid
        if (!state.loadedOnce)
            state.loading = true
        submit {
            try {
                val apps = HashMap<String, XApp>()
                val groups = HashMap<String, String>() // By hook id, of the hooks available
                val errors = ArrayList<Entry>()
                // The log of one app needs only that app; restrictions of other apps are left out, like of uninstalled apps
                val list = ArrayList<XApp>()
                if (only == null)
                    list.addAll(Data.getApps(appContext))
                else {
                    val app = Data.getApp(appContext, only, onlyUid)
                    if (app != null)
                        list.add(app)
                }
                for (app in list) {
                    apps[app.packageName + ":" + app.uid] = app
                    for (assignment in app.assignments) {
                        groups[assignment.id] = assignment.group
                        if (assignment.exception != null) {
                            val entry = Entry()
                            entry.app = app
                            entry.hook = assignment.id
                            entry.group = assignment.group
                            // The time of the last error, see XProvider.Report.write; a restriction can be later
                            entry.time = assignment.installed
                            entry.exception = assignment.exception
                            errors.add(entry)
                        }
                    }
                }

                val entries = ArrayList<Entry>()
                Data.getLog(appContext).use { cursor ->
                    while (cursor.moveToNext()) {
                        val pkg = cursor.getString(cursor.getColumnIndexOrThrow("package"))
                        val uid = cursor.getInt(cursor.getColumnIndexOrThrow("uid"))
                        val app = apps[pkg + ":" + uid] ?: continue // Uninstalled
                        val entry = Entry()
                        entry.app = app
                        entry.hook = cursor.getString(cursor.getColumnIndexOrThrow("hook"))
                        entry.time = cursor.getLong(cursor.getColumnIndexOrThrow("used"))
                        entry.oldValue = cursor.getString(cursor.getColumnIndexOrThrow("old"))
                        entry.newValue = cursor.getString(cursor.getColumnIndexOrThrow("new"))
                        entry.group = groups[entry.hook]
                        entries.add(entry)
                    }
                }
                errors.sortByDescending { it.time }

                post {
                    state.entries = entries
                    state.errors = errors
                    state.loadedOnce = true
                    state.loading = false
                    state.refreshing = false
                }
            } catch (ex: Throwable) {
                post {
                    state.loading = false
                    state.refreshing = false
                }
                loadError(TAG, ex)
            }
        }
    }
}

internal enum class LogFilter { ALL, TODAY, ERRORS }

private val FILTER_TITLES = intArrayOf(
    R.string.title_all,
    R.string.title_today,
    R.string.title_stat_errors
)

internal class Entry {
    lateinit var app: XApp
    lateinit var hook: String
    var group: String? = null
    var time: Long = 0
    var oldValue: String? = null
    var newValue: String? = null
    var exception: String? = null
    private var haystack: Ui.Haystack? = null

    // Lower-cased once, since a search matches each entry for each letter typed
    fun matches(context: Context, query: Ui.Query): Boolean {
        var haystack = this.haystack
        if (haystack == null) {
            haystack = Ui.Haystack(
                app.getLabel(),
                app.packageName,
                hook,
                group,
                group?.let { Groups.getTitle(context, it) }
            )
            this.haystack = haystack
        }
        return haystack.has(query)
    }
}

private fun entryKey(entry: Entry): String =
    entry.app.packageName + "/" + entry.app.uid + "/" + entry.hook

// Public since the ViewModel factory creates it by reflection
class LogState(private val saved: SavedStateHandle) : ViewModel() {
    internal var filter by mutableStateOf(LogFilter.ALL)
    internal var queryOpen by mutableStateOf(saved.get<Boolean>(STATE_SEARCH) ?: false)
    internal var query by mutableStateOf(saved.get<String>(STATE_QUERY) ?: "")
    internal var loading by mutableStateOf(true)
    internal var refreshing by mutableStateOf(false)
    internal var loadedOnce by mutableStateOf(false)
    internal var entries by mutableStateOf<List<Entry>>(emptyList())
    internal var errors by mutableStateOf<List<Entry>>(emptyList())
    internal val expanded = mutableStateListOf<String>().apply {
        saved.get<ArrayList<String>>(STATE_EXPANDED)?.let { addAll(it) }
    }
    // Entries with a text cut off, so a reload shows their arrow at once instead of after the layout, which jumps
    internal val clipped = mutableStateMapOf<String, Boolean>()

    internal fun updateQuery(text: String) {
        query = text
        saved[STATE_QUERY] = text
    }

    internal fun updateSearchOpen(open: Boolean) {
        queryOpen = open
        saved[STATE_SEARCH] = open
    }

    internal fun toggleExpanded(key: String) {
        if (!expanded.remove(key))
            expanded.add(key)
        saved[STATE_EXPANDED] = ArrayList(expanded)
    }

    companion object {
        private const val STATE_SEARCH = "search"
        private const val STATE_QUERY = "query"
        private const val STATE_EXPANDED = "expanded"
    }
}

private sealed interface LogItem {
    class Header(val text: String) : LogItem
    class Row(val entry: Entry, val first: Boolean, val last: Boolean) : LogItem
}

private class ScreenData(
    val items: List<LogItem>,
    val countText: String,
    val callsLabel: String,
    val stats: String,
    val statsError: Boolean,
    val bars: IntArray,
    val barsDescription: String,
    val axis: List<String?>,
    val compactSummary: String,
    val emptyText: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogScreen(
    only: String?,
    label: String?,
    state: LogState,
    hidden: Boolean,
    dispatcher: OnBackPressedDispatcher,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val largeText = density.fontScale > 1.3f
    val showOverview = remember(configuration) { context.resources.getBoolean(R.bool.show_overview) }
    val title = if (only == null) stringResource(R.string.nav_activity) else (label ?: "")

    val data = remember(state.entries, state.errors, state.filter, state.query, only) {
        buildScreenData(context, only, state.entries, state.errors, state.filter, state.query)
    }

    fun closeSearch() {
        state.updateQuery("")
        state.updateSearchOpen(false)
        keyboard?.hide()
    }

    // Back closes the search first, like it does for the views, see TitleSearch
    DisposableEffect(state.queryOpen, hidden) {
        val callback = object : OnBackPressedCallback(state.queryOpen && !hidden) {
            override fun handleOnBackPressed() {
                closeSearch()
            }
        }
        dispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }

    // The keyboard opens when the search is opened, not when it is restored with the screen
    var wasOpen by remember { mutableStateOf(state.queryOpen) }
    LaunchedEffect(state.queryOpen) {
        if (state.queryOpen && !wasOpen)
            focusRequester.requestFocus()
        if (!state.queryOpen)
            keyboard?.hide()
        wasOpen = state.queryOpen
    }

    // The header scrolls away; the compact bar fades in over the last part of it, see CompactBar
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

    val navSpace = with(density) {
        (context.resources.getDimensionPixelSize(R.dimen.nav_floating_height) +
                context.resources.getDimensionPixelSize(R.dimen.nav_floating_margin)).toDp()
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
                contentPadding = PaddingValues(bottom = 16.dp + if (only == null) navSpace else 0.dp)
            ) {
                item {
                    LogHeader(
                        only = only,
                        label = label,
                        data = data,
                        state = state,
                        showOverview = showOverview,
                        largeText = largeText,
                        focusRequester = focusRequester,
                        onHeaderHeight = { height -> if (height != headerHeight) headerHeight = height },
                        onBack = onBack,
                        onToggleSearch = {
                            if (state.queryOpen)
                                closeSearch()
                            else
                                state.updateSearchOpen(true)
                        }
                    )
                }
                items(data.items.size) { index ->
                    when (val item = data.items[index]) {
                        is LogItem.Header -> Text(
                            text = item.text.uppercase(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 6.dp)
                                .semantics { heading() },
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 0.08.sp
                            )
                        )

                        is LogItem.Row -> {
                            val key = entryKey(item.entry)
                            Box(Modifier.padding(horizontal = 16.dp)) {
                                LogRow(
                                    entry = item.entry,
                                    first = item.first,
                                    last = item.last,
                                    full = state.expanded.contains(key),
                                    clipped = state.clipped[key] == true,
                                    only = only,
                                    onToggle = { state.toggleExpanded(key) },
                                    onClipped = {
                                        if (state.clipped[key] != true)
                                            state.clipped[key] = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (compactAlpha > 0.01f) {
            LogCompactBar(
                only = only,
                title = title,
                data = data,
                filter = state.filter,
                largeText = largeText,
                alpha = compactAlpha,
                onBack = onBack,
                onFilter = { state.filter = it },
                onSearch = {
                    scope.launch { listState.animateScrollToItem(0) }
                    if (!state.queryOpen)
                        state.updateSearchOpen(true)
                }
            )
        }

        if (state.loading)
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics {
                        contentDescription = context.getString(R.string.title_loading)
                        liveRegion = LiveRegionMode.Polite
                    }
            )
        else if (data.items.isEmpty())
            Text(
                text = stringResource(data.emptyText),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                textAlign = TextAlign.Center
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogHeader(
    only: String?,
    label: String?,
    data: ScreenData,
    state: LogState,
    showOverview: Boolean,
    largeText: Boolean,
    focusRequester: FocusRequester,
    onHeaderHeight: (Int) -> Unit,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onHeaderHeight(it.size.height) }
    ) {
        // The title at the height of the title of the Apps tab; the log of one app has back and the app as the title
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = if (only == null) 20.dp else 4.dp, top = 26.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (only != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.title_back)
                    )
                }
                Spacer(Modifier.width(8.dp))
            }

            if (state.queryOpen) {
                SearchField(
                    query = state.query,
                    hint = stringResource(if (only == null) R.string.title_search_log else R.string.title_search_log_app),
                    focusRequester = focusRequester,
                    onQueryChange = state::updateQuery,
                    modifier = Modifier.weight(1f)
                )
            } else
                Text(
                    text = if (only == null) stringResource(R.string.nav_activity) else (label ?: ""),
                    modifier = Modifier.weight(1f),
                    style = if (only == null)
                        MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface)
                    else
                        MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

            IconButton(onClick = onToggleSearch) {
                Icon(
                    painterResource(if (state.queryOpen) R.drawable.ic_close else R.drawable.ic_search),
                    contentDescription = stringResource(
                        if (state.queryOpen) R.string.title_close_search else R.string.menu_search
                    )
                )
            }
        }

        if (showOverview) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 10.dp, end = 16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(Modifier.padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 10.dp)) {
                    if (largeText)
                        Column(Modifier.fillMaxWidth()) {
                            CallsCount(data)
                            CallsLabel(data, Modifier.fillMaxWidth())
                            Stats(data, Modifier.fillMaxWidth(), state)
                        }
                    else
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            CallsCount(data)
                            CallsLabel(data, Modifier.weight(1f))
                            Stats(data, Modifier, state)
                        }

                    Bars(
                        values = data.bars,
                        description = data.barsDescription,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(36.dp)
                    )

                    // The hours or the days, in the digits of the language
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clearAndSetSemantics {}
                    ) {
                        data.axis.forEachIndexed { index, label ->
                            Text(
                                text = label ?: "",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                textAlign = when (index) {
                                    0 -> TextAlign.Start
                                    data.axis.lastIndex -> TextAlign.End
                                    else -> TextAlign.Center
                                }
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LogFilter.entries.forEach { filter ->
                val colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { state.filter = filter },
                    label = { Text(stringResource(FILTER_TITLES[filter.ordinal])) },
                    shape = CircleShape,
                    colors = colors,
                    border = null
                )
            }
        }
    }
}

@Composable
private fun CallsCount(data: ScreenData) {
    Text(
        text = data.countText,
        style = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    )
}

@Composable
private fun CallsLabel(data: ScreenData, modifier: Modifier) {
    Text(
        text = data.callsLabel,
        modifier = modifier.padding(start = 8.dp, bottom = 5.dp),
        style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun Stats(data: ScreenData, modifier: Modifier, state: LogState) {
    Text(
        text = data.stats,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(
                onClickLabel = stringResource(R.string.title_show_errors),
                onClick = { state.filter = LogFilter.ERRORS }
            )
            .padding(start = 4.dp, end = 4.dp, bottom = 5.dp),
        style = MaterialTheme.typography.bodySmall.copy(
            color = if (data.statsError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun SearchField(
    query: String,
    hint: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    modifier: Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .heightIn(min = 48.dp)
            .focusRequester(focusRequester),
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = true,
        placeholder = { Text(hint, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.title_clear_search)
                    )
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
    )
}

@Composable
private fun Bars(values: IntArray, description: String?, modifier: Modifier) {
    val none = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
    val some = themeColor(com.google.android.material.R.attr.colorPrimaryContainer)
    val most = themeColor(androidx.appcompat.R.attr.colorPrimary)
    val rtl = LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Canvas(
        modifier.semantics {
            if (description != null)
                contentDescription = description
        }
    ) {
        if (values.isEmpty())
            return@Canvas
        val max = values.max()
        // Narrow views have narrower gaps, like the bars in the compact bar
        val gap = minOf(3.dp.toPx(), size.width / (values.size * 4f))
        val barWidth = (size.width - gap * (values.size - 1)) / values.size
        val line = 2.dp.toPx()
        val radius = CornerRadius(1.dp.toPx())
        for (i in values.indices) {
            val x = if (rtl) size.width - barWidth - i * (barWidth + gap) else i * (barWidth + gap)
            val height = if (values[i] == 0) line else maxOf(line, size.height * values[i] / max)
            drawRoundRect(
                color = if (values[i] == 0) none else if (values[i] == max) most else some,
                topLeft = androidx.compose.ui.geometry.Offset(x, size.height - height),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = radius
            )
        }
    }
}

@Composable
private fun LogCompactBar(
    only: String?,
    title: String,
    data: ScreenData,
    filter: LogFilter,
    largeText: Boolean,
    alpha: Float,
    onBack: () -> Unit,
    onFilter: (LogFilter) -> Unit,
    onSearch: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
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
                    .padding(start = if (only == null) 20.dp else 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (only != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.title_back)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!largeText)
                        Text(
                            text = data.compactSummary,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                }
                Bars(
                    values = data.bars,
                    description = null,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(64.dp, 24.dp)
                        .clearAndSetSemantics {}
                )
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            painterResource(R.drawable.ic_filter),
                            contentDescription = stringResource(R.string.title_filter)
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        LogFilter.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(FILTER_TITLES[option.ordinal])) },
                                onClick = {
                                    onFilter(option)
                                    menu = false
                                },
                                trailingIcon = if (option == filter) {
                                    {
                                        Icon(
                                            painterResource(R.drawable.ic_check),
                                            contentDescription = null
                                        )
                                    }
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
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun LogRow(
    entry: Entry,
    first: Boolean,
    last: Boolean,
    full: Boolean,
    clipped: Boolean,
    only: String?,
    onToggle: () -> Unit,
    onClipped: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val exception = entry.exception
    val error = exception != null
    val background = if (error) scheme.errorContainer else scheme.surfaceContainerLowest
    val meta = if (error) scheme.onErrorContainer else scheme.onSurfaceVariant
    val shape = RoundedCornerShape(
        topStart = if (first) 20.dp else 0.dp,
        topEnd = if (first) 20.dp else 0.dp,
        bottomStart = if (last) 20.dp else 0.dp,
        bottomEnd = if (last) 20.dp else 0.dp
    )
    val onLayout: (TextLayoutResult) -> Unit = { result ->
        if (!error && !full && result.hasVisualOverflow)
            onClipped()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .clickable {
                if (exception != null)
                    Ui.showText(context, entry.hook, exception.trim())
                else {
                    // The screen of the app with the hook; the log of one app is opened from that screen,
                    // which is shown again instead of another one on top of the log
                    val intent = ActivityApp.getIntent(context, entry.app.packageName, entry.app.uid, entry.hook)
                    if (only != null)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    context.startActivity(intent)
                }
            }
    ) {
        if (!first)
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp),
                thickness = 1.dp,
                color = lerp(background, scheme.onSurface, 0.08f)
            )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp)
        ) {
            AppIcon(
                packageName = entry.app.packageName,
                icon = entry.app.icon,
                size = 38.dp,
                modifier = Modifier.padding(top = 1.dp)
            )

            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.app.getLabel(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium.copy(color = scheme.onSurface),
                        maxLines = if (full) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = onLayout
                    )
                    if (entry.time > 0) {
                        Text(
                            text = if (error) dayTime(context, entry.time)
                            else DateUtils.formatDateTime(context, entry.time, DateUtils.FORMAT_SHOW_TIME),
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodySmall.copy(color = meta)
                        )
                    }
                }

                val group = entry.group?.let { Groups.getTitle(context, it) }
                val what = if (exception != null) {
                    val line = exception.trim().split("\n", limit = 2)[0]
                    context.getString(R.string.title_hook_failed, line)
                } else group ?: entry.hook
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painterResource(if (error) R.drawable.ic_error else Groups.getIcon(entry.group)),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (error) scheme.error else scheme.primary
                    )
                    Text(
                        text = what,
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (error) meta else scheme.onSurface
                        )
                    )
                }

                val values = !error && (entry.oldValue != null || entry.newValue != null)
                val oldValue = entry.oldValue ?: "null"
                val newValue = entry.newValue ?: "null"
                val hook = entry.hook.substringAfter('.', entry.hook)
                if (values && !full)
                    ValueLine(
                        oldValue = oldValue,
                        newValue = newValue,
                        hook = hook,
                        showExpand = clipped,
                        onToggle = onToggle,
                        onLayout = onLayout
                    )
                else {
                    if (values)
                        Column(Modifier.padding(top = 4.dp)) {
                            ValueText(
                                oldValue, true,
                                stringResource(R.string.title_real_value, oldValue)
                            )
                            Spacer(Modifier.height(4.dp))
                            ValuePill(
                                newValue,
                                description = stringResource(R.string.title_fake_value, newValue)
                            )
                        }
                    // The hook on its own line, wrapped when the row is expanded
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = hook,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = Mono,
                                color = meta,
                                fontSize = 11.sp
                            ),
                            maxLines = if (full) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = onLayout
                        )
                        if (full || clipped)
                            ExpandButton(full = full, onToggle = onToggle)
                    }
                }
            }
        }
    }
}

@Composable
private fun ValueLine(
    oldValue: String,
    newValue: String,
    hook: String,
    showExpand: Boolean,
    onToggle: () -> Unit,
    onLayout: (TextLayoutResult) -> Unit
) {
    // The two values share the room which is left after the arrow and the expand button, like ValueLine for the views
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cap = ((maxWidth - 48.dp - 12.dp) / 2).coerceAtLeast(0.dp)
        val real = stringResource(R.string.title_real_value, oldValue)
        val fake = stringResource(R.string.title_fake_value, newValue)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = oldValue,
                modifier = Modifier
                    .widthIn(max = cap)
                    .semantics { contentDescription = real },
                style = monoStrike(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = onLayout
            )
            Text(
                text = stringResource(R.string.title_value_arrow),
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .clearAndSetSemantics {},
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            ValuePill(
                newValue,
                modifier = Modifier.widthIn(max = cap),
                onLayout = onLayout,
                description = fake
            )
            Text(
                text = hook,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = Mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                onTextLayout = onLayout
            )
            if (showExpand)
                ExpandButton(full = false, onToggle = onToggle)
        }
    }
}

@Composable
private fun monoStrike() = MaterialTheme.typography.bodySmall.copy(
    fontFamily = Mono,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textDecoration = TextDecoration.LineThrough
)

@Composable
private fun ValueText(value: String, struck: Boolean, description: String? = null) {
    Text(
        text = value,
        modifier = if (description == null) Modifier
        else Modifier.semantics { contentDescription = description },
        style = if (struck) monoStrike()
        else MaterialTheme.typography.bodySmall.copy(
            fontFamily = Mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun ValuePill(
    value: String,
    modifier: Modifier = Modifier,
    onLayout: ((TextLayoutResult) -> Unit)? = null,
    description: String? = null
) {
    Text(
        text = value,
        modifier = modifier
            .semantics { if (description != null) contentDescription = description }
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 7.dp, vertical = 1.dp),
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = Mono,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = onLayout ?: {}
    )
}

@Composable
private fun ExpandButton(full: Boolean, onToggle: () -> Unit) {
    IconButton(
        onClick = onToggle,
        modifier = Modifier.size(48.dp)
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_expand),
                contentDescription = stringResource(
                    if (full) R.string.title_hide_values else R.string.title_show_values
                ),
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (full) 180f else 0f),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun AppIcon(
    packageName: String,
    icon: Int,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val px = with(density) { size.roundToPx() }
    val bitmap by produceState<Bitmap?>(initialValue = null, packageName, icon, px) {
        value = withContext(Dispatchers.IO) {
            if (icon <= 0) null else runCatching {
                Glide.with(context.applicationContext)
                    .asBitmap()
                    .apply(RequestOptions().format(DecodeFormat.PREFER_RGB_565))
                    .load(Uri.parse("android.resource://" + packageName + "/" + icon))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .override(px, px)
                    .submit()
                    .get()
            }.getOrNull()
        }
    }
    val current = bitmap
    if (current != null)
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.size(size)
        )
    else
        Icon(
            painterResource(android.R.drawable.sym_def_app_icon),
            contentDescription = null,
            modifier = modifier.size(size)
        )
}

// Like DateUtils.getRelativeDateTimeString, which formats the time without the 24-hour setting
private fun dayTime(context: Context, time: Long): String {
    val day = DateUtils.getRelativeTimeSpanString(
        time,
        System.currentTimeMillis(),
        DateUtils.DAY_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_ALL
    ).toString()
    val clock = DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_TIME)
    return RelativeDateTimeFormatter.getInstance(Locale.getDefault()).combineDateAndTime(day, clock)
}

private fun matches(context: Context, entry: Entry, query: Ui.Query?): Boolean =
    query == null || entry.matches(context, query)

// The entries by hour of the day
private fun byHour(entries: List<Entry>): IntArray {
    val hours = IntArray(24)
    val calendar = Calendar.getInstance()
    for (entry in entries) {
        calendar.timeInMillis = entry.time
        hours[calendar.get(Calendar.HOUR_OF_DAY)]++
    }
    return hours
}

// The entries by day of the last week, today last; older ones are left out
private fun byDay(entries: List<Entry>, today: Long): IntArray {
    val days = IntArray(7)
    val starts = LongArray(days.size) { addDays(today, it + 1 - days.size) }
    for (entry in entries)
        for (i in days.indices.reversed())
            if (entry.time >= starts[i]) {
                days[i]++
                break
            }
    return days
}

// The start of a day some days later or earlier, also across a change of the clock
private fun addDays(day: Long, days: Int): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = day
    calendar.add(Calendar.DAY_OF_MONTH, days)
    return calendar.timeInMillis
}

private fun buildScreenData(
    context: Context,
    only: String?,
    entries: List<Entry>,
    errors: List<Entry>,
    filter: LogFilter,
    queryText: String
): ScreenData {
    val today = Ui.startOfDay(System.currentTimeMillis())
    val yesterday = Ui.startOfDay(today - 1)
    val q = queryText.trim()
    val query = if (q.isEmpty()) null else Ui.Query(q)
    val counted = (if (filter == LogFilter.ERRORS) errors else entries)
        .filter { (filter != LogFilter.TODAY || it.time >= today) && matches(context, it, query) }
    val packages = counted.map { it.app.packageName }.toSet()
    val byHour = filter == LogFilter.TODAY
    val bars = if (byHour) byHour(counted) else byDay(counted, today)
    val count = counted.size
    val nf = NumberFormat.getIntegerInstance()
    val countText = nf.format(count)
    val callsLabel = context.resources.getQuantityString(
        when {
            filter == LogFilter.ERRORS -> R.plurals.title_failed_hooks
            byHour -> R.plurals.title_calls_today
            else -> R.plurals.title_calls_all
        },
        count
    )
    val errorCount = context.resources.getQuantityString(
        R.plurals.title_stat_errors_count,
        errors.size,
        errors.size
    )
    val stats = if (only != null) errorCount else context.getString(
        R.string.title_pair,
        context.resources.getQuantityString(R.plurals.title_stat_apps, packages.size, packages.size),
        errorCount
    )

    // Screen readers get the bars which are not empty, with their hour or day
    var described = context.getString(
        when {
            filter == LogFilter.ERRORS -> R.string.title_errors_by_day
            byHour -> R.string.title_calls_by_hour
            else -> R.string.title_calls_by_day
        }
    )
    for (i in bars.indices)
        if (bars[i] > 0) {
            val at = Calendar.getInstance()
            at.timeInMillis = if (byHour) today else addDays(today, i + 1 - bars.size)
            if (byHour)
                at.set(Calendar.HOUR_OF_DAY, i)
            val label = DateUtils.formatDateTime(
                context,
                at.timeInMillis,
                if (byHour) DateUtils.FORMAT_SHOW_TIME else DateUtils.FORMAT_SHOW_WEEKDAY
            )
            described = context.getString(
                R.string.title_pair,
                described,
                context.getString(R.string.title_bar, label, nf.format(bars[i]))
            )
        }

    // Under the bars: the hours in the digits of the language, or the first and the last day
    val axis = (0..4).map { i ->
        when {
            byHour -> nf.format(i * 6)
            i == 0 -> DateUtils.formatDateTime(
                context,
                addDays(today, 1 - bars.size),
                DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
            )
            i == 4 -> context.getString(R.string.title_today)
            else -> null
        }
    }

    val compactSummary = context.getString(
        R.string.title_pair,
        if (filter == LogFilter.ERRORS) errorCount else context.getString(
            R.string.title_pair,
            context.resources.getQuantityString(
                if (byHour) R.plurals.title_compact_calls else R.plurals.title_compact_all,
                count,
                count
            ),
            errorCount
        ),
        context.getString(FILTER_TITLES[filter.ordinal])
    )

    // Items are entries under the headers of their days, with the number of entries
    val failed = errors.filter { matches(context, it, query) }
    val items = ArrayList<LogItem>()
    fun addBlock(header: String?, list: List<Entry>) {
        if (header != null)
            items.add(LogItem.Header(header))
        list.forEachIndexed { index, entry ->
            items.add(LogItem.Row(entry, index == 0, index == list.lastIndex))
        }
    }

    if (filter == LogFilter.ERRORS || (filter == LogFilter.ALL && failed.isNotEmpty()))
        addBlock(
            if (filter == LogFilter.ALL)
                context.getString(R.string.title_count, context.getString(R.string.title_stat_errors), failed.size)
            else null,
            failed
        )
    if (filter != LogFilter.ERRORS) {
        val days = LinkedHashMap<String, MutableList<Entry>>()
        // The name of a day is made once for its entries, which follow each other, not for each entry
        var name: String? = null
        var from = 0L
        var until = 0L
        for (entry in counted) {
            if (name == null || entry.time < from || entry.time >= until) {
                from = Ui.startOfDay(entry.time)
                until = addDays(from, 1)
                name = when {
                    entry.time >= today -> context.getString(R.string.title_today)
                    entry.time >= yesterday -> context.getString(R.string.title_yesterday)
                    else -> DateUtils.formatDateTime(
                        context,
                        entry.time,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL
                    )
                }
            }
            days.getOrPut(name!!) { ArrayList() }.add(entry)
        }
        for ((day, list) in days)
            addBlock(context.getString(R.string.title_count, day, list.size), list)
    }

    // Nothing logged, or nothing which the filter or the search matches
    val emptyText = when {
        entries.isEmpty() && errors.isEmpty() -> R.string.title_log_empty
        filter == LogFilter.ERRORS && q.isEmpty() -> R.string.title_log_no_errors
        q.isEmpty() -> R.string.title_filter_empty
        else -> R.string.title_no_matches
    }

    return ScreenData(
        items = items,
        countText = countText,
        callsLabel = callsLabel,
        stats = stats,
        statsError = errors.isNotEmpty(),
        bars = bars,
        barsDescription = described,
        axis = axis,
        compactSummary = compactSummary,
        emptyText = emptyText
    )
}
