package com.spdev22.mycare.data

import android.content.Context
import java.io.File

/**
 * IntruderCaptures — 0.2.8: front-camera photos taken when the
 * failed-unlock threshold is reached (Ghuspaithiya Alert upgrade).
 *
 * Photos live ONLY in app-private storage (filesDir/captures) —
 * nothing leaves the phone. At most the last [MAX] captures are
 * kept; older ones are deleted automatically.
 */
object IntruderCaptures {

    private const val DIR_NAME = "captures"
    const val MAX = 10

    fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** Save one JPEG capture. @return the saved file, or null on failure. */
    fun saveJpeg(context: Context, bytes: ByteArray): File? {
        return try {
            val file = File(dir(context), "intruder_${System.currentTimeMillis()}.jpg")
            file.writeBytes(bytes)
            prune(context)
            file
        } catch (e: Exception) {
            null
        }
    }

    /** All captures, newest first. */
    fun list(context: Context): List<File> {
        return try {
            dir(context).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun count(context: Context): Int = list(context).size

    fun last(context: Context): File? = list(context).firstOrNull()

    /** Keep only the newest [MAX] captures. */
    private fun prune(context: Context) {
        val all = list(context)
        if (all.size <= MAX) return
        for (old in all.drop(MAX)) {
            try {
                old.delete()
            } catch (e: Exception) {
                // a stuck file is not worth crashing over
            }
        }
    }
}
