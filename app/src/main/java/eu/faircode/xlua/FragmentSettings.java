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

package eu.faircode.xlua;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

// Module status, global settings, data and information
public class FragmentSettings extends FragmentBase {
    private static final String TAG = "XLua.Settings";

    // Same order as the chips
    private static final int[] THEME_CHIPS = {R.id.chipThemeSystem, R.id.chipThemeLight, R.id.chipThemeDark};
    private static final String[] THEMES = {ApplicationEx.THEME_SYSTEM, ApplicationEx.THEME_LIGHT, ApplicationEx.THEME_DARK};

    private ImageView ivStatus;
    private TextView tvStatus;
    private TextView tvVersion;
    private CompactBar compact;
    private GridLayout glStatus;
    private TextView tvCollections;
    private View llRestrictNew;
    private MaterialSwitch swRestrictNew;
    private View llNotifyNew;
    private MaterialSwitch swNotifyNew;
    private ChipGroup cgTheme;
    private LinearLayout llPalettes;
    private LinearLayout llIcons;
    private View llBlack;
    private MaterialSwitch swBlack;

    private List<String> collections = new ArrayList<>(); // Known collections
    private List<String> enabled = new ArrayList<>(); // Enabled collections

    private final ActivityResultLauncher<String> backupLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri != null)
                    backup(uri);
            });

    private final ActivityResultLauncher<String[]> restoreLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null)
                    Ui.areYouSure(requireContext(), getString(R.string.msg_restore_sure), R.string.title_restore, () -> restore(uri));
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        final Activity activity = requireActivity();

        ivStatus = view.findViewById(R.id.ivStatus);
        tvStatus = view.findViewById(R.id.tvStatus);
        tvVersion = view.findViewById(R.id.tvVersion);
        glStatus = view.findViewById(R.id.glStatus);
        tvCollections = view.findViewById(R.id.tvCollections);
        llRestrictNew = view.findViewById(R.id.llRestrictNew);
        swRestrictNew = view.findViewById(R.id.swRestrictNew);
        llNotifyNew = view.findViewById(R.id.llNotifyNew);
        swNotifyNew = view.findViewById(R.id.swNotifyNew);
        cgTheme = view.findViewById(R.id.cgTheme);
        llPalettes = view.findViewById(R.id.llPalettes);
        llIcons = view.findViewById(R.id.llIcons);
        llBlack = view.findViewById(R.id.llBlack);
        swBlack = view.findViewById(R.id.swBlack);

        tvVersion.setText(getString(R.string.title_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        // The title and the captions of the cards, which are the texts between the cards, are headings for screen readers
        ViewCompat.setAccessibilityHeading(view.findViewById(R.id.tvTitle), true);
        LinearLayout llSettings = view.findViewById(R.id.llSettings);
        for (int i = 0; i < llSettings.getChildCount(); i++)
            if (llSettings.getChildAt(i) instanceof TextView)
                ViewCompat.setAccessibilityHeading(llSettings.getChildAt(i), true);

        // While the title is scrolled away: whether the hooks are live and the version
        compact = new CompactBar(view, R.string.nav_settings, null, null, null);
        compact.setSubtitle(tvVersion.getText());

        // The colors right away from the local copies of the settings, the service settings follow
        showPalettes(ApplicationEx.getPalette(activity));
        showIcons();
        swBlack.setChecked(ApplicationEx.isBlack(activity));

        // The switches and the theme can be changed once the settings are loaded, which would else undo a change made before
        for (View v : new View[]{llRestrictNew, swRestrictNew, llNotifyNew, swNotifyNew, llBlack, swBlack})
            v.setEnabled(false);
        Ui.enable(cgTheme, false);

        llRestrictNew.setOnClickListener(v -> swRestrictNew.toggle());
        llNotifyNew.setOnClickListener(v -> swNotifyNew.toggle());
        llBlack.setOnClickListener(v -> swBlack.toggle());
        view.findViewById(R.id.llCollections).setOnClickListener(v -> selectCollections());
        view.findViewById(R.id.llValues).setOnClickListener(v ->
                startActivity(ActivityValues.getIntent(activity, null, null)));
        view.findViewById(R.id.llBackup).setOnClickListener(v ->
                backupLauncher.launch(Ui.datedFileName("backup")));
        view.findViewById(R.id.llRestore).setOnClickListener(v ->
                restoreLauncher.launch(Ui.MIME_JSON));
        view.findViewById(R.id.llClear).setOnClickListener(v ->
                Ui.areYouSure(activity, getString(R.string.msg_clear_sure), R.string.title_reset, this::clear));
        view.findViewById(R.id.llDocs).setOnClickListener(v -> Ui.browse(activity, Ui.URL_README));
        view.findViewById(R.id.llFaq).setOnClickListener(v -> Ui.browse(activity, Ui.URL_FAQ));
        view.findViewById(R.id.llLicense).setOnClickListener(v -> Ui.getLicenseDialog(activity)
                .setPositiveButton(android.R.string.ok, null)
                .show());

        return view;
    }

    @Override
    protected void load() {
        final Context context = requireContext().getApplicationContext();
        run(TAG, () -> {
            int version = Data.getVersion(context);
            boolean available = Data.isAvailable(version);
            List<XHook> hooks = Data.getAllHooks(context, false);
            TreeSet<String> known = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (XHook hook : hooks)
                known.add(hook.getCollection());
            List<String> enabled = Data.getCollection(context);
            known.addAll(enabled);
            boolean restrictNew = Data.getSettingBoolean(context, "global", "restrict_new_apps");
            boolean notifyNew = Data.getSettingBoolean(context, "global", "notify_new_apps");
            ApplicationEx.Colors colors = ApplicationEx.Colors.get(context);

            post(() -> {
                // Restoring a backup or clearing data can change the colors and the theme
                if (ApplicationEx.sync(context, colors)) {
                    ((ActivityBase) requireActivity()).recreateSmoothly();
                    return;
                }
                showPalettes(ApplicationEx.getPalette(context));
                swBlack.setOnCheckedChangeListener(null);
                swBlack.setChecked(colors.black);
                swBlack.setOnCheckedChangeListener((button, checked) -> selectBlack(checked));

                this.collections = new ArrayList<>(known);
                this.enabled = enabled;

                tvStatus.setText(available ? R.string.title_module_active : R.string.msg_no_service);
                compact.setSubtitle(getString(R.string.title_pair, tvStatus.getText(), tvVersion.getText()));
                ivStatus.setImageResource(available ? R.drawable.ic_shield : R.drawable.ic_shield_off);
                // The shield of an active module has the accent color, see the palettes
                ivStatus.setBackgroundTintList(Ui.tint(requireContext(), available
                        ? com.google.android.material.R.attr.colorPrimaryContainer : R.attr.colorWarningContainer));
                ivStatus.setImageTintList(Ui.tint(requireContext(), available
                        ? com.google.android.material.R.attr.colorOnPrimaryContainer : R.attr.colorOnWarningContainer));
                glStatus.removeAllViews();
                // Digits of the language, like the counts of the Activity tab
                NumberFormat nf = NumberFormat.getIntegerInstance();
                nf.setGroupingUsed(false);
                addStatus(R.string.title_status_service, version < 0 ? "-" : nf.format(version));
                addStatus(R.string.title_status_hooks, nf.format(hooks.size()));
                addStatus(R.string.title_collections, nf.format(enabled.size()));
                addStatus(R.string.title_status_android, Build.VERSION.RELEASE + " · SDK " + Build.VERSION.SDK_INT);
                tvCollections.setText(TextUtils.join(", ", enabled));

                swRestrictNew.setOnCheckedChangeListener(null);
                swRestrictNew.setChecked(restrictNew);
                swRestrictNew.setOnCheckedChangeListener((button, checked) ->
                        put("restrict_new_apps", Boolean.toString(checked)));
                swNotifyNew.setOnCheckedChangeListener(null);
                swNotifyNew.setChecked(notifyNew);
                swNotifyNew.setOnCheckedChangeListener((button, checked) ->
                        put("notify_new_apps", Boolean.toString(checked)));
                for (View v : new View[]{llRestrictNew, swRestrictNew, llNotifyNew, swNotifyNew})
                    v.setEnabled(true);
                Ui.enable(cgTheme, true);

                // Selecting the chip of the theme loaded should not store it again
                cgTheme.setOnCheckedStateChangeListener(null);
                String theme = (ApplicationEx.THEME_DARK.equals(colors.theme) ? ApplicationEx.THEME_DARK
                        : ApplicationEx.THEME_LIGHT.equals(colors.theme) ? ApplicationEx.THEME_LIGHT
                        : ApplicationEx.THEME_SYSTEM);
                Ui.chips(cgTheme, THEME_CHIPS, THEMES, theme, selected -> run(TAG, () -> {
                    Data.putSetting(context, "global", "theme", selected);
                    post(() -> ApplicationEx.setTheme(context, selected));
                }));
                // Only the dark theme has a black background, also when the system theme is dark;
                // choosing a theme or a change of the system theme recreates the screen
                boolean dark = ((getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES);
                llBlack.setEnabled(dark);
                llBlack.setAlpha(dark ? 1f : 0.38f);
                swBlack.setEnabled(dark);
            });
        });
    }

    // A swatch per color theme in its own colors, of the light or dark theme shown
    private void showPalettes(String selected) {
        Context context = requireContext();
        llPalettes.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(context);
        for (int i = 0; i < ApplicationEx.PALETTES.length; i++) {
            final String palette = ApplicationEx.PALETTES[i];
            ContextThemeWrapper colors = new ContextThemeWrapper(context, R.style.Theme_XLua);
            int style = ApplicationEx.getPaletteStyle(palette);
            if (style != 0)
                colors.getTheme().applyStyle(style, true);

            View view = inflater.inflate(R.layout.item_palette, llPalettes, false);
            view.findViewById(R.id.flSwatch).setBackgroundTintList(
                    Ui.tint(colors, com.google.android.material.R.attr.colorPrimaryContainer));
            ImageView ivSwatch = view.findViewById(R.id.ivSwatch);
            ivSwatch.setBackgroundTintList(Ui.tint(colors, androidx.appcompat.R.attr.colorPrimary));
            ivSwatch.setImageTintList(Ui.tint(colors, com.google.android.material.R.attr.colorOnPrimary));
            // The check of the selected palette
            ivSwatch.setImageAlpha(palette.equals(selected) ? 255 : 0);
            view.setContentDescription(getString(ApplicationEx.PALETTE_TITLES[i]));
            view.setSelected(palette.equals(selected));
            view.setOnClickListener(v -> selectPalette(palette));
            llPalettes.addView(view);
        }
    }

    // The icon of the app in the launcher, see LauncherIcons
    private void showIcons() {
        Context context = requireContext();
        llIcons.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(context);
        int selected = LauncherIcons.getSelected(context);
        for (int i = 0; i < LauncherIcons.ALIASES.length; i++) {
            final int index = i;
            View view = inflater.inflate(R.layout.item_launcher_icon, llIcons, false);
            ((ImageView) view.findViewById(R.id.ivIcon)).setImageResource(LauncherIcons.ICONS[i]);
            view.setContentDescription(getString(LauncherIcons.TITLES[i]));
            view.setSelected(i == selected);
            view.setOnClickListener(v -> selectIcon(index));
            llIcons.addView(view);
        }
    }

    private void selectIcon(int index) {
        Context context = requireContext();
        if (index == LauncherIcons.getSelected(context))
            return;
        boolean pending = LauncherIcons.choose(context, index);
        showIcons();
        // Going back to the icon of the launcher changes nothing
        if (pending)
            Ui.message(requireActivity(), getString(R.string.msg_launcher_icon));
    }

    // The default palette is no setting; screens are recreated with the new colors
    private void selectPalette(String palette) {
        final Context context = requireContext().getApplicationContext();
        final String value = (ApplicationEx.PALETTE_DEFAULT.equals(palette) ? null : palette);
        run(TAG, () -> {
            Data.putSetting(context, "global", "palette", value);
            // Remembered also when the screen is gone meanwhile, which then shows the new colors when it is created
            if (ApplicationEx.setPalette(context, value))
                post(() -> ((ActivityBase) requireActivity()).recreateSmoothly());
        });
    }

    // Black instead of dark grey backgrounds in the dark theme, with any palette; off is no setting
    private void selectBlack(boolean black) {
        final Context context = requireContext().getApplicationContext();
        run(TAG, () -> {
            Data.putSetting(context, "global", "black", black ? Boolean.toString(true) : null);
            if (ApplicationEx.setBlack(context, black))
                post(() -> ((ActivityBase) requireActivity()).recreateSmoothly());
        });
    }

    private void addStatus(int key, String value) {
        View cell = LayoutInflater.from(getContext()).inflate(R.layout.item_status, glStatus, false);
        ((TextView) cell.findViewById(R.id.tvKey)).setText(key);
        ((TextView) cell.findViewById(R.id.tvValue)).setText(value);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        glStatus.addView(cell, lp);
    }

    private void put(String name, String value) {
        final Context context = requireContext().getApplicationContext();
        run(TAG, () -> Data.putSetting(context, "global", name, value));
    }

    // Hooks of disabled collections are not offered and not applied
    private void selectCollections() {
        if (collections.isEmpty())
            return;
        final String[] names = collections.toArray(new String[0]);
        final boolean[] checked = new boolean[names.length];
        for (int i = 0; i < names.length; i++)
            checked[i] = enabled.contains(names[i]);
        // At least one collection, because no setting is the default collection
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.title_collections)
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> {
                    checked[which] = isChecked;
                    ((AlertDialog) d).getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(isAnyChecked(checked));
                })
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    List<String> selected = new ArrayList<>();
                    for (int i = 0; i < names.length; i++)
                        if (checked[i])
                            selected.add(names[i]);
                    final Context context = requireContext().getApplicationContext();
                    run(TAG, () -> {
                        Data.putCollection(context, selected);
                        post(this::load);
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(isAnyChecked(checked));
    }

    private static boolean isAnyChecked(boolean[] checked) {
        for (boolean c : checked)
            if (c)
                return true;
        return false;
    }

    private void backup(Uri uri) {
        final Activity activity = requireActivity();
        run(TAG, () -> {
            int[] counts = Backup.save(activity, uri);
            Ui.message(activity, activity.getString(R.string.msg_backup_done, counts[0], counts[1], counts[2]));
        });
    }

    private void restore(Uri uri) {
        final Activity activity = requireActivity();
        run(TAG, () -> {
            int[] counts = Backup.restore(activity, uri);
            Ui.message(activity, activity.getString(R.string.msg_restore_done, counts[0], counts[1], counts[2]));
            post(this::load);
        });
    }

    // Restrictions and settings of the current user; custom definitions are kept
    private void clear() {
        final Activity activity = requireActivity();
        run(TAG, () -> {
            Data.clearData(activity);
            Ui.message(activity, activity.getString(R.string.msg_cleared));
            post(this::load);
        });
    }
}
