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
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // The classes of several names, like the parameter types of a definition
    static Class<?>[] resolveClasses(String[] names, ClassLoader loader) throws ClassNotFoundException {
        Class<?>[] classes = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++)
            classes[i] = resolveClass(names[i], loader);
        return classes;
    }

    // The field, method or constructor a definition hooks:
    // field:method is a method of the class of the object in a static field, #field a field,
    // no method name the constructor; the return type, when given, must fit
    static Member resolveTarget(Class<?> cls, String methodName, Class<?>[] paramTypes, Class<?> returnType)
            throws Throwable {
        if (methodName != null) {
            String[] m = methodName.split(":");
            if (m.length > 1) {
                Object obj = cls.getField(m[0]).get(null);
                cls = obj.getClass();
            }
            methodName = m[m.length - 1];
        }

        if (methodName != null && methodName.startsWith("#")) {
            if (paramTypes.length > 0)
                throw new NoSuchFieldException("Field with parameters");
            return resolveField(cls, methodName.substring(1), returnType);
        }

        Member member = resolveMember(cls, methodName, paramTypes);
        if (methodName != null && returnType != null) {
            Class<?> memberReturnType = ((Method) member).getReturnType();
            if (!memberReturnType.isAssignableFrom(returnType))
                throw new Throwable("Invalid return type " + memberReturnType + " got " + returnType);
        }
        return member;
    }

    // A field of the class or a super class, of any type when the type is null
    static Field resolveField(Class<?> cls, String name, Class<?> type) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null && !c.equals(Object.class); c = c.getSuperclass())
            for (Field field : c.getDeclaredFields())
                if (name.equals(field.getName()) && (type == null || field.getType().equals(type)))
                    return field;

        // Help finding the right definition, in debug builds only, because this runs on each app start
        if (BuildConfig.DEBUG)
            for (Class<?> c = cls; c != null && !c.equals(Object.class); c = c.getSuperclass()) {
                Log.i(TAG, c.toString());
                for (Field field : c.getDeclaredFields())
                    Log.i(TAG, "- " + field);
            }
        throw new NoSuchFieldException(name);
    }

    // A method of the class or a super class, or a constructor of the class itself when the name is null
    // (constructors are not inherited), with parameters which the given types can be assigned to
    static Member resolveMember(Class<?> cls, String name, Class<?>[] params) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null && !c.equals(Object.class) && (name != null || c == cls); c = c.getSuperclass()) {
            try {
                return (name == null ? c.getDeclaredConstructor(params) : c.getDeclaredMethod(name, params));
            } catch (NoSuchMethodException ignored) {
            }

            for (Member member : name == null ? c.getDeclaredConstructors() : c.getDeclaredMethods()) {
                if (name != null && !name.equals(member.getName()))
                    continue;

                Class<?>[] mparams = parameterTypes(member);
                if (mparams.length != params.length)
                    continue;

                boolean same = true;
                for (int i = 0; i < mparams.length && same; i++)
                    same = mparams[i].isAssignableFrom(params[i]);
                if (same) {
                    if (BuildConfig.DEBUG)
                        Log.i(TAG, "Resolved member=" + member);
                    return member;
                }
            }
        }

        // Help finding the right definition, in debug builds only
        if (BuildConfig.DEBUG)
            for (Class<?> c = cls; c != null && !c.equals(Object.class) && (name != null || c == cls); c = c.getSuperclass()) {
                Log.i(TAG, c.toString());
                for (Member member : name == null ? c.getDeclaredConstructors() : c.getDeclaredMethods())
                    if (name == null || name.equals(member.getName()))
                        Log.i(TAG, "- " + member);
            }
        throw new NoSuchMethodException(name == null ? cls.getName() + ".<init>" : name);
    }

    // The parameter types of a method or constructor; Executable, which has them for both, needs Android 8
    static Class<?>[] parameterTypes(Member member) {
        return (member instanceof Method
                ? ((Method) member).getParameterTypes()
                : ((Constructor<?>) member).getParameterTypes());
    }

    // The methods with a name which objects of the class run: declared by the class or inherited from a super class.
    // Without methods overridden by a subclass, abstract methods, which have no code, bridge methods,
    // which call the method they bridge, and the methods of Object, which all objects share.
    // Private and static methods of a super class are not inherited by the objects of the class.
    static List<Method> resolveMethods(Class<?> cls, String name) {
        List<Method> result = new ArrayList<>();
        Set<List<Class<?>>> overridden = new HashSet<>();
        for (Class<?> c = cls; c != null && !c.equals(Object.class); c = c.getSuperclass()) {
            List<Method> methods = new ArrayList<>();
            for (Method method : c.getDeclaredMethods())
                if (name.equals(method.getName()) &&
                        (c == cls || (method.getModifiers() & (Modifier.PRIVATE | Modifier.STATIC)) == 0))
                    methods.add(method);

            Set<List<Class<?>>> declared = new HashSet<>();
            for (Method method : methods) {
                List<Class<?>> params = Arrays.asList(method.getParameterTypes());
                if (!method.isBridge() || bridgesDeclared(c, method, methods))
                    declared.add(params);
                if (!overridden.contains(params) &&
                        !method.isBridge() && !Modifier.isAbstract(method.getModifiers()))
                    result.add(method);
            }
            overridden.addAll(declared);
        }
        return result;
    }

    // Whether a bridge method calls a method of its own class: one with the same parameters and a covariant
    // return type, or one with the parameters of a generic type, narrower than the type variables of the method
    // of the super class or interface which the bridge has the signature of. Else it is the bridge javac adds
    // to a public class for a public method of a class which is not public, which calls the method of the super
    // class, also when the class overloads the method with narrower parameters.
    private static boolean bridgesDeclared(Class<?> cls, Method bridge, List<Method> methods) {
        Class<?>[] bparams = bridge.getParameterTypes();
        Type[] generic = null;
        for (Method method : methods) {
            Class<?>[] mparams = method.getParameterTypes();
            if (method.isBridge() || mparams.length != bparams.length ||
                    !bridge.getReturnType().isAssignableFrom(method.getReturnType()))
                continue;
            boolean bridged = true;
            for (int i = 0; i < mparams.length && bridged; i++)
                if (!mparams[i].equals(bparams[i])) {
                    if (generic == null)
                        generic = genericParameters(cls, bridge);
                    bridged = (bparams[i].isAssignableFrom(mparams[i]) && !(generic[i] instanceof Class));
                }
            if (bridged)
                return true;
        }
        return false;
    }

    // The generic parameter types of the method of a super class or interface which a bridge has the signature of:
    // type variables for the parameters of a generic type, else classes, also when there is no such method
    private static Type[] genericParameters(Class<?> cls, Method bridge) {
        List<Class<?>> pending = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        pending.add(cls);
        while (!pending.isEmpty()) {
            Class<?> c = pending.remove(0);
            if (c != cls)
                try {
                    return c.getDeclaredMethod(bridge.getName(), bridge.getParameterTypes()).getGenericParameterTypes();
                } catch (NoSuchMethodException ignored) {
                }
            if (c.getSuperclass() != null && seen.add(c.getSuperclass()))
                pending.add(c.getSuperclass());
            for (Class<?> i : c.getInterfaces())
                if (seen.add(i))
                    pending.add(i);
        }
        return bridge.getParameterTypes();
    }
}
