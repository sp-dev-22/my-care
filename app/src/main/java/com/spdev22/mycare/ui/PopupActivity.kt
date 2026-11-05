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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 36, 40, 36)
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
        val wf = WorkflowEngine.loadPack(this).firstOrNull { it.id == wfId }
        if (wf == null) {
            finish()
            return
        }
        root.addView(TextView(this).apply {
            text = wf.title
            textSize = 19f
        })
        root.addView(TextView(this).apply {
            text = wf.text
            setPadding(0, 8, 0, 16)
        })
        val notifId = NotifyFactory.notifIdFor(wf.id)
        for ((index, btn) in wf.buttons.withIndex()) {
            val b = Button(this).apply {
                text = btn.label
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            b.setOnClickListener {
                try {
                    startActivity(
                        NotifyFactory.buttonIntent(this, wf.id, notifId, btn, index)
                    )
                } catch (e: Exception) {
                    toast("Action nahi ho paya, Boss")
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
        root.addView(TextView(this).apply {
            text = "Naya app install hua"
            textSize = 19f
        })
        root.addView(TextView(this).apply {
            text = "'$label' ko kis group me rakhein, Boss? " +
                "Group me rakhne se uske hisaab aur suggestions sahi rahenge."
            setPadding(0, 8, 0, 16)
        })

        val groups = GroupStore.loadGroups(this)
        if (groups.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "Abhi koi group nahi bana hai. Pehle My Care kholkar " +
                    "ek group bana dijiye — phir yeh app wahan jod sakte hain."
                setPadding(0, 0, 0, 12)
            })
        }
        for (g in groups) {
            val b = Button(this).apply {
                text = g.name
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            b.setOnClickListener {
                val added = GroupStore.addPackageToGroup(this, g.name, pkg)
                toast(
                    if (added) "'$label' → '${g.name}' group me jud gaya"
                    else "'$label' pehle se is group me hai"
                )
                LogStore.log(this, "Naya app $pkg → group '${g.name}'")
                finish()
            }
            root.addView(b)
        }
        addCloseButton(root, "Skip — baad me karenge")
    }

    private fun addCloseButton(root: LinearLayout, text: String = "Band karo") {
        val b = Button(this).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
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
