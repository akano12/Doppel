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
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.compose.foundation.text.selection.SelectionContainer
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray
import org.json.JSONObject
import org.luaj.vm2.compiler.LuaC
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.function.Consumer
import java.util.function.Supplier
import java.util.regex.Pattern

// Edit a hook definition; saving creates or updates a custom definition
class ActivityHookEdit : ActivityBase() {
    companion object {
        private const val TAG = "XLua.HookEdit"

        private const val EXTRA_ID = "id"

        // A recreation, like for another theme, keeps what was entered, see onSaveInstanceState
        private const val STATE_ID = "id"
        private const val STATE_UNCHANGED = "unchanged"
        private const val STATE_TAB = "tab"
        private const val STATE_FIELDS = "fields"

        // Of a new definition; the group is a name like the ones of the built-in definitions, not text which is translated
        private const val GROUP_DEFAULT = "Custom"
        private const val AUTHOR_DEFAULT = "me"
        private const val TEMPLATE_SCRIPT = "function after(hook, param)\n" +
                "    local result = param:getResult()\n" +
                "    if result == nil then\n" +
                "        return false\n" +
                "    end\n" +
                "\n" +
                "    local fake = 'private'\n" +
                "    param:setResult(fake)\n" +
                "    return true, result, fake\n" +
                "end\n"

        // Found anywhere, on each change of the script: a match of the whole text would backtrack over long scripts
        private val FUNCTION_BEFORE = Pattern.compile("\\bfunction\\s+before\\s*\\(")
        private val FUNCTION_AFTER = Pattern.compile("\\bfunction\\s+after\\s*\\(")

        @JvmStatic
        fun getIntent(context: Context, id: String?): Intent {
            val intent = Intent(context, ActivityHookEdit::class.java)
            intent.putExtra(EXTRA_ID, id)
            return intent
        }

        // Like Data.putCustom stores it, with a script which compiles, which would else only fail in the apps; the error
        // shows where. Also for import. Only built-in definitions resolve a script '@name', see XHook.readHooks.
        @JvmStatic
        @Throws(Exception::class)
        fun check(jhook: JSONObject) {
            XHook.fromJSONObject(jhook).validate()
            val script = jhook.getString("luaScript")
            LuaC.instance.compile(
                ByteArrayInputStream(script.toByteArray(StandardCharsets.UTF_8)),
                "script"
            )
        }
    }

    private enum class Field {
        Collection, Group, Name, Author, Version, Description, ClassName, Method, Params,
        ReturnType, MinSdk, MaxSdk, MinApk, MaxApk, Exclude, Settings, Script
    }

    private var hook by mutableStateOf<XHook?>(null) // null for a new definition
    private var hookId by mutableStateOf<String?>(null) // Also while loading, null for a new definition which is not saved yet
    private var builtinIds by mutableStateOf<Set<String>>(emptySet())
    private val knownIds = mutableSetOf<String>() // The definitions a save under another id would replace
    private var unchanged: String? = null // What was entered when loaded or saved, see getEntered
    private var loading by mutableStateOf(true) // Until the definition is loaded, saving cannot tell whether it is new
    private var refill = false // The fields are replaced by the definition loading, see delete

    // The fields, as the views had them
    private var collection by mutableStateOf("")
    private var group by mutableStateOf("")
    private var name by mutableStateOf("")
    private var author by mutableStateOf("")
    private var version by mutableStateOf("")
    private var description by mutableStateOf("")
    private var className by mutableStateOf("")
    private var method by mutableStateOf("")
    private var params by mutableStateOf("")
    private var returnType by mutableStateOf("")
    private var minSdk by mutableStateOf("")
    private var maxSdk by mutableStateOf("")
    private var minApk by mutableStateOf("")
    private var maxApk by mutableStateOf("")
    private var exclude by mutableStateOf("")
    private var settings by mutableStateOf("")
    private var script by mutableStateOf("")

    private var enabled by mutableStateOf(false)
    private var optional by mutableStateOf(false)
    private var usage by mutableStateOf(false)
    private var notify by mutableStateOf(false)

    private var tab by mutableIntStateOf(0)
    private val errors = mutableStateMapOf<Field, String>()
    private var checkResult by mutableStateOf<String?>(null)
    private var spinner by mutableStateOf(false)
    private var groups by mutableStateOf<List<String>>(emptyList())

    private var scriptFocused by mutableStateOf(false)
    private var scriptFocusPending by mutableStateOf(false)
    private var scriptEditText: EditText? = null
    private var scriptHighlighter: LuaHighlighter? = null

    private val focusRequesters = Field.values().associateWith { FocusRequester() }

