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

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.core.text.HtmlCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Calendar;

// Top level screens: apps, activity, hooks and settings
public class ActivityMain extends ActivityBase {
    private static final String TAG = "XLua.Main";

    // Opens the restrictions of an app, used by notifications
    public static final String EXTRA_SEARCH_PACKAGE = "package";

    private static final String TAB_APPS = "apps";
    private static final String TAB_ACTIVITY = "activity";
    private static final String TAB_HOOKS = "hooks";
    private static final String TAB_SETTINGS = "settings";

    private BottomNavigationView nav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!XProvider.isAvailable(this)) {
            setContentView(R.layout.activity_unavailable);
            findViewById(R.id.btnRetry).setOnClickListener(view -> recreate());
            findViewById(R.id.btnDocs).setOnClickListener(view ->
                    Ui.browse(this, "https://github.com/M66B/XPrivacyLua/blob/master/README.md"));
            return;
        }

        setContentView(R.layout.activity_main);
        nav = findViewById(R.id.snackbar_anchor);
        nav.setOnItemSelectedListener(item -> {
            show(getTab(item.getItemId()));
            return true;
        });

        // Selected tab and fragments are restored by the framework
        if (savedInstanceState == null) {
            show(TAB_APPS);
            handleIntent(getIntent());
        }

        checkFirstRun();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String packageName = intent.getStringExtra(EXTRA_SEARCH_PACKAGE);
        if (packageName != null) {
            Log.i(TAG, "Show pkg=" + packageName);
            intent.removeExtra(EXTRA_SEARCH_PACKAGE);
            startActivity(ActivityApp.getIntent(this, packageName, -1));
        }
    }

    void selectSettings() {
        if (nav != null)
            nav.setSelectedItemId(R.id.nav_settings);
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
        // Same file as the former default shared preferences
        final SharedPreferences prefs = getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE);
        if (!prefs.getBoolean("firstrun", true))
            return;

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_text, null, false);
        TextView tvLicense = view.findViewById(R.id.tvText);
        tvLicense.setMovementMethod(LinkMovementMethod.getInstance());
        int year = Calendar.getInstance().get(Calendar.YEAR);
        tvLicense.setText(HtmlCompat.fromHtml(getString(R.string.title_license, year), HtmlCompat.FROM_HTML_MODE_LEGACY));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name)
                .setView(view)
                .setCancelable(false)
                .setPositiveButton(R.string.title_accept, (dialog, which) ->
                        prefs.edit().putBoolean("firstrun", false).apply())
                .setNegativeButton(R.string.title_deny, (dialog, which) -> finishAffinity())
                .show();
    }
}
