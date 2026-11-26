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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "XLua.App"
private const val STATE_DIRTY = "dirty"
private const val STATE_EXPANDED = "expanded"
private const val STATE_QUERY = "query"

// Restrictions of one app, by group
class ActivityApp : ActivityBase() {
    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_UID = "uid"
        private const val EXTRA_HOOK = "hook"

        // Settings of an app which are no fake values, see ActivityValues
        private val OPTIONS = HashSet(listOf(Data.FORCE_STOP, Data.NOTIFY))

        @JvmStatic
        fun getIntent(context: Context, packageName: String, uid: Int): Intent {
            return Intent(context, ActivityApp::class.java).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_UID, uid)
            }
        }

        // With the hook expanded and highlighted, like a restriction tapped in the log
        @JvmStatic
        fun getIntent(context: Context, packageName: String, uid: Int, hook: String?): Intent {
            return getIntent(context, packageName, uid).putExtra(EXTRA_HOOK, hook)
        }
    }

    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: run {
            finish()
            return
        }
        state = AppState(packageName, intent.getIntExtra(EXTRA_UID, -1))
        if (savedInstanceState == null)
            // Not again when the screen is restored
            state.reveal = intent.getStringExtra(EXTRA_HOOK)
        else {
            // The restart still needed, the open groups and the search, also after the process was ended in the
            // background; the search is shown again without the keyboard, like before
            state.dirty = savedInstanceState.getBoolean(STATE_DIRTY)
            savedInstanceState.getStringArrayList(STATE_EXPANDED)?.let { state.expanded = it.toSet() }
            state.text = savedInstanceState.getString(STATE_QUERY) ?: ""
            state.searching = state.text.isNotEmpty()
            state.searchRestored = state.searching
        }

        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DoppelTheme {
                    AppScreen(
                        state = state,
                        dispatcher = onBackPressedDispatcher,
                        onBack = { onBackPressedDispatcher.onBackPressed() },
                        onLog = {
                            startActivity(ActivityLog.getIntent(this@ActivityApp, state.packageName, state.uid, state.label))
                        },
                        onAppInfo = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", state.packageName, null)
                                )
                            )
                        },
                        onLaunch = { Ui.launch(this@ActivityApp, state.packageName, state.label) },
                        onValues = {
                            state.app?.let { app ->
                                startActivity(ActivityValues.getIntent(this@ActivityApp, app.packageName, state.label))
                            }
                        },
                        onRestrictAll = ::restrictAll,
                        onSection = ::toggleSection,
                        onToggle = ::toggleExpand,
                        onGroup = ::toggleGroup,
                        onHook = ::toggleHook,
                        onScope = ::toggleScope,
                        onForceStop = ::writeForceStop,
                        onNotify = ::writeNotify,
                        onForceStopNow = ::forceStop,
                        onShowErrors = ::showErrors
                    )
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_DIRTY, state.dirty)
        outState.putStringArrayList(STATE_EXPANDED, ArrayList(state.expanded))
        outState.putString(STATE_QUERY, state.text)
    }

    private fun load() {
        val context = applicationContext
        val started = state.changes
        submit {
            try {
                val app = Data.getApp(context, state.packageName, state.uid)
                if (app == null) {
                    // The service gives no app on an error too, which should not close the screen
                    val installed = isInstalled(context, state.packageName, state.uid)
                    postUnlessGone {
                        // Uninstalled meanwhile, like with App info: its restrictions cannot be changed anymore
                        if (state.app != null && !installed) {
                            finish()
                            return@postUnlessGone
                        }
                        state.loading = false
                        Ui.message(this, getString(R.string.msg_app_not_found, state.packageName))
                    }
                    return@submit
                }
                val hooks = Data.getAvailable(Data.getHooks(context), app, Data.getCollection(context), null)
                val settings = Data.getSettings(context, app.packageName)
                val notify = java.lang.Boolean.parseBoolean(settings[Data.NOTIFY])
                // The fake values of the app itself, the rest come from the values for all apps;
                // a value and its mode count once
                val own = HashSet<String>()
                for (name in settings.keys)
                    if (name !in OPTIONS)
                        own.add(FakeIds.baseKey(name))
                val values = own.size
                // Most system apps cannot be opened
                val launchable = (context.packageManager.getLaunchIntentForPackage(app.packageName) != null)
                val version = getVersionName(context, app.packageName)
                postUnlessGone {
                    // Changed while loading, which was sent after the data was read, on the same background thread;
                    // loading again shows the change, else the switches would go back
                    if (state.changes != started)
                        load()
                    else
                        show(app, hooks, notify, values, launchable, version)
                }
            } catch (ex: Throwable) {
                postUnlessGone { state.loading = false }
                // Else a failed first load leaves the screen with its toolbar only
                Ui.error(this, TAG, ex) {
                    if (!isDestroyed)
                        load()
                }
            }
        }
    }

    private fun show(
        app: XApp,
        hooks: List<XHook>,
        notify: Boolean,
        values: Int,
        launchable: Boolean,
        version: String?
    ) {
        state.app = app
        state.uid = app.uid
        state.label = app.getLabel()
        state.notify = notify
        state.values = values
        state.launchable = launchable
        state.version = version

        // Group the hooks
        val grouped = LinkedHashMap<String, MutableList<XHook>>()
        for (hook in hooks)
            grouped.getOrPut(hook.group) { ArrayList() }.add(hook)
        state.grouped = grouped
        state.sections = GroupsSheet.bySection(this, grouped.keys)
            .entries.map { it.key to it.value }
        state.hasValues = hooks.any { it.settings.isNotEmpty() }

        refreshScope()
        state.loading = false
        state.loaded = true
        state.tick++
    }

    private fun refreshScope() {
        val app = state.app ?: return
        val pkg = app.packageName
        if (!Scope.available() || app.isSystemServer() || BuildConfig.APPLICATION_ID == pkg) {
            state.scopeVisible = false
            return
        }
        submit {
            val inScope = Scope.inScope(pkg)
            postUnlessGone {
                if (state.app?.packageName == pkg)
                    setScope(inScope)
            }
        }
    }

    // Show the scope state without firing the listener, then listen: turning it on asks the framework (which asks the
    // user) and reconciles to the real state in the callback; turning it off removes the app
    private fun setScope(inScope: Boolean) {
        state.scopeVisible = true
        state.scopeChecked = inScope
    }

    private fun toggleScope(checked: Boolean) {
        val app = state.app ?: return
        val pkg = app.packageName
        if (checked) {
            Ui.message(this, getString(R.string.msg_scope_requested))
            val request = ++state.scopeRequests
            state.scopePending = true
            state.scopeChecked = true
            run(TAG) {
                Scope.add(pkg) { nowInScope, failure ->
                    // Also when the screen is gone, since the user can approve in a notification after leaving it:
                    // the app should not keep running without its hooks
                    if (nowInScope && app.stopsAutomatically())
                        try {
                            Data.forceStop(applicationContext, app)
                        } catch (ex: Throwable) {
                            error(TAG, ex)
                        }
                    postUnlessGone {
                        // The framework fails a request still open when a newer one comes, which reports for itself
                        if (!nowInScope && request != state.scopeRequests)
                            return@postUnlessGone
                        state.scopePending = false
                        setScope(nowInScope)
                        if (nowInScope) {
                            state.dirty = state.dirty || !app.stopsAutomatically()
                            state.tick++
                        } else if (failure != null)
                            Ui.message(this, getString(R.string.msg_scope_failed, failure))
                    }
                }
            }
        } else {
            state.scopeChecked = false
            scopeChanged { Scope.remove(pkg) }
        }
    }

    // The framework does not tell a denied request (or one that timed out), so the box is checked against the real
    // scope when the screen gets the focus back, e.g. after the notification of the request was answered
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !state.scopePending)
            return
        val app = state.app ?: return
        val pkg = app.packageName
        val request = state.scopeRequests
        submit {
            val inScope = Scope.inScope(pkg)
            postUnlessGone {
                if (inScope || !state.scopePending || request != state.scopeRequests)
                    return@postUnlessGone
                state.scopePending = false
                setScope(false)
                Ui.message(this, getString(R.string.msg_scope_denied))
            }
        }
    }

    // A scope change applies when the app's process next starts, like a restriction: the app is stopped after the
    // change when it is stopped automatically, else the restart hint shows
    private fun scopeChanged(change: () -> Unit) {
        val app = state.app ?: return
        state.dirty = state.dirty || !app.stopsAutomatically()
        state.tick++
        val context = applicationContext
        run(TAG) {
            change()
            if (app.stopsAutomatically())
                Data.forceStop(context, app)
        }
    }

    private fun writeForceStop(checked: Boolean) {
        val app = state.app ?: return
        app.forceStop = checked
        state.changes++
        state.tick++
        val context = applicationContext
        run(TAG) {
            try {
                Data.putSettingBoolean(context, app.packageName, Data.FORCE_STOP, checked)
            } catch (ex: Throwable) {
                // Else the checkbox shows the change which was not stored
                postUnlessGone { load() }
                throw ex
            }
        }
    }

    // Read by the service when a hook restricts something, see XProvider.Report.write
    private fun writeNotify(checked: Boolean) {
        val app = state.app ?: return
        state.notify = checked
        state.changes++
        val context = applicationContext
        run(TAG) {
            try {
                Data.putSettingBoolean(context, app.packageName, Data.NOTIFY, checked)
            } catch (ex: Throwable) {
                postUnlessGone { load() }
                throw ex
            }
        }
    }

    // Restrict all hooks of all groups, or remove all restrictions when all hooks are restricted
    private fun restrictAll() {
        val app = state.app ?: return
        val groups = state.grouped.keys.toList()
        if (isComplete(groups))
            assign(groups, false)
        else
            Ui.restrictAll(this, app) { assign(groups, true) }
    }

    // The button of a section restricts or removes all of the section, like Restrict all
    private fun toggleSection(groups: List<String>) {
        val app = state.app ?: return
        if (isComplete(groups))
            assign(groups, false)
        else
            Ui.restrictAll(this, app) { assign(groups, true) }
    }

    // The row of a group opens its hooks; a group the search found by its hooks opens them instead of the ones of the
    // group, and can be closed again
    private fun toggleExpand(group: String) {
        if (foundHooks(this, state, group) != null)
            state.toggled = if (group in state.toggled)
                state.toggled - group
            else
                state.toggled + group
        else
            state.expanded = if (group in state.expanded)
                state.expanded - group
            else
                state.expanded + group
    }

    // A switch with a dash applies all hooks of the group, like Restrict all; only a complete group is switched off
    private fun toggleGroup(group: String) {
        assign(listOf(group), !isComplete(group))
    }

    private fun toggleHook(group: String, hook: XHook, assign: Boolean) {
        assign(listOf(group), listOf(hook), assign)
    }

    private fun isComplete(group: String) =
        (state.app?.getAssignments(group)?.size ?: 0) >= (state.grouped[group]?.size ?: 0)

    private fun isComplete(groups: Collection<String>) = groups.all { isComplete(it) }

    private fun assign(group: String, assign: Boolean) = assign(listOf(group), assign)

    private fun assign(groups: List<String>, assign: Boolean) {
        val candidates = ArrayList<XHook>()
        for (group in groups)
            state.grouped[group]?.let { candidates.addAll(it) }
        assign(groups, candidates, assign)
    }

    // Updates the assignments shown right away, the service in the background, with one call,
    // which stops the app once when it is stopped automatically
    private fun assign(groups: List<String>, candidates: List<XHook>, assign: Boolean) {
        val app = state.app ?: return
        val hooks = ArrayList<XHook>()
        for (hook in candidates) {
            val assignment = XAssignment(hook)
            val assigned = app.assignments.contains(assignment)
            if (assign && !assigned) {
                app.assignments.add(assignment)
                hooks.add(hook)
            } else if (!assign && assigned) {
                app.assignments.remove(assignment)
                hooks.add(hook)
            }
        }
        state.tick++
        if (hooks.isEmpty())
            return

        state.dirty = state.dirty || !app.stopsAutomatically()
        state.changes++
        val context = applicationContext
        run(TAG) {
            try {
                Data.assign(context, app, hooks, assign)
            } catch (ex: Throwable) {
                // Else the switches show the change which was not stored; only then, since a reload builds all
                // groups again, which can cancel a press on the next switch
                postUnlessGone { load() }
                throw ex
            }
        }
    }

    private fun forceStop() {
        val app = state.app ?: return
        val context = applicationContext
        val started = state.changes
        run(TAG) {
            Data.forceStop(context, app)
            // Not before, a failed stop needs the hint; a change made meanwhile needs another restart
            postUnlessGone {
                if (state.changes == started)
                    state.dirty = false
                state.tick++
            }
            Ui.message(this, getString(R.string.msg_stopped, state.label))
        }
    }

    private fun showErrors(group: String) {
        val sb = StringBuilder()
        for (assignment in state.app?.getAssignments(group) ?: emptyList()) {
            val exception = assignment.exception ?: continue
            if (sb.isNotEmpty())
                sb.append("\n\n")
            sb.append(assignment.id).append("\n\n").append(exception.trim())
        }
        Ui.showText(this, Groups.getTitle(this, group), sb)
    }

    // A restriction tapped in the log of this app, which closed the log, see FragmentLog;
    // the restrictions are loaded again when the screen resumes
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        state.reveal = intent.getStringExtra(EXTRA_HOOK)
    }

    override fun onResume() {
        super.onResume()
        // The service updates usage and errors while the app runs; changes made here are sent before,
        // on the same background thread. Also the first load, and again after it failed
        load()
    }

    private fun getVersionName(context: Context, packageName: String): String? {
        return try {
            Util.getPackageInfo(context.packageManager, packageName, 0).versionName
        } catch (ignored: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun isInstalled(context: Context, packageName: String, uid: Int): Boolean {
        return try {
            val ai: ApplicationInfo = Util.getApplicationInfo(context.packageManager, packageName, 0)
            uid < 0 || ai.uid == uid
        } catch (ignored: PackageManager.NameNotFoundException) {
            false
        }
    }
}

private class AppState(val packageName: String, var uid: Int) {
    var app by mutableStateOf<XApp?>(null)
    var label by mutableStateOf(packageName)
    var grouped by mutableStateOf<Map<String, List<XHook>>>(emptyMap())
    var sections by mutableStateOf<List<Pair<Int, List<String>>>>(emptyList())
    var expanded by mutableStateOf<Set<String>>(emptySet()) // Groups which show their hooks
    var toggled by mutableStateOf<Set<String>>(emptySet()) // Groups the search opened, which were closed again
    var text by mutableStateOf("") // Of the search
    var searching by mutableStateOf(false) // The search replaces the title
    var searchRestored by mutableStateOf(false) // Shown again with the screen, without the keyboard
    var dirty by mutableStateOf(false) // Changed restrictions which do not apply yet
    var changes by mutableIntStateOf(0) // Changes made here, which a reload started before does not show yet
    var scopeRequests by mutableIntStateOf(0) // Scope requests made here, a newer one supersedes the ones before
    var scopePending by mutableStateOf(false) // A request waits for the user
    var scopeVisible by mutableStateOf(false)
    var scopeChecked by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var loaded by mutableStateOf(false)
    var notify by mutableStateOf(false)
    var values by mutableIntStateOf(0)
    var hasValues by mutableStateOf(false)
    var launchable by mutableStateOf(false)
    var version by mutableStateOf<String?>(null)
    var reveal by mutableStateOf<String?>(null) // The id of a hook tapped in the log
    var highlight by mutableStateOf<String?>(null)
    var tick by mutableIntStateOf(0) // The assignments change in place

    val query: String get() = text.trim()
}

private data class GroupInfo(val title: String, val description: String, val anchor: Rect)

// Where the scroll content starts and how far it is scrolled, to get the position of a child in the content
private class ScrollMapping(val scroll: ScrollState) {
    var boxTop = 0f
}

@Composable
private fun AppScreen(
    state: AppState,
    dispatcher: OnBackPressedDispatcher,
    onBack: () -> Unit,
    onLog: () -> Unit,
    onAppInfo: () -> Unit,
    onLaunch: () -> Unit,
    onValues: () -> Unit,
    onRestrictAll: () -> Unit,
    onSection: (List<String>) -> Unit,
    onToggle: (String) -> Unit,
    onGroup: (String) -> Unit,
    onHook: (String, XHook, Boolean) -> Unit,
    onScope: (Boolean) -> Unit,
    onForceStop: (Boolean) -> Unit,
    onNotify: (Boolean) -> Unit,
    onForceStopNow: () -> Unit,
    onShowErrors: (String) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val tick = state.tick
    val mapping = remember(scroll) { ScrollMapping(scroll) }

    var viewport by remember { mutableIntStateOf(0) }
    var headerBottom by remember { mutableIntStateOf(0) }
    // Positions in the scroll content, for reveal; plain maps, since layout writes them without a repaint
    val hookTop = remember { HashMap<String, Float>() }
    val hookHeight = remember { HashMap<String, Int>() }
    var info by remember { mutableStateOf<GroupInfo?>(null) }

    val scrolled by remember {
        derivedStateOf { headerBottom > 0 && scroll.value > headerBottom }
    }
    val loadingDescription = stringResource(R.string.title_loading)

    fun closeSearch() {
        state.text = ""
        state.toggled = emptySet()
        state.searching = false
        keyboard?.hide()
    }

    // Back closes the search first, like it does for the views, see TitleSearch
    DisposableEffect(state.searching) {
        val callback = object : OnBackPressedCallback(state.searching) {
            override fun handleOnBackPressed() {
                closeSearch()
            }
        }
        dispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }

    // A tap anywhere and back close the bubble
    DisposableEffect(info) {
        val callback = object : OnBackPressedCallback(info != null) {
            override fun handleOnBackPressed() {
                info = null
            }
        }
        dispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }

    // The keyboard opens when the search is opened, not when it is restored with the screen
    LaunchedEffect(state.searching) {
        if (state.searching) {
            if (state.searchRestored)
                state.searchRestored = false
            else {
                focusRequester.requestFocus()
                keyboard?.show()
            }
        }
    }

    // A hook tapped in the log: its group is expanded, the hook scrolled to the middle and highlighted for a moment;
    // a hook which is not available anymore, like of a disabled collection, shows the screen only
    var revealId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.reveal) {
        state.reveal?.let { id ->
            revealId = id
            state.reveal = null
        }
    }
    LaunchedEffect(revealId) {
        val id = revealId ?: return@LaunchedEffect
        if (!state.loaded)
            snapshotFlow { state.loaded }.first { it }
        val group = state.grouped.entries.firstOrNull { entry -> entry.value.any { it.id == id } }?.key
        if (group == null) {
            revealId = null
            return@LaunchedEffect
        }
        // A search left open could hide the hook, which would have no view to scroll to
        state.text = ""
        state.searching = false
        state.toggled = emptySet()
        keyboard?.hide()
        state.expanded = state.expanded + group
        state.tick++

        // After the layout of the expanded hooks
        var frames = 0
        while (!hookTop.containsKey(id) && frames < 10) {
            withFrameNanos {}
            frames++
        }
        withFrameNanos {}
        val top = hookTop[id]
        val height = hookHeight[id]
        val target = if (top != null && height != null && viewport > 0)
            (top - (viewport - height) / 2f).roundToInt().coerceAtLeast(0)
        else
            null
        // The highlight starts with the scroll, like the animation of the views
        state.highlight = id
        coroutineScope {
            if (target != null)
                launch { scroll.animateScrollTo(target) }
            delay(2500)
        }
        if (state.highlight == id)
            state.highlight = null
        revealId = null
    }

    val app = state.app
    val groups = state.grouped
    val restricted = groups.keys.count { app?.getAssignments(it)?.isNotEmpty() == true }
    val complete = groups.keys.all { (app?.getAssignments(it)?.size ?: 0) >= (groups[it]?.size ?: 0) }
    val baseSummary = pluralStringResource(
        R.plurals.title_groups_restricted, groups.size, restricted, groups.size
    )
    val lastCall = remember(tick, groups, app) {
        var last = -1L
        var lastGroup: String? = null
        for (assignment in app?.assignments ?: emptyList()) {
            if (assignment.restricted && assignment.used > last) {
                last = assignment.used
                lastGroup = assignment.group
            }
        }
        if (last > 0 && lastGroup != null)
            last to lastGroup
        else
            null
    }
    val summary = if (lastCall == null) baseSummary else stringResource(
        R.string.title_pair,
        baseSummary,
        stringResource(
            R.string.title_last_call,
            Groups.getTitle(context, lastCall.second),
            Ui.ago(context, lastCall.first)
        )
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.ime)
            )
    ) {
        AppBar(
            state = state,
            summary = if (scrolled) baseSummary else null,
            focusRequester = focusRequester,
            onBack = onBack,
            onLog = onLog,
            onAppInfo = onAppInfo,
            onLaunch = onLaunch,
            onToggleSearch = {
                if (state.searching)
                    closeSearch()
                else
                    state.searching = true
            }
        )

        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    mapping.boxTop = it.positionInWindow().y
                    viewport = it.size.height
                }
        ) {
            if (state.loaded && app != null)
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                ) {
                    OverviewCard(
                        state = state,
                        app = app,
                        restricted = restricted,
                        total = groups.size,
                        complete = complete,
                        summary = summary,
                        mapping = mapping,
                        onRestrictAll = onRestrictAll,
                        onMeasure = { bottom ->
                            if (bottom != headerBottom)
                                headerBottom = bottom
                        }
                    )

                    OptionsCard(
                        state = state,
                        onValues = onValues,
                        onScope = onScope,
                        onForceStop = onForceStop,
                        onNotify = onNotify
                    )

                    if (app.isSystemServer())
                        Banner(
                            icon = R.drawable.ic_error,
                            text = stringResource(R.string.title_bootloop)
                        )

                    val forceStopApp = app
                    val restart = state.dirty && (forceStopApp.persistent || !forceStopApp.forceStop)
                    if (restart)
                        Banner(
                            icon = R.drawable.ic_restart,
                            text = stringResource(
                                if (forceStopApp.persistent) R.string.title_apply_reboot
                                else R.string.title_apply_restart,
                                state.label
                            ),
                            button = if (forceStopApp.persistent) null else stringResource(R.string.title_force_stop_now),
                            onButton = onForceStopNow
                        )

                    var any = false
                    for ((section, allGroups) in state.sections) {
                        val visible = allGroups.filter { group ->
                            val found = foundHooks(context, state, group)
                            found == null || found.isNotEmpty()
                        }
                        if (visible.isEmpty())
                            continue
                        any = true
                        SectionBlock(
                            section = section,
                            groups = visible,
                            allGroups = allGroups,
                            state = state,
                            tick = tick,
                            mapping = mapping,
                            hookTop = hookTop,
                            hookHeight = hookHeight,
                            onSection = onSection,
                            onToggle = onToggle,
                            onGroup = onGroup,
                            onHook = onHook,
                            onShowErrors = onShowErrors,
                            onInfo = { info = it }
                        )
                    }

                    if (!any)
                        Text(
                            stringResource(
                                if (state.query.isEmpty()) R.string.title_no_available_hooks
                                else R.string.title_no_matches
                            ),
                            Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            textAlign = TextAlign.Center
                        )
                }

            if (state.loading)
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .semantics {
                            contentDescription = loadingDescription
                            liveRegion = LiveRegionMode.Polite
                        }
                )
        }
    }

    if (info != null)
        InfoBubble(info!!) { info = null }
}

