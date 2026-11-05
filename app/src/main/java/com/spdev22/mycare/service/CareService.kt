package com.spdev22.mycare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.engine.HisaabBuilder
import com.spdev22.mycare.engine.UsageTracker
import com.spdev22.mycare.engine.Workflow
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.DailyScheduler
import com.spdev22.mycare.notify.NotifyFactory

/**
 * CareService — Phase 2 ka LIVE trigger engine.
 *
 * Kaam:
 *  - Har 20 second me UsageEvents poll karke usage-based triggers
 *    (app_hopping, single_app_over, continuous_use_over) jaanchna
 *  - Din ka pehla unlock pakadna (first_unlock → Welcome Boss)
 *  - Roz wale time-triggers ko DailyScheduler se set rakhna
 *
 * Saare thresholds CareSettings se aate hain (user ne badle ho to
 * woh, warna pack ke defaults) — D3 ka niyam.
 * Cooldowns CareSettings ki state me rehte hain taaki koi workflow
 * spam na kare.
 */
class CareService : Service() {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var tracker: UsageTracker? = null
    private var pack: List<Workflow> = emptyList()
    private var unlockReceiver: BroadcastReceiver? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                pollOnce()
            } catch (e: Exception) {
                Log.w(TAG, "Poll fail: ${e.message}")
            }
            handler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        NotifyFactory.ensureChannel(this)
        startForeground(NOTIF_ID, buildNotification())

        tracker = UsageTracker(this)
        pack = WorkflowEngine.loadPack(this)
        DailyScheduler.scheduleAll(this)
        registerUnlockReceiver()

        thread = HandlerThread("care-engine").also { it.start() }
        handler = Handler(thread!!.looper)
        handler?.post(pollRunnable)
        Log.i(TAG, "Care engine chalu — ${pack.size} workflows load hue")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Settings badle hon to naye time par alarms taaza kar do
        DailyScheduler.scheduleAll(this)
        return START_STICKY
    }

    override fun onDestroy() {
        handler?.removeCallbacks(pollRunnable)
        thread?.quitSafely()
        unlockReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // pehle se unregister — koi baat nahi
            }
        }
        super.onDestroy()
    }

    // ---------------- poll + evaluation ----------------

    private fun pollOnce() {
        val t = tracker ?: return
        if (!t.poll()) return // Usage Access nahi — usage triggers band
        val now = System.currentTimeMillis()
        for (wf in pack) {
            if (!wf.enabled) continue
            when (wf.triggerType) {
                "app_hopping" -> evalBoredom(wf, t, now)
                "single_app_over" -> evalLongApp(wf, t, now)
                "continuous_use_over" -> evalRest(wf, t, now)
            }
        }
    }

    private fun evalBoredom(wf: Workflow, t: UsageTracker, now: Long) {
        val needApps = settingInt(
            wf, CareSettings.KEY_BOREDOM_APPS, "apps",
            CareSettings.DEFAULT_BOREDOM_APPS
        )
        val maxSec = settingInt(
            wf, CareSettings.KEY_BOREDOM_SECONDS, "seconds",
            CareSettings.DEFAULT_BOREDOM_SECONDS
        )
        val windowMin = wf.triggerParams["window_minutes"]?.toIntOrNull() ?: 5
        val cooldownMin = wf.settings["cooldown_minutes"]?.toLongOrNull() ?: 45L

        if (now - CareSettings.boredomLastFired(this) < cooldownMin * 60_000L) return
        if (t.boredomDetected(now, needApps, maxSec * 1000L, windowMin * 60_000L)) {
            NotifyFactory.postWorkflow(this, wf)
            CareSettings.setBoredomLastFired(this, now)
            Log.i(TAG, "Boredom Dost fire hua")
        }
    }

    private fun evalLongApp(wf: Workflow, t: UsageTracker, now: Long) {
        val pkg = t.currentPkg ?: return
        if (t.isSystemOrLauncher(pkg)) return
        val minutes = settingInt(
            wf, CareSettings.KEY_LONG_MINUTES, "minutes",
            CareSettings.DEFAULT_LONG_MINUTES
        )
        val sessionMs = t.currentSessionMs(now)
        if (sessionMs < minutes * 60_000L) return
        val key = pkg + "@" + t.currentStart
        if (CareSettings.longAppFiredKey(this) == key) return // is session me ek baar bas

        val elapsedMin = (sessionMs / 60_000L).toInt()
        NotifyFactory.postWorkflow(
            this, wf,
            mapOf(
                "app" to HisaabBuilder.appLabel(this, pkg),
                "minutes" to elapsedMin.toString()
            )
        )
        CareSettings.setLongAppFiredKey(this, key)
        Log.i(TAG, "Long-App Suggestion fire: $pkg (${elapsedMin}m)")
    }

    private fun evalRest(wf: Workflow, t: UsageTracker, now: Long) {
        if (now < CareSettings.restSnoozeUntil(this)) return
        val minutes = settingInt(
            wf, CareSettings.KEY_REST_MINUTES, "minutes",
            CareSettings.DEFAULT_REST_MINUTES
        )
        val stretchMs = t.currentStretchMs(now)
        if (stretchMs < minutes * 60_000L) return
        if (t.stretchStart == 0L) return
        if (CareSettings.restFiredFor(this) == t.stretchStart) return

        NotifyFactory.postWorkflow(
            this, wf,
            mapOf("minutes" to (stretchMs / 60_000L).toInt().toString())
        )
        CareSettings.setRestFiredFor(this, t.stretchStart)
        Log.i(TAG, "Rest Nudge fire: stretch ${(stretchMs / 60_000L)}m")
    }

    /** User setting (agar badli ho) > pack param > plan default. */
    private fun settingInt(
        wf: Workflow,
        userKey: String,
        paramKey: String,
        default: Int
    ): Int {
        val fallback = wf.triggerParams[paramKey]?.toIntOrNull()
            ?: wf.settings[paramKey]?.toIntOrNull()
            ?: default
        return CareSettings.intVal(this, userKey, fallback)
    }

    // ---------------- first unlock (Welcome Boss) ----------------

    private fun registerUnlockReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) onUserPresent()
            }
        }
        try {
            registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            unlockReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Unlock receiver register fail: ${e.message}")
        }
    }

    private fun onUserPresent() {
        if (!CareSettings.welcomeEnabled(this)) return
        val today = UsageTracker.dayKey(System.currentTimeMillis())
        if (CareSettings.lastWelcomeDate(this) == today) return
        val wf = pack.firstOrNull { it.triggerType == "first_unlock" && it.enabled }
            ?: return
        NotifyFactory.postWorkflow(this, wf)
        CareSettings.setLastWelcomeDate(this, today)
        Log.i(TAG, "Welcome Boss fire hua (din ka pehla unlock)")
    }

    // ---------------- foreground notification ----------------

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
            .setContentText("Care workflows chalu hain — My Care nazar rakh raha hai.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CareService"
        private const val CHANNEL_ID = "care_service"
        private const val NOTIF_ID = 1001
        private const val POLL_INTERVAL_MS = 20_000L

        fun start(context: Context) {
            val intent = Intent(context, CareService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Service start fail: ${e.message}")
            }
        }
    }
}
