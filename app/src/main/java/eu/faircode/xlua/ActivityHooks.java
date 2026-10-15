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
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// All hook definitions: built-in, custom and custom replacing built-in
public class ActivityHooks extends ActivityBase {
    private static final String TAG = "XLua.Hooks";

    private final List<XHook> all = new ArrayList<>();
    private final List<XHook> shown = new ArrayList<>();
    private final Set<String> builtinIds = new HashSet<>();
    private String query = null;
    private Adapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), new ActivityResultCallback<Uri>() {
                @Override
                public void onActivityResult(Uri uri) {
                    if (uri != null)
                        importHooks(uri);
                }
            });

    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), new ActivityResultCallback<Uri>() {
                @Override
                public void onActivityResult(Uri uri) {
                    if (uri != null)
                        exportHooks(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.hooks);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(R.string.menu_hooks);

        RecyclerView rvHooks = findViewById(R.id.rvHooks);
        rvHooks.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rvHooks.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private void load() {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<XHook> hooks = XProvider.getAllHooks(ActivityHooks.this);
                    final Set<String> ids = new HashSet<>();
                    for (XHook builtin : XHook.readHooks(ActivityHooks.this, getApplicationInfo().publicSourceDir))
                        ids.add(builtin.getId());
                    Collections.sort(hooks, new Comparator<XHook>() {
                        @Override
                        public int compare(XHook h1, XHook h2) {
                            return h1.getName().compareToIgnoreCase(h2.getName());
                        }
                    });
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            all.clear();
                            all.addAll(hooks);
                            builtinIds.clear();
                            builtinIds.addAll(ids);
                            filter();
                        }
                    });
                } catch (Throwable ex) {
                    Log.e(TAG, Log.getStackTraceString(ex));
                    showMessage(getString(R.string.msg_error, ex.toString()));
                }
            }
        });
    }

    private void filter() {
        shown.clear();
        String q = (query == null ? null : query.toLowerCase(Locale.ROOT).trim());
        for (XHook hook : all)
            if (TextUtils.isEmpty(q) ||
                    hook.getId().toLowerCase(Locale.ROOT).contains(q) ||
                    hook.getGroup().toLowerCase(Locale.ROOT).contains(q))
                shown.add(hook);
        adapter.notifyDataSetChanged();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.hooks, menu);
        SearchView searchView = (SearchView) menu.findItem(R.id.menu_search).getActionView();
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String text) {
                query = text;
                filter();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String text) {
                query = text;
                filter();
                return true;
            }
        });
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.menu_add) {
            startActivity(new Intent(this, ActivityHookEdit.class));
            return true;
        } else if (id == R.id.menu_import) {
            importLauncher.launch(new String[]{"application/json", "text/*", "application/octet-stream"});
            return true;
        } else if (id == R.id.menu_export) {
            exportLauncher.launch("xprivacylua-hooks.json");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // A file with a definition or an array of definitions
    private void importHooks(final Uri uri) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    String text;
                    try (InputStream is = getContentResolver().openInputStream(uri)) {
                        text = new Scanner(is, "UTF-8").useDelimiter("\\A").next();
                    }
                    JSONArray jarray = (text.trim().startsWith("[") ? new JSONArray(text) : new JSONArray().put(new JSONObject(text)));
                    int count = 0;
                    for (int i = 0; i < jarray.length(); i++) {
                        putCustom(ActivityHooks.this, jarray.getJSONObject(i));
                        count++;
                    }
                    showMessage(getString(R.string.msg_hooks_imported, count));
                    load();
                } catch (Throwable ex) {
                    Log.e(TAG, Log.getStackTraceString(ex));
                    showMessage(getString(R.string.msg_error, ex.toString()));
                }
            }
        });
    }

    // Custom definitions only; built-in definitions are part of the app
    private void exportHooks(final Uri uri) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray jarray = new JSONArray();
                    for (XHook hook : XProvider.getAllHooks(ActivityHooks.this))
                        if (!hook.isBuiltin())
                            jarray.put(toEditable(hook));
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        os.write(jarray.toString(2).getBytes(StandardCharsets.UTF_8));
                    }
                    showMessage(getString(R.string.msg_hooks_exported, jarray.length()));
                } catch (Throwable ex) {
                    Log.e(TAG, Log.getStackTraceString(ex));
                    showMessage(getString(R.string.msg_error, ex.toString()));
                }
            }
        });
    }

    // Definition as shown to and edited by users
    static JSONObject toEditable(XHook hook) throws JSONException {
        JSONObject jhook = hook.toJSONObject();
        jhook.remove("builtin");
        jhook.remove("resolvedClassName");
        return jhook;
    }

    // Store a definition as custom definition, which replaces a built-in definition with the same id
    static XHook putCustom(Context context, JSONObject jhook) throws JSONException {
        jhook.put("builtin", false);
        jhook.remove("resolvedClassName");
        XHook hook = XHook.fromJSONObject(jhook);
        hook.validate();
        XProvider.putHook(context, hook.getId(), hook.toJSON());
        return hook;
    }

    private void showMessage(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {
        class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
            final TextView tvName;
            final TextView tvGroup;
            final TextView tvState;

            ViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvName);
                tvGroup = itemView.findViewById(R.id.tvGroup);
                tvState = itemView.findViewById(R.id.tvState);
                itemView.setOnClickListener(this);
            }

            @Override
            public void onClick(View view) {
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION)
                    return;
                Intent edit = new Intent(ActivityHooks.this, ActivityHookEdit.class);
                edit.putExtra(ActivityHookEdit.EXTRA_ID, shown.get(position).getId());
                startActivity(edit);
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.hookitem, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            XHook hook = shown.get(position);
            holder.tvName.setText(hook.getName());
            holder.tvGroup.setText(hook.getCollection() + " · " + hook.getGroup());

            List<String> state = new ArrayList<>();
            if (hook.isBuiltin())
                state.add(getString(R.string.title_hook_builtin));
            else if (builtinIds.contains(hook.getId()))
                state.add(getString(R.string.title_hook_overridden));
            else
                state.add(getString(R.string.title_hook_custom));
            if (!hook.isEnabled())
                state.add(getString(R.string.title_hook_disabled));
            if (!hook.isForThisSdk())
                state.add(getString(R.string.title_hook_unavailable));
            holder.tvState.setText(TextUtils.join(" · ", state));

            // Hooks which do not run on this device
            holder.itemView.setAlpha(hook.isEnabled() && hook.isForThisSdk() ? 1.0f : 0.5f);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}
