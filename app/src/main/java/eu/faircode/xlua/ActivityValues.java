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

import com.google.android.material.button.MaterialButtonToggleGroup;
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

// Fake values read by the hook scripts (param:getSetting), for all apps or for a single app
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

    private enum Random {NONE, SERIAL, ANDROID_ID, IMEI, MEID}

    private static class Field {
        final String key;
        final int title; // 0 for settings of custom definitions, which show the key
        final Type type;
        final Random random;
        final String builtin; // Value used by the script when there is no setting

        Field(String key, int title, Type type, Random random, String builtin) {
            this.key = key;
            this.title = title;
            this.type = type;
            this.random = random;
            this.builtin = builtin;
        }
    }

    // Settings used by the scripts in assets, with their built-in defaults
    private static final Object[] SECTIONS = new Object[]{
            R.string.title_values_identifiers,
            new Field("value.serial", R.string.title_value_serial, Type.TEXT, Random.SERIAL, "unknown"),
            new Field("value.android_id", R.string.title_value_android_id, Type.TEXT, Random.ANDROID_ID, "0000000000000000"),
            new Field("value.imei", R.string.title_value_imei, Type.TEXT, Random.IMEI, null),
            new Field("value.meid", R.string.title_value_meid, Type.TEXT, Random.MEID, null),
            new Field("value.phone_number", R.string.title_value_phone_number, Type.TEXT, Random.NONE, null),
            new Field("value.email", R.string.title_value_email, Type.TEXT, Random.NONE, "private@lua.xprivacy.eu"),
            R.string.title_values_location,
            new Field("location.type", R.string.title_value_location_type, Type.LOCATION, Random.NONE, null),
            new Field("location.latitude", R.string.title_value_latitude, Type.NUMBER, Random.NONE, "0"),
            new Field("location.longitude", R.string.title_value_longitude, Type.NUMBER, Random.NONE, "0"),
            new Field("location.accuracy", R.string.title_value_accuracy, Type.NUMBER, Random.NONE, "10000"),
            R.string.title_values_other,
            new Field("value.timezone", R.string.title_value_timezone, Type.TIMEZONE, Random.NONE, "UTC"),
            new Field("features.hide", R.string.title_value_features, Type.TEXT, Random.NONE, null),
            new Field("contacts.starred", R.string.title_value_contacts, Type.CONTACTS, Random.NONE, null),
    };

    // Built-in fields and the settings of custom definitions, see load
    private final List<Object> form = new ArrayList<>();

    // Dropdown positions to setting values
    private static final String[] LOCATION_VALUES = new String[]{null, "set", "coarse"};
    private static final String[] CONTACTS_VALUES = new String[]{null, "1", "0"};

    private String packageName; // null for the values of all apps
    private String label;
    private String category;

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, String> globals = new HashMap<>();
    private final Map<String, TextInputLayout> inputs = new HashMap<>();
    private Map<String, String> restored = null; // Entered before a recreation, until the form is built
    private final SecureRandom random = new SecureRandom();

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

        setupToolbar(getString(R.string.menu_values));

        // Values of an app replace the values for all apps
        MaterialButtonToggleGroup tgScope = findViewById(R.id.tgScope);
        if (packageName != null) {
            ((Button) findViewById(R.id.btnScopeApp)).setText(label);
            tgScope.setVisibility(View.VISIBLE);
            tgScope.check(GLOBAL.equals(category) ? R.id.btnScopeGlobal : R.id.btnScopeApp);
            tgScope.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                String selected = (checkedId == R.id.btnScopeApp ? packageName : GLOBAL);
                if (!isChecked || selected.equals(category))
                    return;
                Runnable change = () -> {
                    category = selected;
                    restored = null;
                    load();
                };
                // Switching discards changes which are not saved, keeping them selects the values shown again
                if (getChanges().isEmpty())
                    change.run();
                else
                    confirmDiscard(change, () ->
                            group.check(GLOBAL.equals(category) ? R.id.btnScopeGlobal : R.id.btnScopeApp));
            });
        }

        findViewById(R.id.btnSave).setOnClickListener(view -> save());
        findViewById(R.id.btnReset).setOnClickListener(view -> reset());
        confirmLeaving(() -> !getChanges().isEmpty());

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
        return findViewById(R.id.bar);
    }

    private void load() {
        final String category = this.category;
        final Context context = getApplicationContext();
        run(TAG, () -> {
            final Map<String, String> v = Data.getSettings(context, category);
            final Map<String, String> g = (GLOBAL.equals(category)
                    ? new HashMap<>()
                    : Data.getSettings(context, GLOBAL));

            // Settings declared by custom definitions
            Set<String> known = new HashSet<>();
            for (Object item : SECTIONS)
                if (item instanceof Field)
                    known.add(((Field) item).key);
            TreeSet<String> custom = new TreeSet<>();
            for (XHook hook : Data.getHooks(context))
                for (String name : hook.getSettings())
                    if (!known.contains(name))
                        custom.add(name);

            runOnUiThread(() -> {
                values.clear();
                values.putAll(v);
                globals.clear();
                globals.putAll(g);
                form.clear();
                form.addAll(Arrays.asList(SECTIONS));
                if (!custom.isEmpty()) {
                    form.add(R.string.title_values_custom);
                    for (String name : custom)
                        form.add(new Field(name, 0, Type.TEXT, Random.NONE, null));
                }
                buildForm();

                // Values entered for this scope before a recreation
                if (restored != null && category.equals(this.category)) {
                    for (Map.Entry<String, String> entry : restored.entrySet())
                        setEntered(entry.getKey(), entry.getValue());
                    restored = null;
                }
            });
        });
    }

    private void buildForm() {
        boolean global = GLOBAL.equals(category);
        ((TextView) findViewById(R.id.tvHint)).setText(global
                ? getString(R.string.title_values_global_hint)
                : getString(R.string.title_values_app_hint, label));

        LinearLayout container = findViewById(R.id.llValues);
        container.removeAllViews();
        inputs.clear();
        LayoutInflater inflater = LayoutInflater.from(this);
        LinearLayout rows = null;
        for (Object item : form) {
            if (item instanceof Integer) {
                View card = inflater.inflate(R.layout.item_card, container, false);
                ((TextView) card.findViewById(R.id.tvSection)).setText((Integer) item);
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

                if (field.random != Random.NONE) {
                    til.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
                    til.setEndIconDrawable(R.drawable.ic_shuffle);
                    til.setEndIconContentDescription(getString(R.string.title_randomize_field, getString(field.title)));
                    til.setEndIconOnClickListener(view -> input.setText(randomValue(field.random)));
                }
            }

            rows.addView(til);
            inputs.put(field.key, til);
        }
        updateLocationHints();
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

    // Like entered by the user, an empty field for null
    private void setEntered(String key, String value) {
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

    // Empty fields remove the values, after saving
    private void reset() {
        for (Object item : form)
            if (item instanceof Field) {
                Field field = (Field) item;
                TextInputLayout til = inputs.get(field.key);
                EditText input = (til == null ? null : til.getEditText());
                if (input == null)
                    continue;
                if (isDropdown(field))
                    ((MaterialAutoCompleteTextView) input).setText(getDropdownTitles(field)[0], false);
                else
                    input.setText(null);
            }
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
            String value = getValue(field, til.getEditText());
            if (!Objects.equals(value, values.get(field.key)))
                changed.put(field.key, value);
        }
        return changed;
    }

    private void save() {
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
            runOnUiThread(() -> values.putAll(changed));
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

    private String randomValue(Random type) {
        switch (type) {
            case SERIAL:
                return randomString("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", 12);
            case ANDROID_ID:
                return randomString("0123456789abcdef", 16);
            case IMEI:
                // 14 digits and a Luhn check digit
                String digits = randomString("0123456789", 14);
                return digits + luhn(digits);
            case MEID:
                return randomString("0123456789ABCDEF", 14);
            default:
                return null;
        }
    }

    private String randomString(String alphabet, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++)
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return sb.toString();
    }

    static int luhn(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 0) {
                d *= 2;
                if (d > 9)
                    d -= 9;
            }
            sum += d;
        }
        return (10 - sum % 10) % 10;
    }
}
