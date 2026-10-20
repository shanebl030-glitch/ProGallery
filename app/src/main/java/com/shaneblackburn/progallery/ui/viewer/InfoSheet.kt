package com.shaneblackburn.progallery.ui.viewer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.data.MediaDetails
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(item: MediaItem, load: suspend (MediaItem) -> MediaDetails, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var details by remember(item.id) { mutableStateOf<MediaDetails?>(null) }
    LaunchedEffect(item.id) { details = load(item) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text("Details", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            val d = details
            if (d == null) {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            } else {
                SelectionContainer {
                    Column {
                        d.sections.forEach { section ->
                            Text(
                                section.title, style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                            )
                            section.rows.forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                                    Text(
                                        row.label, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(120.dp),
                                    )
                                    Text(row.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                if (d.isMotionPhoto) {
                    Text(
                        "This is a motion photo (it contains a short video clip).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                val lat = d.latitude
                val lon = d.longitude
                if (lat != null && lon != null) {
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = {
                        // Hands the coordinates to whatever map app the user has; ProGallery itself stays offline.
                        val uri = Uri.parse(String.format(Locale.ROOT, "geo:%f,%f?q=%f,%f", lat, lon, lat, lon))
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }) {
                        Icon(Icons.Outlined.Map, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open in maps")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
