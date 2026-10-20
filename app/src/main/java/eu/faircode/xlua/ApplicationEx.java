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

import java.util.Arrays;
import java.util.Objects;

public class ApplicationEx extends Application {
    private static final String TAG = "XLua.App";

    static final String THEME_SYSTEM = "system";
    static final String THEME_LIGHT = "light";
    static final String THEME_DARK = "dark";

    // Copy of the theme setting of the service, which is not called on the main thread
    private static final String PREF_THEME = "theme";

    // Color themes: the setting value, the theme overlay (res/values/palettes.xml) and the title;
    // no setting is the default indigo palette of the app theme
    static final String PALETTE_DEFAULT = "indigo";
    static final String[] PALETTES = {
            PALETTE_DEFAULT, "ocean", "forest", "sunflower", "coral", "berry", "sky", "teal", "amoled"};
    private static final int[] PALETTE_STYLES = {
            0,
            R.style.ThemeOverlay_XLua_Ocean,
            R.style.ThemeOverlay_XLua_Forest,
            R.style.ThemeOverlay_XLua_Sunflower,
            R.style.ThemeOverlay_XLua_Coral,
            R.style.ThemeOverlay_XLua_Berry,
            R.style.ThemeOverlay_XLua_Sky,
            R.style.ThemeOverlay_XLua_Teal,
            R.style.ThemeOverlay_XLua_Amoled};
    static final int[] PALETTE_TITLES = {
            R.string.title_palette_indigo,
            R.string.title_palette_ocean,
            R.string.title_palette_forest,
            R.string.title_palette_sunflower,
            R.string.title_palette_coral,
            R.string.title_palette_berry,
            R.string.title_palette_sky,
            R.string.title_palette_teal,
            R.string.title_palette_amoled};

    // Copy of the palette setting of the service, like the theme
    private static final String PREF_PALETTE = "palette";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Create version=" + BuildConfig.VERSION_NAME);
        applyTheme(getPreferences(this).getString(PREF_THEME, null));
    }

    // Apply and remember the theme setting of the service, when it changed
    static void setTheme(Context context, String theme) {
        SharedPreferences prefs = getPreferences(context);
        if (Objects.equals(theme, prefs.getString(PREF_THEME, null)))
            return;
        prefs.edit().putString(PREF_THEME, theme).apply();
        applyTheme(theme);
    }

    // Remember the palette setting of the service; true when it changed, then screens need to be recreated
    static boolean setPalette(Context context, String palette) {
        SharedPreferences prefs = getPreferences(context);
        if (Objects.equals(palette, prefs.getString(PREF_PALETTE, null)))
            return false;
        prefs.edit().putString(PREF_PALETTE, palette).apply();
        return true;
    }

    // An unknown palette, like of a backup of another version, is the default palette
    static String getPalette(Context context) {
        String palette = getPreferences(context).getString(PREF_PALETTE, null);
        return (Arrays.asList(PALETTES).contains(palette) ? palette : PALETTE_DEFAULT);
    }

    // The theme overlay of a palette, 0 for the default palette or an unknown one
    static int getPaletteStyle(String palette) {
        int index = Arrays.asList(PALETTES).indexOf(palette);
        return (index < 0 ? 0 : PALETTE_STYLES[index]);
    }

    // Applies the palette to the theme of a screen, before its views are created
    static void applyPalette(Context context) {
        int style = getPaletteStyle(getPalette(context));
        if (style != 0)
            context.getTheme().applyStyle(style, true);
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
