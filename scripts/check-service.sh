#!/bin/sh
# Checks on a rooted device or emulator with Doppel active that the service answers only whom it should
# (see XProvider.answers): system server gets answers, an app without restrictions, like the shell,
# gets the answers of the settings provider, as on a device without Doppel, and cannot change settings.
# Usage: scripts/check-service.sh, with adb on the path or ADB set; exits 1 on a wrong answer.

ADB=${ADB:-adb}
# Git Bash on Windows would turn the content:// paths into Windows paths
export MSYS_NO_PATHCONV=1
URI=content://settings/system
CATEGORY=doppel-check
failed=0

shell() {
    "$ADB" shell "$@" 2>&1 | tr -d '\r'
}

check() {
    if printf '%s' "$2" | grep -q -- "$3"; then
        echo "ok   $1"
    else
        echo "FAIL $1: expected $3, got: $(printf '%s' "$2" | head -2)"
        failed=1
    fi
}

# System server (uid 1000, by root): Doppel is running, else the other checks would pass without it
check "system gets the version" "$(shell "su 1000 -c 'content call --uri $URI --method xlua --arg getVersion'")" \
    "version="

# The shell (uid 2000) has no restrictions
check "shell gets no version" "$(shell content call --uri $URI --method xlua --arg getVersion)" "Result: null"
check "shell cannot read all settings" \
    "$(shell content query --uri $URI --projection xlua.getAllSettings)" "Invalid column"
check "shell cannot read the settings of apps" \
    "$(shell content query --uri $URI --projection xlua.getSettings --where x)" "IllegalArgumentException"
check "shell cannot put a setting" "$(shell content call --uri $URI --method xlua --arg putSetting \
    --extra user:i:0 --extra category:s:$CATEGORY --extra name:s:check --extra value:s:written)" "Result: null"
settings=$(shell "su 1000 -c 'content query --uri $URI --projection xlua.getAllSettings'")
if printf '%s' "$settings" | grep -q "category=$CATEGORY"; then
    echo "FAIL the setting of the shell was written"
    failed=1
else
    echo "ok   the setting of the shell was not written"
fi

exit $failed
