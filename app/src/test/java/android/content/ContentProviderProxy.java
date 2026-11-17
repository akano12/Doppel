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

package android.content;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

// Stand-in for the hidden binder proxy of providers, of which XCore.hideService hooks query and call, with the
// arguments of Android 12 and later, the attribution source and the cancellation signal as objects
@SuppressWarnings("unused")
final class ContentProviderProxy {
    public Cursor query(Object attributionSource, Uri url, String[] projection, Bundle queryArgs, Object cancellationSignal) {
        return null;
    }

    public Bundle call(Object attributionSource, String authority, String method, String request, Bundle extras) {
        return null;
    }
}
