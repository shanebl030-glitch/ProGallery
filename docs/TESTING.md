# Test report and known limitations

## How testing works for this project
The development workspace cannot reach Google's Android repositories, so:
- `core` (pure Kotlin) tests run locally **and** in CI.
- The Android app is compiled, unit-tested, assembled (debug + minified release) and
  linted in **GitHub Actions** on every push.
- **No emulator or physical device is available to the developer.** Everything that
  needs a real phone is listed below as *needs on-device testing*.

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

## Needs on-device testing (not yet verified)
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

## Known limitations (v0.1.0)
- **Favourites are stored by ProGallery**, not in Android's MediaStore favourite flag,
  so favourites made in other gallery apps aren't shown (avoids a system prompt on every tap).
- **Android 10:** no system trash, so delete is permanent (the app says so before deleting).
- **Swipe-down-to-close** in the viewer isn't implemented yet (use back).
- **Motion photos** are detected and labelled but their video part doesn't play yet (Milestone 4).
- **RAW** files show the embedded preview Android provides; no full RAW decode.
- Rename/move ask for Android's confirmation each time for files made by other apps
  (platform rule on Android 11+).
- Album rename is not available (an "album" is a folder; renaming means moving every file).
- The release APK is signed with the CI **debug key**. It installs fine, but future
  builds must be signed with the same key to update in place; see README for setting up
  your own release key.
- Photo and video editors are not in this build (Milestones 2–3).
