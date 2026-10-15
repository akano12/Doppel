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

import android.util.Log;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

// Resolves the target of a hook definition: used by the hook engine and by the hook editor
final class XResolver {
    private static final String TAG = "XLua.Resolver";

    private static final Map<String, Class<?>> PRIMITIVES = new HashMap<>();

    static {
        for (Class<?> cls : new Class<?>[]{boolean.class, byte.class, char.class, short.class,
                int.class, long.class, float.class, double.class, void.class})
            PRIMITIVES.put(cls.getName(), cls);
    }

    private XResolver() {
    }

    // Class names as in definitions: primitive types, arrays like int[] and class names
    static Class<?> resolveClass(String name, ClassLoader loader) throws ClassNotFoundException {
        Class<?> primitive = PRIMITIVES.get(name);
        if (primitive != null)
            return primitive;
        if (name.endsWith("[]"))
            return Array.newInstance(resolveClass(name.substring(0, name.length() - 2), loader), 0).getClass();
        return Class.forName(name, false, loader);
    }

    // A field of the class or a super class, of any type when the type is null
    static Field resolveField(Class<?> cls, String name, Class<?> type) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null && !c.equals(Object.class); c = c.getSuperclass())
            for (Field field : c.getDeclaredFields())
                if (name.equals(field.getName()) && (type == null || field.getType().equals(type)))
                    return field;

        // Help finding the right definition
        for (Class<?> c = cls; c != null && !c.equals(Object.class); c = c.getSuperclass()) {
            Log.i(TAG, c.toString());
            for (Field field : c.getDeclaredFields())
                Log.i(TAG, "- " + field);
        }
        throw new NoSuchFieldException(name);
    }

    // A method, or a constructor when the name is null, of the class or a super class,
    // with parameters which the given types can be assigned to
    static Member resolveMember(Class<?> cls, String name, Class<?>[] params) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null && !c.equals(Object.class); c = c.getSuperclass()) {
            try {
                return (name == null ? c.getDeclaredConstructor(params) : c.getDeclaredMethod(name, params));
            } catch (NoSuchMethodException ignored) {
            }

            for (Member member : name == null ? c.getDeclaredConstructors() : c.getDeclaredMethods()) {
                if (name != null && !name.equals(member.getName()))
                    continue;

                Class<?>[] mparams = (member instanceof Method
                        ? ((Method) member).getParameterTypes()
                        : ((Constructor<?>) member).getParameterTypes());
                if (mparams.length != params.length)
                    continue;

                boolean same = true;
                for (int i = 0; i < mparams.length && same; i++)
                    same = mparams[i].isAssignableFrom(params[i]);
                if (same) {
                    Log.i(TAG, "Resolved member=" + member);
                    return member;
                }
            }
        }

        // Help finding the right definition
        for (Class<?> c = cls; c != null && !c.equals(Object.class); c = c.getSuperclass()) {
            Log.i(TAG, c.toString());
            for (Member member : name == null ? c.getDeclaredConstructors() : c.getDeclaredMethods())
                if (name == null || name.equals(member.getName()))
                    Log.i(TAG, "- " + member);
        }
        throw new NoSuchMethodException(name);
    }
}
