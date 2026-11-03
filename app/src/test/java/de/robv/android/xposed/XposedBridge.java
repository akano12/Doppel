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

package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.concurrent.Callable;

// The legacy Xposed API for unit tests, see XC_MethodHook; call runs a hooked method like
// XposedBridge.handleHookedMethod does with one hook
public final class XposedBridge {
    public static XC_MethodHook hooked = null;

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        hooked = callback;
        return null;
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static Object call(Member method, Object thisObject, Object[] args, Callable<Object> original)
            throws Throwable {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = method;
        param.thisObject = thisObject;
        param.args = args;

        try {
            hooked.beforeHookedMethod(param);
        } catch (Throwable t) {
            log(t);
            param.setResult(null);
            param.returnEarly = false;
        }

        if (!param.returnEarly)
            try {
                param.setResult(original.call());
            } catch (Throwable t) {
                param.setThrowable(t);
            }

        Object lastResult = param.getResult();
        Throwable lastThrowable = param.getThrowable();
        try {
            hooked.afterHookedMethod(param);
        } catch (Throwable t) {
            log(t);
            if (lastThrowable == null)
                param.setResult(lastResult);
            else
                param.setThrowable(lastThrowable);
        }

        if (param.hasThrowable())
            throw param.getThrowable();
        return param.getResult();
    }
}
