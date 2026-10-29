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
 * .previewVideoEffects]) so it reflects what will be exported.
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
    // Clip-local OUTPUT time (ms) to evaluate the transition at. Written before each render, read on
    // the GL thread by ClipTransitionEffect, so one extractor serves successive scrub positions
    // without being rebuilt (only the seek position and this value change while scrubbing a clip).
    @Volatile private var transitionTimeMs: Long = 0L

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
            transitionTimeMs = spec.inClipTimelineMs

            val (outW, outH) = previewSize(project, maxShortSidePx)
            // Rebuild only when the source range or the clip's effect parameters change. Equal MediaItems
            // let Media3 reuse the decoder, and the transition TIME is excluded on purpose (the provider
            // supplies it per frame), so scrubbing within a clip never rebuilds or re-prepares.
            val signature = "$outW:$outH:${clip.hashCode()}:${project.clipEdge(spec.clipIndex).hashCode()}"
            if (extractor == null || extractorSignature != signature) {
                runCatching { extractor?.close() }
                extractor = runCatching {
                    FrameExtractor.Builder(context, ProjectComposer.clipMediaItem(clip))
                        .setEffects(
                            ProjectComposer.previewVideoEffects(project, spec.clipIndex, outW, outH) { transitionTimeMs },
                        )
                        .build()
                }.getOrNull()
                extractorSignature = if (extractor != null) signature else null
            }
            val ex = extractor ?: return@withContext null
            runCatching { awaitFrame(ex, spec.sourceOffsetMs) }.getOrNull()
        }

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
