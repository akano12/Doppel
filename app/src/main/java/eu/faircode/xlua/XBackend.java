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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

// Hooking framework used by the engine: the modern libxposed API
interface XBackend {
    String getName();

    void hook(Member member, Callback callback) throws Throwable;

    // Runs the method in the interpreter, which calls the methods it calls through their hooks, also those which
    // the boot image compiled into it; false when the framework cannot
    default boolean deoptimize(Member member) throws Throwable {
        return false;
    }

    void log(String message);

    void log(Throwable ex);

    // Hook all methods with the given name declared by the class, the number of which it returns; abstract methods have
    // no code to hook
    default int hookAll(Class<?> cls, String name, Callback callback) throws Throwable {
        int hooked = 0;
        for (Method method : cls.getDeclaredMethods())
            if (method.getName().equals(name) && !Modifier.isAbstract(method.getModifiers())) {
                hook(method, callback);
                hooked++;
            }
        return hooked;
    }

    abstract class Callback {
        void before(XCall call) throws Throwable {
        }

        void after(XCall call) throws Throwable {
        }
    }
}
