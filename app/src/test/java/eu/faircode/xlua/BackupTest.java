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
import android.content.ContextWrapper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

// What a backup of an older version restores into the definitions of this version (Backup.renamed, Backup.completing)
public class BackupTest {
    private static final String PREFIX = Util.COLLECTION_DEFAULT + ".";
    private static final List<String> COLLECTION = Collections.singletonList(Util.COLLECTION_DEFAULT);

    // Renamed definitions get the restrictions of their former name, as the database upgrade gives them
    @Test
    public void renamedDefinitionsGetTheirNameNow() {
        assertEquals(PREFIX + "TelephonyManager.getSimOperator", Backup.renamed(PREFIX + "TelephonyManager/getSimOperator"));
        for (String[] rename : XDatabase.RENAMED) {
            String now = Backup.renamed(PREFIX + rename[0]);
            assertFalse(rename[0], now.equals(PREFIX + rename[0]));
            // Fully renamed, also when a name was renamed again later
            assertEquals(rename[0], now, Backup.renamed(now));
        }
    }

    // Other definitions keep their id, like custom ones of another collection
    @Test
    public void otherDefinitionsKeepTheirId() {
        assertEquals(PREFIX + "Location.createFromParcel", Backup.renamed(PREFIX + "Location.createFromParcel"));
        assertEquals("Custom.TelephonyManager/getSimOperator", Backup.renamed("Custom.TelephonyManager/getSimOperator"));
    }

    // Definitions added since the backup complete the groups it restricted, if the app can have them
    @Test
    public void newerDefinitionsCompleteRestrictedGroups() throws Exception {
        Map<String, XHook> hooks = new HashMap<>();
        for (XHook hook : Arrays.asList(
                hook("Read.Identifiers", "Old.identifier", null, true),
                hook("Read.Identifiers", "New.identifier", null, true),
                hook("Read.Identifiers", "New.disabled", null, false),
                hook("Read.Identifiers", "New.excluded", "com\\\\.example\\\\.app", true),
                hook("Get.Location", "New.location", null, true)))
            hooks.put(hook.getId(), hook);
        List<String> newer = Arrays.asList(PREFIX + "New.identifier", PREFIX + "New.disabled",
                PREFIX + "New.excluded", PREFIX + "New.location", PREFIX + "New.unknown");
        Set<String> groups = new HashSet<>(Collections.singletonList("Read.Identifiers"));

        List<String> completing = Backup.completing(newer, groups, hooks, "com.example.app", COLLECTION);
        assertEquals(Collections.singletonList(PREFIX + "New.identifier"), completing);

        // Nothing for an app without restricted groups, nothing of a collection which is not enabled
        assertTrue(Backup.completing(newer, new HashSet<>(), hooks, "com.example.app", COLLECTION).isEmpty());
        assertTrue(Backup.completing(newer, groups, hooks, "com.example.app",
                Collections.singletonList("Other")).isEmpty());
    }

