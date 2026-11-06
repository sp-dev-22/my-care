package com.spdev22.mycare.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.data.WorkflowPrefs
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.NotifyFactory
import com.spdev22.mycare.service.CareService

/**
 * Device Admin receiver.
 * Jobs: Lock Phone (force-lock policy) and watching failed-password
 * attempts (watch-login policy).
 *
 * Intruder Alert (Ghuspaithiya): when failed attempts inside a
 * 10-minute window reach the user's threshold (Settings, default 2),
 * the intruder-alert notification is posted. 0.2.8: the running
 * CareService is then asked to capture a FRONT-camera photo, which
 * upgrades the same alert with the picture attached.
 * A successful unlock resets the counter.
 *
 * NOTE: no app can ever read the device PIN/password — we only
 * learn THAT a wrong attempt happened. The PIN is never asked for,
 * shown, or stored.
 */
class CareDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled — ready for Lock + Intruder Alert")
        LogStore.log(context, "Device Admin ON — Lock + Intruder Alert are live")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        val last = CareSettings.stateLong(context, KEY_LAST_FAIL)
        var count = CareSettings.stateLong(context, KEY_FAIL_COUNT).toInt()
        // Last attempt older than the window -> start counting fresh
        count = if (now - last > WINDOW_MS) 1 else count + 1
        CareSettings.setStateLong(context, KEY_FAIL_COUNT, count.toLong())
        CareSettings.setStateLong(context, KEY_LAST_FAIL, now)
        Log.w(TAG, "Wrong password attempt #$count")
        LogStore.log(context, "Wrong unlock attempt #$count")

        val threshold = CareSettings.intVal(
            context, CareSettings.KEY_INTRUDER_THRESHOLD,
            CareSettings.DEFAULT_INTRUDER_THRESHOLD
        ).coerceIn(1, 10)
        if (count < threshold) return
        // Alert sent -> reset the counter (no spam on every attempt)
        CareSettings.setStateLong(context, KEY_FAIL_COUNT, 0L)
        val wf = WorkflowEngine.loadPack(context)
            .firstOrNull { it.triggerType == "failed_unlock" }
            ?.takeIf { WorkflowPrefs.isEnabled(context, it) }
            ?: run {
                LogStore.log(
                    context,
                    "Intruder threshold reached ($count attempts) but the " +
                        "Intruder Alert workflow is OFF — no alert posted"
                )
                return
            }
        NotifyFactory.postWorkflow(context, wf)
        // 0.2.8: ask the persistent Care Service to capture a
        // front-camera photo; it upgrades this same alert with the
        // picture. If the service is not running, the text alert
        // above still stands and the reason is logged there.
        CareService.requestIntruderCapture(context)
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        // Correct unlock — clear the counter
        CareSettings.setStateLong(context, KEY_FAIL_COUNT, 0L)
    }

    companion object {
        private const val TAG = "CareDeviceAdmin"
        const val KEY_FAIL_COUNT = "state_fail_count"
        const val KEY_LAST_FAIL = "state_fail_last"
        private const val WINDOW_MS = 10 * 60_000L
    }
}
