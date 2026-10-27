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

import java.lang.reflect.Member;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

// Legacy XposedBridge API (Xposed, EdXposed, LSPosed before the modern API)
class LegacyBackend implements XBackend {
    private static final String TAG = "XLua.Legacy";

    @Override
    public String getName() {
        return "legacy";
    }

    @Override
    public void hook(Member member, final Callback callback) {
        XposedBridge.hookMethod(member, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // Share the argument array, so argument changes apply to the call
                XCall call = new XCall(param.method, param.thisObject, param.args);
                callback.before(call);
                if (call.isReturnEarly())
                    apply(call, param);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                // The extras of the param are shared by the hooks of the method, so each call is made again
                XCall call = new XCall(param.method, param.thisObject, param.args);
                call.setOutcome(param.getResult(), param.getThrowable());
                callback.after(call);
                apply(call, param);
            }
        });
    }

    private static void apply(XCall call, XC_MethodHook.MethodHookParam param) {
        if (call.hasThrowable())
            param.setThrowable(call.getThrowable());
        else
            param.setResult(call.getResult());
    }

    @Override
    public void log(String message) {
        XposedBridge.log(TAG + " " + message);
    }

    @Override
    public void log(Throwable ex) {
        XposedBridge.log(ex);
    }
}
