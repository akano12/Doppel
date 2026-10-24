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

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Items under headers, like the hooks of each group: strings and group rows are headers,
// other objects items; the items under a header know whether they are the first or the last one
abstract class AdapterSections<T> extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;
    private static final int TYPE_GROUP = 2;

    // A header with an icon and a line under its title, a row of a block, like the groups of the Hooks tab,
    // which shows or hides its items when tapped; the items are left out when collapsed;
    // a word at the end, like Off, tells a state of the whole group
    private static class Group {
        final String text;
        final String meta;
        final String pill;
        final int icon;
        final boolean expanded;
        final CharSequence description; // What tapping does, for accessibility
        final Runnable onToggle;

        Group(String text, String meta, @Nullable String pill, int icon, boolean expanded, CharSequence description, Runnable onToggle) {
            this.text = text;
            this.meta = meta;
            this.pill = pill;
            this.icon = icon;
            this.expanded = expanded;
            this.description = description;
            this.onToggle = onToggle;
        }
    }

    // Group rows of which one is open at a time, like the groups of the Hooks tab;
    // while searching or filtering all groups are open, unless closed for the current search
    static class Accordion {
        private String open; // null: the first group, empty: none
        private final Set<String> closed = new HashSet<>();
        private final Consumer<String> onOpen; // Remembers the open group

        Accordion(@Nullable String open, @Nullable Consumer<String> onOpen) {
            this.open = open;
            this.onOpen = onOpen;
        }

        // Another search or filter opens all groups again
        void reset() {
            closed.clear();
        }

        private boolean isOpen(String key, String first, boolean filtering) {
            if (filtering)
                return !closed.contains(key);
            return key.equals(open == null ? first : open);
        }

        // Opening a group closes the open one
        private void toggle(String key, String first, boolean filtering) {
            if (filtering) {
                if (!closed.remove(key))
                    closed.add(key);
            } else {
                open = (isOpen(key, first, false) ? "" : key);
                if (onOpen != null)
                    onOpen.accept(open);
            }
        }

        // The row of a key, with an icon and a line, like the number of items, under its title, and the items when
        // it is open; first is the key open at first; tapping the row opens or closes it, then the list is shown again
        void addGroup(Context context, List<Object> items, String key, String title, String meta, @Nullable String pill,
                      int icon, List<?> children, String first, boolean filtering, Runnable show) {
            if (key == null || children.isEmpty())
                return;
            boolean expanded = isOpen(key, first, filtering);
            items.add(new Group(title, meta, pill, icon, expanded,
                    context.getString(expanded ? R.string.title_hide_hooks : R.string.title_show_hooks, title),
                    () -> {
                        toggle(key, first, filtering);
                        show.run();
                    }));
            if (expanded)
                items.addAll(children);
        }
    }

    private List<Object> items = new ArrayList<>();

    abstract RecyclerView.ViewHolder onCreateItemHolder(@NonNull ViewGroup parent);

    // First and last: under its header, for the rounded corners of a block of rows
    abstract void onBindItem(@NonNull RecyclerView.ViewHolder holder, T item, boolean first, boolean last);

    // Headers and items change together
    @SuppressLint("NotifyDataSetChanged")
    void set(List<Object> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @SuppressWarnings("unchecked")
    T getItem(int position) {
        return (T) items.get(position);
    }

    private static boolean isHeader(Object item) {
        return (item instanceof String || item instanceof Group);
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        return (item instanceof String ? TYPE_HEADER : item instanceof Group ? TYPE_GROUP : TYPE_ITEM);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            View view = inflater.inflate(R.layout.item_header, parent, false);
            ViewCompat.setAccessibilityHeading(view, true);
            return new RecyclerView.ViewHolder(view) {
            };
        }
        if (viewType == TYPE_GROUP) {
            GroupHolder holder = new GroupHolder(inflater.inflate(R.layout.item_group_header, parent, false));
            holder.itemView.setOnClickListener(v -> {
                int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION)
                    ((Group) items.get(position)).onToggle.run();
            });
            return holder;
        }
        return onCreateItemHolder(parent);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        int type = getItemViewType(position);
        if (type == TYPE_HEADER)
            ((TextView) holder.itemView).setText((String) items.get(position));
        else if (type == TYPE_GROUP)
            bindGroup((GroupHolder) holder, (Group) items.get(position), position);
        else {
            boolean first = (position == 0 || isHeader(items.get(position - 1)));
            boolean last = (position == items.size() - 1 || isHeader(items.get(position + 1)));
            onBindItem(holder, getItem(position), first, last);
        }
    }

    // The rows under a group row sit on an inset panel in the block of the group rows: the outer background
    // continues the block and ends with the last row of the list, the panel has corners of its own
    void joinInset(@NonNull RecyclerView.ViewHolder holder, View panel, View divider, boolean first, boolean last) {
        Context context = holder.itemView.getContext();
        int position = holder.getBindingAdapterPosition();
        Ui.join(holder.itemView, Ui.color(context, com.google.android.material.R.attr.colorSurfaceContainerLowest), 20,
                false, position == items.size() - 1);
        int background = Ui.color(context, com.google.android.material.R.attr.colorSurface);
        Ui.join(panel, background, 14, first, last);
        ((ViewGroup.MarginLayoutParams) panel.getLayoutParams()).bottomMargin = (last ? Ui.dp(context, 10) : 0);
        panel.requestLayout();
        divider.setVisibility(first ? View.GONE : View.VISIBLE);
        divider.setBackgroundColor(Ui.dividerColor(context, background));
    }

    private static class GroupHolder extends RecyclerView.ViewHolder {
        final View vDivider;
        final View flIcon;
        final ImageView ivIcon;
        final TextView tvTitle;
        final TextView tvMeta;
        final TextView tvPill;
        final ImageView ivExpand;

        GroupHolder(View itemView) {
            super(itemView);
            vDivider = itemView.findViewById(R.id.vDivider);
            flIcon = itemView.findViewById(R.id.flIcon);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            tvMeta = itemView.findViewById(R.id.tvMeta);
            tvPill = itemView.findViewById(R.id.tvPill);
            ivExpand = itemView.findViewById(R.id.ivExpand);
        }
    }

    // The group rows form one block with the rows of their items; an open group has the accent colors
    private void bindGroup(GroupHolder h, Group group, int position) {
        Context context = h.itemView.getContext();
        int background = Ui.color(context, com.google.android.material.R.attr.colorSurfaceContainerLowest);
        Ui.joinRow(h.itemView, h.vDivider, background, position == 0, position == items.size() - 1);

        h.tvTitle.setText(group.text);
        h.tvMeta.setText(group.meta);
        h.tvPill.setVisibility(group.pill == null ? View.GONE : View.VISIBLE);
        h.tvPill.setText(group.pill);
        h.ivIcon.setImageResource(group.icon);
        h.flIcon.setBackgroundTintList(Ui.tint(context, group.expanded
                ? com.google.android.material.R.attr.colorSecondaryContainer
                : com.google.android.material.R.attr.colorSurfaceContainerHigh));
        h.ivIcon.setImageTintList(Ui.tint(context, group.expanded
                ? com.google.android.material.R.attr.colorOnSecondaryContainer
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
        h.ivExpand.setRotation(group.expanded ? 180 : 0);
        h.ivExpand.setImageTintList(Ui.tint(context, group.expanded
                ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorOutline));
        h.itemView.setContentDescription(group.text + ", " + group.meta + ", " + group.description);
    }
}
