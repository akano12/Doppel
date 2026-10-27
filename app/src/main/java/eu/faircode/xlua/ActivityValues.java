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
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

// Fake values read by the hook scripts (param:getSetting), for all apps or for a single app;
// each value tells which group reads it and whether that group is restricted
public class ActivityValues extends ActivityBase implements Ui.Anchored {
    private static final String TAG = "XLua.Values";

    private static final String EXTRA_PACKAGE = "package";
    private static final String EXTRA_LABEL = "label";

    private static final String GLOBAL = "global";

    // A recreation, like for another theme, keeps the scope and the values entered, see onSaveInstanceState
    private static final String STATE_CATEGORY = "category";
    private static final String STATE_KEYS = "keys";
    private static final String STATE_VALUES = "values";

    private enum Type {TEXT, NUMBER, TIMEZONE, LOCATION, CONTACTS}

    private static class Field {
        final String key;
        final int title; // 0 for settings of custom definitions, which show the key
        final Type type;
        final String builtin; // Value used by the script when there is no setting

        Field(String key, int title, Type type, String builtin) {
            this.key = key;
            this.title = title;
            this.type = type;
            this.builtin = builtin;
        }
    }

    // How an identifier gets its value, see FakeIds: the choice and what applies when the field is not shown
    private static class Mode {
        Field field;
        MaterialButtonToggleGroup group;
        View row; // The field of the fixed value
        View box;
        TextView tvBox;
        TextView tvBoxValue;
        MaterialCheckBox cbAll;
    }

    // An app without a mode of its own uses the one of all apps
    private static final String INHERIT = "inherit";

    // Settings used by the scripts in assets, with their built-in defaults
    private static final Object[] SECTIONS = new Object[]{
            R.string.title_values_identifiers,
            new Field("value.serial", R.string.title_value_serial, Type.TEXT, "unknown"),
            new Field("value.android_id", R.string.title_value_android_id, Type.TEXT, "0000000000000000"),
            new Field("value.imei", R.string.title_value_imei, Type.TEXT, null),
            new Field("value.meid", R.string.title_value_meid, Type.TEXT, null),
            new Field("value.phone_number", R.string.title_value_phone_number, Type.TEXT, null),
            new Field("value.email", R.string.title_value_email, Type.TEXT, "private@example.com"),
            R.string.title_values_location,
            new Field("location.type", R.string.title_values_location, Type.LOCATION, null),
            new Field("location.latitude", R.string.title_value_latitude, Type.NUMBER, "0"),
            new Field("location.longitude", R.string.title_value_longitude, Type.NUMBER, "0"),
            new Field("location.accuracy", R.string.title_value_accuracy, Type.NUMBER, "10000"),
            R.string.section_other,
            new Field("value.timezone", R.string.title_value_timezone, Type.TIMEZONE, "UTC"),
            new Field("features.keep", R.string.title_value_features, Type.TEXT, null),
            new Field("contacts.starred", R.string.title_value_contacts, Type.CONTACTS, null),
            new Field("commands.allowed", R.string.title_value_commands, Type.TEXT, null),
            new Field("commands.regex", R.string.title_value_commands_regex, Type.TEXT, null),
    };

    // Built-in fields and the settings of custom definitions, see load
    private final List<Object> form = new ArrayList<>();

    // Dropdown positions to setting values
    private static final String[] LOCATION_VALUES = new String[]{null, "set", "coarse"};
    private static final String[] CONTACTS_VALUES = new String[]{null, "1", "0"};

    private ToolbarSummary toolbarSummary;
    private String packageName; // null for the values of all apps
    private String label;
    private String category;

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, String> globals = new HashMap<>();
    private final Map<String, TextInputLayout> inputs = new HashMap<>();
    private final Map<String, Mode> modes = new HashMap<>();
    private final Map<String, String> bootValues = new HashMap<>(); // Of the app, see FakeIds
    private Map<String, String> restored = null; // Entered for the scope, until its form is built
    private final SecureRandom random = new SecureRandom();

