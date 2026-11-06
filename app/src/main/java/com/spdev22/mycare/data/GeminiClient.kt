package com.spdev22.mycare.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GeminiClient — "AI se Workflow banwao" (0.2.5) ke peeche ka client.
 *
 * Kaam: user ki Hinglish/Hindi/English description ko Gemini
 * (generateContent API) ke paas bhejna, jawab me aaya Workflow
 * Format v1 JSON SAKHTAI se jaanchna-saaf karna, aur saaf record
 * wapas dena. Kuch bhi gadbad ho — network, galat key, kharab JSON,
 * manaahi trigger/action — to Failure me imaandaar Hinglish wajah;
 * aise me KUCH save nahi hota.
 *
 * Key aur model user ke apne hain (Settings me rakhe jaate hain;
 * key sirf phone par rehti hai). Yeh function network call karta
 * hai — hamesha background thread se bulayein.
 *
 * Suraksha ka daira: AI sirf wohi trigger types aur actions use
 * kar sakta hai jo engine asal me chalata hai. 'jump'/'delete_file'
 * jaan-boojh kar AI-set se bahar hain — woh screenshot flow ke
 * bandhe hue hain, AI workflow ke haath me dena sahi nahi.
 *
 * 0.2.6: prompt ab POORA Format-v1 manual hai — har trigger, param,
 * action aur variable jo engine asal me chalata hai (aur manaahi
 * wali cheezein saaf taur par band). Naya trigger
 * notification_posted (packages + keyword STRING params, validation
 * loop inhe alag se sambhalta hai) aur naye actions
 * set_volume/set_brightness (percent zaroori) whitelist me jude.
 */
object GeminiClient {

    sealed class Result {
        data class Success(val workflowJson: JSONObject, val name: String) : Result()
        data class Failure(val message: String) : Result()
    }

    // Engine asal me jo trigger types chalata hai (CareService +
    // DailyScheduler + observers + DeviceAdmin + 0.2.6 se
    // NotificationListener) — iske bahar manaahi.
    private val TRIGGER_TYPES = setOf(
        "first_unlock", "app_hopping", "single_app_over",
        "continuous_use_over", "time", "interval", "battery_level",
        "screenshot_taken", "contact_saved", "app_installed",
        "cache_over", "unused_apps", "failed_unlock",
        "notification_posted", "app_group_used"
    )

    // AI ke liye khule button actions (engine ke implemented set ka
    // surakshit hissa — jump/delete_file screenshot-flow bandhe hain).
    // 0.2.6: set_volume (AudioManager) aur set_brightness
    // (Settings.System) bhi khule — dono ke liye percent zaroori hai.
    private val AI_ACTIONS = setOf(
        "open_app", "open_url", "open_app_info", "open_whatsapp_chat",
        "lock_phone", "go_home", "open_clock", "skip",
        "set_volume", "set_brightness"
    )

    private val SLOTS = setOf("music", "video", "chat", "clock")

    private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val TIME_RE = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    private val MODEL_RE = Regex("^[A-Za-z0-9._-]+$")

    /** Trigger type ke hisaab se kaun se params chalenge. */
    private fun allowedTriggerParams(type: String): Set<String> = when (type) {
        "time" -> setOf("time")
        "interval" -> setOf("hours")
        "battery_level" -> setOf("level", "state")
        "single_app_over", "continuous_use_over" -> setOf("minutes")
        "app_hopping" -> setOf("apps", "seconds", "window_minutes")
        // 0.2.6: dono STRING params hain — validate loop inhe alag
        // se sambhalta hai (packages = comma-wali package list,
        // keyword = ek shabd/vakyaansh)
        "notification_posted" -> setOf("packages", "keyword")
        // 0.2.8: app_group_used — "group" is a STRING param (the App
        // Group's name, handled specially in the validate loop like
        // packages/keyword); "minutes" is numeric.
        "app_group_used" -> setOf("group", "minutes")
        else -> emptySet()
    }

