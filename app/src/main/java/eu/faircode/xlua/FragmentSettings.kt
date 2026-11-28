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
import android.content.DialogInterface
import android.content.res.Configuration
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import androidx.annotation.AttrRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.text.NumberFormat
import java.util.TreeSet

private data class SettingsUi(
    val versionText: String = "",
    @StringRes val statusRes: Int? = null,
    val stats: List<Pair<Int, String>> = emptyList(),
    val enabledCollections: List<String> = emptyList(),
    val restrictNew: Boolean = false,
    val notifyNew: Boolean = false,
    val loaded: Boolean = false,
    val theme: String = ApplicationEx.THEME_SYSTEM,
    val palette: String = ApplicationEx.PALETTE_DEFAULT,
    val palettes: List<PaletteOption> = emptyList(),
    val iconSelected: Int = -1,
    val black: Boolean = false,
    val blackEnabled: Boolean = false,
    val working: Boolean = false
)

private data class PaletteOption(
    val palette: String,
    @StringRes val title: Int,
    val container: Color,
    val primary: Color,
    val onPrimary: Color
)

// Module status, global settings, data and information
class FragmentSettings : FragmentBase() {
    companion object {
        private const val TAG = "XLua.Settings"
        private const val STATE_RESTORING = "restoring"

        // What the last restore did, shown by load once the restored colors do not recreate the screen anymore,
        // which would close the dialog
        private var restored: Backup.Result? = null

        // A backup, restore or clear is running: they change what the others read and write, so one at a time;
        // static like the result, the screen can be recreated meanwhile
        @Volatile
        private var working = false
    }

    private var ui by mutableStateOf(SettingsUi())

