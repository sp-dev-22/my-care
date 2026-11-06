package com.spdev22.mycare

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.spdev22.mycare.data.GroupStore
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.service.CareService
import com.spdev22.mycare.ui.AppPickerActivity
import com.spdev22.mycare.ui.UiKit

/**
 * Home tab (0.2.8):
 *  - Care Service status + start button
 *  - App Groups (D1): tappable rows to pick each group's apps,
 *    long-press to delete, "+ New Group" to create
 *
 * 0.2.8 group-list fix (the blank/broken groups box): the old code
 * put a ListView inside a card whose height was 0 + weight 1 in a
 * NON-scrolling root, next to ~7 fixed-height buttons. On a small
 * phone the fixed children ate the whole screen and the weighted
 * card (and its ListView) was squeezed to zero height — so the box
 * looked broken BOTH when the list was empty and when groups
 * existed. Now the whole screen scrolls (tab screen) and the
 * groups live in a plain WRAP_CONTENT card whose rows are built
 * directly as views — no ListView, no adapter, nothing to squeeze.
 *
 * Navigation to Workflows / AI / Settings / System is the bottom
 * tab bar (UiKit) — nothing that was reachable before is lost:
 * the tab bar and the System tab carry every old entry.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var serviceView: TextView
    private lateinit var groupsCard: LinearLayout
    private lateinit var groupsHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.root(this)
        root.addView(UiKit.title(this, "Android & My Care"))
        root.addView(
            UiKit.subtitle(
                this,
                "The caretaker for you and your phone.\n" +
                    "v0.2.8: tabs, full English, a Running Services page, " +
                    "one default workflow for every permission, and the " +
                    "Intruder Alert now captures a photo. Tap a group to " +
                    "choose its apps."
            )
        )

        statusView = UiKit.small(this, "")
        root.addView(statusView)
        root.addView(UiKit.gap(this, 6))

        // ---- Care Service status / start ----
        val serviceCard = UiKit.card(this)
        serviceView = UiKit.body(this, "", 15f)
        serviceCard.addView(serviceView)
        val serviceBtn = UiKit.primaryButton(this, "Start Care Service")
        serviceBtn.setOnClickListener {
            CareService.start(this)
            toast("Care Service start requested — triggers are live")
            refreshService()
        }
        serviceCard.addView(serviceBtn)
        root.addView(serviceCard)

        // ---- App Groups ----
        root.addView(UiKit.section(this, "My App Groups"))
        groupsCard = UiKit.card(this)
        root.addView(groupsCard)
        groupsHint = UiKit.small(
            this,
            "No groups yet — create your first group, like Padhai " +
                "(Study) or Manoranjan (Entertainment). Tap a group " +
                "to add apps to it."
        )
        root.addView(groupsHint)

        val addBtn = UiKit.primaryButton(this, "+ Create New Group")
        addBtn.setOnClickListener { showAddGroupDialog() }
        root.addView(addBtn)

        UiKit.tabScreen(this, root, UiKit.TAB_HOME)
        refreshGroups()
        refreshStatus()
        refreshService()
        // Start the engine as soon as the app opens (idempotent)
        CareService.start(this)
    }

    override fun onResume() {
        super.onResume()
        refreshGroups()
        refreshStatus()
        refreshService()
        // The service starts asynchronously — re-check its status
        // a moment later so the first paint doesn't claim "stopped"
        statusView.postDelayed({ refreshService() }, 900)
    }

    private fun refreshStatus() {
        val count = WorkflowEngine.loadPack(this).size
        statusView.text = "Workflows: $count loaded in total (pack + your own)"
    }

    private fun refreshService() {
        serviceView.text = if (CareService.isRunning) {
            "✅ Care Service is RUNNING — triggers are live."
        } else {
            "❌ Care Service is not running yet — tap Start (it also " +
                "starts by itself when the app opens)."
        }
    }

    /**
     * Rows are rebuilt from scratch on every refresh — plain views
     * in a wrap-content card, correct with 0 groups (card hidden,
     * hint shown) and with many groups (card grows, page scrolls).
     */
    private fun refreshGroups() {
        val groups = GroupStore.loadGroups(this)
        groupsCard.removeAllViews()
        if (groups.isEmpty()) {
            groupsCard.visibility = View.GONE
            groupsHint.visibility = View.VISIBLE
            return
        }
        groupsCard.visibility = View.VISIBLE
        groupsHint.visibility = View.GONE
        for ((index, g) in groups.withIndex()) {
            if (index > 0) {
                groupsCard.addView(View(this).apply {
                    setBackgroundColor(UiKit.BORDER)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this@MainActivity, 1)
                    ).apply {
                        topMargin = UiKit.dp(this@MainActivity, 8)
                        bottomMargin = UiKit.dp(this@MainActivity, 8)
                    }
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, UiKit.dp(this@MainActivity, 4), 0, UiKit.dp(this@MainActivity, 4))
                isClickable = true
            }
            row.addView(UiKit.body(this, g.name, 16f).apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            row.addView(
                UiKit.small(
                    this,
                    if (g.packages.isEmpty()) "0 apps — tap to add apps"
                    else "${g.packages.size} apps — tap to manage"
                )
            )
            row.setOnClickListener {
                startActivity(
                    Intent(this, AppPickerActivity::class.java)
                        .putExtra(AppPickerActivity.EXTRA_MODE, "group")
                        .putExtra(AppPickerActivity.EXTRA_GROUP, g.name)
                )
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete this group?")
                    .setMessage("The '${g.name}' group will be deleted.")
                    .setPositiveButton("Delete") { _, _ ->
                        GroupStore.removeGroup(this, g.name)
                        refreshGroups()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            groupsCard.addView(row)
        }
    }

    private fun showAddGroupDialog() {
        val input = EditText(this).apply {
            hint = "Group name — e.g. Padhai"
        }
        AlertDialog.Builder(this)
            .setTitle("New App Group")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString()
                if (GroupStore.addGroup(this, name)) {
                    toast("Group created: $name")
                    refreshGroups()
                } else {
                    toast("Name is empty, or a group with this name already exists")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
