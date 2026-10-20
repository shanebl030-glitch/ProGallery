# ProGallery roadmap

Priorities: a solid, attractive gallery and reliable viewing first, then photo
editing, video editing and advanced features. Each milestone must pass CI (core
tests, app tests, debug and release builds) before the next starts.

## Milestone 1 — Gallery and viewing (v0.1.x)  ← current
- [x] MediaStore library across all volumes, live updates, trash-aware
- [x] Photos timeline: day/month/year sections, sort (8 fields), pinch-to-resize grid
- [x] Long-press drag multi-select with auto-scroll; per-section select-all
- [x] Fast-scroll thumb with date bubble
- [x] Albums: covers, counts, pin, hide, sort, 2–4 columns
- [x] Library: Favourites, Videos, Recently added/viewed, Camera, Screenshots,
      Screen recordings, Downloads, GIFs, RAW, Motion photos, Large files
- [x] Search language (text, dates, types, album:, ext:, sizes)
- [x] Share, favourite, move, copy, rename, trash, restore, delete forever
- [x] Recently deleted (Android 11+ system trash)
- [x] Hidden albums with fingerprint/face/PIN lock
- [x] Full-screen viewer: pinch/double-tap zoom with tiled hi-res, swipe, view rotate,
      details/EXIF/location, slideshow, set as, open with
- [x] Video player: seek, ±10 s, 0.25–2× speed, mute, landscape lock, loop
- [x] Open images/videos from other apps
- [x] Light/dark/system theme, Material You, tablet navigation rail
- [ ] On-device verification on a real phone (needs Shane)

## Milestone 2 — Photo editor (v0.2.x)
- Non-destructive edit model in `core` (edit list, serialisable, unit-tested)
- Crop (free, presets), straighten, rotate, flip, resize on export
- Light & colour: exposure, brightness, contrast, highlights, shadows, saturation,
  vibrance, temperature, tint, sharpness, clarity, vignette, blur
- Filters/looks with intensity; curves, HSL
- Text, drawing, shapes/arrows, stickers, frames
- Before/after, undo/redo, reset
- Save as copy (default) or replace original (with consent)
- GPU pipeline (Media3 effects / OpenGL ES) with CPU reference tests

## Milestone 3 — Video editor (v0.3.x)
- Timeline with thumbnail strip, trim, split, merge
- Crop/rotate/flip/resize, colour adjustments and filters
- Speed (slow/fast), mute/volume, music track, fades
- Transitions, text overlays
- Export via Media3 Transformer (hardware encode) in a foreground service with
  progress and cancel

## Milestone 4 — Advanced
- Duplicate detection (exact hash + perceptual)
- Storage analysis and cleanup suggestions
- Motion photo playback, RAW decode improvements
- Private vault (app-private encrypted storage)
- Optional on-device ML (labels/search) — no cloud
- Cast / external display (needs network permission — opt-in decision for Shane)
