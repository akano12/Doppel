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
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

// Notifications of the service in system server, shown to the user of an app.
// The context is a context of the user, with the resources of system server.
final class XNotify {
    private static final String TAG = "XLua.Notify";

    // New apps and errors, which pop up; restrictions, which do not. The importance of a channel cannot be changed,
    // so the former channel of all notifications is the channel which pops up.
    private static final String CHANNEL_ALERTS = "xlua";
    private static final String CHANNEL_USAGE = "xlua_usage";

    // The tags of all notifications start with xlua_, the id is the uid of the app
    private static final String TAG_PREFIX = "xlua_";
    private static final String TAG_NEW_APP = TAG_PREFIX + "new_app";
    private static final String TAG_EXCEPTION = TAG_PREFIX + "exception";
    private static final String TAG_USAGE = TAG_PREFIX + "use_"; // + group
    private static final String TAG_DATA_LOST = TAG_PREFIX + "data_lost";
    private static final String TAG_DATA_FAILED = TAG_PREFIX + "data_failed";

    private static final String APP_NAME = "Doppel"; // Like app_name, which is not translated

    // Resources of the module version which runs, see loadResources
    private static volatile Resources resources = null;

    // The users for whom the channels exist in the package of the settings provider, see getSettingsContext,
    // and the hidden method which posts, looked up once
    private static final Set<Integer> channels = ConcurrentHashMap.newKeySet();
    private static volatile Method notifyMethod = null;

    // The field of a listener of system server which tells whether the system itself registered it, see hideFromListeners
    private static volatile Field systemField = null;

    // The methods of all versions which call isVisibleToListener, sendAccessibilityEvent or the reports to usage stats,
    // see deoptimize
    private static final Set<String> CALLERS = new HashSet<>(Arrays.asList(
            "getActiveNotificationsFromListener", "getSnoozedNotificationsFromListener", "addToListIfNeeded",
            "makeRankingUpdateLocked", "notifyPostedLocked", "prepareNotifyPostedLocked", "notifyRemovedLocked",
            "notifyRankingUpdateLocked", "updateUriPermissionsForActiveNotificationsLocked", "onNotificationEnqueued",
            "onNotificationEnqueuedLocked", "notifyAssistantSnoozedLocked", "onNotificationsSeenLocked",
            "notifyAssistantLocked", "notifyAssistantFeedbackReceived", "buzzBeepBlinkLocked",
            "reportSeen", "reportUserInteraction", "maybeRecordInterruptionLocked", "setNotificationsShownFromListener",
            "snoozeNotificationLocked", "onNotificationVisibilityChanged", "onNotificationClick", "onNotificationActionClick",
            "onNotificationDirectReplied", "onNotificationSmartReplySent", "onNotificationExpansionChanged"));

    private XNotify() {
    }

    // The code in system server keeps running after an update of the module until a reboot,
    // with the resource ids of its own version, which an update can change, so the resources are kept
    static void loadResources(Context context) throws PackageManager.NameNotFoundException {
        resources = context.getPackageManager().getResourcesForApplication(BuildConfig.APPLICATION_ID);
    }

