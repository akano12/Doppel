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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class XParam {
    private static final String TAG = "XLua.XParam";

    private final Context context;
    private final Field field;
    private final XCall param;
    private final Class<?>[] paramTypes;
    private final Class<?> returnType;
    private final Map<String, String> settings;

    // Values scripts keep per scope, like per object, see putValue and getValue
    private static final Map<Object, Map<String, Object>> scopedValues = new WeakHashMap<>();

    // See isPackageVisible
    private static final Map<String, Boolean> visiblePackages = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> checkingPackage = new ThreadLocal<>();

    // Field param
    public XParam(
            Context context,
            Field field,
            Map<String, String> settings) {
        this.context = context;
        this.field = field;
        this.param = null;
        this.paramTypes = null;
        this.returnType = field.getType();
        this.settings = settings;
    }

    // Method param
    public XParam(
            Context context,
            XCall param,
            Map<String, String> settings) {
        this.context = context;
        this.field = null;
        this.param = param;
        if (param.method instanceof Constructor) {
            this.paramTypes = ((Constructor) param.method).getParameterTypes();
            this.returnType = null;
        } else {
            this.paramTypes = ((Method) param.method).getParameterTypes();
            this.returnType = ((Method) param.method).getReturnType();
        }
        this.settings = settings;
    }

    @SuppressWarnings("unused")
    public Context getApplicationContext() {
        return this.context;
    }

    @SuppressWarnings("unused")
    public String getPackageName() {
        return this.context.getPackageName();
    }

    @SuppressWarnings("unused")
    public int getUid() {
        return this.context.getApplicationInfo().uid;
    }

    @SuppressWarnings("unused")
    public Object getThis() {
        if (this.field == null)
            return this.param.thisObject;
        else
            return null;
    }

    @SuppressWarnings("unused")
    public Object getArgument(int index) {
        checkArgument(index);
        return this.param.args[index];
    }

    @SuppressWarnings("unused")
    public void setArgument(int index, Object value) {
        checkArgument(index);

        if (value == null) {
            // Calling with null for a primitive parameter would crash the app
            if (this.paramTypes[index].isPrimitive())
                throw new IllegalArgumentException("Expected argument #" + index + " " + this.paramTypes[index] + " got null");
        } else {
            value = coerceValue(this.paramTypes[index], value);
            if (!boxType(this.paramTypes[index]).isInstance(value))
                throw new IllegalArgumentException(
                        "Expected argument #" + index + " " + this.paramTypes[index] + " got " + value.getClass());
        }

        this.param.args[index] = value;
    }

    // Fields have no arguments
    private void checkArgument(int index) {
        if (this.field != null)
            throw new IllegalStateException("Field " + this.field.getName() + " has no arguments");
        if (index < 0 || index >= this.paramTypes.length)
            throw new ArrayIndexOutOfBoundsException("Argument #" + index);
    }

    // Classes of the hooked app (like Google Play services) are not visible to luajava.bindClass
    @SuppressWarnings("unused")
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, this.context.getClassLoader());
    }

    // Lua sees a Class as its static members, so Class.isInstance cannot be called from Lua
    @SuppressWarnings("unused")
    public boolean isInstance(Object object, String className) throws ClassNotFoundException {
        return (object != null && findClass(className).isInstance(object));
    }

    // Packages which stay visible to an app checking for a specific package:
    // its own package, packages sharing its user id and system packages, which the Android framework,
    // like WebView, looks up in the app process
    @SuppressWarnings("unused")
    public boolean isPackageVisible(String packageName) {
        if (packageName == null || packageName.equals(this.context.getPackageName()))
            return true;

        Boolean visible = visiblePackages.get(packageName);
        if (visible != null)
            return visible;

        // The check below calls hooked methods
        if (Boolean.TRUE.equals(checkingPackage.get()))
            return true;

        checkingPackage.set(true);
        try {
            ApplicationInfo ai = this.context.getPackageManager().getApplicationInfo(packageName, 0);
            visible = (ai.uid == getUid() ||
                    (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
        } catch (PackageManager.NameNotFoundException ignored) {
            // Not installed or not visible because of the package visibility rules
            visible = true;
        } finally {
            checkingPackage.remove();
        }

        visiblePackages.put(packageName, visible);
        return visible;
    }

    @SuppressWarnings("unused")
    public Throwable getException() {
        Throwable ex = (this.field == null ? this.param.getThrowable() : null);
        if (BuildConfig.DEBUG)
            Log.i(TAG, "Get " + this.getPackageName() + ":" + this.getUid() + " exception=" + ex);
        return ex;
    }

    @SuppressWarnings("unused")
    public Object getResult() throws Throwable {
        Object result = (this.field == null ? this.param.getResult() : this.field.get(null));
        if (BuildConfig.DEBUG)
            Log.i(TAG, "Get " + this.getPackageName() + ":" + this.getUid() + " result=" + result);
        return result;
    }

    @SuppressWarnings("unused")
    public void setResult(Object result) throws Throwable {
        if (BuildConfig.DEBUG)
            Log.i(TAG, "Set " + this.getPackageName() + ":" + this.getUid() +
                    " result=" + result + " return=" + this.returnType);
        // Returning null for a primitive would crash the app
        if (result == null && this.returnType != null && this.returnType.isPrimitive() && this.returnType != void.class)
            throw new IllegalArgumentException("Expected return " + this.returnType + " got null");
        if (result != null && !(result instanceof Throwable) && this.returnType != null) {
            result = coerceValue(this.returnType, result);
            if (!boxType(this.returnType).isInstance(result))
                throw new IllegalArgumentException(
                        "Expected return " + this.returnType + " got " + result.getClass());
        }

        if (this.field == null)
            if (result instanceof Throwable)
                this.param.setThrowable((Throwable) result);
            else
                this.param.setResult(result);
        else
            this.field.set(null, result);
    }

    @SuppressWarnings("unused")
    public String getSetting(String name) {
        // The settings are read only, see XCore.hookPackage
        String value = this.settings.get(name);
        if (BuildConfig.DEBUG)
            Log.i(TAG, "Get setting " + this.getPackageName() + ":" + this.getUid() + " " + name + "=" + value);
        return value;
    }

    @SuppressWarnings("unused")
    public void putValue(String name, Object value, Object scope) {
        if (BuildConfig.DEBUG)
            Log.i(TAG, "Put value " + this.getPackageName() + ":" + this.getUid() + " " + name + "=" + value + " @" + scope);
        synchronized (scopedValues) {
            if (!scopedValues.containsKey(scope))
                scopedValues.put(scope, new HashMap<>());
            scopedValues.get(scope).put(name, value);
        }
    }

    @SuppressWarnings("unused")
    public Object getValue(String name, Object scope) {
        Object value = getValueInternal(name, scope);
        if (BuildConfig.DEBUG)
            Log.i(TAG, "Get value " + this.getPackageName() + ":" + this.getUid() + " " + name + "=" + value + " @" + scope);
        return value;
    }

    private static Object getValueInternal(String name, Object scope) {
        synchronized (scopedValues) {
            Map<String, Object> values = scopedValues.get(scope);
            return (values == null ? null : values.get(name));
        }
    }

    private static Class<?> boxType(Class<?> type) {
        if (type == boolean.class)
            return Boolean.class;
        else if (type == byte.class)
            return Byte.class;
        else if (type == char.class)
            return Character.class;
        else if (type == short.class)
            return Short.class;
        else if (type == int.class)
            return Integer.class;
        else if (type == long.class)
            return Long.class;
        else if (type == float.class)
            return Float.class;
        else if (type == double.class)
            return Double.class;
        return type;
    }

    static Object coerceValue(Class<?> type, Object value) {
        // Lua numbers become an Integer or a Double, like 5000000000 for a long;
        // whole numbers which fit are converted to the numeric type expected
        if (value instanceof Number && type.isPrimitive()) {
            Number number = (Number) value;
            double d = number.doubleValue();
            if (double.class.equals(type))
                return d;
            else if (float.class.equals(type))
                return (float) d;
            else if (d == Math.rint(d)) {
                if (long.class.equals(type) && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE)
                    return number.longValue();
                else if (int.class.equals(type) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE)
                    return number.intValue();
                else if (short.class.equals(type) && d >= Short.MIN_VALUE && d <= Short.MAX_VALUE)
                    return number.shortValue();
                else if (byte.class.equals(type) && d >= Byte.MIN_VALUE && d <= Byte.MAX_VALUE)
                    return number.byteValue();
            }
        } else if (value instanceof String && int.class.equals(type))
            return Integer.parseInt((String) value);
        else if (value instanceof String && long.class.equals(type))
            return Long.parseLong((String) value);
        else if (value instanceof String && float.class.equals(type))
            return Float.parseFloat((String) value);
        else if (value instanceof String && double.class.equals(type))
            return Double.parseDouble((String) value);

        return value;
    }
}
