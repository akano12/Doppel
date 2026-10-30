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
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONException;
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
import java.io.IOException;

// Community hook definitions of the repository (repo/), packaged with the app
public class ActivityDownloads extends ActivityBase {
    private static final String TAG = "XLua.Downloads";

    private static final String REPO = "repo/";

    private enum Filter {ALL, AVAILABLE, INSTALLED, UPDATES}

    private static final int[] FILTER_CHIPS = {R.id.chipAll, R.id.chipAvailable, R.id.chipInstalled, R.id.chipUpdates};
    private static final int[] FILTER_TITLES = {
            R.string.title_all, R.string.title_not_installed, R.string.title_installed, R.string.title_updates};

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
        String className;
        String methodName;
        String[] parameterTypes;

        String getId() {
            return XHook.id(collection, name);
        }
    }

    private TextView tvSummary;
    private TitleSearch search;
    private ChipGroup cgFilter;
    private CompactBar compact;
    private View pbLoading;
    private HeaderAdapter updatesAdapter;
    private HeaderAdapter emptyAdapter;
    private Adapter adapter;

    private Filter filter = Filter.ALL;
    private String query = null;
    private List<Entry> all = new ArrayList<>();
    private Map<String, XHook> installed = new HashMap<>();
    private Set<String> builtinIds = new HashSet<>();
    private List<String> collection = new ArrayList<>();
    private boolean loaded = false; // The chips count once the repository is read, not before
    private int updates = 0; // Installed definitions with a newer version in the repository
    private int emptyText = 0; // String resource telling why the list is empty, none when it is not
    private final Set<Integer> expanded = new HashSet<>(); // Entries showing their full description
    private final AdapterSections.Accordion accordion = new AdapterSections.Accordion(null, null);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);

        findViewById(R.id.ibBack).setOnClickListener(view -> getOnBackPressedDispatcher().onBackPressed());
        tvSummary = findViewById(R.id.tvSummary);
        search = new TitleSearch(this, findViewById(R.id.llTitle), findViewById(R.id.tilSearch),
                findViewById(R.id.etSearch), findViewById(R.id.ibSearch), text -> {
                    query = text;
                    accordion.reset();
                    show();
                });
        search.attach();

        pbLoading = findViewById(R.id.pbLoading);
        updatesAdapter = new HeaderAdapter(R.layout.item_download_updates, false, view -> {
            ((TextView) view.findViewById(R.id.tvUpdates)).setText(
                    getResources().getQuantityString(R.plurals.title_update_count, updates, updates));
            view.findViewById(R.id.btnUpdateAll).setOnClickListener(v -> confirmUpdateAll());
        });
        emptyAdapter = new HeaderAdapter(R.layout.item_downloads_empty, false, view -> ((TextView) view).setText(emptyText));
        adapter = new Adapter();
        RecyclerView rvDownloads = findViewById(R.id.rvDownloads);
        rvDownloads.setLayoutManager(new LinearLayoutManager(this));
        // What the list is empty for goes below the notice and the updates: over the list it would cover them on a short screen
        rvDownloads.setAdapter(new ConcatAdapter(
                new HeaderAdapter(R.layout.item_download_notice, true, null), updatesAdapter, emptyAdapter, adapter));

        cgFilter = findViewById(R.id.cgFilter);
        Ui.chips(cgFilter, FILTER_CHIPS, Filter.values(), filter, selected -> {
            filter = selected;
            accordion.reset();
            show();
        });

        // While the header is scrolled away: back, the definitions with the filter, the filter and the search
        compact = new CompactBar(findViewById(android.R.id.content), R.string.title_downloads, cgFilter,
                findViewById(R.id.tilSearch), findViewById(R.id.ibSearch));
        compact.setBack(view -> getOnBackPressedDispatcher().onBackPressed());

        load();
    }

    // A search restored with the views is shown again
    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        search.restore();
    }

    // The editor can change or delete an installed definition
    @Override
    protected void onResume() {
        super.onResume();
        if (!loaded)
            return;
        final Context context = getApplicationContext();
        run(TAG, () -> showInstalled(context));
    }

    // An error offers a retry, since the screen shows nothing without the list
    private void load() {
        final Context context = getApplicationContext();
        run(TAG, () -> {
            // The list has the time of the update, the definition files, as installed, the rest
            List<Entry> entries = new ArrayList<>();
            JSONArray jlist = new JSONObject(readAsset(context, REPO + "list.json")).getJSONArray("list");
            for (int i = 0; i < jlist.length(); i++) {
                JSONObject jentry = jlist.getJSONObject(i);
                Entry entry = new Entry();
                entry.id = jentry.getInt("id");
                entry.updated = jentry.optLong("updated") * 1000L;

                JSONObject jhook = readDefinition(context, entry);
                entry.collection = jhook.getString("collection");
                entry.group = jhook.getString("group");
                entry.name = jhook.getString("name");
                entry.author = jhook.optString("author");
                entry.version = jhook.optInt("version");
                entry.description = jhook.optString("description", null);
                // The hooked member, to find definitions hooking the same one
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

            Map<String, XHook> installed = getInstalled(context);
            Set<String> builtinIds = Data.getBuiltinIds(context);
            List<String> collection = Data.getCollection(context);

            runOnUiThread(() -> {
                this.all = entries;
                this.installed = installed;
                this.builtinIds = builtinIds;
                this.collection = collection;
                loaded = true;
                pbLoading.setVisibility(View.GONE);
                show();
            });
        }, this::load);
    }

    // All definitions by id, built-in and custom
    private static Map<String, XHook> getInstalled(Context context) {
        Map<String, XHook> installed = new HashMap<>();
        for (XHook hook : Data.getAllHooks(context, false))
            installed.put(hook.getId(), hook);
        return installed;
    }

    // The list after the installed definitions changed, from the background thread
    private void showInstalled(Context context) {
        Map<String, XHook> installed = getInstalled(context);
        runOnUiThread(() -> {
            this.installed = installed;
            show();
        });
    }

    // A shared definition, by its entry of the list
    private static JSONObject readDefinition(Context context, Entry entry) throws IOException, JSONException {
        return new JSONObject(readAsset(context, REPO + "hook_" + entry.id + ".json"));
    }

    private static String readAsset(Context context, String name) throws IOException {
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

    // Other active definitions hooking the same member: both run, one after the other,
    // and can undo or break the changes of the other
    private List<XHook> getOverlaps(Entry entry) {
        List<XHook> overlaps = new ArrayList<>();
        for (XHook hook : installed.values())
            if (!hook.getId().equals(entry.getId()) &&
                    hook.isAvailable(null, collection) &&
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
        if (!loaded)
            return;

        // The summary, the chips and the updates count all definitions, whatever the search
        Set<String> collections = new HashSet<>();
        int available = 0;
        int installed = 0;
        int updates = 0;
        for (Entry entry : all) {
            collections.add(entry.collection);
            State state = getState(entry);
            if (state == State.AVAILABLE)
                available++;
            else
                installed++;
            if (state == State.UPDATE)
                updates++;
        }
        this.updates = updates;

        List<String> parts = new ArrayList<>();
        parts.add(getResources().getQuantityString(R.plurals.title_definition_count, all.size(), all.size()));
        parts.add(getResources().getQuantityString(R.plurals.title_collection_count, collections.size(), collections.size()));
        if (installed > 0)
            parts.add(getResources().getQuantityString(R.plurals.title_installed_count, installed, installed));
        tvSummary.setText(all.isEmpty() ? null : TextUtils.join(" · ", parts));
        compact.setSubtitle(getString(R.string.title_pair, parts.get(0), getString(FILTER_TITLES[filter.ordinal()])));

        Ui.setChipCount(cgFilter, R.id.chipAll, R.string.title_all, all.size());
        Ui.setChipCount(cgFilter, R.id.chipAvailable, R.string.title_not_installed, available);
        Ui.setChipCount(cgFilter, R.id.chipInstalled, R.string.title_installed, installed);
        Ui.setChipCount(cgFilter, R.id.chipUpdates, R.string.title_updates, updates);
        updatesAdapter.show(updates > 0);

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

        // The list is empty when nothing matches the filter or the search
        emptyText = (!items.isEmpty() || all.isEmpty() ? 0
                : filter == Filter.UPDATES && q.isEmpty() ? R.string.title_no_updates : R.string.title_no_matches);
        emptyAdapter.show(emptyText != 0);
    }

    // One collection is open at a time, like the groups of the Hooks tab, at first the first one;
    // searching or filtering shows all collections with matches. The row of a collection tells how many
    // definitions it has, how many are installed and how many have an update, and when the collection is off.
    private void addCollection(List<Object> items, String collection, List<Entry> entries) {
        if (collection == null || entries.isEmpty())
            return;
        int installed = 0;
        int updates = 0;
        for (Entry entry : entries) {
            State state = getState(entry);
            if (state != State.AVAILABLE)
                installed++;
            if (state == State.UPDATE)
                updates++;
        }
        boolean off = !this.collection.contains(collection);
        List<String> parts = new ArrayList<>();
        parts.add(getResources().getQuantityString(R.plurals.title_definition_count, entries.size(), entries.size()));
        if (installed > 0)
            parts.add(getResources().getQuantityString(R.plurals.title_installed_count, installed, installed));
        if (updates > 0)
            parts.add(getResources().getQuantityString(R.plurals.title_update_count, updates, updates));
        if (off)
            parts.add(getString(R.string.title_collection_off));
        accordion.addGroup(this, items, collection, collection, TextUtils.join(" · ", parts),
                off ? getString(R.string.title_off) : null, R.drawable.ic_group_custom,
                entries, all.get(0).collection,
                filter != Filter.ALL || (query != null && !query.trim().isEmpty()),
                this::show);
    }

    private void install(Entry entry, boolean restrict) {
        final Context context = getApplicationContext();
        final boolean enabled = collection.contains(entry.collection);
        run(TAG, () -> {
            JSONObject jhook = readDefinition(context, entry);
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

    // Updating keeps the id of a definition, so the apps restricting it keep it
    private void confirmUpdateAll() {
        final List<Entry> entries = new ArrayList<>();
        for (Entry entry : all)
            if (getState(entry) == State.UPDATE)
                entries.add(entry);
        if (entries.isEmpty())
            return;
        final int count = entries.size();
        Ui.areYouSure(this, getResources().getQuantityString(R.plurals.msg_update_all, count, count), R.string.title_update, () -> {
            final Context context = getApplicationContext();
            run(TAG, () -> {
                for (Entry entry : entries)
                    Data.putCustom(context, readDefinition(context, entry));
                showInstalled(context);
                Ui.message(this, getResources().getQuantityString(R.plurals.msg_updated, count, count));
            });
        });
    }

    // Deleting an installed definition restores the built-in definition it replaced
    private void confirmRemove(Entry entry) {
        String message = getString(builtinIds.contains(entry.getId())
                ? R.string.msg_remove_restores : R.string.msg_remove_sure, entry.name);
        Ui.areYouSure(this, message, R.string.title_remove, () -> {
            final Context context = getApplicationContext();
            run(TAG, () -> {
                Data.putHook(context, entry.getId(), null);
                showInstalled(context);
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

    // One view above the definitions, like the notice, shown or not; showing it again binds it again
    private static class HeaderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final int layout;
        private final Consumer<View> bind;
        private boolean shown;

        HeaderAdapter(int layout, boolean shown, @Nullable Consumer<View> bind) {
            this.layout = layout;
            this.shown = shown;
            this.bind = bind;
        }

        void show(boolean show) {
            boolean was = shown;
            shown = show;
            if (show && was)
                notifyItemChanged(0);
            else if (show)
                notifyItemInserted(0);
            else if (was)
                notifyItemRemoved(0);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (bind != null)
                bind.accept(holder.itemView);
        }

        @Override
        public int getItemCount() {
            return (shown ? 1 : 0);
        }
    }

    private class Adapter extends AdapterSections<Entry> {
        class EntryHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
            final View llPanel;
            final View vDivider;
            final TextView tvName;
            final TextView tvMeta;
            final TextView tvDescription;
            final ViewGroup cgBadges;
            final Button btnRemove;
            final TextView tvInstalled;
            final Button btnInstall;
            final Button btnUpdate;

            EntryHolder(View itemView) {
                super(itemView);
                llPanel = itemView.findViewById(R.id.llPanel);
                vDivider = itemView.findViewById(R.id.vDivider);
                tvName = itemView.findViewById(R.id.tvName);
                tvMeta = itemView.findViewById(R.id.tvMeta);
                tvDescription = itemView.findViewById(R.id.tvDescription);
                cgBadges = itemView.findViewById(R.id.cgBadges);
                btnRemove = itemView.findViewById(R.id.btnRemove);
                tvInstalled = itemView.findViewById(R.id.tvInstalled);
                btnInstall = itemView.findViewById(R.id.btnInstall);
                btnUpdate = itemView.findViewById(R.id.btnUpdate);
                itemView.setOnClickListener(this);
                btnRemove.setOnClickListener(this);
                btnInstall.setOnClickListener(this);
                btnUpdate.setOnClickListener(this);

                Ui.setStartIcon(tvInstalled, R.drawable.ic_check, 15,
                        Ui.color(itemView.getContext(), R.attr.colorOnSuccessContainer));
            }

            @Override
            public void onClick(View view) {
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION)
                    return;
                Entry entry = getItem(position);
                if (view == btnRemove)
                    confirmRemove(entry);
                else if (view == btnInstall || view == btnUpdate)
                    confirmInstall(entry);
                else if (getState(entry) != State.AVAILABLE)
                    // An installed definition is a custom hook, which the editor shows in full
                    startActivity(ActivityHookEdit.getIntent(ActivityDownloads.this, entry.getId()));
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
        void onBindItem(@NonNull RecyclerView.ViewHolder holder, Entry entry, boolean first, boolean last) {
            EntryHolder h = (EntryHolder) holder;
            Context context = h.itemView.getContext();
            State state = getState(entry);

            joinInset(h, h.llPanel, h.vDivider, first, last);
            h.tvName.setText(entry.name);

            // The group leads, in the text color
            SpannableStringBuilder meta = new SpannableStringBuilder(Groups.getTitle(context, entry.group));
            meta.setSpan(new ForegroundColorSpan(Ui.color(context, com.google.android.material.R.attr.colorOnSurface)),
                    0, meta.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            meta.append(" · ").append(getString(R.string.title_download_meta, entry.author, entry.version,
                    DateUtils.formatDateTime(context, entry.updated, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR |
                            DateUtils.FORMAT_NO_MONTH_DAY | DateUtils.FORMAT_ABBREV_MONTH)));
            h.tvMeta.setText(meta);

            boolean hasDescription = !TextUtils.isEmpty(entry.description);
            h.tvDescription.setVisibility(hasDescription ? View.VISIBLE : View.GONE);
            h.tvDescription.setText(hasDescription ? entry.description.trim() : null);
            h.tvDescription.setMaxLines(expanded.contains(entry.id) ? Integer.MAX_VALUE : 2);

            h.cgBadges.removeAllViews();
            if (builtinIds.contains(entry.getId()))
                Ui.addBadge(h.cgBadges, getString(R.string.title_replaces_builtin), Ui.Badge.WARNING);
            int overlaps = getOverlaps(entry).size();
            if (overlaps > 0)
                Ui.addBadge(h.cgBadges, getResources().getQuantityString(R.plurals.title_overlaps, overlaps, overlaps),
                        Ui.Badge.WARNING);
            if (state == State.UPDATE)
                Ui.addBadge(h.cgBadges, getString(R.string.title_version_change,
                        Objects.requireNonNull(installed.get(entry.getId())).getVersion(), entry.version), Ui.Badge.NEUTRAL);

            // Installed definitions can be removed, also the ones with an update
            h.btnRemove.setVisibility(state == State.AVAILABLE ? View.GONE : View.VISIBLE);
            h.tvInstalled.setVisibility(state == State.INSTALLED ? View.VISIBLE : View.GONE);
            h.btnInstall.setVisibility(state == State.AVAILABLE ? View.VISIBLE : View.GONE);
            h.btnUpdate.setVisibility(state == State.UPDATE ? View.VISIBLE : View.GONE);
            // Screen readers tell which definition the buttons are for
            h.btnRemove.setContentDescription(h.btnRemove.getText() + ", " + entry.name);
            h.btnInstall.setContentDescription(h.btnInstall.getText() + ", " + entry.name);
            h.btnUpdate.setContentDescription(h.btnUpdate.getText() + ", " + entry.name);
        }
    }
}
