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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// Community hook definitions of the repository (repo/), packaged with the app
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
        String className;
        String methodName;
        String[] parameterTypes;

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
    private final Set<Integer> expanded = new HashSet<>(); // Entries showing their full description
    private String open = null; // The collection which shows its definitions; null: the first one, empty: none
    private final Set<String> closed = new HashSet<>(); // Collections collapsed while searching or filtering

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
            closed.clear();
            show();
        });

        ChipGroup cgFilter = findViewById(R.id.cgFilter);
        Ui.chips(cgFilter, FILTER_CHIPS, Filter.values(), filter, selected -> {
            filter = selected;
            closed.clear();
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

                    // The hooked member, to find definitions hooking the same one
                    JSONObject jhook = new JSONObject(readAsset(context, REPO + "hook_" + entry.id + ".json"));
                    entry.className = jhook.getString("className");
                    entry.methodName = jhook.optString("methodName", null);
                    JSONArray jparams = jhook.getJSONArray("parameterTypes");
                    entry.parameterTypes = new String[jparams.length()];
                    for (int p = 0; p < jparams.length(); p++)
                        entry.parameterTypes[p] = jparams.getString(p);

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

    // The installed definitions can be removed where they are listed by themselves
    private boolean canRemove(Entry entry) {
        return (filter == Filter.INSTALLED && getState(entry) == State.INSTALLED);
    }

    // Other active definitions hooking the same member: both run, one after the other,
    // and can undo or break the changes of the other
    private List<XHook> getOverlaps(Entry entry) {
        List<XHook> overlaps = new ArrayList<>();
        for (XHook hook : installed.values())
            if (!hook.getId().equals(entry.getId()) &&
                    hook.isEnabled() && collection.contains(hook.getCollection()) &&
                    hook.getClassName().equals(entry.className) &&
                    Objects.equals(hook.getMethodName(), entry.methodName) &&
                    Arrays.equals(hook.getParameterTypes(), entry.parameterTypes))
                overlaps.add(hook);
        Collections.sort(overlaps, (h1, h2) -> h1.getId().compareToIgnoreCase(h2.getId()));
        return overlaps;
    }

    // Installing replaces a built-in definition with the same name and adds to definitions hooking the same member;
    // apps restricting the group of the definition can get it too, like for built-in definitions added by updates
    private void confirmInstall(Entry entry) {
        if (getState(entry) != State.AVAILABLE) {
            install(entry, false);
            return;
        }

        StringBuilder sb = new StringBuilder();
        if (builtinIds.contains(entry.getId()))
            sb.append(getString(R.string.msg_install_replaces, entry.getId()));
        List<XHook> overlaps = getOverlaps(entry);
        if (!overlaps.isEmpty()) {
            if (sb.length() > 0)
                sb.append("\n\n");
            sb.append(getString(R.string.msg_install_overlaps));
            for (XHook hook : overlaps)
                sb.append("\n• ").append(hook.getId()).append(" (").append(Groups.getTitle(this, hook.getGroup())).append(')');
        }

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_install, null, false);
        TextView tvMessage = view.findViewById(R.id.tvMessage);
        tvMessage.setText(sb);
        tvMessage.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
        CheckBox cbRestrict = view.findViewById(R.id.cbRestrict);
        cbRestrict.setText(getString(R.string.title_install_restrict, Groups.getTitle(this, entry.group)));

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.title_install_name, entry.name))
                .setView(view)
                .setPositiveButton(R.string.title_install, (dialog, which) -> install(entry, cbRestrict.isChecked()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void show() {
        String q = (query == null ? "" : query.trim());
        List<Object> items = new ArrayList<>();
        String collection = null;
        List<Entry> inCollection = new ArrayList<>();
        for (Entry entry : all) {
            State state = getState(entry);
            if (filter == Filter.AVAILABLE && state != State.AVAILABLE)
                continue;
            if (filter == Filter.INSTALLED && state == State.AVAILABLE)
                continue;
            if (filter == Filter.UPDATES && state != State.UPDATE)
                continue;
            if (!q.isEmpty() &&
                    !Ui.contains(entry.getId(), q) &&
                    !Ui.contains(entry.group, q) &&
                    !Ui.contains(Groups.getTitle(this, entry.group), q) &&
                    !Ui.contains(entry.author, q) &&
                    !Ui.contains(entry.description, q))
                continue;
            if (!entry.collection.equals(collection)) {
                addCollection(items, collection, inCollection);
                collection = entry.collection;
                inCollection = new ArrayList<>();
            }
            inCollection.add(entry);
        }
        addCollection(items, collection, inCollection);
        adapter.set(items);
    }

    // At first the first collection is open
    private String getOpen() {
        return (open == null && !all.isEmpty() ? all.get(0).collection : open);
    }

    // Searching or filtering shows all collections with matches
    private boolean isFiltering() {
        return (filter != Filter.ALL || (query != null && !query.trim().isEmpty()));
    }

    // One collection is open at a time, like the groups of the Hooks tab; while searching or filtering
    // all collections are open unless closed for the current search
    private void addCollection(List<Object> items, String collection, List<Entry> entries) {
        if (collection == null || entries.isEmpty())
            return;
        boolean expand = (isFiltering() ? !closed.contains(collection) : collection.equals(getOpen()));
        items.add(new AdapterSections.Expandable(
                getString(R.string.title_group_header_custom, collection, entries.size()), expand,
                getString(expand ? R.string.title_hide_hooks : R.string.title_show_hooks, collection),
                () -> toggle(collection)));
        if (expand)
            items.addAll(entries);
    }

    private void toggle(String collection) {
        if (isFiltering()) {
            if (!closed.remove(collection))
                closed.add(collection);
        } else
            // Opening a collection closes the open one
            open = (collection.equals(getOpen()) ? "" : collection);
        show();
    }

    private void install(Entry entry, boolean restrict) {
        final Context context = getApplicationContext();
        final boolean enabled = collection.contains(entry.collection);
        run(TAG, () -> {
            JSONObject jhook = new JSONObject(readAsset(context, REPO + "hook_" + entry.id + ".json"));
            XHook hook = Data.putCustom(context, jhook);
            int apps = (restrict
                    ? Data.assignRestricting(context, new ArrayList<>(Collections.singletonList(hook.getId())))
                    : 0);
            runOnUiThread(() -> {
                installed.put(hook.getId(), hook);
                show();
            });

            // Hooks of disabled collections are not applied, also not when they are assigned already
            if (!enabled)
                Ui.message(this, getString(R.string.msg_installed_collection, entry.name, entry.collection),
                        getString(R.string.title_enable), () -> enableCollection(entry.collection));
            else if (apps > 0)
                Ui.message(this, getResources().getQuantityString(R.plurals.msg_installed_restricted, apps, entry.name, apps));
            else
                Ui.message(this, getString(R.string.msg_installed, entry.name));
        });
    }

    // Deleting an installed definition restores the built-in definition it replaced
    private void confirmRemove(Entry entry) {
        String message = getString(builtinIds.contains(entry.getId())
                ? R.string.msg_remove_restores : R.string.msg_remove_sure, entry.getId());
        Ui.areYouSure(this, message, () -> {
            final Context context = getApplicationContext();
            run(TAG, () -> {
                Data.putHook(context, entry.getId(), null);
                Map<String, XHook> installed = new HashMap<>();
                for (XHook hook : Data.getAllHooks(context, false))
                    installed.put(hook.getId(), hook);
                runOnUiThread(() -> {
                    this.installed = installed;
                    show();
                });
                Ui.message(this, getString(R.string.msg_removed, entry.name));
            });
        });
    }

    private void enableCollection(String name) {
        final Context context = getApplicationContext();
        final List<String> enabled = new ArrayList<>(collection);
        if (!enabled.contains(name))
            enabled.add(name);
        run(TAG, () -> {
            Data.putCollection(context, enabled);
            runOnUiThread(() -> {
                collection = enabled;
                show();
            });
            Ui.message(this, getString(R.string.msg_collection_enabled, name));
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
                if (view == btnInstall) {
                    if (canRemove(entry))
                        confirmRemove(entry);
                    else
                        confirmInstall(entry);
                }
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
            h.tvGroup.setText(Groups.getTitle(context, entry.group));
            h.tvMeta.setText(getString(R.string.title_download_meta, entry.author, entry.version,
                    DateUtils.formatDateTime(context, entry.updated, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR),
                    getResources().getQuantityString(R.plurals.title_downloads_count, entry.downloads, entry.downloads)));

            boolean hasDescription = !TextUtils.isEmpty(entry.description);
            h.tvDescription.setVisibility(hasDescription ? View.VISIBLE : View.GONE);
            h.tvDescription.setText(hasDescription ? entry.description.trim() : null);
            h.tvDescription.setMaxLines(expanded.contains(entry.id) ? Integer.MAX_VALUE : 3);

            h.llBadges.removeAllViews();
            if (builtinIds.contains(entry.getId()))
                Ui.addBadge(h.llBadges, getString(R.string.title_replaces_builtin), Ui.Badge.WARNING);
            int overlaps = getOverlaps(entry).size();
            if (overlaps > 0)
                Ui.addBadge(h.llBadges, getResources().getQuantityString(R.plurals.title_overlaps, overlaps, overlaps),
                        Ui.Badge.WARNING);
            if (!collection.contains(entry.collection))
                Ui.addBadge(h.llBadges, getString(R.string.title_hook_collection_off, entry.collection), Ui.Badge.NEUTRAL);

            boolean remove = canRemove(entry);
            h.btnInstall.setEnabled(state != State.INSTALLED || remove);
            h.btnInstall.setText(remove ? R.string.title_remove
                    : state == State.UPDATE ? R.string.title_update
                    : state == State.INSTALLED ? R.string.title_installed
                    : R.string.title_install);
            // Screen readers tell which definition the button is for
            h.btnInstall.setContentDescription(h.btnInstall.getText() + ", " + entry.name);
        }
    }
}
