package com.spdev22.mycare.engine

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.spdev22.mycare.data.GroupStore
import java.util.Calendar

/**
 * Aaj Ka Hisaab (workflow 7) — aaj ke UsageStats se simple totals:
 * kul phone time, Padhai group vs Manoranjan group ka samay,
 * aur sabse zyada chala app.
 *
 * Group pehchan naam se hoti hai (D1): jis group ke naam me
 * "padhai/study" ho woh study, "manoranjan/entertainment/timepass"
 * wala entertainment. Pattern-wali gehri samajh (D5) v2 me aayegi —
 * v1 me sirf saaf totals, jhoothi samajhdari nahi.
 */
object HisaabBuilder {

    /**
     * Canonical group naam — Settings ke "zaroori Groups" section aur
     * hisaab ki pehchan inhi par tikti hai. Classifier neeche naam ke
     * andar in shabdon (aur unke bhai-bandhuon) ko dhoondhta hai,
     * lekin user ko yahi do naam banane ko kaha jata hai taaki
     * pehchan hamesha pakki rahe.
     */
    const val STUDY_GROUP_NAME = "Padhai"
    const val ENTERTAINMENT_GROUP_NAME = "Manoranjan"

    fun buildText(context: Context): String {
        if (!UsageTracker.hasUsageAccess(context)) {
            return "Boss, Usage Access is not granted — the account could " +
                "not be made. Please grant it in the Setup Wizard, and from " +
                "tomorrow your daily account will be ready."
        }
        val totals = queryTodayTotals(context)
        if (totals.isEmpty()) {
            return "Today's account: very little phone usage was recorded " +
                "today. Tomorrow will be a better day, Boss!"
        }

        val totalMs = totals.values.sum()
        val groups = GroupStore.loadGroups(context)

        val studyPkgs = HashSet<String>()
        val entPkgs = HashSet<String>()
        for (g in groups) {
            val n = g.name.lowercase()
            when {
                n.contains("padhai") || n.contains("study") ||
                    n.contains("पढ़ाई") || n.contains("class") ->
                    studyPkgs.addAll(g.packages)
                n.contains("manoranjan") || n.contains("entertainment") ||
                    n.contains("timepass") || n.contains("fun") ||
                    n.contains("मनोरंजन") ->
                    entPkgs.addAll(g.packages)
            }
        }

        val studyMs = totals.filterKeys { studyPkgs.contains(it) }.values.sum()
        val entMs = totals.filterKeys { entPkgs.contains(it) }.values.sum()

        val sb = StringBuilder()
        sb.append("Total phone today: ").append(fmt(totalMs)).append(". ")
        if (studyPkgs.isNotEmpty() || entPkgs.isNotEmpty()) {
            sb.append("Study apps: ").append(fmt(studyMs))
                .append(" • Entertainment: ").append(fmt(entMs)).append(". ")
        } else {
            sb.append("Add apps to your App Groups — then the study vs " +
                "entertainment account will appear here too. ")
        }

        val top = totals.maxByOrNull { it.value }
        if (top != null && top.value > 0) {
            sb.append("Most used: ").append(appLabel(context, top.key))
                .append(" (").append(fmt(top.value)).append("). ")
        }

        if (studyPkgs.isNotEmpty() || entPkgs.isNotEmpty()) {
            if (studyMs >= entMs && studyMs > 0) {
                sb.append("Well done, Boss — study stayed ahead today! 👏")
            } else if (entMs > 0) {
                sb.append("Let's keep study a little ahead tomorrow, Boss. 💪")
            }
        }

        // D5 pattern-finding (halka, on-device): pichhle 7 din ka pattern
        val pattern = weeklyPattern(context)
        if (pattern.isNotEmpty()) {
            sb.append(" ").append(pattern)
        }
        return sb.toString().trim()
    }

    /**
     * Pichhle 7 din ka simple pattern (D5 ki pehli seedhi):
     * roz ka average phone-time aur hafte ka sabse zyada chala app.
     * Koi cloud/AI nahi — sirf UsageStats ke jod-ghataao.
     */
    private fun weeklyPattern(context: Context): String {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return ""
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST, now - 7 * 24 * 60 * 60_000L, now
            ) ?: return ""
            if (stats.isEmpty()) return ""
            var totalMs = 0L
            val perPkg = mutableMapOf<String, Long>()
            for (s in stats) {
                if (s.packageName == context.packageName) continue
                if (s.totalTimeInForeground <= 0) continue
                totalMs += s.totalTimeInForeground
                perPkg[s.packageName] =
                    (perPkg[s.packageName] ?: 0L) + s.totalTimeInForeground
            }
            if (totalMs <= 0) return ""
            val avgPerDay = totalMs / 7
            val sb = StringBuilder()
            sb.append("Pattern (7 days): daily average phone ~")
                .append(fmt(avgPerDay)).append(".")
            val top = perPkg.maxByOrNull { it.value }
            if (top != null) {
                sb.append(" Most used this week: ")
                    .append(appLabel(context, top.key))
                    .append(" (").append(fmt(top.value)).append(").")
            }
            if (avgPerDay > 5 * 60 * 60_000L) {
                sb.append(" The average is high, Boss — let's keep it a little lower tomorrow.")
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    /** Aaj subah 00:00 se ab tak ke per-app foreground totals (ms). */
    private fun queryTodayTotals(context: Context): Map<String, Long> {
        val totals = mutableMapOf<String, Long>()
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return totals
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val events = usm.queryEvents(cal.timeInMillis, System.currentTimeMillis())
                ?: return totals
            val open = mutableMapOf<String, Long>()
            val e = UsageEvents.Event()
            val own = context.packageName
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val pkg = e.packageName ?: continue
                if (pkg == own || pkg == "com.android.systemui") continue
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> open[pkg] = e.timeStamp
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        val start = open.remove(pkg)
                        if (start != null && e.timeStamp > start) {
                            totals[pkg] = (totals[pkg] ?: 0L) + (e.timeStamp - start)
                        }
                    }
                }
            }
            // Jo app abhi bhi khula hai, uska chalta session bhi gino
            val now = System.currentTimeMillis()
            for ((pkg, start) in open) {
                if (now > start) {
                    totals[pkg] = (totals[pkg] ?: 0L) + (now - start)
                }
            }
        } catch (ex: Exception) {
            // khali map — caller fallback text dikhayega
        }
        return totals
    }

    fun appLabel(context: Context, pkg: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    fun fmt(ms: Long): String {
        val totalMin = ms / 60_000L
        return if (totalMin >= 60) "${totalMin / 60}h ${totalMin % 60}m"
        else "${totalMin}m"
    }
}
