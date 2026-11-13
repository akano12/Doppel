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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.StrictMode;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.system.StructTimespec;
import android.util.Log;
import android.webkit.WebView;

import org.luaj.vm2.LuaValue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class XParam {
    private static final String TAG = "XLua.XParam";

    private final Context context;
    private final Field field;
    private final XCall param;
    private final Class<?> returnType;
    private final Map<String, String> settings;
    // The length of the file the hooked method opened, once read, see setFileBytes
    private long fileLength = -1;

    // Values scripts keep per scope, like per object, see putValue and getValue: by identity, like Android tells the
    // listeners of an app apart (NsdManager by SparseArray.indexOfValue), so hashCode and equals of the app do not run
    // in a hook; weakly, so a scope does not keep its object
    private static final Map<Scope, Map<String, Object>> scopedValues = new HashMap<>();
    private static final ReferenceQueue<Object> scopesGone = new ReferenceQueue<>();
    // The scope of null, which is a scope too
    private static final Object NO_SCOPE = new Object();

    // See isPackageVisible and isUidVisible
    private static final Map<String, Boolean> visiblePackages = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> visibleUids = new ConcurrentHashMap<>();
    private static volatile String webViewPackage;
    // Whether the package of WebView was looked up, also when there is none; not when the lookup failed
    private static volatile boolean webViewKnown = false;

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
        Class<?>[] paramTypes = ((Executable) this.param.method).getParameterTypes();
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

    // Copies the fields the class declares from one object to another, also into an object of a subclass, of which
    // Lua does not see these fields; in Java, since through Lua a long becomes a double, which is not exact past 2^53
    @SuppressWarnings("unused")
    public void copyFields(String className, Object from, Object to) throws Exception {
        copyFields(findClass(className), from, to);
    }

    static void copyFields(Class<?> type, Object from, Object to) throws Exception {
        boolean copied = false;
        for (Field field : type.getDeclaredFields())
            if (!Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                field.set(to, field.get(from));
                copied = true;
            }
        // The hidden API rules leave out the fields they refuse instead of failing, so nothing copied is a failure
        if (!copied)
            throw new NoSuchFieldException("No fields of " + type.getName());
    }

    // Replaces a long field of an object, also a final one, by random looking bits of the given width in the form of the
    // f_fsid of bionic, the same for the same seed and value, never 0; in Java, since through Lua a long becomes a
    // double, of which a fake would never have the high bits a real value has. Returns the fake for the log.
    @SuppressWarnings("unused")
    public String fakeLong(Object object, String name, String seed, int bits) throws Throwable {
        return Long.toString(fakeLong(object, object.getClass().getField(name), seed, bits));
    }

    static long fakeLong(Object object, Field field, String seed, int bits) throws Throwable {
        field.setAccessible(true);
        ByteBuffer hash = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                .digest((seed + "/" + field.getLong(object)).getBytes(StandardCharsets.UTF_8)));
        // Bionic packs the two ints of the kernel like this, with the low one sign extended, so a real id of 64 bits has
        // bit 31 clear, or bit 31 set and the high half all ones; 64 uniform bits would have neither form half the time
        int low = hash.getInt();
        int high = hash.getInt();
        long fake = low | ((long) high << 32);
        if (bits < Long.SIZE)
            fake &= (1L << bits) - 1;
        if (fake == 0)
            fake = 1;
        field.setLong(object, fake);
        return fake;
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

    // The package of WebView, which can be an app of the user, like WebView Beta, null when unknown
    private static String getWebViewPackage() {
        if (webViewPackage == null)
            try {
                PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null)
                    webViewPackage = pi.packageName;
                webViewKnown = true;
            } catch (RuntimeException ex) {
                // Like before the app has an application, tried again for the next package
                XLog.w(TAG, "WebView package: " + ex);
            }
        return webViewPackage;
    }

    // Whether a package, which a hooked call found, stays visible to the app checking for it, see isVisible;
    // also the package of WebView, which the framework looks up in the app process, whichever app it is
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
            if (!visible) {
                String webView = getWebViewPackage();
                // Not kept while the lookup of the package of WebView fails, like before the app has an application:
                // the package can be WebView, of the user, like WebView Beta, which would stay hidden for good
                if (webView == null && !webViewKnown)
                    return false;
                visible = packageName.equals(webView);
            }
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

    // Whether a uid stays visible to the app: its own uid, the uid calling it, the uids of the system (app ids below
    // 10000) and the uids with a visible package, see isVisible
    @SuppressWarnings("unused")
    public boolean isUidVisible(int uid) {
        if (isSystem() || uid == getUid() || uid == Binder.getCallingUid() ||
                Util.getAppId(uid) < Process.FIRST_APPLICATION_UID)
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
    private static PackageInfo getPackageInfo(PackageManager pm, String packageName)
            throws PackageManager.NameNotFoundException {
        long flags = PackageManager.MATCH_UNINSTALLED_PACKAGES;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            flags |= PackageManager.MATCH_APEX;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
            flags |= PackageManager.MATCH_ARCHIVED_PACKAGES;
        return Util.getPackageInfo(pm, packageName, flags);
    }

    // A value for the log of debug builds: its toString can be hooked, like NetworkInfo.toString,
    // and must not run the scripts as if the app called it
    private static String text(Object value) {
        XCore.enterService();
        try {
            return String.valueOf(value);
        } finally {
            XCore.leaveService();
        }
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
            Log.i(TAG, "Get " + this.getPackageName() + ":" + this.getUid() + " result=" + text(result));
        return result;
    }

    @SuppressWarnings("unused")
    public void setResult(Object result) throws Throwable {
        if (BuildConfig.DEBUG)
            Log.i(TAG, "Set " + this.getPackageName() + ":" + this.getUid() +
                    " result=" + text(result) + " return=" + this.returnType);
        // A throwable is thrown instead of returned, a constructor has no return type
        if (this.returnType != null && !(result instanceof Throwable))
            result = checkValue(this.returnType, result, "return");

        if (this.field == null)
            if (result instanceof Throwable)
                this.param.setThrowable(withoutHookFrames((Throwable) result));
            else
                this.param.setResult(result);
        else if (result instanceof Throwable)
            // Reading a field cannot throw
            throw new IllegalArgumentException("Expected field " + this.returnType + " got " + result.getClass());
        else
            try {
                this.field.set(null, result);
            } catch (IllegalAccessException ignored) {
                setFinalField(this.field, result);
            }
    }

    // The frames of the hook which made a throwable of a script: of Doppel, LuaJ and the hook frameworks whose names are
    // not obfuscated, and of the reflection which luajava makes it by
    private static final String[] HOOK_FRAMES = {"eu.faircode.xlua.", "org.luaj.", "de.robv.android.xposed.",
            "io.github.libxposed.", "org.lsposed.", "org.matrix.vector."};
    private static final String[] REFLECTION_FRAMES = {"java.lang.reflect.", "jdk.internal.reflect.", "sun.reflect."};

    // A throwable a script makes records the frames of the script, the engine and the hook framework when it is made,
    // which name LuaJ and Doppel; the app gets it, also its causes, without them, like with the frames of the hooked
    // method down, and a throwable with only frames of its own, like the copy of package_probe, as it is.
    // shortcut: the frames Android has above the caller, like the native libcore.io.Linux.open of an ErrnoException, stay
    // missing, the hooker frame and obfuscated frames of the framework stay, and frames of the app's own classes in the
    // packages of the frameworks go too, upgrade when apps compare them.
    static Throwable withoutHookFrames(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable each = ex; each != null && seen.add(each); each = each.getCause()) {
            StackTraceElement[] frames = each.getStackTrace();
            StackTraceElement[] kept = withoutHookFrames(frames);
            if (kept.length != frames.length)
                each.setStackTrace(kept);
        }
        return ex;
    }

    // Without the frames of the hook, and the frames of reflection right above them, which made the throwable, like
    // Constructor.newInstance; the reflection of the app, like Method.invoke calling the hooked method, stays
    static StackTraceElement[] withoutHookFrames(StackTraceElement[] frames) {
        List<StackTraceElement> kept = new ArrayList<>();
        boolean hookBelow = false;
        for (int i = frames.length - 1; i >= 0; i--) {
            String name = frames[i].getClassName();
            if (startsWith(name, HOOK_FRAMES) || (hookBelow && startsWith(name, REFLECTION_FRAMES)))
                hookBelow = true;
            else {
                hookBelow = false;
                kept.add(frames[i]);
            }
        }
        Collections.reverse(kept);
        return kept.toArray(new StackTraceElement[0]);
    }

    private static boolean startsWith(String name, String[] prefixes) {
        for (String prefix : prefixes)
            if (name.startsWith(prefix))
                return true;
        return false;
    }

    // The message of an exception, like of one without a constructor with a message (see package_probe): a private field
    // of Throwable, which LuaJ does not see on a subclass; Android lets every app set it by reflection (unsupported API)
    @SuppressWarnings("unused")
    @SuppressLint("DiscouragedPrivateApi")
    public void setMessage(Throwable ex, String message) throws Throwable {
        Field field = Throwable.class.getDeclaredField("detailMessage");
        field.setAccessible(true);
        field.set(ex, message);
    }

    // Android 17 refuses reflection on static final fields for apps targeting it, their memory stays writable
    @SuppressLint("DiscouragedPrivateApi")
    static void setFinalField(Field field, Object value) throws Exception {
        // Initializing the class later would overwrite the value
        field.get(null);
        Class<?> type = field.getType();
        String put = "put" + (type.isPrimitive()
                ? Character.toUpperCase(type.getName().charAt(0)) + type.getName().substring(1)
                : "Object");
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field instance = unsafe.getDeclaredField("theUnsafe");
        instance.setAccessible(true);
        int offset = (int) Field.class.getMethod("getOffset").invoke(field);
        unsafe.getMethod(put, Object.class, long.class, type.isPrimitive() ? type : Object.class)
                .invoke(instance.get(null), field.getDeclaringClass(), (long) offset, value);
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
        if (plainPath("/" + path).equals(file))
            return true;
        // Without the hooks of the app, which give the canonical path of a hidden file as of one which does not exist
        XCore.enterService();
        try {
            return new File(path).getCanonicalPath().equals(file);
        } catch (IOException ignored) {
            return false;
        } finally {
            XCore.leaveService();
        }
    }

    // See plainPath, compiled once: the filters of several hooks run it for one call of the app, before and after it
    private static final Pattern DOT_FOLDERS = Pattern.compile("/(\\./)+");
    private static final Pattern DOUBLE_SLASHES = Pattern.compile("//+");
    private static final Pattern DOT_AT_END = Pattern.compile("/\\.$");
    private static final Pattern LINK_TO_ROOT = Pattern.compile("^/proc/[\\w-]+/(task/\\d+/)?(root|cwd)/");
    private static final Pattern LINK_TO_ROOT_ITSELF = Pattern.compile("^/proc/[\\w-]+/(task/\\d+/)?(root|cwd)$");

    // An absolute path without // and /./, also at the end, and without a link of /proc to the root, also of the working
    // directory, which is the root for apps, like the file system reads it, see isPath and the filters of XCore; the
    // path itself without any of them, checked first, since the filters run for every call
    static String plainPath(String path) {
        if (!path.contains("/.") && !path.contains("//") && !path.contains("/root/") && !path.contains("/cwd/") &&
                !path.endsWith("/root") && !path.endsWith("/cwd"))
            return path;
        String plain = DOT_FOLDERS.matcher(path).replaceAll("/");
        plain = DOUBLE_SLASHES.matcher(plain).replaceAll("/");
        plain = DOT_AT_END.matcher(plain).replaceFirst("");
        // Also the link of a thread (/proc/self/task/123/root/) and links after each other, which lead to the root too
        for (String rooted = null; !plain.equals(rooted); )
            plain = LINK_TO_ROOT.matcher(rooted = plain).replaceFirst("/");
        // The link itself, like /proc/self/root, is the root
        if (LINK_TO_ROOT_ITSELF.matcher(plain).matches())
            plain = "/";
        return (plain.isEmpty() ? "/" : plain);
    }

    // The text of the file the hooked method opened, like a file of /proc, of which it returned the descriptor
    // or its number, like java.nio opens files; the app still reads it from the start
    @SuppressWarnings("unused")
    public String getFileText() throws Throwable {
        return new String(getFileBytes(), StandardCharsets.UTF_8);
    }

    private byte[] getFileBytes() throws Throwable {
        Object result = this.param.getResult();
        // Quietly, since the reads of Doppel are no disk reads of the app (see quietly)
        byte[] bytes = quietly(() -> {
            if (result instanceof Integer)
                // A descriptor of its own, which the reading does not move, like the one of the app
                try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromFd((Integer) result)) {
                    return readBytes(pfd.getFileDescriptor());
                }
            return readBytes((FileDescriptor) result);
        });
        this.fileLength = bytes.length;
        return bytes;
    }

    private static byte[] readBytes(FileDescriptor fd) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n = Os.pread(fd, buffer, 0, buffer.length, 0); n > 0;
             n = Os.pread(fd, buffer, 0, buffer.length, bytes.size()))
            bytes.write(buffer, 0, n);
        return bytes.toByteArray();
    }

    // The app reads the text instead of the file the hooked method opened, without a file being kept:
    // the method returns a file in memory, or before Android 11 a removed file or a pipe (see oldFile), of which the app
    // cannot tell the contents of the file apart, nor but for a pipe by the hooks of faked_files its stat, size and link
    // (see unfakeFile); the fake takes the number of the file which was opened, which is closed (see renumbered), also for
    // java.nio, which gets the number of a descriptor.
    @SuppressWarnings("unused")
    public void setFileText(String text) throws Throwable {
        setFileBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private void setFileBytes(byte[] bytes) throws Throwable {
        Object opened = this.param.getResult();
        FileDescriptor fake = quietly(() -> newFile(opened, bytes));
        try {
            long read = (this.fileLength < 0 ? getFileBytes().length : this.fileLength);
            quietly(() -> addFaked(opened, fake, read, bytes.length));
        } catch (Throwable ignored) {
            // The app still reads the text, only the descriptor tells the fake apart, like before
        }
        if (quietly(() -> renumbered(opened, fake, this.param.args)))
            return;
        if (opened instanceof Integer) {
            this.param.setResult(ParcelFileDescriptor.dup(fake).detachFd());
            Os.close(fake);
            ParcelFileDescriptor.adoptFd((Integer) opened).close();
        } else {
            this.param.setResult(fake);
            Os.close((FileDescriptor) opened);
        }
    }

    // The fake in place of the file opened (dup2, which closes that), with its number and its flags, like close-on-exec,
    // which dup2 clears, so the next file the app opens gets the number it would without the fake, the next free one;
    // false when the number cannot be read or moved, then the fake keeps a number of its own, like before.
    // shortcut: on Android 9 and 10 the fake of a file opened with O_CLOEXEC keeps a number of its own (see movable); a
    // fake in memory is open for reading and writing (F_GETFL, a write succeeds), since reopening it read-only by
    // /proc/self/fd needs the SELinux open permission of the files in memory of apps; native code can map a fake but a
    // pipe, and /proc/self/fdinfo tells the inode of each; upgrade when apps open files of /proc with O_CLOEXEC on
    // Android 9 and 10 or check how a file of /proc is open.
    // NewApi: Os.fcntlInt is public from Android 11 and hidden on Android 8.1, which checks no hidden APIs (see hasFcntl).
    @SuppressLint("NewApi")
    private static boolean renumbered(Object opened, FileDescriptor fake, Object[] args) throws Exception {
        int sdk = Build.VERSION.SDK_INT;
        if (!movable(sdk, args, OsConstants.O_CLOEXEC))
            return false;
        boolean fcntl = hasFcntl(sdk);
        int flags = 0;
        FileDescriptor replaced;
        try {
            int number;
            if (opened instanceof Integer) {
                number = (Integer) opened;
                if (fcntl) {
                    // A descriptor object for the number, given back without closing it
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.adoptFd(number);
                    try {
                        flags = Os.fcntlInt(pfd.getFileDescriptor(), OsConstants.F_GETFD, 0);
                    } finally {
                        pfd.detachFd();
                    }
                }
            } else {
                Method getInt = descriptorNumber();
                if (getInt == null)
                    return false;
                number = (int) getInt.invoke(opened);
                if (fcntl)
                    flags = Os.fcntlInt((FileDescriptor) opened, OsConstants.F_GETFD, 0);
            }
            replaced = Os.dup2(fake, number);
        } catch (Exception ex) {
            // Nothing changed, the descriptors are as they were
            XLog.w(TAG, "Renumber: " + ex);
            return false;
        }
        try {
            if (flags != 0)
                Os.fcntlInt(replaced, OsConstants.F_SETFD, flags);
        } finally {
            Os.close(fake);
        }
        return true;
    }

    // Whether fcntl, which reads and sets the flags of a descriptor, may be called: Android 8.1 has it as a hidden API but
    // checks no hidden APIs, Android 11 makes it public, and Android 9 and 10 refuse it to apps (not in the light
    // greylist of Android 9, greylist-max-o of Android 10), which would fail to link the call (NoSuchMethodError)
    private static boolean hasFcntl(int sdk) {
        return (sdk < Build.VERSION_CODES.P || sdk >= Build.VERSION_CODES.R);
    }

    // Whether the fake can take the number of the file opened with its flags: always with fcntl; without it only when the
    // hooked open had no O_CLOEXEC in its flags (the argument before the mode, which libcore and java.nio pass to the
    // kernel as they are), since a descriptor just opened is close-on-exec only by that flag, and dup2 clears it
    static boolean movable(int sdk, Object[] args, int cloexec) {
        if (hasFcntl(sdk))
            return true;
        Object flags = (args.length < 2 ? null : args[args.length - 2]);
        return (flags instanceof Integer && ((Integer) flags & cloexec) == 0);
    }

    // The hidden FileDescriptor.getInt$, found by listing the methods, which Android does not report as a use of a hidden
    // API (see storage_times); null without it, like in the tests
    private static volatile Method descriptorNumber;
    private static volatile boolean descriptorNumberKnown = false;

    private static Method descriptorNumber() {
        if (!descriptorNumberKnown) {
            for (Method method : FileDescriptor.class.getDeclaredMethods())
                if (method.getName().equals("getInt$") && method.getParameterTypes().length == 0)
                    descriptorNumber = method;
            descriptorNumberKnown = true;
        }
        return descriptorNumber;
    }

    // The fake with the text: a file in memory, before Android 11 a removed file of the app or a pipe (see oldFile)
    private FileDescriptor newFile(Object opened, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            return oldFile(this.context.getCacheDir(), bytes, sameSize(opened, bytes.length));
        FileDescriptor fake = Os.memfd_create("file", OsConstants.MFD_CLOEXEC);
        try {
            for (int at = 0; at < bytes.length; )
                at += Os.write(fake, bytes, at, bytes.length - at);
            Os.lseek(fake, 0, OsConstants.SEEK_SET);
        } catch (Throwable ex) {
            Os.close(fake);
            throw ex;
        }
        return fake;
    }

    // Whether the hooked method opened a regular file as long as the text, like a feature file of the system padded to
    // its length, unlike the files of /proc, which have no length
    private static boolean sameSize(Object opened, int length) throws Exception {
        if (length == 0)
            return false;
        if (opened instanceof Integer)
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromFd((Integer) opened)) {
                return sameSize(pfd.getFileDescriptor(), length);
            }
        StructStat stat = Os.fstat((FileDescriptor) opened);
        return OsConstants.S_ISREG(stat.st_mode) && stat.st_size == length;
    }

    // The files setFileBytes made, by the device, inode and change time of each, also of their copies (dup), with what
    // the app would learn of the file each replaced, see unfakeFile; the change time keeps a file which gets the inode of
    // a removed fake apart. Not the pipes oldFile makes before Android 11 when it can make no file: FileUtils.copy copies
    // a file with sendfile and a pipe with splice, so a pipe with the stat of a file would fail; its removed files are
    // files like the ones they replace. shortcut: a fake still open after FAKED_MAX later ones answers like a fake again,
    // upgrade when apps keep faked files open that long.
    static final int FAKED_MAX = 256;
    static final Map<String, Faked> fakedFiles = Collections.synchronizedMap(
            new LinkedHashMap<String, Faked>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Faked> eldest) {
                    return (size() > FAKED_MAX);
                }
            });

    static final class Faked {
        final StructStat stat;
        final long size;
        final String path;
        final String link;

        // The stat of the file replaced with the size it tells, the link of its descriptor in /proc/self/fd
        // and the link of the fake there
        Faked(StructStat stat, long size, String path, String link) {
            this.stat = stat;
            this.size = size;
            this.path = path;
            this.link = link;
        }

        // A new stat for each call, like fstat makes
        StructStat newStat() {
            StructStat s = this.stat;
            return new StructStat(s.st_dev, s.st_ino, s.st_mode, s.st_nlink, s.st_uid, s.st_gid, s.st_rdev, this.size,
                    newTime(s.st_atim), newTime(s.st_mtim), newTime(s.st_ctim), s.st_blksize, s.st_blocks);
        }

        private static StructTimespec newTime(StructTimespec time) {
            return new StructTimespec(time.tv_sec, time.tv_nsec);
        }
    }

    static String fakedKey(long device, long inode, long changed, long changedNanos) {
        return device + ":" + inode + ":" + changed + "." + changedNanos;
    }

    private static String fakedKey(StructStat stat) {
        return fakedKey(stat.st_dev, stat.st_ino, stat.st_ctim.tv_sec, stat.st_ctim.tv_nsec);
    }

    // The size a file tells: one of /proc 0 and of /sys a page, whatever their text; other files their length,
    // which the fake has then
    static long sizeOf(long size, long read, long length) {
        return (size == read ? length : size);
    }

    // Like FileInputStream.available of Android for a file: FIONREAD, the size the file tells less the position,
    // which the kernel gives as an int, and none past the end
    static int available(long size, long at) {
        return Math.max(0, (int) (size - at));
    }

    // The file the app opened, the descriptor or its number, before it is closed, and the fake replacing it, but a pipe
    private static Void addFaked(Object opened, FileDescriptor fake, long read, long length) throws Exception {
        StructStat faked = Os.fstat(fake);
        if (!OsConstants.S_ISREG(faked.st_mode))
            return null;
        String link;
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(fake)) {
            link = Os.readlink("/proc/self/fd/" + pfd.getFd());
        }
        try (ParcelFileDescriptor pfd = (opened instanceof Integer ? ParcelFileDescriptor.fromFd((Integer) opened) :
                ParcelFileDescriptor.dup((FileDescriptor) opened))) {
            StructStat stat = Os.fstat(pfd.getFileDescriptor());
            String path = Os.readlink("/proc/self/fd/" + pfd.getFd());
            addFaked(fakedKey(stat), new Faked(stat, stat.st_size, path, null), fakedKey(faked), link, read, length);
        }
        return null;
    }

    // A fake by its key and link, with what the app would learn of the file opened, by the key of that file; a fake of an
    // earlier hook of the same open, like of another property of the same property area, stands for the file it replaced
    static void addFaked(String openedKey, Faked opened, String fakeKey, String link, long read, long length) {
        Faked was = fakedFiles.remove(openedKey);
        if (was == null)
            was = opened;
        fakedFiles.put(fakeKey, new Faked(was.stat, sizeOf(was.size, read, length), was.path, link));
    }

    // The answers about a descriptor of setFileBytes like about the file it replaced: its stat (Os.fstat), the bytes left
    // to read (FileInputStream.available), its length (RandomAccessFile.length), the size of a FileChannel
    // (FileDispatcherImpl.size) and its link in /proc/self/fd, also by /dev/fd and java.nio (readlink), and the stat,
    // length and modification time by that link (BlockGuardOs.stat, UnixFileSystem.getLength and getLastModifiedTime);
    // whether the hooked method answered about one
    @SuppressWarnings("unused")
    public boolean unfakeFile() throws Throwable {
        Object result = this.param.getResult();
        if (result == null || fakedFiles.isEmpty())
            return false;
        Object answer;
        if (result instanceof StructStat) {
            // The stat tells itself which file it is of
            Faked faked = fakedFiles.get(fakedKey((StructStat) result));
            answer = (faked == null ? null : faked.newStat());
        } else
            answer = quietly(() -> unfaked(this.param, result, XParam::keyOf));
        if (answer == null)
            return false;
        setResult(answer);
        return true;
    }

    // The key of a descriptor or of a path by its stat, see fakedKey; the tests have their own, Android stubs have no stat
    interface Keys {
        String of(Object file) throws ErrnoException;
    }

    private static String keyOf(Object file) throws ErrnoException {
        return fakedKey(file instanceof String ? Os.stat((String) file) : Os.fstat((FileDescriptor) file));
    }

    // The answer of the hooked method about the file a fake replaced, null when it answered about no fake
    static Object unfaked(XCall call, Object result, Keys keys) throws Exception {
        String name = call.method.getName();
        Object file;
        switch (name) {
            case "available":
                file = ((FileInputStream) call.thisObject).getFD();
                break;
            case "length":
                file = ((RandomAccessFile) call.thisObject).getFD();
                break;
            case "size":
                file = call.args[0];
                break;
            case "getLength":
            case "getLastModifiedTime":
                file = ((File) call.args[0]).getPath();
                break;
            case "readlink":
                file = call.args[0].toString();
                break;
            default:
                return null;
        }
        Faked faked;
        try {
            faked = fakedFiles.get(keys.of(file));
        } catch (ErrnoException ignored) {
            // A path which leads to no file, like a link of the app to a file which does not exist
            return null;
        }
        if (faked == null)
            return null;
        if (name.equals("available"))
            return available(faked.size, Os.lseek((FileDescriptor) file, 0, OsConstants.SEEK_CUR));
        if (name.equals("readlink")) {
            String link = (result instanceof byte[] ? new String((byte[]) result, StandardCharsets.UTF_8) : (String) result);
            // Not a link of the app to the link of the descriptor, which leads to the fake too
            return (faked.link.equals(link) ? faked.path : null);
        }
        if (name.equals("getLastModifiedTime"))
            return lastModified(faked.stat, (Long) result);
        return faked.size;
    }

    // Like File.lastModified of the file replaced: its modification time in whole seconds, like libcore up to Android 13,
    // or to the millisecond, like libcore of Android 14, which updates of the ART module may bring to Android 12 and 13,
    // as the hooked call about the fake tells (AOSP UnixFileSystem_md.c, getLastModifiedTime0). shortcut: on Android 12
    // and 13 with the newer libcore a fake changed in the first millisecond of a second gives whole seconds, one in a
    // thousand, upgrade when that matters.
    static long lastModified(StructStat stat, long result) {
        long millis = stat.st_mtime * 1000;
        boolean exact = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE || result % 1000 != 0);
        return (exact ? millis + stat.st_mtim.tv_nsec / 1000000 : millis);
    }

    // Os calls of Doppel itself about a file of the app or making a fake: without the hooks, which would take them for
    // calls of the app, and without reports of disk reads and writes to StrictMode (allowThreadDiskWrites allows both),
    // which the hooked call of the app does not make
    private static <T> T quietly(Callable<T> call) throws Exception {
        StrictMode.ThreadPolicy policy = StrictMode.allowThreadDiskWrites();
        XCore.enterService();
        try {
            return call.call();
        } finally {
            XCore.leaveService();
            StrictMode.setThreadPolicy(policy);
        }
    }

    // Before Android 11, without files in memory: a file of the app which is removed right away and opened read-only
    // like the file it replaces. It is a regular file like that one and like the files in memory of Android 11, so seek
    // works and faked_files answers its stat, size and link like those of the file replaced. A pipe, which holds at least
    // a page without a reader, shows itself by the FIFO of fstat, by available (FIONREAD, AOSP libcore
    // FileInputStream.c), by its link pipe:[n] and by ESPIPE of RandomAccessFile.seek; it is used only when no file can
    // be made, like in a cache folder which is missing, since getCacheDir gives the folder also when it cannot make it
    // (AOSP ContextImpl), and only for a text it holds which is not the text of a regular file as long as the file (see
    // sameSize), of which fstat would give a FIFO of no length. shortcut: the removed file shows in the cache folder
    // while it is written, to a listing or a FileObserver of the app, and a pipe answers like a pipe, upgrade when apps
    // watch their cache folder or read faked files where they cannot make one.
    static FileDescriptor oldFile(File folder, byte[] bytes, boolean sameSize) throws Exception {
        try {
            if (folder == null)
                throw new IllegalArgumentException("Text of " + bytes.length + " bytes without a cache folder");
            File file = File.createTempFile("file", null, folder);
            try {
                FileDescriptor write = Os.open(file.getPath(), OsConstants.O_WRONLY | OsConstants.O_CLOEXEC, 0);
                try {
                    for (int at = 0; at < bytes.length; )
                        at += Os.write(write, bytes, at, bytes.length - at);
                } finally {
                    Os.close(write);
                }
                return Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Exception ex) {
            if (bytes.length > 4096 || sameSize)
                throw ex;
        }
        FileDescriptor[] pipe = Os.pipe();
        try {
            for (int at = 0; at < bytes.length; )
                at += Os.write(pipe[1], bytes, at, bytes.length - at);
        } catch (Throwable ex) {
            Os.close(pipe[0]);
            throw ex;
        } finally {
            Os.close(pipe[1]);
        }
        return pipe[0];
    }

    // A property area the hooked method opened, a file of /dev/__properties__ which bionic maps in each process to read
    // the system properties and which apps may read as a file too, with the values of properties replaced, read and
    // written once; the old value of each, null when the method failed, the file is no property area or does not have
    // the property, its value is empty or long, or nothing changed. shortcut: native code reads the areas bionic
    // mapped, which keep the real values, upgrade when native code is hooked.
    @SuppressWarnings("unused")
    public String[] setPropertyValues(String[] names, String[] values) throws Throwable {
        String[] olds = new String[names.length];
        if (this.param.getThrowable() != null || this.param.getResult() == null)
            return olds;
        byte[] area = getFileBytes();
        boolean backup = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        boolean changed = false;
        for (int i = 0; i < names.length; i++)
            if ((olds[i] = replaceProperty(area, names[i], values[i], backup)) != null)
                changed = true;
        if (changed)
            setFileBytes(area);
        return olds;
    }

    // The names and values of the properties of the property area the hooked method opened, each name followed by its
    // value, so a script can fake properties it knows by a pattern of their names with setPropertyValues; without the long
    // values, which only ro. properties have, and none when the method failed or the file is no property area
    @SuppressWarnings("unused")
    public String[] getProperties() throws Throwable {
        if (this.param.getThrowable() != null || this.param.getResult() == null)
            return new String[0];
        return listProperties(getFileBytes());
    }

    // The properties of the bytes of a property area, by the nodes of the trie (see replaceProperty): a node which ends a
    // property has its offset, and a property has its whole name after its value; at most as many nodes as fit
    static String[] listProperties(byte[] area) {
        ByteBuffer buffer = ByteBuffer.wrap(area).order(ByteOrder.nativeOrder());
        List<String> properties = new ArrayList<>();
        try {
            if (buffer.getInt(8) != AREA_MAGIC || buffer.getInt(12) != AREA_VERSION)
                return new String[0];
            Deque<Integer> nodes = new ArrayDeque<>();
            nodes.push(0);
            for (int steps = 0; !nodes.isEmpty() && steps <= area.length / NODE_PART; steps++) {
                int at = AREA_DATA + nodes.pop();
                int offset = buffer.getInt(at + 4);
                int serial = (offset == 0 ? LONG_VALUE : buffer.getInt(AREA_DATA + offset));
                if ((serial & LONG_VALUE) == 0) {
                    int value = AREA_DATA + offset + 4;
                    int name = value + VALUE_MAX;
                    int end = name;
                    while (area[end] != 0)
                        end++;
                    properties.add(new String(area, name, end - name, StandardCharsets.UTF_8));
                    properties.add(new String(area, value, Math.min(serial >>> 24, VALUE_MAX - 1), StandardCharsets.UTF_8));
                }
                // The siblings before and after the node and its children
                for (int link = 8; link <= 16; link += 4)
                    if (buffer.getInt(at + link) != 0)
                        nodes.push(buffer.getInt(at + link));
            }
        } catch (IndexOutOfBoundsException ignored) {
            return new String[0];
        }
        return properties.toArray(new String[0]);
    }

    // The layout of a property area (bionic system_properties/prop_area.h and prop_info.h, the same on Android 8.1 to 17):
    // a header of 128 bytes with the magic and the version, then the data, to which all offsets point. The data starts
    // with the root of a trie of the parts of the names between the dots: each node has the length of its part, the
    // offset of the property it ends, of its siblings before and after it in a binary tree, and of the tree of its
    // children, then the part. A property has a serial, with the length of its value in the top byte, then the value.
    private static final int AREA_MAGIC = 0x504f5250;
    private static final int AREA_VERSION = 0xfc6ed0ab;
    private static final int AREA_DATA = 128;
    private static final int NODE_PART = 20;
    private static final int VALUE_MAX = 92;
    private static final int LONG_VALUE = 1 << 16;

    // Replaces the value of a property in the bytes of a property area like Android sets it, also its length in the
    // serial, without the bytes of an older, longer value after it. From Android 11 a copy of the value before the
    // last change of a property of the area follows the root (the dirty backup area), which is the fake value too
    // when there is one, so no older value is left. The old value, null as for setPropertyValues.
    static String replaceProperty(byte[] area, String name, String value, boolean backup) {
        ByteBuffer buffer = ByteBuffer.wrap(area).order(ByteOrder.nativeOrder());
        byte[] fake = value.getBytes(StandardCharsets.UTF_8);
        try {
            if (fake.length >= VALUE_MAX || buffer.getInt(8) != AREA_MAGIC || buffer.getInt(12) != AREA_VERSION)
                return null;
            int node = 0;
            for (String part : name.split("\\.", -1)) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                node = buffer.getInt(AREA_DATA + node + 16);
                // Like bionic compares parts: shorter first, else by their bytes; at most as many steps as nodes fit
                for (int steps = 0; node != 0; steps++) {
                    int at = AREA_DATA + node;
                    int length = buffer.getInt(at);
                    int order = (bytes.length != length ? Integer.compare(bytes.length, length) :
                            compareBytes(bytes, area, at + NODE_PART));
                    if (order == 0)
                        break;
                    if (steps > area.length / NODE_PART)
                        return null;
                    node = buffer.getInt(at + (order < 0 ? 8 : 12));
                }
                if (node == 0)
                    return null;
            }
            int offset = buffer.getInt(AREA_DATA + node + 4);
            if (offset == 0)
                return null;
            int info = AREA_DATA + offset;
            int serial = buffer.getInt(info);
            int length = serial >>> 24;
            if ((serial & LONG_VALUE) != 0 || length == 0 || length >= VALUE_MAX)
                return null;
            String old = new String(area, info + 4, length, StandardCharsets.UTF_8);
            byte[] before = area.clone();
            Arrays.fill(area, info + 4, info + 4 + VALUE_MAX, (byte) 0);
            System.arraycopy(fake, 0, area, info + 4, fake.length);
            buffer.putInt(info, (fake.length << 24) | (serial & 0xffffff));
            int copy = AREA_DATA + NODE_PART;
            if (backup && area[copy] != 0)
                System.arraycopy(area, info + 4, area, copy, VALUE_MAX);
            return (Arrays.equals(before, area) ? null : old);
        } catch (IndexOutOfBoundsException ignored) {
            return null;
        }
    }

    // Like strncmp, which compares bytes without a sign
    private static int compareBytes(byte[] part, byte[] area, int at) {
        for (int i = 0; i < part.length; i++)
            if (part[i] != area[at + i])
                return Integer.compare(part[i] & 0xff, area[at + i] & 0xff);
        return 0;
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
            Log.i(TAG, "Put value " + this.getPackageName() + ":" + this.getUid() + " " + name + "=" + text(value) + " @" + text(scope));
        Object key = (scope == null ? NO_SCOPE : scope);
        synchronized (scopedValues) {
            for (Reference<?> gone; (gone = scopesGone.poll()) != null; )
                scopedValues.remove(gone);
            Map<String, Object> values = scopedValues.get(new Scope(key, null));
            if (values == null)
                scopedValues.put(new Scope(key, scopesGone), values = new HashMap<>());
            values.put(name, value);
        }
    }

    @SuppressWarnings("unused")
    public Object getValue(String name, Object scope) {
        Object value;
        synchronized (scopedValues) {
            Map<String, Object> values = scopedValues.get(new Scope(scope == null ? NO_SCOPE : scope, null));
            value = (values == null ? null : values.get(name));
        }
        if (BuildConfig.DEBUG)
            Log.i(TAG, "Get value " + this.getPackageName() + ":" + this.getUid() + " " + name + "=" + text(value) + " @" + text(scope));
        return value;
    }

    // An object as a scope of values: the same object, by identity, while it is there; a scope whose object is gone is
    // only itself, so it can be removed
    private static final class Scope extends WeakReference<Object> {
        private final int hash;

        Scope(Object scope, ReferenceQueue<Object> queue) {
            super(scope, queue);
            this.hash = System.identityHashCode(scope);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object other) {
            Object scope;
            return (this == other ||
                    (other instanceof Scope && (scope = get()) != null && scope == ((Scope) other).get()));
        }
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
        else if (value instanceof String && byte[].class.equals(type))
            // A path for a method which takes its bytes, like UnixNativeDispatcher.renameat, in the encoding of UnixPath
            return ((String) value).getBytes(StandardCharsets.UTF_8);

        return value;
    }
}
