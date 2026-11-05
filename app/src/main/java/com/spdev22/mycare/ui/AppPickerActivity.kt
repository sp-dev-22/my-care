package com.spdev22.mycare.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.data.GroupStore
import com.spdev22.mycare.data.LogStore

/**
 * AppPickerActivity — phone ke launchable apps ki list se chunaav.
 *
 * Do mode:
 *  1. mode="slot" — kisi action ke liye EK target app chuno
 *     (Music / Video / Chat buttons kaun sa app kholenge).
 *     Tap = save + band.
 *  2. mode="group" — ek App Group ke apps toggle karo
 *     (jo tick hai woh group me hai; tap par turant save).
 */
class AppPickerActivity : Activity() {

    data class AppEntry(val label: String, val pkg: String)

    private var mode: String = "slot"
    private var slot: String = ""
    private var groupName: String = ""
    private var entries: List<AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: "slot"
        slot = intent.getStringExtra(EXTRA_SLOT) ?: ""
        groupName = intent.getStringExtra(EXTRA_GROUP) ?: ""

        entries = loadLaunchableApps()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = when (mode) {
                "group" -> "'$groupName' group ke apps chunein"
                else -> slotTitle(slot)
            }
            textSize = 19f
        })
        root.addView(TextView(this).apply {
            text = when (mode) {
                "group" -> "Jo apps is group me hone chahiye, unpar tick rakhein. " +
                    "Tap karte hi save ho jata hai."
                else -> "Is kaam ke liye kaun sa app khule, woh chunein. " +
                    "'Auto' par pack ke defaults chalenge."
            }
            setPadding(0, 6, 0, 12)
        })

        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(listView)
        setContentView(root)

        if (mode == "group") {
            buildGroupList(listView)
        } else {
            buildSlotList(listView)
        }
    }

    private fun buildSlotList(listView: ListView) {
        val labels = mutableListOf("Auto (pack ke defaults)")
        labels.addAll(entries.map { "${it.label}  (${it.pkg})" })
        listView.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, labels
        )
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                CareSettings.setTargetApp(this, slot, null)
                toast("Auto par set ho gaya")
                LogStore.log(this, "Target app [$slot] = Auto")
            } else {
                val e = entries[position - 1]
                CareSettings.setTargetApp(this, slot, e.pkg)
                toast("${e.label} set ho gaya")
                LogStore.log(this, "Target app [$slot] = ${e.pkg}")
            }
            finish()
        }
    }

    private fun buildGroupList(listView: ListView) {
        listView.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
        val labels = entries.map { "${it.label}  (${it.pkg})" }
        listView.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_multiple_choice, labels
        )
        val group = GroupStore.loadGroups(this)
            .firstOrNull { it.name.equals(groupName, ignoreCase = true) }
        val inGroup = group?.packages?.toSet() ?: emptySet()
        entries.forEachIndexed { index, e ->
            listView.setItemChecked(index, inGroup.contains(e.pkg))
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val e = entries[position]
            if (listView.isItemChecked(position)) {
                GroupStore.addPackageToGroup(this, groupName, e.pkg)
            } else {
                GroupStore.removePackageFromGroup(this, groupName, e.pkg)
            }
        }
    }

    private fun loadLaunchableApps(): List<AppEntry> {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(main, 0)
        } catch (e: Exception) {
            emptyList()
        }
        val seen = HashSet<String>()
        val out = mutableListOf<AppEntry>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == packageName) continue
            if (!seen.add(pkg)) continue
            val label = try {
                ri.loadLabel(pm)?.toString() ?: pkg
            } catch (e: Exception) {
                pkg
            }
            out.add(AppEntry(label, pkg))
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    private fun slotTitle(slot: String): String = when (slot) {
        "music" -> "Music button ke liye app chunein"
        "video" -> "Videos / YouTube button ke liye app chunein"
        "chat" -> "Muse AI / WhatsApp button ke liye app chunein"
        else -> "Target app chunein"
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_MODE = "picker_mode"
        const val EXTRA_SLOT = "picker_slot"
        const val EXTRA_GROUP = "picker_group"
    }
}
