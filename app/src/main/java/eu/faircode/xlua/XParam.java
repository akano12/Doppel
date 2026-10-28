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

    Copyright 2017-2019 Marcel Bokhorst (M66B)
    Copyright 2026 akano12
 */

package eu.faircode.xlua;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import org.luaj.vm2.LuaValue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class XParam {
    private static final String TAG = "XLua.XParam";

    private final Context context;
    private final Field field;
    private final XCall param;
    private final Class<?> returnType;
    private final Map<String, String> settings;

    // Values scripts keep per scope, like per object, see putValue and getValue
    private static final Map<Object, Map<String, Object>> scopedValues = new WeakHashMap<>();

    // See isPackageVisible and isUidVisible
    private static final Map<String, Boolean> visiblePackages = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> visibleUids = new ConcurrentHashMap<>();

    // Field param
    public XParam(Context context, Field field, Map<String, String> settings) {
        this.context = context;
        this.field = field;
        this.param = null;
        this.returnType = field.getType();
        this.settings = settings;
    }

    // Method param
    public XParam(Context context, XCall param, Map<String, String> settings) {
        this.context = context;
        this.field = null;
        this.param = param;
        this.returnType = (param.method instanceof Method ? ((Method) param.method).getReturnType() : null);
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
        return (this.field == null ? this.param.thisObject : null);
    }

    @SuppressWarnings("unused")
    public Object getArgument(int index) {
        checkArgument(index);
        return this.param.args[index];
    }

    @SuppressWarnings("unused")
    public void setArgument(int index, Object value) {
        checkArgument(index);

        // A copy, so only made when needed
        Class<?>[] paramTypes = XResolver.parameterTypes(this.param.method);
        this.param.args[index] = checkValue(paramTypes[index], value, "argument #" + index);
    }

    // Fields have no arguments
    private void checkArgument(int index) {
        if (this.field != null)
            throw new IllegalStateException("Field " + this.field.getName() + " has no arguments");
        if (index < 0 || index >= (this.param.args == null ? 0 : this.param.args.length))
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

    // Processes of the system, like system server and system apps with the system uid, see all apps
    @SuppressWarnings("unused")
    public boolean isSystem() {
        return (Util.getAppId(getUid()) < Process.FIRST_APPLICATION_UID);
    }

    // Packages which stay visible to an app: its own package, packages sharing its user id and system packages,
    // which the Android framework, like WebView, looks up in the app process; null has no package to hide.
    // All packages are visible to processes of the system, see isSystem.
    @SuppressWarnings("unused")
    public boolean isVisible(ApplicationInfo ai) {
        return (ai == null || isSystem() || ai.uid == getUid() ||
                (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
    }

    // Whether a package, which a hooked call found, stays visible to the app checking for it, see isVisible
    @SuppressWarnings("unused")
    public boolean isPackageVisible(String packageName) {
        if (packageName == null || isSystem() || packageName.equals(this.context.getPackageName()))
            return true;

        Boolean visible = visiblePackages.get(packageName);
        if (visible != null)
            return visible;

        // The lookup calls hooked methods, like the package probes, which would else hide the package
        // and report a call the app did not make
        XCore.enterService();
        try {
            visible = isVisible(getPackageInfo(this.context.getPackageManager(), packageName).applicationInfo);
        } catch (PackageManager.NameNotFoundException ignored) {
            // The hooked call found the package, the lookup, which finds system packages, did not;
            // not kept, the package can be installed while the app runs
            return false;
        } finally {
            XCore.leaveService();
        }

        visiblePackages.put(packageName, visible);
        return visible;
    }

    // Whether a uid stays visible to the app: its own uid, the uids of the system (app ids below 10000)
    // and the uids with a visible package, see isVisible
    @SuppressWarnings("unused")
    public boolean isUidVisible(int uid) {
        if (isSystem() || uid == getUid() || Util.getAppId(uid) < Process.FIRST_APPLICATION_UID)
            return true;

        Boolean visible = visibleUids.get(uid);
        if (visible != null)
            return visible;

        // The lookups call hooked methods, see isPackageVisible
        boolean found = false;
        visible = false;
        XCore.enterService();
        try {
            PackageManager pm = this.context.getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            if (packages != null)
                for (String packageName : packages)
                    try {
                        PackageInfo pi = getPackageInfo(pm, packageName);
                        found = true;
                        if (isVisible(pi.applicationInfo)) {
                            visible = true;
                            break;
                        }
                    } catch (PackageManager.NameNotFoundException ignored) {
                    }
        } finally {
            XCore.leaveService();
        }

        // Not kept without packages, an app with the uid can be installed while the app runs
        if (found)
            visibleUids.put(uid, visible);
        return visible;
    }

    // Whether the package manager tells the app about a package, which is installed for the user
    // and not hidden by the package visibility rules of Android, and it stays visible, see isVisible.
    // Not kept, the package can be installed or removed while the app runs.
    @SuppressWarnings("unused")
    public boolean isInstalledVisible(String packageName) {
        if (packageName == null)
            return false;
        if (packageName.equals(this.context.getPackageName()))
            return true;

        // The lookup calls hooked methods, see isPackageVisible
        XCore.enterService();
        try {
            return isVisible(Util.getApplicationInfo(this.context.getPackageManager(), packageName, 0));
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        } finally {
            XCore.leaveService();
        }
    }

    // Runs a function of a script without the hooks of the app on this thread, for lookups of the script itself,
    // which hooks would else restrict and report as calls of the app; returns the first value the function returns
    @SuppressWarnings("unused")
    public LuaValue unhooked(LuaValue function) {
        XCore.enterService();
        try {
            return function.call();
        } finally {
            XCore.leaveService();
        }
    }

    // Also packages which only some flags of hooked calls find: hidden, archived, uninstalled with data kept
    // and, before Android 16, of another user (profile); and system packages which are not installed for the user
    // The flag of Android 7 is the same as the one of Android 6
    @SuppressWarnings("InlinedApi")
    private static PackageInfo getPackageInfo(PackageManager pm, String packageName)
            throws PackageManager.NameNotFoundException {
        // MATCH_UNINSTALLED_PACKAGES has the value of GET_UNINSTALLED_PACKAGES of Android 6
        long flags = PackageManager.MATCH_UNINSTALLED_PACKAGES;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            flags |= PackageManager.MATCH_APEX;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
            flags |= PackageManager.MATCH_ARCHIVED_PACKAGES;
        return Util.getPackageInfo(pm, packageName, flags);
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
        // A throwable is thrown instead of returned, a constructor has no return type
        if (this.returnType != null && !(result instanceof Throwable))
            result = checkValue(this.returnType, result, "return");

        if (this.field == null)
            if (result instanceof Throwable)
                this.param.setThrowable((Throwable) result);
            else
                this.param.setResult(result);
        else if (result instanceof Throwable)
            // Reading a field cannot throw
            throw new IllegalArgumentException("Expected field " + this.returnType + " got " + result.getClass());
        else
            this.field.set(null, result);
    }

    // Whether the file the hooked method opens, its first argument, is a file, also by another path: relative to the
    // root, which apps run in, with // or /./, by the links of /proc to the root, like /proc/self/root/proc/meminfo,
    // and by other links, like the file system resolves them
    @SuppressWarnings("unused")
    public boolean opens(String file) {
        Object path = getArgument(0);
        return (path != null && isPath(path.toString(), file));
    }

    static boolean isPath(String path, String file) {
        String plain = ("/" + path).replaceAll("/(\\./)+", "/").replaceAll("//+", "/")
                .replaceFirst("^/proc/[\\w-]+/root/", "/");
        if (plain.equals(file))
            return true;
        try {
            return new File(path).getCanonicalPath().equals(file);
        } catch (IOException ignored) {
            return false;
        }
    }

    // The text of the file the hooked method opened, like a file of /proc, of which it returned the descriptor
    // or its number, like java.nio opens files; the app still reads it from the start
    @SuppressWarnings("unused")
    public String getFileText() throws Throwable {
        Object result = this.param.getResult();
        if (result instanceof Integer)
            // A descriptor of its own, which the reading does not move, like the one of the app
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromFd((Integer) result)) {
                return readText(pfd.getFileDescriptor());
            }
        return readText((FileDescriptor) result);
    }

    private static String readText(FileDescriptor fd) throws Throwable {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n = Os.pread(fd, buffer, 0, buffer.length, 0); n > 0;
             n = Os.pread(fd, buffer, 0, buffer.length, text.size()))
            text.write(buffer, 0, n);
        return text.toString(StandardCharsets.UTF_8.name());
    }

    // The app reads the text instead of the file the hooked method opened, without a file being written:
    // the method returns a file in memory, or a pipe before Android 11, of which the app cannot tell
    // the contents of the file apart; the file which was opened is closed. java.nio gets the number of a descriptor.
    @SuppressWarnings("unused")
    public void setFileText(String text) throws Throwable {
        Object opened = this.param.getResult();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        FileDescriptor fake;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fake = Os.memfd_create("file", OsConstants.MFD_CLOEXEC);
            try {
                for (int at = 0; at < bytes.length; )
                    at += Os.write(fake, bytes, at, bytes.length - at);
                Os.lseek(fake, 0, OsConstants.SEEK_SET);
            } catch (Throwable ex) {
                Os.close(fake);
                throw ex;
            }
        } else {
            // A pipe holds at least a page, without a reader; the texts of files of /proc are small
            if (bytes.length > 4096)
                throw new IllegalArgumentException("Text of " + bytes.length + " bytes too long");
            FileDescriptor[] pipe = Os.pipe();
            fake = pipe[0];
            try {
                for (int at = 0; at < bytes.length; )
                    at += Os.write(pipe[1], bytes, at, bytes.length - at);
            } catch (Throwable ex) {
                Os.close(fake);
                throw ex;
            } finally {
                Os.close(pipe[1]);
            }
        }
        if (opened instanceof Integer) {
            this.param.setResult(ParcelFileDescriptor.dup(fake).detachFd());
            Os.close(fake);
            ParcelFileDescriptor.adoptFd((Integer) opened).close();
        } else {
            this.param.setResult(fake);
            Os.close((FileDescriptor) opened);
        }
    }

    // The text of a file like the app reads it, with its hooks of opening files, like of /proc/meminfo;
    // null when the app cannot read it
    @SuppressWarnings("unused")
    public String readFile(String path) {
        try (InputStream is = new FileInputStream(path)) {
            return Util.readText(is);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // A random looking UUID, the same for the same seed, like a UUID of the kernel (version 4),
    // of which the seed cannot be told
    @SuppressWarnings("unused")
    public String uuidOf(String seed) throws Throwable {
        return uuid(seed);
    }

    static String uuid(String seed) throws Throwable {
        ByteBuffer hash = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                .digest(seed.getBytes(StandardCharsets.UTF_8)));
        long msb = (hash.getLong() & ~0xf000L) | 0x4000L;
        long lsb = (hash.getLong() & ~(0xc0L << 56)) | (0x80L << 56);
        return new UUID(msb, lsb).toString();
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
        Object value;
        synchronized (scopedValues) {
            Map<String, Object> values = scopedValues.get(scope);
            value = (values == null ? null : values.get(name));
        }
        if (BuildConfig.DEBUG)
            Log.i(TAG, "Get value " + this.getPackageName() + ":" + this.getUid() + " " + name + "=" + value + " @" + scope);
        return value;
    }

    // The value converted to the type, of which it must be an instance; null for a primitive would crash the app,
    // a void method returns null
    private static Object checkValue(Class<?> type, Object value, String what) {
        if (value == null) {
            if (type.isPrimitive() && type != void.class)
                throw new IllegalArgumentException("Expected " + what + " " + type + " got null");
            return null;
        }
        value = coerceValue(type, value);
        if (!boxType(type).isInstance(value))
            throw new IllegalArgumentException("Expected " + what + " " + type + " got " + value.getClass());
        return value;
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
