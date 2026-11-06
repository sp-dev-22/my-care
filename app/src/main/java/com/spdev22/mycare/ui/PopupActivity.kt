package com.spdev22.mycare.ui

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.spdev22.mycare.data.GroupStore
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.data.WorkflowOverrides
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.NotifyFactory

/**
 * PopupActivity — D7 ka doosra bot type: Popup.
 *
 * Notification me zyada se zyada 3 buttons aate hain; jahan zyada
 * options chahiye (jaise group chunna, ya 4-button wala workflow),
 * wahan yeh dialog-style popup khulta hai — app ki apni window,
 * carrier USSD popup nahi (woh sirf telecom company bhej sakti hai).
 *
 * Mode:
 *  - "workflow": pack ke workflow ke saare buttons yahan bante hain.
 *  - "assign_group": naye install hue app ko group me rakhne ke
 *    liye group-buttons bante hain.
 */
class PopupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: "workflow"

        // 0.2.5: popup ab safed rounded card jaisa dikhta hai
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.cardBackground(this@PopupActivity)
            val p = UiKit.dp(this@PopupActivity, 18)
            setPadding(p, p, p, p)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        when (mode) {
            "assign_group" -> buildAssignGroup(root)
            else -> buildWorkflow(root)
        }
    }

    private fun buildWorkflow(root: LinearLayout) {
        val wfId = intent.getStringExtra(EXTRA_WF) ?: ""
        val packWf = WorkflowEngine.loadPack(this).firstOrNull { it.id == wfId }
        if (packWf == null) {
            finish()
            return
        }
        // 0.2.3: popup me bhi Editor-lite ke edited title/text dikhne chahiye
        val wf = WorkflowOverrides.effective(this, packWf)
        root.addView(UiKit.title(this, wf.title))
        root.addView(UiKit.subtitle(this, wf.text))
        val notifId = NotifyFactory.notifIdFor(wf.id)
        for ((index, btn) in wf.buttons.withIndex()) {
            val b = UiKit.primaryButton(this, btn.label)
            b.setOnClickListener {
                try {
                    startActivity(
                        NotifyFactory.buttonIntent(this, wf.id, notifId, btn, index)
                    )
                } catch (e: Exception) {
                    toast("The action could not be done, Boss")
                }
                finish()
            }
            root.addView(b)
        }
        addCloseButton(root)
    }

    private fun buildAssignGroup(root: LinearLayout) {
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: ""
        val label = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        root.addView(UiKit.title(this, "New app installed"))
        root.addView(
            UiKit.subtitle(
                this,
                "Which group should '$label' go into, Boss? " +
                    "Grouping it keeps its account and suggestions right."
            )
        )

        val groups = GroupStore.loadGroups(this)
        if (groups.isEmpty()) {
            root.addView(UiKit.body(this, "No group exists yet. Open My Care and " +
                "create a group first — then this app can be added to it."))
            root.addView(UiKit.gap(this, 6))
        }
        for (g in groups) {
            val b = UiKit.primaryButton(this, g.name)
            b.setOnClickListener {
                val added = GroupStore.addPackageToGroup(this, g.name, pkg)
                toast(
                    if (added) "'$label' was added to the '${g.name}' group"
                    else "'$label' is already in this group"
                )
                LogStore.log(this, "New app $pkg -> group '${g.name}'")
                finish()
            }
            root.addView(b)
        }
        addCloseButton(root, "Skip — will do it later")
    }

    private fun addCloseButton(root: LinearLayout, text: String = "Close") {
        val b = UiKit.secondaryButton(this, text)
        b.setOnClickListener { finish() }
        root.addView(b)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_MODE = "popup_mode"
        const val EXTRA_WF = "popup_wf"
        const val EXTRA_PKG = "popup_pkg"
        const val EXTRA_LABEL = "popup_label"
    }
}
