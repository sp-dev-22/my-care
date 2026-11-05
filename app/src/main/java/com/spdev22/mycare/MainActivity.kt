package com.spdev22.mycare

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.spdev22.mycare.data.AppGroup
import com.spdev22.mycare.data.GroupStore
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.service.CareService
import com.spdev22.mycare.settings.SettingsActivity
import com.spdev22.mycare.setup.SetupWizardActivity
import com.spdev22.mycare.ui.WorkflowListActivity

/**
 * Main screen:
 * App Groups ki list + naya group banana (D1),
 * Setup Wizard, Workflow Pack aur Settings ke raaste,
 * aur Care Service (live engine) chalu karna.
 */
class MainActivity : Activity() {

    private lateinit var groupListView: ListView
    private lateinit var statusView: TextView
    private var groups: MutableList<AppGroup> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "Android & My Care"
            textSize = 22f
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Jo aapke phone ka aur aapka — dono ka khayal rakhe.\n" +
                "Phase 2: core workflows zinda hain — Welcome Boss, Boredom " +
                "Dost, Rest Nudge, Suggestions aur Aaj Ka Hisaab."
            setPadding(0, 8, 0, 16)
        }
        root.addView(subtitle)

        statusView = TextView(this)
        root.addView(statusView)

        val section = TextView(this).apply {
            text = "Mere App Groups"
            textSize = 17f
            setPadding(0, 20, 0, 8)
        }
        root.addView(section)

        groupListView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(groupListView)

        val addBtn = Button(this).apply { text = "+ Naya Group banao" }
        addBtn.setOnClickListener { showAddGroupDialog() }
        root.addView(addBtn)

        val wizardBtn = Button(this).apply { text = "Setup Wizard — Permissions" }
        wizardBtn.setOnClickListener {
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }
        root.addView(wizardBtn)

        val packBtn = Button(this).apply { text = "Workflow Pack dekho" }
        packBtn.setOnClickListener {
            startActivity(Intent(this, WorkflowListActivity::class.java))
        }
        root.addView(packBtn)

        val settingsBtn = Button(this).apply { text = "Settings — Time aur Limits" }
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        root.addView(settingsBtn)

        val serviceBtn = Button(this).apply { text = "Care Service chalu karo" }
        serviceBtn.setOnClickListener {
            CareService.start(this)
            toast("Care Service chalu ho gaya — triggers live hain")
        }
        root.addView(serviceBtn)

        groupListView.setOnItemClickListener { _, _, position, _ ->
            val g = groups[position]
            toast("'${g.name}' — apps jodna agle step me aayega")
        }
        groupListView.setOnItemLongClickListener { _, _, position, _ ->
            val g = groups[position]
            AlertDialog.Builder(this)
                .setTitle("Group delete karein?")
                .setMessage("'${g.name}' group delete ho jayega.")
                .setPositiveButton("Delete") { _, _ ->
                    GroupStore.removeGroup(this, g.name)
                    refreshGroups()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        setContentView(root)
        refreshGroups()
        refreshStatus()
        // App khulte hi engine zinda kar do (idempotent)
        CareService.start(this)
    }

    override fun onResume() {
        super.onResume()
        refreshGroups()
    }

    private fun refreshStatus() {
        val count = WorkflowEngine.loadPack(this).size
        statusView.text = "Workflow pack: $count workflows load hue (parsing OK)"
    }

    private fun refreshGroups() {
        groups = GroupStore.loadGroups(this)
        val labels = groups.map { g ->
            if (g.packages.isEmpty()) "${g.name} • 0 apps"
            else "${g.name} • ${g.packages.size} apps"
        }
        groupListView.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, labels
        )
        if (groups.isEmpty()) {
            toast("Pehla group banao — jaise Padhai, Manoranjan, Baatcheet")
        }
    }

    private fun showAddGroupDialog() {
        val input = EditText(this).apply {
            hint = "Group ka naam — jaise Padhai"
        }
        AlertDialog.Builder(this)
            .setTitle("Naya App Group")
            .setView(input)
            .setPositiveButton("Banao") { _, _ ->
                val name = input.text.toString()
                if (GroupStore.addGroup(this, name)) {
                    toast("Group ban gaya: $name")
                    refreshGroups()
                } else {
                    toast("Naam khali hai ya group pehle se hai")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
