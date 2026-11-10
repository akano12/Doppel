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

import android.util.Log;

// The log of the engine. Apps read their own entries of the log without any permission (logd gives each uid its
// own), which would tell that Doppel is there, also to apps without restrictions: in the processes of apps release
// builds log nothing, problems of hooks reach the service as reports; system server and debug builds log.
final class XLog {
    private static volatile boolean systemServer = false;

    private XLog() {
    }

    static void inSystemServer() {
        systemServer = true;
    }

    static boolean enabled() {
        return BuildConfig.DEBUG || systemServer;
    }

    static void i(String tag, String message) {
        if (enabled())
            Log.i(tag, message);
    }

    static void w(String tag, String message) {
        if (enabled())
            Log.w(tag, message);
    }

    static void e(String tag, String message) {
        if (enabled())
            Log.e(tag, message);
    }
}
