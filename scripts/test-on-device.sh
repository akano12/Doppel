#!/bin/sh
# Runs unit tests on a device or emulator, against the shell and the tools of Android (mksh, toybox): the tests of the
# commands apps run need sh and its tools, which Windows has not, so the Gradle tests skip them there.
# Usage: ./gradlew testDebugUnitTest first (it compiles the tests), then scripts/test-on-device.sh [test classes],
# by default eu.faircode.xlua.ShellCommandsScriptTest; with adb on the path or ADB set, ANDROID_SERIAL for one of
# several devices, and ANDROID_HOME and JAVA_HOME set like for Gradle. Tests which need AndroidX or the Xposed API
# (AppSectionsTest, ModernBackendTest) do not run here. Exits 1 when a test fails.

set -e
ADB=${ADB:-adb}
# Git Bash on Windows would turn the paths of the device into Windows paths
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
CLASSES=${*:-eu.faircode.xlua.ShellCommandsScriptTest}
DEVICE=/data/local/tmp/doppel-test
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"; "$ADB" shell rm -rf $DEVICE >/dev/null 2>&1 || true' EXIT

# The paths of files for the Java tools and adb, which on Windows take the paths of Windows
win() {
    if command -v cygpath >/dev/null; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

# The compiled app and tests with JUnit, as one dex file
OUT=app/build/intermediates/javac
"$JAVA_HOME/bin/jar" cf "$(win "$WORK/main.jar")" -C $OUT/debug/compileDebugJavaWithJavac/classes .
"$JAVA_HOME/bin/jar" cf "$(win "$WORK/test.jar")" -C $OUT/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes .
CACHE=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1
JUNIT=$(find "$CACHE/junit/junit" -name 'junit-4*.jar' ! -name '*sources*' | sort | tail -1)
HAMCREST=$(find "$CACHE/org.hamcrest/hamcrest-core" -name 'hamcrest-core-*.jar' ! -name '*sources*' | sort | tail -1)
TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
D8=$TOOLS/d8
[ -f "$D8" ] || D8=$TOOLS/d8.bat
ANDROID_JAR=$(ls -d "$ANDROID_HOME"/platforms/android-* | sort -V | tail -1)/android.jar
"$D8" --min-api 27 --lib "$(win "$ANDROID_JAR")" --output "$(win "$WORK")" "$(win "$WORK/main.jar")" \
    "$(win "$WORK/test.jar")" "$(win "$JUNIT")" "$(win "$HAMCREST")" 2>&1 | grep -v -i -e '^warning' -e 'missing class' -e '^ ' || true

# The tests read the scripts and Downloads from the source folders, relative to the folder of the app;
# folders are pushed to targets which do not exist yet, which old and new versions of adb fill alike
"$ADB" shell "rm -rf $DEVICE; mkdir -p $DEVICE/app/src/main/res/values $DEVICE/app/tmp"
"$ADB" push "$(win "$WORK/classes.dex")" $DEVICE/ >/dev/null
"$ADB" push app/src/main/assets $DEVICE/app/src/main/assets >/dev/null
"$ADB" push app/src/main/res/values/strings.xml $DEVICE/app/src/main/res/values/ >/dev/null
"$ADB" push repo $DEVICE/repo >/dev/null

# A script on the device, since adb limits the length of a command
printf 'cd %s/app && CLASSPATH=%s/classes.dex XLUA_TEST_BIN=/system/bin app_process -Djava.io.tmpdir=%s/app/tmp /system/bin org.junit.runner.JUnitCore %s\n' \
    $DEVICE $DEVICE $DEVICE "$CLASSES" > "$WORK/run.sh"
"$ADB" push "$(win "$WORK/run.sh")" $DEVICE/ >/dev/null
"$ADB" shell sh $DEVICE/run.sh 2>&1 | tr -d '\r' | tee "$WORK/result.txt"
grep -q '^OK (' "$WORK/result.txt"
