package com.spdev22.mycare.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.notify.NotifyFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DeleteReceiver — Screenshot Safai ka scheduled auto-delete.
 *
 * User ne jo delay chuna tha (5 min / 1 ghanta / 1 din), uske baad
 * screenshot delete karne ki koshish hoti hai.
 *
 * 0.2.5 (folder-watch): asal pehchan file ka PATH hai. Koshishon ka
 * kram: (1) path se seedha File delete, (2) MediaStore URI se
 * delete (path se URI ab dhoondha bhi ja sakta hai — media index
 * tab tak ban chuka hota hai), (3) woh bhi na ho to "Delete confirm
 * karein" notification — ek tap par system ka confirmation khulta
 * hai. Chup-chaap delete ka jhootha vaada nahi kiya gaya hai.
 *
 * 0.2.7 (device-test ke do fixes):
 *  (A) Timing: pehle wala inexact alarm Doze/batching me 5 min ko
 *      10–15 min bana deta tha. Ab har timed delete ek PERSISTED
 *      pending-list (path + due time) me rehta hai aur do raaste
 *      use chalate hain — jo PEHLE pahunche:
 *        1. EXACT alarm (setExactAndAllowWhileIdle; exact ki
 *           ijaazat na ho to inexact + log), aur
 *        2. CareService ka poll loop (har 20s) overdue entries ko
 *           nipta deta hai.
 *      Entry chalane se PEHLE list se hat ti hai — dedupe yahi
 *      hai: dusra raasta aaye to entry gayab milegi, kaam dobara
 *      nahi hoga. Har execution par due-time vs asal-time LogStore
 *      me likhta hai.
 *  (B) Consent-crash ka ilaaj TrampolineActivity/AndroidManifest
 *      me hai (Theme.NoDisplay wali gotcha) — yahan sirf har stage
 *      ka log aur sahi-file targeting (confirm se pehle legacy
 *      keys me IS entry ki pehchan) pakka kiya gaya hai.
 */
class DeleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 0.2.7: exact alarm yahi bajta hai — jo pending deletes
        // due ho chuke hain, unhe nipta do
        if (executeDueDeletes(context, "alarm")) return
        // Upgrade-compat: 0.2.6 (ya pehle) ke zamane ka koi delete
        // legacy keys me pada reh gaya ho to use bhi nipta do
        val path = CareSettings.stateStr(context, KEY_DELETE_PATH)
            ?.takeIf { it.isNotEmpty() }
        val uriStr = CareSettings.stateStr(context, KEY_DELETE_URI)
            ?.takeIf { it.isNotEmpty() }
        if (path == null && uriStr == null) return
        val result = attemptDelete(context, path, uriStr, "alarm-legacy")
        if (result != DeleteOutcome.CONFIRM_POSTED) clearKeys(context)
    }

    companion object {
        private const val TAG = "DeleteReceiver"
        const val KEY_DELETE_URI = "state_delete_uri"
        const val KEY_DELETE_PATH = "state_delete_path"

        // 0.2.7: pending deletes ki persisted list (JSON array —
        // har entry: path, uri, due (epoch ms), label)
        private const val KEY_PENDING_DELETES = "state_pending_deletes"
        private const val ACTION_DELETE_SCREENSHOT =
            "com.spdev22.mycare.DELETE_SCREENSHOT"
        private const val ALARM_REQUEST_CODE = 7771

        /** Ek timed screenshot delete jo abhi due ka intezaar kar raha hai. */
        data class PendingDelete(
            val path: String,
            val uri: String,
            val due: Long,
            val label: String
        )

        private enum class DeleteOutcome { DELETED, CONFIRM_POSTED, FAILED }

        // ---------------- pending list (persisted) ----------------

        /**
         * Naya timed delete darj karo: list me persist + sabse
         * jaldi due par exact alarm (dobara) set. Ek hi path ki
         * purani entry ho to woh badal jayegi (dedupe).
         */
        fun addPending(
            context: Context,
            path: String,
            uri: String,
            dueAt: Long,
            label: String
        ) {
            if (path.isEmpty() && uri.isEmpty()) return
            val list = loadPending(context)
                .filter { it.path != path }
                .toMutableList()
            list.add(PendingDelete(path, uri, dueAt, label))
            savePending(context, list)
            val secs = (dueAt - System.currentTimeMillis()) / 1000
            LogStore.log(
                context,
                "Screenshot delete pending darj: '$label' — due " +
                    "${fmtTime(dueAt)} (~${secs}s baad), kul pending ${list.size}"
            )
            schedulePendingAlarm(context)
        }

        fun loadPending(context: Context): List<PendingDelete> {
            val raw = CareSettings.stateStr(context, KEY_PENDING_DELETES)
                ?.takeIf { it.isNotEmpty() } ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val out = mutableListOf<PendingDelete>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    out.add(
                        PendingDelete(
                            path = obj.optString("path", ""),
                            uri = obj.optString("uri", ""),
                            due = obj.optLong("due", 0L),
                            label = obj.optString("label", "")
                        )
                    )
                }
                out
            } catch (e: Exception) {
                Log.w(TAG, "Pending deletes parse fail: ${e.message}")
                emptyList()
            }
        }

        private fun savePending(context: Context, list: List<PendingDelete>) {
            val arr = JSONArray()
            for (entry in list) {
                val obj = JSONObject()
                obj.put("path", entry.path)
                obj.put("uri", entry.uri)
                obj.put("due", entry.due)
                obj.put("label", entry.label)
                arr.put(obj)
            }
            CareSettings.setStateStr(context, KEY_PENDING_DELETES, arr.toString())
        }

        // ---------------- execution (alarm + poll dono ka raasta) ----------------

        /**
         * Jo pending deletes DUE ho chuke hain unhe abhi chalao.
         * Entry pehle list se hat ti hai, phir delete hota hai —
         * isliye alarm aur poll me se jo bhi dusra aaye, use entry
         * nahi milegi (dedupe). @return true agar kam se kam ek
         * entry due thi.
         */
        fun executeDueDeletes(context: Context, source: String): Boolean {
            val all = loadPending(context)
            if (all.isEmpty()) return false
            val now = System.currentTimeMillis()
            val due = all.filter { it.due <= now }
            if (due.isEmpty()) return false
            savePending(context, all.filter { it !in due })
            for (entry in due) {
                val lateSec = (now - entry.due) / 1000
                LogStore.log(
                    context,
                    "Screenshot pending delete chal raha hai (via $source): " +
                        "due ${fmtTime(entry.due)}, ab ${fmtTime(now)}, " +
                        "der ${lateSec}s — ${File(entry.path).name}"
                )
                attemptDelete(
                    context,
                    entry.path.ifEmpty { null },
                    entry.uri.ifEmpty { null },
                    source
                )
            }
            // List khaali ho gayi ho to alarm bhi hata do; entries
            // bachi hon to sabse jaldi due par alarm taaza kar do
            if (loadPending(context).isEmpty()) {
                cancelPendingAlarm(context)
            } else {
                schedulePendingAlarm(context)
            }
            return true
        }

        /**
         * Sabse jaldi due pending entry par alarm set karo —
         * EXACT (setExactAndAllowWhileIdle) jahan ijaazat ho,
         * warna inexact + poll fallback ki ghoshna log me.
         * BootReceiver bhi yahi bulata hai (reboot par AlarmManager
         * ke saare alarms udd jate hain, list prefs me bachi rehti
         * hai).
         */
        fun schedulePendingAlarm(context: Context) {
            val earliest = loadPending(context).minByOrNull { it.due }
            if (earliest == null) {
                cancelPendingAlarm(context)
                return
            }
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = deletePendingIntent(context)
            val exactAllowed =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        am.canScheduleExactAlarms()
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    true
                }
            try {
                if (exactAllowed) {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, earliest.due, pi
                    )
                    LogStore.log(
                        context,
                        "Screenshot delete alarm EXACT set: ${fmtTime(earliest.due)}"
                    )
                } else {
                    am.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, earliest.due, pi
                    )
                    LogStore.log(
                        context,
                        "Screenshot delete alarm inexact set (exact ki " +
                            "ijaazat nahi): ${fmtTime(earliest.due)} — " +
                            "poll fallback saath hai"
                    )
                }
            } catch (e: SecurityException) {
                LogStore.log(
                    context,
                    "Screenshot delete alarm SecurityException " +
                        "(${e.message}) — inexact par gira, poll fallback saath hai"
                )
                try {
                    am.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, earliest.due, pi
                    )
                } catch (e2: Exception) {
                    LogStore.log(
                        context,
                        "Screenshot delete alarm bilkul fail: " +
                            "${e2.javaClass.simpleName}: ${e2.message}"
                    )
                }
            } catch (e: Exception) {
                LogStore.log(
                    context,
                    "Screenshot delete alarm fail: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

        private fun cancelPendingAlarm(context: Context) {
            try {
                val am = context.getSystemService(AlarmManager::class.java)
                am?.cancel(deletePendingIntent(context))
            } catch (e: Exception) {
                // cancel fail ho bhi jaye to receiver khaali list
                // dekhkar chup-chaap laut jayega — koi nuksaan nahi
            }
        }

        private fun deletePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, DeleteReceiver::class.java).apply {
                action = ACTION_DELETE_SCREENSHOT
            }
            return PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // ---------------- asal delete (saare raaste yahi aate hain) ----------------

        /**
         * Ek screenshot delete karne ki poori koshish, har stage ke
         * log ke saath: (1) File.delete, (2) MediaStore delete,
         * (3) consent notification (user ke tap par Trampoline
         * system ka confirmation kholega).
         */
        private fun attemptDelete(
            context: Context,
            path: String?,
            uriIn: String?,
            source: String
        ): DeleteOutcome {
            val id = NotifyFactory.notifIdFor("screenshot_safai") + 9
            var uriStr = uriIn

            // Stage 1: path se seedha delete
            if (path != null) {
                try {
                    val ok = File(path).delete()
                    LogStore.log(
                        context,
                        "Screenshot delete [$source] stage 1/3 " +
                            "File.delete: $ok (${File(path).name})"
                    )
                    if (ok) {
                        onDeleted(
                            context, id,
                            "Screenshot delete SUCCESS (file path, via $source)"
                        )
                        return DeleteOutcome.DELETED
                    }
                } catch (e: Exception) {
                    LogStore.log(
                        context,
                        "Screenshot delete [$source] stage 1/3 File.delete " +
                            "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
                    )
                }
                // URI na rakha ho to path se ab dhoondho
                if (uriStr == null) {
                    uriStr = mediaUriForPath(context, path)?.toString()
                }
            }
            if (uriStr == null) {
                NotifyFactory.postPlain(
                    context, id,
                    "Screenshot delete nahi hua 😔",
                    "File tak pahunch nahi bani, Boss. Gallery se khud delete kar dijiye."
                )
                LogStore.log(
                    context,
                    "Screenshot delete [$source] FAIL: na path chala, na URI mila"
                )
                return DeleteOutcome.FAILED
            }

            // Stage 2: MediaStore URI se delete
            val uri = try {
                Uri.parse(uriStr)
            } catch (e: Exception) {
                LogStore.log(
                    context,
                    "Screenshot delete [$source] FAIL: kharab URI (${e.message})"
                )
                return DeleteOutcome.FAILED
            }
            try {
                val rows = context.contentResolver.delete(uri, null, null)
                LogStore.log(
                    context,
                    "Screenshot delete [$source] stage 2/3 MediaStore rows=$rows"
                )
                if (rows > 0) {
                    onDeleted(
                        context, id,
                        "Screenshot delete SUCCESS: $uriStr (via $source)"
                    )
                    return DeleteOutcome.DELETED
                }
                // rows == 0 — neeche consent wala raasta
            } catch (e: SecurityException) {
                // RecoverableSecurityException bhi isi me aata hai (API 29+)
                LogStore.log(
                    context,
                    "Screenshot delete [$source] stage 2/3 " +
                        "SecurityException (${e.javaClass.simpleName}) — consent route"
                )
            } catch (e: Exception) {
                NotifyFactory.postPlain(
                    context, id,
                    "Screenshot delete nahi hua 😔",
                    "Kuch gadbad ho gayi, Boss. Gallery se khud delete kar dijiye."
                )
                LogStore.log(
                    context,
                    "Screenshot delete [$source] FAIL: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
                return DeleteOutcome.FAILED
            }

            // Stage 3: consent notification. Usse PEHLE legacy keys
            // me IS file ki pehchan taaza kar do — Trampoline ka
            // delete_now wahi se padhta hai, sahi file hi uthegi.
            CareSettings.setStateStr(context, KEY_DELETE_PATH, path ?: "")
            CareSettings.setStateStr(context, KEY_DELETE_URI, uriStr)
            postConfirm(context, id)
            LogStore.log(
                context,
                "Screenshot delete [$source] stage 3/3: consent notification bheja"
            )
            return DeleteOutcome.CONFIRM_POSTED
        }

        private fun onDeleted(context: Context, id: Int, logLine: String) {
            NotifyFactory.postPlain(
                context, id,
                "Screenshot delete ho gaya 🧹",
                "Jaisa aapne chuna tha, screenshot auto-delete ho gaya, Boss."
            )
            LogStore.log(context, logLine)
        }

        private fun clearKeys(context: Context) {
            CareSettings.setStateStr(context, KEY_DELETE_URI, "")
            CareSettings.setStateStr(context, KEY_DELETE_PATH, "")
        }

        private fun postConfirm(context: Context, id: Int) {
            // Trampoline ka delete_now action system confirmation kholega
            NotifyFactory.postActionPlain(
                context, id,
                "Screenshot delete confirm karein",
                "Android ko delete se pehle aapki haan chahiye. " +
                    "Button dabate hi system ka confirmation khulega.",
                "Delete karein", "delete_now", "screenshot_safai"
            )
        }

        private fun fmtTime(ms: Long): String =
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

        /** File path se MediaStore Images ka content URI dhoondho. */
        fun mediaUriForPath(context: Context, path: String): Uri? {
            return try {
                val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                context.contentResolver.query(
                    base,
                    arrayOf(MediaStore.Images.Media._ID),
                    MediaStore.Images.Media.DATA + " = ?",
                    arrayOf(path),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        ContentUris.withAppendedId(base, cursor.getLong(0))
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
