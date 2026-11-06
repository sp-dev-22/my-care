package com.spdev22.mycare.data

import android.content.Context
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.DailyScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BackupManager — 0.2.9: ONE-file backup / restore of everything
 * that makes My Care "yours", in a single JSON document:
 *
 *   - app groups                      (GroupStore)
 *   - all user settings               (CareSettings: "set_*" +
 *                                      chosen target apps "target_*")
 *   - workflow On/Off switches        (WorkflowPrefs: "wf_enabled_*")
 *   - Editor-lite edits               (WorkflowOverrides: "ovr_*")
 *   - your own AI-made workflows      (UserWorkflowStore) — this
 *     includes auto-reply workflows and their switches/edits.
 *
 * Deliberately NOT carried: engine state ("state_*" stamps — they
 * describe THIS phone's recent past, not your setup) and, by
 * default, the Gemini API key. The key is included only when the
 * user explicitly ticks "Include Gemini API key" on the Backup
 * screen, next to a plain warning.
 *
 * Restore is all-or-nothing: the whole file is parsed and
 * validated FIRST (format marker, version, section shapes, typed
 * values, every user workflow parseable). Anything wrong -> an
 * honest error and NOTHING is changed. On success the sections
 * present in the file REPLACE the current ones, the scheduler is
 * reloaded, and a summary with counts is returned.
 *
 * Values are stored type-tagged ({"t":"s|b|i|l|f","v":...}) so an
 * Int setting can never come back as a Long and crash a getter.
 */
object BackupManager {

    const val FORMAT = "my_care_backup"
    const val BACKUP_VERSION = 1

    private const val PREFS = "my_care_prefs"
    private const val KEY_USER_WF_JSON = "user_workflows_json"
    private const val KEY_USER_WF_VERSION = "user_workflows_version"

    data class Summary(
        val groups: Int,
        val settings: Int,
        val toggles: Int,
        val overrides: Int,
        val userWorkflows: Int,
        val geminiKey: Boolean
    ) {
        fun describe(): String {
            val parts = mutableListOf(
                "$groups app group(s)",
                "$settings setting(s)",
                "$toggles workflow switch(es)",
                "$overrides workflow edit(s)",
                "$userWorkflows AI workflow(s)"
            )
            if (geminiKey) parts.add("Gemini API key")
            return parts.joinToString(", ")
        }
    }

    sealed class ImportResult {
        data class Ok(val summary: Summary) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- export ----------------

    fun buildBackup(context: Context, includeKey: Boolean): JSONObject {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", BACKUP_VERSION)
        root.put("app", "My Care")
        root.put("app_version", appVersion(context))
        root.put(
            "exported_at",
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        )

        // App groups
        val groupsArr = JSONArray()
        for (g in GroupStore.loadGroups(context)) {
            val obj = JSONObject()
            obj.put("name", g.name)
            val pkgs = JSONArray()
            for (p in g.packages) pkgs.put(p)
            obj.put("packages", pkgs)
            groupsArr.put(obj)
        }
        root.put("groups", groupsArr)

        // Pref sections, type-tagged
        root.put("settings", collectSection(context, listOf("set_", "target_")))
        root.put("workflow_toggles", collectSection(context, listOf("wf_enabled_")))
        root.put("workflow_overrides", collectSection(context, listOf("ovr_")))

        // User (AI-made) workflows — raw Format-v1 records
        root.put("user_workflows", UserWorkflowStore.loadJsonArray(context))

        // Gemini API key: ONLY on explicit opt-in (see class doc)
        if (includeKey) {
            CareSettings.geminiKey(context)?.let { root.put("gemini_api_key", it) }
        }
        return root
    }

    private fun collectSection(context: Context, prefixes: List<String>): JSONObject {
        val out = JSONObject()
        val all = prefs(context).all
        for ((key, value) in all) {
            if (prefixes.none { key.startsWith(it) }) continue
            if (value == null) continue
            wrapValue(value)?.let { out.put(key, it) }
        }
        return out
    }

    // ---------------- import ----------------

    fun applyBackup(context: Context, root: JSONObject): ImportResult {
        // ---- header ----
        if (root.optString("format") != FORMAT) {
            return ImportResult.Error(
                "This file is not a My Care backup (format marker missing)."
            )
        }
        val version = root.optInt("version", -1)
        if (version < 1 || version > BACKUP_VERSION) {
            return ImportResult.Error(
                "This backup's version ($version) is not supported by this app version."
            )
        }

        // ---- parse phase: NOTHING is written until all is valid ----
        val groups: List<AppGroup>? = if (root.has("groups")) {
            val arr = root.optJSONArray("groups")
                ?: return ImportResult.Error("The backup's 'groups' section is damaged.")
            val parsed = mutableListOf<AppGroup>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                    ?: return ImportResult.Error("The backup's 'groups' section is damaged.")
                val name = obj.optString("name").trim()
                if (name.isEmpty()) {
                    return ImportResult.Error("The backup contains a group with no name.")
                }
                val pkgs = mutableListOf<String>()
                val pkgArr = obj.optJSONArray("packages")
                if (pkgArr != null) {
                    for (j in 0 until pkgArr.length()) {
                        val p = pkgArr.optString(j)
                        if (p.isNotEmpty()) pkgs.add(p)
                    }
                }
                parsed.add(AppGroup(name, pkgs))
            }
            parsed
        } else {
            null
        }

        val settings = parseSection(root, "settings", listOf("set_", "target_"))
            ?: return ImportResult.Error("The backup's 'settings' section is damaged.")
        val toggles = parseSection(root, "workflow_toggles", listOf("wf_enabled_"))
            ?: return ImportResult.Error("The backup's 'workflow_toggles' section is damaged.")
        val overrides = parseSection(root, "workflow_overrides", listOf("ovr_"))
            ?: return ImportResult.Error("The backup's 'workflow_overrides' section is damaged.")

        // null = section absent (leave current data alone)
        val settingsPresent = root.has("settings")
        val togglesPresent = root.has("workflow_toggles")
        val overridesPresent = root.has("workflow_overrides")

        val userWorkflows: JSONArray? = if (root.has("user_workflows")) {
            val arr = root.optJSONArray("user_workflows")
                ?: return ImportResult.Error("The backup's 'user_workflows' section is damaged.")
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                    ?: return ImportResult.Error("The backup's 'user_workflows' section is damaged.")
                val ok = try {
                    WorkflowEngine.parseWorkflowObject(obj).id.isNotEmpty()
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    return ImportResult.Error(
                        "One of the backup's AI workflows is damaged — nothing was restored."
                    )
                }
            }
            arr
        } else {
            null
        }

        val geminiKey: String? = if (root.has("gemini_api_key")) {
            val k = root.optString("gemini_api_key").trim()
            k.ifEmpty { null }
        } else {
            null
        }

        // ---- write phase: replace each present section wholesale ----
        val editor = prefs(context).edit()
        if (settingsPresent) {
            replaceSection(editor, context, listOf("set_", "target_"), settings)
        }
        if (togglesPresent) {
            replaceSection(editor, context, listOf("wf_enabled_"), toggles)
        }
        if (overridesPresent) {
            replaceSection(editor, context, listOf("ovr_"), overrides)
        }
        if (userWorkflows != null) {
            editor.putString(KEY_USER_WF_JSON, userWorkflows.toString())
            // Bump the store version so CareService + the
            // notification listener reload their workflow lists.
            editor.putLong(
                KEY_USER_WF_VERSION,
                prefs(context).getLong(KEY_USER_WF_VERSION, 0L) + 1
            )
        }
        editor.apply()

        if (groups != null) GroupStore.saveGroups(context, groups)
        if (geminiKey != null) CareSettings.setGeminiKey(context, geminiKey)

        // Reload daily alarms with the restored switches/times.
        DailyScheduler.scheduleAll(context)

        return ImportResult.Ok(
            Summary(
                groups = groups?.size ?: 0,
                settings = if (settingsPresent) settings.size else 0,
                toggles = if (togglesPresent) toggles.size else 0,
                overrides = if (overridesPresent) overrides.size else 0,
                userWorkflows = userWorkflows?.length() ?: 0,
                geminiKey = geminiKey != null
            )
        )
    }

    /**
     * Parse one type-tagged section. Returns the key->value map
     * (empty when the section is absent), or null when it is present
     * but damaged. Keys outside the section's prefixes are ignored.
     */
    private fun parseSection(
        root: JSONObject,
        name: String,
        prefixes: List<String>
    ): Map<String, Any>? {
        if (!root.has(name)) return emptyMap()
        val obj = root.optJSONObject(name) ?: return null
        val out = LinkedHashMap<String, Any>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (prefixes.none { key.startsWith(it) }) continue
            val wrapped = obj.optJSONObject(key) ?: return null
            val value = unwrapValue(wrapped) ?: return null
            out[key] = value
        }
        return out
    }

    /** Remove every current key of the section, then write the backup's. */
    private fun replaceSection(
        editor: android.content.SharedPreferences.Editor,
        context: Context,
        prefixes: List<String>,
        values: Map<String, Any>
    ) {
        for (key in prefs(context).all.keys) {
            if (prefixes.any { key.startsWith(it) }) editor.remove(key)
        }
        for ((key, value) in values) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
            }
        }
    }

    // ---------------- typed values ----------------

    private fun wrapValue(value: Any): JSONObject? {
        val obj = JSONObject()
        return when (value) {
            is String -> obj.put("t", "s").put("v", value)
            is Boolean -> obj.put("t", "b").put("v", value)
            is Int -> obj.put("t", "i").put("v", value)
            is Long -> obj.put("t", "l").put("v", value)
            is Float -> obj.put("t", "f").put("v", value.toDouble())
            else -> null // sets and other exotics are not carried
        }
    }

    private fun unwrapValue(obj: JSONObject): Any? = when (obj.optString("t")) {
        "s" -> obj.optString("v")
        "b" -> obj.optBoolean("v")
        "i" -> obj.optInt("v")
        "l" -> obj.optLong("v")
        "f" -> obj.optDouble("v").toFloat()
        else -> null
    }

    private fun appVersion(context: Context): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
            ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}
