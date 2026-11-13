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

    // Like when the user restricts an app: persistent apps are never stopped, system apps only when the user set so
    @Test
    public void newSystemAppIsStoppedOnlyWhenSet() {
        assertTrue(ReceiverPackage.stopsAutomatically(0, null));
        assertFalse(ReceiverPackage.stopsAutomatically(0, "false"));
        assertFalse(ReceiverPackage.stopsAutomatically(ApplicationInfo.FLAG_SYSTEM, null));
        assertFalse(ReceiverPackage.stopsAutomatically(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP, null));
        assertTrue(ReceiverPackage.stopsAutomatically(ApplicationInfo.FLAG_SYSTEM, "true"));
        assertFalse(ReceiverPackage.stopsAutomatically(ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_PERSISTENT, "true"));
    }
}
