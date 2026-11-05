package com.spdev22.mycare.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * CareAccessibilityService — Phase 2 ka asli accessibility service.
 *
 * Isi ki wajah se "My Care" Settings → Accessibility → Installed
 * services me dikhega (Phase 1 me sirf wizard ka raasta tha, service
 * thi hi nahi — wahi defect user ne pakda tha).
 *
 * Kaam (abhi): global HOME action (Close App button ke liye) aur
 * foreground window ka package yaad rakhna (window-state tracking,
 * aage ke triggers ke kaam aayega).
 */
class CareAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service jud gaya — My Care list me aa gaya hoga")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { lastForegroundPackage = it }
        }
    }

    override fun onInterrupt() {
        // Android ko interrupt chahiye to chup-chaap ruk jao
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CareA11y"

        @Volatile
        private var instance: CareAccessibilityService? = null

        /** Sabse recent foreground window ka package (tracking ke liye). */
        @Volatile
        var lastForegroundPackage: String? = null
            private set

        fun isRunning(): Boolean = instance != null

        /** GLOBAL ACTION HOME chalao. @return false agar service band hai. */
        fun goHome(): Boolean {
            val svc = instance ?: return false
            return try {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            } catch (e: Exception) {
                false
            }
        }
    }
}
