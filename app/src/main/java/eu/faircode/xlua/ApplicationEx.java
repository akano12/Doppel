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

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

public class ApplicationEx extends Application {
    private static final String TAG = "XLua.App";

    static final String THEME_SYSTEM = "system";
    static final String THEME_LIGHT = "light";
    static final String THEME_DARK = "dark";

    // Copy of the theme setting of the service, which is not called on the main thread
    private static final String PREF_THEME = "theme";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Create version=" + BuildConfig.VERSION_NAME);
        applyTheme(getPreferences(this).getString(PREF_THEME, null));
    }

    // Apply and remember the theme setting of the service
    static void setTheme(Context context, String theme) {
        getPreferences(context).edit().putString(PREF_THEME, theme).apply();
        applyTheme(theme);
    }

    // Same file as the former default shared preferences
    static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    // No setting follows the system theme
    private static void applyTheme(String theme) {
        if (THEME_LIGHT.equals(theme))
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        else if (THEME_DARK.equals(theme))
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        else
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}