    private var collections: List<String> = emptyList() // Known collections
    private var enabled: List<String> = emptyList() // Enabled collections
    private var launcherIcon = -1 // The icon of the launcher, see load and LauncherIcons.getEnabled
    private var changes = 0 // The settings tapped, see put
    private var restoring: Uri? = null // The file of a restore which waits for its confirmation, asked again after a recreation

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { backup(it) } }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { confirmRestore(it) } }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        ui = SettingsUi(
            versionText = getString(
                R.string.title_version,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE
            ),
            palette = ApplicationEx.getPalette(context),
            palettes = buildPalettes(),
            iconSelected = LauncherIcons.getSelected(context, -1),
            black = ApplicationEx.isBlack(context),
            working = working
        )
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DoppelTheme {
                    SettingsScreen(
                        data = ui,
                        onRestrictNew = ::setRestrictNew,
                        onNotifyNew = ::setNotifyNew,
                        onCollections = ::selectCollections,
                        onValues = {
                            startActivity(ActivityValues.getIntent(requireActivity(), null, null))
                        },
                        onBackup = {
                            Ui.pick(requireActivity(), backupLauncher, Ui.datedFileName("backup"))
                        },
                        onRestore = {
                            Ui.pick(requireActivity(), restoreLauncher, Ui.MIME_JSON)
                        },
                        onClear = {
                            Ui.areYouSure(
                                requireActivity(),
                                getString(R.string.msg_clear_sure),
                                R.string.title_reset,
                                ::clear
                            )
                        },
                        onDocs = { Ui.browse(requireActivity(), Ui.URL_README) },
                        onFaq = { Ui.browse(requireActivity(), Ui.URL_FAQ) },
                        onLicense = {
                            Ui.getLicenseDialog(requireActivity())
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        },
                        onTheme = ::selectTheme,
                        onPalette = ::selectPalette,
                        onIcon = ::selectIcon,
                        onBlack = ::selectBlack
                    )
                }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (savedInstanceState != null && savedInstanceState.getString(STATE_RESTORING) != null)
            confirmRestore(Uri.parse(savedInstanceState.getString(STATE_RESTORING)))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (restoring != null)
            outState.putString(STATE_RESTORING, restoring.toString())
    }

    override fun load() {
        val context = requireContext().applicationContext
        // A switch or theme tapped while this loads is stored after it, so what was read is older than what is shown
        val before = changes
        submit {
            try {
                val version = Data.getVersion(context)
                val available = Data.isAvailable(version)
                val icon = LauncherIcons.getEnabled(context)
                val hooks = Data.getAllHooks(context, null)
                val known = TreeSet(String.CASE_INSENSITIVE_ORDER)
                for (hook in hooks)
                    known.add(hook.getCollection())
                val enabled = Data.getCollection(context)
                known.addAll(enabled)
                val restrictNew = Data.getSettingBoolean(context, Data.GLOBAL, Data.RESTRICT_NEW_APPS)
                val notifyNew = Data.getSettingBoolean(context, Data.GLOBAL, Data.NOTIFY_NEW_APPS)
                val colors = ApplicationEx.Colors.get(context)

                post {
                    // Restoring a backup or clearing data can change the colors and the theme
                    if (ApplicationEx.sync(context, colors)) {
                        (requireActivity() as ActivityBase).recreateSmoothly()
                        return@post
                    }
                    // Until it is closed, so also a screen recreated with the dialog open shows it again
                    if (restored != null && !hasDialog() &&
                        !ApplicationEx.changesNight(requireActivity(), colors.theme)
                    )
                        track(showRestored(requireActivity(), restored!!), Runnable { restored = null })

                    showWorking()
                    val current = (before == changes)
                    this.collections = ArrayList(known)
                    this.enabled = enabled
                    launcherIcon = icon

                    // Digits of the language, like the counts of the Activity tab
                    val nf = NumberFormat.getIntegerInstance()
                    nf.isGroupingUsed = false
                    val stats = listOf(
                        R.string.title_status_service to (if (version < 0) "-" else nf.format(version)),
                        R.string.title_status_hooks to nf.format(hooks.size),
                        R.string.title_collections to nf.format(enabled.size),
                        R.string.title_status_android to getString(
                            R.string.title_android_sdk,
                            Build.VERSION.RELEASE,
                            Build.VERSION.SDK_INT
                        )
                    )

                    // Selecting the chip of the theme loaded should not store it again
                    val theme = when (colors.theme) {
                        ApplicationEx.THEME_DARK -> ApplicationEx.THEME_DARK
                        ApplicationEx.THEME_LIGHT -> ApplicationEx.THEME_LIGHT
                        else -> ApplicationEx.THEME_SYSTEM
                    }
                    // Only the dark theme has a black background, also when the system theme is dark;
                    // choosing a theme or a change of the system theme recreates the screen
                    val dark = isDark()

                    ui = ui.copy(
                        statusRes = if (available) R.string.title_module_active else R.string.msg_no_service,
                        stats = stats,
                        enabledCollections = enabled,
                        restrictNew = if (current) restrictNew else ui.restrictNew,
                        notifyNew = if (current) notifyNew else ui.notifyNew,
                        loaded = true,
                        theme = if (current) theme else ui.theme,
                        palette = ApplicationEx.getPalette(context),
                        palettes = buildPalettes(),
                        iconSelected = LauncherIcons.getSelected(requireContext(), icon),
                        black = if (current) colors.black else ui.black,
                        blackEnabled = dark,
                        working = working
                    )
                }
            } catch (ex: Throwable) {
                loadError(TAG, ex)
            }
        }
    }

    private fun setRestrictNew(checked: Boolean) {
        ui = ui.copy(restrictNew = checked)
        put(Data.RESTRICT_NEW_APPS, checked.toString())
    }

    private fun setNotifyNew(checked: Boolean) {
        ui = ui.copy(notifyNew = checked)
        put(Data.NOTIFY_NEW_APPS, checked.toString())
    }

    private fun selectTheme(theme: String) {
        if (theme == ui.theme)
            return
        ui = ui.copy(theme = theme)
        val context = requireContext().applicationContext
        put("theme", theme) {
            val activity = activity
            if (activity is ActivityBase && ApplicationEx.changesNight(activity, theme))
                activity.recreateSmoothly(Runnable { ApplicationEx.setTheme(context, theme) })
            else
                ApplicationEx.setTheme(context, theme)
        }
    }

    // A swatch per color theme in its own colors, of the light or dark theme shown
    private fun buildPalettes(): List<PaletteOption> {
        val context = requireContext()
        return ApplicationEx.PALETTES.mapIndexed { index, palette ->
            val colors = ContextThemeWrapper(context, R.style.Theme_XLua)
            val style = ApplicationEx.getPaletteStyle(palette)
            if (style != 0)
                colors.theme.applyStyle(style, true)
            PaletteOption(
                palette = palette,
                title = ApplicationEx.PALETTE_TITLES[index],
                container = paletteColor(colors, com.google.android.material.R.attr.colorPrimaryContainer),
                primary = paletteColor(colors, androidx.appcompat.R.attr.colorPrimary),
                onPrimary = paletteColor(colors, com.google.android.material.R.attr.colorOnPrimary)
            )
        }
    }

    private fun paletteColor(context: Context, @AttrRes attr: Int): Color {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) Color(value.data)
        else Color.Unspecified
    }

    private fun selectIcon(index: Int) {
        val context = requireContext()
        // Until the icon of the launcher is loaded
        if (launcherIcon < 0 || index == LauncherIcons.getSelected(context, launcherIcon))
            return
        val pending = LauncherIcons.choose(context, index, launcherIcon)
        ui = ui.copy(iconSelected = LauncherIcons.getSelected(context, launcherIcon))
        // Going back to the icon of the launcher changes nothing
        if (pending)
            Ui.message(requireActivity(), getString(R.string.msg_launcher_icon))
    }

    // The default palette is no setting; screens are recreated with the new colors
    private fun selectPalette(palette: String) {
        val context = requireContext().applicationContext
        val value = if (ApplicationEx.PALETTE_DEFAULT == palette) null else palette
        run(TAG, Ui.Task {
            Data.putSetting(context, Data.GLOBAL, Data.PALETTE, value)
            // Remembered also when the screen is gone meanwhile, which then shows the new colors when it is created
            if (ApplicationEx.setPalette(context, value))
                post { (requireActivity() as ActivityBase).recreateSmoothly() }
        })
    }

    // Black instead of dark grey backgrounds in the dark theme, with any palette; off is no setting
    private fun selectBlack(black: Boolean) {
        ui = ui.copy(black = black)
        val context = requireContext().applicationContext
        put("black", if (black) java.lang.Boolean.TRUE.toString() else null) {
            if (ApplicationEx.setBlack(context, black))
                (requireActivity() as ActivityBase).recreateSmoothly()
        }
    }

    private fun put(name: String, value: String?, stored: (() -> Unit)? = null) {
        val context = requireContext().applicationContext
        // A load which runs shows what it read before this, see load
        changes++
        run(TAG, Ui.Task {
            try {
                Data.putSetting(context, Data.GLOBAL, name, value)
            } catch (ex: Throwable) {
                // Else the switch or chip shows the change which was not stored
                post { load() }
                throw ex
            }
            if (stored != null)
                post { stored() }
        })
    }

    // Hooks of disabled collections are not offered and not applied
    private fun selectCollections() {
        if (collections.isEmpty())
            return
        val names = collections.toTypedArray()
        val checked = BooleanArray(names.size) { enabled.contains(names[it]) }
        // At least one collection, because no setting is the default collection
        val dialog: AlertDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.title_collections)
            .setMultiChoiceItems(names, checked) { d: DialogInterface, which: Int, isChecked: Boolean ->
                checked[which] = isChecked
                (d as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = checked.any { it }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = names.filterIndexed { index, _ -> checked[index] }
                val context = requireContext().applicationContext
                run(TAG, Ui.Task {
                    Data.putCollection(context, selected)
                    post { load() }
                })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = checked.any { it }
    }

    private fun backup(uri: Uri) {
        val activity = requireActivity()
        work(Ui.Task {
            val counts = Backup.save(activity, uri)
            Ui.message(
                activity,
                activity.getString(R.string.msg_backup_done, counts[0], counts[1], counts[2])
            )
        })
    }

    // The question has what the file does beyond setting values, so it is read first
    private fun confirmRestore(uri: Uri) {
        val activity = requireActivity()
        run(TAG, Ui.Task {
            // Read once: the restore applies the file which the question was about
            val jroot = Backup.read(activity, uri)
            val preview = Backup.preview(activity, jroot)
            post {
                val question = StringBuilder(getString(R.string.msg_restore_sure))
                if (!preview.replaced.isEmpty())
                    question.append("\n\n")
                        .append(getString(R.string.msg_import_replaces, Ui.bullets(preview.replaced)))
                if (!preview.added.isEmpty())
                    question.append("\n\n")
                        .append(getString(R.string.msg_restore_scripts, Ui.bullets(preview.added)))
                if (preview.android)
                    question.append("\n\n").append(getString(R.string.msg_restore_android))
                restoring = uri
                track(
                    Ui.areYouSure(requireContext(), question, R.string.title_restore) { restore(jroot) },
                    Runnable { restoring = null }
                )
            }
        })
    }

    private fun restore(jroot: JSONObject) {
        val activity = requireActivity()
        work(Ui.Task {
            restored = Backup.restore(activity, jroot)
            post { load() }
        })
    }

    // Run a backup, restore or clear with progress shown, and none other started meanwhile; also not a restore which
    // was confirmed before another work started
    private fun work(task: Ui.Task) {
        if (working)
            return
        working = true
        showWorking()
        run(TAG, Ui.Task {
            try {
                task.run()
            } finally {
                working = false
                post { showWorking() }
            }
        })
    }

    private fun showWorking() {
        ui = ui.copy(working = working)
    }

    private fun isDark(): Boolean =
        (requireContext().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    // What a restore did, a line each; the lines without anything to say are left out
    private fun showRestored(context: Context, r: Backup.Result): AlertDialog {
        val lines = ArrayList<String>()
        if (r.version > 0 && r.version != BuildConfig.VERSION_CODE)
            lines.add(context.getString(R.string.title_restore_version, r.version, BuildConfig.VERSION_CODE))
        lines.add(context.resources.getQuantityString(R.plurals.title_restore_settings, r.settings, r.settings))
        lines.add(context.resources.getQuantityString(R.plurals.title_restore_hooks, r.hooks, r.hooks))
        if (r.hooksFailed > 0)
            lines.add(
                context.resources.getQuantityString(
                    R.plurals.title_restore_hooks_failed,
                    r.hooksFailed,
                    r.hooksFailed
                )
            )
        if (r.replaced.isNotEmpty())
            lines.add(context.getString(R.string.title_restore_replaced, names(context, r.replaced)))
        if (r.failed > 0)
            lines.add(context.resources.getQuantityString(R.plurals.title_restore_failed, r.failed, r.failed))
        lines.add(
            context.resources.getQuantityString(
                R.plurals.title_restore_apps,
                r.apps,
                r.apps,
                r.added
            )
        )
        if (r.missing.isNotEmpty())
            lines.add(
                context.resources.getQuantityString(
                    R.plurals.title_restore_missing,
                    r.missing.size,
                    r.missing.size,
                    names(context, r.missing)
                )
            )
        if (r.renamed > 0)
            lines.add(context.resources.getQuantityString(R.plurals.title_restore_renamed, r.renamed, r.renamed))
        if (r.removed > 0)
            lines.add(context.resources.getQuantityString(R.plurals.title_restore_removed, r.removed, r.removed))
        if (r.completed > 0)
            lines.add(context.resources.getQuantityString(R.plurals.title_restore_completed, r.completed, r.completed))
        if (r.partial > 0)
            lines.add(context.resources.getQuantityString(R.plurals.title_restore_partial, r.partial, r.partial))
        if (r.stopped.isNotEmpty())
            lines.add(context.getString(R.string.title_restore_stopped, names(context, r.stopped)))
        if (r.reboot.isNotEmpty())
            lines.add(context.getString(R.string.title_restore_reboot, names(context, r.reboot)))

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.title_restore_done)
            .setMessage(TextUtils.join("\n\n", lines))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // At most a few names, which can be many, like the apps of another device
    private fun names(context: Context, names: List<String>): String {
        val max = 5
        val separator = context.getString(R.string.title_list_separator)
        val shown = TextUtils.join(separator, names.subList(0, minOf(max, names.size)))
        return if (names.size > max) shown + separator + "…" else shown
    }

    // Restrictions and settings of the current user; custom definitions are kept
    private fun clear() {
        val activity = requireActivity()
        work(Ui.Task {
            Data.clearData(activity)
            Ui.message(activity, activity.getString(R.string.msg_cleared))
            post { load() }
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    data: SettingsUi,
    onRestrictNew: (Boolean) -> Unit,
    onNotifyNew: (Boolean) -> Unit,
    onCollections: () -> Unit,
    onValues: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onClear: () -> Unit,
    onDocs: () -> Unit,
    onFaq: () -> Unit,
    onLicense: () -> Unit,
    onTheme: (String) -> Unit,
    onPalette: (String) -> Unit,
    onIcon: (Int) -> Unit,
    onBlack: (Boolean) -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val largeText = density.fontScale > 1.3f
    val scroll = rememberScrollState()
    val workingLabel = stringResource(R.string.title_working)
    val navSpace = rememberNavSpace()
    val dark = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // The header scrolls away; the compact bar fades in over the last part of it, see SettingsCompactBar
    var headerHeight by remember { mutableIntStateOf(0) }
    val compactAlpha by remember {
        derivedStateOf { compactHeaderAlpha(scroll.value, headerHeight) }
    }

    val status = data.statusRes?.let { stringResource(it) }
    val compactSubtitle = if (status == null) data.versionText
    else stringResource(R.string.title_pair, status, data.versionText)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
        ) {
            // The title at the height of the title of the other tabs
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = 1f - compactAlpha }
                    .onGloballyPositioned { headerHeight = it.size.height }
                    .padding(start = 20.dp, top = 32.dp, end = 20.dp, bottom = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.nav_settings),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                StatusCard(data)

                SectionTitle(R.string.section_new_apps)
                SettingsCard {
                    SettingsSwitchRow(
                        title = R.string.title_restrict_new,
                        subtitle = R.string.title_restrict_new_hint,
                        checked = data.restrictNew,
                        enabled = data.loaded,
                        onChange = onRestrictNew
                    )
                    RowDivider()
                    SettingsSwitchRow(
                        title = R.string.title_notify_new,
                        subtitle = R.string.title_notify_new_hint,
                        checked = data.notifyNew,
                        enabled = data.loaded,
                        onChange = onNotifyNew
                    )
                }

                SectionTitle(R.string.section_hooks)
                SettingsCard {
                    SettingsRow(
                        title = R.string.title_collections,
                        subtitle = R.string.title_collections_hint,
                        extra = data.enabledCollections.joinToString(
                            stringResource(R.string.title_list_separator)
                        ),
                        onClick = onCollections,
                        enabled = data.enabledCollections.isNotEmpty()
                    ) { RowChevron() }
                }

                SectionTitle(R.string.section_appearance)
                SettingsCard {
                    ThemeRow(data.theme, data.loaded, onTheme)
                    RowDivider()
                    PaletteRow(data.palettes, data.palette, onPalette)
                    RowDivider()
                    IconRow(data.iconSelected, onIcon)
                    RowDivider()
                    SettingsSwitchRow(
                        title = R.string.title_black,
                        subtitle = R.string.title_black_hint,
                        checked = data.black,
                        enabled = data.blackEnabled,
                        alpha = if (dark) 1f else 0.38f,
                        onChange = onBlack
                    )
                }

                SectionTitle(R.string.section_data)
                if (data.working)
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = workingLabel }
                    )
                SettingsCard {
                    SettingsRow(
                        title = R.string.title_values_global,
                        subtitle = R.string.title_values_global_sub,
                        onClick = onValues
                    ) { RowChevron() }
                    RowDivider()
                    SettingsRow(
                        title = R.string.title_backup,
                        subtitle = R.string.title_backup_hint,
                        onClick = onBackup,
                        enabled = !data.working,
                        alpha = if (data.working) 0.38f else 1f
                    ) { RowChevron() }
                    RowDivider()
                    SettingsRow(
                        title = R.string.title_restore,
                        subtitle = R.string.title_restore_hint,
                        onClick = onRestore,
                        enabled = !data.working,
                        alpha = if (data.working) 0.38f else 1f
                    ) { RowChevron() }
                    RowDivider()
                    SettingsRow(
                        title = R.string.title_clear_data,
                        subtitle = R.string.title_clear_data_hint,
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = onClear,
                        enabled = !data.working,
                        alpha = if (data.working) 0.38f else 1f,
                        trailing = null
                    )
                }

                SectionTitle(R.string.section_about)
                SettingsCard {
                    SettingsRow(
                        title = R.string.title_readme,
                        onClick = onDocs
                    ) { Icon(painterResource(R.drawable.ic_open), null, Modifier.size(18.dp)) }
                    RowDivider()
                    SettingsRow(
                        title = R.string.title_faq,
                        onClick = onFaq
                    ) { Icon(painterResource(R.drawable.ic_open), null, Modifier.size(18.dp)) }
                    RowDivider()
                    SettingsRow(
                        title = R.string.title_license_row,
                        onClick = onLicense
                    ) { RowChevron() }
                }

                Spacer(Modifier.height(24.dp + navSpace))
            }
        }

        if (compactAlpha > 0.01f)
            SettingsCompactBar(
                title = stringResource(R.string.nav_settings),
                subtitle = compactSubtitle,
                showSubtitle = !largeText,
                alpha = compactAlpha
            )
    }
}

