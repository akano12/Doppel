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

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// What hooked apps may ask the service without the permission of the app, see XProvider.APP_CALLS
public class XProviderTest {
    // A method added here can give any hooked app the data of other apps, like getAllSettings the fake values
    // set for each app: it must check the caller itself, like the ones listed do
    @Test
    public void hookedAppsAskForThemselvesOnly() {
        assertEquals(Collections.singleton("reports"), XProvider.APP_CALLS);
        assertEquals(new HashSet<>(Arrays.asList("getAssignedHooks2", "getSettings", "getBootValues")),
                XProvider.APP_QUERIES);
    }

    // The app of a secondary user acts for its own user only; system server, like ReceiverPackage, for any user
    @Test
    public void appActsForItsOwnUserOnly() {
        int owner = Util.getUserUid(0, 10123);
        int secondary = Util.getUserUid(10, 10123);
        int system = Util.getUserUid(10, android.os.Process.SYSTEM_UID);
        assertTrue(XProvider.forOwnUser(secondary, "getSetting", 10, 0, false));
        assertTrue(XProvider.forOwnUser(secondary, "forceStop", 0, Util.getUserUid(10, 10200), false));
        assertTrue(XProvider.forOwnUser(owner, "putHook", 0, 0, false));
        assertFalse(XProvider.forOwnUser(secondary, "putHook", 0, 0, false));
        assertTrue(XProvider.forOwnUser(system, "putHook", 0, 0, false));
        assertTrue(XProvider.forOwnUser(owner, "clearData", 0, 0, false));

        for (String method : XProvider.USER_CALLS)
            assertFalse(method, XProvider.forOwnUser(secondary, method, 0, 0, false));
        for (String method : XProvider.UID_CALLS)
            assertFalse(method, XProvider.forOwnUser(secondary, method, 10, Util.getUserUid(0, 10200), false));
        assertFalse(XProvider.forOwnUser(secondary, "clearData", 10, 0, true));
        assertFalse(XProvider.forOwnUser(owner, "clearData", 0, 0, true));

        assertTrue(XProvider.forOwnUser(system, "clearData", 0, 0, true));
        assertTrue(XProvider.forOwnUser(android.os.Process.SYSTEM_UID, "initApp", 0, Util.getUserUid(10, 10200), false));
    }

    // Hooked apps read the settings for all apps and the settings and fake values of their own packages only
    @Test
    public void hookedAppsReadTheirOwnPackagesOnly() {
        String[] own = {"own.app", "shared.app"};
        assertTrue(XProvider.mayRead("global", true, own));
        assertTrue(XProvider.mayRead("shared.app", true, own));
        assertTrue(XProvider.mayRead("own.app", false, own));
        // The fake values new each boot are of a package, global is no package
        assertFalse(XProvider.mayRead("global", false, own));
        assertFalse(XProvider.mayRead("other.app", true, own));
        assertFalse(XProvider.mayRead("other.app", true, null));
        assertFalse(XProvider.mayRead(null, true, own));
    }

    // Only system server, of any user, and the app get the answers which need the permission; a hooked app
    // only those of APP_CALLS and APP_QUERIES, see XProvider.call and query
    @Test
    public void permittedCallers() {
        String[] app = {BuildConfig.APPLICATION_ID};
        String[] other = {"other.app", "shared.app"};
        assertTrue(XProvider.permitted(android.os.Process.SYSTEM_UID, null));
        assertTrue(XProvider.permitted(Util.getUserUid(10, android.os.Process.SYSTEM_UID), null));
        assertTrue(XProvider.permitted(10123, app));
        assertTrue(XProvider.permitted(Util.getUserUid(10, 10123), new String[]{"shared.app", BuildConfig.APPLICATION_ID}));
        assertFalse(XProvider.permitted(10124, other));
        assertFalse(XProvider.permitted(10124, null));
        assertFalse(XProvider.permitted(10124, new String[0]));
        // Root and the shell are not system server
        assertFalse(XProvider.permitted(0, null));
        assertFalse(XProvider.permitted(2000, other));
    }

    // Assignments of apps which are not known anymore, or of a uid another app has now, are removed at boot;
    // an app removed with its data kept is known still, and an app which cannot be looked up keeps them
    @Test
    public void removedAppsAtBoot() {
        int secondary = Util.getUserUid(10, 10125);
        List<String[]> apps = Arrays.asList(
                new String[]{"installed.app", "10123"},
                new String[]{"removed.app", "10124"},
                new String[]{"reinstalled.app", "10125"},
                new String[]{"locked.app", Integer.toString(secondary)});
        List<String[]> removed = XProvider.removedApps(apps, (packageName, userid) -> {
            switch (packageName) {
                case "installed.app":
                    assertEquals(0, userid);
                    return 10123;
                case "reinstalled.app":
                    return 10126;
                case "locked.app":
                    assertEquals(10, userid);
                    throw new IllegalStateException("No context");
                default:
                    throw new android.content.pm.PackageManager.NameNotFoundException();
            }
        });
        List<String> names = new java.util.ArrayList<>();
        for (String[] app : removed)
            names.add(app[0]);
        assertEquals(Arrays.asList("removed.app", "reinstalled.app"), names);
    }

