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

## 2026-10-22 — Session 10 (v0.4.3, email crash reports)
- Shane asked to add emailing crash reports to shanebl030@gmail.com: next-launch dialog with
  Send Crash Report / View Report / Not Now; permanent Settings > Diagnostics to view/share/email
  the latest report; privacy (never auto-send, explain contents, exclude media/projects); standard
  email/share intents with a fallback if no email app; keep no-INTERNET; explain native crashes.
- Built on the 0.4.1 CrashReporter:
  - CrashReportText (pure, JVM-tested): email subject, privacy "what's included" text, native note,
    and the report body format — so report/subject/privacy wording are unit-tested without a device.
  - CrashReporter now builds the report via CrashReportText and exposes emailSubject()/whatsIncluded()
    /nativeNote()/EMAIL + deviceLine()/androidLine().
  - FileProvider ("${applicationId}.fileprovider" + res/xml/file_paths.xml for filesDir/crashes/) to
    attach the report .txt; ui/Diagnostics.kt has buildCrashEmailIntent/emailCrashReport/
    shareCrashReport/copyToClipboard + a DiagnosticsDialog. Fallback to clipboard if nothing can send.
  - CrashReportDialog restructured to Send/View/Not Now with privacy + native-crash notes; Settings
    Diagnostics opens DiagnosticsDialog.
- No INTERNET permission added (verified kept); sending is via the user's chosen app only.
- Tests: CrashReportTextTest (4 JVM, pass locally via kotlinc); DiagnosticsDeviceTest (instrumented:
  FileProvider attachment + email intent extras) — compiles, NOT run (no device / emulator down).
- Still no Fold5 crash report received; this feature is the mechanism to get it.

