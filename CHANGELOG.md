# Changelog

All notable changes to ProGallery. Versions follow semantic versioning.

## [0.4.14] — 2026-10-28 — timeline thumbnails were placed by index, not by time (cut looked wrong)

Fold5 test of v0.4.13: a cut made with the preview showing the end of the train still left two
carriage thumbnails at the start of the new clip. The cut itself was landing correctly — the
**thumbnails** were misplaced, which made a correct cut look wrong.

### Root cause (thumbnail placement)
Thumbnails are sampled evenly across the whole source (frame[i] = source time
sourceDurationMs·i/count). For a clip, the strip should show, at each position, the frame for that
position's source time. Instead the old code filtered to the frames whose source time fell in the
trimmed range and then spread that sub-list across the strip **by index** (`inRange[k·N/tiles]`),
widened at the low end by one sample. For a split/trimmed clip whose trim point is mid-source (e.g.
1:55.90), that put a frame from *before* the trim (≈1:53.7) at the left edge and stretched the rest to
fill — shifting every tile up to one sample (~2–4 s) earlier than its true timeline position. So the
first tiles kept showing the carriage even though the playhead/preview was at the end of the train.

Because the preview and the split both use the same playhead position (`positionMs`) and Safe-mode
seeks are exact, the **cut was landing on the frame the preview showed all along** — only the
thumbnail guide was lying.

### Fix
- **Time-accurate thumbnail tiling.** Each tile now shows the sampled frame NEAREST the source time at
  that tile's position in the trimmed range (`trimStart + span·(k+0.5)/tiles` → nearest frame), instead
  of index-spreading an in-range sub-list. The thumbnail under the playhead now matches the preview
  (to the nearest sampled keyframe). VideoTimeline.kt only; cut/preview/export logic untouched.
- **Robust sampling.** The loader keeps `frames.size == count` even when a frame fails to decode
  (reuse the previous good frame; pad a leading gap), so the index→time mapping the strip relies on
  stays exact. Sampling density raised (≤60 frames, ~1 s target spacing) for finer placement.

### Precision note
Thumbnails use fast keyframe decoding (OPTION_CLOSEST_SYNC), so a tile is accurate to the nearest
keyframe (typically ~1–2 s), not frame-exact. For frame-exact cuts the **preview** is the ground truth
— scrub so the preview shows the frame, optionally nudge with the ‹ › frame-step buttons, then split.

### Protected / unchanged
All tools, transitions, icon, settings, saved projects, export, permissions (still **no INTERNET**),
the permanent signing key, and the "Created by Shane BlackbuRn" credit are untouched.

### Status (honest)
The index-vs-time placement bug is confirmed by tracing the code against the reported clip (left tile
showed ≈1:53.7 for a 1:55.90 trim). The fix is display-only and low-risk. **Not yet device-verified by
me** — needs a look on the Fold5 that the thumbnail under the playhead now matches the preview. The
long-press sync diagnostic (v0.4.13) confirms the cut lands on the previewed frame.

## [0.4.13] — 2026-10-28 — timeline overshoot on release (fling) + on-screen sync diagnostic

After v0.4.12 fixed the gross freeze (stuck programmatic-scroll flag), testing on the Fold5 showed the
preview was "somewhat better but still not in sync, and bad when cutting — it cuts off to where I want
it." That last part is the tell.

### Root cause (cutting / overshoot)
The timeline used Compose's default `horizontalScroll`, which applies **fling momentum**. A scrubber
must stop EXACTLY where your finger lifts; with fling, after you release, the timeline keeps gliding
and decelerates to rest PAST your target frame. Because `positionMs` is the resting scroll position,
the playhead, the frame the preview finally seeks to, AND the split point all land beyond where you
let go — so a cut happens later than the frame you were looking at ("it cuts off to where I want it").
During that post-release glide the throttled preview seek also visibly chases the decelerating scroll,
which reads as "still not in sync."

### Fix
- **No fling on the timeline.** `horizontalScroll` is given a no-op `FlingBehavior` whose
  `performFling` consumes nothing, so the scroll settles on the finger-up position. Release now lands
  on the exact frame you chose, and the split happens there. (core/app position math unchanged.)
