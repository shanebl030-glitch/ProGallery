# Changelog

All notable changes to ProGallery. Versions follow semantic versioning.

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
