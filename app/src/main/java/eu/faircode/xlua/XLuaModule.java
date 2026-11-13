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

import android.os.Process;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

// Entry point for the modern libxposed API (META-INF/xposed/java_init.list)
// https://github.com/libxposed/api
// The framework instantiates the module and attaches itself before onModuleLoaded.
public class XLuaModule extends XposedModule {
    private boolean systemServer = false;
    private ModernBackend backend = null;
    private XCore core = null;
    private boolean claimed = false;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        systemServer = param.isSystemServer();
        if (systemServer)
            XLog.inSystemServer();
        backend = new ModernBackend(this);
        core = new XCore(backend);
        XCore.log("Loaded in " + param.getProcessName() + ":" + Process.myUid() +
                " framework=" + getFrameworkName() + " " + getFrameworkVersion() + " api=" + getApiVersion());
    }

    // Claimed right before the first hook, not when loaded: apps can read the claim until the hooks of the app are
    // installed, and the AppComponentFactory of an app runs before onPackageReady. The entry point which claims first
    // hooks, see XCore.claim.
    private boolean claim() {
        if (!claimed) {
            if (!XCore.claim(backend)) {
                // The legacy entry point of this module handles this process
                core = null;
                if (getApiVersion() >= XposedInterface.API_102)
                    detach();
                return false;
            }
            claimed = true;
        }
        return true;
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        if (core == null || !claim())
            return;
        try {
            core.hookAndroid(param.getClassLoader());
        } catch (Throwable ex) {
            XCore.log(ex);
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (core == null)
            return;
        String packageName = param.getPackageName();
        try {
            if (systemServer) {
                // The settings provider is loaded into system server, which has the scope 'system'
                if (XCore.SETTINGS_PACKAGE.equals(packageName) && claim())
                    core.hookSettings(param.getClassLoader());
            } else if (XCore.isApp(packageName, param.isFirstPackage()) && claim())
                core.hookApplication(packageName, param.getClassLoader());
        } catch (Throwable ex) {
            XCore.log(ex);
        }
    }
}
