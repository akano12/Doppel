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
    // no setting is the default palette Ocean of the app theme (res/values/colors.xml)
    static final String PALETTE_DEFAULT = "ocean";
    static final String[] PALETTES = {PALETTE_DEFAULT, "berry", "sky", "teal"};
    private static final int[] PALETTE_STYLES = {
            0,
            R.style.ThemeOverlay_XLua_Berry,
            R.style.ThemeOverlay_XLua_Sky,
            R.style.ThemeOverlay_XLua_Teal};
    static final int[] PALETTE_TITLES = {
            R.string.title_palette_ocean,
            R.string.title_palette_berry,
            R.string.title_palette_sky,
            R.string.title_palette_teal};

    // Copies of the palette and black background settings of the service, like the theme
    private static final String PREF_PALETTE = "palette";
    private static final String PREF_BLACK = "black";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Create version=" + BuildConfig.VERSION_NAME);
        applyTheme(getPreferences(this).getString(PREF_THEME, null));
    }

    // The color settings of the service: theme, palette and black background, see sync
    static class Colors {
        final String theme;
        final String palette;
        final boolean black;

        private Colors(String theme, String palette, boolean black) {
            this.theme = theme;
            this.palette = palette;
            this.black = black;
        }

        // Calls into system server, so not on the main thread
        static Colors get(Context context) {
            return new Colors(
                    Data.getSetting(context, "global", "theme"),
                    Data.getSetting(context, "global", "palette"),
                    Data.getSettingBoolean(context, "global", "black"));
        }
    }

    // Remember the color settings of the service, like after an update, a restored backup or cleared data;
    // true when the palette or the black background changed, then the screen needs to be recreated,
    // which applies the theme when it gets the settings again, so the screen is not recreated twice
    static boolean sync(Context context, Colors colors) {
        boolean changed = setPalette(context, colors.palette);
        changed |= setBlack(context, colors.black);
        if (!changed)
            setTheme(context, colors.theme);
        return changed;
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

    // Remember the black background setting of the service; true when it changed, like setPalette
    static boolean setBlack(Context context, boolean black) {
        SharedPreferences prefs = getPreferences(context);
        if (black == prefs.getBoolean(PREF_BLACK, false))
            return false;
        prefs.edit().putBoolean(PREF_BLACK, black).apply();
        return true;
    }

    static boolean isBlack(Context context) {
        return getPreferences(context).getBoolean(PREF_BLACK, false);
    }

    // Applies the palette and the black background, which only the dark theme has (values-night),
    // to the theme of a screen, before its views are created
    static void applyPalette(Context context) {
        int style = getPaletteStyle(getPalette(context));
        if (style != 0)
            context.getTheme().applyStyle(style, true);
        if (isBlack(context))
            context.getTheme().applyStyle(R.style.ThemeOverlay_XLua_Black, true);
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
