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
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    // Unit tests run with an SDK version of 0, see the android.jar stubs, so the definitions are for all versions
    private static XHook hook(String group, String name, String exclude, boolean enabled) throws Exception {
        return XHook.fromJSON("{\"collection\": \"" + Util.COLLECTION_DEFAULT + "\", \"group\": \"" + group + "\"," +
                " \"name\": \"" + name + "\", \"author\": \"Test\", \"className\": \"android.location.Location\"," +
                " \"parameterTypes\": [], \"minSdk\": 0, \"enabled\": " + enabled + "," +
                (exclude == null ? "" : " \"excludePackages\": \"" + exclude + "\",") +
                " \"luaScript\": \"\"}");
    }
}
