package com.shaneblackburn.progallery.video

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import com.shaneblackburn.progallery.core.video.VideoProject

/**
 * Owns the video editor's live preview.
 *
 * A Media3 [CompositionPlayer] accepts only one composition in its lifetime, so every
 * project change creates a fresh player (releasing the old one) and re-attaches it to the
 * [PlayerView], keeping the playback position and play/pause state.
 * All methods must be called on the main thread.
 */
@OptIn(UnstableApi::class)
class PreviewController(
    private val context: Context,
    private val onPlayingChanged: (Boolean) -> Unit = {},
    private val onError: (String?) -> Unit = {},
    private val onReady: () -> Unit = {},
) {
    var player: CompositionPlayer? = null
        private set
    private var view: PlayerView? = null

    val isPlaying: Boolean get() = player?.isPlaying == true
    val currentPositionMs: Long get() = player?.currentPosition ?: 0L

    @MainThread
    fun attach(playerView: PlayerView?) {
        view = playerView
        playerView?.player = player
    }

    /** Shows [project] starting at [positionMs]. Returns false if the preview couldn't be built. */
    @MainThread
    fun show(project: VideoProject, positionMs: Long): Boolean {
        val wasPlaying = isPlaying
        val old = player
        player = null
        view?.player = null
        old?.release()
        if (project.clips.isEmpty()) return false
        return try {
            val p = CompositionPlayer.Builder(context).build()
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = onPlayingChanged(isPlaying)
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) onReady() }
                override fun onPlayerError(error: PlaybackException) = onError("Preview unavailable: ${error.errorCodeName}")
            })
            p.setComposition(ProjectComposer.build(project))
            p.prepare()
            p.seekTo(positionMs.coerceIn(0, maxOf(0, project.durationMs - 1)))
            if (wasPlaying) p.play()
            player = p
            view?.player = p
            onError(null)
            true
        } catch (e: Exception) {
            onError("Preview unavailable: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    @MainThread fun play() { player?.play() }
    @MainThread fun pause() { player?.pause() }
    @MainThread fun seekTo(ms: Long) { player?.seekTo(ms) }

    @MainThread
    fun release() {
        view?.player = null
        player?.release()
        player = null
    }
}
