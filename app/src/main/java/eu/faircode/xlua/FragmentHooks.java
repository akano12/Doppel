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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// All hook definitions: built-in, custom and custom replacing built-in
public class FragmentHooks extends FragmentBase {
    private static final String TAG = "XLua.Hooks";

    private enum Filter {ALL, CUSTOM, DISABLED}

    private View pbLoading;
    private TextView tvEmpty;
    private TextView tvSummary;
    private Adapter adapter;

    private Filter filter = Filter.ALL;
    private String query = null;
    private List<XHook> all = new ArrayList<>();
    private Set<String> builtinIds = new HashSet<>();
    private List<String> collection = new ArrayList<>();

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

        pbLoading = view.findViewById(R.id.pbLoading);
        tvEmpty = view.findViewById(R.id.tvEmpty);
        tvSummary = view.findViewById(R.id.tvSummary);

        adapter = new Adapter();
        RecyclerView rvHooks = view.findViewById(R.id.rvHooks);
        rvHooks.setLayoutManager(new LinearLayoutManager(context));
        rvHooks.setAdapter(adapter);

        view.findViewById(R.id.fabAdd).setOnClickListener(v ->
                startActivity(new Intent(context, ActivityHookEdit.class)));
        view.findViewById(R.id.ibDownloads).setOnClickListener(v ->
                startActivity(new Intent(context, ActivityDownloads.class)));
        view.findViewById(R.id.ibMore).setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(context, v);
            popup.inflate(R.menu.hooks);
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.menu_import)
                    importLauncher.launch(new String[]{"application/json", "text/*", "application/octet-stream"});
                else if (item.getItemId() == R.id.menu_export)
                    exportLauncher.launch("xprivacylua-hooks.json");
                return true;
            });
            popup.show();
        });

        EditText etSearch = view.findViewById(R.id.etSearch);
        Ui.onTextChanged(etSearch, text -> {
            query = text;
            show();
        });

        ChipGroup cgFilter = view.findViewById(R.id.cgFilter);
        cgFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty())
                return;
            int id = checkedIds.get(0);
            filter = (id == R.id.chipCustom ? Filter.CUSTOM : id == R.id.chipDisabled ? Filter.DISABLED : Filter.ALL);
            show();
        });

        return view;
    }

    @Override
    protected void load() {
        final Context context = requireContext().getApplicationContext();
        executor.submit(() -> {
            try {
                List<XHook> hooks = Data.getAllHooks(context);
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

        String q = (query == null ? "" : query.toLowerCase(Locale.ROOT).trim());
        List<Object> items = new ArrayList<>();
        String group = null;
        List<XHook> inGroup = new ArrayList<>();
        for (XHook hook : all) {
            if (filter == Filter.CUSTOM && hook.isBuiltin())
                continue;
            if (filter == Filter.DISABLED && hook.isEnabled())
                continue;
            if (!q.isEmpty() &&
                    !hook.getId().toLowerCase(Locale.ROOT).contains(q) &&
                    !hook.getGroup().toLowerCase(Locale.ROOT).contains(q) &&
                    !hook.getClassName().toLowerCase(Locale.ROOT).contains(q))
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
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void addGroup(List<Object> items, String group, List<XHook> hooks) {
        if (group == null || hooks.isEmpty())
            return;
        items.add(getString(R.string.title_group_header,
                Groups.getTitle(requireContext(), group), group, hooks.size()));
        items.addAll(hooks);
    }

    // A file with a definition or an array of definitions
    private void importHooks(final Uri uri) {
        final Activity activity = requireActivity();
        final Context context = activity.getApplicationContext();
        executor.submit(() -> {
            try {
                String text;
                try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                    text = Util.readText(is);
                }
                JSONArray jarray = (text.trim().startsWith("[")
                        ? new JSONArray(text) : new JSONArray().put(new JSONObject(text)));
                for (int i = 0; i < jarray.length(); i++)
                    Data.putCustom(context, jarray.getJSONObject(i));
                Ui.message(activity, getString(R.string.msg_hooks_imported, jarray.length()));
                post(this::load);
            } catch (Throwable ex) {
                error(TAG, ex);
            }
        });
    }

    // Custom definitions only; built-in definitions are part of the app
    private void exportHooks(final Uri uri) {
        final Activity activity = requireActivity();
        final Context context = activity.getApplicationContext();
        executor.submit(() -> {
            try {
                JSONArray jarray = new JSONArray();
                for (XHook hook : Data.getAllHooks(context))
                    if (!hook.isBuiltin())
                        jarray.put(Data.toEditable(hook));
                try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                    os.write(jarray.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                Ui.message(activity, getString(R.string.msg_hooks_exported, jarray.length()));
            } catch (Throwable ex) {
                error(TAG, ex);
            }
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
            int neutral = com.google.android.material.R.attr.colorSurfaceContainerHigh;
            int onNeutral = com.google.android.material.R.attr.colorOnSurfaceVariant;
            int accent = com.google.android.material.R.attr.colorPrimaryContainer;
            int onAccent = com.google.android.material.R.attr.colorOnPrimaryContainer;
            if (hook.isBuiltin())
                Ui.addBadge(h.llBadges, getString(R.string.title_hook_builtin), neutral, onNeutral);
            else if (builtinIds.contains(hook.getId()))
                Ui.addBadge(h.llBadges, getString(R.string.title_hook_overridden), accent, onAccent);
            else
                Ui.addBadge(h.llBadges, getString(R.string.title_hook_custom), accent, onAccent);
            if (!hook.isEnabled())
                Ui.addBadge(h.llBadges, getString(R.string.title_hook_disabled), neutral, onNeutral);
            if (!hook.isForThisSdk())
                Ui.addBadge(h.llBadges, getString(R.string.title_hook_unavailable),
                        R.attr.colorWarningContainer, R.attr.colorOnWarningContainer);
            if (!collection.contains(hook.getCollection()))
                Ui.addBadge(h.llBadges, getString(R.string.title_hook_collection_off, hook.getCollection()), neutral, onNeutral);
        }
    }
}
