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
            "android.content.pm.LauncherApps$CallbackMessageHandler",
            "android.app.PendingIntent$FinishedDispatcher", "android.content.IntentSender$FinishedDispatcher",
            "android.app.ActivityThread", "android.app.LoadedApk$ReceiverDispatcher", "android.hardware.SystemSensorManager",
            "android.hardware.input.InputDeviceSensorManager$InputSensorManager",
            "android.app.SharedPreferencesImpl", "android.security.KeyStore",
            "android.system.keystore2.IKeystoreSecurityLevel$Stub$Proxy", "android.security.keymaster.KeyCharacteristics"));

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

    // The lookups of Typeface by the name of a family, on every version which has them (font_config)
    @Test
    public void typefaceLookupsByName() throws Exception {
        String s = "java.lang.String", t = "android.graphics.Typeface";
        check("Typeface.create/font_config", t + " create(" + s + ",int) @font_config 27-");
        check("Typeface$Builder.setFallback", t + "$Builder setFallback(" + s + ") @font_config 27-");
        check("Typeface$CustomFallbackBuilder.setSystemFallback", t + "$CustomFallbackBuilder setSystemFallback(" + s +
                ") @font_config 29-");
        check("Typeface.hasFontFamily", t + " hasFontFamily(" + s + ") @font_config 31- optional");
    }

    // The times of the folders also by the descriptor the app opened, by the public method only, since Android calls
    // Libcore.os.fstat for every file it opens (see storage_times)
    @Test
    public void storageTimesByDescriptor() throws Exception {
        check("Os.fstat", "android.system.Os fstat(java.io.FileDescriptor) @storage_times 27-");
        assertEquals("Use.Tracking", hook("Os.fstat").getString("group"));
        assertEquals("value.drm_id", hook("Os.fstat").getJSONArray("settings").getString(0));
        // The entries of a SecureDirectoryStream, by the descriptor of its folder and their name
        String name = "UnixNativeDispatcher.fstatat/storage_times";
        check(name, "sun.nio.fs.UnixNativeDispatcher fstatat(int,byte[],int,sun.nio.fs.UnixFileAttributes) @storage_times 27-");
        assertEquals("Use.Tracking", hook(name).getString("group"));
        assertEquals("value.drm_id", hook(name).getJSONArray("settings").getString(0));
        // The folder of a SecureDirectoryStream itself, by its descriptor alone
        name = "UnixNativeDispatcher.fstat/storage_times";
        check(name, "sun.nio.fs.UnixNativeDispatcher fstat(int,sun.nio.fs.UnixFileAttributes) @storage_times 27-");
        assertEquals("Use.Tracking", hook(name).getString("group"));
        assertEquals("value.drm_id", hook(name).getJSONArray("settings").getString(0));
    }

    // A hook of a library in the app which does not use the result must not require a return type: R8 makes methods
    // whose result the app does not use return void, which XResolver refuses, like OkHttp's Headers.Builder in LinkedIn.
    // Per hook, because a script shared by several methods, like webview_constructor, can read the result of one only.
    private static final Set<String> APP_HOOKS_WITH_RESULT = new HashSet<>(Arrays.asList(
            "ActivityRecognitionResult.extractResult", "ActivityTransitionResult.extractResult",
            "SleepSegmentEvent.extractEvents", "SleepClassifyEvent.extractEvents",
            "ActivityRecognitionResult.createFromParcel", "ActivityTransitionResult.createFromParcel",
            "ActivityRecognition.getClient", "ActivityRecognition.getClient/activity", "ActivityRecognitionApi.requestActivityUpdates",
            "DetectedActivityResponse.getActivityRecognitionResult",
            "ActivityIdentification.getService", "ActivityIdentification.getService/activity",
            "ActivityIdentificationService.createActivityIdentificationUpdates",
            "ActivityIdentificationService.createActivityConversionUpdates",
            "LocationJsonUtil.parseLocationFromJsonObject", "LocationResult.createFromParcel", "LocationResult.create",
            "ActivityIdentificationResponse.getDataFromIntent", "ActivityConversionResponse.getDataFromIntent",
            "ActivityIdentificationResponse.createFromParcel", "ActivityConversionResponse.createFromParcel",
            "AppSetIdInfo.getId", "AmplitudeClient.getOptOut", "AmplitudeClient.logEvent", "AmplitudeClient.logEvent/extra",
            "AmplitudeClient.logEvent/foreground", "AmplitudeClient.logEvent/api", "AmplitudeClient.logEvent/user",
            "AmplitudeClient.logEvent/groups",
            "Amplitude.getOptOut", "Amplitude.getOptOut/android", "AmplitudeClient.isOptedOut", "Branch.isTrackingDisabled",
            "Branch.isTrackingDisabled/context", "Singular.isAllTrackingStopped",
            "FirebaseAnalytics.getInstance", "GoogleAnalytics.getInstance/optout", "AdRegistration.getInstance",
            "FirebaseCrashlytics.getInstance", "AppsFlyerLib.getInstance", "SegmentAnalytics.build",
            "AppLovinSdk.getInstance", "AppLovinSdk.getInstance/key",
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
        // In the globals of the engine, which a script cannot change the libraries of
        Globals libraries = JsePlatform.standardGlobals();
        Globals globals = new XCore.LuaLocals(libraries, XCore.protect(libraries));
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
        // The engine runs the script of a built-in hook at its first call, unless it can define filter or shared, which it
        // calls when it installs the hook, see XCore.installMethod
        if (!new XCore.Script(source).definesFilterOrShared())
            assertTrue(name + " defines filter or shared, which the engine would not find",
                    globals.get("filter").isnil() && globals.get("shared").isnil());
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

    // No script removes an item of a list by its index with remove(...): for an int below 128 LuaJ scores
    // List.remove(int) and remove(Object) the same and calls the one reflection lists first, which can be
    // remove(Object), which removes nothing; subList(from, to):clear() has no overload. A removal by key, of a Bundle,
    // a Map or a Set, takes a string or an object, not an index, so a remove with "index" or a number is one of a list.
    @Test
    public void listsAreNotShortenedByRemove() throws Exception {
        Pattern byIndex = Pattern.compile(":remove\\(\\s*(index|\\d)");
        Set<String> found = new TreeSet<>();
        for (File file : ASSETS.listFiles((dir, name) -> name.endsWith(".lua")))
            if (byIndex.matcher(read(file).replaceAll("--[^\n]*", "")).find())
                found.add(file.getName());
        for (Map.Entry<String, JSONObject> entry : repository().entrySet())
            if (byIndex.matcher(entry.getValue().getString("luaScript").replaceAll("--[^\n]*", "")).find())
                found.add(entry.getKey());
        assertTrue("Scripts which remove from a list by index: " + found, found.isEmpty());
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
        // A shallow clone lacks the earlier texts, so nothing would be compared: skip instead of passing vacuously
        Assume.assumeFalse("Shallow clone, fetch the full history",
                "true".equals(git(root, "rev-parse", "--is-shallow-repository").trim()));
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
    // ReceiverDispatcher of each version (AOSP oreo-mr1 to android13-release, android14-release to main), the
    // property areas are faked for both ways of opening files, and the TZ of native code is hidden from Java beneath
    // Libcore.os and Libcore.rawOs, not in system server, which gets no TZ
    @Test
    public void timeZoneBroadcastAndPropertyAreas() throws Exception {
        check("Linux.getenv/timezone", "libcore.io.Linux getenv(java.lang.String) @timezone 27-");
        check("Linux.environ/timezone", "libcore.io.Linux environ() @timezone 27-");
        // Removing TZ keeps the fake one. No filter, though the script names it for getenv: the engine resolves the
        // first String of a hook of Os as a path of a file, and TZ is no path
        check("Os.unsetenv/timezone", "android.system.Os unsetenv(java.lang.String) @timezone 28-");
        for (String name : new String[]{"Linux.getenv/timezone", "Linux.environ/timezone", "Os.unsetenv/timezone"}) {
            assertEquals(name, "Read.Timezone", hook(name).getString("group"));
            assertEquals(name, "android", hook(name).getString("excludePackages"));
            assertEquals(name, 1, hook(name).optJSONArray("settings").length());
            assertEquals(name, "value.timezone", hook(name).optJSONArray("settings").getString(0));
        }
        String args = "android.content.Intent,int,java.lang.String,android.os.Bundle,boolean,boolean,";
        check("ActivityThread.handleReceiver/timezone",
                "android.app.ActivityThread handleReceiver(android.app.ActivityThread$ReceiverData) @receiver_skip 27-");
        check("ReceiverDispatcher.performReceive/timezone",
                "android.app.LoadedApk$ReceiverDispatcher performReceive(" + args + "int) @receiver_skip 27-33");
        check("ReceiverDispatcher.performReceive.sender/timezone", "android.app.LoadedApk$ReceiverDispatcher performReceive(" +
                args + "boolean,int,int,java.lang.String) @receiver_skip 34-");
        // The same for the broadcast of a change of the languages
        check("ActivityThread.handleReceiver/locale",
                "android.app.ActivityThread handleReceiver(android.app.ActivityThread$ReceiverData) @receiver_skip 27-");
        check("ReceiverDispatcher.performReceive/locale",
                "android.app.LoadedApk$ReceiverDispatcher performReceive(" + args + "int) @receiver_skip 27-33");
        check("ReceiverDispatcher.performReceive.sender/locale", "android.app.LoadedApk$ReceiverDispatcher performReceive(" +
                args + "boolean,int,int,java.lang.String) @receiver_skip 34-");
        for (String variant : new String[]{"timezone", "locale"}) {
            check("BlockGuardOs.open/" + variant, "libcore.io.BlockGuardOs open(java.lang.String,int,int) @" + variant + " 27-");
            check("UnixNativeDispatcher.open/" + variant,
                    "sun.nio.fs.UnixNativeDispatcher open(sun.nio.fs.UnixPath,int,int) @" + variant + " 27-");
        }
        // The serials, ICC IDs, IMEIs, operators and the model and build of systemproperties_get, in the group of their
        // getters
        for (String variant : new String[]{"serial", "operator", "device"}) {
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

    // The stat, size, access mode and link of faked files answer like for the file the app opened, also by the path of
    // the link, by the methods beneath the public ones (AOSP libcore oreo-mr1-release to main)
    @Test
    public void fakedFilesAnswerLikeTheFileOpened() throws Exception {
        String d = "java.io.FileDescriptor";
        check("Os.fstat/faked_files", "android.system.Os fstat(" + d + ") @faked_files 27-");
        check("FileInputStream.available/faked_files", "java.io.FileInputStream available() @faked_files 27-");
        check("RandomAccessFile.length/faked_files", "java.io.RandomAccessFile length() @faked_files 27-");
        check("FileDispatcherImpl.size/faked_files", "sun.nio.ch.FileDispatcherImpl size(" + d + ") @faked_files 27-");
        check("Os.readlink/faked_files", "android.system.Os readlink(java.lang.String) @faked_files 27-");
        check("UnixNativeDispatcher.readlink/faked_files",
                "sun.nio.fs.UnixNativeDispatcher readlink(sun.nio.fs.UnixPath) @faked_files 27-");
        check("BlockGuardOs.stat/faked_files", "libcore.io.BlockGuardOs stat(java.lang.String) @faked_files 27-");
        check("UnixFileSystem.getLength/faked_files", "java.io.UnixFileSystem getLength(java.io.File) @faked_files 27-");
        check("UnixFileSystem.getLastModifiedTime/faked_files",
                "java.io.UnixFileSystem getLastModifiedTime(java.io.File) @faked_files 27-");
        // The access mode, read-only like the file opened, by the public Os.fcntlInt of Android 11
        check("Os.fcntlInt/faked_files", "android.system.Os fcntlInt(" + d + ",int,int) @faked_files 30-");
        for (String name : new String[]{"Os.fstat", "FileInputStream.available", "RandomAccessFile.length", "FileDispatcherImpl.size",
                "Os.readlink", "UnixNativeDispatcher.readlink", "BlockGuardOs.stat", "UnixFileSystem.getLength",
                "UnixFileSystem.getLastModifiedTime", "Os.fcntlInt"}) {
            assertEquals(name, hook("BlockGuardOs.open/boot_id").getString("group"), hook(name + "/faked_files").getString("group"));
            // The same for the mount tables Read.Debugging rewrites without the mounts of Magisk (see hardware_files)
            JSONObject model = hook(name + "/faked_files"), copy = hook(name + "/faked_files_debugging");
            assertNotNull(name + "/faked_files_debugging", copy);
            for (String key : new String[]{"className", "methodName", "parameterTypes", "returnType", "minSdk", "luaScript"})
                assertEquals(name + " " + key, model.get(key).toString(), copy.get(key).toString());
            assertFalse(name, copy.has("maxSdk") || copy.has("optional"));
            assertEquals(name, hook("BlockGuardOs.open/root").getString("group"), copy.getString("group"));
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
        check("Context.startService/identifiers", "android.app.ContextImpl startService(android.content.Intent) " +
                "@identifier_services 27-");
        check("Context.startForegroundService/identifiers", "android.app.ContextImpl startForegroundService(" +
                "android.content.Intent) @identifier_services 27-");
        check("Context.startServiceAsUser/identifiers", "android.app.ContextImpl startServiceAsUser(" + i +
                "android.os.UserHandle) @identifier_services 27-");
        check("Context.startForegroundServiceAsUser/identifiers", "android.app.ContextImpl startForegroundServiceAsUser(" + i +
                "android.os.UserHandle) @identifier_services 27-");
        check("Context.stopService/identifiers", "android.app.ContextImpl stopService(android.content.Intent) " +
                "@identifier_services 27-");
        check("Context.stopServiceAsUser/identifiers", "android.app.ContextImpl stopServiceAsUser(" + i +
                "android.os.UserHandle) @identifier_services 27-");
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
        String client = "android.content.ContentProviderClient call(java.lang.String,java.lang.String,";
        check("ContentProviderClient.call/appset", client + "android.os.Bundle) @identifier_services 27-28");
        check("ContentProviderClient.call.authority/appset", client + "java.lang.String,android.os.Bundle) @identifier_services 29-");
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
        // The GSF id is hidden from Google's own apps too, only Play services and the services framework own it
        for (String name : new String[]{"ContentResolver.query26/gsf_id", "ContentProviderClient.query26/gsf_id"})
            for (String pkg : new String[]{"com.google.android.youtube", "com.google.android.apps.maps", "com.android.chrome",
                    "com.android.vending", "com.google.android.gms", "com.google.android.gsf", "com.google.android.gms.x"}) {
                boolean excluded = false;
                for (String regex : hook(name).getString("excludePackages").split(","))
                    excluded |= pkg.matches(regex);
                assertEquals(name + " " + pkg, pkg.equals("com.google.android.gms") || pkg.equals("com.google.android.gsf"),
                        excluded);
            }
    }

    // The graphics chip by each version of OpenGL ES: GLES11 inherits glGetString of GLES10, GLES30 to GLES32 the one of
    // GLES20, GLImpl serves javax.microedition.khronos (see gles_getstring)
    @Test
    public void graphicsChipByEveryVersionOfOpenGl() throws Exception {
        check("GLES10.glGetString", "android.opengl.GLES10 glGetString(int) @gles_getstring 27-");
        check("GLES20.glGetString", "android.opengl.GLES20 glGetString(int) @gles_getstring 27-");
    }

    // The extensions by index and their number GL_NUM_EXTENSIONS by each getter the EGL loader answers it for, those of
    // GLES20 also for GLES30 to GLES32, which inherit them (see gles_getstring)
    @Test
    public void extensionsByIndexAndTheirNumber() throws Exception {
        check("GLES30.glGetStringi", "android.opengl.GLES30 glGetStringi(int,int) @gles_getstring 27-");
        check("GLES20.glGetIntegerv", "android.opengl.GLES20 glGetIntegerv(int,int[],int) @gles_getstring 27-");
        check("GLES20.glGetIntegerv/buffer", "android.opengl.GLES20 glGetIntegerv(int,java.nio.IntBuffer) @gles_getstring 27-");
        check("GLES20.glGetFloatv", "android.opengl.GLES20 glGetFloatv(int,float[],int) @gles_getstring 27-");
        check("GLES20.glGetFloatv/buffer", "android.opengl.GLES20 glGetFloatv(int,java.nio.FloatBuffer) @gles_getstring 27-");
        check("GLES30.glGetInteger64v", "android.opengl.GLES30 glGetInteger64v(int,long[],int) @gles_getstring 27-");
        check("GLES30.glGetInteger64v/buffer", "android.opengl.GLES30 glGetInteger64v(int,java.nio.LongBuffer) @gles_getstring 27-");
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

    // AppLovin and LevelPlay get no consent and do not sell, OneSignal requires consent and gets none, before each way
    // to start and by their setters (see analytics_getinstance and analytics_start), also those with the switch first;
    // the configuration of OneSignal keeps it, and its GET requests and log uploads fail, also in version 4; Bugsnag
    // enables no release stage in the client each way to start makes
    @Test
    public void analyticsOfAdSdksOneSignalAndBugsnag() throws Exception {
        String sdk = "com.applovin.sdk.AppLovinSdk getInstance(";
        check("AppLovinSdk.getInstance", sdk + "android.content.Context) @analytics_getinstance 27- optional");
        check("AppLovinSdk.getInstance/key", sdk + "java.lang.String,com.applovin.sdk.AppLovinSdkSettings,android.content.Context) " +
                "@analytics_getinstance 27- optional");
        String privacy = "com.applovin.sdk.AppLovinPrivacySettings ";
        check("AppLovinPrivacySettings.setHasUserConsent", privacy + "setHasUserConsent(boolean,android.content.Context) " +
                "@generic_false_argument 27- optional");
        check("AppLovinPrivacySettings.setDoNotSell", privacy + "setDoNotSell(boolean,android.content.Context) " +
                "@generic_true_argument 27- optional");
        String level = "com.unity3d.mediation.LevelPlay ";
        String iron = "com.ironsource.mediationsdk.IronSource ";
        String units = "com.ironsource.mediationsdk.IronSource$AD_UNIT[]";
        check("LevelPlay.init", level + "init(android.content.Context,com.unity3d.mediation.LevelPlayInitRequest," +
                "com.unity3d.mediation.LevelPlayInitListener) @analytics_start 27- optional");
        check("IronSource.init", iron + "init(android.content.Context,java.lang.String," + units + ") @analytics_start 27- optional");
        check("IronSource.init/listener", iron + "init(android.content.Context,java.lang.String," +
                "com.ironsource.mediationsdk.sdk.InitializationListener," + units + ") @analytics_start 27- optional");
        check("IronSource.initISDemandOnly", iron + "initISDemandOnly(android.content.Context,java.lang.String," + units + ") " +
                "@analytics_start 27- optional");
        for (String c : new String[]{level, iron}) {
            String name = c.substring(c.lastIndexOf('.') + 1).trim();
            check(name + ".setConsent", c + "setConsent(boolean) @generic_false_argument 27- optional");
            check(name + ".setMetaData", c + "setMetaData(java.lang.String,java.lang.String) @analytics_start 27- optional");
            check(name + ".setMetaData/list", c + "setMetaData(java.lang.String,java.util.List) @analytics_start 27- optional");
        }
        String one = "com.onesignal.OneSignal ";
        String coroutine = "kotlin.coroutines.Continuation";
        check("OneSignal.initWithContext", one + "initWithContext(android.content.Context,java.lang.String) @analytics_start 27- optional");
        check("OneSignal.initWithContext/coroutine", one + "initWithContext(android.content.Context," + coroutine + ") " +
                "@analytics_start 27- optional");
        check("OneSignal.initWithContextSuspend", one + "initWithContextSuspend(android.content.Context,java.lang.String," +
                coroutine + ") @analytics_start 27- optional");
        check("OneSignal.initWithContext/v4", one + "initWithContext(android.content.Context) @analytics_start 27- optional");
        check("OneSignal.setConsentRequired", one + "setConsentRequired(boolean) @generic_true_argument 27- optional");
        check("OneSignal.setConsentGiven", one + "setConsentGiven(boolean) @generic_false_argument 27- optional");
        check("OneSignal.setConsentRequiredSuspend", one + "setConsentRequiredSuspend(boolean," + coroutine + ") " +
                "@generic_true_argument 27- optional");
        check("OneSignal.setConsentGivenSuspend", one + "setConsentGivenSuspend(boolean," + coroutine + ") " +
                "@generic_false_argument 27- optional");
        check("OneSignal.setRequiresUserPrivacyConsent", one + "setRequiresUserPrivacyConsent(boolean) " +
                "@generic_true_argument 27- optional");
        check("OneSignal.provideUserConsent", one + "provideUserConsent(boolean) @generic_false_argument 27- optional");
        String config = "com.onesignal.core.internal.config.ConfigModel ";
        check("ConfigModel.setConsentRequired", config + "setConsentRequired(java.lang.Boolean) @generic_true_argument 27- optional");
        check("ConfigModel.setConsentGiven", config + "setConsentGiven(java.lang.Boolean) @generic_false_argument 27- optional");
        check("OneSignalHttpClient.get", "com.onesignal.core.internal.http.impl.HttpClient get(java.lang.String," +
                "com.onesignal.core.internal.http.impl.OptionalHeaders," + coroutine + ") @analytics_start 27- optional");
        check("OneSignalLogHttpSender.send", "com.onesignal.debug.internal.logging.logger.android.OneSignalLogHttpSender " +
                "send(com.onesignal.logger.LogHttpRequest," + coroutine + ") @analytics_start 27- optional");
        check("OSRemoteParamController.savePrivacyConsentRequired", "com.onesignal.OSRemoteParamController " +
                "savePrivacyConsentRequired(boolean) @generic_true_argument 27- optional");
        for (String get : new String[]{"get", "getSync"})
            check("OneSignalRestClient." + get, "com.onesignal.OneSignalRestClient " + get + "(java.lang.String," +
                    "com.onesignal.OneSignalRestClient$ResponseHandler,java.lang.String) @generic_block 27- optional");
        // A constructor, which check does not describe
        JSONObject client = hook("BugsnagClient.constructor");
        assertFalse(client.has("methodName"));
        assertEquals("com.bugsnag.android.Client", client.getString("className"));
        assertEquals("[\"android.content.Context\",\"com.bugsnag.android.Configuration\"]",
                client.getJSONArray("parameterTypes").toString());
        assertEquals("@analytics_start", client.getString("luaScript"));
        assertTrue(client.getBoolean("optional"));
        for (JSONObject hook : hooks)
            if (hook.getString("luaScript").equals("@analytics_start") ||
                    hook.getString("name").matches("(AppLovin|ConfigModel|OSRemote|OneSignalRest).*"))
                assertEquals(hook.getString("name"), "Use.Analytics", hook.getString("group"));
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

    // The input devices from Android 8.1: InputDevice.isExternal, by which the script tells external ones apart, is hidden
    // before Android 10, but unrestricted on Android 8.1 and on the light greylist of Android 9
    @Test
    public void inputDevicesOnAllVersions() throws Exception {
        String m = "android.hardware.input.InputManager ";
        String d = "android.view.InputDevice ";
        check("InputManager.getInputDeviceIds", m + "getInputDeviceIds() @input_devices 27-");
        check("InputManager.getInputDevice", m + "getInputDevice(int) @input_devices 27-");
        check("InputDevice.getDeviceIds", d + "getDeviceIds() @input_devices 27-");
        check("InputDevice.getDevice", d + "getDevice(int) @input_devices 27-");
        for (String method : new String[]{"getName", "getVendorId", "getProductId", "getDescriptor", "toString"})
            check("InputDevice." + method, d + method + "() @input_devices 27-");
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

    // The listeners of input devices, which hear nothing of external ones, and the parcel of a device, which has the
    // fields of the getters; from Android 8.1 like the other hooks of input devices (see input_devices)
    @Test
    public void inputDeviceListenersAndParcels() throws Exception {
        check("InputManager.registerInputDeviceListener", "android.hardware.input.InputManager registerInputDeviceListener(" +
                "android.hardware.input.InputManager$InputDeviceListener,android.os.Handler) @input_devices 27-");
        check("InputDevice.writeToParcel", "android.view.InputDevice writeToParcel(android.os.Parcel,int) @input_devices 27-");
        for (String name : new String[]{"InputManager.registerInputDeviceListener", "InputDevice.writeToParcel"})
            assertEquals(name, "Read.Features", hook(name).getString("group"));
    }

    // The managers and adapters which Android gives only with their features, on every version (see features); the system
    // server is not an app, so it keeps them
    @Test
    public void managersOfHiddenFeatures() throws Exception {
        check("Context.getSystemService/features", "android.app.ContextImpl getSystemService(java.lang.String) @features 27-");
        check("BluetoothAdapter.getDefaultAdapter", "android.bluetooth.BluetoothAdapter getDefaultAdapter() @features 27-");
        check("BluetoothManager.getAdapter", "android.bluetooth.BluetoothManager getAdapter() @features 27-");
        check("NfcAdapter.getDefaultAdapter", "android.nfc.NfcAdapter getDefaultAdapter(android.content.Context) @features 27-");
        check("NfcManager.getDefaultAdapter", "android.nfc.NfcManager getDefaultAdapter() @features 27-");
        check("WifiManager.isP2pSupported", "android.net.wifi.WifiManager isP2pSupported() @features 27-");
        check("WifiManager.isDeviceToApRttSupported", "android.net.wifi.WifiManager isDeviceToApRttSupported() @features 27-");
        check("WifiManager.isWifiDisplayR2Supported", "android.net.wifi.WifiManager isWifiDisplayR2Supported() @features 31-");
        check("WifiManager.isD2dSupportedWhenInfraStaDisabled",
                "android.net.wifi.WifiManager isD2dSupportedWhenInfraStaDisabled() @features 35-");
        check("ConsumerIrManager.hasIrEmitter", "android.hardware.ConsumerIrManager hasIrEmitter() @features 27-");
        check("ConsumerIrManager.getCarrierFrequencies", "android.hardware.ConsumerIrManager getCarrierFrequencies() @features 27-");
        check("ConsumerIrManager.transmit", "android.hardware.ConsumerIrManager transmit(int,int[]) @features 27-33");
        // Their broadcasts, marked when read, are not given by registerReceiver (ContextImpl of AOSP oreo-mr1 to main) and
        // reach no receiver, with the signature of ReceiverDispatcher of each version
        check("Intent.createFromParcel/features", "android.content.Intent CREATOR:createFromParcel(android.os.Parcel) @features 27-");
        check("Context.registerReceiverInternal/features", "android.app.ContextImpl registerReceiverInternal(" +
                "android.content.BroadcastReceiver,int,android.content.IntentFilter,java.lang.String,android.os.Handler," +
                "android.content.Context,int) @features 27-");
        String args = "android.content.Intent,int,java.lang.String,android.os.Bundle,boolean,boolean,";
        check("ActivityThread.handleReceiver/features",
                "android.app.ActivityThread handleReceiver(android.app.ActivityThread$ReceiverData) @receiver_skip 27-");
        check("ReceiverDispatcher.performReceive/features",
                "android.app.LoadedApk$ReceiverDispatcher performReceive(" + args + "int) @receiver_skip 27-33");
        check("ReceiverDispatcher.performReceive.sender/features", "android.app.LoadedApk$ReceiverDispatcher performReceive(" +
                args + "boolean,int,int,java.lang.String) @receiver_skip 34-");
        for (String name : new String[]{"Context.getSystemService/features", "BluetoothAdapter.getDefaultAdapter",
                "BluetoothManager.getAdapter", "NfcAdapter.getDefaultAdapter", "NfcManager.getDefaultAdapter",
                "WifiManager.isP2pSupported", "WifiManager.isDeviceToApRttSupported", "WifiManager.isWifiDisplayR2Supported",
                "WifiManager.isD2dSupportedWhenInfraStaDisabled", "ConsumerIrManager.hasIrEmitter",
                "ConsumerIrManager.getCarrierFrequencies", "ConsumerIrManager.transmit", "Intent.createFromParcel/features",
                "Context.registerReceiverInternal/features"}) {
            assertEquals(name, "Read.Features", hook(name).getString("group"));
            assertEquals(name, "android", hook(name).getString("excludePackages"));
            assertEquals(name, "[\"features.keep\"]", hook(name).getJSONArray("settings").toString());
        }
        for (String name : new String[]{"ActivityThread.handleReceiver/features", "ReceiverDispatcher.performReceive/features",
                "ReceiverDispatcher.performReceive.sender/features"})
            assertEquals(name, "Read.Features", hook(name).getString("group"));
    }

    // The keys and the navigation of the configuration, which Android sets from the input devices: in the configurations
    // the app reads from parcels, in its resources, also those made before the hooks (once, by Resources.mSystem), and in
    // getDeviceConfigurationInfo; not in system server, whose configuration all apps get (see input_devices)
    @Test
    public void inputDevicesInTheConfiguration() throws Exception {
        String r = "android.content.res.ResourcesImpl ";
        String c = "android.content.res.Configuration,android.util.DisplayMetrics,android.content.res.CompatibilityInfo";
        check("Configuration.readFromParcel", "android.content.res.Configuration readFromParcel(android.os.Parcel) @input_devices 27-");
        check("ResourcesImpl.updateConfiguration/input_devices", r + "updateConfiguration(" + c + ") @input_devices 27-34 optional");
        check("ResourcesImpl.updateConfigurationImpl/input_devices", r + "updateConfigurationImpl(" + c +
                ",boolean) @input_devices 34- optional");
        check("Resources.mSystem/input_devices", "android.content.res.Resources #mSystem() @input_devices 27- optional");
        check("ActivityManager.getDeviceConfigurationInfo", "android.app.ActivityManager getDeviceConfigurationInfo() @input_devices 27-");
        for (String name : new String[]{"Configuration.readFromParcel", "ResourcesImpl.updateConfiguration/input_devices",
                "ResourcesImpl.updateConfigurationImpl/input_devices", "Resources.mSystem/input_devices",
                "ActivityManager.getDeviceConfigurationInfo"}) {
            assertEquals(name, "Read.Features", hook(name).getString("group"));
            assertEquals(name, "android", hook(name).getString("excludePackages"));
        }
    }

    // The configuration of the process, which every activity starts with a copy of, and whether any device has keys; not
    // in system server (see input_devices)
    @Test
    public void inputDevicesInCopiesAndKeys() throws Exception {
        check("Configuration.setTo/input_devices", "android.content.res.Configuration setTo(android.content.res.Configuration) " +
                "@input_devices 27-");
        assertFalse(hook("Configuration.setTo/input_devices").optBoolean("usage", true));
        check("KeyCharacterMap.deviceHasKey", "android.view.KeyCharacterMap deviceHasKey(int) @input_devices 27-");
        check("KeyCharacterMap.deviceHasKeys", "android.view.KeyCharacterMap deviceHasKeys(int[]) @input_devices 27-");
        check("InputDevice.hasKeys", "android.view.InputDevice hasKeys(int[]) @input_devices 27-");
        for (String name : new String[]{"Configuration.setTo/input_devices", "KeyCharacterMap.deviceHasKey",
                "KeyCharacterMap.deviceHasKeys", "InputDevice.hasKeys"}) {
            assertEquals(name, "Read.Features", hook(name).getString("group"));
            assertEquals(name, "android", hook(name).getString("excludePackages"));
        }
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
        // The results of Play services by the names of their extras, which R8 does not rename: geofence events
        // (Get.Location) and the activity, its transitions and sleep events (Determine.Activity)
        for (String variant : new String[]{"geofence", "activity"}) {
            check("Intent.createFromParcel/" + variant, "android.content.Intent CREATOR:createFromParcel(android.os.Parcel) " +
                    "@intent_results 27-");
            check("Intent.setExtrasClassLoader/" + variant, "android.content.Intent setExtrasClassLoader(java.lang.ClassLoader) " +
                    "@intent_results 27-");
            // The intents with them do not reach the receivers, with the signature of ReceiverDispatcher of each version
            String args = "android.content.Intent,int,java.lang.String,android.os.Bundle,boolean,boolean,";
            check("ActivityThread.handleReceiver/" + variant,
                    "android.app.ActivityThread handleReceiver(android.app.ActivityThread$ReceiverData) @receiver_skip 27-");
            check("ReceiverDispatcher.performReceive/" + variant,
                    "android.app.LoadedApk$ReceiverDispatcher performReceive(" + args + "int) @receiver_skip 27-33");
            check("ReceiverDispatcher.performReceive.sender/" + variant, "android.app.LoadedApk$ReceiverDispatcher " +
                    "performReceive(" + args + "boolean,int,int,java.lang.String) @receiver_skip 34-");
            for (String method : new String[]{"Intent.createFromParcel/", "Intent.setExtrasClassLoader/",
                    "ActivityThread.handleReceiver/", "ReceiverDispatcher.performReceive/",
                    "ReceiverDispatcher.performReceive.sender/"}) {
                assertEquals(variant.equals("geofence") ? "Get.Location" : "Determine.Activity",
                        hook(method + variant).getString("group"));
                assertEquals("android", hook(method + variant).getString("excludePackages"));
            }
        }
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

    // The sources of ExifInterface and MediaMetadataRetriever, by which photo_location tells the photos and videos of the
    // app's own APK apart: the constructors of a stream, with a type from Android 11 (and AndroidX), and each source of a
    // retriever, as a new one replaces the one before; the paths and uris of a retriever end in the descriptor or the uri
    @Test
    public void photoSourcesAreHooked() throws Exception {
        String[][] constructors = {{"ExifInterface.constructor", "android.media.ExifInterface", "1", "27", "false"},
                {"ExifInterface.constructor/type", "android.media.ExifInterface", "2", "30", "false"},
                {"ExifInterfaceX.constructor", "androidx.exifinterface.media.ExifInterface", "1", "27", "true"},
                {"ExifInterfaceX.constructor/type", "androidx.exifinterface.media.ExifInterface", "2", "27", "true"},
                {"ExifInterfaceS.constructor", "android.support.media.ExifInterface", "1", "27", "true"}};
        for (String[] c : constructors) {
            JSONObject hook = hook(c[0]);
            assertNotNull(c[0], hook);
            assertEquals(c[0], c[1], hook.getString("className"));
            assertFalse(c[0], hook.has("methodName"));
            JSONArray params = hook.getJSONArray("parameterTypes");
            assertEquals(c[0], Integer.parseInt(c[2]), params.length());
            assertEquals(c[0], "java.io.InputStream", params.getString(0));
            if (params.length() > 1)
                assertEquals(c[0], "int", params.getString(1));
            assertEquals(c[0], Integer.parseInt(c[3]), hook.getInt("minSdk"));
            assertEquals(c[0], Boolean.parseBoolean(c[4]), hook.optBoolean("optional", false));
            assertEquals(c[0], "@photo_location", hook.getString("luaScript"));
        }
        String m = "android.media.MediaMetadataRetriever setDataSource(";
        check("MediaMetadataRetriever.setDataSource/fd", m + "java.io.FileDescriptor,long,long) @photo_location 27-");
        check("MediaMetadataRetriever.setDataSource/uri", m + "java.lang.String,java.util.Map) @photo_location 27-");
        check("MediaMetadataRetriever.setDataSource/source", m + "android.media.MediaDataSource) @photo_location 27-");
    }

    // The files of /proc/net are denied for reading, not missing (see hardware_files): Read.Network asks access for
    // reading next to open, and Read.VPN, whose interfaces the files name, hooks open next to its calls of access
    @Test
    public void networkFilesDeniedByOpenAndAccess() throws Exception {
        String s = "java.lang.String", p = "sun.nio.fs.UnixPath";
        check("BlockGuardOs.access/network", "libcore.io.BlockGuardOs access(" + s + ",int) @hardware_files 27-");
        check("Os.access/network", "android.system.Os access(" + s + ",int) @hardware_files 27-");
        check("UnixFileSystem.checkAccess/network", "java.io.UnixFileSystem checkAccess(java.io.File,int) @hardware_files 27-");
        check("UnixNativeDispatcher.access/network", "sun.nio.fs.UnixNativeDispatcher access(" + p + ",int) @hardware_files 27-");
        check("BlockGuardOs.open/vpn_interfaces", "libcore.io.BlockGuardOs open(" + s + ",int,int) @hardware_files 27-");
        check("UnixNativeDispatcher.open/vpn_interfaces", "sun.nio.fs.UnixNativeDispatcher open(" + p +
                ",int,int) @hardware_files 27-");
        // The names of VPN interfaces in /proc/net and /proc/sys/net are left out of listings; File.isFile, by which
        // Read.Network leaves them to Read.VPN, is hooked too
        check("UnixFileSystem.list0/vpn_interfaces", "java.io.UnixFileSystem list0(java.io.File) @hardware_files 27-");
        check("UnixFileSystem.getBooleanAttributes0/vpn_interfaces", "java.io.UnixFileSystem getBooleanAttributes0(" + s +
                ") @hardware_files 27-");
        check("UnixFileSystemProvider.newDirectoryStream/vpn_interfaces", "sun.nio.fs.UnixFileSystemProvider newDirectoryStream(" +
                "java.nio.file.Path,java.nio.file.DirectoryStream$Filter) @hardware_files 27-");
        for (String name : new String[]{"BlockGuardOs.access", "Os.access", "UnixFileSystem.checkAccess", "UnixNativeDispatcher.access"})
            assertEquals(name, "Read.Network", hook(name + "/network").getString("group"));
        for (String name : new String[]{"BlockGuardOs.open", "UnixNativeDispatcher.open", "UnixFileSystem.list0",
                "UnixFileSystemProvider.newDirectoryStream"})
            assertEquals(name, "Read.VPN", hook(name + "/vpn_interfaces").getString("group"));
    }

    // The entries of getifaddrs, which NetworkInterface reads, for each group of its hooks (see interface_entries)
    @Test
    public void interfaceEntriesForEachGroup() throws Exception {
        for (String[] variant : new String[][]{{"network", "Read.Network"}, {"vpn", "Read.VPN"}, {"mac", "Read.Identifiers"}}) {
            check("ForwardingOs.getifaddrs/" + variant[0], "libcore.io.ForwardingOs getifaddrs() @interface_entries 27- optional");
            assertEquals(variant[1], hook("ForwardingOs.getifaddrs/" + variant[0]).getString("group"));
        }
        // The netlink sockets of the routing tables, by Os and by Linux beneath Libcore.os and Libcore.rawOs; not in
        // system_server, which opens them itself on Android 8.1 and 9
        for (String[] variant : new String[][]{{"network", "Read.Network"}, {"vpn", "Read.VPN"}})
            for (String[] layer : new String[][]{{"Os", "android.system.Os"}, {"Linux", "libcore.io.Linux"}}) {
                String name = layer[0] + ".socket/" + variant[0];
                check(name, layer[1] + " socket(int,int,int) @interface_entries 27-");
                assertEquals(variant[1], hook(name).getString("group"));
                assertEquals("android", hook(name).getString("excludePackages"));
            }
    }

    // The access points per channel, which the Wi-Fi module of Android 13 can have, and the link id of a Wi-Fi 7
    // access point, whose MLD address and links are hidden
    @Test
    public void wifiChannelsAndLinks() throws Exception {
        check("WifiManager.getChannelData", "android.net.wifi.WifiManager getChannelData(java.util.concurrent.Executor," +
                "java.util.function.Consumer) @wifimanager_getchanneldata 33- optional");
        check("WifiInfo.getApMloLinkId", "android.net.wifi.WifiInfo getApMloLinkId() @wifiinfo_ssid_bssid 33-");
        for (String name : new String[]{"WifiManager.getChannelData", "WifiInfo.getApMloLinkId"})
            assertEquals(name, "Read.Network", hook(name).getString("group"));
    }

    // Bluetooth LE scans answer like Android (see bluetooth_scan); a discovery is refused, since a started one needs broadcasts
    @Test
    public void bluetoothScans() throws Exception {
        String s = "android.bluetooth.le.BluetoothLeScanner startScan(";
        String a = "android.bluetooth.BluetoothAdapter ";
        String filters = "java.util.List,android.bluetooth.le.ScanSettings,";
        check("BluetoothLeScanner.startScan", s + "android.bluetooth.le.ScanCallback) @bluetooth_scan 27-");
        check("BluetoothLeScanner.startScan/filters", s + filters + "android.bluetooth.le.ScanCallback) @bluetooth_scan 27-");
        check("BluetoothLeScanner.startScan/pending", s + filters + "android.app.PendingIntent) @bluetooth_scan 27-");
        check("BluetoothAdapter.startLeScan", a + "startLeScan(android.bluetooth.BluetoothAdapter$LeScanCallback) @bluetooth_scan 27-");
        check("BluetoothAdapter.startLeScan/uuids", a + "startLeScan(java.util.UUID[]," +
                "android.bluetooth.BluetoothAdapter$LeScanCallback) @bluetooth_scan 27-");
        check("BluetoothAdapter.startDiscovery", a + "startDiscovery() @generic_block 27-");
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
        // The same accounts by the proxy, which apps for Android 8.1 reach by reflection, and the account of a subscription,
        // which the phone service of Android 10 and 11 gives without a permission
        String p = "com.android.internal.telecom.ITelecomService$Stub$Proxy ";
        check("ITelecomService.getCallCapablePhoneAccounts", p + "getCallCapablePhoneAccounts(boolean,java.lang.String) " +
                "@generic_empty 27-29 optional");
        assertEquals("java.util.List", hook("ITelecomService.getCallCapablePhoneAccounts").getString("returnType"));
        check("ITelecomService.getDefaultOutgoingPhoneAccount", p + "getDefaultOutgoingPhoneAccount(java.lang.String," +
                "java.lang.String) @generic_null_value 27-29 optional");
        check("ITelecomService.getUserSelectedOutgoingPhoneAccount", p + "getUserSelectedOutgoingPhoneAccount() " +
                "@generic_null_value 27-28 optional");
        check("TelephonyManager.getPhoneAccountHandleForSubscriptionId", "android.telephony.TelephonyManager " +
                "getPhoneAccountHandleForSubscriptionId(int) @generic_null_value 29-30 optional");
        // The subscriptions, which are hidden where the SIM accounts are: an empty list, of all subscriptions
        // Collections.emptyList on Android 12, a count of 0 and no single subscription
        String sm = "android.telephony.SubscriptionManager ";
        check("SubscriptionManager.getActiveSubscriptionInfoList", sm + "getActiveSubscriptionInfoList() @generic_empty 28-32");
        check("SubscriptionManager.getCompleteActiveSubscriptionInfoList", sm + "getCompleteActiveSubscriptionInfoList() " +
                "@generic_empty 30-32");
        check("SubscriptionManager.getOpportunisticSubscriptions", sm + "getOpportunisticSubscriptions() @generic_empty 29-32");
        check("SubscriptionManager.getAllSubscriptionInfoList", sm + "getAllSubscriptionInfoList() @generic_empty 27-30 optional");
        check("SubscriptionManager.getAllSubscriptionInfoList.31", sm + "getAllSubscriptionInfoList() @generic_empty_list 31-32 optional");
        check("SubscriptionManager.getAllSubscriptionInfoCount", sm + "getAllSubscriptionInfoCount() @generic_zero_value 27-32 optional");
        check("SubscriptionManager.getActiveSubscriptionInfoCount", sm + "getActiveSubscriptionInfoCount() @generic_zero_value 27-32");
        check("SubscriptionManager.getActiveSubscriptionInfo", sm + "getActiveSubscriptionInfo(int) @generic_null_value 27-32");
        check("SubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex", sm +
                "getActiveSubscriptionInfoForSimSlotIndex(int) @generic_null_value 27-32");
        check("SubscriptionManager.getSubscriptionsInGroup", sm + "getSubscriptionsInGroup(android.os.ParcelUuid) @generic_empty 29-32");
        // The methods for which the service throws then
        check("SubscriptionManager.isActiveSubscriptionId", sm + "isActiveSubscriptionId(int) @subscription_denied 29-32");
        check("SubscriptionManager.isActiveSubId", sm + "isActiveSubId(int) @subscription_denied 29-32 optional");
        check("SubscriptionManager.getAvailableSubscriptionInfoList", sm + "getAvailableSubscriptionInfoList() " +
                "@subscription_denied 27-30 optional");
        // The same by the proxy of the subscription service, which apps targeting Android 8.1 reach by reflection, with
        // the answers of the service, null for all subscriptions
        String isub = "com.android.internal.telephony.ISub$Stub$Proxy ";
        String[][] proxies = {
                {"getActiveSubscriptionInfoList", "(java.lang.String) @generic_empty 28-29", "java.util.List"},
                {"getAllSubInfoList", "(java.lang.String) @generic_null_value 27-29", "java.util.List"},
                {"getAllSubInfoCount", "(java.lang.String) @generic_zero_value 27-29", "int"},
                {"getActiveSubInfoCount", "(java.lang.String) @generic_zero_value 27-29", "int"},
                {"getActiveSubscriptionInfo", "(int,java.lang.String) @generic_null_value 27-29", "android.telephony.SubscriptionInfo"},
                {"getActiveSubscriptionInfoForSimSlotIndex", "(int,java.lang.String) @generic_null_value 27-29",
                        "android.telephony.SubscriptionInfo"},
                {"getActiveSubscriptionInfoForIccId", "(java.lang.String,java.lang.String) @generic_null_value 27-29",
                        "android.telephony.SubscriptionInfo"},
                {"getSubscriptionProperty", "(int,java.lang.String,java.lang.String) @generic_null_value 27-29", "java.lang.String"},
                {"getAvailableSubscriptionInfoList", "(java.lang.String) @subscription_denied 27-29", "java.util.List"}};
        List<String> names = new ArrayList<>();
        for (String[] proxy : proxies) {
            String name = "ISub." + proxy[0];
            check(name, isub + proxy[0] + proxy[1] + " optional");
            assertEquals(name, proxy[2], hook(name).getString("returnType"));
            names.add(name);
        }
        names.addAll(Arrays.asList("TelecomManager.getCallCapablePhoneAccounts", "TelecomManager.getCallCapablePhoneAccounts/disabled",
                "ITelecomService.getPhoneAccountsForPackage", "TelecomManager.getDefaultOutgoingPhoneAccount",
                "TelecomManager.getUserSelectedOutgoingPhoneAccount", "TelecomManager.getSimCallManager",
                "TelecomManager.getSimCallManager/user", "TelecomManager.getSimCallManagerForSubscription",
                "TelephonyManager.getPhoneAccountHandle",
                "ITelecomService.getCallCapablePhoneAccounts", "ITelecomService.getDefaultOutgoingPhoneAccount",
                "ITelecomService.getUserSelectedOutgoingPhoneAccount", "TelephonyManager.getPhoneAccountHandleForSubscriptionId",
                "SubscriptionManager.getActiveSubscriptionInfoList",
                "SubscriptionManager.getCompleteActiveSubscriptionInfoList", "SubscriptionManager.getOpportunisticSubscriptions",
                "SubscriptionManager.getAllSubscriptionInfoList", "SubscriptionManager.getAllSubscriptionInfoList.31",
                "SubscriptionManager.getAllSubscriptionInfoCount", "SubscriptionManager.getActiveSubscriptionInfoCount",
                "SubscriptionManager.getActiveSubscriptionInfo", "SubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex",
                "SubscriptionManager.getSubscriptionsInGroup", "SubscriptionManager.isActiveSubscriptionId",
                "SubscriptionManager.isActiveSubId", "SubscriptionManager.getAvailableSubscriptionInfoList"));
        // The phone process, Settings and the system UI keep the SIMs, which they keep for the whole device; without
        // them the lock screen would not ask for the PIN of a locked SIM
        String kept = "android,com\\.android\\.phone,com\\.android\\.settings,com\\.android\\.systemui";
        for (String name : names) {
            assertEquals(name, "Read.Telephony", hook(name).getString("group"));
            assertEquals(name, kept, hook(name).getString("excludePackages"));
        }
        // The service state and the network types of the same failed check, null and unknown up to Android 12, and the
        // network type only from Android 10, since Android 8.1 and 9 give it without the check; the processes which keep
        // the SIMs keep them too, so they show the network of the SIMs they list
        String phone = "android.telephony.TelephonyManager ";
        check("TelephonyManager.getServiceState", phone + "getServiceState() @generic_null_value 27-32");
        check("TelephonyManager.getDataNetworkType", phone + "getDataNetworkType() @generic_zero_value 27-32");
        check("TelephonyManager.getVoiceNetworkType", phone + "getVoiceNetworkType() @generic_zero_value 27-32");
        check("TelephonyManager.getNetworkType", phone + "getNetworkType() @telephony_network_type 29-32");
        for (String name : new String[]{"TelephonyManager.getServiceState", "TelephonyManager.getDataNetworkType",
                "TelephonyManager.getVoiceNetworkType", "TelephonyManager.getNetworkType"}) {
            assertEquals(name, "Read.Telephony", hook(name).getString("group"));
            assertEquals(name, kept, hook(name).getString("excludePackages"));
        }
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
        // And the network name of a group it owns, which ends in the name, and on Android 8.1 and 9, where the device
        // has its real address, the broadcast of the own device
        check("WifiP2pGroup.createFromParcel/name", "android.net.wifi.p2p.WifiP2pGroup CREATOR:createFromParcel(android.os.Parcel) " +
                "@wifip2pdevice_name 27-");
        check("Intent.createFromParcel/device_name", "android.content.Intent CREATOR:createFromParcel(android.os.Parcel) " +
                "@wifip2pdevice_name 27-28");
        for (String name : new String[]{"WifiP2pGroup.createFromParcel/name", "Intent.createFromParcel/device_name"})
            assertEquals(name, "Use.Tracking", hook(name).getString("group"));
        assertEquals("android", hook("Intent.createFromParcel/device_name").getString("excludePackages"));
        // And the network name an app chose for its group, which stays
        check("WifiP2pConfig$Builder.setNetworkName", "android.net.wifi.p2p.WifiP2pConfig$Builder setNetworkName(java.lang.String) " +
                "@wifip2pdevice_name 29-");
        // Its address, which Android 10 and later make anonymous themselves
        check("Intent.createFromParcel/identifiers", "android.content.Intent CREATOR:createFromParcel(android.os.Parcel) " +
                "@wifip2p_own_address 27-28");
        check("WifiP2pGroup.createFromParcel", "android.net.wifi.p2p.WifiP2pGroup CREATOR:createFromParcel(android.os.Parcel) " +
                "@wifip2p_own_address 27-28");
        // The network access identifier, which Android 9 and before give to apps with the phone state permission, also
        // by the hidden methods
        check("TelephonyManager.getNai", "android.telephony.TelephonyManager getNai() @generic_null_value 27-");
        check("TelephonyManager.getNai/slot", "android.telephony.TelephonyManager getNai(int) @generic_null_value 27- optional");
        // The ESN of a CDMA phone by the hidden methods, which privileged apps call by reflection
        check("TelephonyManager.getEsn", "android.telephony.TelephonyManager getEsn() @value_device_esn 27- optional");
        check("TelephonyManager.getEsn/subid", "android.telephony.TelephonyManager getEsn(int) @value_device_esn 27- optional");
        // The EID of the eSIM chip and the ICCIDs of the cards and their ports, which apps with carrier privileges get
        check("EuiccManager.getEid", "android.telephony.euicc.EuiccManager getEid() @generic_null_value 28-");
        check("UiccCardInfo.getEid", "android.telephony.UiccCardInfo getEid() @generic_null_value 29-");
        check("UiccCardInfo.getIccId", "android.telephony.UiccCardInfo getIccId() @generic_null_value 29-32");
        check("UiccPortInfo.getIccId", "android.telephony.UiccPortInfo getIccId() @uiccportinfo_iccid 33-");
        // The card id of a slot, its EID or ICCID, which privileged apps get
        check("UiccSlotInfo.getCardId", "android.telephony.UiccSlotInfo getCardId() @generic_null_value 28- optional");
        // The software version of the IMEI, a common one with a fake IMEI, else none like a phone without an IMEI
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
        // The ids of AdServices, which Android 12 has by the shared library android.ext.adservices of ExtServices, in the
        // results of the service, so the ids an app makes itself keep their values
        String result = "CREATOR:createFromParcel(android.os.Parcel) @adservices_id 31- optional";
        check("GetAdIdResult.createFromParcel", "android.adservices.adid.GetAdIdResult " + result);
        check("GetAppSetIdResult.createFromParcel", "android.adservices.appsetid.GetAppSetIdResult " + result);
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
        // The callbacks of package changes register, and the handler of each one posts only the changes of the app
        // itself, with the signatures of AOSP oreo-mr1 to main
        assertFalse(hooks.stream().anyMatch(h -> h.optString("name").startsWith("LauncherApps.registerCallback")));
        String handler = "android.content.pm.LauncherApps$CallbackMessageHandler ";
        String posted = "LauncherApps.CallbackMessageHandler.";
        String user = "android.os.UserHandle";
        String packages = "java.lang.String[]," + user;
        check(posted + "postOnPackageAdded", handler + "postOnPackageAdded(java.lang.String," + user + ") @launcher_callbacks 27-");
        check(posted + "postOnPackageRemoved", handler + "postOnPackageRemoved(java.lang.String," + user + ") @launcher_callbacks 27-");
        check(posted + "postOnPackageChanged", handler + "postOnPackageChanged(java.lang.String," + user + ") @launcher_callbacks 27-");
        check(posted + "postOnPackagesAvailable", handler + "postOnPackagesAvailable(" + packages + ",boolean) @launcher_callbacks 27-");
        check(posted + "postOnPackagesUnavailable", handler + "postOnPackagesUnavailable(" + packages + ",boolean) @launcher_callbacks 27-");
        check(posted + "postOnPackagesSuspended", handler + "postOnPackagesSuspended(java.lang.String[],android.os.Bundle," +
                user + ") @launcher_callbacks 28-");
        check(posted + "postOnPackagesUnsuspended", handler + "postOnPackagesUnsuspended(" + packages + ") @launcher_callbacks 27-");
        check(posted + "postOnShortcutChanged", handler + "postOnShortcutChanged(java.lang.String," + user +
                ",java.util.List) @launcher_callbacks 27-");
        check(posted + "postOnPackageLoadingProgressChanged", handler + "postOnPackageLoadingProgressChanged(" + user +
                ",java.lang.String,float) @launcher_callbacks 31-");
        check(posted + "postOnUserConfigChanged", handler + "postOnUserConfigChanged(android.content.pm.LauncherUserInfo) " +
                "@launcher_callbacks 35- optional");
        // Android 16 QPR2 and 17 log an added package before posting it, so its listener drops another package first
        check("LauncherApps.AppsChangedListener.onPackageAdded", "android.content.pm.LauncherApps$1 onPackageAdded(" + user +
                ",java.lang.String) @launcher_callbacks 36- optional");
        // Usage access tells about other apps by their package
        check("UsageStatsManager.isAppInactive", "android.app.usage.UsageStatsManager isAppInactive(java.lang.String) @package_probe 27-");
        check("StorageStatsManager.queryStatsForPackage", "android.app.usage.StorageStatsManager queryStatsForPackage(" +
                "java.util.UUID,java.lang.String,android.os.UserHandle) @package_probe 27-");
        // The storage of other uids, packages and the user needs usage access, which reads as not granted
        String ss = "android.app.usage.StorageStatsManager ";
        check("StorageStatsManager.queryStatsForUid", ss + "queryStatsForUid(java.util.UUID,int) @storage_stats_uid 27-");
        check("StorageStatsManager.queryStatsForPackage/usage", ss + "queryStatsForPackage(" +
                "java.util.UUID,java.lang.String,android.os.UserHandle) @storage_stats_uid 27-");
        check("StorageStatsManager.queryStatsForUser", ss + "queryStatsForUser(java.util.UUID,android.os.UserHandle) " +
                "@storage_stats_uid 27-");
        check("StorageStatsManager.queryExternalStatsForUser", ss + "queryExternalStatsForUser(java.util.UUID," +
                "android.os.UserHandle) @storage_stats_uid 27-");
        // So does the data usage by uid, by both ways to iterate it, which must agree
        check("NetworkStats.getNextBucket", "android.app.usage.NetworkStats getNextBucket(android.app.usage.NetworkStats$Bucket) " +
                "@network_stats_filter 27-");
        check("NetworkStats.hasNextBucket", "android.app.usage.NetworkStats hasNextBucket() @network_stats_filter 27-");
        // The device summary, the total of the user and the history of other uids answer like without usage access
        String nsm = "android.app.usage.NetworkStatsManager ";
        String query = "int,java.lang.String,long,long";
        check("NetworkStatsManager.querySummaryForDevice", nsm + "querySummaryForDevice(" + query + ") @network_stats_filter 27-");
        check("NetworkStatsManager.querySummaryForUser", nsm + "querySummaryForUser(" + query + ") @network_stats_filter 27-");
        check("NetworkStats.getSummaryAggregate", "android.app.usage.NetworkStats getSummaryAggregate() " +
                "@network_stats_filter 27- optional");
        check("NetworkStatsManager.queryDetailsForUid", nsm + "queryDetailsForUid(" + query + ",int) @network_stats_filter 27-");
        check("NetworkStatsManager.queryDetailsForUidTag", nsm + "queryDetailsForUidTag(" + query + ",int,int) " +
                "@network_stats_filter 27-");
        check("NetworkStatsManager.queryDetailsForUidTagState", nsm + "queryDetailsForUidTagState(" + query + ",int,int,int) " +
                "@network_stats_filter 28-");
        // The exact free space, Bluetooth input devices, battery saver, NSD by a request, sleep of Play services
        check("StorageManager.getAllocatableBytes", "android.os.storage.StorageManager getAllocatableBytes(java.util.UUID) @resources 27-");
        check("BluetoothHidDevice.getConnectedDevices", "android.bluetooth.BluetoothHidDevice getConnectedDevices() @generic_empty 28-");
        check("BluetoothHidDevice.getDevicesMatchingConnectionStates", "android.bluetooth.BluetoothHidDevice " +
                "getDevicesMatchingConnectionStates(int[]) @generic_empty 28-");
        // The Bluetooth address of an input device, by the hidden method both getters ask
        check("InputManagerGlobal.getInputDeviceBluetoothAddress", "android.hardware.input.InputManagerGlobal " +
                "getInputDeviceBluetoothAddress(int) @generic_null_value 34- optional");
        check("PowerManager.getLocationPowerSaveMode", "android.os.PowerManager getLocationPowerSaveMode() @generic_zero_value 28-");
        check("PowerManager.getBatteryDischargePrediction", "android.os.PowerManager getBatteryDischargePrediction() @battery 31-");
        check("PowerManager.isBatteryDischargePredictionPersonalized", "android.os.PowerManager " +
                "isBatteryDischargePredictionPersonalized() @battery 31-");
        check("NsdManager.registerServiceInfoCallback/discovery", "android.net.nsd.NsdManager registerServiceInfoCallback(" +
                "android.net.nsd.DiscoveryRequest,java.util.concurrent.Executor,android.net.nsd.NsdManager$ServiceInfoCallback) " +
                "@nsdmanager_listener 33- optional");
        // Broadcasts with Bluetooth devices and the network of Android 8.1, with the number of a call, with the carrier
        // and of a VPN
        for (String[] variant : new String[][]{{"network", "Read.Network"}, {"call_log", "Get.Call.log"},
                {"operator", "Use.Tracking"}, {"vpn", "Read.VPN"}}) {
            check("Intent.createFromParcel/" + variant[0], "android.content.Intent CREATOR:createFromParcel(android.os.Parcel) " +
                    "@intent_createfromparcel 27-");
            assertEquals(variant[1], hook("Intent.createFromParcel/" + variant[0]).getString("group"));
        }
        // The broadcasts of Bluetooth about a device, of a VPN and about other apps do not reach either kind of
        // receiver, with the signature of ReceiverDispatcher of each version, like the time zone change
        String args = "android.content.Intent,int,java.lang.String,android.os.Bundle,boolean,boolean,";
        for (String[] variant : new String[][]{{"network", "Read.Network"}, {"vpn", "Read.VPN"}, {"package", "Get.Applications"}}) {
            check("ActivityThread.handleReceiver/" + variant[0],
                    "android.app.ActivityThread handleReceiver(android.app.ActivityThread$ReceiverData) @receiver_skip 27-");
            check("ReceiverDispatcher.performReceive/" + variant[0],
                    "android.app.LoadedApk$ReceiverDispatcher performReceive(" + args + "int) @receiver_skip 27-33");
            check("ReceiverDispatcher.performReceive.sender/" + variant[0], "android.app.LoadedApk$ReceiverDispatcher " +
                    "performReceive(" + args + "boolean,int,int,java.lang.String) @receiver_skip 34-");
            for (String name : new String[]{"ActivityThread.handleReceiver/", "ReceiverDispatcher.performReceive/",
                    "ReceiverDispatcher.performReceive.sender/"})
                assertEquals(name + variant[0], variant[1], hook(name + variant[0]).getString("group"));
        }
        // The clients of a Wi-Fi Direct group, also by the hidden check and in its text
        check("WifiP2pGroup.getClientList", "android.net.wifi.p2p.WifiP2pGroup getClientList() @generic_empty 27-");
        check("WifiP2pGroup.isClientListEmpty",
                "android.net.wifi.p2p.WifiP2pGroup isClientListEmpty() @generic_true_value 27- optional");
        check("WifiP2pGroup.toString", "android.net.wifi.p2p.WifiP2pGroup toString() @network_tostring 27-");
        for (String name : new String[]{"WifiP2pGroup.getClientList", "WifiP2pGroup.isClientListEmpty", "WifiP2pGroup.toString"})
            assertEquals(name, "Read.Network", hook(name).getString("group"));
        assertEquals("android", hook("WifiP2pGroup.isClientListEmpty").getString("excludePackages"));
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
        // The clients of activity recognition, whose requests succeed without registering: those of getClient, and of the
        // constructors of the client class of play-services-location 20 and older
        String client = "com.google.android.gms.location.ActivityRecognitionClient";
        for (String[] variant : new String[][]{{"", "android.content.Context"}, {"/activity", "android.app.Activity"}}) {
            check("ActivityRecognition.getClient" + variant[0], "com.google.android.gms.location.ActivityRecognition " +
                    "getClient(" + variant[1] + ") @activityrecognition_getclient 27- optional");
            assertEquals(client, hook("ActivityRecognition.getClient" + variant[0]).getString("returnType"));
            JSONObject constructor = hook("ActivityRecognitionClient.constructor" + variant[0]);
            assertEquals(client, constructor.getString("className"));
            assertFalse(constructor.has("methodName"));
            assertEquals(variant[1], constructor.getJSONArray("parameterTypes").getString(0));
            assertEquals(1, constructor.getJSONArray("parameterTypes").length());
            assertEquals("@activityrecognition_getclient", constructor.getString("luaScript"));
            assertTrue(constructor.getBoolean("optional"));
            for (String name : new String[]{"ActivityRecognition.getClient", "ActivityRecognitionClient.constructor"})
                assertEquals("Determine.Activity", hook(name + variant[0]).getString("group"));
        }
        // The request of the older ActivityRecognitionApi, of the class of the object in its static field
        check("ActivityRecognitionApi.requestActivityUpdates", "com.google.android.gms.location.ActivityRecognition " +
                "ActivityRecognitionApi:requestActivityUpdates(com.google.android.gms.common.api.GoogleApiClient,long," +
                "android.app.PendingIntent) @activityrecognition_getclient 27- optional");
        assertEquals("com.google.android.gms.common.api.PendingResult",
                hook("ActivityRecognitionApi.requestActivityUpdates").getString("returnType"));
        assertEquals("Determine.Activity", hook("ActivityRecognitionApi.requestActivityUpdates").getString("group"));
        // The Awareness snapshot returns the same activity result type as ActivityRecognitionResult.extractResult
        check("DetectedActivityResponse.getActivityRecognitionResult", "com.google.android.gms.awareness.snapshot.DetectedActivityResponse " +
                "getActivityRecognitionResult() @activityrecognitionresult_extractresult 28- optional");
        assertEquals("Determine.Activity", hook("DetectedActivityResponse.getActivityRecognitionResult").getString("group"));
        assertEquals(28, hook("DetectedActivityResponse.getActivityRecognitionResult").getInt("minSdk"));
        // The activity fences of Awareness, which tell the fake unknown activity instead of the real one
        for (String method : new String[]{"during", "starting", "stopping"})
            check("DetectedActivityFence." + method, "com.google.android.gms.awareness.fence.DetectedActivityFence " +
                    method + "(int[]) @detectedactivityfence 28- optional");
        for (String method : new String[]{"during", "starting", "stopping"}) {
            assertEquals("Determine.Activity", hook("DetectedActivityFence." + method).getString("group"));
            assertTrue(hook("DetectedActivityFence." + method).getBoolean("optional"));
        }
        // Huawei's documented callback action shares the existing GMS activity receiver hooks
        String receiver = "android.app.LoadedApk$ReceiverDispatcher ";
        String receive = "performReceive(" + args;
        for (String name : new String[]{"ActivityThread.handleReceiver/activity", "ReceiverDispatcher.performReceive/activity",
                "ReceiverDispatcher.performReceive.sender/activity"}) {
            assertEquals("Determine.Activity", hook(name).getString("group"));
            assertEquals("android", hook(name).getString("excludePackages"));
        }
        check("ActivityThread.handleReceiver/activity",
                "android.app.ActivityThread handleReceiver(android.app.ActivityThread$ReceiverData) @receiver_skip 27-");
        check("ReceiverDispatcher.performReceive/activity", receiver + receive + "int) @receiver_skip 27-33");
        check("ReceiverDispatcher.performReceive.sender/activity", receiver + receive +
                "boolean,int,int,java.lang.String) @receiver_skip 34-");
        // The Huawei action is handled by the same script, without another hook on each framework method
        // Huawei Activity Identification factory, constructors, and registrations
        for (String[] variant : new String[][]{{"", "android.content.Context"}, {"/activity", "android.app.Activity"}}) {
            check("ActivityIdentification.getService" + variant[0], "com.huawei.hms.location.ActivityIdentification " +
                    "getService(" + variant[1] + ") @activityrecognition_getclient 28- optional");
            assertEquals("com.huawei.hms.location.ActivityIdentificationService",
                    hook("ActivityIdentification.getService" + variant[0]).getString("returnType"));
            JSONObject constructor = hook("ActivityIdentificationService.constructor" + variant[0]);
            assertEquals("com.huawei.hms.location.ActivityIdentificationService", constructor.getString("className"));
            assertFalse(constructor.has("methodName"));
            assertEquals(variant[1], constructor.getJSONArray("parameterTypes").getString(0));
            assertEquals("@activityrecognition_getclient", constructor.getString("luaScript"));
            assertEquals(28, constructor.getInt("minSdk"));
        }
        check("ActivityIdentificationService.createActivityIdentificationUpdates",
                "com.huawei.hms.location.ActivityIdentificationService createActivityIdentificationUpdates(long,android.app.PendingIntent) " +
                        "@activityrecognition_getclient 28- optional");
        check("ActivityIdentificationService.createActivityConversionUpdates",
                "com.huawei.hms.location.ActivityIdentificationService createActivityConversionUpdates(" +
                        "com.huawei.hms.location.ActivityConversionRequest,android.app.PendingIntent) @activityrecognition_getclient 28- optional");
        for (String name : new String[]{"ActivityIdentificationService.createActivityIdentificationUpdates",
                "ActivityIdentificationService.createActivityConversionUpdates"}) {
            assertEquals("com.huawei.hmf.tasks.Task", hook(name).getString("returnType"));
            assertEquals("Determine.Activity", hook(name).getString("group"));
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

    // The framework resource power_profile, loaded by ResourcesImpl with four arguments (Android 8.1 to 15 and main)
    // and with the flag of feature flags (Android 16 and 17), and each way its parser reads a text
    @Test
    public void powerProfileOfTheFramework() throws Exception {
        String load = "android.content.res.ResourcesImpl loadXmlResourceParser(java.lang.String,int,int,java.lang.String";
        String parser = "android.content.res.XmlBlock$Parser ";
        String[][] definitions = {
                {"ResourcesImpl.loadXmlResourceParser/power_profile", load + ") @battery 27- optional"},
                {"ResourcesImpl.loadXmlResourceParser.flags/power_profile", load + ",boolean) @battery 36- optional"},
                {"XmlBlock.Parser.getText/power_profile", parser + "getText() @battery 27- optional"},
                {"XmlBlock.Parser.nextText/power_profile", parser + "nextText() @battery 27- optional"},
                {"XmlBlock.Parser.getTextCharacters/power_profile", parser + "getTextCharacters(int[]) @battery 27- optional"}};
        for (String[] hook : definitions) {
            check(hook[0], hook[1]);
            assertEquals(hook[0], "Read.Battery", hook(hook[0]).getString("group"));
            assertEquals(hook[0], "android", hook(hook[0]).getString("excludePackages"));
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

    // A SecureDirectoryStream opens /proc/meminfo by openat, of which the script checks the file itself (see openedat),
    // and the disks of StorageManager are made of their parcel by the hidden DiskInfo, of which the script rounds the size
    @Test
    public void meminfoByDescriptorAndSizesOfDisks() throws Exception {
        check("UnixNativeDispatcher.openat/meminfo", "sun.nio.fs.UnixNativeDispatcher openat(int,byte[],int,int) @resources 27-");
        check("DiskInfo.createFromParcel",
                "android.os.storage.DiskInfo CREATOR:createFromParcel(android.os.Parcel) @resources 27- optional");
        for (String name : new String[]{"UnixNativeDispatcher.openat/meminfo", "DiskInfo.createFromParcel"})
            assertEquals(name, "Read.Resources", hook(name).getString("group"));
    }

    // The size of the block device of the internal storage, which the binder proxy gives from Android 14 QPR3 and the
    // system server reads itself for its own total
    @Test
    public void blockDeviceSizeOfTheInternalStorage() throws Exception {
        String name = "IStorageManager.getInternalStorageBlockDeviceSize";
        check(name, "android.os.storage.IStorageManager$Stub$Proxy getInternalStorageBlockDeviceSize() @resources 34- optional");
        assertEquals("Read.Resources", hook(name).getString("group"));
        assertEquals("android", hook(name).getString("excludePackages"));
    }

    // A SecureDirectoryStream opens /proc/cpuinfo and the uevent and modalias of the processors by openat too, of which
    // the script checks the file itself (see openedat of cpuinfo)
    @Test
    public void cpuinfoByDescriptor() throws Exception {
        check("UnixNativeDispatcher.openat/cpuinfo", "sun.nio.fs.UnixNativeDispatcher openat(int,byte[],int,int) @cpuinfo 27-");
        assertEquals("Use.Tracking", hook("UnixNativeDispatcher.openat/cpuinfo").getString("group"));
        // And the processors of /sys/devices/system/cpu and the status files of /proc, so their count agrees
        check("UnixNativeDispatcher.openat/cpu_count", "sun.nio.fs.UnixNativeDispatcher openat(int,byte[],int,int) @cpu_count 27-");
        assertEquals("Use.Tracking", hook("UnixNativeDispatcher.openat/cpu_count").getString("group"));
    }

    // Apps targeting Android 9 or older still get the neighboring cells on Android 10 and newer; the method is not
    // in the android.jar of recent versions, so it is optional
    @Test
    public void neighboringCellsOnAllVersions() throws Exception {
        check("TelephonyManager.getNeighboringCellInfo", "android.telephony.TelephonyManager getNeighboringCellInfo() " +
                "@generic_empty 27- optional");
    }

    // The fields of the service states and subscriptions the app gets, which their parcels and equals give, with the
    // operator of the getters, in the group of the getters
    @Test
    public void operatorInTheFieldsOfParcels() throws Exception {
        for (String type : new String[]{"ServiceState", "SubscriptionInfo"}) {
            String name = type + ".createFromParcel/operator";
            String className = "android.telephony." + type;
            check(name, className + " CREATOR:createFromParcel(android.os.Parcel) @test_network 27-");
            assertEquals(name, className, hook(name).getString("returnType"));
            assertEquals(name, "Use.Tracking", hook(name).getString("group"));
        }
    }

    // The cells of a network scan, by the hidden form of Android 8.1 without an executor, the form of Android 9 and the
    // one of Android 13 with a flag first, of which the callback is the last parameter, like of registerTelephonyCallback
    @Test
    public void networkScanGetsNoCells() throws Exception {
        String scan = "android.telephony.NetworkScanRequest,java.util.concurrent.Executor," +
                "android.telephony.TelephonyScanManager$NetworkScanCallback) @telephonymanager_registertelephonycallback ";
        check("TelephonyManager.requestNetworkScan", "android.telephony.TelephonyManager requestNetworkScan(" + scan + "28-");
        check("TelephonyManager.requestNetworkScan.33", "android.telephony.TelephonyManager requestNetworkScan(int," + scan + "33-");
        for (String name : new String[]{"TelephonyManager.requestNetworkScan", "TelephonyManager.requestNetworkScan.33"})
            assertEquals(name, "Read.Network", hook(name).getString("group"));
    }

    // Up to Android 11 an app can make Android send the cell location again, which its listeners get then, empty
    // (telephonymanager_listen), also by the system API of TelephonyManager; from Android 12 Android sends only a
    // location which changed
    @Test
    public void cellLocationRequestUpToAndroid11() throws Exception {
        check("CellLocation.requestLocationUpdate",
                "android.telephony.CellLocation requestLocationUpdate() @telephonymanager_listen 27-30");
        check("TelephonyManager.updateServiceLocation",
                "android.telephony.TelephonyManager updateServiceLocation() @telephonymanager_listen 27-30 optional");
        for (String name : new String[]{"CellLocation.requestLocationUpdate", "TelephonyManager.updateServiceLocation"})
            assertEquals(name, "Read.Network", hook(name).getString("group"));
    }

    // From Android 14 QPR2, still SDK 34, the recognizer of apps is a SpeechRecognizerProxy which calls a
    // SpeechRecognizerImpl; the methods of SpeechRecognizer itself only throw then
    @Test
    public void speechRecognizerImplFromAndroid14() throws Exception {
        check("SpeechRecognizerImpl.startListening", "android.speech.SpeechRecognizerImpl startListening(" +
                "android.content.Intent) @speech_recognizer 34- optional");
        check("SpeechRecognizerImpl.setRecognitionListener", "android.speech.SpeechRecognizerImpl setRecognitionListener(" +
                "android.speech.RecognitionListener) @speech_recognizer 34- optional");
        check("SpeechRecognizerImpl.cancel", "android.speech.SpeechRecognizerImpl cancel() @speech_recognizer 34- optional");
        check("SpeechRecognizerImpl.destroy", "android.speech.SpeechRecognizerImpl destroy() @speech_recognizer 34- optional");
    }

    // The screens of speech recognizers are started and looked up by the same methods as those of hidden apps (see
    // component_hidden and package_resolve), here for Record.Audio
    @Test
    public void speechRecognizerScreens() throws Exception {
        String start = "android.content.Context,android.os.IBinder,android.os.IBinder,";
        String i = "android.content.Intent";
        String pm = "android.content.pm.PackageManager";
        String pi = "android.app.PendingIntent";
        String[][] hooks = {
                {"Instrumentation.execStartActivity/speech", "android.app.Instrumentation execStartActivity(" + start +
                        "android.app.Activity," + i + ",int,android.os.Bundle) @recognizer_intent 27-"},
                {"Instrumentation.execStartActivity.fragment/speech", "android.app.Instrumentation execStartActivity(" + start +
                        "java.lang.String," + i + ",int,android.os.Bundle) @recognizer_intent 27-"},
                {"Instrumentation.execStartActivity.user/speech", "android.app.Instrumentation execStartActivity(" + start +
                        "java.lang.String," + i + ",int,android.os.Bundle,android.os.UserHandle) @recognizer_intent 27-"},
                {"Instrumentation.execStartActivitiesAsUser/speech", "android.app.Instrumentation execStartActivitiesAsUser(" +
                        start + "android.app.Activity," + i + "[],android.os.Bundle,int) @recognizer_intent 27-"},
                {"Activity.startActivityIfNeeded/speech", "android.app.Activity startActivityIfNeeded(" + i +
                        ",int,android.os.Bundle) @recognizer_intent 27-"},
                {"ActivityManager.AppTask.startActivity/speech", "android.app.ActivityManager$AppTask startActivity(" +
                        "android.content.Context," + i + ",android.os.Bundle) @recognizer_intent 27-"},
                {"PackageManager.resolveActivity/speech", pm + " resolveActivity(" + i + ",int) @recognizer_intent 27-"},
                {"PackageManager.resolveActivity/flags/speech", pm + " resolveActivity(" + i + "," + pm +
                        "$ResolveInfoFlags) @recognizer_intent 33-"},
                {"PackageManager.queryIntentActivities/speech", pm + " queryIntentActivities(" + i + ",int) @recognizer_intent 27-"},
                {"PackageManager.queryIntentActivities/flags/speech", pm + " queryIntentActivities(" + i + "," + pm +
                        "$ResolveInfoFlags) @recognizer_intent 33-"},
                {"PackageManager.queryIntentActivityOptions/speech", pm + " queryIntentActivityOptions(" +
                        "android.content.ComponentName," + i + "[]," + i + ",int) @recognizer_intent 27-"},
                {"PackageManager.queryIntentActivityOptions.33/speech", pm + " queryIntentActivityOptions(" +
                        "android.content.ComponentName,java.util.List," + i + "," + pm + "$ResolveInfoFlags) @recognizer_intent 33-"},
                {"PendingIntent.getActivity/speech", pi + " getActivity(android.content.Context,int," + i +
                        ",int) @recognizer_intent 27-"},
                {"PendingIntent.getActivity/options/speech", pi + " getActivity(android.content.Context,int," + i +
                        ",int,android.os.Bundle) @recognizer_intent 27-"},
                {"PendingIntent.getActivities/speech", pi + " getActivities(android.content.Context,int," + i +
                        "[],int) @recognizer_intent 27-"},
                {"PendingIntent.getActivities/options/speech", pi + " getActivities(android.content.Context,int," + i +
                        "[],int,android.os.Bundle) @recognizer_intent 27-"},
                // The fill-ins, where Android makes the call on each version
                {"Activity.startIntentSenderForResultInner/speech", "android.app.Activity startIntentSenderForResultInner(" +
                        "android.content.IntentSender,java.lang.String,int," + i + ",int,int,android.os.Bundle)" +
                        " @recognizer_intent 27- optional"},
                {"Context.startIntentSender/speech", "android.app.ContextImpl startIntentSender(android.content.IntentSender," + i +
                        ",int,int,int,android.os.Bundle) @recognizer_intent 27-"},
                {"PendingIntent.send/speech", pi + " send(android.content.Context,int," + i + "," + pi +
                        "$OnFinished,android.os.Handler,java.lang.String,android.os.Bundle) @recognizer_intent 27-"},
                {"PendingIntent.sendAndReturnResult/speech", pi + " sendAndReturnResult(android.content.Context,int," + i + "," + pi +
                        "$OnFinished,android.os.Handler,java.lang.String,android.os.Bundle) @recognizer_intent 28- optional"},
                {"IntentSender.sendIntent/speech", "android.content.IntentSender sendIntent(android.content.Context,int," + i +
                        ",android.content.IntentSender$OnFinished,android.os.Handler) @recognizer_intent 27-"},
                {"IntentSender.sendIntent/permission/speech", "android.content.IntentSender sendIntent(android.content.Context,int," + i +
                        ",android.content.IntentSender$OnFinished,android.os.Handler,java.lang.String) @recognizer_intent 27-"},
                {"IntentSender.sendIntent/options/speech", "android.content.IntentSender sendIntent(android.content.Context,int," + i +
                        ",android.content.IntentSender$OnFinished,android.os.Handler,java.lang.String,android.os.Bundle)" +
                        " @recognizer_intent 34- optional"},
                {"IntentSender.sendIntent/executor/speech", "android.content.IntentSender sendIntent(android.content.Context,int," + i +
                        ",java.lang.String,android.os.Bundle,java.util.concurrent.Executor,android.content.IntentSender$OnFinished)" +
                        " @recognizer_intent 36-"},
        };
        for (String[] hook : hooks) {
            check(hook[0], hook[1]);
            assertEquals(hook[0], "Record.Audio", hook(hook[0]).getString("group"));
        }
    }

    // The clipboard of the app is read by the clipboard manager; data of a parcel is also of drag and drop and of
    // intents, like the app's own drags, which are not the clipboard
    @Test
    public void clipboardIsReadFromTheClipboardManager() throws Exception {
        check("ClipboardManager.getPrimaryClip", "android.content.ClipboardManager getPrimaryClip() " +
                "@clipboard_getprimaryclip 27-");
        check("ClipboardManager.getPrimaryClipDescription", "android.content.ClipboardManager getPrimaryClipDescription() " +
                "@clipboard_getprimaryclip 27-");
        // The clips the app sets itself, which it gets back, and whether there is a clip, with text, which Android
        // answers
        check("ClipboardManager.setPrimaryClip", "android.content.ClipboardManager setPrimaryClip(android.content.ClipData) " +
                "@clipboard_getprimaryclip 27-");
        check("ClipboardManager.hasText", "android.content.ClipboardManager hasText() @clipboard_getprimaryclip 27-");
        check("ClipboardManager.hasPrimaryClip", "android.content.ClipboardManager hasPrimaryClip() " +
                "@clipboard_getprimaryclip 27-");
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
        for (String method : new String[]{"noteOp", "noteOpNoThrow", "startOp", "startOpNoThrow"})
            check("AppOpsManager." + method + "/code", "android.app.AppOpsManager " + method + "(int,int," + s +
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
        // Accessibility on, with the lists of the services empty
        for (String state : new String[]{"isEnabled", "isTouchExplorationEnabled"})
            check("AccessibilityManager." + state, "android.view.accessibility.AccessibilityManager " + state + "() " +
                    "@service_filter 27-");
        check("InstallSourceInfo.getInitiatingPackageSigningInfo", "android.content.pm.InstallSourceInfo " +
                "getInitiatingPackageSigningInfo() @package_result 30-");
        check("AutofillManager.isEnabled", "android.view.autofill.AutofillManager isEnabled() @package_result 27-");
        check("AutofillManager.ensureServiceClientAddedIfNeededLocked", "android.view.autofill.AutofillManager " +
                "ensureServiceClientAddedIfNeededLocked() @package_result 27-30 optional");
        check("AutofillManager.tryAddServiceClientIfNeededLocked", "android.view.autofill.AutofillManager " +
                "tryAddServiceClientIfNeededLocked() @package_result 31-34 optional");
        check("AutofillManager.tryAddServiceClientIfNeededLocked.credman", "android.view.autofill.AutofillManager " +
                "tryAddServiceClientIfNeededLocked(boolean) @package_result 35- optional");
        check("AutofillManager.setState", "android.view.autofill.AutofillManager setState(int) @package_result 28- optional");
        check("AutofillManager.enableCompatibilityMode", "android.view.autofill.AutofillManager " +
                "enableCompatibilityMode() @package_result 28- optional");
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
        // The calling accounts of hidden apps, on every version, which Read.Telephony empties only up to Android 12
        String telecom = "android.telecom.TelecomManager ";
        check("TelecomManager.getCallCapablePhoneAccounts/apps", telecom + "getCallCapablePhoneAccounts() " +
                "@generic_filter_by_uid 27-");
        check("TelecomManager.getCallCapablePhoneAccounts/disabled/apps", telecom + "getCallCapablePhoneAccounts(boolean) " +
                "@generic_filter_by_uid 27- optional");
        check("TelecomManager.getDefaultOutgoingPhoneAccount/apps", telecom + "getDefaultOutgoingPhoneAccount(" + s +
                ") @package_result 27-");
        check("TelecomManager.getUserSelectedOutgoingPhoneAccount/apps", telecom + "getUserSelectedOutgoingPhoneAccount() " +
                "@package_result 27-");
        check("TelecomManager.getSimCallManager/apps", telecom + "getSimCallManager() @package_result 27-");
        check("TelecomManager.getSimCallManager/user/apps", telecom + "getSimCallManager(int) @package_result 27- optional");
        check("TelecomManager.getSimCallManagerForSubscription/apps", telecom + "getSimCallManagerForSubscription(int) " +
                "@package_result 30-");
        check("TelecomManager.getPhoneAccount/apps", telecom + "getPhoneAccount(android.telecom.PhoneAccountHandle) " +
                "@package_result 27-");
        for (String name : new String[]{"getCallCapablePhoneAccounts/apps", "getDefaultOutgoingPhoneAccount/apps",
                "getPhoneAccount/apps", "getSimCallManager/apps"})
            assertEquals(name, "Get.Applications", hook("TelecomManager." + name).getString("group"));
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
        // Notes and starts record usage access, which reads as not granted for the app itself; each overload calls Android
        String o = "android.app.AppOpsManager ";
        String[][] notes = {
                {"noteOp", "noteOp(" + s + ",int," + s + ") @package_probe 27-"},
                {"noteOp/attribution", "noteOp(" + s + ",int," + s + "," + s + "," + s + ") @package_probe 30-"},
                {"noteOpNoThrow", "noteOpNoThrow(" + s + ",int," + s + ") @package_probe 27-"},
                {"noteOpNoThrow/attribution", "noteOpNoThrow(" + s + ",int," + s + "," + s + "," + s + ") @package_probe 30-"},
                {"startOp", "startOp(" + s + ",int," + s + ") @package_probe 27-"},
                {"startOp/attribution", "startOp(" + s + ",int," + s + "," + s + "," + s + ") @package_probe 30-"},
                {"startOpNoThrow", "startOpNoThrow(" + s + ",int," + s + ") @package_probe 27-"},
                {"startOpNoThrow/attribution", "startOpNoThrow(" + s + ",int," + s + "," + s + "," + s + ") @package_probe 30-"},
                {"noteProxyOp", "noteProxyOp(" + s + "," + s + ") @package_probe 27-"},
                {"noteProxyOp/attribution", "noteProxyOp(" + s + "," + s + ",int," + s + "," + s + ") @package_probe 30-"},
                {"noteProxyOpNoThrow", "noteProxyOpNoThrow(" + s + "," + s + ") @package_probe 27-"},
                {"noteProxyOpNoThrow/uid", "noteProxyOpNoThrow(" + s + "," + s + ",int) @package_probe 29-"},
                {"noteProxyOpNoThrow/attribution", "noteProxyOpNoThrow(" + s + "," + s + ",int," + s + "," + s + ") @package_probe 30-"},
                {"startProxyOp", "startProxyOp(" + s + ",int," + s + "," + s + "," + s + ") @package_probe 31-"},
                {"startProxyOpNoThrow", "startProxyOpNoThrow(" + s + ",int," + s + "," + s + "," + s + ") @package_probe 31-"}};
        for (String[] note : notes)
            check("AppOpsManager." + note[0], o + note[1]);
        check("BlockGuardOs.open/kernel", "libcore.io.BlockGuardOs open(" + s + ",int,int) @kernel_version 27-");
        // The kernel by Os and by Linux beneath Libcore.os
        check("Os.uname", "android.system.Os uname() @uname 27-");
        check("Linux.uname", "libcore.io.Linux uname() @uname 27-");
        assertEquals("Use.Tracking", hook("Linux.uname").getString("group"));
        check("CameraManager2.getCameraCharacteristics", "android.hardware.camera2.CameraManager getCameraCharacteristics(" + s +
                ") @camera2_open 27-");
        // The version of the camera hardware in the characteristics, which apps of Use.Tracking read without the camera
        String c = "android.hardware.camera2.CameraCharacteristics";
        check("CameraCharacteristics.get", c + " get(" + c + "$Key) @camera_characteristics 28-");
        check("CameraCharacteristics.getKeys", c + " getKeys() @camera_characteristics 28-");
        check("CameraManager2.getConcurrentCameraIds", "android.hardware.camera2.CameraManager getConcurrentCameraIds() @generic_empty 30-");
        String cm = "android.hardware.camera2.CameraManager";
        check("CameraManager2.registerAvailabilityCallback/handler", cm + " registerAvailabilityCallback(" + cm +
                "$AvailabilityCallback,android.os.Handler) @camera2_register 27-");
        check("CameraManager2.registerAvailabilityCallback/executor", cm + " registerAvailabilityCallback(" +
                "java.util.concurrent.Executor," + cm + "$AvailabilityCallback) @camera2_register 28-");
        check("CameraManager2.registerTorchCallback/handler", cm + " registerTorchCallback(" + cm +
                "$TorchCallback,android.os.Handler) @camera2_register 27-");
        check("CameraManager2.registerTorchCallback/executor", cm + " registerTorchCallback(" +
                "java.util.concurrent.Executor," + cm + "$TorchCallback) @camera2_register 28-");
        check("SpeechRecognizer.setRecognitionListener", "android.speech.SpeechRecognizer setRecognitionListener(" +
                "android.speech.RecognitionListener) @speech_recognizer 27-");
        check("SpeechRecognizer.startListening", "android.speech.SpeechRecognizer startListening(" + i + ") @speech_recognizer 27-");
        check("SpeechRecognizer.cancel", "android.speech.SpeechRecognizer cancel() @speech_recognizer 27-");
        check("SpeechRecognizer.destroy", "android.speech.SpeechRecognizer destroy() @speech_recognizer 27-");
        check("Display.getHdrCapabilities", "android.view.Display getHdrCapabilities() @display_panel 27-");
        check("Display.getDeviceProductInfo", "android.view.Display getDeviceProductInfo() @display_panel 31-");
        check("Display.toString", "android.view.Display toString() @display_panel 27-");
        check("Display.getAddress", "android.view.Display getAddress() @display_panel 29-29 optional");
        check("Display.getRoundedCorner", "android.view.Display getRoundedCorner(int) @display_panel 31-");
        check("InsetsState.calculateRelativeRoundedCorners",
                "android.view.InsetsState calculateRelativeRoundedCorners(android.graphics.Rect) @display_panel 31- optional");
        check("Display.getShape", "android.view.Display getShape() @display_panel 34-");
        check("InsetsState.calculateRelativeDisplayShape",
                "android.view.InsetsState calculateRelativeDisplayShape(android.graphics.Rect) @display_panel 34- optional");
        for (String name : new String[]{"InsetsState.calculateRelativeRoundedCorners", "InsetsState.calculateRelativeDisplayShape"})
            assertEquals(name, "[\"android.view.InsetsState.calculateInsets\"]", hook(name).getJSONArray("deoptimize").toString());
        // The fingerprint made like its property, the base OS empty like not set
        check("Build.FINGERPRINT", "android.os.Build #FINGERPRINT() @systemproperties_get 27-");
        check("Build.VERSION.BASE_OS", "android.os.Build$VERSION #BASE_OS() @generic_empty_text 27-");
        check("LocationManager.registerGnssStatusCallback30", "android.location.LocationManager registerGnssStatusCallback(" +
                "java.util.concurrent.Executor,android.location.GnssStatus$Callback) @gnss_register 30-");
        check("LinkProperties.getMtu/vpn", "android.net.LinkProperties getMtu() @vpn_addresses 27-");
        check("LinkProperties.getLinkAddresses/vpn", "android.net.LinkProperties getLinkAddresses() @vpn_addresses 27-");
        check("LinkProperties.getDnsServers/vpn", "android.net.LinkProperties getDnsServers() @vpn_addresses 27-");
        check("LinkProperties.getHttpProxy/vpn", "android.net.LinkProperties getHttpProxy() @vpn_addresses 29-");
        check("CarrierConfigManager.getConfigForSubId", "android.telephony.CarrierConfigManager getConfigForSubId(int) @mms_useragent 27-");
        check("UsageStatsManager.queryUsageStats", "android.app.usage.UsageStatsManager queryUsageStats(int,long,long) " +
                "@usageevents_query 27-");
        check("UsageStatsManager.queryEventStats", "android.app.usage.UsageStatsManager queryEventStats(int,long,long) " +
                "@usageevents_query 28-");
        check("UsageStatsManager.queryConfigurations", "android.app.usage.UsageStatsManager queryConfigurations(int,long,long) " +
                "@usageevents_query 27-");
        // The task roots in the app's own events come with usage access, from Android 10
        check("UsageStatsManager.queryEventsForSelf", "android.app.usage.UsageStatsManager queryEventsForSelf(long,long) " +
                "@usageevents_query 29-");
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
        // The looper of a Wi-Fi Direct channel, on which wifi_discovery answers
        check("WifiP2pManager.initialize", "android.net.wifi.p2p.WifiP2pManager initialize(android.content.Context," +
                "android.os.Looper,android.net.wifi.p2p.WifiP2pManager$ChannelListener) @wifi_discovery 27-");
        assertEquals("Read.Network", hook("WifiP2pManager.initialize").getString("group"));
        assertEquals("android.content.SyncAdapterType[]", hook("ContentResolver.getSyncAdapterTypes").getString("returnType"));
        assertEquals("android.net.NetworkRequest$Builder", hook("NetworkRequest.Builder.addTransportType").getString("returnType"));
        // A request for VPN networks is one for LoWPAN (see vpn_request); NetworkCapabilities.Builder, and
        // NetworkRequest.Builder(NetworkCapabilities), are system APIs, which apps cannot use
        check("NetworkRequest.Builder.addTransportType", "android.net.NetworkRequest$Builder addTransportType(int) @vpn_request 27-");
        check("NetworkRequest.Builder.removeTransportType", "android.net.NetworkRequest$Builder removeTransportType(int) @vpn_request 27-");
        // An app which is a VPN starts no tunnel, like one without the consent of the user (see vpn_service);
        // startProvisionedVpnProfile is refused itself also from Android 13, where it calls the session one, in case the
        // boot image inlined that call, and the state of a platform VPN is the null of a package which runs none
        check("VpnService.prepare", "android.net.VpnService prepare(android.content.Context) @vpn_service 27-");
        check("VpnService.Builder.establish", "android.net.VpnService$Builder establish() @vpn_service 27-");
        check("VpnService.isAlwaysOn", "android.net.VpnService isAlwaysOn() @vpn_service 29-");
        check("VpnService.isLockdownEnabled", "android.net.VpnService isLockdownEnabled() @vpn_service 29-");
        check("VpnManager.provisionVpnProfile",
                "android.net.VpnManager provisionVpnProfile(android.net.PlatformVpnProfile) @vpn_service 30-");
        check("VpnManager.startProvisionedVpnProfile", "android.net.VpnManager startProvisionedVpnProfile() @vpn_service 30-");
        check("VpnManager.startProvisionedVpnProfileSession",
                "android.net.VpnManager startProvisionedVpnProfileSession() @vpn_service 33-");
        check("VpnManager.getProvisionedVpnProfileState",
                "android.net.VpnManager getProvisionedVpnProfileState() @vpn_service 33-");
        // NetworkRequest compares the fields of the capabilities of a VPN, which lack NOT_VPN
        check("NetworkRequest.canBeSatisfiedBy", "android.net.NetworkRequest canBeSatisfiedBy(android.net.NetworkCapabilities) @vpn 30-");
        // The parcels of the capabilities, network info and link properties of a VPN, which are made of their fields, not
        // in system server, which parcels them for every app
        for (String cls : new String[]{"NetworkCapabilities", "NetworkInfo", "LinkProperties"}) {
            check(cls + ".writeToParcel/vpn", "android.net." + cls + " writeToParcel(android.os.Parcel,int) @vpn_parcel 27-");
            assertEquals(cls, "Read.VPN", hook(cls + ".writeToParcel/vpn").getString("group"));
            assertEquals(cls, "android", hook(cls + ".writeToParcel/vpn").getString("excludePackages"));
        }
        // The capabilities and link properties of a VPN compare and hash like their parcel, not in system server either
        for (String cls : new String[]{"NetworkCapabilities", "LinkProperties"}) {
            check(cls + ".equals/vpn", "android.net." + cls + " equals(java.lang.Object) @vpn_parcel 27-");
            check(cls + ".hashCode/vpn", "android.net." + cls + " hashCode() @vpn_parcel 27-");
            for (String method : new String[]{"equals", "hashCode"}) {
                assertEquals(cls, "Read.VPN", hook(cls + "." + method + "/vpn").getString("group"));
                assertEquals(cls, "android", hook(cls + "." + method + "/vpn").getString("excludePackages"));
            }
        }
        // The signal strength of the Wi-Fi network under a VPN, hidden before Android 10, and the subtype, extra info
        // and roaming of the network under it
        check("NetworkCapabilities.getSignalStrength", "android.net.NetworkCapabilities getSignalStrength() @vpn 27-");
        check("NetworkInfo.getSubtype", "android.net.NetworkInfo getSubtype() @vpn 27-");
        check("NetworkInfo.getSubtypeName", "android.net.NetworkInfo getSubtypeName() @vpn 27-");
        check("NetworkInfo.getExtraInfo/vpn", "android.net.NetworkInfo getExtraInfo() @vpn 27-");
        // Empty like the extra info of a Wi-Fi network from Android 11, not null, which a connected network does not have
        check("NetworkInfo.getExtraInfo", "android.net.NetworkInfo getExtraInfo() @generic_empty_text 30-");
        check("NetworkInfo.getExtraInfo/29", "android.net.NetworkInfo getExtraInfo() @generic_null_value 27-29");
        check("NetworkInfo.isRoaming", "android.net.NetworkInfo isRoaming() @vpn 27-");
        // The local address of sockets through a VPN, by the methods of Read.Network
        for (String name : new String[]{"Socket.getLocalAddress", "Socket.getLocalSocketAddress", "DatagramSocket.getLocalAddress",
                "DatagramSocket.getLocalSocketAddress", "SocketAdaptor.getLocalAddress", "DatagramSocketAdaptor.getLocalAddress",
                "SocketChannelImpl.getLocalAddress", "AsynchronousSocketChannelImpl.getLocalAddress",
                "DatagramChannelImpl.getLocalAddress", "Os.getsockname"}) {
            JSONObject network = hook(name);
            JSONObject vpn = hook(name + "/vpn");
            assertNotNull(name + "/vpn is not defined", vpn);
            assertEquals(name, "Read.VPN", vpn.getString("group"));
            assertEquals(name, "@vpn", vpn.getString("luaScript"));
            for (String key : new String[]{"className", "methodName", "parameterTypes", "returnType", "minSdk"})
                assertEquals(name + " " + key, network.get(key).toString(), vpn.get(key).toString());
            assertEquals(name, network.optBoolean("optional", false), vpn.optBoolean("optional", false));
        }
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
        // Opening by FileInputStream and FileOutputStream of Android 8.1 and 9, which open natively, like the root
        // files, and the link count of a descriptor of a folder of system apps
        for (String name : new String[]{"FileInputStream.open0", "FileOutputStream.open0"}) {
            JSONObject root = hook(name + "/root"), apps = hook(name + "/privapp_files");
            assertNotNull(name + "/privapp_files", apps);
            for (String key : new String[]{"className", "methodName", "parameterTypes", "returnType", "minSdk", "maxSdk",
                    "optional", "luaScript"})
                assertEquals(name + " " + key, String.valueOf(root.opt(key)), String.valueOf(apps.opt(key)));
            assertEquals(name, "Get.Applications", apps.getString("group"));
        }
        check("Os.fstat/privapp_files", "android.system.Os fstat(java.io.FileDescriptor) @hardware_files 27-");
        assertEquals("Get.Applications", hook("Os.fstat/privapp_files").getString("group"));
        // Making a folder, a pipe, a node or a symbolic link by its name, which gave EEXIST where stat gave ENOENT, like
        // the calls of the root files
        for (String name : PRIVAPP_MAKING) {
            JSONObject root = hook(name + "/root"), apps = hook(name + "/privapp_files");
            assertNotNull(name + "/privapp_files", apps);
            for (String key : new String[]{"className", "methodName", "parameterTypes", "returnType", "minSdk", "maxSdk",
                    "optional", "luaScript"})
                assertEquals(name + " " + key, String.valueOf(root.opt(key)), String.valueOf(apps.opt(key)));
            assertEquals(name, "Get.Applications", apps.getString("group"));
        }
    }

    // The calls of making by a name of Get.Applications, which the groups of the sensor files do not all have
    private static final List<String> PRIVAPP_MAKING = Arrays.asList("Os.mkdir", "Os.mkfifo", "Os.symlink",
            "UnixNativeDispatcher.mkdir", "UnixNativeDispatcher.symlink", "UnixNativeDispatcher.mknod");

    // The calls of Get.Applications which the groups of the sensor files do not have: opening by FileInputStream and
    // FileOutputStream of Android 8.1 and 9 and the link count of a descriptor of a folder of system apps
    private static final List<String> PRIVAPP_ONLY = Arrays.asList("FileInputStream.open0", "FileOutputStream.open0",
            "Os.fstat");

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

    // The same calls for the files Use.Tracking hides, like the speeds of the processors and the fonts of makers, which
    // gave EEXIST for making a folder or a link by their name where stat gave ENOENT
    @Test
    public void hardwareModelByEveryPathCall() throws Exception {
        for (String name : new String[]{"Os.listxattr", "Os.setxattr", "Os.removexattr", "Os.chmod", "Os.chown", "Os.lchown",
                "Os.link", "Os.remove", "Os.mkdir", "Os.mkfifo", "Os.symlink", "Os.rename", "UnixNativeDispatcher.chmod",
                "UnixNativeDispatcher.chown", "UnixNativeDispatcher.lchown", "UnixNativeDispatcher.link",
                "UnixNativeDispatcher.mkdir", "UnixNativeDispatcher.symlink", "UnixNativeDispatcher.rename"}) {
            JSONObject ids = hook(name + "/hardware_ids"), model = hook(name + "/hardware_model");
            assertNotNull(name, model);
            for (String key : new String[]{"className", "methodName", "parameterTypes", "returnType", "minSdk", "luaScript"})
                assertEquals(name + " " + key, ids.get(key).toString(), model.get(key).toString());
            assertFalse(name, model.has("optional"));
            assertEquals(name, "Use.Tracking", model.getString("group"));
        }
    }

    // The same calls for su and the files of Magisk and of the fingerprint sensor, which gave EROFS for changing the
    // mode of su and EEXIST for making a folder by its name where stat gave ENOENT, and a node by its name, which
    // Files.copy of a special file makes (AOSP oreo-release to main: mknod(UnixPath,int,long)); and opening them by
    // FileInputStream and FileOutputStream of Android 8.1 and 9, which open natively, like the folders of VPN interfaces
    @Test
    public void rootAndFingerprintSensorByEveryPathCall() throws Exception {
        for (String suffix : new String[]{"root", "fingerprint_sensor"}) {
            String group = (suffix.equals("root") ? "Read.Debugging" : "Read.Biometrics");
            for (String name : new String[]{"Os.listxattr", "Os.setxattr", "Os.removexattr", "Os.chmod", "Os.chown",
                    "Os.lchown", "Os.link", "Os.remove", "Os.mkdir", "Os.mkfifo", "Os.symlink", "Os.rename",
                    "UnixNativeDispatcher.chmod", "UnixNativeDispatcher.chown", "UnixNativeDispatcher.lchown",
                    "UnixNativeDispatcher.link", "UnixNativeDispatcher.mkdir", "UnixNativeDispatcher.symlink",
                    "UnixNativeDispatcher.rename"}) {
                JSONObject model = hook(name + "/hardware_model"), hook = hook(name + "/" + suffix);
                assertNotNull(name + "/" + suffix, hook);
                for (String key : new String[]{"className", "methodName", "parameterTypes", "returnType", "minSdk", "luaScript"})
                    assertEquals(name + " " + key, model.get(key).toString(), hook.get(key).toString());
                assertFalse(name, hook.has("optional"));
                assertEquals(name, group, hook.getString("group"));
            }
            check("UnixNativeDispatcher.mknod/" + suffix,
                    "sun.nio.fs.UnixNativeDispatcher mknod(sun.nio.fs.UnixPath,int,long) @hardware_files 27-");
            assertEquals(group, hook("UnixNativeDispatcher.mknod/" + suffix).getString("group"));
            for (String name : new String[]{"FileInputStream.open0", "FileOutputStream.open0"}) {
                JSONObject vpn = hook(name + "/vpn_interfaces"), hook = hook(name + "/" + suffix);
                assertNotNull(name + "/" + suffix, hook);
                for (String key : new String[]{"className", "methodName", "parameterTypes", "returnType", "minSdk", "maxSdk",
                        "luaScript"})
                    assertEquals(name + " " + key, vpn.get(key).toString(), hook.get(key).toString());
                assertFalse(name, hook.has("optional"));
                assertEquals(name, group, hook.getString("group"));
            }
        }
    }

    // The state of adb in the property areas apps read as files, like the model of the device in them
    @Test
    public void debuggingInPropertyAreas() throws Exception {
        for (String name : new String[]{"BlockGuardOs.open", "UnixNativeDispatcher.open"}) {
            JSONObject device = hook(name + "/device"), debugging = hook(name + "/debugging");
            assertNotNull(name + "/debugging", debugging);
            for (String key : new String[]{"className", "methodName", "parameterTypes", "returnType", "minSdk", "luaScript"})
                assertEquals(name + " " + key, device.get(key).toString(), debugging.get(key).toString());
            assertFalse(name, debugging.has("maxSdk") || debugging.has("optional"));
            assertEquals(name, hook("SystemProperties.get/debugging").getString("group"), debugging.getString("group"));
        }
    }

    // The fields of Build of a user build, which Android sets once from the properties, in the group of the properties;
    // the flags of the type are hidden (Build.java oreo-mr1-release to main), so not in the SDK
    @Test
    public void userBuildFields() throws Exception {
        String group = hook("SystemProperties.get/debugging").getString("group");
        for (String field : new String[]{"TYPE", "TAGS", "FINGERPRINT", "DISPLAY", "IS_DEBUGGABLE", "IS_ENG", "IS_USERDEBUG",
                "IS_USER"}) {
            boolean flag = field.startsWith("IS_");
            String name = "Build." + field + "/debugging";
            check(name, "android.os.Build #" + field + "() @systemproperties_get 27-" + (flag ? " optional" : ""));
            assertEquals(name, flag ? "boolean" : "java.lang.String", hook(name).getString("returnType"));
            assertEquals(name, group, hook(name).getString("group"));
        }
    }

    // Key attestation, which signs the real security patch, refused below the key pair generator, by keystore of
    // Android 8.1 to 11 and the binder of keystore2 from Android 12 (frameworks/base oreo-mr1-release to
    // android16-release, system/hardware/interfaces keystore2 AIDL), not in system server; and the flags of the type of
    // the build, which Use.Tracking gives as 'unknown', in the group of the security patch
    @Test
    public void keyAttestationAndBuildFlagsOfTracking() throws Exception {
        String group = hook("Build.VERSION.SECURITY_PATCH").getString("group");
        check("KeyStore.attestKey", "android.security.KeyStore attestKey(java.lang.String,android.security.keymaster." +
                "KeymasterArguments,android.security.keymaster.KeymasterCertificateChain) @key_attestation 27-30");
        check("IKeystoreSecurityLevel.generateKey", "android.system.keystore2.IKeystoreSecurityLevel$Stub$Proxy generateKey(" +
                "android.system.keystore2.KeyDescriptor,android.system.keystore2.KeyDescriptor," +
                "android.hardware.security.keymint.KeyParameter[],int,byte[]) @key_attestation 31-");
        // The characteristics of the keys, with the patch levels, read from the parcel of keystore of Android 8.1 to 11
        // (frameworks/base oreo-mr1-release to android11-release KeyCharacteristics, IKeystoreService and its callback)
        check("KeyCharacteristics.readFromParcel", "android.security.keymaster.KeyCharacteristics readFromParcel(" +
                "android.os.Parcel) @key_attestation 27-30");
        assertEquals("void", hook("KeyCharacteristics.readFromParcel").getString("returnType"));
        assertEquals("[\"android.security.keymaster.KeyCharacteristics.<init>\"," +
                "\"android.security.keymaster.KeyCharacteristics$1.createFromParcel\"," +
                "\"android.security.IKeystoreService$Stub$Proxy.generateKey\"," +
                "\"android.security.IKeystoreService$Stub$Proxy.getKeyCharacteristics\"," +
                "\"android.security.IKeystoreService$Stub$Proxy.importKey\"," +
                "\"android.security.IKeystoreService$Stub$Proxy.importWrappedKey\"]",
                hook("KeyCharacteristics.readFromParcel").getJSONArray("deoptimize").toString());
        for (String name : new String[]{"KeyStore.attestKey", "IKeystoreSecurityLevel.generateKey", "KeyCharacteristics.readFromParcel"}) {
            assertEquals(name, group, hook(name).getString("group"));
            assertEquals(name, "android", hook(name).getString("excludePackages"));
        }
        assertEquals("int", hook("KeyStore.attestKey").getString("returnType"));
        assertEquals("android.system.keystore2.KeyMetadata", hook("IKeystoreSecurityLevel.generateKey").getString("returnType"));
        for (String field : new String[]{"IS_ENG", "IS_USERDEBUG", "IS_USER"}) {
            check("Build." + field, "android.os.Build #" + field + "() @systemproperties_get 27- optional");
            assertEquals(field, "boolean", hook("Build." + field).getString("returnType"));
            assertEquals(field, group, hook("Build." + field).getString("group"));
        }
    }

    // The names of the file stores of java.nio, which getFileStores makes of the mount entries it reads natively, on
    // every version (AOSP oreo-mr1-release to android17-release declare both in UnixFileStore)
    @Test
    public void namesOfFileStores() throws Exception {
        for (String method : new String[]{"name", "toString"}) {
            check("UnixFileStore." + method + "/hardware_model", "sun.nio.fs.UnixFileStore " + method + "() @hardware_files 27-");
            assertEquals(method, "Use.Tracking", hook("UnixFileStore." + method + "/hardware_model").getString("group"));
            assertEquals(method, "java.lang.String", hook("UnixFileStore." + method + "/hardware_model").getString("returnType"));
        }
    }

    // The folder of a VPN interface on Android 8.1 (and 9 in /sys/devices/virtual/net): its link and real path, which
    // apps can read, and opening or making its files (EACCES), where a missing interface gives ENOENT, also by
    // FileInputStream and FileOutputStream, which open natively there; listings of the folder fail with EACCES, which
    // File.list answers with null like for a hidden folder, and the hooks of the listings of Read.VPN are for the names
    // of the interfaces in /proc/net and /proc/sys/net (see networkFilesDeniedByOpenAndAccess)
    @Test
    public void vpnFoldersByEveryPathCall() throws Exception {
        for (String name : new String[]{"Os.readlink", "UnixNativeDispatcher.readlink", "UnixNativeDispatcher.realpath",
                "BlockGuardOs.open", "UnixNativeDispatcher.open", "UnixNativeDispatcher.opendir",
                "UnixFileSystem.getLastModifiedTime", "UnixFileSystem.createFileExclusively"}) {
            JSONObject root = hook(name + "/root"), vpn = hook(name + "/vpn_interfaces");
            assertNotNull(name, vpn);
            for (String key : new String[]{"className", "methodName", "parameterTypes", "returnType", "minSdk", "luaScript"})
                assertEquals(name + " " + key, root.get(key).toString(), vpn.get(key).toString());
            assertEquals(name, "Read.VPN", vpn.getString("group"));
        }
        check("FileInputStream.open0/vpn_interfaces", "java.io.FileInputStream open0(java.lang.String) @hardware_files 27-28");
        check("FileOutputStream.open0/vpn_interfaces",
                "java.io.FileOutputStream open0(java.lang.String,boolean) @hardware_files 27-28");
        for (String name : new String[]{"FileInputStream.open0", "FileOutputStream.open0"})
            assertEquals(name, "Read.VPN", hook(name + "/vpn_interfaces").getString("group"));
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

    // The files of the sensor, camera and microphone features are hidden by the same calls as the permission files of
    // system apps, since both are files of a folder which stays; opening the other feature files reads them without
    // the features of the group
    @Test
    public void sensorFilesByEveryPathCall() throws Exception {
        String[][] variants = {{"/sensor_files", "Get.Sensors"}, {"/camera_files", "Use.Camera"},
                {"/microphone_files", "Record.Audio"}};
        int files = 0;
        for (JSONObject hook : hooks)
            if (hook.getString("name").endsWith("/privapp_files") &&
                    !PRIVAPP_MAKING.contains(hook.getString("name").replace("/privapp_files", "")) &&
                    !PRIVAPP_ONLY.contains(hook.getString("name").replace("/privapp_files", ""))) {
                files++;
                for (String[] variant : variants) {
                    String name = hook.getString("name").replace("/privapp_files", variant[0]);
                    JSONObject features = hook(name);
                    assertNotNull(name, features);
                    assertEquals(name, variant[1], features.getString("group"));
                    for (String key : new String[]{"className", "methodName", "parameterTypes", "returnType", "minSdk",
                            "maxSdk", "optional", "luaScript"})
                        assertEquals(name + " " + key, String.valueOf(hook.opt(key)), String.valueOf(features.opt(key)));
                }
            }
        assertEquals(40, files);
        // The camera and microphone files also by FileInputStream and FileOutputStream of Android 8.1 and 9, which open
        // natively, and by making a folder, a pipe or a link by their name, which gave EEXIST where stat gave ENOENT
        String[][] extras = {{"FileInputStream.open0", "/vpn_interfaces"}, {"FileOutputStream.open0", "/vpn_interfaces"},
                {"Os.mkdir", "/hardware_model"}, {"Os.mkfifo", "/hardware_model"}, {"Os.symlink", "/hardware_model"},
                {"UnixNativeDispatcher.mkdir", "/hardware_model"}, {"UnixNativeDispatcher.symlink", "/hardware_model"}};
        for (String[] variant : new String[][]{variants[1], variants[2]})
            for (String[] extra : extras) {
                JSONObject source = hook(extra[0] + extra[1]), copy = hook(extra[0] + variant[0]);
                assertNotNull(extra[0] + variant[0], copy);
                assertEquals(extra[0] + variant[0], variant[1], copy.getString("group"));
                for (String key : new String[]{"className", "methodName", "parameterTypes", "returnType", "minSdk",
                        "maxSdk", "optional", "luaScript"})
                    assertEquals(extra[0] + variant[0] + " " + key, String.valueOf(source.opt(key)),
                            String.valueOf(copy.opt(key)));
            }
        check("FileInputStream.open0/camera_files", "java.io.FileInputStream open0(java.lang.String) @hardware_files 27-28");
        for (String[] variant : variants) {
            int count = 0;
            for (JSONObject hook : hooks)
                if (hook.getString("name").endsWith(variant[0]))
                    count++;
            assertEquals(variant[0], variant[0].equals("/sensor_files") ? 40 : 47, count);
        }
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
        assertEquals(14, groups);
        assertEquals("Read.Telephony", hook("ProcessBuilder.start/telephony").getString("group"));
    }

    // The hooks of the stat of BlockGuardOs behind Os.stat (Android 12 and 13), of the time of UnixFileSystem behind
    // File.lastModified and of the creator of Intent behind the sticky intent registerReceiver returns (Android 12) did not
    // run on the emulators, likely since the boot image compiled them into those callers, not yet confirmed on a device:
    // every hook of these methods, in each group, names the callers to deoptimize, so the hook runs for them too
    @Test
    public void inlinedMethodsAreDeoptimized() throws Exception {
        Map<String, String> callers = new HashMap<>();
        callers.put("libcore.io.BlockGuardOs stat", "[\"android.system.Os.stat\",\"libcore.io.ForwardingOs.stat\"]");
        callers.put("libcore.io.BlockGuardOs lstat", "[\"android.system.Os.lstat\",\"libcore.io.ForwardingOs.lstat\"]");
        callers.put("java.io.UnixFileSystem getLastModifiedTime", "[\"java.io.File.lastModified\"]");
        callers.put("android.content.Intent CREATOR:createFromParcel", "[" +
                "\"android.app.IActivityManager$Stub$Proxy.registerReceiver\"," +
                "\"android.app.IActivityManager$Stub$Proxy.registerReceiverWithFeature\"]");
        Map<String, Integer> found = new HashMap<>();
        for (JSONObject hook : hooks) {
            String method = hook.optString("className") + " " + hook.optString("methodName");
            if (callers.containsKey(method)) {
                assertEquals(hook.getString("name"), callers.get(method), hook.getJSONArray("deoptimize").toString());
                found.merge(method, 1, Integer::sum);
            }
        }
        assertEquals(13, (int) found.get("libcore.io.BlockGuardOs stat"));
        assertEquals(11, (int) found.get("libcore.io.BlockGuardOs lstat"));
        assertEquals(13, (int) found.get("java.io.UnixFileSystem getLastModifiedTime"));
        assertEquals(16, (int) found.get("android.content.Intent CREATOR:createFromParcel"));
    }

    // The checks of access for running a program agree with its exec in every group which refuses it (see
    // commands_blocked): Os.access, Libcore.os.access, UnixNativeDispatcher.access, of the checkAccess of java.nio, which
    // is deoptimized, since the boot image can have access compiled into it, and File.canExecute and Files.isExecutable,
    // after their real check on every version
    @Test
    public void programsMayNotRunByAccess() throws Exception {
        String s = "java.lang.String";
        int groups = 0;
        for (JSONObject hook : hooks)
            if (hook.getString("name").startsWith("ProcessBuilder.start")) {
                groups++;
                String suffix = "/programs" + hook.getString("name").substring("ProcessBuilder.start".length());
                check("Os.access" + suffix, "android.system.Os access(" + s + ",int) @commands_blocked 27-");
                check("BlockGuardOs.access" + suffix, "libcore.io.BlockGuardOs access(" + s + ",int) @commands_blocked 27-");
                check("File.canExecute" + suffix, "java.io.File canExecute() @commands_blocked 27-");
                check("UnixNativeDispatcher.access" + suffix,
                        "sun.nio.fs.UnixNativeDispatcher access(sun.nio.fs.UnixPath,int) @commands_blocked 27-");
                assertEquals("[\"sun.nio.fs.UnixFileSystemProvider.checkAccess\"]",
                        hook("UnixNativeDispatcher.access" + suffix).getJSONArray("deoptimize").toString());
                check("Files.isExecutable" + suffix, "java.nio.file.Files isExecutable(java.nio.file.Path) @commands_blocked 27-");
                for (String name : new String[]{
                        "Os.access", "BlockGuardOs.access", "File.canExecute", "UnixNativeDispatcher.access", "Files.isExecutable"})
                    assertEquals(hook.getString("group"), hook(name + suffix).getString("group"));
            }
        assertEquals(14, groups);
    }

    // The methods beneath ProcessBuilder.start and Os.execv and execve, which apps can call by reflection, for the
    // hidden apps, for the phone services, which the service tool asks with the app's permissions, and for commands
    // themselves
    @Test
    public void programsBeneathTheWaysToRun() throws Exception {
        String s = "java.lang.String";
        // Use.Tracking for its own commands, by the names without a suffix
        String[][] groups = {{"/packages", "Get.Applications"}, {"/commands", "Run.Commands"}, {"", "Use.Tracking"},
                {"/telephony", "Read.Telephony"}};
        for (String[] group : groups) {
            String suffix = group[0];
            check("UNIXProcess.forkAndExec" + suffix,
                    "java.lang.UNIXProcess forkAndExec(byte[],byte[],int,byte[],int,byte[],int[],boolean) @commands_blocked 27-");
            check("Linux.execv" + suffix, "libcore.io.Linux execv(" + s + "," + s + "[]) @commands_blocked 27-");
            check("Linux.execve" + suffix, "libcore.io.Linux execve(" + s + "," + s + "[]," + s + "[]) @commands_blocked 27-");
            for (String name : new String[]{"UNIXProcess.forkAndExec", "Linux.execv", "Linux.execve"})
                assertEquals(group[1], hook(name + suffix).getString("group"));
        }
    }

    // The other ways to the values of fingerprints: the pages of memory, the hidden natives of the memory sizes, which
    // the system server keeps for its own memory levels, the methods behind SystemProperties.get, the
    // metrics and parameters of codecs, listeners of the heat; of the vibrator SystemVibrator only, which is
    // what Vibrator resolves to
    @Test
    public void fingerprintsByOtherWays() throws Exception {
        String s = "java.lang.String", p = "android.os.PowerManager";
        String status = p + "$OnThermalStatusChangedListener", headroom = p + "$OnThermalHeadroomChangedListener";
        String e = "java.util.concurrent.Executor,";
        check("Os.sysconf/resources", "android.system.Os sysconf(int) @resources 27-");
        check("ForwardingOs.sysconf/resources", "libcore.io.ForwardingOs sysconf(int) @resources 27-");
        check("IActivityManager.getMemoryInfo", "android.app.IActivityManager$Stub$Proxy getMemoryInfo(" +
                "android.app.ActivityManager$MemoryInfo) @resources 27- optional");
        check("Process.getTotalMemory", "android.os.Process getTotalMemory() @resources 27- optional");
        check("Process.getFreeMemory", "android.os.Process getFreeMemory() @resources 27- optional");
        check("Process.readProcLines/meminfo", "android.os.Process readProcLines(" + s +
                ",java.lang.String[],long[]) @resources 27- optional");
        check("Process.readProcFile/meminfo", "android.os.Process readProcFile(" + s +
                ",int[],java.lang.String[],long[],float[]) @resources 27- optional");
        check("Debug.getMemInfo", "android.os.Debug getMemInfo(long[]) @resources 27- optional");
        for (String name : new String[]{"Process.getTotalMemory", "Process.getFreeMemory", "Process.readProcLines/meminfo",
                "Process.readProcFile/meminfo", "Debug.getMemInfo"})
            assertEquals(name, "android", hook(name).getString("excludePackages"));
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
        // The natives of get(key, default), getInt, getLong and getBoolean for each variant which hooks the getter, in its
        // group and with its settings
        String[][] natives = {{"native_get.default", "get.default", "native_get(" + s + "," + s + ")"},
                {"native_get_int", "getInt", "native_get_int(" + s + ",int)"},
                {"native_get_long", "getLong", "native_get_long(" + s + ",long)"},
                {"native_get_boolean", "getBoolean", "native_get_boolean(" + s + ",boolean)"}};
        int pairs = 0;
        for (String variant : new String[]{"serial", "operator", "device", "timezone", "debugging", "resources"})
            for (String[] n : natives) {
                JSONObject getter = hook("SystemProperties." + n[1] + "/" + variant);
                if (getter == null)
                    continue;
                String name = "SystemProperties." + n[0] + "/" + variant;
                check(name, "android.os.SystemProperties " + n[2] + " @systemproperties_get 27- optional");
                assertEquals(name, getter.getString("group"), hook(name).getString("group"));
                assertEquals(name, getter.optJSONArray("settings") + "", hook(name).optJSONArray("settings") + "");
                assertEquals(name, getter.optBoolean("usage", true), hook(name).optBoolean("usage", true));
                assertEquals(name, getter.optString("excludePackages"), hook(name).optString("excludePackages"));
                pairs++;
            }
        assertEquals(15, pairs);
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
        // And the subtypes in the secure settings, and the languages of the configurations of the resources where Android
        // fills them, like display_panel, for apps only
        check("Settings.Secure.getStringForUser/locale", "android.provider.Settings$Secure getStringForUser(" +
                "android.content.ContentResolver,java.lang.String,int) @settingssecure_getstring 27- optional");
        String config = "android.content.res.Configuration,android.util.DisplayMetrics,android.content.res.CompatibilityInfo";
        check("ResourcesImpl.updateConfiguration/locale", "android.content.res.ResourcesImpl updateConfiguration(" +
                config + ") @locale 27-34 optional");
        check("ResourcesImpl.updateConfigurationImpl/locale", "android.content.res.ResourcesImpl updateConfigurationImpl(" +
                config + ",boolean) @locale 34- optional");
        // And of the changes the app is told of, with the signature of each version (AOSP oreo-mr1 to android11-release,
        // android12-release to android13-release, android14-release to main)
        String callbacks = "(android.content.ComponentCallbacks2,android.content.res.Configuration) @locale ";
        check("ActivityThread.performConfigurationChanged/locale",
                "android.app.ActivityThread performConfigurationChanged" + callbacks + "27-30 optional");
        check("ConfigurationController.performConfigurationChanged/locale",
                "android.app.ConfigurationController performConfigurationChanged" + callbacks + "31- optional");
        String activity = "android.app.ActivityThread performActivityConfigurationChanged(";
        String configs = "android.content.res.Configuration,android.content.res.Configuration,int";
        check("ActivityThread.performActivityConfigurationChanged/locale",
                activity + "android.app.Activity," + configs + ",boolean) @locale 27-30 optional");
        check("ActivityThread.performActivityConfigurationChanged.display/locale",
                activity + "android.app.Activity," + configs + ") @locale 31-33 optional");
        check("ActivityThread.performActivityConfigurationChanged.record/locale",
                activity + "android.app.ActivityThread$ActivityClientRecord," + configs + ",boolean) @locale 34- optional");
        for (String mode : new String[]{"MultiWindow", "PictureInPicture"})
            check("Activity.dispatch" + mode + "ModeChanged/locale", "android.app.Activity dispatch" + mode +
                    "ModeChanged(boolean,android.content.res.Configuration) @locale 27- optional");
        check("Activity.dumpInner/locale", "android.app.Activity dumpInner(java.lang.String,java.io.FileDescriptor," +
                "java.io.PrintWriter,java.lang.String[]) @locale 27- optional");
        for (String name : new String[]{"Settings.Secure.getStringForUser/locale", "ResourcesImpl.updateConfiguration/locale",
                "ResourcesImpl.updateConfigurationImpl/locale", "ActivityThread.performConfigurationChanged/locale",
                "ConfigurationController.performConfigurationChanged/locale",
                "ActivityThread.performActivityConfigurationChanged/locale",
                "ActivityThread.performActivityConfigurationChanged.display/locale",
                "ActivityThread.performActivityConfigurationChanged.record/locale",
                "Activity.dispatchMultiWindowModeChanged/locale", "Activity.dispatchPictureInPictureModeChanged/locale",
                "Activity.dumpInner/locale"}) {
            assertEquals(name, "Read.Locale", hook(name).getString("group"));
            assertEquals(name, "[\"value.locale\"]", hook(name).getJSONArray("settings").toString());
            assertEquals(name, "android", hook(name).getString("excludePackages"));
        }
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
        // No screen lock also by its complexity and by the secure user id, which keystore asks (oreo-mr1 to main)
        check("DevicePolicyManager.getPasswordComplexity",
                "android.app.admin.DevicePolicyManager getPasswordComplexity() @device_details 29-");
        check("GateKeeper.getSecureUserId", "android.security.GateKeeper getSecureUserId() @device_details 27- optional");
        check("GateKeeper.getSecureUserId/user", "android.security.GateKeeper getSecureUserId(int) @device_details 35- optional");
        // No lock also of a user (oreo-mr1 to main, with a device from android15-release) and no intent to confirm it
        String kg = "android.app.KeyguardManager ";
        check("KeyguardManager.isDeviceSecure/user", kg + "isDeviceSecure(int) @device_details 27- optional");
        check("KeyguardManager.isDeviceSecure/device", kg + "isDeviceSecure(int,int) @device_details 35- optional");
        check("KeyguardManager.createConfirmDeviceCredentialIntent", kg + "createConfirmDeviceCredentialIntent(" +
                "java.lang.CharSequence,java.lang.CharSequence) @device_details 27-");
        // Never locked without a secure lock, also of a user and of a device (oreo-mr1 to main, android15-release)
        check("KeyguardManager.isDeviceLocked", kg + "isDeviceLocked() @device_details 27-");
        check("KeyguardManager.isDeviceLocked/user", kg + "isDeviceLocked(int) @device_details 27- optional");
        check("KeyguardManager.isDeviceLocked/device", kg + "isDeviceLocked(int,int) @device_details 35- optional");
        // No biometrics enrolled without a lock: the checks of apps, the ids keystore asks (KeymasterUtils of
        // oreo-mr1 to android10-release, then BiometricManager) and the last authentication
        String bm = "android.hardware.biometrics.BiometricManager ", fm = "android.hardware.fingerprint.FingerprintManager ";
        check("BiometricManager.canAuthenticate/lock/biometric", bm + "canAuthenticate() @device_details 29-");
        check("FingerprintManager.hasEnrolledFingerprints/lock", fm + "hasEnrolledFingerprints() @device_details 27- optional");
        // The hidden overload of a user, which apps of Android 8.1 reach by reflection (oreo-mr1 FingerprintManager)
        check("FingerprintManager.hasEnrolledFingerprints/user/lock",
                fm + "hasEnrolledFingerprints(int) @device_details 27- optional");
        check("FingerprintManager.getEnrolledFingerprints/lock", fm + "getEnrolledFingerprints() @device_details 27- optional");
        check("FingerprintManager.getEnrolledFingerprints/user/lock",
                fm + "getEnrolledFingerprints(int) @device_details 27- optional");
        check("BiometricManager.getAuthenticatorIds/lock", bm + "getAuthenticatorIds() @device_details 30- optional");
        check("BiometricManager.getAuthenticatorIds/user/lock", bm + "getAuthenticatorIds(int) @device_details 31- optional");
        check("FingerprintManager.getAuthenticatorId/lock", fm + "getAuthenticatorId() @device_details 27- optional");
        check("FaceManager.getAuthenticatorId/lock",
                "android.hardware.face.FaceManager getAuthenticatorId() @device_details 29- optional");
        check("BiometricManager.getLastAuthenticationTime/lock", bm + "getLastAuthenticationTime(int) @device_details 35-");
        // The texts of a request of the lock and biometrics, which name the lock only with a lock (android12 to main)
        String strings = "android.hardware.biometrics.BiometricManager$Strings ";
        check("BiometricManager.getStrings/lock", bm + "getStrings(int) @device_details 31-");
        check("BiometricManager.Strings.getButtonLabel/lock", strings + "getButtonLabel() @device_details 31-");
        check("BiometricManager.Strings.getPromptMessage/lock", strings + "getPromptMessage() @device_details 31-");
        for (String name : new String[]{"KeyguardManager.isDeviceSecure/user", "BiometricManager.canAuthenticate/lock/biometric",
                "BiometricManager.getAuthenticatorIds/lock", "FingerprintManager.getAuthenticatorId/lock",
                "KeyguardManager.isDeviceLocked", "FingerprintManager.getEnrolledFingerprints/lock",
                "FingerprintManager.hasEnrolledFingerprints/user/lock", "BiometricManager.getStrings/lock",
                "BiometricManager.Strings.getButtonLabel/lock", "BiometricManager.Strings.getPromptMessage/lock"})
            assertEquals(name, "Use.Tracking", hook(name).getString("group"));
        // The least buffer of a track agrees with the faked frames per buffer
        check("AudioTrack.getMinBufferSize", "android.media.AudioTrack getMinBufferSize(int,int,int) @device_details 27-");
        // The accepted issuers of the platform trust manager agree with the aliases of AndroidCAStore
        check("NetworkSecurityTrustManager.getAcceptedIssuers",
                "android.security.net.config.NetworkSecurityTrustManager getAcceptedIssuers() @device_details 27- optional");
        check("PowerManager.addThermalStatusListener", p + " addThermalStatusListener(" + status + ") @device_details 29-");
        check("PowerManager.addThermalStatusListener/executor", p + " addThermalStatusListener(" + e + status + ") @device_details 29-");
        check("PowerManager.removeThermalStatusListener", p + " removeThermalStatusListener(" + status + ") @device_details 29-");
        check("PowerManager.addThermalHeadroomListener", p + " addThermalHeadroomListener(" + headroom + ") @device_details 36-");
        check("PowerManager.addThermalHeadroomListener/executor", p + " addThermalHeadroomListener(" + e + headroom + ") @device_details 36-");
        check("PowerManager.removeThermalHeadroomListener", p + " removeThermalHeadroomListener(" + headroom + ") @device_details 36-");
        check("SystemVibrator.getInfo", "android.os.SystemVibrator getInfo() @device_details 31- optional");
        assertNull(hook("Vibrator.getInfo"));
        // The alias of a certificate by both overloads, and whether the user added it, also by the public API
        String store = "com.android.org.conscrypt.TrustedCertificateStore ";
        check("TrustedCertificateStore.getCertificateAlias", store +
                "getCertificateAlias(java.security.cert.Certificate) @device_details 27- optional");
        check("TrustedCertificateStore.getCertificateAlias/deleted", store +
                "getCertificateAlias(java.security.cert.Certificate,boolean) @device_details 27- optional");
        check("TrustedCertificateStore.isUserAddedCertificate", store +
                "isUserAddedCertificate(java.security.cert.X509Certificate) @device_details 27- optional");
        check("X509TrustManagerExtensions.isUserAddedCertificate", "android.net.http.X509TrustManagerExtensions " +
                "isUserAddedCertificate(java.security.cert.X509Certificate) @device_details 27-");
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

    // LinkProperties is parcelled, compared and hashed as a copy with what its getters show, also by the hidden
    // comparisons of the addresses, the DNS servers and the routes
    @Test
    public void linkPropertiesParcelledAsShown() throws Exception {
        String lp = "android.net.LinkProperties ";
        check("LinkProperties.writeToParcel", lp + "writeToParcel(android.os.Parcel,int) @network_address 27-");
        check("LinkProperties.equals", lp + "equals(java.lang.Object) @network_address 27-");
        check("LinkProperties.hashCode", lp + "hashCode() @network_address 27-");
        for (String method : new String[]{"isIdenticalAddresses", "isIdenticalDnses", "isIdenticalRoutes"})
            check("LinkProperties." + method, lp + method + "(android.net.LinkProperties) @network_address 27- optional");
        check("LinkProperties.isPrivateDnsActive", lp + "isPrivateDnsActive() @network_address 28-");
        check("LinkProperties.isWakeOnLanSupported", lp + "isWakeOnLanSupported() @network_address 30-");
    }

    // The Wi-Fi info is made again without its address, for its parcel, hashCode and equals
    @Test
    public void wifiInfoWithoutAddress() throws Exception {
        check("WifiManager.getConnectionInfo", "android.net.wifi.WifiManager getConnectionInfo() @network_address 27-");
        check("NetworkCapabilities.getTransportInfo/network",
                "android.net.NetworkCapabilities getTransportInfo() @network_address 31-");
    }

    // The text of socket channels, which has their local address, and the values of an interface by ioctl
    @Test
    public void localAddressesOfChannelsAndInterfaces() throws Exception {
        check("SocketChannelImpl.toString", "sun.nio.ch.SocketChannelImpl toString() @network_address 27- optional");
        check("AsynchronousSocketChannelImpl.toString",
                "sun.nio.ch.AsynchronousSocketChannelImpl toString() @network_address 27- optional");
        check("ForwardingOs.ioctlInetAddress",
                "libcore.io.ForwardingOs ioctlInetAddress(java.io.FileDescriptor,int,java.lang.String) @network_address 27- optional");
    }

    // The NAT64 prefix of the network is null, like it is left out of the text of LinkProperties (network_tostring)
    @Test
    public void nat64PrefixIsNull() throws Exception {
        check("LinkProperties.getNat64Prefix", "android.net.LinkProperties getNat64Prefix() @generic_null_value 29-");
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
        // Keystore asks for the enrolled biometrics, the managers are built from the binders of ServiceManager, and
        // Settings keeps whether fingerprint and face unlock are on
        String biometric = "android.hardware.biometrics.BiometricManager getAuthenticatorIds(";
        check("BiometricManager.getAuthenticatorIds", biometric + ") @biometrics 30- optional");
        check("BiometricManager.getAuthenticatorIds/user", biometric + "int) @biometrics 31- optional");
        for (String method : new String[]{"getService", "checkService"}) {
            check("ServiceManager." + method + "/biometrics", "android.os.ServiceManager " + method + "(" + s +
                    ") @biometrics 27- optional");
            assertEquals(method, "android", hook("ServiceManager." + method + "/biometrics").getString("excludePackages"));
        }
        check("ServiceManager.listServices/biometrics", "android.os.ServiceManager listServices() @biometrics 27- optional");
        assertEquals("java.lang.String[]", hook("ServiceManager.listServices/biometrics").getString("returnType"));
        assertEquals("android", hook("ServiceManager.listServices/biometrics").getString("excludePackages"));
        // The same of the IServiceManager behind it, which lists for a priority from Android 9
        String proxy = "android.os.ServiceManagerProxy ";
        for (String method : new String[]{"getService", "checkService"}) {
            check("ServiceManagerProxy." + method + "/biometrics", proxy + method + "(" + s + ") @biometrics 27- optional");
            assertEquals(method, "android.os.IBinder", hook("ServiceManagerProxy." + method + "/biometrics").getString("returnType"));
        }
        check("ServiceManagerProxy.listServices.priority/biometrics", proxy + "listServices(int) @biometrics 28- optional");
        for (String name : new String[]{"getService", "checkService", "listServices.priority"}) {
            assertEquals(name, "android", hook("ServiceManagerProxy." + name + "/biometrics").getString("excludePackages"));
            if (name.startsWith("listServices"))
                assertEquals(name, "java.lang.String[]", hook("ServiceManagerProxy." + name + "/biometrics").getString("returnType"));
        }
        check("Settings.Secure.getStringForUser/biometrics", "android.provider.Settings$Secure getStringForUser(" +
                "android.content.ContentResolver," + s + ",int) @settingssecure_getstring 27- optional");
        check("ContentResolver.query26/biometrics", "android.content.ContentResolver query(android.net.Uri," + s +
                "[],android.os.Bundle,android.os.CancellationSignal) @settingssecure_getstring 27-");
        check("ContentResolver.call/biometrics", "android.content.ContentResolver call(" + s + "," + s + "," + s +
                ",android.os.Bundle) @settingssecure_getstring 29-");
        check("Settings.System.getStringForUser/adb", "android.provider.Settings$System getStringForUser(" +
                "android.content.ContentResolver," + s + ",int) @settingssecure_getstring 27- optional");
        // Data roaming of the phone process, like the setting data_roaming of the same group
        check("TelephonyManager.isDataRoamingEnabled/settings", "android.telephony.TelephonyManager isDataRoamingEnabled() " +
                "@settingssecure_getstring 29-");
        assertEquals("Read.Settings", hook("TelephonyManager.isDataRoamingEnabled/settings").getString("group"));
        // The copies of the sounds of the settings provider, like the files of the contacts
        String opened = "(android.net.Uri," + s + ",android.os.CancellationSignal) @contentresolver_open 27-";
        String typed = "(android.net.Uri," + s + ",android.os.Bundle,android.os.CancellationSignal) @contentresolver_open ";
        check("ContentResolver.openAssetFileDescriptor/settings", "android.content.ContentResolver openAssetFileDescriptor" + opened);
        check("ContentResolver.openTypedAssetFileDescriptor/settings",
                "android.content.ContentResolver openTypedAssetFileDescriptor" + typed + "27-");
        check("ContentProviderClient.openFile/settings", "android.content.ContentProviderClient openFile" + opened);
        check("ContentProviderClient.openAssetFile/settings", "android.content.ContentProviderClient openAssetFile" + opened);
        check("ContentProviderClient.openTypedAssetFileDescriptor/settings",
                "android.content.ContentProviderClient openTypedAssetFileDescriptor" + typed + "27-28");
        check("ContentProviderClient.openTypedAssetFile/settings",
                "android.content.ContentProviderClient openTypedAssetFile" + typed + "29-");
        for (String method : new String[]{"ContentResolver.openAssetFileDescriptor", "ContentResolver.openTypedAssetFileDescriptor",
                "ContentProviderClient.openFile", "ContentProviderClient.openAssetFile",
                "ContentProviderClient.openTypedAssetFileDescriptor", "ContentProviderClient.openTypedAssetFile"})
            assertEquals(method, "Read.Settings", hook(method + "/settings").getString("group"));
        check("CameraManager2.setTorchMode", "android.hardware.camera2.CameraManager setTorchMode(" + s + ",boolean) @camera2_open 27-");
        check("CameraManager2.turnOnTorchWithStrengthLevel", "android.hardware.camera2.CameraManager turnOnTorchWithStrengthLevel(" +
                s + ",int) @camera2_open 33-");
        check("AudioRecord.startRecording", "android.media.AudioRecord startRecording() @audio_record 27-");
        check("AudioRecord.getRecordingState", "android.media.AudioRecord getRecordingState() @audio_record 27-");
        check("AudioRecord.read/short", "android.media.AudioRecord read(short[],int,int) @audio_record 27-");
        check("AudioRecord.read/float/mode", "android.media.AudioRecord read(float[],int,int,int) @audio_record 27-");
        check("AudioRecord.read/buffer/mode", "android.media.AudioRecord read(java.nio.ByteBuffer,int,int) @audio_record 27-");
        // A recorder starts only once prepared, and stops by its output format, which each group of a source sees,
        // see mediarecorder_startstop
        for (String group : new String[]{"Audio", "Video", "Camera"}) {
            check("MediaRecorder.prepare." + group, "android.media.MediaRecorder prepare() @mediarecorder_startstop 27-");
            check("MediaRecorder.setOutputFormat." + group,
                    "android.media.MediaRecorder setOutputFormat(int) @mediarecorder_startstop 27-");
        }
        // Screen capture gets the result of a declined consent, by the dispatcher of each version, and no projection
        // from a consent which reaches the app otherwise (see mediaprojection_result)
        String result = "android.app.Activity dispatchActivityResult(java.lang.String,int,int,android.content.Intent";
        check("Activity.dispatchActivityResult.reason/projection", result + ",java.lang.String) @mediaprojection_result 28-34 optional");
        check("Activity.internalDispatchActivityResult/projection", "android.app.Activity internalDispatchActivityResult(" +
                "java.lang.String,int,int,android.content.Intent,android.app.ComponentCaller,java.lang.String) " +
                "@mediaprojection_result 34- optional");
        check("MediaProjectionManager.getMediaProjection", "android.media.projection.MediaProjectionManager " +
                "getMediaProjection(int,android.content.Intent) @generic_block 27-");
        for (String name : new String[]{"Activity.dispatchActivityResult.reason/projection",
                "Activity.internalDispatchActivityResult/projection", "MediaProjectionManager.getMediaProjection"})
            assertEquals(name, "Record.Video", hook(name).getString("group"));
        check("AudioManager.getCommunicationDevice", "android.media.AudioManager getCommunicationDevice() @audio_devices 31-");
        // The hidden dispatcher of the device for calls to the listeners of the app
        check("AudioManager.dispatchCommunicationDeviceChanged", "android.media.AudioManager$CommunicationDeviceDispatcherStub " +
                "dispatchCommunicationDeviceChanged(int) @audio_devices 31- optional");
        check("AudioManager.getSupportedDeviceTypes", "android.media.AudioManager getSupportedDeviceTypes(int) @audio_devices 35-");
        check("AudioManager.isWiredHeadsetOn", "android.media.AudioManager isWiredHeadsetOn() @generic_block 27-");
        // The hidden maker of every device list, of the routed devices and of the device callbacks, which run again
        check("AudioManager.infoListFromPortList", "android.media.AudioManager infoListFromPortList(java.util.ArrayList,int) " +
                "@audio_devices 27- optional");
        assertNull(hook("AudioManager.registerAudioDeviceCallback"));
        // The setup of a camera, which Android checks against the real camera list
        check("CameraManager2.isCameraDeviceSetupSupported", "android.hardware.camera2.CameraManager isCameraDeviceSetupSupported(" +
                s + ") @camera_setup 35-");
        check("CameraManager2.getCameraDeviceSetup", "android.hardware.camera2.CameraManager getCameraDeviceSetup(" + s +
                ") @camera_setup 35-");
        String manager = "android.hardware.camera2.CameraManager ";
        check("CameraManager2.isConcurrentSessionConfigurationSupported", manager +
                "isConcurrentSessionConfigurationSupported(java.util.Map) @camera_setup 30-");
        // The hidden opening, characteristics and list of camera2 and the hidden setup of the constructors of Camera,
        // under every hidden and test variant apps reach by reflection, for each signature of Android 8.1 to 17
        String open = manager + "openCameraDeviceUserAsync(" + s + ",android.hardware.camera2.CameraDevice$StateCallback,";
        String executor = open + "java.util.concurrent.Executor,int";
        String compat = "android.content.res.CameraCompatibilityInfo";
        check("CameraManager2.openCameraDeviceUserAsync/uid", executor + ") @camera2_open 28-30 optional");
        check("CameraManager2.openCameraDeviceUserAsync/oom", executor + ",int) @camera2_open 31-35 optional");
        check("CameraManager2.openCameraDeviceUserAsync/flag", executor + ",int,boolean) @camera2_open 34-36 optional");
        check("CameraManager2.openCameraDeviceUserAsync/rotation", executor + ",int,int) @camera2_open 35-35 optional");
        check("CameraManager2.openCameraDeviceUserAsync/compat", executor + "," + compat + ",boolean) @camera2_open 36- optional");
        for (String name : new String[]{"uid", "oom", "flag", "rotation", "compat"})
            assertTrue(name, hook("CameraManager2.openCameraDeviceUserAsync/" + name).getBoolean("notify"));
        check("CameraManager2.getCameraCharacteristics/portrait", manager + "getCameraCharacteristics(" + s +
                ",boolean) @camera2_open 34- optional");
        check("CameraManager2.getCameraIdListNoLazy", manager + "getCameraIdListNoLazy() @generic_empty 30- optional");
        String camera = "android.hardware.Camera ";
        String context = "android.content.Context";
        check("Camera.cameraInitVersion", camera + "cameraInitVersion(int,int) @camera_open 27-30 optional");
        check("Camera.cameraInit", camera + "cameraInit(int) @camera_open 31-34 optional");
        check("Camera.cameraInit/context", camera + "cameraInit(int," + context + ",int) @camera_open 35-36 optional");
        check("Camera.cameraInit/compat", camera + "cameraInit(int," + context + "," + compat + ") @camera_open 36- optional");
        check("Camera.getCameraInfo/context", camera + "getCameraInfo(int," + context +
                ",int,android.hardware.Camera$CameraInfo) @camera_open 35-36 optional");
        check("Camera.getCameraInfo/compat", camera + "getCameraInfo(int," + context + "," + compat +
                ",android.hardware.Camera$CameraInfo) @camera_open 36- optional");
        check("Camera.getNumberOfCameras/context", camera + "getNumberOfCameras(" + context + ") @generic_zero_value 35- optional");
        // The camcorder profiles of a camera by its id
        String profile = "android.media.CamcorderProfile ";
        check("CamcorderProfile.get/camera", profile + "get(int,int) @camcorder_profile 27-");
        check("CamcorderProfile.hasProfile/camera", profile + "hasProfile(int,int) @generic_block 27-");
        check("CamcorderProfile.getAll", profile + "getAll(" + s + ",int) @generic_null_value 31-");
        // The captures of a Visualizer
        String visualizer = "android.media.audiofx.Visualizer ";
        check("Visualizer.getWaveForm", visualizer + "getWaveForm(byte[]) @visualizer 27-");
        check("Visualizer.getFft", visualizer + "getFft(byte[]) @visualizer 27-");
        check("Visualizer.getMeasurementPeakRms", visualizer +
                "getMeasurementPeakRms(android.media.audiofx.Visualizer$MeasurementPeakRms) @visualizer 27-");
        check("Visualizer.postEventFromNative", visualizer +
                "postEventFromNative(java.lang.Object,int,int,int,java.lang.Object) @visualizer 27-29 optional");
        check("Visualizer.postEventFromNative/data", visualizer +
                "postEventFromNative(java.lang.Object,int,int,byte[]) @visualizer 30- optional");
        check("AppEventsLogger.activateApp/companion/app", "com.facebook.appevents.AppEventsLogger$Companion activateApp(" +
                "android.app.Application) @generic_block 27- optional");
        check("Amplitude.getOptOut/android", "com.amplitude.android.Configuration getOptOut() @generic_true_value 27- optional");
        // The SDKs which read their own field or tell plugins opt out when the app opts in, so the field and its other views
        // agree with the hooked getter: the setter of Kotlin Amplitude, the Boolean getter, setOptOut and the events
        // of legacy Amplitude, which answer -1 like when opted out, Branch before and from 5.11
        check("Amplitude.setOptOut", "com.amplitude.core.Amplitude setOptOut(boolean) @generic_true_argument 27- optional");
        String event = "com.amplitude.api.AmplitudeClient logEvent(java.lang.String,org.json.JSONObject,org.json.JSONObject," +
                "org.json.JSONObject,org.json.JSONObject,org.json.JSONObject,long,boolean";
        check("AmplitudeClient.logEvent", event + ") @amplitude_logevent 27- optional");
        check("AmplitudeClient.logEvent/extra", event + ",com.amplitude.api.MiddlewareExtra) @amplitude_logevent 27- optional");
        check("AmplitudeClient.logEvent/foreground", event +
                ",com.amplitude.api.MiddlewareExtra,boolean) @amplitude_logevent 27- optional");
        // Before 2.20 with fewer properties; the return type long keeps the form of 2.0 off the public void logEvent of 2.12
        String old = "com.amplitude.api.AmplitudeClient logEvent(java.lang.String,org.json.JSONObject,org.json.JSONObject,";
        check("AmplitudeClient.logEvent/api", old + "long,boolean) @amplitude_logevent 27- optional");
        check("AmplitudeClient.logEvent/user", old + "org.json.JSONObject,long,boolean) @amplitude_logevent 27- optional");
        check("AmplitudeClient.logEvent/groups", old +
                "org.json.JSONObject,org.json.JSONObject,long,boolean) @amplitude_logevent 27- optional");
        for (String form : new String[]{"", "/extra", "/foreground", "/api", "/user", "/groups"})
            assertEquals(form, "long", hook("AmplitudeClient.logEvent" + form).getString("returnType"));
        check("AmplitudeClient.getOptOut", "com.amplitude.api.AmplitudeClient getOptOut() @generic_true_value 27- optional");
        assertEquals("java.lang.Boolean", hook("AmplitudeClient.getOptOut").getString("returnType"));
        check("AmplitudeClient.setOptOut", "com.amplitude.api.AmplitudeClient setOptOut(boolean) " +
                "@generic_true_argument 27- optional");
        String branch = "io.branch.referral.TrackingController disableTracking(android.content.Context,boolean";
        check("Branch.disableTracking", branch + ") @branch_tracking 27- optional");
        check("Branch.disableTracking/callback", branch +
                ",io.branch.referral.Branch$TrackingStateCallback) @branch_tracking 27- optional");
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
        // And under the lookups and lists of packages, with flags of an int up to Android 12 and a long later
        // (IPackageManager.aidl of android12-release and android13-release)
        for (String[] version : new String[][]{{"", "int", "27-32"}, {".33", "long", "33-"}}) {
            String f = version[1];
            check("IPackageManager.getPackageInfo" + version[0], pm + "getPackageInfo(" + s + "," + f + ",int) @package_probe " +
                    version[2] + " optional");
            check("IPackageManager.getApplicationInfo" + version[0], pm + "getApplicationInfo(" + s + "," + f +
                    ",int) @package_probe " + version[2] + " optional");
            check("IPackageManager.getPackageUid" + version[0], pm + "getPackageUid(" + s + "," + f + ",int) @package_probe " +
                    version[2] + " optional");
            check("IPackageManager.getInstalledPackages" + version[0], pm + "getInstalledPackages(" + f +
                    ",int) @generic_filter_by_uid " + version[2] + " optional");
            check("IPackageManager.getInstalledApplications" + version[0], pm + "getInstalledApplications(" + f +
                    ",int) @generic_filter_by_uid " + version[2] + " optional");
            // The lists of Android 13 and later have no return type: Android 17 returns PackageInfoList, a subclass
            for (String list : new String[]{"getInstalledPackages", "getInstalledApplications"})
                if (version[0].isEmpty())
                    assertEquals(list, "android.content.pm.ParceledListSlice",
                            hook("IPackageManager." + list).getString("returnType"));
                else
                    assertFalse(list, hook("IPackageManager." + list + version[0]).has("returnType"));
        }
        check("IContentService.getSyncAdapterTypes", "android.content.IContentService$Stub$Proxy getSyncAdapterTypes() " +
                "@package_resolve 27- optional");
        check("PackageManager.getPackageInfo/analytics", "android.content.pm.PackageManager getPackageInfo(" + s +
                ",int) @analytics_metadata 27-");
        check("PackageManager.getPackageInfo/flags/analytics", "android.content.pm.PackageManager getPackageInfo(" + s +
                ",android.content.pm.PackageManager$PackageInfoFlags) @analytics_metadata 33-");
        // The other answers with the app's meta-data: the hidden overload under getApplicationInfo, each info read from
        // a parcel and the app's own APK file
        check("PackageManager.getApplicationInfoAsUser/analytics", "android.content.pm.PackageManager getApplicationInfoAsUser(" +
                s + ",int,int) @analytics_metadata 27- optional");
        check("PackageManager.getApplicationInfoAsUser/flags/analytics", "android.content.pm.PackageManager " +
                "getApplicationInfoAsUser(" + s + ",android.content.pm.PackageManager$ApplicationInfoFlags,int) " +
                "@analytics_metadata 33- optional");
        check("ApplicationInfo.createFromParcel/analytics", "android.content.pm.ApplicationInfo " +
                "CREATOR:createFromParcel(android.os.Parcel) @analytics_metadata 27-");
        check("PackageManager.getPackageArchiveInfo/analytics", "android.content.pm.PackageManager getPackageArchiveInfo(" + s +
                ",int) @analytics_metadata 27-");
        check("PackageManager.getPackageArchiveInfo/flags/analytics", "android.content.pm.PackageManager getPackageArchiveInfo(" +
                s + ",android.content.pm.PackageManager$PackageInfoFlags) @analytics_metadata 33-");
        for (String name : new String[]{"ApplicationInfo.createFromParcel/analytics", "PackageManager.getPackageArchiveInfo/analytics"})
            assertEquals(name, "android", hook(name).getString("excludePackages"));
        // The settings of Crashlytics and Facebook stored for the app, which they read before the meta-data
        check("SharedPreferences.getBoolean/analytics", "android.app.SharedPreferencesImpl getBoolean(" + s +
                ",boolean) @analytics_metadata 27-");
        check("SharedPreferences.getString/analytics", "android.app.SharedPreferencesImpl getString(" + s + "," + s +
                ") @analytics_metadata 27-");
        // The consent strings of the IAB a consent tool stored are not there
        String prefs = "android.app.SharedPreferencesImpl ";
        check("SharedPreferences.getString/iab", prefs + "getString(" + s + "," + s + ") @iab_consent 27-");
        check("SharedPreferences.getInt/iab", prefs + "getInt(" + s + ",int) @iab_consent 27-");
        check("SharedPreferences.getBoolean/iab", prefs + "getBoolean(" + s + ",boolean) @iab_consent 27-");
        check("SharedPreferences.getLong/iab", prefs + "getLong(" + s + ",long) @iab_consent 27-");
        check("SharedPreferences.getFloat/iab", prefs + "getFloat(" + s + ",float) @iab_consent 27-");
        check("SharedPreferences.getStringSet/iab", prefs + "getStringSet(" + s + ",java.util.Set) @iab_consent 27-");
        check("SharedPreferences.contains/iab", prefs + "contains(" + s + ") @iab_consent 27-");
        check("SharedPreferences.getAll/iab", prefs + "getAll() @iab_consent 27-");
        check("NetworkCapabilities.getTransportTypes", "android.net.NetworkCapabilities getTransportTypes() @vpn 27- optional");
        for (String name : new String[]{"UserManager.isUserRunning", "UserManager.isUserRunningOrStopping",
                "UserManager.getUserRestrictions", "UserManager.isUserUnlocked", "UserManager.isQuietModeEnabled",
                "ContentResolver.getSyncAdapterTypes", "Context.checkPermission", "IPackageManager.getPackagesForUid",
                "IPackageManager.getNameForUid", "IPackageManager.checkUidSignatures", "IContentService.getSyncAdapterTypes"})
            assertTrue(name + " is not excluded from android", Arrays.asList(hook(name).optString("excludePackages", "")
                    .split(",")).contains("android"));
        assertEquals("Get.Applications", hook("AccountManager.getAuthenticatorTypes/apps").getString("group"));
        // The accounts go with the types of their authenticators, in every list and in the calls which name a type
        String a = "android.accounts.AccountManager ";
        check("AccountManager.getAccounts/apps", a + "getAccounts() @account_authenticators 27-");
        check("AccountManager.getAccountsAsUser/apps", a + "getAccountsAsUser(int) @account_authenticators 27- optional");
        check("AccountManager.getAccountsByType/apps", a + "getAccountsByType(java.lang.String) @account_authenticators 27-");
        check("AccountManager.getAccountsByTypeAsUser/apps", a +
                "getAccountsByTypeAsUser(java.lang.String,android.os.UserHandle) @account_authenticators 27- optional");
        check("AccountManager.getAccountsByTypeForPackage/apps", a +
                "getAccountsByTypeForPackage(java.lang.String,java.lang.String) @account_authenticators 27-");
        check("AccountManager.getAccountsByTypeAndFeatures/apps", a + "getAccountsByTypeAndFeatures(java.lang.String," +
                "java.lang.String[],android.accounts.AccountManagerCallback,android.os.Handler) @account_authenticators 27-");
        check("AccountManager.getAccountsAndVisibilityForPackage/apps", a +
                "getAccountsAndVisibilityForPackage(java.lang.String,java.lang.String) @account_authenticators 27-");
        for (JSONObject hook : hooks)
            if ("@account_authenticators".equals(hook.getString("luaScript")))
                assertEquals(hook.getString("name"), "Get.Applications", hook.getString("group"));
        // The account types are left out only with the apps they tell are installed, not by Read.Account alone
        for (JSONObject hook : hooks)
            if ("getAuthenticatorTypes".equals(hook.optString("methodName")))
                assertEquals(hook.getString("name"), "Get.Applications", hook.getString("group"));
    }
}