@Composable
private fun AppBar(
    state: AppState,
    summary: String?,
    focusRequester: FocusRequester,
    onBack: () -> Unit,
    onLog: () -> Unit,
    onAppInfo: () -> Unit,
    onLaunch: () -> Unit,
    onToggleSearch: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.title_back)
            )
        }
        if (state.searching)
            OutlinedTextField(
                value = state.text,
                onValueChange = {
                    state.text = it
                    // Another search opens all sections again
                    state.toggled = emptySet()
                },
                modifier = Modifier
                                    .weight(1f)
                .focusRequester(focusRequester),
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                },
                trailingIcon = if (state.text.isEmpty()) null else {
                    {
                        IconButton(onClick = {
                            state.text = ""
                            state.toggled = emptySet()
                        }) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.title_clear_search)
                            )
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
            )
        else
            Column(
                Modifier
                    .weight(1f)
                    // After the navigation icon, like the title of the toolbar
                    .padding(start = 16.dp, end = 8.dp)
            ) {
                Text(
                    state.label,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // The summary of the top while it is scrolled away, see ToolbarSummary
                if (summary != null)
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
            }
        IconButton(onClick = onToggleSearch) {
            Icon(
                painterResource(if (state.searching) R.drawable.ic_close else R.drawable.ic_search),
                contentDescription = stringResource(
                    if (state.searching) R.string.title_close_search else R.string.menu_search
                )
            )
        }
        if (state.launchable)
            IconButton(onClick = onLaunch) {
                Icon(
                    painterResource(R.drawable.ic_open),
                    contentDescription = stringResource(R.string.menu_launch)
                )
            }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    painterResource(R.drawable.ic_more),
                    contentDescription = stringResource(R.string.title_more)
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_view_log)) },
                    onClick = {
                        menu = false
                        onLog()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_app_info)) },
                    onClick = {
                        menu = false
                        onAppInfo()
                    }
                )
            }
        }
    }
}

