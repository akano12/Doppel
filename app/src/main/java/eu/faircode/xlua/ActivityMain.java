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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;

// Top level screens: apps, activity, hooks and settings
public class ActivityMain extends ActivityBase implements Ui.Anchored {
    private static final String TAG = "XLua.Main";

    // Opens the restrictions of an app, used by notifications
    public static final String EXTRA_PACKAGE = "package";

    private static final String TAB_APPS = "apps";
    private static final String TAB_ACTIVITY = "activity";
    private static final String TAB_HOOKS = "hooks";
    private static final String TAB_SETTINGS = "settings";

    private static final String STATE_AVAILABLE = "available";

    private BottomNavigationView nav;
    private boolean available = false;
    private Runnable checked = null; // What to show after checking the service, when the screen was left meanwhile

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The tabs restored by the framework need the main layout right away
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_AVAILABLE)) {
            showMain(false);
            return;
        }

        // Checking the service is a call into system server, so not on the main thread
        final Context context = getApplicationContext();
        run(TAG, () -> {
            boolean available = Data.isAvailable(context);
            // The local copies of the colors do not exist yet after an update
            ApplicationEx.Colors colors = (available ? ApplicationEx.Colors.get(context) : null);
            postUnlessGone(() -> {
                Runnable show = () -> {
                    if (available) {
                        // The colors are applied when the screen is created
                        if (ApplicationEx.sync(context, colors)) {
                            recreate();
                            return;
                        }
                        showMain(true);
                    } else
                        showUnavailable();
                };
                // Tabs cannot be added after the state of the screen was saved, like when it was left meanwhile
                if (getSupportFragmentManager().isStateSaved())
                    checked = show;
                else
                    show.run();
            });
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (checked != null) {
            Runnable show = checked;
            checked = null;
            show.run();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_AVAILABLE, available);
    }

    private void showUnavailable() {
        setContentView(R.layout.activity_unavailable);
        findViewById(R.id.btnRetry).setOnClickListener(view -> recreate());
        findViewById(R.id.btnDocs).setOnClickListener(view -> Ui.browse(this, Ui.URL_README));
    }

    private void showMain(boolean first) {
        available = true;
        setContentView(R.layout.activity_main);
        nav = findViewById(R.id.nav);
        nav.setOnItemSelectedListener(item -> {
            show(getTab(item.getItemId()));
            return true;
        });

        // Else the selected tab and fragments are restored by the framework
        if (first) {
            show(TAB_APPS);
            handleIntent(getIntent());
        }

        checkFirstRun();
    }

    @Override
    public View getMessageAnchor() {
        // Null without the main layout, which places messages at the bottom
        return findViewById(R.id.nav);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (available)
            handleIntent(intent);
    }

    // Only the package name is taken from the intent, for a new intent to the screen of the app, which is not exported;
    // lint takes the factory ActivityApp.getIntent for the intent this activity was started with, see lint.xml
    private void handleIntent(Intent intent) {
        String packageName = intent.getStringExtra(EXTRA_PACKAGE);
        if (packageName != null) {
            Log.i(TAG, "Show pkg=" + packageName);
            intent.removeExtra(EXTRA_PACKAGE);
            startActivity(ActivityApp.getIntent(this, packageName, -1));
        }
    }

    // Opens a tab, like the overview card of the Apps tab opens the Activity tab
    void selectTab(int itemId) {
        if (nav != null)
            nav.setSelectedItemId(itemId);
    }

    private static String getTab(int itemId) {
        if (itemId == R.id.nav_activity)
            return TAB_ACTIVITY;
        else if (itemId == R.id.nav_hooks)
            return TAB_HOOKS;
        else if (itemId == R.id.nav_settings)
            return TAB_SETTINGS;
        else
            return TAB_APPS;
    }

    // Tabs are kept, so they keep their scroll position and filters
    private void show(String tab) {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction().setReorderingAllowed(true);
        for (Fragment fragment : fm.getFragments())
            if (!tab.equals(fragment.getTag()) && !fragment.isHidden())
                ft.hide(fragment);
        Fragment fragment = fm.findFragmentByTag(tab);
        if (fragment == null)
            ft.add(R.id.content, createTab(tab), tab);
        else
            ft.show(fragment);
        ft.commit();
    }

    private static Fragment createTab(String tab) {
        switch (tab) {
            case TAB_ACTIVITY:
                return new FragmentLog();
            case TAB_HOOKS:
                return new FragmentHooks();
            case TAB_SETTINGS:
                return new FragmentSettings();
            default:
                return new FragmentApps();
        }
    }

    private void checkFirstRun() {
        final SharedPreferences prefs = ApplicationEx.getPreferences(this);
        if (!prefs.getBoolean("firstrun", true))
            return;

        Ui.getLicenseDialog(this)
                .setCancelable(false)
                .setPositiveButton(R.string.title_accept, (dialog, which) ->
                        prefs.edit().putBoolean("firstrun", false).apply())
                .setNegativeButton(R.string.title_deny, (dialog, which) -> finishAffinity())
                .show();
    }
}