    private val exportLauncher: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) export(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A definition saved meanwhile can have another id than the one the screen was started with
        hookId = if (savedInstanceState == null)
            intent.getStringExtra(EXTRA_ID)
        else
            savedInstanceState.getString(STATE_ID)
        tab = savedInstanceState?.getInt(STATE_TAB, 0) ?: 0

        if (savedInstanceState != null) {
            val fields = savedInstanceState.getBundle(STATE_FIELDS)
            collection = fields?.getString("collection") ?: ""
            group = fields?.getString("group") ?: ""
            name = fields?.getString("name") ?: ""
            author = fields?.getString("author") ?: ""
            version = fields?.getString("version") ?: ""
            description = fields?.getString("description") ?: ""
            className = fields?.getString("className") ?: ""
            method = fields?.getString("method") ?: ""
            params = fields?.getString("params") ?: ""
            returnType = fields?.getString("returnType") ?: ""
            minSdk = fields?.getString("minSdk") ?: ""
            maxSdk = fields?.getString("maxSdk") ?: ""
            minApk = fields?.getString("minApk") ?: ""
            maxApk = fields?.getString("maxApk") ?: ""
            exclude = fields?.getString("exclude") ?: ""
            settings = fields?.getString("settings") ?: ""
            script = fields?.getString("script") ?: ""
            enabled = fields?.getBoolean("enabled", false) ?: false
            optional = fields?.getBoolean("optional", false) ?: false
            usage = fields?.getBoolean("usage", false) ?: false
            notify = fields?.getBoolean("notify", false) ?: false
        } else if (hookId == null) {
            collection = Util.COLLECTION_DEFAULT
            group = GROUP_DEFAULT
            author = AUTHOR_DEFAULT
            version = "1"
            minSdk = "1"
            enabled = true
            usage = true
            script = TEMPLATE_SCRIPT
            unchanged = getEntered()
        }

        // The fields are restored above, the loaded definition does not replace them then
        val restored = (savedInstanceState != null && savedInstanceState.containsKey(STATE_UNCHANGED))
        if (restored)
            unchanged = savedInstanceState.getString(STATE_UNCHANGED)

        spinner = (hookId != null)

        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DoppelTheme {
                    HookEditScreen()
                }
            }
        })

        // A definition which is still loading has no changes
        confirmLeaving(Supplier { unchanged != null && unchanged != getEntered() })

        load(hookId, !restored)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ID, hookId)
        // Not while loading, the fields are filled after a recreation then
        if (unchanged != null && !refill)
            outState.putString(STATE_UNCHANGED, unchanged)
        outState.putInt(STATE_TAB, tab)
        outState.putBundle(STATE_FIELDS, Bundle().apply {
            putString("collection", collection)
            putString("group", group)
            putString("name", name)
            putString("author", author)
            putString("version", version)
            putString("description", description)
            putString("className", className)
            putString("method", method)
            putString("params", params)
            putString("returnType", returnType)
            putString("minSdk", minSdk)
            putString("maxSdk", maxSdk)
            putString("minApk", minApk)
            putString("maxApk", maxApk)
            putString("exclude", exclude)
            putString("settings", settings)
            putString("script", script)
            putBoolean("enabled", enabled)
            putBoolean("optional", optional)
            putBoolean("usage", usage)
            putBoolean("notify", notify)
        })
    }

    override fun onDestroy() {
        scriptHighlighter?.stop()
        super.onDestroy()
    }

    // The fields show the definition, unless they show what was entered before a recreation.
    // An existing definition cannot be saved until it is loaded (see HookEditScreen), so a failed load is retried.
    private fun load(id: String?, fill: Boolean) {
        val context = applicationContext
        // Until the fields show the definition the first time, also on a retry; a later load, see delete, runs in the
        // background and leaves the spinner alone
        if (loading && id != null) {
            if (Looper.myLooper() == Looper.getMainLooper())
                spinner = true
            else
                postUnlessGone { spinner = true }
        }
        Ui.submit(Ui.Task {
            // Only an existing definition needs its script, the others none
            val hooks = Data.getAllHooks(context, id)
            val ids = Data.getBuiltinIds(context)
            val names = Data.getGroupNames(hooks)
            var found: XHook? = null
            if (id != null)
                for (h in hooks)
                    if (id == h.id)
                        found = h
            val loadedHook = found
            val jhook = if (loadedHook == null) null else Data.toEditable(loadedHook)
            runOnUiThread {
                builtinIds = ids.toSet()
                knownIds.clear()
                knownIds.addAll(hooks.map { it.id })
                groups = names
                if (jhook != null) {
                    hook = loadedHook
                    if (fill)
                        fill(jhook)
                }
                loading = false
                refill = false
                spinner = false
            }
        }, Consumer { ex ->
            postUnlessGone { spinner = false }
            // This screen does not load again by itself
            Ui.error(this, TAG, ex, Runnable { load(id, fill) }, Snackbar.LENGTH_INDEFINITE)
        })
    }

    // The number fields hold plain digits, see number, not numbers in the format of the language
    private fun fill(jhook: JSONObject) {
        // Setting the text cleared the errors of the fields, as it did for the views
        errors.clear()
        collection = jhook.optString("collection")
        group = jhook.optString("group")
        name = jhook.optString("name")
        author = jhook.optString("author")
        version = jhook.optInt("version", 0).toString()
        description = jhook.optString("description", "")
        className = jhook.optString("className")
        method = jhook.optString("methodName", "")
        params = join(jhook.optJSONArray("parameterTypes"))
        returnType = jhook.optString("returnType", "")
        minSdk = jhook.optInt("minSdk", 1).toString()
        maxSdk = jhook.optInt("maxSdk", 999).toString()
        minApk = if (jhook.optInt("minApk", 0) == 0) "" else jhook.optInt("minApk").toString()
        maxApk = if (jhook.optInt("maxApk", Int.MAX_VALUE) == Int.MAX_VALUE) ""
        else jhook.optInt("maxApk").toString()
        exclude = jhook.optString("excludePackages", "")
        settings = join(jhook.optJSONArray("settings"))
        enabled = jhook.optBoolean("enabled", true)
        optional = jhook.optBoolean("optional", false)
        usage = jhook.optBoolean("usage", true)
        notify = jhook.optBoolean("notify", false)
        script = jhook.optString("luaScript")
        unchanged = getEntered()
    }

    // All fields and switches, to tell whether something was changed, as a digest: the text has the script, which the
    // state of its field has already, and the saved state of a screen is limited to about 1 MB
    private fun getEntered(): String {
        val text = StringBuilder()
        for (value in arrayOf(collection, group, name, author, version, description,
            className, method, params, returnType, minSdk, maxSdk, minApk, maxApk, exclude,
            settings, script))
            text.append(value).append('\u0000')
        for (checked in arrayOf(enabled, optional, usage, notify))
            text.append(checked)
        return UUID.nameUUIDFromBytes(text.toString().toByteArray(StandardCharsets.UTF_8)).toString()
    }

    private fun join(jarray: JSONArray?): String {
        if (jarray == null)
            return ""
        val items = ArrayList<String>()
        for (i in 0 until jarray.length())
            items.add(jarray.optString(i))
        return items.joinToString(", ")
    }

    private fun split(value: String): JSONArray = JSONArray(Util.split(value))

    // Shows the error at the field
    private fun number(value: String, field: Field, empty: Int): Int? {
        errors.remove(field)
        val text = value.trim()
        if (text.isEmpty())
            return empty
        return try {
            text.toInt()
        } catch (ex: NumberFormatException) {
            errors[field] = getString(R.string.msg_invalid, text)
            focusField(field)
            null
        }
    }

    // Shows the error at the field of the maximum when it is below the minimum
    private fun below(field: Field, max: Int, min: Int): Boolean {
        if (max >= min)
            return false
        errors[field] = getString(R.string.msg_invalid_max, min.toString())
        focusField(field)
        return true
    }

    private fun focusField(field: Field) {
        if (tab == 0)
            focusRequesters[field]?.requestFocus()
    }

    // Shows the error at the field, which takes the focus, and so scrolls into view; false when it is fine
    private fun invalid(field: Field, bad: Boolean, @StringRes message: Int): Boolean {
        if (bad)
            errors[field] = getString(message)
        else
            errors.remove(field)
        if (bad) {
            if (field == Field.Script) {
                tab = 1
                scriptFocusPending = true
            } else
                focusField(field)
        }
        return bad
    }

    // The fields which every definition needs, before the definition is built, and in the order they are shown
    private fun requiredMissing(): Boolean {
        val collectionText = collection.trim()
        return invalid(Field.Collection, collectionText.isEmpty(), R.string.msg_required) ||
                invalid(
                    Field.Collection,
                    collectionText.contains(',') || collection != collectionText,
                    R.string.msg_collection_invalid
                ) ||
                invalid(Field.Group, group.trim().isEmpty(), R.string.msg_required) ||
                invalid(Field.Name, name.trim().isEmpty(), R.string.msg_required) ||
                invalid(Field.Author, author.trim().isEmpty(), R.string.msg_required) ||
                invalid(Field.ClassName, className.trim().isEmpty(), R.string.msg_required) ||
                invalid(Field.Script, script.isEmpty(), R.string.msg_required)
    }

    // The definition as entered, null with an error shown at a field
    private fun getDefinition(): JSONObject? {
        if (requiredMissing())
            return null
        val versionValue = number(version, Field.Version, 0)
        val minSdkValue = number(minSdk, Field.MinSdk, 1)
        val maxSdkValue = number(maxSdk, Field.MaxSdk, 999)
        val minApkValue = number(minApk, Field.MinApk, 0)
        val maxApkValue = number(maxApk, Field.MaxApk, Int.MAX_VALUE)
        if (versionValue == null || minSdkValue == null || maxSdkValue == null ||
            minApkValue == null || maxApkValue == null
        )
            return null
        // A maximum below the minimum would make the definition available nowhere
        if (below(Field.MaxSdk, maxSdkValue, minSdkValue) ||
            below(Field.MaxApk, maxApkValue, minApkValue)
        )
            return null

        val jhook = JSONObject()
        jhook.put("collection", collection.trim())
        jhook.put("group", group.trim())
        jhook.put("name", name.trim())
        jhook.put("author", author.trim())
        jhook.put("version", versionValue)
        if (description.trim().isNotEmpty())
            jhook.put("description", description)
        jhook.put("className", className.trim())
        if (method.trim().isNotEmpty())
            jhook.put("methodName", method.trim())
        jhook.put("parameterTypes", split(params))
        if (returnType.trim().isNotEmpty())
            jhook.put("returnType", returnType.trim())
        jhook.put("minSdk", minSdkValue)
        jhook.put("maxSdk", maxSdkValue)
        jhook.put("minApk", minApkValue)
        jhook.put("maxApk", maxApkValue)
        if (exclude.trim().isNotEmpty())
            jhook.put("excludePackages", exclude.trim().replace(Regex("\\s+"), ""))
        jhook.put("enabled", enabled)
        jhook.put("optional", optional)
        jhook.put("usage", usage)
        jhook.put("notify", notify)
        jhook.put("luaScript", script)
        val settingsArray = split(settings)
        if (settingsArray.length() > 0)
            jhook.put("settings", settingsArray)
        // Not a field of the editor, kept as the definition has it
        val deoptimize = hook?.deoptimize
        if (deoptimize != null && deoptimize.isNotEmpty())
            jhook.put("deoptimize", JSONArray(deoptimize.toList()))
        return jhook
    }

    // The definition as entered, null with an error shown at a field or as a message
    private fun getDefinitionOrShowError(): JSONObject? = try {
        getDefinition()
    } catch (ex: Throwable) {
        Ui.message(this, getString(R.string.msg_error, ex.message))
        null
    }

    // The enabled switch of the definition enables or disables it
    private fun save() {
        val jhook = getDefinitionOrShowError() ?: return

        // The fields as saved, also when they change meanwhile
        val entered = getEntered()
        // A built-in definition saved unchanged would become a custom copy, which updates of the app no longer reach
        if (hook?.isBuiltin == true && entered == unchanged) {
            Ui.message(this, getString(R.string.msg_no_changes))
            return
        }
        val context = applicationContext
        // A new definition, also one saved with another name, is not assigned to apps yet
        val before = hook?.id
        val store = Runnable {
            run(TAG, Ui.Task {
                check(jhook)
                val saved = Data.putCustom(context, jhook)
                runOnUiThread {
                    knownIds.add(saved.id)
                    hook = saved
                    hookId = saved.id
                    unchanged = entered
                }
                if (saved.id == before)
                    Ui.message(this, getString(R.string.msg_hook_saved))
                else
                    Ui.message(
                        this,
                        if (before == null)
                            getString(R.string.msg_hook_saved)
                        else
                            getString(R.string.msg_hook_saved_as, saved.id, before),
                        getString(R.string.title_restrict_too),
                        Runnable {
                            run(TAG, Ui.Task {
                                Ui.message(
                                    this,
                                    Data.restrictForGroups(context, listOf(saved))
                                )
                            })
                        }
                    )
            })
        }
        // Another definition with the id entered would be gone: a custom one with its script, a built-in one in
        // all apps which have it, which import, Downloads and a restore ask about too
        val id = XHook.id(jhook.optString("collection"), jhook.optString("name"))
        if (id == before || !knownIds.contains(id))
            store.run()
        else
            Ui.areYouSure(
                this,
                if (builtinIds.contains(id))
                    getString(R.string.msg_import_replaces, "\n• $id")
                else
                    getString(R.string.msg_hook_replaces, id),
                R.string.menu_save,
                store
            )
    }

    // Deleting a definition which replaces a built-in one brings back the built-in one, which is shown then
    private fun delete() {
        val current = hook ?: return
        val id = current.id
        val restores = builtinIds.contains(id)
        Ui.areYouSure(
            this,
            getString(
                if (restores) R.string.msg_remove_restores else R.string.msg_delete_sure,
                id
            ),
            if (restores) R.string.menu_restore_builtin else R.string.menu_delete,
            Runnable {
                // A recreation before the built-in one is shown shows it too, not what was deleted
                refill = restores
                val context = applicationContext
                run(TAG, Ui.Task {
                    try {
                        Data.putHook(context, id, null)
                    } catch (ex: Throwable) {
                        // Nothing replaces the fields, so a recreation keeps what is entered
                        runOnUiThread { refill = false }
                        throw ex
                    }
                    if (restores) {
                        load(id, true)
                        Ui.message(this, getString(R.string.msg_builtin_restored, id))
                    } else
                        runOnUiThread { finish() }
                })
            }
        )
    }

    private fun export(uri: Uri) {
        val jhook = getDefinitionOrShowError() ?: return
        val context = applicationContext
        run(TAG, Ui.Task {
            Util.writeJson(context, uri, jhook.toString(2))
            Ui.message(this, resources.getQuantityString(R.plurals.msg_hooks_exported, 1, 1))
        })
    }

    // Resolve the target in this app, like the hook engine does in an app,
    // which has the framework classes, but not the classes of other apps
    private fun checkTarget() {
        val className = className.trim()
        val member = method.trim()
        val params = split(params)
        val returnType = returnType.trim()
        val context = applicationContext
        checkResult = getString(R.string.title_checking)
        submit(Runnable {
            val result = getCheckResult(context, className, member, params, returnType)
            runOnUiThread { checkResult = result }
        })
    }

    // With the resolution of the engine, see XResolver.resolveTarget, so what is found here is what is hooked
    private fun getCheckResult(
        context: Context,
        className: String,
        member: String,
        params: JSONArray,
        returnType: String
    ): String {
        val loader = context.classLoader
        var resolving = className // For the message when a class is missing
        try {
            // System services are hooked in their implementation, like the engine does, see XHook.resolveClassName
            val target = XHook.fromJSONObject(
                JSONObject()
                    .put("collection", "").put("group", "").put("name", "").put("author", "")
                    .put("className", className).put("parameterTypes", JSONArray())
                    .put("minSdk", 1).put("luaScript", "")
            )
            target.resolveClassName(context)
            val cls = XResolver.resolveClass(target.resolvedClassName, loader)

            val types = Array(params.length()) { i ->
                resolving = params.getString(i)
                XResolver.resolveClass(resolving, loader)
            }
            resolving = returnType
            val ret = if (returnType.isEmpty()) null else XResolver.resolveClass(returnType, loader)
            resolving = className

            val name = if (member.isEmpty()) null else member
            try {
                return getString(
                    R.string.title_check_found,
                    XResolver.resolveTarget(cls, name, types, ret).toString()
                )
            } catch (ex: Throwable) {
                // Tell when only the return type does not fit
                if (ret != null)
                    try {
                        val other = XResolver.resolveTarget(cls, name, types, null)
                        return getString(
                            R.string.title_check_return_type,
                            other.toString(),
                            returnType
                        )
                    } catch (ignored: Throwable) {
                    }
                throw ex
            }
        } catch (ex: NoSuchFieldException) {
            // The engine refuses a field with parameter types too
            if (member.startsWith("#") && params.length() > 0)
                return getString(R.string.title_check_field_params)
            return getString(
                R.string.title_check_no_member,
                if (member.isEmpty()) "<init>" else member
            )
        } catch (ex: NoSuchMethodException) {
            if (member.startsWith("#") && params.length() > 0)
                return getString(R.string.title_check_field_params)
            return getString(
                R.string.title_check_no_member,
                if (member.isEmpty()) "<init>" else member
            )
        } catch (ex: NullPointerException) {
            // field:method with a field which is null or not static
            return getString(R.string.title_check_no_member, member)
        } catch (ex: ClassNotFoundException) {
            return getString(R.string.title_check_no_class, resolving)
        } catch (ex: NoClassDefFoundError) {
            return getString(R.string.title_check_no_class, resolving)
        } catch (ex: Throwable) {
            return getString(R.string.msg_error, ex.toString())
        }
    }

    @Composable
    private fun HookEditScreen() {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .union(WindowInsets.ime)
                )
        ) {
            EditorBar()
            PrimaryTabRow(
                selectedTabIndex = tab,
                modifier = Modifier.padding(horizontal = 16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {}
            ) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = {
                        Text(
                            stringResource(R.string.title_tab_definition),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1
                        )
                    }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = {
                        Text(
                            stringResource(R.string.title_tab_script),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1
                        )
                    }
                )
            }
            Box(Modifier.fillMaxSize().weight(1f)) {
                val holder = rememberSaveableStateHolder()
                if (tab == 0)
                    holder.SaveableStateProvider("definition") { DefinitionTab() }
                else
                    holder.SaveableStateProvider("script") { ScriptTab() }

                if (spinner)
                    Loading(Modifier.align(Alignment.Center))
            }
        }
    }

    @Composable
    private fun EditorBar() {
        var menu by remember { mutableStateOf(false) }
        val current = hook
        val title = hookId ?: stringResource(R.string.title_hook_new)
        val subtitle = when {
            current == null -> null
            current.isBuiltin -> R.string.title_hook_builtin
            builtinIds.contains(current.id) -> R.string.title_hook_overridden
            else -> R.string.title_hook_custom
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onBackPressedDispatcher.onBackPressed() }) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.title_back)
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 8.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null)
                    Text(
                        stringResource(subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
            }
            IconButton(
                onClick = { save() },
                enabled = (hookId == null || !loading)
            ) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(R.string.menu_save)
                )
            }
            if (current != null) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            painterResource(R.drawable.ic_more),
                            contentDescription = stringResource(R.string.title_more)
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_export)) },
                            onClick = {
                                menu = false
                                Ui.pick(
                                    this@ActivityHookEdit,
                                    exportLauncher,
                                    name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"
                                )
                            }
                        )
                        if (!current.isBuiltin)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (builtinIds.contains(current.id))
                                                R.string.menu_restore_builtin
                                            else
                                                R.string.menu_delete
                                        )
                                    )
                                },
                                onClick = {
                                    menu = false
                                    delete()
                                }
                            )
                    }
                }
            }
        }
    }

    @Composable
    private fun DefinitionTab() {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            ) {
                if (hook?.isBuiltin == true) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 10.dp)
                    ) {
                        Text(
                            stringResource(R.string.title_hook_edit_builtin),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        )
                    }
                }

                SectionTitle(R.string.title_section_target)
                FormCard {
                    FormField(
                        value = className,
                        onValueChange = {
                            className = it
                            errors.remove(Field.ClassName)
                        },
                        label = R.string.title_field_class,
                        modifier = Modifier.fillMaxWidth(),
                        mono = true,
                        ltr = true,
                        focusRequester = focusRequesters[Field.ClassName]
                    )
                    FormField(
                        value = method,
                        onValueChange = { method = it },
                        label = R.string.title_field_member,
                        helper = R.string.title_field_member_hint,
                        modifier = Modifier.fillMaxWidth(),
                        mono = true,
                        ltr = true
                    )
                    FormField(
                        value = params,
                        onValueChange = { params = it },
                        label = R.string.title_field_params,
                        helper = R.string.title_field_params_hint,
                        modifier = Modifier.fillMaxWidth(),
                        mono = true,
                        ltr = true
                    )
                    FormField(
                        value = returnType,
                        onValueChange = { returnType = it },
                        label = R.string.title_field_return,
                        modifier = Modifier.fillMaxWidth(),
                        mono = true,
                        ltr = true
                    )
                    CheckStrip()
                }

                SectionTitle(R.string.title_tab_definition)
                FormCard {
                    FormField(
                        value = collection,
                        onValueChange = {
                            collection = it
                            errors.remove(Field.Collection)
                        },
                        label = R.string.title_field_collection,
                        errorMessage = errors[Field.Collection],
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = focusRequesters[Field.Collection]
                    )
                    GroupField()
                    FormField(
                        value = name,
                        onValueChange = {
                            name = it
                            errors.remove(Field.Name)
                        },
                        label = R.string.title_field_name,
                        helper = R.string.title_field_name_hint,
                        errorMessage = errors[Field.Name],
                        modifier = Modifier.fillMaxWidth(),
                        mono = true,
                        focusRequester = focusRequesters[Field.Name]
                    )
                    Row(Modifier.fillMaxWidth()) {
                        FormField(
                            value = author,
                            onValueChange = {
                                author = it
                                errors.remove(Field.Author)
                            },
                            label = R.string.title_field_author,
                            errorMessage = errors[Field.Author],
                            modifier = Modifier
                                .weight(2f)
                                .padding(end = 8.dp),
                            focusRequester = focusRequesters[Field.Author]
                        )
                        FormField(
                            value = version,
                            onValueChange = {
                                version = it
                                errors.remove(Field.Version)
                            },
                            label = R.string.title_field_version,
                            errorMessage = errors[Field.Version],
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                            focusRequester = focusRequesters[Field.Version]
                        )
                    }
                    FormField(
                        value = description,
                        onValueChange = { description = it },
                        label = R.string.title_field_description,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 8
                    )
                }

                SectionTitle(R.string.title_section_availability)
                FormCard {
                    Row(Modifier.fillMaxWidth()) {
                        FormField(
                            value = minSdk,
                            onValueChange = {
                                minSdk = it
                                errors.remove(Field.MinSdk)
                            },
                            label = R.string.title_field_min_sdk,
                            errorMessage = errors[Field.MinSdk],
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            focusRequester = focusRequesters[Field.MinSdk]
                        )
                        FormField(
                            value = maxSdk,
                            onValueChange = {
                                maxSdk = it
                                errors.remove(Field.MaxSdk)
                            },
                            label = R.string.title_field_max_sdk,
                            errorMessage = errors[Field.MaxSdk],
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                            focusRequester = focusRequesters[Field.MaxSdk]
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        FormField(
                            value = minApk,
                            onValueChange = {
                                minApk = it
                                errors.remove(Field.MinApk)
                            },
                            label = R.string.title_field_min_apk,
                            errorMessage = errors[Field.MinApk],
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            focusRequester = focusRequesters[Field.MinApk]
                        )
                        FormField(
                            value = maxApk,
                            onValueChange = {
                                maxApk = it
                                errors.remove(Field.MaxApk)
                            },
                            label = R.string.title_field_max_apk,
                            errorMessage = errors[Field.MaxApk],
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                            focusRequester = focusRequesters[Field.MaxApk]
                        )
                    }
                    FormField(
                        value = exclude,
                        onValueChange = { exclude = it },
                        label = R.string.title_field_exclude,
                        helper = R.string.title_field_comma,
                        modifier = Modifier.fillMaxWidth(),
                        mono = true,
                        ltr = true
                    )
                    FormField(
                        value = settings,
                        onValueChange = { settings = it },
                        label = R.string.title_field_settings,
                        helper = R.string.title_field_settings_hint,
                        modifier = Modifier.fillMaxWidth(),
                        mono = true,
                        ltr = true
                    )
                }

                SectionTitle(R.string.title_section_behavior)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    ),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        SwitchRow(
                            title = R.string.title_hook_enabled,
                            hint = R.string.title_hook_enabled_hint,
                            checked = enabled,
                            onCheckedChange = { enabled = it }
                        )
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        SwitchRow(
                            title = R.string.title_hook_optional,
                            hint = R.string.title_hook_optional_hint,
                            checked = optional,
                            onCheckedChange = { optional = it }
                        )
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        SwitchRow(
                            title = R.string.title_hook_usage,
                            hint = R.string.title_hook_usage_hint,
                            checked = usage,
                            onCheckedChange = { usage = it }
                        )
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        SwitchRow(
                            title = R.string.title_hook_notify,
                            hint = R.string.title_hook_notify_hint,
                            checked = notify,
                            onCheckedChange = { notify = it }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SectionTitle(@StringRes title: Int) {
        val locale = LocalConfiguration.current.locales[0]
        Text(
            stringResource(title).uppercase(locale),
            Modifier
                .padding(start = 4.dp, top = 20.dp, bottom = 6.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 0.08.em
            )
        )
    }

    @Composable
    private fun FormCard(content: @Composable () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
            ),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
            ) {
                content()
            }
        }
    }

    @Composable
    private fun FormField(
        value: String,
        onValueChange: (String) -> Unit,
        @StringRes label: Int,
        modifier: Modifier,
        @StringRes helper: Int? = null,
        errorMessage: String? = null,
        mono: Boolean = false,
        ltr: Boolean = false,
        keyboardType: KeyboardType = KeyboardType.Text,
        singleLine: Boolean = true,
        maxLines: Int = 1,
        focusRequester: FocusRequester? = null
    ) {
        val supporting = errorMessage ?: helper?.let { stringResource(it) }
        val baseStyle = MaterialTheme.typography.bodyMedium
        val style = if (mono || ltr)
            baseStyle.copy(
                fontFamily = if (mono) Mono else FontFamily.Default,
                textDirection = if (ltr) TextDirection.Ltr else TextDirection.Unspecified
            )
        else
            baseStyle
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .padding(top = 10.dp)
                .then(
                    if (focusRequester == null) Modifier
                    else Modifier.focusRequester(focusRequester)
                )
                .then(
                    if (errorMessage == null) Modifier
                    else Modifier.semantics { error(errorMessage) }
                ),
            label = { Text(stringResource(label)) },
                        supportingText = if (supporting == null) null else {
                { Text(supporting) }
            },

            isError = (errorMessage != null),
            singleLine = singleLine,
            maxLines = maxLines,
            textStyle = style,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent
            )
        )
    }

    @Composable
    private fun GroupField() {
        var expanded by remember { mutableStateOf(false) }
        val error = errors[Field.Group]
        val matches = groups.filter { group.isEmpty() || it.startsWith(group, ignoreCase = true) }
        val open = expanded && matches.isNotEmpty()
        ExposedDropdownMenuBox(
            expanded = open,
            onExpandedChange = { expanded = it },
            modifier = Modifier.padding(top = 10.dp)
        ) {
            OutlinedTextField(
                value = group,
                onValueChange = {
                    group = it
                    errors.remove(Field.Group)
                    expanded = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .then(
                        if (error == null) Modifier
                        else Modifier.semantics { error(error) }
                    )
                    .focusRequester(focusRequesters.getValue(Field.Group)),
                label = { Text(stringResource(R.string.title_field_group)) },
                supportingText = error?.let { message -> { Text(message) } },
                isError = (error != null),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = open
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent
                )
            )
            ExposedDropdownMenu(
                expanded = open,
                onDismissRequest = { expanded = false }
            ) {
                matches.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item) },
                        onClick = {
                            group = item
                            errors.remove(Field.Group)
                            expanded = false
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun CheckStrip() {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                checkResult ?: stringResource(R.string.title_check_hint),
                Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = { checkTarget() }) {
                Text(stringResource(R.string.title_check))
            }
        }
    }

    @Composable
    private fun SwitchRow(
        @StringRes title: Int,
        @StringRes hint: Int,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        val description = stringResource(title)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(hint),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.semantics { contentDescription = description }
            )
        }
    }

    @Composable
    private fun ScriptTab() {
        val error = errors[Field.Script]
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colorResource(R.color.c_code_background))
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.title_script_file),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = Mono,
                            fontSize = 12.sp,
                            color = colorResource(R.color.c_code_comment)
                        )
                    )
                    Text(
                        scriptInfo(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = Mono,
                            fontSize = 12.sp,
                            color = colorResource(R.color.c_code_comment)
                        )
                    )
                }
                ScriptEditor(Modifier.weight(1f).fillMaxWidth())
            }
            if (error != null)
                Text(
                    error,
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.error
                    )
                )
            if (!scriptFocused)
                SelectionContainer {
                    Text(
                        stringResource(R.string.title_script_api),
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = Mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
        }

        LaunchedEffect(scriptFocusPending) {
            if (scriptFocusPending) {
                scriptFocusPending = false
                scriptEditText?.requestFocus()
            }
        }
    }

    @Composable
    private fun scriptInfo(): String {
        val functions = ArrayList<String>()
        if (FUNCTION_BEFORE.matcher(script).find())
            functions.add("before()")
        if (FUNCTION_AFTER.matcher(script).find())
            functions.add("after()")
        return if (functions.isEmpty())
            stringResource(R.string.title_script_no_function)
        else
            functions.joinToString(" · ")
    }

    @Composable
    private fun ScriptEditor(modifier: Modifier) {
        val textColor = colorResource(R.color.c_code_text)
        val hintColor = colorResource(R.color.c_code_comment)
        val hint = stringResource(R.string.title_tab_script)
        val context = androidx.compose.ui.platform.LocalContext.current
        val font = remember(context) {
            ResourcesCompat.getFont(context, R.font.plex_mono)
        }
        val density = LocalDensity.current
        val padding = with(density) { 14.dp.roundToPx() }

        AndroidView(
            factory = { ctx ->
                EditText(ctx).apply {
                    background = null
                    typeface = font ?: Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = Gravity.TOP or Gravity.START
                    this.hint = hint
                    setHintTextColor(hintColor.toArgb())
                    setTextColor(textColor.toArgb())
                    inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                    setPadding(padding, 0, padding, padding)
                    setHorizontallyScrolling(true)
                    isVerticalScrollBarEnabled = true
                    isHorizontalScrollBarEnabled = true
                    textDirection = View.TEXT_DIRECTION_LTR
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        textCursorDrawable = null
                    setText(script)
                    scriptEditText = this
                    scriptHighlighter = LuaHighlighter(this).also { it.highlight() }
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence,
                            start: Int,
                            count: Int,
                            after: Int
                        ) {
                        }

                        override fun onTextChanged(
                            s: CharSequence,
                            start: Int,
                            before: Int,
                            count: Int
                        ) {
                        }

                        override fun afterTextChanged(s: Editable) {
                            script = s.toString()
                            errors.remove(Field.Script)
                        }
                    })
                    setOnFocusChangeListener { _, focused -> scriptFocused = focused }
                }
            },
            modifier = modifier,
            update = { editText ->
                if (editText.text.toString() != script) {
                    editText.setText(script)
                    scriptHighlighter?.highlight()
                }
            },
            onRelease = { editText ->
                if (scriptEditText === editText) {
                    scriptEditText = null
                    scriptHighlighter?.stop()
                    scriptHighlighter = null
                    scriptFocused = false
                }
            }
        )
    }
}
