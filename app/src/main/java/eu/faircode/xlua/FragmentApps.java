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

    Copyright 2017-2019 Marcel Bokhorst (M66B)
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// Installed apps by what needs attention, see AdapterApp
public class FragmentApps extends FragmentBase {
    private static final String TAG = "XLua.Apps";

    private static final String PREF_FILTER = "apps_filter";
    // Collapsed sections, comma separated; at first only all apps
    private static final String PREF_COLLAPSED = "apps_collapsed";

    // Same order as AdapterApp.Filter
    private static final int[] FILTER_BUTTONS = {R.id.btnFilterUser, R.id.btnFilterSystem, R.id.btnFilterAll};
    private static final int[] FILTER_TITLES = {R.string.title_filter_user, R.string.title_filter_system, R.string.title_all};

    private SwipeRefreshLayout swipeRefresh;
    private View pbLoading;
    private TextView tvEmpty;
    private TextView tvTitle;
    private View tilSearch;
    private EditText etSearch;
    private ImageButton ibSearch;
    private MaterialButtonToggleGroup tgFilter;
    private AdapterApp adapter;
    private OnBackPressedCallback closeSearch;

    private SharedPreferences prefs;
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
        tvTitle = view.findViewById(R.id.tvTitle);
        tilSearch = view.findViewById(R.id.tilSearch);
        etSearch = view.findViewById(R.id.etSearch);
        ibSearch = view.findViewById(R.id.ibSearch);
        tgFilter = view.findViewById(R.id.tgFilter);

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
            public void onChanged() {
                updateEmpty();
            }

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

        ibSearch.setOnClickListener(v -> showSearch(tilSearch.getVisibility() != View.VISIBLE));
        view.findViewById(R.id.ibMore).setOnClickListener(this::showMenu);

        Ui.onTextChanged(etSearch, text -> adapter.setQuery(text));

        AdapterApp.Filter filter = AdapterApp.Filter.USER;
        try {
            filter = AdapterApp.Filter.valueOf(prefs.getString(PREF_FILTER, filter.name()));
        } catch (IllegalArgumentException ignored) {
            // Like the former filter restricted, which the sections replace
        }
        adapter.setFilter(filter);
        tgFilter.check(FILTER_BUTTONS[filter.ordinal()]);
        tgFilter.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked)
                return;
            for (AdapterApp.Filter f : AdapterApp.Filter.values())
                if (FILTER_BUTTONS[f.ordinal()] == checkedId) {
                    prefs.edit().putString(PREF_FILTER, f.name()).apply();
                    adapter.setFilter(f);
                }
        });

        String value = prefs.getString(PREF_COLLAPSED, AdapterApp.Section.ALL.name());
        for (String name : value.split(","))
            for (AdapterApp.Section section : AdapterApp.Section.values())
                if (section.name().equals(name))
                    collapsed.add(section);
        adapter.setCollapsed(collapsed);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Back closes the search first, like its close button
        closeSearch = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                showSearch(false);
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), closeSearch);
    }

    // The search text is restored when the screen is recreated, like for other colors, and filters the apps,
    // so the search is shown again, without the keyboard, since the tab can be hidden
    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        if (!TextUtils.isEmpty(etSearch.getText()))
            setSearchShown(true);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        updateBack();
    }

    // Search replaces the title
    private void setSearchShown(boolean show) {
        tvTitle.setVisibility(show ? View.GONE : View.VISIBLE);
        tilSearch.setVisibility(show ? View.VISIBLE : View.GONE);
        ibSearch.setImageResource(show ? R.drawable.ic_close : R.drawable.ic_search);
        ibSearch.setContentDescription(getString(show ? R.string.title_close_search : R.string.menu_search));
        updateBack();
    }

    // Back closes the search of the tab shown, else it leaves the app
    private void updateBack() {
        if (closeSearch != null && tilSearch != null)
            closeSearch.setEnabled(tilSearch.getVisibility() == View.VISIBLE && !isHidden());
    }

    // Closing the search clears it
    private void showSearch(boolean show) {
        setSearchShown(show);
        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(requireActivity().getWindow(), etSearch);
        if (show) {
            etSearch.requestFocus();
            controller.show(WindowInsetsCompat.Type.ime());
        } else {
            etSearch.setText(null);
            controller.hide(WindowInsetsCompat.Type.ime());
        }
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
                List<XHook> hooks = Data.getHooks(context);
                List<String> collection = Data.getCollection(context);
                List<AdapterApp.Row> rows = new ArrayList<>();
                for (XApp app : Data.getApps(context))
                    rows.add(AdapterApp.Row.summarize(app, hooks, collection));
                Log.i(TAG, "Loaded apps=" + rows.size() + " hooks=" + hooks.size());

                post(() -> {
                    this.hooks = hooks;
                    this.collection = collection;
                    loaded = true;
                    adapter.set(rows);
                    swipeRefresh.setRefreshing(false);
                    pbLoading.setVisibility(View.GONE);
                    updateCounts();
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

    private void updateCounts() {
        for (AdapterApp.Filter filter : AdapterApp.Filter.values()) {
            Button button = tgFilter.findViewById(FILTER_BUTTONS[filter.ordinal()]);
            button.setText(getString(R.string.title_count,
                    getString(FILTER_TITLES[filter.ordinal()]), adapter.getCount(filter)));
        }
    }

    // A search without matches says so, rather than that there are no apps
    private void updateEmpty() {
        if (tvEmpty != null && loaded) {
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
