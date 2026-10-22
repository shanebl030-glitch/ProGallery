package com.shaneblackburn.progallery

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.shaneblackburn.progallery.data.MediaActions
import com.shaneblackburn.progallery.data.MediaRepository
import com.shaneblackburn.progallery.data.MetadataReader
import com.shaneblackburn.progallery.data.SettingsRepository
import com.shaneblackburn.progallery.ui.components.MediaThumbFetcher
import com.shaneblackburn.progallery.ui.components.MediaThumbKeyer

/**
 * Application-wide singletons (simple manual DI; the graph is small enough that a DI
 * framework would add more weight than value).
 */
class ProGalleryApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Capture uncaught exceptions to a shareable report before the process dies.
        CrashReporter.install(this)
    }

    val settings by lazy { SettingsRepository(this) }
    val media by lazy { MediaRepository(this) }
    val actions by lazy { MediaActions(this) }
    val metadata by lazy { MetadataReader(this) }
    val videoExport by lazy { com.shaneblackburn.progallery.video.VideoExportManager(this) }
    val projects by lazy { com.shaneblackburn.progallery.video.ProjectStore(this) }

    /**
     * Coil loader tuned for galleries: a large memory cache for fast back-scrolling,
     * a disk cache for decoded thumbnails, video-frame thumbnails, and animated
     * GIF/WebP/HEIF decoding.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this).maxSizePercent(0.25).build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("thumbnails"))
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        .components {
            add(ImageDecoderDecoder.Factory()) // minSdk 29: ImageDecoder always available
            add(VideoFrameDecoder.Factory())
            add(MediaThumbFetcher.Factory())
            add(MediaThumbKeyer())
        }
        .crossfade(120)
        .respectCacheHeaders(false)
        .build()
}
