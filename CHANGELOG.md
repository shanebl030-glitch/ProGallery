# Changelog

All notable changes to ProGallery. Versions follow semantic versioning.

## [0.4.5] — 2026-10-23 — export no longer depends on the foreground service

0.4.4 stopped the *crash* but broke normal exporting on the Galaxy Z Fold5: tapping Export while
the app was open showed "Export failed — Export needs ProGallery open." This is the real fix.

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
