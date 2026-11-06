package com.spdev22.mycare.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import com.spdev22.mycare.MainActivity
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.data.WorkflowOverrides
import com.spdev22.mycare.engine.DirectActions
import com.spdev22.mycare.engine.WfButton
import com.spdev22.mycare.engine.WfStep
import com.spdev22.mycare.engine.Workflow
import com.spdev22.mycare.ui.CapturesActivity
import com.spdev22.mycare.ui.PopupActivity
import com.spdev22.mycare.ui.TrampolineActivity
import java.io.File

/**
 * NotifyFactory — Workflow Format v1 se asli notifications banata hai.
 *
 * Saare button PendingIntents TrampolineActivity ko jaate hain (activity
 * PendingIntent = user ke tap se activity-start ki poori ijaazat milti
 * hai, background se bhi).
 *
 * Phase 3 jod: popup bot type (D7) — workflow popup ho to notification
 * par tap PopupActivity kholta hai jahan saare buttons milte hain;
 * multi-step flows ke liye postStep; ek-button wali plain suchna ke
 * liye postActionPlain; aur har fire ka LogStore me record.
 *
 * 0.2.5: Alarm Suggestion ka RemoteInput wala title-step HATA diya
 * gaya — ab woh sirf "Clock kholo" button deta hai (user khud Clock
 * app me alarm lagata hai; XOS par programmatic set_alarm bharosemand
 * nahi tha — user ka saaf directive).
 */
object NotifyFactory {

    const val CHANNEL_ID = "care_suggestions"

