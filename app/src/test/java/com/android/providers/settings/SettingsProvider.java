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

package com.android.providers.settings;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

// Stand-in for the settings provider in system server, of which XCore.hookSettings hooks call and query
@SuppressWarnings("unused")
public class SettingsProvider {
    // The context of ContentProvider, which the service is called with
    public Context context;

    public Context getContext() {
        return context;
    }

    public Bundle call(String method, String arg, Bundle extras) {
        return null;
    }

    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }
}
