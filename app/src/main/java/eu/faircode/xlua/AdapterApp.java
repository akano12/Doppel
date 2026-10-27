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

    Copyright 2017-2019 Marcel Bokhorst (M66B)
    Copyright 2026 akano12
 */

package eu.faircode.xlua;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// Apps in sections by what needs attention: apps with a failed hook, new apps without restrictions,
// apps restricted recently or which restricted something recently, and all apps, also the ones above
public class AdapterApp extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    enum Filter {USER, SYSTEM, ALL}

    enum Section {ATTENTION, NEW, RECENT, ALL}

    // The order of all apps, the other sections have their own
    enum Sort {NAME, RECENT, INSTALLED}

    // Apps installed this long ago are new, apps restricted or restricting this long ago are recent
    private static final long INSTALLED = 7 * DateUtils.DAY_IN_MILLIS;
    private static final long RECENT = DateUtils.DAY_IN_MILLIS;

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_APP = 1;

    private long open = RecyclerView.NO_ID; // The row with its actions shown, see Swipe

    interface Listener {
        void onApp(XApp app);

        void onLaunch(XApp app);

        void onRestrictAll(Row row);

        // From the actions behind a row, see Swipe
        void onSetAll(Row row, boolean restrict);

        void onGroups(Row row);

        void onToggle(Section section);
    }

    // Restrictions of an app, summarized in the background
    static class Row {
        XApp app;
        int groups; // Groups which can be restricted
        int restricted; // Groups with one or more assigned hooks
        long used = -1; // Last time a hook restricted something
        String usedGroup;
        long assigned = -1; // Last time a hook was assigned by the user or for a new app
        boolean error;
        boolean partial; // Hooks which are not assigned, also when all groups are restricted

        static Row summarize(XApp app, List<XHook> hooks, List<String> collection) {
            Row row = new Row();
            row.app = app;

            Set<String> groups = new HashSet<>();
            Set<String> available = new HashSet<>();
            for (XHook hook : hooks)
                if (hook.isAvailable(app.packageName, collection)) {
                    groups.add(hook.getGroup());
                    available.add(hook.getId());
                }
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
                row.assigned = Math.max(row.assigned, assignment.assigned);
            }
            row.restricted = restricted.size();
            int assigned = 0;
            for (XAssignment assignment : app.assignments)
                if (available.contains(assignment.hook.getId()))
                    assigned++;
            row.partial = (assigned < available.size());
            return row;
        }

        // Failed hooks need a look, like new apps without restrictions; restricting a new app makes it
        // restricted recently. Apps left unrestricted on purpose are only with all apps after a while.
        // System apps do not count as new, they are installed when the device is set up, like after a reset.
        Section getSection(long now) {
            if (error)
                return Section.ATTENTION;
            if (!app.system && restricted == 0 && groups > 0 && app.installed > 0 && now - app.installed < INSTALLED)
                return Section.NEW;
            long recent = getRecent();
            if (recent > 0 && now - recent < RECENT)
                return Section.RECENT;
            return Section.ALL;
        }

        // The last time the app was restricted or restricted something
        long getRecent() {
            return (restricted == 0 ? used : Math.max(used, assigned));
        }

        // Restrict all changes something while a hook is not assigned, Remove all while one is
        boolean canRestrict() {
            return (restricted < groups || partial);
        }

        boolean canRemove() {
            return (restricted > 0);
        }

        // Restricting all groups is offered for apps without restrictions, not for system server
        boolean canRestrictAll() {
            return (!error && restricted == 0 && groups > 0 && !app.isSystemServer());
        }

        boolean isSame(Row other) {
            return (app.uid == other.app.uid && app.packageName.equals(other.app.packageName));
        }

        boolean hasSameContent(Row other) {
            return (groups == other.groups &&
                    restricted == other.restricted &&
                    partial == other.partial &&
                    used == other.used &&
                    assigned == other.assigned &&
                    error == other.error &&
                    Objects.equals(usedGroup, other.usedGroup) &&
                    app.icon == other.app.icon &&
                    app.system == other.app.system &&
                    app.persistent == other.app.persistent &&
                    app.installed == other.app.installed &&
                    Objects.equals(app.label, other.app.label));
        }
    }

    // The apps of a filter for the overview: how many, how many with restrictions, how many to look at
    // (failed hooks and new apps) and how many restricted recently, see Row.getSection
    static class Summary {
        int total;
        int restricted;
        int review;
        int recent;

        static Summary of(List<Row> rows, Filter filter, long now) {
            Summary summary = new Summary();
            for (Row row : rows)
                if (matches(row, filter)) {
                    summary.total++;
                    if (row.restricted > 0)
                        summary.restricted++;
                    Section section = row.getSection(now);
                    if (section == Section.ATTENTION || section == Section.NEW)
                        summary.review++;
                    else if (section == Section.RECENT)
                        summary.recent++;
                }
            return summary;
        }

        // The share of the apps with restrictions
        int getPercent() {
            return (total == 0 ? 0 : Math.round(100f * restricted / total));
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
    private Sort sort = Sort.NAME;
    private String query = null;
    private Set<Section> collapsed = new HashSet<>();
    private final Set<Section> closed = EnumSet.noneOf(Section.class); // Sections closed for the current search

    AdapterApp(Listener listener) {
        this.listener = listener;
        this.collator = Groups.collator();
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

    Filter getFilter() {
        return filter;
    }

    void setSort(Sort sort) {
        this.sort = sort;
        apply();
    }

    void setQuery(String query) {
        // Another search opens all sections again
        if (!Objects.equals(this.query, query))
            closed.clear();
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

    // The overview of the filter, without the search query
    Summary getSummary() {
        return Summary.of(all, filter, System.currentTimeMillis());
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

    boolean isSearching() {
        return (query != null && !query.trim().isEmpty());
    }

    // The order of all apps: by name, by the last restriction or restricted call (apps without one last),
    // or by the install time, the latest first; equal times by name
    static Comparator<Row> comparator(Sort sort, Collator collator) {
        Comparator<Row> byName = (r1, r2) -> collator.compare(r1.app.getLabel(), r2.app.getLabel());
        switch (sort) {
            case RECENT:
                return (r1, r2) -> {
                    int order = Long.compare(r2.getRecent(), r1.getRecent());
                    return (order == 0 ? byName.compare(r1, r2) : order);
                };
            case INSTALLED:
                return (r1, r2) -> {
                    int order = Long.compare(r2.app.installed, r1.app.installed);
                    return (order == 0 ? byName.compare(r1, r2) : order);
                };
            default:
                return byName;
        }
    }

    private void apply() {
        // The actions shown close when the list changes, like for another filter or search, or after a reload
        open = RecyclerView.NO_ID;
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

        // Sections: failures by name, new apps and recent apps by time, all apps as sorted
        long now = System.currentTimeMillis();
        Map<Section, List<Row>> sections = new EnumMap<>(Section.class);
        for (Section section : Section.values())
            sections.put(section, new ArrayList<>());
        for (Row row : result) {
            Section section = row.getSection(now);
            if (section != Section.ALL)
                sections.get(section).add(row);
            sections.get(Section.ALL).add(row);
        }

        Collections.sort(sections.get(Section.ATTENTION), comparator(Sort.NAME, collator));
        Collections.sort(sections.get(Section.NEW), (r1, r2) -> Long.compare(r2.app.installed, r1.app.installed));
        Collections.sort(sections.get(Section.RECENT), (r1, r2) -> Long.compare(r2.getRecent(), r1.getRecent()));
        Collections.sort(sections.get(Section.ALL), comparator(sort, collator));

        // Searching shows the matches of all sections, unless closed for the search
        List<Object> next = new ArrayList<>();
        for (Section section : Section.values()) {
            List<Row> rows = sections.get(section);
            if (rows.isEmpty())
                continue;
            boolean expanded = !(isSearching() ? closed : collapsed).contains(section);
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
                    return (((Item) o).section == ((Item) n).section && ((Item) o).row.isSame(((Item) n).row));
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
            ViewCompat.setAccessibilityHeading(tvHeader, true);
            ivExpand = itemView.findViewById(R.id.ivExpand);
            itemView.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION)
                    return;
                Section section = ((Header) items.get(position)).section;
                // A section closed while searching is not remembered, like in the Hooks tab
                if (isSearching()) {
                    if (!closed.remove(section))
                        closed.add(section);
                    apply();
                } else
                    listener.onToggle(section);
            });
        }
    }

    private class AppHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener {
        final View llRow;
        final View llActions;
        final View btnRestrictAll;
        final View btnRemoveAll;
        final View vDivider;
        final ImageView ivIcon;
        final TextView tvLabel;
        final LinearLayout llBadges;
        final TextView tvSummary;
        final View flCoverage;
        final TextView tvCoverage;
        final Button btnAction;

        AppHolder(View itemView) {
            super(itemView);
            llRow = itemView.findViewById(R.id.llRow);
            llActions = itemView.findViewById(R.id.llActions);
            btnRestrictAll = itemView.findViewById(R.id.btnRestrictAll);
            btnRemoveAll = itemView.findViewById(R.id.btnRemoveAll);
            vDivider = itemView.findViewById(R.id.vDivider);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvLabel = itemView.findViewById(R.id.tvLabel);
            llBadges = itemView.findViewById(R.id.llBadges);
            tvSummary = itemView.findViewById(R.id.tvSummary);
            flCoverage = itemView.findViewById(R.id.flCoverage);
            tvCoverage = itemView.findViewById(R.id.tvCoverage);
            btnAction = itemView.findViewById(R.id.btnAction);
            // The actions are clipped to the rounded corners of the first and the last row of a section
            itemView.setClipToOutline(true);
            llRow.setOnClickListener(this);
            llRow.setOnLongClickListener(this);
            llRow.setOnTouchListener(new Swipe(this));
            Context context = itemView.getContext();
            // Screen readers tell what a long press does, and offer the actions behind the row
            ViewCompat.replaceAccessibilityAction(llRow, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                    context.getString(R.string.menu_launch), null);
            ViewCompat.addAccessibilityAction(llRow, context.getString(R.string.title_restrict_all_groups), (v, args) -> act(1));
            ViewCompat.addAccessibilityAction(llRow, context.getString(R.string.title_remove_all), (v, args) -> act(0));
            ViewCompat.addAccessibilityAction(llRow, context.getString(R.string.title_groups), (v, args) -> act(2));
            btnRestrictAll.setOnClickListener(view -> act(1));
            btnRemoveAll.setOnClickListener(view -> act(0));
            itemView.findViewById(R.id.btnGroups).setOnClickListener(view -> act(2));
            flCoverage.setOnClickListener(view -> setOpen(getKey() != open));
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

        // The id of the row, also without stable ids
        private long getKey() {
            int position = getBindingAdapterPosition();
            return (position == RecyclerView.NO_POSITION ? RecyclerView.NO_ID : AdapterApp.this.getItemId(position));
        }

        private Row getRow() {
            int position = getBindingAdapterPosition();
            return (position == RecyclerView.NO_POSITION ? null : ((Item) items.get(position)).row);
        }

        // Restrict all (1), remove all (0) or the groups (2); the actions close
        private boolean act(int action) {
            Row row = getRow();
            if (row == null)
                return false;
            setOpen(false);
            if (action == 2)
                listener.onGroups(row);
            else if (action == 1 ? row.canRestrict() : row.canRemove())
                listener.onSetAll(row, action == 1);
            return true;
        }

        // One row at a time shows its actions
        void setOpen(boolean show) {
            long previous = open;
            open = (show ? getKey() : RecyclerView.NO_ID);
            if (previous != RecyclerView.NO_ID && previous != open)
                for (int i = 0; i < items.size(); i++)
                    if (AdapterApp.this.getItemId(i) == previous)
                        notifyItemChanged(i);
            slide(show);
        }

        // The offset of the row which shows the actions, which are at its end: to the left, or the right for RTL
        float shown() {
            return (llRow.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1 : -1) * llActions.getWidth();
        }

        void slide(boolean show) {
            if (show)
                llActions.setVisibility(View.VISIBLE);
            llRow.animate().translationX(show ? shown() : 0).setDuration(180)
                    .withEndAction(() -> {
                        if (!show)
                            llActions.setVisibility(View.INVISIBLE);
                    })
                    .start();
        }

        @Override
        public void onClick(View view) {
            // A tap closes the actions shown, else opens the app
            if (open != RecyclerView.NO_ID) {
                closeOpen();
                return;
            }
            Row row = getRow();
            if (row != null)
                listener.onApp(row.app);
        }

        // Start the app
        @Override
        public boolean onLongClick(View view) {
            Row row = getRow();
            if (row == null)
                return false;
            listener.onLaunch(row.app);
            return true;
        }
    }

    // Closes the actions of the row which shows them, like when the list changes
    void closeOpen() {
        long previous = open;
        open = RecyclerView.NO_ID;
        if (previous != RecyclerView.NO_ID)
            for (int i = 0; i < items.size(); i++)
                if (getItemId(i) == previous)
                    notifyItemChanged(i);
    }

    // Swiping a row to the start shows its actions behind it, swiping it back hides them; a short swipe springs back.
    // Taps and long presses stay those of the row; the list does not scroll while a row is swiped.
    private static class Swipe implements View.OnTouchListener {
        private final AppHolder holder;
        private final int slop;
        private float startX;
        private float startY;
        private float base;
        private boolean swiping;

        Swipe(AppHolder holder) {
            this.holder = holder;
            this.slop = android.view.ViewConfiguration.get(holder.itemView.getContext()).getScaledTouchSlop();
        }

        // Taps and long presses are left to the row (false), which handles them like clicks for accessibility too
        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View view, android.view.MotionEvent event) {
            // The offset with the actions shown, negative to the left, positive to the right for RTL
            float shown = holder.shown();
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    startY = event.getRawY();
                    base = view.getTranslationX();
                    swiping = false;
                    return false;

                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - startX;
                    if (!swiping && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(event.getRawY() - startY)) {
                        swiping = true;
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                        view.cancelLongPress();
                        view.setPressed(false);
                        holder.llActions.setVisibility(View.VISIBLE);
                    }
                    if (swiping) {
                        float x = base + dx;
                        view.setTranslationX(shown < 0 ? Math.max(shown, Math.min(0, x)) : Math.min(shown, Math.max(0, x)));
                        return true;
                    }
                    return false;

                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (!swiping)
                        return false;
                    swiping = false;
                    holder.setOpen(Math.abs(view.getTranslationX()) > Math.abs(shown) / 3);
                    return true;

                default:
                    return swiping;
            }
        }
    }

    @Override
    public long getItemId(int position) {
        return getIdOf(items.get(position));
    }

    private static long getIdOf(Object item) {
        if (item instanceof Header)
            return -1 - ((Header) item).section.ordinal();
        // An app can be in a section and with all apps
        Item i = (Item) item;
        XApp app = i.row.app;
        return (Objects.hash(app.packageName, app.uid) & 0xffffffffL) << 2 | i.section.ordinal();
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
            case ATTENTION:
                return R.string.section_apps_attention;
            case NEW:
                return R.string.section_apps_new;
            case RECENT:
                return R.string.section_apps_recent;
            default:
                return R.string.section_apps_all;
        }
    }

    // The sections to look at have the colors of their rows: failed hooks the error color, new apps the warning color
    private static int getColor(Section section) {
        switch (section) {
            case ATTENTION:
                return androidx.appcompat.R.attr.colorError;
            case NEW:
                return R.attr.colorWarning;
            default:
                return androidx.appcompat.R.attr.colorPrimary;
        }
    }

    private void bindHeader(HeaderHolder h, Header header) {
        Context context = h.itemView.getContext();
        String title = context.getString(getTitle(header.section));
        h.tvHeader.setText(context.getString(R.string.title_count, title, header.count));
        h.tvHeader.setTextColor(Ui.color(context, getColor(header.section)));
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

        // The apps of a section form one rounded block: apps with a failed hook on the error color,
        // new apps on the warning color, the others on the lowest surface, like cards
        int background;
        int meta = com.google.android.material.R.attr.colorOnSurfaceVariant;
        switch (item.section) {
            case ATTENTION:
                background = com.google.android.material.R.attr.colorErrorContainer;
                meta = com.google.android.material.R.attr.colorOnErrorContainer;
                break;
            case NEW:
                background = R.attr.colorWarningContainer;
                meta = R.attr.colorOnWarningContainer;
                break;
            default:
                background = com.google.android.material.R.attr.colorSurfaceContainerLowest;
        }
        int color = Ui.color(context, background);
        Ui.joinRow(h.llRow, h.vDivider, color, item.first, item.last);
        Ui.join(h.itemView, color, 20, item.first, item.last);
        boolean open = (getIdOf(item) == this.open);
        h.llRow.animate().cancel();
        h.llRow.setTranslationX(0);
        h.llActions.setVisibility(open ? View.VISIBLE : View.INVISIBLE);
        // After the layout, when the width of the actions is known, if the holder still shows this row
        if (open)
            h.llActions.post(() -> {
                if (h.getKey() == this.open)
                    h.llRow.setTranslationX(h.shown());
            });
        // An action which would change nothing is dimmed, its colors are fixed
        h.btnRestrictAll.setEnabled(row.canRestrict());
        h.btnRestrictAll.setAlpha(row.canRestrict() ? 1f : 0.38f);
        h.btnRemoveAll.setEnabled(row.canRemove());
        h.btnRemoveAll.setAlpha(row.canRemove() ? 1f : 0.38f);

        Ui.loadIcon(h.ivIcon, app.packageName, app.icon);
        h.tvLabel.setText(app.getLabel());

        h.llBadges.removeAllViews();
        // What the app is, like system; whether changes are waiting for a restart is shown on the screen of the app
        if (app.persistent)
            Ui.addBadge(h.llBadges, context.getString(R.string.badge_persistent), Ui.Badge.NEUTRAL)
                    .setContentDescription(context.getString(R.string.badge_persistent) + ", " +
                            context.getString(R.string.title_apply_reboot));
        if (app.system)
            Ui.addBadge(h.llBadges, context.getString(R.string.title_filter_system), Ui.Badge.NEUTRAL);

        h.flCoverage.setVisibility(View.GONE);
        h.btnAction.setVisibility(View.GONE);
        String summary;
        switch (item.section) {
            case ATTENTION:
            case NEW:
                // A failed hook, or when a new app without restrictions was installed, see Row.getSection
                summary = (row.error
                        ? context.getString(R.string.title_review_error)
                        : context.getString(R.string.msg_installed, Ui.ago(context, app.installed)));
                h.btnAction.setVisibility(View.VISIBLE);
                h.btnAction.setText(row.canRestrictAll() ? R.string.title_restrict_all_groups : R.string.title_review);
                // Screen readers tell which app the button is for
                h.btnAction.setContentDescription(h.btnAction.getText() + ", " + h.tvLabel.getText());
                break;

            case RECENT:
                // What was restricted last, else that the app was restricted, and when
                boolean used = (row.used > 0 && row.used >= row.assigned);
                summary = context.getString(R.string.title_pair,
                        used ? Groups.getTitle(context, row.usedGroup) : context.getString(R.string.title_restrictions_added),
                        Ui.ago(context, row.getRecent()));
                bindCoverage(h.flCoverage, h.tvCoverage, row);
                break;

            default:
                if (row.restricted == 0)
                    summary = context.getString(R.string.title_not_restricted);
                else if (row.used > 0)
                    summary = context.getString(R.string.title_pair,
                            Groups.getTitle(context, row.usedGroup), Ui.ago(context, row.used));
                else
                    summary = context.getString(R.string.title_no_calls);
                bindCoverage(h.flCoverage, h.tvCoverage, row);
        }
        h.tvSummary.setText(summary);
        h.tvSummary.setTextColor(Ui.color(context, meta));
    }

    // How much of the app is restricted: all groups on the secondary color, some on the warning color,
    // none in grey; screen readers get the words
    private static void bindCoverage(View fl, TextView tv, Row row) {
        Context context = tv.getContext();
        Ui.Badge badge;
        if (row.restricted == 0) {
            badge = Ui.Badge.NEUTRAL;
            tv.setText(R.string.title_off);
            tv.setContentDescription(context.getString(R.string.title_not_restricted));
        } else {
            badge = (row.restricted >= row.groups ? Ui.Badge.ACCENT : Ui.Badge.WARNING);
            tv.setText(String.format(Locale.getDefault(), "%d/%d", row.restricted, row.groups));
            tv.setContentDescription(context.getResources().getQuantityString(
                    R.plurals.title_groups_restricted, row.groups, row.restricted, row.groups));
        }
        tv.setBackgroundTintList(Ui.tint(context, badge.background));
        tv.setTextColor(Ui.color(context, badge.text));
        tv.setVisibility(View.VISIBLE);
        // Tapping the badge shows the actions behind the row
        fl.setVisibility(View.VISIBLE);
        fl.setContentDescription(tv.getContentDescription() + ", " + context.getString(R.string.title_show_actions));
    }
}
