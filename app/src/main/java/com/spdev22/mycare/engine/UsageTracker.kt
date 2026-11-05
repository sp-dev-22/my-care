package com.spdev22.mycare.engine

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UsageTracker — UsageEvents ko incrementally padhkar teen cheezein
 * maintain karta hai:
 *  1. Abhi foreground me kaun sa app hai aur kab se (current session)
 *  2. Lagatar phone-use ka stretch (beech me lamba gap/screen-off = reset)
 *  3. Haal ke chhote sessions ki list (boredom / app-hopping ke liye)
 *     + aaj ke per-app totals (snapshot me persist hote hain)
 *
 * Poora state ek JSON snapshot me SharedPreferences me rehta hai, taaki
 * service restart par bhi hisaab na toote. Sirf tab chalta hai jab
 * Usage Access permission mili ho — warna poll() chup-chaap ruk jata hai.
 */
class UsageTracker(private val context: Context) {

    data class Session(val pkg: String, val start: Long, val end: Long) {
        val durationMs: Long get() = end - start
    }

    var currentPkg: String? = null
        private set
    var currentStart: Long = 0L
        private set
    var stretchStart: Long = 0L
        private set
    var lastSessionEnd: Long = 0L
        private set
    var screenOn: Boolean = true
        private set

    val recentSessions = ArrayDeque<Session>()
    val todayTotals = mutableMapOf<String, Long>()

    private var todayKey: String = dayKey(System.currentTimeMillis())
    private var watermark: Long = 0L
    private var launcherPkgs: Set<String>? = null

    init {
        loadSnapshot()
    }

    /** Abhi chal raha session kitni der ka ho gaya (ms). Koi session nahi to 0. */
    fun currentSessionMs(now: Long): Long {
        return if (currentPkg != null) now - currentStart else 0L
    }

    /** Lagatar use ka stretch kitna lamba (ms). Screen off / idle par 0. */
    fun currentStretchMs(now: Long): Long {
        if (stretchStart == 0L || !screenOn) return 0L
        val end = if (currentPkg != null) now else lastSessionEnd
        return if (end > stretchStart) end - stretchStart else 0L
    }

    /**
     * Boredom check: pichhle [windowMs] me kam se kam [needApps] ALAG apps,
     * har ek ka session [maxSessionMs] se chhota.
     */
    fun boredomDetected(now: Long, needApps: Int, maxSessionMs: Long, windowMs: Long): Boolean {
        val distinct = HashSet<String>()
        for (s in recentSessions) {
            if (now - s.end <= windowMs && s.durationMs < maxSessionMs) {
                distinct.add(s.pkg)
            }
        }
        return distinct.size >= needApps
    }