    // Packages whose code runs in system server only cannot be restricted themselves, see getApps
    @Test
    public void packagesOfSystemServerLeftOut() {
        int system = android.os.Process.SYSTEM_UID;
        List<String> fused = Arrays.asList("system", "system");
        assertTrue(XProvider.inSystemServerOnly("com.android.location.fused", system, fused));
        assertTrue(XProvider.inSystemServerOnly("com.android.location.fused", Util.getUserUid(10, system), fused));
        assertTrue(XProvider.inSystemServerOnly("com.android.providers.settings", system,
                Collections.singletonList("system")));
        // Telecom has a process of its own for its screens, where its restrictions are installed
        assertFalse(XProvider.inSystemServerOnly("com.android.server.telecom", system,
                Arrays.asList("system", "com.android.server.telecom:ui", "system")));
        assertFalse(XProvider.inSystemServerOnly("android", system, Collections.singletonList("system")));
        // A process of another uid named "system" is a process of its own, like of an app hiding from the list
        assertFalse(XProvider.inSystemServerOnly("hiding.app", 10123, Collections.singletonList("system")));
        assertFalse(XProvider.inSystemServerOnly("phone.app", android.os.Process.PHONE_UID,
                Collections.singletonList("system")));
    }

    // A package stays listed when the application or any of its components runs in a process of its own, see getProcesses
    @Test
    public void processesOfAPackage() {
        int system = android.os.Process.SYSTEM_UID;
        // Telecom runs in system server, but its screens run in a process of its own
        PackageInfo telecom = packageInfo("com.android.server.telecom", "system");
        telecom.services = new ServiceInfo[]{component(new ServiceInfo(), "system")};
        telecom.activities = new ActivityInfo[]{component(new ActivityInfo(), "system"),
                component(new ActivityInfo(), "com.android.server.telecom:ui")};
        assertFalse(XProvider.inSystemServerOnly(telecom.packageName, system, XProvider.getProcesses(telecom)));

        // Fused Location has services only, all in system server, and no array for the kinds it has none of
        PackageInfo fused = packageInfo("com.android.location.fused", "system");
        fused.services = new ServiceInfo[]{component(new ServiceInfo(), "system"), component(new ServiceInfo(), "system")};
        assertTrue(XProvider.inSystemServerOnly(fused.packageName, system, XProvider.getProcesses(fused)));

        PackageInfo receiver = packageInfo("system.receiver", "system");
        receiver.receivers = new ActivityInfo[]{component(new ActivityInfo(), "system.receiver:remote")};
        assertFalse(XProvider.inSystemServerOnly(receiver.packageName, system, XProvider.getProcesses(receiver)));

        // Each kind of component counts, in any process
        PackageInfo all = packageInfo("all.kinds", "application");
        all.activities = new ActivityInfo[]{component(new ActivityInfo(), "activity")};
        all.services = new ServiceInfo[]{component(new ServiceInfo(), "service")};
        all.receivers = new ActivityInfo[]{component(new ActivityInfo(), "receiver")};
        all.providers = new ProviderInfo[]{component(new ProviderInfo(), "provider")};
        assertEquals(Arrays.asList("application", "activity", "service", "receiver", "provider"), XProvider.getProcesses(all));
    }

    private static PackageInfo packageInfo(String packageName, String process) {
        PackageInfo pi = new PackageInfo();
        pi.packageName = packageName;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.processName = process;
        return pi;
    }

    private static <T extends ComponentInfo> T component(T component, String process) {
        component.processName = process;
        return component;
    }

