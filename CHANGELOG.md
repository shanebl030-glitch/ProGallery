# Changelog

All notable changes to ProGallery. Versions follow semantic versioning.

## [Unreleased]

### Added (Milestone 2 groundwork — not yet visible in the app)
- `core/edit`: non-destructive edit model (geometry, 17 adjustments, 18 looks, tone
  curves, HSL bands, overlays, frames) and a CPU pixel pipeline that renders in strips
  (bounded memory, multi-threaded, bit-identical to whole-image rendering). 17 new tests.

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
