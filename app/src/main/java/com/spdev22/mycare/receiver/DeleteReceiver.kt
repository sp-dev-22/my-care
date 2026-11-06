package com.spdev22.mycare.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.notify.NotifyFactory

/**
 * DeleteReceiver — Screenshot Safai ka scheduled auto-delete.
 *
 * User ne jo delay chuna tha (5 min / 1 ghanta / 1 din), uske baad
 * AlarmManager yahan bhejta hai aur hum screenshot delete karne ki
 * koshish karte hain.
 *
 * Sachchai (PLAN.md ki warning): Android 12 me doosri app ke banaye
 * media ko bina maange delete karna allowed nahi hai. Direct delete
 * fail ho to hum "Delete confirm karein" notification bhejte hain —
 * ek tap par system ka confirmation khulta hai. Chup-chaap delete
 * ka jhootha vaada nahi kiya gaya hai.
 */
class DeleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uriStr = CareSettings.stateStr(context, KEY_DELETE_URI) ?: return
        val uri = try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            return
        }
        val id = NotifyFactory.notifIdFor("screenshot_safai") + 9
        try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) {
                NotifyFactory.postPlain(
                    context, id,
                    "Screenshot delete ho gaya 🧹",
                    "Jaisa aapne chuna tha, screenshot auto-delete ho gaya, Boss."
                )
                LogStore.log(context, "Screenshot auto-delete SUCCESS: $uriStr")
                CareSettings.setStateStr(context, KEY_DELETE_URI, "")
            } else {
                postConfirm(context, id)
                LogStore.log(context, "Screenshot delete: 0 rows — confirm chahiye")
            }
        } catch (e: SecurityException) {
            // RecoverableSecurityException bhi isi me aata hai (API 29+)
            postConfirm(context, id)
            LogStore.log(context, "Screenshot delete: permission/confirm chahiye")
        } catch (e: Exception) {
            NotifyFactory.postPlain(
                context, id,
                "Screenshot delete nahi hua 😔",
                "Kuch gadbad ho gayi, Boss. Gallery se khud delete kar dijiye."
            )
            LogStore.log(context, "Screenshot delete fail: ${e.message}")
        }
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

    companion object {
        private const val TAG = "DeleteReceiver"
        const val KEY_DELETE_URI = "state_delete_uri"
    }
}