    const val EXTRA_WF = "extra_wf"
    const val EXTRA_NOTIF = "extra_notif"
    const val EXTRA_ACTION = "extra_action"
    const val EXTRA_ARGS = "extra_args"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Care Suggestions",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                channel.description = "My Care suggestions and workflow alerts"
                manager?.createNotificationChannel(channel)
            }
        }
    }

    fun notifIdFor(workflowId: String): Int =
        2000 + ((workflowId.hashCode() and 0x7fffffff) % 4000)

    /** {app} {minutes} {percent} {hisaab} jaise variables bharo. */
    fun applyVars(text: String, vars: Map<String, String>): String {
        var out = text
        for ((k, v) in vars) out = out.replace("{$k}", v)
        return out
    }

    /** Ek button ke liye TrampolineActivity ka Intent (Popup bhi yahi use karta hai). */
    fun buttonIntent(
        context: Context,
        workflowId: String,
        notifId: Int,
        btn: WfButton,
        index: Int
    ): Intent {
        return Intent(context, TrampolineActivity::class.java).apply {
            action = "com.spdev22.mycare.TRAMP_" + notifId + "_" + index
            putExtra(EXTRA_WF, workflowId)
            putExtra(EXTRA_NOTIF, notifId)
            putExtra(EXTRA_ACTION, btn.action)
            if (btn.args.isNotEmpty()) {
                val obj = org.json.JSONObject()
                for ((k, v) in btn.args) obj.put(k, v)
                putExtra(EXTRA_ARGS, obj.toString())
            }
            if (btn.next != null) putExtra("extra_next", btn.next)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun postWorkflow(
        context: Context,
        wf: Workflow,
        vars: Map<String, String> = emptyMap()
    ) {
        ensureChannel(context)
        // 0.2.3: Editor-lite overrides — caller ne raw pack workflow
        // diya ho tab bhi notification me edited title/text hi jayenge.
        // (Buttons effective() nahi badalta, isliye runtime par bane
        // khaas buttons — jaise contact ka WhatsApp button — bache rehte hain.)
        val eff = WorkflowOverrides.effective(context, wf)
        // 0.2.8: a workflow-level direct action (set_brightness /
        // set_volume) runs IMMEDIATELY on fire — the notification
        // below remains the visible record, the result is logged.
        DirectActions.executeIfAny(context, eff)
        val notifId = notifIdFor(wf.id)
        val title = applyVars(eff.title, vars)
        val text = applyVars(eff.text, vars)

        // Popup bot type: notification par tap popup kholta hai,
        // jahan 3 se zyada buttons bhi mil sakte hain (D7).
        val contentTarget = if (eff.effectiveBotType == "popup") {
            Intent(context, PopupActivity::class.java).apply {
                putExtra(PopupActivity.EXTRA_MODE, "workflow")
                putExtra(PopupActivity.EXTRA_WF, wf.id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        }
        val contentPi = PendingIntent.getActivity(
            context, notifId, contentTarget,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = newBuilder(context)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentPi)
            .setAutoCancel(true)

        for ((index, btn) in eff.buttons.take(3).withIndex()) {
            builder.addAction(
                Notification.Action.Builder(
                    iconFor(btn.action), btn.label,
                    trampolineIntent(context, wf.id, notifId, btn, index)
                ).build()
            )
        }

        manager(context)?.notify(notifId, builder.build())
        LogStore.log(context, "FIRE ${wf.id} — $title")
    }

    /**
     * 0.2.8: the intruder alert WITH the captured front-camera photo
     * as the large picture. Uses the workflow's own notification id,
     * so it upgrades the plain text alert posted moments earlier.
     * Tapping it opens the in-app captures list.
     */
    fun postIntruderPhoto(context: Context, wf: Workflow, photo: File) {
        ensureChannel(context)
        val eff = WorkflowOverrides.effective(context, wf)
        val notifId = notifIdFor(wf.id)
        val bitmap = try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(photo.absolutePath, opts)
        } catch (e: Exception) {
            null
        }
        if (bitmap == null) {
            LogStore.log(context, "Intruder photo notification skipped — image could not be decoded")
            return
        }
        val contentIntent = Intent(context, CapturesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val contentPi = PendingIntent.getActivity(
            context, notifId + 31, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = applyVars(eff.text, emptyMap()) +
            "\n📸 A photo was captured — tap to view it in the app."
        val builder = newBuilder(context)
            .setContentTitle(applyVars(eff.title, emptyMap()))
            .setContentText(text)
            .setStyle(
                Notification.BigPictureStyle()
                    .bigPicture(bitmap)
                    .bigContentTitle(applyVars(eff.title, emptyMap()))
                    .summaryText(text)
            )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
        for ((index, btn) in eff.buttons.take(3).withIndex()) {
            builder.addAction(
                Notification.Action.Builder(
                    iconFor(btn.action), btn.label,
                    trampolineIntent(context, wf.id, notifId, btn, index)
                ).build()
            )
        }
        manager(context)?.notify(notifId, builder.build())
        LogStore.log(context, "FIRE ${wf.id} — intruder alert WITH photo (${photo.name})")
    }

    /** Multi-step flow ka agla step ek nayi notification ke roop me. */
    fun postStep(
        context: Context,
        wf: Workflow,
        step: WfStep,
        vars: Map<String, String> = emptyMap()
    ) {
        ensureChannel(context)
        val notifId = notifIdFor(wf.id) + 500
        val title = applyVars(step.title, vars)
        val text = applyVars(step.text, vars)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val contentPi = PendingIntent.getActivity(
            context, notifId, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = newBuilder(context)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentPi)
            .setAutoCancel(true)

        for ((index, btn) in step.buttons.take(3).withIndex()) {
            builder.addAction(
                Notification.Action.Builder(
                    iconFor(btn.action), btn.label,
                    trampolineIntent(context, wf.id, notifId, btn, index)
                ).build()
            )
        }

        manager(context)?.notify(notifId, builder.build())
        LogStore.log(context, "STEP ${wf.id} -> ${step.id} — $title")
    }

    /** Chhoti si plain notification — confirmation ya jaankari ke liye. */
    fun postPlain(context: Context, id: Int, title: String, text: String) {
        ensureChannel(context)
        val notification = newBuilder(context)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        manager(context)?.notify(id, notification)
    }

    /** Plain notification + ek Trampoline button (jaise delete confirm). */
    fun postActionPlain(
        context: Context,
        id: Int,
        title: String,
        text: String,
        actionLabel: String,
        action: String,
        workflowId: String
    ) {
        ensureChannel(context)
        val btn = WfButton(actionLabel, action)
        val builder = newBuilder(context)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
        builder.addAction(
            Notification.Action.Builder(
                iconFor(action), actionLabel,
                trampolineIntent(context, workflowId, id, btn, 0)
            ).build()
        )
        manager(context)?.notify(id, builder.build())
        LogStore.log(context, "ACTION-NOTIF $workflowId — $title")
    }

    /** Notification jispar tap karte hi assign-group popup khulta hai. */
    fun postPopupLauncher(
        context: Context,
        id: Int,
        title: String,
        text: String,
        pkg: String,
        label: String,
        workflowId: String
    ) {
        ensureChannel(context)
        val popupIntent = Intent(context, PopupActivity::class.java).apply {
            putExtra(PopupActivity.EXTRA_MODE, "assign_group")
            putExtra(PopupActivity.EXTRA_PKG, pkg)
            putExtra(PopupActivity.EXTRA_LABEL, label)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val contentPi = PendingIntent.getActivity(
            context, id, popupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = newBuilder(context)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .build()
        manager(context)?.notify(id, notification)
        LogStore.log(context, "POPUP-LAUNCH $workflowId — $pkg")
    }

    fun cancel(context: Context, notifId: Int) {
        manager(context)?.cancel(notifId)
    }

    private fun trampolineIntent(
        context: Context,
        workflowId: String,
        notifId: Int,
        btn: WfButton,
        index: Int
    ): PendingIntent {
        return PendingIntent.getActivity(
            context, notifId * 10 + index,
            buttonIntent(context, workflowId, notifId, btn, index),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun iconFor(action: String): Int = when (action) {
        "lock_phone" -> android.R.drawable.ic_lock_idle_lock
        "open_app" -> android.R.drawable.ic_media_play
        "skip" -> android.R.drawable.ic_menu_close_clear_cancel
        "open_clock" -> android.R.drawable.ic_lock_idle_alarm
        "go_home" -> android.R.drawable.ic_menu_view
        else -> android.R.drawable.ic_dialog_info
    }

    private fun newBuilder(context: Context): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
    }

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)
}
