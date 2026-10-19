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
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Community hook definitions of the repository (repo/), packaged with the app.
// The online repository of the pro companion app is not available anymore.
public class ActivityDownloads extends ActivityBase {
    private static final String TAG = "XLua.Downloads";

    private static final String REPO = "repo/";

    private enum Filter {ALL, AVAILABLE, INSTALLED, UPDATES}

    private static final int[] FILTER_CHIPS = {R.id.chipAll, R.id.chipAvailable, R.id.chipInstalled, R.id.chipUpdates};

    private enum State {AVAILABLE, INSTALLED, UPDATE}

    private static class Entry {
        int id;
        String collection;
        String group;
        String name;
        String author;
        int version;
        String description;
        long updated;
        int downloads;

        String getId() {
            return collection + "." + name;
        }
    }

    private View pbLoading;
    private Adapter adapter;

    private Filter filter = Filter.ALL;
    private String query = null;
    private List<Entry> all = new ArrayList<>();
    private Map<String, XHook> installed = new HashMap<>();
    private Set<String> builtinIds = new HashSet<>();
    private List<String> collection = new ArrayList<>();
    private final Set<Integer> expanded = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);
        setupToolbar(getString(R.string.menu_downloads));

        pbLoading = findViewById(R.id.pbLoading);
        adapter = new Adapter();
        RecyclerView rvDownloads = findViewById(R.id.rvDownloads);
        rvDownloads.setLayoutManager(new LinearLayoutManager(this));
        rvDownloads.setAdapter(new ConcatAdapter(new NoticeAdapter(), adapter));

        EditText etSearch = findViewById(R.id.etSearch);
        Ui.onTextChanged(etSearch, text -> {
            query = text;
            show();
        });

        ChipGroup cgFilter = findViewById(R.id.cgFilter);
        Ui.chips(cgFilter, FILTER_CHIPS, Filter.values(), filter, selected -> {
            filter = selected;
            show();
        });

        load();
    }

    private void load() {
        final Context context = getApplicationContext();
        executor.submit(() -> {
            try {
                List<Entry> entries = new ArrayList<>();
                JSONArray jlist = new JSONObject(readAsset(context, REPO + "list.json")).getJSONArray("list");
                for (int i = 0; i < jlist.length(); i++) {
                    JSONObject jentry = jlist.getJSONObject(i);
                    Entry entry = new Entry();
                    entry.id = jentry.getInt("id");
                    entry.collection = jentry.getString("collection");
                    entry.group = jentry.getString("group");
                    entry.name = jentry.getString("name");
                    entry.author = jentry.optString("author");
                    entry.version = jentry.optInt("version");
                    entry.description = jentry.optString("description", null);
                    entry.updated = jentry.optLong("updated") * 1000L;
                    entry.downloads = jentry.optInt("downloads");
                    entries.add(entry);
                }
                Collections.sort(entries, (e1, e2) -> {
                    int c = e1.collection.compareToIgnoreCase(e2.collection);
                    return (c == 0 ? e1.name.compareToIgnoreCase(e2.name) : c);
                });

                Map<String, XHook> installed = new HashMap<>();
                for (XHook hook : Data.getAllHooks(context, false))
                    installed.put(hook.getId(), hook);
                Set<String> builtinIds = Data.getBuiltinIds(context);
                List<String> collection = Data.getCollection(context);

                runOnUiThread(() -> {
                    this.all = entries;
                    this.installed = installed;
                    this.builtinIds = builtinIds;
                    this.collection = collection;
                    pbLoading.setVisibility(View.GONE);
                    show();
                });
            } catch (Throwable ex) {
                error(TAG, ex);
            }
        });
    }

    private static String readAsset(Context context, String name) throws Exception {
        try (InputStream is = context.getAssets().open(name)) {
            return Util.readText(is);
        }
    }

    // Built-in definitions do not count as installed, installing replaces them
    private State getState(Entry entry) {
        XHook hook = installed.get(entry.getId());
        if (hook == null || hook.isBuiltin())
            return State.AVAILABLE;
        return (hook.getVersion() < entry.version ? State.UPDATE : State.INSTALLED);
    }

    private void show() {
        String q = (query == null ? "" : query.toLowerCase(Locale.getDefault()).trim());
        List<Object> items = new ArrayList<>();
        String collection = null;
        for (Entry entry : all) {
            State state = getState(entry);
            if (filter == Filter.AVAILABLE && state != State.AVAILABLE)
                continue;
            if (filter == Filter.INSTALLED && state == State.AVAILABLE)
                continue;
            if (filter == Filter.UPDATES && state != State.UPDATE)
                continue;
            if (!q.isEmpty() &&
                    !entry.getId().toLowerCase(Locale.ROOT).contains(q) &&
                    !entry.group.toLowerCase(Locale.getDefault()).contains(q) &&
                    !entry.author.toLowerCase(Locale.getDefault()).contains(q) &&
                    (entry.description == null || !entry.description.toLowerCase(Locale.getDefault()).contains(q)))
                continue;
            if (!entry.collection.equals(collection)) {
                collection = entry.collection;
                items.add(collection);
            }
            items.add(entry);
        }
        adapter.set(items);
    }

    private void install(Entry entry) {
        final Context context = getApplicationContext();
        executor.submit(() -> {
            try {
                JSONObject jhook = new JSONObject(readAsset(context, REPO + "hook_" + entry.id + ".json"));
                XHook hook = Data.putCustom(context, jhook);
                runOnUiThread(() -> {
                    installed.put(hook.getId(), hook);
                    show();
                });

                // Hooks of disabled collections are not applied
                if (collection.contains(entry.collection))
                    Ui.message(this, getString(R.string.msg_installed, entry.name));
                else
                    Ui.message(this, getString(R.string.msg_installed_collection, entry.name, entry.collection),
                            getString(R.string.title_enable), () -> enableCollection(entry.collection));
            } catch (Throwable ex) {
                error(TAG, ex);
            }
        });
    }

    private void enableCollection(String name) {
        final Context context = getApplicationContext();
        final List<String> enabled = new ArrayList<>(collection);
        if (!enabled.contains(name))
            enabled.add(name);
        executor.submit(() -> {
            try {
                Data.putCollection(context, enabled);
                runOnUiThread(() -> {
                    collection = enabled;
                    show();
                });
                Ui.message(this, getString(R.string.msg_collection_enabled, name));
            } catch (Throwable ex) {
                error(TAG, ex);
            }
        });
    }

    // The use at own risk notice, above the definitions
    private static class NoticeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_download_notice, parent, false);
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        }

        @Override
        public int getItemCount() {
            return 1;
        }
    }

    private class Adapter extends AdapterSections<Entry> {
        class EntryHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
            final TextView tvName;
            final TextView tvGroup;
            final TextView tvMeta;
            final TextView tvDescription;
            final LinearLayout llBadges;
            final Button btnInstall;

            EntryHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvName);
                tvGroup = itemView.findViewById(R.id.tvGroup);
                tvMeta = itemView.findViewById(R.id.tvMeta);
                tvDescription = itemView.findViewById(R.id.tvDescription);
                llBadges = itemView.findViewById(R.id.llBadges);
                btnInstall = itemView.findViewById(R.id.btnInstall);
                itemView.setOnClickListener(this);
                btnInstall.setOnClickListener(this);
            }

            @Override
            public void onClick(View view) {
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION)
                    return;
                Entry entry = getItem(position);
                if (view == btnInstall)
                    install(entry);
                else {
                    // Show or hide the full description
                    if (!expanded.remove(entry.id))
                        expanded.add(entry.id);
                    notifyItemChanged(position);
                }
            }
        }

        @Override
        RecyclerView.ViewHolder onCreateItemHolder(@NonNull ViewGroup parent) {
            return new EntryHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_download, parent, false));
        }

        @Override
        void onBindItem(@NonNull RecyclerView.ViewHolder holder, Entry entry) {
            EntryHolder h = (EntryHolder) holder;
            Context context = h.itemView.getContext();
            State state = getState(entry);

            h.tvName.setText(entry.name);
            h.tvGroup.setText(entry.group);
            h.tvMeta.setText(getString(R.string.title_download_meta, entry.author, entry.version,
                    DateUtils.formatDateTime(context, entry.updated, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR),
                    getResources().getQuantityString(R.plurals.title_downloads_count, entry.downloads, entry.downloads)));

            boolean hasDescription = !TextUtils.isEmpty(entry.description);
            h.tvDescription.setVisibility(hasDescription ? View.VISIBLE : View.GONE);
            h.tvDescription.setText(hasDescription ? entry.description.trim() : null);
            h.tvDescription.setMaxLines(expanded.contains(entry.id) ? Integer.MAX_VALUE : 3);

            h.llBadges.removeAllViews();
            if (builtinIds.contains(entry.getId()))
                Ui.addBadge(h.llBadges, getString(R.string.title_replaces_builtin),
                        R.attr.colorWarningContainer, R.attr.colorOnWarningContainer);
            if (!collection.contains(entry.collection))
                Ui.addBadge(h.llBadges, getString(R.string.title_hook_collection_off, entry.collection),
                        com.google.android.material.R.attr.colorSurfaceContainerHigh,
                        com.google.android.material.R.attr.colorOnSurfaceVariant);

            h.btnInstall.setEnabled(state != State.INSTALLED);
            h.btnInstall.setText(state == State.UPDATE ? R.string.title_update
                    : state == State.INSTALLED ? R.string.title_installed
                    : R.string.title_install);
        }
    }
}
