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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Installed apps by what needs attention, with an overview of the filter, see AdapterApp
public class FragmentApps extends FragmentBase {
    private static final String TAG = "XLua.Apps";

    private static final String PREF_FILTER = "apps_filter";
    private static final String PREF_SORT = "apps_sort";
    // Collapsed sections, comma separated; at first only all apps
    private static final String PREF_COLLAPSED = "apps_collapsed";

    // Same order as AdapterApp.Filter
    private static final int[] FILTER_CHIPS = {R.id.chipUser, R.id.chipSystem, R.id.chipAll};
    private static final int[] FILTER_TITLES = {R.string.title_filter_user, R.string.title_filter_system, R.string.title_all};
    private static final int[] OVERVIEW_TITLES = {
            R.plurals.title_overview_user, R.plurals.title_overview_system, R.plurals.title_overview_all};
    // Same order as AdapterApp.Sort
    private static final int[] SORT_TITLES = {R.string.sort_name, R.string.sort_recent, R.string.sort_installed};

    private SwipeRefreshLayout swipeRefresh;
    private View pbLoading;
    private TextView tvEmpty;
    private TextView tvStatus;
    private View cvOverview;
    private CircularProgressIndicator piShare;
    private TextView tvShare;
    private TextView tvOverview;
    private TextView tvOverviewMeta;
    private ChipGroup cgFilter;
    private ImageButton ibSort;
    private AdapterApp adapter;

    private SharedPreferences prefs;
    private AdapterApp.Sort sort = AdapterApp.Sort.NAME;
    private final Set<AdapterApp.Section> collapsed = EnumSet.noneOf(AdapterApp.Section.class);
    private boolean loaded = false;

