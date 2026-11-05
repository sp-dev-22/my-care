package com.spdev22.mycare.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.NotifyFactory

/**
 * Device Admin receiver.
 * Kaam: Lock Phone (force-lock policy) aur galat-password attempts
 * (watch-login policy) pakadna.
 *
 * Ghuspaithiya Alert (workflow 15): 10 minute ke andar 2 galat
 * koshishein ho jayein to intruder-alert notification jati hai —
 * buttons: Lock Phone / Ignore. Sahi password lagte hi ginati
 * reset ho jati hai.
 *
 * NOTE: Device ka PIN/password koi app kabhi padh nahi sakta —
 * hum sirf "galat koshish hui" itna jaan sakte hain. PIN kabhi
 * maanga, dikhaya ya save nahi jata — yeh approved pivot hai.
 */
class CareDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled — Lock aur Ghuspaithiya Alert ke liye taiyar")
        LogStore.log(context, "Device Admin ON — Lock + Ghuspaithiya live")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        val last = CareSettings.stateLong(context, KEY_LAST_FAIL)
        var count = CareSettings.stateLong(context, KEY_FAIL_COUNT).toInt()
        // Pichhli koshish 10 minute se purani ho to ginati nayi shuru karo
        count = if (now - last > WINDOW_MS) 1 else count + 1
        CareSettings.setStateLong(context, KEY_FAIL_COUNT, count.toLong())
        CareSettings.setStateLong(context, KEY_LAST_FAIL, now)
        Log.w(TAG, "Galat password attempt #$count")
        LogStore.log(context, "Galat unlock koshish #$count")

        if (count < 2) return
        // Alert bhejkar ginati reset — har koshish par spam nahi
        CareSettings.setStateLong(context, KEY_FAIL_COUNT, 0L)
        val wf = WorkflowEngine.loadPack(context)
            .firstOrNull { it.triggerType == "failed_unlock" && it.enabled }
            ?: return
        NotifyFactory.postWorkflow(context, wf)
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        // Sahi unlock — ginati saaf
        CareSettings.setStateLong(context, KEY_FAIL_COUNT, 0L)
    }

    companion object {
        private const val TAG = "CareDeviceAdmin"
        private const val KEY_FAIL_COUNT = "state_fail_count"
        private const val KEY_LAST_FAIL = "state_fail_last"
        private const val WINDOW_MS = 10 * 60_000L
    }
}