@Composable
private fun StatusCard(data: SettingsUi) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    val available = data.statusRes == R.string.title_module_active
    val container = if (available)
        themeColor(com.google.android.material.R.attr.colorPrimaryContainer)
    else themeColor(R.attr.colorWarningContainer)
    val content = if (available)
        themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
    else themeColor(R.attr.colorOnWarningContainer)

    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(container, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(
                            if (available) R.drawable.ic_shield else R.drawable.ic_shield_off
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = content
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 14.dp)
                ) {
                    if (data.statusRes != null)
                        Text(
                            text = stringResource(data.statusRes),
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    Text(
                        text = data.versionText,
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            if (data.stats.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                val columns = if (largeText) 1 else 2
                data.stats.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { (key, value) ->
                            StatCell(key, value, Modifier.weight(1f))
                        }
                        if (row.size < columns)
                            Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(@StringRes key: Int, value: String, modifier: Modifier) {
    Column(
        modifier.padding(top = 4.dp, end = 8.dp, bottom = 4.dp)
    ) {
        Text(
            text = stringResource(key).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.06.sp
            )
        )
        Text(
            text = value,
            modifier = Modifier.padding(top = 1.dp),
            style = MaterialTheme.typography.titleSmall.copy(
                color = MaterialTheme.colorScheme.onSurface
            )
        )
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(
        text = stringResource(title).uppercase(),
        modifier = Modifier
            .padding(start = 4.dp, top = 20.dp, bottom = 6.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.labelMedium.copy(
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 0.08.sp
        )
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

@Composable
private fun ColumnScope.RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun SettingsRow(
    @StringRes title: Int,
    @StringRes subtitle: Int? = null,
    extra: String? = null,
    titleColor: Color? = null,
    enabled: Boolean = true,
    alpha: Float = 1f,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() }
            .padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = titleColor ?: MaterialTheme.colorScheme.onSurface
                )
            )
            if (subtitle != null)
                Text(
                    text = stringResource(subtitle),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            if (!extra.isNullOrEmpty())
                Text(
                    text = extra,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    @StringRes title: Int,
    @StringRes subtitle: Int,
    checked: Boolean,
    enabled: Boolean,
    alpha: Float = 1f,
    onChange: (Boolean) -> Unit
) {
    val label = stringResource(title)
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
            Text(
                text = stringResource(subtitle),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label }
        )
    }
}

@Composable
private fun RowChevron() {
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.outline
    )
}

@Composable
private fun ThemeRow(selected: String, enabled: Boolean, onTheme: (String) -> Unit) {
    val themes = arrayOf(
        ApplicationEx.THEME_SYSTEM to R.string.title_theme_system,
        ApplicationEx.THEME_LIGHT to R.string.title_theme_light,
        ApplicationEx.THEME_DARK to R.string.title_theme_dark
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.title_theme),
            modifier = Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            )
        )
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            themes.forEach { (theme, title) ->
                FilterChip(
                    selected = selected == theme,
                    onClick = { onTheme(theme) },
                    enabled = enabled,
                    label = { Text(stringResource(title)) },
                    shape = CircleShape,
                    colors = settingsChipColors(),
                    border = null
                )
            }
        }
    }
}

