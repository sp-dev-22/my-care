package com.spdev22.mycare.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.ContactsContract
import android.util.Log
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.data.UserWorkflowStore
import com.spdev22.mycare.data.WorkflowOverrides
import com.spdev22.mycare.data.WorkflowPrefs
import com.spdev22.mycare.engine.CareChecks
import com.spdev22.mycare.engine.HisaabBuilder
import com.spdev22.mycare.engine.UsageTracker
import com.spdev22.mycare.engine.WfButton
import com.spdev22.mycare.engine.Workflow
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.DailyScheduler
import com.spdev22.mycare.notify.NotifyFactory
import com.spdev22.mycare.receiver.AlarmNotifyReceiver
import com.spdev22.mycare.receiver.DeleteReceiver
import java.io.File
import java.util.Calendar

/**
 * CareService — LIVE trigger engine (Phase 2 + Phase 3).
 *
 * Kaam:
 *  - Har 20 second me UsageEvents poll karke usage-based triggers
 *    (app_hopping, single_app_over, continuous_use_over) jaanchna
 *  - Din ka pehla unlock pakadna (first_unlock → Welcome Boss)
 *  - Roz wale time-triggers ko DailyScheduler se set rakhna
 *  - Phase 3: battery (full/low), paani interval, naya contact
 *    observer, naya app install (poll-diff), cache aur
 *    unused-apps ke dheere checks (CareChecks)
 *  - 0.2.5: screenshot ki pehchan ab FOLDER-WATCH se hai —
 *    Screenshots folders ko poll me scan karke "nayi file" pakadna
 *    (MediaStore ContentObserver XOS par bharosemand nahi tha —
 *    user ka saaf directive). User ke Gemini-se bane workflows bhi
 *    yahi chalte hain — UserWorkflowStore ka version badhte hi
 *    service apni list taaza kar leti hai.
 *
 * Saare thresholds CareSettings se aate hain (user ne badle ho to
 * woh, warna pack ke defaults) — D3 ka niyam.
 * Cooldowns CareSettings ki state me rehte hain taaki koi workflow
 * spam na kare.
 */
