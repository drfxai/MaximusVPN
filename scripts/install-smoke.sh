#!/usr/bin/env bash
set -euo pipefail
apk=${1:?APK path required}
package=com.aistudio.raytunnel.vpnrx
adb install -r "$apk"
adb logcat -c
adb shell am force-stop "$package"
adb shell am start -W -n "$package/com.example.MainActivity" | tee /tmp/maximus-launch.txt
if grep -Eq 'Error:|Exception|Status: timeout' /tmp/maximus-launch.txt; then exit 1; fi
sleep 8
adb shell pidof "$package"
adb logcat -d -b crash > /tmp/maximus-crash.txt
if grep -Fq "$package" /tmp/maximus-crash.txt; then cat /tmp/maximus-crash.txt; exit 1; fi
