package com.spdev22.mycare.receiver

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.notify.NotifyFactory
import java.io.File

/**
 * DeleteReceiver — Screenshot Safai ka scheduled auto-delete.
 *
 * User ne jo delay chuna tha (5 min / 1 ghanta / 1 din), uske baad
 * AlarmManager yahan bhejta hai aur hum screenshot delete karne ki
 * koshish karte hain.
 *
 * 0.2.5 (folder-watch): ab asal pehchan file ka PATH hai. Koshishon
 * ka kram: (1) path se seedha File delete, (2) MediaStore URI se
 * delete (path se URI ab dhoondha bhi ja sakta hai — media index
 * tab tak ban chuka hota hai), (3) woh bhi na ho to "Delete confirm
 * karein" notification — ek tap par system ka confirmation khulta
 * hai. Chup-chaap delete ka jhootha vaada nahi kiya gaya hai.
 */
class DeleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val path = CareSettings.stateStr(context, KEY_DELETE_PATH)
            ?.takeIf { it.isNotEmpty() }
        var uriStr = CareSettings.stateStr(context, KEY_DELETE_URI)
            ?.takeIf { it.isNotEmpty() }
        if (path == null && uriStr == null) return
        val id = NotifyFactory.notifIdFor("screenshot_safai") + 9

        // (1) Path se seedha delete — kuch builds par kaam kar jata hai
        if (path != null) {
            try {
                if (File(path).delete()) {
                    onDeleted(context, id, "Screenshot auto-delete SUCCESS (file path)")
                    return
                }
            } catch (e: Exception) {
                LogStore.log(context, "Screenshot delete: file path fail (${e.message})")
            }
            // (2a) URI na rakha ho to path se ab dhoondho
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
            LogStore.log(context, "Screenshot delete fail: na path chala, na URI mila")
            clearKeys(context)
            return
        }

        // (2b) MediaStore URI se delete
        val uri = try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            clearKeys(context)
            return
        }
        try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) {
                onDeleted(context, id, "Screenshot auto-delete SUCCESS: $uriStr")
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
            clearKeys(context)
        }
    }

    private fun onDeleted(context: Context, id: Int, logLine: String) {
        NotifyFactory.postPlain(
            context, id,
            "Screenshot delete ho gaya 🧹",
            "Jaisa aapne chuna tha, screenshot auto-delete ho gaya, Boss."
        )
        LogStore.log(context, logLine)
        clearKeys(context)
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

    companion object {
        private const val TAG = "DeleteReceiver"
        const val KEY_DELETE_URI = "state_delete_uri"
        const val KEY_DELETE_PATH = "state_delete_path"

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
