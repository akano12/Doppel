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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// What hooked apps may ask the service without the permission of the app, see XProvider.POLICIES
public class XProviderTest {
    private static Set<String> methods(boolean query, boolean app) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, XProvider.Policy> entry : XProvider.POLICIES.entrySet())
            if (entry.getValue().isQuery() == query && (!app || entry.getValue().app))
                result.add(entry.getKey());
        return result;
    }

    // A method added as one for apps can give any hooked app the data of other apps, like getAllSettings the fake values
    // set for each app: it must check the caller itself, like the ones listed do
    @Test
    public void hookedAppsAskForThemselvesOnly() {
        assertEquals(Collections.singleton("reports"), methods(false, true));
        assertEquals(new HashSet<>(Arrays.asList("getAssignedHooks2", "getSettings", "getBootValues", "getServiceVersion")),
                methods(true, true));
    }

    // Each method of apps acts for the user of its caller or of the uid it is about, which each checks (ownUid)
    @Test
    public void methodsOfAppsActForTheUserOfTheCallerOrOfTheirUid() {
        for (String method : methods(true, true))
            assertTrue(method, XProvider.POLICIES.get(method).acts == XProvider.Acts.UID ||
                    XProvider.POLICIES.get(method).acts == XProvider.Acts.CALLER);
        assertTrue(XProvider.POLICIES.get("reports").acts == XProvider.Acts.CALLER);
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

        for (Map.Entry<String, XProvider.Policy> entry : XProvider.POLICIES.entrySet()) {
            String method = entry.getKey();
            if (entry.getValue().acts == XProvider.Acts.USER)
                assertFalse(method, XProvider.forOwnUser(secondary, method, 0, 0, false));
            if (entry.getValue().acts == XProvider.Acts.UID)
                assertFalse(method, XProvider.forOwnUser(secondary, method, 10, Util.getUserUid(0, 10200), false));
            // System server acts for any user, whatever the method
            assertTrue(method, XProvider.forOwnUser(system, method, 0, 0, true));
        }
        assertFalse(XProvider.forOwnUser(secondary, "clearData", 10, 0, true));
        assertFalse(XProvider.forOwnUser(owner, "clearData", 0, 0, true));

        assertTrue(XProvider.forOwnUser(system, "clearData", 0, 0, true));
        assertTrue(XProvider.forOwnUser(android.os.Process.SYSTEM_UID, "initApp", 0, Util.getUserUid(10, 10200), false));
    }

    // A method which is not in the table is refused for every caller, also for system server, so a method added to the
    // service without a rule for its user is never run with none
    @Test
    public void unknownMethodsFailClosed() {
        int system = android.os.Process.SYSTEM_UID;
        for (String method : new String[]{"unknown", "", "getVersion", "xlua", "putSettings2", null}) {
            assertFalse(String.valueOf(method), XProvider.forOwnUser(system, method, 0, 0, false));
            assertFalse(String.valueOf(method), XProvider.forOwnUser(10123, method, 0, 0, false));
            for (boolean query : new boolean[]{false, true}) {
                try {
                    XProvider.policy(query, method, true, system);
                    fail(method);
                } catch (IllegalArgumentException expected) {
                }
                // Apps get the error of a method which is not theirs, so they cannot tell the unknown ones
                try {
                    XProvider.policy(query, method, false, 10123);
                    fail(method);
                } catch (SecurityException expected) {
                }
            }
        }
    }

    // The dispatch of calls and queries: a call is not a query, apps get their own methods only, the permitted get all
    @Test
    public void dispatchByCallerAndKind() {
        int system = android.os.Process.SYSTEM_UID;
        int app = 10123;
        for (Map.Entry<String, XProvider.Policy> entry : XProvider.POLICIES.entrySet()) {
            String method = entry.getKey();
            boolean query = entry.getValue().isQuery();
            assertEquals(method, entry.getValue(), XProvider.policy(query, method, true, system));
            // The other kind of the same name is no method
            try {
                XProvider.policy(!query, method, true, system);
                fail(method);
            } catch (IllegalArgumentException expected) {
            }
            if (entry.getValue().app)
                assertEquals(method, entry.getValue(), XProvider.policy(query, method, false, app));
            else
                try {
                    XProvider.policy(query, method, false, app);
                    fail(method);
                } catch (SecurityException expected) {
                }
        }
        // Definitions, assignments and the data of other apps are for the permitted only
        for (String method : new String[]{"putHook", "assignHooks", "putScope", "putSetting", "putSettings", "clearData"})
            assertFalse(method, XProvider.POLICIES.get(method).app);
        for (String method : new String[]{"getHooks2", "getApps2", "getAssignments", "getAllSettings", "getLog"})
            assertFalse(method, XProvider.POLICIES.get(method).app);
    }

    // The tier of a caller: system server and the app are permitted, an app without assignments gets the answer of a device
    // without Doppel, an app with assignments is answered when it is in the scope its manager told, or when none was told
    @Test
    public void tierOfACaller() throws Throwable {
        int uid = 10123;
        String[] packages = {"assigned.app"};
        Map<Integer, Set<String>> synced = Collections.singletonMap(0, new HashSet<>(Arrays.asList("assigned.app", "system")));
        XProvider.Check yes = () -> true;
        XProvider.Check no = () -> false;
        XProvider.Check unused = () -> {
            throw new AssertionError("Assignments looked up");
        };

        assertEquals(XProvider.CALLER_PERMITTED, XProvider.caller(1000, null, true, synced, unused));
        assertEquals(XProvider.CALLER_ASSIGNED, XProvider.caller(uid, packages, false, synced, yes));
        assertEquals(XProvider.CALLER_NONE, XProvider.caller(uid, packages, false, synced, no));
        assertEquals(XProvider.CALLER_NONE, XProvider.caller(uid, null, false, synced, unused));
        assertEquals(XProvider.CALLER_NONE, XProvider.caller(uid, new String[0], false, synced, unused));

        // Outside the scope the assignments do not count, and are not looked up
        assertEquals(XProvider.CALLER_NONE, XProvider.caller(uid, new String[]{"outside.app"}, false, synced, unused));
        // A shared uid is answered when any of its packages is in the scope
        assertEquals(XProvider.CALLER_ASSIGNED,
                XProvider.caller(uid, new String[]{"outside.app", "assigned.app"}, false, synced, yes));
        // Another user has its own scope; one whose manager told none is answered as before
        assertEquals(XProvider.CALLER_ASSIGNED,
                XProvider.caller(Util.getUserUid(10, uid), new String[]{"outside.app"}, false, synced, yes));
        assertEquals(XProvider.CALLER_NONE,
                XProvider.caller(Util.getUserUid(10, uid), new String[]{"outside.app"}, false,
                        Collections.singletonMap(10, new HashSet<>()), unused));
        assertEquals(XProvider.CALLER_ASSIGNED, XProvider.caller(uid, new String[]{"outside.app"}, false, null, yes));
        assertEquals(XProvider.CALLER_ASSIGNED,
                XProvider.caller(uid, new String[]{"outside.app"}, false, new HashMap<>(), yes));
        // A scope which is told empty answers none
        assertFalse(XProvider.inScope(Collections.singletonMap(0, new HashSet<>()), 0, packages));
    }

    // An app with assignments asks about itself: its own uid and packages, nobody else's, also not of its other users
    @Test
    public void appsAskAboutThemselvesOnly() {
        int uid = 10123;
        java.util.function.IntFunction<String[]> packages =
                u -> (u == uid ? new String[]{"own.app", "shared.app"} : new String[]{"other.app"});
        for (String method : methods(true, true)) {
            if (XProvider.POLICIES.get(method).acts != XProvider.Acts.UID)
                continue;
            assertEquals(method, uid, XProvider.ownUid(uid, method, new String[]{"own.app", Integer.toString(uid)}, false, packages));
            assertEquals(method, uid, XProvider.ownUid(uid, method, new String[]{"shared.app", Integer.toString(uid)}, false, packages));
            // Another package, another uid, the same package of another user
            for (String[] selection : new String[][]{{"other.app", Integer.toString(uid)}, {"other.app", "10124"},
                    {"own.app", "10124"}, {"own.app", Integer.toString(Util.getUserUid(10, uid))}}) {
                try {
                    XProvider.ownUid(uid, method, selection, false, packages);
                    fail(method + " " + Arrays.toString(selection));
                } catch (SecurityException expected) {
                }
            }
            for (String[] selection : new String[][]{null, {}, {"own.app"}, {"own.app", Integer.toString(uid), "x"}}) {
                try {
                    XProvider.ownUid(uid, method, selection, false, packages);
                    fail(method);
                } catch (IllegalArgumentException expected) {
                }
            }
        }
        // The app and system server ask about any app of their user, not of another user
        assertEquals(10124, XProvider.ownUid(uid, "getBootValues", new String[]{"other.app", "10124"}, true, packages));
        assertEquals(10124, XProvider.ownUid(1000, "getAssignments", new String[]{"other.app", "10124"}, true, packages));
        try {
            XProvider.ownUid(uid, "getBootValues", new String[]{"other.app", Integer.toString(Util.getUserUid(10, 10124))}, true, packages);
            fail();
        } catch (SecurityException expected) {
        }
        assertEquals(Util.getUserUid(10, 10124), XProvider.ownUid(1000, "getAssignments",
                new String[]{"other.app", Integer.toString(Util.getUserUid(10, 10124))}, true, packages));
    }

    // An app which sends reports in a loop is dropped, the next window starts counting again; each app counts for itself
    @Test
    public void reportsOfAnAppAreLimited() {
        Map<Integer, long[]> counts = new HashMap<>();
        long now = 1000;
        int each = XCore.REPORTS_PER_CALL;
        int calls = XProvider.REPORTS_PER_WINDOW / each;
        for (int i = 0; i < calls; i++)
            assertTrue("Call " + i, XProvider.allowsReports(counts, 10123, each, now + i));
        assertFalse(XProvider.allowsReports(counts, 10123, each, now + calls));
        assertFalse(XProvider.allowsReports(counts, 10123, 1, now + XProvider.REPORT_WINDOW - 1));
        // Other apps are not affected, nor is the app in the next window
        assertTrue(XProvider.allowsReports(counts, 10124, each, now + calls));
        assertTrue(XProvider.allowsReports(counts, 10123, each, now + XProvider.REPORT_WINDOW));
        // A clock which went back starts again, rather than dropping until it catches up
        assertTrue(XProvider.allowsReports(counts, 10123, each, 5));
        // What hooks use in a second is far below
        for (int i = 0; i < 100; i++)
            assertTrue(XProvider.allowsReports(counts, 10125, 5, now + i * 1000L));
    }

    // What an app can report, as the engine sends it: an install with an error, a use which restricted or failed; a use which
    // did not restrict would let the app clear its restrictions from the log, so it is refused, and no other event; for the
    // app as a whole an install with the reason as text only
    @Test
    public void appsReportWhatTheEngineSendsOnly() {
        // Of a hook: event, error, restricted, text
        assertTrue(XProvider.Report.accepts("A", "install", true, 0, false));
        assertFalse(XProvider.Report.accepts("A", "install", false, 0, false));
        assertFalse(XProvider.Report.accepts("A", "install", false, 1, false));
        assertTrue(XProvider.Report.accepts("A", "use", false, 1, false));
        assertTrue(XProvider.Report.accepts("A", "use", true, 0, false));
        assertTrue(XProvider.Report.accepts("A", "use", true, 1, false));
        // An empty use, or one which did not restrict, is the app clearing its restriction from the log
        assertFalse(XProvider.Report.accepts("A", "use", false, 0, false));
        assertFalse(XProvider.Report.accepts("A", "use", false, 2, false));
        assertFalse(XProvider.Report.accepts("A", "other", true, 1, true));
        assertFalse(XProvider.Report.accepts("A", null, true, 1, true));

        assertTrue(XProvider.Report.accepts(XProvider.ENGINE_HOOK, "install", true, 0, true));
        assertFalse(XProvider.Report.accepts(XProvider.ENGINE_HOOK, "install", true, 0, false));
        assertFalse(XProvider.Report.accepts(XProvider.ENGINE_HOOK, "use", true, 1, true));
        assertFalse(XProvider.Report.accepts(XProvider.ENGINE_HOOK, "other", false, 0, true));
    }

    // That an app ran without its hooks is told to the user once per app and boot, by user and package: the same package of
    // another user is another app, a report which could not be shown is told again (the key is taken out), and an app
    // which is removed is told about again when it is installed again
    @Test
    public void unhookedAppsAreToldOncePerBoot() {
        Set<String> told = new HashSet<>();
        assertEquals("xlua", XProvider.ENGINE_HOOK);
        assertTrue(XProvider.firstUnhooked(told, 0, "a.app"));
        assertFalse(XProvider.firstUnhooked(told, 0, "a.app"));
        assertTrue(XProvider.firstUnhooked(told, 10, "a.app"));
        assertTrue(XProvider.firstUnhooked(told, 0, "b.app"));
        // Nor is a package of another user whose name starts like a user and a package
        assertTrue(XProvider.firstUnhooked(told, 1, "0:a.app"));
        assertEquals("10:a.app", XProvider.engineKey(10, "a.app"));
        told.remove(XProvider.engineKey(0, "a.app"));
        assertTrue(XProvider.firstUnhooked(told, 0, "a.app"));
    }

    // The cursors an app has open are limited, and counted again when it closes them, once each
    @Test
    public void openCursorsOfAnAppAreLimited() {
        XProvider.OpenCursors open = new XProvider.OpenCursors(2);
        assertTrue(open.acquire(10123));
        assertTrue(open.acquire(10123));
        assertFalse(open.acquire(10123));
        assertTrue(open.acquire(10124));

        android.database.Cursor first = open.track(10123, null);
        first.close();
        first.close();
        assertTrue(open.acquire(10123));
        assertFalse(open.acquire(10123));
        open.release(10123);
        open.release(10123);
        assertTrue(open.acquire(10123));
        // More releases than acquires keep none negative
        open.release(10125);
        open.release(10125);
        assertTrue(open.acquire(10125));
    }

    // Hooked apps read the settings for all apps and the settings and fake values of their own packages only
    @Test
    public void hookedAppsReadTheirOwnPackagesOnly() {
        String[] own = {"own.app", "shared.app"};
        assertTrue(XProvider.mayRead("global", true, own));
        assertTrue(XProvider.mayRead("shared.app", true, own));
        assertTrue(XProvider.mayRead("own.app", false, own));
        // The fake values the service derives (getBootValues) are of a package, global is no package
        assertFalse(XProvider.mayRead("global", false, own));
        assertFalse(XProvider.mayRead("other.app", true, own));
        assertFalse(XProvider.mayRead("other.app", true, null));
        assertFalse(XProvider.mayRead(null, true, own));
    }

    // Each start-up query of a hooked app is about one of its own packages, the settings also about global, so another
    // package gets the answer of the settings provider
    @Test
    public void hookedAppsAskAboutTheirOwnPackagesOnly() {
        String[] own = {"own.app"};
        for (String method : methods(true, true)) {
            if (XProvider.POLICIES.get(method).acts != XProvider.Acts.UID)
                continue;
            assertTrue(method, XProvider.mayAsk(method, "own.app", own));
            assertFalse(method, XProvider.mayAsk(method, "com.any.other", own));
            assertEquals(method, "getSettings".equals(method), XProvider.mayAsk(method, "global", own));
        }
    }

    // Only system server, of any user, and the app get the answers which need the permission; a hooked app
    // only those of the table which are for apps, see XProvider.call and query. Only callers with the name of the app
    // cost the lookup of their signers.
    @Test
    public void permittedCallers() {
        String[] app = {BuildConfig.APPLICATION_ID};
        String[] other = {"other.app", "shared.app"};
        Set<String> module = Collections.singleton("3082aa");
        Supplier<Set<String>> same = () -> new HashSet<>(module);
        Supplier<Set<String>> unused = () -> {
            throw new AssertionError("Signers looked up");
        };
        assertTrue(XProvider.permitted(android.os.Process.SYSTEM_UID, null, null, unused));
        assertTrue(XProvider.permitted(Util.getUserUid(10, android.os.Process.SYSTEM_UID), null, module, unused));
        assertTrue(XProvider.permitted(10123, app, module, same));
        assertTrue(XProvider.permitted(Util.getUserUid(10, 10123), new String[]{"shared.app", BuildConfig.APPLICATION_ID},
                module, same));
        assertFalse(XProvider.permitted(10124, other, module, unused));
        assertFalse(XProvider.permitted(10124, null, module, unused));
        assertFalse(XProvider.permitted(10124, new String[0], module, unused));
        // Root and the shell are not system server
        assertFalse(XProvider.permitted(0, null, module, unused));
        assertFalse(XProvider.permitted(2000, other, module, unused));
    }

    // An app which took the name of the app after it was removed, signed by another key, is not the app; nor is any app
    // before system server read the signers of the module which runs, or with the module not installed anymore
    @Test
    public void appOfOtherSignersIsNotPermitted() {
        String[] app = {BuildConfig.APPLICATION_ID};
        Set<String> module = Collections.singleton("3082aa");
        assertFalse(XProvider.permitted(10123, app, module, () -> Collections.singleton("3082bb")));
        assertFalse(XProvider.permitted(10123, app, module, () -> new HashSet<>(Arrays.asList("3082aa", "3082bb"))));
        assertFalse(XProvider.permitted(10123, app, module, () -> null));
        assertFalse(XProvider.permitted(10123, app, null, () -> module));
        assertFalse(XProvider.permitted(10123, app, null, () -> null));
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

    // A hook which leaves out fewer packages than before goes once to the apps which restrict its group and which it
    // left out (see assignMovedBuiltins): a Google app and the Play Store restricting Read.Identifiers get the gsf_id
    // hooks, Play services, which they still leave out, an app they did not leave out, which had them unless its user
    // switched them off, and an app restricting another group do not, and a second run adds nothing; new hooks, which
    // are not moved, go to every app restricting their group
    @Test
    public void narrowedBuiltinsCompleteRestrictedGroups() throws Exception {
        XHook androidId = hook("Settings.Secure.getStringForUser/android_id", "Read.Identifiers", "android");
        XHook other = hook("Build.getSerial", "Use.Tracking", null);
        String now = "com\\.google\\.android\\.gms,com\\.google\\.android\\.gsf";
        List<XHook> gsf = Arrays.asList(hook("ContentResolver.query26/gsf_id", "Read.Identifiers", now),
                hook("ContentProviderClient.query26/gsf_id", "Read.Identifiers", now));
        Map<String, XHook> hooks = new HashMap<>();
        for (XHook hook : Arrays.asList(androidId, other, gsf.get(0), gsf.get(1)))
            hooks.put(hook.getId(), hook);
        List<String[]> rows = new ArrayList<>(Arrays.asList(new String[]{"com.google.android.youtube", "10100", androidId.getId()},
                new String[]{"com.google.android.gms", "10050", androidId.getId()},
                new String[]{"com.android.vending", "10060", androidId.getId()},
                new String[]{"com.example.app", "10300", androidId.getId()},
                new String[]{"com.example", "10200", other.getId()}));
        Function<Integer, List<String>> collection = userid -> Collections.singletonList(Util.COLLECTION_DEFAULT);

        Set<String> added = new HashSet<>();
        for (String[] row : XProvider.completing(rows, hooks, gsf, collection, false, true))
            added.add(String.join(":", row));
        Set<String> expected = new HashSet<>();
        for (String app : new String[]{"com.google.android.youtube:10100", "com.android.vending:10060"})
            for (XHook hook : gsf)
                expected.add(app + ":" + hook.getId());
        assertEquals(expected, added);
        assertEquals(6, XProvider.completing(rows, hooks, gsf, collection, false, false).size());

        rows.addAll(XProvider.completing(rows, hooks, gsf, collection, false, true));
        assertTrue(XProvider.completing(rows, hooks, gsf, collection, false, true).isEmpty());

        // The start-up step assigns the hooks of the list once
        Set<String> moved = new HashSet<>();
        for (String[] move : XDatabase.MOVED)
            moved.add(move[0]);
        assertTrue(moved.containsAll(Arrays.asList("ContentResolver.query26/gsf_id", "ContentProviderClient.query26/gsf_id")));
    }

    private static Set<String> minus(List<String> ids, Set<String> known) {
        Set<String> result = new HashSet<>(ids);
        result.removeAll(known);
        return result;
    }

    private static String id(String name) {
        return Util.COLLECTION_DEFAULT + "." + name;
    }

    private static XHook hook(String name, String group, String excludePackages) throws Exception {
        org.json.JSONObject jhook = hook(name, android.os.Build.VERSION.SDK_INT, true).toJSONObject();
        jhook.put("group", group);
        if (excludePackages != null)
            jhook.put("excludePackages", excludePackages);
        return XHook.fromJSONObject(jhook);
    }

    private static XHook hook(String name, int minSdk, boolean enabled) throws Exception {
        return XHook.fromJSON("{\"collection\": \"" + Util.COLLECTION_DEFAULT + "\", \"group\": \"Test\"," +
                " \"name\": \"" + name + "\", \"author\": \"Test\", \"className\": \"android.location.Location\"," +
                " \"parameterTypes\": [], \"minSdk\": " + minSdk + ", \"enabled\": " + enabled + ", \"luaScript\": \"\"}");
    }
}