    /**
     * Watermark se ab tak ke saare UsageEvents process karo.
     * @return true agar Usage Access hai aur events padhe gaye.
     */
    fun poll(): Boolean {
        if (!hasUsageAccess(context)) return false
        val now = System.currentTimeMillis()

        // Naya din — totals saaf
        val dk = dayKey(now)
        if (dk != todayKey) {
            todayKey = dk
            todayTotals.clear()
            recentSessions.clear()
        }

        if (watermark == 0L || now - watermark > MAX_BACKFILL_MS) {
            // Pehli baar, ya service bahut der band rahi — purani khabar
            // par bharosa nahi; bas thoda peeche se shuru karo.
            watermark = now - 15 * 60_000L
            currentPkg = null
            stretchStart = 0L
        }

        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return false
            val events = usm.queryEvents(watermark, now) ?: return false
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val pkg = e.packageName ?: continue
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> onResume(pkg, e.timeStamp)
                    UsageEvents.Event.ACTIVITY_PAUSED -> onPause(pkg, e.timeStamp)
                    UsageEvents.Event.SCREEN_INTERACTIVE -> screenOn = true
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        screenOn = false
                        closeCurrent(e.timeStamp)
                        stretchStart = 0L
                    }
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "UsageEvents poll fail: ${ex.message}")
            return false
        }

        watermark = now
        saveSnapshot()
        return true
    }

    private fun onResume(pkg: String, ts: Long) {
        if (pkg == context.packageName) {
            // Apna hi app khula — pichla session band karo, khud ko track mat karo
            closeCurrent(ts)
            return
        }
        if (currentPkg == pkg) return
        closeCurrent(ts)
        currentPkg = pkg
        currentStart = ts
        if (stretchStart == 0L || ts - lastSessionEnd > STRETCH_GAP_MS) {
            stretchStart = ts
        }
        screenOn = true
    }

    private fun onPause(pkg: String, ts: Long) {
        if (currentPkg == pkg) closeCurrent(ts)
    }

    private fun closeCurrent(ts: Long) {
        val pkg = currentPkg ?: return
        if (ts > currentStart) {
            val dur = ts - currentStart
            todayTotals[pkg] = (todayTotals[pkg] ?: 0L) + dur
            if (!isExcludedFromHopping(pkg)) {
                recentSessions.addLast(Session(pkg, currentStart, ts))
                while (recentSessions.size > MAX_RECENT) recentSessions.removeFirst()
            }
            lastSessionEnd = ts
        }
        currentPkg = null
    }

    private fun isExcludedFromHopping(pkg: String): Boolean {
        if (pkg == context.packageName) return true
        if (pkg == "com.android.systemui") return true
        return launcherPackages().contains(pkg)
    }

    /** Long-App jaise triggers ke liye: system/launcher ko app mat gino. */
    fun isSystemOrLauncher(pkg: String): Boolean = isExcludedFromHopping(pkg)

    private fun launcherPackages(): Set<String> {
        launcherPkgs?.let { return it }
        val result = HashSet<String>()
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val list = context.packageManager.queryIntentActivities(intent, 0)
            for (ri in list) {
                ri.activityInfo?.packageName?.let { result.add(it) }
            }
        } catch (e: Exception) {
            // khali set — koi baat nahi
        }
        launcherPkgs = result
        return result
    }

    // ---------- snapshot persistence ----------

    private fun saveSnapshot() {
        try {
            val obj = JSONObject()
            obj.put("day", todayKey)
            obj.put("watermark", watermark)
            obj.put("stretchStart", stretchStart)
            obj.put("lastSessionEnd", lastSessionEnd)
            currentPkg?.let {
                obj.put("curPkg", it)
                obj.put("curStart", currentStart)
            }
            val totals = JSONObject()
            for ((k, v) in todayTotals) totals.put(k, v)
            obj.put("totals", totals)
            prefs().edit().putString(KEY_SNAPSHOT, obj.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot save fail: ${e.message}")
        }
    }

    private fun loadSnapshot() {
        try {
            val raw = prefs().getString(KEY_SNAPSHOT, null) ?: return
            val obj = JSONObject(raw)
            if (obj.optString("day") != todayKey) return // purana din — fresh start
            watermark = obj.optLong("watermark", 0L)
            stretchStart = obj.optLong("stretchStart", 0L)
            lastSessionEnd = obj.optLong("lastSessionEnd", 0L)
            val cp = obj.optString("curPkg")
            if (cp.isNotEmpty()) {
                currentPkg = cp
                currentStart = obj.optLong("curStart", 0L)
            }
            val totals = obj.optJSONObject("totals")
            if (totals != null) {
                val keys = totals.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    todayTotals[k] = totals.optLong(k, 0L)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot load fail: ${e.message}")
        }
    }

    private fun prefs() =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "UsageTracker"
        private const val PREFS = "my_care_prefs"
        private const val KEY_SNAPSHOT = "state_tracker_json"
        private const val MAX_BACKFILL_MS = 6 * 60 * 60_000L
        private const val STRETCH_GAP_MS = 90_000L
        private const val MAX_RECENT = 40

        fun dayKey(ts: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

        fun hasUsageAccess(context: Context): Boolean {
            return try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
                    as? AppOpsManager ?: return false
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), context.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), context.packageName
                    )
                }
                mode == AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                false
            }
        }
    }
}
