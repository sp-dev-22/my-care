package com.spdev22.mycare.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.data.WorkflowPrefs
import com.spdev22.mycare.engine.Workflow
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.DailyScheduler

/**
 * Workflows screen (0.2.2) — ready-made pack ke har workflow ka
 * apna On/Off switch (D9: user sirf on/off aur settings badlega).
 *
 * OFF ka matlab pakka OFF hai:
 *  - trigger engine use kabhi fire nahi karega, aur
 *  - roz wale 'time' workflows ka laga hua alarm bhi cancel ho jayega.
 * ON karte hi time wale workflows ke alarms dobara set ho jaate hain.
 *
 * Default sthiti pack ki apni `enabled` value hai; user ke toggle
 * karte hi override WorkflowPrefs me save ho jata hai.
 */
class WorkflowListActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Workflows — On/Off"
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = "Har workflow ko yahan se chalu/band karein, Boss. " +
                "Kisi workflow par TAP karke uska message, samay aur " +
                "limits bhi badal sakte hain (Editor-lite). " +
                "Jo OFF hai woh kabhi fire nahi hoga — roz wale time " +
                "workflows ka alarm bhi band ho jayega."
            setPadding(0, 8, 0, 16)
        })

        val workflows = WorkflowEngine.loadPack(this)
        if (workflows.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "Pack load nahi hua — koi workflow nahi mila."
                textSize = 16f
            })
            return
        }
        for (wf in workflows) {
            root.addView(workflowRow(wf))
        }

        root.addView(TextView(this).apply {
            text = "\nNote: Switch ki sthiti aapki apni hai — pack update " +
                "hone par bhi aapke On/Off waise hi rahenge."
            setPadding(0, 20, 0, 0)
        })
    }

    private fun workflowRow(wf: Workflow): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 10, 0, 10)
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = wf.name
            textSize = 16f
        })
        val steps = if (wf.steps.isEmpty()) "" else " • ${wf.steps.size} extra steps"
        texts.addView(TextView(this).apply {
            text = "trigger: ${wf.triggerType} • bot: ${wf.effectiveBotType}$steps • tap = edit ✏️"
            textSize = 13f
        })
        row.addView(texts)

        val toggle = Switch(this).apply {
            // Listener lagane se PEHLE sthiti set karo, taaki khulte hi
            // purani value par bekaar ka event na fire ho jaye
            setOnCheckedChangeListener(null)
            isChecked = WorkflowPrefs.isEnabled(this@WorkflowListActivity, wf)
            setOnCheckedChangeListener { _, checked ->
                WorkflowPrefs.setEnabled(this@WorkflowListActivity, wf.id, checked)
                if (wf.triggerType == "time") {
                    // Time wale workflow ka alarm schedule/cancel turant taaza karo
                    DailyScheduler.scheduleAll(this@WorkflowListActivity)
                }
                LogStore.log(
                    this@WorkflowListActivity,
                    "Workflow [${wf.id}] user ne ${if (checked) "ON" else "OFF"} kiya"
                )
                Toast.makeText(
                    this@WorkflowListActivity,
                    "${wf.name} — ${if (checked) "ON" else "OFF"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        row.addView(toggle)

        // 0.2.3: puri row (switch ke alaava) tap karne par Editor-lite
        // khulta hai. Switch apna click khud sambhalta hai — toggle
        // aur edit aapas me takraate nahi.
        row.setOnClickListener {
            startActivity(
                Intent(this, WorkflowEditActivity::class.java)
                    .putExtra(WorkflowEditActivity.EXTRA_WF, wf.id)
            )
        }
        return row
    }
}
