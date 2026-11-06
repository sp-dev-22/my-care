package com.spdev22.mycare.engine

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.util.Log
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.data.WorkflowOverrides
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.notify.NotifyFactory

/**
 * CareChecks — Phase 3 ke dheere chalne wale phone-care checks:
 *
 *  - Cache Safai: kisi app ka cache had se bada ho jaye to suggestion
 *    (original "app crash" wali soch ka approved pivot — doosre apps
 *    ke crash koi normal app dekh nahi sakta, cache size dekh sakta hai).
 *  - Unused Apps Safai: 30+ din se na chala app — hafte me ek baar.
 *
 * Dono checks mehnge hain (saare apps scan hote hain), isliye
 * throttle ke saath chalte hain — cache evaluation har ~4 ghante
 * (aur service start ke turant baad), unused hafte me ek baar.
 * Spam bilkul nahi: ek app ke liye dobara suggestion kam se kam
 * 3 din (cache) / 30 din (unused) baad hi jayega.
 *
 * 0.2.4 BUG FIX — Cache Safai kabhi fire hi nahi hoti thi. Wajah
 * code me chhupi kai "chup-chaap rukne" wali jagahen theen: Usage
 * Access na ho to bina kisi nishaan ke return, roz-ek-baar wali
 * mohar scan se PEHLE lag jaana, har app ke stats-error ko bina
 * gine nigal jaana, aur 500MB ki had sirf SABSE BADE ek app par
 * lagna. Upar se Logs me sirf FIRE hone par line likhti thi —
 * baaki har haal adrishya tha. Ab har evaluation ek LogStore line
 * chhodti hai: kul cache, threshold, aur faisla (fire / below /
 * permission missing / errors) — taaki Logs screen se hi pata
 * chale ki check chala bhi ya nahi, aur ruka to kyun.
 */
object CareChecks {

    private const val TAG = "CareChecks"
    private const val DAY_MS = 24 * 60 * 60_000L

    /** 0.2.4: cache evaluation ki cadence — har ~4 ghante ek baar. */
    private const val CACHE_EVAL_INTERVAL_MS = 4 * 60 * 60_000L

    // ---------------- Cache Safai ----------------

    /**
     * Halki si entry: sirf tab asal scan chalega jab pichhli
     * evaluation ko ~4 ghante ho gaye hon. State key khaali ho
     * (fresh install / update / service ki nayi shuruaat ke baad
     * pehli poll) to turant evaluation hogi — isiliye service start
     * ke thodi der me hi pehli jaanch pakki hai.
     */
    fun checkCache(context: Context, wf: Workflow): Boolean {
        val now = System.currentTimeMillis()
        val lastEval = CareSettings.stateLong(context, "state_cache_eval")
        if (lastEval != 0L && now - lastEval < CACHE_EVAL_INTERVAL_MS) {
            return false
        }
        return evaluateCache(context, wf, now)
    }

