#!/usr/bin/env bash
# Release-build smoke test on the CI emulator.
#
# The instrumented suite runs against the debug build. The APK people install is the
# minified (R8) release build, where a missing keep rule only shows up at runtime. This
# script installs app-release.apk and drives it with adb like a user would:
# gallery -> photo viewer -> photo editor (Mono filter, save copy) -> video editor export.
# It relies on the PGTest media left on the device by the instrumented suite.
#
# Writes device-out/release_smoke.txt (log) and device-out/release_smoke_result (pass/fail).
set +e
OUT=device-out
mkdir -p "$OUT/screenshots"
PKG=com.shaneblackburn.progallery
APK=app/build/outputs/apk/release/app-release.apk
LOG="$OUT/release_smoke.txt"
FAILED=0
: > "$LOG"

say() { echo "$*" | tee -a "$LOG"; }
fail() { say "FAIL: $*"; FAILED=1; }
shot() { adb exec-out screencap -p > "$OUT/screenshots/r_$1.png"; }

# Finds a node by exact text or content-desc in a UI dump and taps its centre.
# Usage: tap "Label" [timeout_seconds] [prefer_lowest]
tap() {
  local label="$1" timeout="${2:-15}" lowest="${3:-}" end=$((SECONDS + ${2:-15}))
  while [ $SECONDS -lt $end ]; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    local xy
    xy=$(python3 - "$label" "$lowest" <<'EOF'
import re, sys, xml.etree.ElementTree as ET
label, lowest = sys.argv[1], sys.argv[2]
try:
    nodes = [n for n in ET.parse('/tmp/ui.xml').iter('node')
             if n.get('text') == label or n.get('content-desc') == label]
except Exception:
    nodes = []
pts = []
for n in nodes:
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds', '')))
    if x2 > x1 and y2 > y1:
        pts.append(((x1 + x2) // 2, (y1 + y2) // 2))
if pts:
    x, y = max(pts, key=lambda p: p[1]) if lowest else pts[0]
    print(x, y)
EOF
)
    if [ -n "$xy" ]; then adb shell input tap $xy; sleep 1; return 0; fi
    sleep 1
  done
  return 1
}

has_media() { adb shell content query --uri "content://media/external/$1/media" --projection _display_name 2>/dev/null | grep -q "_display_name=$2"; }
alive() { adb shell pidof "$PKG" >/dev/null 2>&1; }

say "== release smoke test"
[ -f "$APK" ] || { fail "release APK not built"; echo fail > "$OUT/release_smoke_result"; exit 0; }
adb uninstall "$PKG" >/dev/null 2>&1
adb install -r "$APK" >> "$LOG" 2>&1 || fail "install"
for p in READ_MEDIA_IMAGES READ_MEDIA_VIDEO POST_NOTIFICATIONS ACCESS_MEDIA_LOCATION; do
  adb shell pm grant "$PKG" "android.permission.$p" 2>/dev/null
done
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" >> "$LOG" 2>&1
sleep 4
shot 01_timeline

# Photo viewer -> editor -> Mono -> save copy
tap "pgtest_photo_1.jpg" 20 && say "ok: photo opened" || fail "photo not found in timeline"
tap "Edit" && say "ok: editor opened" || fail "Edit button"
tap "Filters" 20 || fail "Filters tab"
tap "Mono" || fail "Mono filter"
sleep 5; shot 02_photo_mono
tap "Save" || fail "Save"
tap "Save as copy" || fail "Save as copy"
for i in $(seq 1 30); do has_media images pgtest_photo_1_edited.jpg && break; sleep 2; done
has_media images pgtest_photo_1_edited.jpg && say "ok: edited photo saved" || fail "edited photo not saved"
sleep 2; shot 03_photo_saved
alive || fail "app died after photo save"

# Back to the gallery, open the video, export it unchanged
for i in 1 2 3 4; do adb shell input keyevent KEYCODE_BACK; sleep 1; done
adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 3
tap "pgtest_clip.mp4" 20 && say "ok: video opened" || fail "video not found"
sleep 2; shot 04_video_player
tap "Edit" && say "ok: video editor opened" || fail "video Edit button"
tap "Export" 20 || fail "Export button"
sleep 5; shot 05_video_editor
tap "Export" 10 lowest || fail "dialog Export button"
for i in $(seq 1 90); do has_media video pgtest_clip_edited.mp4 && break; sleep 2; done
has_media video pgtest_clip_edited.mp4 && say "ok: edited video exported" || fail "edited video not exported"
sleep 2; shot 06_video_exported
alive || fail "app died after video export"

adb logcat -d > "$OUT/release_logcat.txt" 2>/dev/null
if grep -E "FATAL EXCEPTION|AndroidRuntime: Process: $PKG" "$OUT/release_logcat.txt" | grep -q .; then
  fail "crash in release build:"
  grep -A25 "FATAL EXCEPTION" "$OUT/release_logcat.txt" | head -60 >> "$LOG"
fi

[ $FAILED -eq 0 ] && { say "RESULT: pass"; echo pass > "$OUT/release_smoke_result"; } \
                  || { say "RESULT: fail"; echo fail > "$OUT/release_smoke_result"; }
exit 0
