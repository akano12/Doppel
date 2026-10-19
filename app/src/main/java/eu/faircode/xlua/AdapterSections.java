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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

// Items under headers, like the hooks of each group: strings and expandable headers are headers,
// other objects items
abstract class AdapterSections<T> extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;
    private static final int TYPE_EXPANDABLE = 2;

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

    private List<Object> items = new ArrayList<>();

    abstract RecyclerView.ViewHolder onCreateItemHolder(@NonNull ViewGroup parent);

    abstract void onBindItem(@NonNull RecyclerView.ViewHolder holder, T item);

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

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        return (item instanceof String ? TYPE_HEADER : item instanceof Expandable ? TYPE_EXPANDABLE : TYPE_ITEM);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_header, parent, false);
            return new RecyclerView.ViewHolder(view) {
            };
        }
        if (viewType == TYPE_EXPANDABLE) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_header_expandable, parent, false);
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
        else if (type == TYPE_EXPANDABLE) {
            Expandable header = (Expandable) items.get(position);
            ((TextView) holder.itemView.findViewById(R.id.tvHeader)).setText(header.text);
            ((ImageView) holder.itemView.findViewById(R.id.ivExpand)).setRotation(header.expanded ? 180 : 0);
            holder.itemView.setContentDescription(header.text + ", " + header.description);
        } else
            onBindItem(holder, getItem(position));
    }
}
