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

import android.app.Notification;
import android.app.NotificationChannel;
import android.content.pm.ParceledListSlice;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

// Stand-in for the notification manager in system server, of which XNotify.hideFromListeners hooks and deoptimizes
// methods, with the arguments of Android 8.1 to 11 and of Android 12 and later, and each caller of a hooked method
// of any version in its class
@SuppressWarnings("unused")
public class NotificationManagerService {
    // The binder, an anonymous class
    final Object mService = new Object() {
        public ParceledListSlice<StatusBarNotification> getActiveNotificationsFromListener() {
            return null;
        }

        public ParceledListSlice<StatusBarNotification> getSnoozedNotificationsFromListener() {
            return null;
        }

        private void addToListIfNeeded() {
        }

        public void setNotificationsShownFromListener(Object token, String[] keys) {
        }

        // Calls isVisibleToListener from Android 17
        public void cancelNotificationsFromListener(Object token, String[] keys) {
        }

        public ParceledListSlice<NotificationChannel> getNotificationChannelsFromPrivilegedListener(
                Object token, String pkg, UserHandle user) {
            return null;
        }
    };

    // The delegate of the status bar, an anonymous class
    final Object mNotificationDelegate = new Object() {
        public void onNotificationClick() {
        }

        public void onNotificationActionClick() {
        }

        public void onNotificationVisibilityChanged() {
        }

        public void onNotificationExpansionChanged() {
        }

        public void onNotificationDirectReplied() {
        }

        public void onNotificationSmartReplySent() {
        }

        public void onPanelRevealed() {
        }
    };

    public void onStart() {
    }

    private boolean isVisibleToListener(StatusBarNotification sbn, ManagedServices.ManagedServiceInfo listener) {
        return true;
    }

    boolean isVisibleToListener(StatusBarNotification sbn, int notificationType, ManagedServices.ManagedServiceInfo listener) {
        return true;
    }

    // The accessor of the private method up to Android 11 for the inner classes, which javac 8 made
    static boolean access$4300(NotificationManagerService service, StatusBarNotification sbn,
            ManagedServices.ManagedServiceInfo listener) {
        return service.isVisibleToListener(sbn, listener);
    }

    // Another accessor, which is left alone
    static Object access$100(NotificationManagerService service) {
        return null;
    }

    private Object makeRankingUpdateLocked(ManagedServices.ManagedServiceInfo info) {
        return null;
    }

    void buzzBeepBlinkLocked(NotificationRecord record) {
    }

    protected void reportSeen(NotificationRecord r) {
    }

    protected void reportUserInteraction(NotificationRecord r) {
    }

    protected void maybeRecordInterruptionLocked(NotificationRecord r) {
    }

    void sendAccessibilityEvent(Notification notification, CharSequence packageName) {
    }

    void sendAccessibilityEvent(NotificationRecord record) {
    }

    public class NotificationListeners {
        public void notifyPostedLocked(NotificationRecord r, NotificationRecord old) {
        }

        void prepareNotifyPostedLocked() {
        }

        public void notifyRemovedLocked() {
        }

        public void notifyRankingUpdateLocked() {
        }

        void updateUriPermissionsForActiveNotificationsLocked() {
        }

        protected void notifyNotificationChannelChanged(String pkg, UserHandle user, NotificationChannel channel,
                int modificationType) {
        }

        void notifyNotificationChannelChanged(ManagedServices.ManagedServiceInfo info, String pkg, UserHandle user,
                NotificationChannel channel, int modificationType) {
        }

        public void onStatusBarIconsBehaviorChanged(boolean hideSilentStatusIcons) {
        }
    }

    public class NotificationAssistants {
        private void notifyAssistantLocked(StatusBarNotification sbn, int notificationType, boolean sameUserOnly,
                Object callback) {
        }

        public void onNotificationEnqueued() {
        }

        public void onNotificationEnqueuedLocked() {
        }

        public void notifyAssistantSnoozedLocked() {
        }

        public void onNotificationsSeenLocked() {
        }

        public void notifyAssistantFeedbackReceived() {
        }
    }

    protected class SnoozeNotificationRunnable implements Runnable {
        @Override
        public void run() {
        }

        void snoozeNotificationLocked(NotificationRecord r) {
        }
    }
}
