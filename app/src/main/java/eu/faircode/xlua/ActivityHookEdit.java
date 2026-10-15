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

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.snackbar.Snackbar;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Edit a hook definition; saving creates or updates a custom definition
public class ActivityHookEdit extends ActivityBase {
    private static final String TAG = "XLua.HookEdit";

    static final String EXTRA_ID = "id";

    private static final String TEMPLATE = "{\n" +
            "  \"collection\": \"Privacy\",\n" +
            "  \"group\": \"Custom\",\n" +
            "  \"name\": \"TelephonyManager.getSimOperatorName/custom\",\n" +
            "  \"author\": \"me\",\n" +
            "  \"className\": \"android.telephony.TelephonyManager\",\n" +
            "  \"methodName\": \"getSimOperatorName\",\n" +
            "  \"parameterTypes\": [],\n" +
            "  \"returnType\": \"java.lang.String\",\n" +
            "  \"minSdk\": 1,\n" +
            "  \"enabled\": true,\n" +
            "  \"optional\": false,\n" +
            "  \"usage\": true,\n" +
            "  \"notify\": false\n" +
            "}";

    private static final String TEMPLATE_SCRIPT = "function after(hook, param)\n" +
            "    local result = param:getResult()\n" +
            "    if result == nil then\n" +
            "        return false\n" +
            "    end\n" +
            "\n" +
            "    local fake = 'private'\n" +
            "    param:setResult(fake)\n" +
            "    return true, result, fake\n" +
            "end\n";

    private EditText etDefinition;
    private EditText etScript;
    private TextView tvNote;
    private XHook hook = null; // null for a new definition
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), new ActivityResultCallback<Uri>() {
                @Override
                public void onActivityResult(Uri uri) {
                    if (uri != null)
                        export(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.hookedit);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        etDefinition = findViewById(R.id.etDefinition);
        etScript = findViewById(R.id.etScript);
        tvNote = findViewById(R.id.tvNote);
        etDefinition.setHorizontallyScrolling(true);
        etScript.setHorizontallyScrolling(true);

        final String id = getIntent().getStringExtra(EXTRA_ID);
        if (id == null) {
            getSupportActionBar().setTitle(R.string.menu_add);
            etDefinition.setText(TEMPLATE);
            etScript.setText(TEMPLATE_SCRIPT);
            return;
        }

        getSupportActionBar().setTitle(id);
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    for (final XHook h : XProvider.getAllHooks(ActivityHookEdit.this))
                        if (id.equals(h.getId())) {
                            JSONObject jhook = ActivityHooks.toEditable(h);
                            jhook.remove("luaScript");
                            final String definition = jhook.toString(2).replace("\\/", "/");
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    hook = h;
                                    etDefinition.setText(definition);
                                    etScript.setText(h.getLuaScript());
                                    tvNote.setVisibility(h.isBuiltin() ? View.VISIBLE : View.GONE);
                                    invalidateOptionsMenu();
                                }
                            });
                            break;
                        }
                } catch (Throwable ex) {
                    Log.e(TAG, Log.getStackTraceString(ex));
                    showMessage(getString(R.string.msg_error, ex.toString()));
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.hookedit, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean enabled = (hook == null || hook.isEnabled());
        menu.findItem(R.id.menu_enable).setTitle(enabled ? R.string.menu_disable : R.string.menu_enable);
        menu.findItem(R.id.menu_enable).setVisible(hook != null);
        // Only custom definitions can be deleted; deleting one replacing a built-in restores the built-in
        menu.findItem(R.id.menu_delete).setVisible(hook != null && !hook.isBuiltin());
        menu.findItem(R.id.menu_export).setVisible(hook != null);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.menu_save) {
            save(null);
            return true;
        } else if (id == R.id.menu_enable) {
            save(!hook.isEnabled());
            return true;
        } else if (id == R.id.menu_delete) {
            delete();
            return true;
        } else if (id == R.id.menu_export) {
            exportLauncher.launch(hook.getName().replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private JSONObject getDefinition() throws Exception {
        JSONObject jhook = new JSONObject(etDefinition.getText().toString());
        jhook.put("luaScript", etScript.getText().toString());
        return jhook;
    }

    private void save(final Boolean enabled) {
        final JSONObject jhook;
        try {
            jhook = getDefinition();
            if (enabled != null)
                jhook.put("enabled", enabled);
        } catch (Throwable ex) {
            showMessage(getString(R.string.msg_error, ex.getMessage()));
            return;
        }

        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final XHook saved = ActivityHooks.putCustom(ActivityHookEdit.this, jhook);
                    final String definition;
                    JSONObject jedit = ActivityHooks.toEditable(saved);
                    jedit.remove("luaScript");
                    definition = jedit.toString(2).replace("\\/", "/");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hook = saved;
                            etDefinition.setText(definition);
                            getSupportActionBar().setTitle(saved.getId());
                            tvNote.setVisibility(View.GONE);
                            invalidateOptionsMenu();
                        }
                    });
                    showMessage(getString(R.string.msg_hook_saved));
                } catch (Throwable ex) {
                    Log.e(TAG, Log.getStackTraceString(ex));
                    showMessage(getString(R.string.msg_error, ex.getMessage()));
                }
            }
        });
    }

    private void delete() {
        Util.areYouSure(this, getString(R.string.msg_delete_sure, hook.getId()), new Util.DoubtListener() {
            @Override
            public void onSure() {
                final String id = hook.getId();
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            XProvider.putHook(ActivityHookEdit.this, id, null);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    finish();
                                }
                            });
                        } catch (Throwable ex) {
                            Log.e(TAG, Log.getStackTraceString(ex));
                            showMessage(getString(R.string.msg_error, ex.getMessage()));
                        }
                    }
                });
            }
        });
    }

    private void export(final Uri uri) {
        final JSONObject jhook;
        try {
            jhook = getDefinition();
        } catch (Throwable ex) {
            showMessage(getString(R.string.msg_error, ex.getMessage()));
            return;
        }
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(jhook.toString(2).replace("\\/", "/").getBytes(StandardCharsets.UTF_8));
                    showMessage(getString(R.string.msg_hooks_exported, 1));
                } catch (Throwable ex) {
                    Log.e(TAG, Log.getStackTraceString(ex));
                    showMessage(getString(R.string.msg_error, ex.getMessage()));
                }
            }
        });
    }

    private void showMessage(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
            }
        });
    }
}
