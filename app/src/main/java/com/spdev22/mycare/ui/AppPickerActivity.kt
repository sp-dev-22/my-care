package com.spdev22.mycare.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.CheckBox
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

    // 0.2.2: system apps chhupana default hai; toggle ON ho to woh bhi dikhte hain
    private var showSystem = false
    private var pickerList: ListView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: "slot"
        slot = intent.getStringExtra(EXTRA_SLOT) ?: ""
        groupName = intent.getStringExtra(EXTRA_GROUP) ?: ""

        entries = loadAllApps()

        val root = UiKit.root(this)
        root.addView(
            UiKit.title(
                this, when (mode) {
                    "group" -> "'$groupName' group ke apps chunein"
                    else -> slotTitle(slot)
                }
            )
        )
        root.addView(
            UiKit.subtitle(
                this, when (mode) {
                    "group" -> "Jo apps is group me hone chahiye, unpar tick rakhein. " +
                        "Tap karte hi save ho jata hai."
                    else -> "Is kaam ke liye kaun sa app khule, woh chunein. " +
                        "'Auto' par pack ke defaults chalenge."
                }
            )
        )
        root.addView(CheckBox(this).apply {
            text = "System apps bhi dikhao"
            textSize = 15f
            setTextColor(UiKit.INK)
            UiKit.tintCheckBox(this@AppPickerActivity, this)
            setOnCheckedChangeListener { _, checked ->
                showSystem = checked
                entries = loadAllApps()
                refreshList()
            }
        })
        root.addView(UiKit.gap(this, 6))

        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        pickerList = listView
        val listCard = UiKit.card(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        listCard.addView(listView)
        root.addView(listCard)
        setContentView(root)

        refreshList()
    }

    private fun refreshList() {
        val listView = pickerList ?: return
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

    /**
     * 0.2.2 BUG FIX — pehle sirf launcher-intent wale apps dikhte the
     * (queryIntentActivities), isliye Groups me bahut saare apps gayab the.
     * Android 11+ package-visibility ke kaaran woh raasta aadhi list deta hai.
     *
     * Ab: manifest me QUERY_ALL_PACKAGES ke saath getInstalledApplications
     * se POORI list aati hai. User apps hamesha dikhte hain; asal system
     * apps (jo update bhi na hue hon) sirf "System apps bhi dikhao" ON ho
     * aur woh khul sakte hon (launch intent ho) tab dikhte hain.
     */
    private fun loadAllApps(): List<AppEntry> {
        val pm = packageManager
        val infos = try {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        } catch (e: Exception) {
            emptyList()
        }
        val out = mutableListOf<AppEntry>()
        for (info in infos) {
            val pkg = info.packageName
            if (pkg.isNullOrEmpty() || pkg == packageName) continue
            val isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            if (isSystem) {
                if (!showSystem) continue
                val launchable = try {
                    pm.getLaunchIntentForPackage(pkg) != null
                } catch (e: Exception) {
                    false
                }
                if (!launchable) continue
            }
            val label = try {
                pm.getApplicationLabel(info).toString()
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
        "clock" -> "Alarm ke liye Clock app chunein"
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
