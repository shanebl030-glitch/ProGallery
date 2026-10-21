# Development session log

## 2026-10-17 — Session 1 (setup)
- Environment: cloud workspace with JDK 21 and Gradle, but **no access to Google Maven or
  the Android SDK** (network policy). Kotlin compiler fetched from JetBrains' GitHub release.
- Decision: pure-Kotlin `core` module for all testable logic, run locally via
  `tools/core-test.sh`; Android build + tests run in GitHub Actions.
- Built `core`: model, album builder, natural sort, filter/sort specs, date grouping,
  search query language, formatters, file-name utilities, recent list. 20 tests pass.
- Android scaffolding: Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01.

## 2026-10-21 — Session 2 (Milestone 1)
- GitHub repo access granted (after reconnecting the GitHub connector with repo selection).
- CI workflow added; first run green on all stages (validated dependency versions).
- CI publishes logs + APK to the `ci-results` branch so builds can be inspected from the
  workspace via git.
- Implemented Milestone 1 (see CHANGELOG 0.1.0).

## 2026-10-21 — Session 3 (v0.2.0, personal use)
- Shane confirmed v0.1.0 works well on his phone; project stays personal-use only (no Play
  publishing, ads or billing).
- Fixed the video editor compile error (Guava ImmutableList type for OverlayEffect).
- Icon: first redesigned as a vector; Shane then supplied his own design, which replaced it.
  The artwork was cut out of his image (flood-fill background removal), placed inside the
  adaptive-icon safe zone on a matched navy background, with a generated monochrome layer;
  the full design with wordmark is on the About screen. Previewed under circle/squircle/
  rounded masks at 36–192 px and in themed mode before use.
- Author credit set to exactly "Created by Shane BlackbuRn" via `AppInfo.AUTHOR_CREDIT`.
- Added an emulator test job to CI (UI Automator + engine tests, screenshots).
- Confirmed v0.1.0 was signed with a temporary per-run CI key (cert 7dad21ce…), so 0.2.0
  can't update it in place; the permanent key (SHA-256 B0:0A:A0:37…) is used once the
  repository secrets are set.
- Diagnosed the "photo editor preview doesn't update" problem with render-timing logs and
  a thread dump from the emulator: the filter render took ~19 s (per-pixel ThreadLocal
  lookups + filter thumbnails competing for the 1-thread ForkJoin pool on a 2-core
  emulator). Fixed in the pipeline; render now ~1–1.5 s on the CI emulator.
- CI emulator switched to the plain AOSP image with all runner cores (the Google image
  overloaded it: System UI crashes, ANRs, black screenshots).
- The AOSP image's 3-button navigation exposed Settings/Guide content hidden behind the
  nav bar — fixed. Video editor Back now deselects before offering to discard.
- Device suite: 18/18 pass (run 39543681545, commit ec47e05).
- Added a release-APK smoke test (adb-driven gallery → photo edit/save → video export)
  because the instrumented suite exercises the debug build, not the minified release.
- Release APK for Shane re-signed locally with the permanent key (cert B0:0A:A0:37…) using
  uber-apk-signer 1.3.0 (apksig); verified with apksig and androguard (v1/v2/v3, no
  INTERNET permission).

## 2026-10-21 — Session 4 (v0.3.0, video editor foundation)
- Roadmap agreed with Shane (v0.3–v0.9): video projects → transitions/layers/keyframes →
  audio → Memories engine → story videos → AI object eraser → photo editor pro.
- Milestone 1 (v0.3.0) built on top of v0.2.0, no rewrites of working code:
  - Dependency-free JSON reader/writer in `core`; versioned, forward/back-compatible
    project codec (unknown fields ignored, missing fields defaulted, damaged values
    repaired). 9 new core tests.
  - Photo clips on the video timeline (duration slider, EXIF-aware), drag-to-reorder
    (pure maths in core, unit-tested), pro colour grading via a custom Media3 GlEffect
    shader whose per-pixel maths mirrors a CPU reference in core.
  - ProjectStore (atomic JSON files in app storage), autosave, Projects screen, in-app
    media picker, missing-media handling.
- CI: added VideoProjectDeviceTest (engine) and ProjectsUiTest (full workflow).
- CI stability: the self-hosted-style emulator hung twice under heavy software-GL export
  load. Fixed by widening the device-test step budget (85 min step / 110 min job),
  capping the gradle run (45 min) and the release smoke test (10 min) so results are
  always collected, and trimming redundant exports. Added live logcat capture.
- Known gap: exact upright orientation of a *single-image* export (one photo, no video)
  can't be asserted on the emulator — Media3's single-image effect pipeline differs from
  the video path. The photo renders with its content; orientation is logged for
  confirmation on Shane's phone. Photos within mixed projects export correctly (tested).

## 2026-10-22 — Session 5 (v0.3.1, preview decode fix + split/cut)
- Shane reported ERROR_CODE_DECODING_FAILED in the preview on a real Samsung portrait
  video (thumbnails fine, preview dead). Diagnosed as HDR10+/10-bit HEVC vs Media3's
  default HDR_MODE_KEEP_HDR.
- Fix: Composition tone-maps HDR/10-bit to SDR via OpenGL (preview + export share it).
- Added a raw-ExoPlayer preview fallback (decodes HDR natively) with a banner, plus
  VideoDiagnostics (codec/res/fps/bit-depth/HDR) surfaced in errors and logs.
- Added a prominent Split-at-playhead button in the transport bar; the split/delete-middle
  workflow uses existing core ops (splitAt/removeClip/withTrim) with source-time accuracy.
- Tests: 64 core, 26/26 device (incl. split→delete→export and the diagnostics reader).
- Caveat: the HDR decode itself can't be verified on the CI emulator (no HDR decoder);
  needs confirmation on Shane's phone. One pre-existing flaky photo-editor "Frame" tab
  scroll was made robust with raw swipe gestures.
- CI emulator remained flaky (two boot/hang failures and one androidTest compile error —
  a missing Uri import — before the green run).
