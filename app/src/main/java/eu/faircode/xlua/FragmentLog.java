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

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Paint;
import android.icu.text.RelativeDateTimeFormatter;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.chip.ChipGroup;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// What hooks did: the last restriction of each hook per app, and hooks which failed; of all apps or of one app
public class FragmentLog extends FragmentBase {
    private static final String TAG = "XLua.Log";

    private static final String ARG_PACKAGE = "package";
    private static final String ARG_UID = "uid";
    private static final String ARG_LABEL = "label";

    private enum Filter {ALL, TODAY, ERRORS}

    private static final int[] FILTER_CHIPS = {R.id.chipAll, R.id.chipToday, R.id.chipErrors};
    private static final int[] FILTER_TITLES = {R.string.title_all, R.string.title_today, R.string.title_stat_errors};

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
    private TextView tvCalls;
    private TextView tvCallsLabel;
    private TextView tvStats;
    private BarsView vBars;
    private ViewGroup llAxis;
    private CompactBar compact;
    private BarsView vBarsCompact;
    private Adapter adapter;

    private Filter filter = Filter.ALL;
    private String query = null;
    private String only = null; // The package of the app of the log, null for all apps
    private int onlyUid = -1; // Any user when unknown
    private List<Entry> entries = new ArrayList<>();
    private List<Entry> errors = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>(); // Entries shown in full, see key

    private static String key(Entry entry) {
        return entry.app.uid + "/" + entry.hook;
    }

    // The log of one app, see ActivityLog
    static FragmentLog forApp(String packageName, int uid, String label) {
        Bundle args = new Bundle();
        args.putString(ARG_PACKAGE, packageName);
        args.putInt(ARG_UID, uid);
        args.putString(ARG_LABEL, label);
        FragmentLog fragment = new FragmentLog();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_log, container, false);
        Context context = requireContext();
        Bundle args = getArguments();
        String label = null;
        if (args != null) {
            only = args.getString(ARG_PACKAGE);
            onlyUid = args.getInt(ARG_UID, -1);
            label = args.getString(ARG_LABEL);
        }

        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        pbLoading = view.findViewById(R.id.pbLoading);
        tvEmpty = view.findViewById(R.id.tvEmpty);
        tvCalls = view.findViewById(R.id.tvCalls);
        tvCallsLabel = view.findViewById(R.id.tvCallsLabel);
        tvStats = view.findViewById(R.id.tvStats);
        vBars = view.findViewById(R.id.vBars);
        llAxis = view.findViewById(R.id.llAxis);
        TextView tvTitle = view.findViewById(R.id.tvTitle);
        View tilSearch = view.findViewById(R.id.tilSearch);
        EditText etSearch = view.findViewById(R.id.etSearch);
        ImageButton ibSearch = view.findViewById(R.id.ibSearch);
        search = new TitleSearch(this, tvTitle, tilSearch, etSearch, ibSearch, text -> {
            query = text;
            show();
        });
        // The log of one app is like the Activity tab, of the app: back, the app as the title and its overview
        if (only != null) {
            View ibBack = view.findViewById(R.id.ibBack);
            ibBack.setVisibility(View.VISIBLE);
            ibBack.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
            View llTitle = view.findViewById(R.id.llTitle);
            llTitle.setPaddingRelative(Ui.dp(context, 4), llTitle.getPaddingTop(), llTitle.getPaddingEnd(), 0);
            tvTitle.setText(label);
            etSearch.setHint(R.string.title_search_log_app);
        }

        adapter = new Adapter();
        RecyclerView rvLog = view.findViewById(R.id.rvLog);
        rvLog.setLayoutManager(new LinearLayoutManager(context));
        rvLog.setAdapter(adapter);

        swipeRefresh.setColorSchemeColors(Ui.color(context, androidx.appcompat.R.attr.colorPrimary));
        swipeRefresh.setOnRefreshListener(this::load);

