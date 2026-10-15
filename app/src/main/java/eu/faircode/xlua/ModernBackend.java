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
 */

package eu.faircode.xlua;

import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;

import io.github.libxposed.api.XposedInterface;

// Modern libxposed API (LSPosed and compatible frameworks)
// https://github.com/libxposed/api
@RequiresApi(Build.VERSION_CODES.O)
class ModernBackend implements XBackend {
    private static final String TAG = "XLua.Modern";

    private final XposedInterface xposed;

    ModernBackend(XposedInterface xposed) {
        this.xposed = xposed;
    }

    @Override
    public String getName() {
        return "modern";
    }

    @Override
    public void hook(Member member, final Callback callback) {
        // Callbacks handle and report their own errors, other errors should not break the app
        xposed.hook((Executable) member)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    XCall call = new XCall(member, chain.getThisObject(), chain.getArgs().toArray());

                    callback.before(call);

                    // Like legacy Xposed, a result set before skips the original method,
                    // but the after callback is still called
                    if (!call.isReturnEarly()) {
                        Object result = null;
                        Throwable throwable = null;
                        try {
                            result = chain.proceed(call.args);
                        } catch (Throwable ex) {
                            throwable = ex;
                        }
                        call.setOutcome(result, throwable);
                    }

                    callback.after(call);

                    if (call.hasThrowable())
                        throw call.getThrowable();
                    return call.getResult();
                });
    }

    @Override
    public void log(String message) {
        xposed.log(Log.INFO, TAG, message);
    }

    @Override
    public void log(Throwable ex) {
        xposed.log(Log.ERROR, TAG, String.valueOf(ex.getMessage()), ex);
    }
}
