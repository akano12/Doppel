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

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Fake values read by the hook scripts (param:getSetting), for all apps or for a single app
public class ActivityValues extends ActivityBase {
    private static final String TAG = "XLua.Values";

    static final String EXTRA_PACKAGE = "package";
    static final String EXTRA_LABEL = "label";

    private static final String GLOBAL = "global";

    private enum Type {TEXT, NUMBER, TIMEZONE, LOCATION, CONTACTS}

    private enum Random {NONE, SERIAL, ANDROID_ID, IMEI, MEID}

    private static class Field {
        final String key;
        final int title;
        final Type type;
        final Random random;
        final String builtin; // Value used by the script when there is no setting
        final boolean globalOnly;

        Field(String key, int title, Type type, Random random, String builtin, boolean globalOnly) {
            this.key = key;
            this.title = title;
            this.type = type;
            this.random = random;
            this.builtin = builtin;
            this.globalOnly = globalOnly;
        }
    }

    // Settings used by the scripts in assets, with their built-in defaults
    private static final Object[] SECTIONS = new Object[]{
            R.string.title_values_identifiers,
            new Field("value.serial", R.string.title_value_serial, Type.TEXT, Random.SERIAL, "unknown", false),
            new Field("value.android_id", R.string.title_value_android_id, Type.TEXT, Random.ANDROID_ID, "0000000000000000", false),
            new Field("value.imei", R.string.title_value_imei, Type.TEXT, Random.IMEI, null, false),
            new Field("value.meid", R.string.title_value_meid, Type.TEXT, Random.MEID, null, false),
            new Field("value.phone_number", R.string.title_value_phone_number, Type.TEXT, Random.NONE, null, false),
            new Field("value.email", R.string.title_value_email, Type.TEXT, Random.NONE, "private@lua.xprivacy.eu", false),
            R.string.title_values_location,
            new Field("location.type", R.string.title_value_location_type, Type.LOCATION, Random.NONE, null, false),
            new Field("location.latitude", R.string.title_value_latitude, Type.NUMBER, Random.NONE, "0", false),
            new Field("location.longitude", R.string.title_value_longitude, Type.NUMBER, Random.NONE, "0", false),
            new Field("location.accuracy", R.string.title_value_accuracy, Type.NUMBER, Random.NONE, null, false),
            R.string.title_values_other,
            new Field("value.timezone", R.string.title_value_timezone, Type.TIMEZONE, Random.NONE, "UTC", false),
            new Field("features.hide", R.string.title_value_features, Type.TEXT, Random.NONE, null, false),
            new Field("contacts.starred", R.string.title_value_contacts, Type.CONTACTS, Random.NONE, null, false),
            new Field("collection", R.string.title_value_collection, Type.TEXT, Random.NONE, "Privacy", true),
    };

    // Spinner positions to setting values
    private static final String[] LOCATION_VALUES = new String[]{null, "set", "coarse"};
    private static final String[] CONTACTS_VALUES = new String[]{null, "1", "0"};

