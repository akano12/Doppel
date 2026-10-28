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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The groups of an app from the Apps tab, like on the screen of the app but without its hooks: a switch per group
// by section, all of a section, Restrict all, and the screen of the app for single hooks and fake values.
// A group is restricted at once; closing the sheet tells the host what was assigned before, for Undo.
final class GroupsSheet {
    interface Host {
        // Work on the background thread
        void background(Ui.Task task);

        // The sheet closed after changes, with the hooks assigned before and now
        void changed(XApp app, Set<String> before, Set<String> after, List<XHook> available);
    }

    private final Activity activity;
    private final XApp app;
    private final Host host;
    private final List<XHook> available;
    private final Map<String, List<XHook>> groups = new LinkedHashMap<>();
    private final Set<String> assigned = new HashSet<>();
    private final Set<String> before;

    private final Map<String, View> rows = new HashMap<>();
    private final List<Runnable> sections = new ArrayList<>();
    private View view;
    private ColorStateList track; // Of a switch which is on

    private BottomSheetDialog dialog;

    static GroupsSheet show(Activity activity, XApp app, List<XHook> hooks, List<String> collection, Host host) {
        GroupsSheet sheet = new GroupsSheet(activity, app, hooks, collection, host);
        sheet.show();
        return sheet;
    }

    // Without telling the host, like when its view is gone; what changed is saved already
    void close() {
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
    }

    private GroupsSheet(Activity activity, XApp app, List<XHook> hooks, List<String> collection, Host host) {
        this.activity = activity;
        this.app = app;
        this.host = host;
        this.available = Data.getAvailable(hooks, app, collection, null);
        for (XHook hook : available) {
            if (!groups.containsKey(hook.getGroup()))
                groups.put(hook.getGroup(), new ArrayList<>());
            groups.get(hook.getGroup()).add(hook);
        }
        for (XAssignment assignment : app.assignments)
            assigned.add(assignment.id);
        this.before = new HashSet<>(assigned);
    }

