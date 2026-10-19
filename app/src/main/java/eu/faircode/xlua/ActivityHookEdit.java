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
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Edit a hook definition; saving creates or updates a custom definition
public class ActivityHookEdit extends ActivityBase {
    private static final String TAG = "XLua.HookEdit";

    private static final String EXTRA_ID = "id";

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

    private XHook hook = null; // null for a new definition
    private Set<String> builtinIds = null;

    private MaterialToolbar toolbar;
    private EditText etCollection;
    private MaterialAutoCompleteTextView etGroup;
    private EditText etName;
    private EditText etAuthor;
    private EditText etVersion;
    private EditText etDescription;
    private EditText etClass;
    private EditText etMethod;
    private EditText etParams;
    private EditText etReturn;
    private EditText etMinSdk;
    private EditText etMaxSdk;
    private EditText etMinApk;
    private EditText etMaxApk;
    private EditText etExclude;
    private EditText etSettings;
    private MaterialSwitch swEnabled;
    private MaterialSwitch swOptional;
    private MaterialSwitch swUsage;
    private MaterialSwitch swNotify;
    private EditText etScript;
    private TextView tvCheck;
    private TextView tvScriptInfo;
    private LuaHighlighter highlighter;

    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri != null)
                    export(uri);
            });

    static Intent getIntent(Context context, String id) {
        Intent intent = new Intent(context, ActivityHookEdit.class);
        intent.putExtra(EXTRA_ID, id);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hook_edit);

        final String id = getIntent().getStringExtra(EXTRA_ID);
        toolbar = setupToolbar(id == null ? getString(R.string.title_hook_new) : id);
        toolbar.inflateMenu(R.menu.hookedit);
        toolbar.setOnMenuItemClickListener(this::onMenu);

        etCollection = findViewById(R.id.etCollection);
        etGroup = findViewById(R.id.etGroup);
        etName = findViewById(R.id.etName);
        etAuthor = findViewById(R.id.etAuthor);
        etVersion = findViewById(R.id.etVersion);
        etDescription = findViewById(R.id.etDescription);
        etClass = findViewById(R.id.etClass);
        etMethod = findViewById(R.id.etMethod);
        etParams = findViewById(R.id.etParams);
        etReturn = findViewById(R.id.etReturn);
        etMinSdk = findViewById(R.id.etMinSdk);
        etMaxSdk = findViewById(R.id.etMaxSdk);
        etMinApk = findViewById(R.id.etMinApk);
        etMaxApk = findViewById(R.id.etMaxApk);
        etExclude = findViewById(R.id.etExclude);
        etSettings = findViewById(R.id.etSettings);
        swEnabled = findViewById(R.id.swEnabled);
        swOptional = findViewById(R.id.swOptional);
        swUsage = findViewById(R.id.swUsage);
        swNotify = findViewById(R.id.swNotify);
        etScript = findViewById(R.id.etScript);
        tvCheck = findViewById(R.id.tvCheck);
        tvScriptInfo = findViewById(R.id.tvScriptInfo);

        etScript.setHorizontallyScrolling(true);
        highlighter = new LuaHighlighter(etScript);
        etScript.addTextChangedListener(highlighter);

        final View svDefinition = findViewById(R.id.svDefinition);
        final View llScript = findViewById(R.id.llScript);
        TabLayout tabs = findViewById(R.id.tabs);
        tabs.addTab(tabs.newTab().setText(R.string.title_tab_definition));
        tabs.addTab(tabs.newTab().setText(R.string.title_tab_script));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                svDefinition.setVisibility(tab.getPosition() == 0 ? View.VISIBLE : View.GONE);
                llScript.setVisibility(tab.getPosition() == 1 ? View.VISIBLE : View.GONE);
                if (tab.getPosition() == 1)
                    updateScriptInfo();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        findViewById(R.id.btnCheck).setOnClickListener(view -> check());

        if (id == null) {
            etCollection.setText(Util.COLLECTION_DEFAULT);
            etGroup.setText("Custom");
            etAuthor.setText("me");
            etVersion.setText("1");
            etMinSdk.setText("1");
            swEnabled.setChecked(true);
            swUsage.setChecked(true);
            etScript.setText(TEMPLATE_SCRIPT);
            updateMenu();
        }

        load(id);
    }

    @Override
    protected void onDestroy() {
        highlighter.stop();
        super.onDestroy();
    }

    private void load(final String id) {
        final Context context = getApplicationContext();
        executor.submit(() -> {
            try {
                List<XHook> hooks = Data.getAllHooks(context, true);
                Set<String> ids = Data.getBuiltinIds(context);
                List<String> groups = Data.getGroupNames(hooks);
                XHook found = null;
                if (id != null)
                    for (XHook h : hooks)
                        if (id.equals(h.getId()))
                            found = h;
                final XHook hook = found;
                final JSONObject jhook = (hook == null ? null : Data.toEditable(hook));
                runOnUiThread(() -> {
                    builtinIds = ids;
                    etGroup.setSimpleItems(groups.toArray(new String[0]));
                    if (jhook != null) {
                        this.hook = hook;
                        fill(jhook);
                    }
                    updateMenu();
                });
            } catch (Throwable ex) {
                error(TAG, ex);
            }
        });
    }

    private void fill(JSONObject jhook) {
        etCollection.setText(jhook.optString("collection"));
        etGroup.setText(jhook.optString("group"), false);
        etName.setText(jhook.optString("name"));
        etAuthor.setText(jhook.optString("author"));
        etVersion.setText(Integer.toString(jhook.optInt("version", 0)));
        etDescription.setText(jhook.optString("description", ""));
        etClass.setText(jhook.optString("className"));
        etMethod.setText(jhook.optString("methodName", ""));
        etParams.setText(join(jhook.optJSONArray("parameterTypes")));
        etReturn.setText(jhook.optString("returnType", ""));
        etMinSdk.setText(Integer.toString(jhook.optInt("minSdk", 1)));
        etMaxSdk.setText(Integer.toString(jhook.optInt("maxSdk", 999)));
        etMinApk.setText(jhook.optInt("minApk", 0) == 0 ? "" : Integer.toString(jhook.optInt("minApk")));
        etMaxApk.setText(jhook.optInt("maxApk", Integer.MAX_VALUE) == Integer.MAX_VALUE ? "" : Integer.toString(jhook.optInt("maxApk")));
        etExclude.setText(jhook.optString("excludePackages", ""));
        etSettings.setText(join(jhook.optJSONArray("settings")));
        swEnabled.setChecked(jhook.optBoolean("enabled", true));
        swOptional.setChecked(jhook.optBoolean("optional", false));
        swUsage.setChecked(jhook.optBoolean("usage", true));
        swNotify.setChecked(jhook.optBoolean("notify", false));
        etScript.setText(jhook.optString("luaScript"));
        highlighter.highlight();
    }

    private static String join(JSONArray jarray) {
        if (jarray == null)
            return "";
        List<String> items = new ArrayList<>();
        for (int i = 0; i < jarray.length(); i++)
            items.add(jarray.optString(i));
        return TextUtils.join(", ", items);
    }

    private static JSONArray split(EditText et) {
        JSONArray jarray = new JSONArray();
        for (String item : et.getText().toString().split(","))
            if (!item.trim().isEmpty())
                jarray.put(item.trim());
        return jarray;
    }

    private static String text(EditText et) {
        return et.getText().toString().trim();
    }

    // Shows the error at the field
    private Integer number(int tilId, int empty) {
        TextInputLayout til = findViewById(tilId);
        EditText et = til.getEditText();
        til.setError(null);
        String value = text(et);
        if (value.isEmpty())
            return empty;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            til.setError(getString(R.string.msg_invalid, value));
            et.requestFocus();
            return null;
        }
    }

    // The definition as entered, null with an error shown at a field
    private JSONObject getDefinition() throws Exception {
        Integer version = number(R.id.tilVersion, 0);
        Integer minSdk = number(R.id.tilMinSdk, 1);
        Integer maxSdk = number(R.id.tilMaxSdk, 999);
        Integer minApk = number(R.id.tilMinApk, 0);
        Integer maxApk = number(R.id.tilMaxApk, Integer.MAX_VALUE);
        if (version == null || minSdk == null || maxSdk == null || minApk == null || maxApk == null)
            return null;

        JSONObject jhook = new JSONObject();
        jhook.put("collection", text(etCollection));
        jhook.put("group", text(etGroup));
        jhook.put("name", text(etName));
        jhook.put("author", text(etAuthor));
        jhook.put("version", version);
        if (!text(etDescription).isEmpty())
            jhook.put("description", etDescription.getText().toString());
        jhook.put("className", text(etClass));
        if (!text(etMethod).isEmpty())
            jhook.put("methodName", text(etMethod));
        jhook.put("parameterTypes", split(etParams));
        if (!text(etReturn).isEmpty())
            jhook.put("returnType", text(etReturn));
        jhook.put("minSdk", minSdk);
        jhook.put("maxSdk", maxSdk);
        jhook.put("minApk", minApk);
        jhook.put("maxApk", maxApk);
        if (!text(etExclude).isEmpty())
            jhook.put("excludePackages", text(etExclude).replaceAll("\\s+", ""));
        jhook.put("enabled", swEnabled.isChecked());
        jhook.put("optional", swOptional.isChecked());
        jhook.put("usage", swUsage.isChecked());
        jhook.put("notify", swNotify.isChecked());
        jhook.put("luaScript", etScript.getText().toString());
        JSONArray settings = split(etSettings);
        if (settings.length() > 0)
            jhook.put("settings", settings);
        return jhook;
    }

    private boolean onMenu(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_save)
            save(null);
        else if (id == R.id.menu_enable)
            save(!swEnabled.isChecked());
        else if (id == R.id.menu_delete)
            delete();
        else if (id == R.id.menu_export)
            exportLauncher.launch(text(etName).replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
        return true;
    }

    private void updateMenu() {
        boolean overrides = (hook != null && !hook.isBuiltin() && builtinIds != null && builtinIds.contains(hook.getId()));
        MenuItem enable = toolbar.getMenu().findItem(R.id.menu_enable);
        enable.setVisible(hook != null);
        enable.setTitle(swEnabled.isChecked() ? R.string.menu_disable : R.string.menu_enable);
        // Only custom definitions can be deleted; deleting one replacing a built-in restores the built-in
        MenuItem delete = toolbar.getMenu().findItem(R.id.menu_delete);
        delete.setVisible(hook != null && !hook.isBuiltin());
        delete.setTitle(overrides ? R.string.menu_restore_builtin : R.string.menu_delete);
        toolbar.getMenu().findItem(R.id.menu_export).setVisible(hook != null);

        findViewById(R.id.llBuiltin).setVisibility(hook != null && hook.isBuiltin() ? View.VISIBLE : View.GONE);
        if (hook == null)
            toolbar.setSubtitle(null);
        else if (hook.isBuiltin())
            toolbar.setSubtitle(R.string.title_hook_builtin);
        else
            toolbar.setSubtitle(overrides ? R.string.title_hook_overridden : R.string.title_hook_custom);
    }

    private void updateScriptInfo() {
        String script = etScript.getText().toString();
        List<String> functions = new ArrayList<>();
        if (script.matches("(?s).*\\bfunction\\s+before\\s*\\(.*"))
            functions.add("before()");
        if (script.matches("(?s).*\\bfunction\\s+after\\s*\\(.*"))
            functions.add("after()");
        tvScriptInfo.setText(functions.isEmpty()
                ? getString(R.string.title_script_no_function)
                : TextUtils.join(" · ", functions));
    }

    private void save(final Boolean enabled) {
        final JSONObject jhook;
        try {
            jhook = getDefinition();
            if (jhook == null)
                return;
            if (enabled != null)
                jhook.put("enabled", enabled);
        } catch (Throwable ex) {
            Ui.message(this, getString(R.string.msg_error, ex.getMessage()));
            return;
        }

        final Context context = getApplicationContext();
        executor.submit(() -> {
            try {
                final XHook saved = Data.putCustom(context, jhook);
                runOnUiThread(() -> {
                    hook = saved;
                    swEnabled.setChecked(saved.isEnabled());
                    toolbar.setTitle(saved.getId());
                    updateMenu();
                });
                Ui.message(this, getString(R.string.msg_hook_saved));
            } catch (Throwable ex) {
                error(TAG, ex);
            }
        });
    }

    private void delete() {
        final String id = hook.getId();
        Ui.areYouSure(this, getString(R.string.msg_delete_sure, id), () -> {
            final Context context = getApplicationContext();
            executor.submit(() -> {
                try {
                    Data.putHook(context, id, null);
                    runOnUiThread(this::finish);
                } catch (Throwable ex) {
                    error(TAG, ex);
                }
            });
        });
    }

    private void export(final Uri uri) {
        final JSONObject jhook;
        try {
            jhook = getDefinition();
            if (jhook == null)
                return;
        } catch (Throwable ex) {
            Ui.message(this, getString(R.string.msg_error, ex.getMessage()));
            return;
        }
        final Context context = getApplicationContext();
        executor.submit(() -> {
            try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                os.write(jhook.toString(2).replace("\\/", "/").getBytes(StandardCharsets.UTF_8));
                Ui.message(this, getString(R.string.msg_hooks_exported, 1));
            } catch (Throwable ex) {
                error(TAG, ex);
            }
        });
    }

    // Resolve the target in this app, like the hook engine does in an app,
    // which has the framework classes, but not the classes of other apps
    private void check() {
        final String className = text(etClass);
        final String member = text(etMethod);
        final JSONArray params = split(etParams);
        final ClassLoader loader = getClassLoader();
        tvCheck.setText(R.string.title_checking);
        executor.submit(() -> {
            String result;
            try {
                Class<?> cls = XResolver.resolveClass(className, loader);
                if (member.contains(":"))
                    result = getString(R.string.title_check_class, cls.getName());
                else if (member.startsWith("#"))
                    result = getString(R.string.title_check_found,
                            XResolver.resolveField(cls, member.substring(1), null).toString());
                else {
                    Class<?>[] types = new Class<?>[params.length()];
                    for (int i = 0; i < types.length; i++)
                        types[i] = XResolver.resolveClass(params.getString(i), loader);
                    result = getString(R.string.title_check_found,
                            XResolver.resolveMember(cls, member.isEmpty() ? null : member, types).toString());
                }
            } catch (NoSuchFieldException | NoSuchMethodException ex) {
                result = getString(R.string.title_check_no_member, member.isEmpty() ? "<init>" : member);
            } catch (ClassNotFoundException | NoClassDefFoundError ex) {
                result = getString(R.string.title_check_no_class, className);
            } catch (Throwable ex) {
                result = getString(R.string.msg_error, ex.toString());
            }
            final String text = result;
            runOnUiThread(() -> tvCheck.setText(text));
        });
    }
}
