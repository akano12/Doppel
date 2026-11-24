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
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.DigitsKeyListener;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

// Fake values read by the hook scripts (param:getSetting), for all apps or for a single app;
// each value tells which group reads it and whether that group is restricted
public class ActivityValues extends ActivityBase implements Ui.Anchored {
    private static final String TAG = "XLua.Values";

    private static final String EXTRA_PACKAGE = "package";
    private static final String EXTRA_LABEL = "label";

    private static final String GLOBAL = Data.GLOBAL;

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
    private static final String INHERIT = FakeIds.INHERIT;

    // Settings used by the scripts in assets, with their built-in defaults
    private static final Object[] SECTIONS = new Object[]{
            R.string.title_values_identifiers,
            new Field(FakeIds.SERIAL, R.string.title_value_serial, Type.TEXT, "unknown"),
            // Fixed without a value, the app gets one of its developer which stays, see FakeIds.getBootValues
            new Field(FakeIds.ANDROID_ID, R.string.title_value_android_id, Type.TEXT, null),
            new Field(FakeIds.IMEI, R.string.title_value_imei, Type.TEXT, null),
            new Field(FakeIds.MEID, R.string.title_value_meid, Type.TEXT, null),
            new Field("value.phone_number", R.string.title_value_phone_number, Type.TEXT, null),
            new Field("value.email", R.string.title_value_email, Type.TEXT, "private@example.com"),
            R.string.title_values_location,
            new Field("location.type", R.string.title_values_location, Type.LOCATION, null),
            new Field("location.latitude", R.string.title_value_latitude, Type.NUMBER, "0"),
            new Field("location.longitude", R.string.title_value_longitude, Type.NUMBER, "0"),
            new Field("location.accuracy", R.string.title_value_accuracy, Type.NUMBER, "10000"),
            R.string.section_region,
            new Field("value.timezone", R.string.title_value_timezone, Type.TIMEZONE, "UTC"),
            new Field("value.locale", R.string.title_value_locale, Type.TEXT, "en-US"),
            R.string.section_device,
            new Field("features.keep", R.string.title_value_features, Type.TEXT, null),
            new Field("contacts.starred", R.string.title_value_contacts, Type.CONTACTS, null),
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

    // The row of a value: its title and what applies, which opens to the editor of the value; for an app, the switch
    // of its own value, which chooses the mode All apps or Fixed for an identifier and empties another value
    private static class Row {
        Field field;
        View view;
        View editor;
        TextView tvValue;
        TextView tvTag;
        TextView tvPill;
        ImageView ivExpand;
        MaterialSwitch swOwn;
        ChipGroup readers;
        View section;
    }

    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final Set<String> expanded = new HashSet<>(); // Rows open, also after the form is built again
    private final Set<String> opened = new HashSet<>(); // Own values switched on which are still empty
    private final Map<View, Chip> sectionChips = new LinkedHashMap<>();
    private TitleSearch search;
    private String query = "";
    private boolean ownOnly = false;
    private int jumpY = -1; // Where a chip scrolls to, until then the chip of the section on top is not followed

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

        // Values of an app replace the values for all apps; a scope of all apps kept by an older version of this
        // screen, which could switch to it, opens as the values of the app, without what was entered there
        if (packageName != null && !packageName.equals(category)) {
            category = packageName;
            restored = null;
        }

        MaterialToolbar toolbar = setupToolbar(getString(R.string.title_values));
        toolbarSummary = new ToolbarSummary(toolbar, findViewById(R.id.svValues), findViewById(R.id.tvHint));
        search = TitleSearch.ofTitle(this, show -> {
            toolbar.setTitle(show ? getString(R.string.title_values) : null);
            toolbarSummary.setHidden(!show);
        }, findViewById(R.id.tilSearch), findViewById(R.id.etSearch), findViewById(R.id.ibSearch), text -> {
            query = text.trim().toLowerCase(Locale.ROOT);
            updateRows();
        });
        search.attach();

        // The values of the app only
        Chip chipOwn = findViewById(R.id.chipOwn);
        chipOwn.setVisibility(packageName == null ? View.GONE : View.VISIBLE);
        chipOwn.setContentDescription(getString(R.string.title_own_filter));
        chipOwn.setOnCheckedChangeListener((chip, checked) -> {
            ownOnly = checked;
            updateRows();
        });

        // The chip of the section on top follows the scroll, except while a chip scrolls to its section
        NestedScrollView svValues = findViewById(R.id.svValues);
        svValues.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> {
            if (jumpY >= 0) {
                int max = Math.max(0, v.getChildAt(0).getHeight() - v.getHeight());
                if (Math.abs(y - Math.min(jumpY, max)) <= 2)
                    jumpY = -1;
                return;
            }
            followSection();
        });

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
        // Until there is a form, like at the start and on a retry; a switch of the scope shows the old form meanwhile
        View pbLoading = findViewById(R.id.pbLoading);
        if (((LinearLayout) findViewById(R.id.llValues)).getChildCount() == 0)
            pbLoading.setVisibility(View.VISIBLE);
        Ui.submit(() -> {
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
                    List<String> groups = readers.computeIfAbsent(name, k -> new ArrayList<>());
                    if (!groups.contains(hook.getGroup()))
                        groups.add(hook.getGroup());
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
                        groups.add(assignment.group);
                    for (String group : groups)
                        restricting.merge(group, 1, Integer::sum);
                }
            } else {
                app = Data.getApp(context, category, -1);
                if (app != null)
                    for (XAssignment assignment : app.assignments)
                        restricted.add(assignment.group);
            }
            // What the app gets of the identifiers the service derives, new each boot and the default Android ID
            final Map<String, String> boot = (app == null ? new HashMap<>() : Data.getBootValues(context, category, app.uid));