    // A hooked app can store an error in each of its assignments, which would together not fit in the one row of
    // the app, see getApps: past the limit errors are sent as their first line, never as no error
    @Test
    public void errorsOfAnAppFitInItsRow() throws Exception {
        String json = new String(java.nio.file.Files.readAllBytes(
                new java.io.File("src/main/assets/hooks.json").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        // All built-in hooks restricted and some custom ones, with the longest names there are
        int count = json.split("\"luaScript\"", -1).length - 1 + 500;
        // A first line longer than what is sent of it
        String line = new String(new char[200]).replace('\0', 'y');
        String trace = line + "\n" + new String(new char[16384]).replace('\0', 'x');
        List<XAssignment> assignments = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            XAssignment assignment = new XAssignment(hook(String.format("%073d", i), 0, true));
            assignment.exception = (i == 1 ? "short\nerror" : trace);
            assignments.add(assignment);
        }
        XProvider.limitErrors(assignments);

        // Parcel.writeString: a length and the UTF-16 chars with a terminator, padded to 4 bytes
        long size = 0;
        for (XAssignment assignment : assignments) {
            assertTrue(assignment.exception != null);
            size += 4 + parcelled(assignment.id) + parcelled(String.format("%18s", "")) + 3 * 8 + 4 +
                    parcelled(assignment.exception);
        }
        assertTrue("Row of " + size + " bytes", size < 2048 * 1024 - 64 * 1024);
        assertEquals(trace, assignments.get(0).exception);
        assertEquals("short\nerror", assignments.get(1).exception);
        assertEquals(line.substring(0, XProvider.MAX_ERROR_LINE) + "…", assignments.get(count - 1).exception);
    }

    private static int parcelled(String text) {
        return 4 + ((text.length() + 1) * 2 + 3) / 4 * 4;
    }

    // The scripts are sent of the definitions which need them only, see getHooks
    @Test
    public void scriptsOfTheDefinitionsAskedFor() throws Exception {
        XHook builtin = builtin(hook("Now", android.os.Build.VERSION.SDK_INT, true));
        XHook custom = hook("Custom", android.os.Build.VERSION.SDK_INT, true);
        assertFalse(XProvider.sendsScript(builtin, false, null));
        assertTrue(XProvider.sendsScript(builtin, true, null));
        assertFalse(XProvider.sendsScript(builtin, false, builtin.getId()));

        assertTrue(XProvider.sendsScript(custom, true, XProvider.SCRIPTS_CUSTOM));
        assertFalse(XProvider.sendsScript(builtin, true, XProvider.SCRIPTS_CUSTOM));

        assertTrue(XProvider.sendsScript(builtin, true, builtin.getId()));
        assertFalse(XProvider.sendsScript(custom, true, builtin.getId()));
    }

    private static XHook builtin(XHook hook) throws Exception {
        org.json.JSONObject jhook = hook.toJSONObject();
        jhook.put("builtin", true);
        return XHook.fromJSONObject(jhook);
    }

    // The built-in hooks an update or an upgrade of Android adds to the apps which restrict their group, see
    // assignNewBuiltins. A minSdk above the SDK version (0 in unit tests, see the android.jar stubs) stands for
    // a hook of a newer Android version.
    @Test
    public void newBuiltinsAfterAnUpgradeOfAndroid() throws Exception {
        List<XHook> builtins = Arrays.asList(hook("Now", android.os.Build.VERSION.SDK_INT, true), hook("Newer", android.os.Build.VERSION.SDK_INT + 1, true), hook("Off", android.os.Build.VERSION.SDK_INT, false));
        List<String> enabled = XProvider.enabledBuiltins(builtins);
        // A hook of a newer Android version cannot be assigned yet, so it is not known after this start
        assertEquals(Collections.singletonList(id("Now")), enabled);

        // The first start knows the hooks there are, nothing is new
        assertEquals(new HashSet<>(enabled), XProvider.knownBuiltins(enabled, null));

        // A marker of a former version, with all enabled hooks, adds nothing (and is rewritten, being another set)
        Set<String> known = XProvider.knownBuiltins(enabled, id("Now") + "," + id("Newer"));
        assertTrue(known.containsAll(enabled));

        // After the upgrade of Android the hook of the newer version is new, the hook known already is not
        List<String> upgraded = Arrays.asList(id("Now"), id("Newer"));
        Set<String> before = XProvider.knownBuiltins(enabled, String.join(",", enabled));
        assertEquals(Collections.singleton(id("Newer")), minus(upgraded, before));

        // Renamed hooks of a marker are known under their names of now
        assertTrue(XProvider.knownBuiltins(enabled, Util.COLLECTION_DEFAULT + ".TelephonyManager/getSimOperator")
                .contains(Util.COLLECTION_DEFAULT + ".TelephonyManager.getSimOperator"));
    }

    private static Set<String> minus(List<String> ids, Set<String> known) {
        Set<String> result = new HashSet<>(ids);
        result.removeAll(known);
        return result;
    }

    private static String id(String name) {
        return Util.COLLECTION_DEFAULT + "." + name;
    }

    private static XHook hook(String name, int minSdk, boolean enabled) throws Exception {
        return XHook.fromJSON("{\"collection\": \"" + Util.COLLECTION_DEFAULT + "\", \"group\": \"Test\"," +
                " \"name\": \"" + name + "\", \"author\": \"Test\", \"className\": \"android.location.Location\"," +
                " \"parameterTypes\": [], \"minSdk\": " + minSdk + ", \"enabled\": " + enabled + ", \"luaScript\": \"\"}");
    }
}
