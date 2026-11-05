package com.spdev22.mycare.data

import android.content.Context
import com.spdev22.mycare.engine.Workflow
import org.json.JSONObject

/**
 * WorkflowOverrides — Editor-lite (0.2.3) ki per-workflow user edits.
 *
 * Pack (D9) ready-made rehta hai; user ki edits uske UPAR ek patli
 * layer ki tarah SharedPreferences me save hoti hain:
 *   ovr_title_<id>    String — message title
 *   ovr_text_<id>     String — message text
 *   ovr_time_<id>     String — "HH:MM" (sirf 'time' trigger walon ke liye)
 *   ovr_trigger_<id>  JSON   — trigger params ke badle hue numeric moolya
 *   ovr_settings_<id> JSON   — settings ke badle hue numeric moolya
 *
 * Sirf wohi values save hoti hain jo pack se ALAG hain — user pack
 * value par wapas aa jaye to woh override apne aap hat jata hai.
 *
 * Har consumer effective() se hi workflow padhta hai — CareService
 * ka poll loop, DailyScheduler/DailyAlarmReceiver, screenshot/
 * contact observers, NotifyFactory, PopupActivity — taaki ek edit
 * har jagah ek saath lagu ho. Buttons/steps/trigger-type edit
 * NAHI hote (Editor-lite ka daira); effective() unhe nahi chhuta.
 */
object WorkflowOverrides {

    private const val PREFS = "my_care_prefs"

    private fun keyTitle(id: String) = "ovr_title_$id"
    private fun keyText(id: String) = "ovr_text_$id"
    private fun keyTime(id: String) = "ovr_time_$id"
    private fun keyTrigger(id: String) = "ovr_trigger_$id"
    private fun keySettings(id: String) = "ovr_settings_$id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- padhna ----------

    fun titleOverride(context: Context, wfId: String): String? =
        prefs(context).getString(keyTitle(wfId), null)

    fun textOverride(context: Context, wfId: String): String? =
        prefs(context).getString(keyText(wfId), null)

    /** 'time' trigger wale workflow ka user-badla "HH:MM", ho to. */
    fun timeOverride(context: Context, wfId: String): String? =
        prefs(context).getString(keyTime(wfId), null)

    fun triggerOverrides(context: Context, wfId: String): Map<String, String> =
        jsonToMap(prefs(context).getString(keyTrigger(wfId), null))

    fun settingsOverrides(context: Context, wfId: String): Map<String, String> =
        jsonToMap(prefs(context).getString(keySettings(wfId), null))

    /**
     * Kisi numeric param ki override value — pehle trigger overrides,
     * phir settings overrides me dekho. Engine ke resolution points
     * (CareService, CareChecks) isse global Settings se PEHLE poochte
     * hain, taaki per-workflow edit sabse upar rahe.
     */
    fun overrideValue(context: Context, wfId: String, key: String): String? =
        triggerOverrides(context, wfId)[key] ?: settingsOverrides(context, wfId)[key]

    fun hasOverrides(context: Context, wfId: String): Boolean {
        val p = prefs(context)
        return p.contains(keyTitle(wfId)) || p.contains(keyText(wfId)) ||
            p.contains(keyTime(wfId)) || p.contains(keyTrigger(wfId)) ||
            p.contains(keySettings(wfId))
    }

    // ---------- effective workflow ----------

    /**
     * Pack workflow + user overrides = woh workflow jo asal me
     * chalega. Override na ho to wahi object wapas (bekaar copy nahi).
     * enabled/buttons/steps/trigger-type hamesha pack ke hi rehte hain.
     */
    fun effective(context: Context, wf: Workflow): Workflow {
        if (!hasOverrides(context, wf.id)) return wf
        val trigger = wf.triggerParams.toMutableMap()
        trigger.putAll(triggerOverrides(context, wf.id))
        timeOverride(context, wf.id)?.let { trigger["time"] = it }
        val settings = wf.settings.toMutableMap()
        settings.putAll(settingsOverrides(context, wf.id))
        return wf.copy(
            title = titleOverride(context, wf.id) ?: wf.title,
            text = textOverride(context, wf.id) ?: wf.text,
            triggerParams = trigger,
            settings = settings
        )
    }

    // ---------- likhna ----------

    /**
     * Editor ki save. null scalar ka matlab: woh override hata do.
     * trigger/settings maps POORI tarah replace hote hain (khaali map
     * = key hi hata do) — editor hamesha pack se alag values hi bhejta
     * hai, isliye purani ghisi-piti entries nahi bachti.
     */
    fun save(
        context: Context,
        wfId: String,
        title: String?,
        text: String?,
        time: String?,
        trigger: Map<String, String>,
        settings: Map<String, String>
    ) {
        val e = prefs(context).edit()
        if (title != null) e.putString(keyTitle(wfId), title) else e.remove(keyTitle(wfId))
        if (text != null) e.putString(keyText(wfId), text) else e.remove(keyText(wfId))
        if (time != null) e.putString(keyTime(wfId), time) else e.remove(keyTime(wfId))
        if (trigger.isEmpty()) {
            e.remove(keyTrigger(wfId))
        } else {
            e.putString(keyTrigger(wfId), mapToJson(trigger))
        }
        if (settings.isEmpty()) {
            e.remove(keySettings(wfId))
        } else {
            e.putString(keySettings(wfId), mapToJson(settings))
        }
        e.apply()
    }

    /** Is workflow ke saare overrides saaf — pack default wapas. */
    fun reset(context: Context, wfId: String) {
        prefs(context).edit()
            .remove(keyTitle(wfId))
            .remove(keyText(wfId))
            .remove(keyTime(wfId))
            .remove(keyTrigger(wfId))
            .remove(keySettings(wfId))
            .apply()
    }

    // ---------- JSON helpers ----------

    private fun mapToJson(map: Map<String, String>): String {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        return obj.toString()
    }

    private fun jsonToMap(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val result = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                result[k] = obj.optString(k)
            }
            result
        } catch (e: Exception) {
            // Kharab data par crash nahi — override nahi hai, bas
            emptyMap()
        }
    }
}
