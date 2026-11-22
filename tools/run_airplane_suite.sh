#!/usr/bin/env bash
# Runs the airplane-mode instrumented suite (task 34.2). Airplane mode drops a wireless adb link, so use a USB cable:
#   1. Connect the phone by USB (adb devices shows a serial, not an IP/tls entry).
#   2. Run this script; it turns airplane mode on, runs the suite, and turns it off again.
set -euo pipefail
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
cd "$(dirname "$0")/.."
"$ADB" devices | grep -q "tls-connect" && { echo "Wireless adb detected: airplane mode would drop it. Use USB."; exit 1; }
./gradlew :app:installDebug :app:installDebugAndroidTest
trap '"$ADB" shell cmd connectivity airplane-mode disable' EXIT
"$ADB" shell cmd connectivity airplane-mode enable
sleep 5
"$ADB" shell am instrument -w -e class com.callanswer.app.AirplaneModeSuiteTest com.callanswer.app.test/androidx.test.runner.AndroidJUnitRunner
