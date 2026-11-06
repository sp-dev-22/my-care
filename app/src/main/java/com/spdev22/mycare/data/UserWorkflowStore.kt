package com.spdev22.mycare.data

import android.content.Context
import com.spdev22.mycare.engine.Workflow
import com.spdev22.mycare.engine.WorkflowEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * UserWorkflowStore — user ke APNE banaye hue workflows (0.2.5).
 *
 * Pehla source: "AI se Workflow banwao" (Gemini) screen. Bane hue
 * workflows Format v1 JSON ke roop me SharedPreferences me rehte
 * hain ("user_workflows_json" — JSONArray). WorkflowEngine.loadPack
 * inhe pack ke saath mila deta hai, isliye engine, scheduler,
 * toggles aur Editor-lite — sab jagah yeh pack workflows jaise hi
 * dikhte/chalte hain.
 *
 * Har save/delete par ek version counter badhta hai — CareService
 * usi ko dekhkar apni workflow list taaza kar leta hai (service
 * restart kiye bina naya workflow live ho jata hai).
 */
object UserWorkflowStore {

    private const val PREFS = "my_care_prefs"
    private const val KEY_JSON = "user_workflows_json"
    private const val KEY_VERSION = "user_workflows_version"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saare user workflows ko Workflow records me badlo. */
    fun load(context: Context): List<Workflow> {
        val arr = loadJsonArray(context)
        val result = mutableListOf<Workflow>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            try {
                val wf = WorkflowEngine.parseWorkflowObject(obj)
                if (wf.id.isNotEmpty()) result.add(wf)
            } catch (e: Exception) {
                // Ek kharab entry baaki sab ko nahi rokegi
            }
        }
        return result
    }

    /** Hamare paas rakha kachcha JSON array (har record ek workflow). */
    fun loadJsonArray(context: Context): JSONArray {
        val raw = prefs(context).getString(KEY_JSON, null)
        if (raw.isNullOrEmpty()) return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    /** Yeh id kisi user workflow ki hai? (pack ki nahi) */
    fun contains(context: Context, wfId: String): Boolean {
        val arr = loadJsonArray(context)
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optString("id") == wfId) return true
        }
        return false
    }

    /** Saare maujood id (pack + user) — naye id ki uniqueness ke liye. */
    fun existingIds(context: Context): Set<String> {
        val ids = mutableSetOf<String>()
        val arr = loadJsonArray(context)
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString("id") ?: continue
            if (id.isNotEmpty()) ids.add(id)
        }
        return ids
    }

    /** Ek workflow JSON save karo — id pehle se ho to replace, warna jodo. */
    fun save(context: Context, workflowJson: JSONObject) {
        val id = workflowJson.optString("id")
        if (id.isEmpty()) return
        val arr = loadJsonArray(context)
        val out = JSONArray()
        var replaced = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("id") == id) {
                out.put(workflowJson)
                replaced = true
            } else {
                out.put(obj)
            }
        }
        if (!replaced) out.put(workflowJson)
        prefs(context).edit()
            .putString(KEY_JSON, out.toString())
            .putLong(KEY_VERSION, version(context) + 1)
            .apply()
    }

    /** User workflow hamesha ke liye hata do. @return true agar mila tha. */
    fun delete(context: Context, wfId: String): Boolean {
        val arr = loadJsonArray(context)
        val out = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("id") == wfId) {
                removed = true
            } else {
                out.put(obj)
            }
        }
        if (!removed) return false
        prefs(context).edit()
            .putString(KEY_JSON, out.toString())
            .putLong(KEY_VERSION, version(context) + 1)
            .apply()
        return true
    }

    /**
     * Badlaav ginane wala counter — CareService har poll me ise
     * dekhkar apni list taaza karta hai.
     */
    fun version(context: Context): Long =
        prefs(context).getLong(KEY_VERSION, 0L)
}