## 2026-10-22 — Session 11 (v0.4.4, Galaxy Z Fold5 crash FIXED)
- Shane emailed the crash report (via the 0.4.3 feature). ROOT CAUSE, and it was NOT GPU/
  foldable/preview (as I'd flagged — good that I never claimed otherwise):
    device : samsung SM-F946B (Z Fold5); android 16 (API 36); doing: startup
    Caused by: ForegroundServiceStartNotAllowedException: Service.startForeground() not allowed
    due to mAllowStartForeground false, at VideoExportService.onStartCommand
- The export foreground service was being (re)started on a cold process in the BACKGROUND
  (phase "startup" = manager state Idle on a fresh process). Android 14+ (strict on 15/16)
  forbids starting an FGS from the background → startForeground() threw → uncaught → app crash.
  The other phone is on older Android where this isn't enforced, hence Fold5-only.
- Fix:
  - ExportServicePolicy.shouldRunForeground(state) = state is Running; service stopSelf()s
    instead of calling startForeground() when no export is running (kills the cold-restart crash).
  - startForeground() wrapped in try/catch → on refusal, log + stopSelf, export continues
    in-process with in-app progress (no crash, graceful degradation).
  - VideoExportManager wraps startForegroundService() in runCatching too.
- Tests: ExportServicePolicyTest (JVM). 83 total. NOT yet confirmed on the Fold5 (no device /
  emulator down) — but the fix targets the exact exception + state from the report.
- Lesson reinforced: insisting on the real crash log (not assuming GPU) was correct — the cause
  was an Android-16 foreground-service policy, nothing to do with the GPU/foldable display.

## 2026-10-23 — Session 12 (v0.4.5, export decoupled from foreground service)
- 0.4.4 stopped the crash but BROKE normal exporting on the Fold5: tapping Export (app open)
  showed "Export failed — Export needs ProGallery open." My 0.4.4 fallback cancelled the export
  on any startForeground() refusal — wrong, because Android 16/Samsung refuses the FGS *start*
  even for a visible user-initiated export.
- Root cause (full trace): VideoExportManager.start() renders via Media3 Transformer IN-PROCESS,
  but started it through startForegroundService() and tied the export's fate to the service.
  Android 15/16 tightened FGS-start rules for dataSync/mediaProcessing; Samsung enforces strictly;
  startForeground() → ForegroundServiceStartNotAllowedException (mAllowStartForeground false).
  0.4.4 caught it and cancelled the export.
- Fix — decouple:
  - Export renders in-process first; success/failure from the Transformer listener only
    (onCompleted → MediaStore save; onError → real error). No dependency on the service.
  - Keep-alive service is best-effort: startService() (not startForegroundService()), promotes to
    foreground only if allowed; a refusal is logged, never crashes or cancels the export.
  - Removed failBackgroundRefused(). Kept the shouldRunForeground guard for system restarts.
  - Honest limit: without an FGS, an export is only guaranteed while the app stays open (the normal
    case). Never claims background success falsely.
- Tests: ExportServicePolicyTest (JVM) + new ExportManagerDeviceTest (instrumented, drives a real
  export through the manager). The instrumented test COMPILES but did NOT run (emulator down; and
  CI emulator is Android 14, can't reproduce the Android-16 refusal). 83 unit tests pass.
- Lesson: 0.4.4 was a classic "caught the exception, called it fixed" mistake. The right fix was
  architectural — the export never needed the foreground service to run.

## 2026-10-23 — CONFIRMED on device (v0.4.5)
- Shane confirmed 0.4.5 works on the physical Samsung Galaxy Z Fold5 (Android 16):
  preview with transitions works; EXPORT at 4K / high quality succeeded; playback is great.
- This resolves two long-standing "unverified on device" caveats at once:
  (1) the Fold5/Android-16 export foreground-service crash is fixed and a real user-initiated
      export now completes and saves; (2) the v0.4.0 transition library renders correctly in an
      exported 4K video on real hardware (previously only JVM/design-verified — the CI emulator
      was down the whole time).
- No code change in this entry; recording the on-device verification result.

## 2026-10-23 — Session 13 (v0.4.6, more transitions + duration slider)
- Shane: continue transition work, explore more, and when adding a transition be able to select
  how long it goes for.
- Added 4 new per-clip transitions (same proven engine, CPU reference + GLSL in lockstep):
  Spin (rotate+zoom, L/R direction), Shake (seed-animated jitter), Pixelate (uv quantise), Ripple
  (sinusoidal uv warp). New TransitionUniforms fields rotate/pixel/shake/ripple + MAX_* consts.
  Timeline glyphs added. 15 selectable transitions now.
- Duration control: the "Length" chips were buried BELOW the whole family grid (easy to miss).
  Restructured TransitionTools — controls (Length slider + Direction + Strength) now sit right
  under the preview; Length is a Slider (0.2–3.0 s) with live value, committing once on release
  (no undo spam). Grid relabelled "Choose a transition".
- Verified design via the CPU-reference contact sheet (transitions_v2.png) and 83 core tests
  (new spinPixelateShakeRippleTransform). Fixed ProjectCodecTest's "unknown type" fixture (it
  used "SPIN", now a real type → changed to NOT_A_REAL_TYPE).
- Device export test allTransitionsRenderOnExport auto-covers the new ones; runs when the CI
  emulator returns (still down).

## 2026-10-23 — Session 14 (v0.4.7, blur quality + first clean on-device transition run)
- CI emulator returned (healthy rerun 39839341845, commit 91a53b7). FIRST time all 15 transitions
  were exported on a real device — all 15 exported successfully (SPIN/SHAKE/PIXELATE/RIPPLE all
  produced good metrics). Two device-test failures surfaced; this session fixes both.
- BLUR quality (real app bug): the 9-tap blur kernel's taps were a full radius apart, so on real
  edge-rich video it laid down ghost edges and RAISED high-frequency (on-device BLUR hf=6.37 vs
  plain 5.35 — wrong direction). Replaced with a 25-tap (5×5) Gaussian, taps at half-radius so they
  overlap into a smooth ramp. Updated in lockstep: TransitionCpu.sampleBlur (core) + GLSL
  ClipTransitionEffect.sampleBlur (app). 83 core tests still pass (blurSoftensDetail etc.).
- Honest note: the ghost-edge failure is content-dependent and only shows on the real GPU-exported
  frames; it could NOT be reproduced on the pure-CPU JVM harness (tried a synthetic stripe image —
  both kernels softened it, so it doesn't capture the aliasing). The dense kernel is a principled
  low-pass fix (25 lightly-weighted taps → any single tap crossing an edge moves adjacent output
  pixels far less than the old heavy corner taps), but the PROOF is the device re-run, reported
  with delivery — not claimed up front.
- Device-test miscategorisation (test bug, not app bug): allTransitionsRenderOnExport put WHIP_PAN
  (luma 2.5) and PUSH (luma 0.0) in the "must not be black" else-branch, but both slide the clips
  fully off-screen by design, so the exact-cut frame is legitimately black. Moved them into the
  "passes through black" group with Fade-black/Wipe/Iris. Updated the in-app guide line to match.
- Bumped to versionCode 13 / versionName 0.4.7. Next: push, re-run device tests to confirm a clean
  pass, then sign + deliver only if it passes; report the real result either way.

## 2026-10-23 — Session 15 (v0.4.8, timeline/preview synchronisation)
- Shane reported timeline playhead vs preview out of sync even on a single uncut clip with no
  transitions → investigate the real root cause, not a visual workaround.
- Traced the whole path. RULED OUT (verified correct): core time model (durationMs/clipStarts/
  locate/stepFrames); ProjectComposer setDurationUs (checked against Media3 1.5.1 source — durationUs
  must be the full pre-clip duration; clipping is subtracted, and it's ignored for encoded video, so
  the current value is correct and must NOT be changed or the export breaks); timeline geometry
  (centre playhead + leading half-viewport spacer => time = scroll/pxPerMs, matches the mapping).
- FOUND + FIXED runtime coordination defects in VideoEditorScreen:
  (1) pause/stop didn't snap the playhead to the player's exact position (follow loop sampled on a
      timer) -> on stop it now reads currentPosition exactly and aligns scroll;
  (2) follow loop used delay(33) -> now vsync-aligned via withFrameMillis so the playhead tracks the
      preview smoothly;
  (3) added SeekParameters.EXACT to the ExoPlayer fallback for frame-accurate scrub/step;
  (4) new core TimelineMath (scrollPxForMs / msForScrollPx) is now the single source of truth for the
      time<->pixel maths used by the follow loop, scrub handler and seek path.
- Tests: 89 core unit tests (new TimelineMathTest: round-trip/no-drift, clamp, zoom-invariance,
  frame-step reversible/speed/clamp, single-clip locate). New PlaybackSyncDeviceTest drives the real
  CompositionPlayer on one clip and asserts position is seek-accurate, stable when paused, advances
  while playing, and ends on the last of rapid seeks (verifies the position VALUE the UI reads; a pure
  visual render-lag is for the device to confirm).
- CI: device-tests.sh now runs Gradle --no-daemon and hard-kills at the cap (-k 120) so reports +
  artifacts upload even when the software-GL emulator overruns (the df6e8f1 blur run died with the
  test step stuck "in progress" and uploaded nothing).
- Note: the v0.4.7 blur device run (df6e8f1) did NOT complete — the emulator job overran its 85-min
  cap, so the dense-Gaussian blur + whip/push test fixes remain unverified on-device (not failed).
  v0.4.8 carries both the blur (0.4.7) and sync (0.4.8) changes; the re-run covers both.
- Bumped to versionCode 14 / versionName 0.4.8. Next: push, let CI build+unit+lint+assemble verify
  compilation, download the release APK, sign with the permanent key, androguard-verify, deliver;
  report device-test outcome honestly; Shane verifies sync on his Fold5.

## 2026-10-23 — Session 16 (v0.4.9, blur really softens + sync confirmed on-device)
- First clean compile+emulator device run (after fixing the androidTest compile blocker: a
  `continue` inside err?.let{} — illegal under Kotlin 2.1; also made headless preview cases skip).
- Device evidence (run on Android 14 emulator):
  * SYNC PASS: previewTimelineMatchesProjectTimeline — playerDur == project timeline and seeks land
    exactly, for uncut (4023/4023, seek 2011→2011) AND trimmed (2023/2023, seek 1011→1011). Engine-
    level confirmation the v0.4.8 sync work holds. On-screen feel still for Shane's Fold5.
  * Whip/Push pass (through-black recategorisation correct).
  * BLUR still FAILED: hf 5.96 vs plain 5.35 (needs < 4.81). The v0.4.8 25-tap Gaussian still raised
    hf — root cause: GLSL read weights from gw[i+2], an array indexed by the loop variable, which
    GL ES 1.00 software GL indexes unreliably → wrong weights → ghosting, not smoothing.
- Fix: replaced the kernel with a 49-tap (7x7) Gaussian computing weight exp(-(i*i+j*j)/8)
  ARITHMETICALLY (no array indexing), step=radius/3 spanning ±radius; widened MAX_BLUR_UV 0.05→0.07.
  Updated in lockstep in ClipTransitionEffect (GLSL) + TransitionCpu (CPU ref). 89 core tests pass.
- Bumped to versionCode 15 / versionName 0.4.9. Next: push, confirm BLUR now softens on the emulator
  re-run (device suite now compiles+runs), re-sign + deliver 0.4.9 if green; Shane verifies on Fold5.
- NOTE for future-me: CI logs live on an Azure blob host this dev env can't reach; the workflow now
  tees the androidTest build log to device-out → committed to the ci-results branch (git-readable).
  Fetch device results via `git show origin/ci-results:SUMMARY.txt` and `:device/logcat.txt`.

## 2026-10-23 — Session 17 (v0.4.10, blur edge-clamp)
- Device re-run (emulator finally cooperated after many flaky attempts) confirmed sync PASS again
  (uncut 4023/4023 seek 2011→2011; trimmed 2023/2023 seek 1011→1011) but BLUR still failed:
  hf 5.68 vs plain 5.35 (needs <4.81). v0.4.9's arithmetic Gaussian only moved it 5.96→5.68.
- Diagnosis: a blur must never RAISE hf above plain, yet it did. Cause = blur taps near the frame
  border sampled OFF-FRAME as black (tap() returns black outside [0,1]), building a dark halo that
  adds high-frequency at the edges (diffCut jumped 19.9→30.9 — a visible dark border), swamping the
  interior softening.
- Fix: edge-clamp the blur neighbour taps to [0,1] (repeat the border pixel) — standard artifact-free
  blur. Centre still returns black when off-frame, so whip/push keep sliding through black. Lockstep:
  ClipTransitionEffect GLSL (texture2D direct on clamped coords) + TransitionCpu. 89 core tests pass.
- Bumped versionCode 16 / versionName 0.4.10. Next: push, retry device suite to confirm BLUR hf now
  drops below plain, re-sign + deliver 0.4.10.
- Reminder: CI emulator is very flaky (dead console / hung boot / crash); ~1 in 4-5 runs produce
  results. Retry loop with per-attempt cap + cancel-stuck lives in scratchpad/retry2.sh.

## 2026-10-24 — Session 18 (v0.4.11, scrub preview frame — real root cause)
- Shane re-reported preview≠playhead WITH screenshots (DJI drone footage, long clips). My earlier
  timestamp-based "confirmation" was insufficient — he called that out explicitly.
- Re-traced: geometry/mapping correct (centre playhead + half-viewport spacer => time=scroll/pxPerMs;
  thumbnails sampled across source, tiled onto trimmed strip at matching positions). Seeks exact
  (ExoPlayer default SeekParameters.EXACT; CompositionPlayer reports exact position — hence the old
  test passed). CompositionPlayer exposes NO seek-parameter/scrubbing control (checked 1.5.1 source).
- Root cause = preview SURFACE, not timeline maths: on long-GOP footage each exact seek decodes from
  the prior keyframe + GL composite; scrub fired ~16 seeks/s, burying the compositor so the surface
  trails and can settle on a STALE composited frame from an earlier seek (position right, frame wrong).
- Fix: scrub seeks the preview only after the drag settles ~90ms (debounce, was sample 60ms) and
  cancels the pending seek on release before the final exact seek. Playhead/timecode still follow the
  finger live. Export/projects/settings/key/credit untouched.
- Test: new PlaybackSyncDeviceTest.previewSurfaceShowsTheFrameForTheSeekPosition renders the preview
  into an ImageReader and compares the ACTUAL rendered frame (8x8 grey signature) to the source frame
  at the mapped position (closest-match, not timestamp). Skips if emulator can't present frames.
- 89 core tests pass; androidTest compiles. NOT verified on-device: GitHub emulator still in outage
  ("Failed to start Emulator console") so the surface test couldn't run. Scrub fix is reasoned;
  Shane's Fold5 is the confirmation.
- Bumped versionCode 17 / versionName 0.4.11. Build + sign + deliver.

## 2026-10-24 — Session 19 (v0.4.12, REAL root cause: stuck programmatic-scroll flag)
- Shane: still out of sync (same/worse) after v0.4.11, with screenshot. Asked him to test SAFE
  PREVIEW MODE (different player, same timeline code). Result: ALSO out of sync → bug is in SHARED
  timeline code, NOT CompositionPlayer. v0.4.11 (debounce) was the wrong direction.
- ROOT CAUSE: `programmaticScroll = true; scroll.scrollTo(...); programmaticScroll = false`.
  scroll.scrollTo is suspend; the playback follow loop runs it in LaunchedEffect(playing), which is
  CANCELLED when playback stops. If cancelled mid-scrollTo (~half the time — loop alternates
  withFrameMillis/scrollTo), the unwind SKIPS `= false`, so programmaticScroll stays stuck true.
  Then the scrub guard (!playing && !programmaticScroll) is always false → positionMs stops updating
  while scrolling → playhead/timecode/thumbnails move but preview stays frozen. Both modes. Play→
  pause→scrub triggers it. Pre-dates this whole session = the original desync.
- FIX: clear the flag in a finally. One guarded helper scrollToMs(ms) used by all 3 scroll sites
  (follow loop, pause-align, seek/frame-step). Reverted v0.4.11 debounce → sample(66) (responsive).
  89 core tests pass; androidTest compiles.
- NOT device-verified (emulator outage). Deterministic cancellation fix, strong symptom match.
- Bumped versionCode 18 / 0.4.12. Build + sign + deliver.
- LESSON: my timestamp test (v0.4.8) and CompositionPlayer theories (v0.4.11) missed this because
  the bug is in the Compose coroutine lifecycle, not the player. The Safe-mode diagnostic localised
  it in one step — should have asked for that earlier.

## 2026-10-28 — Session 20 (CI slimmed; Actions minutes exhausted)
- GitHub Actions has been failing to START any job for ~5 days: fresh runs end in 1-2s with ZERO
  steps, across build/device-tests/publish. Repo is PRIVATE → free tier = 2000 Actions min/month,
  exhausted by the many ~85-110 min emulator retries. Not an outage; a quota wall.
- Per Shane's instruction (keep GitHub for builds/unit/lint, stop the emulator drain): removed the
  `device-tests` (Android emulator) job from .github/workflows/android.yml entirely. `publish` now
  needs only `build`. The build job still COMPILES androidTest (added :app:assembleDebugAndroidTest
  to the assemble step) so the instrumented tests can't rot. CI is now ~6 min/run.
- Instrumented/visual tests will run on real hardware (adb) or a local emulator, free, not on CI.
- BLOCKER: even cheap builds can't run until the free Actions minutes reset (monthly, check GitHub
  billing for the date) OR the repo is made public (unlimited free Actions). v0.4.12 (the real sync
  fix — stuck programmaticScroll flag) is committed but UNBUILT until then.
- v0.4.12 sync fix status: committed (5231978), 89 core unit tests pass locally, compiles locally
  (core). Needs a CI build to produce the signed APK.

## 2026-10-28 — Session 21 (v0.4.13, timeline overshoot/fling + on-screen sync diagnostic)
- Repo made PUBLIC by Shane → unlimited free Actions. v0.4.12 built green (commit f0e851b: core+app
  tests, assemble incl. androidTest, lint all success; app-release.apk on ci-results).
- Fold5 test of v0.4.12 (Shane): "somewhat better but still not in sync, and bad when cutting — it
  cuts off to where I want it", in Safe preview mode, at a clip boundary while trimming.
- Root cause (this session): the timeline used the default `horizontalScroll`, which **flings**. On
  release the scroll keeps gliding and settles PAST the target frame; since positionMs IS the resting
  scroll, the playhead, the final preview seek AND the split point all land beyond where the finger
  lifted → "it cuts off to where I want it", and the preview visibly chases the decelerating scroll
  (reads as "still not in sync"). A scrubber must stop dead on finger-up.
- Fix: gave `horizontalScroll` a no-op `FlingBehavior` (performFling consumes nothing) in
  VideoTimeline.kt. Release now rests exactly on the finger-up frame; split happens there. core/app
  position math untouched.
- Added an opt-in on-screen sync diagnostic: long-press the timecode toggles a green overlay showing
  pos (playhead ms), play (preview.currentPositionMs), Δ, preview mode, and the located clip/source.
  Turns any residual mismatch into readable numbers (screenshot) instead of another guess. Off by
  default, zero cost when hidden. (VideoEditorScreen.kt: showSyncDebug state + sampler + overlay +
  combinedClickable on the timecode; added ExperimentalFoundationApi opt-in.)
- Protected/unchanged: all tools, transitions (Blur/Whip/Push code unchanged since v0.4.10), icon,
  settings, saved projects, export, permissions (no INTERNET), permanent signing key, the
  "Created by Shane BlackbuRn" credit.
- First CI attempt (commit 4664150) FAILED to compile: `combinedClickable` is `@ExperimentalFoundationApi`
  and the function-level `@OptIn` wasn't accepted by this Compose/Kotlin toolchain (one error cascaded
  into app-tests, assemble and lint). Fixed by switching the long-press toggle to the STABLE
  `Modifier.pointerInput { detectTapGestures(onLongPress = …) }` — no opt-in needed — and reverting the
  experimental import/opt-in. FlingBehavior/ScrollScope are stable, so the fling fix was unaffected.
