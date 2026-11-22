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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import io.github.libxposed.service.XposedProvider;

// In place of the provider of the libxposed service library (see the manifest), through which the framework delivers
// its service, see Scope. The library takes a service from any app, which would then answer for the framework: whether
// an app is in scope, and the apps the user adds. The framework delivers it as root or as system, which also reach the
// provider where it is not exported (see the manifest), so no other app can open it, by call, query or insert, and the
// activity manager refuses them before it starts the process of Doppel.
// ContentResolver.getType is the exception, on all versions: for an app of the user asked for the activity manager clears
// the identity of the caller and looks the provider up as the system (ActivityManagerService.getProviderMimeType up to
// Android 11, ContentProviderHelper.getMimeTypeFilterAsync from Android 12, which are the same), which is let through
// whatever the manifest says, and starts the process; the permission and visibility of the caller are checked after that.
// No manifest setting stops it, so getType answers null like for an authority which does not exist, and the other methods
// nothing: an app learns nothing from the answers. Other apps cannot see the process of another app on Android 9 and
// later; they can measure the call, which takes longer when a process is started, which is not measured.
// shortcut: an app can still tell by the time of getType that Doppel is installed; the engine answers it for its restricted
// apps without the call (provider_hidden.lua), but only for a provider the package manager finds for the app, which an app
// that cannot see Doppel does not; upgrade to answering this authority there whatever the package manager finds.
public class ScopeProvider extends ContentProvider {
    private static final String TAG = "XLua.ScopeProvider";

    private static final int ROOT_UID = 0; // Process.ROOT_UID is there from Android 10

    // Hands the service to the library, which does not use its context for a call
    private final XposedProvider library = new XposedProvider();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (!accepts(uid)) {
            Log.w(TAG, "Refused " + method + " of uid " + uid);
            return null;
        }
        return library.call(method, arg, extras);
    }

    // Whether a uid can deliver the service of the framework, see ScopeProviderTest
    static boolean accepts(int uid) {
        return (uid == ROOT_UID || uid == Process.SYSTEM_UID);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
