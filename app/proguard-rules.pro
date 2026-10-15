# https://developer.android.com/build/shrink-code

# Preserve the line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# XPrivacyLua
# XLua is the legacy Xposed entry point (assets/xposed_init),
# XLuaModule the modern entry point (META-INF/xposed/java_init.list),
# XHook and XParam are accessed from Lua scripts by reflection
-keep class eu.faircode.xlua.XLua { *; }
-keep class eu.faircode.xlua.XLuaModule { *; }
-keep class eu.faircode.xlua.XHook { *; }
-keep class eu.faircode.xlua.XParam { *; }
-keepnames class eu.faircode.xlua.** { *; }

# LuaJ binds library functions and Java objects by reflection
-dontwarn org.luaj.vm2.**
-keep class org.luaj.vm2.** { *; }

# Xposed APIs are provided at runtime
-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.**