- HONEST status: fling-on-release is a sound, matching mechanism; no-fling is the standard remedy.
  NOT YET verified by me on device — needs the play→drag→release→cut test on the Fold5. If still off,
  the long-press diagnostic localises it precisely.

## 2026-10-28 — Session 22 (v0.4.14, thumbnails placed by index not time — the "cut looks wrong" cause)
- v0.4.13 delivered (signed 073bd78…, no INTERNET, permanent key). Fold5 test: cut with preview on the
  end of the train still showed two carriage thumbnails at the new clip's start.
- Traced it: the cut is CORRECT (preview + split both use positionMs; Safe-mode seek is exact), the
  THUMBNAILS were misplaced. ClipStrip filtered frames to the trimmed range then spread them by INDEX
  (`inRange[k*N/tiles]`, low end widened by one sample). For a mid-source trim (1:55.90) that pinned a
  pre-trim frame (~1:53.7) to the left edge and stretched the rest — every tile up to one sample
  (~2–4 s) too early. Hand-computed against Shane's clip to confirm the magnitude.
- Fix (VideoTimeline.kt, display-only): each tile shows the sampled frame nearest the SOURCE time at
  its position in the trimmed range (`trimStart + span*(k+0.5)/tiles` → nearest frame). Removed the
  index-spread + inRange filter. Cut/preview/export untouched.
