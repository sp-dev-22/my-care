package com.spdev22.mycare.ui

import android.app.Activity
import android.app.RemoteInput
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log
import android.widget.Toast
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.NotifyFactory
import com.spdev22.mycare.receiver.CareDeviceAdminReceiver
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
 */
class TrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Action fail: ${e.message}")
        }
        finish()
    }

    private fun handle(intent: Intent) {
        val action = intent.getStringExtra(NotifyFactory.EXTRA_ACTION) ?: return
        val wfId = intent.getStringExtra(NotifyFactory.EXTRA_WF) ?: ""
        val notifId = intent.getIntExtra(NotifyFactory.EXTRA_NOTIF, -1)
        val args = parseArgs(intent.getStringExtra(NotifyFactory.EXTRA_ARGS))

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

            "alarm_input" -> {
                // Alarm Suggestion ka step 2: title likhne wali notification
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                val wf = WorkflowEngine.loadPack(this).firstOrNull { it.id == wfId }
                if (wf != null) NotifyFactory.postAlarmInput(this, wf)
            }

            "alarm_submit" -> submitAlarm(intent, notifId)

            else -> {
                // jump / delete_file / assign_group jaise actions Phase 3 ke hain
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                toast("Yeh action Phase 3 me aayega, Boss")
            }
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
        // 1) Music jaise category wale iraade pehle
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
    }
}