@Composable
private fun OverviewCard(
    state: AppState,
    app: XApp,
    restricted: Int,
    total: Int,
    complete: Boolean,
    summary: String,
    mapping: ScrollMapping,
    onRestrictAll: () -> Unit,
    onMeasure: (Int) -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (total == 0) 0f else 100f * restricted / total,
        animationSpec = tween(),
        label = "restricted"
    )
    Card(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                // In the content, also while it is scrolled, for the bar which hides the summary when it is out of sight
                onMeasure(
                    (it.positionInWindow().y - mapping.boxTop + it.size.height + mapping.scroll.value).roundToInt()
                )
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.packageName, app.icon, 52.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    SelectionContainer {
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = Mono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                    Row(Modifier.padding(top = 6.dp)) {
                        AppBadge(stringResource(if (app.system) R.string.title_filter_system else R.string.title_filter_user))
                        AppBadge(stringResource(R.string.title_uid, app.uid))
                        state.version?.let { AppBadge(it) }
                    }
                }
            }

            HorizontalDivider(
                Modifier.padding(top = 14.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // The share of the groups restricted and the last call, with a dash when not all hooks are
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(onClick = onRestrictAll),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    Text(
                        "$restricted",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        stringResource(R.string.title_restrict_all_groups),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        summary,
                        Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                AppSwitch(
                    checked = restricted > 0,
                    partial = restricted > 0 && !complete,
                    description = stringResource(R.string.title_restrict_all_groups),
                    modifier = Modifier.padding(start = 8.dp),
                    onCheckedChange = { onRestrictAll() }
                )
            }
        }
    }
}

