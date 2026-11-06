package com.spdev22.mycare.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.data.UserWorkflowStore
import com.spdev22.mycare.data.WorkflowPrefs
import com.spdev22.mycare.engine.Workflow
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.DailyScheduler
import com.spdev22.mycare.setup.WorkflowPermissions

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
 *
 * 0.2.5: list me pack ke saath user ke Gemini-se bane workflows bhi
 * hain (loadPack dono milakar deta hai) — unpar "✨ AI banaya" nishan
 * lagta hai. UiKit wala naya look: har workflow apne safed card me.
 */
class WorkflowListActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.root(this)
        UiKit.tabScreen(this, root, UiKit.TAB_WORKFLOWS)

        root.addView(UiKit.title(this, "Workflows — On/Off"))
        root.addView(
            UiKit.subtitle(
                this,
                "Turn every workflow on or off here, Boss. TAP a " +
                    "workflow to also change its message, time and " +
                    "limits (Editor-lite). What is OFF will never fire — " +
                    "a daily time workflow's alarm is cancelled too. " +
                    "Switching something ON checks its permissions and " +
                    "offers the missing ones right away."
            )
        )

        val workflows = WorkflowEngine.loadPack(this)
        if (workflows.isEmpty()) {
            val card = UiKit.card(this)
            card.addView(UiKit.body(this, "The pack did not load — no workflows found.", 16f))
            root.addView(card)
            return
        }
        for (wf in workflows) {
            root.addView(workflowRow(wf))
        }

        root.addView(
            UiKit.small(
                this,
                "Note: your switch positions are yours — they stay " +
                    "as you set them even when the pack updates. " +
                    "✨ Made by AI = your own workflow created with " +
                    "Gemini; you can delete it from its edit screen."
            )
        )
    }

    private fun workflowRow(wf: Workflow): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.cardBackground(this@WorkflowListActivity)
            val p = UiKit.dp(this@WorkflowListActivity, 14)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = UiKit.dp(this@WorkflowListActivity, 10)
            }
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val isAi = UserWorkflowStore.contains(this, wf.id)
        texts.addView(UiKit.body(this, wf.name + if (isAi) "  ✨ Made by AI" else "", 16f).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        val steps = if (wf.steps.isEmpty()) "" else " • ${wf.steps.size} extra steps"
        texts.addView(
            UiKit.small(
                this,
                "trigger: ${wf.triggerType} • bot: ${wf.effectiveBotType}$steps • tap = edit ✏️"
            )
        )
        row.addView(texts)

        val toggle = Switch(this).apply {
            // Listener lagane se PEHLE sthiti set karo, taaki khulte hi
            // purani value par bekaar ka event na fire ho jaye
            setOnCheckedChangeListener(null)
            isChecked = WorkflowPrefs.isEnabled(this@WorkflowListActivity, wf)
            UiKit.tintSwitch(this@WorkflowListActivity, this)
            setOnCheckedChangeListener { _, checked ->
                WorkflowPrefs.setEnabled(this@WorkflowListActivity, wf.id, checked)
                if (wf.triggerType == "time") {
                    // Time wale workflow ka alarm schedule/cancel turant taaza karo
                    DailyScheduler.scheduleAll(this@WorkflowListActivity)
                }
                LogStore.log(
                    this@WorkflowListActivity,
                    "Workflow [${wf.id}] switched ${if (checked) "ON" else "OFF"} by user"
                )
                Toast.makeText(
                    this@WorkflowListActivity,
                    "${wf.name} — ${if (checked) "ON" else "OFF"}",
                    Toast.LENGTH_SHORT
                ).show()
                if (checked) {
                    // 0.2.8: permission-on-enable — if this workflow
                    // still needs permissions, offer them right now
                    WorkflowPermissions.promptIfMissing(this@WorkflowListActivity, wf)
                }
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
