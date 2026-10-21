#!/usr/bin/env bash
# Runs the instrumented test suite on the CI emulator and collects reports and screenshots.
set +e
OUT=device-out
mkdir -p "$OUT/screenshots"
PKG=com.shaneblackburn.progallery.debug

# Stream logcat to a file during the run, so a hang still leaves evidence.
adb logcat -v time > "$OUT/logcat_live.txt" 2>/dev/null &
LOGCAT_PID=$!
# Per-test limit (5 min) and an overall cap below the CI step timeout, so reports,
# screenshots and logs are always collected even if a test hangs.
timeout --signal=TERM 2700 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.timeout_msec=300000 \
  --stacktrace > "$OUT/device-test.log" 2>&1
CODE=$?
[ $CODE -eq 124 ] && echo "TIMED OUT after 38 min" >> "$OUT/device-test.log"
echo $CODE > "$OUT/exit_code"
kill $LOGCAT_PID 2>/dev/null
# Last test the runner started (helps when it hung).
grep -E "TestRunner: (started|finished|failed)" "$OUT/logcat_live.txt" | tail -20 > "$OUT/last_tests.txt"

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
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
# Then the minified release build, driven through adb.
timeout --signal=TERM 600 bash .github/scripts/release-smoke.sh
ls "$OUT/screenshots" | head -50
exit 0
