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

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.Prototype;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// Validates the built-in hook definitions (assets/hooks.json) and their Lua scripts
public class HookDefinitionsTest {
    private static final File ASSETS = new File("src/main/assets");
    private static final File STRINGS = new File("src/main/res/values/strings.xml");

    // Hidden framework classes, which are not part of the SDK
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "android.os.SystemProperties"));

    private static List<JSONObject> hooks;

    @BeforeClass
    public static void load() throws Exception {
        String json = read(new File(ASSETS, "hooks.json"));
        // Android's org.json accepts comments, the reference implementation does not
        json = json.replaceFirst("(?s)^\\s*/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
        JSONArray jarray = new JSONArray(json);
        hooks = new ArrayList<>();
        for (int i = 0; i < jarray.length(); i++)
            hooks.add(jarray.getJSONObject(i));
        assertTrue(hooks.size() > 0);
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
    public void requiredFieldsPresent() throws Exception {
        for (JSONObject hook : hooks)
            for (String key : new String[]{
                    "collection", "group", "name", "author", "className", "parameterTypes", "minSdk", "luaScript"})
                assertTrue(hook.optString("name") + " misses " + key, hook.has(key));
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

            Globals globals = JsePlatform.standardGlobals();
            Prototype prototype = LuaC.instance.compile(
                    new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "script");
            new LuaClosure(prototype, globals).call();
            assertTrue("No before or after function for " + hook.getString("name"),
                    globals.get("before").isfunction() || globals.get("after").isfunction());
        }

        // Unreferenced scripts are dead code
        File[] files = ASSETS.listFiles((dir, name) -> name.endsWith(".lua"));
        for (File file : files)
            assertTrue("Unreferenced script " + file.getName(), referenced.contains(file.getName()));
    }

    // Framework hooks must exist in the SDK the app is compiled against,
    // unless they are optional, hidden or limited to older versions
    @Test
    public void frameworkMembersExist() throws Exception {
        int sdk = compileSdk();
        int checked = 0;
        List<String> errors = new ArrayList<>();
        for (JSONObject hook : hooks) {
            String className = hook.getString("className");
            if (!className.startsWith("android.") || HIDDEN.contains(className))
                continue;
            if (hook.optBoolean("optional", false))
                continue;
            if (hook.getInt("minSdk") > sdk || hook.optInt("maxSdk", 999) < sdk)
                continue;

            String name = hook.getString("name");
            checked++;
            try {
                Class<?> cls = Class.forName(className, false, getClass().getClassLoader());

                JSONArray jparams = hook.getJSONArray("parameterTypes");
                Class<?>[] params = new Class<?>[jparams.length()];
                for (int i = 0; i < params.length; i++)
                    params[i] = resolveClass(jparams.getString(i));

                String methodName = hook.optString("methodName", null);
                if (methodName == null)
                    cls.getDeclaredConstructor(params);
                else if (methodName.startsWith("#"))
                    findField(cls, methodName.substring(1));
                else if (methodName.contains(":"))
                    findField(cls, methodName.split(":")[0]);
                else
                    findMethod(cls, methodName, params);
            } catch (Throwable ex) {
                errors.add(name + ": " + ex);
            }
        }
        assertTrue("Only " + checked + " hooks checked against SDK " + sdk, sdk >= 36 && checked > 100);
        if (!errors.isEmpty())
            fail(errors.size() + " hooks do not resolve against SDK " + sdk + ":\n" + String.join("\n", errors));
    }

    private static void findMethod(Class<?> cls, String name, Class<?>[] params) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass())
            try {
                c.getDeclaredMethod(name, params);
                return;
            } catch (NoSuchMethodException ignored) {
            }
        throw new NoSuchMethodException(cls.getName() + "." + name + Arrays.toString(params));
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass())
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        throw new NoSuchFieldException(cls.getName() + "." + name);
    }

    // Same type names as XLua.resolveClass
    private static Class<?> resolveClass(String name) throws ClassNotFoundException {
        switch (name) {
            case "boolean":
                return boolean.class;
            case "byte":
                return byte.class;
            case "char":
                return char.class;
            case "short":
                return short.class;
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            case "byte[]":
                return byte[].class;
            case "int[]":
                return int[].class;
            case "long[]":
                return long[].class;
            default:
                return Class.forName(name, false, HookDefinitionsTest.class.getClassLoader());
        }
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

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void readerMatchesAndroidRuntime() throws Exception {
        // Sanity check of the comment stripping: all hooks belong to a collection
        for (JSONObject hook : hooks)
            assertEquals("Privacy", hook.getString("collection"));
    }
}
