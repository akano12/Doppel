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

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// The targets of definitions, as the hook engine and the hook editor resolve them
public class XResolverTest {
    @SuppressWarnings("unused")
    public static class Base {
        public static final Base INSTANCE = new Sub();
        public String name = "base";

        public Base() {
        }

        public Base(String name) {
            this.name = name;
        }

        public Object get(int index) {
            return null;
        }

        public void put(CharSequence value) {
        }

        public void inherited() {
        }

        public void overridden() {
        }
    }

    @SuppressWarnings("unused")
    public static class Sub extends Base {
        public Sub() {
        }

        @Override
        public String get(int index) {
            return "sub";
        }

        @Override
        public void overridden() {
        }
    }

    @SuppressWarnings("unused")
    public abstract static class Generic<T> {
        public abstract void set(T value);

        public void set(T value, int count) {
        }
    }

    @SuppressWarnings("unused")
    public static class Concrete extends Generic<String> {
        @Override
        public void set(String value) {
        }
    }

    // Not public, so javac gives a public subclass a bridge method for each public method, which calls this method
    @SuppressWarnings("unused")
    static class Hidden {
        public String visible(String value) {
            return value;
        }

        private void helper(int value) {
        }

        public static void create() {
        }
    }

    @SuppressWarnings("unused")
    public static class Exposed extends Hidden {
        private void helper(String value) {
        }
    }

    @SuppressWarnings("unused")
    public static class Holder<T> {
        public void hold(T value) {
        }
    }

    @SuppressWarnings("unused")
    public static class StringHolder extends Holder<String> {
        @Override
        public void hold(String value) {
        }
    }

    private static Member resolve(Class<?> cls, String methodName, Class<?>[] params, Class<?> returnType) throws Throwable {
        return XResolver.resolveTarget(cls, methodName, params, returnType);
    }

    @Test
    public void method() throws Throwable {
        Member member = resolve(Sub.class, "get", new Class<?>[]{int.class}, null);
        assertEquals(Sub.class.getMethod("get", int.class), member);
    }

    @Test
    public void methodOfSuperClass() throws Throwable {
        Member member = resolve(Sub.class, "inherited", new Class<?>[0], null);
        assertEquals(Base.class.getMethod("inherited"), member);
    }

    @Test
    public void methodWithAssignableParameter() throws Throwable {
        Member member = resolve(Sub.class, "put", new Class<?>[]{String.class}, null);
        assertEquals(Base.class.getMethod("put", CharSequence.class), member);
    }

    @Test
    public void returnType() throws Throwable {
        // The type of the definition must be assignable to the return type of the method
        resolve(Base.class, "get", new Class<?>[]{int.class}, String.class);
        try {
            resolve(Base.class, "put", new Class<?>[]{CharSequence.class}, String.class);
            fail("Wrong return type accepted");
        } catch (NoSuchMethodException ex) {
            fail("Method not found");
        } catch (Throwable ex) {
            assertTrue(ex.getMessage(), ex.getMessage().startsWith("Invalid return type"));
        }
    }

    @Test
    public void constructor() throws Throwable {
        Member member = resolve(Base.class, null, new Class<?>[]{String.class}, null);
        assertEquals(Base.class.getConstructor(String.class), member);
        assertTrue(member instanceof Constructor);
    }

    // Constructors are not inherited, so a constructor of a super class is never the target
    @Test
    public void constructorOfClassOnly() throws Throwable {
        try {
            resolve(Sub.class, null, new Class<?>[]{String.class}, null);
            fail("Constructor of the super class resolved");
        } catch (NoSuchMethodException ignored) {
        }
    }

    @Test
    public void field() throws Throwable {
        Member member = resolve(Sub.class, "#name", new Class<?>[0], String.class);
        assertEquals(Base.class.getField("name"), member);
        assertTrue(member instanceof Field);

        try {
            resolve(Sub.class, "#name", new Class<?>[0], Object.class);
            fail("Field of another type resolved");
        } catch (NoSuchFieldException ignored) {
        }

        try {
            resolve(Sub.class, "#name", new Class<?>[]{int.class}, null);
            fail("Field with parameters resolved");
        } catch (NoSuchFieldException ignored) {
        }
    }

    // field:method is a method of the class of the object in a static field
    @Test
    public void methodOfObjectInField() throws Throwable {
        Member member = resolve(Base.class, "INSTANCE:get", new Class<?>[]{int.class}, null);
        assertEquals(Sub.class.getMethod("get", int.class), member);
    }

    private static List<String> methods(Class<?> cls, String name) {
        List<String> result = new ArrayList<>();
        for (Method method : XResolver.resolveMethods(cls, name))
            result.add(method.getDeclaringClass().getSimpleName() + "." + method.getName() +
                    method.getParameterTypes().length);
        return result;
    }

    // Dynamic hooks: the methods objects of a class run, declared or inherited
    @Test
    public void inheritedMethods() {
        assertEquals(Collections.singletonList("Base.inherited0"), methods(Sub.class, "inherited"));
        assertEquals(Collections.singletonList("Sub.overridden0"), methods(Sub.class, "overridden"));
        // Not the bridge method get(int) returning Object, which calls the method
        assertEquals(Collections.singletonList("Sub.get1"), methods(Sub.class, "get"));
        assertEquals(Collections.emptyList(), methods(Sub.class, "missing"));
        // The methods of Object are not hooked for all objects
        assertEquals(Collections.emptyList(), methods(Sub.class, "hashCode"));
    }

    @Test
    public void abstractMethods() {
        List<String> result = methods(Concrete.class, "set");
        assertEquals(2, result.size());
        assertTrue(result.contains("Concrete.set1"));
        assertTrue(result.contains("Generic.set2"));
    }

    // The bridge of a public class for a method of a super class which is not public calls that method
    @Test
    public void visibilityBridges() throws NoSuchMethodException {
        assertTrue(Exposed.class.getDeclaredMethod("visible", String.class).isBridge());
        assertEquals(Collections.singletonList("Hidden.visible1"), methods(Exposed.class, "visible"));
    }

    // The bridge of a generic type calls the method of the class, which overrides the method of the super class
    @Test
    public void genericBridges() {
        assertEquals(Collections.singletonList("StringHolder.hold1"), methods(StringHolder.class, "hold"));
    }

    // Private and static methods of a super class are not methods of the objects of a class, but of the class itself
    @Test
    public void notInherited() {
        assertEquals(Collections.singletonList("Exposed.helper1"), methods(Exposed.class, "helper"));
        assertEquals(Collections.emptyList(), methods(Exposed.class, "create"));
        assertEquals(Collections.singletonList("Hidden.helper1"), methods(Hidden.class, "helper"));
        assertEquals(Collections.singletonList("Hidden.create0"), methods(Hidden.class, "create"));
    }
}
