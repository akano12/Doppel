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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.faircode.xlua

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.function.Consumer

// Top level screens: apps, activity, hooks and settings
class ActivityMain : ActivityBase(), Ui.Anchored {
    companion object {
        private const val TAG = "XLua.Main"

        private const val TAB_APPS = "apps"
        private const val TAB_ACTIVITY = "activity"
        private const val TAB_HOOKS = "hooks"
        private const val TAB_SETTINGS = "settings"

        private const val STATE_AVAILABLE = "available"
        private const val STATE_SELECTED = "selected"

        // What to do, for the cases the app can tell apart: the module of the service is of another version, or the
        // framework is connected, so the module is enabled; not connected can be no framework, a framework with the
        // legacy API or a module which is not enabled, the app cannot tell, so the text covers them all
        @JvmStatic
        fun helpFor(version: Int, bound: Boolean, systemInScope: Boolean): Int {
            if (version >= 0 && version < BuildConfig.VERSION_CODE)
                return R.string.msg_module_version
            if (version < 0 && bound)
                return if (systemInScope) R.string.msg_no_service_restart else R.string.msg_no_service_scope
            return R.string.msg_no_service_help
        }
    }

    private var selected by mutableIntStateOf(R.id.nav_apps)
    private var available = false
    private var checked: Runnable? = null // What to show after checking the service, when the screen was left meanwhile
    private var anchor: View? = null // The bar of the tabs, which messages are shown above

    override fun onCreate(savedInstanceState: Bundle?) {
        // The masks on black while the app starts, then the theme of the app, see Theme.XLua.Starting
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // The tabs restored by the framework need the main view right away
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_AVAILABLE)) {
            selected = savedInstanceState.getInt(STATE_SELECTED, R.id.nav_apps)
            showMain(false)
            return
        }

        // Checking the service is a call into system server, so not on the main thread
        val context = applicationContext
        Ui.submit(Ui.Task {
            val version = Data.getVersion(context)
            val serviceAvailable = Data.isAvailable(version)
            // Why not, as far as the app can tell; binder calls
            val why = if (serviceAvailable) null else unavailable(context, version)
            // The local copies of the colors do not exist yet after an update
            val colors = if (serviceAvailable) ApplicationEx.Colors.get(context) else null
            postUnlessGone {
                val show = Runnable {
                    if (serviceAvailable) {
                        // The colors are applied when the screen is created
                        if (ApplicationEx.sync(context, colors)) {
                            recreate()
                            return@Runnable
                        }
                        showMain(true)
                    } else
                        showUnavailable(why)
                }
                // Tabs cannot be added after the state of the screen was saved, like when it was left meanwhile
                if (supportFragmentManager.isStateSaved)
                    checked = show
                else
                    show.run()
            }
        }, Consumer { ex ->
            // A failed check would else leave the screen blank; this screen has a button to try again
            postUnlessGone { showUnavailable(null) }
            error(TAG, ex)
        })
    }

    override fun onStart() {
        super.onStart()
        val show = checked ?: return
        checked = null
        show.run()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AVAILABLE, available)
        outState.putInt(STATE_SELECTED, selected)
    }

    private fun unavailable(context: Context, version: Int): String {
        val bound = Scope.available()
        return context.getString(
            helpFor(version, bound, bound && Scope.inScope("system")),
            version,
            BuildConfig.VERSION_CODE
        )
    }

    private fun showUnavailable(why: String?) {
        anchor = null
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DoppelTheme {
                    UnavailableScreen(
                        why = why,
                        onRetry = { recreate() },
                        onDocs = { Ui.browse(this@ActivityMain, Ui.URL_FAQ + "#user-content-faq3") }
                    )
                }
            }
        })
    }

    private fun showMain(first: Boolean) {
        available = true

        // The tabs are fragments, which the framework restores in onCreate and onStart, so their container exists
        // before that: a view built here, with the bar floating over it in a ComposeView, see NavBar
        val root = FrameLayout(this)
        root.addView(
            FrameLayout(this).apply { id = R.id.content },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    DoppelTheme {
                        NavBar(
                            selected = selected,
                            onSelect = { itemId -> select(itemId) },
                            onAnchor = { view -> anchor = view },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
        applyInsets(root)
        // The window may be attached already, when the check of the service outlived onCreate
        ViewCompat.requestApplyInsets(root)

        // Else the selected tab and fragments are restored by the framework
        if (first)
            show(TAB_APPS)

        checkFirstRun()
    }

    // Null without the main view, which places messages at the bottom
    override fun getMessageAnchor(): View? = anchor

    // Opens a tab, like the overview card of the Apps tab opens the Activity tab
    fun selectTab(itemId: Int) {
        if (available)
            select(itemId)
    }

    // Like tapping a tab; the tab which is selected already is not shown again
    private fun select(itemId: Int) {
        if (itemId == selected)
            return
        selected = itemId
        show(getTab(itemId))
    }

    private fun getTab(itemId: Int): String = when (itemId) {
        R.id.nav_activity -> TAB_ACTIVITY
        R.id.nav_hooks -> TAB_HOOKS
        R.id.nav_settings -> TAB_SETTINGS
        else -> TAB_APPS
    }

    // Tabs are kept, so they keep their scroll position and filters
    private fun show(tab: String) {
        val fm = supportFragmentManager
        val ft = fm.beginTransaction().setReorderingAllowed(true)
        for (fragment in fm.fragments)
            if (tab != fragment.tag && !fragment.isHidden)
                ft.hide(fragment)
        val fragment = fm.findFragmentByTag(tab)
        if (fragment == null)
            ft.add(R.id.content, createTab(tab), tab)
        else
            ft.show(fragment)
        ft.commit()
    }

    private fun createTab(tab: String): Fragment = when (tab) {
        TAB_ACTIVITY -> FragmentLog()
        TAB_HOOKS -> FragmentHooks()
        TAB_SETTINGS -> FragmentSettings()
        else -> FragmentApps()
    }

    private fun checkFirstRun() {
        val prefs = ApplicationEx.getPreferences(this)
        if (!prefs.getBoolean("firstrun", true))
            return

        Ui.getLicenseDialog(this)
            .setCancelable(false)
            .setPositiveButton(R.string.title_accept) { _, _ ->
                prefs.edit { putBoolean("firstrun", false) }
                // Once: restricting is not enough, the app must also be in the framework scope
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.title_first_steps)
                    .setMessage(R.string.msg_first_steps)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            .setNegativeButton(R.string.title_deny) { _, _ -> finishAffinity() }
            .show()
    }
}

// A tab of the bar, in the order the menu had them
private class NavTab(
    val id: Int,
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    @DrawableRes val selectedIcon: Int = icon
)

private val NavTabs = listOf(
    NavTab(R.id.nav_apps, R.drawable.ic_grid_view, R.string.nav_apps, R.drawable.ic_grid_view_filled),
    NavTab(R.id.nav_activity, R.drawable.ic_timeline, R.string.nav_activity),
    NavTab(R.id.nav_hooks, R.drawable.ic_data_object, R.string.nav_hooks),
    NavTab(R.id.nav_settings, R.drawable.ic_settings, R.string.nav_settings, R.drawable.ic_settings_filled)
)

// The bar of the tabs, floating as a pill: the selected tab has its icon next to its label and the width it needs,
// the other tabs are icons, named for screen readers, which share the rest of the width
@Composable
private fun NavBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    onAnchor: (View) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(dimensionResource(R.dimen.nav_floating_radius))
    Box(modifier) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = dimensionResource(R.dimen.nav_floating_margin_sides),
                    end = dimensionResource(R.dimen.nav_floating_margin_sides),
                    bottom = dimensionResource(R.dimen.nav_floating_margin)
                )
                .fillMaxWidth()
                .height(dimensionResource(R.dimen.nav_floating_height))
                .shadow(6.dp, shape, clip = false)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(horizontal = dimensionResource(R.dimen.nav_floating_padding))
        ) {
            // The anchor of messages: the bar, as it was for the view
            AndroidView(
                factory = { context ->
                    View(context).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        onAnchor(this)
                    }
                },
                modifier = Modifier.matchParentSize()
            )

            NavBarItems(selected = selected, onSelect = onSelect)
        }
    }
}

