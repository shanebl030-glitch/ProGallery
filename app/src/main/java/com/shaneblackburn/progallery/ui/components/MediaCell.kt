package com.shaneblackburn.progallery.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.shaneblackburn.progallery.core.format.Formatters
import com.shaneblackburn.progallery.core.model.MediaItem

/**
 * One square cell in a media grid. Purely visual: gestures are handled by the grid
 * so that long-press-drag selection works across cells.
 */
@Composable
fun MediaCell(
    item: MediaItem,
    selected: Boolean,
    selectionMode: Boolean,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 4,
) {
    val context = LocalContext.current
    val request = remember(item.uri, item.dateModified, item.sizeBytes) {
        ImageRequest.Builder(context)
            .data(MediaThumb(item))
            .memoryCacheKey("thumb:${item.uri}:${item.dateModified xor item.sizeBytes}")
            .build()
    }
    val inset by animateDpAsState(if (selected) 10.dp else 0.dp, label = "inset")
    val radius by animateDpAsState(if (selected) 14.dp else cornerRadius.dp, label = "radius")

    Box(
        modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        AsyncImage(
            model = request,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .padding(inset)
                .clip(RoundedCornerShape(radius))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )

        // Bottom gradient only when there's something to read on it.
        if (item.isVideo || item.isFavorite) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxSize()
                    .padding(inset)
                    .clip(RoundedCornerShape(radius))
                    .background(
                        Brush.verticalGradient(
                            0.6f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f),
                        ),
                    ),
            )
        }

        if (item.isVideo) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(inset + 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    Formatters.duration(item.durationMs),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        if (item.isFavorite) {
            Icon(
                Icons.Filled.Favorite, contentDescription = "Favourite", tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(inset + 5.dp).size(13.dp),
            )
        }
        val badge = when {
            item.isGif -> "GIF"
            item.isRaw -> "RAW"
            item.isMotionPhotoCandidate -> "MOTION"
            else -> null
        }
        if (badge != null) {
            Text(
                badge,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(inset + 4.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }

        if (selectionMode) {
            val tint = if (selected) MaterialTheme.colorScheme.primary else Color.White
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .background(if (selected) MaterialTheme.colorScheme.surface else Color.Black.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

