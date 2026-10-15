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

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

// Apps with a summary of their restrictions
public class AdapterApp extends RecyclerView.Adapter<AdapterApp.ViewHolder> {
    enum Filter {ALL, USER, SYSTEM, RESTRICTED}

    enum Sort {NAME, RECENT}

    interface Listener {
        void onApp(XApp app);

        void onLaunch(XApp app);
    }

    // Restrictions of an app, summarized in the background
    static class Row {
        XApp app;
        int groups; // Groups which can be restricted
        int restricted; // Groups with one or more assigned hooks
        long used = -1; // Last time a hook restricted something
        String usedGroup;
        boolean error;

        static Row summarize(XApp app, List<XHook> hooks, List<String> collection) {
            Row row = new Row();
            row.app = app;

            Set<String> groups = new HashSet<>();
            for (XHook hook : hooks)
                if (hook.isAvailable(app.packageName, collection))
                    groups.add(hook.getGroup());
            row.groups = groups.size();

            Set<String> restricted = new HashSet<>();
            for (XAssignment assignment : app.assignments) {
                String group = assignment.hook.getGroup();
                if (groups.contains(group))
                    restricted.add(group);
                if (assignment.exception != null)
                    row.error = true;
                if (assignment.restricted && assignment.used > row.used) {
                    row.used = assignment.used;
                    row.usedGroup = group;
                }
            }
            row.restricted = restricted.size();
            return row;
        }

        boolean isSame(Row other) {
            return (app.uid == other.app.uid && app.packageName.equals(other.app.packageName));
        }

        boolean hasSameContent(Row other) {
            return (groups == other.groups &&
                    restricted == other.restricted &&
                    used == other.used &&
                    error == other.error &&
                    app.icon == other.app.icon &&
                    Objects.equals(app.label, other.app.label));
        }
    }

    private final Listener listener;
    private final Collator collator;

    private List<Row> all = new ArrayList<>();
    private List<Row> shown = new ArrayList<>();
    private Filter filter = Filter.USER;
    private Sort sort = Sort.NAME;
    private String query = null;

    AdapterApp(Listener listener) {
        this.listener = listener;
        this.collator = Collator.getInstance(Locale.getDefault());
        this.collator.setStrength(Collator.SECONDARY); // Case insensitive, process accents etc
        setHasStableIds(true);
    }

    void set(List<Row> rows) {
        this.all = rows;
        apply();
    }

    void setFilter(Filter filter) {
        this.filter = filter;
        apply();
    }

    void setSort(Sort sort) {
        this.sort = sort;
        apply();
    }

    void setQuery(String query) {
        this.query = query;
        apply();
    }

    List<Row> getShown() {
        return shown;
    }

    private void apply() {
        // Search prefixes: ! restricted, ? not restricted, # system, @ user, or a uid
        String q = (query == null ? "" : query.toLowerCase(Locale.getDefault()).trim());
        boolean restricted = false;
        boolean unrestricted = false;
        boolean system = false;
        boolean user = false;
        while (!q.isEmpty()) {
            char c = q.charAt(0);
            if (c == '!')
                restricted = true;
            else if (c == '?')
                unrestricted = true;
            else if (c == '#')
                system = true;
            else if (c == '@')
                user = true;
            else
                break;
            q = q.substring(1);
        }
        int uid;
        try {
            uid = Integer.parseInt(q);
        } catch (NumberFormatException ignored) {
            uid = -1;
        }

        List<Row> result = new ArrayList<>();
        for (Row row : all) {
            XApp app = row.app;
            boolean isRestricted = !app.assignments.isEmpty();
            if (filter == Filter.USER && app.system)
                continue;
            if (filter == Filter.SYSTEM && !app.system)
                continue;
            if (filter == Filter.RESTRICTED && !isRestricted)
                continue;
            if ((restricted && !isRestricted) || (unrestricted && isRestricted))
                continue;
            if ((system && !app.system) || (user && app.system))
                continue;
            if (!q.isEmpty() &&
                    app.uid != uid &&
                    !app.packageName.toLowerCase(Locale.ROOT).contains(q) &&
                    (app.label == null || !app.label.toLowerCase(Locale.getDefault()).contains(q)))
                continue;
            result.add(row);
        }

        Comparator<Row> byName = (r1, r2) -> collator.compare(
                r1.app.label == null ? r1.app.packageName : r1.app.label,
                r2.app.label == null ? r2.app.packageName : r2.app.label);
        if (sort == Sort.RECENT)
            Collections.sort(result, (r1, r2) -> {
                int c = Long.compare(r2.used, r1.used);
                return (c == 0 ? byName.compare(r1, r2) : c);
            });
        else
            Collections.sort(result, byName);

        final List<Row> prev = shown;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return prev.size();
            }

