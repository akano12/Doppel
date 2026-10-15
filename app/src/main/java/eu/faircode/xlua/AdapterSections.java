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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

// Items under headers, like the hooks of each group: strings are headers, other objects items
abstract class AdapterSections<T> extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

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
        return (items.get(position) instanceof String ? TYPE_HEADER : TYPE_ITEM);
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
        return onCreateItemHolder(parent);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (getItemViewType(position) == TYPE_HEADER)
            ((TextView) holder.itemView).setText((String) items.get(position));
        else
            onBindItem(holder, getItem(position));
    }
}
