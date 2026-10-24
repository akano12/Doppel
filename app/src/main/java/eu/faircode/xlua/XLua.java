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

import android.os.Process;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

// Entry point for the legacy XposedBridge API (assets/xposed_init)
// Frameworks supporting the modern API use XLuaModule instead
public class XLua implements IXposedHookLoadPackage {
    private static final String TAG = "XLua.Xposed";

    private final LegacyBackend backend = new LegacyBackend();
    private XCore core = null;

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (hasModernApi() || !XCore.claim(backend))
            return;
        if (core == null)
            core = new XCore(backend);

        int uid = Process.myUid();
        XCore.log("Loaded " + lpparam.packageName + ":" + uid);

        if ("android".equals(lpparam.packageName))
            core.hookAndroid(lpparam.classLoader);

        if (XCore.SETTINGS_PACKAGE.equals(lpparam.packageName))
            core.hookSettings(lpparam.classLoader);

        if (XCore.isApp(lpparam.packageName, lpparam.isFirstApplication))
            core.hookApplication(lpparam.packageName, lpparam.classLoader);
    }

    // Frameworks providing the modern API (101 or later, see module.prop) load XLuaModule,
    // and modern modules are not allowed to use the legacy API
    private static Boolean modern = null;

    private static boolean hasModernApi() {
        if (modern == null)
            try {
                Class<?> cls = Class.forName("io.github.libxposed.api.XposedInterface", false, XLua.class.getClassLoader());
                cls.getField("API_101");
                modern = true;
                Log.i(TAG, "Modern API available, legacy entry inactive");
            } catch (Throwable ignored) {
                modern = false;
            }
        return modern;
    }
}
