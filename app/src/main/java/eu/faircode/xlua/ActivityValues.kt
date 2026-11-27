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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.faircode.xlua

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.HashSet
import java.util.Locale
import java.util.TimeZone
import java.util.TreeSet
import java.util.function.Consumer
import kotlin.math.abs

// Fake values read by the hook scripts (param:getSetting), for all apps or for a single app;
// each value tells which group reads it and whether that group is restricted
class ActivityValues : ActivityBase() {
    companion object {
        private const val TAG = "XLua.Values"

        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_LABEL = "label"

        private const val GLOBAL = Data.GLOBAL

        // A recreation, like for another theme, keeps the scope and the values entered, see onSaveInstanceState
        private const val STATE_CATEGORY = "category"
        private const val STATE_KEYS = "keys"
        private const val STATE_VALUES = "values"

        private const val TYPE_TEXT = 0
        private const val TYPE_NUMBER = 1
        private const val TYPE_TIMEZONE = 2
        private const val TYPE_LOCATION = 3
        private const val TYPE_CONTACTS = 4

        private const val INHERIT = FakeIds.INHERIT

        // The longest value of an identifier, which its field takes, and which characters it takes; a value of another
        // form would tell the app it is fake. The serial number is that of the compatibility test of Android (CTS
        // BuildTest.SERIAL_NUMBER_PATTERN), an IMEI that of 3GPP TS 23.003, a phone number that of ITU E.164 and an
        // email address that of RFC 5321
        private const val SERIAL_MAX = 20
        private const val ANDROID_ID_MAX = 16
        private const val IMEI_LENGTH = 15
        private const val MEID_LENGTH = 14
        private const val PHONE_MAX = 15
        private const val EMAIL_MAX = 254
        private const val DIGITS = "0123456789"
        private const val HEX = "0123456789abcdefABCDEF"

        // Dropdown positions to setting values; the first item is no value, which for an app means the value of all apps
        private val LOCATION_VALUES = arrayOf<String?>(null, "set", "coarse")
        private val CONTACTS_VALUES = arrayOf<String?>(null, "1", "0")

        private data class Field(
            val key: String,
            @StringRes val title: Int, // 0 for settings of custom definitions, which show the key
            val type: Int,
            val builtin: String? // Value used by the script when there is no setting
        )

        private data class Section(@StringRes val title: Int)

        // Settings used by the scripts in assets, with their built-in defaults
        private val SECTIONS = listOf<Any>(
            Section(R.string.title_values_identifiers),
            Field(FakeIds.SERIAL, R.string.title_value_serial, TYPE_TEXT, "unknown"),
            // Fixed without a value, the app gets one of its developer which stays, see FakeIds.getBootValues
            Field(FakeIds.ANDROID_ID, R.string.title_value_android_id, TYPE_TEXT, null),
            Field(FakeIds.IMEI, R.string.title_value_imei, TYPE_TEXT, null),
            Field(FakeIds.MEID, R.string.title_value_meid, TYPE_TEXT, null),
            Field("value.phone_number", R.string.title_value_phone_number, TYPE_TEXT, null),
            Field("value.email", R.string.title_value_email, TYPE_TEXT, "private@example.com"),
            Section(R.string.title_values_location),
            Field("location.type", R.string.title_values_location, TYPE_LOCATION, null),
            Field("location.latitude", R.string.title_value_latitude, TYPE_NUMBER, "0"),
            Field("location.longitude", R.string.title_value_longitude, TYPE_NUMBER, "0"),
            Field("location.accuracy", R.string.title_value_accuracy, TYPE_NUMBER, "10000"),
            Section(R.string.section_region),
            Field("value.timezone", R.string.title_value_timezone, TYPE_TIMEZONE, "UTC"),
            Field("value.locale", R.string.title_value_locale, TYPE_TEXT, "en-US"),
            Section(R.string.section_device),
            Field("features.keep", R.string.title_value_features, TYPE_TEXT, null),
            Field("contacts.starred", R.string.title_value_contacts, TYPE_CONTACTS, null)
        )

        @JvmStatic
        fun getIntent(context: Context, packageName: String?, label: String?): Intent =
            Intent(context, ActivityValues::class.java).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_LABEL, label)
            }

        // Keyboards of languages like German and French type a decimal comma, which Java and the scripts (tonumber)
        // do not read, so numbers are saved with a decimal point, see ActivityValuesTest
        @JvmStatic
        fun decimal(value: String): String = value.replace(',', '.')

        // Takes only the characters given, also of a paste, in upper case (true), lower case (false) or as typed (null)
        @JvmStatic
        fun chars(allowed: String, upper: Boolean?): InputFilter = InputFilter { source, start, end, _, _, _ ->
            val kept = StringBuilder()
            var changed = false
            for (i in start until end) {
                val c = source[i]
                if (allowed.indexOf(c) < 0) {
                    changed = true
                    continue
                }
                val d = when (upper) {
                    true -> c.uppercaseChar()
                    false -> c.lowercaseChar()
                    null -> c
                }
                changed = changed || d != c
                kept.append(d)
            }
            if (changed) kept.toString() else null
        }

        @JvmStatic
        fun isValid(key: String, value: String): Boolean = try {
            when (key) {
                FakeIds.SERIAL -> value.matches(Regex("[0-9A-Za-z]{6,$SERIAL_MAX}"))
                FakeIds.IMEI -> value.matches(Regex("[0-9]{$IMEI_LENGTH}")) &&
                        FakeIds.luhn(value.substring(0, IMEI_LENGTH - 1)) == value.last() - '0'
                FakeIds.MEID -> value.matches(Regex("[0-9A-Fa-f]{$MEID_LENGTH}"))
                "value.phone_number" -> value.matches(Regex("\\+?[0-9]{1,$PHONE_MAX}"))
                "value.email" -> value.length <= EMAIL_MAX && value.matches(Regex("[^@\\s]+@[^@\\s]+"))
                "value.timezone" -> TimeZone.getAvailableIDs().contains(value)
                "value.locale" -> {
                    // Tags of languages (BCP 47) without repeats, like locale.lua reads them
                    val tags = HashSet<String>()
                    value.split(',').all { tag ->
                        val locale = Locale.forLanguageTag(tag.trim())
                        locale.language.isNotEmpty() && tags.add(locale.toLanguageTag())
                    }
                }
                "location.latitude" -> abs(value.toDouble()) <= 90
                "location.longitude" -> abs(value.toDouble()) <= 180
                "location.accuracy" -> value.toDouble() > 0
                // Lowercase hexadecimal like Android gives, 16 digits, fewer for an id kept from before Android 8,
                // which had no leading zeros
                FakeIds.ANDROID_ID -> value.matches(Regex("[0-9a-f]{1,16}"))
                else -> true
            }
        } catch (_: NumberFormatException) {
            false
        }

        private fun limitOf(key: String): Int = when (key) {
            FakeIds.SERIAL -> SERIAL_MAX
            FakeIds.ANDROID_ID -> ANDROID_ID_MAX
            FakeIds.IMEI -> IMEI_LENGTH
            FakeIds.MEID -> MEID_LENGTH
            "value.phone_number" -> PHONE_MAX + 1
            "value.email" -> EMAIL_MAX
            else -> 0
        }

        private fun isDropdown(field: Field): Boolean =
            field.type == TYPE_LOCATION || field.type == TYPE_CONTACTS

        // The location type which the script uses the value for, null when it is always used
        private fun locationTypeOf(key: String): String? = when (key) {
            "location.latitude", "location.longitude" -> "set"
            "location.accuracy" -> "coarse"
            else -> null
        }
    }

    // Built-in fields and the settings of custom definitions, see load
    private var form by mutableStateOf<List<Any>>(emptyList())

    private var packageName: String? = null // null for the values of all apps
    private var label: String = ""
    private var category: String = GLOBAL

    private val values = HashMap<String, String>()
    private val globals = HashMap<String, String>()
    private val bootValues = HashMap<String, String>() // Of the app, see FakeIds
    private var restored: Map<String, String?>? = null // Entered for the scope, until its form is built
    private val random = SecureRandom()

    // The groups which read a value, by its key, and whether they restrict the app, or how many apps they restrict
    private val readers = HashMap<String, List<String>>()
    private val restricting = HashMap<String, Int>()
    private var app: XApp? = null // The app of the values, when they are of one app
    private var hooks: List<XHook> = emptyList()
    private var collection: List<String> = emptyList()

    // What is entered, and how an identifier gets its value, by key, see FakeIds
    private val drafts = mutableStateMapOf<String, String>()
    private val modes = mutableStateMapOf<String, String>()
    // The boot mode chosen last, which its choice keeps while the value is fixed or inherited, like the checkbox of
    // the views did
    private val bootModes = mutableStateMapOf<String, String>()
    private val restricted = mutableStateListOf<String>()
    private var expanded by mutableStateOf<Set<String>>(emptySet()) // Rows open, also after the form is built again
    private var opened by mutableStateOf<Set<String>>(emptySet()) // Own values switched on which are still empty

    private var loading by mutableStateOf(true)
    private var saving by mutableStateOf(false)
    private var errorKey by mutableStateOf<String?>(null)
    private var errorText by mutableStateOf<String?>(null)
    private var focusKey by mutableStateOf<String?>(null) // A value switched on, whose field takes the keyboard
    private var query by mutableStateOf("")
    private var ownOnly by mutableStateOf(false)
    private var searchOpen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        packageName = intent.getStringExtra(EXTRA_PACKAGE)
        label = intent.getStringExtra(EXTRA_LABEL) ?: packageName.orEmpty()
        category = packageName ?: GLOBAL

        // The values entered are put back in the form when it is built, see load
        if (savedInstanceState != null) {
            category = savedInstanceState.getString(STATE_CATEGORY, category)
            val keys = savedInstanceState.getStringArray(STATE_KEYS)
            val entered = savedInstanceState.getStringArray(STATE_VALUES)
            if (keys != null && entered != null && keys.size == entered.size)
                restored = keys.indices.associateTo(HashMap()) { keys[it] to entered[it] }
        }

        // Values of an app replace the values for all apps; a scope of all apps kept by an older version of this
        // screen, which could switch to it, opens as the values of the app, without what was entered there
        if (packageName != null && packageName != category) {
            category = packageName!!
            restored = null
        }

        // Back closes the search first, as it did for the views; then changes which are not saved
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    searchOpen -> closeSearch()
                    hasChanges() -> confirmDiscard(this@ActivityValues::finish, null)
                    else -> finish()
                }
            }
        })

        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { DoppelTheme { ValuesScreen() } }
        })

        load()
    }

    // Empty fields are kept too, as null values, they remove a value when saved
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val changes = restored ?: getChanges()
        outState.putString(STATE_CATEGORY, category)
        outState.putStringArray(STATE_KEYS, changes.keys.toTypedArray())
        outState.putStringArray(STATE_VALUES, changes.values.toTypedArray())
    }

    // The form cannot be saved until it is built (see save), so a failed load is retried
    private fun load() {
        val requested = category
        val global = requested == GLOBAL
        val context = applicationContext
        loading = true
        Ui.submit(Ui.Task {
            val settings = Data.getSettings(context, requested)
            val globalSettings = if (global) HashMap() else Data.getSettings(context, GLOBAL)
            val loadedHooks = Data.getHooks(context)
            val loadedCollection = Data.getCollection(context)

            // Settings declared by custom definitions, and the groups which read each setting
            val known = SECTIONS.filterIsInstance<Field>().mapTo(HashSet()) { it.key }
            val custom = TreeSet<String>()
            val loadedReaders = HashMap<String, MutableList<String>>()
            for (hook in loadedHooks)
                for (name in hook.settings) {
                    if (name !in known) custom.add(name)
                    val groups = loadedReaders.getOrPut(name) { mutableListOf() }
                    if (hook.group !in groups) groups.add(hook.group)
                }
            loadedReaders.values.forEach { Groups.sortByTitle(context, it) }

            // Whether the groups restrict the app, or how many apps they restrict
            val loadedRestricted = HashSet<String>()
            val loadedRestricting = HashMap<String, Int>()
            val loadedApp = if (global) null else Data.getApp(context, requested, -1)
            if (global)
                for (other in Data.getApps(context))
                    other.assignments.map { it.group }.toSet().forEach { group ->
                        loadedRestricting.merge(group, 1, Int::plus)
                    }
            else loadedApp?.assignments?.forEach { loadedRestricted.add(it.group) }

            // What the app gets of the identifiers the service derives, new each boot and the default Android ID
            val boot = if (loadedApp == null) HashMap<String, String>()
            else Data.getBootValues(context, requested, loadedApp.uid)

            runOnUiThread {
                // The scope was switched meanwhile, its form is built by the load which followed
                if (requested != category) return@runOnUiThread
                // What is entered stays, like values not saved or typed during a save
                if (restored == null) restored = getChanges()

                values.clear(); values.putAll(settings)
                globals.clear(); globals.putAll(globalSettings)
                bootValues.clear(); bootValues.putAll(boot)
                readers.clear(); readers.putAll(loadedReaders)
                restricting.clear(); restricting.putAll(loadedRestricting)
                restricted.clear(); restricted.addAll(loadedRestricted)
                app = loadedApp
                hooks = loadedHooks
                collection = loadedCollection
                form = buildForm(custom)
                opened = emptySet()
                form.filterIsInstance<Field>().forEach { field ->
                    drafts[field.key] = values[field.key].orEmpty()
                    if (FakeIds.isId(field.key)) {
                        modes[field.key] = savedMode(field.key)
                        if (modes[field.key] == FakeIds.BOOT || modes[field.key] == FakeIds.BOOT_ALL)
                            bootModes[field.key] = modes[field.key]!!
                    }
                }

                restored?.forEach { (key, value) -> setEntered(key, value) }
                restored = null
                loading = false
            }
        }, Consumer { ex ->
            postUnlessGone { loading = false }
            // This screen does not load again by itself
            Ui.error(this, TAG, ex, Runnable { load() }, Snackbar.LENGTH_INDEFINITE)
        })
    }

    private fun buildForm(custom: Set<String>): List<Any> = buildList {
        addAll(SECTIONS)
        if (custom.isNotEmpty()) {
            add(Section(R.string.title_values_custom))
            custom.forEach { add(Field(it, 0, TYPE_TEXT, null)) }
        }
    }

    // Values entered which are not saved, null for an empty field
    private fun getChanges(): MutableMap<String, String?> {
        val changed = HashMap<String, String?>()
        for (field in form.filterIsInstance<Field>()) {
            val mode = if (FakeIds.isId(field.key)) modes[field.key] ?: savedMode(field.key) else null
            FakeIds.putChanges(changed, field.key, mode, mode?.let { savedMode(field.key) }, value(field), values)
        }
        return changed
    }

    private fun hasChanges(): Boolean = !(restored ?: getChanges()).isEmpty()

    // The mode saved for the scope, of all apps with their default, else of the app, INHERIT for none
    private fun savedMode(key: String): String = FakeIds.getSavedMode(key, values, category == GLOBAL)

    // Like entered by the user, an empty field for null
    private fun value(field: Field): String? {
        val text = drafts[field.key].orEmpty()
        if (isDropdown(field)) return text.ifEmpty { null }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return if (field.type == TYPE_NUMBER) decimal(trimmed) else trimmed
    }

    // The characters and the length of a field, which its filter takes, also of a paste
    private fun filtered(field: Field, text: String): String = when (field.key) {
        FakeIds.SERIAL -> text.filter { it in DIGITS || it in 'a'..'z' || it in 'A'..'Z' }
            .take(SERIAL_MAX).uppercase(Locale.ROOT)
        FakeIds.ANDROID_ID -> text.filter { it in HEX }.take(ANDROID_ID_MAX).lowercase(Locale.ROOT)
        FakeIds.IMEI -> text.filter { it in DIGITS }.take(IMEI_LENGTH)
        FakeIds.MEID -> text.filter { it in HEX }.take(MEID_LENGTH).uppercase(Locale.ROOT)
        "value.phone_number" -> text.filter { it in DIGITS || it == '+' }.take(PHONE_MAX + 1)
        "value.email" -> text.take(EMAIL_MAX)
        else -> text
    }

    private fun setDraft(field: Field, text: String?) {
        drafts[field.key] = text.orEmpty()
        // An error is of the text it was given for
        errorKey = null
        errorText = null
    }

    // Like entered by the user, an empty field for null; a mode like chosen
    private fun setEntered(key: String, value: String?) {
        if (key.endsWith(FakeIds.MODE)) {
            val base = FakeIds.baseKey(key)
            if (form.any { it is Field && it.key == base }) {
                modes[base] = when (value) {
                    null, INHERIT -> if (category == GLOBAL) FakeIds.getMode(base, null, null) else INHERIT
                    else -> value
                }
                if (value == FakeIds.BOOT || value == FakeIds.BOOT_ALL) bootModes[base] = value
            }
            return
        }
        if (form.any { it is Field && it.key == key })
            drafts[key] = value.orEmpty()
    }

    // Whether the app has its own value, or one switched on which is still empty; always for all apps
    private fun isOwn(field: Field): Boolean {
        if (category == GLOBAL) return true
        if (FakeIds.isId(field.key)) return modes[field.key] != INHERIT
        return value(field) != null || (!isDropdown(field) && field.key in opened)
    }

    private fun ownCount(): Int =
        form.filterIsInstance<Field>().count { isOwn(it) }

    // Switching on an own value of the app chooses Fixed, or the choice of all apps of a list, and opens the editor;
    // switching it off chooses the values of all apps, which empties the field
    private fun setOwn(field: Field, own: Boolean) {
        if (FakeIds.isId(field.key))
            modes[field.key] = if (own) FakeIds.FIXED else INHERIT
        else if (isDropdown(field)) {
            val options = dropdownValues(field)
            val index = if (own) {
                val inherited = options.indexOf(globals[field.key])
                if (inherited <= 0) 1 else inherited
            } else 0
            drafts[field.key] = options[index].orEmpty()
        } else if (own) {
            opened = opened + field.key
            // The field of a value switched on takes the keyboard, like it did for the views
            focusKey = field.key
        } else {
            opened = opened - field.key
            drafts[field.key] = ""
        }
        expanded = if (own) expanded + field.key else expanded - field.key
        errorKey = null
        errorText = null
    }

    private fun setMode(field: Field, mode: String) {
        modes[field.key] = mode
        if (mode == FakeIds.BOOT || mode == FakeIds.BOOT_ALL) bootModes[field.key] = mode
        // An app's value switched off has nothing to edit
        if (mode == INHERIT) expanded = expanded - field.key
        errorKey = null
        errorText = null
    }

    private fun fieldTitle(field: Field): String =
        if (field.title == 0) field.key else getString(field.title)

    private fun dropdownValues(field: Field): List<String?> =
        (if (field.type == TYPE_LOCATION) LOCATION_VALUES else CONTACTS_VALUES).toList()

    // The first item is no value, which for an app means the value for all apps, when there is one
    private fun dropdownLabels(field: Field): List<String> {
        val names = resources.getStringArray(
            if (field.type == TYPE_LOCATION) R.array.location_types else R.array.contacts_types
        )
        val global = dropdownValues(field).indexOf(globals[field.key])
        if (global > 0) names[0] = getString(R.string.title_global, names[global])
        return names.toList()
    }

    private fun selectDropdown(field: Field, label: String) {
        val index = dropdownLabels(field).indexOf(label).coerceAtLeast(0)
        setDraft(field, dropdownValues(field)[index])
    }

    // What an empty field gives: for an app the value for all apps, else the default of the script, else none
    private fun placeholder(field: Field): String {
        val inherited = if (category == GLOBAL) null else globals[field.key]
        return when {
            inherited != null -> inherited
            field.builtin != null -> field.builtin
            field.key == FakeIds.ANDROID_ID -> getString(R.string.title_value_android_id_short)
            else -> getString(if (field.title == 0) R.string.title_value_not_set else R.string.title_value_none)
        }
    }

    // A fixed value which is not set: the value for all apps, else the default of the script
    private fun getDefault(field: Field, inherited: String?): String = when {
        inherited != null -> inherited
        field.builtin != null -> field.builtin
        field.key == FakeIds.ANDROID_ID -> getString(R.string.title_value_android_id_short)
        else -> getString(R.string.title_value_none)
    }

    // What applies in the row: the value entered or chosen, else the value for all apps or the default, with a tag
    // which tells which; for an identifier also its mode
    private fun rowDisplay(field: Field): Triple<String, Int?, Int?> {
        val global = category == GLOBAL
        var tag: Int? = null
        var pill: Int? = null
        val shown: String
        if (FakeIds.isId(field.key)) {
            val mode = modes[field.key] ?: savedMode(field.key)
            when (mode) {
                INHERIT -> {
                    val inherited = FakeIds.getMode(field.key, globals, null)
                    // The value the app gets, which the service derives for it when all apps have none
                    val boot = if (savedMode(field.key) == INHERIT) bootValues[field.key] else null
                    shown = if (inherited == FakeIds.FIXED)
                        if (globals[field.key] == null && field.builtin == null && boot != null) boot
                        else getDefault(field, globals[field.key])
                    else boot ?: getString(R.string.title_mode_boot)
                    tag = R.string.title_tag_all_apps
                }
                FakeIds.FIXED -> {
                    val entered = value(field)
                    if (entered == null) {
                        // An app's fixed value which is empty is the one of all apps, like the placeholder of its field
                        val inherited = if (global) null else globals[field.key]
                        if (inherited != null) tag = R.string.title_tag_all_apps
                        else if (field.builtin != null || field.key == FakeIds.ANDROID_ID)
                            tag = R.string.title_tag_default
                        shown = getDefault(field, inherited)
                    } else shown = entered
                    pill = R.string.title_mode_fixed
                }
                else -> {
                    val boot = if (mode == savedMode(field.key)) bootValues[field.key] else null
                    shown = boot ?: getString(R.string.title_mode_boot)
                    pill = R.string.title_mode_boot
                }
            }
        } else if (isDropdown(field)) {
            val labels = dropdownLabels(field)
            val index = dropdownValues(field).indexOf(value(field)).coerceAtLeast(0)
            if (index == 0 && !global) {
                val inherited = dropdownValues(field).indexOf(globals[field.key]).coerceAtLeast(0)
                shown = labels[inherited]
                tag = R.string.title_tag_all_apps
            } else shown = labels[index]
        } else {
            val entered = value(field)
            shown = when {
                entered != null -> entered
                // An app without its own value gets the one of all apps, also where that is the default
                !global -> {
                    tag = R.string.title_tag_all_apps
                    globals[field.key] ?: field.builtin ?: getString(
                        if (field.title == 0) R.string.title_value_not_set else R.string.title_value_none
                    )
                }
                field.builtin != null -> {
                    tag = R.string.title_tag_default
                    field.builtin
                }
                else -> getString(
                    if (field.title == 0) R.string.title_value_not_set else R.string.title_value_none
                )
            }
        }
        return Triple(shown, tag, pill)
    }

    private fun validationRule(key: String): Int? = when (key) {
        FakeIds.SERIAL -> R.string.rule_serial
        FakeIds.ANDROID_ID -> R.string.rule_android_id
        FakeIds.IMEI -> R.string.rule_imei
        FakeIds.MEID -> R.string.rule_meid
        "value.phone_number" -> R.string.rule_phone_number
        "value.email" -> R.string.rule_email
        else -> null
    }

    private fun validateAndSave() {
        // The form shown is empty or of the scope before a switch, until it is built
        if (loading) return
        errorKey = null
        errorText = null
        for (field in form.filterIsInstance<Field>()) {
            val entered = value(field)
            // The field of an identifier is hidden unless it is fixed: what it keeps of before is not checked then;
            // a value saved before is not checked either, so it does not stop saving the other values
            val mode = if (FakeIds.isId(field.key)) modes[field.key] ?: savedMode(field.key) else null
            if (entered != null && (mode == null || mode == FakeIds.FIXED) &&
                entered != values[field.key] && !isValid(field.key, entered)
            ) {
                errorKey = field.key
                val rule = validationRule(field.key)
                errorText = if (rule != null) getString(rule) else getString(R.string.msg_invalid, entered)
                expanded = expanded + field.key
                return
            }
        }

        val changed = getChanges()
        if (changed.isEmpty()) {
            Ui.message(this, getString(R.string.msg_no_changes))
            return
        }

        saving = true
        val savedCategory = category
        val context = applicationContext
        run(TAG, Ui.Task {
            // Like for restrictions, the app is stopped when it is stopped automatically
            val target = if (savedCategory == GLOBAL) null else Data.getApp(context, savedCategory, -1)
            val stop = target != null && target.stopsAutomatically()
            try {
                changed.forEach { (key, entered) -> Data.putSetting(context, savedCategory, key, entered) }
            } catch (ex: Throwable) {
                // The values saved before the error apply too, so the form shows what was saved and the app is
                // stopped; the values not saved stay entered, see load
                postUnlessGone {
                    saving = false
                    load()
                }
                if (stop) try {
                    Data.forceStop(context, target)
                } catch (_: Throwable) {
                }
                throw ex
            }
            // Stop the app once, so it starts with the new values
            if (stop) Data.forceStop(context, target)
            runOnUiThread {
                saving = false
                load()
            }
            // Persistent apps restart with the device only
            Ui.message(this, getString(
                when {
                    stop -> R.string.msg_saved
                    target != null && target.persistent -> R.string.msg_saved_reboot
                    else -> R.string.msg_saved_restart
                }
            ))
        })
    }

    // All hooks of the group for the app, like the switch of the group on the screen of the app
    private fun restrict(group: String) {
        val current = app ?: return
        val available = Data.getAvailable(hooks, current, collection, group)
        val title = Groups.getTitle(this, group)
        restricted.add(group)
        val context = applicationContext
        run(TAG, Ui.Task {
            try {
                Data.assign(context, current, available, true)
            } catch (ex: Throwable) {
                // Else the hint would say restricted while nothing was assigned
                postUnlessGone { restricted.remove(group) }
                throw ex
            }
            Ui.message(this, getString(R.string.msg_group_restricted, title, label))
        })
    }

    // Under each value: the group which reads it and whether it restricts the app, or for all apps how many apps it
    // restricts; a value no group reads has no line. For all apps the groups with how many apps they restrict, the
    // same number once; for an app the groups, those which do not restrict it marked, with Restrict for them
    private fun readerText(field: Field): Pair<String?, List<String>> {
        val groups = readers[field.key] ?: return null to emptyList()
        if (category == GLOBAL) {
            val counts = groups.map { restricting[it] ?: 0 }.toSet()
            val same = counts.size == 1
            val titles = groups.map { group ->
                val title = Groups.getTitle(this, group)
                val apps = restricting[group] ?: 0
                when {
                    same -> title
                    apps == 0 -> getString(R.string.title_hint_no_apps, title)
                    else -> resources.getQuantityString(R.plurals.title_reader_apps, apps, title, apps)
                }
            }
            var text = titles.joinToString(", ")
            if (same) {
                val apps = counts.first()
                text = if (apps == 0) getString(R.string.title_hint_no_apps, text)
                else resources.getQuantityString(R.plurals.title_reader_apps, apps, text, apps)
            }
            return text to emptyList()
        }
        val unrestricted = mutableListOf<String>()
        val titles = groups.map { group ->
            val title = Groups.getTitle(this, group)
            if (group in restricted) title
            else {
                unrestricted.add(group)
                getString(R.string.title_reader_unrestricted, title)
            }
        }
        return titles.joinToString(", ") to unrestricted
    }

    // The location type which applies: the one entered for this scope, else the one of all apps
    private fun locationType(): String? {
        val field = form.filterIsInstance<Field>().firstOrNull { it.type == TYPE_LOCATION } ?: return null
        return value(field) ?: globals[field.key]
    }

    // The coordinates are used for a fixed location only, the accuracy for a coarse real location only,
    // see location.lua; a value which the location type does not use is dimmed
    private fun locationDimmed(field: Field): Boolean {
        val needed = locationTypeOf(field.key) ?: return false
        return needed != locationType()
    }

    // The note under a field: what applies when it is empty is its placeholder, see placeholder, so only the
    // format of a list of features, which has no default, or the location type it is used with
    private fun fieldHint(field: Field): String? {
        val needed = locationTypeOf(field.key)
        if (needed == null)
            return if (field.key == "features.keep") getString(R.string.title_value_features_hint) else null
        if (needed == locationType()) return null
        val titles = resources.getStringArray(R.array.location_types)
        return getString(R.string.title_value_used_with, titles[LOCATION_VALUES.indexOf(needed)])
    }

    private fun randomize(field: Field) {
        setDraft(field, FakeIds.generate(field.key, random))
    }

    private fun openSearch() {
        searchOpen = true
    }

    private fun closeSearch() {
        searchOpen = false
        query = ""
    }

    @Composable
    private fun ValuesScreen() {
        val resources = LocalResources.current
        val keyboard = LocalSoftwareKeyboardController.current
        val scope = rememberCoroutineScope()
        val scroll = rememberScrollState()
        val chips = rememberLazyListState()
        val searchFocus = remember { FocusRequester() }
        val locale = LocalConfiguration.current.locales[0]

        var selected by remember { mutableIntStateOf(0) }
        var jumping by remember { mutableStateOf(false) }
        var headerBottom by remember { mutableIntStateOf(0) }
        // The summary appears once the hint is scrolled away, like ToolbarSummary; only that change repaints
        val scrolled by remember {
            derivedStateOf { headerBottom > 0 && scroll.value > headerBottom }
        }
        // Positions in the scroll content, for the chips; plain map, since layout writes it without a repaint
        val sectionTop = remember { HashMap<Int, Int>() }

        // The sections which have a row to show, by the search and the filter of own values, see updateRows
        val visible = remember(form, drafts.toMap(), modes.toMap(), opened, query, ownOnly) {
            val text = query.trim().lowercase(Locale.ROOT)
            val sections = mutableListOf<Pair<Section, MutableList<Field>>>()
            for (item in form) when (item) {
                is Section -> sections.add(item to mutableListOf())
                is Field -> {
                    val own = isOwn(item)
                    val title = fieldTitle(item).lowercase(Locale.ROOT)
                    if ((!ownOnly || own) &&
                        (text.isEmpty() || title.contains(text) || item.key.lowercase(Locale.ROOT).contains(text))
                    ) sections.last().second.add(item)
                }
            }
            sections.filter { it.second.isNotEmpty() }
        }
        val selectedIndex = selected.coerceIn(0, (visible.size - 1).coerceAtLeast(0))
        val ownAt = if (packageName != null) 1 else 0

        // The chip of the last section which starts above the middle of the top half of the list, except while a
        // chip scrolls to its section
        LaunchedEffect(scroll, visible.size, jumping) {
            snapshotFlow { scroll.value + scroll.viewportSize / 4 }.collect { line ->
                if (jumping || visible.isEmpty()) return@collect
                var active = 0
                for (index in visible.indices) {
                    val top = sectionTop[index] ?: continue
                    if (top <= line) active = index
                }
                selected = active
            }
        }

        // The chip is shown, also when the row of chips is scrolled
        LaunchedEffect(selectedIndex, visible.size) {
            val index = selectedIndex + ownAt
            if (chips.layoutInfo.visibleItemsInfo.none { it.index == index })
                chips.animateScrollToItem(index)
        }

        // The keyboard opens when the search is opened, not when it is restored with the screen
        LaunchedEffect(searchOpen) {
            if (searchOpen) {
                searchFocus.requestFocus()
                keyboard?.show()
            } else keyboard?.hide()
        }

        fun jumpTo(index: Int) {
            selected = index
            jumping = true
            val target = sectionTop[index]
            scope.launch {
                try {
                    // A scroll which is stopped before it gets there, like by a touch, ends the jump too
                    if (target != null) withTimeoutOrNull(800) { scroll.animateScrollTo(target) }
                } finally {
                    jumping = false
                }
            }
        }

        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.ime)
                )
        ) {
            ValuesToolbar(
                searchOpen = searchOpen,
                query = query,
                summary = if (category == GLOBAL) stringResource(R.string.title_all_apps) else label,
                summaryShown = scrolled,
                focus = searchFocus,
                onBack = { onBackPressedDispatcher.onBackPressed() },
                onSearch = { if (searchOpen) closeSearch() else openSearch() },
                onQuery = { query = it }
            )

            // The values of the app only, and the sections, which scroll to their values
            if (form.isNotEmpty()) {
                LazyRow(
                    state = chips,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (packageName != null)
                        item(key = "own") {
                            FilterChip(
                                selected = ownOnly,
                                onClick = { ownOnly = !ownOnly },
                                label = { Text(stringResource(R.string.title_own_count, ownCount())) },
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.ic_filter), null, Modifier.size(16.dp))
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = resources.getString(R.string.title_own_filter)
                                }
                            )
                        }
                    items(visible.size, key = { visible[it].first.title }) { index ->
                        FilterChip(
                            selected = selectedIndex == index,
                            onClick = { jumpTo(index) },
                            label = { Text(stringResource(visible[index].first.title)) }
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll)
                        .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                ) {
                    Text(
                        hintText(),
                        Modifier.fillMaxWidth()
                            .padding(start = 4.dp, top = 12.dp, bottom = 8.dp)
                            .onGloballyPositioned {
                                headerBottom = (it.positionInParent().y + it.size.height).toInt()
                            },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )

                    visible.forEachIndexed { index, (section, fields) ->
                        ValueSection(
                            section = section,
                            fields = fields,
                            locale = locale,
                            onPosition = { y -> sectionTop[index] = y }
                        )
                    }

                    if (!loading && form.isNotEmpty() && visible.isEmpty())
                        Text(
                            stringResource(R.string.title_no_matches),
                            Modifier.fillMaxWidth().padding(top = 32.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            textAlign = TextAlign.Center
                        )
                }

                if (loading) {
                    val description = stringResource(R.string.title_loading)
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center).semantics {
                            contentDescription = description
                            liveRegion = LiveRegionMode.Polite
                        }
                    )
                }
            }

            // The bar of the changes, while there are some, with how many values changed
            val changed = getChanges().keys.map(FakeIds::baseKey).toSet().size
            if (!loading && changed > 0)
                ChangesBar(
                    count = changed,
                    saving = saving,
                    onDiscard = { resetDrafts() },
                    onSave = { validateAndSave() }
                )
        }
    }

    // The hint of the screen; for an app with the way to the values of all apps, which apply where it has none
    @Composable
    private fun hintText() = if (category == GLOBAL)
        buildAnnotatedString { append(stringResource(R.string.title_values_global_hint)) }
    else buildAnnotatedString {
        append(stringResource(R.string.title_values_app_switch_hint, label))
        append(" ")
        withLink(LinkAnnotation.Clickable("global") {
            startActivity(getIntent(this@ActivityValues, null, null))
        }) {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                append(stringResource(R.string.title_values_global))
            }
        }
    }

    @Composable
    private fun ValuesToolbar(
        searchOpen: Boolean,
        query: String,
        summary: String,
        summaryShown: Boolean,
        focus: FocusRequester,
        onBack: () -> Unit,
        onSearch: () -> Unit,
        onQuery: (String) -> Unit
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.title_back))
            }
            if (searchOpen)
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    Modifier.weight(1f).padding(start = 4.dp).heightIn(min = 48.dp).focusRequester(focus),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    placeholder = {
                        Text(
                            stringResource(R.string.title_search_values),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                    trailingIcon = if (query.isEmpty()) null else {
                        {
                            IconButton(onClick = { onQuery("") }) {
                                Icon(
                                    painterResource(R.drawable.ic_close),
                                    stringResource(R.string.title_clear_search)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
                )
            else
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        stringResource(R.string.title_values),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 1
                    )
                    // The summary of the top while that is scrolled away, like ToolbarSummary
                    if (summaryShown)
                        Text(
                            summary,
                            Modifier.padding(top = 2.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                }
            IconButton(onClick = onSearch) {
                Icon(
                    painterResource(if (searchOpen) R.drawable.ic_close else R.drawable.ic_search),
                    stringResource(if (searchOpen) R.string.title_close_search else R.string.menu_search)
                )
            }
        }
    }

    @Composable
    private fun ValueSection(section: Section, fields: List<Field>, locale: Locale, onPosition: (Int) -> Unit) {
        Column(Modifier.fillMaxWidth().onGloballyPositioned {
            onPosition(it.positionInParent().y.toInt())
        }) {
            Text(
                stringResource(section.title).uppercase(locale),
                Modifier.padding(start = 4.dp, top = 20.dp, bottom = 6.dp).semantics { heading() },
                style = MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.08.em
                )
            )
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            ) {
                Column {
                    fields.forEachIndexed { index, field ->
                        ValueRow(field)
                        if (index != fields.lastIndex)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    // A value: its title and what applies, which opens to the editor of the value; for an app, the switch of its own
    // value, which chooses the mode All apps or Fixed for an identifier and empties another value
    @Composable
    private fun ValueRow(field: Field) {
        val global = category == GLOBAL
        val own = isOwn(field)
        val open = field.key in expanded
        val (shown, tag, pill) = rowDisplay(field)
        val mode = modes[field.key] ?: savedMode(field.key)
        val error = if (errorKey == field.key) errorText else null
        val dimmed = locationDimmed(field)
        val resource = LocalResources.current
        val title = fieldTitle(field)

        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).alpha(if (dimmed) 0.6f else 1f)
                    .clickable { if (global || own) expanded = if (open) expanded - field.key else expanded + field.key
                                 else setOwn(field, true) }
                    .padding(start = 16.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = if (field.title == 0) Mono else FontFamily.Default,
                            textDirection = if (field.title == 0) TextDirection.Ltr else TextDirection.Unspecified
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    // The tag follows the value, which shrinks for it when it is long
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            shown,
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = if (own && !global) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = Mono,
                                textDirection = TextDirection.Ltr
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (tag != null) {
                            Spacer(Modifier.size(8.dp))
                            ValuePill(stringResource(tag))
                        }
                    }
                }

                // An identifier has its choice in the row instead of the pill, so it changes without opening the row
                if (FakeIds.isId(field.key)) {
                    if (own) ModeSelector(field, mode, global)
                } else if (pill != null && own) {
                    Spacer(Modifier.size(8.dp))
                    ValuePill(stringResource(pill))
                }

                // On the screen of an app, which has the switch in the row too, without the arrow, for room
                if (own && (global || !FakeIds.isId(field.key)))
                    Icon(
                        painterResource(R.drawable.ic_expand),
                        null,
                        Modifier.padding(start = 8.dp).size(20.dp).rotate(if (open) 180f else 0f),
                        tint = MaterialTheme.colorScheme.outline
                    )

                if (!global) {
                    Spacer(Modifier.size(8.dp))
                    Switch(
                        checked = own,
                        onCheckedChange = { setOwn(field, it) },
                        modifier = Modifier.semantics {
                            contentDescription = resource.getString(R.string.title_own_value, title)
                        }
                    )
                }
            }

            if (open && own) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    when {
                        FakeIds.isId(field.key) -> ModeEditor(field, mode, error)
                        isDropdown(field) -> SelectValue(
                            field,
                            dropdownLabels(field)[dropdownValues(field).indexOf(drafts[field.key].orEmpty())
                                .coerceAtLeast(0)],
                            dropdownLabels(field),
                            error,
                            onPick = { selectDropdown(field, it) },
                            onText = null
                        )
                        field.type == TYPE_TIMEZONE -> SelectValue(
                            field,
                            drafts[field.key].orEmpty(),
                            TimeZone.getAvailableIDs().filter {
                                it.startsWith(drafts[field.key].orEmpty(), ignoreCase = true)
                            },
                            error,
                            onPick = { setDraft(field, it) },
                            onText = { setDraft(field, it) }
                        )
                        else -> TextValue(field, drafts[field.key].orEmpty(), error, fieldHint(field))
                    }

                    // One line under the field: the groups which read the value, and Random for an identifier
                    val (readersText, unrestricted) = readerText(field)
                    if (readersText != null || FakeIds.isId(field.key))
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                readersText.orEmpty(),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            if (!global && app != null && unrestricted.isNotEmpty()) {
                                // Screen readers tell which groups a tap restricts
                                val names = unrestricted.joinToString(", ") { Groups.getTitle(this@ActivityValues, it) }
                                val description = resource.getString(
                                    R.string.title_described,
                                    resource.getString(R.string.title_restrict),
                                    names
                                )
                                TextButton(
                                    onClick = { unrestricted.forEach { restrict(it) } },
                                    modifier = Modifier.semantics { contentDescription = description }
                                ) { Text(stringResource(R.string.title_restrict)) }
                            }
                            if (FakeIds.isId(field.key) && mode == FakeIds.FIXED) {
                                val description = resource.getString(R.string.title_randomize_field, title)
                                TextButton(
                                    onClick = { randomize(field) },
                                    modifier = Modifier.semantics { contentDescription = description }
                                ) {
                                    Icon(painterResource(R.drawable.ic_shuffle), null, Modifier.size(18.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text(stringResource(R.string.title_random))
                                }
                            }
                        }
                }
            }
        }
    }

    // The choice of how an identifier gets its value: fixed, like all apps (for an app) or new each boot
    @Composable
    private fun ModeSelector(field: Field, mode: String, global: Boolean) {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!global)
                ModeOption(stringResource(R.string.title_mode_inherit), mode == INHERIT) {
                    setMode(field, INHERIT)
                }
            ModeOption(stringResource(R.string.title_mode_fixed), mode == FakeIds.FIXED) {
                setMode(field, FakeIds.FIXED)
            }
            ModeOption(stringResource(R.string.title_mode_boot), mode == FakeIds.BOOT || mode == FakeIds.BOOT_ALL) {
                setMode(field, bootModes[field.key] ?: FakeIds.BOOT)
            }
        }
    }

    @Composable
    private fun ModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
        Surface(
            Modifier.clip(RoundedCornerShape(11.dp)).clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            shape = RoundedCornerShape(11.dp),
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }

    // The field for a fixed value, else what applies, with the value the app gets until the next restart when it is
    // known: the one the service makes for what was saved
    @Composable
    private fun ModeEditor(field: Field, mode: String, error: String?) {
        if (mode == FakeIds.FIXED) {
            TextValue(field, drafts[field.key].orEmpty(), error, fieldHint(field))
            return
        }

        val key = field.key
        val saved = mode == savedMode(key)
        val boot = mode == FakeIds.BOOT || mode == FakeIds.BOOT_ALL
        val global = FakeIds.getMode(key, globals, null)
        var value: String? = if (saved) bootValues[key] else null
        var text: String
        if (mode == INHERIT) {
            if (global == FakeIds.FIXED) {
                text = stringResource(R.string.title_box_inherit_fixed)
                // The default Android ID is the one the service derives for the app
                value = globals[key] ?: field.builtin ?: (if (saved) bootValues[key] else null)
            } else {
                text = stringResource(
                    if (global == FakeIds.BOOT_ALL) R.string.title_box_inherit_boot_all
                    else R.string.title_box_inherit_boot
                )
                // Without a value the service makes for what was saved, the box tells that one applies
                if (value == null)
                    text = stringResource(
                        if (global == FakeIds.BOOT_ALL) R.string.title_box_inherit_boot_all_short
                        else R.string.title_box_inherit_boot_short
                    )
            }
        } else {
            text = stringResource(if (mode == FakeIds.BOOT_ALL) R.string.title_box_boot_all else R.string.title_box_boot)
            if (value != null) text += "\n" + stringResource(R.string.title_box_saved)
        }

        Column(Modifier.padding(start = 4.dp, top = 4.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            if (value != null)
                SelectionContainer {
                    Text(
                        value,
                        Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = Mono,
                            textDirection = TextDirection.Ltr
                        )
                    )
                }
            if (boot)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = 4.dp)
                        .clickable {
                            setMode(field, if (mode == FakeIds.BOOT_ALL) FakeIds.BOOT else FakeIds.BOOT_ALL)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.title_mode_all),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = mode == FakeIds.BOOT_ALL,
                        onCheckedChange = { setMode(field, if (it) FakeIds.BOOT_ALL else FakeIds.BOOT) }
                    )
                }
        }
    }

    @Composable
    private fun ValuePill(text: String) {
        Text(
            text,
            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1
        )
    }

    // The field of a value, which the row names for screen readers; an empty field shows what applies then
    @Composable
    private fun TextValue(field: Field, text: String, error: String?, helper: String?) {
        val label = fieldTitle(field)
        val focus = remember(field.key) { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val limit = limitOf(field.key)
        val support = error ?: helper
        OutlinedTextField(
            value = text,
            onValueChange = { setDraft(field, filtered(field, it)) },
            Modifier.fillMaxWidth().padding(top = 10.dp).focusRequester(focus).semantics {
                contentDescription = label
            },
            placeholder = {
                Text(
                    placeholder(field),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = Mono,
                        textDirection = TextDirection.Ltr
                    )
                )
            },
            supportingText = if (support == null) null else ({ Text(support) }),
            isError = error != null,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = Mono,
                textDirection = TextDirection.Ltr
            ),
            // The count at the end of the field, which needs no line of its own
            suffix = if (limit > 0 && error == null) {
                { Text("${text.length}/$limit", style = MaterialTheme.typography.labelSmall) }
            } else null,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = when {
                field.type == TYPE_NUMBER -> KeyboardType.Decimal
                field.key == "value.phone_number" -> KeyboardType.Phone
                field.key == "value.email" -> KeyboardType.Email
                else -> KeyboardType.Text
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            ),
            // A value can be cleared by itself, an empty field removes the value when saved
            trailingIcon = if (text.isEmpty()) null else {
                {
                    IconButton(onClick = { setDraft(field, "") }) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            stringResource(R.string.title_clear_field, label)
                        )
                    }
                }
            }
        )
        LaunchedEffect(error) { if (error != null) focus.requestFocus() }
        // A value switched on opens with the keyboard, like it did for the views
        LaunchedEffect(focusKey) {
            if (focusKey == field.key) {
                focus.requestFocus()
                keyboard?.show()
                focusKey = null
            }
        }
    }

    // A choice of a list, or a value of a list of time zones, which is typed
    @Composable
    private fun SelectValue(
        field: Field,
        text: String,
        options: List<String>,
        error: String?,
        onPick: (String) -> Unit,
        onText: ((String) -> Unit)?
    ) {
        var menu by remember(field.key) { mutableStateOf(false) }
        val label = fieldTitle(field)
        val timezone = field.type == TYPE_TIMEZONE
        ExposedDropdownMenuBox(
            expanded = menu,
            onExpandedChange = { menu = if (menu) false else options.isNotEmpty() }
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    onText?.invoke(value)
                    if (!timezone) return@OutlinedTextField
                    menu = value.isNotEmpty()
                },
                Modifier.fillMaxWidth().padding(top = 10.dp)
                    .menuAnchor(
                        if (onText == null) ExposedDropdownMenuAnchorType.PrimaryNotEditable
                        else ExposedDropdownMenuAnchorType.PrimaryEditable
                    )
                    .semantics { contentDescription = label },
                readOnly = onText == null,
                supportingText = if (error == null) null else ({ Text(error) }),
                isError = error != null,
                singleLine = true,
                textStyle = if (timezone) MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = Mono,
                    textDirection = TextDirection.Ltr
                ) else MaterialTheme.typography.bodyMedium,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            )
            ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onPick(option)
                            menu = false
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun ChangesBar(count: Int, saving: Boolean, onDiscard: () -> Unit, onSave: () -> Unit) {
        Surface(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 3.dp
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    pluralStringResource(R.plurals.title_changes, count, count),
                    Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyLarge
                )
                TextButton(onClick = onDiscard, modifier = Modifier.padding(end = 8.dp)) {
                    Text(stringResource(R.string.title_discard))
                }
                Button(onClick = onSave, enabled = !saving) {
                    Text(stringResource(R.string.menu_save))
                }
            }
        }
    }

    // The form of the scope with what is saved, like for the discard of the changes
    private fun resetDrafts() {
        drafts.clear()
        modes.clear()
        bootModes.clear()
        opened = emptySet()
        form.filterIsInstance<Field>().forEach { field ->
            drafts[field.key] = values[field.key].orEmpty()
            if (FakeIds.isId(field.key)) {
                modes[field.key] = savedMode(field.key)
                if (modes[field.key] == FakeIds.BOOT || modes[field.key] == FakeIds.BOOT_ALL)
                    bootModes[field.key] = modes[field.key]!!
            }
        }
        errorKey = null
        errorText = null
    }
}
