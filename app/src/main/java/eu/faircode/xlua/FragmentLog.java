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
import android.database.Cursor;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// What hooks did: the last restriction of each hook per app, and hooks which failed
public class FragmentLog extends FragmentBase {
    private static final String TAG = "XLua.Log";

    private enum Filter {ALL, TODAY, ERRORS}

    private static class Entry {
        XApp app;
        String hook;
        String group;
        long time;
        String oldValue;
        String newValue;
        String exception; // An error instead of a restriction
    }

    private SwipeRefreshLayout swipeRefresh;
    private View pbLoading;
    private TextView tvEmpty;
    private TextView tvToday;
    private TextView tvApps;
    private TextView tvErrors;
    private MaterialCardView cvErrors;
    private Adapter adapter;

    private Filter filter = Filter.ALL;
    private List<Entry> entries = new ArrayList<>();
    private List<Entry> errors = new ArrayList<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_log, container, false);

        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        pbLoading = view.findViewById(R.id.pbLoading);
        tvEmpty = view.findViewById(R.id.tvEmpty);
        tvToday = view.findViewById(R.id.tvToday);
        tvApps = view.findViewById(R.id.tvApps);
        tvErrors = view.findViewById(R.id.tvErrors);
        cvErrors = view.findViewById(R.id.cvErrors);

        adapter = new Adapter();
        RecyclerView rvLog = view.findViewById(R.id.rvLog);
        rvLog.setLayoutManager(new LinearLayoutManager(getContext()));
        rvLog.setAdapter(adapter);

        swipeRefresh.setColorSchemeColors(Ui.color(requireContext(), androidx.appcompat.R.attr.colorPrimary));
        swipeRefresh.setOnRefreshListener(this::load);

        ChipGroup cgFilter = view.findViewById(R.id.cgFilter);
        cgFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty())
                return;
            int id = checkedIds.get(0);
            filter = (id == R.id.chipToday ? Filter.TODAY : id == R.id.chipErrors ? Filter.ERRORS : Filter.ALL);
            show();
        });
        cvErrors.setOnClickListener(v -> cgFilter.check(R.id.chipErrors));

        return view;
    }

    @Override
    protected void load() {
        final Context context = requireContext().getApplicationContext();
        executor.submit(() -> {
            try {
                Map<String, XApp> apps = new HashMap<>();
                List<Entry> errors = new ArrayList<>();
                for (XApp app : Data.getApps(context)) {
                    apps.put(app.packageName + ":" + app.uid, app);
                    for (XAssignment assignment : app.assignments)
                        if (assignment.exception != null) {
                            Entry entry = new Entry();
                            entry.app = app;
                            entry.hook = assignment.hook.getId();
                            entry.group = assignment.hook.getGroup();
                            entry.time = Math.max(assignment.used, assignment.installed);
                            entry.exception = assignment.exception;
                            errors.add(entry);
                        }
                }

                List<Entry> entries = new ArrayList<>();
                try (Cursor cursor = context.getContentResolver().query(XProvider.getURI(),
                        new String[]{"xlua.getLog"}, null, null, null)) {
                    while (cursor != null && cursor.moveToNext()) {
                        String pkg = cursor.getString(cursor.getColumnIndexOrThrow("package"));
                        int uid = cursor.getInt(cursor.getColumnIndexOrThrow("uid"));
                        XApp app = apps.get(pkg + ":" + uid);
                        if (app == null)
                            continue; // Uninstalled
                        Entry entry = new Entry();
                        entry.app = app;
                        entry.hook = cursor.getString(cursor.getColumnIndexOrThrow("hook"));
                        entry.time = cursor.getLong(cursor.getColumnIndexOrThrow("used"));
                        entry.oldValue = cursor.getString(cursor.getColumnIndexOrThrow("old"));
                        entry.newValue = cursor.getString(cursor.getColumnIndexOrThrow("new"));
                        for (XAssignment assignment : app.assignments)
                            if (assignment.hook.getId().equals(entry.hook))
                                entry.group = assignment.hook.getGroup();
                        entries.add(entry);
                    }
                }
                Collections.sort(errors, (e1, e2) -> Long.compare(e2.time, e1.time));

                post(() -> {
                    this.entries = entries;
                    this.errors = errors;
                    swipeRefresh.setRefreshing(false);
                    pbLoading.setVisibility(View.GONE);
                    show();
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

    private void show() {
        Context context = requireContext();
        long today = Ui.startOfDay(System.currentTimeMillis());
        long yesterday = Ui.startOfDay(today - 1);

        int countToday = 0;
        Set<String> packages = new HashSet<>();
        for (Entry entry : entries) {
            if (entry.time >= today)
                countToday++;
            packages.add(entry.app.packageName);
        }
        tvToday.setText(Integer.toString(countToday));
        tvApps.setText(Integer.toString(packages.size()));
        tvErrors.setText(Integer.toString(errors.size()));
        cvErrors.setCardBackgroundColor(Ui.color(context, errors.isEmpty()
                ? com.google.android.material.R.attr.colorSurfaceContainerLowest
                : R.attr.colorWarningContainer));

        // Items are entries or day headers
        List<Object> items = new ArrayList<>();
        if (filter == Filter.ERRORS || (filter == Filter.ALL && !errors.isEmpty())) {
            if (filter == Filter.ALL)
                items.add(getString(R.string.title_stat_errors));
            items.addAll(errors);
        }
        if (filter != Filter.ERRORS) {
            String day = null;
            for (Entry entry : entries) {
                if (filter == Filter.TODAY && entry.time < today)
                    continue;
                String header;
                if (entry.time >= today)
                    header = getString(R.string.title_today);
                else if (entry.time >= yesterday)
                    header = getString(R.string.title_yesterday);
                else
                    header = DateUtils.formatDateTime(context, entry.time,
                            DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_ALL);
                if (!header.equals(day)) {
                    items.add(header);
                    day = header;
                }
                items.add(entry);
            }
        }
        adapter.set(items);
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_ENTRY = 1;

        private List<Object> items = new ArrayList<>();

        void set(List<Object> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        class HeaderHolder extends RecyclerView.ViewHolder {
            final TextView tvHeader;

            HeaderHolder(View itemView) {
                super(itemView);
                tvHeader = itemView.findViewById(R.id.tvHeader);
            }
        }

        class EntryHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
            final MaterialCardView card;
            final ImageView ivIcon;
            final TextView tvApp;
            final TextView tvTime;
            final TextView tvWhat;
            final View llValues;
            final TextView tvOld;
            final TextView tvNew;
            final TextView tvHook;

            EntryHolder(View itemView) {
                super(itemView);
                card = (MaterialCardView) itemView;
                ivIcon = itemView.findViewById(R.id.ivIcon);
                tvApp = itemView.findViewById(R.id.tvApp);
                tvTime = itemView.findViewById(R.id.tvTime);
                tvWhat = itemView.findViewById(R.id.tvWhat);
                llValues = itemView.findViewById(R.id.llValues);
                tvOld = itemView.findViewById(R.id.tvOld);
                tvNew = itemView.findViewById(R.id.tvNew);
                tvHook = itemView.findViewById(R.id.tvHook);
                tvOld.setPaintFlags(tvOld.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                itemView.setOnClickListener(this);
            }

            @Override
            public void onClick(View view) {
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION)
                    return;
                Entry entry = (Entry) items.get(position);
                if (entry.exception == null)
                    startActivity(ActivityApp.getIntent(view.getContext(), entry.app.packageName, entry.app.uid));
                else
                    Ui.showText(view.getContext(), entry.hook, entry.exception.trim());
            }
        }

        @Override
        public int getItemViewType(int position) {
            return (items.get(position) instanceof String ? TYPE_HEADER : TYPE_ENTRY);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER)
                return new HeaderHolder(inflater.inflate(R.layout.item_header, parent, false));
            else
                return new EntryHolder(inflater.inflate(R.layout.item_log, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = items.get(position);
            if (holder instanceof HeaderHolder) {
                ((HeaderHolder) holder).tvHeader.setText((String) item);
                return;
            }

            EntryHolder h = (EntryHolder) holder;
            Entry entry = (Entry) item;
            Context context = h.itemView.getContext();
            boolean error = (entry.exception != null);

            Ui.loadIcon(h.ivIcon, entry.app.packageName, entry.app.icon);
            h.tvApp.setText(TextUtils.isEmpty(entry.app.label) ? entry.app.packageName : entry.app.label);
            h.tvTime.setText(entry.time > 0
                    ? DateUtils.formatDateTime(context, entry.time, DateUtils.FORMAT_SHOW_TIME) : "");
            h.tvHook.setText(entry.hook);

            String group = (entry.group == null ? null : Groups.getTitle(context, entry.group));
            if (error) {
                String line = entry.exception.trim().split("\n", 2)[0];
                h.tvWhat.setText(context.getString(R.string.title_hook_failed, line));
                h.tvWhat.setTextColor(Ui.color(context, R.attr.colorOnWarningContainer));
            } else {
                h.tvWhat.setText(group == null ? entry.hook : group);
                h.tvWhat.setTextColor(Ui.color(context, com.google.android.material.R.attr.colorOnSurface));
            }

            boolean values = (!error && (entry.oldValue != null || entry.newValue != null));
            h.llValues.setVisibility(values ? View.VISIBLE : View.GONE);
            if (values) {
                h.tvOld.setText(entry.oldValue == null ? "null" : entry.oldValue);
                h.tvNew.setText(entry.newValue == null ? "null" : entry.newValue);
            }

            h.card.setCardBackgroundColor(Ui.color(context, error
                    ? R.attr.colorWarningContainer
                    : com.google.android.material.R.attr.colorSurfaceContainerLowest));
        }
    }
}