            @Override
            public int getNewListSize() {
                return result.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return prev.get(oldPosition).isSame(result.get(newPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                return prev.get(oldPosition).hasSameContent(result.get(newPosition));
            }
        });
        shown = result;
        diff.dispatchUpdatesTo(this);
    }

    class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener {
        final ImageView ivIcon;
        final TextView tvLabel;
        final LinearLayout llBadges;
        final TextView tvPackage;
        final LinearProgressIndicator piGroups;
        final TextView tvGroups;
        final TextView tvLast;

        ViewHolder(View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvLabel = itemView.findViewById(R.id.tvLabel);
            llBadges = itemView.findViewById(R.id.llBadges);
            tvPackage = itemView.findViewById(R.id.tvPackage);
            piGroups = itemView.findViewById(R.id.piGroups);
            tvGroups = itemView.findViewById(R.id.tvGroups);
            tvLast = itemView.findViewById(R.id.tvLast);
            itemView.setOnClickListener(this);
            itemView.setOnLongClickListener(this);
        }

        @Override
        public void onClick(View view) {
            int position = getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION)
                listener.onApp(shown.get(position).app);
        }

        // Start the app, like before
        @Override
        public boolean onLongClick(View view) {
            int position = getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION)
                return false;
            listener.onLaunch(shown.get(position).app);
            return true;
        }
    }

    @Override
    public long getItemId(int position) {
        XApp app = shown.get(position).app;
        return ((long) app.packageName.hashCode()) << 32 | app.uid;
    }

    @Override
    public int getItemCount() {
        return shown.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Row row = shown.get(position);
        XApp app = row.app;
        Context context = holder.itemView.getContext();

        Ui.loadIcon(holder.ivIcon, app.packageName, app.icon);
        holder.tvLabel.setText(TextUtils.isEmpty(app.label) ? app.packageName : app.label);
        holder.tvPackage.setText(app.packageName);

        holder.llBadges.removeAllViews();
        if (app.persistent)
            Ui.addBadge(holder.llBadges, context.getString(R.string.badge_reboot),
                    R.attr.colorWarningContainer, R.attr.colorOnWarningContainer);
        if (app.system)
            Ui.addBadge(holder.llBadges, context.getString(R.string.badge_system),
                    com.google.android.material.R.attr.colorSurfaceContainerHigh,
                    com.google.android.material.R.attr.colorOnSurfaceVariant);

        holder.piGroups.setMax(Math.max(row.groups, 1));
        holder.piGroups.setProgressCompat(row.restricted, false);
        holder.piGroups.setIndicatorColor(Ui.color(context, row.restricted > 0
                ? androidx.appcompat.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOutline));
        holder.tvGroups.setText(context.getString(R.string.title_groups_count, row.restricted, row.groups));

        if (row.error) {
            holder.tvLast.setText(R.string.title_hook_error);
            holder.tvLast.setTextColor(Ui.color(context, R.attr.colorWarning));
        } else {
            holder.tvLast.setTextColor(Ui.color(context, com.google.android.material.R.attr.colorOnSurfaceVariant));
            if (row.used > 0)
                holder.tvLast.setText(context.getString(R.string.title_last_used,
                        Groups.getTitle(context, row.usedGroup), Ui.ago(row.used)));
            else if (row.restricted > 0)
                holder.tvLast.setText(R.string.title_not_used);
            else
                holder.tvLast.setText(R.string.title_not_restricted);
        }
    }
}
