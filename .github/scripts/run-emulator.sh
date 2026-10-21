#!/usr/bin/env bash
# Boots an Android emulator with full diagnostics, then runs the device test suite.
set -x
API=${API:-34}
TARGET=${TARGET:-google_apis}
IMG="system-images;android-${API};${TARGET};x86_64"
OUT=device-out
mkdir -p "$OUT"
# One explicit AVD folder for both avdmanager and the emulator (newer tools differ otherwise).
export ANDROID_USER_HOME="$HOME/.android"
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
SDKM=$(ls "$ANDROID_HOME"/cmdline-tools/latest/bin/sdkmanager)
AVDM=$(ls "$ANDROID_HOME"/cmdline-tools/latest/bin/avdmanager)
ls -l /dev/kvm > "$OUT/kvm.txt" 2>&1
yes | "$SDKM" --licenses > /dev/null 2>&1
"$SDKM" --install "emulator" "platform-tools" "$IMG" > "$OUT/sdk-install.log" 2>&1
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"
emulator -accel-check > "$OUT/accel.txt" 2>&1
echo no | "$AVDM" create avd -n pgtest -k "$IMG" -d pixel_6 --force > "$OUT/avd.log" 2>&1 \
  || echo no | "$AVDM" create avd -n pgtest -k "$IMG" --force >> "$OUT/avd.log" 2>&1
{ echo "--- emulator -list-avds"; emulator -list-avds; echo "--- avd dir"; ls -la "$ANDROID_AVD_HOME"; } >> "$OUT/avd.log" 2>&1
emulator -avd pgtest -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot \
  -camera-back none -camera-front none -memory 4096 -partition-size 6000 > "$OUT/emulator.log" 2>&1 &
timeout 600 adb wait-for-device || { echo "device never appeared" > "$OUT/boot.txt"; echo missing > "$OUT/exit_code"; exit 0; }
booted=0
for i in $(seq 1 240); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then booted=1; break; fi
  sleep 5
done
echo "booted=$booted after ~$((i*5))s" > "$OUT/boot.txt"
if [ "$booted" != "1" ]; then echo "missing" > "$OUT/exit_code"; exit 0; fi
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell input keyevent 82   # dismiss keyguard
sleep 10
bash .github/scripts/device-tests.sh
adb emu kill || true
exit 0
