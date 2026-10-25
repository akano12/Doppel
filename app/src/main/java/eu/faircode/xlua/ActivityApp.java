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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Restrictions of one app, by group
public class ActivityApp extends ActivityBase {
    private static final String TAG = "XLua.AppDetail";

    private static final String EXTRA_PACKAGE = "package";
    private static final String EXTRA_UID = "uid";
    private static final String EXTRA_HOOK = "hook";

    // Settings of an app which are no fake values, see ActivityValues
    private static final Set<String> OPTIONS = new HashSet<>(Arrays.asList("forcestop", "notify"));

    private String packageName;
    private int uid;

    private XApp app = null;
    private final Map<String, List<XHook>> available = new HashMap<>();
    private final Map<String, GroupView> views = new HashMap<>();
    private final Set<String> expanded = new HashSet<>(); // Groups which show their hooks
    private boolean dirty = false; // Changed restrictions which do not apply yet
    private int changes = 0; // Changes made here, which a reload started before does not show yet
    private String reveal = null; // The id of a hook tapped in the log, shown when the restrictions are

    private MaterialToolbar toolbar;
    private NestedScrollView svContent;
    private View pbLoading;
    private CircularProgressIndicator piAll;
    private TextView tvAllCount;
    private TextView tvSummary;
    private MaterialSwitch swAll;
    private ColorStateList trackAll; // Of all groups fully restricted
    private MaterialCheckBox cbForceStop;
    private MaterialCheckBox cbNotify;
    private TextView tvValues;
    private View llRestart;
    private TextView tvRestart;
    private Button btnForceStop;

    private static class GroupView {
        View llRow;
        View flIcon;
        ImageView ivIcon;
        TextView tvMeta;
        ImageView ivExpand;
        MaterialSwitch swGroup;
        ColorStateList track; // Of a fully restricted group
        ImageButton ibError;
        LinearLayout llHooks;
    }

    static Intent getIntent(Context context, String packageName, int uid) {
        Intent intent = new Intent(context, ActivityApp.class);
        intent.putExtra(EXTRA_PACKAGE, packageName);
        intent.putExtra(EXTRA_UID, uid);
        return intent;
    }

    // With the hook expanded and highlighted, like a restriction tapped in the log
    static Intent getIntent(Context context, String packageName, int uid, String hook) {
        return getIntent(context, packageName, uid).putExtra(EXTRA_HOOK, hook);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app);

        packageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        uid = getIntent().getIntExtra(EXTRA_UID, -1);
        // Not again when the screen is restored
        if (savedInstanceState == null)
            reveal = getIntent().getStringExtra(EXTRA_HOOK);

