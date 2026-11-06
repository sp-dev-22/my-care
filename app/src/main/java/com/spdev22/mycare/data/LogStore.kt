package com.spdev22.mycare.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LogStore — in-app Logs/Console ke peeche ka simple file log.
 *
 * Har workflow fire, har button action aur har badi ghatna ek line
 * bankar app ke internal file (care_logs.txt) me judti hai.
 * File chhoti rakhi gayi hai (zyada se zyada ~250 lines) taaki
 * Go phone par koi bojh na pade. Koi data phone se bahar nahi jata.
 */
object LogStore {

    private const val FILE_NAME = "care_logs.txt"
    private const val MAX_LINES = 250
    private val lock = Any()

    fun log(context: Context, message: String) {
        try {
            synchronized(lock) {
                val ts = SimpleDateFormat("dd-MM HH:mm:ss", Locale.US)
                    .format(Date())
                val file = File(context.filesDir, FILE_NAME)
                file.appendText("$ts  $message\n")
                // File badhi ho jaye to purani lines kaato
                val lines = file.readLines()
                if (lines.size > MAX_LINES) {
                    file.writeText(
                        lines.takeLast(MAX_LINES).joinToString("\n") + "\n"
                    )
                }
            }
        } catch (e: Exception) {
            // Log fail ho to app ko kuch nahi hona chahiye
        }
    }

    fun readAll(context: Context): String {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                val text = file.readText().trim()
                if (text.isEmpty()) "No logs yet, Boss." else text
            } else {
                "No logs yet, Boss."
            }
        } catch (e: Exception) {
            "Could not read logs: ${e.message}"
        }
    }

    fun clear(context: Context) {
        try {
            synchronized(lock) {
                File(context.filesDir, FILE_NAME).delete()
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}
