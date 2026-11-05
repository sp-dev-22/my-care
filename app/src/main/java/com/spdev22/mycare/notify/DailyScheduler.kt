package com.spdev22.mycare.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.data.WorkflowOverrides
import com.spdev22.mycare.data.WorkflowPrefs
import com.spdev22.mycare.engine.Workflow
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.receiver.DailyAlarmReceiver
import java.util.Calendar

/**
 * DailyScheduler — 'time' trigger wale workflows (Study Suggestion,
 * Aaj Ka Hisaab, Alarm Suggestion) ko AlarmManager se roz ke
 * time par set karta hai.
 *
 * Har workflow ka time: pehle user ki Settings value (trigger ka
 * settings_key, jaise "study_time"), nahi mili to pack ka time.
 * Fire hone ke baad DailyAlarmReceiver agle din ke liye dobara set
 * kar deta hai; service/boot par scheduleAll sab ko taaza kar deta hai.
 */
object DailyScheduler {

    private const val TAG = "DailyScheduler"
    const val EXTRA_WF = "daily_wf"

    fun scheduleAll(context: Context) {
        val pack = WorkflowEngine.loadPack(context)
        for (wf in pack) {
            if (wf.triggerType == "time") scheduleOne(context, wf)
        }
    }

    fun scheduleOne(context: Context, wf: Workflow) {
        if (wf.triggerType != "time") return
        // 0.2.2: user ne workflow OFF kiya ho to alarm set karne ke
        // bajaaye purana laga hua alarm CANCEL kar do — band workflow
        // ka time-alarm kabhi nahi bajna chahiye.
        if (!WorkflowPrefs.isEnabled(context, wf)) {
            cancelOne(context, wf)
            return
        }
        val triggerAt = nextOccurrence(resolveTime(context, wf))
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, wf)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                am.canScheduleExactAlarms()
            ) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Alarm set fail (${wf.id}): ${e.message}")
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e2: Exception) {
                Log.w(TAG, "Fallback alarm bhi fail: ${e2.message}")
            }
        }
    }

    /** Ek workflow ka pending daily alarm hata do (toggle OFF par). */
    fun cancelOne(context: Context, wf: Workflow) {
        if (wf.triggerType != "time") return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, wf)
        try {
            am.cancel(pi)
        } catch (e: Exception) {
            Log.w(TAG, "Alarm cancel fail (${wf.id}): ${e.message}")
        }
        pi.cancel()
    }

    /**
     * Workflow ka effective "HH:MM" — pehle Editor-lite ka
     * per-workflow time override (0.2.3), phir user ki Settings
     * value (trigger ka settings_key), phir pack ka time.
     */
    fun resolveTime(context: Context, wf: Workflow): String {
        WorkflowOverrides.timeOverride(context, wf.id)?.let { return it }
        val settingsKey = wf.triggerParams["settings_key"]
        val packTime = wf.triggerParams["time"] ?: "08:00"
        return if (settingsKey != null) {
            CareSettings.time(context, settingsKey, packTime)
        } else {
            packTime
        }
    }

    private fun pendingIntent(context: Context, wf: Workflow): PendingIntent {
        val intent = Intent(context, DailyAlarmReceiver::class.java).apply {
            action = "com.spdev22.mycare.DAILY_" + wf.id
            putExtra(EXTRA_WF, wf.id)
        }
        return PendingIntent.getBroadcast(
            context,
            NotifyFactory.notifIdFor(wf.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** "HH:MM" ka agla aane wala samay (aaj nikal gaya ho to kal). */
    fun nextOccurrence(hhmm: String): Long {
        val parts = hhmm.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
