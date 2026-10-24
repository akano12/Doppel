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

import android.os.Build;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

// Entry point for the modern libxposed API (META-INF/xposed/java_init.list)
// https://github.com/libxposed/api
// The modern API requires Android 8.0 or later
@RequiresApi(Build.VERSION_CODES.O)
public class XLuaModule extends XposedModule {
    private boolean systemServer = false;
    private XCore core = null;

    // Instantiated by the framework, which attaches itself before onModuleLoaded
    public XLuaModule() {
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        ModernBackend backend = new ModernBackend(this);
        if (!XCore.claim(backend)) {
            // The legacy entry point of this module handles this process
            if (getApiVersion() >= XposedInterface.API_102)
                detach();
            return;
        }
        systemServer = param.isSystemServer();
        core = new XCore(backend);
        XCore.log("Loaded in " + param.getProcessName() + ":" + Process.myUid() +
                " framework=" + getFrameworkName() + " " + getFrameworkVersion() + " api=" + getApiVersion());
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        if (core == null)
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
                if (XCore.SETTINGS_PACKAGE.equals(packageName))
                    core.hookSettings(param.getClassLoader());
            } else if (XCore.isApp(packageName, param.isFirstPackage()))
                core.hookApplication(packageName, param.getClassLoader());
        } catch (Throwable ex) {
            XCore.log(ex);
        }
    }
}