    /** Numeric param ki seema (min, max, default). */
    private fun clampFor(key: String): Triple<Int, Int, Int> = when (key) {
        "minutes" -> Triple(1, 720, 30)
        "hours" -> Triple(1, 24, 2)
        "level" -> Triple(1, 100, 20)
        "apps" -> Triple(2, 10, 3)
        "seconds" -> Triple(1, 600, 10)
        "window_minutes" -> Triple(1, 60, 5)
        "cache_mb" -> Triple(10, 5000, 500)
        "days" -> Triple(1, 365, 30)
        "cooldown_minutes" -> Triple(1, 1440, 45)
        "after_minutes" -> Triple(1, 120, 1)
        else -> Triple(0, 100000, 0)
    }

    private val SETTINGS_KEYS = setOf(
        "minutes", "hours", "level", "cache_mb", "days",
        "cooldown_minutes", "after_minutes"
    )

    // ---------------- public API ----------------

    /**
     * Description se workflow banwao. BLOCKING network call hai —
     * background thread se hi bulayein.
     *
     * @param existingIds pehle se maujood saare workflow ids
     * (pack + user) — naye id ki uniqueness ke liye.
     */
    fun generate(
        apiKey: String,
        model: String,
        description: String,
        existingIds: Set<String>
    ): Result {
        val cleanModel = model.trim()
        if (!MODEL_RE.matches(cleanModel)) {
            return Result.Failure(
                "The model name does not look right, Boss. Please check " +
                    "the Gemini model in Settings (e.g. gemini-2.0-flash)."
            )
        }
        val prompt = buildPrompt(description.trim())
        val responseText = try {
            callGemini(apiKey.trim(), cleanModel, prompt)
        } catch (e: GeminiHttpException) {
            return Result.Failure(e.userMessage)
        } catch (e: Exception) {
            return Result.Failure(
                "Network problem, Boss — the request did not reach Gemini " +
                    "(${e.message?.take(100) ?: "unknown error"}). " +
                    "Check your internet and try again."
            )
        }
        val rawJson = extractJsonObject(responseText)
            ?: return Result.Failure(
                "Gemini did not reply in the right format, Boss. " +
                    "Describe it a little more clearly and try again."
            )
        return validateAndClean(rawJson, existingIds)
    }

    // ---------------- Gemini HTTP call ----------------

    private class GeminiHttpException(val userMessage: String) : Exception(userMessage)

