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

    Copyright 2026 akano12
 */

package eu.faircode.xlua;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// The icon of the app in the launcher: the manifest has one launcher entry, an alias of ActivityMain, per icon,
// of which one is enabled; the first one when the app did not choose. Android closes the screens opened from
// an entry which is disabled, so a chosen icon is switched to when the app goes to the background.
final class LauncherIcons {
    private static final String PREF_CHOSEN = "launcher_icon";
    static final String[] ALIASES = {"IconMasks", "IconSplit", "IconTwoD", "IconEcho"};
    static final int[] ICONS = {
            R.mipmap.ic_launcher, R.mipmap.ic_launcher_split, R.mipmap.ic_launcher_twod, R.mipmap.ic_launcher_echo};
    static final int[] TITLES = {
            R.string.title_icon_masks, R.string.title_icon_split, R.string.title_icon_twod, R.string.title_icon_echo};

    // A screen waits for the result of another app, like the file picker of a backup: the screen would be closed
    // with it when the icon is switched, so the result would be lost
    private static boolean awaitingResult = false;

    private LauncherIcons() {
    }

    static void setAwaitingResult(boolean awaiting) {
        awaitingResult = awaiting;
    }

    // The entries are named after the package of the code, which differs from the package of the app
    private static ComponentName getComponent(Context context, int index) {
        String code = LauncherIcons.class.getName();
        return new ComponentName(context, code.substring(0, code.lastIndexOf('.') + 1) + ALIASES[index]);
    }

    // The chosen icon, also when it is not switched to yet
    static int getSelected(Context context) {
        int chosen = ApplicationEx.getPreferences(context).getInt(PREF_CHOSEN, -1);
        return (chosen < 0 ? getEnabled(context) : chosen);
    }

    // True when the icon is switched to later, false for the icon of the launcher, which cancels a choice made before
    static boolean choose(Context context, int index) {
        boolean pending = (index != getEnabled(context));
        SharedPreferences.Editor editor = ApplicationEx.getPreferences(context).edit();
        if (pending)
            editor.putInt(PREF_CHOSEN, index);
        else
            editor.remove(PREF_CHOSEN);
        editor.apply();
        return pending;
    }

    private static int getEnabled(Context context) {
        PackageManager pm = context.getPackageManager();
        for (int i = 0; i < ALIASES.length; i++)
            if (pm.getComponentEnabledSetting(getComponent(context, i)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                return i;
        return 0;
    }

    // Switches to the chosen icon when no screen of the app is visible anymore, see ApplicationEx
    static void register(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private int started = 0;

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                started++;
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                if (--started == 0 && !activity.isChangingConfigurations() && !awaitingResult) {
                    int chosen = ApplicationEx.getPreferences(app).getInt(PREF_CHOSEN, -1);
                    if (chosen >= 0) {
                        enable(app, chosen);
                        ApplicationEx.getPreferences(app).edit().remove(PREF_CHOSEN).apply();
                    }
                }
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }

    // The new entry is enabled before the others are disabled, so the launcher always has one;
    // the app keeps running, a shortcut of the former icon on the home screen is removed by the launcher
    private static void enable(Context context, int index) {
        PackageManager pm = context.getPackageManager();
        pm.setComponentEnabledSetting(getComponent(context, index),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        for (int i = 0; i < ALIASES.length; i++)
            if (i != index)
                pm.setComponentEnabledSetting(getComponent(context, i),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }
}