    /**
     * Asal cache jaanch. HAR raaste par ek LogStore line banti hai —
     * kul naapa hua cache, lagu threshold aur faisla — taaki Logs
     * screen iski gawahi de. Notification par purana per-app
     * 3-din ka cooldown waise ka waisa hai.
     */
    private fun evaluateCache(context: Context, wf: Workflow, now: Long): Boolean {
        // 0.2.3: Editor-lite ka 'cache_mb' override Settings wali limit se upar
        val thresholdMb = WorkflowOverrides.overrideValue(context, wf.id, "cache_mb")
            ?.toIntOrNull()
            ?: CareSettings.intVal(
                context, CareSettings.KEY_CACHE_MB, CareSettings.DEFAULT_CACHE_MB
            )
        val thresholdBytes = thresholdMb.toLong() * 1_000_000L

        fun stamp() = CareSettings.setStateLong(context, "state_cache_eval", now)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            stamp()
            LogStore.log(
                context,
                "Cache Safai eval: SKIP — is Android par StorageStats nahi " +
                    "milta (threshold ${thresholdMb}MB)"
            )
            return false
        }
        if (!UsageTracker.hasUsageAccess(context)) {
            stamp()
            LogStore.log(
                context,
                "Cache Safai eval: SKIP — Usage Access NAHI hai " +
                    "(threshold ${thresholdMb}MB). Setup Wizard me Usage " +
                    "Access ON karein — bina iske Cache Safai kabhi nahi chalegi."
            )
            return false
        }
        val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE)
            as? StorageStatsManager
        if (ssm == null) {
            stamp()
            LogStore.log(
                context,
                "Cache Safai eval: SKIP — StorageStatsManager nahi mila " +
                    "(threshold ${thresholdMb}MB)"
            )
            return false
        }
        stamp()

        var scanned = 0
        var statErrors = 0
        var totalCache = 0L
        var worstPkg: String? = null
        var worstCache = 0L
        try {
            val pm = context.packageManager
            for (app in pm.getInstalledApplications(0)) {
                if (app.packageName == context.packageName) continue
                if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
                try {
                    val stats = ssm.queryStatsForPackage(
                        StorageManager.UUID_DEFAULT,
                        app.packageName,
                        Process.myUserHandle()
                    )
                    scanned++
                    val cache = stats.cacheBytes
                    totalCache += cache
                    if (cache > worstCache) {
                        worstCache = cache
                        worstPkg = app.packageName
                    }
                } catch (e: Exception) {
                    // Ek app ka stat na mile to gin kar aage badho —
                    // pehle yeh khamoshi se nigal jata tha aur agar
                    // SAARON par error aaye to lagta tha cache hai hi nahi
                    statErrors++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cache check fail: ${e.message}")
            LogStore.log(context, "Cache Safai eval: FAIL — scan error: ${e.message}")
            return false
        }

        val scanNote = "scan ${scanned} apps (errors $statErrors), " +
            "kul cache ${totalCache / 1_000_000L}MB, sabse bada " +
            (worstPkg?.let {
                "${HisaabBuilder.appLabel(context, it)} ${worstCache / 1_000_000L}MB"
            } ?: "koi nahi") +
            ", threshold ${thresholdMb}MB"

        val pkg = worstPkg
        if (pkg == null || worstCache < thresholdBytes) {
            LogStore.log(context, "Cache Safai eval: BELOW THRESHOLD — $scanNote")
            return false
        }
        val lastKey = "state_cache_notified_$pkg"
        if (now - CareSettings.stateLong(context, lastKey) < 3 * DAY_MS) {
            LogStore.log(
                context,
                "Cache Safai eval: COOLDOWN — $pkg ko 3 din ke andar " +
                    "suggestion ja chuka hai — $scanNote"
            )
            return false
        }
        CareSettings.setStateLong(context, lastKey, now)

        val cacheMb = worstCache / 1_000_000L
        val runtime = wf.copy(
            buttons = listOf(
                WfButton(
                    "App Info kholo", "open_app_info",
                    mapOf("package" to pkg)
                ),
                WfButton("Skip", "skip")
            )
        )
        NotifyFactory.postWorkflow(
            context, runtime,
            mapOf(
                "app" to HisaabBuilder.appLabel(context, pkg),
                "cache" to "${cacheMb} MB"
            )
        )
        LogStore.log(context, "Cache Safai FIRE: $pkg (${cacheMb}MB) — $scanNote")
        return true
    }

    // ---------------- Unused Apps Safai ----------------

    fun checkUnused(context: Context, wf: Workflow): Boolean {
        if (!UsageTracker.hasUsageAccess(context)) return false
        val now = System.currentTimeMillis()
        if (now - CareSettings.stateLong(context, "state_unused_check") < 7 * DAY_MS) {
            return false
        }
        CareSettings.setStateLong(context, "state_unused_check", now)

        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return false
            // 0.2.3: 'days' limit ab pack settings / Editor-lite override
            // se aati hai (pehle 30 hardkod tha, pack value padhi hi nahi jati thi)
            val daysLimit = wf.settings["days"]?.toLongOrNull() ?: 30L
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST, now - (daysLimit + 5) * DAY_MS, now
            ) ?: return false
            val lastUsed = mutableMapOf<String, Long>()
            for (s in stats) {
                val prev = lastUsed[s.packageName] ?: 0L
                if (s.lastTimeUsed > prev) lastUsed[s.packageName] = s.lastTimeUsed
            }
            val pm = context.packageManager
            val candidates = mutableListOf<Pair<String, Long>>()
            for (app in pm.getInstalledApplications(0)) {
                if (app.packageName == context.packageName) continue
                if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
                // Sirf woh apps jo launcher se chalte hain — background
                // services ko "unused" kehna galat hoga
                if (pm.getLaunchIntentForPackage(app.packageName) == null) continue
                val used = lastUsed[app.packageName] ?: 0L
                if (now - used >= daysLimit * DAY_MS) {
                    candidates.add(app.packageName to used)
                }
            }
            if (candidates.isEmpty()) return false
            candidates.sortBy { it.second }
            val pkg = candidates.first().first
            val lastKey = "state_unused_notified_$pkg"
            if (now - CareSettings.stateLong(context, lastKey) < 30 * DAY_MS) {
                return false
            }
            CareSettings.setStateLong(context, lastKey, now)

            val runtime = wf.copy(
                buttons = listOf(
                    WfButton(
                        "App Info kholo", "open_app_info",
                        mapOf("package" to pkg)
                    ),
                    WfButton("Skip", "skip")
                )
            )
            NotifyFactory.postWorkflow(
                context, runtime,
                mapOf(
                    "app" to HisaabBuilder.appLabel(context, pkg),
                    "count" to candidates.size.toString()
                )
            )
            LogStore.log(context, "Unused Apps fire: $pkg (kul ${candidates.size} aise apps)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Unused check fail: ${e.message}")
            false
        }
    }
}
