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

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Restrictions applied to apps: hook, time and the real and fake value
public class ActivityLog extends ActivityBase {
    private static final String TAG = "XLua.Log";

    private static class Entry {
        String packageName;
        String label;
        int icon;
        String hook;
        long used;
        String oldValue;
        String newValue;
    }

    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();
    private Adapter adapter;
    private TextView tvEmpty;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.log);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(R.string.menu_log);

        tvEmpty = findViewById(R.id.tvEmpty);
        RecyclerView rvLog = findViewById(R.id.rvLog);
        rvLog.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rvLog.setAdapter(adapter);
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
                final List<Entry> entries = new ArrayList<>();
                try {
                    PackageManager pm = getPackageManager();
                    Map<String, ApplicationInfo> infos = new HashMap<>();
                    try (Cursor cursor = getContentResolver().query(XProvider.getURI(),
                            new String[]{"xlua.getLog"}, null, null, null)) {
                        while (cursor != null && cursor.moveToNext()) {
                            Entry entry = new Entry();
                            entry.packageName = cursor.getString(cursor.getColumnIndexOrThrow("package"));
                            entry.hook = cursor.getString(cursor.getColumnIndexOrThrow("hook"));
                            entry.used = cursor.getLong(cursor.getColumnIndexOrThrow("used"));
                            entry.oldValue = cursor.getString(cursor.getColumnIndexOrThrow("old"));
                            entry.newValue = cursor.getString(cursor.getColumnIndexOrThrow("new"));

                            if (!infos.containsKey(entry.packageName))
                                try {
                                    infos.put(entry.packageName, pm.getApplicationInfo(entry.packageName, 0));
                                } catch (PackageManager.NameNotFoundException ignored) {
                                    infos.put(entry.packageName, null);
                                }
                            ApplicationInfo ai = infos.get(entry.packageName);
                            entry.label = (ai == null ? entry.packageName : pm.getApplicationLabel(ai).toString());
                            entry.icon = (ai == null ? 0 : ai.icon);
                            entries.add(entry);
                        }
                    }
                } catch (Throwable ex) {
                    Log.e(TAG, Log.getStackTraceString(ex));
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        all.addAll(entries);
                        filter(null);
                    }
                });
            }
        });
    }

    private void filter(String query) {
        shown.clear();
        String q = (query == null ? null : query.toLowerCase(Locale.getDefault()).trim());
        for (Entry entry : all)
            if (TextUtils.isEmpty(q) ||
                    entry.label.toLowerCase(Locale.getDefault()).contains(q) ||
                    entry.packageName.toLowerCase(Locale.ROOT).contains(q) ||
                    entry.hook.toLowerCase(Locale.ROOT).contains(q))
                shown.add(entry);
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.search, menu);
        SearchView searchView = (SearchView) menu.findItem(R.id.menu_search).getActionView();
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                filter(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                filter(newText);
                return true;
            }
        });
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {
        class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
            final ImageView ivIcon;
            final TextView tvApp;
            final TextView tvTime;
            final TextView tvHook;
            final TextView tvValues;

            ViewHolder(View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.ivIcon);
                tvApp = itemView.findViewById(R.id.tvApp);
                tvTime = itemView.findViewById(R.id.tvTime);
                tvHook = itemView.findViewById(R.id.tvHook);
                tvValues = itemView.findViewById(R.id.tvValues);
                itemView.setOnClickListener(this);
            }

            // Show the app in the main list
            @Override
            public void onClick(View view) {
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION)
                    return;
                Intent main = new Intent(ActivityLog.this, ActivityMain.class);
                main.putExtra(ActivityMain.EXTRA_SEARCH_PACKAGE, shown.get(position).packageName);
                startActivity(main);
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.logitem, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Entry entry = shown.get(position);
            if (entry.icon <= 0)
                holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            else
                Glide.with(holder.itemView.getContext())
                        .load(Uri.parse("android.resource://" + entry.packageName + "/" + entry.icon))
                        .into(holder.ivIcon);
            holder.tvApp.setText(entry.label);
            holder.tvTime.setText(DateUtils.formatDateTime(holder.itemView.getContext(), entry.used,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_ALL));
            holder.tvHook.setText(entry.hook);
            boolean values = (entry.oldValue != null || entry.newValue != null);
            holder.tvValues.setVisibility(values ? View.VISIBLE : View.GONE);
            if (values)
                holder.tvValues.setText(entry.oldValue + " → " + entry.newValue);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}
