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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// All hook definitions: built-in, custom and custom replacing built-in
public class FragmentHooks extends FragmentBase {
    private static final String TAG = "XLua.Hooks";

    private enum Filter {ALL, CUSTOM, DISABLED}

    private static final int[] FILTER_CHIPS = {R.id.chipAll, R.id.chipCustom, R.id.chipDisabled};

    // The group which shows its hooks, the others show only their header
    private static final String PREF_EXPANDED = "hooks_group";

    private View pbLoading;
    private TextView tvEmpty;
    private TextView tvSummary;
    private ExtendedFloatingActionButton fabAdd;
    private Adapter adapter;

    private Filter filter = Filter.ALL;
    private String query = null;
    private List<XHook> all = new ArrayList<>();
    private Set<String> builtinIds = new HashSet<>();
    private List<String> collection = new ArrayList<>();
    private AdapterSections.Accordion accordion;

    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null)
                    importHooks(uri);
            });

    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri != null)
                    exportHooks(uri);
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_hooks, container, false);
        Context context = requireContext();
        // The open group is remembered
        accordion = new AdapterSections.Accordion(
                ApplicationEx.getPreferences(context).getString(PREF_EXPANDED, null),
                open -> ApplicationEx.getPreferences(context).edit().putString(PREF_EXPANDED, open).apply());

        pbLoading = view.findViewById(R.id.pbLoading);
        tvEmpty = view.findViewById(R.id.tvEmpty);
        tvSummary = view.findViewById(R.id.tvSummary);

        adapter = new Adapter();
        RecyclerView rvHooks = view.findViewById(R.id.rvHooks);
        rvHooks.setLayoutManager(new LinearLayoutManager(context));
        rvHooks.setAdapter(adapter);

        // The button shows only its icon while scrolling down, so it covers less of the list
        fabAdd = view.findViewById(R.id.fabAdd);
        fabAdd.setOnClickListener(v -> startActivity(new Intent(context, ActivityHookEdit.class)));
        rvHooks.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0)
                    fabAdd.shrink();
                else if (dy < 0)
                    fabAdd.extend();
            }
        });
        view.findViewById(R.id.ibDownloads).setOnClickListener(v ->
                startActivity(new Intent(context, ActivityDownloads.class)));
        view.findViewById(R.id.ibMore).setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(context, v);
            popup.inflate(R.menu.hooks);
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.menu_import)
                    importLauncher.launch(new String[]{"application/json", "text/*", "application/octet-stream"});
                else if (item.getItemId() == R.id.menu_export)
                    exportLauncher.launch(Ui.datedFileName("hooks"));
                return true;
            });
            popup.show();
        });

        EditText etSearch = view.findViewById(R.id.etSearch);
        Ui.onTextChanged(etSearch, text -> {
            query = text;
            accordion.reset();
            show();
        });

        ChipGroup cgFilter = view.findViewById(R.id.cgFilter);
        Ui.chips(cgFilter, FILTER_CHIPS, Filter.values(), filter, selected -> {
            filter = selected;
            accordion.reset();
            show();
        });

        return view;
    }

    @Override
    protected void load() {
        final Context context = requireContext().getApplicationContext();
        executor.submit(() -> {
            try {
                List<XHook> hooks = Data.getAllHooks(context, false);
                Set<String> ids = Data.getBuiltinIds(context);
                List<String> collection = Data.getCollection(context);
                Collections.sort(hooks, (h1, h2) -> {
                    int c = h1.getGroup().compareToIgnoreCase(h2.getGroup());
                    return (c == 0 ? h1.getName().compareToIgnoreCase(h2.getName()) : c);
                });
                post(() -> {
                    all = hooks;
                    builtinIds = ids;
                    this.collection = collection;
                    pbLoading.setVisibility(View.GONE);
                    show();
                });
            } catch (Throwable ex) {
                post(() -> pbLoading.setVisibility(View.GONE));
                error(TAG, ex);
            }
        });
    }

    private void show() {
        int builtin = 0;
        int custom = 0;
        Set<String> groups = new HashSet<>();
        for (XHook hook : all) {
            if (hook.isBuiltin())
                builtin++;
            else
                custom++;
            groups.add(hook.getGroup());
        }
        tvSummary.setText(getString(R.string.title_hooks_summary, builtin, custom, groups.size()));

        String q = (query == null ? "" : query.trim());
        List<Object> items = new ArrayList<>();
        String group = null;
        List<XHook> inGroup = new ArrayList<>();
        Context context = requireContext();
        for (XHook hook : all) {
            if (filter == Filter.CUSTOM && hook.isBuiltin())
                continue;
            if (filter == Filter.DISABLED && hook.isEnabled())
                continue;
            // Groups by name and by the title shown, like 'call log' for Get.Call.log
            if (!q.isEmpty() &&
                    !Ui.contains(hook.getId(), q) &&
                    !Ui.contains(hook.getGroup(), q) &&
                    !Ui.contains(Groups.getTitle(context, hook.getGroup()), q) &&
                    !Ui.contains(hook.getClassName(), q))
                continue;
            if (!hook.getGroup().equals(group)) {
                addGroup(items, group, inGroup);
                group = hook.getGroup();
                inGroup = new ArrayList<>();
            }
            inGroup.add(hook);
        }
        addGroup(items, group, inGroup);

        adapter.set(items);
        // Another list can be too short to scroll the button back
        fabAdd.extend();
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // One group is open at a time, at first the first one; searching or filtering shows all groups with matches
    private void addGroup(List<Object> items, String group, List<XHook> hooks) {
        Context context = requireContext();
        accordion.add(context, items, group, group == null ? null : Groups.getTitle(context, group), hooks,
                all.isEmpty() ? null : all.get(0).getGroup(),
                filter != Filter.ALL || (query != null && !query.trim().isEmpty()),
                this::show);
    }

    // A file with a definition or an array of definitions
    private void importHooks(final Uri uri) {
        final Activity activity = requireActivity();
        final Context context = activity.getApplicationContext();
        run(TAG, () -> {
            try {
                String text = Util.readText(context, uri);
                JSONArray jarray = (text.trim().startsWith("[")
                        ? new JSONArray(text) : new JSONArray().put(new JSONObject(text)));
                List<XHook> imported = new ArrayList<>();
                for (int i = 0; i < jarray.length(); i++)
                    imported.add(Data.putCustom(context, jarray.getJSONObject(i)));
                // Apps get the definitions when asked, like when installing from Downloads
                Ui.message(activity,
                        activity.getResources().getQuantityString(R.plurals.msg_hooks_imported, imported.size(), imported.size()),
                        activity.getString(R.string.title_restrict_too),
                        () -> run(TAG, () -> Ui.message(activity, Data.restrictForGroups(context, imported))));
            } finally {
                // Also the definitions imported before an error
                post(this::load);
            }
        });
    }

    // Custom definitions only; built-in definitions are part of the app
    private void exportHooks(final Uri uri) {
        final Activity activity = requireActivity();
        final Context context = activity.getApplicationContext();
        run(TAG, () -> {
            JSONArray jarray = Data.getCustomHooks(context);
            Util.writeJson(context, uri, jarray.toString(2));
            Ui.message(activity, activity.getResources().getQuantityString(R.plurals.msg_hooks_exported, jarray.length(), jarray.length()));
        });
    }

    // Where a hook applies, like android.os.Build#SERIAL or TelephonyManager.getImei(int)
    static String getTarget(XHook hook) {
        StringBuilder sb = new StringBuilder(hook.getClassName());
        String method = hook.getMethodName();
        if (method != null && method.startsWith("#"))
            return sb.append(method).toString();
        sb.append('.').append(method == null ? "<init>" : method).append('(');
        String[] params = hook.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(params[i].substring(params[i].lastIndexOf('.') + 1));
        }
        return sb.append(')').toString();
    }

    private class Adapter extends AdapterSections<XHook> {
        class HookHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
            final TextView tvName;
            final TextView tvClass;
            final LinearLayout llBadges;

            HookHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvName);
                tvClass = itemView.findViewById(R.id.tvClass);
                llBadges = itemView.findViewById(R.id.llBadges);
                itemView.setOnClickListener(this);
            }

            @Override
            public void onClick(View view) {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION)
                    startActivity(ActivityHookEdit.getIntent(view.getContext(), getItem(position).getId()));
            }
        }

        @Override
        RecyclerView.ViewHolder onCreateItemHolder(@NonNull ViewGroup parent) {
            return new HookHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hook, parent, false));
        }

        @Override
        void onBindItem(@NonNull RecyclerView.ViewHolder holder, XHook hook) {
            HookHolder h = (HookHolder) holder;
            Context context = h.itemView.getContext();
            boolean runs = (hook.isEnabled() && hook.isForThisSdk() && collection.contains(hook.getCollection()));

            h.tvName.setText(hook.getName());
            h.tvName.setTextColor(Ui.color(context, runs
                    ? com.google.android.material.R.attr.colorOnSurface
                    : com.google.android.material.R.attr.colorOnSurfaceVariant));
            h.tvClass.setText(getTarget(hook));

            h.llBadges.removeAllViews();
            // Built-in definitions, which most are, have no badge of their own
            if (!hook.isBuiltin())
                Ui.addBadge(h.llBadges, getString(builtinIds.contains(hook.getId())
                        ? R.string.title_hook_overridden : R.string.title_hook_custom), Ui.Badge.ACCENT);
            if (!hook.isEnabled())
                Ui.addBadge(h.llBadges, getString(R.string.title_hook_disabled), Ui.Badge.NEUTRAL);
            if (!hook.isForThisSdk())
                Ui.addBadge(h.llBadges, getString(R.string.title_hook_unavailable), Ui.Badge.WARNING);
            if (!collection.contains(hook.getCollection()))
                Ui.addBadge(h.llBadges, getString(R.string.title_hook_collection_off, hook.getCollection()), Ui.Badge.NEUTRAL);
            h.llBadges.setVisibility(h.llBadges.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        }
    }
}
