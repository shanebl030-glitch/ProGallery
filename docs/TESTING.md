# Test report and known limitations

## How testing works for this project
The development workspace cannot reach Google's Android repositories, so:
- `core` (pure Kotlin) tests run locally **and** in CI.
- The Android app is compiled, unit-tested, assembled (debug + minified release) and
  linted in **GitHub Actions** on every push.
- Since v0.2.0, CI also boots an **Android 14 emulator** (API 34, x86_64, Pixel 6 screen,
  3-button navigation) and runs the device test suite: UI Automator tests that drive the
  real app, plus engine tests for photo rendering/saving and video export. Screenshots,
  logcat and test reports are published to the `ci-results` branch for review.
- The emulator is not a phone: real hardware still needs checking (see the list below).

## v0.2.0 results (CI run 39547218141, commit 8b6e47b)

| Check | Result |
|---|---|
| Core unit tests | ✅ 51 pass |
| App unit tests (incl. exact credit string) | ✅ pass |
| Debug + release APK build, lint | ✅ |
| Device tests on the emulator (debug build) | ✅ 18 / 18 pass |
| Release (minified) APK smoke test on the emulator | ✅ pass: gallery, photo edit + save, video export, no crash |

### Verified on the emulator (with screenshots reviewed)
- **Gallery:** timeline, albums list, opening an album, library, search ("4 results"),
  photo viewer, details sheet with EXIF (camera make/model, aperture), video player.
- **Credit:** About screen shows Shane's logo and exactly "Created by Shane BlackbuRn";
  Settings footer shows "ProGallery 0.2.0 · Created by Shane BlackbuRn".
- **Icon:** adaptive icon with monochrome layer; rendered correctly in the launcher's
  app drawer.
- **Photo editor (through the UI):** opens, Mono filter applied and the live preview
  turns monochrome (measured colour spread 75.9 → 0.0), filter thumbnails render, crop
  1:1, Instant frame, *Save as copy* writes a new full-resolution file and leaves the
  original untouched.
- **Photo engine:** render + save copy keeps EXIF; replace original overwrites in place;
  preview decode is downscaled; editor preview updates after an edit.
- **Video editor (through the UI):** opens with live preview, add a timed title (preview
  still shows picture), Back deselects the title, fade in from black, export dialog,
  export saves `pgtest_clip_edited.mp4` (duration checked 3.5–4.6 s, has video), original
  untouched.
- **Video engine:** trim + speed + filter + title + fade; merge with transition + music +
  square frame + rotate; muted and split clips; title appears and disappears at the
  right times; preview player prepares and survives repeated rebuilds.

### Bugs found and fixed by these tests
- Video preview crashed (missing clip durations) and stopped after the first edit.
- Photo editor preview could take ~20 s to update after choosing a filter (thread
  contention in the pixel pipeline) — now about 1–1.5 s on the 4-core emulator.
- Settings / User guide bottom items hidden behind the 3-button navigation bar.
- Dark text on the editors' dark background.

## v0.1.0 results (CI run 39502088415, commit 6637246)

| Check | Result |
|---|---|
| Core unit tests (20 at v0.1.0; 37 after edit groundwork) | ✅ pass |
| App unit tests (MediaSource) | ✅ pass |
| Debug APK build | ✅ |
| Release APK build (R8 minify + resource shrink) | ✅ 5.98 MB |
| Lint | ✅ no errors; minor warnings fixed after this run |

### What the automated tests cover
- Sorting (all fields, ties, direction), filtering, hidden-album filtering
- Date grouping labels and midnight boundaries in the local time zone
- Search parser and matcher (operators, quotes, bad input)
- Album building (covers, counts, trash exclusion, pin/priority ordering)
- Smart collections, recently viewed list persistence format
- File-name validation (illegal chars, byte length), unique/edited naming
- Formatters (sizes, durations, EXIF values)
- Edit pipeline: identity, alpha preservation, each adjustment's direction, highlight
  roll-off, tone curve monotonicity for all extreme combinations, every look, HSL band
  isolation, curves, crop/rotate/flip maths, frame layout, cancellation, and
  **seam-free strip rendering** (bit-identical to whole-image rendering)

## Needs on-device testing (not verified on a real phone)
1. First-launch permission flow on Android 10, 13 and 14 (including "Select photos" partial access).
2. Thumbnail scrolling performance on a large library (10k+ items).
3. Pinch-to-resize grid and long-press drag-select feel.
4. Viewer zoom on very large photos; swipe between photo and video pages.
5. Video playback for HEVC, HDR and high-frame-rate clips; speed changes; rotate.
6. Trash / restore / delete / rename / move consent dialogs (Android 11+).
7. Copy into a new album; move between storage volumes (SD card).
8. Hidden albums lock with fingerprint and with PIN only.
9. Opening a photo from another app (file manager, WhatsApp).
10. Dark mode, dynamic colour, tablet layout.
11. Photo editor on large camera photos (12–200 MP): preview speed, saving at full size,
    replace-original consent dialog, drawing/text/stickers by touch.
12. Video editor with real camera clips (HEVC, 4K, 60 fps, portrait), export speed and
    the progress notification, background music from your files.
13. The new icon on your own launcher, including themed icons (Android 13+).

## Known limitations (v0.2.0)
- **Signing:** builds are signed with the permanent ProGallery key only once the two
  repository secrets are set (see README). Until then each CI build uses a temporary key,
  and v0.1.0 (temporary key) can't be updated in place — uninstall it once.
- **Favourites are stored by ProGallery**, not in Android's MediaStore favourite flag,
  so favourites made in other gallery apps aren't shown.
- **Android 10:** no system trash, so delete is permanent (the app says so before deleting).
- **Swipe-down-to-close** in the viewer isn't implemented (use Back).
- **Motion photos** are detected and labelled but their video part doesn't play.
- **RAW** files show the embedded preview Android provides; no full RAW decode.
- Rename/move ask for Android's confirmation each time for files made by other apps
  (platform rule on Android 11+). Album rename is not available.
- Photo editor: the crop tool shows the photo without filters/adjustments while cropping
  (they are applied again when you leave the tool and in the saved file).
- Video editor: transitions are fades through black/white only. HDR, 4K and long
  projects haven't been tested; export speed depends on the phone's hardware encoder.
