package com.shaneblackburn.progallery.video

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.shaneblackburn.progallery.MainActivity
import com.shaneblackburn.progallery.ProGalleryApp
import com.shaneblackburn.progallery.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app alive and shows progress while a video export runs. The export itself
 * is owned by [VideoExportManager]; this service only mirrors its state.
 */
class VideoExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            (application as ProGalleryApp).videoExport.cancel()
            return START_NOT_STICKY
        }
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(0f, "Preparing…", ongoing = true), type)

        scope.launch {
            (application as ProGalleryApp).videoExport.state.collect { s ->
                when (s) {
                    is ExportState.Running -> notify(notification(s.progress, s.title, ongoing = true))
                    is ExportState.Done -> finish("Video saved", s.title)
                    is ExportState.Failed -> finish("Export failed", s.message)
                    ExportState.Cancelled -> finish(null, null)
                    ExportState.Idle -> stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(title: String?, text: String?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (title != null && NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(this).notify(DONE_ID, n) }
        }
        stopSelf()
    }

    private fun notify(n: android.app.Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n) }
        }
    }

    private fun notification(progress: Float, title: String, ongoing: Boolean) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle("Exporting video")
            .setContentText(title)
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Cancel", PendingIntent.getService(
                this, 1, Intent(this, VideoExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Video export", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "video_export"
        private const val NOTIFICATION_ID = 41
        private const val DONE_ID = 42
        const val ACTION_CANCEL = "com.shaneblackburn.progallery.CANCEL_EXPORT"
    }
}