    private List<XHook> hooks = new ArrayList<>();
    private List<String> collection = new ArrayList<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_apps, container, false);
        Context context = requireContext();
        prefs = ApplicationEx.getPreferences(context);

        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        pbLoading = view.findViewById(R.id.pbLoading);
        tvEmpty = view.findViewById(R.id.tvEmpty);
        tvStatus = view.findViewById(R.id.tvStatus);
        cvOverview = view.findViewById(R.id.cvOverview);
        piShare = view.findViewById(R.id.piShare);
        tvShare = view.findViewById(R.id.tvShare);
        tvOverview = view.findViewById(R.id.tvOverview);
        tvOverviewMeta = view.findViewById(R.id.tvOverviewMeta);
        cgFilter = view.findViewById(R.id.cgFilter);
        ibSort = view.findViewById(R.id.ibSort);

        adapter = new AdapterApp(new AdapterApp.Listener() {
            @Override
            public void onApp(XApp app) {
                startActivity(ActivityApp.getIntent(context, app.packageName, app.uid));
            }

            @Override
            public void onLaunch(XApp app) {
                Ui.launch(requireActivity(), app.packageName, app.getLabel());
            }

            @Override
            public void onRestrictAll(AdapterApp.Row row) {
                restrictAll(row);
            }

            @Override
            public void onToggle(AdapterApp.Section section) {
                toggle(section);
            }
        });
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                updateEmpty();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                updateEmpty();
            }
        });

        RecyclerView rvApps = view.findViewById(R.id.rvApps);
        rvApps.setLayoutManager(new LinearLayoutManager(context));
        // Rows change in place, sections open and close with the default animations
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        rvApps.setItemAnimator(animator);
        rvApps.setAdapter(adapter);

        swipeRefresh.setColorSchemeColors(Ui.color(context, androidx.appcompat.R.attr.colorPrimary));
        swipeRefresh.setOnRefreshListener(this::load);

        search = new TitleSearch(this, view.findViewById(R.id.tvTitle), view.findViewById(R.id.tilSearch),
                view.findViewById(R.id.etSearch), view.findViewById(R.id.ibSearch), text -> adapter.setQuery(text));
        view.findViewById(R.id.ibMore).setOnClickListener(this::showMenu);

        AdapterApp.Filter filter = AdapterApp.Filter.USER;
        try {
            filter = AdapterApp.Filter.valueOf(prefs.getString(PREF_FILTER, filter.name()));
        } catch (IllegalArgumentException ignored) {
            // Like the former filter restricted, which the sections replace
        }
        adapter.setFilter(filter);
        Ui.chips(cgFilter, FILTER_CHIPS, AdapterApp.Filter.values(), filter, f -> {
            prefs.edit().putString(PREF_FILTER, f.name()).apply();
            adapter.setFilter(f);
            updateOverview();
        });

        try {
            sort = AdapterApp.Sort.valueOf(prefs.getString(PREF_SORT, sort.name()));
        } catch (IllegalArgumentException ignored) {
        }
        adapter.setSort(sort);
        setSortDescription();
        ibSort.setOnClickListener(this::showSort);

        // The recent events are on the Activity tab
        cvOverview.setOnClickListener(v -> {
            if (getActivity() instanceof ActivityMain)
                ((ActivityMain) getActivity()).selectTab(R.id.nav_activity);
        });

        String value = prefs.getString(PREF_COLLAPSED, AdapterApp.Section.ALL.name());
        for (String name : value.split(","))
            for (AdapterApp.Section section : AdapterApp.Section.values())
                if (section.name().equals(name))
                    collapsed.add(section);
        adapter.setCollapsed(collapsed);

        return view;
    }

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.inflate(R.menu.apps);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_bulk)
                bulk();
            return true;
        });
        popup.show();
    }

    // The order of all apps, which is remembered; the other sections have their own
    private void showSort(View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        Menu menu = popup.getMenu();
        for (AdapterApp.Sort s : AdapterApp.Sort.values())
            menu.add(Menu.NONE, s.ordinal() + 1, s.ordinal(), SORT_TITLES[s.ordinal()]).setChecked(s == sort);
        menu.setGroupCheckable(Menu.NONE, true, true);
        popup.setOnMenuItemClickListener(item -> {
            sort = AdapterApp.Sort.values()[item.getItemId() - 1];
            prefs.edit().putString(PREF_SORT, sort.name()).apply();
            adapter.setSort(sort);
            setSortDescription();
            return true;
        });
        popup.show();
    }

    // Screen readers tell the order
    private void setSortDescription() {
        ibSort.setContentDescription(getString(R.string.title_sorted_by, getString(SORT_TITLES[sort.ordinal()])));
    }

    // Sections open and close when their header is tapped, which is remembered
    private void toggle(AdapterApp.Section section) {
        if (!collapsed.remove(section))
            collapsed.add(section);
        List<String> names = new ArrayList<>();
        for (AdapterApp.Section s : collapsed)
            names.add(s.name());
        prefs.edit().putString(PREF_COLLAPSED, TextUtils.join(",", names)).apply();
        adapter.setCollapsed(collapsed);
    }

    @Override
    public void onResume() {
        IntentFilter ifPackage = new IntentFilter();
        ifPackage.addAction(Intent.ACTION_PACKAGE_ADDED);
        ifPackage.addAction(Intent.ACTION_PACKAGE_CHANGED);
        ifPackage.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        ifPackage.addDataScheme("package");
        ContextCompat.registerReceiver(requireContext(), packageChangedReceiver, ifPackage, ContextCompat.RECEIVER_NOT_EXPORTED);
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        requireContext().unregisterReceiver(packageChangedReceiver);
    }

    private final BroadcastReceiver packageChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            // A hidden tab loads when it is shown again
            if (!isHidden())
                load();
        }
    };

    @Override
    protected void load() {
        final Context context = requireContext().getApplicationContext();
        executor.submit(() -> {
            try {
                boolean available = Data.isAvailable(Data.getVersion(context));
                List<XHook> hooks = Data.getHooks(context);
                List<String> collection = Data.getCollection(context);
                List<AdapterApp.Row> rows = new ArrayList<>();
                for (XApp app : Data.getApps(context))
                    rows.add(AdapterApp.Row.summarize(app, hooks, collection));
                Log.i(TAG, "Loaded apps=" + rows.size() + " hooks=" + hooks.size() + " available=" + available);

                post(() -> {
                    this.hooks = hooks;
                    this.collection = collection;
                    loaded = true;
                    adapter.set(rows);
                    swipeRefresh.setRefreshing(false);
                    pbLoading.setVisibility(View.GONE);
                    setStatus(available);
                    updateOverview();
                    updateEmpty();
                });
            } catch (Throwable ex) {
                post(() -> {
                    swipeRefresh.setRefreshing(false);
                    pbLoading.setVisibility(View.GONE);
                });
                error(TAG, ex);
            }
        });
    }

    // Whether the service answers, like the status card of Settings tells, with its shield
    private void setStatus(boolean available) {
        Context context = requireContext();
        tvStatus.setText(available ? R.string.badge_active : R.string.badge_inactive);
        int text = Ui.color(context, available ? R.attr.colorOnSuccessContainer : R.attr.colorOnWarningContainer);
        tvStatus.setTextColor(text);
        tvStatus.setBackgroundTintList(
                Ui.tint(context, available ? R.attr.colorSuccessContainer : R.attr.colorWarningContainer));
        Ui.setStartIcon(tvStatus, available ? R.drawable.ic_shield : R.drawable.ic_shield_off, 15, text);
        tvStatus.setContentDescription(getString(available ? R.string.title_module_active : R.string.msg_no_service));
        tvStatus.setVisibility(View.VISIBLE);
    }

    // The counts of the filter chips and the overview card follow the filter, not the search
    private void updateOverview() {
        if (!loaded)
            return;
        for (AdapterApp.Filter filter : AdapterApp.Filter.values())
            Ui.setChipCount(cgFilter, FILTER_CHIPS[filter.ordinal()], FILTER_TITLES[filter.ordinal()], adapter.getCount(filter));

        AdapterApp.Summary summary = adapter.getSummary();
        int percent = summary.getPercent();
        piShare.setProgressCompat(percent, true);
        tvShare.setText(String.format(Locale.getDefault(), "%d%%", percent));
        tvOverview.setText(getResources().getQuantityString(OVERVIEW_TITLES[adapter.getFilter().ordinal()],
                summary.total, summary.restricted, summary.total));
        List<String> parts = new ArrayList<>();
        if (summary.review > 0)
            parts.add(getResources().getQuantityString(R.plurals.title_overview_review, summary.review, summary.review));
        if (summary.recent > 0)
            parts.add(getResources().getQuantityString(R.plurals.title_overview_recent, summary.recent, summary.recent));
        tvOverviewMeta.setText(parts.isEmpty() ? getString(R.string.title_overview_none) : TextUtils.join(" · ", parts));
        cvOverview.setContentDescription(getString(R.string.title_overview) + ", " +
                tvOverview.getText() + ", " + tvOverviewMeta.getText());
        cvOverview.setVisibility(View.VISIBLE);
    }

    // A search without matches says so, rather than that there are no apps
    private void updateEmpty() {
        if (loaded) {
            tvEmpty.setText(adapter.isSearching() ? R.string.title_no_matches : R.string.title_no_apps);
            tvEmpty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        }
    }

    // All available hooks, like Restrict all on the screen of the app, which stops the app when it is stopped automatically;
    // the app had no restrictions (AdapterApp.Row.canRestrictAll), so removing the hooks again undoes it
    private void restrictAll(AdapterApp.Row row) {
        final Activity activity = requireActivity();
        final Context context = activity.getApplicationContext();
        final List<XHook> available = Data.getAvailable(hooks, row.app, collection, null);
        final String label = row.app.getLabel();
        run(TAG, () -> {
            Data.assign(context, row.app, available, true);
            Ui.message(activity, activity.getString(R.string.msg_restricted_all, label),
                    activity.getString(R.string.title_undo), () -> run(TAG, () -> {
                        Data.assign(context, row.app, available, false);
                        post(this::load);
                    }));
            post(this::load);
        });
    }

    // Toggle a group for all shown apps: remove it from the apps which have it, else add it to all of them
    private void bulk() {
        final Context context = requireContext();
        final List<String> groups = Data.getGroupNames(hooks);
        if (groups.isEmpty())
            return;
        Groups.sortByTitle(context, groups);
        String[] titles = new String[groups.size()];
        for (int i = 0; i < groups.size(); i++)
            titles[i] = Groups.getTitle(context, groups.get(i));

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.menu_bulk)
                .setItems(titles, (dialog, which) -> {
                    final String group = groups.get(which);
                    final String title = titles[which];
                    final List<XApp> apps = new ArrayList<>();
                    for (AdapterApp.Row row : adapter.getShown())
                        apps.add(row.app);
                    // The question tells which way, the restrictions of the shown apps are known
                    final List<XApp> restricting = getRestricting(apps, group);
                    if (restricting.isEmpty())
                        Ui.areYouSure(context,
                                getResources().getQuantityString(R.plurals.msg_bulk_add_sure, apps.size(), title, apps.size()),
                                R.string.menu_add, () -> bulk(group, title, apps, true));
                    else
                        Ui.areYouSure(context,
                                getResources().getQuantityString(R.plurals.msg_bulk_remove_sure, restricting.size(),
                                        title, restricting.size(), apps.size()),
                                R.string.title_remove, () -> bulk(group, title, restricting, false));
                })
                .show();
    }

    // The apps with one or more hooks of a group
    static List<XApp> getRestricting(List<XApp> apps, String group) {
        List<XApp> result = new ArrayList<>();
        for (XApp app : apps)
            if (!app.getAssignments(group).isEmpty())
                result.add(app);
        return result;
    }

    // Only the apps which are changed are stopped, when they are stopped automatically
    private void bulk(String group, String title, List<XApp> apps, boolean assign) {
        final Activity activity = requireActivity();
        final Context context = activity.getApplicationContext();
        final List<XHook> hooks = this.hooks;
        final List<String> collection = this.collection;
        run(TAG, () -> {
            try {
                Log.i(TAG, "Bulk group=" + group + " apps=" + apps.size() + " assign=" + assign);
                int changed = 0;
                for (XApp app : apps) {
                    List<XHook> available = Data.getAvailable(hooks, app, collection, group);
                    if (!available.isEmpty()) {
                        Data.assign(context, app, available, assign);
                        changed++;
                    }
                }
                Ui.message(activity, activity.getResources().getQuantityString(
                        assign ? R.plurals.msg_bulk_added : R.plurals.msg_bulk_removed, changed, title, changed));
            } finally {
                // Also the apps changed before an error
                post(this::load);
            }
        });
    }
}