    // Definitions which leave out fewer packages or moved since the backup complete the groups it restricted, told
    // without the moved ones, like the update did, the ones leaving out fewer packages only for the apps they left out
    // before, as the others had them, unless their user switched them off; a backup of this version lists them as
    // moved already, so it gets none, and one of 2.1 to 2.3, which lists the built-in definitions only, was made after
    // the moves to another group, so it gets only the ones leaving out fewer packages
    @Test
    public void movedDefinitionsCompleteRestrictedGroups() throws Exception {
        String now = "com\\\\.google\\\\.android\\\\.gms,com\\\\.google\\\\.android\\\\.gsf";
        Map<String, XHook> hooks = new HashMap<>();
        for (XHook hook : Arrays.asList(
                hook("Read.Identifiers", "Settings.Secure.getString/android_id", null, true),
                hook("Read.Identifiers", "ContentResolver.query26/gsf_id", now, true),
                hook("Read.Identifiers", "ContentProviderClient.query26/gsf_id", now, true),
                hook("Use.Tracking", "Build.MODEL", null, true),
                hook("Use.Tracking", "SubscriptionInfo.getMcc", null, true),
                hook("Use.Tracking", "SubscriptionInfo.getMnc", null, true)))
            hooks.put(hook.getId(), hook);
        List<String> ids = Collections.singletonList(PREFIX + "Settings.Secure.getString/android_id");
        Set<String> gsf = new HashSet<>(Arrays.asList(PREFIX + "ContentResolver.query26/gsf_id",
                PREFIX + "ContentProviderClient.query26/gsf_id"));
        // Use.Tracking restricted with SubscriptionInfo.getMcc switched off
        List<String> tracking = Arrays.asList(PREFIX + "Build.MODEL", PREFIX + "SubscriptionInfo.getMnc");
        JSONObject v20 = new JSONObject();
        JSONObject v23 = new JSONObject().put("builtins", new JSONArray());
        JSONObject current = new JSONObject().put("builtins", new JSONArray()).put("moved", Backup.moved());

        // A backup of before the list, like of YouTube and the Play Store when these left them out, but not of an
        // app they did not leave out
        for (JSONObject jroot : new JSONObject[]{v20, v23}) {
            for (String pkg : new String[]{"com.google.android.youtube", "com.android.vending"})
                assertEquals(pkg, gsf, new HashSet<>(Backup.completingMoved(jroot, ids, hooks, pkg, COLLECTION)));
            for (String pkg : new String[]{"com.google.android.gms", "com.example.app"})
                assertTrue(pkg, Backup.completingMoved(jroot, ids, hooks, pkg, COLLECTION).isEmpty());
        }

        // A hook moved to another group comes with a backup of 2.0, which was made before the move, but not of 2.1 to
        // 2.3, which were made after it, so its user switched it off
        assertEquals(new HashSet<>(Arrays.asList(PREFIX + "SubscriptionInfo.getMcc", PREFIX + "SubscriptionInfo.getMnc")),
                new HashSet<>(Backup.completingMoved(v20, tracking, hooks, "com.example.app", COLLECTION)));
        assertTrue(Backup.completingMoved(v23, tracking, hooks, "com.example.app", COLLECTION).isEmpty());
        assertEquals(Arrays.asList("Build.getRadioVersion", "MediaDrm.getPropertyByteArray", "SubscriptionInfo.getMcc",
                "SubscriptionInfo.getMccString", "SubscriptionInfo.getMnc", "SubscriptionInfo.getMncString",
                "SubscriptionInfo.toString/plmn"), firstNames(XDatabase.MOVED, XDatabase.MOVED_UNLISTED));

        // Restricting a moved hook in its former group does not restrict the group it is in now
        assertTrue(Backup.completingMoved(v20, Collections.singletonList(PREFIX + "SubscriptionInfo.getMcc"), hooks,
                "com.example.app", COLLECTION).isEmpty());

        // A backup of this version lists the moved hooks it knows, so it gets none of them
        JSONArray jmoved = Backup.moved();
        Set<String> listed = new HashSet<>();
        for (int i = 0; i < jmoved.length(); i++)
            listed.add(jmoved.getString(i));
        assertEquals(XDatabase.MOVED.length, listed.size());
        assertTrue(listed.containsAll(gsf));
        assertTrue(Backup.completingMoved(current, ids, hooks, "com.google.android.youtube", COLLECTION).isEmpty());
        assertTrue(Backup.completingMoved(current, tracking, hooks, "com.example.app", COLLECTION).isEmpty());
    }