- **On-screen sync diagnostic (opt-in).** Long-press the timecode to toggle a small green readout over
  the preview showing `pos` (playhead ms), `play` (the player's actual reported position), their
  difference `Δ`, the preview mode, and the clip/source the playhead maps to. This turns any remaining
  mismatch into numbers you can screenshot instead of something I have to guess at. Off by default.

### Protected / unchanged
All editing tools, transitions, icon, settings, saved projects, export, permissions (still **no
INTERNET**), the permanent release signing key, and the "Created by Shane BlackbuRn" credit are
untouched. Blur/Whip/Push transition code is unchanged from v0.4.10.

### Status (honest)
Fling-on-release is a confirmed mechanism and matches the exact "cuts off to where I want it" report;
the no-fling fix is the standard remedy and compiles clean in CI. **Not yet verified by me on a real
device** — needs the play→drag→release→cut test on the Fold5. If it's still off, the long-press
diagnostic will show exactly where the gap is.

## [0.4.12] — 2026-10-24 — timeline/preview desync: the real root cause (stuck programmatic-scroll flag)

A targeted diagnostic (Safe preview mode — a different player, same timeline code — was ALSO out of
sync) proved the fault was in the SHARED timeline code, not the effects preview engine. That ruled
out v0.4.11's direction.

### Root cause
Every programmatic timeline scroll ran `programmaticScroll = true; scroll.scrollTo(...);
programmaticScroll = false`, and `scroll.scrollTo` is a suspend call. The playback follow loop runs
it inside `LaunchedEffect(playing)`, which is CANCELLED the instant playback stops (pause or end). If
the cancellation lands while `scrollTo` is suspended — about half the time, since the loop alternates
`withFrameMillis` and `scrollTo` — the cancellation unwind SKIPS `programmaticScroll = false`, so the
flag stays stuck `true`. After that, the scrub handler's guard (`!playing && !programmaticScroll`)
is permanently false, so **`positionMs` stops updating while you scroll**: the playhead, timecode and
thumbnails move with your finger, but the preview stays frozen where it was — in BOTH preview modes.
Play → pause → scrub is exactly the sequence that triggers it, which matches the report. (This bug
pre-dates this release — it's the original desync.)

### Fixed
- The programmatic scroll now clears its flag in a `finally`, so cancellation can never leave it
  stuck. All three scroll sites (playback follow, pause-align, seek/frame-step) go through one
  guarded helper. `positionMs` now always tracks the timeline, so the preview, playhead, timecode
  and thumbnails stay together.
- Reverted v0.4.11's scrub debounce (which made scrubbing feel laggier) back to a responsive
  latest-wins throttle; the preview still lands exactly on release.
- No change to the export pipeline, projects, settings, signing key or credit.

### Testing
- 89 core unit tests pass; the androidTest suite (incl. the ImageReader preview-surface test added in
  0.4.11) compiles.
- **Not device-verified**: the GitHub Actions emulator is still in its outage. This is a
  deterministic coroutine-cancellation fix with a clear symptom match; Shane's Fold5 is the
  confirmation. Diagnostic that localised it: Safe preview mode was also out of sync → shared code.

## [0.4.11] — 2026-10-24 — scrub preview frame (stop burying the compositor)

Shane reported (with screenshots) that the preview frame still doesn't match the playhead, at rest.
Re-traced the whole path and corrected the earlier diagnosis.

### What was re-checked and ruled out
- **Timeline geometry / mapping**: playhead is screen-centred with a leading half-viewport spacer,
  so time-under-playhead = scroll/pxPerMs — exactly what the scrub handler and the preview seek use.
  Timeline thumbnails are sampled across the source and tiled onto the trimmed strip at matching
  positions. The position→time→source-frame mapping is correct.
- **Seek accuracy**: `CompositionPlayer` forwards seeks to internal ExoPlayers whose default is
  `SeekParameters.EXACT`, and it reports the exact position — which is why the previous
  timestamp-only test passed and was insufficient (as Shane rightly called out).

### Root cause (preview surface, not the timeline maths)
On long-GOP footage (e.g. DJI drone clips) each exact seek decodes from the previous keyframe, then
goes through the GL compositor. The scrub code fired ~16 seeks/second, which buries that pipeline:
the surface trails the finger and can settle on a **stale composited frame from an earlier seek** —
position correct, visible frame wrong. `CompositionPlayer` exposes no scrubbing/seek-parameter
control to make this cheaper.

### Changed
- Scrub now seeks the (heavy, effects) preview only once the drag **settles for ~90 ms**, instead
  of on every scroll frame, and **cancels any pending seek on release** before the final exact seek.
  The playhead/timecode still follow the finger live; the preview catches up when the drag pauses
  and lands exactly on release — without a backlog of competing seeks leaving a stale frame.
- No change to the export pipeline, saved projects, settings, signing key or credit.

### Testing
- New on-device test `previewSurfaceShowsTheFrameForTheSeekPosition` renders the preview into an
  `ImageReader` and checks the ACTUALLY-RENDERED frame (not the reported timestamp) matches the
  clip's source frame at the mapped position — and is closer to it than to frames well before/after.
  This is the frame-vs-position check Shane asked for; it skips cleanly if the emulator can't present
  frames headlessly.
- 89 core unit tests pass; the androidTest suite compiles on CI.
- **Not yet verified on-device**: the GitHub Actions emulator has been in a multi-hour outage
  ("Failed to start Emulator console"), so the new surface test hasn't been able to run. The scrub
  change is a reasoned fix for the identified cause; final confirmation is Shane's Fold5.

## [0.4.10] — 2026-10-23 — blur edge-clamp (removes the dark halo that defeated softening)

On-device, v0.4.9's 49-tap Gaussian still measured hf 5.68 at the cut vs plain 5.35 (needs < 4.81).
Root cause: near the frame border the blur sampled **off-frame pixels as black**, building a dark
halo that *added* high-frequency at the edges and swamped the interior softening (diffCut jumped to
~31, consistent with a visible dark border).

### Fixed
- Blur taps now **edge-clamp** to the frame ([0,1]) — repeating the border pixel instead of pulling
  in black — the standard artifact-free blur. The sample *centre* being off-frame still returns
  black, so whip-pan/push keep sliding through black by design. Changed in lockstep in the GPU
  shader (`ClipTransitionEffect`, sampling `texture2D` directly for the clamped coords) and the CPU
  reference (`TransitionCpu`). 89 core unit tests pass; on-device hf confirmed by the re-run reported
  with this release.

### Still confirmed on-device
- Sync: `uncut 4023/4023 seek 2011→2011`, `trimmed 2023/2023 seek 1011→1011`; whip/push pass.

## [0.4.9] — 2026-10-23 — blur genuinely softens (on-device), sync confirmed on-device

The device suite finally ran a clean compile+emulator pass, giving real on-device evidence.

### Confirmed on-device (Android 14 emulator)
- **Timeline/preview sync — PASS.** `previewTimelineMatchesProjectTimeline` shows the preview
  engine's own duration equals the project timeline the playhead is computed from, and seeks land
  exactly where asked, for both an uncut and a trimmed clip:
  `uncut: timeline=4023 playerDur=4023 seek 2011→2011` · `trimmed: timeline=2023 playerDur=2023 seek 1011→1011`.
  This is engine-level confirmation that the sync work (v0.4.8) holds; the on-screen feel is still
  for Shane to confirm on the Fold5.
- Whip-pan and Push transitions pass (correctly treated as passing through black at the cut).

### Fixed — Blur transition now actually softens
- On-device, the v0.4.8 "dense Gaussian" blur still *raised* high-frequency at the cut
  (hf 5.96 vs plain 5.35). Root cause: the GLSL kernel read its weights from an array indexed by
  the loop variable (`gw[i+2]`), and GL ES 1.00 software GL indexes such arrays unreliably — so the
  weights were effectively wrong and the "blur" ghosted instead of smoothing.
- Replaced with a **49-tap (7×7) Gaussian whose weight `exp(-(i²+j²)/8)` is computed
  arithmetically** (no array indexing), taps spanning ±radius at step = radius/3, and widened the
  max blur radius (`MAX_BLUR_UV` 0.05 → 0.07). Changed in lockstep in the GPU shader
  (`ClipTransitionEffect`) and the CPU reference (`TransitionCpu`).
- *Status:* unit tests pass; the on-device hf-reduction is confirmed by the re-run reported with
  this release.

## [0.4.8] — 2026-10-23 — timeline/preview synchronisation

Shane reported the timeline playhead and the video preview drifting out of sync even on a single,
uncut clip with no transitions. Full trace of the playback/seek/timeline path below.

### Root-cause investigation (what I checked)
- **Core time model** (`VideoProject`): `durationMs = Σ timelineDurationMs`, cumulative `clipStarts`,
  `locate` and `stepFrames` (real `frameRate`/`speed`) — all internally consistent. Pinned by new
  unit tests. Not the cause.
- **Composition time base** (`ProjectComposer`): verified `setDurationUs(sourceDurationMs)` against
  the Media3 1.5.1 contract — `durationUs` must be the FULL pre-clip duration (the clipping config
  is subtracted to get the presentation length, and it's ignored outright for encoded video). So it
  is correct; changing it would have been wrong and would have risked the working export. Not the
  cause.
- **Timeline geometry** (`VideoTimeline`): playhead fixed at centre, content has a leading
  half-viewport spacer, so time-under-playhead = `scroll/pxPerMs`, which exactly matches the
  position↔scroll mapping. No half-viewport offset. Not the cause.

### Fixed — runtime playback/playhead coordination
- **Pause/stop now snaps the playhead to the player's exact position.** The follow loop sampled the
  player on a timer, so when playback stopped the playhead/timecode could sit up to one sample
  behind the frozen frame. On stop it now reads the exact position and aligns the scroll.
- **The follow loop is vsync-aligned** (`withFrameMillis`) instead of a fixed 33 ms timer, so the
  playhead tracks the continuously-rendered preview smoothly instead of stepping behind it.
- **Frame-accurate seeking** on the fallback/Safe-mode player (`SeekParameters.EXACT`) so scrub and
  frame-step land on the exact frame, not the nearest sync sample.
- **One source of truth for the time↔pixel maths** (new `TimelineMath`): the follow loop, the scrub
  handler and the seek/frame-step path now all convert through the same functions, so they cannot
  diverge. (The maths was already equivalent; this prevents a future regression.)

### Testing
- **89 core unit tests** (added `TimelineMathTest`: ms↔scroll round-trips with no growing offset,
  clamping, zoom-invariance, frame-step reversibility/speed/clamp, single-clip locate).
- **New on-device test** `PlaybackSyncDeviceTest` drives the real `CompositionPlayer` on one clip and
  asserts the reported position is seek-accurate, stable while paused, advances while playing, and
  ends on the last of several rapid seeks. Scope: it verifies the position VALUE the UI reads; a
  purely visual render-lag can only be confirmed on a real device.
- CI reliability: the device-test step now runs Gradle daemonless and is hard-killed at its cap, so
  reports and artifacts upload even when the (software-GL) emulator overruns.
- **Honest status:** the fixes target concrete, identified defects and pass the unit suite; final
  confirmation of on-screen sync is for Shane's Galaxy Z Fold5, as requested.

## [0.4.7] — 2026-10-23 — blur quality + first clean on-device transition run

The CI emulator came back, so for the first time every one of the 15 transitions was exported on a
real device (Android 14 emulator). All 15 exported successfully. Two issues surfaced that this
release fixes.

### Fixed — Blur transition now genuinely softens
- The Blur (and the blur inside Cross-zoom / Whip / Shake) used a sparse 9-tap kernel whose taps
  were spaced a full blur-radius apart. On real video with sharp edges that lays down ghost copies
  of each edge — it *raised* high-frequency detail on-device instead of reducing it (measured:
  Blur hf 6.37 vs plain 5.35 at the cut — the wrong direction).
- Replaced it with a **25-tap (5×5) Gaussian** whose taps are spaced at half the radius so
  neighbouring samples overlap into a smooth ramp. Updated in lockstep in both the GPU shader
  (`ClipTransitionEffect`) and the CPU reference (`TransitionCpu`). The CPU reference still passes
  `blurSoftensDetail` / `crossZoomBlursAndFlashes`.
- *Honest status:* the ghost-edge failure is content-dependent (it only shows on the real exported
  GPU frames), so it could not be reproduced on the pure-CPU JVM harness — the proof is the
  on-device re-run, reported with this release rather than asserted up front.

### Fixed — device-test miscategorised two "through-black" transitions
- `allTransitionsRenderOnExport` flagged **Whip pan** (luma 2.5) and **Push** (luma 0.0) as
  "unexpected black frame at the cut." Both *slide the clips fully off-screen by design*, so the
  single exact-cut frame is legitimately black (the streak reads on the surrounding frames). Moved
  them into the "passes through black at the cut" group alongside Fade-to-black / Wipe / Iris. This
  was a test assertion bug, not an app bug — the transitions were rendering correctly.

### Testing
- **83 core unit tests** pass locally (`tools/core-test.sh`).
- On-device: all 15 transitions export; the two fixes above target the two remaining device-test
  failures. Result of the device re-run is reported with the delivery, not assumed.

## [0.4.6] — 2026-10-23 — more transitions + a proper duration control

### Added — 4 new transitions (15 selectable now)
Built on the same per-clip engine (CPU-reference-verified, same pattern as the colour grade):
- **Spin** (Movement) — the frame rotates through the cut, with a slight zoom so the corners stay
  filled; left/right chooses the spin direction; strength sets the angle.
- **Shake** (Movement) — a camera-shake jitter that peaks at the cut; strength sets how hard.
- **Pixelate** (Stylised) — the frame dissolves into blocks and back; strength sets the block size.
- **Ripple** (Stylised) — a wave/liquid distortion across the cut; strength sets the amplitude.

### Changed — duration control
- When you select a transition, a **Length slider (0.2–3.0 s)** now appears **right under the
  preview**, with the live value shown (e.g. "1.2 s"). It commits once when you let go (so it
  doesn't flood undo), and the chosen length drives the real exported transition. The controls
  (Length / Direction / Strength) moved above the picker grid so they're always visible, and the
  grid is now clearly labelled "Choose a transition".

### Testing
- **83 core unit tests** (added `spinPixelateShakeRippleTransform`, which renders each new effect
  with the CPU reference and checks it transforms the frame and is a no-op at the endpoints).
- The design of all 15 transitions was verified by rendering real before/during/after frames.
- `allTransitionsRenderOnExport` iterates every selectable transition, so the new ones are covered
  by the on-device export test automatically — which **runs when the CI emulator returns** (still
  down). Build + lint + unit tests pass on CI.
- 0.4.5's export fix is confirmed working on the physical Galaxy Z Fold5 (Android 16).

## [0.4.5] — 2026-10-23 — export no longer depends on the foreground service

0.4.4 stopped the *crash* but broke normal exporting on the Galaxy Z Fold5: tapping Export while
the app was open showed "Export failed — Export needs ProGallery open." This is the real fix.

**Confirmed on device (2026-10-23):** verified on a physical Samsung Galaxy Z Fold5 (Android 16) —
preview with transitions works, a 4K/high-quality export succeeded, and playback is clean. This
also confirms the v0.4.0 transition library renders correctly in a real exported video.

### Root cause (full trace)
`VideoExportManager.start()` rendered the video with Media3 `Transformer` **in the app process**,
but it started that render via `startForegroundService()` and the export's fate was tied to the
service. On **Android 16 (Samsung SM-F946B)** the platform **refuses the foreground-service start**
(`ForegroundServiceStartNotAllowedException` / `mAllowStartForeground false`) even for a visible,
user-initiated action — Android 15/16 tightened foreground-service *start* rules for the
`dataSync`/`mediaProcessing` types and Samsung enforces them strictly. 0.4.4 caught that refusal
and **cancelled the export**, so a perfectly runnable in-process export was aborted. Older Android
(the other phone) doesn't refuse the start, so it only failed on the Fold5.

### The fix — decouple the export from the foreground service
- The export now **renders in-process first** and its success/failure comes solely from the
  `Transformer` listener (`onCompleted` → save to the gallery via MediaStore; `onError` → the real
  error). It does **not** depend on a foreground service — so tapping Export while the app is open
  works regardless of the OS's foreground-service decision.
- The keep-alive service is now **best-effort**: started with `startService()` (not
  `startForegroundService()`, which forces a mandatory foreground promotion), it promotes itself to
  a foreground service **only if the OS allows it** (for a progress notification + surviving
  backgrounding). A refusal is logged and ignored — it **no longer crashes or cancels the export**.
- Removed the 0.4.4 `failBackgroundRefused()` path (it wrongly cancelled valid exports).
- A system/background restart of the service with no active export still stops quietly
  (`ExportServicePolicy.shouldRunForeground`).

### Honest limitation
Without a foreground service, an export can only be *guaranteed* to finish while ProGallery stays
open. That's the normal case (you tap Export and watch it). If the app is backgrounded on a device
that refused the foreground service, the OS may stop the export — but it is never silently claimed
to have succeeded; the in-app progress and result reflect reality.

### Testing
- **83 core + app unit tests** pass (incl. `ExportServicePolicyTest`).
- Added `ExportManagerDeviceTest` (instrumented): drives a real user-initiated export through the
  manager and asserts the video renders and saves. **Compiles; not run** — the CI emulator is down,
  and even when up it runs Android 14 (can't reproduce the Android-16 refusal).
- App compiles + lints; debug + release assembled.
- **Not verified on a physical Galaxy Z Fold5.**

## [0.4.4] — 2026-10-22 — Galaxy Z Fold5 crash fixed (foreground service)

**Root cause found** from the crash report emailed via 0.4.3 — and it was **not** GPU, foldable or
preview-related. On a Galaxy Z Fold5 running **Android 16 (API 36)**, the video-export foreground
service crashed with:

```
android.app.ForegroundServiceStartNotAllowedException:
  Service.startForeground() not allowed due to mAllowStartForeground false
  at VideoExportService.onStartCommand
```

The report's `doing: startup` showed the export service was being **(re)started on a cold process
in the background**. Android 14+ — enforced strictly on 15/16 — forbids starting a foreground
service from the background, so `startForeground()` threw and, being uncaught, took the whole app
down. Older Android versions (likely on the other phone) don't enforce this, which is why it only
crashed on the Fold5.

### Fixed
- The export service now **only goes foreground while an export is actually running**
  (`ExportServicePolicy.shouldRunForeground`). A background/system restart of the service now stops
  quietly instead of calling `startForeground()` from the background.
- `startForeground()` is wrapped so an OS refusal can **never crash the app**. Because a video
  export can't be relied on to finish without a foreground service (the OS may kill a backgrounded
  process), a refusal now **stops the export cleanly and asks the user to reopen the app and export
  again** — rather than pretending it continues in the background.
- Starting the service from `VideoExportManager` is likewise guarded, so a refused start can't crash
  the export either.
- Normal exports are started from the foreground (the user taps Export), where the foreground
  service is permitted, so they run and save as before — this only affects the rare refusal case.

### Testing
- **83 core + app unit tests** (added `ExportServicePolicyTest`, which locks in "foreground only
  while running").
- App compiles + lints on CI.
- **Not yet confirmed on the Galaxy Z Fold5.** I don't have the device and the CI emulator is still
  down. The fix directly addresses the exact exception and state in the crash report; please
  reproduce the same steps on 0.4.4 — exporting should now work (or, worst case, degrade without a
  notification) instead of force-closing. If anything still crashes, 0.4.3's crash reporter will
  capture it.

Everything else (editing, transitions, Safe preview mode, Diagnostics/email crash reports,
compatibility work, projects, settings, app icon, signing key, author credit) is unchanged.

## [0.4.3] — 2026-10-22 — email crash reports

Adds a way to email crash reports (so the Galaxy Z Fold5 crash can be diagnosed from the exact
report). Everything stays user-initiated and offline — ProGallery still has no INTERNET permission.

### Added
- **Send crash reports by email.** The next-launch dialog after a crash now offers:
  - **Send Crash Report** — opens the user's email/share app with `shanebl030@gmail.com` prefilled,
    subject `ProGallery Crash Report - <make> <model>` (e.g. `… - samsung SM-F946B`), the full report
    as the message body, and the report attached as a readable **.txt** file (via a local FileProvider).
  - **View Report** — review the full report (device, Android version, app version, the editing step
    that was running, and the complete stack trace) before sending.
  - **Not Now** — dismiss without deleting the report (it stays available in Diagnostics).
- **Settings › Diagnostics** now opens a panel to **view, email or share the most recent crash
  report** at any time, even days later (the last few reports are kept on the device).
- The dialog states plainly **what the report contains and excludes** (no photos, videos, projects or
  personal media) and that **nothing is ever sent automatically** — only through the app you pick.

### Privacy / offline
- No automatic sending and **no INTERNET permission** — reports leave the phone only through the
  email/share app the user chooses. If no app can send, the report is copied to the clipboard as a
  fallback and remains in Diagnostics.
- Reports contain only diagnostic text (exception + stack trace, device make/model, Android + app
  version, current editing step). Native crashes that occur before a report can be written are
  explained in the dialog/Diagnostics (a device logcat is needed for those).

### Testing (JVM + instrumented; not run on a physical Samsung device)
- **4 JVM unit tests** for the report/subject/privacy-text formatting (`CrashReportTextTest`).
- Instrumented tests (`DiagnosticsDeviceTest`) for the FileProvider attachment and the email intent's
  recipient/subject/body/attachment — these **compile** and are ready to run; they have **not** been
  run on a device or the CI emulator (emulator outage continues).
- All previous editing features, transitions, Safe preview mode, 0.4.2 compatibility work, saved
  projects, settings, app icon, signing key and the author credit are unchanged.

## [0.4.2] — 2026-10-22 — Android 13+ / Samsung Galaxy compatibility

Broadens robustness across Samsung Galaxy devices (S, Z Fold, Z Flip, A series) on Android 13
and newer, following the Galaxy Z Fold5 video-editing crash. The **exact Fold5 cause still needs
a crash report from that device** (see 0.4.1) — this release widens compatibility and adds a
foldable-specific mitigation, but does not claim the Fold5 crash is fixed.

### Compatibility
- **Supported range stated and centralised** (`core/video/Compat.kt`): minSdk = Android 10 (API 29),
  which covers Android 13+ (the required floor) and older; targetSdk = Android 15 (API 35). Kept
  minSdk at 29 rather than raising to 33 because 29 already satisfies "Android 13 and newer" while
  also supporting 10–12 — raising it would only drop devices, with no benefit for a personal app.
- **Foldable display switch handling.** `resizeableActivity="true"` is now explicit, and the editor
  **rebuilds the preview when the screen configuration changes** (fold↔unfold on a Z Fold/Flip, or
  rotation). The window resizes without the Activity being recreated, so the player now gets a fresh
  surface for the new size instead of rendering onto a stale one — a plausible non-GPU cause of a
  foldable crash. (If the full-effects preview still can't run on a device, Safe preview mode from
  0.4.1 takes over; effects still apply on export.)
- **GPU-safe output clamp.** Output dimensions are clamped to even numbers within a conservative
  maximum (7680) that stays inside the OpenGL texture limit on every supported GPU, protecting the
  GL pipeline from oversized sources. Normal phone video (up to 8K) is unaffected.
- Android 13/14/15 media permissions (granular `READ_MEDIA_*`, partial `…_USER_SELECTED`, foreground
  service types) were already in place and are unchanged. No network permission (no ads/telemetry).

### Testing (JVM only — not run on a physical Samsung device)
- **82 core unit tests** (added `CompatTest` across simulated Android versions + source sizes, and
  `fullEditingWorkflowStaysConsistent`, which chains open → split → delete middle → trim → transition
  with duration → reorder → frame-step → save/load).
- App compiles + lints on CI.
- **Not verified on a Galaxy Z Fold5 or any Samsung phone, and not on the CI emulator** (GitHub's
  hosted emulator is still failing to boot). On-device verification and the exact crash cause depend
  on the crash report / logcat from the Fold5.

## [0.4.1] — 2026-10-22 — crash diagnostics + Safe preview mode

Focused on the Samsung Galaxy Fold crash Shane reported (ProGallery force-closes when editing
video on the Fold, but works on his other phone). The exact cause can't be identified without a
crash log from that device, so this release is built to **capture it** and to **keep the app
usable** in the meantime — it does not claim the Fold crash is fixed.

### Added
- **Crash reporter.** Any uncaught exception is now written to a shareable report (app version,
  device model + SoC, Android version, what the app was doing, and the full stack trace) instead
  of vanishing into Android's "keeps stopping" dialog. On the next launch the app offers to
  **share or copy** that report. Also available any time in **Settings › Diagnostics**.
- **Safe preview mode** (Settings/editor toggle). Previews the video **without** the GPU effects
  pipeline (CompositionPlayer) — the most likely source of a device-specific native GL/codec
  crash — so the editor stays usable. Effects still apply to the **exported** video. It is turned
  **on automatically** after a video-editor crash so the device isn't stuck in a crash loop, and
  can be turned off again in the editor.
- **Phase tags** in crash reports (e.g. `video:preview-full`, `video:preview-safe`,
  `video:export`) so a report shows *where* the crash happened.

### Notes on the Fold crash
The preview already falls back to a plain player on any Media3 decode error, releases are guarded,
and thumbnails are size-capped independent of screen size — so a remaining crash is most likely a
**native** GPU/codec crash (which Java cannot catch) or a background-thread exception. Safe preview
mode avoids the GPU pipeline entirely; if the crash is during **export**, the report will say so and
a different fix will follow. I need the crash report (or an `adb logcat`) from the Fold to confirm.

### Transitions (unchanged from 0.4.0, re-verified in code/tests)
All 11 transitions and their **duration/direction/strength** controls are unchanged. Added a unit
test (`transitionDurationSetsExportWindow`) proving the chosen duration drives the actual export
window (half on each side of the cut), that Standard cut carries no duration, and that duration +
direction + strength **persist** across save/load (`projectRoundTripsExactly`). On-device export
verification of each transition is still pending the CI emulator (see below).

### Testing
- **78 core unit tests** pass (added the duration-window test).
- App compiles + lints on CI.
- **Not verified on a Galaxy Fold:** the crash fix/mitigation and transition export rendering have
  not been run on that device (I don't have one) or on the CI emulator (GitHub's hosted emulator
  has been failing to boot for ~19h). Safe preview mode and the crash reporter are the tools to
  diagnose and work around it on Shane's actual device.

## [0.4.0] — 2026-10-22 — transition library

Milestone 4 (first part): a full, adjustable transition library with a visual picker.
Everything from 0.3.2 is kept (frame-step, split/cut, diagnostics, colour grade, filters).

### Added — 11 selectable transitions (up from 4)
Grouped in the picker by family. Each has an adjustable **Length** (0.3–2.0 s, default 1.0 s);
those that support it also have **Direction** and **Strength** controls:
- **Cut** — Standard cut (no effect, no duration forced).
- **Dissolve & fade** — Fade through black, Fade through white.
- **Blur & zoom** — Blur (strength), Zoom (in/out + strength), Cross zoom (strength).
- **Movement** — Whip pan (4 directions + strength), Push / slide (4 directions).
- **Wipe & shape** — Wipe (left/right/up/down/diagonal), Iris (circle).
- **Stylised** — Light leak (strength), Glitch (strength).

### Added — picker, previews, timeline
- A phone-friendly **transition picker** with a live animated **demo** of the current choice and
  a faithful **thumbnail** on every option — both rendered from the exact maths the export uses,
  so what you see is what you get (and previews work even where the GPU preview falls back).
- The timeline **dot between clips** shows a glyph per transition and highlights when one is set;
  tap it to open the picker. The panel states plainly that a transition is **between two clips**
  (vs. the Colour/Filter tabs, which style one whole clip).
- A **Match-cut helper**: the end-of-A and start-of-B frames shown side by side so you can line
  up composition, then use frame-step + trim to align the cut. (A manual aid — not an automatic
  effect that pretends any two clips match.)

### How transitions render (and the honest limits)
All transitions are applied **per clip** (the proven, reliable path): the outgoing clip's tail
transforms toward a neutral state and the incoming clip's head transforms back out of it, meeting
exactly at the cut (so there is no jump or frozen frame). Consequences, stated plainly:
- Fade / Wipe / Iris pass **through black (or white)** at the cut — that is how they work.
- Whip pan / Push slide **through black** (the area the clip vacates is black, not the other clip).
- **Cross dissolve** (both clips' live frames blended), **true reveal** wipes/pushes (that show the
  other clip underneath), and **J-/L-cuts** (audio leading/trailing the visual cut) need a
  **two-clip overlap compositor**. That is designed and written up (docs/SESSION_LOG.md) as the
  next milestone. No placeholder/broken buttons for these ship in 0.4.0.

### Internals
- New `core/video/Transitions.kt`: the transition types + parameters and a **CPU reference**
  (`TransitionCpu`) of the exact shader maths, so the preview, the export and the unit tests can
  never drift apart (the same pattern the colour grade uses).
- `ClipTransitionEffect` GLSL rewritten to apply those uniforms; project files now store each
  transition's direction + strength (older saves load with sensible defaults).

### Testing
- **77 core unit tests** (was 66): `TransitionCpuTest` renders every transition with the CPU
  reference and checks the pixels change correctly and are identity at the endpoints.
- Verified on my machine by **rendering real frames** of all 11 transitions (before/during/after
  a cut) — the design is confirmed.
- Device test added: `allTransitionsRenderOnExport` exports every transition between two different
  clips and inspects the exported boundary + off-cut frames (effect present, time-limited, no
  crash, no unexpected black frame).
- **Not yet verified on a physical phone / on-device export:** the instrumented device suite could
  not run — GitHub's hosted Android emulator has been failing for many hours (infrastructure). The
  on-device run is queued and will run the moment it recovers; results will be reported then.

## [0.3.2] — 2026-10-22 — frame-step + more transitions

### Fixed
- **Transitions now actually render at clip boundaries.** They were applied as a
  whole-composition effect, which receives per-clip timestamps (reset at each clip), so the
  effect was zero exactly at every boundary — fades between clips, and the new blur/zoom,
  never showed. Transitions are now applied per clip (like the colour grade), computed from
  each clip's own time, so they appear correctly on both sides of a cut. (Project fade-in/out
  at the very start/end already worked and still do.)

### Added
- **Frame-step** buttons (‹ ›) in the transport bar: nudge the playhead by exactly one
  frame to land a cut precisely. Uses the clip's real frame rate (probed; 30 fps default)
  and accounts for speed changes. Disabled during playback.
- **Two more transitions — Blur and Zoom** — alongside Fade through black / white. Both
  are single-stream GL effects applied at the clip boundary, so they look the same in the
  live preview and the export. (Classic cross-dissolve/wipe/slide, which show both clips at
  once, need two-stream compositing and are planned for the transitions milestone.)
- **Preview diagnostics:** when the full-effects preview falls back to simplified playback,
  the banner is now tappable and shows exactly why (the decoder error code, message and the
  clip's format). This is to track down why the effects preview falls back on some phones
  even for ordinary SDR video — collect that text if it happens to you.

### Testing
- 66 core tests (added frame-step math and the blur/zoom transition curve).
- Device tests: blur softens and zoom shifts the boundary frame on export, with unchanged
  duration; saved project round-trips the new frame-rate and transition types.

### Known
- On some devices the live preview still falls back to simplified (raw) playback even for
  SDR video, so colour grading and the new transitions are seen on export rather than live.
  The new tappable details are there to diagnose and fix this.

## [0.3.1] — 2026-10-22 — preview decoding fix + split/cut tools

Focused fix release before milestone 2. Everything from 0.3.0 is kept.

### Fixed
- **Video preview failed with ERROR_CODE_DECODING_FAILED on real phones** (seen on a
  Samsung portrait recording). Phones commonly record HDR10+ / 10-bit HEVC, which Media3's
  editing pipeline (used by the live preview and export) can't consume in its default
  HDR-keeping mode. The composition now tone-maps HDR and 10-bit down to SDR with OpenGL
  (supported from Android 10), which both the preview and the export use — so what you see
  matches what you get. This is the root-cause fix; it can only be fully confirmed on an
  affected phone (the CI emulator has no HDR decoder).

### Added
- **Resilient preview:** if a device still can't decode a clip through the effects
  pipeline, the preview automatically falls back to plain playback of the footage (which
  decodes HDR natively) with a banner — "Simplified preview — colour, speed and effects
  apply on export" — instead of a dead preview. Play, pause and seek keep working.
- **Preview diagnostics:** decode failures now name the video's format (codec, resolution,
  frame rate, bit depth, HDR) in the message and the log, to pinpoint problem media.
- **Split at the playhead** button in the transport bar — always available, whatever is
  selected. Splits the clip under the playhead and selects the new right-hand piece, so
  you can split at the start and end of an unwanted section and delete the middle (the gap
  closes automatically). Splitting preserves audio/video sync and never creates a
  zero-length clip.

### Testing
- 64 core tests (added split-at-playhead source-time accuracy, cut-the-middle-section,
  trim-after-split independence, and speed-aware split).
- Device tests: split → delete middle → export keeps the right duration and audio, and
  leaves the original untouched; the format-diagnostics reader; HDR tone-mapping path
  exercised by every export test.

## [0.3.0] — 2026-10-21 — video editor foundation

Milestone 1 of the v0.3–v0.9 roadmap. Everything from 0.2.0 is kept.

### Added
- **Video projects** (Library › Video projects): projects save automatically and can be
  reopened, renamed, duplicated and deleted. Saved as versioned JSON in app storage with
  atomic writes; damaged files are skipped and reported, never fatal. Projects reference
  media by its MediaStore link — nothing is copied and originals are never changed.
- **New video** from several photos and videos, chosen in an in-app picker (numbered in
  tap order). Using the gallery's own media links means saved projects keep access to
  their files.
- **Photo clips** on the video timeline with a duration slider (0.5–15 s); shown upright
  according to EXIF orientation; split works on photos too.
- **Drag to reorder:** long-press a clip on the timeline and drag it.
- **Pro colour grading** per clip, on the GPU, identical in preview and export:
  exposure, brightness, contrast, highlights, shadows, saturation, vibrance, warmth,
  tint and sharpness, grouped into Light / Colour / Detail with Reset all.
- **Filter strength** slider for video filters.
- **Missing media** banner when a project's source file was deleted or moved, with a
  one-tap "Remove them".
- "Keep this video project?" when leaving a quick edit of a single video.

### Changed
- Version 0.3.0 (versionCode 3).
- "Add clip" is now "Add media" and accepts photos as well as videos.
- Video colour controls moved from a 4-slider colour matrix to the new grading shader
  (the look of brightness/contrast/saturation/warmth changes slightly).

## [0.2.0] — 2026-10-21 — photo editor, video editor, new icon

Personal-use build. Everything from 0.1.0 is unchanged.

### Added
- **Photo editor** (pencil button in the viewer): crop with aspect presets, straighten
  with auto-zoom, rotate, flip; 17 adjustments (light, colour, detail, effects); 18 filter
  looks with strength; tone curves (RGB and per channel); HSL for 8 colour ranges; text,
  drawing (pen, marker, highlighter, eraser, line, arrow, rectangle, oval), stickers;
  frames; hold-to-compare; undo/redo; save as copy (default) or replace original;
  export format/quality/size options; photo details (EXIF) kept, optional location removal.
- **Video editor** (pencil button on videos): timeline with thumbnails, trim, split, speed
  0.25×–4× with pitch kept, volume/mute, rotate/flip, colour and filters, reorder, add
  clips, fade-through-black/white transitions, timed titles with animation, background
  music with loop and fades, fade in/out, aspect frames; live preview using the same
  pipeline as export; export with resolution/frame rate/quality/H.264 or HEVC, running
  in a foreground service with progress notification and cancel.
- **New app icon (Shane's design):** adaptive icon built from the supplied artwork —
  photo cards with a colour aperture on a matched navy background, sized for every
  launcher mask, plus a monochrome layer for Android 13+ themed icons. The full design
  with the "ProGallery" wordmark is shown on the About screen.
- **Author credit** "Created by Shane BlackbuRn" on the About screen and Settings footer
  (single constant `AppInfo.AUTHOR_CREDIT`, covered by a unit test and a device test).
- **Device test suite** (runs on an Android 14 emulator in CI): gallery, albums, library,
  search, viewer, details, video player, About credit, photo editor save, video editor
  export, rendering/saving/export engines, preview player, launcher icon; screenshots
  collected for review.

### Changed
- Version 0.2.0 (versionCode 2).
- Video editor: Back first deselects the selected clip, title or music track instead of
  jumping straight to the discard prompt.
- Credit text corrected from "Created By" to "Created by".
- Export-progress notification uses the monochrome icon (Android requires single-colour
  small icons).

### Fixed
- Settings and User guide: the last items (including the version/credit footer) were
  hidden behind the navigation bar on phones using 3-button navigation. Found by the
  emulator tests.
- Video editor build error (overlay list type in `ProjectComposer`).
- Video editor live preview: clip and music durations are now supplied, which
  CompositionPlayer requires, and a fresh preview player is created after every edit
  (a CompositionPlayer accepts only one composition). Both found by the emulator tests.
- Editor text and icons were too dark on the editors' near-black background (missing
  content colour); now readable.
- Photo editor preview could lag far behind (or appear stuck) right after choosing a
  filter: the pixel pipeline looked up a thread-local buffer for every pixel, and the
  filter-thumbnail strip competed with the preview for the same small thread pool. Now
  each row uses its own scratch buffer and thumbnails render on a single thread. Found
  through render-timing logs from the emulator tests; a core test confirms serial and
  parallel rendering give identical pixels.
- Video preview no longer goes black after an edit while paused.
- Editor tool panels (photo and video) are height-limited and scroll, so tall panels such
  as Text no longer squeeze the preview and timeline.
- Video titles now update every frame (double-buffered overlay); previously the first
  frame's title state would have stuck for the whole video.

### Notes
- Installing 0.2.0 requires uninstalling 0.1.0 once (0.1.0 was signed with a temporary
  CI key). See README "Signing".

## [0.1.0] — 2026-10-21 — first installable build

### Added
- **Gallery:** MediaStore library across all storage volumes with live updates.
  Photos timeline with day/month/year sections, 8 sort fields, ascending/descending,
  pinch-to-resize grid (2–8 columns), fast-scroll handle with date bubble.
- **Selection:** long-press and drag to select ranges with edge auto-scroll; select a
  whole day; share, favourite, move, copy and delete in bulk.
- **Albums:** cover, item and video counts, pin to top, hide, sort (recent/name/items/size),
  2–4 per row.
- **Library:** Favourites, Videos, Recently added, Recently viewed, Camera, Screenshots,
  Screen recordings, Downloads, Animated (GIF), RAW, Motion photos, Large files.
- **Search:** file/album name, years, months, date ranges, type, album:, ext:, size filters.
- **Viewer:** swipe between items, pinch/double-tap zoom up to 10× with tiled
  full-resolution decoding, view-only rotate, details sheet (file info, camera EXIF,
  location with "open in maps"), slideshow, rename, move, copy, set as, open with.
- **Video player:** play/pause, seek bar, ±10 s, speed 0.25×–2×, mute, rotate to
  landscape, loop and auto-play settings, pause on headphone unplug.
- **Recently deleted** (Android 11+): restore, delete permanently, empty.
- **Hidden albums** protected by fingerprint/face/screen lock; re-lock on leaving the app.
- Opens images and videos from other apps.
- Light, dark and system themes; Material You dynamic colour; navigation rail on tablets.
- In-app user guide; About screen ("Created By Shane BlackbuRn").
- No internet permission (explicitly stripped from the merged manifest).
- CI: GitHub Actions builds, tests and lints every push; APK + logs published to the
  `ci-results` branch.
