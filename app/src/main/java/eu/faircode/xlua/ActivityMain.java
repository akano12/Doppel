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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.annotation.NonNull;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.RecyclerView;

// Top level screens: apps, activity, hooks and settings
public class ActivityMain extends ActivityBase implements Ui.Anchored {
    private static final String TAG = "XLua.Main";

    private static final String TAB_APPS = "apps";
    private static final String TAB_ACTIVITY = "activity";
    private static final String TAB_HOOKS = "hooks";
    private static final String TAB_SETTINGS = "settings";

    private static final String STATE_AVAILABLE = "available";

    private NavBar nav;
    private boolean available = false;
    private Runnable checked = null; // What to show after checking the service, when the screen was left meanwhile

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // The masks on black while the app starts, then the theme of the app, see Theme.XLua.Starting
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        // The tabs restored by the framework need the main layout right away
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_AVAILABLE)) {
            showMain(false);
            return;
        }

        // Checking the service is a call into system server, so not on the main thread
        final Context context = getApplicationContext();
        Ui.submit(() -> {
            int version = Data.getVersion(context);
            boolean available = Data.isAvailable(version);
            // Why not, as far as the app can tell; binder calls
            String why = (available ? null : unavailable(context, version));
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
                        showUnavailable(why);
                };
                // Tabs cannot be added after the state of the screen was saved, like when it was left meanwhile
                if (getSupportFragmentManager().isStateSaved())
                    checked = show;
                else
                    show.run();
            });
        }, ex -> {
            // A failed check would else leave the screen blank; this screen has a button to try again
            postUnlessGone(() -> showUnavailable(null));
            error(TAG, ex);
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

    // What to do, for the cases the app can tell apart: the module of the service is of another version, or the
    // framework is connected, so the module is enabled; not connected can be no framework, a framework with the legacy
    // API or a module which is not enabled, the app cannot tell, so the text covers them all
    private static String unavailable(Context context, int version) {
        boolean bound = Scope.available();
        return context.getString(helpFor(version, bound, bound && Scope.inScope("system")), version, BuildConfig.VERSION_CODE);
    }

    static int helpFor(int version, boolean bound, boolean systemInScope) {
        if (version >= 0 && version < BuildConfig.VERSION_CODE)
            return R.string.msg_module_version;
        if (version < 0 && bound)
            return (systemInScope ? R.string.msg_no_service_restart : R.string.msg_no_service_scope);
        return R.string.msg_no_service_help;
    }

    private void showUnavailable(String why) {
        setContentView(R.layout.activity_unavailable);
        if (why != null)
            ((TextView) findViewById(R.id.tvHelp)).setText(why);
        findViewById(R.id.btnRetry).setOnClickListener(view -> recreate());
        findViewById(R.id.btnDocs).setOnClickListener(view -> Ui.browse(this, Ui.URL_FAQ + "#user-content-faq3"));
    }

    private void showMain(boolean first) {
        available = true;
        setContentView(R.layout.activity_main);
        nav = findViewById(R.id.nav);
        nav.setMenu(R.menu.nav);
        nav.setOnItemSelectedListener(itemId -> show(getTab(itemId)));

        // Also for the tabs which the framework restores
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f, @NonNull View v, Bundle state) {
                keepClearOfBar(v);
            }
        }, false);

        // Else the selected tab and fragments are restored by the framework
        if (first)
            show(TAB_APPS);

        checkFirstRun();
    }

    // The bar floats over the bottom of the tabs: their lists can scroll their end above it,
    // and the add button of the Hooks tab stays above it
    private void keepClearOfBar(View view) {
        int space = getResources().getDimensionPixelSize(R.dimen.nav_floating_height) +
                getResources().getDimensionPixelSize(R.dimen.nav_floating_margin);
        if (view instanceof RecyclerView || view instanceof NestedScrollView) {
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), view.getPaddingBottom() + space);
            ((ViewGroup) view).setClipToPadding(false);
        } else if (view.getId() == R.id.fabAdd) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            lp.bottomMargin += space;
            view.setLayoutParams(lp);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                keepClearOfBar(group.getChildAt(i));
        }
    }

    @Override
    public View getMessageAnchor() {
        // Null without the main layout, which places messages at the bottom
        return findViewById(R.id.nav);
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
                .setPositiveButton(R.string.title_accept, (dialog, which) -> {
                    prefs.edit().putBoolean("firstrun", false).apply();
                    // Once: restricting is not enough, the app must also be in the framework scope
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.title_first_steps)
                            .setMessage(R.string.msg_first_steps)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                })
                .setNegativeButton(R.string.title_deny, (dialog, which) -> finishAffinity())
                .show();
    }
}
