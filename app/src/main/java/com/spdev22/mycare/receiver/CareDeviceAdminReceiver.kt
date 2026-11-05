package com.spdev22.mycare.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin receiver.
 * Kaam: Lock Phone (force-lock policy) aur galat-password attempts
 * (watch-login policy) pakadna.
 *
 * Phase 1: sirf log. Phase 3 me onPasswordFailed se
 * Ghuspaithiya Alert workflow trigger hoga.
 *
 * NOTE: Device ka PIN/password koi app kabhi padh nahi sakta —
 * hum sirf "galat koshish hui" itna jaan sakte hain.
 */
class CareDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled — Lock aur Ghuspaithiya Alert ke liye taiyar")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        // Phase 3: 2 failed attempts par intruder-alert workflow yahan se chalega
        Log.w(TAG, "Galat password attempt detect hua")
    }

    companion object {
        private const val TAG = "CareDeviceAdmin"
    }
}
