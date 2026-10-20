#!/usr/bin/env bash
# Runs the instrumented test suite on the CI emulator and collects reports and screenshots.
set +e
OUT=device-out
mkdir -p "$OUT/screenshots"
PKG=com.shaneblackburn.progallery.debug

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  --stacktrace > "$OUT/device-test.log" 2>&1
echo $? > "$OUT/exit_code"

# Test reports
find app/build/outputs/androidTest-results -name '*.xml' -exec cp {} "$OUT/" \; 2>/dev/null
# Screenshots written by the tests (two possible locations)
adb pull "/sdcard/Android/data/$PKG/files/screenshots/." "$OUT/screenshots/" >/dev/null 2>&1
find app/build/outputs/connected_android_test_additional_output -name '*.png' -exec cp {} "$OUT/screenshots/" \; 2>/dev/null

# Launcher: home screen and app drawer, to check the icon as the launcher draws it.
adb shell input keyevent KEYCODE_HOME; sleep 3
adb exec-out screencap -p > "$OUT/screenshots/60_launcher_home.png"
W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1); H=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f2)
adb shell input swipe $((W/2)) $((H*85/100)) $((W/2)) $((H*20/100)) 400; sleep 3
adb exec-out screencap -p > "$OUT/screenshots/61_launcher_drawer.png"
adb logcat -d -t 3000 > "$OUT/logcat.txt" 2>/dev/null
ls "$OUT/screenshots" | head -50
exit 0
