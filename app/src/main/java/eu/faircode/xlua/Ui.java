/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.
 */

package eu.faircode.xlua;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.core.graphics.ColorUtils;
import androidx.core.text.HtmlCompat;
import androidx.core.util.Consumer;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

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

    // Run a task on the background thread of a screen, unless the screen is gone; errors go to onError
    static void submit(ExecutorService executor, Task task, Consumer<Throwable> onError) {
        if (executor.isShutdown())
            return;
        executor.submit(() -> {
            try {
                task.run();
            } catch (Throwable ex) {
                onError.accept(ex);
            }
        });
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
    static final String URL_README = "https://github.com/akano12/XPrivacyLua/blob/master/README.md";
    static final String URL_FAQ = "https://github.com/akano12/XPrivacyLua/blob/master/FAQ.md";

    private Ui() {
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int color(Context context, int attr) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    // Rows which form one block, like a card: the first row has the top corners rounded, the last the bottom ones
    static void join(View view, int color, int radiusDp, boolean first, boolean last) {
        float radius = dp(view.getContext(), radiusDp);
        float top = (first ? radius : 0);
        float bottom = (last ? radius : 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
        view.setBackground(shape);
    }

    // The hairline between the rows of a block, a shade of their background
    static int dividerColor(Context context, int background) {
        return ColorUtils.blendARGB(background, color(context, com.google.android.material.R.attr.colorOnSurface), 0.08f);
    }

    // Enables or disables the children, like the chips of a group
    static void enable(ViewGroup group, boolean enabled) {
        for (int i = 0; i < group.getChildCount(); i++)
            group.getChildAt(i).setEnabled(enabled);
    }

    // Rounded label, like 'built-in' or 'system'
    static TextView addBadge(ViewGroup parent, String text, Badge style) {
        Context context = parent.getContext();
        TextView badge = (TextView) LayoutInflater.from(context).inflate(R.layout.badge, parent, false);
        badge.setText(text);
        badge.setTextColor(color(context, style.text));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(context, 6));
        background.setColor(color(context, style.background));
        badge.setBackground(background);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(parent.getContext(), 6));
        parent.addView(badge, lp);
        return badge;
    }

    static void loadIcon(ImageView iv, String packageName, int icon) {
        if (icon <= 0) {
            iv.setImageResource(android.R.drawable.sym_def_app_icon);
            return;
        }
        int size = dp(iv.getContext(), 48);
        Glide.with(iv.getContext())
                .applyDefaultRequestOptions(new RequestOptions().format(DecodeFormat.PREFER_RGB_565))
                .load(Uri.parse("android.resource://" + packageName + "/" + icon))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .override(size, size)
                .into(iv);
    }

    // Like '5 min. ago', or 'just now' within a minute, which DateUtils calls '0 min. ago'
    static CharSequence ago(Context context, long time) {
        long now = System.currentTimeMillis();
        if (now - time < DateUtils.MINUTE_IN_MILLIS)
            return context.getString(R.string.title_just_now);
        return DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
    }

    // A file name with today's date, like xprivacylua-backup-2026-10-20.json, so backups can be told apart
    static String datedFileName(String name) {
        return "xprivacylua-" + name + "-" +
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

    static void message(Activity activity, CharSequence message, CharSequence action, Runnable onAction) {
        activity.runOnUiThread(() -> {
            Snackbar snackbar = Snackbar.make(activity.findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG);
            if (activity instanceof Anchored)
                snackbar.setAnchorView(((Anchored) activity).getMessageAnchor());
            if (action != null)
                snackbar.setAction(action, view -> onAction.run());
            snackbar.show();
        });
    }

    // Log an error and show it, from any thread; the activity can be gone already
    static void error(Activity activity, String tag, Throwable ex) {
        error(activity, tag, ex, null);
    }

    // Log an error and show it, with an action to try again
    static void error(Activity activity, String tag, Throwable ex, Runnable retry) {
        Log.e(tag, Log.getStackTraceString(ex));
        if (activity != null)
            message(activity, activity.getString(R.string.msg_error, ex.toString()),
                    retry == null ? null : activity.getString(R.string.title_retry), retry);
    }

    // Start an app, else say that it cannot be started, like most system apps
    static void launch(Activity activity, String packageName, String label) {
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null)
            message(activity, activity.getString(R.string.msg_cannot_open, label));
        else
            activity.startActivity(launch);
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
        tvText.setTypeface(Typeface.MONOSPACE);
        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    static void showLicense(Context context) {
        getLicenseDialog(context)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // The license with working links, without buttons
    static MaterialAlertDialogBuilder getLicenseDialog(Context context) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_text, null, false);
        TextView tvText = view.findViewById(R.id.tvText);
        tvText.setMovementMethod(LinkMovementMethod.getInstance());
        int year = Calendar.getInstance().get(Calendar.YEAR);
        tvText.setText(HtmlCompat.fromHtml(context.getString(R.string.title_license, year), HtmlCompat.FROM_HTML_MODE_LEGACY));
        return new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.app_name)
                .setView(view);
    }

    // Search matching ignoring case: ids are matched with the root locale,
    // labels in the language of the device (like the Turkish dotless i)
    static boolean contains(String text, String query) {
        return (text != null &&
                (text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)) ||
                        text.toLowerCase(Locale.getDefault()).contains(query.toLowerCase(Locale.getDefault()))));
    }

    // Text of a search field, after each change
    static void onTextChanged(EditText editText, Consumer<String> listener) {
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                listener.accept(s.toString());
            }
        });
    }

    // Filter chips with one chip id per value, in the same order; selects a value and reports selections
    static <E> void chips(ChipGroup group, int[] ids, E[] values, E selected, Consumer<E> listener) {
        group.check(ids[Arrays.asList(values).indexOf(selected)]);
        group.setOnCheckedStateChangeListener((g, checkedIds) -> {
            if (checkedIds.isEmpty())
                return;
            for (int i = 0; i < ids.length; i++)
                if (ids[i] == checkedIds.get(0))
                    listener.accept(values[i]);
        });
    }

    // The button names the action, like Delete, so an action which cannot be undone is not confirmed with OK
    static void areYouSure(Context context, CharSequence question, @StringRes int positive, Runnable onSure) {
        new MaterialAlertDialogBuilder(context)
                .setMessage(question)
                .setPositiveButton(positive, (dialog, which) -> onSure.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
