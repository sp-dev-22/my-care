package com.spdev22.mycare.engine

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
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
 *
 * 0.2.9: a third direct action, `auto_reply`, exists but is NOT run
 * here — it needs the live StatusBarNotification, so it is executed
 * by CareNotificationListener via [executeAutoReply] below. It is
 * valid ONLY on notification_posted workflows: the reply is sent
 * through the source app's own notification Reply action
 * (RemoteInput), i.e. from the user's own account in that app.
 */
object DirectActions {

    /** Action types this executor runs immediately on fire. */
    private val DIRECT_TYPES = setOf("set_brightness", "set_volume")

    /** 0.2.9: direct action that replies on the source notification. */
    const val TYPE_AUTO_REPLY = "auto_reply"

    fun isDirect(type: String?): Boolean = type != null && type in DIRECT_TYPES

    /** True when the workflow's direct action is an auto-reply. */
    fun isAutoReply(wf: Workflow): Boolean = wf.primaryAction == TYPE_AUTO_REPLY

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

    // ---------------- 0.2.9: auto_reply ----------------

    /**
     * Send the workflow's reply text through the source
     * notification's own Reply action (the action carrying a
     * RemoteInput), from the user's own account in that app.
     *
     * Called ONLY by CareNotificationListener, which holds the live
     * StatusBarNotification at fire time and has already applied
     * the packages/keyword filters and the per-workflow cooldown.
     *
     * Privacy rule (same as the listener): the reply text and the
     * incoming message contents are NEVER written to the Logs —
     * only the app label, the workflow id and the outcome.
     *
     * @return true when the reply was handed to the source app;
     * false when this notification cannot be replied to (caller
     * then falls back to posting the workflow's notification).
     */
    fun executeAutoReply(
        context: Context,
        sbn: StatusBarNotification,
        wf: Workflow,
        appLabel: String,
        vars: Map<String, String>
    ): Boolean {
        val rawText = wf.primaryActionArgs["text"]?.trim().orEmpty()
        if (rawText.isEmpty()) {
            LogStore.log(
                context,
                "auto-reply FAILED [${wf.id}] — $appLabel: no reply text in the workflow"
            )
            return false
        }
        // {app} / {text} variables work in the reply text too
        var replyText = rawText
        for ((k, v) in vars) replyText = replyText.replace("{$k}", v)
        replyText = replyText.take(300)

        val notif = sbn.notification
        val actions = notif?.actions
        val replyAction = actions?.firstOrNull { a ->
            val inputs = try {
                a.remoteInputs
            } catch (e: Exception) {
                null
            }
            !inputs.isNullOrEmpty() && a.actionIntent != null
        }
        if (replyAction == null || notif == null) {
            LogStore.log(
                context,
                "auto-reply: no reply action on this notification — " +
                    "workflow [${wf.id}], app $appLabel"
            )
            return false
        }
        return try {
            val inputs = replyAction.remoteInputs ?: return false
            val intent = Intent()
            val bundle = Bundle()
            for (input in inputs) {
                bundle.putCharSequence(input.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(inputs, intent, bundle)
            replyAction.actionIntent?.send(context, 0, intent)
            LogStore.log(
                context,
                "AUTO-REPLY [${wf.id}] reply SENT via $appLabel"
            )
            true
        } catch (e: Exception) {
            LogStore.log(
                context,
                "auto-reply FAILED [${wf.id}] — $appLabel: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }
}
