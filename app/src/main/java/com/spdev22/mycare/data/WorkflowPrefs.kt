package com.spdev22.mycare.data

import android.content.Context
import com.spdev22.mycare.engine.Workflow

/**
 * WorkflowPrefs — har workflow ka user-side on/off override (0.2.2).
 *
 * Niyam: user ne kabhi toggle chhua hi nahi → pack ki apni `enabled`
 * value chalegi (default). Ek baar toggle badla → wahi override
 * hamesha chalega, jab tak user phir na badle.
 *
 * Keys "my_care_prefs" wali hi file me: "wf_enabled_<workflowId>".
 * Engine (CareService), DailyScheduler, DailyAlarmReceiver aur
 * DeviceAdmin receiver — sab fire points yahi se poochte hain.
 */
object WorkflowPrefs {

    private const val PREFS = "my_care_prefs"
    private const val KEY_PREFIX = "wf_enabled_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Effective sthiti: user override ho to woh, warna pack ka default. */
    fun isEnabled(context: Context, wf: Workflow): Boolean {
        val p = prefs(context)
        val key = KEY_PREFIX + wf.id
        return if (p.contains(key)) p.getBoolean(key, wf.enabled) else wf.enabled
    }

    fun isEnabled(context: Context, wfId: String, packDefault: Boolean): Boolean {
        val p = prefs(context)
        val key = KEY_PREFIX + wfId
        return if (p.contains(key)) p.getBoolean(key, packDefault) else packDefault
    }

    /** User ne override set kiya hai ya nahi (UI me dikhane ke liye). */
    fun hasOverride(context: Context, wfId: String): Boolean =
        prefs(context).contains(KEY_PREFIX + wfId)

    fun setEnabled(context: Context, wfId: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFIX + wfId, enabled).apply()
    }
}