    // The groups which read a value, by its key, and whether they restrict the app, or how many apps they restrict
    private final Map<String, List<String>> readers = new HashMap<>();
    private final Set<String> restricted = new HashSet<>();
    private final Map<String, Integer> restricting = new HashMap<>();
    private final List<View> hints = new ArrayList<>(); // Under the values, see bindHints
    private XApp app = null; // The app of the values, when they are of one app
    private List<XHook> hooks = new ArrayList<>();
    private List<String> collection = new ArrayList<>();

    static Intent getIntent(Context context, String packageName, String label) {
        Intent intent = new Intent(context, ActivityValues.class);
        intent.putExtra(EXTRA_PACKAGE, packageName);
        intent.putExtra(EXTRA_LABEL, label);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_values);

        packageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (label == null)
            label = packageName;
        category = (packageName == null ? GLOBAL : packageName);

        // The values entered are put back in the form when it is built, see load
        if (savedInstanceState != null) {
            category = savedInstanceState.getString(STATE_CATEGORY, category);
            String[] keys = savedInstanceState.getStringArray(STATE_KEYS);
            String[] entered = savedInstanceState.getStringArray(STATE_VALUES);
            if (keys != null && entered != null && keys.length == entered.length) {
                restored = new HashMap<>();
                for (int i = 0; i < keys.length; i++)
                    restored.put(keys[i], entered[i]);
            }
        }

        toolbarSummary = new ToolbarSummary(setupToolbar(getString(R.string.title_values)),
                findViewById(R.id.svValues), findViewById(R.id.tvHint));

        // Values of an app replace the values for all apps
        ChipGroup cgScope = findViewById(R.id.cgScope);
        if (packageName != null) {
            ((Chip) findViewById(R.id.chipScopeApp)).setText(label);
            cgScope.setVisibility(View.VISIBLE);
            cgScope.check(GLOBAL.equals(category) ? R.id.chipScopeGlobal : R.id.chipScopeApp);
            cgScope.setOnCheckedStateChangeListener((group, checkedIds) -> {
                if (checkedIds.isEmpty())
                    return;
                String selected = (checkedIds.get(0) == R.id.chipScopeApp ? packageName : GLOBAL);
                if (selected.equals(category))
                    return;
                // Nothing is entered for the other scope yet; the form shows the old scope until it is built,
                // its discarded values should not be saved for the other scope on a recreation meanwhile
                Runnable change = () -> {
                    category = selected;
                    restored = new HashMap<>();
                    load();
                };
                // Switching discards changes which are not saved, keeping them selects the values shown again
                if (!hasChanges())
                    change.run();
                else
                    confirmDiscard(change, () ->
                            group.check(GLOBAL.equals(category) ? R.id.chipScopeGlobal : R.id.chipScopeApp));
            });
        }

        findViewById(R.id.btnSave).setOnClickListener(view -> save());
        findViewById(R.id.btnDiscard).setOnClickListener(view -> {
            buildForm();
            updateBar();
        });
        confirmLeaving(this::hasChanges);

