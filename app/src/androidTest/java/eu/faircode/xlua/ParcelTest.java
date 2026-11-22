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

import android.os.Parcel;
import android.os.Parcelable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// The parcels the service sends to the manager and the engine (XApp, XAssignment, XHook): the service keeps the module it
// started with until a reboot, so after an update the two sides write and read different versions of them, see
// Data.requireSameVersion. A parcel has no format, so the order and the types of the fields are the format: what is
// written here field by field is what the manager reads, and a change of it, other than a field added at the end which is
// read only when it is there (like deoptimize of XHook), fails these tests, which then need the version check in the engine
// and a new service version to go with the change. Run with a device (the Parcel of the framework).
@RunWith(AndroidJUnit4.class)
public class ParcelTest {
    private static byte[] marshall(Parcelable parcelable) {
        Parcel parcel = Parcel.obtain();
        try {
            parcelable.writeToParcel(parcel, 0);
            return parcel.marshall();
        } finally {
            parcel.recycle();
        }
    }

    private static <T> T unmarshall(byte[] data, Parcelable.Creator<T> creator) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            return creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private static XHook hook(String name, String... deoptimize) throws Exception {
        return XHook.fromJSON("{\"collection\": \"Privacy\", \"group\": \"Get.Location\", \"name\": \"" + name + "\"," +
                " \"author\": \"Test\", \"version\": 3, \"description\": \"Text\", \"className\": \"android.location.Location\"," +
                " \"methodName\": \"getLatitude\", \"parameterTypes\": [\"int\", \"java.lang.String\"], \"returnType\": \"double\"," +
                " \"minSdk\": 28, \"maxSdk\": 37, \"minApk\": 2, \"maxApk\": 9, \"excludePackages\": \"a\\\\.b,c\"," +
                " \"enabled\": true, \"optional\": true, \"usage\": true, \"notify\": true, \"settings\": [\"s1\", \"s2\"]," +
                (deoptimize.length == 0 ? "" : " \"deoptimize\": [\"" + String.join("\", \"", deoptimize) + "\"],") +
                " \"luaScript\": \"function after(hook, param) return false end\"}");
    }

    @Test
    public void assignmentParcelHasTheLayoutOfTheManager() throws Exception {
        XAssignment assignment = new XAssignment(hook("A"));
        assignment.installed = 11;
        assignment.assigned = 22;
        assignment.used = 33;
        assignment.restricted = true;
        assignment.exception = "Error\nline";

        Parcel expected = Parcel.obtain();
        expected.writeString(assignment.id);
        expected.writeString("Get.Location");
        expected.writeLong(11);
        expected.writeLong(22);
        expected.writeLong(33);
        expected.writeByte((byte) 1);
        expected.writeString("Error\nline");
        byte[] layout = expected.marshall();
        expected.recycle();

        byte[] data = marshall(assignment);
        assertArrayEquals(layout, data);

        XAssignment back = unmarshall(data, XAssignment.CREATOR);
        assertEquals(assignment.id, back.id);
        assertEquals("Get.Location", back.group);
        assertEquals(11, back.installed);
        assertEquals(22, back.assigned);
        assertEquals(33, back.used);
        assertTrue(back.restricted);
        assertEquals("Error\nline", back.exception);

        XAssignment none = unmarshall(marshall(new XAssignment(hook("B"))), XAssignment.CREATOR);
        assertEquals(-1, none.installed);
        assertNull(none.exception);
    }

    @Test
    public void appParcelHasTheLayoutOfTheManager() throws Exception {
        XApp app = new XApp();
        app.packageName = "com.example.app";
        app.uid = 10123;
        app.icon = 7;
        app.label = "Example";
        app.persistent = false;
        app.system = true;
        app.forceStop = false;
        app.installed = 1234567890123L;
        app.assignments = new ArrayList<>(Arrays.asList(new XAssignment(hook("A")), new XAssignment(hook("B"))));
        app.assignments.get(1).exception = "Error";

        Parcel expected = Parcel.obtain();
        expected.writeString("com.example.app");
        expected.writeInt(10123);
        expected.writeInt(7);
        expected.writeString("Example");
        expected.writeByte((byte) 0);
        expected.writeByte((byte) 1);
        expected.writeByte((byte) 0);
        expected.writeLong(1234567890123L);
        expected.writeTypedList(app.assignments);
        byte[] layout = expected.marshall();
        expected.recycle();

        byte[] data = app.marshall();
        assertArrayEquals(layout, data);

        XApp back = unmarshall(data, XApp.CREATOR);
        assertEquals("com.example.app", back.packageName);
        assertEquals(10123, back.uid);
        assertEquals(7, back.icon);
        assertEquals("Example", back.label);
        assertEquals(false, back.persistent);
        assertEquals(true, back.system);
        assertEquals(false, back.forceStop);
        assertEquals(1234567890123L, back.installed);
        assertEquals(2, back.assignments.size());
        assertEquals(app.assignments.get(0).id, back.assignments.get(0).id);
        assertEquals("Error", back.assignments.get(1).exception);
    }

    @Test
    public void hookParcelRoundTripsWithAndWithoutItsScript() throws Exception {
        XHook hook = hook("A", "x.Y.z", "w");
        for (boolean withLua : new boolean[]{true, false}) {
            XHook back = unmarshall(hook.marshall(withLua), XHook.CREATOR);
            assertEquals(hook.getId(), back.getId());
            assertEquals("Get.Location", back.getGroup());
            assertEquals("getLatitude", back.getMethodName());
            assertArrayEquals(hook.getParameterTypes(), back.getParameterTypes());
            assertArrayEquals(hook.getSettings(), back.getSettings());
            assertEquals(withLua ? hook.getLuaScript() : null, back.getLuaScript());
        }
        assertArrayEquals(new String[]{"x.Y.z", "w"}, unmarshall(hook.marshall(true), XHook.CREATOR).getDeoptimize());
    }

    // The service of the version before, until the reboot after an update, sends the parcel without deoptimize, the field
    // added last: a parcel which ends before it reads as none, so the parcel of a definition has no field in the middle
    @Test
    public void hookParcelOfTheVersionBeforeReadsWithoutItsLastField() throws Exception {
        XHook hook = hook("A");
        byte[] data = hook.marshall(true);
        // No deoptimize is an array of -1 elements: 4 bytes
        byte[] before = Arrays.copyOf(data, data.length - 4);
        XHook back = unmarshall(before, XHook.CREATOR);
        assertEquals(hook.getId(), back.getId());
        assertEquals(0, back.getDeoptimize().length);
        assertEquals(hook.getLuaScript(), back.getLuaScript());
        assertArrayEquals(new String[]{"s1", "s2"}, back.getSettings());
    }
}
