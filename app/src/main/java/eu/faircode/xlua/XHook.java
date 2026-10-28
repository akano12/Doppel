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
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Arrays;

public class XHook implements Parcelable {
    private static final String TAG = "XLua.XHook";

    private boolean builtin = false;
    private String collection;
    private String group;
    private String name;
    private String author;
    private int version = 0;
    private String description;

    private String className;
    private String resolvedClassName = null;
    private String methodName;
    private String[] parameterTypes;
    private String returnType;

    private int minSdk;
    private int maxSdk;
    private int minApk;
    private int maxApk;
    private String[] excludePackages;
    private volatile Pattern[] excludePatterns; // Compiled once, see isAvailable, also by threads at once
    private boolean enabled;
    private boolean optional;
    private boolean usage;
    private boolean notify;

    private String luaScript;

    private String[] settings;

    private XHook() {
    }

    public String getId() {
        return id(this.collection, this.name);
    }

    // The id of a definition: its collection and its name
    static String id(String collection, String name) {
        return collection + "." + name;
    }

    // The id of a built-in definition
    static String builtinId(String name) {
        return id(Util.COLLECTION_DEFAULT, name);
    }

    public boolean isBuiltin() {
        return this.builtin;
    }

    public String getCollection() {
        return this.collection;
    }

    public String getGroup() {
        return this.group;
    }

    public String getName() {
        return this.name;
    }

    public int getVersion() {
        return this.version;
    }

    public String getClassName() {
        return this.className;
    }

    public String getResolvedClassName() {
        return (this.resolvedClassName == null ? this.className : this.resolvedClassName);
    }

    public String getMethodName() {
        return this.methodName;
    }

    public String[] getParameterTypes() {
        return this.parameterTypes;
    }

    public String getReturnType() {
        return this.returnType;
    }

    public boolean isAvailable(String packageName, List<String> collection) {
        if (!collection.contains(this.collection))
            return false;

        if (!this.enabled)
            return false;

        if (!isForThisSdk())
            return false;

        if (packageName == null)
            return true;

        if (this.excludePackages == null)
            return true;

        Pattern[] patterns = this.excludePatterns;
        if (patterns == null) {
            patterns = new Pattern[this.excludePackages.length];
            for (int i = 0; i < patterns.length; i++)
                patterns[i] = Pattern.compile(this.excludePackages[i]);
            this.excludePatterns = patterns;
        }
        for (Pattern pattern : patterns)
            if (pattern.matcher(packageName).matches())
                return false;
        return true;
    }

