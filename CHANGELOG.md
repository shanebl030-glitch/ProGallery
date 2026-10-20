# Changelog

All notable changes to ProGallery. Versions follow semantic versioning.

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
- Credit text corrected from "Created By" to "Created by".
- Export-progress notification uses the monochrome icon (Android requires single-colour
  small icons).

### Fixed
- Video editor build error (overlay list type in `ProjectComposer`).
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
