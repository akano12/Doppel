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
            new Field("location.accuracy", R.string.title_value_accuracy, Type.NUMBER, Random.NONE, null),
            R.string.title_values_other,
            new Field("value.timezone", R.string.title_value_timezone, Type.TIMEZONE, Random.NONE, "UTC"),
            new Field("features.hide", R.string.title_value_features, Type.TEXT, Random.NONE, null),
            new Field("contacts.starred", R.string.title_value_contacts, Type.CONTACTS, Random.NONE, null),
    };

    // Built-in fields and the settings of custom definitions
    private final List<Object> form = new ArrayList<>(Arrays.asList(SECTIONS));

    // Dropdown positions to setting values
    private static final String[] LOCATION_VALUES = new String[]{null, "set", "coarse"};
    private static final String[] CONTACTS_VALUES = new String[]{null, "1", "0"};

    private String packageName; // null for the values of all apps
    private String label;
    private String category;

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, String> globals = new HashMap<>();
    private final Map<String, TextInputLayout> inputs = new HashMap<>();
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

        setupToolbar(getString(R.string.menu_values));

        // Values of an app replace the values for all apps
        MaterialButtonToggleGroup tgScope = findViewById(R.id.tgScope);
        if (packageName != null) {
            ((Button) findViewById(R.id.btnScopeApp)).setText(label);
            tgScope.setVisibility(View.VISIBLE);
            tgScope.check(R.id.btnScopeApp);
            tgScope.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked)
                    return;
                category = (checkedId == R.id.btnScopeApp ? packageName : GLOBAL);
                load();
            });
        }

        findViewById(R.id.btnSave).setOnClickListener(view -> save());
        findViewById(R.id.btnReset).setOnClickListener(view -> reset());

        load();
    }

    @Override
    public View getMessageAnchor() {
        return findViewById(R.id.bar);
    }

    private void load() {
        final String category = this.category;
        final Context context = getApplicationContext();
        executor.submit(() -> {
            try {
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
                });
            } catch (Throwable ex) {
                error(TAG, ex);
            }
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
                int index = Math.max(0, Arrays.asList(spinnerValues(field)).indexOf(value));
                dropdown.setText(titles[index], false);
            } else {
                if (field.type == Type.TIMEZONE)
                    ((MaterialAutoCompleteTextView) input).setSimpleItems(TimeZone.getAvailableIDs());
                if (field.type == Type.NUMBER)
                    input.setInputType(InputType.TYPE_CLASS_NUMBER |
                            InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
                input.setText(value);

                // Show what applies when the field is empty
                String fallback = globals.get(field.key);
                if (fallback != null)
                    til.setHelperText(getString(R.string.title_global, fallback));
                else if (field.builtin != null)
                    til.setHelperText(getString(R.string.title_default, field.builtin));

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
            return spinnerValues(field)[index];
        }
        String value = input.getText().toString().trim();
        return (TextUtils.isEmpty(value) ? null : value);
    }

    private void save() {
        final Map<String, String> changed = new HashMap<>();
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
            if (!Objects.equals(value, values.get(field.key)))
                changed.put(field.key, value);
        }

        if (changed.isEmpty()) {
            Ui.message(this, getString(R.string.msg_no_changes));
            return;
        }

        final String category = this.category;
        final boolean global = GLOBAL.equals(category);
        final Context context = getApplicationContext();
        executor.submit(() -> {
            try {
                List<String> keys = new ArrayList<>(changed.keySet());
                for (int i = 0; i < keys.size(); i++) {
                    // Stop the app once, so it starts with the new values
                    boolean kill = (!global && i == keys.size() - 1);
                    Data.putSetting(context, category, keys.get(i), changed.get(keys.get(i)), kill);
                }
                runOnUiThread(() -> values.putAll(changed));
                Ui.message(this, getString(global ? R.string.msg_saved_restart : R.string.msg_saved));
            } catch (Throwable ex) {
                error(TAG, ex);
            }
        });
    }

    private static boolean isDropdown(Field field) {
        return (field.type == Type.LOCATION || field.type == Type.CONTACTS);
    }

    private String[] getDropdownTitles(Field field) {
        return getResources().getStringArray(field.type == Type.LOCATION ? R.array.location_types : R.array.contacts_types);
    }

    private static String[] spinnerValues(Field field) {
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