        load();
    }

    // Empty fields are kept too, as null values, they remove a value when saved
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Map<String, String> changes = (restored == null ? getChanges() : restored);
        outState.putString(STATE_CATEGORY, category);
        List<String> keys = new ArrayList<>(changes.keySet());
        String[] entered = new String[keys.size()];
        for (int i = 0; i < entered.length; i++)
            entered[i] = changes.get(keys.get(i));
        outState.putStringArray(STATE_KEYS, keys.toArray(new String[0]));
        outState.putStringArray(STATE_VALUES, entered);
    }

    @Override
    public View getMessageAnchor() {
        View bar = findViewById(R.id.bar);
        return (bar.getVisibility() == View.VISIBLE ? bar : null);
    }

    // The form cannot be saved until it is built (see save), so a failed load is retried
    private void load() {
        final String category = this.category;
        final boolean global = GLOBAL.equals(category);
        final Context context = getApplicationContext();
        run(TAG, () -> {
            final Map<String, String> v = Data.getSettings(context, category);
            final Map<String, String> g = (global ? new HashMap<>() : Data.getSettings(context, GLOBAL));
            final List<XHook> hooks = Data.getHooks(context);
            final List<String> collection = Data.getCollection(context);

            // Settings declared by custom definitions, and the groups which read each setting
            Set<String> known = new HashSet<>();
            for (Object item : SECTIONS)
                if (item instanceof Field)
                    known.add(((Field) item).key);
            TreeSet<String> custom = new TreeSet<>();
            final Map<String, List<String>> readers = new HashMap<>();
            for (XHook hook : hooks)
                for (String name : hook.getSettings()) {
                    if (!known.contains(name))
                        custom.add(name);
                    if (!readers.containsKey(name))
                        readers.put(name, new ArrayList<>());
                    if (!readers.get(name).contains(hook.getGroup()))
                        readers.get(name).add(hook.getGroup());
                }
            for (List<String> groups : readers.values())
                Groups.sortByTitle(context, groups);

            // Whether the groups restrict the app, or how many apps they restrict
            final Set<String> restricted = new HashSet<>();
            final Map<String, Integer> restricting = new HashMap<>();
            final XApp app;
            if (global) {
                app = null;
                for (XApp other : Data.getApps(context)) {
                    Set<String> groups = new HashSet<>();
                    for (XAssignment assignment : other.assignments)
                        groups.add(assignment.hook.getGroup());
                    for (String group : groups) {
                        Integer count = restricting.get(group);
                        restricting.put(group, count == null ? 1 : count + 1);
                    }
                }
            } else {
                app = Data.getApp(context, category, -1);
                if (app != null)
                    for (XAssignment assignment : app.assignments)
                        restricted.add(assignment.hook.getGroup());
            }
            // What the app gets until the next restart, of the identifiers new each boot
            final Map<String, String> boot = (app == null ? new HashMap<>() : Data.getBootValues(context, category, app.uid));

            runOnUiThread(() -> {
                // The scope was switched meanwhile, its form is built by the load which followed
                if (!category.equals(this.category))
                    return;

                values.clear();
                values.putAll(v);
                globals.clear();
                globals.putAll(g);
                this.hooks = hooks;
                this.collection = collection;
                this.app = app;
                this.bootValues.clear();
                this.bootValues.putAll(boot);
                this.readers.clear();
                this.readers.putAll(readers);
                this.restricted.clear();
                this.restricted.addAll(restricted);
                this.restricting.clear();
                this.restricting.putAll(restricting);
                form.clear();
                form.addAll(Arrays.asList(SECTIONS));
                if (!custom.isEmpty()) {
                    form.add(R.string.title_values_custom);
                    for (String name : custom)
                        form.add(new Field(name, 0, Type.TEXT, null));
                }
                buildForm();

                // Values entered for this scope before a recreation
                if (restored != null) {
                    for (Map.Entry<String, String> entry : restored.entrySet())
                        setEntered(entry.getKey(), entry.getValue());
                    restored = null;
                }
                updateBar();
            });
        }, this::load);
    }

    private void buildForm() {
        boolean global = GLOBAL.equals(category);
        toolbarSummary.setSummary(global ? getString(R.string.title_all_apps) : label);
        ((TextView) findViewById(R.id.tvHint)).setText(global
                ? getString(R.string.title_values_global_hint)
                : getString(R.string.title_values_app_hint, label));

        LinearLayout container = findViewById(R.id.llValues);
        container.removeAllViews();
        inputs.clear();
        modes.clear();
        hints.clear();
        LayoutInflater inflater = LayoutInflater.from(this);
        LinearLayout rows = null;
        for (Object item : form) {
            if (item instanceof Integer) {
                View card = inflater.inflate(R.layout.item_card, container, false);
                TextView tvSection = card.findViewById(R.id.tvSection);
                tvSection.setText((Integer) item);
                ViewCompat.setAccessibilityHeading(tvSection, true);
                rows = card.findViewById(R.id.llRows);
                container.addView(card);
                continue;
            }

            final Field field = (Field) item;
            String value = values.get(field.key);

            int layout;
            if (isDropdown(field))
                layout = R.layout.item_value_dropdown;
            else if (field.type == Type.TIMEZONE)
                layout = R.layout.item_value_auto;
            else
                layout = R.layout.item_value_text;
            TextInputLayout til = (TextInputLayout) inflater.inflate(layout, rows, false);
            if (field.title == 0)
                til.setHint(field.key);
            else
                til.setHint(field.title);
            EditText input = til.findViewById(R.id.etValue);
            boolean id = FakeIds.isId(field.key);
            Mode mode = (id ? new Mode() : null);
            if (id) {
                // The title is above the choice, the field of the value follows
                View view = inflater.inflate(R.layout.item_value_mode, rows, false);
                mode.field = field;
                mode.group = view.findViewById(R.id.tgMode);
                mode.box = view.findViewById(R.id.llBox);
                mode.tvBox = view.findViewById(R.id.tvBox);
                mode.tvBoxValue = view.findViewById(R.id.tvBoxValue);
                mode.cbAll = view.findViewById(R.id.cbAll);
                TextView tvTitle = view.findViewById(R.id.tvTitle);
                tvTitle.setText(field.title);
                til.setHint(null);
                input.setId(View.generateViewId());
                tvTitle.setLabelFor(input.getId());
                view.findViewById(R.id.btnInherit).setVisibility(global ? View.GONE : View.VISIBLE);
                rows.addView(view);
                modes.put(field.key, mode);
            }

            if (isDropdown(field)) {
                String[] titles = getDropdownTitles(field);
                MaterialAutoCompleteTextView dropdown = (MaterialAutoCompleteTextView) input;
                dropdown.setSimpleItems(titles);
                int index = Math.max(0, Arrays.asList(dropdownValues(field)).indexOf(value));
                dropdown.setText(titles[index], false);
                if (field.type == Type.LOCATION)
                    Ui.onTextChanged(dropdown, text -> updateLocationHints());
            } else {
                if (field.type == Type.TIMEZONE)
                    ((MaterialAutoCompleteTextView) input).setSimpleItems(TimeZone.getAvailableIDs());
                if (field.type == Type.NUMBER)
                    input.setInputType(InputType.TYPE_CLASS_NUMBER |
                            InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
                input.setText(value);
                til.setHelperText(getFallbackHint(field));

                // A value can be cleared by itself, an empty field removes the value when saved
                if (field.type != Type.TIMEZONE) {
                    til.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
                    til.setEndIconContentDescription(getString(R.string.title_clear_field,
                            field.title == 0 ? field.key : getString(field.title)));
                }
            }

            if (id) {
                // The randomize button next to the field
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.addView(til, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                MaterialButton shuffle = new MaterialButton(this, null,
                        com.google.android.material.R.attr.materialIconButtonStyle);
                shuffle.setIconResource(R.drawable.ic_shuffle);
                shuffle.setContentDescription(getString(R.string.title_randomize_field, getString(field.title)));
                shuffle.setOnClickListener(view -> input.setText(FakeIds.generate(field.key, random)));
                row.addView(shuffle);
                rows.addView(row);
                mode.row = row;
                initMode(mode);
            } else
                rows.addView(til);
            inputs.put(field.key, til);
            Ui.onTextChanged(input, text -> updateBar());

            // The groups which read the value, under it
            List<String> groups = readers.get(field.key);
            if (groups != null)
                for (String group : groups) {
                    View hint = inflater.inflate(R.layout.item_value_hint, rows, false);
                    hint.setTag(group);
                    rows.addView(hint);
                    hints.add(hint);
                }
        }
        updateLocationHints();
        bindHints();
    }

    // Under each value: the group which reads it and whether it restricts the app, with the action to restrict it,
    // or for all apps how many apps it restricts; a value no group reads has no line
    private void bindHints() {
        boolean global = GLOBAL.equals(category);
        for (View view : hints) {
            final String group = (String) view.getTag();
            String title = Groups.getTitle(this, group);
            TextView tvHint = view.findViewById(R.id.tvHint);
            Button btnRestrict = view.findViewById(R.id.btnRestrict);
            int color;
            if (global) {
                Integer count = restricting.get(group);
                int apps = (count == null ? 0 : count);
                tvHint.setText(apps == 0
                        ? getString(R.string.title_hint_no_apps, title)
                        : getResources().getQuantityString(R.plurals.title_hint_apps, apps, title, apps));
                color = (apps == 0 ? R.attr.colorWarning : R.attr.colorSuccess);
                btnRestrict.setVisibility(View.GONE);
            } else {
                boolean on = restricted.contains(group);
                tvHint.setText(getString(on ? R.string.title_hint_restricted : R.string.title_hint_unrestricted, title));
                color = (on ? R.attr.colorSuccess : R.attr.colorWarning);
                btnRestrict.setVisibility(on || app == null ? View.GONE : View.VISIBLE);
                // Screen readers tell which group the button restricts
                btnRestrict.setContentDescription(getString(R.string.title_restrict) + ", " + title);
                btnRestrict.setOnClickListener(v -> restrict(group));
            }
            view.findViewById(R.id.vDot).setBackgroundTintList(Ui.tint(this, color));
        }
    }

    // All hooks of the group for the app, like the switch of the group on the screen of the app
    private void restrict(String group) {
        final Context context = getApplicationContext();
        final XApp app = this.app;
        final List<XHook> available = Data.getAvailable(hooks, app, collection, group);
        final String title = Groups.getTitle(this, group);
        restricted.add(group);
        bindHints();
        run(TAG, () -> {
            Data.assign(context, app, available, true);
            Ui.message(this, getString(R.string.msg_group_restricted, title, label));
        });
    }

    // What applies when the field is empty
    private String getFallbackHint(Field field) {
        String fallback = globals.get(field.key);
        if (fallback != null)
            return getString(R.string.title_global, fallback);
        else if (field.builtin != null)
            return getString(R.string.title_default, field.builtin);
        else
            return null;
    }

    // The coordinates are used for a fixed location only, the accuracy for a coarse real location only,
    // see location.lua; for an app without its own location type, the type for all apps applies
    private void updateLocationHints() {
        Field typeField = null;
        for (Object item : form)
            if (item instanceof Field && ((Field) item).type == Type.LOCATION)
                typeField = (Field) item;
        TextInputLayout tilType = (typeField == null ? null : inputs.get(typeField.key));
        if (tilType == null)
            return;
        String type = getValue(typeField, tilType.getEditText());
        if (type == null)
            type = globals.get(typeField.key);

        String[] titles = getResources().getStringArray(R.array.location_types);
        for (Object item : form)
            if (item instanceof Field) {
                Field field = (Field) item;
                String needs = getLocationType(field);
                TextInputLayout til = inputs.get(field.key);
                if (needs == null || til == null)
                    continue;
                til.setHelperText(needs.equals(type)
                        ? getFallbackHint(field)
                        : getString(R.string.title_value_used_with, titles[Arrays.asList(LOCATION_VALUES).indexOf(needs)]));
            }
    }

    // The location type which the script uses the value for, null when it is always used
    private static String getLocationType(Field field) {
        switch (field.key) {
            case "location.latitude":
            case "location.longitude":
                return "set";
            case "location.accuracy":
                return "coarse";
            default:
                return null;
        }
    }

    // Like entered by the user, an empty field for null; a mode like chosen
    private void setEntered(String key, String value) {
        if (key.endsWith(FakeIds.MODE)) {
            Mode mode = modes.get(key.substring(0, key.length() - FakeIds.MODE.length()));
            if (mode != null)
                selectMode(mode, value);
            return;
        }
        Field field = null;
        for (Object item : form)
            if (item instanceof Field && ((Field) item).key.equals(key))
                field = (Field) item;
        TextInputLayout til = inputs.get(key);
        EditText input = (til == null ? null : til.getEditText());
        if (field == null || input == null)
            return;
        if (isDropdown(field)) {
            int index = Math.max(0, Arrays.asList(dropdownValues(field)).indexOf(value));
            ((MaterialAutoCompleteTextView) input).setText(getDropdownTitles(field)[index], false);
        } else
            input.setText(value);
    }

    // The mode saved for the scope, of all apps with their default, else of the app, INHERIT for none
    private String getSavedMode(String key) {
        if (GLOBAL.equals(category))
            return FakeIds.getMode(key, values, null);
        String own = FakeIds.getOwnMode(key, values);
        return (own == null ? INHERIT : own);
    }

    private void initMode(Mode mode) {
        selectMode(mode, getSavedMode(mode.field.key));
        mode.group.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) {
                updateMode(mode);
                updateBar();
            }
        });
        mode.cbAll.setOnCheckedChangeListener((button, checked) -> {
            updateMode(mode);
            updateBar();
        });
    }

    private void selectMode(Mode mode, String value) {
        if (value == null || INHERIT.equals(value))
            value = (GLOBAL.equals(category) ? FakeIds.getMode(mode.field.key, null, null) : INHERIT);
        mode.cbAll.setChecked(FakeIds.BOOT_ALL.equals(value));
        mode.group.check(INHERIT.equals(value) ? R.id.btnInherit
                : FakeIds.FIXED.equals(value) ? R.id.btnFixed : R.id.btnBoot);
        updateMode(mode);
    }

    // The mode chosen: INHERIT, FakeIds.FIXED, BOOT or BOOT_ALL
    private static String getMode(Mode mode) {
        int checked = mode.group.getCheckedButtonId();
        if (checked == R.id.btnInherit)
            return INHERIT;
        if (checked == R.id.btnFixed)
            return FakeIds.FIXED;
        return (mode.cbAll.isChecked() ? FakeIds.BOOT_ALL : FakeIds.BOOT);
    }

    // The field for a fixed value, else what applies, with the value the app gets until the next restart when it is
    // known: the one the service makes for what was saved
    private void updateMode(Mode mode) {
        String key = mode.field.key;
        String chosen = getMode(mode);
        boolean fixed = FakeIds.FIXED.equals(chosen);
        boolean boot = FakeIds.BOOT.equals(chosen) || FakeIds.BOOT_ALL.equals(chosen);
        mode.row.setVisibility(fixed ? View.VISIBLE : View.GONE);
        mode.box.setVisibility(fixed ? View.GONE : View.VISIBLE);
        mode.cbAll.setVisibility(boot ? View.VISIBLE : View.GONE);

        String text;
        String value = null;
        boolean saved = chosen.equals(getSavedMode(key));
        if (INHERIT.equals(chosen)) {
            String global = FakeIds.getMode(key, globals, null);
            if (FakeIds.FIXED.equals(global)) {
                text = getString(R.string.title_box_inherit_fixed);
                value = (globals.get(key) == null ? mode.field.builtin : globals.get(key));
            } else {
                text = getString(FakeIds.BOOT_ALL.equals(global)
                        ? R.string.title_box_inherit_boot_all : R.string.title_box_inherit_boot);
                value = (saved ? bootValues.get(key) : null);
                if (value == null)
                    text = getString(FakeIds.BOOT_ALL.equals(global) ? R.string.title_box_boot_all : R.string.title_box_boot);
            }
        } else {
            text = getString(FakeIds.BOOT_ALL.equals(chosen) ? R.string.title_box_boot_all : R.string.title_box_boot);
            value = (saved ? bootValues.get(key) : null);
            if (value != null)
                text = text + "\n" + getString(R.string.title_box_saved);
        }
        mode.tvBox.setText(text);
        mode.tvBoxValue.setText(value);
        mode.tvBoxValue.setVisibility(value == null ? View.GONE : View.VISIBLE);
    }

    // The bar of the changes, while there are some, with how many values changed
    private void updateBar() {
        if (restored != null)
            return;
        Set<String> changed = new HashSet<>();
        for (String key : getChanges().keySet())
            changed.add(key.endsWith(FakeIds.MODE) ? key.substring(0, key.length() - FakeIds.MODE.length()) : key);
        View bar = findViewById(R.id.bar);
        bar.setVisibility(changed.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.tvChanges)).setText(
                getResources().getQuantityString(R.plurals.title_changes, changed.size(), changed.size()));
    }

    private String getValue(Field field, EditText input) {
        if (isDropdown(field)) {
            String[] titles = getDropdownTitles(field);
            int index = Math.max(0, Arrays.asList(titles).indexOf(input.getText().toString()));
            return dropdownValues(field)[index];
        }
        String value = input.getText().toString().trim();
        return (TextUtils.isEmpty(value) ? null : value);
    }

    // Until the form of the scope is built, the values entered are the ones kept for it
    private boolean hasChanges() {
        return !(restored == null ? getChanges() : restored).isEmpty();
    }

    // Values entered which are not saved, null for an empty field
    private Map<String, String> getChanges() {
        Map<String, String> changed = new HashMap<>();
        for (Object item : form) {
            if (!(item instanceof Field))
                continue;
            Field field = (Field) item;
            TextInputLayout til = inputs.get(field.key);
            if (til == null)
                continue;
            Mode mode = modes.get(field.key);
            String chosen = (mode == null ? null : getMode(mode));
            if (!INHERIT.equals(chosen)) {
                String value = getValue(field, til.getEditText());
                if (!Objects.equals(value, values.get(field.key)))
                    changed.put(field.key, value);
            }
            if (mode != null && !chosen.equals(getSavedMode(field.key))) {
                changed.put(field.key + FakeIds.MODE, INHERIT.equals(chosen) ? null : chosen);
                if (INHERIT.equals(chosen) && values.get(field.key) != null)
                    changed.put(field.key, null);
            }
        }
        return changed;
    }

    private void save() {
        // The form shown is of the scope before a switch, or empty after a recreation, until it is built
        if (restored != null)
            return;
        for (Object item : form) {
            if (!(item instanceof Field))
                continue;
            Field field = (Field) item;
            TextInputLayout til = inputs.get(field.key);
            if (til == null)
                continue;
            EditText input = til.getEditText();
            til.setError(null);
            String value = getValue(field, input);
            if (value != null && !isValid(field, value)) {
                til.setError(getString(R.string.msg_invalid, value));
                input.requestFocus();
                return;
            }
        }

        final Map<String, String> changed = getChanges();
        if (changed.isEmpty()) {
            Ui.message(this, getString(R.string.msg_no_changes));
            return;
        }

        final String category = this.category;
        final boolean global = GLOBAL.equals(category);
        final Context context = getApplicationContext();
        run(TAG, () -> {
            // Like for restrictions, the app is stopped when it is stopped automatically
            XApp app = (global ? null : Data.getApp(context, category, -1));
            boolean stop = (app != null && app.forceStop && !app.persistent);
            List<String> keys = new ArrayList<>(changed.keySet());
            for (int i = 0; i < keys.size(); i++) {
                // Stop the app once, so it starts with the new values
                boolean kill = (stop && i == keys.size() - 1);
                Data.putSetting(context, category, keys.get(i), changed.get(keys.get(i)), kill);
            }
            runOnUiThread(() -> {
                values.putAll(changed);
                load();
            });
            Ui.message(this, getString(stop ? R.string.msg_saved : R.string.msg_saved_restart));
        });
    }

    private static boolean isDropdown(Field field) {
        return (field.type == Type.LOCATION || field.type == Type.CONTACTS);
    }

    // The first item is no value, which for an app means the value for all apps, when there is one
    private String[] getDropdownTitles(Field field) {
        String[] titles = getResources().getStringArray(field.type == Type.LOCATION ? R.array.location_types : R.array.contacts_types);
        int global = Arrays.asList(dropdownValues(field)).indexOf(globals.get(field.key));
        if (global > 0)
            titles[0] = getString(R.string.title_global, titles[global]);
        return titles;
    }

    private static String[] dropdownValues(Field field) {
        return (field.type == Type.LOCATION ? LOCATION_VALUES : CONTACTS_VALUES);
    }

    private static boolean isValid(Field field, String value) {
        try {
            switch (field.key) {
                case "value.timezone":
                    return Arrays.asList(TimeZone.getAvailableIDs()).contains(value);
                case "location.latitude":
                    return Math.abs(Double.parseDouble(value)) <= 90;
                case "location.longitude":
                    return Math.abs(Double.parseDouble(value)) <= 180;
                case "location.accuracy":
                    return Double.parseDouble(value) > 0;
                default:
                    return true;
            }
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
