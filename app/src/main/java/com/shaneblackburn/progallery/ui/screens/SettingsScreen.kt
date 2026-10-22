package com.shaneblackburn.progallery.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shaneblackburn.progallery.AppInfo
import com.shaneblackburn.progallery.BuildConfig
import com.shaneblackburn.progallery.R
import com.shaneblackburn.progallery.ui.GalleryViewModel
import com.shaneblackburn.progallery.ui.components.ChoiceDialog
import com.shaneblackburn.progallery.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: GalleryViewModel,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenHidden: () -> Unit,
) {
    val s by vm.settings.collectAsState()
    val context = LocalContext.current
    var themeDialog by remember { mutableStateOf(false) }
    var slideshowDialog by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { inner ->
        LazyColumn(contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding() + 32.dp)) {
            item { Section("Appearance") }
            item {
                ListItem(
                    headlineContent = { Text("Theme") },
                    supportingContent = { Text(themeLabel(s.themeMode)) },
                    modifier = Modifier.clickable { themeDialog = true },
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    SwitchRow("Dynamic colour", "Match your wallpaper colours (Material You)", s.dynamicColor, vm::setDynamicColor)
                }
            }

            item { Section("Viewing") }
            item {
                ListItem(
                    headlineContent = { Text("Slideshow speed") },
                    supportingContent = { Text("${s.slideshowSeconds} seconds per photo") },
                    modifier = Modifier.clickable { slideshowDialog = true },
                )
            }
            item { SwitchRow("Auto-play videos", "Start playing when you swipe to a video", s.autoPlayVideos, vm::setAutoPlay) }
            item { SwitchRow("Loop videos", "Restart videos when they finish", s.loopVideos, vm::setLoop) }

            item { Section("Privacy & safety") }
            item {
                SwitchRow(
                    "Confirm before deleting",
                    "Ask before moving items to Recently deleted",
                    s.confirmTrash, vm::setConfirmTrash,
                )
            }
            item {
                SwitchRow(
                    "Lock hidden albums",
                    "Require fingerprint, face or screen lock to view hidden albums",
                    s.requireAuthForHidden, vm::setRequireAuthForHidden,
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Hidden albums") },
                    supportingContent = { Text("${s.hiddenBuckets.size} hidden") },
                    modifier = Modifier.clickable(onClick = onOpenHidden),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Clear recently viewed") },
                    supportingContent = { Text("${s.recent.ids.size} items in history") },
                    modifier = Modifier.clickable { vm.clearRecent() },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Media permissions") },
                    supportingContent = { Text("Change photo and video access in Android settings") },
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        }
                    },
                )
            }

            item { Section("Help") }
            item {
                ListItem(
                    headlineContent = { Text("User guide") },
                    supportingContent = { Text("Gestures, search tips and features") },
                    modifier = Modifier.clickable(onClick = onOpenGuide),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Diagnostics") },
                    supportingContent = {
                        Text(
                            if (com.shaneblackburn.progallery.CrashReporter.latestReport() != null)
                                "View, email or share the last crash report"
                            else "View or send device info (no crash recorded)",
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Info, null) },
                    modifier = Modifier.clickable { showDiagnostics = true },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("About ProGallery") },
                    supportingContent = { Text("Version ${BuildConfig.VERSION_NAME}") },
                    leadingContent = { Icon(Icons.Outlined.Info, null) },
                    modifier = Modifier.clickable(onClick = onOpenAbout),
                )
            }
            item {
                Text(
                    "ProGallery ${BuildConfig.VERSION_NAME} · ${AppInfo.AUTHOR_CREDIT}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            }
        }
    }

    if (showDiagnostics) {
        com.shaneblackburn.progallery.ui.DiagnosticsDialog { showDiagnostics = false }
    }
    if (themeDialog) {
        ChoiceDialog(
            "Theme",
            listOf(ThemeMode.SYSTEM to "System default", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"),
            s.themeMode, vm::setTheme,
        ) { themeDialog = false }
    }
    if (slideshowDialog) {
        ChoiceDialog(
            "Slideshow speed",
            listOf(2, 3, 4, 5, 8, 10, 15).map { it to "$it seconds" },
            s.slideshowSeconds, vm::setSlideshowSeconds,
        ) { slideshowDialog = false }
    }
}

private fun themeLabel(m: ThemeMode) = when (m) {
    ThemeMode.SYSTEM -> "System default"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

@Composable
private fun Section(title: String) {
    Column {
        Spacer(Modifier.height(12.dp))
        Text(
            title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Shane's full ProGallery logo (with wordmark).
            Image(
                painterResource(R.drawable.about_logo),
                contentDescription = "ProGallery logo",
                modifier = Modifier.size(168.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    AppInfo.AUTHOR_CREDIT,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                "A fast, private photo and video gallery. Everything stays on your device — " +
                    "ProGallery has no internet permission and sends nothing anywhere.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Built with Kotlin, Jetpack Compose, Material 3, AndroidX Media3, Coil and Telephoto.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