        toolbar = setupToolbar(packageName);
        toolbar.inflateMenu(R.menu.app);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_launch)
                Ui.launch(this, packageName, getLabel());
            else if (id == R.id.menu_log)
                startActivity(ActivityLog.getIntent(this, packageName, uid, getLabel()));
            else if (id == R.id.menu_app_info)
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)));
            return true;
        });

        svContent = findViewById(R.id.svContent);
        pbLoading = findViewById(R.id.pbLoading);
        piAll = findViewById(R.id.piAll);
        tvAllCount = findViewById(R.id.tvAllCount);
        tvSummary = findViewById(R.id.tvSummary);
        swAll = findViewById(R.id.swAll);
        trackAll = swAll.getTrackTintList();
        cbForceStop = findViewById(R.id.cbForceStop);
        cbNotify = findViewById(R.id.cbNotify);
        tvValues = findViewById(R.id.tvValues);
        llRestart = findViewById(R.id.llRestart);
        tvRestart = findViewById(R.id.tvRestart);
        btnForceStop = findViewById(R.id.btnForceStop);

        findViewById(R.id.llAll).setOnClickListener(view -> restrictAll());
        swAll.setOnClickListener(view -> restrictAll());
        findViewById(R.id.llForceStop).setOnClickListener(view -> {
            if (cbForceStop.isEnabled())
                cbForceStop.toggle();
        });
        findViewById(R.id.llNotify).setOnClickListener(view -> cbNotify.toggle());
        btnForceStop.setOnClickListener(view -> forceStop());
        findViewById(R.id.llValues).setOnClickListener(view -> {
            if (app != null)
                startActivity(ActivityValues.getIntent(this, app.packageName, getLabel()));
        });

        load();
    }

    private void load() {
        final Context context = getApplicationContext();
        final int started = changes;
        executor.submit(() -> {
            try {
                XApp app = Data.getApp(context, packageName, uid);
                if (app == null) {
                    // The service gives no app on an error too, which should not close the screen
                    boolean installed = isInstalled(context, packageName, uid);
                    postUnlessGone(() -> {
                        // Uninstalled meanwhile, like with App info: its restrictions cannot be changed anymore
                        if (this.app != null && !installed) {
                            finish();
                            return;
                        }
                        pbLoading.setVisibility(View.GONE);
                        Ui.message(this, getString(R.string.msg_app_not_found, packageName));
                    });
                    return;
                }
                List<XHook> hooks = Data.getAvailable(Data.getHooks(context), app, Data.getCollection(context), null);
                Map<String, String> settings = Data.getSettings(context, app.packageName);
                boolean notify = Boolean.parseBoolean(settings.get("notify"));
                // The fake values of the app itself, the rest come from the values for all apps
                int own = 0;
                for (String name : settings.keySet())
                    if (!OPTIONS.contains(name))
                        own++;
                final int values = own;
                postUnlessGone(() -> {
                    // Changed while loading, which was sent after the data was read, on the same background thread;
                    // loading again shows the change, else the switches would go back
                    if (changes == started)
                        show(app, hooks, notify, values);
                    else
                        load();
                });
            } catch (Throwable ex) {
                postUnlessGone(() -> pbLoading.setVisibility(View.GONE));
                error(TAG, ex);
            }
        });
    }

    private static boolean isInstalled(Context context, String packageName, int uid) {
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(packageName, 0);
            return (uid < 0 || ai.uid == uid);
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private String getLabel() {
        return (app == null ? packageName : app.getLabel());
    }

    private void show(XApp app, List<XHook> hooks, boolean notify, int values) {
        this.app = app;
        uid = app.uid;

        toolbar.setTitle(getLabel());
        // Most system apps cannot be opened
        toolbar.getMenu().findItem(R.id.menu_launch)
                .setVisible(getPackageManager().getLaunchIntentForPackage(app.packageName) != null);

        Ui.loadIcon(findViewById(R.id.ivIcon), app.packageName, app.icon);
        ((TextView) findViewById(R.id.tvPackage)).setText(app.packageName);
        LinearLayout llBadges = findViewById(R.id.llBadges);
        llBadges.removeAllViews();
        Ui.addBadge(llBadges, getString(app.system ? R.string.title_filter_system : R.string.title_filter_user), Ui.Badge.NEUTRAL);
        Ui.addBadge(llBadges, getString(R.string.title_uid, app.uid), Ui.Badge.NEUTRAL);
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(app.packageName, 0);
            if (pi.versionName != null)
                Ui.addBadge(llBadges, pi.versionName, Ui.Badge.NEUTRAL);
        } catch (Throwable ignored) {
        }
        findViewById(R.id.llAndroid).setVisibility("android".equals(app.packageName) ? View.VISIBLE : View.GONE);

        cbForceStop.setOnCheckedChangeListener(null);
        cbForceStop.setChecked(app.forceStop && !app.persistent);
        cbForceStop.setEnabled(!app.persistent);
        ((TextView) findViewById(R.id.tvForceStop)).setText(app.persistent
                ? R.string.title_force_stop_persistent : R.string.title_force_stop_hint);
        final Context context = getApplicationContext();
        cbForceStop.setOnCheckedChangeListener((button, checked) -> {
            app.forceStop = checked;
            changes++;
            run(TAG, () -> Data.putSettingBoolean(context, app.packageName, "forcestop", checked));
            updateRestart();
        });

        // Read by the service when a hook restricts something, see XProvider.report
        cbNotify.setOnCheckedChangeListener(null);
        cbNotify.setChecked(notify);
        cbNotify.setOnCheckedChangeListener((button, checked) -> {
            changes++;
            run(TAG, () -> Data.putSettingBoolean(context, app.packageName, "notify", checked));
        });

        // Group the hooks
        available.clear();
        boolean hasValues = false;
        for (XHook hook : hooks) {
            if (!available.containsKey(hook.getGroup()))
                available.put(hook.getGroup(), new ArrayList<>());
            available.get(hook.getGroup()).add(hook);
            if (hook.getSettings().length > 0)
                hasValues = true;
        }
        findViewById(R.id.llValues).setVisibility(hasValues ? View.VISIBLE : View.GONE);
        tvValues.setText(values == 0
                ? getString(R.string.title_values_none)
                : getResources().getQuantityString(R.plurals.title_values_own, values, values));

        // Sections with their groups by title
        Map<Integer, List<String>> sections = new HashMap<>();
        for (String group : available.keySet()) {
            int section = Groups.getSection(group);
            if (!sections.containsKey(section))
                sections.put(section, new ArrayList<>());
            sections.get(section).add(group);
        }

        LinearLayout llSections = findViewById(R.id.llSections);
        llSections.removeAllViews();
        views.clear();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int section : Groups.SECTIONS) {
            List<String> groups = sections.get(section);
            if (groups == null)
                continue;
            Groups.sortByTitle(this, groups);

            View vSection = inflater.inflate(R.layout.item_section, llSections, false);
            TextView tvSection = vSection.findViewById(R.id.tvSection);
            tvSection.setText(section);
            ViewCompat.setAccessibilityHeading(tvSection, true);
            vSection.setTag(groups);
            LinearLayout llRows = vSection.findViewById(R.id.llRows);
            for (final String group : groups) {
                View vGroup = inflater.inflate(R.layout.item_group, llRows, false);
                GroupView gv = new GroupView();
                gv.llRow = vGroup.findViewById(R.id.llRow);
                gv.flIcon = vGroup.findViewById(R.id.flIcon);
                gv.ivIcon = vGroup.findViewById(R.id.ivIcon);
                gv.tvMeta = vGroup.findViewById(R.id.tvMeta);
                gv.ivExpand = vGroup.findViewById(R.id.ivExpand);
                gv.swGroup = vGroup.findViewById(R.id.swGroup);
                gv.track = gv.swGroup.getTrackTintList();
                gv.ibError = vGroup.findViewById(R.id.ibError);
                gv.llHooks = vGroup.findViewById(R.id.llHooks);
                String title = Groups.getTitle(this, group);
                ((TextView) vGroup.findViewById(R.id.tvTitle)).setText(title);
                gv.ivIcon.setImageResource(Groups.getIcon(group));
                TextView tvDescription = vGroup.findViewById(R.id.tvDescription);
                String description = Groups.getDescription(this, group);
                tvDescription.setText(description);
                tvDescription.setVisibility(description == null ? View.GONE : View.VISIBLE);
                gv.swGroup.setContentDescription(title);
                // The row shows the hooks, the switch restricts
                gv.llRow.setOnClickListener(view -> {
                    if (!expanded.remove(group))
                        expanded.add(group);
                    bind(group);
                });
                // Screen readers tell which group failed
                gv.ibError.setContentDescription(getString(R.string.title_show_error) + ", " + title);
                gv.ibError.setOnClickListener(view -> showErrors(group));
                views.put(group, gv);
                llRows.addView(vGroup);
                bind(group);
            }
            llSections.addView(vSection);
        }

        updateSummary();
        updateRestart();
        pbLoading.setVisibility(View.GONE);
        svContent.setVisibility(View.VISIBLE);

        if (reveal != null) {
            reveal(reveal);
            reveal = null;
        }
    }

    // A hook tapped in the log: its group is expanded, the hook scrolled to the middle and highlighted for a moment;
    // a hook which is not available anymore, like of a disabled collection, shows the screen only
    private void reveal(String id) {
        for (Map.Entry<String, List<XHook>> group : available.entrySet())
            for (XHook hook : group.getValue())
                if (hook.getId().equals(id)) {
                    expanded.add(group.getKey());
                    bind(group.getKey());
                    View vHook = views.get(group.getKey()).llHooks.findViewWithTag(id);
                    // After the layout of the expanded hooks
                    svContent.post(() -> {
                        Rect rect = new Rect();
                        vHook.getDrawingRect(rect);
                        svContent.offsetDescendantRectToMyCoords(vHook, rect);
                        svContent.smoothScrollTo(0, rect.top - (svContent.getHeight() - rect.height()) / 2);
                        highlight(vHook);
                    });
                    return;
                }
    }

    // Fades in, stays and fades out, then the hook has its own background again
    private void highlight(View view) {
        Drawable background = view.getBackground();
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Ui.color(this, com.google.android.material.R.attr.colorSecondaryContainer));
        shape.setCornerRadius(Ui.dp(this, 10));
        shape.setAlpha(0);
        view.setBackground(shape);
        ObjectAnimator animator = ObjectAnimator.ofInt(shape, "alpha", 0, 255, 255, 255, 255, 0);
        animator.setDuration(2500);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setBackground(background);
            }
        });
        animator.start();
    }

    // Colors of a group: text, icon background and icon
    private static final int[] COLORS_ERROR = {
            R.attr.colorWarning, R.attr.colorWarningContainer, R.attr.colorOnWarningContainer};
    private static final int[] COLORS_RESTRICTED = {
            androidx.appcompat.R.attr.colorPrimary,
            com.google.android.material.R.attr.colorSecondaryContainer,
            com.google.android.material.R.attr.colorOnSecondaryContainer};
    private static final int[] COLORS_NONE = {
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            com.google.android.material.R.attr.colorOnSurfaceVariant};

    private void bind(String group) {
        GroupView gv = views.get(group);
        List<XHook> hooks = available.get(group);
        List<XAssignment> assignments = app.getAssignments(group);

        long used = -1;
        boolean error = false;
        for (XAssignment assignment : assignments) {
            if (assignment.restricted)
                used = Math.max(used, assignment.used);
            if (assignment.exception != null)
                error = true;
        }

        boolean restricted = !assignments.isEmpty();
        int[] colors = (error ? COLORS_ERROR : restricted ? COLORS_RESTRICTED : COLORS_NONE);
        gv.tvMeta.setTextColor(Ui.color(this, colors[0]));
        gv.flIcon.setBackgroundTintList(Ui.tint(this, colors[1]));
        gv.ivIcon.setImageTintList(Ui.tint(this, colors[2]));
        // A restricted group which did not restrict anything yet has nothing to say, the switch tells
        String meta;
        if (assignments.isEmpty())
            meta = getString(R.string.title_not_restricted);
        else if (error)
            meta = getString(R.string.title_group_error);
        else if (assignments.size() < hooks.size())
            meta = getResources().getQuantityString(R.plurals.title_group_partial, hooks.size(), assignments.size(), hooks.size());
        else if (used > 0)
            meta = getString(R.string.title_restricted_when, Ui.ago(this, used));
        else
            meta = null;
        gv.tvMeta.setText(meta);
        gv.tvMeta.setVisibility(meta == null ? View.GONE : View.VISIBLE);

        gv.ibError.setVisibility(error ? View.VISIBLE : View.GONE);
        bindHooks(group, gv, hooks);

        setPartial(gv.swGroup, gv.track, restricted && assignments.size() < hooks.size());
        gv.swGroup.setOnCheckedChangeListener(null);
        gv.swGroup.setChecked(restricted);
        // A switch with a dash applies all hooks of the group, like Restrict all; only a complete group is switched off
        gv.swGroup.setOnCheckedChangeListener((button, checked) -> assign(group, !isComplete(group)));
    }

    // The hooks of an expanded group, with whether they restricted something, each can be assigned on its own
    private void bindHooks(String group, GroupView gv, List<XHook> hooks) {
        boolean show = expanded.contains(group);
        String title = Groups.getTitle(this, group);
        gv.ivExpand.setRotation(show ? 180 : 0);
        // Screen readers tell what tapping the row does, after its title and state
        ViewCompat.replaceAccessibilityAction(gv.llRow, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                getString(show ? R.string.title_hide_hooks : R.string.title_show_hooks, title), null);
        gv.llHooks.setVisibility(show ? View.VISIBLE : View.GONE);
        gv.llHooks.removeAllViews();
        if (!show)
            return;

        List<XHook> sorted = new ArrayList<>(hooks);
        Collections.sort(sorted, (h1, h2) -> h1.getName().compareToIgnoreCase(h2.getName()));
        LayoutInflater inflater = LayoutInflater.from(this);
        for (XHook hook : sorted) {
            View vHook = inflater.inflate(R.layout.item_group_hook, gv.llHooks, false);
            int index = app.assignments.indexOf(new XAssignment(hook));
            XAssignment assignment = (index < 0 ? null : app.assignments.get(index));

            // The outline color is too light for text
            int color;
            int dot;
            String status;
            if (assignment == null) {
                color = com.google.android.material.R.attr.colorOnSurfaceVariant;
                dot = com.google.android.material.R.attr.colorOutline;
                status = getString(R.string.title_not_restricted);
            } else if (assignment.exception != null) {
                color = dot = R.attr.colorWarning;
                status = getString(R.string.title_hook_state_failed);
            } else if (assignment.restricted && assignment.used > 0) {
                color = dot = androidx.appcompat.R.attr.colorPrimary;
                status = getString(R.string.title_restricted_when, Ui.ago(this, assignment.used));
            } else {
                color = dot = com.google.android.material.R.attr.colorOnSurfaceVariant;
                status = getString(R.string.title_hook_not_used);
            }

            vHook.setTag(hook.getId()); // See reveal
            ((TextView) vHook.findViewById(R.id.tvName)).setText(hook.getName());
            TextView tvStatus = vHook.findViewById(R.id.tvStatus);
            tvStatus.setText(status);
            tvStatus.setTextColor(Ui.color(this, color));
            vHook.findViewById(R.id.vDot).setBackgroundTintList(Ui.tint(this, dot));

            // Errors are shown with the button of the group
            MaterialSwitch swHook = vHook.findViewById(R.id.swHook);
            swHook.setChecked(assignment != null);
            swHook.setContentDescription(hook.getName());
            swHook.setOnCheckedChangeListener((button, checked) ->
                    assign(Collections.singletonList(group), Collections.singletonList(hook), checked));
            vHook.setOnClickListener(view -> swHook.toggle());
            gv.llHooks.addView(vHook);
        }
    }

    // A partially restricted group, or all groups partially restricted,
    // has a dash in the switch and a lighter track; track is the one of the switch when fully restricted
    private void setPartial(MaterialSwitch sw, ColorStateList track, boolean partial) {
        sw.setThumbIconDrawable(partial ? AppCompatResources.getDrawable(this, R.drawable.ic_partial) : null);
        sw.setTrackTintList(partial
                ? ColorStateList.valueOf(ColorUtils.setAlphaComponent(Ui.color(this, androidx.appcompat.R.attr.colorPrimary), 0x80))
                : track);
    }

    // All available hooks of the group are assigned
    private boolean isComplete(String group) {
        return (app.getAssignments(group).size() >= available.get(group).size());
    }

    // The ring and the line of Restrict all, and the counts of the sections, like 3 of 3
    private void updateSummary() {
        int restricted = 0;
        boolean complete = true;
        for (String group : available.keySet()) {
            if (!app.getAssignments(group).isEmpty())
                restricted++;
            if (!isComplete(group))
                complete = false;
        }
        // The last call restricted, of any group
        long last = -1;
        String lastGroup = null;
        for (XAssignment assignment : app.assignments)
            if (assignment.restricted && assignment.used > last) {
                last = assignment.used;
                lastGroup = assignment.hook.getGroup();
            }

        int groups = available.size();
        piAll.setProgressCompat(groups == 0 ? 0 : Math.round(100f * restricted / groups), true);
        tvAllCount.setText(String.format(Locale.getDefault(), "%d", restricted));
        String summary = getResources().getQuantityString(R.plurals.title_groups_restricted, groups, restricted, groups);
        if (last > 0)
            summary = getString(R.string.title_pair, summary,
                    getString(R.string.title_last_call, Groups.getTitle(this, lastGroup), Ui.ago(this, last)));
        tvSummary.setText(summary);
        swAll.setChecked(restricted > 0);
        setPartial(swAll, trackAll, restricted > 0 && !complete);

        LinearLayout llSections = findViewById(R.id.llSections);
        for (int i = 0; i < llSections.getChildCount(); i++) {
            View vSection = llSections.getChildAt(i);
            @SuppressWarnings("unchecked")
            List<String> groupsOf = (List<String>) vSection.getTag();
            int count = 0;
            for (String group : groupsOf)
                if (!app.getAssignments(group).isEmpty())
                    count++;
            ((TextView) vSection.findViewById(R.id.tvCount)).setText(getString(R.string.title_of, count, groupsOf.size()));
        }
    }

    // Restrict all hooks of all groups, or remove all restrictions when all hooks are restricted
    private void restrictAll() {
        if (app == null)
            return;
        boolean all = true;
        for (String group : available.keySet())
            if (!isComplete(group))
                all = false;
        final boolean assign = !all;
        final List<String> groups = new ArrayList<>(available.keySet());
        if (assign && "android".equals(app.packageName)) {
            updateSummary();
            Ui.areYouSure(this, getString(R.string.title_bootloop), R.string.title_restrict_all_groups, () -> assign(groups, true));
        } else
            assign(groups, assign);
    }

    private void assign(String group, boolean assign) {
        assign(Collections.singletonList(group), assign);
    }

    private void assign(List<String> groups, boolean assign) {
        List<XHook> candidates = new ArrayList<>();
        for (String group : groups)
            candidates.addAll(available.get(group));
        assign(groups, candidates, assign);
    }

    // Updates the assignments shown right away, the service in the background, with one call,
    // which stops the app once when it is stopped automatically
    private void assign(List<String> groups, List<XHook> candidates, boolean assign) {
        final List<XHook> hooks = new ArrayList<>();
        for (XHook hook : candidates) {
            XAssignment assignment = new XAssignment(hook);
            boolean assigned = app.assignments.contains(assignment);
            if (assign && !assigned) {
                app.assignments.add(assignment);
                hooks.add(hook);
            } else if (!assign && assigned) {
                app.assignments.remove(assignment);
                hooks.add(hook);
            }
        }
        for (String group : groups)
            bind(group);
        updateSummary();
        if (hooks.isEmpty())
            return;

        dirty = true;
        changes++;
        updateRestart();

        final Context context = getApplicationContext();
        final XApp app = this.app;
        run(TAG, () -> Data.assign(context, app, hooks, assign));
    }

    // Changes apply when the app starts, stopped apps start with the new restrictions
    private void updateRestart() {
        boolean show = (dirty && (app.persistent || !app.forceStop));
        llRestart.setVisibility(show ? View.VISIBLE : View.GONE);
        btnForceStop.setVisibility(app.persistent ? View.GONE : View.VISIBLE);
        tvRestart.setText(app.persistent
                ? getString(R.string.title_apply_reboot)
                : getString(R.string.title_apply_restart, getLabel()));
    }

    private void forceStop() {
        final Context context = getApplicationContext();
        final XApp app = this.app;
        dirty = false;
        updateRestart();
        run(TAG, () -> {
            Data.forceStop(context, app);
            Ui.message(this, getString(R.string.msg_stopped, getLabel()));
        });
    }

    private void showErrors(String group) {
        StringBuilder sb = new StringBuilder();
        for (XAssignment assignment : app.getAssignments(group))
            if (assignment.exception != null) {
                if (sb.length() > 0)
                    sb.append("\n\n");
                sb.append(assignment.hook.getId()).append("\n\n").append(assignment.exception.trim());
            }
        Ui.showText(this, Groups.getTitle(this, group), sb);
    }

    // A restriction tapped in the log of this app, which closed the log, see FragmentLog;
    // the restrictions are loaded again when the screen resumes
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        reveal = intent.getStringExtra(EXTRA_HOOK);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The service updates usage and errors while the app runs; changes made here are sent before,
        // on the same background thread
        if (app != null)
            load();
    }
}