- Fix (VideoEditorViewModel.kt loadThumbs): keep frames.size==count even on decode failure (reuse last
  good frame; pad leading gap) so the index→time mapping stays exact; sampling raised to ≤60 @ ~1s.
- Precision note: thumbnails use OPTION_CLOSEST_SYNC (keyframe) so they're nearest-keyframe accurate
  (~1–2s), not frame-exact; the PREVIEW remains the frame-exact guide (+ ‹ › frame-step).
- Protected/unchanged: tools, transitions, icon, settings, saved projects, export, no-INTERNET,
  permanent signing key, "Created by Shane BlackbuRn".
- HONEST status: placement bug confirmed by code trace; fix is low-risk/display-only. Not yet
  device-verified by me — Shane to check the thumbnail under the playhead now matches the preview.

## 2026-10-28 — Session 23 (v0.4.15, fix the two Safe-mode bugs from the bug sweep)
- Ran a full bug sweep (reported to Shane + a Claude Doc). Core found solid; two real bugs, both
  Safe-mode only, export unaffected.
- Bug 1: PreviewController.currentPositionMs returned ExoPlayer currentPosition = position within the
  current clip, so Safe-mode playback of 2+ clips reset the playhead to 0 at each boundary.
- Bug 2: locateInSimpleTimeline accumulated raw source durations and ignored speed, so Safe-mode
  scrubbing of a sped clip (or a later clip) landed on the wrong frame.
- Fix put the mapping in CORE (unit-testable) as an inverse pair on VideoProject: simpleSeek(timelineMs)
  -> (clipIndex, in-clip offset) [accumulates TIMELINE durations, scales offset by speed]; and
  simpleTimelinePosition(clipIndex, inClipMs) -> timeline position [adds earlier clips' timeline
  durations, divides in-clip pos by speed]. PreviewController.locateInSimpleTimeline and
  currentPositionMs now delegate to these.
- Tests: +5 core tests (multi-clip, speed, clamp, seek<->position round-trip) -> 94 total, all pass.
- NOTE: a parallel execution of this same task duplicated some edits (a second simpleTimelinePosition
  and the doc revs under the same session id). Reconciled by keeping one coherent simpleSeek +
  simpleTimelinePosition pair and removing the duplicate; tree verified to compile with 94 tests green.
- Protected/unchanged: tools, transitions, icon, settings, saved projects, export, no-INTERNET,
  permanent signing key, "Created by Shane BlackbuRn".
- HONEST status: verified by core unit tests + CI compile; on-device Safe-mode play/scrub still to be
  confirmed on the Fold5.

## 2026-10-29 — Session 24 (v0.4.16-media3, Media3 upgrade for full-effects scrub rendering) [BRANCH]
- Shane: don't want two half-working modes; make full-effects (CompositionPlayer) reliable enough to be
  the single editing mode (smooth playback, accurate scrub/seek, correct paused frame, accurate cuts,
  transitions shown, preview/playhead/thumbnail sync). Fold the useful bits of Safe mode in and remove
  it ONLY after full mode is device-confirmed.
- Clarified the failing workflow: play a transition -> pause -> scrub => preview shows a stale frame
  (playhead/thumbnails correct). Since the transition PLAYS, it is FULL mode (Safe mode has no effects),
  so v0.4.15 (Safe-mode) did not address it. Earlier Fold5 cut-testing was in Safe mode (banner).
- Code trace: timeline/position logic is correct (playhead == positionMs == seekTo target). The fault is
  the full-effects player not painting the seeked frame while paused.
- Research (authoritative Media3 release notes): matches a GL effects-pipeline seek bug fixed in Media3
  1.11.0 ("Clear redraw state in FinalShaderProgramWrapper when flushing to prevent the player from
  freezing when seeking"). We were on 1.5.1. App-level re-seek workarounds re-trigger the same flush, so
  the real fix is the library upgrade. Shane chose: ship workaround now / upgrade next, but the deeper
  finding showed the workaround is compromised (no-op or adds lag/flash), so went straight to upgrade.
- Upgrade (on media3-upgrade branch, main untouched): Media3 1.5.1 -> 1.11.1; compileSdk 35 -> 36 via
  android.suppressUnsupportedCompileSdk (no AGP/Gradle/targetSdk change). API fixes verified against the
  cloned 1.11.1 source: EditedMediaItemSequence.Builder; ChannelMixingMatrix identity ctor (create()
  removed; createForConstantGain only 1-2 ch); OverlaySettings -> androidx.media3.common +
  StaticOverlaySettings builder. Verified all other Media3 APIs we use are unchanged.
- CI: branch builds GREEN (core+app tests, assemble, lint; 94 tests). versionName 0.4.16-media3,
  versionCode 22, signed with permanent key for device testing.
- HONEST status: upgrade is sound (compiles + tests). NOT device-verified — the scrub render fix is
  GL/player behaviour only the Fold5 can confirm. Safe mode kept. If confirmed, merge to main as 0.4.16.

## 2026-10-29 — Session 25 (v0.4.17-media3, fix two Media3-1.11 regressions) [BRANCH]
- 0.4.16-media3 on device: (1) Export failed "trackTypes must only contain TRACK_TYPE_AUDIO and/or
  TRACK_TYPE_VIDEO"; (2) transitions didn't play in preview (screenshot showed the "Simplified preview"
  banner = full-effects CompositionPlayer errored and fell back).
- ONE root cause (confirmed in cloned Media3 1.11.1 source): EditedMediaItemSequence list/varargs
  ctors leave trackTypes={TRACK_TYPE_NONE}; Composition.experimentalSetForceAudioTrack is deprecated and
  no longer resolves it; Media3 rejects {NONE} (ALLOWED = {AUDIO,VIDEO}). Export (Transformer) and
  preview (CompositionPlayer) build the SAME composition -> both fail; preview falls back to simplified
  (no effects) -> no transitions.
- Fix (ProjectComposer): declare explicit trackTypes per sequence — video/clip sequence {AUDIO,VIDEO}
  (keeps the continuous audio track, replacing the deprecated force-audio flag), music sequence {AUDIO};
  removed experimentalSetForceAudioTrack. Added imports C, ImmutableSet.
- Test: instrumented everySequenceDeclaresValidTrackTypes builds the composition for 5 project shapes
  from synthetic clips and asserts each sequence's trackTypes ⊆ {AUDIO,VIDEO} and the video sequence has
  VIDEO. Compiles in CI; RUNS on device (needs Android Uri/media3), so CI only proves compile.
- versionName 0.4.17-media3, versionCode 23, signed with permanent key for device re-test.
- HONEST status: root cause confirmed from source; compiles + unit tests pass in CI. Export success and
  transition rendering NOT verified by CI — need the Fold5. main/0.4.15 untouched; Safe mode kept.

