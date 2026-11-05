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

    fun buildText(context: Context): String {
        if (!UsageTracker.hasUsageAccess(context)) {
            return "Boss, Usage Access nahi mila — hisaab nahi ban paya. " +
                "Setup Wizard se woh permission de dijiye, kal se hisaab pakka milega."
        }
        val totals = queryTodayTotals(context)
        if (totals.isEmpty()) {
            return "Aaj ka hisaab: aaj phone ka usage record bahut kam hai. " +
                "Kal ka din aur behtar hoga, Boss!"
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
        sb.append("Kul phone aaj: ").append(fmt(totalMs)).append(". ")
        if (studyPkgs.isNotEmpty() || entPkgs.isNotEmpty()) {
            sb.append("Padhai apps: ").append(fmt(studyMs))
                .append(" • Manoranjan: ").append(fmt(entMs)).append(". ")
        } else {
            sb.append("App Groups me apps jod dijiye — phir padhai vs " +
                "manoranjan ka hisaab bhi yahin milega. ")
        }

        val top = totals.maxByOrNull { it.value }
        if (top != null && top.value > 0) {
            sb.append("Sabse zyada chala: ").append(appLabel(context, top.key))
                .append(" (").append(fmt(top.value)).append("). ")
        }

        if (studyPkgs.isNotEmpty() || entPkgs.isNotEmpty()) {
            if (studyMs >= entMs && studyMs > 0) {
                sb.append("Bahut badhiya, Boss — aaj padhai aage rahi! 👏")
            } else if (entMs > 0) {
                sb.append("Kal padhai ko thoda aage rakhte hain, Boss. 💪")
            }
        }
        return sb.toString().trim()
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
