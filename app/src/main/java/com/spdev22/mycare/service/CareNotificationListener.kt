package com.spdev22.mycare.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.data.UserWorkflowStore
import com.spdev22.mycare.data.WorkflowOverrides
import com.spdev22.mycare.data.WorkflowPrefs
import com.spdev22.mycare.engine.HisaabBuilder
import com.spdev22.mycare.engine.Workflow
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.NotifyFactory

/**
 * CareNotificationListener — 0.2.6 ka naya `notification_posted`
 * trigger.
 *
 * Kaam: doosre apps ki post hui notifications par woh workflows
 * FIRE karna jinka trigger notification_posted hai. Workflow ke
 * trigger params:
 *   packages — (optional) comma-separated package names; ho to
 *              sirf unhi apps ki notifications par chalega.
 *   keyword  — (optional) shabd; notification ke title/text me
 *              (case-insensitive) milna chahiye.
 * Variables: {app} = notification bhejne wale app ka naam,
 *            {text} = notification ka title (title khaali ho to
 *            uska text).
 *
 * System-level grant: yeh service Android tabhi jodta hai jab user
 * Setup Wizard se Notification Access de. Grant na ho to service
 * kabhi chalti hi nahi — apne aap inert.
 *
 * Gopniyata ka pakka niyam: notification ka text KAHIN store nahi
 * hota — na prefs me, na Logs me. Log me sirf app ka label aur
 * workflow id likhta hai. Spam se bachne ke liye har workflow par
 * chhota sa cooldown hai (settings "cooldown_minutes", default
 * 1 minute) — ek hi workflow baar-baar nahi bajega.
 */
class CareNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        LogStore.log(this, "Notification listener jud gaya — notification_posted trigger live")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LogStore.log(this, "Notification listener kat gaya")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        try {
            handlePosted(posted)
        } catch (e: Exception) {
            // Listener kabhi crash na kare — phone ki har notification
            // yahin se guzarti hai
        }
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        // Apni hi notifications par kabhi fire nahi (loop suraksha)
        if (pkg == packageName) return
        // Group-summary notifications par double fire na ho
        val notif = sbn.notification ?: return
        if (notif.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val candidates = notificationWorkflows()
        if (candidates.isEmpty()) return

        val extras = notif.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val body = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val haystack = "$title $body"

        val now = System.currentTimeMillis()
        for (packWf in candidates) {
            if (!WorkflowPrefs.isEnabled(this, packWf)) continue
            val wf = WorkflowOverrides.effective(this, packWf)

            val pkgsParam = wf.triggerParams["packages"]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            if (!pkgsParam.isNullOrEmpty() && pkg !in pkgsParam) continue

            val keyword = wf.triggerParams["keyword"]?.trim()
            if (!keyword.isNullOrEmpty() &&
                !haystack.contains(keyword, ignoreCase = true)
            ) {
                continue
            }

            // Chhota cooldown — ek workflow lagataar na baje
            val cooldownMin = wf.settings["cooldown_minutes"]?.toLongOrNull() ?: 1L
            val stateKey = "state_notif_fired_${wf.id}"
            if (now - CareSettings.stateLong(this, stateKey) <
                cooldownMin.coerceAtLeast(1L) * 60_000L
            ) {
                continue
            }
            CareSettings.setStateLong(this, stateKey, now)

            val appLabel = HisaabBuilder.appLabel(this, pkg)
            val textVar = title.ifEmpty { body }
            NotifyFactory.postWorkflow(
                this, wf,
                mapOf("app" to appLabel, "text" to textVar)
            )
            // Log me SIRF app label + workflow id — text kabhi nahi
            LogStore.log(this, "notification_posted FIRE [${wf.id}] — $appLabel")
        }
    }

    /**
     * notification_posted wale workflows ki taazi list — har
     * notification par poora pack parse na ho isliye halki cache:
     * UserWorkflowStore ka version badle tabhi dobara load hota hai.
     */
    private fun notificationWorkflows(): List<Workflow> {
        val v = UserWorkflowStore.version(this)
        val cached = cache
        if (cached != null && cacheVersion == v) return cached
        val list = WorkflowEngine.loadPack(this)
            .filter { it.triggerType == "notification_posted" }
        cache = list
        cacheVersion = v
        return list
    }

    companion object {
        @Volatile
        private var cache: List<Workflow>? = null

        @Volatile
        private var cacheVersion: Long = -1L

        /** Notification Access mila hai ya nahi — Wizard ki sthiti ke liye. */
        fun isAccessGranted(context: Context): Boolean {
            return try {
                val flat = Settings.Secure.getString(
                    context.contentResolver, "enabled_notification_listeners"
                ) ?: return false
                val mine = ComponentName(
                    context, CareNotificationListener::class.java
                ).flattenToString()
                flat.split(":").any { it.equals(mine, ignoreCase = true) }
            } catch (e: Exception) {
                false
            }
        }
    }
}
