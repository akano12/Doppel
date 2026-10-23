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
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Items under headers, like the hooks of each group: strings and expandable headers are headers,
// other objects items; the items under a header know whether they are the first or the last one
abstract class AdapterSections<T> extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;
    private static final int TYPE_EXPANDABLE = 2;
    private static final int TYPE_GROUP = 3;

    // A header which shows or hides its items when tapped; the items are left out when collapsed
    static class Expandable {
        final String text;
        final boolean expanded;
        final CharSequence description; // What tapping does, for accessibility
        final Runnable onToggle;

        Expandable(String text, boolean expanded, CharSequence description, Runnable onToggle) {
            this.text = text;
            this.expanded = expanded;
            this.description = description;
            this.onToggle = onToggle;
        }
    }

    // An expandable header with an icon and a line under its title, a row of a block, like the groups of the Hooks tab
    static class Group extends Expandable {
        final String meta;
        final int icon;

        Group(String text, String meta, int icon, boolean expanded, CharSequence description, Runnable onToggle) {
            super(text, expanded, description, onToggle);
            this.meta = meta;
            this.icon = icon;
        }
    }

    // Expandable headers of which one is open at a time, like the groups of the Hooks tab;
    // while searching or filtering all headers are open, unless closed for the current search
    static class Accordion {
        private String open; // null: the first header, empty: none
        private final Set<String> closed = new HashSet<>();
        private final Consumer<String> onOpen; // Remembers the open header

        Accordion(@Nullable String open, @Nullable Consumer<String> onOpen) {
            this.open = open;
            this.onOpen = onOpen;
        }

        // Another search or filter opens all headers again
        void reset() {
            closed.clear();
        }

        private boolean isOpen(String key, String first, boolean filtering) {
            if (filtering)
                return !closed.contains(key);
            return key.equals(open == null ? first : open);
        }

        // Opening a header closes the open one
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

        // The header of a key with the number of items and the items when it is open; first is the key open
        // at first; tapping the header opens or closes it, then the list is shown again
        void add(Context context, List<Object> items, String key, String title, List<?> children,
                 String first, boolean filtering, Runnable show) {
            if (key == null || children.isEmpty())
                return;
            boolean expanded = isOpen(key, first, filtering);
            items.add(new Expandable(context.getString(R.string.title_count, title, children.size()), expanded,
                    context.getString(expanded ? R.string.title_hide_hooks : R.string.title_show_hooks, title),
                    () -> {
                        toggle(key, first, filtering);
                        show.run();
                    }));
            if (expanded)
                items.addAll(children);
        }

        // Like add, as a group row with an icon and a line, like the number of items, under the title
        void addGroup(Context context, List<Object> items, String key, String title, String meta, int icon,
                      List<?> children, String first, boolean filtering, Runnable show) {
            if (key == null || children.isEmpty())
                return;
            boolean expanded = isOpen(key, first, filtering);
            items.add(new Group(title, meta, icon, expanded,
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
        return (item instanceof String || item instanceof Expandable);
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        return (item instanceof String ? TYPE_HEADER
                : item instanceof Group ? TYPE_GROUP
                : item instanceof Expandable ? TYPE_EXPANDABLE : TYPE_ITEM);
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
            return new RecyclerView.ViewHolder(view) {
            };
        }
        if (viewType == TYPE_EXPANDABLE || viewType == TYPE_GROUP) {
            View view = inflater.inflate(viewType == TYPE_GROUP
                    ? R.layout.item_group_header : R.layout.item_header_expandable, parent, false);
            RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(view) {
            };
            view.setOnClickListener(v -> {
                int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION)
                    ((Expandable) items.get(position)).onToggle.run();
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
            bindGroup(holder.itemView, (Group) items.get(position), position);
        else if (type == TYPE_EXPANDABLE) {
            Expandable header = (Expandable) items.get(position);
            ((TextView) holder.itemView.findViewById(R.id.tvHeader)).setText(header.text);
            ((ImageView) holder.itemView.findViewById(R.id.ivExpand)).setRotation(header.expanded ? 180 : 0);
            holder.itemView.setContentDescription(header.text + ", " + header.description);
        } else {
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

    // The group rows form one block with the rows of their items; an open group has the accent colors
    private void bindGroup(View view, Group group, int position) {
        Context context = view.getContext();
        int background = Ui.color(context, com.google.android.material.R.attr.colorSurfaceContainerLowest);
        Ui.join(view, background, 20, position == 0, position == items.size() - 1);
        View divider = view.findViewById(R.id.vDivider);
        divider.setVisibility(position == 0 ? View.GONE : View.VISIBLE);
        divider.setBackgroundColor(Ui.dividerColor(context, background));

        ((TextView) view.findViewById(R.id.tvTitle)).setText(group.text);
        ((TextView) view.findViewById(R.id.tvMeta)).setText(group.meta);
        View flIcon = view.findViewById(R.id.flIcon);
        ImageView ivIcon = view.findViewById(R.id.ivIcon);
        ivIcon.setImageResource(group.icon);
        flIcon.setBackgroundTintList(ColorStateList.valueOf(Ui.color(context, group.expanded
                ? com.google.android.material.R.attr.colorSecondaryContainer
                : com.google.android.material.R.attr.colorSurfaceContainerHigh)));
        ivIcon.setImageTintList(ColorStateList.valueOf(Ui.color(context, group.expanded
                ? com.google.android.material.R.attr.colorOnSecondaryContainer
                : com.google.android.material.R.attr.colorOnSurfaceVariant)));
        ImageView ivExpand = view.findViewById(R.id.ivExpand);
        ivExpand.setRotation(group.expanded ? 180 : 0);
        ivExpand.setImageTintList(ColorStateList.valueOf(Ui.color(context, group.expanded
                ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorOutline)));
        view.setContentDescription(group.text + ", " + group.meta + ", " + group.description);
    }
}
