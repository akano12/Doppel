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
    // Collapsed sections, comma separated; the others are collapsed at first
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
                Ui.launch(context, app.packageName);
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

        String value = prefs.getString(PREF_COLLAPSED, AdapterApp.Section.OTHERS.name());
        for (String name : value.split(","))
            for (AdapterApp.Section section : AdapterApp.Section.values())
                if (section.name().equals(name))
                    collapsed.add(section);
        adapter.setCollapsed(collapsed);

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
            button.setText(getString(R.string.title_filter_count,
                    getString(FILTER_TITLES[filter.ordinal()]), adapter.getCount(filter)));
        }
    }

    private void updateEmpty() {
        if (tvEmpty != null && loaded)
            tvEmpty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    // All available hooks, like Restrict all on the screen of the app, which stops the app when it is stopped automatically;
    // the app had no restrictions (AdapterApp.Row.canRestrictAll), so removing the hooks again undoes it
    private void restrictAll(AdapterApp.Row row) {
        final Activity activity = requireActivity();
        final Context context = activity.getApplicationContext();
        final List<XHook> available = Data.getAvailable(hooks, row.app, collection, null);
        final String label = (TextUtils.isEmpty(row.app.label) ? row.app.packageName : row.app.label);
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
