package com.vcodec.smartencoder.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.vcodec.smartencoder.R

object TranscodeNotificationController {

    private const val NOTIFICATION_ID = 1001
    private const val CHANNEL_ID = "video_compress_channel"

    fun createForegroundInfo(context: Context, progress: Float, message: String): ForegroundInfo {
        createNotificationChannel(context)

        val progressPercent = (progress * 100).toInt()
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Smart Video Encoder")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setProgress(100, progressPercent, false)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Video Compression Queue"
            val descriptionText = "Displays progress of active background video encoding tasks"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
