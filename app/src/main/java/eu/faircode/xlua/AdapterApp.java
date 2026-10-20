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
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// Apps in sections by what needs attention: apps to review, apps which restricted something recently and the others
public class AdapterApp extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    enum Filter {USER, SYSTEM, ALL}

    enum Section {REVIEW, RECENT, OTHERS}

    // Restrictions used this long ago are recent
    private static final long RECENT = 7 * DateUtils.DAY_IN_MILLIS;

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_APP = 1;

    interface Listener {
        void onApp(XApp app);

        void onLaunch(XApp app);

        void onRestrictAll(Row row);

        void onToggle(Section section);
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

        // Failed hooks and user apps without restrictions need a look; system apps are often left unrestricted
        // on purpose, like groups switched off for an app, which the bar of recent apps shows
        Section getSection(long now) {
            if (error || (!app.system && restricted == 0 && groups > 0))
                return Section.REVIEW;
            if (used > 0 && now - used < RECENT)
                return Section.RECENT;
            return Section.OTHERS;
        }

        // Restricting all groups is offered for apps without restrictions, not for Android itself (boot loop risk)
        boolean canRestrictAll() {
            return (!error && restricted == 0 && groups > 0 && !"android".equals(app.packageName));
        }

        boolean isSame(Row other) {
            return (app.uid == other.app.uid && app.packageName.equals(other.app.packageName));
        }

        boolean hasSameContent(Row other) {
            return (groups == other.groups &&
                    restricted == other.restricted &&
                    used == other.used &&
                    error == other.error &&
                    Objects.equals(usedGroup, other.usedGroup) &&
                    app.icon == other.app.icon &&
                    app.system == other.app.system &&
                    app.persistent == other.app.persistent &&
                    Objects.equals(app.label, other.app.label));
        }
    }

    private static class Header {
        final Section section;
        final int count;
        final boolean expanded;

        Header(Section section, int count, boolean expanded) {
            this.section = section;
            this.count = count;
            this.expanded = expanded;
        }
    }

    // An app with its place in its section, which rounds the corners of the first and last app
    private static class Item {
        final Row row;
        final Section section;
        final boolean first;
        final boolean last;

        Item(Row row, Section section, boolean first, boolean last) {
            this.row = row;
            this.section = section;
            this.first = first;
            this.last = last;
        }
    }

    private final Listener listener;
    private final Collator collator;

    private List<Row> all = new ArrayList<>();
    private List<Row> shown = new ArrayList<>();
    private List<Object> items = new ArrayList<>();
    private Filter filter = Filter.USER;
    private String query = null;
    private Set<Section> collapsed = new HashSet<>();

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

    void setQuery(String query) {
        this.query = query;
        apply();
    }

    void setCollapsed(Set<Section> collapsed) {
        this.collapsed = collapsed;
        apply();
    }

    // Apps of the filter and the search, also in collapsed sections
    List<Row> getShown() {
        return shown;
    }

    // Number of apps for a filter, without the search query
    int getCount(Filter filter) {
        int count = 0;
        for (Row row : all)
            if (matches(row, filter))
                count++;
        return count;
    }

    private static boolean matches(Row row, Filter filter) {
        switch (filter) {
            case USER:
                return !row.app.system;
            case SYSTEM:
                return row.app.system;
            default:
                return true;
        }
    }

    private boolean isSearching() {
        return (query != null && !query.trim().isEmpty());
    }

    private void apply() {
        // Search prefixes: ! restricted, ? not restricted, # system, @ user, or a uid
        String q = (query == null ? "" : query.trim());
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
            if (!matches(row, filter))
                continue;
            if ((restricted && !isRestricted) || (unrestricted && isRestricted))
                continue;
            if ((system && !app.system) || (user && app.system))
                continue;
            if (!q.isEmpty() &&
                    app.uid != uid &&
                    !Ui.contains(app.packageName, q) &&
                    !Ui.contains(app.label, q))
                continue;
            result.add(row);
        }
        shown = result;

        // Sections: failures first, then by name; recent by time; others by name
        long now = System.currentTimeMillis();
        Map<Section, List<Row>> sections = new EnumMap<>(Section.class);
        for (Section section : Section.values())
            sections.put(section, new ArrayList<>());
        for (Row row : result)
            sections.get(row.getSection(now)).add(row);

        Comparator<Row> byName = (r1, r2) -> collator.compare(
                r1.app.label == null ? r1.app.packageName : r1.app.label,
                r2.app.label == null ? r2.app.packageName : r2.app.label);
        Collections.sort(sections.get(Section.REVIEW), (r1, r2) -> {
            int c = Boolean.compare(r2.error, r1.error);
            return (c == 0 ? byName.compare(r1, r2) : c);
        });
        Collections.sort(sections.get(Section.RECENT), (r1, r2) -> Long.compare(r2.used, r1.used));
        Collections.sort(sections.get(Section.OTHERS), byName);

        // Searching shows the matches of all sections
        List<Object> next = new ArrayList<>();
        for (Section section : Section.values()) {
            List<Row> rows = sections.get(section);
            if (rows.isEmpty())
                continue;
            boolean expanded = (isSearching() || !collapsed.contains(section));
            next.add(new Header(section, rows.size(), expanded));
            if (expanded)
                for (int i = 0; i < rows.size(); i++)
                    next.add(new Item(rows.get(i), section, i == 0, i == rows.size() - 1));
        }

        final List<Object> prev = items;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return prev.size();
            }

            @Override
            public int getNewListSize() {
                return next.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                Object o = prev.get(oldPosition);
                Object n = next.get(newPosition);
                if (o instanceof Header && n instanceof Header)
                    return ((Header) o).section == ((Header) n).section;
                if (o instanceof Item && n instanceof Item)
                    return ((Item) o).row.isSame(((Item) n).row);
                return false;
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                Object o = prev.get(oldPosition);
                Object n = next.get(newPosition);
                if (o instanceof Header) {
                    Header h1 = (Header) o;
                    Header h2 = (Header) n;
                    return (h1.count == h2.count && h1.expanded == h2.expanded);
                }
                Item i1 = (Item) o;
                Item i2 = (Item) n;
                return (i1.section == i2.section && i1.first == i2.first && i1.last == i2.last &&
                        i1.row.hasSameContent(i2.row));
            }
        });
        items = next;
        diff.dispatchUpdatesTo(this);
    }

    private class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView tvHeader;
        final ImageView ivExpand;

        HeaderHolder(View itemView) {
            super(itemView);
            tvHeader = itemView.findViewById(R.id.tvHeader);
            ivExpand = itemView.findViewById(R.id.ivExpand);
            itemView.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION)
                    listener.onToggle(((Header) items.get(position)).section);
            });
        }
    }

    private class AppHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener {
        final ImageView ivIcon;
        final TextView tvLabel;
        final LinearLayout llBadges;
        final TextView tvTime;
        final TextView tvSummary;
        final SegmentBar sbGroups;
        final Button btnAction;

        AppHolder(View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvLabel = itemView.findViewById(R.id.tvLabel);
            llBadges = itemView.findViewById(R.id.llBadges);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvSummary = itemView.findViewById(R.id.tvSummary);
            sbGroups = itemView.findViewById(R.id.sbGroups);
            btnAction = itemView.findViewById(R.id.btnAction);
            itemView.setOnClickListener(this);
            itemView.setOnLongClickListener(this);
            btnAction.setOnClickListener(view -> {
                Row row = getRow();
                if (row == null)
                    return;
                if (row.canRestrictAll())
                    listener.onRestrictAll(row);
                else
                    listener.onApp(row.app);
            });
        }

        private Row getRow() {
            int position = getBindingAdapterPosition();
            return (position == RecyclerView.NO_POSITION ? null : ((Item) items.get(position)).row);
        }

        @Override
        public void onClick(View view) {
            Row row = getRow();
            if (row != null)
                listener.onApp(row.app);
        }

        // Start the app, like before
        @Override
        public boolean onLongClick(View view) {
            Row row = getRow();
            if (row == null)
                return false;
            listener.onLaunch(row.app);
            return true;
        }
    }

    @Override
    public long getItemId(int position) {
        Object item = items.get(position);
        if (item instanceof Header)
            return -1 - ((Header) item).section.ordinal();
        XApp app = ((Item) item).row.app;
        return ((long) app.packageName.hashCode()) << 32 | app.uid;
    }

    @Override
    public int getItemViewType(int position) {
        return (items.get(position) instanceof Header ? TYPE_HEADER : TYPE_APP);
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
            return new HeaderHolder(inflater.inflate(R.layout.item_header_expandable, parent, false));
        return new AppHolder(inflater.inflate(R.layout.item_app, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        if (item instanceof Header)
            bindHeader((HeaderHolder) holder, (Header) item);
        else
            bindApp((AppHolder) holder, (Item) item);
    }

    private static int getTitle(Section section) {
        switch (section) {
            case REVIEW:
                return R.string.section_apps_review;
            case RECENT:
                return R.string.section_apps_recent;
            default:
                return R.string.section_apps_others;
        }
    }

    private void bindHeader(HeaderHolder h, Header header) {
        Context context = h.itemView.getContext();
        String title = context.getString(getTitle(header.section));
        h.tvHeader.setText(context.getString(R.string.title_filter_count, title, header.count));
        h.tvHeader.setTextColor(Ui.color(context, header.section == Section.REVIEW
                ? com.google.android.material.R.attr.colorTertiary
                : header.section == Section.RECENT
                ? androidx.appcompat.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
        // Turning the arrow when the section is toggled, not when the header is shown
        float rotation = (header.expanded ? 180 : 0);
        if (h.itemView.getTag() == header.section)
            h.ivExpand.animate().rotation(rotation).setDuration(200).start();
        else
            h.ivExpand.setRotation(rotation);
        h.itemView.setTag(header.section);
        h.itemView.setContentDescription(h.tvHeader.getText() + ", " +
                context.getString(header.expanded ? R.string.title_collapse : R.string.title_expand));
    }

    private void bindApp(AppHolder h, Item item) {
        Row row = item.row;
        XApp app = row.app;
        Context context = h.itemView.getContext();

        // The apps of a section form one rounded block, the apps to review are tinted with the third color of the palette, amber by default
        int background = Ui.color(context, com.google.android.material.R.attr.colorSurfaceContainerLow);
        if (item.section == Section.REVIEW)
            background = ColorUtils.blendARGB(background, Ui.color(context, com.google.android.material.R.attr.colorTertiary), 0.16f);
        float radius = Ui.dp(context, 20);
        float top = (item.first ? radius : 0);
        float bottom = (item.last ? radius : 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
        h.itemView.setBackground(shape);

        Ui.loadIcon(h.ivIcon, app.packageName, app.icon);
        h.tvLabel.setText(TextUtils.isEmpty(app.label) ? app.packageName : app.label);

        h.llBadges.removeAllViews();
        if (app.persistent)
            Ui.addBadge(h.llBadges, context.getString(R.string.badge_reboot), Ui.Badge.WARNING);
        if (app.system)
            Ui.addBadge(h.llBadges, context.getString(R.string.badge_system), Ui.Badge.NEUTRAL);

        h.tvTime.setVisibility(View.GONE);
        h.sbGroups.setVisibility(View.GONE);
        h.btnAction.setVisibility(View.GONE);
        int summaryColor = com.google.android.material.R.attr.colorOnSurfaceVariant;
        String summary;
        switch (item.section) {
            case REVIEW:
                if (row.error)
                    summary = context.getString(R.string.title_review_error);
                else if (row.restricted == 0)
                    summary = context.getString(R.string.title_review_not_restricted);
                else
                    summary = context.getString(R.string.title_review_partial, row.restricted, row.groups);
                summaryColor = com.google.android.material.R.attr.colorTertiary;
                h.btnAction.setVisibility(View.VISIBLE);
                h.btnAction.setText(row.canRestrictAll() ? R.string.title_restrict_all_groups : R.string.title_review);
                break;

            case RECENT:
                summary = Groups.getTitle(context, row.usedGroup);
                summaryColor = androidx.appcompat.R.attr.colorPrimary;
                h.tvTime.setVisibility(View.VISIBLE);
                h.tvTime.setText(Ui.ago(row.used));
                h.sbGroups.setVisibility(View.VISIBLE);
                h.sbGroups.set(row.groups, row.restricted);
                h.sbGroups.setContentDescription(
                        context.getString(R.string.title_groups_restricted, row.restricted, row.groups));
                break;

            default:
                if (row.restricted == 0)
                    summary = context.getString(R.string.title_not_restricted);
                else {
                    summary = context.getString(R.string.title_groups_of, row.restricted, row.groups);
                    if (row.used > 0)
                        summary = context.getString(R.string.title_last_used, summary,
                                context.getString(R.string.title_last_used, Groups.getTitle(context, row.usedGroup), Ui.ago(row.used)));
                }
        }
        h.tvSummary.setText(summary);
        h.tvSummary.setTextColor(Ui.color(context, summaryColor));
    }
}
