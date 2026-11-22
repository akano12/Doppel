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

import android.app.ActivityManager;
import android.content.pm.ApplicationInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// A new app which runs already when it gets its assignments is stopped, see ReceiverPackage.added
public class ReceiverPackageTest {
    private static final String PACKAGE = "new.app";
    private static final int UID = 10123;

    private static ActivityManager.RunningAppProcessInfo process(int uid, String... packages) {
        ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo();
        process.uid = uid;
        process.pkgList = packages;
        return process;
    }

    // Only a process of the app itself, of its user, and not an app which is not running, which Android would not stop
    @Test
    public void runningNewAppIsStopped() {
        assertTrue(ReceiverPackage.runs(Arrays.asList(
                process(Util.getUserUid(10, UID), PACKAGE), process(UID, "other.app", PACKAGE)), PACKAGE, UID));

        assertFalse(ReceiverPackage.runs(null, PACKAGE, UID));
        assertFalse(ReceiverPackage.runs(Collections.emptyList(), PACKAGE, UID));
        // The same app of another user, and another package of a shared uid
        assertFalse(ReceiverPackage.runs(Collections.singletonList(process(Util.getUserUid(10, UID), PACKAGE)), PACKAGE, UID));
        assertFalse(ReceiverPackage.runs(Arrays.asList(process(UID, "shared.app"), process(UID, (String[]) null)), PACKAGE, UID));
    }

    // Like when the user restricts an app: persistent apps are never stopped, system apps only when the user set so; the
    // apps the service lists, which the screens stop, and the new apps it stops are of the one rule, XApp.stopsAutomatically
    @Test
    public void newSystemAppIsStoppedOnlyWhenSet() {
        assertTrue(XApp.stopsAutomatically(0, PACKAGE, null));
        assertFalse(XApp.stopsAutomatically(0, PACKAGE, "false"));
        assertFalse(XApp.stopsAutomatically(ApplicationInfo.FLAG_SYSTEM, PACKAGE, null));
        assertFalse(XApp.stopsAutomatically(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP, PACKAGE, null));
        assertTrue(XApp.stopsAutomatically(ApplicationInfo.FLAG_SYSTEM, PACKAGE, "true"));
        assertFalse(XApp.stopsAutomatically(ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_PERSISTENT, PACKAGE, "true"));
        // System server is kept running by the system
        assertFalse(XApp.stopsAutomatically(0, "android", "true"));
    }

    // What the screen of an app tells (forceStop, from the default or the setting of the user) is the same rule
    @Test
    public void listedAppsAndNewAppsAreStoppedAlike() {
        for (int flags : new int[]{0, ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
                ApplicationInfo.FLAG_PERSISTENT, ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_PERSISTENT})
            for (String setting : new String[]{null, "true", "false"}) {
                XApp app = new XApp();
                app.packageName = PACKAGE;
                app.persistent = XApp.isPersistent(flags, PACKAGE);
                app.system = XApp.isSystem(flags);
                // As XProvider.getApps makes it: the default, then the setting
                app.forceStop = (setting == null ? XApp.stopsAutomatically(flags, PACKAGE, null) : Boolean.parseBoolean(setting));
                assertEquals(flags + " " + setting, XApp.stopsAutomatically(flags, PACKAGE, setting), app.stopsAutomatically());
            }
    }
}