    // The version code of an app is a long, which versionCodeMajor takes beyond the int of maxApk;
    // without a maxApk a definition is for all versions
    public boolean isAvailable(long versionCode) {
        return (versionCode >= this.minApk && (this.maxApk == Integer.MAX_VALUE || versionCode <= this.maxApk));
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    // Whether the hook applies to the Android version of the device
    public boolean isForThisSdk() {
        return (Build.VERSION.SDK_INT >= this.minSdk && Build.VERSION.SDK_INT <= this.maxSdk);
    }

    public boolean isOptional() {
        return this.optional;
    }

    public boolean doUsage() {
        return this.usage;
    }

    public boolean doNotify() {
        return this.notify;
    }

    public String getLuaScript() {
        return this.luaScript;
    }

    // A script sent once for the hooks which share it, see Data.getAssignedHooks
    void setLuaScript(String script) {
        this.luaScript = script;
    }

    // Names of the settings (fake values) the script reads, empty when none
    public String[] getSettings() {
        return (this.settings == null ? new String[0] : this.settings);
    }

    // Hooks of system services hook the implementation, which can be a subclass
    public void resolveClassName(Context context) {
        try {
            Object service;
            if ("android.content.ContentResolver".equals(this.className))
                service = context.getContentResolver();
            else if ("android.content.pm.PackageManager".equals(this.className))
                service = context.getPackageManager();
            else {
                Class<?> cls = Class.forName(this.className, false, XHook.class.getClassLoader());
                String name = context.getSystemServiceName(cls);
                // Some services keep the context, like the Wi-Fi manager
                Context app = context.getApplicationContext();
                service = (name == null ? null : (app == null ? context : app).getSystemService(name));
            }
            if (service != null)
                this.resolvedClassName = service.getClass().getName();
        } catch (ClassNotFoundException ignored) {
            // A class of an app, like Google Play services
        } catch (Throwable ex) {
            Log.w(TAG, "Resolve " + this.className + ": " + ex);
        }
    }

    // Read the built-in hook definitions of the app, with the scripts in separate files
    static ArrayList<XHook> readHooks(String apk) throws IOException, JSONException {
        try (ZipFile zipFile = new ZipFile(apk)) {
            ZipEntry zipEntry = zipFile.getEntry("assets/hooks.json");
            if (zipEntry == null)
                throw new IllegalArgumentException("assets/hooks.json not found in " + apk);

            String json;
            try (InputStream is = zipFile.getInputStream(zipEntry)) {
                json = Util.readText(is);
            }

            ArrayList<XHook> hooks = new ArrayList<>();
            // Scripts in separate files, read once for the hooks which share them, like the dozens of hooks of settings,
            // since system server keeps them as long as it runs
            Map<String, String> scripts = new HashMap<>();
            JSONArray jarray = new JSONArray(json);
            for (int i = 0; i < jarray.length(); i++) {
                XHook hook = XHook.fromJSONObject(jarray.getJSONObject(i));
                hook.builtin = true;

                if (hook.luaScript.startsWith("@")) {
                    String script = scripts.get(hook.luaScript);
                    if (script == null) {
                        ZipEntry luaEntry = zipFile.getEntry("assets/" + hook.luaScript.substring(1) + ".lua");
                        if (luaEntry == null)
                            throw new IllegalArgumentException(hook.luaScript + " not found for " + hook.getId());
                        try (InputStream lis = zipFile.getInputStream(luaEntry)) {
                            script = Util.readText(lis);
                        }
                        scripts.put(hook.luaScript, script);
                    }
                    hook.luaScript = script;
                }

                hooks.add(hook);
            }
            return hooks;
        }
    }

    String toJSON() throws JSONException {
        return toJSONObject().toString(2);
    }

    JSONObject toJSONObject() throws JSONException {
        JSONObject jroot = new JSONObject();

        jroot.put("builtin", this.builtin);
        jroot.put("collection", this.collection);
        jroot.put("group", this.group);
        jroot.put("name", this.name);
        jroot.put("author", this.author);
        jroot.put("version", this.version);
        if (this.description != null)
            jroot.put("description", this.description);

        jroot.put("className", this.className);
        if (this.resolvedClassName != null)
            jroot.put("resolvedClassName", this.resolvedClassName);
        if (this.methodName != null)
            jroot.put("methodName", this.methodName);

        jroot.put("parameterTypes", new JSONArray(Arrays.asList(this.parameterTypes)));

        if (this.returnType != null)
            jroot.put("returnType", this.returnType);

        jroot.put("minSdk", this.minSdk);
        jroot.put("maxSdk", this.maxSdk);

        jroot.put("minApk", this.minApk);
        jroot.put("maxApk", this.maxApk);

        if (this.excludePackages != null)
            jroot.put("excludePackages", TextUtils.join(",", this.excludePackages));

        jroot.put("enabled", this.enabled);
        jroot.put("optional", this.optional);
        jroot.put("usage", this.usage);
        jroot.put("notify", this.notify);

        jroot.put("luaScript", this.luaScript);

        if (this.settings != null)
            jroot.put("settings", new JSONArray(Arrays.asList(this.settings)));

        return jroot;
    }

    static XHook fromJSON(String json) throws JSONException {
        return fromJSONObject(new JSONObject(json));
    }

    static XHook fromJSONObject(JSONObject jroot) throws JSONException {
        XHook hook = new XHook();

        hook.builtin = jroot.optBoolean("builtin", false);
        hook.collection = jroot.getString("collection");
        hook.group = jroot.getString("group");
        hook.name = jroot.getString("name");
        hook.author = jroot.getString("author");
        hook.version = jroot.optInt("version", 0);
        hook.description = jroot.optString("description", null);

        hook.className = jroot.getString("className");
        hook.resolvedClassName = jroot.optString("resolvedClassName", null);
        hook.methodName = jroot.optString("methodName", null);

        JSONArray jparam = jroot.getJSONArray("parameterTypes");
        hook.parameterTypes = new String[jparam.length()];
        for (int i = 0; i < jparam.length(); i++)
            hook.parameterTypes[i] = jparam.getString(i);

        hook.returnType = jroot.optString("returnType", null);

        hook.minSdk = jroot.getInt("minSdk");
        hook.maxSdk = jroot.optInt("maxSdk", 999);

        hook.minApk = jroot.optInt("minApk", 0);
        hook.maxApk = jroot.optInt("maxApk", Integer.MAX_VALUE);

        // Patterns separated by commas, spaces around them do not count
        if (jroot.has("excludePackages"))
            hook.excludePackages = Util.split(jroot.getString("excludePackages")).toArray(new String[0]);
        else
            hook.excludePackages = null;

        hook.enabled = jroot.optBoolean("enabled", true);
        hook.optional = jroot.optBoolean("optional", false);
        hook.usage = jroot.optBoolean("usage", true);
        hook.notify = jroot.optBoolean("notify", false);

        hook.luaScript = jroot.getString("luaScript");

        if (jroot.has("settings")) {
            JSONArray jsettings = jroot.getJSONArray("settings");
            hook.settings = new String[jsettings.length()];
            for (int i = 0; i < jsettings.length(); i++)
                hook.settings[i] = jsettings.getString(i);
        } else
            hook.settings = null;

        return hook;
    }

    public void validate() {
        if (TextUtils.isEmpty(this.collection))
            throw new IllegalArgumentException("collection missing");
        if (TextUtils.isEmpty(this.group))
            throw new IllegalArgumentException("group missing");
        if (TextUtils.isEmpty(this.name))
            throw new IllegalArgumentException("name missing");
        if (TextUtils.isEmpty(this.author))
            throw new IllegalArgumentException("author missing");
        if (TextUtils.isEmpty(this.className))
            throw new IllegalArgumentException("class name missing");
        if (parameterTypes == null)
            throw new IllegalArgumentException("parameter types missing");
        if (TextUtils.isEmpty(this.luaScript))
            throw new IllegalArgumentException("Lua script missing");
        // An invalid expression would fail each lookup of available hooks, see isAvailable
        if (this.excludePackages != null)
            for (String regex : this.excludePackages)
                Pattern.compile(regex);
    }

    @Override
    public String toString() {
        return this.getId() + "@" + this.className + ":" + this.methodName;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof XHook))
            return false;
        XHook other = (XHook) obj;
        return this.getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return this.getId().hashCode();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    // Parcels of assignments and app lists do not include the script
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        write(dest, false);
    }

    byte[] marshall(boolean withLua) {
        Parcel parcel = Parcel.obtain();
        try {
            write(parcel, withLua);
            return parcel.marshall();
        } finally {
            parcel.recycle();
        }
    }

    private void write(Parcel dest, boolean withLua) {
        dest.writeByte(this.builtin ? (byte) 1 : (byte) 0);
        dest.writeString(this.collection);
        dest.writeString(this.group);
        dest.writeString(this.name);
        dest.writeString(this.author);
        dest.writeInt(this.version);
        dest.writeString(this.description);
        dest.writeString(this.className);
        dest.writeString(this.resolvedClassName);
        dest.writeString(this.methodName);
        dest.writeStringArray(this.parameterTypes);
        dest.writeString(this.returnType);
        dest.writeInt(this.minSdk);
        dest.writeInt(this.maxSdk);
        dest.writeInt(this.minApk);
        dest.writeInt(this.maxApk);
        dest.writeStringArray(this.excludePackages);
        dest.writeByte(this.enabled ? (byte) 1 : (byte) 0);
        dest.writeByte(this.optional ? (byte) 1 : (byte) 0);
        dest.writeByte(this.usage ? (byte) 1 : (byte) 0);
        dest.writeByte(this.notify ? (byte) 1 : (byte) 0);
        dest.writeString(withLua ? this.luaScript : null);
        dest.writeStringArray(this.settings);
    }

    protected XHook(Parcel in) {
        this.builtin = (in.readByte() != 0);
        this.collection = in.readString();
        this.group = in.readString();
        this.name = in.readString();
        this.author = in.readString();
        this.version = in.readInt();
        this.description = in.readString();
        this.className = in.readString();
        this.resolvedClassName = in.readString();
        this.methodName = in.readString();
        this.parameterTypes = in.createStringArray();
        this.returnType = in.readString();
        this.minSdk = in.readInt();
        this.maxSdk = in.readInt();
        this.minApk = in.readInt();
        this.maxApk = in.readInt();
        this.excludePackages = in.createStringArray();
        this.enabled = (in.readByte() != 0);
        this.optional = (in.readByte() != 0);
        this.usage = (in.readByte() != 0);
        this.notify = (in.readByte() != 0);
        this.luaScript = in.readString();
        this.settings = in.createStringArray();
    }

    public static final Parcelable.Creator<XHook> CREATOR = new Parcelable.Creator<XHook>() {
        @Override
        public XHook createFromParcel(Parcel source) {
            return new XHook(source);
        }

        @Override
        public XHook[] newArray(int size) {
            return new XHook[size];
        }
    };
}
