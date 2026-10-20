# ProGallery user guide

The same content is available inside the app under **Library → Settings → User guide**
(`ui/screens/GuideScreen.kt`). Keep both in sync.

## Browsing
- **Photos** shows everything, newest first, grouped by day. The sliders icon changes
  sort order (date taken/modified/added, name, size, type, duration, resolution),
  grouping (day/month/year/none) and grid size.
- **Pinch** the grid with two fingers for more or fewer photos per row.
- Drag the **scroll handle** on the right edge to jump through your library.
- **Albums** lists every folder containing photos or videos. Long-press an album to pin
  it to the top or hide it.
- **Library** has smart collections: Favourites, Videos, Recently added, Recently viewed,
  Camera, Screenshots, Screen recordings, Downloads, GIFs, RAW, Motion photos, Large files.

## Selecting
- Long-press a photo to start selecting; keep your finger down and drag to select a range.
  The grid scrolls automatically at the edges.
- Tap the circle beside a date to select that whole section.
- Selected items can be shared, favourited, moved, copied or deleted.

## Viewing
- Tap to open full screen; swipe between items; pinch or double-tap to zoom.
- Tap once to hide/show controls. The rotate button rotates the view only (the file is
  not changed).
- **Details** shows size, resolution, camera settings and location (when present and
  location access was allowed). "Open in maps" hands the coordinates to your map app.
- Videos: play/pause, ±10 seconds, seek bar, speed 0.25×–2×, mute, rotate to landscape.
- **Slideshow** from the viewer menu or an album's top bar.

## Editing photos
- Open a photo and tap the **pencil**. The original is never changed unless you pick
  **Save → Replace original…** (Android also asks you to confirm).
- **Crop:** drag corners/edges, aspect presets (Free, Original, 1:1, 4:5, 3:4, 2:3, 9:16,
  4:3, 3:2, 16:9), straighten (±45° with automatic zoom so no blank corners), rotate, flip.
- **Adjust:** exposure, brightness, contrast, highlights, shadows, whites, blacks,
  saturation, vibrance, warmth, tint, sharpen, clarity, denoise, vignette, blur, grain.
- **Filters:** 18 looks with a strength slider.
- **Curves:** RGB or per-channel; tap to add a point, drag, double-tap to remove.
- **Colour:** hue, saturation and luminance for eight colour ranges.
- **Text / Draw / Stickers:** drag to move, pinch to resize and rotate; double-tap text to
  edit. Draw has pen, marker, highlighter, eraser, line, arrow, rectangle and oval.
- **Frame:** border, instant photo, rounded, square fit; colour, thickness, corner radius.
- **Compare:** hold the compare button. **Undo/redo** covers everything; a slider drag
  counts as one step.
- **Save:** *Save as copy* (default) writes `name_edited.jpg` next to the original.
  *Export options* choose JPEG/PNG/WebP, quality, size, keeping details, removing location.

## Searching
- Words match file and album names: `holiday`, `"beach day"`.
- Dates: `2024`, `june`, `after:2024-01-01`, `before:2024-12-31`.
- Types: `videos`, `photos`, `gif`, `raw`, `fav`, `screenshots`, `downloads`.
- Filters: `album:camera`, `ext:png`, `>20mb`, `<500kb`. Combine freely.

## Deleting and recovering
- Deleted items go to **Recently deleted** (Library → Manage) for 30 days.
- Restore or permanently delete from there.
- Android shows a confirmation for any change to files ProGallery didn't create.
- Android 10 has no system trash, so deleting there is permanent.

## Hidden albums
- Long-press an album → **Hide**. Manage in Library → Hidden albums.
- With **Lock hidden albums** on (default), viewing them needs fingerprint, face or your
  screen lock. They re-lock when you leave the app.
- Hiding is a ProGallery setting; other apps still see the files.

## Privacy
- ProGallery has no internet permission. Nothing leaves your device.
- Favourites, hidden albums and history are stored only on this device and excluded
  from cloud backup.
