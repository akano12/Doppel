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

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

// The parts of XParam which need Android itself: the calling uid and the classes Intent, Bundle and ClipData,
// which the unit tests have as stubs only
@RunWith(AndroidJUnit4.class)
public class XParamDeviceTest {
    // An app id which no app has, so the lookup finds no package
    private static final int UNUSED_UID = 19999;

    @SuppressWarnings("unused")
    private static String field;

    private static XParam param() throws NoSuchFieldException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new XParam(context, XParamDeviceTest.class.getDeclaredField("field"), new HashMap<>());
    }

    // An app calling this one stays hidden, so its uid and its packages agree, like a uid Android does not show the app.
    // The calling identity is set in this process, like a call of that app would.
    @Test
    public void callingAppStaysHidden() throws Exception {
        XParam param = param();
        long identity = Binder.clearCallingIdentity();
        try {
            Binder.restoreCallingIdentity(((long) UNUSED_UID << 32) | Process.myPid());
            assertEquals(UNUSED_UID, Binder.getCallingUid());
            assertFalse(param.isUidVisible(UNUSED_UID));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    // Code of the app, which must not run in a lookup without the hooks
    private static final class AppParcelable implements Parcelable {
        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            throw new AssertionError("writeToParcel of the app");
        }
    }

    private static final class AppIntent extends Intent {
        AppIntent(String action, Uri data) {
            super(action, data);
            putExtra("app", new AppParcelable());
        }

        @Override
        public String resolveTypeIfNeeded(ContentResolver resolver) {
            throw new AssertionError("resolveTypeIfNeeded of the app");
        }

        @Override
        public String getScheme() {
            throw new AssertionError("getScheme of the app");
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            throw new AssertionError("writeToParcel of the app");
        }
    }

    // The copy is of Android's own class, with the action, data and flags, without the extras and clip data, also of
    // its selector, so it parcels without code of the app; the intent of the app keeps them
    @Test
    public void plainIntentHasNoCodeOfTheApp() throws Exception {
        Uri link = Uri.parse("https://example.com/x");
        Intent app = new AppIntent(Intent.ACTION_VIEW, link);
        app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        app.setClipData(ClipData.newIntent("app", new AppIntent(Intent.ACTION_VIEW, link)));
        app.setSelector(new AppIntent(Intent.ACTION_MAIN, null));

        Intent copy = param().plainIntent(app);
        assertSame(Intent.class, copy.getClass());
        assertEquals(Intent.ACTION_VIEW, copy.getAction());
        assertEquals(link, copy.getData());
        assertNull(copy.getType());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, copy.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        assertNull(copy.getExtras());
        assertNull(copy.getClipData());
        assertSame(Intent.class, copy.getSelector().getClass());
        assertEquals(Intent.ACTION_MAIN, copy.getSelector().getAction());
        assertNull(copy.getSelector().getExtras());
        Parcel parcel = Parcel.obtain();
        try {
            copy.writeToParcel(parcel, 0);
        } finally {
            parcel.recycle();
        }

        assertNotNull(app.getExtras());
        assertNotNull(app.getClipData());
        assertNotNull(app.getSelector().getExtras());
    }

    // A uri of content: gets its type, which the lookup would else ask the provider for again: the type of the intent,
    // else none when no provider has the uri, which the lookup cannot be asked without
    @Test
    public void plainIntentOfContent() throws Exception {
        XParam param = param();
        Uri missing = Uri.parse("content://sk.akano12.doppel.missing/x");
        assertNull(param.plainIntent(new Intent(Intent.ACTION_VIEW, missing)));
        Intent typed = param.plainIntent(new Intent(Intent.ACTION_VIEW).setDataAndType(missing, "text/plain"));
        assertEquals("text/plain", typed.getType());
        assertEquals(missing, typed.getData());
    }

    // The copy of a query Bundle has the query arguments a provider reads, the same for its getters, and no value of
    // the app, so it parcels without code of the app; the Bundle of the app keeps them
    @Test
    public void plainQueryArgsHaveNoCodeOfTheApp() throws Exception {
        Bundle nested = new Bundle();
        nested.putParcelable("app", new AppParcelable());
        Bundle app = new Bundle();
        app.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "_id > ?");
        app.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{"1"});
        app.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        app.putLong(ContentResolver.QUERY_ARG_SORT_COLLATION, 1);
        app.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, null);
        app.putParcelable(ContentResolver.QUERY_ARG_SORT_COLUMNS, new AppParcelable());
        app.putBundle("android:query-arg-nested", nested);
        app.putParcelable("app", new AppParcelable());

        assertNull(param().plainQueryArgs(null));
        Bundle copy = param().plainQueryArgs(app);
        assertEquals(new HashSet<>(Arrays.asList(ContentResolver.QUERY_ARG_SQL_SELECTION,
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_ARG_SORT_COLLATION, ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                ContentResolver.QUERY_ARG_SORT_COLUMNS, "android:query-arg-nested")), copy.keySet());
        assertEquals("_id > ?", copy.getString(ContentResolver.QUERY_ARG_SQL_SELECTION));
        assertArrayEquals(new String[]{"1"}, copy.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS));
        assertEquals(ContentResolver.QUERY_SORT_DIRECTION_DESCENDING, copy.getInt(ContentResolver.QUERY_ARG_SORT_DIRECTION));
        for (String key : new String[]{ContentResolver.QUERY_ARG_SORT_COLLATION, ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                ContentResolver.QUERY_ARG_SORT_COLUMNS, "android:query-arg-nested"}) {
            assertEquals(key, app.getInt(key, 7), copy.getInt(key, 7));
            assertEquals(key, app.getString(key), copy.getString(key));
            assertArrayEquals(key, app.getStringArray(key), copy.getStringArray(key));
        }
        Parcel parcel = Parcel.obtain();
        try {
            copy.writeToParcel(parcel, 0);
        } finally {
            parcel.recycle();
        }

        assertNotNull(app.getBundle("android:query-arg-nested"));
        assertNotNull(app.getParcelable("app"));
    }
}
