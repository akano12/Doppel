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
import android.content.pm.ApplicationInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

// Assigning hooks an app has already changes nothing, like adding a group to all apps from a list which is not up to
// date: assigning one again would reset its usage data and stop the app for nothing. Needs the module running and an
// app without restrictions (skipped without them); what it assigns is removed again.
@RunWith(AndroidJUnit4.class)
public class AssignTest {
    // An app every device has; it is not stopped and its assignments are removed at the end
    private static final String PACKAGE = "com.android.settings";

    @Test
    public void assignedHooksAreNotAssignedAgain() throws Throwable {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("Module not running", Data.isAvailable(context));
        ApplicationInfo ai;
        try {
            ai = context.getPackageManager().getApplicationInfo(PACKAGE, 0);
        } catch (Throwable ex) {
            assumeTrue("Not installed: " + PACKAGE, false);
            return;
        }
        XApp app = Data.getApp(context, PACKAGE, ai.uid);
        assumeTrue("Has restrictions: " + PACKAGE, app != null && app.assignments.isEmpty());

        ArrayList<String> ids = new ArrayList<>(Arrays.asList("Privacy.WifiInfo.getSSID", "Privacy.Os.getsockname"));
        try {
            assertEquals(2, Data.assignHooks(context, ids, PACKAGE, ai.uid, false, false, false));
            assertEquals(0, Data.assignHooks(context, ids, PACKAGE, ai.uid, false, false, false));
            // One new, one assigned already
            ids.add("Privacy.WifiInfo.getBSSID");
            assertEquals(1, Data.assignHooks(context, ids, PACKAGE, ai.uid, false, false, false));
        } finally {
            assertEquals(3, Data.assignHooks(context, ids, PACKAGE, ai.uid, true, false, false));
        }
    }
}