    private static List<String> firstNames(String[][] rows, int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++)
            names.add(rows[i][0]);
        return names;
    }

    // A backup lists the built-in definitions for its Android version only, so a restore on a newer Android version
    // completes the restricted groups with the hooks of that version. A minSdk above the SDK version (0 in unit tests)
    // stands for a hook of a newer Android version.
    @Test
    public void backupListsTheBuiltinsOfItsAndroidVersion() throws Exception {
        XHook now = hook("Get.Applications", "Now", null, true);
        XHook newer = XHook.fromJSON(now.toJSON().replace("\"Now\"", "\"Newer\"").replace("\"minSdk\": 0", "\"minSdk\": " + (android.os.Build.VERSION.SDK_INT + 1)));
        // A disabled hook is not listed either, so a restore by a version which enables it adds it
        XHook disabled = hook("Get.Applications", "Disabled", null, false);
        List<String> listed = Backup.builtinsForThisSdk(Arrays.asList(now.getId(), newer.getId(), disabled.getId()),
                Arrays.asList(now, newer, disabled));
        assertEquals(Collections.singletonList(now.getId()), listed);
    }

    // A backup can come from someone else: what it does beyond setting values is asked before the restore
    @Test
    public void previewNamesReplacedDefinitionsAndRestrictionsOfAndroid() throws Exception {
        Set<String> builtins = Collections.singleton(PREFIX + "Build.MODEL");
        Backup.Preview preview = Backup.preview(new JSONObject("{\"hooks\": [" +
                "{\"collection\": \"" + Util.COLLECTION_DEFAULT + "\", \"name\": \"Build.MODEL\"}," +
                "{\"collection\": \"Custom\", \"name\": \"Build.MODEL\"}, 1]," +
                "\"restrictions\": [{\"package\": \"com.example\"}, {\"package\": \"android\"}, 2]}"), builtins);
        assertEquals(Collections.singletonList(PREFIX + "Build.MODEL"), preview.replaced);
        // A definition with a new id can run its script in the apps the backup restricts with it
        assertEquals(Collections.singletonList("Custom.Build.MODEL"), preview.added);
        assertTrue(preview.android);

        preview = Backup.preview(new JSONObject("{\"restrictions\": [{\"package\": \"com.example\"}]}"), builtins);
        assertTrue(preview.replaced.isEmpty());
        assertTrue(preview.added.isEmpty());
        assertFalse(preview.android);
        assertTrue(Backup.preview(new JSONObject("{}"), builtins).replaced.isEmpty());
    }

    // An import asks about a custom definition it would replace, also one which replaces a built-in definition itself
    @Test
    public void previewOfAnImportNamesTheCustomDefinitionsItReplaces() throws Exception {
        Set<String> builtins = new HashSet<>(Arrays.asList(PREFIX + "Build.MODEL", PREFIX + "Build.BRAND"));
        Set<String> custom = new HashSet<>(Arrays.asList(PREFIX + "Build.BRAND", "Custom.mine"));
        Backup.Preview preview = Backup.preview(new JSONArray("[" +
                "{\"collection\": \"" + Util.COLLECTION_DEFAULT + "\", \"name\": \"Build.MODEL\"}," +
                "{\"collection\": \"" + Util.COLLECTION_DEFAULT + "\", \"name\": \"Build.BRAND\"}," +
                "{\"collection\": \"Custom\", \"name\": \"mine\"}," +
                "{\"collection\": \"Custom\", \"name\": \"new\"}]"), builtins, custom);
        assertEquals(Collections.singletonList(PREFIX + "Build.MODEL"), preview.replaced);
        assertEquals(Arrays.asList(PREFIX + "Build.BRAND", "Custom.mine"), preview.existing);
        assertEquals(Collections.singletonList("Custom.new"), preview.added);
    }

    // A file which is cut off, not JSON, or not of this format is not restored, whatever it has: the user is told so
    @Test
    public void filesWhichAreNoBackupAreRefused() {
        Context context = new ContextWrapper(null);
        String valid = "{\"format\": 1, \"version\": 203, \"settings\": [], \"hooks\": [], \"restrictions\": []}";
        assertEquals(203, Backup.parse(context, valid).optInt("version"));
        String[] refused = {
                valid.substring(0, valid.length() - 1), // Truncated
                valid.substring(0, 30),
                "", "not json", "[1, 2]", "{}", "{\"settings\": []}", // No format: exported definitions have a similar name
                "{\"format\": 2}", "{\"format\": \"x\"}",
                "{\"format\": 1, \"x\": " + "[".repeat(100000), // Nested deeper than the parser reads
        };
        for (String text : refused)
            assertThrows(text.length() > 40 ? "Long" : text, Backup.FileException.class, () -> Backup.parse(context, text));
    }

    // What cannot be read is found before anything is written and left out, counted: settings without a name, with
    // a value which is no text or too long for the service, definitions which do not compile, restrictions of no app
    @Test
    public void whatCannotBeReadIsLeftOut() throws Exception {
        String longest = "x".repeat(Backup.MAX_VALUE);
        JSONObject jroot = Backup.parse(new ContextWrapper(null), "{\"format\": 1," +
                " \"settings\": [" +
                "{\"category\": \"global\", \"name\": \"ok\", \"value\": \"1\"}," +
                "{\"category\": \"global\", \"name\": \"longest\", \"value\": \"" + longest + "\"}," +
                "{\"category\": \"global\", \"name\": \"oversized\", \"value\": \"" + longest + "y\"}," +
                "{\"category\": \"global\", \"name\": \"" + "n".repeat(Backup.MAX_NAME + 1) + "\", \"value\": \"1\"}," +
                "{\"category\": \"global\", \"name\": \"deleted\", \"value\": null}," +
                "{\"category\": null, \"name\": \"nocategory\"}, {\"name\": \"x\"}, 42]," +
                " \"hooks\": [" + jhook("Good", "function after(hook, param) return false end") + "," +
                jhook("Broken", "function after(hook, param") + ", 7]," +
                " \"restrictions\": [{\"package\": \"a.app\", \"hooks\": [\"Privacy.One\", \"Privacy.Two\"]}," +
                "{\"hooks\": []}, {\"package\": \"b.app\"}, 3]}");

        Backup.Result result = new Backup.Result();
        Backup.Plan plan = Backup.plan(jroot, result);

        List<String> names = new ArrayList<>();
        for (String[] setting : plan.settings)
            names.add(setting[1]);
        assertEquals(Arrays.asList("ok", "longest", "deleted"), names);
        assertNull(plan.settings.get(2)[2]);
        // Of the settings the oversized value, the long name, no category, no name and a number; of the restrictions
        // the one without a package, the one without definitions and a number
        assertEquals(5 + 3, result.failed);
        assertEquals(1, plan.hooks.size());
        assertEquals(2, result.hooksFailed);
        assertTrue(plan.hasRestrictions);
        assertEquals(1, plan.restrictions.size());
        assertEquals(Arrays.asList("Privacy.One", "Privacy.Two"), plan.restrictions.get(0).ids);
        assertNull(plan.builtins);
    }

    // The restore of a backup: the state of the service before it, kept in memory
    private static final String CUSTOM = "Custom.";

    private static String jhook(String name, String script) {
        return "{\"collection\": \"Custom\", \"group\": \"Test\", \"name\": \"" + name + "\", \"author\": \"Test\"," +
                " \"className\": \"android.location.Location\", \"parameterTypes\": [], \"minSdk\": 0," +
                " \"luaScript\": \"" + script + "\"}";
    }

    // The service of the tests: definitions, settings and assignments in memory, which fails when told to
    private static class FakeService implements Backup.Service {
        final Map<String, XHook> builtins = new TreeMap<>();
        final Map<String, JSONObject> custom = new TreeMap<>();
        final Map<String, String> settings = new TreeMap<>();
        final Map<String, Set<String>> assignments = new TreeMap<>(); // By package
        final Map<String, Integer> uids = new HashMap<>();
        int settingCalls = 0;
        int failSettingCall = -1; // The call of putSettings which fails, from 0
        String failAssign = null; // The package which cannot be assigned
        boolean failPutBack = false; // Deleting a definition and an assignment fails, as the service does when it is gone

        FakeService() throws Exception {
            for (XHook hook : Arrays.asList(hook("Get.Location", "One", null, true), hook("Get.Location", "Two", null, true),
                    hook("Get.Location", "Three", null, true), hook("Use.Tracking", "Four", null, true)))
                builtins.put(hook.getId(), hook);
        }

        // The definitions with their fields in the order of their names, which a JSON object does not keep
        String state() {
            Map<String, Map<String, String>> definitions = new TreeMap<>();
            for (Map.Entry<String, JSONObject> entry : custom.entrySet()) {
                Map<String, String> fields = new TreeMap<>();
                JSONObject jhook = entry.getValue();
                for (java.util.Iterator<String> it = jhook.keys(); it.hasNext(); ) {
                    String key = it.next();
                    fields.put(key, String.valueOf(jhook.opt(key)));
                }
                definitions.put(entry.getKey(), fields);
            }
            return definitions + "|" + settings + "|" + assignments;
        }

        @Override
        public Set<String> builtinIds() {
            return new HashSet<>(builtins.keySet());
        }

        @Override
        public JSONArray customHooks() throws JSONException {
            JSONArray result = new JSONArray();
            for (JSONObject jhook : custom.values())
                result.put(new JSONObject(jhook.toString()));
            return result;
        }

        @Override
        public List<String[]> settings() {
            List<String[]> result = new ArrayList<>();
            for (Map.Entry<String, String> entry : settings.entrySet())
                result.add(new String[]{entry.getKey().split("\n")[0], entry.getKey().split("\n")[1], entry.getValue()});
            return result;
        }

        @Override
        public List<XHook> allHooks() throws Exception {
            Map<String, XHook> all = new HashMap<>(builtins);
            for (JSONObject jhook : custom.values()) {
                XHook hook = XHook.fromJSONObject(new JSONObject(jhook.toString()));
                all.put(hook.getId(), hook);
            }
            return new ArrayList<>(all.values());
        }

        @Override
        public List<String> collection() {
            return Collections.singletonList(Util.COLLECTION_DEFAULT);
        }

        @Override
        public XHook putCustom(JSONObject jhook) throws Exception {
            JSONObject copy = new JSONObject(jhook.toString());
            copy.remove("builtin");
            XHook hook = XHook.fromJSONObject(new JSONObject(copy.toString()));
            hook.validate();
            custom.put(hook.getId(), copy);
            return hook;
        }

        @Override
        public void putHook(String id, String definition) {
            if (failPutBack)
                throw new IllegalStateException("No answer");
            if (custom.remove(id) == null)
                throw new IllegalArgumentException("builtin");
        }

        @Override
        public void putSettings(List<String[]> list) {
            int call = settingCalls++;
            if (call == failSettingCall)
                throw new IllegalStateException("No answer");
            for (String[] setting : list)
                if (setting[2] == null)
                    settings.remove(setting[0] + "\n" + setting[1]);
                else
                    settings.put(setting[0] + "\n" + setting[1], setting[2]);
        }

        @Override
        public Integer uid(String packageName) {
            return uids.get(packageName);
        }

        @Override
        public XApp app(String packageName, int uid) {
            XApp app = new XApp();
            app.packageName = packageName;
            app.uid = uid;
            app.forceStop = true;
            app.assignments = new ArrayList<>();
            for (String id : assignments.getOrDefault(packageName, Collections.emptySet()))
                app.assignments.add(new XAssignment(builtins.get(id)));
            return app;
        }

        @Override
        public boolean inSystemServerOnly(String packageName) {
            return false;
        }

        @Override
        public int assign(ArrayList<String> ids, String packageName, int uid, boolean delete, boolean kill) {
            if (!delete && packageName.equals(failAssign))
                throw new IllegalStateException("No answer");
            if (delete && failPutBack)
                throw new IllegalStateException("No answer");
            Set<String> have = assignments.computeIfAbsent(packageName, p -> new TreeSet<>());
            int changed = 0;
            for (String id : ids)
                if (delete ? have.remove(id) : (builtins.containsKey(id) && have.add(id)))
                    changed++;
            if (have.isEmpty())
                assignments.remove(packageName);
            return changed;
        }
    }

    private static final String BACKUP = "{\"format\": 1, \"version\": 203," +
            " \"settings\": [{\"category\": \"global\", \"name\": \"restrict_new_apps\", \"value\": \"true\"}," +
            "{\"category\": \"a.app\", \"name\": \"notify\", \"value\": \"true\"}]," +
            " \"hooks\": [" + jhook("Mine", "function after(hook, param) return false end") + "," +
            jhook("Was", "function after(hook, param) return true end") + "]," +
            " \"restrictions\": [{\"package\": \"a.app\", \"hooks\": [\"Privacy.One\", \"Privacy.Two\"]}," +
            "{\"package\": \"b.app\", \"hooks\": [\"Privacy.Three\"]}," +
            "{\"package\": \"gone.app\", \"hooks\": [\"Privacy.One\"]}]}";

    // A service with something of the user in it, which a restore replaces and adds to
    private static FakeService service() throws Exception {
        FakeService service = new FakeService();
        service.uids.put("a.app", 10101);
        service.uids.put("b.app", 10102);
        service.settings.put("global\nrestrict_new_apps", "false");
        service.settings.put("global\ntheme", "dark");
        service.custom.put(CUSTOM + "Was", new JSONObject(jhook("Was", "function after(hook, param) return nil end")));
        service.assignments.put("a.app", new TreeSet<>(Collections.singletonList("Privacy.Four")));
        return service;
    }

    @Test
    public void restoreAppliesWhatTheFileHas() throws Throwable {
        FakeService service = service();
        Context context = new ContextWrapper(null);
        Backup.Result result = Backup.restore(context, service, Backup.parse(context, BACKUP));

        assertEquals(2, result.hooks);
        assertEquals(2, result.settings);
        assertEquals(2, result.apps);
        assertEquals(3, result.added);
        assertEquals(Collections.singletonList("gone.app"), result.missing);
        assertEquals("true", service.settings.get("global\nrestrict_new_apps"));
        assertEquals("dark", service.settings.get("global\ntheme"));
        assertEquals(new TreeSet<>(Arrays.asList("Privacy.Four", "Privacy.One", "Privacy.Two")), service.assignments.get("a.app"));
        assertEquals(new TreeSet<>(Collections.singletonList("Privacy.Three")), service.assignments.get("b.app"));
        assertEquals(new TreeSet<>(Arrays.asList(CUSTOM + "Mine", CUSTOM + "Was")), service.custom.keySet());
        assertTrue(service.custom.get(CUSTOM + "Was").toString().contains("return true"));
    }

    // A restore which fails halfway, in any of its steps, leaves the state it found: the definitions which are live, the
    // settings which are replaced and the restrictions of the apps before the one which fails are put back
    @Test
    public void restoreWhichFailsHalfwayPutsBackWhatItDid() throws Throwable {
        Context context = new ContextWrapper(null);
        for (int step = 0; step < 3; step++) {
            FakeService service = service();
            String before = service.state();
            if (step == 0)
                service.failSettingCall = 0; // After the definitions
            else if (step == 1)
                service.failAssign = "a.app"; // The first app, after the definitions and settings
            else
                service.failAssign = "b.app"; // The second, after the first
            Backup.RestoreException ex = assertThrows("Step " + step, Backup.RestoreException.class,
                    () -> Backup.restore(context, service, Backup.parse(context, BACKUP)));
            assertTrue(ex.getCause().toString(), ex.getCause() instanceof IllegalStateException);
            assertEquals("Step " + step, before, service.state());
        }
    }

    // Settings go in parts, of which the later ones can fail after the earlier ones were written
    @Test
    public void restoreWhichFailsAfterSomeSettingsPutsThemBack() throws Throwable {
        Context context = new ContextWrapper(null);
        StringBuilder settings = new StringBuilder();
        for (int i = 0; i < 2 * Backup.CHUNK_SETTINGS + 1; i++)
            settings.append(i == 0 ? "" : ",").append("{\"category\": \"global\", \"name\": \"n").append(i)
                    .append("\", \"value\": \"v\"}");
        String backup = "{\"format\": 1, \"settings\": [" + settings +
                ",{\"category\": \"global\", \"name\": \"theme\", \"value\": \"light\"}]}";

        FakeService service = service();
        String before = service.state();
        service.failSettingCall = 2; // The third part
        assertThrows(Backup.RestoreException.class, () -> Backup.restore(context, service, Backup.parse(context, backup)));
        assertEquals(before, service.state());
        // Three parts, of which the third failed, and three which put them back
        assertEquals(6, service.settingCalls);

        // Without the failure all of them are written, in parts
        FakeService fine = service();
        Backup.Result result = Backup.restore(context, fine, Backup.parse(context, backup));
        assertEquals(2 * Backup.CHUNK_SETTINGS + 2, result.settings);
        assertEquals("light", fine.settings.get("global\ntheme"));
        assertEquals(3, fine.settingCalls);
    }

    // What cannot be put back is told, and the rest is put back all the same
    @Test
    public void restoreTellsWhatItCouldNotPutBack() throws Throwable {
        Context context = new ContextWrapper(null);
        FakeService service = service();
        String settings = service.settings.toString();
        service.failAssign = "b.app";
        service.failPutBack = true; // The assignments of b.app and a.app and the definition Mine cannot be put back
        Backup.RestoreException ex = assertThrows(Backup.RestoreException.class,
                () -> Backup.restore(context, service, Backup.parse(context, BACKUP)));
        assertNotNull(ex.getCause());
        assertEquals(3, ex.notUndone);
        // The others are put back: the settings and the definition of the user, not the ones which cannot
        assertEquals(settings, service.settings.toString());
        assertTrue(service.custom.get(CUSTOM + "Was").toString().contains("return nil"));
        assertTrue(service.custom.containsKey(CUSTOM + "Mine"));
        assertEquals(new TreeSet<>(Arrays.asList("Privacy.Four", "Privacy.One", "Privacy.Two")), service.assignments.get("a.app"));
    }

    // The steps of putting back run from the last to the first, and one which fails does not keep the others
    @Test
    public void stepsAreUndoneInReverseAndOneFailureDoesNotStopTheRest() {
        List<Integer> done = new ArrayList<>();
        Deque<Backup.Undo> steps = new ArrayDeque<>();
        steps.push(() -> done.add(1));
        steps.push(() -> {
            throw new IllegalStateException("No answer");
        });
        steps.push(() -> done.add(3));
        assertEquals(1, Backup.undo(steps));
        assertEquals(Arrays.asList(3, 1), done);
        assertTrue(steps.isEmpty());
        assertEquals(0, Backup.undo(steps));
    }

    // Settings go to the service in parts of a count and a size which fit a call
    @Test
    public void settingsGoInParts() {
        List<String[]> settings = new ArrayList<>();
        for (int i = 0; i < 250; i++)
            settings.add(new String[]{"global", "n" + i, "v"});
        List<List<String[]>> chunks = Backup.chunks(settings);
        assertEquals(Arrays.asList(100, 100, 50), Arrays.asList(chunks.get(0).size(), chunks.get(1).size(), chunks.get(2).size()));
        assertEquals(3, chunks.size());

        // By size: each of the large ones, which the service takes one at a time
        List<String[]> large = new ArrayList<>();
        for (int i = 0; i < 5; i++)
            large.add(new String[]{"global", "n" + i, "x".repeat(Backup.MAX_VALUE / 2 + 1)});
        List<List<String[]>> parts = Backup.chunks(large);
        assertEquals(5, parts.size());
        assertTrue(Backup.chunks(new ArrayList<>()).isEmpty());
    }

    // Unit tests run with an SDK version of 0, see the android.jar stubs, so the definitions are for all versions
    private static XHook hook(String group, String name, String exclude, boolean enabled) throws Exception {
        return XHook.fromJSON("{\"collection\": \"" + Util.COLLECTION_DEFAULT + "\", \"group\": \"" + group + "\"," +
                " \"name\": \"" + name + "\", \"author\": \"Test\", \"className\": \"android.location.Location\"," +
                " \"parameterTypes\": [], \"minSdk\": 0, \"enabled\": " + enabled + "," +
                (exclude == null ? "" : " \"excludePackages\": \"" + exclude + "\",") +
                " \"luaScript\": \"\"}");
    }
}
