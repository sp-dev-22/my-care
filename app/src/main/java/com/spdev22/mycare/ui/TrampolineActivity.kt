package com.spdev22.mycare.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
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
 * hi ijaazat hai).
 *
 * Phase 3 ke naye actions: jump (agla step), delete_file (screenshot
 * auto-delete schedule), delete_now (system confirm ke saath delete),
 * open_app_info, open_url, open_whatsapp_chat.
 * open_app ab user ke chune hue target apps (slot) ko pehle dekhta hai.
 * 0.2.5: programmatic set_alarm (alarm_input/alarm_submit + RemoteInput
 * wala title-step) POORI tarah HATA diya gaya — XOS par woh bharosemand
 * nahi tha aur user ka saaf directive hai: naya action "open_clock"
 * sirf Clock app KHOLTA hai (Settings wala chuna hua Clock app, warna
 * system ki Alarms screen / khoj), alarm user khud set karta hai.
 * 0.2.6: do naye system actions — "set_volume" (AudioManager se media
 * volume, args percent) aur "set_brightness" (Settings.System se
 * screen brightness, args percent; WRITE_SETTINGS grant na ho to
 * imaandaar suchna, chup-chaap fail nahi).
 * 0.2.7: (A) screenshot ka timed delete ab DeleteReceiver ki
 * persisted pending-list + EXACT alarm + poll-fallback se chalta
 * hai — yahan sirf addPending darj hota hai, purana inexact alarm
 * wala code hata. (B) delete-confirm wala crash: is activity ka
 * theme pehle Theme.NoDisplay tha, jiski shart hai ki activity
 * onResume() poora hone se PEHLE finish() ho — warna framework use
 * IllegalStateException se gira deta hai. Delete-confirm hi ekmatra
 * raasta tha jo result ke intezaar me zinda rehta tha, isliye crash
 * hamesha thik consent khulne ke pal par hota tha (consent window
 * system process me pehle hi nikal chuki hoti thi — crash alert ke
 * neeche dikhti thi aur delete bhi ho jata tha). Fix: theme ab
 * Theme.Translucent.NoTitleBar hai (koi content view nahi banta,
 * isliye ab bhi poori invisible; bas zinda rehna kanooni hai) —
 * saath me consent launch par double-launch guard aur har stage
 * ka poora exception log.
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
        if (requestCode != REQ_DELETE_CONFIRM) return
        // 0.2.7: consent ka result — jo bhi ho, crash kabhi nahi;
        // har haalaat LogStore me (granted / denied / gadbad)
        try {
            LogStore.log(
                this,
                "Screenshot delete consent result: " +
                    if (resultCode == RESULT_OK) "GRANTED (deleted)"
                    else "DENIED/cancelled (code=$resultCode)"
            )
            if (resultCode == RESULT_OK) {
                // System ne confirm kiya aur file delete kar di
                CareSettings.setStateStr(this, DeleteReceiver.KEY_DELETE_URI, "")
                CareSettings.setStateStr(this, DeleteReceiver.KEY_DELETE_PATH, "")
                NotifyFactory.postPlain(
                    this, NotifyFactory.notifIdFor("screenshot_safai") + 9,
                    "Screenshot deleted 🧹",
                    "Thanks for confirming, Boss — the screenshot is cleaned up!"
                )
            }
        } catch (e: Exception) {
            LogStore.log(
                this,
                "Screenshot delete consent result handling FAIL: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }
        finish()
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
                        toast("Could not go Home — try turning Accessibility ON")
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
                        toast("App Info did not open, Boss")
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
                        toast("The link did not open, Boss")
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
                        toast("The WhatsApp chat did not open, Boss")
                    }
                } else {
                    // Number nahi mila — sirf WhatsApp khol do
                    openApp(mapOf("package" to "com.whatsapp"))
                }
            }

            "open_clock" -> {
                // 0.2.5: Alarm Suggestion ka ekmatra kaam — Clock kholo,
                // alarm user khud set karega (set_alarm hata diya gaya)
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                openClockApp()
            }

            "set_volume" -> {
                // 0.2.6: media volume seedha AudioManager se set karo
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                setMediaVolume(args["percent"]?.toIntOrNull())
            }

            "set_brightness" -> {
                // 0.2.6: screen brightness Settings.System se — iske
                // liye WRITE_SETTINGS wala system grant chahiye
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                setBrightness(args["percent"]?.toIntOrNull())
            }

            "jump" -> {
                // Multi-step flow ka agla step post karo (screenshot flow)
                if (notifId > 0) NotifyFactory.cancel(this, notifId)
                val nextId = intent.getStringExtra("extra_next") ?: ""
                val wf = WorkflowEngine.loadPack(this).firstOrNull { it.id == wfId }
                val step = wf?.steps?.firstOrNull { it.id == nextId }
                if (wf != null && step != null) {
                    NotifyFactory.postStep(this, wf, step)
                } else {
                    toast("The next step was not found, Boss")
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
                toast("This action is not available right now, Boss")
                LogStore.log(this, "Unknown action: $action")
            }
        }
    }

    /**
     * Screenshot auto-delete ki ghadi set karo.
     * 0.2.7: purana inexact alarm (setAndAllowWhileIdle) Doze/
     * batching me 5 min ko 10–15 min bana deta tha. Ab delete
     * DeleteReceiver ki PERSISTED pending-list me darj hota hai —
     * EXACT alarm + CareService ke poll ka overdue-check, jo pehle
     * pahunche wahi chalata hai (entry pehle list se hat ti hai,
     * isliye kaam ek hi baar hota hai).
     */
    private fun scheduleScreenshotDelete(after: String) {
        val uriStr = CareSettings.stateStr(this, "state_screenshot_uri")
        val pathStr = CareSettings.stateStr(this, "state_screenshot_path")
        if (uriStr.isNullOrEmpty() && pathStr.isNullOrEmpty()) {
            toast("No record of the screenshot was found, Boss")
            return
        }
        // Legacy keys bhi taaza rakho — delete_now (turant wala
        // raasta) aur consent ke baad ki safai yahi se padhte hain
        CareSettings.setStateStr(this, DeleteReceiver.KEY_DELETE_URI, uriStr ?: "")
        CareSettings.setStateStr(this, DeleteReceiver.KEY_DELETE_PATH, pathStr ?: "")
        val delayMs = when (after) {
            "5m" -> 5 * 60_000L
            "1d" -> 24 * 60 * 60_000L
            else -> 60 * 60_000L // "1h"
        }
        val label = when (after) {
            "5m" -> "5 minutes"
            "1d" -> "1 day"
            else -> "1 hour"
        }
        DeleteReceiver.addPending(
            this, pathStr ?: "", uriStr ?: "",
            System.currentTimeMillis() + delayMs, label
        )
        toast("OK Boss — the screenshot will be deleted in $label")
        LogStore.log(this, "Screenshot delete scheduled: $after")
    }

    /**
     * Abhi delete karo — 0.2.5 ke folder-watch ke saath pehle file
     * PATH se seedha delete ki koshish, phir MediaStore URI wala
     * raasta: direct delete fail ho to system ka confirmation dialog
     * (createDeleteRequest) kholo.
     */
    private fun deleteNowWithConfirm() {
        // Stage 1/3: Path wala seedha raasta (kuch builds par kaam kar jata hai)
        val pathStr = CareSettings.stateStr(this, DeleteReceiver.KEY_DELETE_PATH)
            ?.takeIf { it.isNotEmpty() }
            ?: CareSettings.stateStr(this, "state_screenshot_path")
        if (!pathStr.isNullOrEmpty()) {
            try {
                val ok = java.io.File(pathStr).delete()
                LogStore.log(
                    this,
                    "delete_now stage 1/3 File.delete: $ok (${java.io.File(pathStr).name})"
                )
                if (ok) {
                    CareSettings.setStateStr(this, DeleteReceiver.KEY_DELETE_URI, "")
                    CareSettings.setStateStr(this, DeleteReceiver.KEY_DELETE_PATH, "")
                    toast("Screenshot deleted 🧹")
                    LogStore.log(this, "Screenshot delete_now SUCCESS (file path)")
                    return
                }
            } catch (e: Exception) {
                LogStore.log(
                    this,
                    "delete_now stage 1/3 File.delete EXCEPTION: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
        val uriStr = CareSettings.stateStr(this, DeleteReceiver.KEY_DELETE_URI)
            ?.takeIf { it.isNotEmpty() }
            ?: CareSettings.stateStr(this, "state_screenshot_uri")
                ?.takeIf { it.isNotEmpty() }
            ?: if (!pathStr.isNullOrEmpty()) {
                DeleteReceiver.mediaUriForPath(this, pathStr)?.toString()
            } else {
                null
            }
        if (uriStr.isNullOrEmpty()) {
            toast("No screenshot was found to delete")
            LogStore.log(this, "delete_now FAIL: na path chala, na URI mila")
            return
        }
        // Stage 2/3: MediaStore se seedha delete
        val uri = Uri.parse(uriStr)
        try {
            val rows = contentResolver.delete(uri, null, null)
            LogStore.log(this, "delete_now stage 2/3 MediaStore rows=$rows")
            if (rows > 0) {
                CareSettings.setStateStr(this, DeleteReceiver.KEY_DELETE_URI, "")
                CareSettings.setStateStr(this, DeleteReceiver.KEY_DELETE_PATH, "")
                toast("Screenshot deleted 🧹")
                LogStore.log(this, "Screenshot delete_now SUCCESS")
                return
            }
            // rows == 0 — neeche consent wala raasta
        } catch (e: SecurityException) {
            // RecoverableSecurityException (API 29+) bhi isi me aata hai —
            // neeche createDeleteRequest wala confirm raasta chalega
            LogStore.log(
                this,
                "delete_now stage 2/3 SecurityException " +
                    "(${e.javaClass.simpleName}) — consent route"
            )
        } catch (e: Exception) {
            LogStore.log(
                this,
                "delete_now stage 2/3 FAIL: ${e.javaClass.simpleName}: ${e.message}"
            )
            toast("The delete did not happen, Boss")
            return
        }
        // Stage 3/3: Android 11+ ka aadhikarik confirmation raasta.
        // 0.2.7 ki hifazatein: (a) consent EK hi baar launch hoga —
        // pehle se intezaar chal raha ho to dobara nahi; (b) launch
        // ya uski taiyaari me koi bhi exception aaye to poora
        // class+message LogStore me aur waiting flag reset — taaki
        // activity latki na rahe.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (waitingForDeleteConfirm) {
                LogStore.log(
                    this,
                    "delete_now stage 3/3: consent pehle se khula hai — " +
                        "not launched again"
                )
                return
            }
            try {
                val pending = MediaStore.createDeleteRequest(
                    contentResolver, listOf(uri)
                )
                waitingForDeleteConfirm = true
                startIntentSenderForResult(
                    pending.intentSender, REQ_DELETE_CONFIRM, null, 0, 0, 0
                )
                LogStore.log(
                    this,
                    "delete_now stage 3/3: consent launch OK — user ke " +
                        "jawab ka intezaar"
                )
            } catch (e: Exception) {
                waitingForDeleteConfirm = false
                toast("The confirmation did not open — please delete it from the Gallery")
                LogStore.log(
                    this,
                    "delete_now consent launch FAIL: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        } else {
            toast("On this Android, My Care cannot delete directly — please delete it from the Gallery")
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
        toast("Lock needs Device Admin — opening the Setup Wizard")
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
        toast("That app was not found on this phone, Boss")
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
     * 0.2.5: Clock app KHOLO — alarm set karna user ka kaam hai.
     *
     * Koshishon ka kram:
     *  1. Settings me chuna hua Clock app (slot) — pehle uski Alarms
     *     screen (ACTION_SHOW_ALARMS targeted), phir uska launcher.
     *  2. Bina target ke system ki Alarms screen.
     *  3. Jaane-maane clock packages, phir launcher apps me naam/
     *     package se "clock" ki khoj.
     * Har koshish LogStore me likhti hai — kaun sa package khula.
     */
    private fun openClockApp() {
        val slotPkg = CareSettings.targetApp(this, "clock")
        if (!slotPkg.isNullOrEmpty()) {
            val alarms = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .setPackage(slotPkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(alarms)) {
                LogStore.log(this, "Clock opened: $slotPkg (SHOW_ALARMS, chosen app)")
                return
            }
            val launch = packageManager.getLaunchIntentForPackage(slotPkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (tryStart(launch)) {
                    LogStore.log(this, "Clock opened: $slotPkg (launcher, chosen app)")
                    return
                }
            }
            LogStore.log(this, "Clock: the chosen app ($slotPkg) did not open — auto search")
        }
        val generic = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(generic)) {
            LogStore.log(this, "Clock opened: auto (SHOW_ALARMS)")
            return
        }
        val candidates = listOf(
            "com.google.android.deskclock", "com.android.deskclock",
            "com.transsion.clock", "com.infinix.deskclock"
        )
        for (pkg in candidates) {
            val launch = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(launch)) {
                LogStore.log(this, "Clock opened: $pkg (package search)")
                return
            }
        }
        try {
            val launcherApps = packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
            for (ri in launcherApps) {
                val pkg = ri.activityInfo?.packageName ?: continue
                val label = try {
                    ri.loadLabel(packageManager).toString()
                } catch (e: Exception) {
                    ""
                }
                if (pkg.contains("clock", ignoreCase = true) ||
                    label.contains("clock", ignoreCase = true)
                ) {
                    val launch = packageManager.getLaunchIntentForPackage(pkg)
                        ?: continue
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (tryStart(launch)) {
                        LogStore.log(this, "Clock opened: $pkg (launcher search)")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            // khoj fail — neeche ka sandesh hi kaafi hai
        }
        toast("No Clock app found, Boss — choose your Clock app in Settings")
        LogStore.log(this, "Clock open FAILED: no clock app found")
    }

    /**
     * 0.2.6: media (music/video) volume percent ke hisaab se set karo.
     * AudioManager ka index 0..max hota hai — percent usi par map hota
     * hai. MODIFY_AUDIO_SETTINGS chahiye (manifest me hai; koi khaas
     * grant nahi lagta). MEDIA stream isliye ki care workflows ka
     * matlab music/video ki awaaz se hai, ringtone se nahi.
     */
    private fun setMediaVolume(percentRaw: Int?) {
        val percent = (percentRaw ?: 50).coerceIn(0, 100)
        try {
            val am = getSystemService(AudioManager::class.java)
            if (am == null) {
                toast("The volume service was not found, Boss")
                LogStore.log(this, "set_volume FAIL: AudioManager null")
                return
            }
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val index = Math.round(percent / 100.0 * max).toInt().coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            LogStore.log(this, "Volume set: $percent% (index $index/$max)")
        } catch (e: Exception) {
            toast("The volume could not be set, Boss")
            LogStore.log(this, "set_volume FAIL: ${e.message}")
        }
    }

    /**
     * 0.2.6: screen brightness percent ke hisaab se set karo.
     * Settings.System.WRITE_SETTINGS ek SYSTEM-LEVEL grant hai —
     * user Setup Wizard se deta hai. Grant na ho to chup-chaap fail
     * hone ke bajaaye saaf suchna (notification + log) di jati hai.
     */
    private fun setBrightness(percentRaw: Int?) {
        val percent = (percentRaw ?: 50).coerceIn(0, 100)
        if (!Settings.System.canWrite(this)) {
            NotifyFactory.postPlain(
                this, NotifyFactory.notifIdFor("brightness_permission") + 7,
                "Brightness needs a permission ⚠️",
                "Boss, the system permission to change brightness is not granted " +
                    "yet. My Care -> Setup Wizard -> 'System Settings " +
                    "(Brightness)' step and grant it — then this button will work."
            )
            LogStore.log(this, "set_brightness SKIPPED: WRITE_SETTINGS grant missing")
            return
        }
        try {
            val resolver = contentResolver
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val value = Math.round(percent / 100.0 * 255).toInt().coerceIn(1, 255)
            Settings.System.putInt(
                resolver, Settings.System.SCREEN_BRIGHTNESS, value
            )
            LogStore.log(this, "Brightness set: $percent% (value $value/255)")
        } catch (e: Exception) {
            toast("The brightness could not be set, Boss")
            LogStore.log(this, "set_brightness FAIL: ${e.message}")
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
