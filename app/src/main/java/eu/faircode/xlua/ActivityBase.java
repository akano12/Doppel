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

    Copyright 2017-2019 Marcel Bokhorst (M66B)
 */

package eu.faircode.xlua;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Screens are drawn edge-to-edge and share a background thread for service calls
public class ActivityBase extends AppCompatActivity {
    protected final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
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

    // Toolbar with an up button, which finishes the screen
    protected MaterialToolbar setupToolbar(CharSequence title) {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(title);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationContentDescription(R.string.title_back);
        toolbar.setNavigationOnClickListener(view -> finish());
        return toolbar;
    }

    // Log an error and show it, from any thread
    protected void error(String tag, Throwable ex) {
        Ui.error(this, tag, ex);
    }

    // Run on the background thread, errors are shown
    protected void run(String tag, Ui.Task task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (Throwable ex) {
                error(tag, ex);
            }
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
