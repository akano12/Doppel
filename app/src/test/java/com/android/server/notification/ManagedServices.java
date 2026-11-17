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

package com.android.server.notification;

// Stand-in for the managed services of system server, of which XNotify.hideFromListeners reads the listener
public class ManagedServices {
    public static class ManagedServiceInfo {
        public boolean isSystem;

        public ManagedServiceInfo(boolean isSystem) {
            this.isSystem = isSystem;
        }
    }
}
