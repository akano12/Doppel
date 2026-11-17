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

import android.service.notification.StatusBarNotification;

// Stand-in for a notification of system server, of which XNotify.hideFromListeners reads the notification of the app
// from the field, which all versions have; it is not final, so the tests can stand in for a subclass of a vendor
public class NotificationRecord {
    private final StatusBarNotification sbn;

    public NotificationRecord(StatusBarNotification sbn) {
        this.sbn = sbn;
    }
}
