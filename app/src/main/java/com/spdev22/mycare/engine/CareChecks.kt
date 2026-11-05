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
 * throttle ke saath chalte hain — cache roz ek baar, unused hafte
 * me ek baar. Spam bilkul nahi: ek app ke liye dobara suggestion
 * kam se kam 3 din (cache) / 30 din (unused) baad hi jayega.
 */
object CareChecks {

    private const val TAG = "CareChecks"
    private const val DAY_MS = 24 * 60 * 60_000L

    // ---------------- Cache Safai ----------------

    fun checkCache(context: Context, wf: Workflow): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!UsageTracker.hasUsageAccess(context)) return false
        val now = System.currentTimeMillis()
        if (now - CareSettings.stateLong(context, "state_cache_check") < DAY_MS) {
            return false
        }
        CareSettings.setStateLong(context, "state_cache_check", now)

        val thresholdMb = CareSettings.intVal(
            context, CareSettings.KEY_CACHE_MB, CareSettings.DEFAULT_CACHE_MB
        )
        val thresholdBytes = thresholdMb.toLong() * 1_000_000L

        return try {
            val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE)
                as? StorageStatsManager ?: return false
            val pm = context.packageManager
            var worstPkg: String? = null
            var worstCache = 0L
            for (app in pm.getInstalledApplications(0)) {
                if (app.packageName == context.packageName) continue
                if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
                try {
                    val stats = ssm.queryStatsForPackage(
                        StorageManager.UUID_DEFAULT,
                        app.packageName,
                        Process.myUserHandle()
                    )
                    val cache = stats.cacheBytes
                    if (cache > worstCache) {
                        worstCache = cache
                        worstPkg = app.packageName
                    }
                } catch (e: Exception) {
                    // kisi ek app ka stat na mile to baaki jaari rakho
                }
            }
            val pkg = worstPkg ?: return false
            if (worstCache < thresholdBytes) return false
            val lastKey = "state_cache_notified_$pkg"
            if (now - CareSettings.stateLong(context, lastKey) < 3 * DAY_MS) {
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
            LogStore.log(context, "Cache Safai fire: $pkg (${cacheMb}MB)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cache check fail: ${e.message}")
            false
        }
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
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST, now - 35 * DAY_MS, now
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
                if (now - used >= 30 * DAY_MS) {
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
