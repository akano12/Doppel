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
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

// Installed apps and how much they are restricted
public class FragmentApps extends FragmentBase {
    private static final String TAG = "XLua.Apps";

    private static final String PREF_FILTER = "apps_filter";
    private static final String PREF_SORT = "apps_sort";

    private SwipeRefreshLayout swipeRefresh;
    private View pbLoading;
    private TextView tvEmpty;
    private TextView tvCount;
    private Button btnSort;
    private AdapterApp adapter;

    private SharedPreferences prefs;
    private AdapterApp.Sort sort;

    private List<XHook> hooks = new ArrayList<>();
    private List<String> collection = new ArrayList<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_apps, container, false);
        Context context = requireContext();
        prefs = context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);

        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        pbLoading = view.findViewById(R.id.pbLoading);
        tvEmpty = view.findViewById(R.id.tvEmpty);
        tvCount = view.findViewById(R.id.tvCount);
        btnSort = view.findViewById(R.id.btnSort);
        EditText etSearch = view.findViewById(R.id.etSearch);
        ChipGroup cgFilter = view.findViewById(R.id.cgFilter);

        adapter = new AdapterApp(new AdapterApp.Listener() {
            @Override
            public void onApp(XApp app) {
                startActivity(ActivityApp.getIntent(context, app.packageName, app.uid));
            }

            @Override
            public void onLaunch(XApp app) {
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(app.packageName);
                if (launch != null)
                    startActivity(launch);
            }
        });
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateCount();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                updateCount();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                updateCount();
            }
        });

        RecyclerView rvApps = view.findViewById(R.id.rvApps);
        rvApps.setLayoutManager(new LinearLayoutManager(context));
        rvApps.setAdapter(adapter);

        swipeRefresh.setColorSchemeColors(Ui.color(context, androidx.appcompat.R.attr.colorPrimary));
        swipeRefresh.setOnRefreshListener(this::load);

        view.findViewById(R.id.tvStatus).setOnClickListener(v -> {
            if (getActivity() instanceof ActivityMain)
                ((ActivityMain) getActivity()).selectSettings();
        });

        view.findViewById(R.id.ibMore).setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(context, v);
            popup.inflate(R.menu.apps);
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.menu_bulk)
                    bulk();
                else if (item.getItemId() == R.id.menu_help)
                    Ui.browse(requireActivity(), "https://github.com/M66B/XPrivacyLua/blob/master/README.md");
                return true;
            });
            popup.show();
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.setQuery(s.toString());
            }
        });

        AdapterApp.Filter filter = AdapterApp.Filter.USER;
        try {
            filter = AdapterApp.Filter.valueOf(prefs.getString(PREF_FILTER, filter.name()));
        } catch (IllegalArgumentException ignored) {
        }
        cgFilter.check(getChip(filter));
        adapter.setFilter(filter);
        cgFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty())
                return;
            AdapterApp.Filter selected = getFilter(checkedIds.get(0));
            prefs.edit().putString(PREF_FILTER, selected.name()).apply();
            adapter.setFilter(selected);
        });

        sort = (AdapterApp.Sort.RECENT.name().equals(prefs.getString(PREF_SORT, null))
                ? AdapterApp.Sort.RECENT : AdapterApp.Sort.NAME);
        adapter.setSort(sort);
        updateSort();
        btnSort.setOnClickListener(v -> {
            sort = (sort == AdapterApp.Sort.NAME ? AdapterApp.Sort.RECENT : AdapterApp.Sort.NAME);
            prefs.edit().putString(PREF_SORT, sort.name()).apply();
            adapter.setSort(sort);
            updateSort();
        });

        return view;
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
                    adapter.set(rows);
                    swipeRefresh.setRefreshing(false);
                    pbLoading.setVisibility(View.GONE);
                    updateCount();
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

    private void updateCount() {
        if (tvCount == null || pbLoading.getVisibility() == View.VISIBLE)
            return;
        int count = adapter.getItemCount();
        tvCount.setText(getResources().getQuantityString(R.plurals.title_apps_count, count, count));
        tvEmpty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
    }

    private void updateSort() {
        btnSort.setText(sort == AdapterApp.Sort.RECENT ? R.string.title_sort_recent : R.string.title_sort_name);
    }

    private static int getChip(AdapterApp.Filter filter) {
        switch (filter) {
            case ALL:
                return R.id.chipAll;
            case SYSTEM:
                return R.id.chipSystem;
            case RESTRICTED:
                return R.id.chipRestricted;
            default:
                return R.id.chipUser;
        }
    }

    private static AdapterApp.Filter getFilter(int chip) {
        if (chip == R.id.chipAll)
            return AdapterApp.Filter.ALL;
        else if (chip == R.id.chipSystem)
            return AdapterApp.Filter.SYSTEM;
        else if (chip == R.id.chipRestricted)
            return AdapterApp.Filter.RESTRICTED;
        else
            return AdapterApp.Filter.USER;
    }

    // Toggle a group for all shown apps: remove it when an app has it, else add it
    private void bulk() {
        final Context context = requireContext();
        final List<String> groups = Data.getGroupNames(hooks);
        if (groups.isEmpty())
            return;
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
        executor.submit(() -> {
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
            } catch (Throwable ex) {
                error(TAG, ex);
            }
            post(this::load);
        });
    }
}
