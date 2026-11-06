package com.spdev22.mycare.engine

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import com.spdev22.mycare.data.LogStore

/**
 * DirectActions — 0.2.8: a workflow-level direct `action` that runs
 * IMMEDIATELY when the workflow fires (before/independent of any
 * button tap). Supported here: set_brightness and set_volume.
 *
 * The notification is still posted as the visible record (see
 * NotifyFactory.postWorkflow, which calls this first); the outcome
 * of the direct action is written to the Logs so nothing fails
 * silently.
 *
 * The implementations mirror TrampolineActivity's button actions:
 *  - set_volume: AudioManager STREAM_MUSIC, percent -> index.
 *  - set_brightness: Settings.System; needs the WRITE_SETTINGS
 *    system grant (Setup Wizard). Without it we skip + log, never
 *    pretend it worked.
 *
 * (set_alarm is NOT handled here — the battery workflow's one-shot
 * alarm is scheduled by CareService exactly as before.)
 */
object DirectActions {

    /** Action types this executor runs immediately on fire. */
    private val DIRECT_TYPES = setOf("set_brightness", "set_volume")

    fun isDirect(type: String?): Boolean = type != null && type in DIRECT_TYPES

    /**
     * Run the workflow's direct action, if it has one.
     * @return a short English result line, or null when the workflow
     * has no direct action.
     */
    fun executeIfAny(context: Context, wf: Workflow): String? {
        val type = wf.primaryAction ?: return null
        if (!isDirect(type)) return null
        val percent = (
            wf.primaryActionArgs["percent"]
                ?: wf.primaryActionArgs["value"]
                ?: wf.settings["percent"]
            )?.toIntOrNull()
        val result = when (type) {
            "set_volume" -> setVolume(context, percent)
            "set_brightness" -> setBrightness(context, percent)
            else -> "not a direct action"
        }
        LogStore.log(context, "DIRECT-ACTION [${wf.id}] $type -> $result")
        return result
    }

    private fun setVolume(context: Context, percentRaw: Int?): String {
        val percent = (percentRaw ?: 50).coerceIn(0, 100)
        return try {
            val am = context.getSystemService(AudioManager::class.java)
                ?: return "FAILED — AudioManager not available"
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val index = Math.round(percent / 100.0 * max).toInt().coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            "OK — media volume set to $percent% (index $index/$max)"
        } catch (e: Exception) {
            "FAILED — ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun setBrightness(context: Context, percentRaw: Int?): String {
        val percent = (percentRaw ?: 50).coerceIn(0, 100)
        if (!Settings.System.canWrite(context)) {
            return "SKIPPED — 'Modify system settings' (WRITE_SETTINGS) " +
                "permission not granted; see Setup Wizard"
        }
        return try {
            val resolver = context.contentResolver
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val value = Math.round(percent / 100.0 * 255).toInt().coerceIn(1, 255)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
            "OK — brightness set to $percent% (value $value/255)"
        } catch (e: Exception) {
            "FAILED — ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
