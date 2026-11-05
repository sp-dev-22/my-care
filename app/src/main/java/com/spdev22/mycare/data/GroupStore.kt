package com.spdev22.mycare.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Ek app group (D1): naam + usme shamil apps ke package names. */
data class AppGroup(
    val name: String,
    val packages: MutableList<String> = mutableListOf()
)

/**
 * App Groups ka storage — SharedPreferences me JSON ke roop me.
 * Saare usage triggers inhi groups par bandhe honge, fixed app list par nahi.
 */
object GroupStore {

    private const val PREFS = "my_care_prefs"
    private const val KEY_GROUPS = "app_groups_json"

    fun loadGroups(context: Context): MutableList<AppGroup> {
        val raw = prefs(context).getString(KEY_GROUPS, null) ?: return mutableListOf()
        val result = mutableListOf<AppGroup>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty()) continue
                val pkgs = mutableListOf<String>()
                val pkgArr = obj.optJSONArray("packages")
                if (pkgArr != null) {
                    for (j in 0 until pkgArr.length()) {
                        val p = pkgArr.optString(j)
                        if (p.isNotEmpty()) pkgs.add(p)
                    }
                }
                result.add(AppGroup(name, pkgs))
            }
        } catch (e: Exception) {
            // Kharab data par crash nahi — khali list se shuru karo
            return mutableListOf()
        }
        return result
    }

    fun saveGroups(context: Context, groups: List<AppGroup>) {
        val arr = JSONArray()
        for (g in groups) {
            val obj = JSONObject()
            obj.put("name", g.name)
            val pkgArr = JSONArray()
            for (p in g.packages) pkgArr.put(p)
            obj.put("packages", pkgArr)
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_GROUPS, arr.toString()).apply()
    }

    /** @return false agar isi naam ka group pehle se hai */
    fun addGroup(context: Context, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val groups = loadGroups(context)
        if (groups.any { it.name.equals(clean, ignoreCase = true) }) return false
        groups.add(AppGroup(clean))
        saveGroups(context, groups)
        return true
    }

    fun removeGroup(context: Context, name: String) {
        val groups = loadGroups(context)
        groups.removeAll { it.name.equals(name, ignoreCase = true) }
        saveGroups(context, groups)
    }

    /** Ek app ko group me jodo. @return false agar pehle se tha / group nahi mila. */
    fun addPackageToGroup(context: Context, groupName: String, pkg: String): Boolean {
        val groups = loadGroups(context)
        val g = groups.firstOrNull { it.name.equals(groupName, ignoreCase = true) }
            ?: return false
        if (g.packages.contains(pkg)) return false
        g.packages.add(pkg)
        saveGroups(context, groups)
        return true
    }

    fun removePackageFromGroup(context: Context, groupName: String, pkg: String) {
        val groups = loadGroups(context)
        val g = groups.firstOrNull { it.name.equals(groupName, ignoreCase = true) }
            ?: return
        if (g.packages.remove(pkg)) saveGroups(context, groups)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