## 2026-10-29 — Session 26 (v0.4.18-media3, scrubbing control + effect redraw) [BRANCH]
- 0.4.17-media3 on device (Shane): transitions play initially (progress), but 3 issues:
  (1) Simplified-preview banner returns after the first cut (full-effects player falls back);
  (2) scrubbing during playback moves playhead but video keeps playing; (3) transition effect stays
  stuck when scrubbing backwards out of a transition.
- Reviewed Media3 1.11.1 CompositionPlayer scrubbing APIs (from cloned source, per Shane's pointer):
  setScrubbingModeEnabled(bool) [optimises frequent seeks, suppresses playback, implicit-play on disable
  if was playing]; experimentalRedrawLastFrame() [forces effect pipeline redraw; REQUIRES
  experimentalSetEnableReplayableCache(true), costs more power]. Also checked setDurationUs doc: we pass
  the correct full-source duration, so Issue 1 is NOT a duration mismatch.
- Issue 2 fix: scrub engages regardless of play state; beginScrub() pauses THEN enters scrubbing mode
  (pause-first so disable's implicit-play keeps it paused -> stays put on release); follow loop stops on
  scrubbing (while playing && !scrubbing). PreviewController.beginScrub/endScrub; VideoEditorScreen guard.
- Issue 3 fix: build preview CompositionPlayer with experimentalSetEnableReplayableCache(true); every
  user seek (seekTo) now calls experimentalRedrawLastFrame() to repaint the frame/effect at the new
  position, clearing a stuck transition in either direction. Added @OptIn(ExperimentalApi::class).
- Issue 1: NOT fixed yet. Ruled out trackTypes (0.4.17) and setDurationUs. Needs the actual
  CompositionPlayer error (app captures it; banner "ⓘ Details"). Asked Shane to report it.
- Scope: preview-only (scrubbing mode/replayable cache/redraw on the preview player, not the export
  Transformer) -> export/audio/transitions untouched. versionName 0.4.18-media3, versionCode 24.
- HONEST status: compiles + unit tests pass. Issues 2 & 3 are player/GL + UI-timing behaviours NOT
  verifiable by CI; need the Fold5. main/0.4.15 untouched; Safe mode kept.

## 2026-10-29 — Session 27 (v0.4.19-media3, diagnostic single-decoder retry for Issue 1) [BRANCH]
- Actual error (banner Details, 0.4.18): ERROR_CODE_DECODING_FAILED, index=1 (2nd clip), format
  supported, c2.mtk.avc.decoder CodecException 0x80000000 -> decode failure on the 2nd clip after a cut.
- Investigation (cloned 1.11.1 source + Media3 issue tracker): CompositionPlayer prewarming defaults ON
  and creates a secondary renderer = a 2nd concurrent HW decoder for the next clip; many devices allow
  only 1 instance per codec (#1825) -> multi-clip fails. Cross-device concurrent-decoder limit, exposed
  by the Media3 1.11 migration. NO public API to disable prewarming (setter is @VisibleForTesting,
  package-private, b/369817794). Confirmed export/Transformer does NOT prewarm (sequential) -> likely
  unaffected but UNTESTED on device.
- Fix (preview only, guarded, reactive, one-shot): on a full-effects decode error with prewarming on,
  rebuild ONCE with prewarming disabled via reflection on CompositionPlayer.Builder
  .setVideoPrewarmingEnabled(false); session flag prevents loops; retry only on decode-resource codes;
  if reflection unavailable/fails -> existing simplified fallback (safe). Logs success/failure.
  + ProGuard -keep for the method name (release minifies). + instrumented test
  compositionPlayerPrewarmingSetterIsReflectable (fails if Media3 removes the method; runs on device).
- DIAGNOSTIC, not proven: works-after-disable => concurrency confirmed; still-fails => concurrency
  refuted (other causes: same-source 2-clip reconfig, boundary seek, effects pipeline, Media3 bug).
- versionName 0.4.19-media3, versionCode 25. Scope preserved: export/audio/transitions/Safe mode
  untouched, no INTERNET, permanent key, credit. main/0.4.15 untouched.
- HONEST status: compiles + unit tests pass. CI emulator uses SOFTWARE decoders (no concurrent limit)
  so CANNOT validate this fix; needs real hardware. Export on-device UNTESTED (outstanding). No
  cross-device claim.

## 2026-10-29 — Session 28 (v0.4.20-media3, diagnostic visibility)
- Result of the 0.4.19 diagnostic: Shane's phone STILL falls back to the simplified preview on opening
  a multi-clip project, with the identical error (ERROR_CODE_DECODING_FAILED, clip index=1,
  c2.mtk.avc.decoder CodecException 0x80000000). So the single-decoder retry did NOT stop it.
- Forensics to disambiguate (did the retry actually disable prewarming?):
  * androguard on the shipped 0.4.19 APK: setVideoPrewarmingEnabled(boolean) SURVIVED R8 (the -keep
    worked); class renamed to Y2.C, but CompositionPlayer.Builder::class.java resolves to it at runtime,
    so getDeclaredMethod should still find it. "R8 stripped it" ruled out statically.
  * Media3 1.11.1 source: SequenceRenderersFactory.createSecondaryRenderer returns null when
    videoPrewarmingEnabled=false => disabling prewarming genuinely removes the 2nd decoder.
  * BUT index=1 decode error at position 0 is the signature of prewarming decoding the NEXT clip early
    => looks like prewarming was STILL ON during the failing attempt. Contradiction only a runtime
    signal can resolve; logcat not accessible to Shane.
- Change (diagnostic only, no behaviour change): buildReason() now appends "[clips=N, prewarm=X]" to the
  fallback Details text. X in {NO-API, ON(default), OFF(retry), ON(disable-failed)} tells us exactly what
  the failing attempt did. OFF(retry)+still-fails => concurrency REFUTED (investigate second-clip decode
  path). ON(default)/NO-API/ON(disable-failed) => reflection path didn't take effect; fix that.
- versionName 0.4.20-media3, versionCode 26. Scope preserved: no INTERNET, permanent key, credit, main
  untouched. Unit tests pass; CI build-only (sw decoders can't reproduce the hw limit). Export on-device
  still OUTSTANDING; no cross-device claim.

## 2026-10-30 — Session 28 (cont.) — root cause established; CompositionPlayer limit
- Shane's Details now reads [clips=3, prewarm=OFF(retry)] with the SAME index=1 c2.mtk.avc.decoder
  0x80000000 error. DEFINITIVE: the retry fired, prewarming was genuinely disabled (single decoder),
  and clip index=1 STILL failed => concurrency-via-prewarming is REFUTED.
- Source dig (Media3 1.11.1): CompositionPlayer's internal SequenceVideoRenderer is built with
  MediaCodecSelector.DEFAULT (hardware-first) AND setEnableDecoderFallback(false). So a hardware decode
  failure does NOT fall back to software; it fails hard. CompositionPlayer.Builder exposes NO hook to
  change the codec selector, the renderers factory, or decoder fallback (setMediaSourceFactory,
  setVideoGraphFactory, setLoadControl, setPerStreamMediaProgressionEnabled exist; none touch decoder
  selection). setPerStreamMediaProgressionEnabled = cross-sequence progression, not decoder concurrency.
- CONCLUSION: the full-effects preview's index=1 hardware-decode failure on same-source multi-clip
  (what cutting one video produces) is NOT fixable via supported CompositionPlayer APIs in 1.11.1. The
  only levers (software decode / decoder fallback) are sealed in the internal renderer factory. Likely
  mechanism: decoder reconfiguration/handoff for the 2nd same-source clip (mid-GOP start) or transient
  item-handoff decoder overlap that MediaTek rejects; effects/BufferingVideoSink hold frames longer,
  which is why adding a transition makes it surface. NOT a GL-effect bug (ClipTransitionEffect is a pure
  per-clip fragment shader on one decoded texture; it never needs the other clip's frames).
- Transitions are pure per-clip shaders (no cross-clip blend), so transitions alone don't force
  concurrency; the trigger is same-source multi-clip, which cutting always produces.
- NO code change this session-part (investigation only). No new APK: no supported fix to build, and
  Shane wants root cause + direction before the next build. Need two device facts next: (1) does EXPORT
  of this 3-clip project render correctly? (2) does the yellow banner also show for plain multi-clip
  cuts with NO transition? Options to weigh: keep split (export = source of truth); custom LoadControl
  experiment (supported, speculative); on-demand composed-frame preview via the real pipeline (reliable,
  medium effort); custom GL compositor preview w/ software decode (the "one reliable mode", large).

## 2026-10-30 — Session 29 (v0.4.21-media3) — Phase 1: on-demand composed preview
- Approved Option C (staged). Phase 1: accurate transition preview via Media3 1.11.1 FrameExtractor
  (androidx.media3:media3-inspector-frame), which defaults to PREFER_SOFTWARE decode => no hardware
  per-codec instance limit => decodes the 2nd same-source clip that CompositionPlayer (hardware-only,
  enableDecoderFallback=false, no injection hook) rejects. Verified all APIs against the 1.11.1 source
  before coding (FrameExtractor.getFrame/effects/Frame; internal re-prepares ONLY on MediaItem change,
  so scrubbing within a clip just re-seeks; ClipEdge/simpleSeek time bases).
- Changes: new FramePreviewRenderer (per-clip extractor reuse, ~720p downscale, dedicated thread,
  newest-wins); ProjectComposer.previewVideoEffects + clipMediaItem (shared with export so preview uses
  the same shaders, minus the speed effect which doesn't change a still frame); ClipTransitionEffect
  optional fixed-time provider (fixes the single-frame t=0 freeze; playback/export path unchanged when
  null); VideoProject.framePreviewSpec (pure, unit-tested); VideoEditorScreen shows the composed Bitmap
  over the raw player while paused/scrubbing in simplified mode (video clips), banner goes neutral.
- Tests: +3 core unit (framePreviewSpec mapping incl. speed; transition progress) -> 97 pass; +1
  instrumented composedPreviewRendersTransitionFrame (device-run; CI compiles). CI is build-only.
- Limits (honest): video clips only; accurate on pause/scrub, NOT real-time transition playback
  (deferred to Phase 3); inspector-frame is @UnstableApi; preview DESIGNED to match export, exact
  frame-for-frame match NOT yet verified on device.
- Scope preserved: export untouched, no INTERNET, permanent key, credit, versionCode 27 /
  0.4.21-media3, main/0.4.15 untouched. Report API deviations before delivering the signed APK.

## 2026-10-30 — Session 30 (prototype spike) — ExoPlayer + setVideoEffects preview engine
- Architecture decision: evaluate replacing CompositionPlayer for the LIVE preview. Decisive finding
  from Transitions.kt: ProGallery's transitions are ALL per-clip "through-a-state" effects (fade/blur/
  zoom/wipe/push to-or-from black/neutral) — NOT true cross-clip blends. Export produces the same
  (single sequence + per-clip ClipTransitionEffect). CROSS_DISSOLVE exists but crossClip=true and is
  excluded from the picker. So "genuine cross-clip transitions" is a separate future feature; Shane
  chose Option 1: faithfully play today's per-clip transitions. True cross-clip would need a two-clip
  overlap compositor in BOTH preview and export (or the pre-rendered proxy).
- Verified APIs in 1.11.1 source: ExoPlayer.setVideoEffects (default MediaCodecVideoRenderer only; no
  timestamp-altering effects (so speed via PlaybackParameters, not SpeedChangeEffect); call before
  prepare, swappable during playback); DefaultRenderersFactory.setMediaCodecSelector(PREFER_SOFTWARE)
  (public); AnalyticsListener.onVideoDecoderInitialized gives the real decoder name; ExoPlayer honors
  ClippingConfiguration (ClippingMediaSource) for same-source clips; enableDecoderFallback only retries
  on decoder INIT failure, not decode-time CodecException (so it would NOT fix the 0x80000000 decode
  stall — software decode is the right lever).
- PROTOTYPE (not wired to production): ExoPlayerPreviewEngine (playlist + PREFER_SOFTWARE + per-clip
  setVideoEffects swap at onMediaItemTransition + per-clip volume/speed + decoder-name capture);
  ProjectComposer.playbackVideoEffects (additive; reuses export helpers minus speed; default transition
  timing); debug-only SpikeActivity/SpikeScreen (src/debug, separate "ProGallery Spike" launcher) to
  test on real phones; instrumented ExoPlayerPreviewEngineSpikeTest (continuity across same-source
  boundary, actual decoder, seek, effects-reaches-ready); spike.yml emulator workflow (free, public
  repo) publishing results+logcat+debug APK to spike-results branch.
- Production preview/PreviewController/export/main/0.4.15 UNTOUCHED. No version bump, no release APK.
- Known prototype limits to confirm: background music (parallel track) not mixed by a single playlist
  player; per-clip speed approximated via PlaybackParameters; per-clip volume via player volume on
  boundary; effects-render on a raw SurfaceTexture may need a sized surface (PlayerView path used on
  device). Emulator = software path only, NOT proof of hardware/vendor behaviour.

## 2026-10-30 — Session 30 (cont.) — spike build green; emulator CI unreliable; debug APK for device test
- Build-only CI green on the fixed commit: ExoPlayerPreviewEngine, debug SpikeActivity/SpikeScreen,
  playbackVideoEffects, and the instrumented spike test all COMPILE. (One compile error fixed: the
  engine's onPlayerError constructor param shadowed the listener override -> recursive type-check;
  renamed to onError.)
- Emulator CI: 3 attempts on reactivecircus/android-emulator-runner (API 30 google_apis x86_64, KVM).
  Unreliable, as the repo history already documented: run 1 booted but the test didn't run; run 2
  booted (logcat) but connectedDebugAndroidTest produced no output (hung); run 3 the emulator didn't
  boot at all (no logcat). Switched to adb install -g + am instrument (robust mechanism) but the
  emulator still failed to come up. Conclusion: could NOT get automated runtime results from the free
  emulator here. Spike workflow kept (gated on a [spike] commit marker) in case a future run succeeds.
- Delivered the debug prototype instead for REAL device testing: ProGallery-Spike-0.4.21.apk
  (package com.shaneblackburn.progallery.debug — installs ALONGSIDE production; separate "ProGallery
  Spike" launcher; no INTERNET; re-signed with the permanent key b00aa037 for update consistency;
  SHA-256 91c486ad70b8ba32f8a3b18b21722e33165f147a798f0404aad98b18fbd4eb06). Shane tests the engine on
  his two phones: pick a video -> split into 2 same-source clips + a chosen transition -> continuous
  playback via the prototype engine, with the actual decoder shown and SW/HW toggle.
- Honest status: automated empirical results NOT obtained (emulator); correctness/decoder/perf/audio
  to be confirmed on-device. Production preview/export/main/0.4.15 untouched; no release APK; no version
  bump.

## 2026-10-30 — Session 31 — spike scrapped by Shane's decision; keep the built-in editor
- Shane: "Scrap that. I prefer the built-in editor in ProGallery." Decision: do NOT replace the
  CompositionPlayer-based preview with an ExoPlayer engine. Removed ALL spike prototype code from
  media3-upgrade: ExoPlayerPreviewEngine, debug SpikeActivity/screen + src/debug manifest,
  ExoPlayerPreviewEngineSpikeTest, .github/workflows/spike.yml, and ProjectComposer.playbackVideoEffects.
  Nothing in production referenced them, so removal is clean.
- KEPT (part of the built-in editor Shane likes): Phase 1 composed-frame preview (FrameExtractor) for
  accurate paused/scrubbing transitions — confirmed working on his phones; media3-inspector-frame dep;
  ClipTransitionEffect fixed-time provider; VideoProject.framePreviewSpec; ProjectComposer
  .previewVideoEffects; VideoEditorScreen composed-overlay wiring.
- Outstanding for the built-in editor: continuous playback still STOPS at a transition (the simplified
  raw ExoPlayer can't hardware-decode the 2nd same-source clip; enableDecoderFallback only covers INIT
  failures, not the decode-time CodecException). Proposed fix (not yet built, awaiting Shane's go):
  on a decode error in the simplified player, rebuild ONCE with DefaultRenderersFactory
  .setMediaCodecSelector(MediaCodecSelector.PREFER_SOFTWARE) and resume from the current position, so
  raw playback continues through the whole timeline. Preview-only; export untouched.

## 2026-10-30 — Session 31 (cont.) — v0.4.22-media3: continuous playback through transitions
- Implemented the approved fix in PreviewController (built-in editor, preview only). On a decode error
  in the simplified (raw) ExoPlayer, rebuild ONCE with DefaultRenderersFactory.setMediaCodecSelector(
  MediaCodecSelector.PREFER_SOFTWARE) and resume at the current position with the same play state, so
  continuous playback runs through the 2nd same-source clip at a transition instead of stopping. Sticky
  flag simplePlayerSoftware (one switch/session, no loop); hardware stays the default fast path. Reuses
  the existing isDecodeError/DECODE_RETRY_CODES. Export + composed-frame preview untouched.
- versionCode 28 / 0.4.22-media3. Scope preserved: no INTERNET, permanent key, credit, main/0.4.15
  untouched. HONEST: the simplified player is the most likely cause of the stop (not 100% proven w/o
  device logs); software decode is the proven-reliable lever (same as FrameExtractor). Needs Shane's
  on-device confirmation that playback now runs through transitions; perf of software 1080p to watch.

## 2026-10-30 — Session 32 (v0.4.23-media3) — Safe-mode auto-recovery + MediaTek software fallback
- Root cause of the two-phone difference confirmed: Fold5 (Qualcomm) was stuck in auto-Safe-mode from an
  earlier test-build crash (no Details = no CompositionPlayer attempt). Turning Safe mode off made the
  Fold5 run full-effects perfectly. Phone 2 (MediaTek, c2.mtk) genuinely can't run CompositionPlayer ->
  simplified raw ExoPlayer -> hardware decode stops at the 2nd same-source clip. Transitions show while
  scrubbing (FrameExtractor software, per-frame) but not during playback (raw) -- architectural.
- Implemented (approved plan, stricter recovery):
  * SafePreviewPolicy (core, pure, unit-tested): shouldTrial/skipFullEffects; MAX_TRIAL_CRASHES=2,
    STABILITY_MS=5000. +4 unit tests.
  * CrashReporter: K_SAFE_AUTO + K_SAFE_CRASH_COUNT; enableSafePreviewAfterCrash (auto+count++),
    setSafePreviewManual (auto=false; off resets count), clearSafePreviewAfterSuccess (no-op if manual),
    shouldRecoveryTrial. CrashReportDialog uses enableSafePreviewAfterCrash.
  * PreviewController: new onFirstFrame callback fired from CompositionPlayer onRenderedFirstFrame
    (confirmed it dispatches via SimpleBasePlayer). MediaTek: raw player uses PREFER_SOFTWARE from the
    start for error-driven fallback (cause!=null, sticky); AnalyticsListener captures the real decoder
    name -> appended to Details as [raw=...]; reactive software retry kept for the Safe-mode path.
  * VideoEditorScreen: recoveryTrial on open (attempt full-effects if auto-safe & back-off not spent);
    clear auto-Safe only after full-effects first frame + stable 5s (cancelled if it falls back);
    manual toggle -> setSafePreviewManual + ends trial; always-visible "Safe preview mode is on" banner
    with one-tap Turn off. Guide text updated.
- STRICTER per Shane: clear ONLY on confirmed full-effects success (init + frame rendered + stable 5s),
  never just "open 5s"; manual never auto-overridden; keep counter + back-off.
- Fold5 untouched (recovery only runs when auto-safe; Fold5 is manual-off). Export untouched; no feature
  removal; no INTERNET; permanent key; main/0.4.15 untouched. versionCode 29 / 0.4.23-media3.
- HONEST: recovery trial + decoder behaviour are device-verified by Shane; CI verifies compile + unit
  tests (emulator unreliable). Edge: a device that crashed once then gracefully falls back (no crash,
  no full success) keeps trialing and isn't auto-cleared -- per the stricter rule; user can clear manually.

## 2026-10-31 — Session 33 (v0.4.24-media3) — Ripple backwards-scrub fix + exported videos in Today
- Two approved, isolated fixes. Fold5 full-effects playback, the MediaTek software fallback and the
  export pipeline are all untouched; changes are confined to the composed-frame preview and the
  gallery's day-grouping. versionCode 30 / 0.4.24-media3. main/0.4.15 untouched; permanent key; no INTERNET.

- Fix 1 — Ripple (and all) transition backwards-scrub freeze.
  * Refined diagnosis before coding: the ripple SHADER cannot flip a frame (displacement capped at
    MAX_RIPPLE=0.04). The "almost upside down, stuck until the next transition" symptom is a FROZEN
    composed frame: the transition was baked into FrameExtractor's effect list, and FrameExtractor's
    internal frame is keyed on source position, so a reused/cached frame held a stale transition while
    scrubbing. It cleared at the next clip boundary because that re-prepares the extractor.
  * Proper fix (no timestamp nudging): extractor now renders the TRANSITION-FREE base frame only
    (ProjectComposer.previewBaseEffects = geometry + colour + Presentation) — a pure function of source
    position, so the cache/seek is always correct. The transition is applied on the CPU every render,
    from the exact playhead output-time (clipEdge.uniformsAt(spec.inClipTimelineMs)), via the shared
    TransitionCpu reference (same maths as the export shader) → preview/export parity, and nothing
    position-dependent is left in the cache to go stale.
  * New TransitionBitmap (app): applies TransitionUniforms to the base bitmap with bilinear sampling,
    reusing TransitionCpu.render. Skipped entirely when uniforms are identity (outside transition
    windows → full-speed scrub). Work capped to 540px short side then scaled back, so the 49-tap blur
    transitions stay responsive on a paused/scrub still; result scaled back to the base size.
    Seed derived from source position so glitch/shake/ripple phase varies smoothly (never freezes).
  * ClipTransitionEffect (GPU, export/playback path) left UNTOUCHED — its fixedTimeMs provider is just
    no longer used by the preview. CompositionPlayer playback and Transformer export unchanged.

- Fix 2 — exported videos now appear under Today (Shane: KEEP the original capture date).
  * Export (VideoExportManager) UNCHANGED: still sets no date columns, so MediaStore stamps DATE_ADDED
    = now on insert while DATE_TAKEN keeps the original footage's capture date. Source files/metadata
    never touched.
  * New GalleryDate.of(item, today, zone) (core, pure): returns dateAdded when an item was added today
    AND its capture date is older than today; otherwise effectiveDate. So freshly added/exported media
    surfaces under Today (at the top, by add time) and settles back to its capture date the next day.
  * Applied via SortSpec.comparator(today,zone)/dateOf(item,today,zone) and a new applyFilterAndSort
    overload — ONLY the DATE_TAKEN ordering is boosted; DATE_ADDED/DATE_MODIFIED sorts and existing
    media's chronology are unchanged. GalleryViewModel threads LocalDate.now()/systemDefault() into the
    timeline sort, album sort, smart-collection sort and group() so grouping and sort stay in lockstep.
  * No duplicates (one galleryDate → one section); file stays in library/Albums/Videos as before.

- Tests: +12 core unit tests (113 total, all pass locally via tools/core-test.sh):
  * GalleryDateTest (8): boost today+older-capture; no-boost same-day/added-before-today/no-add-date/
    future-capture; zone-correct local date (UTC+13 vs UTC); DATE_ADDED/DATE_MODIFIED unaffected;
    integration — exported video at top of Today, older media unchanged, exactly one entry, settles next day.
  * TransitionScrubTest (4): ripple deterministic forwards==backwards per position; varies monotonically
    (not frozen); different positions render different pixels; SPIN/ZOOM/SHAKE/GLITCH/PIXELATE also vary.
- HONEST / device-check items: core logic + preview/export parity are unit-verified on the JVM. The
  app module (GalleryViewModel, ProjectComposer, FramePreviewRenderer, TransitionBitmap) compiles +
  builds in CI (no Android SDK in the workspace). Needs Shane's on-device confirmation: (1) ripple
  backwards-scrub now shows the correct frame, and the same for other transitions; (2) scrub
  responsiveness on blur-heavy transitions (Blur/Cross zoom) given the CPU render; (3) exported video
  shows at the top of Today with its original capture date in Details.

## 2026-10-31 — Session 34 (v0.4.25-media3) — multiple-transition scrubbing fix
- Shane (Fold5) found a second scrub bug: Ripple at cut 1 + Blur at cut 2; scrubbing back from Blur,
  Ripple persisted across the whole middle clip instead of plain footage between the two transitions.
- Investigated before changing anything. Root cause is in the GPU preview path, NOT the v0.4.24 CPU fix:
  * On a full-effects device simplifiedPreview=false, so the composed CPU frame (v0.4.24) is NOT used on
    the Fold5 during scrub (VideoEditorScreen gate was `simplifiedPreview && !playing && !loading`). The
    Fold5 scrub frame comes from the live CompositionPlayer → ClipTransitionEffect.
  * ClipTransitionEffect (fixedTimeMs=null path) derives clip time from firstUs = "first frame the shader
    instance saw", set once, never reset. Correct for ordered playback/export; wrong under random-access
    seeking. A wrong/negative clip-relative t makes ClipEdge.sampleAt's incoming edge (1 - t/inMs) clamp to
    1 → full incoming transition across the whole clip → Ripple floods clip 2's middle.
  * Needs BOTH multiple transitions AND scrubbing; single-transition and ordered playback/export fine.
- Fix (approved; GPU playback + export untouched): drive the paused/scrub still from the position-accurate
  composed CPU frame on ALL devices when the clip under the playhead is a VIDEO clip carrying a transition.
  * New pure VideoProject.usesComposedTransitionPreview(timelineMs): clip is video && clipEdge.isActive.
  * VideoEditorScreen: new local useComposedAt(pos) = !playing && !loading && (simplifiedPreview ||
    project.usesComposedTransitionPreview(pos)); used for both the composedTick trigger and the render
    gate. Overlay display (composedFrame?.let) was already device-agnostic; banner stays simplified-only.
  * ClipTransitionEffect, CompositionPlayer, ProjectComposer export path, FramePreviewRenderer, TransitionCpu
    all UNCHANGED. Live playback still GPU; export identical.
- Behaviour: transition-free video clips keep the full-res GPU paused frame. Image clips with a transition
  still use the GPU while paused (composed path is video-only) — known limit, logged for a later phase.
- Tests: +6 core (119 total, all pass via tools/core-test.sh). TransitionScrubTest multi-transition suite:
  middle-is-identity regression, neither transition leaks outside its window (10 ms sweep), forward==backward,
  random-access jumps consistent, usesComposedTransitionPreview only for transition-bearing video clips,
  transition-free middle clip keeps GPU preview.
- versionCode 31 / 0.4.25-media3. main/v0.4.15 untouched; permanent key; package/identity/credit/no-INTERNET
  all preserved. CHANGELOG + in-app guide updated.
- HONEST / device-check: core logic unit-verified; app module compiles+builds in CI. Needs Fold5 confirmation:
  (1) Ripple+Blur both confined to their own cuts, plain footage between, backward & forward & jumps;
  (2) scrub smoothness now that transition clips render composed CPU frames on the Fold5 (Snapdragon should
  be fine; 540px cap for blur). Transition-free clips unchanged (still GPU).

## 2026-10-31 — Session 35 (CI release APK filename)
- Restored the historical release filename ProGallery-<version>.apk (e.g. ProGallery-0.4.25.apk).
- CI-only change in .github/workflows/android.yml "Collect results": read versionName from
  build.gradle.kts at build time (not hardcoded), drop the "-media3" suffix, rename out/app-release.apk
  to ProGallery-$VERNUM.apk BEFORE the checksum/signing steps so SHA256SUMS/SIGNING use the new name.
  Debug APKs keep their names and are still dropped in the publish job.
- No app code, versionCode/versionName, package, identity, functionality or signing config changed.
  main/v0.4.15 untouched. versionName stays 0.4.25-media3 -> filename ProGallery-0.4.25.apk.

## 2026-10-31 — Session 36 (v0.4.25.1-media3-diag) — timestamp-mapping diagnostic build
- Shane found a LIVE PLAYBACK bug on the Fold5: seek into the middle of a clip then Play, and the clip's
  incoming transition (Ripple) plays across the whole clip until the next cut. Root cause (confirmed by
  reasoning): ClipTransitionEffect derives clip time from firstUs = first frame the shader instance saw,
  which after a seek is an arbitrary position, so the incoming edge reads active across the clip. Blur at
  the next cut looks right because the NEXT clip's shader anchors firstUs=0 when playback enters it fresh.
- Approved Option 1: confirm the Media3 timestamp mapping BEFORE changing timing. This build is
  DIAGNOSTIC-ONLY — rendering/playback/export behaviour is unchanged (shader still uses firstUs).
  * New TransitionDiag (volatile holder) written each drawFrame: clipIndex, pts(ms), firstUs(ms),
    clipStart(ms, = sum of earlier clips' timelineDurationMs, passed from ProjectComposer.editedItem),
    clipDur(ms), t(firstUs)=pts-firstUs (current), t(calc)=pts-clipStart (proposed).
  * ProjectComposer threads clipStartMs + index into transitionEffect/ClipTransitionEffect (diagnostic
    args only; do NOT affect the effect list's behaviour).
  * VideoEditorScreen Sync-check overlay (long-press timecode) now also shows the TX line, re-read each
    120ms sample tick. Compare: pts vs pos (is pts global output time?), t(calc) vs pos-start (does
    pts-clipStart recover the true clip-relative time?), and watch t(firstUs) go wrong after a seek.
- To read it on the Fold5: open the editor, long-press the timecode to show SYNC, then run the 4 scenarios
  (play from start; seek to mid-clip; play after seeking; cross into next clip). Report the TX numbers.
- versionCode 32 / 0.4.25.1-media3-diag (installs over 0.4.25; same permanent key). CI filename
  ProGallery-0.4.25.1.apk. main/v0.4.15 untouched; export behaviour unchanged. Real GPU timing fix is
  gated on this diagnostic's confirmation.