@Composable
private fun OptionsCard(
    state: AppState,
    onValues: () -> Unit,
    onScope: (Boolean) -> Unit,
    onForceStop: (Boolean) -> Unit,
    onNotify: (Boolean) -> Unit
) {
    val app = state.app ?: return
    val scopeDescription = stringResource(R.string.title_scope)
    val stopDescription = stringResource(R.string.title_force_stop)
    val notifyDescription = stringResource(R.string.title_notify_usage)
    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            // Add the app to Doppel's LSPosed scope without opening the framework; shown only when a
            // framework with the libxposed service is connected, see Scope and ActivityApp
            if (state.scopeVisible)
                OptionRow(
                    title = stringResource(R.string.title_scope),
                    subtitle = stringResource(R.string.title_scope_hint),
                    onClick = { onScope(!state.scopeChecked) }
                ) {
                    Checkbox(
                        checked = state.scopeChecked,
                        onCheckedChange = onScope,
                        modifier = Modifier.semantics { contentDescription = scopeDescription }
                    )
                }

            if (state.scopeVisible)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            OptionRow(
                title = stringResource(R.string.title_force_stop),
                subtitle = stringResource(
                    if (app.persistent) R.string.title_force_stop_persistent else R.string.title_force_stop_hint
                ),
                onClick = { if (!app.persistent) onForceStop(!app.stopsAutomatically()) }
            ) {
                Checkbox(
                    checked = app.stopsAutomatically(),
                    onCheckedChange = onForceStop,
                    enabled = !app.persistent,
                    modifier = Modifier.semantics { contentDescription = stopDescription }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            OptionRow(
                title = stringResource(R.string.title_notify_usage),
                subtitle = stringResource(R.string.title_notify_usage_hint),
                onClick = { onNotify(!state.notify) }
            ) {
                Checkbox(
                    checked = state.notify,
                    onCheckedChange = onNotify,
                    modifier = Modifier.semantics { contentDescription = notifyDescription }
                )
            }

            if (state.hasValues) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val values = if (state.values == 0)
                    stringResource(R.string.title_values_none)
                else
                    pluralStringResource(R.plurals.title_values_own, state.values, state.values)
                OptionRow(
                    title = stringResource(R.string.title_values_app),
                    subtitle = values,
                    onClick = onValues
                ) {
                    Icon(
                        painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
        Box(Modifier.padding(start = 8.dp), contentAlignment = Alignment.Center) {
            trailing()
        }
    }
}

@Composable
private fun Banner(
    icon: Int,
    text: String,
    button: String? = null,
    onButton: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(themeColor(R.attr.colorWarningContainer))
            .padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = themeColor(R.attr.colorWarning)
        )
        Text(
            text,
            Modifier
                .weight(1f)
                .padding(start = 12.dp, end = if (button == null) 0.dp else 8.dp),
            style = MaterialTheme.typography.bodyMedium.copy(color = themeColor(R.attr.colorOnWarningContainer))
        )
        if (button != null && onButton != null)
            Button(
                onClick = onButton,
                colors = ButtonDefaults.buttonColors(
                    containerColor = themeColor(R.attr.colorWarning),
                    contentColor = themeColor(R.attr.colorWarningContainer)
                )
            ) {
                Text(button)
            }
    }
}

@Composable
private fun SectionBlock(
    section: Int,
    groups: List<String>,
    allGroups: List<String>,
    state: AppState,
    tick: Int,
    mapping: ScrollMapping,
    hookTop: MutableMap<String, Float>,
    hookHeight: MutableMap<String, Int>,
    onSection: (List<String>) -> Unit,
    onToggle: (String) -> Unit,
    onGroup: (String) -> Unit,
    onHook: (String, XHook, Boolean) -> Unit,
    onShowErrors: (String) -> Unit,
    onInfo: (GroupInfo) -> Unit
) {
    val app = state.app ?: return
    val title = stringResource(section)
    val restricted = allGroups.count { app.getAssignments(it).isNotEmpty() }
    val complete = allGroups.all { (app.getAssignments(it).size) >= (state.grouped[it]?.size ?: 0) }
    val button = stringResource(if (complete) R.string.title_section_none else R.string.title_section_all)
    val described = stringResource(
        if (complete) R.string.title_section_none_of else R.string.title_section_all_of,
        title
    )
    val sectionStyle = MaterialTheme.typography.labelMedium.copy(
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.08.em
    )

    Column(Modifier.padding(top = 20.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title.uppercase(Locale.getDefault()),
                Modifier
                    .weight(1f)
                    .semantics { heading() },
                style = sectionStyle
            )
            Text(
                stringResource(R.string.title_of, restricted, allGroups.size)
                    .uppercase(Locale.getDefault()),
                Modifier.padding(start = 8.dp),
                style = sectionStyle
            )
            OutlinedButton(
                onClick = { onSection(allGroups) },
                modifier = Modifier
                    .padding(start = 8.dp)
                    .semantics { contentDescription = described },
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(button)
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column {
                groups.forEachIndexed { index, group ->
                    if (index > 0)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    GroupRow(
                        group = group,
                        state = state,
                        tick = tick,
                        mapping = mapping,
                        hookTop = hookTop,
                        hookHeight = hookHeight,
                        onToggle = onToggle,
                        onGroup = onGroup,
                        onHook = onHook,
                        onShowErrors = onShowErrors,
                        onInfo = onInfo
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: String,
    state: AppState,
    tick: Int,
    mapping: ScrollMapping,
    hookTop: MutableMap<String, Float>,
    hookHeight: MutableMap<String, Int>,
    onToggle: (String) -> Unit,
    onGroup: (String) -> Unit,
    onHook: (String, XHook, Boolean) -> Unit,
    onShowErrors: (String) -> Unit,
    onInfo: (GroupInfo) -> Unit
) {
    val app = state.app ?: return
    val context = LocalContext.current
    val hooks = state.grouped[group] ?: return
    val found = foundHooks(context, state, group)
    // A group the search found by its hooks is open and shows those
    val byHooks = found != null
    val shown = found ?: hooks
    val open = if (byHooks) group !in state.toggled else group in state.expanded
    val title = Groups.getTitle(context, group)
    val description = Groups.getDescription(context, group)
    val assignments = app.getAssignments(group)
    var used = -1L
    var failed = false
    for (assignment in assignments) {
        if (assignment.restricted)
            used = max(used, assignment.used)
        if (assignment.exception != null)
            failed = true
    }
    val restricted = assignments.isNotEmpty()
    val background = when {
        failed -> themeColor(com.google.android.material.R.attr.colorErrorContainer)
        restricted -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val foreground = when {
        failed -> themeColor(com.google.android.material.R.attr.colorOnErrorContainer)
        restricted -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val metaColor = when {
        failed -> themeColor(androidx.appcompat.R.attr.colorError)
        restricted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // A restricted group which did not restrict anything yet has nothing to say, the switch tells
    val meta = when {
        assignments.isEmpty() -> stringResource(R.string.title_not_restricted)
        failed -> stringResource(R.string.title_group_error)
        assignments.size < hooks.size -> pluralStringResource(
            R.plurals.title_group_partial, hooks.size, assignments.size, hooks.size
        )
        used > 0 -> stringResource(R.string.title_restricted_when, Ui.ago(context, used))
        else -> null
    }
    val describeClick = stringResource(
        if (open) R.string.title_hide_hooks else R.string.title_show_hooks, title
    )
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "expand"
    )
    var iconBounds by remember(group) { mutableStateOf(Rect.Zero) }
    val groupInfo = if (description == null) null else stringResource(R.string.title_group_info, title)
    val switchDescription = title
    val errorDescription = stringResource(R.string.title_described, stringResource(R.string.title_show_error), title)

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClickLabel = describeClick) { onToggle(group) }
                .padding(start = 10.dp, top = 11.dp, end = 10.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tapping the icon shows the whole description in a bubble; a touch target of 48dp around it
            Box(
                Modifier
                    .size(48.dp)
                    .then(
                        if (groupInfo == null) Modifier
                        else Modifier
                            .clickable { onInfo(GroupInfo(title, description ?: "", iconBounds)) }
                            .semantics { contentDescription = groupInfo }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(background)
                        .onGloballyPositioned { iconBounds = it.boundsInRoot() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(Groups.getIcon(group)),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = foreground
                    )
                }
            }

            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (description != null)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                if (meta != null)
                    Text(
                        meta,
                        Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.labelMedium.copy(color = metaColor)
                    )
            }

            // Screen readers tell which group failed
            if (failed)
                IconButton(
                    onClick = { onShowErrors(group) },
                    modifier = Modifier.semantics { contentDescription = errorDescription }
                ) {
                    Icon(
                        painterResource(R.drawable.ic_error),
                        contentDescription = null,
                        tint = themeColor(androidx.appcompat.R.attr.colorError)
                    )
                }

            Icon(
                painterResource(R.drawable.ic_expand),
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 4.dp, end = 6.dp)
                    .size(20.dp)
                    .rotate(rotation),
                tint = MaterialTheme.colorScheme.outline
            )

            AppSwitch(
                checked = restricted,
                partial = restricted && assignments.size < hooks.size,
                description = switchDescription,
                onCheckedChange = { onGroup(group) }
            )
        }

        if (open) {
            val sorted = remember(shown, tick) {
                shown.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { hook: XHook -> hook.name })
            }
            Column(
                Modifier
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(4.dp)
            ) {
                for (hook in sorted)
                    HookRow(
                        group = group,
                        hook = hook,
                        app = app,
                        state = state,
                        mapping = mapping,
                        hookTop = hookTop,
                        hookHeight = hookHeight,
                        onHook = onHook
                    )
            }
        }
    }
}

@Composable
private fun HookRow(
    group: String,
    hook: XHook,
    app: XApp,
    state: AppState,
    mapping: ScrollMapping,
    hookTop: MutableMap<String, Float>,
    hookHeight: MutableMap<String, Int>,
    onHook: (String, XHook, Boolean) -> Unit
) {
    val context = LocalContext.current
    val index = app.assignments.indexOfFirst { it == XAssignment(hook) }
    val assignment = if (index < 0) null else app.assignments[index]
    val color = when {
        assignment == null -> MaterialTheme.colorScheme.onSurfaceVariant
        assignment.exception != null -> themeColor(androidx.appcompat.R.attr.colorError)
        assignment.restricted && assignment.used > 0 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val dot = when {
        assignment == null -> MaterialTheme.colorScheme.outline
        assignment.exception != null -> themeColor(androidx.appcompat.R.attr.colorError)
        assignment.restricted && assignment.used > 0 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val status = when {
        assignment == null -> stringResource(R.string.title_not_restricted)
        assignment.exception != null -> stringResource(R.string.title_hook_state_failed)
        assignment.restricted && assignment.used > 0 ->
            stringResource(R.string.title_restricted_when, Ui.ago(context, assignment.used))
        else -> stringResource(R.string.title_hook_not_used)
    }
    val highlighted = state.highlight == hook.id
    val glow = remember(hook.id) { Animatable(0f) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            glow.snapTo(0f)
            glow.animateTo(1f, tween(durationMillis = 500))
            delay(1500)
            glow.animateTo(0f, tween(durationMillis = 500))
        } else
            glow.snapTo(0f)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = glow.value),
                RoundedCornerShape(10.dp)
            )
            .onGloballyPositioned {
                // The position in the scroll content, see reveal
                hookTop[hook.id] = it.positionInWindow().y - mapping.boxTop + mapping.scroll.value
                hookHeight[hook.id] = it.size.height
            }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { onHook(group, hook, assignment == null) }
                .padding(start = 8.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    hook.name,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = Mono,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall.copy(color = color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AppSwitch(
                checked = assignment != null,
                partial = false,
                description = hook.name,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .graphicsLayer {
                        scaleX = 0.75f
                        scaleY = 0.75f
                    },
                onCheckedChange = { onHook(group, hook, it) }
            )
        }
    }
}

@Composable
private fun AppSwitch(
    checked: Boolean,
    partial: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.semantics { contentDescription = description },
        thumbContent = if (partial) {
            {
                Icon(
                    painterResource(R.drawable.ic_partial),
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            }
        } else null,
        colors = SwitchDefaults.colors(
            checkedTrackColor = if (partial)
                MaterialTheme.colorScheme.primary.copy(alpha = 0x80 / 255f)
            else
                MaterialTheme.colorScheme.primary
        )
    )
}

// The description of a group in a bubble pointing at its icon: below it, or above it when there is no room below;
// a tap anywhere and back close it, like the popup of the views, see the former ActivityApp.showInfo
@Composable
private fun InfoBubble(info: GroupInfo, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // The part of the window apps can draw in, like getWindowVisibleDisplayFrame of the views, without the system bars,
    // the display cutouts and the keyboard
    val insets = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .union(WindowInsets.ime)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val frameWidth = with(density) { maxWidth.toPx() }
        val frameHeight = with(density) { maxHeight.toPx() }
        val frameLeft = insets.getLeft(density, layoutDirection).toFloat()
        val frameTop = insets.getTop(density).toFloat()
        val frameRight = frameWidth - insets.getRight(density, layoutDirection)
        val frameBottom = frameHeight - insets.getBottom(density)
        val margin = with(density) { 16.dp.toPx() }
        val maxWidth = with(density) { 320.dp.toPx() }
        val leftInset = with(density) { 8.dp.toPx() }
        val rightInset = with(density) { 8.dp.toPx() }
        val topInset = with(density) { 4.dp.toPx() }
        val bottomInset = with(density) { 10.dp.toPx() }
        val bodyWidth = min(frameRight - frameLeft - 2 * margin, maxWidth)
        var height by remember(info) { mutableIntStateOf(0) }
        val above = height > 0 &&
                info.anchor.bottom + height - topInset > frameBottom - margin &&
                info.anchor.top - height + bottomInset >= frameTop + margin
        val center = info.anchor.left + info.anchor.width / 2
        val x = max(
            frameLeft + margin,
            min(center - with(density) { 29.dp.toPx() }, frameRight - margin - bodyWidth)
        )
        val y = if (above) info.anchor.top - height + bottomInset else info.anchor.bottom - topInset
        val arrow = center - x - with(density) { 9.dp.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
        Column(
            Modifier
                .offset { IntOffset((x - leftInset).roundToInt(), y.roundToInt()) }
                .width(with(density) { (leftInset + bodyWidth + rightInset).toDp() })
                .padding(start = with(density) { leftInset.toDp() }, end = with(density) { rightInset.toDp() })
                .graphicsLayer { alpha = if (height > 0) 1f else 0f }
                .onGloballyPositioned { height = it.size.height }
        ) {
            Spacer(Modifier.height(with(density) { topInset.toDp() }))
            if (!above)
                BubbleArrow(up = true, offset = with(density) { arrow.toDp() })
            Surface(
                Modifier
                    .width(with(density) { bodyWidth.toDp() })
                    .shadow(3.dp, RoundedCornerShape(14.dp)),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp)) {
                    Text(
                        info.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        info.description,
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
            if (above)
                BubbleArrow(up = false, offset = with(density) { arrow.toDp() })
            Spacer(Modifier.height(with(density) { bottomInset.toDp() }))
        }
    }
}

@Composable
private fun BubbleArrow(up: Boolean, offset: Dp) {
    Box(Modifier.fillMaxWidth()) {
        Image(
            painterResource(R.drawable.bg_bubble_arrow),
            contentDescription = null,
            modifier = Modifier
                .padding(start = offset)
                .size(18.dp, 9.dp)
                .rotate(if (up) 0f else 180f),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
    }
}

// The hooks of a group the search finds: all of a group found by its title, name or description,
// else the ones found by their name; null when the search found the group itself
private fun foundHooks(context: Context, state: AppState, group: String): List<XHook>? {
    val hooks = state.grouped[group] ?: return null
    val query = state.query
    if (query.isEmpty() ||
        Ui.contains(Groups.getTitle(context, group), query) ||
        Ui.contains(group, query) ||
        Ui.contains(Groups.getDescription(context, group), query)
    )
        return null
    return hooks.filter { Ui.contains(it.name, query) }
}
