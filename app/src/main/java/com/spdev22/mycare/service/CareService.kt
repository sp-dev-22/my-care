package com.spdev22.mycare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * CareService — foreground service ka SKELETON (stub).
 * Phase 2 me yahi service saare triggers (unlock, usage, battery,
 * screenshot) sunegi aur WorkflowEngine se workflows chalegi.
 * Abhi yeh sirf ek chhoti si notification ke saath zinda rehti hai,
 * taaki Infinix ke system par iski jagah pakki ho jaye.
 */
class CareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Phase 2: trigger listeners yahan register honge
        return START_STICKY
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "My Care Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Care service chalu hai — iske bina triggers nahi chalenge"
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("My Care active hai")
            .setContentText("Care workflows Phase 2 me chalu honge.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "care_service"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, CareService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
