package com.spdev22.mycare.engine

import android.content.Context
import android.util.Log
import com.spdev22.mycare.data.UserWorkflowStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * WorkflowEngine — pack ko parse karke Workflow records banata hai.
 * Phase 2: triggers CareService (usage/time/unlock) aur DailyScheduler
 * (time-based) se fire hote hain; notifications NotifyFactory banata hai.
 *
 * Pack ek ready-made data file hai (D9): naye workflows nayi pack file se
 * aayenge, app dobara banane ki zaroorat nahi padegi.
 */
object WorkflowEngine {

    private const val TAG = "WorkflowEngine"
    const val PACK_ASSET_PATH = "workflows/pack_v1.json"

    /**
     * Poori workflow list jo asal me chalegi: pehle ready-made pack,
     * phir user ke apne banaye hue workflows (0.2.5: Gemini AI se
     * bane). HAR consumer yahi function use karta hai — engine,
     * scheduler, receivers, toggles, Editor-lite — isliye user
     * workflows har jagah apne aap shamil ho jaate hain.
     */
    fun loadPack(context: Context): List<Workflow> {
        val pack = try {
            val json = context.assets.open(PACK_ASSET_PATH)
                .bufferedReader().use { it.readText() }
            parsePack(json)
        } catch (e: Exception) {
            Log.e(TAG, "Pack load nahi hua: ${e.message}")
            emptyList()
        }
        val user = try {
            UserWorkflowStore.load(context)
        } catch (e: Exception) {
            Log.e(TAG, "User workflows load nahi hue: ${e.message}")
            emptyList()
        }
        if (user.isEmpty()) return pack
        // Id takraav ho to pack wala hi rahe — user wala chhod do
        val packIds = pack.map { it.id }.toSet()
        return pack + user.filter { it.id !in packIds }
    }

    fun parsePack(json: String): List<Workflow> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("workflows") ?: return emptyList()
        val result = mutableListOf<Workflow>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            result.add(parseWorkflowObject(obj))
        }
        return result
    }

    /** Ek akela workflow record parse karo (pack ya user workflow). */
    fun parseWorkflowObject(obj: JSONObject): Workflow = parseWorkflow(obj)

    private fun parseWorkflow(obj: JSONObject): Workflow {
        val trigger = obj.optJSONObject("trigger")
        val message = obj.optJSONObject("message")
        val action = obj.optJSONObject("action")

        val steps = mutableListOf<WfStep>()
        val stepArr = obj.optJSONArray("steps")
        if (stepArr != null) {
            for (i in 0 until stepArr.length()) {
                val s = stepArr.optJSONObject(i) ?: continue
                val sm = s.optJSONObject("message")
                steps.add(
                    WfStep(
                        id = s.optString("id"),
                        title = sm?.optString("title") ?: "",
                        text = sm?.optString("text") ?: "",
                        buttons = parseButtons(s.optJSONArray("buttons"))
                    )
                )
            }
        }

        val triggerParams = mutableMapOf<String, String>()
        if (trigger != null) {
            val tKeys = trigger.keys()
            while (tKeys.hasNext()) {
                val k = tKeys.next()
                if (k != "type") {
                    triggerParams[k] = trigger.opt(k)?.toString() ?: ""
                }
            }
        }

        val actionArgs = mutableMapOf<String, String>()
        if (action != null) {
            val aKeys = action.keys()
            while (aKeys.hasNext()) {
                val k = aKeys.next()
                if (k != "type") {
                    actionArgs[k] = action.opt(k)?.toString() ?: ""
                }
            }
        }

        return Workflow(
            id = obj.optString("id"),
            name = obj.optString("name", obj.optString("id")),
            enabled = obj.optBoolean("enabled", true),
            triggerType = trigger?.optString("type", "unknown") ?: "unknown",
            triggerParams = triggerParams,
            botType = obj.optString("bot_type", "notification"),
            title = message?.optString("title") ?: "",
            text = message?.optString("text") ?: "",
            primaryAction = action?.optString("type")?.takeIf { it.isNotEmpty() },
            primaryActionArgs = actionArgs,
            buttons = parseButtons(obj.optJSONArray("buttons")),
            steps = steps,
            settings = parseSettings(obj.optJSONObject("settings"))
        )
    }

    private fun parseButtons(arr: JSONArray?): List<WfButton> {
        if (arr == null) return emptyList()
        val result = mutableListOf<WfButton>()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val args = mutableMapOf<String, String>()
            val argsObj = b.optJSONObject("args")
            if (argsObj != null) {
                val keys = argsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    args[k] = argsObj.optString(k)
                }
            }
            result.add(
                WfButton(
                    label = b.optString("label"),
                    action = b.optString("action", "skip"),
                    args = args,
                    next = b.optString("next").takeIf { it.isNotEmpty() }
                )
            )
        }
        return result
    }

    private fun parseSettings(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val result = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            result[k] = obj.opt(k)?.toString() ?: ""
        }
        return result
    }
}
