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

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

// Modern libxposed API (LSPosed and compatible frameworks)
// https://github.com/libxposed/api
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
        // Callbacks handle and report their own errors, so exceptions thrown here are intended:
        // exceptions of scripts, like for a blocked camera, and exceptions of the original method.
        // The protective mode would swallow them and call the original method instead.
        xposed.hook((Executable) member)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
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

                    if (call.hasThrowable()) {
                        // The stack is walked on the way out of an exception only, which costs about as much as making it;
                        // an exception of the app's own class which refuses, or a stack too deep to walk, is thrown as it is
                        try {
                            hideHook(call.getThrowable(), member, new Throwable().getStackTrace());
                        } catch (Throwable ignored) {
                        }
                        throw call.getThrowable();
                    }
                    return call.getResult();
                });
    }

    // On a device without Doppel the stack trace of an exception of a method goes from the method straight to its caller.
    // The frames in between are those of the hook: the call of the original method by the framework, the interceptors,
    // of which this one names Doppel, and the stub which LSPlant made to stand in for the method. An exception made
    // under this call, so its trace ends with the frames below here, loses them, and so do its causes and suppressed ones.
    // shortcut: an exception a script makes has no frame of the method to keep and keeps its frames, and so does a trace
    // taken inside the method, like by a callback of the app it calls, upgrade when an app is seen looking at them
    static void hideHook(Throwable ex, Member member, StackTraceElement[] here) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> todo = new ArrayDeque<>();
        todo.add(ex);
        while (!todo.isEmpty()) {
            Throwable next = todo.pop();
            if (!seen.add(next))
                continue;
            StackTraceElement[] trace = unhooked(next.getStackTrace(), here, member);
            if (trace != null)
                next.setStackTrace(trace);
            if (next.getCause() != null)
                todo.add(next.getCause());
            Collections.addAll(todo, next.getSuppressed());
        }
    }

    // The trace without the frames of the hook, or null when it was not made under the call whose stack is here,
    // with here[0] the frame of the interceptor; the frames are found by their place, since the framework's
    // are obfuscated and a release build renames the synthetic ones of Doppel
    static StackTraceElement[] unhooked(StackTraceElement[] trace, StackTraceElement[] here, Member member) {
        int interceptor = trace.length - here.length;
        if (interceptor < 1)
            return null;
        for (int i = 1; i < here.length; i++)
            if (!here[i].equals(trace[interceptor + i]))
                return null;

        // The original method is the nearest frame above the interceptor which has its class and name
        boolean constructor = (member instanceof Constructor);
        String cls = member.getDeclaringClass().getName();
        String name = (constructor ? "<init>" : member.getName());
        int original = interceptor - 1;
        while (original >= 0 && !(cls.equals(trace[original].getClassName()) && name.equals(trace[original].getMethodName())))
            original--;
        if (original < 0)
            return null;

        // The stub is the first frame below the interceptor of Doppel, whose name the interface of the framework fixes,
        // with the name LSPlant gives it, the one of the method or "constructor"; without it only Doppel's frames go
        int intercept = indexOf(here, 1, "intercept");
        if (intercept < 0)
            return null;
        int stub = indexOf(here, intercept + 1, constructor ? "constructor" : member.getName());
        if (stub < 0)
            stub = intercept;

        int below = here.length - stub - 1;
        StackTraceElement[] result = new StackTraceElement[original + 1 + below];
        System.arraycopy(trace, 0, result, 0, original + 1);
        System.arraycopy(trace, trace.length - below, result, original + 1, below);
        return result;
    }

    private static int indexOf(StackTraceElement[] here, int from, String method) {
        for (int i = from; i < here.length; i++)
            if (method.equals(here[i].getMethodName()))
                return i;
        return -1;
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
