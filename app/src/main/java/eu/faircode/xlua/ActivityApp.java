/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.
 */

package eu.faircode.xlua;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.text.Collator;
import java.util.ArrayList;
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

    private String packageName;
    private int uid;

    private XApp app = null;
    private final Map<String, List<XHook>> available = new HashMap<>();
    private final Map<String, GroupView> views = new HashMap<>();
    private final Set<String> expanded = new HashSet<>(); // Groups which show their hooks
    private boolean dirty = false; // Changed restrictions which do not apply yet

    private View svContent;
    private View pbLoading;
    private TextView tvSummary;
    private MaterialCheckBox cbAll;
    private MaterialSwitch swForceStop;
    private MaterialSwitch swNotify;
    private View llRestart;
    private TextView tvRestart;
    private Button btnForceStop;

    private static class GroupView {
        View flIcon;
        ImageView ivIcon;
        TextView tvTitle;
        TextView tvMeta;
        ImageView ivExpand;
        MaterialSwitch swGroup;
        ImageButton ibError;
        LinearLayout llHooks;
    }

    static Intent getIntent(Context context, String packageName, int uid) {
        Intent intent = new Intent(context, ActivityApp.class);
        intent.putExtra(EXTRA_PACKAGE, packageName);
        intent.putExtra(EXTRA_UID, uid);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app);

        packageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        uid = getIntent().getIntExtra(EXTRA_UID, -1);

        MaterialToolbar toolbar = setupToolbar(packageName);
        toolbar.inflateMenu(R.menu.app);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_launch) {
                Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
                if (launch != null)
                    startActivity(launch);
            } else if (id == R.id.menu_app_info)
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)));
            return true;
        });

        svContent = findViewById(R.id.svContent);
        pbLoading = findViewById(R.id.pbLoading);
        tvSummary = findViewById(R.id.tvSummary);
        cbAll = findViewById(R.id.cbAll);
        swForceStop = findViewById(R.id.swForceStop);
        swNotify = findViewById(R.id.swNotify);
        llRestart = findViewById(R.id.llRestart);
        tvRestart = findViewById(R.id.tvRestart);
        btnForceStop = findViewById(R.id.btnForceStop);

        findViewById(R.id.llAll).setOnClickListener(view -> restrictAll());
        cbAll.setOnClickListener(view -> restrictAll());
        findViewById(R.id.llForceStop).setOnClickListener(view -> {
            if (swForceStop.isEnabled())
                swForceStop.toggle();
        });
        findViewById(R.id.llNotify).setOnClickListener(view -> swNotify.toggle());
        btnForceStop.setOnClickListener(view -> forceStop());
        findViewById(R.id.llValues).setOnClickListener(view -> {
            if (app != null)
                startActivity(ActivityValues.getIntent(this, app.packageName, getLabel()));
        });

        load();
    }

    private void load() {
        final Context context = getApplicationContext();
        executor.submit(() -> {
            try {
                XApp app = Data.getApp(context, packageName, uid);
                if (app == null) {
                    runOnUiThread(() -> pbLoading.setVisibility(View.GONE));
                    Ui.message(this, getString(R.string.msg_app_not_found, packageName));
                    return;
                }
                List<XHook> hooks = Data.getAvailable(Data.getHooks(context), app, Data.getCollection(context), null);
                boolean notify = Data.getSettingBoolean(context, app.packageName, "notify");
                runOnUiThread(() -> show(app, hooks, notify));
            } catch (Throwable ex) {
                runOnUiThread(() -> pbLoading.setVisibility(View.GONE));
                error(TAG, ex);
            }
        });
    }

    private String getLabel() {
        return (app == null || TextUtils.isEmpty(app.label) ? packageName : app.label);
    }

    private void show(XApp app, List<XHook> hooks, boolean notify) {
        this.app = app;
        uid = app.uid;

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(getLabel());

        Ui.loadIcon(findViewById(R.id.ivIcon), app.packageName, app.icon);
        ((TextView) findViewById(R.id.tvPackage)).setText(app.packageName);
        String version = null;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(app.packageName, 0);
            version = pi.versionName;
        } catch (Throwable ignored) {
        }
        ((TextView) findViewById(R.id.tvMeta)).setText(getString(R.string.title_app_meta,
                app.uid, version == null ? "?" : version,
                getString(app.system ? R.string.badge_system : R.string.title_filter_user)));
        findViewById(R.id.llAndroid).setVisibility("android".equals(app.packageName) ? View.VISIBLE : View.GONE);

        swForceStop.setOnCheckedChangeListener(null);
        swForceStop.setChecked(app.forceStop && !app.persistent);
        swForceStop.setEnabled(!app.persistent);
        ((TextView) findViewById(R.id.tvForceStop)).setText(app.persistent
                ? R.string.title_force_stop_persistent : R.string.title_force_stop_hint);
        swForceStop.setOnCheckedChangeListener((button, checked) -> {
            app.forceStop = checked;
            executor.submit(() -> Data.putSettingBoolean(this, app.packageName, "forcestop", checked));
            updateRestart();
        });

        // Read by the service when a hook restricts something, see XProvider.report
        swNotify.setOnCheckedChangeListener(null);
        swNotify.setChecked(notify);
        swNotify.setOnCheckedChangeListener((button, checked) ->
                executor.submit(() -> Data.putSettingBoolean(this, app.packageName, "notify", checked)));

        // Group the hooks
        available.clear();
        boolean values = false;
        for (XHook hook : hooks) {
            if (!available.containsKey(hook.getGroup()))
                available.put(hook.getGroup(), new ArrayList<>());
            available.get(hook.getGroup()).add(hook);
            if (hook.getSettings().length > 0)
                values = true;
        }
        findViewById(R.id.llValues).setVisibility(values ? View.VISIBLE : View.GONE);

        // Sections with their groups by title
        final Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.SECONDARY);
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
            Collections.sort(groups, (g1, g2) -> collator.compare(Groups.getTitle(this, g1), Groups.getTitle(this, g2)));

            View vSection = inflater.inflate(R.layout.item_section, llSections, false);
            ((TextView) vSection.findViewById(R.id.tvSection)).setText(section);
            LinearLayout llRows = vSection.findViewById(R.id.llRows);
            for (final String group : groups) {
                View vGroup = inflater.inflate(R.layout.item_group, llRows, false);
                GroupView gv = new GroupView();
                gv.flIcon = vGroup.findViewById(R.id.flIcon);
                gv.ivIcon = vGroup.findViewById(R.id.ivIcon);
                gv.tvTitle = vGroup.findViewById(R.id.tvTitle);
                gv.tvMeta = vGroup.findViewById(R.id.tvMeta);
                gv.ivExpand = vGroup.findViewById(R.id.ivExpand);
                gv.swGroup = vGroup.findViewById(R.id.swGroup);
                gv.ibError = vGroup.findViewById(R.id.ibError);
                gv.llHooks = vGroup.findViewById(R.id.llHooks);
                String title = Groups.getTitle(this, group);
                gv.tvTitle.setText(title);
                gv.ivIcon.setImageResource(Groups.getIcon(group));
                TextView tvDescription = vGroup.findViewById(R.id.tvDescription);
                String description = Groups.getDescription(this, group);
                tvDescription.setText(description);
                tvDescription.setVisibility(description == null ? View.GONE : View.VISIBLE);
                gv.swGroup.setContentDescription(title);
                // The row shows the hooks, the switch restricts
                vGroup.findViewById(R.id.llRow).setOnClickListener(view -> {
                    if (!expanded.remove(group))
                        expanded.add(group);
                    bind(group);
                });
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
    }

    private List<XAssignment> getAssignments(String group) {
        return app.getAssignments(group);
    }

    private void bind(String group) {
        GroupView gv = views.get(group);
        List<XHook> hooks = available.get(group);
        List<XAssignment> assignments = getAssignments(group);

        long used = -1;
        boolean error = false;
        for (XAssignment assignment : assignments) {
            if (assignment.restricted)
                used = Math.max(used, assignment.used);
            if (assignment.exception != null)
                error = true;
        }

        boolean restricted = !assignments.isEmpty();
        gv.tvMeta.setTextColor(Ui.color(this, error
                ? R.attr.colorWarning
                : restricted
                ? androidx.appcompat.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
        gv.flIcon.setBackgroundTintList(ColorStateList.valueOf(Ui.color(this, error
                ? R.attr.colorWarningContainer
                : restricted
                ? com.google.android.material.R.attr.colorPrimaryContainer
                : com.google.android.material.R.attr.colorSurfaceContainerHigh)));
        gv.ivIcon.setImageTintList(ColorStateList.valueOf(Ui.color(this, error
                ? R.attr.colorOnWarningContainer
                : restricted
                ? com.google.android.material.R.attr.colorOnPrimaryContainer
                : com.google.android.material.R.attr.colorOnSurfaceVariant)));
        if (assignments.isEmpty())
            gv.tvMeta.setText(R.string.title_not_restricted);
        else if (error)
            gv.tvMeta.setText(R.string.title_group_error);
        else if (assignments.size() < hooks.size())
            gv.tvMeta.setText(getString(R.string.title_group_partial, assignments.size(), hooks.size()));
        else if (used > 0)
            gv.tvMeta.setText(getString(R.string.title_group_used, Ui.ago(used)));
        else
            gv.tvMeta.setText(R.string.title_not_used);

        gv.ibError.setVisibility(error ? View.VISIBLE : View.GONE);
        bindHooks(group, gv, hooks);

        gv.swGroup.setOnCheckedChangeListener(null);
        gv.swGroup.setChecked(!assignments.isEmpty());
        gv.swGroup.setOnCheckedChangeListener((button, checked) -> assign(group, checked));
    }

    // The hooks of an expanded group, with whether they restricted something
    private void bindHooks(String group, GroupView gv, List<XHook> hooks) {
        boolean show = expanded.contains(group);
        String title = Groups.getTitle(this, group);
        gv.ivExpand.setRotation(show ? 180 : 0);
        gv.ivExpand.setContentDescription(getString(show ? R.string.title_hide_hooks : R.string.title_show_hooks, title));
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

            int color;
            String status;
            if (assignment == null) {
                color = com.google.android.material.R.attr.colorOutline;
                status = getString(R.string.title_hook_not_restricted);
            } else if (assignment.exception != null) {
                color = R.attr.colorWarning;
                status = getString(R.string.title_hook_state_failed);
            } else if (assignment.restricted && assignment.used > 0) {
                color = androidx.appcompat.R.attr.colorPrimary;
                status = getString(R.string.title_hook_used, Ui.ago(assignment.used));
            } else {
                color = com.google.android.material.R.attr.colorOnSurfaceVariant;
                status = getString(R.string.title_hook_not_used);
            }

            ((TextView) vHook.findViewById(R.id.tvName)).setText(hook.getName());
            TextView tvStatus = vHook.findViewById(R.id.tvStatus);
            tvStatus.setText(status);
            tvStatus.setTextColor(Ui.color(this, color));
            vHook.findViewById(R.id.vDot).setBackgroundTintList(ColorStateList.valueOf(Ui.color(this, color)));
            if (assignment != null && assignment.exception != null) {
                final String exception = assignment.exception;
                vHook.setBackgroundResource(Ui.resource(this, android.R.attr.selectableItemBackground));
                vHook.setOnClickListener(view -> Ui.showText(this, hook.getId(), exception.trim()));
            }
            gv.llHooks.addView(vHook);
        }
    }

    // All available hooks of the group are assigned
    private boolean isComplete(String group) {
        return (getAssignments(group).size() >= available.get(group).size());
    }

    private void updateSummary() {
        int restricted = 0;
        boolean complete = true;
        for (String group : available.keySet()) {
            if (!getAssignments(group).isEmpty())
                restricted++;
            if (!isComplete(group))
                complete = false;
        }
        tvSummary.setText(getString(R.string.title_groups_restricted, restricted, available.size()));
        cbAll.setCheckedState(restricted > 0 && complete ? MaterialCheckBox.STATE_CHECKED
                : restricted > 0 ? MaterialCheckBox.STATE_INDETERMINATE
                : MaterialCheckBox.STATE_UNCHECKED);
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
            Ui.areYouSure(this, getString(R.string.title_bootloop), () -> assign(groups, true));
        } else
            assign(groups, assign);
    }

    private void assign(String group, boolean assign) {
        List<String> groups = new ArrayList<>();
        groups.add(group);
        assign(groups, assign);
    }

    // Updates the assignments shown right away, the service in the background
    private void assign(List<String> groups, boolean assign) {
        final List<XHook> hooks = new ArrayList<>();
        for (String group : groups)
            for (XHook hook : available.get(group)) {
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
        updateRestart();

        final Context context = getApplicationContext();
        final XApp app = this.app;
        executor.submit(() -> {
            try {
                Data.assign(context, app, hooks, assign);
            } catch (Throwable ex) {
                error(TAG, ex);
            }
        });
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
        executor.submit(() -> {
            try {
                Data.forceStop(context, app);
                Ui.message(this, getString(R.string.msg_stopped, getLabel()));
            } catch (Throwable ex) {
                error(TAG, ex);
            }
        });
    }

    private void showErrors(String group) {
        StringBuilder sb = new StringBuilder();
        for (XAssignment assignment : getAssignments(group))
            if (assignment.exception != null) {
                if (sb.length() > 0)
                    sb.append("\n\n");
                sb.append(assignment.hook.getId()).append("\n\n").append(assignment.exception.trim());
            }
        Ui.showText(this, Groups.getTitle(this, group), sb);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The service updates usage and errors while the app runs
        if (app != null && !dirty)
            load();
    }
}
