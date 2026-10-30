package com.shaneblackburn.progallery.video

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import com.shaneblackburn.progallery.core.video.VideoProject
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-demand composed preview: renders the exact export frame — colour grade, filter, geometry and the
 * transition — at a whole-timeline position to a [Bitmap], using Media3's [FrameExtractor] with a
 * SOFTWARE decoder (its default [androidx.media3.exoplayer.mediacodec.MediaCodecSelector.PREFER_SOFTWARE]).
 *
 * This is the accurate preview shown while PAUSED or SCRUBBING on devices where CompositionPlayer's
 * hardware-only, no-fallback pipeline can't decode the composition (e.g. a multi-clip cut of one source
 * with a transition, which fails on the MediaTek AVC decoder). It is preview-only: the export pipeline
 * is untouched, and the frame is built from the SAME per-clip effects as export ([ProjectComposer
 * .previewBaseEffects] plus the shared [com.shaneblackburn.progallery.core.video.TransitionCpu] maths)
 * so it reflects what will be exported.
 *
 * Transition handling (why it is split out of the extractor): the extractor renders only the clip's
 * BASE frame — colour, filter, geometry, letterboxing — which is a pure function of the source
 * position, so FrameExtractor can seek and cache it correctly. The transition is then applied on the
 * CPU ([TransitionBitmap]) from the EXACT playhead output-time, every render. This removes the
 * backwards-scrub freeze: there is no position-dependent transition inside the cached extractor that a
 * stale cached frame could hold onto.
 *
 * Scope and rules:
 * - Video clips only. [FrameExtractor] requires a video track, so image clips return null and the
 *   caller keeps the existing preview for them.
 * - All [FrameExtractor] access is confined to one dedicated thread, as the API requires.
 * - Call latest-wins: don't overlap calls — serialise them (one render at a time) and drop stale ones.
 */
@OptIn(UnstableApi::class)
class FramePreviewRenderer(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "frame-preview").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    private var extractor: FrameExtractor? = null
    private var extractorSignature: String? = null

    /**
     * The composed frame at whole-timeline [timelineMs], downscaled so its short side is about
     * [maxShortSidePx] (preview resolution; the look — aspect, letterboxing, effects — matches export).
     * Returns null for image clips, empty projects, or on any extraction failure, in which case the
     * caller keeps the existing preview. Suspends on the dedicated extractor thread.
     */
    suspend fun render(project: VideoProject, timelineMs: Long, maxShortSidePx: Int): Bitmap? =
        withContext(dispatcher) {
            val clips = project.clips
            if (clips.isEmpty()) return@withContext null
            val spec = project.framePreviewSpec(timelineMs)
            val clip = clips.getOrNull(spec.clipIndex) ?: return@withContext null
            if (clip.isImage) return@withContext null // Phase 1: video clips only.

            val (outW, outH) = previewSize(project, maxShortSidePx)
            // Rebuild only when the source range or the clip's effect parameters change. Equal MediaItems
            // let Media3 reuse the decoder. The extractor renders the TRANSITION-FREE base frame, which
            // depends only on the source position, so scrubbing within a clip never rebuilds or
            // re-prepares and the cached frame is always correct for that position.
            val signature = "$outW:$outH:${clip.hashCode()}"
            if (extractor == null || extractorSignature != signature) {
                runCatching { extractor?.close() }
                extractor = runCatching {
                    FrameExtractor.Builder(context, ProjectComposer.clipMediaItem(clip))
                        .setEffects(ProjectComposer.previewBaseEffects(project, spec.clipIndex, outW, outH))
                        .build()
                }.getOrNull()
                extractorSignature = if (extractor != null) signature else null
            }
            val ex = extractor ?: return@withContext null
            val base = runCatching { awaitFrame(ex, spec.sourceOffsetMs) }.getOrNull() ?: return@withContext null

            // Apply the transition for the EXACT playhead output-time on the CPU. Evaluating it here,
            // every render, means a cached base frame can never freeze the transition during a
            // (backwards) scrub. Identity windows return the base untouched (no per-pixel work).
            val uniforms = project.clipEdge(spec.clipIndex).uniformsAt(spec.inClipTimelineMs)
            if (uniforms.isIdentity) return@withContext base
            runCatching { TransitionBitmap.apply(base, uniforms, seedFor(spec.sourceOffsetMs)) }
                .getOrDefault(base)
        }

    /**
     * Animation seed for glitch/shake/ripple, derived from the source position so it varies smoothly
     * as the playhead moves (never freezes). Matches the GPU shader's seed formula
     * (presentationTimeUs / 60000 % 997) with the extracted frame's source offset as the position.
     */
    private fun seedFor(sourceOffsetMs: Long): Float = ((sourceOffsetMs / 60L) % 997L).toFloat()

    private suspend fun awaitFrame(ex: FrameExtractor, positionMs: Long): Bitmap =
        suspendCancellableCoroutine { cont ->
            val future = ex.getFrame(positionMs)
            Futures.addCallback(
                future,
                object : FutureCallback<FrameExtractor.Frame> {
                    override fun onSuccess(result: FrameExtractor.Frame?) {
                        val bmp = result?.bitmap
                        if (bmp != null) cont.resume(bmp)
                        else cont.resumeWithException(IllegalStateException("FrameExtractor returned no bitmap"))
                    }

                    override fun onFailure(t: Throwable) = cont.resumeWithException(t)
                },
                MoreExecutors.directExecutor(),
            )
            cont.invokeOnCancellation { future.cancel(false) }
        }

    /** Preview render size: the export output size scaled so its short side is ~[maxShortSidePx] (even). */
    private fun previewSize(project: VideoProject, maxShortSidePx: Int): Pair<Int, Int> {
        val (w, h) = ProjectComposer.outputSize(project)
        val shortSide = min(w, h)
        if (maxShortSidePx <= 0 || shortSide <= maxShortSidePx) return even(w) to even(h)
        val s = maxShortSidePx.toFloat() / shortSide
        return even((w * s).roundToInt()) to even((h * s).roundToInt())
    }

    private fun even(v: Int) = max(2, v - (v % 2))

    /** Release the extractor and stop the thread. Safe to call once; idempotent afterwards. */
    fun release() {
        val ex = extractor
        extractor = null
        extractorSignature = null
        runCatching {
            executor.execute { runCatching { ex?.close() } }
            executor.shutdown()
        }
    }
}