@Composable
private fun settingsChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
)

@Composable
private fun PaletteRow(
    palettes: List<PaletteOption>,
    selected: String,
    onPalette: (String) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.title_palette),
            modifier = Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            )
        )
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
        ) {
            palettes.forEach { option ->
                val title = stringResource(option.title)
                Box(
                    Modifier
                        .size(48.dp)
                        .selectable(
                            selected = option.palette == selected,
                            role = Role.Button,
                            onClick = { onPalette(option.palette) }
                        )
                        .semantics { contentDescription = title },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(option.container, CircleShape)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(option.primary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (option.palette == selected)
                                Icon(
                                    painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = option.onPrimary
                                )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LauncherIcon(icon: Int, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val density = LocalDensity.current
    val size = with(density) { 40.dp.roundToPx() }
    val bitmap = remember(icon, size, resources) {
        createBitmap(size, size).also { bitmap ->
            ResourcesCompat.getDrawable(resources, icon, null)?.let { drawable ->
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(bitmap))
            }
        }.asImageBitmap()
    }
    Image(bitmap, contentDescription = null, modifier = modifier)
}

@Composable
private fun IconRow(selected: Int, onIcon: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, top = 2.dp, end = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.title_launcher_icon),
            modifier = Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            )
        )
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
        ) {
            LauncherIcons.ICONS.forEachIndexed { index, icon ->
                val title = stringResource(LauncherIcons.TITLES[index])
                Box(
                    Modifier
                        .size(52.dp)
                        .selectable(
                            selected = index == selected,
                            role = Role.Button,
                            onClick = { onIcon(index) }
                        )
                        .semantics { contentDescription = title },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .then(
                                if (index == selected)
                                    Modifier.border(
                                        2.5.dp,
                                        MaterialTheme.colorScheme.primary,
                                        CircleShape
                                    )
                                else Modifier
                            )
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LauncherIcon(icon, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCompactBar(
    title: String,
    subtitle: String,
    showSubtitle: Boolean,
    alpha: Float
) {
    CompactBar(alpha = alpha) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showSubtitle)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
        }
    }
}
