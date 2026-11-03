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

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Prototype;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// Validates the built-in hook definitions (assets/hooks.json) and their Lua scripts
public class HookDefinitionsTest {
    private static final File ASSETS = new File("src/main/assets");
    private static final File STRINGS = new File("src/main/res/values/strings.xml");

    // Hidden framework classes, which are not part of the SDK, and a class of which the hooked members are hidden
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "android.os.SystemProperties", "android.app.ContextImpl", "android.app.Instrumentation",
            "android.app.LoadedApk$ServiceDispatcher"));

    // The defaults XHook.fromJSONObject uses for missing attributes, see docs/DEFINE.md
    private static final Map<String, Object> DEFAULTS = new HashMap<>();

    static {
        DEFAULTS.put("builtin", false);
        DEFAULTS.put("version", 0);
        DEFAULTS.put("maxSdk", 999);
        DEFAULTS.put("minApk", 0);
        DEFAULTS.put("maxApk", Integer.MAX_VALUE);
        DEFAULTS.put("enabled", true);
        DEFAULTS.put("optional", false);
        DEFAULTS.put("usage", true);
        DEFAULTS.put("notify", false);
    }

    private static List<JSONObject> hooks;

    @BeforeClass
    public static void load() throws Exception {
        JSONArray jarray = new JSONArray(readLikeAndroid(new File(ASSETS, "hooks.json")));
        hooks = new ArrayList<>();
        for (int i = 0; i < jarray.length(); i++)
            hooks.add(jarray.getJSONObject(i));
        assertTrue(hooks.size() > 0);
    }

    // Android's org.json (JSONTokener) skips comments between tokens: /* */, and // and # up to the end of the line.
    // The reference implementation, which the unit tests use, does not, so the comments outside strings become spaces,
    // which keeps the positions in parse errors.
    private static String stripComments(String json) {
        StringBuilder sb = new StringBuilder(json);
        char quote = 0;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            char next = (i + 1 < sb.length() ? sb.charAt(i + 1) : 0);
            if (quote != 0) {
                if (c == '\\')
                    i++;
                else if (c == quote)
                    quote = 0;
            } else if (c == '"' || c == '\'')
                quote = c;
            else if (c == '#' || (c == '/' && next == '/')) {
                for (; i < sb.length() && sb.charAt(i) != '\n' && sb.charAt(i) != '\r'; i++)
                    sb.setCharAt(i, ' ');
            } else if (c == '/' && next == '*') {
                int end = sb.indexOf("*/", i + 2);
                if (end < 0)
                    throw new IllegalArgumentException("Unterminated comment at " + i);
                for (; i < end + 2; i++)
                    if (sb.charAt(i) != '\n' && sb.charAt(i) != '\r')
                        sb.setCharAt(i, ' ');
                i--;
            }
        }
        return sb.toString();
    }

    // A separator before the end of an array is an extra null element for Android's org.json, which fails
    // XHook.readHooks ("Value at n is null"), and before the end of an object a parse error ("Expected literal value");
    // the reference implementation ignores both. For JSON without comments, see stripComments.
    private static void checkSeparators(String name, String json) {
        char quote = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quote != 0) {
                if (c == '\\')
                    i++;
                else if (c == quote)
                    quote = 0;
            } else if (c == '"' || c == '\'')
                quote = c;
            else if (c == ',') {
                int next = i + 1;
                while (next < json.length() && json.charAt(next) <= ' ')
                    next++;
                if (next < json.length() && (json.charAt(next) == ']' || json.charAt(next) == '}')) {
                    int line = 1;
                    for (int l = 0; l < i; l++)
                        if (json.charAt(l) == '\n')
                            line++;
                    throw new IllegalArgumentException(name + ": separator before " + json.charAt(next) + " in line " + line);
                }
            }
        }
    }

    // JSON as the app reads it with Android's org.json, for the reference implementation of the unit tests
    private static String readLikeAndroid(File file) throws Exception {
        String json = stripComments(read(file));
        checkSeparators(file.getName(), json);
        return json;
    }

    // The comments hooks.json can have, also within a line
    @Test
    public void commentsAreSkippedLikeAndroid() throws Exception {
        String json = "/* licence\n */\n" +
                "[ // line\n" +
                "  # line\n" +
                "  {\"a\": \"// # /* */\", /* inline */ \"b\": 29, // trailing\n" +
                "   \"c\": '/* \" */', \"d\": \"\\\" // \\\"\" # trailing\n" +
                "  }/**/]# end";
        String stripped = stripComments(json);
        assertEquals(json.split("\n", -1).length, stripped.split("\n", -1).length);

        JSONArray jarray = new JSONArray(stripped);
        assertEquals(1, jarray.length());
        JSONObject jobject = jarray.getJSONObject(0);
        assertEquals(4, jobject.length());
        assertEquals("// # /* */", jobject.getString("a"));
        assertEquals(29, jobject.getInt("b"));
        assertEquals("/* \" */", jobject.getString("c"));
        assertEquals("\" // \"", jobject.getString("d"));

        try {
            stripComments("[] /* open");
            fail("Unterminated comment accepted");
        } catch (IllegalArgumentException ignored) {
        }
    }

    // Separators at the end of arrays and objects, also before comments, fail; those in strings and comments do not
    @Test
    public void trailingSeparatorsFailLikeAndroid() throws Exception {
        for (String json : new String[]{
                "[{\"a\": 1},]", "[{\"a\": 1}, // last\n]", "[{\"a\": 1, /* last */ }]", "[{\"a\": [1,\n\t]}]"})
            try {
                checkSeparators("test", stripComments(json));
                fail("Trailing separator accepted in " + json);
            } catch (IllegalArgumentException ignored) {
            }

        String json = "[{\"a\": \",]\", \"b\": ',}', \"c\": \"\\\",]\"}, // ,]\n {\"d\": [1, 2]} /* ,} */]";
        checkSeparators("test", stripComments(json));
        assertEquals(2, new JSONArray(stripComments(json)).length());
    }

    @Test
    public void namesAreUnique() throws Exception {
        Set<String> ids = new HashSet<>();
        for (JSONObject hook : hooks) {
            String id = hook.getString("collection") + "." + hook.getString("name");
            assertTrue("Duplicate hook " + id, ids.add(id));
        }
    }

    @Test
    public void groupsHaveTitles() throws Exception {
        String strings = read(STRINGS);
        for (JSONObject hook : hooks) {
            String name = hook.getString("group").toLowerCase().replaceAll("[^a-z]", "_");
            assertTrue("No title for group " + hook.getString("group"),
                    strings.contains("name=\"group_" + name + "\""));
        }
    }

    @Test
    public void scriptsExistAndCompile() throws Exception {
        Set<String> referenced = new HashSet<>();
        for (JSONObject hook : hooks) {
            String script = hook.getString("luaScript");
            String source;
            if (script.startsWith("@")) {
                File file = new File(ASSETS, script.substring(1) + ".lua");
                assertTrue("Missing script " + file + " for " + hook.getString("name"), file.exists());
                referenced.add(file.getName());
                source = read(file);
            } else
                source = script;
            runScript(hook.getString("name"), source);
        }

        // Unreferenced scripts are dead code
        File[] files = ASSETS.listFiles((dir, name) -> name.endsWith(".lua"));
        for (File file : files)
            assertTrue("Unreferenced script " + file.getName(), referenced.contains(file.getName()));
    }

    // The values of filter functions are of the forms XCore.Filter matches: a value, a prefix with * at the end or a
    // part between two *; a * elsewhere, like *font*.xml, is taken as a value no argument has, so the script never runs
    @Test
    public void filtersHaveKnownForms() throws Exception {
        List<String> errors = new ArrayList<>();
        Map<String, Globals> loaded = new HashMap<>();
        for (JSONObject hook : hooks) {
            String script = hook.getString("luaScript");
            if (!script.startsWith("@"))
                continue;
            String name = script.substring(1);
            if (!read(new File(ASSETS, name + ".lua")).contains("function filter("))
                continue;
            Globals globals = loaded.get(name);
            if (globals == null) {
                // The shared storage, which storage_times filters by
                globals = Scripts.load(name, "local bind = luajava.bindClass\n" +
                        "luajava.bindClass = function(n)\n" +
                        "  if n == 'android.os.Environment' then return { getExternalStorageDirectory = function()\n" +
                        "    return { getPath = function() return '/storage/emulated/0' end } end } end\n" +
                        "  return bind(n)\n" +
                        "end");
                loaded.put(name, globals);
            }
            globals.set("H_NAME", hook.getString("name"));
            globals.set("H_GROUP", hook.getString("group"));
            globals.set("H_CLASS", hook.getString("className"));
            globals.set("H_METHOD", hook.optString("methodName", ""));
            globals.set("H_RETURN", hook.optString("returnType", ""));
            LuaValue values = globals.load("return filter({ getName = function() return H_NAME end, " +
                    "getGroup = function() return H_GROUP end, getClassName = function() return H_CLASS end, " +
                    "getMethodName = function() return H_METHOD end, getReturnType = function() return H_RETURN end })").call();
            if (values.isnil())
                continue;
            for (int i = 1; i <= values.length(); i++) {
                String value = values.get(i).tojstring();
                String inner = value.replaceAll("^\\*", "").replaceAll("\\*$", "");
                boolean known = !inner.contains("*") && (!value.startsWith("*") || (value.length() > 1 && value.endsWith("*")));
                if (!known)
                    errors.add(hook.getString("name") + ": " + value);
            }
        }
        assertTrue("Filter values of unknown forms: " + errors, errors.isEmpty());
        assertTrue(loaded.containsKey("font_config"));
    }

    // A hook of a library in the app which does not use the result must not require a return type: R8 makes methods
    // whose result the app does not use return void, which XResolver refuses, like OkHttp's Headers.Builder in LinkedIn.
    // Per hook, because a script shared by several methods, like webview_constructor, can read the result of one only.
    private static final Set<String> APP_HOOKS_WITH_RESULT = new HashSet<>(Arrays.asList(
            "ActivityRecognitionResult.extractResult", "ActivityTransitionResult.extractResult",
            "SleepSegmentEvent.extractEvents", "SleepClassifyEvent.extractEvents",
            "AdvertisingIdClient$Info.getId", "HuaweiAdvertisingIdClient$Info.getId", "AppSetIdInfo.getId",
            "AdvertisingIdClient$Info.isLimitAdTrackingEnabled", "HuaweiAdvertisingIdClient$Info.isLimitAdTrackingEnabled",
            "Amplitude.getOptOut", "AmplitudeClient.isOptedOut", "Branch.isTrackingDisabled",
            "Branch.isTrackingDisabled/context", "Singular.isAllTrackingStopped",
            "FirebaseAnalytics.getInstance", "GoogleAnalytics.getInstance/optout", "AdRegistration.getInstance",
            "FirebaseCrashlytics.getInstance", "AppsFlyerLib.getInstance", "SegmentAnalytics.build",
            "GL10.glGetString", "EGL10.eglQueryString", "RadarJobScheduler.onStartJob"));

    @Test
    public void appLibraryHooksWithoutResultHaveNoReturnType() throws Exception {
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // The built-in definitions and those of Downloads, which have their script inline
        List<JSONObject> all = new ArrayList<>(hooks);
        all.addAll(repository().values());
        for (JSONObject hook : all) {
            String className = hook.getString("className");
            String script = hook.getString("luaScript");
            String methodName = hook.optString("methodName", "");
            String returnType = hook.optString("returnType", "void");
            if (returnType.equals("void") || methodName.startsWith("#") ||
                    className.matches("^(android|androidx|java|javax|com\\.android|libcore|dalvik|sun)\\..*"))
                continue;
            String name = hook.getString("name");
            seen.add(name);
            String source = (script.startsWith("@") ? read(new File(ASSETS, script.substring(1) + ".lua")) : script);
            // Listed hooks are those whose result the script uses, checked when they were listed
            if (!APP_HOOKS_WITH_RESULT.contains(name) ||
                    (!source.contains("setResult(") && !source.contains("getResult(")))
                errors.add(name);
        }
        assertTrue("Return type without result (or not listed): " + errors, errors.isEmpty());
        Set<String> stale = new TreeSet<>(APP_HOOKS_WITH_RESULT);
        stale.removeAll(seen);
        assertTrue("Listed hooks without a return type: " + stale, stale.isEmpty());
    }

    // Runs a script like the hook engine does to define its functions (see XCore.LuaLocals):
    // scripts can define functions, other globals are an error at run time
    private static void runScript(String name, String source) throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        Set<String> standard = new HashSet<>();
        for (LuaValue key : globals.keys())
            standard.add(key.tojstring());

        Prototype prototype = LuaC.instance.compile(
                new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "script");
        new LuaClosure(prototype, globals).call();

        for (LuaValue key : globals.keys())
            if (!standard.contains(key.tojstring()))
                assertTrue(name + " defines global " + key + ", which is not a function",
                        globals.rawget(key).isfunction());
        assertTrue("No before or after function for " + name,
                globals.get("before").isfunction() || globals.get("after").isfunction());
        // The engine calls a function filter with the hook when it installs the hook, see XCore.getFilter
        LuaValue filter = globals.get("filter");
        if (filter.isfunction())
            assertEquals(name + " defines filter, which the engine calls with the hook only",
                    1, ((LuaClosure) filter).p.numparams);
    }

    // Excluded packages are regular expressions, see XHook.validate
    @Test
    public void excludedPackagesCompile() throws Exception {
        for (JSONObject hook : hooks)
            compileExcluded(hook.getString("name"), hook);
    }

    private static void compileExcluded(String name, JSONObject hook) throws Exception {
        if (hook.has("excludePackages"))
            for (String regex : hook.getString("excludePackages").split(","))
                try {
                    Pattern.compile(regex);
                } catch (PatternSyntaxException ex) {
                    fail(name + " excludes invalid " + regex + ": " + ex.getMessage());
                }
    }

    // Groups of built-in hooks have an icon and a section, else they are shown like custom groups
    @Test
    public void groupsArePresented() throws Exception {
        for (JSONObject hook : hooks) {
            String group = hook.getString("group");
            assertTrue("No icon for group " + group, Groups.getIcon(group) != R.drawable.ic_group_custom);
            assertTrue("No section for group " + group, Groups.getSection(group) != R.string.section_other);
        }
    }

    // The definitions offered by Downloads (repo/), which are installed as they are, see Data.putCustom.
    // The list has the id and the time of the last update (seconds) of each definition,
    // the attributes Downloads shows otherwise, like the name and version, are those of the definition,
    // see ActivityDownloads.load
    @Test
    public void repositoryIsValid() throws Exception {
        File repo = new File("../repo");
        JSONArray list = new JSONObject(readLikeAndroid(new File(repo, "list.json"))).getJSONArray("list");
        Set<String> attributes = new HashSet<>(Arrays.asList("id", "updated"));
        Set<String> listed = new HashSet<>();
        Set<String> ids = new HashSet<>();
        int sdk = compileSdk();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject entry = list.getJSONObject(i);
            File file = new File(repo, "hook_" + entry.getInt("id") + ".json");
            assertEquals(file.getName() + " list attributes", attributes, keys(entry));
            assertTrue(file.getName() + " listed twice", listed.add(file.getName()));
            assertTrue(file.getName() + " updated", entry.getLong("updated") > 0);
            assertTrue("Missing " + file, file.exists());

            JSONObject hook = new JSONObject(readLikeAndroid(file));
            for (String key : new String[]{
                    "collection", "group", "name", "author", "version", "className", "parameterTypes", "minSdk", "luaScript"})
                assertTrue(file.getName() + " misses " + key, hook.has(key));
            String id = hook.getString("collection") + "." + hook.getString("name");
            assertTrue(file.getName() + " has the id of another definition " + id, ids.add(id));
            checkRead(file.getName(), hook);
            compileExcluded(file.getName(), hook);
            runScript(file.getName(), hook.getString("luaScript"));
            // Installed hooks which do not resolve report an error for every app, see XCore.install
            if (isFramework(hook, sdk))
                resolve(hook, errors);
        }
        if (!errors.isEmpty())
            fail(errors.size() + " repository hooks do not resolve against SDK " + sdk + ":\n" + String.join("\n", errors));

        File[] files = repo.listFiles((dir, name) -> name.startsWith("hook_"));
        for (File file : files)
            assertTrue("Not listed " + file.getName(), listed.contains(file.getName()));
    }

    // The definitions of Downloads by file name
    private static Map<String, JSONObject> repository() throws Exception {
        Map<String, JSONObject> repository = new HashMap<>();
        for (File file : new File("../repo").listFiles((dir, name) -> name.startsWith("hook_")))
            repository.put(file.getName(), new JSONObject(readLikeAndroid(file)));
        return repository;
    }

    // Scripts of Downloads do not write to the log, like the built-in scripts: the app can read the lines
    // of its own uid, which show Doppel and the real values
    @Test
    public void repositoryScriptsDoNotLog() throws Exception {
        Pattern log = Pattern.compile("(^|[^\\w.:])log\\s*\\(", Pattern.MULTILINE);
        Set<String> logging = new TreeSet<>();
        for (Map.Entry<String, JSONObject> entry : repository().entrySet())
            if (log.matcher(entry.getValue().getString("luaScript").replaceAll("--[^\n]*", "")).find())
                logging.add(entry.getKey());
        assertTrue("Scripts which log: " + logging, logging.isEmpty());
    }

    // Every getDrawable of WallpaperManager of the SDK is hidden, not only the one without arguments,
    // which delegates to getDrawable(int) since Android 14
    @Test
    public void wallpaperOverloadsAreHidden() throws Exception {
        int sdk = compileSdk();
        Collection<JSONObject> repository = repository().values();
        for (Method method : Class.forName("android.app.WallpaperManager").getMethods()) {
            if (!method.getName().equals("getDrawable"))
                continue;
            boolean hooked = false;
            for (JSONObject hook : repository)
                if (hook.getString("className").equals("android.app.WallpaperManager")
                        && method.getName().equals(hook.optString("methodName")) && isFramework(hook, sdk)) {
                    JSONArray jparams = hook.getJSONArray("parameterTypes");
                    String[] names = new String[jparams.length()];
                    for (int i = 0; i < names.length; i++)
                        names[i] = jparams.getString(i);
                    hooked |= Arrays.equals(method.getParameterTypes(),
                            XResolver.resolveClasses(names, getClass().getClassLoader()));
                }
            assertTrue("Not hooked on SDK " + sdk + ": " + method, hooked);
        }
    }

    // Each definition carries its own script (no includes); the probes and the list filters of Intika-Google-FCM
    // carry one script each, so a change of the one reaches all copies; the main hook (Intent) has its own
    @Test
    public void fcmScriptsAreShared() throws Exception {
        Map<String, Set<String>> scripts = new HashMap<>();
        for (Map.Entry<String, JSONObject> entry : repository().entrySet())
            if (entry.getValue().getString("collection").equals("Intika-Google-FCM")) {
                String script = entry.getValue().getString("luaScript");
                if (!scripts.containsKey(script))
                    scripts.put(script, new TreeSet<>());
                scripts.get(script).add(entry.getKey());
            }
        assertTrue("Scripts of Intika-Google-FCM: " + scripts.values(), scripts.size() <= 3);
    }

    // Downloads offers an update of an installed definition only for a higher version (ActivityDownloads.getState),
    // so every earlier text of a definition in the history has a lower version than the current one
    @Test
    public void repositoryVersionsRaised() throws Exception {
        File root = new File("..").getCanonicalFile();
        Assume.assumeTrue("No git history", git(root, "rev-parse", "--git-dir") != null);
        Set<String> errors = new TreeSet<>();
        for (Map.Entry<String, JSONObject> entry : repository().entrySet()) {
            String path = "repo/" + entry.getKey();
            File file = new File(root, path);
            JSONObject current = entry.getValue();
            String log = git(root, "log", "--format=%H", "--", path);
            for (String commit : (log == null ? "" : log).split("\n")) {
                String text = (commit.isEmpty() ? null : git(root, "show", commit + ":" + path));
                if (text == null)
                    continue; // Removed by the commit
                JSONObject earlier = new JSONObject(text);
                if (!text.trim().replace("\\/", "/").equals(read(file).trim().replace("\\/", "/"))
                        && earlier.optInt("version") >= current.optInt("version"))
                    errors.add(entry.getKey() + " version " + current.optInt("version") +
                            " differs from version " + earlier.optInt("version") + " of " + commit.substring(0, 7));
            }
        }
        if (!errors.isEmpty())
            fail(String.join("\n", errors));
    }

    // The output of a git command, or null if it fails
    private static String git(File dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList(args));
        command.add(0, "git");
        try {
            Process process = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
            byte[] out = readAll(process.getInputStream());
            return (process.waitFor() == 0 ? new String(out, StandardCharsets.UTF_8) : null);
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; )
            out.write(buffer, 0, n);
        return out.toByteArray();
    }

    // The settings attribute declares the settings a script reads, which the values screen offers.
    // Scripts shared by hooks can read a setting for some of the hooks only.
    @Test
    public void settingsAreDeclared() throws Exception {
        Pattern pattern = Pattern.compile("getSetting\\('([^']+)'\\)");
        Map<String, Set<String>> read = new HashMap<>();
        Map<String, Set<String>> declared = new HashMap<>();
        for (JSONObject hook : hooks) {
            String script = hook.getString("luaScript");
            if (!read.containsKey(script)) {
                Set<String> keys = new HashSet<>();
                Matcher m = pattern.matcher(read(new File(ASSETS, script.substring(1) + ".lua")));
                while (m.find())
                    keys.add(m.group(1));
                read.put(script, keys);
                declared.put(script, new HashSet<>());
            }

            JSONArray jsettings = hook.optJSONArray("settings");
            if (jsettings != null)
                for (int i = 0; i < jsettings.length(); i++) {
                    String key = jsettings.getString(i);
                    assertTrue(hook.getString("name") + " does not read " + key, read.get(script).contains(key));
                    declared.get(script).add(key);
                }
        }
        for (String script : read.keySet())
            assertEquals("Settings of " + script, read.get(script), declared.get(script));
    }

    // Framework hooks must exist in the SDK the app is compiled against,
    // unless they are optional, hidden or limited to older versions
    @Test
    public void frameworkMembersExist() throws Exception {
        int sdk = compileSdk();
        int checked = 0;
        List<String> errors = new ArrayList<>();
        for (JSONObject hook : hooks)
            if (isFramework(hook, sdk)) {
                checked++;
                resolve(hook, errors);
            }
        assertTrue("Only " + checked + " hooks checked against SDK " + sdk, sdk >= 36 && checked > 100);
        if (!errors.isEmpty())
            fail(errors.size() + " hooks do not resolve against SDK " + sdk + ":\n" + String.join("\n", errors));
    }

    // A non-optional hook of a framework class of the SDK, for the SDK the app is compiled against
    private static boolean isFramework(JSONObject hook, int sdk) throws Exception {
        String className = hook.getString("className");
        return className.startsWith("android.") && !HIDDEN.contains(className) && !hook.optBoolean("optional", false)
                && hook.getInt("minSdk") <= sdk && hook.optInt("maxSdk", 999) >= sdk;
    }

    // Resolves the hooked member like XCore.install: the class, the member with the parameter types and the return type
    private void resolve(JSONObject hook, List<String> errors) throws Exception {
        String className = hook.getString("className");
        String name = hook.getString("name");
        try {
            Class<?> cls = Class.forName(className, false, getClass().getClassLoader());

            JSONArray jparams = hook.getJSONArray("parameterTypes");
            String[] names = new String[jparams.length()];
            for (int i = 0; i < names.length; i++)
                names[i] = jparams.getString(i);
            Class<?>[] params = XResolver.resolveClasses(names, getClass().getClassLoader());

            // Like XCore.install: the return type of a method is the declared one or a subclass,
            // the type of a field the declared one
            String methodName = hook.optString("methodName", null);
            String returnName = hook.optString("returnType", null);
            Class<?> returnType = (returnName == null ? null
                    : XResolver.resolveClass(returnName, getClass().getClassLoader()));
            if (methodName == null)
                cls.getDeclaredConstructor(params);
            else if (methodName.startsWith("#")) {
                Field field = XResolver.resolveField(cls, methodName.substring(1), null);
                if (returnType != null && !field.getType().equals(returnType))
                    errors.add(name + ": field type " + field.getType().getName() + " not " + returnName);
            } else if (methodName.contains(":"))
                XResolver.resolveField(cls, methodName.split(":")[0], null);
            else {
                Method method = findMethod(cls, methodName, params);
                if (returnType != null && !method.getReturnType().isAssignableFrom(returnType))
                    errors.add(name + ": return type " + method.getReturnType().getName() + " not " + returnName);
            }
        } catch (Throwable ex) {
            errors.add(name + ": " + ex);
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>[] params) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass())
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        throw new NoSuchMethodException(cls.getName() + "." + name + Arrays.toString(params));
    }

    // The SDK level of the android.jar on the unit test class path
    private static int compileSdk() throws Exception {
        Class<?> codes = Class.forName("android.os.Build$VERSION_CODES");
        int max = 0;
        for (Field field : codes.getFields())
            if (field.getType() == int.class && field.getInt(null) < 10000)
                max = Math.max(max, field.getInt(null));
        assertTrue(max > 0);
        return max;
    }

    // JSONObject.keySet is not in the org.json of Android, which the unit tests compile against
    private static Set<String> keys(JSONObject jobject) {
        Set<String> keys = new HashSet<>();
        for (Iterator<String> it = jobject.keys(); it.hasNext(); )
            keys.add(it.next());
        return keys;
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    // Assignments of renamed hooks move to the new id, see XDatabase.upgrade
    @Test
    public void renamedHooksExist() throws Exception {
        Set<String> names = new HashSet<>();
        for (JSONObject hook : hooks)
            names.add(hook.getString("name"));
        for (String[] renamed : XDatabase.RENAMED) {
            assertTrue("Renamed into unknown hook " + renamed[1], names.contains(renamed[1]));
            assertTrue("Renamed hook still exists " + renamed[0], !names.contains(renamed[0]));
        }
        // A removed name which is a built-in definition again would lose the assignments of older versions
        for (String removed : XDatabase.REMOVED)
            assertFalse("Removed hook exists " + removed, names.contains(removed));
    }

    // Moved hooks are built-in definitions in the group they moved to, once each, see XProvider.assignMovedBuiltins
    @Test
    public void movedHooksAreInTheirGroup() throws Exception {
        Map<String, String> groups = new HashMap<>();
        for (JSONObject hook : hooks)
            groups.put(hook.getString("name"), hook.getString("group"));
        Set<String> moved = new HashSet<>();
        for (String[] move : XDatabase.MOVED) {
            assertTrue("Moved twice " + move[0], moved.add(move[0]));
            assertEquals("Group of moved hook " + move[0], move[1], groups.get(move[0]));
        }
    }

    // The built-in definitions as the app reads them, see XHook.readHooks: in the default collection,
    // which the renames of XDatabase.upgrade assume, with the attributes the validation requires
    // (XHook.validate uses TextUtils, which is a stub in unit tests)
    @Test
    public void definitionsReadLikeApp() throws Exception {
        for (JSONObject jhook : hooks) {
            String name = jhook.getString("name");
            assertEquals(name, Util.COLLECTION_DEFAULT, jhook.getString("collection"));
            for (String key : new String[]{"collection", "group", "name", "author", "className", "luaScript"})
                assertFalse(name + " has an empty " + key, jhook.getString(key).trim().isEmpty());
            checkRead(name, jhook);
        }
    }

    // Reads a definition like the app (XHook.fromJSONObject) and exports it again (toJSONObject, like the hook editor):
    // the result is the definition with the defaults for missing attributes, so an unknown (misspelled) attribute,
    // which the app would ignore, and a changed default fail
    private static void checkRead(String name, JSONObject jhook) throws Exception {
        JSONObject expected = new JSONObject(jhook.toString());
        for (Map.Entry<String, Object> entry : DEFAULTS.entrySet())
            if (!expected.has(entry.getKey()))
                expected.put(entry.getKey(), entry.getValue());

        JSONObject exported = XHook.fromJSONObject(jhook).toJSONObject();

        Set<String> keys = new TreeSet<>(keys(expected));
        keys.addAll(keys(exported));
        for (String key : keys)
            assertEquals(name + " " + key, String.valueOf(expected.opt(key)), String.valueOf(exported.opt(key)));
    }

    // The app reads its definitions from its APK, with the scripts in separate files
    @Test
    public void definitionsReadFromApk() throws Exception {
        File apk = File.createTempFile("hooks", ".apk");
        try {
            // The reference org.json of the unit tests does not accept comments, see readLikeAndroid
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(apk))) {
                zos.putNextEntry(new ZipEntry("assets/hooks.json"));
                zos.write(readLikeAndroid(new File(ASSETS, "hooks.json")).getBytes(StandardCharsets.UTF_8));
                for (File file : ASSETS.listFiles((dir, name) -> name.endsWith(".lua"))) {
                    zos.putNextEntry(new ZipEntry("assets/" + file.getName()));
                    zos.write(Files.readAllBytes(file.toPath()));
                }
            }

            List<XHook> builtin = XHook.readHooks(apk.getPath());
            assertEquals(hooks.size(), builtin.size());
            // A script file is read once, system server keeps one copy of it for all the hooks which share it
            Map<String, String> copies = new HashMap<>();
            for (int i = 0; i < builtin.size(); i++) {
                JSONObject jhook = hooks.get(i);
                XHook hook = builtin.get(i);
                String script = jhook.getString("luaScript");
                assertEquals(jhook.getString("collection") + "." + jhook.getString("name"), hook.getId());
                assertTrue(hook.getId() + " not built-in", hook.isBuiltin());
                assertEquals(hook.getId() + " script", script.startsWith("@")
                        ? read(new File(ASSETS, script.substring(1) + ".lua")) : script, hook.getLuaScript());
                if (script.startsWith("@")) {
                    String copy = copies.get(script);
                    if (copy == null)
                        copies.put(script, hook.getLuaScript());
                    else
                        assertSame(hook.getId() + " has its own copy of " + script, copy, hook.getLuaScript());
                }
            }
        } finally {
            apk.delete();
        }
    }

    // A built-in definition by its name, null when there is none
    private static JSONObject hook(String name) throws Exception {
        for (JSONObject hook : hooks)
            if (name.equals(hook.getString("name")))
                return hook;
        return null;
    }

    // Definitions which close gaps: the method, its versions and its script,
    // as "class method(params) script minSdk-maxSdk optional"
    private static void check(String name, String expected) throws Exception {
        JSONObject hook = hook(name);
        assertNotNull(name + " is not defined", hook);
        JSONArray params = hook.getJSONArray("parameterTypes");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length(); i++)
            sb.append(i == 0 ? "" : ",").append(params.getString(i));
        String actual = hook.getString("className") + " " + hook.getString("methodName") + "(" + sb + ") " +
                hook.getString("luaScript") + " " + hook.getInt("minSdk") + "-" + hook.optString("maxSdk", "") +
                (hook.optBoolean("optional", false) ? " optional" : "");
        assertEquals(name, expected, actual);
    }

    // Each overload calls Android itself, so each one is hooked (see component_hidden)
    @Test
    public void componentsOfHiddenAppsByEveryOverload() throws Exception {
        String i = "android.content.Intent,";
        check("Context.bindServiceAsUser/hidden", "android.app.ContextImpl bindServiceAsUser(" + i +
                "android.content.ServiceConnection,int,android.os.UserHandle) @component_hidden 27-");
        check("Context.bindServiceAsUser.flags/hidden", "android.app.ContextImpl bindServiceAsUser(" + i +
                "android.content.ServiceConnection,android.content.Context$BindServiceFlags,android.os.UserHandle) @component_hidden 34-");
        check("Context.sendBroadcast.options/hidden", "android.app.ContextImpl sendBroadcast(" + i +
                "java.lang.String,android.os.Bundle) @component_hidden 34-");
        check("Context.sendBroadcastWithMultiplePermissions/hidden", "android.app.ContextImpl sendBroadcastWithMultiplePermissions(" + i +
                "java.lang.String[]) @component_hidden 30-");
        check("Context.sendBroadcastAsUser/hidden", "android.app.ContextImpl sendBroadcastAsUser(" + i +
                "android.os.UserHandle) @component_hidden 27-");
        check("Context.sendBroadcastAsUser.permission/hidden", "android.app.ContextImpl sendBroadcastAsUser(" + i +
                "android.os.UserHandle,java.lang.String) @component_hidden 27-");
        check("Activity.startActivityIfNeeded/hidden", "android.app.Activity startActivityIfNeeded(" + i +
                "int,android.os.Bundle) @component_hidden 27-");
    }

    // The identifier services of manufacturers are bound by each method binding services, and their providers are
    // acquired or called by their Uri or name (see identifier_services); like the hooks of hidden apps
    @Test
    public void identifierServicesByEveryOverload() throws Exception {
        String i = "android.content.Intent,";
        check("Context.bindService/identifiers", "android.app.ContextImpl bindService(" + i +
                "android.content.ServiceConnection,int) @identifier_services 27-");
        check("Context.bindService.executor/identifiers", "android.app.ContextImpl bindService(" + i +
                "int,java.util.concurrent.Executor,android.content.ServiceConnection) @identifier_services 29-");
        check("Context.bindService.flags/identifiers", "android.app.ContextImpl bindService(" + i +
                "android.content.ServiceConnection,android.content.Context$BindServiceFlags) @identifier_services 34-");
        check("Context.bindService.flags.executor/identifiers", "android.app.ContextImpl bindService(" + i +
                "android.content.Context$BindServiceFlags,java.util.concurrent.Executor,android.content.ServiceConnection) " +
                "@identifier_services 34-");
        check("Context.bindIsolatedService/identifiers", "android.app.ContextImpl bindIsolatedService(" + i +
                "int,java.lang.String,java.util.concurrent.Executor,android.content.ServiceConnection) @identifier_services 29-");
        check("Context.bindIsolatedService.flags/identifiers", "android.app.ContextImpl bindIsolatedService(" + i +
                "android.content.Context$BindServiceFlags,java.lang.String,java.util.concurrent.Executor," +
                "android.content.ServiceConnection) @identifier_services 34-");
        check("Context.bindServiceAsUser/identifiers", "android.app.ContextImpl bindServiceAsUser(" + i +
                "android.content.ServiceConnection,int,android.os.UserHandle) @identifier_services 27-");
        check("Context.bindServiceAsUser.flags/identifiers", "android.app.ContextImpl bindServiceAsUser(" + i +
                "android.content.ServiceConnection,android.content.Context$BindServiceFlags,android.os.UserHandle) " +
                "@identifier_services 34-");
        String r = "android.content.ContentResolver ";
        check("ContentResolver.acquireContentProviderClient/identifiers",
                r + "acquireContentProviderClient(android.net.Uri) @identifier_services 27-");
        check("ContentResolver.acquireContentProviderClient.name/identifiers",
                r + "acquireContentProviderClient(java.lang.String) @identifier_services 27-");
        check("ContentResolver.acquireUnstableContentProviderClient/identifiers",
                r + "acquireUnstableContentProviderClient(android.net.Uri) @identifier_services 27-");
        check("ContentResolver.acquireUnstableContentProviderClient.name/identifiers",
                r + "acquireUnstableContentProviderClient(java.lang.String) @identifier_services 27-");
        check("ContentResolver.call.uri/oaid", r + "call(android.net.Uri,java.lang.String,java.lang.String," +
                "android.os.Bundle) @identifier_services 27-28");
        check("ContentResolver.call/oaid", r + "call(java.lang.String,java.lang.String,java.lang.String," +
                "android.os.Bundle) @identifier_services 29-");
        // Identifiers which manufacturers and SDKs keep in settings, in any table (see settingssecure_getstring)
        for (String table : new String[]{"Global", "Secure", "System"})
            check("Settings." + table + ".getStringForUser/oaid", "android.provider.Settings$" + table +
                    " getStringForUser(android.content.ContentResolver,java.lang.String,int) @settingssecure_getstring 27- optional");
        // Coosea: hidden methods of the keyguard manager, which other devices do not have
        for (String method : new String[]{"obtainOaid", "obtainAaid", "obtainVaid"})
            check("KeyguardManager." + method, "android.app.KeyguardManager " + method + "() @generic_null_value 27- optional");
    }

    // The graphics chip by each version of OpenGL ES: GLES11 inherits glGetString of GLES10, GLES30 to GLES32 the one of
    // GLES20, GLImpl serves javax.microedition.khronos (see gles_getstring)
    @Test
    public void graphicsChipByEveryVersionOfOpenGl() throws Exception {
        check("GLES10.glGetString", "android.opengl.GLES10 glGetString(int) @gles_getstring 27-");
        check("GLES20.glGetString", "android.opengl.GLES20 glGetString(int) @gles_getstring 27-");
    }

    // Unity Ads gets no consent, by its setters, the MetaData of former versions and before each way it starts (see
    // unityads_consent); Umeng does not start, like when the user declined (see generic_block), the way its
    // documentation says, and the policy is not granted
    @Test
    public void analyticsOfUnityAdsAndUmeng() throws Exception {
        String ads = "com.unity3d.ads.UnityAds initialize(android.content.Context,java.lang.String";
        check("UnityAds.initialize", ads + ") @unityads_consent 27- optional");
        check("UnityAds.initialize/listener", ads + ",com.unity3d.ads.IUnityAdsInitializationListener) @unityads_consent 27- optional");
        check("UnityAds.initialize/test", ads + ",boolean) @unityads_consent 27- optional");
        check("UnityAds.initialize/test/listener", ads + ",boolean,com.unity3d.ads.IUnityAdsInitializationListener) " +
                "@unityads_consent 27- optional");
        check("UnityAds.initialize/configuration", "com.unity3d.ads.UnityAds initialize(com.unity3d.ads.InitializationConfiguration," +
                "com.unity3d.ads.InitializationListener) @unityads_consent 27- optional");
        check("UnityAds.setUserConsent", "com.unity3d.ads.UnityAds setUserConsent(java.lang.Boolean) @generic_false_argument 27- optional");
        check("UnityAds.setUserOptOut", "com.unity3d.ads.UnityAds setUserOptOut(java.lang.Boolean) @generic_true_argument 27- optional");
        check("UnityAds.setNonBehavioral", "com.unity3d.ads.UnityAds setNonBehavioral(java.lang.Boolean) @generic_true_argument 27- optional");
        check("UnityAdsMetaData.set", "com.unity3d.ads.metadata.MetaData set(java.lang.String,java.lang.Object) @unityads_consent 27- optional");
        String umeng = "com.umeng.commonsdk.UMConfigure ";
        check("UMConfigure.init", umeng + "init(android.content.Context,int,java.lang.String) @generic_block 27- optional");
        check("UMConfigure.init/appkey", umeng + "init(android.content.Context,java.lang.String,java.lang.String,int," +
                "java.lang.String) @generic_block 27- optional");
        check("UMConfigure.submitPolicyGrantResult", umeng + "submitPolicyGrantResult(android.content.Context,boolean) " +
                "@generic_false_argument 27- optional");
    }

    // The entries of getifaddrs, which NetworkInterface reads, for each group of its hooks (see interface_entries)
    @Test
    public void interfaceEntriesForEachGroup() throws Exception {
        for (String[] variant : new String[][]{{"network", "Read.Network"}, {"vpn", "Read.VPN"}, {"mac", "Read.Identifiers"}}) {
            check("ForwardingOs.getifaddrs/" + variant[0], "libcore.io.ForwardingOs getifaddrs() @interface_entries 27- optional");
            assertEquals(variant[1], hook("ForwardingOs.getifaddrs/" + variant[0]).getString("group"));
        }
    }

    // Routes to data of groups which other hooks of the groups leave open, found by an audit
    @Test
    public void routesOfAudit() throws Exception {
        String t = "android.telecom.TelecomManager ";
        // The id of a SIM account is its serial number (ICC ID) until Android 13
        check("TelecomManager.getCallCapablePhoneAccounts", t + "getCallCapablePhoneAccounts() @generic_empty 27-32");
        check("TelecomManager.getDefaultOutgoingPhoneAccount", t + "getDefaultOutgoingPhoneAccount(java.lang.String) @generic_null_value 27-32");
        check("TelecomManager.getUserSelectedOutgoingPhoneAccount", t + "getUserSelectedOutgoingPhoneAccount() @generic_null_value 29-32");
        check("TelecomManager.getSimCallManager", t + "getSimCallManager() @generic_null_value 27-32");
        check("TelephonyManager.getPhoneAccountHandle", "android.telephony.TelephonyManager getPhoneAccountHandle() @generic_null_value 31-32");
        // The number of a SIM is the address of its phone account
        for (String method : new String[]{"getAddress", "getSubscriptionAddress"})
            check("PhoneAccount." + method, "android.telecom.PhoneAccount " + method + "() @phone_account_address 27-");
        // The carrier configuration of MMS with the user agent and profile, which can name the model
        check("SmsManager.getCarrierConfigValues", "android.telephony.SmsManager getCarrierConfigValues() @mms_useragent 27-");
        // The name of the own device of Wi-Fi Direct
        check("WifiP2pDevice.createFromParcel", "android.net.wifi.p2p.WifiP2pDevice CREATOR:createFromParcel(android.os.Parcel) " +
                "@wifip2pdevice_name 27-");
        // The network access identifier, which only Android 9 gives to apps with the phone state permission
        check("TelephonyManager.getNai", "android.telephony.TelephonyManager getNai() @generic_null_value 28-28");
        // Media sessions of other apps, which notification listeners get
        String m = "android.media.session.MediaSessionManager ";
        check("MediaSessionManager.getActiveSessions", m + "getActiveSessions(android.content.ComponentName) @generic_empty 27-");
        check("MediaSessionManager.getActiveSessionsForPackage", m + "getActiveSessionsForPackage(java.lang.String) @generic_empty 37-");
        check("MediaSessionManager.getActiveSessionsForPackage/listener", m + "getActiveSessionsForPackage(java.lang.String," +
                "android.content.ComponentName) @generic_empty 37-");
        check("MediaSessionManager.getSession2Tokens", m + "getSession2Tokens() @generic_empty 29-");
        check("MediaSessionManager.getMediaKeyEventSessionPackageName", m + "getMediaKeyEventSessionPackageName() @generic_null_value 33-");
        String l = "android.media.session.MediaSessionManager$OnActiveSessionsChangedListener";
        check("MediaSessionManager.addOnActiveSessionsChangedListener", m + "addOnActiveSessionsChangedListener(" + l +
                ",android.content.ComponentName) @generic_block 27-");
        check("MediaSessionManager.addOnActiveSessionsChangedListener/handler", m + "addOnActiveSessionsChangedListener(" + l +
                ",android.content.ComponentName,android.os.Handler) @generic_block 27-");
        check("MediaSessionManager.addOnActiveSessionsForPackageChangedListener", m + "addOnActiveSessionsForPackageChangedListener(" +
                "java.lang.String,java.util.concurrent.Executor," + l + ") @generic_block 37-");
        check("MediaSessionManager.addOnActiveSessionsForPackageChangedListener/listener", m +
                "addOnActiveSessionsForPackageChangedListener(java.lang.String,java.util.concurrent.Executor," +
                "android.content.ComponentName," + l + ") @generic_block 37-");
        // Install sessions and package changes name other apps
        String i = "android.content.pm.PackageInstaller ";
        check("PackageInstaller.getAllSessions", i + "getAllSessions() @generic_empty 27-");
        check("PackageInstaller.getStagedSessions", i + "getStagedSessions() @generic_empty 29-");
        check("PackageInstaller.getSessionInfo", i + "getSessionInfo(int) @generic_null_value 27-");
        check("PackageInstaller.registerSessionCallback", i + "registerSessionCallback(" +
                "android.content.pm.PackageInstaller$SessionCallback) @generic_block 27-");
        check("PackageInstaller.registerSessionCallback/handler", i + "registerSessionCallback(" +
                "android.content.pm.PackageInstaller$SessionCallback,android.os.Handler) @generic_block 27-");
        String a = "android.content.pm.LauncherApps ";
        check("LauncherApps.getAllPackageInstallerSessions", a + "getAllPackageInstallerSessions() @generic_empty 29-");
        check("LauncherApps.registerPackageInstallerSessionCallback", a + "registerPackageInstallerSessionCallback(" +
                "java.util.concurrent.Executor,android.content.pm.PackageInstaller$SessionCallback) @generic_block 29-");
        check("LauncherApps.registerCallback", a + "registerCallback(android.content.pm.LauncherApps$Callback) @generic_block 27-");
        check("LauncherApps.registerCallback/handler", a + "registerCallback(android.content.pm.LauncherApps$Callback," +
                "android.os.Handler) @generic_block 27-");
        // Usage access tells about other apps by their package
        check("UsageStatsManager.isAppInactive", "android.app.usage.UsageStatsManager isAppInactive(java.lang.String) @package_probe 27-");
        check("StorageStatsManager.queryStatsForPackage", "android.app.usage.StorageStatsManager queryStatsForPackage(" +
                "java.util.UUID,java.lang.String,android.os.UserHandle) @package_probe 27-");
        // The exact free space, Bluetooth input devices, battery saver, NSD by a request, sleep of Play services
        check("StorageManager.getAllocatableBytes", "android.os.storage.StorageManager getAllocatableBytes(java.util.UUID) @resources 27-");
        check("BluetoothHidDevice.getConnectedDevices", "android.bluetooth.BluetoothHidDevice getConnectedDevices() @generic_empty 28-");
        check("BluetoothHidDevice.getDevicesMatchingConnectionStates", "android.bluetooth.BluetoothHidDevice " +
                "getDevicesMatchingConnectionStates(int[]) @generic_empty 28-");
        check("PowerManager.getLocationPowerSaveMode", "android.os.PowerManager getLocationPowerSaveMode() @generic_zero_value 28-");
        check("NsdManager.registerServiceInfoCallback/discovery", "android.net.nsd.NsdManager registerServiceInfoCallback(" +
                "android.net.nsd.DiscoveryRequest,java.util.concurrent.Executor,android.net.nsd.NsdManager$ServiceInfoCallback) " +
                "@nsdmanager_listener 33- optional");
        // Broadcasts with Bluetooth devices and the network of Android 8.1, and with the number of a call
        for (String[] variant : new String[][]{{"network", "Read.Network"}, {"call_log", "Get.Call.log"}}) {
            check("Intent.createFromParcel/" + variant[0], "android.content.Intent CREATOR:createFromParcel(android.os.Parcel) " +
                    "@intent_createfromparcel 27-");
            assertEquals(variant[1], hook("Intent.createFromParcel/" + variant[0]).getString("group"));
        }
        check("TelephonyManager.listen/call_log", "android.telephony.TelephonyManager listen(android.telephony.PhoneStateListener," +
                "int) @telephonymanager_listen 27-");
        assertEquals("Get.Call.log", hook("TelephonyManager.listen/call_log").getString("group"));
        for (String c : new String[]{"SleepSegmentEvent", "SleepClassifyEvent"})
            check(c + ".extractEvents", "com.google.android.gms.location." + c + " extractEvents(android.content.Intent) " +
                    "@generic_empty 27- optional");
    }

    // Permissions and their groups name the app which defines them (see package_resolve)
    @Test
    public void permissionsOfHiddenApps() throws Exception {
        check("PackageManager.getPermissionGroupInfo", "android.content.pm.PackageManager getPermissionGroupInfo(" +
                "java.lang.String,int) @package_resolve 27-");
        check("PackageManager.getAllPermissionGroups", "android.content.pm.PackageManager getAllPermissionGroups(" +
                "int) @package_resolve 27-");
        check("PackageManager.getPermissionInfo", "android.content.pm.PackageManager getPermissionInfo(" +
                "java.lang.String,int) @package_resolve 27-");
        check("PackageManager.queryPermissionsByGroup", "android.content.pm.PackageManager queryPermissionsByGroup(" +
                "java.lang.String,int) @package_resolve 27-");
    }

    // Files.getFileStore(path).getTotalSpace() and the others, also by getAttribute, read statvfs themselves
    @Test
    public void fileStoreSizesAreRounded() throws Exception {
        for (String method : new String[]{"getTotalSpace", "getUsableSpace", "getUnallocatedSpace"})
            check("UnixFileStore." + method, "sun.nio.fs.UnixFileStore " + method + "() @resources 27-");
    }

    // Apps targeting Android 9 or older still get the neighboring cells on Android 10 and newer; the method is not
    // in the android.jar of recent versions, so it is optional
    @Test
    public void neighboringCellsOnAllVersions() throws Exception {
        check("TelephonyManager.getNeighboringCellInfo", "android.telephony.TelephonyManager getNeighboringCellInfo() " +
                "@generic_empty 27- optional");
    }

    // The clipboard of the app is read by the clipboard manager; data of a parcel is also of drag and drop and of
    // intents, like the app's own drags, which are not the clipboard
    @Test
    public void clipboardIsReadFromTheClipboardManager() throws Exception {
        check("ClipboardManager.getPrimaryClip", "android.content.ClipboardManager getPrimaryClip() " +
                "@clipboard_getprimaryclip 27-");
        check("ClipboardManager.getPrimaryClipDescription", "android.content.ClipboardManager getPrimaryClipDescription() " +
                "@clipboard_getprimaryclip 27-");
    }

    // Other ways to apps hidden by Get.Applications: from the version which has the method, ordered and sticky
    // broadcasts with a result receiver, screens of app tasks, services and lookups for a user
    @Test
    public void otherWaysToHiddenApps() throws Exception {
        String s = "java.lang.String", b = "android.content.BroadcastReceiver,android.os.Handler,";
        check("PackageManager.getLaunchIntentSenderForPackage", "android.content.pm.PackageManager " +
                "getLaunchIntentSenderForPackage(" + s + ") @package_probe 33-");
        check("PackageManager.getTargetSdkVersion", "android.content.pm.PackageManager getTargetSdkVersion(" + s +
                ") @package_probe 31-");
        check("Context.sendOrderedBroadcast.options/hidden", "android.app.ContextImpl sendOrderedBroadcast(" +
                "android.content.Intent," + s + ",android.os.Bundle) @component_hidden 34-");
        check("Context.sendOrderedBroadcast.code/hidden", "android.app.ContextImpl sendOrderedBroadcast(" +
                "android.content.Intent,int," + s + "," + s + "," + b + s + ",android.os.Bundle,android.os.Bundle) " +
                "@component_hidden 30-");
        check("Context.sendOrderedBroadcastAsUser/hidden", "android.app.ContextImpl sendOrderedBroadcastAsUser(" +
                "android.content.Intent,android.os.UserHandle," + s + "," + b + "int," + s + ",android.os.Bundle) " +
                "@component_hidden 27-");
        check("Context.sendStickyOrderedBroadcast/hidden", "android.app.ContextImpl sendStickyOrderedBroadcast(" +
                "android.content.Intent," + b + "int," + s + ",android.os.Bundle) @component_hidden 27-");
        check("Context.sendStickyOrderedBroadcastAsUser/hidden", "android.app.ContextImpl sendStickyOrderedBroadcastAsUser(" +
                "android.content.Intent,android.os.UserHandle," + b + "int," + s + ",android.os.Bundle) @component_hidden 27-");
        check("ActivityManager.AppTask.startActivity/hidden", "android.app.ActivityManager$AppTask startActivity(" +
                "android.content.Context,android.content.Intent,android.os.Bundle) @component_hidden 27-");
        check("Context.startServiceAsUser/hidden", "android.app.ContextImpl startServiceAsUser(" +
                "android.content.Intent,android.os.UserHandle) @component_hidden 27-");
        check("PackageManager.queryIntentActivitiesAsUser.33", "android.content.pm.PackageManager " +
                "queryIntentActivitiesAsUser(android.content.Intent,android.content.pm.PackageManager$ResolveInfoFlags,int) " +
                "@generic_filter_by_uid 33- optional");
        check("PackageManager.isPackageAvailable", "android.content.pm.PackageManager isPackageAvailable(" + s +
                ") @package_probe 27- optional");
    }

    // Settings, intents, accounts and the default account of contacts restricted for Android would change what
    // system server itself reads and stores, like adb_enabled or the accounts apps add, so Android is excluded
    @Test
    public void systemServerKeepsItsOwnValues() throws Exception {
        List<String> errors = new ArrayList<>();
        for (JSONObject hook : hooks) {
            String script = hook.getString("luaScript");
            boolean settings = script.equals("@locale") && hook.getString("className").startsWith("android.provider.Settings");
            if ((settings || script.equals("@settingssecure_getstring") || script.equals("@intent_createfromparcel") ||
                    script.equals("@account_createfromparcel") || script.equals("@contacts_default_account")) &&
                    !Arrays.asList(hook.optString("excludePackages", "").split(",")).contains("android"))
                errors.add(hook.getString("name"));
        }
        assertTrue("Not excluding android: " + errors, errors.isEmpty());
    }

    // The ways around the hooks which audits found: file sizes and listings, sticky broadcasts, app ops, the kernel
    // in /proc, cameras, speech, HDR, partitions, GNSS status, VPN addresses and the MMS user agent of the carrier
    @Test
    public void otherWaysOfAudits() throws Exception {
        String s = "java.lang.String", f = "java.io.File", i = "android.content.Intent";
        for (String suffix : new String[]{"hardware_ids", "hardware_model", "fingerprint_sensor", "feature_files",
                "privapp_files", "vpn_interfaces", "root"}) {
            check("UnixFileSystem.getLength/" + suffix, "java.io.UnixFileSystem getLength(" + f + ") @hardware_files 27-");
            check("UnixFileSystem.getSpace/" + suffix, "java.io.UnixFileSystem getSpace(" + f + ",int) @hardware_files 27-");
        }
        check("UnixFileSystem.list0/root", "java.io.UnixFileSystem list0(" + f + ") @hardware_files 27-");
        check("UnixFileSystem.getLastModifiedTime/root", "java.io.UnixFileSystem getLastModifiedTime(" + f + ") @hardware_files 27-");
        check("UnixNativeDispatcher.opendir/root", "sun.nio.fs.UnixNativeDispatcher opendir(sun.nio.fs.UnixPath) @hardware_files 27-");
        check("Os.readlink/root", "android.system.Os readlink(" + s + ") @hardware_files 27-");
        check("Context.sendStickyBroadcast/hidden", "android.app.ContextImpl sendStickyBroadcast(" + i + ") @component_hidden 27-");
        check("Context.sendStickyBroadcast.options/hidden", "android.app.ContextImpl sendStickyBroadcast(" + i +
                ",android.os.Bundle) @component_hidden 31-");
        check("Context.sendStickyBroadcastAsUser/hidden", "android.app.ContextImpl sendStickyBroadcastAsUser(" + i +
                ",android.os.UserHandle) @component_hidden 27-");
        check("AppOpsManager.checkPackage", "android.app.AppOpsManager checkPackage(int," + s + ") @package_probe 27-");
        check("AppOpsManager.checkOpNoThrow", "android.app.AppOpsManager checkOpNoThrow(" + s + ",int," + s + ") @package_probe 27-");
        check("AppOpsManager.unsafeCheckOpNoThrow", "android.app.AppOpsManager unsafeCheckOpNoThrow(" + s + ",int," + s +
                ") @package_probe 29-");
        check("BlockGuardOs.open/kernel", "libcore.io.BlockGuardOs open(" + s + ",int,int) @kernel_version 27-");
        check("CameraManager2.getCameraCharacteristics", "android.hardware.camera2.CameraManager getCameraCharacteristics(" + s +
                ") @camera2_open 27-");
        check("CameraManager2.getConcurrentCameraIds", "android.hardware.camera2.CameraManager getConcurrentCameraIds() @generic_empty 30-");
        check("CameraManager2.registerAvailabilityCallback/executor", "android.hardware.camera2.CameraManager " +
                "registerAvailabilityCallback(java.util.concurrent.Executor,android.hardware.camera2.CameraManager$AvailabilityCallback) " +
                "@generic_block 28-");
        check("SpeechRecognizer.setRecognitionListener", "android.speech.SpeechRecognizer setRecognitionListener(" +
                "android.speech.RecognitionListener) @speech_recognizer 27-");
        check("SpeechRecognizer.startListening", "android.speech.SpeechRecognizer startListening(" + i + ") @speech_recognizer 27-");
        check("Display.getHdrCapabilities", "android.view.Display getHdrCapabilities() @display_panel 27-");
        check("Display.getDeviceProductInfo", "android.view.Display getDeviceProductInfo() @display_panel 31-");
        check("Build.getFingerprintedPartitions", "android.os.Build getFingerprintedPartitions() @generic_empty 29-");
        check("LocationManager.registerGnssStatusCallback30", "android.location.LocationManager registerGnssStatusCallback(" +
                "java.util.concurrent.Executor,android.location.GnssStatus$Callback) @generic_block 30-");
        check("LinkProperties.getLinkAddresses/vpn", "android.net.LinkProperties getLinkAddresses() @vpn_addresses 27-");
        check("LinkProperties.getDnsServers/vpn", "android.net.LinkProperties getDnsServers() @vpn_addresses 27-");
        check("CarrierConfigManager.getConfigForSubId", "android.telephony.CarrierConfigManager getConfigForSubId(int) @mms_useragent 27-");
        check("UsageStatsManager.queryUsageStats", "android.app.usage.UsageStatsManager queryUsageStats(int,long,long) " +
                "@usageevents_query 27-");
        // R8 can make Clarity.initialize return void
        check("Clarity.initialize/context", "com.microsoft.clarity.Clarity initialize(android.content.Context," +
                "com.microsoft.clarity.ClarityConfig) @generic_block_untyped 27- optional");
        assertFalse(hook("Clarity.initialize/context").has("returnType"));
        // The default account of contacts is answered from Android 16
        check("ContentResolver.call/defaultaccount", "android.content.ContentResolver call(" + s + "," + s + "," + s +
                ",android.os.Bundle) @contacts_default_account 36-");
        // The NSD module of Android 13 can have these, like registerServiceInfoCallback/discovery
        check("NsdManager.stopServiceResolution", "android.net.nsd.NsdManager stopServiceResolution(" +
                "android.net.nsd.NsdManager$ResolveListener) @nsdmanager_listener 33- optional");
        check("NsdManager.unregisterServiceInfoCallback", "android.net.nsd.NsdManager unregisterServiceInfoCallback(" +
                "android.net.nsd.NsdManager$ServiceInfoCallback) @nsdmanager_listener 33- optional");
        check("NsdManager.registerServiceInfoCallback", "android.net.nsd.NsdManager registerServiceInfoCallback(" +
                "android.net.nsd.NsdServiceInfo,java.util.concurrent.Executor,android.net.nsd.NsdManager$ServiceInfoCallback) " +
                "@nsdmanager_listener 33- optional");
        assertEquals("android.content.SyncAdapterType[]", hook("ContentResolver.getSyncAdapterTypes").getString("returnType"));
        assertEquals("android.net.NetworkRequest$Builder", hook("NetworkRequest.Builder.addTransportType").getString("returnType"));
        // A request for VPN networks is one for LoWPAN (see vpn_request); NetworkCapabilities.Builder, and
        // NetworkRequest.Builder(NetworkCapabilities), are system APIs, which apps cannot use
        check("NetworkRequest.Builder.addTransportType", "android.net.NetworkRequest$Builder addTransportType(int) @vpn_request 27-");
    }
}
