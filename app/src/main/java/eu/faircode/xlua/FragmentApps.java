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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

// Installed apps and how much they are restricted
public class FragmentApps extends FragmentBase {
    private static final String TAG = "XLua.Apps";

    private static final String PREF_FILTER = "apps_filter";
    private static final String PREF_SORT = "apps_sort";

    // Same order as AdapterApp.Filter
    private static final int[] FILTER_CHIPS = {R.id.chipAll, R.id.chipUser, R.id.chipSystem, R.id.chipRestricted};

    private SwipeRefreshLayout swipeRefresh;
    private View pbLoading;
    private TextView tvEmpty;
    private TextView tvTitle;
    private View tilSearch;
    private EditText etSearch;
    private ImageButton ibSearch;
    private ChipGroup cgFilter;
    private AdapterApp adapter;

    private SharedPreferences prefs;
    private AdapterApp.Sort sort;
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
        cgFilter = view.findViewById(R.id.cgFilter);

        adapter = new AdapterApp(new AdapterApp.Listener() {
            @Override
            public void onApp(XApp app) {
                startActivity(ActivityApp.getIntent(context, app.packageName, app.uid));
            }

            @Override
            public void onLaunch(XApp app) {
                Ui.launch(context, app.packageName);
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
        }
        adapter.setFilter(filter);
        Ui.chips(cgFilter, FILTER_CHIPS, AdapterApp.Filter.values(), filter, selected -> {
            prefs.edit().putString(PREF_FILTER, selected.name()).apply();
            adapter.setFilter(selected);
        });

        sort = (AdapterApp.Sort.RECENT.name().equals(prefs.getString(PREF_SORT, null))
                ? AdapterApp.Sort.RECENT : AdapterApp.Sort.NAME);
        adapter.setSort(sort);

        return view;
    }

    // Search replaces the title, closing it clears the search
    private void showSearch(boolean show) {
        tvTitle.setVisibility(show ? View.GONE : View.VISIBLE);
        tilSearch.setVisibility(show ? View.VISIBLE : View.GONE);
        ibSearch.setImageResource(show ? R.drawable.ic_close : R.drawable.ic_search);
        ibSearch.setContentDescription(getString(show ? R.string.title_close_search : R.string.menu_search));
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
        popup.getMenu().findItem(sort == AdapterApp.Sort.RECENT ? R.id.menu_sort_recent : R.id.menu_sort_name)
                .setChecked(true);
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_sort_name || id == R.id.menu_sort_recent) {
                sort = (id == R.id.menu_sort_recent ? AdapterApp.Sort.RECENT : AdapterApp.Sort.NAME);
                prefs.edit().putString(PREF_SORT, sort.name()).apply();
                adapter.setSort(sort);
            } else if (id == R.id.menu_bulk)
                bulk();
            return true;
        });
        popup.show();
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
            Chip chip = cgFilter.findViewById(FILTER_CHIPS[filter.ordinal()]);
            chip.setText(getString(R.string.title_filter_count, getString(getTitle(filter)), adapter.getCount(filter)));
        }
    }

    private void updateEmpty() {
        if (tvEmpty != null && loaded)
            tvEmpty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private static int getTitle(AdapterApp.Filter filter) {
        switch (filter) {
            case ALL:
                return R.string.title_all;
            case SYSTEM:
                return R.string.title_filter_system;
            case RESTRICTED:
                return R.string.title_filter_restricted;
            default:
                return R.string.title_filter_user;
        }
    }

    // Toggle a group for all shown apps: remove it when an app has it, else add it
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
                    final List<XApp> apps = new ArrayList<>();
                    for (AdapterApp.Row row : adapter.getShown())
                        apps.add(row.app);
                    Ui.areYouSure(context,
                            getString(R.string.msg_bulk_sure, titles[which], apps.size()),
                            () -> bulk(group, apps));
                })
                .show();
    }

    private void bulk(String group, List<XApp> apps) {
        final Context context = requireContext().getApplicationContext();
        final List<XHook> hooks = this.hooks;
        final List<String> collection = this.collection;
        run(TAG, () -> {
            try {
                boolean revert = false;
                for (XApp app : apps)
                    for (XAssignment assignment : app.assignments)
                        if (group.equals(assignment.hook.getGroup())) {
                            revert = true;
                            break;
                        }
                Log.i(TAG, "Bulk group=" + group + " apps=" + apps.size() + " revert=" + revert);
                for (XApp app : apps) {
                    List<XHook> available = Data.getAvailable(hooks, app, collection, group);
                    if (!available.isEmpty())
                        Data.assign(context, app, available, !revert);
                }
            } finally {
                // Also the apps changed before an error
                post(this::load);
            }
        });
    }
}