            runOnUiThread(() -> {
                // The scope was switched meanwhile, its form is built by the load which followed
                if (!category.equals(this.category))
                    return;

                // What is entered stays, like values not saved or typed during a save; a recreation and another
                // scope have set what to enter already
                if (restored == null)
                    restored = getChanges();

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
                pbLoading.setVisibility(View.GONE);

                for (Map.Entry<String, String> entry : restored.entrySet())
                    setEntered(entry.getKey(), entry.getValue());
                restored = null;
                updateRows();
                updateBar();
            });
        }, ex -> {
            postUnlessGone(() -> pbLoading.setVisibility(View.GONE));
            // This screen does not load again by itself
            Ui.error(this, TAG, ex, this::load, Snackbar.LENGTH_INDEFINITE);
        });
    }

    // The hint of the screen; for an app with the way to the values of all apps, which apply where it has none
    private void bindHint() {
        TextView tvHint = findViewById(R.id.tvHint);
        if (GLOBAL.equals(category)) {
            tvHint.setText(getString(R.string.title_values_global_hint));
            tvHint.setMovementMethod(null);
            return;
        }
        SpannableStringBuilder text = new SpannableStringBuilder(getString(R.string.title_values_app_switch_hint, label));
        text.append(' ');
        int start = text.length();
        text.append(getString(R.string.title_values_global));
        text.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                startActivity(getIntent(ActivityValues.this, null, null));
            }
        }, start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvHint.setText(text);
        tvHint.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private void buildForm() {
        boolean global = GLOBAL.equals(category);
        toolbarSummary.setSummary(global ? getString(R.string.title_all_apps) : label);
        bindHint();

        LinearLayout container = findViewById(R.id.llValues);
        container.removeAllViews();
        inputs.clear();
        modes.clear();
        hints.clear();
        rows.clear();
        opened.clear();
        sectionChips.clear();
        ChipGroup cgSections = findViewById(R.id.cgSections);
        cgSections.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        View section = null;
        LinearLayout llRows = null;
        for (Object item : form) {
            if (item instanceof Integer) {
                section = inflater.inflate(R.layout.item_value_section, container, false);
                TextView tvSection = section.findViewById(R.id.tvSection);
                tvSection.setText((Integer) item);
                ViewCompat.setAccessibilityHeading(tvSection, true);
                llRows = section.findViewById(R.id.llRows);
                container.addView(section);

                // The chip of the section scrolls to it
                Chip chip = (Chip) inflater.inflate(R.layout.item_section_chip, cgSections, false);
                chip.setId(View.generateViewId());
                chip.setText((Integer) item);
                final View target = section;
                chip.setOnClickListener(v -> jumpTo(target));
                cgSections.addView(chip);
                sectionChips.put(section, chip);
                continue;
            }

            final Field field = (Field) item;
            String value = values.get(field.key);

            // The row of the value, which opens to the editor below it
            final Row row = new Row();
            row.field = field;
            row.section = section;
            row.view = inflater.inflate(R.layout.item_value_row, llRows, false);
            row.editor = row.view.findViewById(R.id.llEditor);
            row.tvValue = row.view.findViewById(R.id.tvValue);
            row.tvTag = row.view.findViewById(R.id.tvTag);
            row.tvPill = row.view.findViewById(R.id.tvPill);
            row.ivExpand = row.view.findViewById(R.id.ivExpand);
            row.swOwn = row.view.findViewById(R.id.swOwn);
            String title = (field.title == 0 ? field.key : getString(field.title));
            TextView tvRowTitle = row.view.findViewById(R.id.tvTitle);
            tvRowTitle.setText(title);
            if (field.title == 0) {
                tvRowTitle.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.plex_mono));
                tvRowTitle.setTextDirection(View.TEXT_DIRECTION_LTR);
            }
            row.swOwn.setVisibility(global ? View.GONE : View.VISIBLE);
            row.swOwn.setContentDescription(getString(R.string.title_own_value, title));
            row.swOwn.setOnClickListener(v -> setOwn(row, row.swOwn.isChecked()));
            row.view.findViewById(R.id.llHead).setOnClickListener(v -> {
                if (global || isOwn(row))
                    setExpanded(row, !expanded.contains(field.key));
                else
                    setOwn(row, true);
            });
            llRows.addView(row.view);
            rows.put(field.key, row);
            LinearLayout editor = (LinearLayout) row.editor;

            int layout;
            if (isDropdown(field))
                layout = R.layout.item_value_dropdown;
            else if (field.type == Type.TIMEZONE)
                layout = R.layout.item_value_auto;
            else
                layout = R.layout.item_value_text;
            // The field has no label, which would take room above the text: the row has the title, which screen
            // readers name the field by, and an empty field shows what applies then, see getPlaceholder
            TextInputLayout til = (TextInputLayout) inflater.inflate(layout, editor, false);
            EditText input = til.findViewById(R.id.etValue);
            input.setContentDescription(title);
            boolean id = FakeIds.isId(field.key);
            Mode mode = (id ? new Mode() : null);
            if (id) {
                // The row has the title; the choice is above the field of the value. For an app the switch of the
                // row chooses the values of all apps, so the choice has Fixed and Each boot only
                View view = inflater.inflate(R.layout.item_value_mode, editor, false);
                mode.field = field;
                mode.group = view.findViewById(R.id.tgMode);
                mode.box = view.findViewById(R.id.llBox);
                mode.tvBox = view.findViewById(R.id.tvBox);
                mode.tvBoxValue = view.findViewById(R.id.tvBoxValue);
                mode.cbAll = view.findViewById(R.id.cbAll);
                view.findViewById(R.id.tvTitle).setVisibility(View.GONE);
                ((LinearLayout.LayoutParams) view.getLayoutParams()).topMargin = 0;
                view.findViewById(R.id.btnInherit).setVisibility(View.GONE);
                editor.addView(view);
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
                Ui.onTextChanged(dropdown, text -> updateRow(row));
            } else {
                if (field.type == Type.TIMEZONE)
                    ((MaterialAutoCompleteTextView) input).setSimpleItems(TimeZone.getAvailableIDs());
                if (field.type == Type.NUMBER) {
                    // The key listener of the number type drops a decimal comma, see decimal: 11,5 would become 115
                    input.setKeyListener(DigitsKeyListener.getInstance("0123456789+-.,"));
                    input.setRawInputType(InputType.TYPE_CLASS_NUMBER |
                            InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
                }
                input.setText(value);
                input.setHint(getPlaceholder(field));
                til.setHelperText(getFallbackHint(field));
                // After the value saved, which the limits would otherwise cut, see save
                limit(field, til, input);

                // A value can be cleared by itself, an empty field removes the value when saved
                if (field.type != Type.TIMEZONE) {
                    til.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
                    til.setEndIconContentDescription(getString(R.string.title_clear_field,
                            field.title == 0 ? field.key : getString(field.title)));
                }
            }

            inputs.put(field.key, til);
            if (id) {
                // The randomize button at the end of the field, in the place of the button which clears it
                til.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
                til.setEndIconDrawable(R.drawable.ic_shuffle);
                til.setEndIconTintList(Ui.tint(this, androidx.appcompat.R.attr.colorPrimary));
                til.setEndIconContentDescription(getString(R.string.title_randomize_field, getString(field.title)));
                til.setEndIconOnClickListener(view -> input.setText(FakeIds.generate(field.key, random)));
                editor.addView(til);
                mode.row = til;
                initMode(mode);
            } else
                editor.addView(til);
            // An error is of the text it was given for
            Ui.onTextChanged(input, text -> {
                til.setError(null);
                updateRow(row);
                updateBar();
            });

            // The groups which read the value, under it
            List<String> groups = readers.get(field.key);
            if (groups != null) {
                row.readers = new ChipGroup(this);
                row.readers.setChipSpacingVertical(0);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Math.round(4 * getResources().getDisplayMetrics().density);
                editor.addView(row.readers, lp);
                for (String group : groups) {
                    Chip hint = (Chip) inflater.inflate(R.layout.item_value_reader, row.readers, false);
                    hint.setTag(group);
                    row.readers.addView(hint);
                    hints.add(hint);
                }
            }

            if (expanded.contains(field.key))
                row.editor.setVisibility(View.VISIBLE);
        }
        updateLocationHints();
        bindHints();
        updateRows();
        // The sections have their places once they are laid out
        findViewById(R.id.svValues).post(this::followSection);
    }

    // Whether the app has its own value, or one switched on which is still empty; always for all apps
    private boolean isOwn(Row row) {
        if (GLOBAL.equals(category))
            return true;
        Field field = row.field;
        Mode mode = modes.get(field.key);
        if (mode != null)
            return !INHERIT.equals(getMode(mode));
        TextInputLayout til = inputs.get(field.key);
        if (til == null)
            return false;
        return (getValue(field, til.getEditText()) != null || (!isDropdown(field) && opened.contains(field.key)));
    }

    // Switching on an own value of the app chooses Fixed, or the choice of all apps of a list, and opens the editor;
    // switching it off chooses the values of all apps, which empties the field
    private void setOwn(Row row, boolean own) {
        Field field = row.field;
        Mode mode = modes.get(field.key);
        TextInputLayout til = inputs.get(field.key);
        EditText input = (til == null ? null : til.getEditText());
        if (mode != null)
            mode.group.check(own ? R.id.btnFixed : R.id.btnInherit);
        else if (input != null && isDropdown(field)) {
            String[] titles = getDropdownTitles(field);
            int index = 0;
            if (own) {
                index = Arrays.asList(dropdownValues(field)).indexOf(globals.get(field.key));
                if (index <= 0)
                    index = 1;
            }
            ((MaterialAutoCompleteTextView) input).setText(titles[index], false);
        } else if (input != null) {
            if (own)
                opened.add(field.key);
            else {
                opened.remove(field.key);
                input.setText(null);
            }
        }
        setExpanded(row, own);
        if (own && input != null && mode == null && !isDropdown(field)) {
            input.requestFocus();
            new WindowInsetsControllerCompat(getWindow(), input).show(WindowInsetsCompat.Type.ime());
        }
        updateRows();
        updateBar();
    }

    private void setExpanded(Row row, boolean expand) {
        if (expand)
            expanded.add(row.field.key);
        else
            expanded.remove(row.field.key);
        row.editor.setVisibility(expand ? View.VISIBLE : View.GONE);
        updateRow(row);
    }

    // What applies in the row: the value entered or chosen, else the value for all apps or the default, with a tag
    // which tells which; for an identifier also its mode
    private void updateRow(Row row) {
        if (row == null || row.view == null)
            return;
        Field field = row.field;
        boolean global = GLOBAL.equals(category);
        boolean own = isOwn(row);
        Mode mode = modes.get(field.key);
        TextInputLayout til = inputs.get(field.key);
        EditText input = (til == null ? null : til.getEditText());

        String value = null;
        Integer tag = null;
        Integer pill = null;
        if (mode != null) {
            String chosen = getMode(mode);
            if (INHERIT.equals(chosen)) {
                String inherited = FakeIds.getMode(field.key, globals, null);
                // The value the app gets, which the service derives for it when all apps have none, see updateMode
                String boot = (INHERIT.equals(getSavedMode(field.key)) ? bootValues.get(field.key) : null);
                if (FakeIds.FIXED.equals(inherited))
                    value = (globals.get(field.key) == null && field.builtin == null && boot != null
                            ? boot : getDefault(field, globals.get(field.key)));
                else
                    value = (boot == null ? getString(R.string.title_mode_boot) : boot);
                tag = R.string.title_tag_all_apps;
            } else if (FakeIds.FIXED.equals(chosen)) {
                value = (input == null ? null : getValue(field, input));
                if (value == null) {
                    value = getDefault(field, null);
                    if (field.builtin != null || FakeIds.ANDROID_ID.equals(field.key))
                        tag = R.string.title_tag_default;
                }
                pill = R.string.title_mode_fixed;
            } else {
                String boot = (chosen.equals(getSavedMode(field.key)) ? bootValues.get(field.key) : null);
                value = (boot == null ? getString(R.string.title_mode_boot) : boot);
                pill = R.string.title_mode_boot;
            }
        } else if (input != null && isDropdown(field)) {
            String[] names = getResources().getStringArray(
                    field.type == Type.LOCATION ? R.array.location_types : R.array.contacts_types);
            int index = Math.max(0, Arrays.asList(dropdownValues(field)).indexOf(getValue(field, input)));
            if (index == 0 && !global) {
                int inherited = Arrays.asList(dropdownValues(field)).indexOf(globals.get(field.key));
                value = names[Math.max(0, inherited)];
                tag = R.string.title_tag_all_apps;
            } else
                value = names[index];
        } else if (input != null) {
            value = getValue(field, input);
            if (value == null) {
                // An app without its own value gets the one of all apps, also where that is the default
                if (!global) {
                    value = (globals.get(field.key) != null ? globals.get(field.key) : field.builtin != null ? field.builtin
                            : getString(field.title == 0 ? R.string.title_value_not_set : R.string.title_value_none));
                    tag = R.string.title_tag_all_apps;
                } else if (field.builtin != null) {
                    value = field.builtin;
                    tag = R.string.title_tag_default;
                } else
                    value = getString(field.title == 0 ? R.string.title_value_not_set : R.string.title_value_none);
            }
        }

        row.tvValue.setText(value);
        row.tvValue.setTextColor(Ui.color(this, own && !global
                ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorOnSurfaceVariant));
        row.tvTag.setText(tag == null ? null : getString(tag));
        row.tvTag.setVisibility(tag == null ? View.GONE : View.VISIBLE);
        row.tvPill.setText(pill == null ? null : getString(pill));
        row.tvPill.setVisibility(pill == null || !own ? View.GONE : View.VISIBLE);
        boolean open = expanded.contains(field.key);
        row.ivExpand.setVisibility(own ? View.VISIBLE : View.GONE);
        row.ivExpand.setRotation(open ? 180 : 0);
        if (!global)
            row.swOwn.setChecked(own);
        // An app's value switched off has nothing to edit
        if (!own && open) {
            expanded.remove(field.key);
            row.editor.setVisibility(View.GONE);
        }
    }

    // What an empty field gives: for an app the value for all apps, else the default of the script, else none
    private String getPlaceholder(Field field) {
        String inherited = (GLOBAL.equals(category) ? null : globals.get(field.key));
        if (inherited != null)
            return inherited;
        if (field.builtin != null)
            return field.builtin;
        if (FakeIds.ANDROID_ID.equals(field.key))
            return getString(R.string.title_value_android_id_default);
        return getString(field.title == 0 ? R.string.title_value_not_set : R.string.title_value_none);
    }

    // A fixed value which is not set: the value for all apps, else the default of the script
    private String getDefault(Field field, String inherited) {
        if (inherited != null)
            return inherited;
        if (field.builtin != null)
            return field.builtin;
        if (FakeIds.ANDROID_ID.equals(field.key))
            return getString(R.string.title_value_android_id_default);
        return getString(R.string.title_value_none);
    }

    // The rows, the number of own values of an app, and which rows the search and the filter of own values show
    private void updateRows() {
        int own = 0;
        Map<View, Boolean> shown = new HashMap<>();
        for (Row row : rows.values()) {
            updateRow(row);
            boolean isOwn = isOwn(row);
            if (isOwn && !GLOBAL.equals(category))
                own++;
            String title = (row.field.title == 0 ? row.field.key : getString(row.field.title));
            boolean visible = (!ownOnly || isOwn) &&
                    (query.isEmpty() || title.toLowerCase(Locale.ROOT).contains(query) ||
                            row.field.key.toLowerCase(Locale.ROOT).contains(query));
            row.view.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (row.section != null && visible)
                shown.put(row.section, true);
        }
        boolean any = false;
        for (Map.Entry<View, Chip> entry : sectionChips.entrySet()) {
            boolean visible = shown.containsKey(entry.getKey());
            entry.getKey().setVisibility(visible ? View.VISIBLE : View.GONE);
            entry.getValue().setVisibility(visible ? View.VISIBLE : View.GONE);
            any |= visible;
        }
        boolean loaded = !rows.isEmpty();
        findViewById(R.id.tvNoMatches).setVisibility(loaded && !any ? View.VISIBLE : View.GONE);
        findViewById(R.id.hsvChips).setVisibility(loaded ? View.VISIBLE : View.GONE);
        findViewById(R.id.vChipsDivider).setVisibility(loaded ? View.VISIBLE : View.GONE);
        ((Chip) findViewById(R.id.chipOwn)).setText(getString(R.string.title_own_count, own));
    }

    // Scrolls to the section of a chip; the chip shows it at once, the section on top is followed again after
    private void jumpTo(View section) {
        NestedScrollView svValues = findViewById(R.id.svValues);
        View content = findViewById(R.id.llValues);
        int max = Math.max(0, svValues.getChildAt(0).getHeight() - svValues.getHeight());
        jumpY = Math.min(max, content.getTop() + section.getTop());
        checkChip(sectionChips.get(section));
        svValues.smoothScrollTo(0, jumpY);
        // A scroll which is stopped before it gets there, like by a touch, ends the jump too
        svValues.postDelayed(() -> jumpY = -1, 800);
    }

    // The chip of the last section which starts above the middle of the top half of the list
    private void followSection() {
        NestedScrollView svValues = findViewById(R.id.svValues);
        View content = findViewById(R.id.llValues);
        int y = svValues.getScrollY() + svValues.getHeight() / 4;
        Chip current = null;
        for (Map.Entry<View, Chip> entry : sectionChips.entrySet())
            if (entry.getKey().getVisibility() == View.VISIBLE &&
                    (current == null || content.getTop() + entry.getKey().getTop() <= y))
                current = entry.getValue();
        checkChip(current);
    }

    private void checkChip(Chip chip) {
        if (chip == null || chip.isChecked())
            return;
        ((ChipGroup) findViewById(R.id.cgSections)).check(chip.getId());
        // The chip is shown, also when the row of chips is scrolled
        HorizontalScrollView hsv = findViewById(R.id.hsvChips);
        int left = ((View) chip.getParent()).getLeft() + chip.getLeft();
        if (left < hsv.getScrollX() || left + chip.getWidth() > hsv.getScrollX() + hsv.getWidth())
            hsv.smoothScrollTo(Math.max(0, left - hsv.getPaddingStart()), 0);
    }

    // Under each value: the group which reads it and whether it restricts the app, with the action to restrict it,
    // or for all apps how many apps it restricts; a value no group reads has no line
    private void bindHints() {
        boolean global = GLOBAL.equals(category);
        for (View view : hints) {
            final String group = (String) view.getTag();
            String title = Groups.getTitle(this, group);
            Chip chip = (Chip) view;
            int color;
            String described;
            boolean action = false;
            if (global) {
                int apps = restricting.getOrDefault(group, 0);
                chip.setText(apps == 0 ? title
                        : getResources().getQuantityString(R.plurals.title_reader_apps, apps, title, apps));
                described = (apps == 0
                        ? getString(R.string.title_hint_no_apps, title)
                        : getResources().getQuantityString(R.plurals.title_hint_apps, apps, title, apps));
                color = (apps == 0 ? R.attr.colorWarning : R.attr.colorSuccess);
            } else {
                boolean on = restricted.contains(group);
                action = (!on && app != null);
                chip.setText(action ? getString(R.string.title_reader_restrict, title) : title);
                described = getString(on ? R.string.title_hint_restricted : R.string.title_hint_unrestricted, title);
                // Screen readers tell which group a tap restricts
                if (action)
                    described = getString(R.string.title_described, described,
                            getString(R.string.title_described, getString(R.string.title_restrict), title));
                color = (on ? R.attr.colorSuccess : R.attr.colorWarning);
            }
            chip.setContentDescription(described);
            chip.setChipIconTint(Ui.tint(this, color));
            chip.setClickable(action);
            chip.setFocusable(action);
            chip.setOnClickListener(action ? v -> restrict(group) : null);
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
            try {
                Data.assign(context, app, available, true);
            } catch (Throwable ex) {
                // Else the hint would say restricted while nothing was assigned
                postUnlessGone(() -> {
                    restricted.remove(group);
                    bindHints();
                });
                throw ex;
            }
            Ui.message(this, getString(R.string.msg_group_restricted, title, label));
        });
    }

    // What applies when the field is empty; a list of features has no default, so its format, too long for the label
    private String getFallbackHint(Field field) {
        String fallback = globals.get(field.key);
        if (fallback != null)
            return getString(R.string.title_global, fallback);
        else if (field.builtin != null)
            return getString(R.string.title_default, field.builtin);
        else if (FakeIds.ANDROID_ID.equals(field.key))
            return getString(R.string.title_default, getString(R.string.title_value_android_id_default));
        else if ("features.keep".equals(field.key))
            return getString(R.string.title_value_features_hint);
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
                // A value which the location type does not use is dimmed
                Row row = rows.get(field.key);
                if (row != null)
                    row.view.findViewById(R.id.llHead).setAlpha(needs.equals(type) ? 1f : 0.6f);
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
            Mode mode = modes.get(FakeIds.baseKey(key));
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
        return FakeIds.getSavedMode(key, values, GLOBAL.equals(category));
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
        String value;
        boolean saved = chosen.equals(getSavedMode(key));
        if (INHERIT.equals(chosen)) {
            String global = FakeIds.getMode(key, globals, null);
            if (FakeIds.FIXED.equals(global)) {
                text = getString(R.string.title_box_inherit_fixed);
                // The default Android ID is the one the service derives for the app
                value = (globals.get(key) != null ? globals.get(key)
                        : mode.field.builtin != null ? mode.field.builtin : saved ? bootValues.get(key) : null);
            } else {
                text = getString(FakeIds.BOOT_ALL.equals(global)
                        ? R.string.title_box_inherit_boot_all : R.string.title_box_inherit_boot);
                value = (saved ? bootValues.get(key) : null);
                if (value == null)
                    text = getString(FakeIds.BOOT_ALL.equals(global)
                            ? R.string.title_box_inherit_boot_all_short : R.string.title_box_inherit_boot_short);
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
        updateRow(rows.get(key));
    }

    // The bar of the changes, while there are some, with how many values changed
    private void updateBar() {
        if (restored != null)
            return;
        Set<String> changed = new HashSet<>();
        for (String key : getChanges().keySet())
            changed.add(FakeIds.baseKey(key));
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
        return (TextUtils.isEmpty(value) ? null : field.type == Type.NUMBER ? decimal(value) : value);
    }

    // Keyboards of languages like German and French type a decimal comma, which Java and the scripts (tonumber)
    // do not read, so numbers are saved with a decimal point, see ActivityValuesTest
    static String decimal(String value) {
        return value.replace(',', '.');
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
            FakeIds.putChanges(changed, field.key, mode == null ? null : getMode(mode),
                    mode == null ? null : getSavedMode(field.key), getValue(field, til.getEditText()), values);
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
            // The field of an identifier is hidden unless it is fixed: what it keeps of before is not checked then;
            // a value saved before, like by an older version without the limits, is not checked either, so it does not
            // stop saving the other values
            Mode mode = modes.get(field.key);
            if (value != null && (mode == null || FakeIds.FIXED.equals(getMode(mode))) &&
                    !value.equals(values.get(field.key)) && !isValid(field, value)) {
                Integer rule = getRule(field);
                til.setError(rule == null ? getString(R.string.msg_invalid, value) : getString(rule));
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
            boolean stop = (app != null && app.stopsAutomatically());
            try {
                for (Map.Entry<String, String> entry : changed.entrySet())
                    Data.putSetting(context, category, entry.getKey(), entry.getValue());
            } catch (Throwable ex) {
                // The values saved before the error apply too, so the form shows what was saved and the app is
                // stopped; stopping fails too when the service is gone, which must not hide the error of the save.
                // The values not saved stay entered, see load
                postUnlessGone(this::load);
                if (stop)
                    try {
                        Data.forceStop(context, app);
                    } catch (Throwable ignored) {
                    }
                throw ex;
            }
            // Stop the app once, so it starts with the new values
            if (stop)
                Data.forceStop(context, app);
            runOnUiThread(() -> {
                findViewById(R.id.bar).setVisibility(View.GONE);
                load();
            });
            // Persistent apps restart with the device only
            Ui.message(this, getString(stop ? R.string.msg_saved
                    : app != null && app.persistent ? R.string.msg_saved_reboot : R.string.msg_saved_restart));
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

    // The longest value of an identifier, which its field takes, and which characters it takes; a value of another
    // form would tell the app it is fake. The serial number is that of the compatibility test of Android (CTS
    // BuildTest.SERIAL_NUMBER_PATTERN), an IMEI that of 3GPP TS 23.003, which value_device_id.lua needs for the
    // other SIM slots too, a phone number that of ITU E.164 and an email address that of RFC 5321
    private static final int SERIAL_MAX = 20;
    private static final int ANDROID_ID_MAX = 16;
    private static final int IMEI_LENGTH = 15;
    private static final int MEID_LENGTH = 14;
    private static final int PHONE_MAX = 15;
    private static final int EMAIL_MAX = 254;
    private static final String DIGITS = "0123456789";
    private static final String HEX = "0123456789abcdefABCDEF";

    private void limit(Field field, TextInputLayout til, EditText input) {
        int max;
        List<InputFilter> filters = new ArrayList<>();
        switch (field.key) {
            case FakeIds.SERIAL:
                max = SERIAL_MAX;
                filters.add(chars(DIGITS + "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ", null));
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
                break;
            case FakeIds.ANDROID_ID:
                max = ANDROID_ID_MAX;
                filters.add(chars(HEX, false));
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                break;
            case FakeIds.IMEI:
                max = IMEI_LENGTH;
                filters.add(chars(DIGITS, null));
                input.setInputType(InputType.TYPE_CLASS_NUMBER);
                break;
            case FakeIds.MEID:
                max = MEID_LENGTH;
                filters.add(chars(HEX, true));
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
                break;
            case "value.phone_number":
                max = PHONE_MAX + 1;
                filters.add(chars(DIGITS + "+", null));
                input.setInputType(InputType.TYPE_CLASS_PHONE);
                break;
            case "value.email":
                max = EMAIL_MAX;
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
                break;
            default:
                return;
        }
        filters.add(new InputFilter.LengthFilter(max));
        input.setFilters(filters.toArray(new InputFilter[0]));
        til.setCounterEnabled(true);
        til.setCounterMaxLength(max);
    }

    // The form of an identifier, which an invalid value is told with
    private static Integer getRule(Field field) {
        switch (field.key) {
            case FakeIds.SERIAL:
                return R.string.rule_serial;
            case FakeIds.ANDROID_ID:
                return R.string.rule_android_id;
            case FakeIds.IMEI:
                return R.string.rule_imei;
            case FakeIds.MEID:
                return R.string.rule_meid;
            case "value.phone_number":
                return R.string.rule_phone_number;
            case "value.email":
                return R.string.rule_email;
            default:
                return null;
        }
    }

    // Takes only the characters given, also of a paste, in upper case (true), lower case (false) or as typed (null)
    static InputFilter chars(String allowed, Boolean upper) {
        return (source, start, end, dest, dstart, dend) -> {
            StringBuilder kept = new StringBuilder();
            boolean changed = false;
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (allowed.indexOf(c) < 0) {
                    changed = true;
                    continue;
                }
                char d = (upper == null ? c : upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
                changed |= (d != c);
                kept.append(d);
            }
            return (changed ? kept : null);
        };
    }

    private static boolean isValid(Field field, String value) {
        return isValid(field.key, value);
    }

    static boolean isValid(String key, String value) {
        try {
            switch (key) {
                case FakeIds.SERIAL:
                    return value.matches("[0-9A-Za-z]{6," + SERIAL_MAX + "}");
                case FakeIds.IMEI:
                    return value.matches("[0-9]{" + IMEI_LENGTH + "}") &&
                            FakeIds.luhn(value.substring(0, IMEI_LENGTH - 1)) == value.charAt(IMEI_LENGTH - 1) - '0';
                case FakeIds.MEID:
                    return value.matches("[0-9A-Fa-f]{" + MEID_LENGTH + "}");
                case "value.phone_number":
                    return value.matches("\\+?[0-9]{1," + PHONE_MAX + "}");
                case "value.email":
                    return value.length() <= EMAIL_MAX && value.matches("[^@\\s]+@[^@\\s]+");
                case "value.timezone":
                    return Arrays.asList(TimeZone.getAvailableIDs()).contains(value);
                case "value.locale":
                    // Tags of languages (BCP 47) without repeats, like locale.lua reads them
                    Set<String> tags = new HashSet<>();
                    for (String tag : value.split(","))
                        if (Locale.forLanguageTag(tag.trim()).getLanguage().isEmpty() ||
                                !tags.add(Locale.forLanguageTag(tag.trim()).toLanguageTag()))
                            return false;
                    return true;
                case "location.latitude":
                    return Math.abs(Double.parseDouble(value)) <= 90;
                case "location.longitude":
                    return Math.abs(Double.parseDouble(value)) <= 180;
                case "location.accuracy":
                    return Double.parseDouble(value) > 0;
                case FakeIds.ANDROID_ID:
                    // Lowercase hexadecimal like Android gives, 16 digits, fewer for an id kept from before Android 8,
                    // which had no leading zeros
                    return value.matches("[0-9a-f]{1,16}");
                default:
                    return true;
            }
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