    private fun callGemini(apiKey: String, model: String, prompt: String): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "$model:generateContent?key=" +
                URLEncoder.encode(apiKey, "UTF-8")
        )
        val body = JSONObject()
            .put(
                "contents", JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            .put("generationConfig", JSONObject().put("temperature", 0.3))

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { out ->
                out.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val serverMsg = try {
                    JSONObject(text).optJSONObject("error")
                        ?.optString("message")?.take(160) ?: ""
                } catch (e: Exception) {
                    ""
                }
                throw GeminiHttpException(
                    "Gemini did not answer (HTTP $code)" +
                        (if (serverMsg.isNotEmpty()) ": $serverMsg" else "") +
                        ". Please check the API key and model in Settings, Boss."
                )
            }
            // candidates[0].content.parts[*].text jodo
            val root = JSONObject(text)
            val candidates = root.optJSONArray("candidates")
            val first = candidates?.optJSONObject(0)
            val parts = first?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
                }
            }
            if (sb.isEmpty()) {
                throw GeminiHttpException(
                    "Gemini returned an empty answer, Boss. Please try again."
                )
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- prompt ----------------
    //
    // 0.2.6: prompt ab ek POORA manual hai — jo kuch engine asal
    // me chalata hai (har trigger, har param, har action, har
    // variable) wahi yahan likha hai, taaki AI poore engine ka
    // sahi istemal kare. Koi bhi cheez jo engine me nahi hai
    // (set_alarm, jump, {group} var...) yahan jaan-boojh kar
    // "manaahi" me saaf likhi hai — AI use invent na kare.

    private fun buildPrompt(description: String): String {
        return """
You are the workflow generator of "My Care", an Android care app. The user (called "Boss") describes a care workflow in Hindi, Hinglish or English. Convert it into ONE workflow record in "Workflow Format v1" JSON.

OUTPUT RULE (STRICT): Reply with ONLY one JSON object. No markdown fences, no explanation, no greeting, no extra text before or after. If the description is impossible with the triggers/actions listed below, still output the CLOSEST possible workflow using only what exists.

=== 1. JSON SHAPE (exactly these fields) ===
{"id":"snake_case_english","name":"Short Name","enabled":true,"bot_type":"notification","trigger":{"type":"..."},"message":{"title":"...","text":"..."},"buttons":[{"label":"...","action":"...","args":{}}],"steps":[],"settings":{}}
- "id": short snake_case English suggestion (lowercase letters, digits, underscores). The app automatically adds an "ai_" prefix and makes it unique — do not add prefixes yourself.
- "name": short human name for the workflow (max 60 characters). ENGLISH ONLY.
- "enabled": always true.
- "steps": always [].
- OPTIONAL top-level "action": a DIRECT action that runs IMMEDIATELY when the workflow fires (the notification is still posted as the record). Shape: {"type":"set_brightness","percent":25} or {"type":"set_volume","percent":25}. ONLY these two types, percent 0-100 REQUIRED. Use it when the user wants the thing DONE automatically (e.g. "dim the screen at 6 pm", "set volume to 25% when a music app opens") instead of just being asked. Omit the field otherwise.

=== 2. TRIGGERS (use ONLY these 15 types; copy names exactly) ===
1. "time" — fires daily at a fixed time. Params: {"time":"HH:MM"} 24-hour, REQUIRED (e.g. "08:00", "22:30").
2. "interval" — fires every N hours. Params: {"hours": number 1-24}. (The app stays quiet between 23:00 and 07:00 for interval workflows.)
3. "battery_level" — Params: {"level": number 1-100 (REQUIRED), "state": "charging" or "discharging"}. With "charging": fires once when charge reaches the level (use for full-charge alerts, e.g. level 95). With "discharging": fires when battery drops to the level (use for low-battery warnings, e.g. level 20). Variable: {percent}.
4. "single_app_over" — one single app used continuously for N minutes. Params: {"minutes": number 1-720}. Variables: {app}, {minutes}.
5. "continuous_use_over" — the phone itself used continuously for N minutes without a break. Params: {"minutes": number 1-720}. Variable: {minutes}.
6. "app_hopping" — boredom pattern: many apps opened one after another, none used properly. Params: {"apps": number 2-10 (how many different apps), "seconds": number 1-600 (each app used LESS than this), "window_minutes": number 1-60 (within this window)}. Also put "cooldown_minutes" in settings (see section 5).
7. "first_unlock" — the first phone unlock of the day. No params.
8. "screenshot_taken" — a new screenshot appears in the Screenshots folder. No params.
9. "contact_saved" — a new contact is saved. No params. Variable: {name} (the new contact's name). Note: the app automatically adds its own WhatsApp button when a number exists; your buttons come after it.
10. "app_installed" — a new app is installed. No params.
11. "cache_over" — some app's cache grows too big. NO trigger params; the limit goes in settings: {"cache_mb": number 10-5000}. Variables: {app}, {cache} (e.g. "512 MB"). Note: for this trigger the app shows its own fixed notification with an App Info button — keep your buttons minimal (one skip button is enough).
12. "unused_apps" — apps not used for many days. NO trigger params; the limit goes in settings: {"days": number 1-365}. Variables: {app}, {count} (how many such apps).
13. "failed_unlock" — wrong unlock attempts reach the user's threshold (default 2) within 10 minutes (possible intruder). No params. The app also captures a front-camera photo for the alert when it can.
14. "notification_posted" — a notification arrives from another app. Params (both optional strings): {"packages": "com.whatsapp,com.google.android.gm", "keyword": "OTP"}. "packages" = comma-separated Android package names; only those apps' notifications fire it (omit = all apps). "keyword" = a word that must appear in the notification title/text, case-insensitive (omit = any notification from those apps). Variables: {app} (sending app's name), {text} (the notification's title). NOTE: this trigger needs the user to grant "Notification Access" in the app's Setup Wizard — still generate it when the user asks for notification-based workflows.

15. "app_group_used" — an app from a named App Group is used. Params: {"group":"Music"} REQUIRED (the group's exact name — the user creates groups in the app; if the group does not exist yet, the workflow simply never fires until he creates it), {"minutes": number 0-720} OPTIONAL (0 or omitted = fire as soon as the app opens; N = after N minutes of use). Variables: {app}, {minutes}. PERFECT partner for a direct action, e.g. group "Music" + action set_volume 25.

For triggers NOT listed with params above, the trigger object contains ONLY "type". Never invent param names.

=== 3. BUTTON ACTIONS (use ONLY these; copy names exactly) ===
Every button: {"label":"short label (max 30 chars)","action":"...","args":{...}}. "args" only where listed.
- "open_app" — opens an app. args: EITHER {"slot":"music"} / {"slot":"video"} / {"slot":"chat"} / {"slot":"clock"} (opens the app the user chose for that purpose — PREFER slots), OR {"package":"com.example.app"} (one exact app), OR {"kind":"music"} (any music app).
- "open_url" — opens a link. args: {"url":"https://..."} REQUIRED.
- "open_app_info" — opens an app's system App Info page (for clearing cache / uninstall nudges). args: {"package":"com.example.app"} REQUIRED.
- "open_whatsapp_chat" — opens WhatsApp; optional args {"number":"919999999999"} opens a chat with that number. No args = just opens WhatsApp.
- "lock_phone" — locks the phone immediately. No args. (Works only if the user enabled Device Admin.)
- "go_home" — sends the user to the Home screen (the "close app / take a break" effect). No args.
- "open_clock" — opens the user's Clock app so he can set an alarm himself. No args. (There is NO automatic alarm-setting action — never try to set alarms.)
- "set_volume" — sets the MEDIA (music/video) volume. args: {"percent": number 0-100} REQUIRED. Example: 25 = quiet and safe for ears, 50 = half, 0 = silent.
- "set_brightness" — sets screen brightness. args: {"percent": number 0-100} REQUIRED. Example: 20 = dim for night, 70 = bright for outdoors. (Needs a system permission the user grants in the Setup Wizard; if not granted, the app politely tells the user — the button is still correct to generate.)
- "skip" — dismisses the notification, does nothing else. No args. Almost every workflow should end with ONE skip button (label ideas: "Skip", "OK", "Later").

FORBIDDEN (do NOT use — the app rejects them): set_alarm, set_reminder, input_text, jump, delete_file, assign_group, kill_app, or any other name you invent.

=== 4. BOT RULE (buttons count) ===
- 1 to 3 buttons -> "bot_type":"notification".
- Exactly 4 buttons -> "bot_type":"popup" (the app shows a popup card instead of a notification).
- Never more than 4 buttons. Prefer 2-3.

=== 5. SETTINGS MAP ===
"settings" holds the workflow's editable numeric limits so Boss can change them later in the app. Allowed keys ONLY: "minutes", "hours", "level", "cache_mb" (10-5000), "days" (1-365), "cooldown_minutes" (1-1440; minimum gap between two firings), "after_minutes" (rarely needed).
- MIRROR the main limit here: e.g. trigger minutes 45 -> settings {"minutes":45}; app_hopping -> add {"cooldown_minutes":45}.
- For "cache_over" and "unused_apps" the limit lives ONLY in settings (cache_mb / days) — it is REQUIRED there.

=== 6. VARIABLES (only inside message.title / message.text) ===
Use ONLY the variables listed for that trigger in section 2: {app}, {minutes}, {percent}, {cache}, {text}, {name}, {count}. Write them exactly like {app}. Any other {placeholder} (e.g. {group}, {time}, {date}) is NOT supported and would be shown literally — never use them.

=== 7. VOICE (name / message.title / message.text / button labels) ===
- LANGUAGE: ENGLISH ONLY — every user-facing string you produce (name, title, text, button labels) must be in English, even when the user described the workflow in Hindi or Hinglish. (The app and all its workflows are English now.)
- Tone: respectful and caring, like a friend/family member. Address the user as "Boss" in the text (usually at the start or end).
- Friendly scolding is welcome for study/health habits (e.g. "at this rate, how will the exam get cleared?"), never abusive, never frightening.
- Title: ONE short line (max 80 chars). Text: 1-2 short lines (max 400 chars). Emojis: at most 1-2, only if natural.
- The title/text must make sense with the trigger and use its variables where natural.

=== 7b. PERMISSION MAP (the app checks these itself — you only need the right shape) ===
The app automatically works out which permissions a workflow needs from its trigger and actions, and asks the user for the missing ones the moment the workflow is switched ON — you never write permissions into the JSON. For your awareness: usage triggers (app_hopping, single_app_over, continuous_use_over, app_group_used, cache_over, unused_apps) need Usage Access; screenshot_taken needs Photos/Media; notification_posted needs Notification Access; failed_unlock needs Device Admin + Camera; contact_saved needs Contacts; time needs Exact Alarms; set_brightness needs the system-settings grant; go_home needs Accessibility; lock_phone needs Device Admin; popup needs Display-over-apps. So: generate the workflow the user asked for — the app handles the permission side.

=== 8. EXAMPLES (follow this quality and shape) ===

Example A — daily time reminder:
{"id":"night_water_reminder","name":"Night Water Reminder","enabled":true,"bot_type":"notification","trigger":{"type":"time","time":"22:00"},"message":{"title":"Drink some water, Boss 💧","text":"Have a glass of water before sleeping, Boss. It keeps you healthy and helps you sleep better."},"buttons":[{"label":"Done","action":"skip"}],"steps":[],"settings":{}}

Example B — usage limit with friendly scolding:
{"id":"long_app_scolding","name":"Long App Scolding","enabled":true,"bot_type":"notification","trigger":{"type":"single_app_over","minutes":45},"message":{"title":"That's enough, Boss ⚠️","text":"You have been using {app} for the last {minutes} minutes. Study time is being wasted — take a small break, or how will the exam get cleared?"},"buttons":[{"label":"Go Home","action":"go_home"},{"label":"Lock Phone","action":"lock_phone"},{"label":"Skip","action":"skip"}],"steps":[],"settings":{"minutes":45}}

Example C — music volume safety with a set_volume BUTTON (asks first):
{"id":"music_volume_safety","name":"Music Volume Safety","enabled":true,"bot_type":"notification","trigger":{"type":"single_app_over","minutes":1},"message":{"title":"Volume Alert 🎧","text":"Boss, a music app is open. Shall I set the volume to 25% to keep your ears safe? Loud sound damages ears over time."},"buttons":[{"label":"Set volume 25%","action":"set_volume","args":{"percent":25}},{"label":"Skip","action":"skip"}],"steps":[],"settings":{"minutes":1}}

Example D — DIRECT action (acts immediately, no tap needed):
{"id":"evening_dim","name":"Evening Dim","enabled":true,"bot_type":"notification","trigger":{"type":"time","time":"18:00"},"message":{"title":"Evening brightness 🌆","text":"It is 6 PM, Boss — screen brightness has been set to 25% automatically to keep your eyes comfortable."},"buttons":[{"label":"OK","action":"skip"}],"steps":[],"settings":{},"action":{"type":"set_brightness","percent":25}}

=== 9. USER'S REQUEST ===
"$description"
""".trimIndent()
    }

    // ---------------- jawab se JSON nikaalna ----------------

    private fun extractJsonObject(text: String): JSONObject? {
        var t = text.trim()
        // Markdown fences aaye hon to hata do
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            val end = t.lastIndexOf("```")
            if (end > 0) t = t.substring(0, end).trim()
        }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(t.substring(start, end + 1))
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- sakht jaanch + safai ----------------

    private fun validateAndClean(raw: JSONObject, existingIds: Set<String>): Result {
        // --- trigger ---
        val triggerRaw = raw.optJSONObject("trigger")
            ?: return Result.Failure("No trigger was found in the AI's answer, Boss. Please try again.")
        val type = triggerRaw.optString("type").trim()
        if (type !in TRIGGER_TYPES) {
            return Result.Failure(
                "The AI chose a trigger that My Care cannot run ('$type'). " +
                    "Describe it a little differently and try again, Boss."
            )
        }
        val trigger = JSONObject().put("type", type)
        for (key in allowedTriggerParams(type)) {
            when (key) {
                "time" -> {
                    val tv = triggerRaw.optString("time").trim()
                    if (!TIME_RE.matches(tv)) {
                        return Result.Failure(
                            "The AI did not give the time in the right format " +
                                "(HH:MM, e.g. 22:00), Boss. Please try again."
                        )
                    }
                    trigger.put("time", tv)
                }
                "state" -> {
                    val lv = numeric(triggerRaw.opt("level"))
                    var st = triggerRaw.optString("state").trim().lowercase()
                    if (st != "charging" && st != "discharging") {
                        st = if (lv != null && lv <= 30) "discharging" else "charging"
                    }
                    trigger.put("state", st)
                }
                "packages" -> {
                    // notification_posted: comma-wali package list —
                    // sirf sahi package naam hi rehne do; khaali list
                    // ka matlab "sab apps" hai, isliye key hi gira do
                    val pkgs = triggerRaw.optString("packages")
                        .split(",").map { it.trim() }
                        .filter { PACKAGE_RE.matches(it) }
                        .take(10)
                    if (pkgs.isNotEmpty()) {
                        trigger.put("packages", pkgs.joinToString(","))
                    }
                }
                "group" -> {
                    // app_group_used: the App Group's NAME (string).
                    // Required — without a group the trigger can
                    // never match anything.
                    val gname = triggerRaw.optString("group").trim().take(40)
                    if (gname.isEmpty()) {
                        return Result.Failure(
                            "The AI did not name an App Group for the " +
                                "group trigger, Boss. Mention the group " +
                                "name (e.g. Music) and try again."
                        )
                    }
                    trigger.put("group", gname)
                }
                "keyword" -> {
                    // notification_posted: ek shabd/vakyaansh; bahut
                    // lamba ya khaali ho to key hi gira do (bina
                    // keyword ke trigger har notification par chalega)
                    val kw = triggerRaw.optString("keyword").trim().take(50)
                    if (kw.isNotEmpty()) trigger.put("keyword", kw)
                }
                else -> {
                    val (_, _, def) = clampFor(key)
                    val v = numeric(triggerRaw.opt(key)) ?: def
                    val (min, max, _) = clampFor(key)
                    trigger.put(key, v.coerceIn(min, max))
                }
            }
        }
        if (type == "battery_level" && !trigger.has("level")) {
            return Result.Failure(
                "The AI did not give a battery level, Boss. Please try again."
            )
        }

        // --- message ---
        val msgRaw = raw.optJSONObject("message")
        val text = (msgRaw?.optString("text") ?: "").trim().take(400)
        if (text.isEmpty()) {
            return Result.Failure("The message text in the AI's answer was empty, Boss. Please try again.")
        }
        val name = raw.optString("name").trim().take(60)
            .ifEmpty { "AI Workflow" }
        val title = (msgRaw?.optString("title") ?: "").trim().take(80)
            .ifEmpty { name }

        // --- bot type + buttons ---
        val botType =
            if (raw.optString("bot_type").trim() == "popup") "popup" else "notification"
        val maxButtons = if (botType == "popup") 4 else 3
        val buttons = JSONArray()
        val btnArr = raw.optJSONArray("buttons")
        if (btnArr != null) {
            for (i in 0 until btnArr.length()) {
                if (buttons.length() >= maxButtons) break
                val b = btnArr.optJSONObject(i) ?: continue
                cleanButton(b)?.let { buttons.put(it) }
            }
        }

        // --- settings (sirf jaane hue numeric keys) ---
        val settings = JSONObject()
        val setRaw = raw.optJSONObject("settings")
        if (setRaw != null) {
            for (key in SETTINGS_KEYS) {
                if (!setRaw.has(key)) continue
                val v = numeric(setRaw.opt(key)) ?: continue
                val (min, max, _) = clampFor(key)
                settings.put(key, v.coerceIn(min, max))
            }
        }

        // --- 0.2.8: optional top-level DIRECT action ---
        // {"type":"set_brightness"|"set_volume","percent":0-100} —
        // runs immediately when the workflow fires. Anything else
        // (set_alarm and friends) is dropped, never trusted.
        val actionOut: JSONObject? = run {
            val aRaw = raw.optJSONObject("action") ?: return@run null
            val aType = aRaw.optString("type").trim()
            if (aType != "set_brightness" && aType != "set_volume") return@run null
            val pct = numeric(aRaw.opt("percent")) ?: numeric(aRaw.opt("value"))
                ?: return@run null
            JSONObject()
                .put("type", aType)
                .put("percent", pct.coerceIn(0, 100))
        }

        // --- id: surakshit + unique, "ai_" shuruaat ke saath ---
        val id = uniqueId(raw.optString("id"), name, existingIds)

        val out = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("enabled", raw.optBoolean("enabled", true))
            .put("bot_type", botType)
            .put("trigger", trigger)
            .put("message", JSONObject().put("title", title).put("text", text))
            .put("buttons", buttons)
            .put("steps", JSONArray())
            .put("settings", settings)
        if (actionOut != null) out.put("action", actionOut)
        return Result.Success(out, name)
    }

    private fun cleanButton(b: JSONObject): JSONObject? {
        val label = b.optString("label").trim().take(30)
        val action = b.optString("action").trim()
        if (label.isEmpty() || action !in AI_ACTIONS) return null
        val out = JSONObject().put("label", label).put("action", action)
        val argsRaw = b.optJSONObject("args")
        val args = JSONObject()
        if (argsRaw != null) {
            val pkg = argsRaw.optString("package").trim()
            if (PACKAGE_RE.matches(pkg)) args.put("package", pkg)
            val pkgs = argsRaw.optString("packages")
                .split(",").map { it.trim() }
                .filter { PACKAGE_RE.matches(it) }
            if (pkgs.isNotEmpty()) args.put("packages", pkgs.joinToString(","))
            val slot = argsRaw.optString("slot").trim()
            if (slot in SLOTS) args.put("slot", slot)
            if (argsRaw.optString("kind").trim() == "music") args.put("kind", "music")
            val url = argsRaw.optString("url").trim()
            if (url.startsWith("https://") || url.startsWith("http://")) {
                args.put("url", url.take(300))
            }
            val number = argsRaw.optString("number").filter { it.isDigit() || it == '+' }
            if (number.isNotEmpty()) args.put("number", number.take(20))
            // 0.2.6: percent sirf set_volume / set_brightness ke liye (0-100)
            if (action == "set_volume" || action == "set_brightness") {
                val pct = numeric(argsRaw.opt("percent"))
                if (pct != null) {
                    args.put("percent", pct.coerceIn(0, 100).toString())
                }
            }
        }
        // Action ke hisaab se zaroori args na hon to button ka koi matlab nahi
        when (action) {
            "open_app" -> if (!args.has("slot") && !args.has("package") &&
                !args.has("packages") && !args.has("kind")
            ) return null
            "open_url" -> if (!args.has("url")) return null
            "open_app_info" -> if (!args.has("package")) return null
            "set_volume", "set_brightness" ->
                if (!args.has("percent")) return null
        }
        if (args.length() > 0) out.put("args", args)
        return out
    }

    /** JSONObject ke kisi bhi roop (number ya string) se Int nikalo. */
    private fun numeric(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.toInt()
        else -> null
    }

    private fun uniqueId(rawId: String, name: String, existingIds: Set<String>): String {
        fun sanitize(s: String): String = s.lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .take(40)
            .trimEnd('_')
        var base = sanitize(rawId)
        if (base.isEmpty()) base = sanitize(name)
        if (base.isEmpty()) base = "workflow"
        if (!base.startsWith("ai_")) base = "ai_$base"
        var candidate = base
        var n = 2
        while (candidate in existingIds) {
            candidate = "${base}_$n"
            n++
        }
        return candidate
    }
}
