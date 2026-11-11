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
import static org.junit.Assert.assertNull;
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
            "android.app.LoadedApk$ServiceDispatcher", "android.content.pm.PackageInstaller$SessionCallbackDelegate",
            "android.app.PendingIntent$FinishedDispatcher", "android.content.IntentSender$FinishedDispatcher",
            "android.app.ActivityThread", "android.app.LoadedApk$ReceiverDispatcher", "android.hardware.SystemSensorManager",
            "android.hardware.input.InputDeviceSensorManager$InputSensorManager"));

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
            // XCore.filterValue compares texts, files and java.nio paths, which are of UnixPath; else the script never runs
            JSONArray types = hook.optJSONArray("parameterTypes");
            String first = (types == null || types.length() == 0 ? null : types.getString(0));
            assertTrue(hook.getString("name") + " has a filter, its first parameter is " + first, Arrays.asList(
                    "java.lang.String", "java.io.File", "java.nio.file.Path", "sun.nio.fs.UnixPath").contains(first));
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
            "ActivityRecognitionResult.createFromParcel", "ActivityTransitionResult.createFromParcel",
            "LocationJsonUtil.parseLocationFromJsonObject", "LocationResult.createFromParcel", "LocationResult.create",
            "ActivityIdentificationResponse.getDataFromIntent", "ActivityConversionResponse.getDataFromIntent",
            "ActivityIdentificationResponse.createFromParcel", "ActivityConversionResponse.createFromParcel",
            "AdvertisingIdClient$Info.getId", "HuaweiAdvertisingIdClient$Info.getId", "AppSetIdInfo.getId",
            "AdvertisingIdClient$Info.isLimitAdTrackingEnabled", "HuaweiAdvertisingIdClient$Info.isLimitAdTrackingEnabled",
            "Amplitude.getOptOut", "Amplitude.getOptOut/android", "AmplitudeClient.isOptedOut", "Branch.isTrackingDisabled",
            "Branch.isTrackingDisabled/context", "Singular.isAllTrackingStopped",
            "FirebaseAnalytics.getInstance", "GoogleAnalytics.getInstance/optout", "AdRegistration.getInstance",
            "FirebaseCrashlytics.getInstance", "AppsFlyerLib.getInstance", "SegmentAnalytics.build",
            "MixpanelAPI.hasOptedOutTracking",
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
    // Scripts shared by hooks can read a setting for some of the hooks only. The suffix of package_probe is a value
    // of the service, new each boot (see FakeIds), which nobody sets.
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
                keys.remove(FakeIds.PROBE_SUFFIX);
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

    // Framework hooks must exist in the SDK the app is compiled against, unless they are optional or hidden;
    // hooks of java, sun and libcore are of ART, which the JVM of the tests does not have
    @Test
    public void frameworkMembersExist() throws Exception {
        int sdk = compileSdk();
        int checked = 0;
        List<String> errors = new ArrayList<>();
        for (JSONObject hook : hooks)
            // Also the hooks of older versions only: the SDK keeps methods it deprecates, so one removed from it
            // would need to be optional
            if (isFramework(hook, sdk) || (hook.optInt("maxSdk", 999) < sdk && isFramework(hook, hook.getInt("maxSdk")))) {
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
            } else if (methodName.contains(":")) {
                // Like XResolver.resolveTarget: the method of the object in a static field, like CREATOR:createFromParcel;
                // the stubs have no such objects, so the method of the type of the field
                String[] m = methodName.split(":");
                Field field = cls.getField(m[0]);
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    errors.add(name + ": field " + m[0] + " not static");
                Method method = findMethod(field.getType(), m[1], params);
                if (returnType != null && !method.getReturnType().isAssignableFrom(returnType))
                    errors.add(name + ": return type " + method.getReturnType().getName() + " not " + returnName);
            } else {
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

    // The broadcast of a time zone change is left out where both kinds of receivers get it, with the signature of
    // ReceiverDispatcher of each version (AOSP oreo-mr1 to android13-release, android14-release to main), and the
    // property areas are faked for both ways of opening files
    @Test
    public void timeZoneBroadcastAndPropertyAreas() throws Exception {
        String args = "android.content.Intent,int,java.lang.String,android.os.Bundle,boolean,boolean,";
        check("ActivityThread.handleReceiver/timezone",
                "android.app.ActivityThread handleReceiver(android.app.ActivityThread$ReceiverData) @receiver_skip 27-");
        check("ReceiverDispatcher.performReceive/timezone",
                "android.app.LoadedApk$ReceiverDispatcher performReceive(" + args + "int) @receiver_skip 27-33");
        check("ReceiverDispatcher.performReceive.sender/timezone", "android.app.LoadedApk$ReceiverDispatcher performReceive(" +
                args + "boolean,int,int,java.lang.String) @receiver_skip 34-");
        for (String variant : new String[]{"timezone", "locale"}) {
            check("BlockGuardOs.open/" + variant, "libcore.io.BlockGuardOs open(java.lang.String,int,int) @" + variant + " 27-");
            check("UnixNativeDispatcher.open/" + variant,
                    "sun.nio.fs.UnixNativeDispatcher open(sun.nio.fs.UnixPath,int,int) @" + variant + " 27-");
        }
        // The serials, ICC IDs, IMEIs and operators of systemproperties_get, in the group of their getters
        for (String variant : new String[]{"serial", "operator"}) {
            check("BlockGuardOs.open/" + variant, "libcore.io.BlockGuardOs open(java.lang.String,int,int) @systemproperties_get 27-");
            check("UnixNativeDispatcher.open/" + variant,
                    "sun.nio.fs.UnixNativeDispatcher open(sun.nio.fs.UnixPath,int,int) @systemproperties_get 27-");
            String group = hook("SystemProperties.get/" + variant).getString("group");
            for (String name : new String[]{"BlockGuardOs.open/", "UnixNativeDispatcher.open/"}) {
                assertEquals(name + variant, group, hook(name + variant).getString("group"));
                assertEquals(name + variant, hook("SystemProperties.get/" + variant).optJSONArray("settings") + "",
                        hook(name + variant).optJSONArray("settings") + "");
            }
        }
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
        check("Context.bindServiceAsUser.handler/identifiers", "android.app.ContextImpl bindServiceAsUser(" + i +
                "android.content.ServiceConnection,int,android.os.Handler,android.os.UserHandle) @identifier_services 27- optional");
        check("Context.bindServiceAsUser.flags.handler/identifiers", "android.app.ContextImpl bindServiceAsUser(" + i +
                "android.content.ServiceConnection,android.content.Context$BindServiceFlags,android.os.Handler," +
                "android.os.UserHandle) @identifier_services 34- optional");
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
        // The hidden methods giving the provider itself, which apps call by reflection and the others of ContentResolver
        for (String[] acquire : new String[][]{{"acquireProvider/identifiers", "android.net.Uri"},
                {"acquireProvider.authority/identifiers", "java.lang.String"},
                {"acquireUnstableProvider/identifiers", "android.net.Uri"},
                {"acquireUnstableProvider.authority/identifiers", "java.lang.String"}})
            check("ContentResolver." + acquire[0], r + acquire[0].replaceAll("[./].*", "") + "(" + acquire[1] +
                    ") @identifier_services 27- optional");
        // And the method of the app's process under them, which their protected overloads with a context call
        check("ActivityThread.acquireProvider/identifiers", "android.app.ActivityThread acquireProvider(android.content.Context," +
                "java.lang.String,int,boolean) @identifier_services 27- optional");
        // PackageManager finds neither the services nor the provider, by each overload, of which those of Android 13
        // and later and the public ones call the others, and by the hidden ones for a user
        String pm = "android.content.pm.PackageManager ";
        String[][] lookups = {
                {"resolveService", "android.content.Intent,int", "27-"},
                {"resolveService/flags", "android.content.Intent,android.content.pm.PackageManager$ResolveInfoFlags", "33-"},
                {"resolveServiceAsUser", "android.content.Intent,int,int", "27- optional"},
                {"resolveServiceAsUser/flags", "android.content.Intent,android.content.pm.PackageManager$ResolveInfoFlags,int",
                        "33- optional"},
                {"queryIntentServices", "android.content.Intent,int", "27-"},
                {"queryIntentServices/flags", "android.content.Intent,android.content.pm.PackageManager$ResolveInfoFlags", "33-"},
                {"queryIntentServicesAsUser", "android.content.Intent,int,int", "27- optional"},
                {"queryIntentServicesAsUser/flags",
                        "android.content.Intent,android.content.pm.PackageManager$ResolveInfoFlags,int", "33- optional"},
                {"getServiceInfo", "android.content.ComponentName,int", "27-"},
                {"getServiceInfo/flags", "android.content.ComponentName,android.content.pm.PackageManager$ComponentInfoFlags",
                        "33-"},
                {"getPackageInfo", "java.lang.String,int", "27-"},
                {"getPackageInfo/flags", "java.lang.String,android.content.pm.PackageManager$PackageInfoFlags", "33-"},
                {"getPackageInfo/versioned", "android.content.pm.VersionedPackage,int", "27-"},
                {"getPackageInfo/versioned/flags",
                        "android.content.pm.VersionedPackage,android.content.pm.PackageManager$PackageInfoFlags", "33-"},
                {"getPackageInfoAsUser", "java.lang.String,int,int", "27- optional"},
                {"getPackageInfoAsUser/flags", "java.lang.String,android.content.pm.PackageManager$PackageInfoFlags,int",
                        "33- optional"},
                {"getInstalledPackages", "int", "27-"},
                {"getInstalledPackages/flags", "android.content.pm.PackageManager$PackageInfoFlags", "33-"},
                {"getInstalledPackagesAsUser", "int,int", "27- optional"},
                {"getInstalledPackagesAsUser/flags", "android.content.pm.PackageManager$PackageInfoFlags,int", "33- optional"},
                {"resolveContentProvider", "java.lang.String,int", "27-"},
                {"resolveContentProvider/flags", "java.lang.String,android.content.pm.PackageManager$ComponentInfoFlags", "33-"},
                {"resolveContentProviderAsUser", "java.lang.String,int,int", "27- optional"},
                {"resolveContentProviderAsUser/flags",
                        "java.lang.String,android.content.pm.PackageManager$ComponentInfoFlags,int", "33- optional"}};
        for (String[] lookup : lookups)
            check("PackageManager." + lookup[0] + "/identifiers", pm + lookup[0].replaceAll("/.*", "") + "(" + lookup[1] +
                    ") @identifier_services " + lookup[2]);
        // And the binder proxy under that on Android 8.1 and 9, which apps reach by reflection
        check("IActivityManager.getContentProvider/identifiers", "android.app.IActivityManager$Stub$Proxy getContentProvider(" +
                "android.app.IApplicationThread,java.lang.String,int,boolean) @identifier_services 27-28 optional");
        // The binder proxy under Build.getSerial, of which getSerial still answers some apps from Android 10, when
        // Build.getSerial calls getSerialForPackage, with the attribution tag too from Android 11
        String serial = "android.os.IDeviceIdentifiersPolicyService$Stub$Proxy ";
        check("IDeviceIdentifiersPolicyService.getSerial", serial + "getSerial() @value_serial 27- optional");
        check("IDeviceIdentifiersPolicyService.getSerialForPackage", serial +
                "getSerialForPackage(java.lang.String) @value_serial 29-29 optional");
        check("IDeviceIdentifiersPolicyService.getSerialForPackage/feature", serial +
                "getSerialForPackage(java.lang.String,java.lang.String) @value_serial 30- optional");
        for (String name : new String[]{"getSerial", "getSerialForPackage", "getSerialForPackage/feature"})
            assertEquals("[\"value.serial\"]", hook("IDeviceIdentifiersPolicyService." + name).getJSONArray("settings").toString());
        // Identifiers which manufacturers and SDKs keep in settings, in any table (see settingssecure_getstring)
        for (String table : new String[]{"Global", "Secure", "System"})
            check("Settings." + table + ".getStringForUser/oaid", "android.provider.Settings$" + table +
                    " getStringForUser(android.content.ContentResolver,java.lang.String,int) @settingssecure_getstring 27- optional");
        // The cache of the settings below the getters, which apps call by reflection, for each variant of the identifiers
        for (String variant : new String[]{"oaid", "android_id", "mac"}) {
            check("Settings.NameValueCache.getStringForUser/" + variant, "android.provider.Settings$NameValueCache" +
                    " getStringForUser(android.content.ContentResolver,java.lang.String,int) @settingssecure_getstring 27- optional");
            assertEquals(hook("Settings.Secure.getStringForUser/" + variant).optString("settings"),
                    hook("Settings.NameValueCache.getStringForUser/" + variant).optString("settings"));
        }
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

    // Mixpanel by its opt-out, which also stops what the track hooks do not; stored crash reports are not sent;
    // Aps of Amazon has no setConsentStatus in any version (9.10 to 12), only AdRegistration has
    @Test
    public void analyticsByEveryWayToSend() throws Exception {
        check("MixpanelAPI.hasOptedOutTracking", "com.mixpanel.android.mpmetrics.MixpanelAPI hasOptedOutTracking() " +
                "@generic_true_value 27- optional");
        check("FirebaseCrashlytics.sendUnsentReports", "com.google.firebase.crashlytics.FirebaseCrashlytics " +
                "sendUnsentReports() @generic_block 27- optional");
        check("AppEventsLogger.logPurchase", "com.facebook.appevents.AppEventsLogger logPurchase(" +
                "java.math.BigDecimal,java.util.Currency) @generic_block 27- optional");
        assertEquals("@generic_block", hook("AppEventsLogger.logProductItem").optString("luaScript"));
        assertNull(hook("Aps.setConsentStatus"));
    }

    // The satellites of the deprecated GpsStatus listener, like those of GnssStatus
    @Test
    public void satellitesByEveryListener() throws Exception {
        check("LocationManager.addGpsStatusListener", "android.location.LocationManager " +
                "addGpsStatusListener(android.location.GpsStatus$Listener) @gnss_register 27-");
        // The messages of the satellites
        String m = "android.location.LocationManager registerGnssNavigationMessageCallback(";
        String c = "android.location.GnssNavigationMessage$Callback";
        check("LocationManager.registerGnssNavigationMessageCallback24a", m + c + ") @gnss_register 27-");
        check("LocationManager.registerGnssNavigationMessageCallback24b", m + c + ",android.os.Handler) @gnss_register 27-");
        check("LocationManager.registerGnssNavigationMessageCallback30", m + "java.util.concurrent.Executor," + c +
                ") @gnss_register 30-");
    }

    // The regions of the location fences of Awareness and of the geofences of HMS, where the app sets them, and the
    // locations HMS makes of its own, of JSON and of the parcels of its results, like those of a parcel, with
    // the same settings, so they drift by the same key
    // The regions of the location fences of Awareness and of the geofences of HMS, like the geofences of Play services,
    // and the locations HMS makes of its own, of JSON, of the parcels of its results and in LocationResult.create, like
    // those of a parcel, with the same settings, so they drift by the same key
    @Test
    public void regionsAndLocationsOfLibraries() throws Exception {
        String fence = "com.google.android.gms.awareness.fence.LocationFence ";
        String region = " @geofence$builder_setcircularregion 27- optional";
        check("LocationFence.in", fence + "in(double,double,double,long)" + region);
        check("LocationFence.entering", fence + "entering(double,double,double)" + region);
        check("LocationFence.exiting", fence + "exiting(double,double,double)" + region);
        check("Geofence$Builder.setRoundArea", "com.huawei.hms.location.Geofence$Builder setRoundArea(double,double,float)" + region);
        check("LocationJsonUtil.parseLocationFromJsonObject", "com.huawei.hms.support.api.location.common.LocationJsonUtil " +
                "parseLocationFromJsonObject(org.json.JSONObject) @location 27- optional");
        check("LocationResult.createFromParcel", "com.huawei.hms.location.LocationResult " +
                "CREATOR:createFromParcel(android.os.Parcel) @location 27- optional");
        check("LocationResult.create", "com.huawei.hms.location.LocationResult create(java.util.List) @location 27- optional");
        assertEquals("com.huawei.hms.location.LocationResult", hook("LocationResult.create").getString("returnType"));
        for (String name : new String[]{"LocationJsonUtil.parseLocationFromJsonObject", "LocationResult.createFromParcel",
                "LocationResult.create"})
            assertEquals(name, hook("Location.createFromParcel").getJSONArray("settings").toString(),
                    hook(name).getJSONArray("settings").toString());
        for (String name : new String[]{"LocationFence.in", "LocationFence.entering", "LocationFence.exiting",
                "Geofence$Builder.setRoundArea", "LocationJsonUtil.parseLocationFromJsonObject", "LocationResult.createFromParcel",
                "LocationResult.create"})
            assertEquals(name, "Get.Location", hook(name).getString("group"));
    }

    // The full lists of sensors, which the getters read and apps can call by reflection, are empty, so Android answers
    // every getter from them; the manager of the sensors of an input device is not the system one, which the hooks of
    // SensorManager resolve to (on Android 14 and later also getSensorList, which SystemSensorManager overrides), so it
    // has its own hooks from Android 12, which has it
    @Test
    public void fullListsOfSensors() throws Exception {
        check("SystemSensorManager.getFullSensorList", "android.hardware.SystemSensorManager getFullSensorList() @generic_empty 27-");
        check("SystemSensorManager.getFullDynamicSensorList",
                "android.hardware.SystemSensorManager getFullDynamicSensorList() @generic_empty 27-");
        String input = "android.hardware.input.InputDeviceSensorManager$InputSensorManager ";
        check("InputSensorManager.getDefaultSensor", input + "getDefaultSensor(int) @generic_null_value 31-");
        check("InputSensorManager.getFullSensorList", input + "getFullSensorList() @generic_empty 31-");
        for (String name : new String[]{"SystemSensorManager.getFullSensorList", "SystemSensorManager.getFullDynamicSensorList",
                "InputSensorManager.getDefaultSensor", "InputSensorManager.getFullSensorList"})
            assertEquals(name, "Get.Sensors", hook(name).getString("group"));
    }

    // The location updates of a PendingIntent, which stay parcelled in the extras of the intent
    @Test
    public void locationUpdatesOfIntents() throws Exception {
        check("Intent.createFromParcel/location", "android.content.Intent CREATOR:createFromParcel(android.os.Parcel) " +
                "@location 27-");
        assertEquals("Get.Location", hook("Intent.createFromParcel/location").getString("group"));
        assertEquals("android", hook("Intent.createFromParcel/location").getString("excludePackages"));
        // Also when Android gives the intent to a component, for an update which starts the app and for onNewIntent
        check("Intent.setExtrasClassLoader/location", "android.content.Intent setExtrasClassLoader(java.lang.ClassLoader) " +
                "@location 27-");
        assertEquals("Get.Location", hook("Intent.setExtrasClassLoader/location").getString("group"));
        assertEquals("android", hook("Intent.setExtrasClassLoader/location").getString("excludePackages"));
    }

    // Geofences get the fake region in the parcel of their request, not in the builder, of which the app reads it back
    @Test
    public void geofencesByTheirParcel() throws Exception {
        check("GeofencingRequest.writeToParcel", "com.google.android.gms.location.GeofencingRequest writeToParcel(" +
                "android.os.Parcel,int) @geofencingrequest_writetoparcel 27- optional");
        assertNull(hook("Geofence$Builder.setCircularRegion"));
    }

    // getLatLong, getAltitude and getGpsDateTime of ExifInterface read the GPS tags with the readers of tags,
    // whose hooks hide them first, so these must report or the location of photos is never in the log
    @Test
    public void photoLocationIsReported() throws Exception {
        int readers = 0;
        for (JSONObject hook : hooks)
            if ("@photo_location".equals(hook.getString("luaScript")) &&
                    hook.optString("methodName").startsWith("getAttribute")) {
                readers++;
                assertTrue(hook.getString("name"), hook.optBoolean("usage", true));
            }
        assertEquals(13, readers);
        assertTrue(hook("MediaMetadataRetriever.extractMetadata").optBoolean("usage", true));
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
        // Hidden, which apps for Android 9 and before can call; getUserSelectedOutgoingPhoneAccount without a permission
        check("TelecomManager.getCallCapablePhoneAccounts/disabled", t + "getCallCapablePhoneAccounts(boolean) @generic_empty 27-32 optional");
        // The accounts of another package by the proxy of the service, which Android 8.1 to 11 give without a permission;
        // without a return type, which the patched Android 10 changed
        check("ITelecomService.getPhoneAccountsForPackage", "com.android.internal.telecom.ITelecomService$Stub$Proxy " +
                "getPhoneAccountsForPackage(java.lang.String) @phone_accounts_package 27-30 optional");
        assertFalse(hook("ITelecomService.getPhoneAccountsForPackage").has("returnType"));
        check("TelecomManager.getSimCallManager/user", t + "getSimCallManager(int) @generic_null_value 27-32 optional");
        check("TelecomManager.getDefaultOutgoingPhoneAccount", t + "getDefaultOutgoingPhoneAccount(java.lang.String) @generic_null_value 27-32");
        check("TelecomManager.getUserSelectedOutgoingPhoneAccount", t + "getUserSelectedOutgoingPhoneAccount() @generic_null_value 27-32");
        check("TelecomManager.getSimCallManager", t + "getSimCallManager() @generic_null_value 27-32");
        check("TelephonyManager.getPhoneAccountHandle", "android.telephony.TelephonyManager getPhoneAccountHandle() @generic_null_value 31-32");
        // The number of a SIM is the address of its phone account
        for (String method : new String[]{"getAddress", "getSubscriptionAddress"})
            check("PhoneAccount." + method, "android.telecom.PhoneAccount " + method + "() @phone_account_address 27-");
        // The fields of the accounts and the subscriptions, which their parcels give, with the values of the getters
        // in the same processes as the getters
        for (String type : new String[]{"android.telecom.PhoneAccount.getAddress", "android.telephony.SubscriptionInfo.getNumber"}) {
            String className = type.substring(0, type.lastIndexOf('.'));
            String simple = className.substring(className.lastIndexOf('.') + 1);
            String name = simple + ".createFromParcel";
            check(name, className + " CREATOR:createFromParcel(android.os.Parcel) @telephony_createfromparcel 27-");
            assertEquals(name, className, hook(name).getString("returnType"));
            assertEquals(name, hook(simple + type.substring(type.lastIndexOf('.'))).optString("excludePackages"),
                    hook(name).optString("excludePackages"));
        }
        // The carrier configuration of MMS with the user agent and profile, which can name the model
        check("SmsManager.getCarrierConfigValues", "android.telephony.SmsManager getCarrierConfigValues() @mms_useragent 27-");
        // Sends past the public methods of SmsManager: hidden ones up to Android 10, the hidden manager of MMS and
        // visual voicemail
        String sms = "android.telephony.SmsManager ", pi = "android.app.PendingIntent";
        check("SmsManager.sendTextMessageWithSelfPermissions", sms + "sendTextMessageWithSelfPermissions(java.lang.String," +
                "java.lang.String,java.lang.String," + pi + "," + pi + ",boolean) @generic_block 27-29 optional");
        check("SmsManager.sendDataMessageWithSelfPermissions", sms + "sendDataMessageWithSelfPermissions(java.lang.String," +
                "java.lang.String,short,byte[]," + pi + "," + pi + ") @generic_block 27-29 optional");
        check("SmsManager.sendStoredTextMessage", sms + "sendStoredTextMessage(android.net.Uri,java.lang.String," + pi + "," + pi +
                ") @generic_block 27-29 optional");
        check("SmsManager.sendStoredMultipartTextMessage", sms + "sendStoredMultipartTextMessage(android.net.Uri,java.lang.String," +
                "java.util.ArrayList,java.util.ArrayList) @generic_block 27-29 optional");
        check("SmsManager.sendStoredMultimediaMessage", sms + "sendStoredMultimediaMessage(android.net.Uri,android.os.Bundle," + pi +
                ") @generic_block 27-29 optional");
        // Hidden on Android 8.1, where privileged and carrier apps can call it already, public from Android 9
        check("SmsManager.sendTextMessageWithoutPersisting", sms + "sendTextMessageWithoutPersisting(java.lang.String," +
                "java.lang.String,java.lang.String," + pi + "," + pi + ") @sms_send 27-");
        check("MmsManager.sendMultimediaMessage", "android.telephony.MmsManager sendMultimediaMessage(int,android.net.Uri," +
                "java.lang.String,android.os.Bundle," + pi + ",long) @generic_block 30- optional");
        String tm = "android.telephony.TelephonyManager ";
        check("TelephonyManager.sendVisualVoicemailSms", tm + "sendVisualVoicemailSms(java.lang.String,int,java.lang.String," + pi +
                ") @generic_block 27-");
        check("TelephonyManager.sendVisualVoicemailSmsForSubscriber", tm + "sendVisualVoicemailSmsForSubscriber(int," +
                "java.lang.String,int,java.lang.String," + pi + ") @generic_block 27- optional");
        // The binder proxies under them, which apps may call up to Android 10 (sendStoredMessage of MMS on all versions);
        // the methods of the proxies have other parameters from Android 11
        String isms = "com.android.internal.telephony.ISms$Stub$Proxy ", str = "java.lang.String,", lst = "java.util.List,";
        String binderData = "(int," + str + str + str + "int,byte[]," + pi + "," + pi + ") @generic_block 27-29 optional";
        String binderText = "(int," + str + str + str + str + pi + "," + pi + ",boolean";
        String binderParts = "(int," + str + str + str + lst + lst + lst + "boolean";
        String binderOptions = ",int,boolean,int) @generic_block 28-29 optional";
        check("ISms.sendDataForSubscriber", isms + "sendDataForSubscriber" + binderData);
        check("ISms.sendDataForSubscriberWithSelfPermissions", isms + "sendDataForSubscriberWithSelfPermissions" + binderData);
        check("ISms.sendTextForSubscriber", isms + "sendTextForSubscriber" + binderText + ") @generic_block 27-29 optional");
        check("ISms.sendTextForSubscriberWithSelfPermissions", isms + "sendTextForSubscriberWithSelfPermissions" + binderText +
                ") @generic_block 27-29 optional");
        check("ISms.sendTextForSubscriberWithOptions", isms + "sendTextForSubscriberWithOptions" + binderText + binderOptions);
        check("ISms.sendMultipartTextForSubscriber", isms + "sendMultipartTextForSubscriber" + binderParts +
                ") @generic_block 27-29 optional");
        check("ISms.sendMultipartTextForSubscriberWithOptions", isms + "sendMultipartTextForSubscriberWithOptions" + binderParts +
                binderOptions);
        check("ISms.sendStoredText", isms + "sendStoredText(int," + str + "android.net.Uri," + str + pi + "," + pi +
                ") @generic_block 27-29 optional");
        check("ISms.sendStoredMultipartText", isms + "sendStoredMultipartText(int," + str + "android.net.Uri," + str + lst +
                "java.util.List) @generic_block 27-29 optional");
        String imms = "com.android.internal.telephony.IMms$Stub$Proxy ";
        check("IMms.sendMessage", imms + "sendMessage(int," + str + "android.net.Uri," + str + "android.os.Bundle," + pi +
                ") @generic_block 27-29 optional");
        check("IMms.sendStoredMessage", imms + "sendStoredMessage(int," + str + "android.net.Uri,android.os.Bundle," + pi +
                ") @generic_block 27- optional");
        for (String name : new String[]{"SmsManager.sendTextMessageWithSelfPermissions", "MmsManager.sendMultimediaMessage",
                "TelephonyManager.sendVisualVoicemailSms", "TelephonyManager.sendVisualVoicemailSmsForSubscriber",
                "ISms.sendDataForSubscriber", "ISms.sendDataForSubscriberWithSelfPermissions", "ISms.sendTextForSubscriber",
                "ISms.sendTextForSubscriberWithSelfPermissions", "ISms.sendTextForSubscriberWithOptions",
                "ISms.sendMultipartTextForSubscriber", "ISms.sendMultipartTextForSubscriberWithOptions", "ISms.sendStoredText",
                "ISms.sendStoredMultipartText", "IMms.sendMessage", "IMms.sendStoredMessage"}) {
            assertEquals(name, "Send.Messages", hook(name).getString("group"));
            if (name.startsWith("I"))
                assertEquals(name, "android", hook(name).getString("excludePackages"));
        }
        // The name of the own device of Wi-Fi Direct
        check("WifiP2pDevice.createFromParcel", "android.net.wifi.p2p.WifiP2pDevice CREATOR:createFromParcel(android.os.Parcel) " +
                "@wifip2pdevice_name 27-");
        // Its address, which Android 10 and later make anonymous themselves
        check("Intent.createFromParcel/identifiers", "android.content.Intent CREATOR:createFromParcel(android.os.Parcel) " +
                "@wifip2p_own_address 27-28");
        check("WifiP2pGroup.createFromParcel", "android.net.wifi.p2p.WifiP2pGroup CREATOR:createFromParcel(android.os.Parcel) " +
                "@wifip2p_own_address 27-28");
        // The network access identifier, which Android 9 and before give to apps with the phone state permission, also
        // by the hidden methods
        check("TelephonyManager.getNai", "android.telephony.TelephonyManager getNai() @generic_null_value 27-");
        check("TelephonyManager.getNai/slot", "android.telephony.TelephonyManager getNai(int) @generic_null_value 27- optional");
        // The EID of the eSIM chip and the ICCIDs of the cards and their ports, which apps with carrier privileges get
        check("EuiccManager.getEid", "android.telephony.euicc.EuiccManager getEid() @generic_null_value 28-");
        check("UiccCardInfo.getEid", "android.telephony.UiccCardInfo getEid() @generic_null_value 29-");
        check("UiccCardInfo.getIccId", "android.telephony.UiccCardInfo getIccId() @generic_null_value 29-32");
        check("UiccPortInfo.getIccId", "android.telephony.UiccPortInfo getIccId() @uiccportinfo_iccid 33-");
        // The card id of a slot, its EID or ICCID, which privileged apps get
        check("UiccSlotInfo.getCardId", "android.telephony.UiccSlotInfo getCardId() @generic_null_value 28- optional");
        // The software version of the IMEI, a common one instead of the null a phone with a modem does not give
        check("TelephonyManager.getDeviceSoftwareVersion", "android.telephony.TelephonyManager getDeviceSoftwareVersion() " +
                "@value_device_svn 27-");
        check("TelephonyManager.getDeviceSoftwareVersion/slot", "android.telephony.TelephonyManager getDeviceSoftwareVersion(int) " +
                "@value_device_svn 27- optional");
        // The type allocation code and manufacturer code of the fake IMEI and MEID, the SIM cards of the telephony provider
        // and the merged subscriber ids (the audit of Read.Telephony)
        check("TelephonyManager.getTypeAllocationCode", "android.telephony.TelephonyManager getTypeAllocationCode() @value_device_code 29-");
        check("TelephonyManager.getManufacturerCode/slot", "android.telephony.TelephonyManager getManufacturerCode(int) @value_device_code 29-");
        check("ContentResolver.query26/siminfo", "android.content.ContentResolver query(android.net.Uri,java.lang.String[],android.os.Bundle," +
                "android.os.CancellationSignal) @contentresolver_query 27-");
        check("ContentProviderClient.query26/siminfo", "android.content.ContentProviderClient query(android.net.Uri,java.lang.String[]," +
                "android.os.Bundle,android.os.CancellationSignal) @contentresolver_query 27-");
        // The account of the SIM in the calls of the call log, with the own number and the ICCID
        check("ContentResolver.query26/call_log_account", "android.content.ContentResolver query(android.net.Uri,java.lang.String[]," +
                "android.os.Bundle,android.os.CancellationSignal) @contentresolver_query 27-");
        check("ContentProviderClient.query26/call_log_account", "android.content.ContentProviderClient query(android.net.Uri," +
                "java.lang.String[],android.os.Bundle,android.os.CancellationSignal) @contentresolver_query 27-");
        // The asserts of batches, whose queries the provider runs itself, for each variant of the queries which are emptied
        for (String variant : new String[]{"calendars", "call_log", "contacts", "blockednumber", "mmssms", "voicemail"})
            check("ContentProviderOperation.Builder.build/" + variant, "android.content.ContentProviderOperation$Builder build() " +
                    "@contentresolver_query 27-");
        // Whether a number is blocked, which the provider answers by call, like the uri and the client before Android 10
        String call = "java.lang.String,java.lang.String,android.os.Bundle) @blockednumber_call ";
        check("ContentResolver.call.uri/blockednumber", "android.content.ContentResolver call(android.net.Uri," + call + "27-28");
        check("ContentResolver.call/blockednumber", "android.content.ContentResolver call(java.lang.String," + call + "29-");
        check("ContentProviderClient.call/blockednumber", "android.content.ContentProviderClient call(" + call + "27-28");
        check("ContentProviderClient.call.authority/blockednumber", "android.content.ContentProviderClient call(java.lang.String," +
                call + "29-");
        for (String name : new String[]{"ContentResolver.call/blockednumber", "ContentProviderClient.call/blockednumber"})
            assertEquals(name, "Get.Contacts", hook(name).getString("group"));
        check("TelephonyManager.getMergedSubscriberIds", "android.telephony.TelephonyManager getMergedSubscriberIds() " +
                "@generic_null_value 27- optional");
        check("TelephonyManager.getVoiceMailAlphaTag", "android.telephony.TelephonyManager getVoiceMailAlphaTag() " +
                "@value_voicemail_tag 27-");
        // The ids of AdServices, which Android 12 has by the shared library android.ext.adservices of ExtServices, with the
        // constructors, which make the ids agree with the getters
        String ad = "android.adservices.adid.AdId ";
        check("AdId.getAdId", ad + "getAdId() @advertisingidclient$info_getid 31- optional");
        check("AdId.isLimitAdTrackingEnabled", ad + "isLimitAdTrackingEnabled() @generic_true_value 31- optional");
        check("AppSetId.getId", "android.adservices.appsetid.AppSetId getId() @advertisingidclient$info_getid 31- optional");
        for (String name : new String[]{"AdId.constructor", "AppSetId.constructor"})
            assertEquals(name, 31, hook(name).getInt("minSdk"));
        // The ICCID by the hidden getters of the card, and in the text of a subscription with the number and the group
        String sub = "android.telephony.SubscriptionInfo ";
        check("SubscriptionInfo.getCardId", sub + "getCardId() @generic_empty_text 28-28 optional");
        check("SubscriptionInfo.getCardString", sub + "getCardString() @generic_empty_text 29- optional");
        check("SubscriptionInfo.toString/telephony", sub + "toString() @operator_tostring 27-");
        assertEquals("Read.Telephony", hook("SubscriptionInfo.toString/telephony").getString("group"));
        // Media sessions of other apps, which notification listeners get
        String m = "android.media.session.MediaSessionManager ";
        check("MediaSessionManager.getActiveSessions", m + "getActiveSessions(android.content.ComponentName) @media_sessions 27-");
        check("MediaSessionManager.getActiveSessionsForPackage", m + "getActiveSessionsForPackage(java.lang.String) @media_sessions 37-");
        check("MediaSessionManager.getActiveSessionsForPackage/listener", m + "getActiveSessionsForPackage(java.lang.String," +
                "android.content.ComponentName) @media_sessions 37-");
        check("MediaSessionManager.getSession2Tokens", m + "getSession2Tokens() @media_sessions 29-");
        // The same tokens from the service of Android 12, which MediaSessionManager asks itself from then on
        check("MediaCommunicationManager.getSession2Tokens", "android.media.MediaCommunicationManager getSession2Tokens() " +
                "@generic_empty 31-");
        assertEquals("Read.Notifications", hook("MediaCommunicationManager.getSession2Tokens").getString("group"));
        check("MediaSessionManager.getMediaKeyEventSessionPackageName", m + "getMediaKeyEventSessionPackageName() @generic_empty_text 33-");
        // Listeners of the sessions are not added, the call still goes to Android (see media_session_listener)
        String l = "android.media.session.MediaSessionManager$OnActiveSessionsChangedListener";
        check("MediaSessionManager.addOnActiveSessionsChangedListener", m + "addOnActiveSessionsChangedListener(" + l +
                ",android.content.ComponentName) @media_session_listener 27-");
        check("MediaSessionManager.addOnActiveSessionsChangedListener/handler", m + "addOnActiveSessionsChangedListener(" + l +
                ",android.content.ComponentName,android.os.Handler) @media_session_listener 27-");
        // Hidden, which the two above and RemoteController call up to Android 11
        check("MediaSessionManager.addOnActiveSessionsChangedListener/user", m + "addOnActiveSessionsChangedListener(" + l +
                ",android.content.ComponentName,int,android.os.Handler) @media_session_listener 27-30 optional");
        check("MediaSessionManager.addOnActiveSessionsForPackageChangedListener", m + "addOnActiveSessionsForPackageChangedListener(" +
                "java.lang.String,java.util.concurrent.Executor," + l + ") @media_session_listener 37-");
        check("MediaSessionManager.addOnActiveSessionsForPackageChangedListener/listener", m +
                "addOnActiveSessionsForPackageChangedListener(java.lang.String,java.util.concurrent.Executor," +
                "android.content.ComponentName," + l + ") @media_session_listener 37-");
        String t2 = "android.media.session.MediaSessionManager$OnSession2TokensChangedListener";
        check("MediaSessionManager.addOnSession2TokensChangedListener", m + "addOnSession2TokensChangedListener(" + t2 +
                ") @media_session_listener 29-");
        check("MediaSessionManager.addOnSession2TokensChangedListener/handler", m + "addOnSession2TokensChangedListener(" + t2 +
                ",android.os.Handler) @media_session_listener 29-");
        check("MediaSessionManager.addOnMediaKeyEventSessionChangedListener", m + "addOnMediaKeyEventSessionChangedListener(" +
                "java.util.concurrent.Executor,android.media.session.MediaSessionManager$OnMediaKeyEventSessionChangedListener) " +
                "@media_session_listener 33-");
        // Syncs which run or wait, of other apps, which getCurrentSyncs hides, and the other ways to them
        String resolver = "android.content.ContentResolver ";
        String account = "android.accounts.Account,java.lang.String";
        check("ContentResolver.getCurrentSync", resolver + "getCurrentSync() @sync_activity 27-");
        check("ContentResolver.getCurrentSyncs", resolver + "getCurrentSyncs() @sync_activity 27-");
        check("ContentResolver.getCurrentSyncsAsUser", resolver + "getCurrentSyncsAsUser(int) @sync_activity 27- optional");
        check("ContentResolver.isSyncActive", resolver + "isSyncActive(" + account + ") @sync_activity 27-");
        check("ContentResolver.isSyncPending", resolver + "isSyncPending(" + account + ") @sync_activity 27-");
        check("ContentResolver.isSyncPendingAsUser", resolver + "isSyncPendingAsUser(" + account + ",int) @sync_activity 27- optional");
        check("ContentResolver.getPeriodicSyncs", resolver + "getPeriodicSyncs(" + account + ") @sync_activity 27-");
        // The script changes the status itself, which system server has of the sync storage
        check("ContentResolver.getSyncStatus", resolver + "getSyncStatus(" + account + ") @sync_activity 27- optional");
        check("ContentResolver.getSyncStatusAsUser", resolver + "getSyncStatusAsUser(" + account + ",int) @sync_activity 27- optional");
        for (String name : new String[]{"ContentResolver.getSyncStatus", "ContentResolver.getSyncStatusAsUser"})
            assertEquals(name, "android", hook(name).getString("excludePackages"));
        check("ContentResolver.addStatusChangeListener", resolver + "addStatusChangeListener(int,android.content.SyncStatusObserver) " +
                "@sync_activity 27-");
        // Messages are not sent, calls Android refuses without sending still go to Android (see sms_send)
        String s = "android.telephony.SmsManager ";
        String text = "java.lang.String,java.lang.String,java.lang.String,android.app.PendingIntent,android.app.PendingIntent";
        String parts = "java.lang.String,java.lang.String,java.util.List,java.util.List,java.util.List";
        String arrays = "java.lang.String,java.lang.String,java.util.ArrayList,java.util.ArrayList,java.util.ArrayList";
        String mms = "android.content.Context,android.net.Uri,java.lang.String,android.os.Bundle,android.app.PendingIntent";
        check("SmsManager.sendTextMessage", s + "sendTextMessage(" + text + ") @sms_send 27-");
        check("SmsManager.sendTextMessage/id", s + "sendTextMessage(" + text + ",long) @sms_send 30-");
        check("SmsManager.sendTextMessage/options", s + "sendTextMessage(" + text + ",int,boolean,int) @sms_send 28- optional");
        check("SmsManager.sendTextMessageWithoutPersisting", s + "sendTextMessageWithoutPersisting(" + text + ") @sms_send 27-");
        check("SmsManager.sendMultipartTextMessage", s + "sendMultipartTextMessage(" + arrays + ") @sms_send 27-");
        check("SmsManager.sendMultipartTextMessage/id", s + "sendMultipartTextMessage(" + parts + ",long) @sms_send 30-");
        check("SmsManager.sendMultipartTextMessage/attribution", s + "sendMultipartTextMessage(" + parts +
                ",java.lang.String,java.lang.String) @sms_send 30-");
        check("SmsManager.sendMultipartTextMessage/options", s + "sendMultipartTextMessage(" + arrays +
                ",int,boolean,int) @sms_send 28- optional");
        check("SmsManager.sendDataMessage", s + "sendDataMessage(java.lang.String,java.lang.String,short,byte[]," +
                "android.app.PendingIntent,android.app.PendingIntent) @sms_send 27-");
        check("SmsManager.sendMultimediaMessage", s + "sendMultimediaMessage(" + mms + ") @sms_send 27-");
        check("SmsManager.sendMultimediaMessage/id", s + "sendMultimediaMessage(" + mms + ",long) @sms_send 31-");
        // The text of notifications of listeners, the channels of conversations and the notifications of accessibility events
        String sn = "@statusbarnotification_getnotification ";
        check("StatusBarNotification.toString", "android.service.notification.StatusBarNotification toString() " + sn + "27-");
        check("StatusBarNotification.writeToParcel", "android.service.notification.StatusBarNotification " +
                "writeToParcel(android.os.Parcel,int) " + sn + "27-");
        check("StatusBarNotification.getGroupKey", "android.service.notification.StatusBarNotification getGroupKey() " + sn + "27-");
        check("NotificationListenerService.Ranking.getChannel", "android.service.notification.NotificationListenerService$Ranking " +
                "getChannel() " + sn + "30-");
        check("AccessibilityEvent.initFromParcel", "android.view.accessibility.AccessibilityEvent initFromParcel(android.os.Parcel) " +
                sn + "27-");
        assertEquals("android", hook("AccessibilityEvent.initFromParcel").getString("excludePackages"));
        check("AccessibilityRecord.getParcelableData", "android.view.accessibility.AccessibilityRecord getParcelableData() " +
                sn + "27-");
        assertEquals("android", hook("AccessibilityRecord.getParcelableData").getString("excludePackages"));
        check("AccessibilityEvent.toString", "android.view.accessibility.AccessibilityEvent toString() " + sn + "27-");
        assertEquals("android", hook("AccessibilityEvent.toString").getString("excludePackages"));
        // Install sessions and package changes name other apps
        String i = "android.content.pm.PackageInstaller ";
        check("PackageInstaller.getAllSessions", i + "getAllSessions() @install_sessions 27-");
        check("PackageInstaller.getStagedSessions", i + "getStagedSessions() @install_sessions 29-");
        check("PackageInstaller.getSessionInfo", i + "getSessionInfo(int) @install_sessions 27-");
        // A callback is told about every session of the device, by this object of Android
        String d = "android.content.pm.PackageInstaller$SessionCallbackDelegate ";
        String n = "PackageInstaller.SessionCallbackDelegate.";
        check(n + "onSessionCreated", d + "onSessionCreated(int) @install_sessions 27-");
        check(n + "onSessionBadgingChanged", d + "onSessionBadgingChanged(int) @install_sessions 27-");
        check(n + "onSessionActiveChanged", d + "onSessionActiveChanged(int,boolean) @install_sessions 27-");
        check(n + "onSessionProgressChanged", d + "onSessionProgressChanged(int,float) @install_sessions 27-");
        check(n + "onSessionFinished", d + "onSessionFinished(int,boolean) @install_sessions 27-");
        String a = "android.content.pm.LauncherApps ";
        check("LauncherApps.getAllPackageInstallerSessions", a + "getAllPackageInstallerSessions() @install_sessions 29-");
        // The callbacks of LauncherApps get the same delegate, filtered above, so Android's checks of the call stay
        assertFalse(hooks.stream().anyMatch(h -> h.optString("name").equals("LauncherApps.registerPackageInstallerSessionCallback")));
        check("LauncherApps.registerCallback", a + "registerCallback(android.content.pm.LauncherApps$Callback) @generic_block 27-");
        check("LauncherApps.registerCallback/handler", a + "registerCallback(android.content.pm.LauncherApps$Callback," +
                "android.os.Handler) @generic_block 27-");
        // Usage access tells about other apps by their package
        check("UsageStatsManager.isAppInactive", "android.app.usage.UsageStatsManager isAppInactive(java.lang.String) @package_probe 27-");
        check("StorageStatsManager.queryStatsForPackage", "android.app.usage.StorageStatsManager queryStatsForPackage(" +
                "java.util.UUID,java.lang.String,android.os.UserHandle) @package_probe 27-");
        // So does the data usage by uid, by both ways to iterate it, which must agree
        check("NetworkStats.getNextBucket", "android.app.usage.NetworkStats getNextBucket(android.app.usage.NetworkStats$Bucket) " +
                "@network_stats_filter 27-");
        check("NetworkStats.hasNextBucket", "android.app.usage.NetworkStats hasNextBucket() @network_stats_filter 27-");
        // The exact free space, Bluetooth input devices, battery saver, NSD by a request, sleep of Play services
        check("StorageManager.getAllocatableBytes", "android.os.storage.StorageManager getAllocatableBytes(java.util.UUID) @resources 27-");
        check("BluetoothHidDevice.getConnectedDevices", "android.bluetooth.BluetoothHidDevice getConnectedDevices() @generic_empty 28-");
        check("BluetoothHidDevice.getDevicesMatchingConnectionStates", "android.bluetooth.BluetoothHidDevice " +
                "getDevicesMatchingConnectionStates(int[]) @generic_empty 28-");
        // The Bluetooth address of an input device, by the hidden method both getters ask
        check("InputManagerGlobal.getInputDeviceBluetoothAddress", "android.hardware.input.InputManagerGlobal " +
                "getInputDeviceBluetoothAddress(int) @generic_null_value 34- optional");
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
        // The number of an outgoing call as the result data of its broadcast, also after goAsync
        for (String receiver : new String[]{"BroadcastReceiver", "BroadcastReceiver$PendingResult"}) {
            String name = receiver.replace("BroadcastReceiver$", "") + ".getResultData/call_log";
            check(name, "android.content." + receiver + " getResultData() @receiver_resultdata 27-");
            assertEquals("Get.Call.log", hook(name).getString("group"));
        }
        check("TelephonyManager.listen/call_log", "android.telephony.TelephonyManager listen(android.telephony.PhoneStateListener," +
                "int) @telephonymanager_listen 27-");
        assertEquals("Get.Call.log", hook("TelephonyManager.listen/call_log").getString("group"));
        // Call composer pictures, which CallLogProvider opens as of Android 12
        String file = "(android.net.Uri,java.lang.String,android.os.CancellationSignal) @contentresolver_open 31-";
        String typed = "(android.net.Uri,java.lang.String,android.os.Bundle,android.os.CancellationSignal) @contentresolver_open 31-";
        check("ContentResolver.openAssetFileDescriptor/call_log", "android.content.ContentResolver openAssetFileDescriptor" + file);
        check("ContentResolver.openTypedAssetFileDescriptor/call_log",
                "android.content.ContentResolver openTypedAssetFileDescriptor" + typed);
        check("ContentProviderClient.openFile/call_log", "android.content.ContentProviderClient openFile" + file);
        check("ContentProviderClient.openAssetFile/call_log", "android.content.ContentProviderClient openAssetFile" + file);
        check("ContentProviderClient.openTypedAssetFile/call_log", "android.content.ContentProviderClient openTypedAssetFile" + typed);
        for (String method : new String[]{"ContentResolver.openAssetFileDescriptor", "ContentResolver.openTypedAssetFileDescriptor",
                "ContentProviderClient.openFile", "ContentProviderClient.openAssetFile", "ContentProviderClient.openTypedAssetFile"})
            assertEquals(method, "Get.Call.log", hook(method + "/call_log").getString("group"));
        for (String c : new String[]{"SleepSegmentEvent", "SleepClassifyEvent"})
            check(c + ".extractEvents", "com.google.android.gms.location." + c + " extractEvents(android.content.Intent) " +
                    "@sleepevent_extractevents 27- optional");
        // The creators of the results of activity, also for the extras the app reads itself and for Awareness
        for (String c : new String[]{"ActivityRecognitionResult", "ActivityTransitionResult"}) {
            check(c + ".createFromParcel", "com.google.android.gms.location." + c + " CREATOR:createFromParcel(android.os.Parcel) @" +
                    c.toLowerCase() + "_extractresult 27- optional");
            assertEquals("Determine.Activity", hook(c + ".createFromParcel").getString("group"));
        }
        // The activity and its conversions of HMS, like those of Play services, also by their creators
        for (String c : new String[]{"ActivityIdentificationResponse", "ActivityConversionResponse"}) {
            check(c + ".getDataFromIntent", "com.huawei.hms.location." + c + " getDataFromIntent(android.content.Intent) " +
                    "@activityidentificationresponse_getdatafromintent 27- optional");
            check(c + ".createFromParcel", "com.huawei.hms.location." + c + " CREATOR:createFromParcel(android.os.Parcel) " +
                    "@activityidentificationresponse_getdatafromintent 27- optional");
            for (String method : new String[]{".getDataFromIntent", ".createFromParcel"}) {
                assertEquals("Determine.Activity", hook(c + method).getString("group"));
                assertEquals("com.huawei.hms.location." + c, hook(c + method).getString("returnType"));
            }
        }
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

    // From Android 14 QPR2, still SDK 34, the recognizer of apps is a SpeechRecognizerProxy which calls a
    // SpeechRecognizerImpl; the methods of SpeechRecognizer itself only throw then
    @Test
    public void speechRecognizerImplFromAndroid14() throws Exception {
        check("SpeechRecognizerImpl.startListening", "android.speech.SpeechRecognizerImpl startListening(" +
                "android.content.Intent) @speech_recognizer 34- optional");
        check("SpeechRecognizerImpl.setRecognitionListener", "android.speech.SpeechRecognizerImpl setRecognitionListener(" +
                "android.speech.RecognitionListener) @speech_recognizer 34- optional");
    }

    // The clipboard of the app is read by the clipboard manager; data of a parcel is also of drag and drop and of
    // intents, like the app's own drags, which are not the clipboard
    @Test
    public void clipboardIsReadFromTheClipboardManager() throws Exception {
        check("ClipboardManager.getPrimaryClip", "android.content.ClipboardManager getPrimaryClip() " +
                "@clipboard_getprimaryclip 27-");
        check("ClipboardManager.getPrimaryClipDescription", "android.content.ClipboardManager getPrimaryClipDescription() " +
                "@clipboard_getprimaryclip 27-");
        // The clips the app sets itself, which it gets back, and whether the real clip has text, which Android answers
        check("ClipboardManager.setPrimaryClip", "android.content.ClipboardManager setPrimaryClip(android.content.ClipData) " +
                "@clipboard_getprimaryclip 27-");
        check("ClipboardManager.hasText", "android.content.ClipboardManager hasText() @clipboard_getprimaryclip 27-");
    }

    // Other ways to apps hidden by Get.Applications: from the version which has the method, ordered and sticky
    // broadcasts with a result receiver, screens of app tasks, services and lookups for a user
    @Test
    public void otherWaysToHiddenApps() throws Exception {
        String s = "java.lang.String", b = "android.content.BroadcastReceiver,android.os.Handler,";
        check("PackageManager.getLaunchIntentSenderForPackage", "android.content.pm.PackageManager " +
                "getLaunchIntentSenderForPackage(" + s + ") @package_probe 33-");
        // The callbacks of sent PendingIntents and IntentSenders, of every version, get the names the app asked for
        String f = "performReceive(android.content.Intent,int," + s + ",android.os.Bundle,boolean,boolean,int) @package_probe 27-";
        check("PendingIntent.FinishedDispatcher.performReceive", "android.app.PendingIntent$FinishedDispatcher " + f);
        check("IntentSender.FinishedDispatcher.performReceive", "android.content.IntentSender$FinishedDispatcher " + f);
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
        // Key sets, which Android throws for when the package is not installed, and stopping a service
        check("PackageManager.getKeySetByAlias", "android.content.pm.PackageManager getKeySetByAlias(" + s + "," + s +
                ") @package_probe 27- optional");
        check("PackageManager.getSigningKeySet", "android.content.pm.PackageManager getSigningKeySet(" + s +
                ") @package_probe 27- optional");
        check("PackageManager.isSignedBy", "android.content.pm.PackageManager isSignedBy(" + s +
                ",android.content.pm.KeySet) @package_probe 27- optional");
        check("PackageManager.isSignedByExactly", "android.content.pm.PackageManager isSignedByExactly(" + s +
                ",android.content.pm.KeySet) @package_probe 27- optional");
        check("Context.stopService/hidden", "android.app.ContextImpl stopService(android.content.Intent) " +
                "@component_hidden 27-");
        check("Context.stopServiceAsUser/hidden", "android.app.ContextImpl stopServiceAsUser(android.content.Intent," +
                "android.os.UserHandle) @component_hidden 27-");
        // Attribution checks of Android 16 and the enabled settings, which Android looks the package up for first
        check("AppOpsManager.checkOp/attribution", "android.app.AppOpsManager checkOp(" + s + ",int," + s + "," + s +
                ") @package_probe 36-");
        check("AppOpsManager.checkOpRawNoThrow/attribution", "android.app.AppOpsManager checkOpRawNoThrow(" + s +
                ",int," + s + "," + s + ") @package_probe 36-");
        check("PackageManager.setApplicationEnabledSetting", "android.content.pm.PackageManager " +
                "setApplicationEnabledSetting(" + s + ",int,int) @package_probe 27-");
        check("PackageManager.setComponentEnabledSetting", "android.content.pm.PackageManager " +
                "setComponentEnabledSetting(android.content.ComponentName,int,int) @package_probe 27-");
        check("PowerManager.isIgnoringBatteryOptimizations", "android.os.PowerManager " +
                "isIgnoringBatteryOptimizations(" + s + ") @package_probe 27-");
        // Lists which the app passes, and the users by their serial numbers
        check("PackageManager.getPreferredActivities", "android.content.pm.PackageManager getPreferredActivities(" +
                "java.util.List,java.util.List," + s + ") @generic_filter_by_uid 27-");
        check("UserManager.getUserForSerialNumber", "android.os.UserManager getUserForSerialNumber(long) @user_profiles 27-");
        check("UserManager.getSerialNumberForUser", "android.os.UserManager getSerialNumberForUser(android.os.UserHandle) " +
                "@user_profiles 27-");
    }

    // The shared libraries name the apps which use them and declare them: both overloads also in Get.Applications,
    // with the script of Read.Features, which tells the groups apart (see features)
    @Test
    public void sharedLibrariesOfHiddenApps() throws Exception {
        check("PackageManager.getSharedLibraries/apps", "android.content.pm.PackageManager getSharedLibraries(int) " +
                "@features 27-");
        check("PackageManager.getSharedLibraries/flags/apps", "android.content.pm.PackageManager getSharedLibraries(" +
                "android.content.pm.PackageManager$PackageInfoFlags) @features 33-");
        // The hidden methods for a user, which the public ones call and apps can call by reflection
        check("PackageManager.getSharedLibrariesAsUser", "android.content.pm.PackageManager getSharedLibrariesAsUser(" +
                "int,int) @features 27- optional");
        check("PackageManager.getSharedLibrariesAsUser/flags", "android.content.pm.PackageManager getSharedLibrariesAsUser(" +
                "android.content.pm.PackageManager$PackageInfoFlags,int) @features 33- optional");
        for (String name : new String[]{"PackageManager.getSharedLibraries/apps", "PackageManager.getSharedLibraries/flags/apps",
                "PackageManager.getSharedLibrariesAsUser", "PackageManager.getSharedLibrariesAsUser/flags"}) {
            assertEquals(name, "Get.Applications", hook(name).getString("group"));
            assertEquals(name, "android", hook(name).getString("excludePackages"));
        }
    }

    // Ways to hidden apps the audit of Get.Applications found without a hook: hidden overloads apps can call by
    // reflection, newer public ones, binds and ordered broadcasts with a Handler or an app op, the current keyboard
    // of a user, the spell checker of a session and the certificate of the store which installed the app
    @Test
    public void moreWaysToHiddenApps() throws Exception {
        String s = "java.lang.String", i = "android.content.Intent", u = "android.os.UserHandle";
        String r = "android.content.BroadcastReceiver,android.os.Handler,int," + s + ",android.os.Bundle";
        check("Context.createPackageContextAsUser", "android.app.ContextImpl createPackageContextAsUser(" + s +
                ",int," + u + ") @package_probe 27- optional");
        check("PackageManager.isPackageSuspendedForUser", "android.content.pm.PackageManager isPackageSuspendedForUser(" +
                s + ",int) @package_probe 27- optional");
        check("PackageManager.getAllIntentFilters", "android.content.pm.PackageManager getAllIntentFilters(" + s +
                ") @package_probe 27- optional");
        check("PackageManager.getInstallReason", "android.content.pm.PackageManager getInstallReason(" + s + "," + u +
                ") @package_probe 27- optional");
        check("PackageManager.getIntentFilterVerifications", "android.content.pm.PackageManager " +
                "getIntentFilterVerifications(" + s + ") @package_probe 27- optional");
        check("PackageManager.relinquishUpdateOwnership", "android.content.pm.PackageManager relinquishUpdateOwnership(" +
                s + ") @package_probe 34-");
        check("PackageInstaller.requestArchive", "android.content.pm.PackageInstaller requestArchive(" + s +
                ",android.content.IntentSender) @package_probe 35-");
        check("PackageInstaller.requestUnarchive", "android.content.pm.PackageInstaller requestUnarchive(" + s +
                ",android.content.IntentSender) @package_probe 35-");
        check("LauncherApps.getSuspendedPackageLauncherExtras", "android.content.pm.LauncherApps " +
                "getSuspendedPackageLauncherExtras(" + s + "," + u + ") @package_probe 28-");
        check("AppOpsManager.checkOp/code", "android.app.AppOpsManager checkOp(int,int," + s + ") @package_probe 27- optional");
        check("AppOpsManager.checkOpNoThrow/code", "android.app.AppOpsManager checkOpNoThrow(int,int," + s +
                ") @package_probe 27- optional");
        check("Context.bindServiceAsUser.handler/hidden", "android.app.ContextImpl bindServiceAsUser(" + i +
                ",android.content.ServiceConnection,int,android.os.Handler," + u + ") @component_hidden 27- optional");
        check("Context.bindServiceAsUser.flags.handler/hidden", "android.app.ContextImpl bindServiceAsUser(" + i +
                ",android.content.ServiceConnection,android.content.Context$BindServiceFlags,android.os.Handler," + u +
                ") @component_hidden 34- optional");
        check("Context.sendOrderedBroadcast.op/hidden", "android.app.ContextImpl sendOrderedBroadcast(" + i + "," + s +
                ",int," + r + ") @component_hidden 27- optional");
        check("Context.sendOrderedBroadcast.op.options/hidden", "android.app.ContextImpl sendOrderedBroadcast(" + i + "," +
                s + ",int," + r + ",android.os.Bundle) @component_hidden 27- optional");
        check("Context.sendOrderedBroadcastAsUser.op/hidden", "android.app.ContextImpl sendOrderedBroadcastAsUser(" + i +
                "," + u + "," + s + ",int," + r + ") @component_hidden 27- optional");
        check("Context.sendOrderedBroadcastAsUser.op.options/hidden", "android.app.ContextImpl sendOrderedBroadcastAsUser(" +
                i + "," + u + "," + s + ",int,android.os.Bundle," + r + ") @component_hidden 27- optional");
        check("InputMethodManager.getCurrentInputMethodInfoAsUser", "android.view.inputmethod.InputMethodManager " +
                "getCurrentInputMethodInfoAsUser(" + u + ") @service_filter 34- optional");
        check("SpellCheckerSession.getSpellChecker", "android.view.textservice.SpellCheckerSession getSpellChecker() " +
                "@service_filter 27-");
        check("InstallSourceInfo.getInitiatingPackageSigningInfo", "android.content.pm.InstallSourceInfo " +
                "getInitiatingPackageSigningInfo() @package_result 30-");
        for (String[] acquire : new String[][]{{"acquireProvider/hidden", "android.net.Uri"},
                {"acquireProvider.authority/hidden", s}, {"acquireUnstableProvider/hidden", "android.net.Uri"},
                {"acquireUnstableProvider.authority/hidden", s}})
            check("ContentResolver." + acquire[0], "android.content.ContentResolver " + acquire[0].replaceAll("[./].*", "") +
                    "(" + acquire[1] + ") @provider_hidden 27- optional");
        String observer = "android.content.ContentResolver registerContentObserver(android.net.Uri,boolean," +
                "android.database.ContentObserver";
        check("ContentResolver.registerContentObserver/hidden", observer + ") @provider_hidden 27-");
        check("ContentResolver.registerContentObserver.user/hidden", observer + ",int) @provider_hidden 27- optional");
        String notify = "android.content.ContentResolver notifyChange(";
        check("ContentResolver.notifyChange/hidden", notify + "android.net.Uri,android.database.ContentObserver) @provider_hidden 27-");
        check("ContentResolver.notifyChange.sync/hidden", notify + "android.net.Uri,android.database.ContentObserver,boolean) " +
                "@provider_hidden 27-");
        check("ContentResolver.notifyChange.flags/hidden", notify + "android.net.Uri,android.database.ContentObserver,int) " +
                "@provider_hidden 27-");
        check("ContentResolver.notifyChange.sync.user/hidden", notify + "android.net.Uri,android.database.ContentObserver," +
                "boolean,int) @provider_hidden 27- optional");
        check("ContentResolver.notifyChange.flags.user/hidden", notify + "android.net.Uri,android.database.ContentObserver," +
                "int,int) @provider_hidden 27- optional");
        check("ContentResolver.notifyChange.uris/hidden", notify + "java.util.Collection,android.database.ContentObserver,int) " +
                "@provider_hidden 30-");
        check("PackageManager.resolveServiceAsUser", "android.content.pm.PackageManager resolveServiceAsUser(" + i +
                ",int,int) @package_resolve 27- optional");
        check("PackageManager.resolveServiceAsUser/flags", "android.content.pm.PackageManager resolveServiceAsUser(" + i +
                ",android.content.pm.PackageManager$ResolveInfoFlags,int) @package_resolve 33- optional");
        check("PackageManager.getNamesForUids", "android.content.pm.PackageManager getNamesForUids(int[]) " +
                "@package_resolve 27- optional");
        check("ActivityManager.getProcessesInErrorState", "android.app.ActivityManager getProcessesInErrorState() " +
                "@generic_filter_by_uid 27-");
        check("TelecomManager.getSelfManagedPhoneAccounts", "android.telecom.TelecomManager getSelfManagedPhoneAccounts() " +
                "@generic_filter_by_uid 27-");
        check("UsageStatsManager.getAppStandbyBuckets", "android.app.usage.UsageStatsManager getAppStandbyBuckets() " +
                "@generic_filter_by_uid 28- optional");
        check("UsageStatsManager.getAppStandbyBucket", "android.app.usage.UsageStatsManager getAppStandbyBucket(" + s +
                ") @package_probe 28- optional");
        check("AppWidgetManager.getInstalledProviders.category", "android.appwidget.AppWidgetManager " +
                "getInstalledProviders(int) @generic_filter_by_uid 27- optional");
        check("AppWidgetManager.getInstalledProvidersForProfile.category", "android.appwidget.AppWidgetManager " +
                "getInstalledProvidersForProfile(int," + u + "," + s + ") @generic_filter_by_uid 27- optional");
        check("DevicePolicyManager.getActiveAdminsAsUser", "android.app.admin.DevicePolicyManager getActiveAdminsAsUser(int) " +
                "@generic_filter_by_uid 27- optional");
        check("ContentResolver.getSyncAdapterTypesAsUser", "android.content.ContentResolver getSyncAdapterTypesAsUser(int) " +
                "@package_resolve 27- optional");
        check("ContentResolver.getSyncAdapterPackagesForAuthorityAsUser", "android.content.ContentResolver " +
                "getSyncAdapterPackagesForAuthorityAsUser(" + s + ",int) @package_resolve 27- optional");
        check("ActivityManager.getRunningServiceControlPanel", "android.app.ActivityManager getRunningServiceControlPanel(" +
                "android.content.ComponentName) @package_probe 27-");
        check("SearchManager.getSearchablesInGlobalSearch", "android.app.SearchManager getSearchablesInGlobalSearch() " +
                "@generic_filter_by_uid 27-");
        check("SearchManager.getGlobalSearchActivities", "android.app.SearchManager getGlobalSearchActivities() " +
                "@generic_filter_by_uid 27- optional");
        check("SearchManager.getWebSearchActivity", "android.app.SearchManager getWebSearchActivity() @package_result 27- optional");
        check("ActivityManager.getRunningTasks.27", "android.app.ActivityManager getRunningTasks(int) @generic_filter_by_uid 27-");
        check("PackageManager.getHomeActivities", "android.content.pm.PackageManager getHomeActivities(java.util.List) " +
                "@generic_filter_by_uid 27- optional");
    }

    // The default browser, a system API of the package manager on all versions and of the role behind it from
    // Android 12, and the app which made a PendingIntent or IntentSender, by each getter
    @Test
    public void defaultBrowserAndCreatorsOfIntents() throws Exception {
        check("PackageManager.getDefaultBrowserPackageNameAsUser", "android.content.pm.PackageManager " +
                "getDefaultBrowserPackageNameAsUser(int) @default_browser 27- optional");
        check("RoleManager.getBrowserRoleHolder", "android.app.role.RoleManager getBrowserRoleHolder(int) @default_browser 31- optional");
        for (String cls : new String[]{"android.app.PendingIntent", "android.content.IntentSender"})
            for (String method : new String[]{"getCreatorPackage", "getTargetPackage", "getCreatorUid", "getCreatorUserHandle"}) {
                String name = cls.substring(cls.lastIndexOf('.') + 1) + "." + method;
                check(name, cls + " " + method + "() @pendingintent_creator 27-");
                assertEquals(name, "android", hook(name).getString("excludePackages"));
            }
    }

    // Other users by a handle, and the attribution and GPS tags of photos, which answer like the hooked siblings
    @Test
    public void otherUsersAndPhotoAttributesAreHooked() throws Exception {
        check("UserManager.isUserRunningOrStopping", "android.os.UserManager isUserRunningOrStopping(" +
                "android.os.UserHandle) @user_profiles 27-");
        check("UserManager.getUserRestrictions", "android.os.UserManager getUserRestrictions(" +
                "android.os.UserHandle) @user_profiles 27-");
        String s = "java.lang.String";
        check("ExifInterface.hasAttribute", "android.media.ExifInterface hasAttribute(" + s + ") @photo_location 29-");
        check("ExifInterface.getAttributeRange", "android.media.ExifInterface getAttributeRange(" + s +
                ") @photo_location 29-");
        check("ExifInterfaceX.hasAttribute", "androidx.exifinterface.media.ExifInterface hasAttribute(" + s +
                ") @photo_location 27- optional");
        check("ExifInterfaceX.getAttributeRange", "androidx.exifinterface.media.ExifInterface getAttributeRange(" +
                s + ") @photo_location 27- optional");
        check("ExifInterfaceS.hasAttribute", "android.support.media.ExifInterface hasAttribute(" + s +
                ") @photo_location 27- optional");
    }

    // Settings, intents, accounts and the default account of contacts restricted for Android would change what
    // system server itself reads and stores, like adb_enabled or the accounts apps add, and refused commands the
    // logs it collects with them, so Android is excluded
    @Test
    public void systemServerKeepsItsOwnValues() throws Exception {
        List<String> errors = new ArrayList<>();
        for (JSONObject hook : hooks) {
            String script = hook.getString("luaScript");
            boolean settings = script.equals("@locale") && hook.getString("className").startsWith("android.provider.Settings");
            if ((settings || script.equals("@settingssecure_getstring") || script.equals("@intent_createfromparcel") ||
                    script.equals("@account_createfromparcel") || script.equals("@contacts_default_account") ||
                    script.equals("@commands_blocked")) &&
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
            // File.getCanonicalPath follows links, like the one of su to Magisk; the native method has the path
            // alone, or with a flag in newer versions of the runtime, which Android 12 and later update
            check("UnixFileSystem.canonicalize0/" + suffix, "java.io.UnixFileSystem canonicalize0(" + s + ") @hardware_files 27-");
            check("UnixFileSystem.canonicalize0/native/" + suffix, "java.io.UnixFileSystem canonicalize0(" + s +
                    ",boolean) @hardware_files 31- optional");
        }
        check("UnixFileSystem.list0/root", "java.io.UnixFileSystem list0(" + f + ") @hardware_files 27-");
        check("UnixFileSystem.getLastModifiedTime/root", "java.io.UnixFileSystem getLastModifiedTime(" + f + ") @hardware_files 27-");
        check("UnixNativeDispatcher.opendir/root", "sun.nio.fs.UnixNativeDispatcher opendir(sun.nio.fs.UnixPath) @hardware_files 27-");
        check("Os.readlink/root", "android.system.Os readlink(" + s + ") @hardware_files 27-");
        // Opening su tells it exists, like for the other groups of hidden files
        check("BlockGuardOs.open/root", "libcore.io.BlockGuardOs open(" + s + ",int,int) @hardware_files 27-");
        // The id of the file system by the native methods, which Libcore.os and Libcore.rawOs reach without Os
        check("Linux.statvfs/fsid", "libcore.io.Linux statvfs(" + s + ") @statvfs_fsid 27-");
        check("Linux.fstatvfs/fsid", "libcore.io.Linux fstatvfs(java.io.FileDescriptor) @statvfs_fsid 27-");
        check("UnixNativeDispatcher.open/root", "sun.nio.fs.UnixNativeDispatcher open(sun.nio.fs.UnixPath,int,int) @hardware_files 27-");
        check("UnixFileSystem.createFileExclusively/root", "java.io.UnixFileSystem createFileExclusively(" + s + ") @hardware_files 27-");
        check("Context.sendStickyBroadcast/hidden", "android.app.ContextImpl sendStickyBroadcast(" + i + ") @component_hidden 27-");
        check("Context.sendStickyBroadcast.options/hidden", "android.app.ContextImpl sendStickyBroadcast(" + i +
                ",android.os.Bundle) @component_hidden 31-");
        check("Context.sendStickyBroadcastAsUser/hidden", "android.app.ContextImpl sendStickyBroadcastAsUser(" + i +
                ",android.os.UserHandle) @component_hidden 27-");
        // Removing a sticky broadcast gets the same copy as sending it
        check("Context.removeStickyBroadcast/hidden", "android.app.ContextImpl removeStickyBroadcast(" + i + ") @component_hidden 27-");
        check("Context.removeStickyBroadcastAsUser/hidden", "android.app.ContextImpl removeStickyBroadcastAsUser(" + i +
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
                "java.util.concurrent.Executor,android.location.GnssStatus$Callback) @gnss_register 30-");
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
        // The name of an account as text in the result of the chooser of accounts and the broadcast of a removed account
        check("Intent.createFromParcel/account", "android.content.Intent CREATOR:createFromParcel(android.os.Parcel) " +
                "@account_createfromparcel 27-");
        assertEquals("Read.Account", hook("Intent.createFromParcel/account").getString("group"));
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

    // The calls which look up the path of a hidden permission file of a system app before anything else: the
    // attributes, the mode, the owner and a link to it, by android.system.Os and by java.nio.file
    @Test
    public void privappFilesByEveryPathCall() throws Exception {
        String s = "java.lang.String", p = "sun.nio.fs.UnixPath";
        check("Os.listxattr/privapp_files", "android.system.Os listxattr(" + s + ") @hardware_files 27-");
        check("Os.setxattr/privapp_files", "android.system.Os setxattr(" + s + "," + s + ",byte[],int) @hardware_files 27-");
        check("Os.removexattr/privapp_files", "android.system.Os removexattr(" + s + "," + s + ") @hardware_files 27-");
        check("Os.chmod/privapp_files", "android.system.Os chmod(" + s + ",int) @hardware_files 27-");
        check("Os.chown/privapp_files", "android.system.Os chown(" + s + ",int,int) @hardware_files 27-");
        check("Os.lchown/privapp_files", "android.system.Os lchown(" + s + ",int,int) @hardware_files 27-");
        check("Os.link/privapp_files", "android.system.Os link(" + s + "," + s + ") @hardware_files 27-");
        check("UnixNativeDispatcher.chmod/privapp_files", "sun.nio.fs.UnixNativeDispatcher chmod(" + p + ",int) @hardware_files 27-");
        check("UnixNativeDispatcher.chown/privapp_files", "sun.nio.fs.UnixNativeDispatcher chown(" + p +
                ",int,int) @hardware_files 27-");
        check("UnixNativeDispatcher.lchown/privapp_files", "sun.nio.fs.UnixNativeDispatcher lchown(" + p +
                ",int,int) @hardware_files 27-");
        check("UnixNativeDispatcher.link/privapp_files", "sun.nio.fs.UnixNativeDispatcher link(" + p + "," + p +
                ") @hardware_files 27-");
        for (String name : new String[]{"Os.listxattr", "Os.chmod", "Os.link", "UnixNativeDispatcher.chmod"})
            assertEquals(name, "Get.Applications", hook(name + "/privapp_files").getString("group"));
    }

    // The same calls for the ids of /sys, and removing a hidden id and making a folder or a pipe by its name, which tell
    // it exists (EACCES, EEXIST) where a missing name gives ENOENT and EACCES
    @Test
    public void hardwareIdsByEveryPathCall() throws Exception {
        String s = "java.lang.String", p = "sun.nio.fs.UnixPath";
        for (String name : new String[]{"Os.listxattr", "Os.setxattr", "Os.removexattr", "Os.chmod", "Os.chown", "Os.lchown",
                "Os.link", "UnixNativeDispatcher.chmod", "UnixNativeDispatcher.chown", "UnixNativeDispatcher.lchown",
                "UnixNativeDispatcher.link"}) {
            JSONObject apps = hook(name + "/privapp_files"), ids = hook(name + "/hardware_ids");
            assertNotNull(name, ids);
            for (String key : new String[]{"className", "methodName", "parameterTypes", "returnType", "minSdk", "luaScript"})
                assertEquals(name + " " + key, apps.get(key).toString(), ids.get(key).toString());
            assertEquals(name, "Read.Identifiers", ids.getString("group"));
        }
        check("Os.remove/hardware_ids", "android.system.Os remove(" + s + ") @hardware_files 27-");
        check("Os.mkdir/hardware_ids", "android.system.Os mkdir(" + s + ",int) @hardware_files 27-");
        check("Os.mkfifo/hardware_ids", "android.system.Os mkfifo(" + s + ",int) @hardware_files 27-");
        check("UnixNativeDispatcher.mkdir/hardware_ids", "sun.nio.fs.UnixNativeDispatcher mkdir(" + p + ",int) @hardware_files 27-");
        // A symbolic link by the name of a hidden id (EEXIST) and moving it (EACCES), where a missing name gives EACCES
        // and ENOENT
        check("Os.symlink/hardware_ids", "android.system.Os symlink(" + s + "," + s + ") @hardware_files 27-");
        check("Os.rename/hardware_ids", "android.system.Os rename(" + s + "," + s + ") @hardware_files 27-");
        check("UnixNativeDispatcher.symlink/hardware_ids", "sun.nio.fs.UnixNativeDispatcher symlink(byte[]," + p + ") @hardware_files 27-");
        check("UnixNativeDispatcher.rename/hardware_ids", "sun.nio.fs.UnixNativeDispatcher rename(" + p + "," + p +
                ") @hardware_files 27-");
        for (String name : new String[]{"Os.remove", "Os.mkdir", "Os.mkfifo", "UnixNativeDispatcher.mkdir", "Os.symlink", "Os.rename",
                "UnixNativeDispatcher.symlink", "UnixNativeDispatcher.rename"})
            assertEquals(name, "Read.Identifiers", hook(name + "/hardware_ids").getString("group"));
    }

    // A SecureDirectoryStream opens the files of its folder and reads their attributes by the descriptor of the folder
    // and their name, which the script checks itself, since the filter only compares paths
    @Test
    public void hardwareIdsOfASecureDirectoryStream() throws Exception {
        check("UnixNativeDispatcher.openat/hardware_ids", "sun.nio.fs.UnixNativeDispatcher openat(int,byte[],int,int) @hardware_files 27-");
        check("UnixNativeDispatcher.fstatat/hardware_ids",
                "sun.nio.fs.UnixNativeDispatcher fstatat(int,byte[],int,sun.nio.fs.UnixFileAttributes) @hardware_files 27-");
        // Removing and moving them by name (AOSP oreo-mr1 to main)
        check("UnixNativeDispatcher.unlinkat/hardware_ids", "sun.nio.fs.UnixNativeDispatcher unlinkat(int,byte[],int) @hardware_files 27-");
        check("UnixNativeDispatcher.renameat/hardware_ids",
                "sun.nio.fs.UnixNativeDispatcher renameat(int,byte[],int,byte[]) @hardware_files 27-");
        for (String name : new String[]{"UnixNativeDispatcher.openat", "UnixNativeDispatcher.fstatat",
                "UnixNativeDispatcher.unlinkat", "UnixNativeDispatcher.renameat"})
            assertEquals(name, "Read.Identifiers", hook(name + "/hardware_ids").getString("group"));
    }

    // The files of the sensor features are hidden by the same calls as the permission files of system apps, since
    // both are files of a folder which stays; opening the other feature files reads them without the sensor features
    @Test
    public void sensorFilesByEveryPathCall() throws Exception {
        int files = 0;
        for (JSONObject hook : hooks)
            if (hook.getString("name").endsWith("/privapp_files")) {
                files++;
                String name = hook.getString("name").replace("/privapp_files", "/sensor_files");
                JSONObject sensors = hook(name);
                assertNotNull(name, sensors);
                assertEquals(name, "Get.Sensors", sensors.getString("group"));
                for (String key : new String[]{"className", "methodName", "parameterTypes", "returnType", "minSdk",
                        "maxSdk", "optional", "luaScript"})
                    assertEquals(name + " " + key, String.valueOf(hook.opt(key)), String.valueOf(sensors.opt(key)));
            }
        assertEquals(40, files);
    }

    // Commands by Os.execv and execve are refused in every group which refuses ProcessBuilder.start
    @Test
    public void programsByEveryWayToRun() throws Exception {
        String s = "java.lang.String";
        int groups = 0;
        for (JSONObject hook : hooks)
            if (hook.getString("name").startsWith("ProcessBuilder.start")) {
                groups++;
                String suffix = hook.getString("name").substring("ProcessBuilder.start".length());
                check("Os.execv" + suffix, "android.system.Os execv(" + s + "," + s + "[]) @commands_blocked 27-");
                check("Os.execve" + suffix, "android.system.Os execve(" + s + "," + s + "[]," + s + "[]) @commands_blocked 27-");
                assertEquals(hook.getString("group"), hook("Os.execv" + suffix).getString("group"));
                assertEquals(hook.getString("group"), hook("Os.execve" + suffix).getString("group"));
            }
        assertEquals(13, groups);
    }

    // The methods beneath ProcessBuilder.start and Os.execv and execve, which apps can call by reflection, for the
    // hidden apps and for commands themselves
    @Test
    public void programsBeneathTheWaysToRun() throws Exception {
        String s = "java.lang.String";
        String[][] groups = {{"packages", "Get.Applications"}, {"commands", "Run.Commands"}};
        for (String[] group : groups) {
            String suffix = "/" + group[0];
            check("UNIXProcess.forkAndExec" + suffix,
                    "java.lang.UNIXProcess forkAndExec(byte[],byte[],int,byte[],int,byte[],int[],boolean) @commands_blocked 27-");
            check("Linux.execv" + suffix, "libcore.io.Linux execv(" + s + "," + s + "[]) @commands_blocked 27-");
            check("Linux.execve" + suffix, "libcore.io.Linux execve(" + s + "," + s + "[]," + s + "[]) @commands_blocked 27-");
            for (String name : new String[]{"UNIXProcess.forkAndExec", "Linux.execv", "Linux.execve"})
                assertEquals(group[1], hook(name + suffix).getString("group"));
        }
    }

    // The other ways to the values of fingerprints: the pages of memory, the methods behind SystemProperties.get, the
    // metrics and parameters of codecs, listeners of the heat; of the vibrator SystemVibrator only, which is
    // what Vibrator resolves to
    @Test
    public void fingerprintsByOtherWays() throws Exception {
        String s = "java.lang.String", p = "android.os.PowerManager";
        String status = p + "$OnThermalStatusChangedListener", headroom = p + "$OnThermalHeadroomChangedListener";
        String e = "java.util.concurrent.Executor,";
        check("Os.sysconf/resources", "android.system.Os sysconf(int) @resources 27-");
        check("ForwardingOs.sysconf/resources", "libcore.io.ForwardingOs sysconf(int) @resources 27-");
        for (String variant : new String[]{"serial", "operator", "device", "timezone", "debugging", "resources"})
            check("SystemProperties.native_get/" + variant, "android.os.SystemProperties native_get(" + s +
                    ") @systemproperties_get 27- optional");
        check("SystemProperties.native_get/locale", "android.os.SystemProperties native_get(" + s + ") @locale 27- optional");
        // native_get(key, default), which apps for Android 9 and before reach by reflection; a name with .default/,
        // so the scripts keep the default of a property which is not set
        check("SystemProperties.native_get.default/timezone", "android.os.SystemProperties native_get(" + s + "," + s +
                ") @systemproperties_get 27- optional");
        check("SystemProperties.native_get.default/locale", "android.os.SystemProperties native_get(" + s + "," + s +
                ") @locale 27- optional");
        check("ITextToSpeechService.getClientDefaultLanguage", "android.speech.tts.ITextToSpeechService$Stub$Proxy " +
                "getClientDefaultLanguage() @locale 27- optional");
        // The languages of the keyboards, by the same methods on all versions
        String imm = "android.view.inputmethod.InputMethodManager ";
        check("InputMethodManager.getCurrentInputMethodSubtype", imm + "getCurrentInputMethodSubtype() @locale 27-");
        check("InputMethodManager.getLastInputMethodSubtype", imm + "getLastInputMethodSubtype() @locale 27-");
        check("InputMethodManager.getEnabledInputMethodSubtypeList", imm + "getEnabledInputMethodSubtypeList(" +
                "android.view.inputmethod.InputMethodInfo,boolean) @locale 27-");
        check("InputMethodManager.getShortcutInputMethodsAndSubtypes", imm + "getShortcutInputMethodsAndSubtypes() @locale 27-");
        for (String name : new String[]{"getCurrentInputMethodSubtype", "getLastInputMethodSubtype",
                "getEnabledInputMethodSubtypeList", "getShortcutInputMethodsAndSubtypes"})
            assertEquals(name, "Read.Locale", hook("InputMethodManager." + name).getString("group"));
        check("ResourcesImpl.updateConfigurationImpl", "android.content.res.ResourcesImpl updateConfigurationImpl(" +
                "android.content.res.Configuration,android.util.DisplayMetrics,android.content.res.CompatibilityInfo,boolean) " +
                "@display_panel 34- optional");
        check("MediaCodec.getMetrics", "android.media.MediaCodec getMetrics() @media_codecs 27- optional");
        check("MediaPlayer.getMetrics", "android.media.MediaPlayer getMetrics() @media_codecs 27- optional");
        check("MediaCodec.getSupportedVendorParameters", "android.media.MediaCodec getSupportedVendorParameters() " +
                "@generic_empty 31- optional");
        check("MediaCodec.getParameterDescriptor", "android.media.MediaCodec getParameterDescriptor(" + s +
                ") @generic_null_value 31- optional");
        check("BiometricManager.canAuthenticate/lock",
                "android.hardware.biometrics.BiometricManager canAuthenticate(int) @device_details 30-");
        check("PowerManager.addThermalStatusListener", p + " addThermalStatusListener(" + status + ") @device_details 29-");
        check("PowerManager.addThermalStatusListener/executor", p + " addThermalStatusListener(" + e + status + ") @device_details 29-");
        check("PowerManager.removeThermalStatusListener", p + " removeThermalStatusListener(" + status + ") @device_details 29-");
        check("PowerManager.addThermalHeadroomListener", p + " addThermalHeadroomListener(" + headroom + ") @device_details 36-");
        check("PowerManager.addThermalHeadroomListener/executor", p + " addThermalHeadroomListener(" + e + headroom + ") @device_details 36-");
        check("PowerManager.removeThermalHeadroomListener", p + " removeThermalHeadroomListener(" + headroom + ") @device_details 36-");
        check("SystemVibrator.getInfo", "android.os.SystemVibrator getInfo() @device_details 31- optional");
        assertNull(hook("Vibrator.getInfo"));
    }

    // The hidden getters of LinkProperties, with the links on top of it; before Android 11 also of its own values
    @Test
    public void addressesByHiddenGetters() throws Exception {
        check("NetworkInfo.getReason", "android.net.NetworkInfo getReason() @vpn 27-");
        check("NetworkInfo.toString/vpn", "android.net.NetworkInfo toString() @vpn 27-");
        for (String[] getter : new String[][]{{"getAllAddresses", ""}, {"getAddresses", "29"}, {"getAllLinkAddresses", "29"},
                {"getAllRoutes", "29"}, {"getAllInterfaceNames", "29"}}) {
            String name = "LinkProperties." + getter[0];
            check(name, "android.net.LinkProperties " + getter[0] + "() @network_address 27-" + getter[1] + " optional");
            check(name + "/vpn", "android.net.LinkProperties " + getter[0] + "() @vpn_addresses 27-" + getter[1] + " optional");
        }
    }

    // The prompt of biometrics, the developer options in the system settings, the torch, the recordings and the
    // devices of audio, and the analytics of Kotlin apps (the audit of 2026-11-11)
    @Test
    public void deviceStateAndMediaDefinitions() throws Exception {
        String s = "java.lang.String";
        String prompt = "android.hardware.biometrics.BiometricPrompt";
        check("BiometricPrompt.authenticate", prompt + " authenticate(android.os.CancellationSignal," +
                "java.util.concurrent.Executor," + prompt + "$AuthenticationCallback) @biometric_prompt 30-");
        check("BiometricPrompt.authenticate/crypto", prompt + " authenticate(" + prompt + "$CryptoObject," +
                "android.os.CancellationSignal,java.util.concurrent.Executor," + prompt + "$AuthenticationCallback) @biometric_prompt 30-");
        check("Settings.System.getStringForUser/adb", "android.provider.Settings$System getStringForUser(" +
                "android.content.ContentResolver," + s + ",int) @settingssecure_getstring 27- optional");
        check("CameraManager2.setTorchMode", "android.hardware.camera2.CameraManager setTorchMode(" + s + ",boolean) @camera2_open 27-");
        check("CameraManager2.turnOnTorchWithStrengthLevel", "android.hardware.camera2.CameraManager turnOnTorchWithStrengthLevel(" +
                s + ",int) @camera2_open 33-");
        check("AudioRecord.startRecording", "android.media.AudioRecord startRecording() @audio_record 27-");
        check("AudioRecord.getRecordingState", "android.media.AudioRecord getRecordingState() @audio_record 27-");
        check("AudioRecord.read/short", "android.media.AudioRecord read(short[],int,int) @audio_record 27-");
        check("AudioRecord.read/float/mode", "android.media.AudioRecord read(float[],int,int,int) @audio_record 27-");
        check("AudioRecord.read/buffer/mode", "android.media.AudioRecord read(java.nio.ByteBuffer,int,int) @audio_record 27-");
        check("AudioManager.getCommunicationDevice", "android.media.AudioManager getCommunicationDevice() @audio_devices 31-");
        check("AudioManager.isWiredHeadsetOn", "android.media.AudioManager isWiredHeadsetOn() @generic_block 27-");
        check("AppEventsLogger.activateApp/companion/app", "com.facebook.appevents.AppEventsLogger$Companion activateApp(" +
                "android.app.Application) @generic_block 27- optional");
        check("Amplitude.getOptOut/android", "com.amplitude.android.Configuration getOptOut() @generic_true_value 27- optional");
        // The settings provider sees the system settings of the developer options too
        assertTrue(Arrays.asList(hook("Settings.System.getStringForUser/adb").optString("excludePackages", "").split(","))
                .contains("android"));
    }

    // The permission checks of contexts, the packages of the analytics, the transports of a VPN and the account types of apps
    @Test
    public void secondAuditDefinitions() throws Exception {
        String s = "java.lang.String";
        check("Context.checkPermission", "android.app.ContextImpl checkPermission(" + s + ",int,int) @package_resolve 27-");
        check("Context.checkPermission/token", "android.app.ContextImpl checkPermission(" + s + ",int,int,android.os.IBinder) " +
                "@package_resolve 27-30");
        check("ActivityManager.checkComponentPermission", "android.app.ActivityManager checkComponentPermission(" + s +
                ",int,int,boolean) @package_resolve 27- optional");
        // The binder proxies under the lookups of user ids and sync adapters, which apps call by reflection
        String pm = "android.content.pm.IPackageManager$Stub$Proxy ";
        check("IPackageManager.getPackagesForUid", pm + "getPackagesForUid(int) @package_resolve 27- optional");
        check("IPackageManager.getNameForUid", pm + "getNameForUid(int) @package_resolve 27- optional");
        check("IPackageManager.checkUidSignatures", pm + "checkUidSignatures(int,int) @package_resolve 27- optional");
        check("IContentService.getSyncAdapterTypes", "android.content.IContentService$Stub$Proxy getSyncAdapterTypes() " +
                "@package_resolve 27- optional");
        check("PackageManager.getPackageInfo/analytics", "android.content.pm.PackageManager getPackageInfo(" + s +
                ",int) @analytics_metadata 27-");
        check("PackageManager.getPackageInfo/flags/analytics", "android.content.pm.PackageManager getPackageInfo(" + s +
                ",android.content.pm.PackageManager$PackageInfoFlags) @analytics_metadata 33-");
        check("NetworkCapabilities.getTransportTypes", "android.net.NetworkCapabilities getTransportTypes() @vpn 27- optional");
        for (String name : new String[]{"UserManager.isUserRunning", "UserManager.isUserRunningOrStopping",
                "UserManager.getUserRestrictions", "UserManager.isUserUnlocked", "UserManager.isQuietModeEnabled",
                "ContentResolver.getSyncAdapterTypes", "Context.checkPermission", "IPackageManager.getPackagesForUid",
                "IPackageManager.getNameForUid", "IPackageManager.checkUidSignatures", "IContentService.getSyncAdapterTypes"})
            assertTrue(name + " is not excluded from android", Arrays.asList(hook(name).optString("excludePackages", "")
                    .split(",")).contains("android"));
        assertEquals("Get.Applications", hook("AccountManager.getAuthenticatorTypes/apps").getString("group"));
        // The account types are left out only with the apps they tell are installed, not by Read.Account alone
        for (JSONObject hook : hooks)
            if ("getAuthenticatorTypes".equals(hook.optString("methodName")))
                assertEquals(hook.getString("name"), "Get.Applications", hook.getString("group"));
    }
}
