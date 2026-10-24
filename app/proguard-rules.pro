# https://developer.android.com/build/shrink-code

# Preserve the line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Doppel
# XLua is the legacy Xposed entry point (assets/xposed_init),
# XLuaModule the modern entry point (META-INF/xposed/java_init.list),
# XHook and XParam are accessed from Lua scripts by reflection
-keep class eu.faircode.xlua.XLua { *; }
-keep class eu.faircode.xlua.XLuaModule { *; }
-keep class eu.faircode.xlua.XHook { *; }
-keep class eu.faircode.xlua.XParam { *; }
# Readable stack traces in the errors shown by the app, the code is small
-keepnames class eu.faircode.xlua.** { *; }

# LuaJ creates library functions by reflection (LibFunction.bind), the rest can be shrunk
-dontwarn org.luaj.vm2.**
-keep class * extends org.luaj.vm2.lib.LibFunction { <init>(); }
-keepnames class org.luaj.vm2.** { *; }

# Xposed APIs are provided at runtime
-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.**
