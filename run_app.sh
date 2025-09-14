#!/usr/bin/env bash
set -euo pipefail

APP_ID=com.example.eveng1text
APK=app/build/outputs/apk/debug/app-debug.apk

# Build
./gradlew assembleDebug

# Ensure a device is connected
adb wait-for-device

# Fresh install
adb uninstall "$APP_ID" >/dev/null 2>&1 || true
adb install -r "$APK"

# Grant runtime permissions (ignore errors if already granted/not needed)
adb shell pm grant "$APP_ID" android.permission.BLUETOOTH_SCAN || true
adb shell pm grant "$APP_ID" android.permission.BLUETOOTH_CONNECT || true
adb shell pm grant "$APP_ID" android.permission.ACCESS_FINE_LOCATION || true

# Launch app
adb shell am start -n "$APP_ID"/.MainActivity

# Tail logs (Ctrl+C to stop)
echo "---- Logcat (filter) ----"
adb logcat | egrep -i 'Even|Bluetooth|BtGatt|Gatt|AndroidRuntime|eveng1text'