    // An app used a restricted function; hooks which notify on each use update the notification without alerting
    static void notifyUsage(Context context, int userid, int uid, String packageName, String group, String hookid)
            throws Throwable {
        context = getSettingsContext(context);
        Resources resources = getResources(context);
        Notification.Builder builder = getBuilder(context, false);
        builder.setOnlyAlertOnce(true);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info);
        builder.setContentTitle(resources.getString(R.string.msg_usage, Groups.getTitle(resources, group)));
        builder.setContentText(getLabel(context, packageName));
        if (BuildConfig.DEBUG)
            builder.setSubText(hookid);
        builder.setContentIntent(getAppIntent(context, uid, packageName));
        notifyAsUser(context, TAG_USAGE + group, uid, builder.build(), userid);
    }

    // A hook of an app failed
    static void notifyException(Context context, int userid, int uid, String packageName, String hookid) throws Throwable {
        context = getSettingsContext(context);
        Resources resources = getResources(context);
        Notification.Builder builder = getBuilder(context, true);
        builder.setSmallIcon(android.R.drawable.ic_dialog_alert);
        // Several hooks of an app can fail at once, each with its own report
        builder.setOnlyAlertOnce(true);
        builder.setContentTitle(resources.getString(R.string.msg_exception, hookid));
        builder.setContentText(getLabel(context, packageName));
        builder.setContentIntent(getAppIntent(context, uid, packageName));
        notifyAsUser(context, TAG_EXCEPTION, uid, builder.build(), userid);
    }

    // The database was corrupt and is gone (lost), so no app is restricted anymore, which looks like a fresh install;
    // or the service could not load its data, so the restricted apps which start meanwhile run without hooks
    static void notifyData(Context context, int userid, boolean lost) throws Throwable {
        context = getSettingsContext(context);
        Resources resources = getResources(context);
        Notification.Builder builder = getBuilder(context, true);
        builder.setSmallIcon(android.R.drawable.ic_dialog_alert);
        builder.setContentTitle(resources.getString(lost ? R.string.msg_data_lost : R.string.msg_data_failed));
        builder.setContentText(resources.getString(lost ? R.string.msg_data_lost_hint : R.string.msg_data_failed_hint));
        Intent main = new Intent()
                .setClassName(BuildConfig.APPLICATION_ID, ActivityMain.class.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        builder.setContentIntent(PendingIntent.getActivity(context, 0, main,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        notifyAsUser(context, lost ? TAG_DATA_LOST : TAG_DATA_FAILED, 0, builder.build(), userid);
    }

    // An app was installed
    static void notifyNewApp(Context context, int userid, int uid, String packageName) throws Throwable {
        context = getSettingsContext(context);
        Notification.Builder builder = getBuilder(context, true);
        builder.setSmallIcon(android.R.drawable.ic_dialog_alert);
        builder.setContentTitle(getResources(context).getString(R.string.msg_review_settings));
        builder.setContentText(getLabel(context, packageName));
        builder.setContentIntent(getAppIntent(context, uid, packageName));
        notifyAsUser(context, TAG_NEW_APP, uid, builder.build(), userid);
    }

    // Other apps with notification access, like the companion app of a watch, would get these notifications, and with them
    // that Doppel runs and which apps it restricts: they go only to the listeners the system registered itself, the status
    // bar, so other listeners see what they see on a device without Doppel, where these notifications do not exist.
    // This check also serves their lists of active and snoozed notifications, the ranking and the removals. The changes
    // of Doppel's channels and the list of channels of a listener with a companion device leave them out too, usage stats,
    // which apps with usage access read, get no event of them, and accessibility services get no notification event of
    // them, so screen readers do not announce them, also not to the user, like for a notification which does not exist.
    // The compiler of system server may have inlined the hooked methods into their callers, out of reach of the hooks,
    // which is likely for small ones, like isVisibleToListener up to Android 11, so their callers run in the interpreter.
    // shortcut: not yet seen to work on a device, and what R8 inlined when the platform was built, what the compiler
    // inlined into a caller of a caller or a legacy framework without deoptimizeMethod still bypasses the hooks;
    // probe a listener, an app with usage access and an accessibility service on each version. All callers run in the
    // interpreter from boot, also where no app is restricted, like makeRankingUpdateLocked, which notifyPostedLocked
    // and notifyRankingUpdateLocked run for each listener over all notifications under the lock of the notifications,
    // which costs system server time on each post, not measured; keep only the callers such a probe shows skipping a
    // hook without it
    // shortcut: an accessibility service can still read them in the shade and in the window where they pop up, a status
    // bar which a vendor does not register as system loses them, the rare notification of the settings provider itself
    // after a restore of the hotspot settings on Android 11 to 14 loses its usage events too, and a listener which guesses
    // their keys or names can still mark them shown, snooze or cancel them, update Doppel's channels with a companion
    // device or, from Android 16, make a conversation channel of them, where Android without them throws; answer that
    // if probes use it
    static void hideFromListeners(XBackend backend, ClassLoader classLoader) throws Throwable {
        // AOSP frameworks/base: services/core/java/com/android/server/notification/NotificationManagerService.java
        String name = "com.android.server.notification.NotificationManagerService";
        Class<?> service = Class.forName(name, false, classLoader);
        Class<?> listeners = Class.forName(name + "$NotificationListeners", false, classLoader);
        Class<?> assistants = Class.forName(name + "$NotificationAssistants", false, classLoader);
        Class<?> snooze = findClass(name + "$SnoozeNotificationRunnable", classLoader);
        // Before Android 15 the service alerts itself
        Class<?> attention = findClass("com.android.server.notification.NotificationAttentionHelper", classLoader);

        // boolean isVisibleToListener(StatusBarNotification sbn, [int notificationType,] ManagedServiceInfo listener)
        int hooked = backend.hookAll(service, "isVisibleToListener", new XBackend.Callback() {
            @Override
            void after(XCall call) {
                if (Boolean.TRUE.equals(call.getResult()) &&
                        hide(() -> isOwn((StatusBarNotification) call.args[0]) && !isSystem(call.args[call.args.length - 1])))
                    call.setResult(false);
            }
        });

        // protected void notifyNotificationChannelChanged(String pkg, UserHandle user, NotificationChannel channel,
        //   int modificationType), which tells the listeners with a companion device, from Android 11 the system ones too;
        // the status bar does not need the changes of Doppel's channels, which come with each notification
        for (Method method : listeners.getDeclaredMethods())
            if (method.getName().equals("notifyNotificationChannelChanged") && method.getParameterTypes()[0] == String.class) {
                backend.hook(method, new XBackend.Callback() {
                    @Override
                    void before(XCall call) {
                        if (hide(() -> call.args[2] != null && isOwn((String) call.args[0], getId(call.args[2]))))
                            call.setResult(null);
                    }
                });
                hooked++;
            }

        // void sendAccessibilityEvent(Notification notification, CharSequence packageName) up to Android 11,
        // void sendAccessibilityEvent(NotificationRecord record) from Android 12, in NotificationAttentionHelper from Android 15
        XBackend.Callback accessibility = new XBackend.Callback() {
            @Override
            void before(XCall call) {
                if (hide(() -> (call.args.length == 2
                        ? isOwn(String.valueOf(call.args[1]), ((Notification) call.args[0]).getChannelId())
                        : isOwn(getSbn(call.args[0])))))
                    call.setResult(null);
            }
        };
        hooked += backend.hookAll(service, "sendAccessibilityEvent", accessibility);
        if (attention != null)
            hooked += backend.hookAll(attention, "sendAccessibilityEvent", accessibility);

        // The events of usage stats, which apps with usage access read: from Android 9 maybeRecordInterruptionLocked calls
        // public void reportInterruptiveNotification(String packageName, String channelId, int userId) of the local service
        // next to the history of notifications, which only the system reads and which keeps them; the local service gets
        // public void reportEvent(String packageName, int userId, int eventType) when a notification is seen or used,
        // from Android 9 by reportSeen and reportUserInteraction, on Android 8.1 by setNotificationsShownFromListener,
        // which the status bar calls for each notification it shows, without the channel, but only Doppel's notifications
        // are of the settings provider in normal use, see getSettingsContext
        // AOSP frameworks/base: services/usage/java/com/android/server/usage/UsageStatsService.java
        try {
            Class<?> stats = Class.forName("com.android.server.usage.UsageStatsService$LocalService", false, classLoader);
            hooked += backend.hookAll(stats, "reportInterruptiveNotification", new XBackend.Callback() {
                @Override
                void before(XCall call) {
                    if (hide(() -> isOwn((String) call.args[0], (String) call.args[1])))
                        call.setResult(null);
                }
            });
            backend.hook(stats.getDeclaredMethod("reportEvent", String.class, int.class, int.class), new XBackend.Callback() {
                @Override
                void before(XCall call) {
                    // UsageEvents.Event.USER_INTERACTION and NOTIFICATION_SEEN, which is hidden
                    if (hide(() -> XCore.SETTINGS_PACKAGE.equals(call.args[0]) &&
                            (call.args[2].equals(UsageEvents.Event.USER_INTERACTION) || call.args[2].equals(10))))
                        call.setResult(null);
                }
            });
            hooked++;
        } catch (Throwable ex) {
            XCore.log(ex);
        }

        // The binder and the delegate, which the status bar tells what the user sees and does, are anonymous classes,
        // of which the service makes one object each
        hooked += backend.hookAll(service, "onStart", new XBackend.Callback() {
            @Override
            void after(XCall call) {
                XCore.enterService();
                try {
                    Class<?> binder = getField(service, call.thisObject, "mService").getClass();
                    // ParceledListSlice<NotificationChannel> getNotificationChannelsFromPrivilegedListener(
                    //   INotificationListener token, String pkg, UserHandle user), of which the list is made for the call
                    int count = backend.hookAll(binder, "getNotificationChannelsFromPrivilegedListener", new XBackend.Callback() {
                        @Override
                        void after(XCall call) {
                            Object slice = call.getResult();
                            if (slice != null)
                                hide(() -> {
                                    Iterator<?> channels = ((List<?>) slice.getClass().getMethod("getList").invoke(slice)).iterator();
                                    while (channels.hasNext())
                                        if (isOwn((String) call.args[1], getId(channels.next())))
                                            channels.remove();
                                    return true;
                                });
                        }
                    });
                    int deoptimized = deoptimize(backend, binder);
                    deoptimized += deoptimize(backend, getField(service, call.thisObject, "mNotificationDelegate").getClass());
                    Log.i(TAG, "Listener binder hooks=" + count + " deoptimized=" + deoptimized);
                } catch (Throwable ex) {
                    XCore.log(ex);
                } finally {
                    XCore.leaveService();
                }
            }
        });

        int deoptimized = deoptimize(backend, service, listeners, assistants, snooze, attention);
        Log.i(TAG, "Listener hooks=" + hooked + " deoptimized=" + deoptimized);
    }

    // A class which only some versions have, else null
    private static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    // The callers of the hooked methods of all versions, in the service, its binder, delegate, listeners, assistants,
    // snoozing and the helper which alerts, run in the interpreter, which calls the hooked methods also where the compiler
    // inlined them; a method which cannot be deoptimized keeps its compiled code, without stopping the others
    private static int deoptimize(XBackend backend, Class<?>... classes) {
        int count = 0;
        for (Class<?> cls : classes)
            if (cls != null)
                for (Method method : cls.getDeclaredMethods())
                    if ((CALLERS.contains(method.getName()) || isAccessor(method)) && !Modifier.isAbstract(method.getModifiers()))
                        try {
                            if (backend.deoptimize(method))
                                count++;
                            else
                                Log.w(TAG, "Cannot deoptimize " + method);
                        } catch (Throwable ex) {
                            Log.w(TAG, "Deoptimize " + method + ": " + ex);
                        }
        return count;
    }

    // Up to Android 11 the inner classes call isVisibleToListener, which is private there, through an accessor:
    // static boolean access$NNNN(NotificationManagerService service, StatusBarNotification sbn, ManagedServiceInfo listener)
    private static boolean isAccessor(Method method) {
        Class<?>[] types = method.getParameterTypes();
        return (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == boolean.class && types.length == 3 &&
                types[0] == method.getDeclaringClass() && types[1] == StatusBarNotification.class);
    }

    // A check of system server for the service, so the hooks of system server do not restrict it,
    // often under the lock of its notifications: an error keeps the answer of Android
    private static boolean hide(Callable<Boolean> check) {
        XCore.enterService();
        try {
            return check.call();
        } catch (Throwable ex) {
            XCore.log(ex);
            return false;
        } finally {
            XCore.leaveService();
        }
    }

    // Notifications of system server of any user on the channels of Doppel, also the summary which Android posts to group
    // them, which has the channel of one of them
    private static boolean isOwn(StatusBarNotification sbn) {
        return (Util.getAppId(sbn.getUid()) == Process.SYSTEM_UID && isOwnChannel(sbn.getNotification().getChannelId()));
    }

    // Without a uid: Doppel posts as system server with the package of the settings provider, see getSettingsContext;
    // earlier versions posted new apps as the framework, where their channel stays
    private static boolean isOwn(String pkg, String channel) {
        return (("android".equals(pkg) || "com.android.providers.settings".equals(pkg)) && isOwnChannel(channel));
    }

    private static boolean isOwnChannel(String channel) {
        return (CHANNEL_ALERTS.equals(channel) || CHANNEL_USAGE.equals(channel));
    }

    // The notification of a record of system server, of which the field is not public on any version and the getter of
    // Android 11 and later neither; it is read from the class which declares it, also for a subclass a vendor made
    private static StatusBarNotification getSbn(Object record) throws ReflectiveOperationException {
        for (Class<?> cls = record.getClass(); cls != null; cls = cls.getSuperclass())
            try {
                return (StatusBarNotification) getField(cls, record, "sbn");
            } catch (NoSuchFieldException ignored) {
            }
        throw new NoSuchFieldException("sbn");
    }

    // A field of an object of system server, declared by the class, of which a vendor may make a subclass
    private static Object getField(Class<?> cls, Object obj, String name) throws ReflectiveOperationException {
        Field field = cls.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(obj);
    }

    // NotificationChannel.getId by reflection, since the tests cannot stand in for the final class
    private static String getId(Object channel) throws ReflectiveOperationException {
        return (String) channel.getClass().getMethod("getId").invoke(channel);
    }

    // ManagedServiceInfo.isSystem: set for the listeners which the system registers itself, like the status bar
    private static boolean isSystem(Object listener) throws ReflectiveOperationException {
        Field field = systemField;
        if (field == null) {
            field = listener.getClass().getField("isSystem");
            systemField = field;
        }
        return field.getBoolean(listener);
    }

    // A removed user: a new user with the same id needs the channels again
    static void forgetUser(int userid) {
        channels.remove(userid);
    }

    // An app was removed: its usage, error and new app notifications, which are notifications of system server
    static void cancelApp(Context context, int userid, int uid) throws Throwable {
        context = getSettingsContext(context);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        // public void cancelAsUser(String tag, int id, UserHandle user)
        Method mCancelAsUser = nm.getClass().getDeclaredMethod(
                "cancelAsUser", String.class, int.class, UserHandle.class);

        // The active notifications of system server of the user of the context, all tags of custom groups too
        Set<String> tags = new HashSet<>();
        for (StatusBarNotification sbn : nm.getActiveNotifications()) {
            String tag = sbn.getTag();
            if (sbn.getId() == uid && tag != null && tag.startsWith(TAG_PREFIX))
                tags.add(tag);
        }

        for (String tag : tags)
            mCancelAsUser.invoke(nm, tag, uid, Util.getUserHandle(userid));
        Log.i(TAG, "Cancelled " + tags.size() + " tags of " + uid + " as " + userid);
    }

    // All notifications and channels of Doppel are in the package of the settings provider, which hosts the service and has
    // no notifications of its own in normal use, so Android does not group them with those of the framework, of which
    // a listener would then see a group without them, and they are cancelled in one package; the receiver of new and
    // removed apps has a context of the framework for the user, of which a package context keeps the user. Both keep the
    // op package of the framework, which a package context takes from the context it is made from, so Android takes the
    // posts for every user as of the calling uid 1000 of system server, under which the channels are, and the receiver
    // may cancel the posts of the service (read in AOSP, not yet checked on a device)
    static Context getSettingsContext(Context context) throws PackageManager.NameNotFoundException {
        if (XCore.SETTINGS_PACKAGE.equals(context.getPackageName()))
            return context;
        return context.createPackageContext(XCore.SETTINGS_PACKAGE, 0);
    }

    // Resources of the module, not of system server
    private static Resources getResources(Context context) throws PackageManager.NameNotFoundException {
        Resources loaded = resources;
        return (loaded == null
                ? context.getPackageManager().getResourcesForApplication(BuildConfig.APPLICATION_ID)
                : loaded);
    }

    private static CharSequence getLabel(Context context, String packageName) throws PackageManager.NameNotFoundException {
        PackageManager pm = context.getPackageManager();
        return pm.getApplicationLabel(Util.getApplicationInfo(pm, packageName, 0));
    }

    private static Notification.Builder getBuilder(Context context, boolean high) {
        Notification.Builder builder = new Notification.Builder(context, high ? CHANNEL_ALERTS : CHANNEL_USAGE);
        builder.setCategory(Notification.CATEGORY_STATUS);
        builder.setVisibility(Notification.VISIBILITY_SECRET);
        builder.setAutoCancel(true);
        // The service posts as system server, which would show as Settings Storage; system server may name the app
        // (Notification.EXTRA_SUBSTITUTE_APP_NAME, a system API)
        Bundle extras = new Bundle();
        extras.putString("android.substName", APP_NAME);
        builder.addExtras(extras);
        return builder;
    }

    // Opens the restrictions of the app, on top of the screens open in the task of Doppel, like an editor with changes
    // which are not saved: the main screen would only bring the task to the front, being the root of the task.
    // System server may start the screen, which is not exported. One pending intent per app, since apps can share a uid.
    private static PendingIntent getAppIntent(Context context, int uid, String packageName) {
        Intent app = new Intent()
                .setClassName(BuildConfig.APPLICATION_ID, ActivityApp.class.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ActivityApp.EXTRA_PACKAGE, packageName)
                .putExtra(ActivityApp.EXTRA_UID, uid);
        return PendingIntent.getActivity(context, Objects.hash(packageName, uid), app,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void notifyAsUser(Context context, String tag, int id, Notification notification, int userid) throws Throwable {
        NotificationManager nm = context.getSystemService(NotificationManager.class);

        if (!channels.contains(userid)) {
            Resources resources = getResources(context);
            NotificationChannel alerts = new NotificationChannel(CHANNEL_ALERTS,
                    resources.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH);
            alerts.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
            nm.createNotificationChannel(alerts);

            NotificationChannel usage = new NotificationChannel(CHANNEL_USAGE,
                    resources.getString(R.string.channel_usage), NotificationManager.IMPORTANCE_DEFAULT);
            usage.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
            nm.createNotificationChannel(usage);
            channels.add(userid);
        }

        // public void notifyAsUser(String tag, int id, Notification notification, UserHandle user)
        Method mNotifyAsUser = notifyMethod;
        if (mNotifyAsUser == null) {
            mNotifyAsUser = nm.getClass().getDeclaredMethod(
                    "notifyAsUser", String.class, int.class, Notification.class, UserHandle.class);
            notifyMethod = mNotifyAsUser;
        }
        mNotifyAsUser.invoke(nm, tag, id, notification, Util.getUserHandle(userid));
        Log.i(TAG, "Notified " + tag + ":" + id + " as " + userid);
    }
}
