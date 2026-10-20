package com.shaneblackburn.progallery.ui.viewer

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shaneblackburn.progallery.core.format.Formatters
import kotlinx.coroutines.delay

/** Observable snapshot of player state for Compose. */
class PlayerUiState {
    var isPlaying by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    var muted by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var buffering by mutableStateOf(true)
}

@OptIn(UnstableApi::class)
fun buildPlayer(context: Context): ExoPlayer =
    ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
        .build()
        .apply {
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
        }

/**
 * Video surface + Compose controls for one page. The [player] is shared across pages
 * and owned by the viewer; this composable only binds it while [active].
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPage(
    player: ExoPlayer,
    uri: String,
    active: Boolean,
    autoPlay: Boolean,
    loop: Boolean,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onRotate: () -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(uri) { PlayerUiState() }
    val currentOnEnded by rememberUpdatedState(onEnded)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Bind media when this page becomes active.
    DisposableEffect(player, uri, active) {
        if (!active) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { state.isPlaying = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.buffering = playbackState == Player.STATE_BUFFERING
                state.ended = playbackState == Player.STATE_ENDED
                if (playbackState == Player.STATE_READY) state.durationMs = player.duration.coerceAtLeast(0)
                if (playbackState == Player.STATE_ENDED) currentOnEnded()
            }
            override fun onPlayerError(error: PlaybackException) {
                state.error = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                        "This device can't decode this video format."
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The video file is missing."
                    else -> "Can't play this video (${error.errorCodeName})."
                }
            }
        }
        player.addListener(listener)
        state.error = null
        player.setMediaItem(ExoMediaItem.fromUri(Uri.parse(uri)))
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.prepare()
        player.playWhenReady = autoPlay
        state.speed = player.playbackParameters.speed
        state.muted = player.volume == 0f
        onDispose {
            player.removeListener(listener)
            player.stop()
            player.clearMediaItems()
        }
    }

    // Pause when the app goes to the background.
    DisposableEffect(lifecycle, active) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP && active) player.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(active, state.isPlaying) {
        while (active) {
            state.positionMs = player.currentPosition.coerceAtLeast(0)
            if (state.durationMs <= 0) state.durationMs = player.duration.coerceAtLeast(0)
            delay(if (state.isPlaying) 200 else 500)
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (active) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(com.shaneblackburn.progallery.R.layout.player_view, null) as PlayerView).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setOnClickListener { onToggleControls() }
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }

        state.error?.let { msg ->
            Text(msg, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }

        AnimatedVisibility(
            visible = active && controlsVisible && state.error == null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            VideoControls(player, state, onRotate)
        }
    }
}

@Composable
private fun VideoControls(player: ExoPlayer, state: PlayerUiState, onRotate: () -> Unit) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton({ player.seekBack() }, 48) { Icon(Icons.Outlined.Replay10, "Back 10 seconds", tint = Color.White) }
            RoundButton({
                when {
                    state.ended -> { player.seekTo(0); player.play() }
                    player.isPlaying -> player.pause()
                    else -> player.play()
                }
            }, 68) {
                Icon(
                    when { state.ended -> Icons.Filled.Replay; state.isPlaying -> Icons.Filled.Pause; else -> Icons.Filled.PlayArrow },
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            RoundButton({ player.seekForward() }, 48) { Icon(Icons.Outlined.Forward10, "Forward 10 seconds", tint = Color.White) }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 88.dp, start = 12.dp, end = 12.dp),
        ) {
            val duration = state.durationMs.coerceAtLeast(1)
            val shown = scrubbing?.let { (it * duration).toLong() } ?: state.positionMs
            Slider(
                value = scrubbing ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let { player.seekTo((it * duration).toLong()) }
                    scrubbing = null
                },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${Formatters.duration(shown)} / ${Formatters.duration(state.durationMs)}",
                    color = Color.White, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Box {
                    TextButton(onClick = { speedMenu = true }) {
                        Text(speedLabel(state.speed), color = Color.White)
                    }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                            DropdownMenuItem(
                                text = { Text(speedLabel(sp)) },
                                onClick = { player.setPlaybackSpeed(sp); state.speed = sp; speedMenu = false },
                            )
                        }
                    }
                }
                IconButton(onClick = {
                    state.muted = !state.muted
                    player.volume = if (state.muted) 0f else 1f
                }) {
                    Icon(if (state.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        if (state.muted) "Unmute" else "Mute", tint = Color.White)
                }
                IconButton(onClick = onRotate) { Icon(Icons.Outlined.ScreenRotation, "Rotate screen", tint = Color.White) }
            }
        }
    }
}

private fun speedLabel(s: Float) = if (s == 1f) "1×" else "${if (s % 1f == 0f) s.toInt().toString() else s.toString()}×"

@Composable
private fun RoundButton(onClick: () -> Unit, size: Int, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape),
    ) { content() }
}
