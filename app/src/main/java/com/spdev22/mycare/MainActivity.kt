package com.spdev22.mycare

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
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
import com.spdev22.mycare.ui.AiWorkflowActivity
import com.spdev22.mycare.ui.AppPickerActivity
import com.spdev22.mycare.ui.LogsActivity
import com.spdev22.mycare.ui.UiKit
import com.spdev22.mycare.ui.WorkflowListActivity

/**
 * Main screen:
 * App Groups ki list + naya group banana (D1),
 * Setup Wizard, Workflow Pack, Settings, Logs aur — 0.2.5 se —
 * "AI se Workflow banwao" (Gemini) ke raaste,
 * aur Care Service (live engine) chalu karna.
 *
 * 0.2.5: UiKit se naya minimal, beautiful look — shaant palette,
 * safed cards, teal buttons.
 */
class MainActivity : Activity() {

    private lateinit var groupListView: ListView
    private lateinit var statusView: TextView
    private var groups: MutableList<AppGroup> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.root(this)
        root.addView(UiKit.title(this, "Android & My Care"))
        root.addView(
            UiKit.subtitle(
                this,
                "Jo aapke phone ka aur aapka — dono ka khayal rakhe.\n" +
                    "v0.2.5: saare 16 workflows zinda hain, aur ab Gemini AI se " +
                    "apne workflows bhi ban sakte hain. Group par tap karke " +
                    "uske apps chunein."
            )
        )

        statusView = UiKit.small(this, "")
        root.addView(statusView)
        root.addView(UiKit.gap(this, 6))

        root.addView(UiKit.section(this, "Mere App Groups"))

        // Groups ki list ek safed card ke andar
        val listCard = UiKit.card(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        groupListView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            divider = null
        }
        listCard.addView(groupListView)
        root.addView(listCard)

        val addBtn = UiKit.primaryButton(this, "+ Naya Group banao")
        addBtn.setOnClickListener { showAddGroupDialog() }
        root.addView(addBtn)

        val aiBtn = UiKit.accentButton(this, "✨ AI se Workflow banwao (Gemini)")
        aiBtn.setOnClickListener {
            startActivity(Intent(this, AiWorkflowActivity::class.java))
        }
        root.addView(aiBtn)

        val packBtn = UiKit.secondaryButton(this, "Workflows — On/Off")
        packBtn.setOnClickListener {
            startActivity(Intent(this, WorkflowListActivity::class.java))
        }
        root.addView(packBtn)

        val settingsBtn = UiKit.secondaryButton(this, "Settings — Time aur Limits")
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        root.addView(settingsBtn)

        val wizardBtn = UiKit.secondaryButton(this, "Setup Wizard — Permissions")
        wizardBtn.setOnClickListener {
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }
        root.addView(wizardBtn)

        val logsBtn = UiKit.secondaryButton(this, "Logs / Console")
        logsBtn.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
        root.addView(logsBtn)

        val serviceBtn = UiKit.secondaryButton(this, "Care Service chalu karo")
        serviceBtn.setOnClickListener {
            CareService.start(this)
            toast("Care Service chalu ho gaya — triggers live hain")
        }
        root.addView(serviceBtn)

        groupListView.setOnItemClickListener { _, _, position, _ ->
            val g = groups[position]
            startActivity(
                Intent(this, AppPickerActivity::class.java)
                    .putExtra(AppPickerActivity.EXTRA_MODE, "group")
                    .putExtra(AppPickerActivity.EXTRA_GROUP, g.name)
            )
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
        refreshStatus()
    }

    private fun refreshStatus() {
        val count = WorkflowEngine.loadPack(this).size
        statusView.text = "Workflows: kul $count load hue (pack + aapke banaye hue)"
    }

    private fun refreshGroups() {
        groups = GroupStore.loadGroups(this)
        val labels = groups.map { g ->
            if (g.packages.isEmpty()) "${g.name}  •  0 apps"
            else "${g.name}  •  ${g.packages.size} apps"
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