    @SuppressLint("InflateParams")
    private void show() {
        Context context = activity;
        dialog = new BottomSheetDialog(context);
        LayoutInflater inflater = LayoutInflater.from(context);
        // The sheet is the parent, which the dialog makes
        view = inflater.inflate(R.layout.sheet_groups, null, false);
        dialog.setContentView(view);
        // Most of the screen, so the sections show; the list scrolls inside
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        lp.height = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.85f);
        view.setLayoutParams(lp);
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);

        Ui.loadIcon(view.findViewById(R.id.ivIcon), app.packageName, app.icon);
        ((TextView) view.findViewById(R.id.tvLabel)).setText(app.getLabel());
        MaterialSwitch swAll = view.findViewById(R.id.swAll);
        track = swAll.getTrackTintList();
        view.findViewById(R.id.cvAll).setOnClickListener(v -> {
            if (isComplete(available))
                assign(available, false);
            else if (app.isSystemServer())
                Ui.areYouSure(activity, activity.getString(R.string.title_bootloop),
                        R.string.title_restrict_all_groups, () -> assign(available, true));
            else
                assign(available, true);
        });
        view.findViewById(R.id.btnMore).setOnClickListener(v -> {
            dialog.dismiss();
            activity.startActivity(ActivityApp.getIntent(activity, app.packageName, app.uid));
        });
        view.findViewById(R.id.btnDone).setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            if (!assigned.equals(before))
                host.changed(app, before, assigned, available);
        });

        buildSections(inflater);
        update();
        dialog.show();
    }

    // The sections of the screen of the app, in its order, with the groups by title
    private void buildSections(LayoutInflater inflater) {
        Map<Integer, List<String>> bySection = new LinkedHashMap<>();
        for (int section : Groups.SECTIONS)
            bySection.put(section, new ArrayList<>());
        for (String group : groups.keySet())
            bySection.get(Groups.getSection(group)).add(group);

        LinearLayout llSections = view.findViewById(R.id.llSections);
        for (Map.Entry<Integer, List<String>> entry : bySection.entrySet()) {
            final List<String> names = entry.getValue();
            if (names.isEmpty())
                continue;
            Groups.sortByTitle(activity, names);

            View vSection = inflater.inflate(R.layout.item_section, llSections, false);
            TextView tvSection = vSection.findViewById(R.id.tvSection);
            String title = activity.getString(entry.getKey());
            tvSection.setText(title);
            ViewCompat.setAccessibilityHeading(tvSection, true);
            LinearLayout llRows = vSection.findViewById(R.id.llRows);
            for (String group : names)
                llRows.addView(buildGroup(inflater, llRows, group));

            TextView tvCount = vSection.findViewById(R.id.tvCount);
            Button btnSection = vSection.findViewById(R.id.btnSection);
            btnSection.setVisibility(View.VISIBLE);
            final List<XHook> hooks = new ArrayList<>();
            for (String group : names)
                hooks.addAll(groups.get(group));
            btnSection.setOnClickListener(v -> {
                boolean all = isComplete(hooks);
                // Like Restrict all, restricting all of a section of system server asks first
                if (!all && app.isSystemServer())
                    Ui.areYouSure(activity, activity.getString(R.string.title_bootloop),
                            R.string.title_restrict_all_groups, () -> assign(hooks, true));
                else
                    assign(hooks, !all);
            });
            sections.add(() -> {
                int restricted = 0;
                for (String group : names)
                    if (isRestricted(groups.get(group)))
                        restricted++;
                tvCount.setText(activity.getString(R.string.title_of, restricted, names.size()));
                boolean complete = isComplete(hooks);
                btnSection.setText(complete ? R.string.title_section_none : R.string.title_section_all);
                btnSection.setContentDescription(activity.getString(
                        complete ? R.string.title_section_none_of : R.string.title_section_all_of, title));
            });
            llSections.addView(vSection);
        }
    }

    // A group row of the screen of the app, without its hooks; the row and the switch restrict or remove the group
    private View buildGroup(LayoutInflater inflater, ViewGroup parent, String group) {
        View vGroup = inflater.inflate(R.layout.item_group, parent, false);
        ((TextView) vGroup.findViewById(R.id.tvTitle)).setText(Groups.getTitle(activity, group));
        ((ImageView) vGroup.findViewById(R.id.ivIcon)).setImageResource(Groups.getIcon(group));
        TextView tvDescription = vGroup.findViewById(R.id.tvDescription);
        String description = Groups.getDescription(activity, group);
        tvDescription.setText(description);
        tvDescription.setVisibility(description == null ? View.GONE : View.VISIBLE);
        View flInfo = vGroup.findViewById(R.id.flInfo);
        flInfo.setClickable(false);
        flInfo.setFocusable(false);
        flInfo.setBackground(null);
        flInfo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        vGroup.findViewById(R.id.ivExpand).setVisibility(View.GONE);
        vGroup.findViewById(R.id.ibError).setVisibility(View.GONE);
        List<XHook> hooks = groups.get(group);
        // A switch with a dash restricts the whole group, like on the screen of the app
        vGroup.findViewById(R.id.llRow).setOnClickListener(v -> assign(hooks, !isComplete(hooks)));
        rows.put(group, vGroup);
        return vGroup;
    }

    private void update() {
        int restricted = 0;
        for (String group : groups.keySet()) {
            List<XHook> hooks = groups.get(group);
            View vGroup = rows.get(group);
            int count = 0;
            for (XHook hook : hooks)
                if (assigned.contains(hook.getId()))
                    count++;
            boolean on = (count > 0);
            if (on)
                restricted++;
            Ui.Badge badge = (on ? Ui.Badge.ACCENT : Ui.Badge.NEUTRAL);
            vGroup.findViewById(R.id.flIcon).setBackgroundTintList(Ui.tint(activity, badge.background));
            ((ImageView) vGroup.findViewById(R.id.ivIcon)).setImageTintList(Ui.tint(activity, badge.text));
            TextView tvMeta = vGroup.findViewById(R.id.tvMeta);
            boolean partial = (on && count < hooks.size());
            tvMeta.setText(partial ? activity.getResources().getQuantityString(
                    R.plurals.title_group_partial, hooks.size(), count, hooks.size()) : null);
            tvMeta.setVisibility(partial ? View.VISIBLE : View.GONE);
            MaterialSwitch sw = vGroup.findViewById(R.id.swGroup);
            sw.setOnCheckedChangeListener(null);
            Ui.setPartial(sw, track, partial);
            sw.setChecked(on);
            sw.setOnCheckedChangeListener((button, checked) -> assign(hooks, !isComplete(hooks)));
        }
        for (Runnable section : sections)
            section.run();

        int total = groups.size();
        boolean complete = isComplete(available);
        LinearProgressIndicator pi = view.findViewById(R.id.piGroups);
        pi.setMax(Math.max(1, total));
        pi.setProgressCompat(restricted, true);
        pi.setIndicatorColor(Ui.color(activity, complete ? androidx.appcompat.R.attr.colorPrimary : R.attr.colorWarning));
        ((TextView) view.findViewById(R.id.tvCount)).setText(restricted == 0
                ? activity.getString(R.string.title_not_restricted)
                : activity.getResources().getQuantityString(R.plurals.title_groups_restricted, total, restricted, total));
        MaterialSwitch swAll = view.findViewById(R.id.swAll);
        Ui.setPartial(swAll, track, restricted > 0 && !complete);
        swAll.setChecked(restricted > 0);
        view.findViewById(R.id.cvAll).setContentDescription(activity.getString(R.string.title_restrict_all_groups) + ", " +
                ((TextView) view.findViewById(R.id.tvCount)).getText());
    }

    private boolean isComplete(List<XHook> hooks) {
        for (XHook hook : hooks)
            if (!assigned.contains(hook.getId()))
                return false;
        return true;
    }

    private boolean isRestricted(List<XHook> hooks) {
        for (XHook hook : hooks)
            if (assigned.contains(hook.getId()))
                return true;
        return false;
    }

    // Shown at once, saved in the background, only the hooks which change, since assigning a hook again starts its
    // use over
    private void assign(List<XHook> hooks, boolean assign) {
        final List<XHook> changed = new ArrayList<>();
        for (XHook hook : hooks)
            if (assign ? assigned.add(hook.getId()) : assigned.remove(hook.getId()))
                changed.add(hook);
        update();
        if (changed.isEmpty())
            return;
        final Context context = activity.getApplicationContext();
        host.background(() -> Data.assign(context, app, changed, assign));
    }
}
