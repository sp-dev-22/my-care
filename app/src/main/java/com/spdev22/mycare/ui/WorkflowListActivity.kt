package com.spdev22.mycare.ui

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.spdev22.mycare.engine.Workflow
import com.spdev22.mycare.engine.WorkflowEngine

/**
 * Ready-made workflow pack ki jhalak (D9) — Phase 1 me sirf dekhna hai.
 * On/off aur settings badalna agle phase me aayega.
 */
class WorkflowListActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        root.addView(TextView(this).apply {
            text = "Workflow Pack (ready-made)"
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = "Phase 1: pack sirf parse hota hai — triggers Phase 2 me fire honge."
            setPadding(0, 8, 0, 16)
        })

        val workflows = WorkflowEngine.loadPack(this)
        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val labels = workflows.map { describe(it) }
        listView.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, labels
        )
        listView.setOnItemClickListener { _, _, position, _ ->
            val w = workflows[position]
            val msg = w.text.ifEmpty { w.title }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        root.addView(listView)

        setContentView(root)
    }

    private fun describe(w: Workflow): String {
        val state = if (w.enabled) "ON" else "OFF"
        val steps = if (w.steps.isEmpty()) "" else " • ${w.steps.size} extra steps"
        return "${w.name} — $state\n" +
            "trigger: ${w.triggerType} • bot: ${w.effectiveBotType}$steps"
    }
}
