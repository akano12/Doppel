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

    Copyright 2017-2019 Marcel Bokhorst (M66B)
    Copyright 2026 akano12
 */

package eu.faircode.xlua;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.util.Supplier;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Screens are drawn edge-to-edge and share a background thread for service calls
public class ActivityBase extends AppCompatActivity {
    private static final String TAG = "XLua.Base";

    protected final ExecutorService executor = Executors.newSingleThreadExecutor();

    // The screen before it was recreated for other colors, see recreateSmoothly
    private static Bitmap snapshot = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ApplicationEx.applyPalette(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
    }

    // The icon of the launcher is not switched while another app returns a result, see LauncherIcons
    @Override
    public void startActivityForResult(Intent intent, int requestCode, @Nullable Bundle options) {
        if (requestCode >= 0)
            LauncherIcons.setAwaitingResult(true);
        try {
            super.startActivityForResult(intent, requestCode, options);
        } catch (RuntimeException ex) {
            LauncherIcons.setAwaitingResult(false);
            throw ex;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        LauncherIcons.setAwaitingResult(false);
        super.onActivityResult(requestCode, resultCode, data);
    }

    // The layout keeps clear of the system bars, display cutouts and the keyboard
    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        View root = ((ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() |
                    WindowInsetsCompat.Type.displayCutout() |
                    WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    // Toolbar with an up button, which goes back, like the back gesture
    protected MaterialToolbar setupToolbar(CharSequence title) {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(title);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationContentDescription(R.string.title_back);
        toolbar.setNavigationOnClickListener(view -> getOnBackPressedDispatcher().onBackPressed());
        return toolbar;
    }

    // Going back from a screen with changes which are not saved asks first
    protected void confirmLeaving(Supplier<Boolean> changed) {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (changed.get())
                    confirmDiscard(ActivityBase.this::finish, null);
                else
                    finish();
            }
        });
    }

    // Discard changes which are not saved, or keep them
    protected void confirmDiscard(Runnable onDiscard, @Nullable Runnable onKeep) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.msg_discard_changes)
                .setPositiveButton(R.string.title_discard, (dialog, which) -> onDiscard.run())
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    if (onKeep != null)
                        onKeep.run();
                })
                .setCancelable(false)
                .show();
    }

    // Log an error and show it, from any thread
    protected void error(String tag, Throwable ex) {
        Ui.error(this, tag, ex);
    }

    // Run on the background thread, errors are shown; not anymore when the screen is gone
    protected void run(String tag, Ui.Task task) {
        Ui.submit(executor, task, ex -> error(tag, ex));
    }

    // Run on the background thread, an error is shown with a retry: for loads the screen cannot do without
    protected void run(String tag, Ui.Task task, Runnable retry) {
        Ui.submit(executor, task, ex -> Ui.error(this, tag, ex, retry));
    }

    // Run on the main thread, from a background thread, unless the screen is closing or gone meanwhile,
    // because views cannot load images anymore then (Glide refuses a destroyed activity)
    protected void postUnlessGone(Runnable runnable) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed())
                runnable.run();
        });
    }

    // Recreate the screen for other colors, which fades from the old screen into the new one, like a theme change;
    // without the fade the new screen shows for a moment without the data, which is loaded in the background.
    // A screen which is not in the foreground has no surface to copy, like after a restore which ended in the background.
    void recreateSmoothly() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || decor.getWidth() == 0 || decor.getHeight() == 0 ||
                !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            recreate();
            return;
        }
        Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(getWindow(), bitmap, result -> {
                // A snapshot without the recreated screen would be shown over the next screen
                if (isFinishing() || isDestroyed())
                    return;
                if (result == PixelCopy.SUCCESS)
                    snapshot = bitmap;
                recreate();
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException ex) {
            // The surface can also be gone just before or after resuming
            Log.w(TAG, "No snapshot: " + ex);
            bitmap.recycle();
            recreate();
        }
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (snapshot == null)
            return;

        // The old screen over the new one, until the data is shown
        ImageView overlay = new ImageView(this);
        overlay.setScaleType(ImageView.ScaleType.FIT_XY);
        overlay.setImageBitmap(snapshot);
        snapshot = null;
        ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        decor.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.animate().alpha(0).setStartDelay(300).setDuration(250)
                .withEndAction(() -> decor.removeView(overlay))
                .start();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
