# ProGallery user guide

Created by Shane BlackbuRn.

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

## Editing videos
- **One video:** open it and tap the **pencil**.
- **A movie from several photos and videos:** Library → **Video projects** → **New
  video**, then tap items in the order you want them (they're numbered) and **Create**.
- **Projects save automatically** (Library → Video projects): reopen, rename, duplicate
  or delete. If you edited a single video and leave, ProGallery asks whether to keep it
  as a project. Projects only reference your media; nothing is copied or changed.
- Scroll the **timeline** to move the playhead; zoom with the magnifier buttons.
- Press **Back** to deselect a clip, title or music track.
- Tap a **clip** to select it, drag its edges to **trim**. **Long-press and drag** a clip
  to reorder. Clip tools: **Split** at the playhead, **Speed** 0.25×–4× (pitch
  preserved), **Audio** volume/mute, **Rotate**, **Flip**, **Colour**, **Filter** (with
  strength), move **Earlier/Later**, **Delete**.
- **Photos** in a project have a **Photo duration** slider (0.5–15 s) instead of trimming,
  speed and audio. Photos are shown upright according to how the camera saved them.
- **Split / cut:** scrub the playhead and tap the **scissors** in the transport bar to
  split the clip there. To remove a section, split at its start and end, tap the middle
  piece and delete it — the remaining footage joins up automatically, and audio stays in
  sync. Use the **‹ › frame-step** buttons to nudge the playhead one frame at a time and
  cut at the exact spot.
- If a video can’t be decoded through the effects preview on your phone, ProGallery shows
  a **simplified preview** of the footage (with a banner); colour, speed and effects still
  apply on export.
- **Colour grading** (per clip): *Light* — exposure, brightness, contrast, highlights,
  shadows; *Colour* — saturation, vibrance, warmth, tint; *Detail* — sharpness. The live
  preview and the export use the same GPU grade.
- **Add media** adds more photos and videos. Tap the **dot between clips** to open the
  **transition picker** — a transition plays *between* two clips (to style one whole clip,
  select it and use Colour/Filter). Choose from: **Standard cut**; **Fade** through black or
  white; **Blur**; **Zoom** (in/out); **Cross zoom**; **Whip pan** (4 directions); **Push /
  slide** (4 directions); **Wipe** (left/right/up/down/diagonal); **Iris**; **Light leak**;
  **Glitch**. Each has a **Length** (0.3–2.0 s, default 1.0 s); where it applies you also get
  **Direction** and **Strength**. The picker shows a live preview of every option, and the
  timeline dot shows which transition is set. A **Match-cut helper** shows the end-of-A and
  start-of-B frames side by side so you can line up composition, then use frame-step + trim to
  align the cut.
  - How they look: Fade/Wipe/Iris pass *through black (or white)* at the cut; Whip pan/Push
    slide *through black*. A true cross-dissolve (both clips blended at once), reveal wipes/
    pushes, and J-/L audio cuts need two-clip compositing and are planned for the next update.
- **Text** adds a timed title (fade, slide-up or pop animation). **Music** adds a
  background track (volume, start point, loop, fade in/out). **Frame** sets 1:1, 9:16,
  16:9 or 4:5. **Fade in/out** from or to black or white.
- If a source file was deleted or moved, a banner offers **Remove them**.
- **Export:** resolution (original down to 480p), frame rate (only lowers), quality,
  H.264 or HEVC, with an estimated file size. Export continues in the background with a
  progress notification and a Cancel button. Exports are saved as new videos
  (`name_edited.mp4`); originals are never overwritten.

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
