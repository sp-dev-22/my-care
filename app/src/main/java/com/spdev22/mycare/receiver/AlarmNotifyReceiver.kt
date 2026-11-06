package com.spdev22.mycare.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.notify.NotifyFactory

/**
 * AlarmNotifyReceiver — ek-baari ke "alarm-style" reminders ke liye.
 *
 * Battery Full workflow ka rule: 95% charge par 1 minute baad
 * "⚠️ Phone Full Charged!" wala alarm bajna chahiye. Background se
 * Clock app kholna Android 12 me reliable nahi hai, isliye hum
 * AlarmManager se exact-ish time par ek zor-daar notification
 * bhejte hain — wahi is workflow ka alarm hai.
 */
class AlarmNotifyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val id = intent.getIntExtra(EXTRA_ID, 9901)
        NotifyFactory.postPlain(context, id, title, text)
        LogStore.log(context, "Alarm-style notification fired: $title")
    }

    companion object {
        const val EXTRA_TITLE = "alarm_title"
        const val EXTRA_TEXT = "alarm_text"
        const val EXTRA_ID = "alarm_id"
    }
}
