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

import android.app.Notification;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ParceledListSlice;
import android.os.Parcel;
import android.service.notification.StatusBarNotification;

import com.android.server.notification.ManagedServices.ManagedServiceInfo;
import com.android.server.notification.NotificationManagerService;
import com.android.server.notification.NotificationRecord;

import org.junit.Test;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

// Doppel's notifications in system server go to the listeners of the system only (XNotify.hideFromListeners),
// with stand-ins for the notification manager, its listeners and the helper which alerts
public class XNotifyTest {
    private static final String SETTINGS = "com.android.providers.settings";

    private final List<Member> members = new ArrayList<>();
    private final List<XBackend.Callback> callbacks = new ArrayList<>();
    private final Set<String> deoptimized = new TreeSet<>();

    private void hook() throws Throwable {
        XNotify.hideFromListeners(new XBackend() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public void hook(Member member, Callback callback) {
                members.add(member);
                callbacks.add(callback);
            }

            @Override
            public boolean deoptimize(Member member) {
                deoptimized.add(name(member));
                return true;
            }

            @Override
            public void log(String message) {
            }

            @Override
            public void log(Throwable ex) {
            }
        }, getClass().getClassLoader());
    }

    // The class without its package and the method, like NotificationManagerService$NotificationListeners.notifyPostedLocked
    private static String name(Member member) {
        String cls = member.getDeclaringClass().getName();
        return cls.substring(cls.lastIndexOf('.') + 1) + "." + member.getName();
    }

    private List<Integer> hooked(String method) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < members.size(); i++)
            if (members.get(i).getName().equals(method))
                indexes.add(i);
        return indexes;
    }

    // The binder exists once the service started
    private void start() throws Throwable {
        int i = hooked("onStart").get(0);
        callbacks.get(i).after(new XCall(members.get(i), new NotificationManagerService(), new Object[0]));
    }

    private static Notification notification(String channel) {
        return new Notification() {
            @Override
            public String getChannelId() {
                return channel;
            }
        };
    }

    // Stand-in for a channel, of which the hooks read the id like of NotificationChannel, which is final
    public static final class Channel {
        private final String id;

        Channel(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    private static Channel channel(String id) {
        return new Channel(id);
    }

    // Like Android, which posts as system server with the package of the context, the uid follows the package
    private static StatusBarNotification sbn(String pkg, String tag, String channel) {
        return sbn((pkg.equals("android") || pkg.equals(SETTINGS) ? 1000 : 10123), pkg, tag, channel);
    }

    private static StatusBarNotification sbn(int uid, String pkg, String tag, String channel) {
        Notification notification = notification(channel);
        return new StatusBarNotification((Parcel) null) {
            @Override
            public int getUid() {
                return uid;
            }

            @Override
            public String getPackageName() {
                return pkg;
            }

            @Override
            public String getTag() {
                return tag;
            }

            @Override
            public Notification getNotification() {
                return notification;
            }
        };
    }

    // What each hooked method answers a listener for a notification Android shows it
    private List<Object> visible(StatusBarNotification sbn, boolean system) throws Throwable {
        List<Object> answers = new ArrayList<>();
        for (int i : hooked("isVisibleToListener")) {
            ManagedServiceInfo listener = new ManagedServiceInfo(system);
            Object[] args = (((Method) members.get(i)).getParameterCount() == 2
                    ? new Object[]{sbn, listener} : new Object[]{sbn, 0, listener});
            XCall call = new XCall(members.get(i), null, args);
            call.setOutcome(true, null);
            callbacks.get(i).after(call);
            answers.add(call.getResult());
        }
        return answers;
    }

    // Whether each hooked method skips the call; an accessibility event gets the notification in the arguments of its version
    private List<Boolean> skipped(String method, Object... args) throws Throwable {
        List<Boolean> answers = new ArrayList<>();
        for (int i : hooked(method)) {
            Object[] actual = args;
            if (method.equals("sendAccessibilityEvent")) {
                StatusBarNotification sbn = (StatusBarNotification) args[0];
                actual = (((Method) members.get(i)).getParameterCount() == 2
                        ? new Object[]{sbn.getNotification(), sbn.getPackageName()}
                        : new Object[]{new NotificationRecord(sbn)});
            }
            XCall call = new XCall(members.get(i), null, actual);
            callbacks.get(i).before(call);
            answers.add(call.isReturnEarly());
        }
        return answers;
    }

    @Test
    public void hiddenFromOtherListeners() throws Throwable {
        hook();
        // Both the method of Android 8.1 to 11 and the one of Android 12 and later
        assertEquals(2, hooked("isVisibleToListener").size());
        List<Object> hidden = List.of(false, false);
        List<Object> shown = List.of(true, true);

        // Usage, an error, lost data and new apps, which the settings provider posts, and new apps, which earlier versions
        // posted as the framework
        assertEquals(hidden, visible(sbn(SETTINGS, "xlua_use_Use.Camera", "xlua_usage"), false));
        assertEquals(hidden, visible(sbn(SETTINGS, "xlua_exception", "xlua"), false));
        assertEquals(hidden, visible(sbn(SETTINGS, "xlua_data_lost", "xlua"), false));
        assertEquals(hidden, visible(sbn("android", "xlua_new_app", "xlua"), false));
        // Of a secondary user
        assertEquals(hidden, visible(sbn(10 * 100000 + 1000, SETTINGS, "xlua_use_Record.Audio", "xlua_usage"), false));
        // The summary Android posts to group them
        assertEquals(hidden, visible(sbn(SETTINGS, "ranker_group", "xlua_usage"), false));

        // The status bar, which the system registered, shows them
        assertEquals(shown, visible(sbn(SETTINGS, "xlua_use_Use.Camera", "xlua_usage"), true));

        // Notifications of apps, also with the tags and channels of Doppel, and other ones of system server, stay
        assertEquals(shown, visible(sbn("com.example", "xlua_exception", "xlua"), false));
        assertEquals(shown, visible(sbn("android", null, "DEVELOPER"), false));
        assertEquals(shown, visible(sbn("android", "xlua_other", "DEVELOPER"), false));

        // Android's own answer when a listener does not see a notification at all, and on an error
        int i = hooked("isVisibleToListener").get(1);
        ManagedServiceInfo listener = new ManagedServiceInfo(false);
        XCall unseen = new XCall(members.get(i), null, new Object[]{sbn(SETTINGS, "xlua_exception", "xlua"), 0, listener});
        unseen.setOutcome(false, null);
        callbacks.get(i).after(unseen);
        assertEquals(false, unseen.getResult());

        StatusBarNotification broken = new StatusBarNotification((Parcel) null) {
            @Override
            public int getUid() {
                return 1000;
            }

            @Override
            public Notification getNotification() {
                throw new IllegalStateException("broken");
            }
        };
        assertEquals(shown, visible(broken, false));
    }

    @Test
    public void channelsHiddenFromOtherListeners() throws Throwable {
        hook();
        // The changes go to all listeners through the method of four arguments
        List<Integer> changed = hooked("notifyNotificationChannelChanged");
        assertEquals(1, changed.size());
        assertEquals(String.class, ((Method) members.get(changed.get(0))).getParameterTypes()[0]);
        assertEquals(List.of(true), skipped("notifyNotificationChannelChanged", SETTINGS, null, channel("xlua_usage"), 1));
        assertEquals(List.of(true), skipped("notifyNotificationChannelChanged", "android", null, channel("xlua"), 2));
        assertEquals(List.of(false), skipped("notifyNotificationChannelChanged", "android", null, channel("DEVELOPER"), 1));
        assertEquals(List.of(false), skipped("notifyNotificationChannelChanged", "com.example", null, channel("xlua"), 1));
        assertEquals(List.of(false), skipped("notifyNotificationChannelChanged", "android", null, null, 1));

        // The channels a listener with a companion device lists, of which the list is made for the call
        assertEquals(0, hooked("getNotificationChannelsFromPrivilegedListener").size());
        start();
        int i = hooked("getNotificationChannelsFromPrivilegedListener").get(0);
        List<Channel> channels = new ArrayList<>(List.of(channel("xlua"), channel("DEVELOPER"), channel("xlua_usage")));
        XCall list = new XCall(members.get(i), null, new Object[]{null, SETTINGS, null});
        list.setOutcome(new ParceledListSlice<>(channels), null);
        callbacks.get(i).after(list);
        assertEquals(1, channels.size());
        assertEquals("DEVELOPER", channels.get(0).getId());

        List<Channel> app = new ArrayList<>(List.of(channel("xlua")));
        XCall other = new XCall(members.get(i), null, new Object[]{null, "com.example", null});
        other.setOutcome(new ParceledListSlice<>(app), null);
        callbacks.get(i).after(other);
        assertEquals(1, app.size());

        // The shared empty list of a package without channels, and the exception of a listener without a companion device
        XCall none = new XCall(members.get(i), null, new Object[]{null, "android", null});
        none.setOutcome(new ParceledListSlice<>(Collections.emptyList()), null);
        callbacks.get(i).after(none);
        XCall refused = new XCall(members.get(i), null, new Object[]{null, "android", null});
        SecurityException ex = new SecurityException("Disallowed call from listener");
        refused.setOutcome(null, ex);
        callbacks.get(i).after(refused);
        assertEquals(ex, refused.getThrowable());
    }

    @Test
    public void accessibilityEventsLeftOut() throws Throwable {
        hook();
        // Up to Android 11 and from Android 12 in the service, from Android 15 in the helper which alerts
        List<Boolean> all = List.of(true, true, true);
        List<Boolean> none = List.of(false, false, false);
        assertEquals(all, skipped("sendAccessibilityEvent", sbn(SETTINGS, "xlua_use_Use.Camera", "xlua_usage")));
        assertEquals(all, skipped("sendAccessibilityEvent", sbn("android", "xlua_new_app", "xlua")));
        assertEquals(none, skipped("sendAccessibilityEvent", sbn("com.example", "xlua_exception", "xlua")));
        assertEquals(none, skipped("sendAccessibilityEvent", sbn("android", null, "DEVELOPER")));

        // A record of a subclass, which a vendor may make, from Android 12 in the service and from Android 15 in the helper
        List<Boolean> subclass = new ArrayList<>();
        for (int i : hooked("sendAccessibilityEvent"))
            if (((Method) members.get(i)).getParameterCount() == 1) {
                NotificationRecord record = new NotificationRecord(sbn(SETTINGS, "xlua_exception", "xlua")) {
                };
                XCall call = new XCall(members.get(i), null, new Object[]{record});
                callbacks.get(i).before(call);
                subclass.add(call.isReturnEarly());
            }
        assertEquals(List.of(true, true), subclass);
    }

    @Test
    public void usageEventsLeftOut() throws Throwable {
        hook();
        // Seen and used, which Android reports for the package without the channel, only by package name
        List<Integer> reported = hooked("reportEvent");
        assertEquals(1, reported.size());
        assertEquals(String.class, ((Method) members.get(reported.get(0))).getParameterTypes()[0]);
        String event = "reportEvent";
        assertEquals(List.of(true), skipped(event, SETTINGS, 0, 7));
        assertEquals(List.of(true), skipped(event, SETTINGS, 10, 10));
        assertEquals(List.of(false), skipped(event, SETTINGS, 0, 1));
        assertEquals(List.of(false), skipped(event, "android", 0, 7));
        assertEquals(List.of(false), skipped(event, "com.example", 0, 10));

        // The interruption, which the local service of usage stats gets with the channel
        String interrupted = "reportInterruptiveNotification";
        assertEquals(List.of(true), skipped(interrupted, SETTINGS, "xlua_usage", 0));
        assertEquals(List.of(true), skipped(interrupted, SETTINGS, "xlua", 10));
        assertEquals(List.of(false), skipped(interrupted, "android", "DEVELOPER", 0));
        assertEquals(List.of(false), skipped(interrupted, "com.example", "xlua", 0));
    }

    @Test
    public void callersDeoptimized() throws Throwable {
        hook();
        // The callers of the hooked methods of all versions, the accessor of the private method and no other methods
        Set<String> callers = new TreeSet<>(List.of(
                "NotificationAttentionHelper.buzzBeepBlinkLocked",
                "NotificationManagerService$NotificationAssistants.notifyAssistantFeedbackReceived",
                "NotificationManagerService$NotificationAssistants.notifyAssistantLocked",
                "NotificationManagerService$NotificationAssistants.notifyAssistantSnoozedLocked",
                "NotificationManagerService$NotificationAssistants.onNotificationEnqueued",
                "NotificationManagerService$NotificationAssistants.onNotificationEnqueuedLocked",
                "NotificationManagerService$NotificationAssistants.onNotificationsSeenLocked",
                "NotificationManagerService$NotificationListeners.notifyPostedLocked",
                "NotificationManagerService$NotificationListeners.notifyRankingUpdateLocked",
                "NotificationManagerService$NotificationListeners.notifyRemovedLocked",
                "NotificationManagerService$NotificationListeners.prepareNotifyPostedLocked",
                "NotificationManagerService$NotificationListeners.updateUriPermissionsForActiveNotificationsLocked",
                "NotificationManagerService$SnoozeNotificationRunnable.snoozeNotificationLocked",
                "NotificationManagerService.access$4300",
                "NotificationManagerService.buzzBeepBlinkLocked",
                "NotificationManagerService.makeRankingUpdateLocked",
                "NotificationManagerService.maybeRecordInterruptionLocked",
                "NotificationManagerService.reportSeen",
                "NotificationManagerService.reportUserInteraction"));
        assertEquals(callers, deoptimized);

        // Those of the binder and the delegate once they exist
        start();
        callers.addAll(List.of(
                "NotificationManagerService$1.addToListIfNeeded",
                "NotificationManagerService$1.getActiveNotificationsFromListener",
                "NotificationManagerService$1.getSnoozedNotificationsFromListener",
                "NotificationManagerService$1.setNotificationsShownFromListener",
                "NotificationManagerService$2.onNotificationActionClick",
                "NotificationManagerService$2.onNotificationClick",
                "NotificationManagerService$2.onNotificationDirectReplied",
                "NotificationManagerService$2.onNotificationExpansionChanged",
                "NotificationManagerService$2.onNotificationSmartReplySent",
                "NotificationManagerService$2.onNotificationVisibilityChanged"));
        assertEquals(callers, deoptimized);
    }

    // Stand-in for ContextImpl, of which a package context has the package and keeps the user; it remembers the packages
    // of the package contexts made from it
    public static class PackageContext extends ContextWrapper {
        private final String pkg;
        final List<String> made = new ArrayList<>();

        PackageContext(String pkg) {
            super(null);
            this.pkg = pkg;
        }

        @Override
        public String getPackageName() {
            return pkg;
        }

        @Override
        public Context createPackageContext(String packageName, int flags) {
            made.add(packageName);
            return new PackageContext(packageName);
        }
    }

    @Test
    public void postedBySettingsProvider() throws Throwable {
        // The service posts with the context of the settings provider; the receiver of new and removed apps, with one of
        // the framework, gets one of the settings provider for the user
        Context settings = new PackageContext(SETTINGS);
        assertSame(settings, XNotify.getSettingsContext(settings));
        assertEquals(SETTINGS, XNotify.getSettingsContext(new PackageContext("android")).getPackageName());

        // The receiver posts new apps and cancels the notifications of a removed app with its context of the framework,
        // so these two must move into the settings provider themselves; the stand-in has no package manager and no
        // notification manager, so the calls fail right after that
        PackageContext framework = new PackageContext("android");
        assertThrows(NullPointerException.class, () -> XNotify.notifyNewApp(framework, 0, 10123, "com.example"));
        assertEquals(List.of(SETTINGS), framework.made);
        assertThrows(NullPointerException.class, () -> XNotify.cancelApp(framework, 0, 10123));
        assertEquals(List.of(SETTINGS, SETTINGS), framework.made);
    }

    // The notification of an app is updated at most each MIN_INTERVAL per tag, so an app which reports in a loop, or a hook
    // which notifies on each use, does not keep the notification service busy; other tags and apps are not held back
    @Test
    public void notificationsOfAnAppAreLimitedPerTag() {
        java.util.Map<String, Long> updated = new java.util.HashMap<>();
        long now = 100000;
        assertTrue(XNotify.due(updated, "0:10123:xlua_use_Get.Location", now));
        assertFalse(XNotify.due(updated, "0:10123:xlua_use_Get.Location", now + 1));
        assertFalse(XNotify.due(updated, "0:10123:xlua_use_Get.Location", now + XNotify.MIN_INTERVAL - 1));
        assertTrue(XNotify.due(updated, "0:10123:xlua_use_Read.Network", now + 1));
        assertTrue(XNotify.due(updated, "0:10124:xlua_use_Get.Location", now + 1));
        assertTrue(XNotify.due(updated, "10:10123:xlua_use_Get.Location", now + 1));
        assertTrue(XNotify.due(updated, "0:10123:xlua_use_Get.Location", now + XNotify.MIN_INTERVAL));
        assertFalse(XNotify.due(updated, "0:10123:xlua_use_Get.Location", now + XNotify.MIN_INTERVAL + 1));
        // A loop of 10,000 reports in 30 seconds updates it a few times, not 10,000
        int updates = 0;
        for (int i = 0; i < 10000; i++)
            if (XNotify.due(updated, "0:10200:xlua_exception", now + i * 3L))
                updates++;
        assertEquals(6, updates);
        // A clock which went back does not hold it back until it catches up
        assertTrue(XNotify.due(updated, "0:10200:xlua_exception", 5));
    }

    // The table does not grow without limit
    @Test
    public void trackedNotificationsAreBounded() {
        java.util.Map<String, Long> updated = new java.util.HashMap<>();
        for (int i = 0; i < 3 * XNotify.MAX_TRACKED; i++)
            XNotify.due(updated, "0:" + i + ":xlua_exception", 1000);
        assertTrue(updated.size() <= XNotify.MAX_TRACKED + 1);
    }
}
