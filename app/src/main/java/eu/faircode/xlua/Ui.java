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
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.text.HtmlCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.Calendar;

// Small view helpers shared by the screens
final class Ui {
    private Ui() {
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int color(Context context, int attr) {
        return Util.resolveColor(context, attr);
    }

    // Rounded label, like 'built-in' or 'system'
    static TextView badge(Context context, String text, int backgroundAttr, int textAttr) {
        TextView badge = (TextView) LayoutInflater.from(context).inflate(R.layout.badge, null, false);
        badge.setText(text);
        badge.setTextColor(color(context, textAttr));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(context, 6));
        background.setColor(color(context, backgroundAttr));
        badge.setBackground(background);
        return badge;
    }

    static void addBadge(ViewGroup parent, String text, int backgroundAttr, int textAttr) {
        TextView badge = badge(parent.getContext(), text, backgroundAttr, textAttr);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(parent.getContext(), 6));
        parent.addView(badge, lp);
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

    // Like '5 minutes ago'
    static CharSequence ago(long time) {
        return DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
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
            View anchor = activity.findViewById(R.id.snackbar_anchor);
            Snackbar snackbar = Snackbar.make(activity.findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG);
            if (anchor != null)
                snackbar.setAnchorView(anchor);
            if (action != null)
                snackbar.setAction(action, view -> onAction.run());
            snackbar.show();
        });
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
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_text, null, false);
        TextView tvText = view.findViewById(R.id.tvText);
        tvText.setMovementMethod(LinkMovementMethod.getInstance());
        int year = Calendar.getInstance().get(Calendar.YEAR);
        tvText.setText(HtmlCompat.fromHtml(context.getString(R.string.title_license, year), HtmlCompat.FROM_HTML_MODE_LEGACY));
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.app_name)
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    static void areYouSure(Context context, CharSequence question, Runnable onSure) {
        new MaterialAlertDialogBuilder(context)
                .setMessage(question)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> onSure.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
