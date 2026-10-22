package com.shaneblackburn.progallery.core.video

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The single source of truth for converting between timeline time (ms) and the timeline's
 * horizontal scroll offset (px), shared by the preview follow loop, the scrub handler and the
 * frame-step/seek path so they can never drift apart.
 *
 * Geometry contract (see [com.shaneblackburn.progallery.core.video] and VideoTimeline):
 *  - The playhead is fixed at the horizontal centre of the viewport.
 *  - The scrollable content has a leading half-viewport spacer, so timeline time 0 sits under the
 *    centred playhead when the scroll offset is 0.
 *  - Therefore the time currently under the playhead is exactly `scrollPx / pxPerMs`, and the
 *    scroll offset that places [positionMs] under the playhead is `positionMs * pxPerMs`.
 *
 * Keeping both directions here (instead of inline arithmetic in the UI) guarantees the preview
 * position, the timecode and the timeline playhead are always computed the same way.
 */
object TimelineMath {
    /** Scroll offset (px) that puts [positionMs] under the centred playhead. */
    fun scrollPxForMs(positionMs: Long, pxPerMs: Float): Int =
        (positionMs.toDouble() * pxPerMs).roundToInt()

    /** Timeline time (ms) currently under the centred playhead for a given [scrollPx]. */
    fun msForScrollPx(scrollPx: Int, pxPerMs: Float, durationMs: Long): Long {
        if (pxPerMs <= 0f) return 0L
        val ms = (scrollPx / pxPerMs).toLong()
        return ms.coerceIn(0L, max(0L, durationMs))
    }
}
