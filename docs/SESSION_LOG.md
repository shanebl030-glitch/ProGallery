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

## 2026-10-22 — Session 6 (v0.3.2, frame-step + transitions)
- Shane confirmed the HDR/simplified-preview fix works (an 8-bit H.264 WhatsApp video now
  previews via the fallback). Requested: more transitions, and frame-step for exact cuts.
- Added frame-step (‹ ›) in the transport bar (clip frame rate probed), and Blur + Zoom
  transitions.
- Found and fixed a real bug: transitions were applied as a whole-composition effect, which
  receives per-clip timestamps (reset at each clip), so the effect was zero exactly at every
  boundary — fades between clips and the new blur/zoom never rendered (confirmed by
  inspecting exported boundary frames: plain/blur/zoom were identical). Reworked transitions
  to apply per clip (ClipEdge + ClipTransitionEffect), like the colour grade which is proven
  to work on-device.
- Also surfaced the exact reason the full-effects preview falls back (tappable details), to
  diagnose why CompositionPlayer fails on Shane's phone even for SDR video.
- Hardened PreviewController release (CompositionPlayer.release can throw).
- Testing: 66 core tests pass (incl. per-clip transition edge math). The last HEALTHY CI run
  (39f12c9) ran the full device suite (frame-step, transition UI, exports all exercised);
  its only real failure was blur not rendering at the boundary — the bug now fixed.
- BLOCKER: GitHub's hosted Android emulator was in a sustained outage for several hours
  (~12 consecutive device-job boot/runner failures across re-runs and fresh pushes), so the
  per-clip transition RENDERING and the release smoke could not be re-verified in CI for the
  final commit. Code compiles every time; delivered with this caveat for on-phone check.

## 2026-10-22 — Session 7 (v0.4.0, transition library)
- Shane tested 0.3.2 on his phone: frame-step + split/cut worked; wasn't sure the Blur
  transition actually rendered (well-founded — the per-clip fix was never CI-verified due to
  the emulator outage). Asked for: verify existing transitions on export (inspect frames),
  a much larger transition library (listed 12), direction/intensity/duration controls, a
  better picker with previews + timeline display, plus J-/L-cuts and match-cut support — and
  to explain limitations rather than ship broken effects.
- Built a single source of truth for transitions in core: `Transitions.kt` holds the types +
  parameters and `TransitionCpu`, a CPU reference of the exact shader maths (the VideoGrade
  pattern). The GPU shader (`ClipTransitionEffect`) was rewritten to apply the same uniforms.
- Verified the DESIGN locally without a GPU: 77 core tests (TransitionCpuTest renders each
  effect and checks the pixels), plus a rendered contact sheet of all 11 transitions
  (before/during/after a cut) — confirmed each looks right and meets at a shared neutral state
  at the cut (no jump/frozen frame). Sent the contact sheet to Shane.
- UI: grouped picker with a live animated demo + faithful per-option thumbnails (rendered from
  TransitionCpu, so previews match the export and work even when the GPU preview falls back);
  direction/strength/length controls; timeline glyphs; clip-edge-vs-whole-clip guidance; a
  match-cut helper showing the end-of-A/start-of-B frames side by side.
- Device test `allTransitionsRenderOnExport` added: exports every transition and inspects the
  boundary + off-cut frames. NOT yet run — emulator outage continued into this session.

