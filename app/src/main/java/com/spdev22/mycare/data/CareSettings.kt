package com.spdev22.mycare.data

import android.content.Context

/**
 * CareSettings — user ki editable settings (D3) + engine ki internal state.
 *
 * Settings ke defaults PLAN.md se aaye hain:
 * Study Suggestion 08:00, Aaj Ka Hisaab 19:45, Alarm Suggestion 20:00,
 * long-app 30 min, rest 25 min, boredom 3 apps / 10 sec, welcome ON.
 *
 * User ne Settings screen se value BADLI hai to wahi chalegi; nahi badli
 * to caller ka diya fallback (pack ka default) chalega — isiliye har getter
 * pehle SharedPreferences me "key maujood hai?" dekhta hai.
 *
 * Wahi prefs file GroupStore bhi use karta hai ("my_care_prefs").
 */
object CareSettings {

    private const val PREFS = "my_care_prefs"

    // ---- user settings ke keys ----
    const val KEY_LONG_MINUTES = "set_long_minutes"
    const val KEY_REST_MINUTES = "set_rest_minutes"
    const val KEY_BOREDOM_APPS = "set_boredom_apps"
    const val KEY_BOREDOM_SECONDS = "set_boredom_seconds"
    const val KEY_WELCOME_ENABLED = "set_welcome_enabled"
    const val KEY_PAANI_HOURS = "set_paani_hours"
    const val KEY_BATTERY_LOW = "set_battery_low"
    const val KEY_CACHE_MB = "set_cache_mb"

    // ---- plan wale defaults ----
    const val DEFAULT_STUDY_TIME = "08:00"
    const val DEFAULT_HISAAB_TIME = "19:45"
    const val DEFAULT_ALARM_TIME = "20:00"
    const val DEFAULT_LONG_MINUTES = 30
    const val DEFAULT_REST_MINUTES = 25
    const val DEFAULT_BOREDOM_APPS = 3
    const val DEFAULT_BOREDOM_SECONDS = 10
    const val DEFAULT_PAANI_HOURS = 2
    const val DEFAULT_BATTERY_LOW = 20
    const val DEFAULT_CACHE_MB = 500

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- time settings (settings_key = "study_time" -> "set_study_time") ----------

    fun time(context: Context, settingsKey: String, fallback: String): String =
        prefs(context).getString("set_$settingsKey", null) ?: fallback

    fun setTime(context: Context, settingsKey: String, value: String) {
        prefs(context).edit().putString("set_$settingsKey", value).apply()
    }

    // ---------- int settings ----------

    /** User ne set kiya ho to woh, warna fallback. */
    fun intVal(context: Context, key: String, fallback: Int): Int {
        val p = prefs(context)
        return if (p.contains(key)) p.getInt(key, fallback) else fallback
    }

    fun setInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).apply()
    }

    // ---------- welcome toggle ----------

    fun welcomeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WELCOME_ENABLED, true)

    fun setWelcomeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WELCOME_ENABLED, enabled).apply()
    }

    // ---------- engine ki internal state (user ko nahi dikhti) ----------

    fun lastWelcomeDate(context: Context): String? =
        prefs(context).getString("state_welcome_date", null)

    fun setLastWelcomeDate(context: Context, date: String) {
        prefs(context).edit().putString("state_welcome_date", date).apply()
    }

    fun boredomLastFired(context: Context): Long =
        prefs(context).getLong("state_boredom_fired", 0L)

    fun setBoredomLastFired(context: Context, ts: Long) {
        prefs(context).edit().putLong("state_boredom_fired", ts).apply()
    }

    fun restSnoozeUntil(context: Context): Long =
        prefs(context).getLong("state_rest_snooze_until", 0L)

    fun setRestSnoozeUntil(context: Context, ts: Long) {
        prefs(context).edit().putLong("state_rest_snooze_until", ts).apply()
    }

    /** Jis stretch (start ts) ke liye Rest Nudge fire ho chuka hai. */
    fun restFiredFor(context: Context): Long =
        prefs(context).getLong("state_rest_fired_for", 0L)

    fun setRestFiredFor(context: Context, stretchStart: Long) {
        prefs(context).edit().putLong("state_rest_fired_for", stretchStart).apply()
    }

    /** "package@sessionStart" — jis session ke liye Long-App fire ho chuka. */
    fun longAppFiredKey(context: Context): String? =
        prefs(context).getString("state_longapp_fired_key", null)

    fun setLongAppFiredKey(context: Context, key: String) {
        prefs(context).edit().putString("state_longapp_fired_key", key).apply()
    }

    // ---------- generic engine state (Phase 3 ke naye triggers ke liye) ----------

    fun stateLong(context: Context, key: String, default: Long = 0L): Long =
        prefs(context).getLong(key, default)

    fun setStateLong(context: Context, key: String, value: Long) {
        prefs(context).edit().putLong(key, value).apply()
    }

    fun stateStr(context: Context, key: String): String? =
        prefs(context).getString(key, null)

    fun setStateStr(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    // ---------- action-target apps (2.1 ka app picker) ----------
    // Slot naam: "music", "video", "chat" (open_app buttons ke liye)
    // aur "clock" (0.2.4: alarm ke liye chuna gaya; 0.2.5 se yahi app
    // Alarm Suggestion par KHULTA hai — user alarm wahi set karta hai)
    // — user ne app chuna ho to wahi khulega/chalega, warna pack ke
    // default candidates chalenge.

    fun targetApp(context: Context, slot: String): String? =
        prefs(context).getString("target_$slot", null)

    fun setTargetApp(context: Context, slot: String, pkg: String?) {
        val edit = prefs(context).edit()
        if (pkg == null) edit.remove("target_$slot") else edit.putString("target_$slot", pkg)
        edit.apply()
    }

    // ---------- Gemini AI (0.2.5: "AI se Workflow banwao") ----------
    // API key SIRF is phone ke prefs me rehti hai — kahin upload ya
    // share nahi hoti. Model ka naam user khud badal sakta hai.

    const val KEY_GEMINI_MODEL = "set_gemini_model"
    const val DEFAULT_GEMINI_MODEL = "gemini-2.0-flash"

    fun geminiKey(context: Context): String? =
        prefs(context).getString("gemini_api_key", null)?.takeIf { it.isNotBlank() }

    fun setGeminiKey(context: Context, key: String?) {
        val edit = prefs(context).edit()
        if (key.isNullOrBlank()) {
            edit.remove("gemini_api_key")
        } else {
            edit.putString("gemini_api_key", key.trim())
        }
        edit.apply()
    }

    fun geminiModel(context: Context): String =
        prefs(context).getString(KEY_GEMINI_MODEL, null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_GEMINI_MODEL

    fun setGeminiModel(context: Context, model: String) {
        val clean = model.trim()
        val edit = prefs(context).edit()
        if (clean.isEmpty()) {
            edit.remove(KEY_GEMINI_MODEL)
        } else {
            edit.putString(KEY_GEMINI_MODEL, clean)
        }
        edit.apply()
    }
}
