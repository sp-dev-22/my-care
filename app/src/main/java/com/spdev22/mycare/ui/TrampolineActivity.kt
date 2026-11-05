package com.spdev22.mycare.ui

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.NotifyFactory
import com.spdev22.mycare.receiver.CareDeviceAdminReceiver
import com.spdev22.mycare.receiver.DeleteReceiver
import com.spdev22.mycare.service.CareAccessibilityService
import com.spdev22.mycare.setup.SetupWizardActivity
import org.json.JSONObject

/**
 * TrampolineActivity — notification ke saare buttons yahin aate hain
 * (invisible activity; kaam karke turant finish).
 *
 * Activity PendingIntent se aane ki wajah se background me bhi
 * doosra app kholna / clock kholna allowed rehta hai (user ka tap
 * hi ijaazat hai). RemoteInput ka jawab bhi yahin process hota hai.
 *
 * Phase 3 ke naye actions: jump (agla step), delete_file (screenshot
 * auto-delete schedule), delete_now (system confirm ke saath delete),
 * open_app_info, open_url, open_whatsapp_chat.
 * open_app ab user ke chune hue target apps (slot) ko pehle dekhta hai.
 */
class TrampolineActivity : Activity() {

    /** delete_now ke system-confirmation ke dauraan activity ko zinda rakhna hai. */
    private var waitingForDeleteConfirm = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Action fail: ${e.message}")
            LogStore.log(this, "Action fail: ${e.message}")
        }
        if (!waitingForDeleteConfirm) finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE_CONFIRM) {
            val uriStr = CareSettings.stateStr(this, DeleteReceiver.KEY_DELETE_URI)
            if (resultCode == RESULT_OK) {
                // System ne confirm kiya aur file delete kar di
                if (!uriStr.isNullOrEmpty()) {
                    CareSettings.setStateStr(this, DeleteReceiver.KEY_DELETE_URI, "")
                }
                NotifyFactory.postPlain(
                    this, NotifyFactory.notifIdFor("screenshot_safai") + 9,
                    "Screenshot delete ho gaya 🧹",
                    "Confirm karne ke liye dhanyavaad, Boss — screenshot saaf!"
                )
                LogStore.log(this, "Screenshot delete confirm OK")
            } else {
                LogStore.log(this, "Screenshot delete confirm cancel hua")
            }
            finish()
        }
    }

    private fun handle(intent: Intent) {
        val action = intent.getStringExtra(NotifyFactory.EXTRA_ACTION) ?: return
        val wfId = intent.getStringExtra(NotifyFactory.EXTRA_WF) ?: ""
        val notifId = intent.getIntExtra(NotifyFactory.EXTRA_NOTIF, -1)
        val args = parseArgs(intent.getStringExtra(NotifyFactory.EXTRA_ARGS))
        LogStore.log(this, "ACTION $action (wf=$wfId)")

        when (action) {
            "skip" -> {
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                if (wfId == "rest_nudge") {
                    // Boss ne kaha abhi nahi — 30 min tak dobara tang mat karo
                    CareSettings.setRestSnoozeUntil(
                        this, System.currentTimeMillis() + 30 * 60_000L
                    )
                }
            }

            "lock_phone" -> {
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                lockPhone()
            }

            "go_home" -> {
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                if (!CareAccessibilityService.goHome()) {
                    // Accessibility band hai — seedha HOME intent se jaao
                    try {
                        startActivity(
                            Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_HOME)
                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        toast("Home nahi ja paya — Accessibility ON karke dekhein")
                    }
                }
            }

            "open_app" -> {
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                openApp(args)
            }

            "open_app_info" -> {
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                val pkg = args["package"] ?: ""
                if (pkg.isNotEmpty()) {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:$pkg"))
                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        toast("App Info nahi khula, Boss")
                    }
                }
            }

            "open_url" -> {
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                val url = args["url"] ?: ""
                if (url.isNotEmpty()) {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        toast("Link nahi khula, Boss")
                    }
                }
            }

            "open_whatsapp_chat" -> {
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                val digits = (args["number"] ?: "").filter { it.isDigit() }
                if (digits.isNotEmpty()) {
                    try {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://wa.me/$digits")
                            ).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        toast("WhatsApp chat nahi khula, Boss")
                    }
                } else {
                    // Number nahi mila — sirf WhatsApp khol do
                    openApp(mapOf("package" to "com.whatsapp"))
                }
            }

            "alarm_input" -> {
                // Alarm Suggestion ka step 2: title likhne wali notification
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                val wf = WorkflowEngine.loadPack(this).firstOrNull { it.id == wfId }
                if (wf != null) NotifyFactory.postAlarmInput(this, wf)
            }

            "alarm_submit" -> submitAlarm(intent, notifId)

            "jump" -> {
                // Multi-step flow ka agla step post karo (screenshot flow)
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                val nextId = intent.getStringExtra("extra_next") ?: ""
                val wf = WorkflowEngine.loadPack(this).firstOrNull { it.id == wfId }
                val step = wf?.steps?.firstOrNull { it.id == nextId }
                if (wf != null && step != null) {
                    NotifyFactory.postStep(this, wf, step)
                } else {
                    toast("Agla step nahi mila, Boss")
                }
            }

            "delete_file" -> {
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                scheduleScreenshotDelete(args["after"] ?: "1h")
            }

            "delete_now" -> {
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                deleteNowWithConfirm()
            }

            else -> {
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                toast("Yeh action abhi uplabdh nahi hai, Boss")
                LogStore.log(this, "Unknown action: $action")
            }
        }
    }

    /** Screenshot auto-delete ko AlarmManager par set karo. */
    private fun scheduleScreenshotDelete(after: String) {
        val uriStr = CareSettings.stateStr(this, "state_screenshot_uri")
        if (uriStr.isNullOrEmpty()) {
            toast("Screenshot ka record nahi mila, Boss")
            return
        }
        // DeleteReceiver yahi URI padhta hai
        CareSettings.setStateStr(this, DeleteReceiver.KEY_DELETE_URI, uriStr)
        val delayMs = when (after) {
            "5m" -> 5 * 60_000L
            "1d" -> 24 * 60 * 60_000L
            else -> 60 * 60_000L // "1h"
        }
        val label = when (after) {
            "5m" -> "5 minutes"
            "1d" -> "1 din"
            else -> "1 ghanta"
        }
        val am = getSystemService(AlarmManager::class.java)
        val intent = Intent(this, DeleteReceiver::class.java).apply {
            action = "com.spdev22.mycare.DELETE_SCREENSHOT"
        }
        val pi = PendingIntent.getBroadcast(
            this, 7771, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am?.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs, pi
            )
            toast("Theek hai Boss — screenshot $label me delete hoga")
            LogStore.log(this, "Screenshot delete scheduled: $after")
        } catch (e: Exception) {
            toast("Delete schedule nahi ho paya, Boss")
            LogStore.log(this, "Delete schedule fail: ${e.message}")
        }
    }

    /**
     * Abhi delete karo — pehle direct koshish, Android mana kare to
     * system ka confirmation dialog (createDeleteRequest) kholo.
     */
    private fun deleteNowWithConfirm() {
        val uriStr = CareSettings.stateStr(this, DeleteReceiver.KEY_DELETE_URI)
            ?: CareSettings.stateStr(this, "state_screenshot_uri")
        if (uriStr.isNullOrEmpty()) {
            toast("Delete karne ke liye screenshot nahi mila")
            return
        }
        val uri = Uri.parse(uriStr)
        try {
            val rows = contentResolver.delete(uri, null, null)
            if (rows > 0) {
                CareSettings.setStateStr(this, DeleteReceiver.KEY_DELETE_URI, "")
                toast("Screenshot delete ho gaya 🧹")
                LogStore.log(this, "Screenshot delete_now SUCCESS")
                return
            }
        } catch (e: SecurityException) {
            // RecoverableSecurityException (API 29+) bhi isi me aata hai —
            // neeche createDeleteRequest wala confirm raasta chalega
            LogStore.log(this, "delete_now: security — confirm route")
        } catch (e: Exception) {
            toast("Delete nahi hua, Boss")
            return
        }
        // Android 11+ ka aadhikarik confirmation raasta
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pending = MediaStore.createDeleteRequest(
                    contentResolver, listOf(uri)
                )
                waitingForDeleteConfirm = true
                startIntentSenderForResult(
                    pending.intentSender, REQ_DELETE_CONFIRM, null, 0, 0, 0
                )
            } catch (e: Exception) {
                toast("Confirmation nahi khula — Gallery se delete kar dijiye")
                LogStore.log(this, "createDeleteRequest fail: ${e.message}")
            }
        } else {
            toast("Is Android par My Care seedha delete nahi kar sakta — Gallery se kar dijiye")
        }
    }

    private fun lockPhone() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, CareDeviceAdminReceiver::class.java)
        if (dpm != null && dpm.isAdminActive(admin)) {
            try {
                dpm.lockNow()
                return
            } catch (e: Exception) {
                Log.w(TAG, "lockNow fail: ${e.message}")
            }
        }
        toast("Lock ke liye Device Admin chahiye — Setup Wizard khol raha hun")
        startActivity(
            Intent(this, SetupWizardActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun openApp(args: Map<String, String>) {
        // 0) User ka chuna hua target app (App Picker) — sabse pehle wahi
        val slot = args["slot"] ?: ""
        if (slot.isNotEmpty()) {
            val chosen = CareSettings.targetApp(this, slot)
            if (!chosen.isNullOrEmpty()) {
                val launch = packageManager.getLaunchIntentForPackage(chosen)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (tryStart(launch)) return
                }
            }
        }
        // 1) Music jaise category wale iraade
        if (args["kind"] == "music") {
            val musicIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_APP_MUSIC)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(musicIntent)) return
        }
        // 2) Package list — pehla installed app khulega
        val candidates = mutableListOf<String>()
        args["package"]?.let { if (it.isNotEmpty()) candidates.add(it) }
        args["packages"]?.split(",")?.forEach {
            val p = it.trim()
            if (p.isNotEmpty()) candidates.add(p)
        }
        for (pkg in candidates) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (tryStart(launch)) return
            }
        }
        toast("Woh app is phone me nahi mila, Boss")
    }

    private fun tryStart(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Alarm title ka RemoteInput jawab — title se alarm lagao.
     * CHOICE (simple rakha gaya hai): title me time likha ho (jaise
     * "7:30" ya "7 baje") to wahi time, warna agla subah 6:00.
     */
    private fun submitAlarm(intent: Intent, notifId: Int) {
        if (notifId > 0) NotifyFactory.cancel(this, notifId)
        val results = RemoteInput.getResultsFromIntent(intent)
        val raw = results?.getCharSequence(NotifyFactory.KEY_ALARM_TITLE)
            ?.toString()?.trim().orEmpty()

        var hour = 6
        var minute = 0
        var title = raw

        val colonTime = Regex("""(\d{1,2})[:.](\d{2})""").find(raw)
        val bajeTime = Regex("""(\d{1,2})\s*baje""").find(raw)
        when {
            colonTime != null -> {
                hour = colonTime.groupValues[1].toInt().coerceIn(0, 23)
                minute = colonTime.groupValues[2].toInt().coerceIn(0, 59)
                title = raw.replace(colonTime.value, "").trim()
            }
            bajeTime != null -> {
                hour = bajeTime.groupValues[1].toInt().coerceIn(0, 23)
                minute = 0
                title = raw.replace(bajeTime.value, "").trim()
            }
        }
        if (title.isEmpty()) title = "Subah ka alarm"

        val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, title)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val confirmId = NotifyFactory.notifIdFor("alarm_suggestion") + 5
        if (tryStart(alarmIntent)) {
            NotifyFactory.postPlain(
                this, confirmId,
                "Alarm lag gaya ⏰",
                "\"$title\" — ${"%02d:%02d".format(hour, minute)} baje ka alarm " +
                    "set ho gaya hai. Kal subah milte hain, Boss!"
            )
        } else {
            NotifyFactory.postPlain(
                this, confirmId,
                "Alarm nahi laga 😔",
                "Clock app nahi mila, Boss. Clock app se khud " +
                    "${"%02d:%02d".format(hour, minute)} ka alarm laga dijiye."
            )
        }
    }

    private fun parseArgs(json: String?): Map<String, String> {
        if (json.isNullOrEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optString(k)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "CareTrampoline"
        private const val REQ_DELETE_CONFIRM = 4711
    }
}
