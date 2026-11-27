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

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.Html;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.res.ResourcesCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

// Small view helpers shared by the screens
final class Ui {
    // Screens with a bar at the bottom, which messages are shown above
    interface Anchored {
        View getMessageAnchor();
    }

    // Work for a background thread, see ActivityBase.run and FragmentBase.run
    interface Task {
        void run() throws Throwable;
    }

    // One thread for the service calls of all screens, so the write of a screen which closes is done before the load
    // of the screen which shows next, and a recreated screen loads after the write of the one it replaces
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "XLua.Ui"));

    // Run a task on the background thread of the screens; errors go to onError
    static void submit(Task task, Consumer<Throwable> onError) {
        submit(() -> {
            try {
                task.run();
            } catch (Throwable ex) {
                onError.accept(ex);
            }
        });
    }

    static void submit(Runnable task) {
        EXECUTOR.submit(task);
    }

    // The text of a file chosen by the user, null when it has more than max bytes, which would else be read whole into
    // memory; without the byte order mark which some editors write
    static String readText(Context context, Uri uri, int max) throws IOException {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null)
                throw new IOException("Cannot open " + uri);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = is.read(buffer)) > 0) {
                if (bos.size() + length > max)
                    return null;
                bos.write(buffer, 0, length);
            }
            String text = bos.toString(StandardCharsets.UTF_8.name());
            return (text.startsWith("\uFEFF") ? text.substring(1) : text);
        }
    }

    // Colors of badges: information, what differs from built-in, what needs attention
    enum Badge {
        NEUTRAL(com.google.android.material.R.attr.colorSurfaceContainerHigh,
                com.google.android.material.R.attr.colorOnSurfaceVariant),
        ACCENT(com.google.android.material.R.attr.colorSecondaryContainer,
                com.google.android.material.R.attr.colorOnSecondaryContainer),
        WARNING(R.attr.colorWarningContainer, R.attr.colorOnWarningContainer);

        final int background;
        final int text;

        Badge(int background, int text) {
            this.background = background;
            this.text = text;
        }
    }

    // Documentation of this fork
    static final String URL_README = "https://github.com/akano12/Doppel/blob/master/README.md";
    static final String URL_FAQ = "https://github.com/akano12/Doppel/blob/master/FAQ.md";
    // What a definition or backup file may have been saved as, for the picker of an import
    static final String[] MIME_JSON = {"application/json", "text/*", "application/octet-stream"};
    // Of a message with an action, in milliseconds, see message
    private static final int ACTION_DURATION = 6000;
    // Errors can be long, like what a failed restore did, so there is time to read them to the end
    private static final int ERROR_DURATION = 12000;
    private static final int ERROR_LINES = 8;

    private Ui() {
    }

    // Like '5 min. ago', or 'just now' within a minute, which DateUtils calls '0 min. ago'
    static CharSequence ago(Context context, long time) {
        long now = System.currentTimeMillis();
        if (now - time < DateUtils.MINUTE_IN_MILLIS)
            return context.getString(R.string.title_just_now);
        return DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
    }

    // A file name with today's date, like doppel-backup-2026-10-20.json, so backups can be told apart
    static String datedFileName(String name) {
        return "doppel-" + name + "-" +
                new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()) + ".json";
    }

    static long startOfDay(long time) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(time);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    static void message(Activity activity, CharSequence message) {
        message(activity, message, null, null);
    }

    static void message(Activity started, CharSequence message, CharSequence action, Runnable onAction) {
        message(started, message, action, onAction, ACTION_DURATION);
    }

    private static void message(Activity started, CharSequence message, CharSequence action, Runnable onAction, int duration) {
        started.runOnUiThread(() -> {
            // The screen of the work can be recreated meanwhile, like rotated, then the message goes to the screen in front
            Activity activity = (started.isDestroyed() ? ActivityBase.front() : started);
            if (activity == null)
                return;
            Snackbar snackbar = Snackbar.make(activity.findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG);
            if (activity instanceof Anchored)
                snackbar.setAnchorView(((Anchored) activity).getMessageAnchor());
            // The default of two lines cuts what a failure says
            ((TextView) snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text)).setMaxLines(ERROR_LINES);
            // Time to read the message and to reach its action, like Undo; the action belongs to the screen which
            // started the work, so it is left out when that screen is gone, else tapping it would do nothing
            if (action != null && activity == started) {
                snackbar.setAction(action, view -> onAction.run());
                snackbar.setDuration(duration);
            }
            snackbar.show();
        });
    }

    // Log an error and show it, from any thread; the activity can be gone already
    static void error(Activity activity, String tag, Throwable ex) {
        error(activity, tag, ex, null);
    }

    // Log an error and show it, with an action to try again
    static void error(Activity activity, String tag, Throwable ex, Runnable retry) {
        error(activity, tag, ex, retry, retry == null ? ERROR_DURATION : ACTION_DURATION);
    }

    // What went wrong in words of the user: the message of the exception, which has no class names
    static String describe(Context context, Throwable ex) {
        String message = ex.getMessage();
        return (message == null || message.trim().isEmpty() ? context.getString(R.string.msg_error_unknown) : message);
    }

    // Screens which do not load again by themselves keep the action until it is used: Snackbar.LENGTH_INDEFINITE
    static void error(Activity activity, String tag, Throwable ex, Runnable retry, int duration) {
        Log.e(tag, Log.getStackTraceString(ex));
        if (activity != null && ex instanceof Data.ModuleVersionException)
            versionSkew(activity, ex.getMessage());
        else if (activity != null)
            // An error without a retry has OK, to close it once read
            message(activity, activity.getString(R.string.msg_error, describe(activity, ex)),
                    activity.getString(retry == null ? android.R.string.ok : R.string.title_retry),
                    retry == null ? () -> { } : retry, duration);
    }

    // The dialog of a module of another version in the service, see Data.requireSameVersion
    private static WeakReference<Dialog> skew = new WeakReference<>(null);

    // A message which stays until it is read, not for seconds like the others: every screen loads through the service,
    // so each one stays blank until the device is restarted; one dialog for all loads, not one for each
    private static void versionSkew(Activity started, String message) {
        started.runOnUiThread(() -> {
            Activity activity = (started.isDestroyed() ? ActivityBase.front() : started);
            Dialog shown = skew.get();
            if (activity == null || activity.isFinishing() || (shown != null && shown.isShowing()))
                return;
            AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
            dialog.setOwnerActivity(activity);
            dialog.show();
            skew = new WeakReference<>(dialog);
        });
    }

    // With the screen it belongs to, else its window leaks; the next load shows it again
    static void closeVersionSkew(Activity activity) {
        Dialog shown = skew.get();
        if (shown != null && shown.getOwnerActivity() == activity)
            shown.dismiss();
    }

    // Start an app, else say that it cannot be started, like most system apps
    static void launch(Activity activity, String packageName, String label) {
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null)
            message(activity, activity.getString(R.string.msg_cannot_open, label));
        else
            try {
                activity.startActivity(launch);
            } catch (ActivityNotFoundException | SecurityException ignored) {
                message(activity, activity.getString(R.string.msg_cannot_open, label));
            }
    }

    // The file picker of Android, which a ROM can have left out: a message instead of a crash
    static <I> void pick(Activity activity, ActivityResultLauncher<I> launcher, I input) {
        try {
            launcher.launch(input);
        } catch (ActivityNotFoundException ignored) {
            message(activity, activity.getString(R.string.msg_no_picker));
        }
    }

    static void browse(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
            message(activity, activity.getString(R.string.msg_no_browser));
        }
    }

    // Selectable monospaced text, like an exception
    static void showText(Context context, CharSequence title, CharSequence text) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_text, null, false);
        TextView tvText = view.findViewById(R.id.tvText);
        tvText.setText(text);
        tvText.setTypeface(ResourcesCompat.getFont(context, R.font.plex_mono));
        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // The license with working links, without buttons
    static MaterialAlertDialogBuilder getLicenseDialog(Context context) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_text, null, false);
        TextView tvText = view.findViewById(R.id.tvText);
        tvText.setMovementMethod(LinkMovementMethod.getInstance());
        // The years of the copyright of Doppel: from 2026, a range once the year is later
        int year = Calendar.getInstance().get(Calendar.YEAR);
        String years = (year > 2026 ? "2026-" + year : "2026");
        tvText.setText(Html.fromHtml(context.getString(R.string.title_license, years), Html.FROM_HTML_MODE_LEGACY));
        return new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.app_name)
                .setView(view);
    }

    // Search matching ignoring case: ids are matched with the root locale,
    // labels in the language of the device (like the Turkish dotless i)
    static boolean contains(String text, String query) {
        return new Query(query).in(text);
    }

    // A search text lower-cased once, for the many texts of a list which it is matched with
    static final class Query {
        private final String root;
        private final String local;

        Query(String query) {
            root = query.toLowerCase(Locale.ROOT);
            local = query.toLowerCase(Locale.getDefault());
        }

        boolean in(String text) {
            return (text != null &&
                    (text.toLowerCase(Locale.ROOT).contains(root) || text.toLowerCase(Locale.getDefault()).contains(local)));
        }
    }

    // The texts of a row of a list, lower-cased once for all searches, instead of for each letter typed
    static final class Haystack {
        private final String root;
        private final String local;

        Haystack(String... texts) {
            // A line each, so a search text, which has no line break, matches one text only
            StringBuilder joined = new StringBuilder();
            for (String text : texts)
                joined.append(text == null ? "" : text).append('\n');
            root = joined.toString().toLowerCase(Locale.ROOT);
            local = joined.toString().toLowerCase(Locale.getDefault());
        }

        boolean has(Query query) {
            return (root.contains(query.root) || local.contains(query.local));
        }
    }

    // Names below a question, each on a line of its own
    static String bullets(List<String> names) {
        return "\n• " + TextUtils.join("\n• ", names);
    }

    // The button names the action, like Delete, so an action which cannot be undone is not confirmed with OK
    static AlertDialog areYouSure(Context context, CharSequence question, @StringRes int positive, Runnable onSure) {
        return new MaterialAlertDialogBuilder(context)
                .setMessage(question)
                .setPositiveButton(positive, (dialog, which) -> onSure.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // Restricting all of system server can make the device boot loop, so it asks first
    static void restrictAll(Context context, XApp app, Runnable restrict) {
        if (app.isSystemServer())
            areYouSure(context, context.getString(R.string.title_bootloop), R.string.title_restrict_all_groups, restrict);
        else
            restrict.run();
    }
}