### Investigation — what needs a two-clip overlap compositor (next milestone)
The current engine applies transitions PER CLIP because a Media3 `EditedMediaItemSequence`
plays clips strictly back-to-back with no overlap, and a composition-level effect gets per-clip
timestamps (so it can't straddle a boundary). That makes these impossible today, and they are
deliberately NOT faked:
- **True cross dissolve** (both clips' live frames blended) — a fade-through-black is not a
  dissolve, so CROSS_DISSOLVE is modelled but excluded from the picker.
- **True reveal wipe/push** (the other clip shown underneath, not black).
- **J-/L-cuts** — audio leading or trailing the visual cut, i.e. a clip's audio independent of
  its video timing.
The rendering approach for all three is the same: a second `EditedMediaItemSequence` carrying
the incoming clip, positioned to OVERLAP the outgoing clip by the transition duration, composited
with a per-frame alpha/mask (and, for J/L-cuts, an independent audio offset per clip). Risks to
resolve before shipping: CompositionPlayer's multi-sequence *video* compositing support for the
live preview in Media3 1.5.1 (export via Transformer is the better-supported path); precise gap/
offset bookkeeping across splits/reorders; and verifying alpha compositing on-device. Planned as
v0.4.1 once (a) the emulator is back to verify it and (b) the per-clip library here is confirmed
on Shane's phone. A freeze-frame dissolve (overlaying the neighbour's boundary still via the
proven OverlayEffect path) is a lower-risk interim option if a full-motion dissolve proves
unreliable.

- BLOCKER (still): GitHub hosted Android emulator outage — device verification of the whole
  milestone is queued, not done. Delivered with that split stated clearly.

## 2026-10-22 — Session 8 (v0.4.1, Galaxy Fold crash diagnosis)
- Shane installed 0.4.0 on two phones: works on the first, but on his Samsung Galaxy Fold
  ProGallery "keeps stopping" when editing video. Asked to find the EXACT cause (not guess),
  harden error handling so the whole app doesn't close, keep the duration controls, and
  re-verify transition rendering — and, if I can't reproduce it, tell him exactly how to
  collect the crash log.
- Can't reproduce: no Galaxy Fold here, and the CI emulator is still down. Inspected the code:
  MainActivity already handles configChanges (so fold/unfold does NOT recreate the Activity —
  rules out the classic foldable crash); the preview already falls back to a plain player on
  Media3 errors, releases are guarded, thumbnails are capped 160x160 regardless of screen size.
  So a remaining crash is most likely a NATIVE GPU/codec crash in the CompositionPlayer GL
  pipeline (uncatchable in Java) or a background-thread exception — and there was NO global
  uncaught-exception handler, which is why any throw became "keeps stopping".
- Shipped diagnosis + mitigation (not a claimed fix):
  - CrashReporter: global handler → shareable report (device + SoC + Android + phase + stack
    trace); next-launch dialog + Settings > Diagnostics. Synchronous SharedPreferences (safe in
    a crash handler); chains to the platform handler.
  - Safe preview mode: skips the GPU effects pipeline in preview (effects still export); editor
    toggle; auto-enabled after a video crash so the Fold isn't stuck crash-looping.
  - Phase tags to show where it crashed (preview-full / preview-safe / export).
- Duration controls verified in code + tests: transitionDurationSetsExportWindow (duration →
  export window, Standard cut has none) and projectRoundTripsExactly now covers transition
  direction + intensity persistence. 78 core tests pass.
- NEED FROM SHANE: the crash report (in-app Share, or adb logcat) from the Fold, plus its exact
  model + Android version, to identify the exact cause. Until then, Safe preview mode keeps the
  editor usable on that device.
- Still blocked: CI emulator outage continues, so no on-device export verification of transitions.

## 2026-10-22 — Session 9 (v0.4.2, Android 13+/Samsung compatibility)
- Shane clarified the device is a Samsung Galaxy Z Fold5 (his friend's), and set ongoing
  requirements: support Android 13 and all newer; design for Galaxy S/Z Fold/Z Flip/A series
  (different SoCs, GPUs, decoders, memory, screens, foldables); find the ACTUAL Fold5 cause from
  crash logs rather than assuming GPU; keep all editing features working; graceful fallbacks not
  crashes; automated + clearly-labelled device tests; preserve data/settings/icon/credit; same
  signing key; no Play/ads/IAP. Fix Fold5 first, then broaden.
- Note: Z Fold5 = Snapdragon 8 Gen 2 / Adreno 740 (a flagship GPU), so a raw "GPU can't compile
  shaders" theory is unlikely. More plausible non-GPU causes: the experimental Media3
  CompositionPlayer, or the cover↔main display switch resizing the preview surface.
- STILL need the crash report/logcat from the Fold5 to pin the exact cause — not received yet.
- Shipped compatibility hardening (not a claimed Fold5 fix):
  - core/video/Compat.kt: pure, unit-tested version/size decisions (supported range, HDR tone-map
    availability, GPU-safe even-dimension output clamp). minSdk kept at 29 (covers 13+ and older);
    targetSdk 35.
  - resizeableActivity=true; editor rebuilds the preview on configuration change (fold/unfold/
    rotate) so a stale surface can't crash — a concrete foldable mitigation.
  - Output size clamped via Compat.clampOutput.
- Tests: 82 core (added CompatTest across simulated SDKs/sizes + fullEditingWorkflowStaysConsistent).
  All JVM-level; NOT run on a Samsung device or the CI emulator (emulator outage continues).
- Reminder to self: the CI build job's unit-test/assemble steps use continue-on-error; the REAL
  pass/fail is in ci-results SUMMARY.txt (core_tests/app_tests/assemble/lint outcomes), not the
  masked step conclusions. (A missing import in 0.4.1 was caught this way.)