    private String category;
    private final Map<String, String> values = new HashMap<>();
    private final Map<String, String> globals = new HashMap<>();
    private final Map<String, View> inputs = new HashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.values);

        category = getIntent().getStringExtra(EXTRA_PACKAGE);
        if (category == null)
            category = GLOBAL;
        String label = getIntent().getStringExtra(EXTRA_LABEL);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(GLOBAL.equals(category)
                ? getString(R.string.title_values_global)
                : getString(R.string.title_values_app, label == null ? category : label));

        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final Map<String, String> v = XProvider.getSettings(ActivityValues.this, category);
                    final Map<String, String> g = (GLOBAL.equals(category)
                            ? new HashMap<String, String>()
                            : XProvider.getSettings(ActivityValues.this, GLOBAL));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            values.putAll(v);
                            globals.putAll(g);
                            buildForm();
                        }
                    });
                } catch (final Throwable ex) {
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

    private void buildForm() {
        LinearLayout container = findViewById(R.id.llValues);
        boolean global = GLOBAL.equals(category);
        for (Object item : SECTIONS) {
            if (item instanceof Integer) {
                TextView section = new TextView(this);
                section.setText((Integer) item);
                section.setTextAppearance(android.R.style.TextAppearance_Medium);
                section.setPadding(0, dp(24), 0, dp(4));
                container.addView(section);
                continue;
            }

            final Field field = (Field) item;
            if (field.globalOnly && !global)
                continue;

            TextView title = new TextView(this);
            title.setText(field.title);
            title.setPadding(0, dp(12), 0, 0);
            container.addView(title);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            container.addView(row);

            final View input;
            String value = values.get(field.key);
            if (field.type == Type.LOCATION || field.type == Type.CONTACTS) {
                Spinner spinner = new Spinner(this);
                ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                        field.type == Type.LOCATION ? R.array.location_types : R.array.contacts_types,
                        android.R.layout.simple_spinner_item);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinner.setAdapter(adapter);
                spinner.setSelection(Math.max(0, Arrays.asList(spinnerValues(field)).indexOf(value)));
                input = spinner;
            } else {
                EditText edit;
                if (field.type == Type.TIMEZONE) {
                    AutoCompleteTextView auto = new AutoCompleteTextView(this);
                    auto.setAdapter(new ArrayAdapter<>(this,
                            android.R.layout.simple_dropdown_item_1line, TimeZone.getAvailableIDs()));
                    auto.setThreshold(1);
                    edit = auto;
                } else
                    edit = new EditText(this);
                edit.setSingleLine(true);
                if (field.type == Type.NUMBER)
                    edit.setInputType(InputType.TYPE_CLASS_NUMBER |
                            InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
                edit.setText(value);

                // Show what applies when the field is empty
                String fallback = globals.get(field.key);
                if (fallback != null)
                    edit.setHint(getString(R.string.title_global, fallback));
                else if (field.builtin != null)
                    edit.setHint(getString(R.string.title_default, field.builtin));
                input = edit;
            }
            row.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            inputs.put(field.key, input);

            if (field.random != Random.NONE) {
                Button button = new Button(this);
                button.setText(R.string.title_randomize);
                button.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        ((EditText) input).setText(randomValue(field.random));
                    }
                });
                row.addView(button);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.values, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.menu_save) {
            save();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void save() {
        final Map<String, String> changed = new HashMap<>();
        for (Object item : SECTIONS) {
            if (!(item instanceof Field))
                continue;
            Field field = (Field) item;
            View input = inputs.get(field.key);
            if (input == null)
                continue;

            String value;
            if (input instanceof Spinner)
                value = spinnerValues(field)[((Spinner) input).getSelectedItemPosition()];
            else {
                value = ((EditText) input).getText().toString().trim();
                if (TextUtils.isEmpty(value))
                    value = null;
            }

            if (value != null && !isValid(field, value)) {
                ((EditText) input).setError(getString(R.string.msg_invalid, value));
                input.requestFocus();
                return;
            }

            if (!Objects.equals(value, values.get(field.key)))
                changed.put(field.key, value);
        }

        if (changed.isEmpty()) {
            finish();
            return;
        }

        final boolean global = GLOBAL.equals(category);
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    List<String> keys = new ArrayList<>(changed.keySet());
                    for (int i = 0; i < keys.size(); i++) {
                        // Stop the app once, so it starts with the new values
                        boolean kill = (!global && i == keys.size() - 1);
                        XProvider.putSetting(ActivityValues.this, category,
                                keys.get(i), changed.get(keys.get(i)), kill);
                    }
                    values.putAll(changed);
                    showMessage(getString(global ? R.string.msg_saved_restart : R.string.msg_saved));
                } catch (Throwable ex) {
                    Log.e(TAG, Log.getStackTraceString(ex));
                    showMessage(getString(R.string.msg_error, ex.toString()));
                }
            }
        });
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