@Composable
private fun NavBarItems(selected: Int, onSelect: (Int) -> Unit) {
    val titles = NavTabs.map { stringResource(it.title) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val iconSize = dimensionResource(R.dimen.nav_icon_size)
    val labelSpacing = dimensionResource(R.dimen.nav_label_spacing)
    val itemPadding = dimensionResource(R.dimen.nav_item_padding)
    val itemHeight = dimensionResource(R.dimen.nav_item_height)

    // The width a tab needs with its label, which the others leave it, in pixels
    val fullWidths = titles.map { title ->
        with(density) {
            (itemPadding * 2 + iconSize + labelSpacing).toPx() +
                    measurer.measure(
                        AnnotatedString(title),
                        style = style,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    ).size.width
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(density) { maxWidth.toPx() }
        val index = NavTabs.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        // The others share the rest of the width, like the weighted tabs of the view
        val rest = (width - fullWidths[index]) / (NavTabs.size - 1)
        val widths = List(NavTabs.size) { i ->
            animateFloatAsState(
                targetValue = if (i == index) fullWidths[i] else rest,
                animationSpec = tween(durationMillis = 300),
                label = "tab width"
            )
        }

        Row(
            Modifier
                .fillMaxSize()
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTabs.forEachIndexed { i, tab ->
                NavTabItem(
                    tab = tab,
                    title = titles[i],
                    selected = i == index,
                    onSelect = { onSelect(tab.id) },
                    modifier = Modifier
                        .width(with(density) { widths[i].value.toDp() })
                        .height(itemHeight)
                )
            }
        }
    }
}

@Composable
private fun NavTabItem(
    tab: NavTab,
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(dimensionResource(R.dimen.nav_item_radius))
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    val interaction = remember { MutableInteractionSource() }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(title) } },
        state = rememberTooltipState(),
        modifier = modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = title }
                .selectable(
                    selected = selected,
                    interactionSource = interaction,
                    indication = ripple(color = themeColor(android.R.attr.colorControlHighlight)),
                    role = Role.Tab,
                    onClick = { if (!selected) onSelect() }
                )
                .padding(horizontal = dimensionResource(R.dimen.nav_item_padding)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(if (selected) tab.selectedIcon else tab.icon),
                null,
                Modifier.size(dimensionResource(R.dimen.nav_icon_size)),
                tint = content
            )
            if (selected) {
                Spacer(Modifier.width(dimensionResource(R.dimen.nav_label_spacing)))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

@Composable
private fun UnavailableScreen(why: String?, onRetry: () -> Unit, onDocs: () -> Unit) {
    val warning = themeColor(R.attr.colorWarning)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(screenInsets())
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_shield_off),
                null,
                Modifier.size(64.dp),
                tint = warning
            )
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.msg_no_service),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                why ?: stringResource(R.string.msg_no_service_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.title_retry))
            }
            // The buttons of the view had their insets, which Compose does not draw
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onDocs) {
                Text(stringResource(R.string.title_faq))
            }
        }
    }
}
