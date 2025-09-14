# Even G1 Text App

Android app to scan, connect over BLE to Even G1 glasses, and send text to the display.

## Build locally
Open in Android Studio (Hedgehog+), or:
./gradlew assembleDebug

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Usage
1. Grant Bluetooth permissions on first run.
2. Optionally enter Even G1 Service UUID and TX Characteristic UUID from `https://github.com/even-realities/EvenDemoApp`.
3. Scan, select your glasses, Connect, type, Send.
4. If the device uses Nordic UART, keep protocol set to NUS.

## CI
On every push, GitHub Actions builds a Debug APK and uploads it as a downloadable artifact.
# even-g1
