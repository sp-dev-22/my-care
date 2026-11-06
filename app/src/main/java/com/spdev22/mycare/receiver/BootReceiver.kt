package com.spdev22.mycare.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spdev22.mycare.notify.DailyScheduler
import com.spdev22.mycare.service.CareService

/**
 * BootReceiver — phone restart ke baad:
 *  1. Care Service dobara chalu
 *  2. Roz wale suggestion alarms dobara set
 *  3. 0.2.7: screenshot pending deletes ka exact alarm dobara set
 *     (reboot par AlarmManager saaf ho jata hai; pending list
 *     prefs me bachi rehti hai — overdue hon to CareService ka
 *     poll unhe nipta dega)
 * XOS ke battery-killer se bachne ke liye yeh kadam zaroori hain.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        CareService.start(context)
        DailyScheduler.scheduleAll(context)
        DeleteReceiver.schedulePendingAlarm(context)
    }
}