class CareService : Service() {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var tracker: UsageTracker? = null
    private var pack: List<Workflow> = emptyList()
    private var unlockReceiver: BroadcastReceiver? = null
    private var contactObserver: ContentObserver? = null
    private var lastContactCheckMs: Long = 0L
    /** User workflows ka dekha hua version — badle to list taaza karo. */
    private var wfVersionSeen: Long = -1L
    private var lastShotPermLogMs: Long = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                pollOnce()
            } catch (e: Exception) {
                Log.w(TAG, "Poll fail: ${e.message}")
            }
            handler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        NotifyFactory.ensureChannel(this)
        startForeground(NOTIF_ID, buildNotification())

        tracker = UsageTracker(this)
        pack = WorkflowEngine.loadPack(this)
        wfVersionSeen = UserWorkflowStore.version(this)
        DailyScheduler.scheduleAll(this)
        registerUnlockReceiver()

        thread = HandlerThread("care-engine").also { it.start() }
        handler = Handler(thread!!.looper)
        registerObservers()
        handler?.post(pollRunnable)
        Log.i(TAG, "Care engine chalu — ${pack.size} workflows load hue")
        LogStore.log(this, "Care Service chalu — ${pack.size} workflows live")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Settings badle hon to naye time par alarms taaza kar do
        DailyScheduler.scheduleAll(this)
        return START_STICKY
    }

    override fun onDestroy() {
        handler?.removeCallbacks(pollRunnable)
        contactObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        thread?.quitSafely()
        unlockReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // pehle se unregister — koi baat nahi
            }
        }
        super.onDestroy()
    }

    // ---------------- poll + evaluation ----------------

    private fun pollOnce() {
        val t = tracker ?: return
        val now = System.currentTimeMillis()

        // 0.2.5: user ne naya workflow banaya/delete kiya ho to
        // bina service restart kiye taazi list utha lo — roz wale
        // time alarms bhi nayi list se dobara set kar do.
        val wfVersion = UserWorkflowStore.version(this)
        if (wfVersionSeen >= 0 && wfVersion != wfVersionSeen) {
            pack = WorkflowEngine.loadPack(this)
            DailyScheduler.scheduleAll(this)
            LogStore.log(this, "Workflows taaza hue — kul ${pack.size} (pack + user ke banaye)")
        }
        wfVersionSeen = wfVersion

        // 0.2.5: screenshot folder-watch (poll ke saath)
        checkScreenshotFolders(now)

        // 0.2.7: screenshot pending deletes ka jaal — EXACT alarm
        // kisi wajah se fail ho jaye (Doze/OEM ki marzi) to poll
        // hi overdue delete nipta dega; entry pehle list se hat
        // ti hai isliye alarm se dobara kaam nahi hoga (dedupe)
        try {
            DeleteReceiver.executeDueDeletes(this, "poll")
        } catch (e: Exception) {
            Log.w(TAG, "Pending delete check fail: ${e.message}")
        }

        val usageOk = t.poll() // Usage Access nahi to usage triggers band, baaki chalte rahenge
        val batt = batteryInfo()
        for (packWf in pack) {
            // 0.2.3: Editor-lite overrides lagu karke effective
            // workflow banao — saare limits/message yahi se milenge
            val wf = WorkflowOverrides.effective(this, packWf)
            // 0.2.2: user ka on/off toggle hi final — OFF workflow kabhi fire nahi
            if (!WorkflowPrefs.isEnabled(this, wf)) continue
            when (wf.triggerType) {
                "app_hopping" -> if (usageOk) evalBoredom(wf, t, now)
                "single_app_over" -> if (usageOk) evalLongApp(wf, t, now)
                "continuous_use_over" -> if (usageOk) evalRest(wf, t, now)
                "interval" -> evalInterval(wf, now)
                "battery_level" -> evalBattery(wf, batt, now)
                "cache_over" -> CareChecks.checkCache(this, wf)
                "unused_apps" -> CareChecks.checkUnused(this, wf)
                "app_installed" -> evalNewApps(wf, now)
            }
        }
    }

    private fun evalBoredom(wf: Workflow, t: UsageTracker, now: Long) {
        val needApps = settingInt(
            wf, CareSettings.KEY_BOREDOM_APPS, "apps",
            CareSettings.DEFAULT_BOREDOM_APPS
        )
        val maxSec = settingInt(
            wf, CareSettings.KEY_BOREDOM_SECONDS, "seconds",
            CareSettings.DEFAULT_BOREDOM_SECONDS
        )
        val windowMin = wf.triggerParams["window_minutes"]?.toIntOrNull() ?: 5
        val cooldownMin = wf.settings["cooldown_minutes"]?.toLongOrNull() ?: 45L

        if (now - CareSettings.boredomLastFired(this) < cooldownMin * 60_000L) return
        if (t.boredomDetected(now, needApps, maxSec * 1000L, windowMin * 60_000L)) {
            NotifyFactory.postWorkflow(this, wf)
            CareSettings.setBoredomLastFired(this, now)
            Log.i(TAG, "Boredom Dost fire hua")
        }
    }

    private fun evalLongApp(wf: Workflow, t: UsageTracker, now: Long) {
        val pkg = t.currentPkg ?: return
        if (t.isSystemOrLauncher(pkg)) return
        val minutes = settingInt(
            wf, CareSettings.KEY_LONG_MINUTES, "minutes",
            CareSettings.DEFAULT_LONG_MINUTES
        )
        val sessionMs = t.currentSessionMs(now)
        if (sessionMs < minutes * 60_000L) return
        val key = pkg + "@" + t.currentStart
        if (CareSettings.longAppFiredKey(this) == key) return // is session me ek baar bas

        val elapsedMin = (sessionMs / 60_000L).toInt()
        NotifyFactory.postWorkflow(
            this, wf,
            mapOf(
                "app" to HisaabBuilder.appLabel(this, pkg),
                "minutes" to elapsedMin.toString()
            )
        )
        CareSettings.setLongAppFiredKey(this, key)
        Log.i(TAG, "Long-App Suggestion fire: $pkg (${elapsedMin}m)")
    }

    private fun evalRest(wf: Workflow, t: UsageTracker, now: Long) {
        if (now < CareSettings.restSnoozeUntil(this)) return
        val minutes = settingInt(
            wf, CareSettings.KEY_REST_MINUTES, "minutes",
            CareSettings.DEFAULT_REST_MINUTES
        )
        val stretchMs = t.currentStretchMs(now)
        if (stretchMs < minutes * 60_000L) return
        if (t.stretchStart == 0L) return
        if (CareSettings.restFiredFor(this) == t.stretchStart) return

        NotifyFactory.postWorkflow(
            this, wf,
            mapOf("minutes" to (stretchMs / 60_000L).toInt().toString())
        )
        CareSettings.setRestFiredFor(this, t.stretchStart)
        Log.i(TAG, "Rest Nudge fire: stretch ${(stretchMs / 60_000L)}m")
    }

    /**
     * User setting (agar badli ho) > pack param > plan default.
     * 0.2.3: sabse pehle Editor-lite ka per-workflow override —
     * yeh is workflow ke liye user ki sabse khaas pasand hai.
     */
    private fun settingInt(
        wf: Workflow,
        userKey: String,
        paramKey: String,
        default: Int
    ): Int {
        WorkflowOverrides.overrideValue(this, wf.id, paramKey)
            ?.toIntOrNull()?.let { return it }
        val fallback = wf.triggerParams[paramKey]?.toIntOrNull()
            ?: wf.settings[paramKey]?.toIntOrNull()
            ?: default
        return CareSettings.intVal(this, userKey, fallback)
    }

    // ---------------- Phase 3: interval (Paani) ----------------

    private fun evalInterval(wf: Workflow, now: Long) {
        val hours = if (wf.id == "paani_reminder") {
            // 0.2.3: Editor-lite override > Settings wala ghanta > pack
            WorkflowOverrides.overrideValue(this, wf.id, "hours")?.toIntOrNull()
                ?: CareSettings.intVal(
                    this, CareSettings.KEY_PAANI_HOURS,
                    wf.triggerParams["hours"]?.toIntOrNull()
                        ?: CareSettings.DEFAULT_PAANI_HOURS
                )
        } else {
            wf.triggerParams["hours"]?.toIntOrNull() ?: 2
        }
        val key = "state_interval_${wf.id}"
        val last = CareSettings.stateLong(this, key)
        if (last == 0L) {
            // Pehli baar — baseline abhi se, turant fire nahi
            CareSettings.setStateLong(this, key, now)
            return
        }
        // Raat ko tang mat karo (23:00–07:00 shaanti)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour >= 23 || hour < 7) return
        if (now - last >= hours * 60 * 60_000L) {
            NotifyFactory.postWorkflow(this, wf)
            CareSettings.setStateLong(this, key, now)
        }
    }

    // ---------------- Phase 3: battery ----------------

    private data class BatteryInfo(val percent: Int, val charging: Boolean)

    private fun batteryInfo(): BatteryInfo {
        return try {
            val intent = registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            BatteryInfo(pct, charging)
        } catch (e: Exception) {
            BatteryInfo(-1, false)
        }
    }

    private fun evalBattery(wf: Workflow, batt: BatteryInfo, now: Long) {
        if (batt.percent < 0) return
        val stateWanted = wf.triggerParams["state"] ?: ""
        val level = wf.triggerParams["level"]?.toIntOrNull() ?: 95
        when (stateWanted) {
            "charging" -> {
                // Battery Full: ek charging session me ek hi baar
                if (!batt.charging || batt.percent < level) {
                    if (batt.percent < level - 5) {
                        CareSettings.setStateLong(this, "state_battfull_fired", 0L)
                    }
                    return
                }
                if (CareSettings.stateLong(this, "state_battfull_fired") != 0L) return
                CareSettings.setStateLong(this, "state_battfull_fired", now)
                NotifyFactory.postWorkflow(
                    this, wf, mapOf("percent" to batt.percent.toString())
                )
                if (wf.primaryAction == "set_alarm") {
                    scheduleFullChargeAlarm(wf)
                }
            }
            "discharging" -> {
                // Battery Low: user ki setting wala level, 4 ghante cooldown
                // 0.2.3: Editor-lite ka 'level' override Settings wale % se upar
                val lowLevel = WorkflowOverrides.overrideValue(this, wf.id, "level")
                    ?.toIntOrNull()
                    ?: CareSettings.intVal(this, CareSettings.KEY_BATTERY_LOW, level)
                if (batt.charging || batt.percent > lowLevel) return
                val last = CareSettings.stateLong(this, "state_battlow_fired")
                if (now - last < 4 * 60 * 60_000L) return
                CareSettings.setStateLong(this, "state_battlow_fired", now)
                NotifyFactory.postWorkflow(
                    this, wf, mapOf("percent" to batt.percent.toString())
                )
            }
        }
    }

    /** Battery Full ka "alarm": 1 minute baad alarm-style notification. */
    private fun scheduleFullChargeAlarm(wf: Workflow) {
        try {
            val afterMin = wf.settings["after_minutes"]?.toLongOrNull() ?: 1L
            val intent = Intent(this, AlarmNotifyReceiver::class.java).apply {
                action = "com.spdev22.mycare.FULL_CHARGE_ALARM"
                putExtra(AlarmNotifyReceiver.EXTRA_TITLE, "⚠️ Phone Full Charged!")
                putExtra(
                    AlarmNotifyReceiver.EXTRA_TEXT,
                    "Battery full ho gayi hai, Boss — charger hata dijiye, " +
                        "battery ki sehat bani rahegi."
                )
                putExtra(AlarmNotifyReceiver.EXTRA_ID, 9901)
            }
            val pi = PendingIntent.getBroadcast(
                this, 8801, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(AlarmManager::class.java)
            am?.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + afterMin * 60_000L, pi
            )
            LogStore.log(this, "Battery Full alarm set: $afterMin min baad")
        } catch (e: Exception) {
            Log.w(TAG, "Full charge alarm fail: ${e.message}")
        }
    }

    // ---------------- Phase 3: naya app install (poll-diff) ----------------

    private fun evalNewApps(wf: Workflow, now: Long) {
        if (now - CareSettings.stateLong(this, "state_newapp_check") < 10 * 60_000L) {
            return
        }
        CareSettings.setStateLong(this, "state_newapp_check", now)
        try {
            val pm = packageManager
            val current = pm.getInstalledApplications(0)
                .map { it.packageName }
                .filter { it != packageName }
                .toSet()
            val stored = CareSettings.stateStr(this, "state_installed_pkgs")
            if (stored == null) {
                // Pehli baar — baseline chup-chaap banao
                CareSettings.setStateStr(
                    this, "state_installed_pkgs", current.joinToString(",")
                )
                return
            }
            val old = stored.split(",").filter { it.isNotEmpty() }.toSet()
            val newOnes = current - old
            // Har haal me baseline taaza rakho (uninstall wale bhi ghatein)
            CareSettings.setStateStr(
                this, "state_installed_pkgs", current.joinToString(",")
            )
            for (pkg in newOnes) {
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
                    if (pm.getLaunchIntentForPackage(pkg) == null) continue
                    val label = pm.getApplicationLabel(info).toString()
                    NotifyFactory.postPopupLauncher(
                        this,
                        NotifyFactory.notifIdFor(wf.id),
                        "Naya app install hua 📲",
                        "'$label' install ho gaya hai. Use kis group me rakhein, Boss? " +
                            "Is notification par tap karke group chunein.",
                        pkg, label, wf.id
                    )
                    // Ek cycle me ek hi suchna — baaki baseline me chale gaye
                    break
                } catch (e: Exception) {
                    // is app ki jaankari nahi mili — agla dekho
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "New-app check fail: ${e.message}")
        }
    }

    // ---------------- Phase 3: contact observer ----------------

    private fun registerObservers() {
        val h = handler ?: return
        // Contact baseline: pehle se saved contacts fire na hon
        if (CareSettings.stateLong(this, "state_last_contact_id") == 0L) {
            latestContactId()?.let {
                CareSettings.setStateLong(this, "state_last_contact_id", it)
            }
        }
        try {
            val contactObs = object : ContentObserver(h) {
                override fun onChange(selfChange: Boolean) {
                    maybeNewContact()
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    maybeNewContact()
                }
            }
            contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI, true, contactObs
            )
            contactObserver = contactObs
        } catch (e: Exception) {
            Log.w(TAG, "Contact observer fail: ${e.message}")
        }
    }

    private fun latestContactId(): Long? {
        return try {
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                null, null,
                ContactsContract.Contacts._ID + " DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun maybeNewContact() {
        val now = System.currentTimeMillis()
        if (now - lastContactCheckMs < 4_000L) return
        lastContactCheckMs = now
        val wf = pack.firstOrNull { it.triggerType == "contact_saved" }
            ?.takeIf { WorkflowPrefs.isEnabled(this, it) }
            ?.let { WorkflowOverrides.effective(this, it) }
            ?: return
        try {
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME
                ),
                null, null,
                ContactsContract.Contacts._ID + " DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: "Naya contact"
                val last = CareSettings.stateLong(this, "state_last_contact_id")
                if (id <= last) return
                CareSettings.setStateLong(this, "state_last_contact_id", id)

                // Number nikalo — WhatsApp chat ke liye
                var number = ""
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                    arrayOf(id.toString()), null
                )?.use { phoneCursor ->
                    if (phoneCursor.moveToFirst()) {
                        number = phoneCursor.getString(0) ?: ""
                    }
                }
                val digits = number.filter { it.isDigit() }
                val waNumber = when {
                    digits.length == 10 -> "91$digits"
                    digits.length == 11 && digits.startsWith("0") ->
                        "91" + digits.drop(1)
                    else -> digits
                }
                val runtime = if (waNumber.isNotEmpty()) {
                    wf.copy(
                        buttons = listOf(
                            WfButton(
                                "WhatsApp par kholo", "open_whatsapp_chat",
                                mapOf("number" to waNumber)
                            ),
                            WfButton("Skip", "skip")
                        )
                    )
                } else {
                    wf
                }
                NotifyFactory.postWorkflow(
                    this, runtime, mapOf("name" to name)
                )
                LogStore.log(this, "Naya contact fire: $name")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact check fail: ${e.message}")
        }
    }

    // ---------------- 0.2.5: screenshot folder-watch ----------------
    //
    // MediaStore ContentObserver XOS par chal hi nahi raha tha —
    // user ka directive: seedha Screenshots FOLDERS par nazar rakho.
    // Har poll me chaar standard folders scan hote hain; pehle se
    // dekhi hui files ka set prefs me rehta hai ("state_shot_seen_v2":
    // har line "path|lastModified"). Nayi image file = screenshot
    // liya gaya.
    //
    // PEHLI baar (baseline): maujooda saari files "dekhi hui" maan
    // li jaati hain — purane screenshots par fire NAHI hoga.

    private fun checkScreenshotFolders(now: Long) {
        val packWf = pack.firstOrNull { it.triggerType == "screenshot_taken" }
            ?: return
        val base = try {
            Environment.getExternalStorageDirectory()
        } catch (e: Exception) {
            null
        } ?: return

        var anyListable = false
        val files = mutableListOf<File>()
        for (rel in SHOT_DIRS) {
            val dir = File(base, rel)
            val listed = try {
                dir.listFiles()
            } catch (e: Exception) {
                null
            }
            if (listed != null) {
                anyListable = true
                for (f in listed) {
                    if (f.isFile && isImageFile(f.name)) files.add(f)
                }
            }
        }
        if (!anyListable) {
            // Koi folder padh hi nahi paye — aksar Photos/Media
            // permission na milne se hota hai. Ghante me ek baar bolo.
            if (now - lastShotPermLogMs > 60 * 60_000L) {
                lastShotPermLogMs = now
                LogStore.log(
                    this,
                    "Screenshot folder-watch: folders padh nahi paya — " +
                        "Photos/Media permission check karein (Setup Wizard)"
                )
            }
            return
        }

        val seenRaw = CareSettings.stateStr(this, KEY_SHOT_SEEN)
        if (seenRaw == null) {
            saveShotSeen(files)
            LogStore.log(
                this,
                "Screenshot folder-watch baseline: ${files.size} purani " +
                    "files dekhi hui maani gayin (fire nahi hongi)"
            )
            return
        }
        val seen = parseShotSeen(seenRaw)
        val fresh = files.filter { it.absolutePath !in seen }
        // Seen-set hamesha taaza rakho — delete hui files khud ghatein
        saveShotSeen(files)
        if (fresh.isEmpty()) return

        // Ek poll me kai nayi files hon to sirf SABSE NAYI par fire
        // (notification spam na ho); baaki seen-set me chali gayin
        val newest = fresh.maxByOrNull { it.lastModified() } ?: return
        val folderName = newest.parentFile?.name ?: ""
        LogStore.log(
            this,
            "Screenshot detect (folder watch): $folderName/${newest.name}"
        )
        if (!WorkflowPrefs.isEnabled(this, packWf)) {
            LogStore.log(this, "Screenshot mila par Screenshot Safai OFF hai — fire nahi")
            return
        }
        val wf = WorkflowOverrides.effective(this, packWf)
        // Delete flow ke liye path (asal bharosa) + mile to MediaStore URI
        CareSettings.setStateStr(this, "state_screenshot_path", newest.absolutePath)
        val uri = DeleteReceiver.mediaUriForPath(this, newest.absolutePath)
        CareSettings.setStateStr(this, "state_screenshot_uri", uri?.toString() ?: "")
        NotifyFactory.postWorkflow(this, wf)
    }

    private fun isImageFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".png") || n.endsWith(".jpg") ||
            n.endsWith(".jpeg") || n.endsWith(".webp")
    }

    private fun parseShotSeen(raw: String): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        for (line in raw.split("\n")) {
            val cut = line.lastIndexOf('|')
            if (cut <= 0) continue
            map[line.substring(0, cut)] =
                line.substring(cut + 1).toLongOrNull() ?: 0L
        }
        return map
    }

    private fun saveShotSeen(files: List<File>) {
        // Prefs halka rakho — sabse nayi 300 files ka record kaafi hai
        val kept = files.sortedByDescending { it.lastModified() }.take(300)
        val raw = kept.joinToString("\n") {
            "${it.absolutePath}|${it.lastModified()}"
        }
        CareSettings.setStateStr(this, KEY_SHOT_SEEN, raw)
    }

    // ---------------- first unlock (Welcome Boss) ----------------

    private fun registerUnlockReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) onUserPresent()
            }
        }
        try {
            registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            unlockReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Unlock receiver register fail: ${e.message}")
        }
    }

    private fun onUserPresent() {
        if (!CareSettings.welcomeEnabled(this)) return
        val today = UsageTracker.dayKey(System.currentTimeMillis())
        if (CareSettings.lastWelcomeDate(this) == today) return
        val wf = pack.firstOrNull { it.triggerType == "first_unlock" }
            ?.takeIf { WorkflowPrefs.isEnabled(this, it) }
            ?.let { WorkflowOverrides.effective(this, it) }
            ?: return
        NotifyFactory.postWorkflow(this, wf)
        CareSettings.setLastWelcomeDate(this, today)
        Log.i(TAG, "Welcome Boss fire hua (din ka pehla unlock)")
    }

    // ---------------- foreground notification ----------------

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "My Care Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Care service chalu hai — iske bina triggers nahi chalenge"
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("My Care active hai")
            .setContentText("Care workflows chalu hain — My Care nazar rakh raha hai.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CareService"
        private const val CHANNEL_ID = "care_service"
        private const val NOTIF_ID = 1001
        private const val POLL_INTERVAL_MS = 20_000L

        /** Folder-watch wale screenshot folders (shared storage ke andar). */
        private val SHOT_DIRS = listOf(
            "Pictures/Screenshots", "DCIM/Screenshots",
            "Pictures/Screenshot", "DCIM/Screenshot"
        )

        /** Dekhi hui screenshot files ke set wali state key. */
        private const val KEY_SHOT_SEEN = "state_shot_seen_v2"

        fun start(context: Context) {
            val intent = Intent(context, CareService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Service start fail: ${e.message}")
            }
        }
    }
}