        ChipGroup cgFilter = view.findViewById(R.id.cgFilter);
        Ui.chips(cgFilter, FILTER_CHIPS, Filter.values(), filter, selected -> {
            filter = selected;
            show();
        });
        tvStats.setOnClickListener(v -> cgFilter.check(R.id.chipErrors));

        // While the header is scrolled away: the calls of today by hour as small bars, and the filter
        compact = (only == null
                ? new CompactBar(view, R.string.nav_activity, cgFilter, tilSearch, ibSearch)
                : new CompactBar(view, label, cgFilter, tilSearch, ibSearch));
        if (only != null)
            compact.setBack(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        vBarsCompact = new BarsView(context, null);
        vBarsCompact.setLayoutParams(new FrameLayout.LayoutParams(Ui.dp(context, 64), Ui.dp(context, 24)));
        compact.setTrail(vBarsCompact);

        return view;
    }

    @Override
    protected void load() {
        final Context context = requireContext().getApplicationContext();
        final String only = this.only;
        final int onlyUid = this.onlyUid;
        submit(() -> {
            try {
                Map<String, XApp> apps = new HashMap<>();
                Map<String, String> groups = new HashMap<>(); // By hook id, of the hooks available
                List<Entry> errors = new ArrayList<>();
                // The log of one app needs only that app; restrictions of other apps are left out, like of uninstalled apps
                List<XApp> list = new ArrayList<>();
                if (only == null)
                    list.addAll(Data.getApps(context));
                else {
                    XApp app = Data.getApp(context, only, onlyUid);
                    if (app != null)
                        list.add(app);
                }
                for (XApp app : list) {
                    apps.put(app.packageName + ":" + app.uid, app);
                    for (XAssignment assignment : app.assignments) {
                        groups.put(assignment.id, assignment.group);
                        if (assignment.exception != null) {
                            Entry entry = new Entry();
                            entry.app = app;
                            entry.hook = assignment.id;
                            entry.group = assignment.group;
                            // The time of the last error, see XProvider.report; a restriction can be later
                            entry.time = assignment.installed;
                            entry.exception = assignment.exception;
                            errors.add(entry);
                        }
                    }
                }

                List<Entry> entries = new ArrayList<>();
                try (Cursor cursor = Data.getLog(context)) {
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
                        entry.group = groups.get(entry.hook);
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

    // The entries by hour of the day
    private static int[] byHour(List<Entry> entries) {
        int[] hours = new int[24];
        Calendar calendar = Calendar.getInstance();
        for (Entry entry : entries) {
            calendar.setTimeInMillis(entry.time);
            hours[calendar.get(Calendar.HOUR_OF_DAY)]++;
        }
        return hours;
    }

    // The entries by day of the last week, today last; older ones are left out
    private static int[] byDay(List<Entry> entries, long today) {
        int[] days = new int[7];
        long[] starts = new long[days.length];
        for (int i = 0; i < days.length; i++)
            starts[i] = addDays(today, i + 1 - days.length);
        for (Entry entry : entries)
            for (int i = days.length - 1; i >= 0; i--)
                if (entry.time >= starts[i]) {
                    days[i]++;
                    break;
                }
        return days;
    }

    // The start of a day some days later or earlier, also across a change of the clock
    private static long addDays(long day, int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(day);
        calendar.add(Calendar.DAY_OF_MONTH, days);
        return calendar.getTimeInMillis();
    }

    private void show() {
        Context context = requireContext();
        long today = Ui.startOfDay(System.currentTimeMillis());
        long yesterday = Ui.startOfDay(today - 1);

        // The overview of what the filter and the search show, which the list below shows too: the restrictions of today
        // by hour, or all restrictions or the errors by day of the last week; with the apps of these and all errors
        String q = (query == null ? "" : query.trim());
        List<Entry> counted = new ArrayList<>();
        for (Entry entry : (filter == Filter.ERRORS ? errors : entries))
            if ((filter != Filter.TODAY || entry.time >= today) && matches(context, entry, q))
                counted.add(entry);
        Set<String> packages = new HashSet<>();
        for (Entry entry : counted)
            packages.add(entry.app.packageName);
        boolean byHour = (filter == Filter.TODAY);
        int[] bars = (byHour ? byHour(counted) : byDay(counted, today));
        int count = counted.size();
        // Digits of the language, like Arabic digits
        NumberFormat nf = NumberFormat.getIntegerInstance();
        tvCalls.setText(nf.format(count));
        tvCallsLabel.setText(getResources().getQuantityString(filter == Filter.ERRORS ? R.plurals.title_failed_hooks
                : byHour ? R.plurals.title_calls_today : R.plurals.title_calls_all, count));
        // The log of one app is of that app only
        String errorCount = getResources().getQuantityString(R.plurals.title_stat_errors_count, errors.size(), errors.size());
        tvStats.setText(only != null ? errorCount : getString(R.string.title_pair,
                getResources().getQuantityString(R.plurals.title_stat_apps, packages.size(), packages.size()), errorCount));
        tvStats.setTextColor(Ui.color(context, errors.isEmpty()
                ? com.google.android.material.R.attr.colorOnSurfaceVariant : R.attr.colorWarning));
        vBars.setValues(bars);
        vBars.setContentDescription(getString(filter == Filter.ERRORS ? R.string.title_errors_by_day
                : byHour ? R.string.title_calls_by_hour : R.string.title_calls_by_day));
        // Under the bars: the hours in the digits of the language, or the first and the last day
        for (int i = 0; i < llAxis.getChildCount(); i++) {
            String label = null;
            if (byHour)
                label = nf.format(i * 6);
            else if (i == 0 || i == llAxis.getChildCount() - 1)
                label = (i == 0 ? DateUtils.formatDateTime(context, addDays(today, 1 - bars.length),
                        DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY) : getString(R.string.title_today));
            ((TextView) llAxis.getChildAt(i)).setText(label);
        }
        String summary = getString(R.string.title_pair,
                filter == Filter.ERRORS ? errorCount : getString(R.string.title_pair,
                        getResources().getQuantityString(byHour ? R.plurals.title_compact_calls : R.plurals.title_compact_all,
                                count, count),
                        errorCount),
                getString(FILTER_TITLES[filter.ordinal()]));
        vBarsCompact.setValues(bars);
        compact.setSubtitle(summary);

        // Items are entries under the headers of their days, with the number of entries
        List<Entry> failed = new ArrayList<>();
        for (Entry entry : errors)
            if (matches(context, entry, q))
                failed.add(entry);
        List<Object> items = new ArrayList<>();
        if (filter == Filter.ERRORS || (filter == Filter.ALL && !failed.isEmpty())) {
            if (filter == Filter.ALL)
                items.add(getString(R.string.title_count, getString(R.string.title_stat_errors), failed.size()));
            items.addAll(failed);
        }
        if (filter != Filter.ERRORS) {
            Map<String, List<Entry>> days = new LinkedHashMap<>();
            for (Entry entry : counted) {
                String day;
                if (entry.time >= today)
                    day = getString(R.string.title_today);
                else if (entry.time >= yesterday)
                    day = getString(R.string.title_yesterday);
                else
                    day = DateUtils.formatDateTime(context, entry.time,
                            DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_ALL);
                if (!days.containsKey(day))
                    days.put(day, new ArrayList<>());
                days.get(day).add(entry);
            }
            for (Map.Entry<String, List<Entry>> day : days.entrySet()) {
                items.add(getString(R.string.title_count, day.getKey(), day.getValue().size()));
                items.addAll(day.getValue());
            }
        }
        adapter.set(items);
        // Nothing logged, or nothing which the filter or the search matches
        if (entries.isEmpty() && errors.isEmpty())
            tvEmpty.setText(R.string.title_log_empty);
        else if (filter == Filter.ERRORS && q.isEmpty())
            tvEmpty.setText(R.string.title_log_no_errors);
        else
            tvEmpty.setText(R.string.title_no_matches);
        // Not while loading, like when a search is restored
        tvEmpty.setVisibility(items.isEmpty() && pbLoading.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    // Search by app, like the name or the package name, and by restriction, like the group or the hook
    private static boolean matches(Context context, Entry entry, String q) {
        return (q.isEmpty() ||
                Ui.contains(entry.app.label, q) ||
                Ui.contains(entry.app.packageName, q) ||
                Ui.contains(entry.hook, q) ||
                (entry.group != null &&
                        (Ui.contains(entry.group, q) || Ui.contains(Groups.getTitle(context, entry.group), q))));
    }

    private class Adapter extends AdapterSections<Entry> {
        class EntryHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
            final View vDivider;
            final ImageView ivIcon;
            final TextView tvApp;
            final TextView tvTime;
            final TextView tvWhat;
            final TextView tvOld;
            final TextView tvArrow;
            final TextView tvNew;
            final TextView tvHook;
            final ImageButton ibExpand;
            final View llFull;
            final TextView tvOldFull;
            final TextView tvNewFull;
            boolean expandable; // Errors show in full when tapped

            EntryHolder(View itemView) {
                super(itemView);
                vDivider = itemView.findViewById(R.id.vDivider);
                ivIcon = itemView.findViewById(R.id.ivIcon);
                tvApp = itemView.findViewById(R.id.tvApp);
                tvTime = itemView.findViewById(R.id.tvTime);
                tvWhat = itemView.findViewById(R.id.tvWhat);
                tvOld = itemView.findViewById(R.id.tvOld);
                tvArrow = itemView.findViewById(R.id.tvArrow);
                tvNew = itemView.findViewById(R.id.tvNew);
                tvHook = itemView.findViewById(R.id.tvHook);
                ibExpand = itemView.findViewById(R.id.ibExpand);
                llFull = itemView.findViewById(R.id.llFull);
                tvOldFull = itemView.findViewById(R.id.tvOldFull);
                tvNewFull = itemView.findViewById(R.id.tvNewFull);
                tvOld.setPaintFlags(tvOld.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                tvOldFull.setPaintFlags(tvOldFull.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                itemView.setOnClickListener(this);
                // A text is cut off by its own width or by the others on its line, which only the layout tells;
                // the arrow takes room from the line, so a text which was cut stays cut
                View.OnLayoutChangeListener check = (v, l, t, r, b, ol, ot, or, ob) -> {
                    if (expandable && ibExpand.getVisibility() != View.VISIBLE &&
                            (cut(tvApp) || cut(tvOld) || cut(tvNew) || cut(tvHook)))
                        ibExpand.post(() -> ibExpand.setVisibility(View.VISIBLE));
                };
                for (TextView tv : new TextView[]{tvApp, tvOld, tvNew, tvHook})
                    tv.addOnLayoutChangeListener(check);
                ibExpand.setOnClickListener(view -> {
                    int position = getBindingAdapterPosition();
                    if (position == RecyclerView.NO_POSITION)
                        return;
                    String key = key(getItem(position));
                    if (!expanded.remove(key))
                        expanded.add(key);
                    notifyItemChanged(position);
                });
            }

            // A hidden text can have the layout of the entry shown before
            private boolean cut(TextView tv) {
                android.text.Layout layout = tv.getLayout();
                return (tv.getVisibility() == View.VISIBLE &&
                        layout != null && layout.getLineCount() > 0 && layout.getEllipsisCount(0) > 0);
            }

            @Override
            public void onClick(View view) {
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION)
                    return;
                Entry entry = getItem(position);
                if (entry.exception != null)
                    Ui.showText(view.getContext(), entry.hook, entry.exception.trim());
                else {
                    // The screen of the app with the hook; the log of one app is opened from that screen,
                    // which is shown again instead of another one on top of the log
                    Intent intent = ActivityApp.getIntent(view.getContext(), entry.app.packageName, entry.app.uid, entry.hook);
                    if (only != null)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                }
            }
        }

        @Override
        RecyclerView.ViewHolder onCreateItemHolder(@NonNull ViewGroup parent) {
            return new EntryHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log, parent, false));
        }

        // Like DateUtils.getRelativeDateTimeString, which formats the time without the 24-hour setting
        private String getDayTime(Context context, long time) {
            String day = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(),
                    DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL).toString();
            String clock = DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_TIME);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                return RelativeDateTimeFormatter.getInstance(Locale.getDefault()).combineDateAndTime(day, clock);
            return day + ", " + clock;
        }

        @Override
        void onBindItem(@NonNull RecyclerView.ViewHolder holder, Entry entry, boolean first, boolean last) {
            EntryHolder h = (EntryHolder) holder;
            Context context = h.itemView.getContext();
            boolean error = (entry.exception != null);

            // The rows of a day form one block, failed hooks are on the error color
            int background = Ui.color(context, error
                    ? com.google.android.material.R.attr.colorErrorContainer
                    : com.google.android.material.R.attr.colorSurfaceContainerLowest);
            Ui.joinRow(h.itemView, h.vDivider, background, first, last);
            int meta = Ui.color(context, error
                    ? com.google.android.material.R.attr.colorOnErrorContainer
                    : com.google.android.material.R.attr.colorOnSurfaceVariant);

            Ui.loadIcon(h.ivIcon, entry.app.packageName, entry.app.icon);
            h.tvApp.setText(entry.app.getLabel());
            // Errors are not listed under the day, so they show it, like Yesterday, 09:15
            if (entry.time <= 0)
                h.tvTime.setText(null);
            else if (error)
                h.tvTime.setText(getDayTime(context, entry.time));
            else
                h.tvTime.setText(DateUtils.formatDateTime(context, entry.time, DateUtils.FORMAT_SHOW_TIME));
            h.tvTime.setTextColor(meta);
            // Ids are the collection and the name, like Privacy.TelephonyManager.getImei
            int dot = entry.hook.indexOf('.');
            h.tvHook.setText(dot < 0 ? entry.hook : entry.hook.substring(dot + 1));
            h.tvHook.setTextColor(meta);
            Ui.setStartIcon(h.tvWhat, error ? R.drawable.ic_error : Groups.getIcon(entry.group), 16,
                    Ui.color(context, error ? androidx.appcompat.R.attr.colorError : androidx.appcompat.R.attr.colorPrimary));

            String group = (entry.group == null ? null : Groups.getTitle(context, entry.group));
            if (error) {
                String line = entry.exception.trim().split("\n", 2)[0];
                h.tvWhat.setText(context.getString(R.string.title_hook_failed, line));
                h.tvWhat.setTextColor(meta);
            } else {
                h.tvWhat.setText(group == null ? entry.hook : group);
                h.tvWhat.setTextColor(Ui.color(context, com.google.android.material.R.attr.colorOnSurface));
            }

            // The texts on one line each, cut when they do not fit, or in full when the row is expanded: the app and
            // the hook wrapped, the values under them; the arrow shows once the lines are laid out, see EntryHolder
            boolean values = (!error && (entry.oldValue != null || entry.newValue != null));
            String oldValue = (entry.oldValue == null ? "null" : entry.oldValue);
            String newValue = (entry.newValue == null ? "null" : entry.newValue);
            boolean full = (!error && expanded.contains(key(entry)));
            h.expandable = !error;
            h.tvApp.setMaxLines(full ? Integer.MAX_VALUE : 1);
            h.tvHook.setSingleLine(!full);
            for (View v : new View[]{h.tvOld, h.tvArrow, h.tvNew})
                v.setVisibility(values && !full ? View.VISIBLE : View.GONE);
            h.llFull.setVisibility(values && full ? View.VISIBLE : View.GONE);
            h.ibExpand.setVisibility(full ? View.VISIBLE : View.GONE);
            h.ibExpand.setRotation(full ? 180 : 0);
            h.ibExpand.setContentDescription(context.getString(full ? R.string.title_hide_values : R.string.title_show_values));
            if (values) {
                h.tvOld.setText(oldValue);
                h.tvNew.setText(newValue);
                h.tvOldFull.setText(oldValue);
                h.tvNewFull.setText(newValue);
            }
        }
    }
}
