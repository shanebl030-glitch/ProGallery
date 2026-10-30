package com.shaneblackburn.progallery.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * In-app user guide. Keep in sync with docs/USER_GUIDE.md whenever features change.
 */
private val GUIDE: List<Pair<String, List<String>>> = listOf(
    "Browsing" to listOf(
        "Photos shows everything, newest first, grouped by day. Tap the sliders icon to change sort order, grouping and grid size.",
        "Anything added today — including a video you just exported — appears at the top of Today, even when it keeps an older capture date (an edited video keeps its original footage's date in Details). The next day it moves back to sit under its own capture date.",
        "Pinch the grid with two fingers to show more or fewer photos per row.",
        "Drag the scroll handle on the right edge to jump quickly through years of photos.",
        "Albums lists every folder that contains photos or videos. Long-press an album to pin it to the top or hide it.",
        "Library has smart collections: Favourites, Videos, Recently added, Recently viewed, Camera, Screenshots, Downloads, GIFs, RAW, Motion photos and Large files.",
    ),
    "Selecting" to listOf(
        "Long-press a photo to start selecting. Keep your finger down and drag to select a whole range — the grid scrolls when you reach the edge.",
        "Tap the circle next to a date to select that whole day.",
        "With items selected you can share, favourite, move, copy or delete them.",
    ),
    "Viewing" to listOf(
        "Tap a photo to open it full screen. Swipe left and right to move between items.",
        "Pinch or double-tap to zoom. Tap once to hide or show the controls.",
        "Swipe up or tap the info button to see details: size, resolution, camera settings and location (if the photo has it and you allowed location access).",
        "Videos have play/pause, a seek bar, playback speed (0.25× to 2×), mute and rotate-to-fullscreen.",
        "Use the slideshow button in the menu to play the current album as a slideshow.",
    ),
    "Editing photos" to listOf(
        "Open a photo and tap the pencil to edit. Edits never change the original unless you choose “Replace original”.",
        "Crop: drag the corners or edges, pick an aspect ratio, straighten with the slider, rotate and flip.",
        "Adjust: light (exposure, brightness, contrast, highlights, shadows, whites, blacks), colour (saturation, vibrance, warmth, tint), detail (sharpen, clarity, denoise) and effects (vignette, blur, grain).",
        "Filters: 18 looks including black & white, vintage and cinematic. Use the strength slider to tone a filter down.",
        "Curves and Colour (HSL) give precise control over tones and individual colours.",
        "Text, Draw and Stickers add overlays. Drag to move, pinch to resize and rotate, double-tap text to change it.",
        "Frame adds a border, instant-photo frame, rounded corners or square fit.",
        "Hold the compare button to see the original. Undo and redo cover every change.",
        "Save → Save as copy keeps the original. Export options set format (JPEG/PNG/WebP), quality, size and whether to keep photo details or remove location.",
    ),
    "Editing videos" to listOf(
        "Edit one video: open it and tap the pencil. Make a movie from several photos and videos: Library → Video projects → New video, then tap items in the order you want them.",
        "Video projects save automatically. Reopen, rename, duplicate or delete them in Library → Video projects. Your photos and videos are never changed; exports are saved as new videos, which show up at the top of Today the day you make them.",
        "If you edited a single video and leave, ProGallery asks whether to keep it as a project.",
        "Scroll the timeline to move the playhead. Tap a clip to select it, then drag its edges to trim. Long-press a clip and drag it to reorder. Back deselects.",
        "Clip tools: split at the playhead, speed (0.25× to 4×, voice pitch kept), volume or mute, rotate, flip, colour, filters with strength, and move earlier or later. Photos have a duration slider instead of trimming and speed.",
        "Split and cut: scrub the playhead and tap the scissors in the transport bar to split the clip there. To remove a section, split at its start and end, tap the middle piece and delete it — the gap closes automatically. Audio and video stay in sync.",
        "Use the ‹ and › frame-step buttons to nudge the playhead one frame at a time so you can cut at the exact spot.",
        "For an exact cut, trust the preview — it shows the precise frame at the playhead, and that's where a split lands. The little timeline thumbnails are a fast visual guide placed to the nearest second or so, so near a cut they can look slightly ahead or behind the preview; the preview (and frame-step) is the accurate one.",
        "Transitions (tap the dot between two clips): Standard cut, Fade (black/white), Blur, Zoom, Cross zoom, Whip pan, Push/slide, Spin, Shake, Wipe, Iris, Light leak, Glitch, Pixelate and Ripple. When you pick one, a Length slider (0.2–3.0 s) appears right under the preview — drag it to set how long the transition lasts; Direction and Strength appear where they apply. The picker previews every option and matches the export; Fade/Wipe/Iris, Whip pan and Push pass through black at the exact cut (the motion reads on the surrounding frames). A true cross-dissolve and J/L audio cuts are coming in a later update. Each transition is shown exactly at its own cut — whether you pause, scrub, or start playback from anywhere in the timeline — and with several transitions the footage between them stays untouched.",
        "Match-cut helper: when a transition is selected, the frames on each side of the cut are shown side by side so you can line up composition, then use frame-step and trim to align.",
        "If your phone can't show a video through the effects preview, ProGallery falls back to a simple preview of the footage (a banner says so); colour, speed and effects still apply when you export.",
        "Safe preview mode (in the project tools): previews without the live effects pipeline. Turn it on if the editor crashes or stutters on a particular phone — your effects still apply to the exported video. It turns on automatically if the editor has just crashed, and a banner at the top of the editor shows when it's on (with a one-tap 'Turn off'). If it was turned on automatically, ProGallery tries the full preview again next time you open the editor and turns Safe mode back off once the full preview runs successfully; if you turn it on yourself it stays on until you turn it off.",
        "If ProGallery closes unexpectedly, the next launch offers to email the crash report (to the developer), view it first, or dismiss it. You can also view, email or share the last report any time in Settings › Diagnostics. Nothing is ever sent automatically and the app has no internet access — reports leave your phone only through the email or share app you pick, and never include your photos, videos or projects.",
        "Colour grading: Light (exposure, brightness, contrast, highlights, shadows), Colour (saturation, vibrance, warmth, tint) and Detail (sharpness). The preview shows exactly what the export will look like.",
        "Playhead: the white line is fixed in the centre — drag the timeline to scrub and the preview follows the frame under it; Play moves both together, and the ‹ › buttons step one frame at a time for precise cuts. The timeline doesn't fling, so it stops exactly where you lift your finger — cuts land on the frame you chose.",
        "Sync check (advanced): long-press the timecode to show a small readout over the preview — the playhead position, the player's actual position, their difference, the preview mode and the clip the playhead is on. Long-press again to hide it. It's just a diagnostic; it changes nothing about your video.",
        "Add media adds more photos and videos. Tap the dot between two clips to pick a transition.",
        "Text adds a timed title; Music adds a background track with volume, loop and fades; Frame changes the shape (1:1, 9:16, 16:9, 4:5); fades in from or out to black or white.",
        "If a project's source file is deleted or moved, a banner offers to remove the missing items.",
        "Export: choose resolution, frame rate, quality and H.264 or HEVC. Export keeps running with a notification if you leave the app, and can be cancelled.",
    ),
    "Searching" to listOf(
        "Type words from the file or album name, e.g. “holiday”.",
        "Dates: a year (“2024”), a month (“june”), or ranges (“after:2024-01-01”, “before:2024-12-31”).",
        "Types: “videos”, “photos”, “gif”, “raw”, “fav”, “screenshots”, “downloads”.",
        "Filters: “album:camera”, “ext:png”, size like “>20mb” or “<500kb”. Combine as many as you like.",
    ),
    "Deleting & recovering" to listOf(
        "Deleted items go to Recently deleted (Library → Manage) for 30 days, then Android removes them for good.",
        "From Recently deleted you can restore items or delete them permanently.",
        "Android asks you to confirm every change to a file ProGallery didn't create — that dialog comes from the system and protects your files.",
        "On Android 10 there is no system trash, so deletion is permanent.",
    ),
    "Hidden albums" to listOf(
        "Hide albums you don't want appearing in Photos or Albums (long-press an album → Hide).",
        "Manage them in Library → Hidden albums. With “Lock hidden albums” on, you'll need your fingerprint, face or screen lock.",
        "Hiding is a ProGallery setting: other apps can still see the files.",
    ),
    "Privacy" to listOf(
        "ProGallery has no internet access. Your photos never leave your device.",
        "Favourites, hidden albums and history are stored only on this device and are excluded from cloud backups.",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User guide") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { inner ->
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding() + 32.dp)) {
            GUIDE.forEach { (title, lines) ->
                item(key = title) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
                }
                items(lines) { line ->
                    Text("•  $line", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}
