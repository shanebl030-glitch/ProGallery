package com.shaneblackburn.progallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import coil.request.ImageRequest
import com.shaneblackburn.progallery.ui.theme.ProGalleryTheme
import com.shaneblackburn.progallery.ui.theme.ThemeMode
import com.shaneblackburn.progallery.ui.viewer.VideoPage
import com.shaneblackburn.progallery.ui.viewer.buildPlayer
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * Opens a single image or video handed over by another app (ACTION_VIEW). Works with
 * any content:// URI, including ones not in MediaStore, using only the read grant that
 * came with the intent.
 */
class ExternalViewerActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val mime = intent?.type ?: uri?.let { contentResolver.getType(it) }.orEmpty()
        if (uri == null) { finish(); return }
        val isVideo = mime.startsWith("video/")

        setContent {
            ProGalleryTheme(mode = ThemeMode.DARK) {
                val context = LocalContext.current
                var chrome by remember { mutableStateOf(true) }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (isVideo) {
                        val player = remember { buildPlayer(context) }
                        DisposableEffect(Unit) { onDispose { player.release() } }
                        VideoPage(
                            player = player, uri = uri.toString(), active = true, autoPlay = true, loop = false,
                            controlsVisible = chrome, onToggleControls = { chrome = !chrome }, onRotate = {}, onEnded = {},
                        )
                    } else {
                        ZoomableAsyncImage(
                            model = ImageRequest.Builder(context).data(uri).build(),
                            contentDescription = null,
                            state = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 10f))),
                            onClick = { chrome = !chrome },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (chrome) {
                        Box(Modifier.fillMaxSize().statusBarsPadding().padding(4.dp)) {
                            IconButton(onClick = { finish() }, modifier = Modifier.align(Alignment.TopStart)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White)
                            }
                            IconButton(onClick = { share(uri, mime) }, modifier = Modifier.align(Alignment.TopEnd)) {
                                Icon(Icons.Outlined.Share, "Share", tint = Color.White)
                            }
                        }
                    }
                    if (mime.isEmpty()) Text("Unknown file type", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }

    private fun share(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime.ifEmpty { "*/*" })
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, null)) }
    }
}
