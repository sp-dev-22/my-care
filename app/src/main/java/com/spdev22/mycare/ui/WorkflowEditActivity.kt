package com.spdev22.mycare.ui

import android.app.Activity
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.data.WorkflowOverrides
import com.spdev22.mycare.engine.Workflow
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.DailyScheduler

/**
 * Workflow Edit (Editor-lite, 0.2.3) — pack ke kisi workflow ka
 * message (title/text), samay ('time' trigger ka HH:MM) aur limits
 * (numeric trigger params + settings) app ke andar badlo.
 *
 * Edits pack ko NAHI badalte — WorkflowOverrides me per-workflow
 * layer ki tarah save hote hain, aur har consumer effective()
 * workflow hi dekhta hai. Trigger type, buttons aur steps fixed
 * hain — yahan sirf jaankari ke liye dikhte hain.
 *
 * Samay badalte hi us workflow ka roz wala alarm DailyScheduler
 * se turant taaza set ho jata hai.
 */
class WorkflowEditActivity : Activity() {

    private lateinit var packWf: Workflow
    private lateinit var titleInput: EditText
    private lateinit var textInput: EditText
    private var chosenTime: String? = null
    private val limitFields = LinkedHashMap<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wfId = intent.getStringExtra(EXTRA_WF) ?: ""
        val found = WorkflowEngine.loadPack(this).firstOrNull { it.id == wfId }
        if (found == null) {
            Toast.makeText(this, "Workflow nahi mila, Boss", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        packWf = found
        val eff = WorkflowOverrides.effective(this, packWf)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "✏️ ${packWf.name}"
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = "Message, samay aur limits yahan badlein, Boss. Pack " +
                "waise ka waisa rahega — aapki edits uske upar ek layer " +
                "ki tarah lagu hongi, aur reset kabhi bhi ho sakta hai."
            setPadding(0, 8, 0, 8)
        })
        if (WorkflowOverrides.hasOverrides(this, packWf.id)) {
            root.addView(TextView(this).apply {
                text = "✏️ Is workflow par aapke custom edits lagu hain."
                setPadding(0, 0, 0, 8)
            })
        }

        // ---------- Message ----------
        section(root, "Message")
        root.addView(label("Title"))
        titleInput = EditText(this).apply {
            setText(eff.title)
            setSingleLine(true)
        }
        root.addView(titleInput)
        root.addView(label("Text"))
        textInput = EditText(this).apply {
            setText(eff.text)
            setSingleLine(false)
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(textInput)

        // ---------- Samay (sirf 'time' trigger) ----------
        val packTime = packWf.triggerParams["time"]
        if (packWf.triggerType == "time" && packTime != null) {
            section(root, "Samay (roz ka time)")
            chosenTime = eff.triggerParams["time"] ?: packTime
            val timeBtn = Button(this).apply {
                text = "⏰ $chosenTime — badalne ke liye tap karein"
                layoutParams = matchWrap()
            }
            timeBtn.setOnClickListener {
                val parts = (chosenTime ?: packTime).split(":")
                val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
                val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
                TimePickerDialog(this, { _, hour, minute ->
                    chosenTime = String.format("%02d:%02d", hour, minute)
                    timeBtn.text = "⏰ $chosenTime — badalne ke liye tap karein"
                }, h, m, true).show()
            }
            root.addView(timeBtn)
            if (packWf.triggerParams.containsKey("settings_key")) {
                root.addView(TextView(this).apply {
                    text = "Note: yeh is workflow ka apna samay hai — " +
                        "Settings screen wale samay se upar yahi chalega. " +
                        "Reset karne par Settings wala samay wapas lagu hoga."
                    setPadding(0, 4, 0, 0)
                })
            }
        }

        // ---------- Limits (numeric trigger params + settings) ----------
        val numericKeys = numericKeys()
        if (numericKeys.isNotEmpty()) {
            section(root, "Limits")
            for (key in numericKeys) {
                root.addView(label(labelFor(key)))
                val et = EditText(this).apply {
                    setText(eff.triggerParams[key] ?: eff.settings[key] ?: "")
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_NUMBER
                }
                limitFields[key] = et
                root.addView(et)
            }
        }

        // ---------- Fixed jaankari (read-only) ----------
        section(root, "Fixed cheezein (badal nahi sakti)")
        val fixed = StringBuilder()
        fixed.append("Trigger: ${packWf.triggerType}")
        for ((k, v) in packWf.triggerParams) {
            if (k == "time" || numericKeys.contains(k)) continue
            fixed.append("\n$k = $v")
        }
        fixed.append("\nBot type: ${packWf.effectiveBotType}")
        if (packWf.primaryAction != null) {
            fixed.append("\nAction: ${packWf.primaryAction}")
        }
        root.addView(TextView(this).apply { text = fixed.toString() })
        if (packWf.buttons.isNotEmpty()) {
            root.addView(label("Buttons"))
            for (b in packWf.buttons) {
                root.addView(TextView(this).apply {
                    text = "• ${b.label} → ${b.action}"
                })
            }
        }
        for (s in packWf.steps) {
            root.addView(TextView(this).apply {
                text = "Step '${s.id}': ${s.title}"
            })
        }

        // ---------- Save / Reset ----------
        val saveBtn = Button(this).apply {
            text = "Save karo"
            layoutParams = matchWrap()
        }
        saveBtn.setOnClickListener { save() }
        root.addView(saveBtn)

        val resetBtn = Button(this).apply {
            text = "Pack default par reset"
            layoutParams = matchWrap()
        }
        resetBtn.setOnClickListener { resetAll() }
        root.addView(resetBtn)
    }

    /**
     * Editable numeric keys — trigger params aur settings ka sangam.
     * Ek key dono jagah ho (jaise battery ki 'level') to field ek hi
     * banta hai, aur save par dono jagah ki override ek saath badalti
     * hai — engine jahan se bhi padhe, nayi value hi milegi.
     * 'time' (HH:MM) aur 'settings_key' (pointer) numeric nahi hain.
     */
    private fun numericKeys(): List<String> {
        val keys = LinkedHashSet<String>()
        for ((k, v) in packWf.triggerParams) {
            if (k == "time" || k == "settings_key") continue
            if (v.toIntOrNull() != null) keys.add(k)
        }
        for ((k, v) in packWf.settings) {
            if (v.toIntOrNull() != null) keys.add(k)
        }
        return keys.toList()
    }

    private fun labelFor(key: String): String = when (key) {
        "minutes" -> "Minutes (limit)"
        "apps" -> "Apps ki sankhya"
        "seconds" -> "Har app ke seconds"
        "window_minutes" -> "Window (minutes)"
        "cooldown_minutes" -> "Cooldown (minutes)"
        "hours" -> "Har kitne ghante (hours)"
        "level" -> "Battery level (%)"
        "after_minutes" -> "Alarm kitne minute baad (minutes)"
        "cache_mb" -> "Cache limit (MB)"
        "days" -> "Din (days)"
        else -> key
    }

    private fun save() {
        val newTitle = titleInput.text.toString().trim()
        val newText = textInput.text.toString().trim()
        if (newTitle.isEmpty() || newText.isEmpty()) {
            toast("Title aur text khaali nahi ho sakte, Boss")
            return
        }
        val trigOvr = mutableMapOf<String, String>()
        val setOvr = mutableMapOf<String, String>()
        for ((key, et) in limitFields) {
            val num = et.text.toString().trim().toIntOrNull()
            if (num == null || num < 0) {
                toast("'$key' ke liye sahi number daalein, Boss")
                return
            }
            val v = num.toString()
            if (packWf.triggerParams.containsKey(key) &&
                packWf.triggerParams[key] != v
            ) {
                trigOvr[key] = v
            }
            if (packWf.settings.containsKey(key) &&
                packWf.settings[key] != v
            ) {
                setOvr[key] = v
            }
        }
        val packTime = packWf.triggerParams["time"]
        val timeOvr =
            if (packTime != null && chosenTime != null && chosenTime != packTime) {
                chosenTime
            } else {
                null
            }
        WorkflowOverrides.save(
            this, packWf.id,
            title = if (newTitle != packWf.title) newTitle else null,
            text = if (newText != packWf.text) newText else null,
            time = timeOvr,
            trigger = trigOvr,
            settings = setOvr
        )
        if (packWf.triggerType == "time") {
            // Samay/limits taaze hon — roz wala alarm nayi sthiti se set karo
            DailyScheduler.scheduleOne(this, packWf)
        }
        LogStore.log(this, "Workflow [${packWf.id}] edit save hua (Editor-lite)")
        toast("Save ho gaya, Boss ✅")
        finish()
    }

    private fun resetAll() {
        WorkflowOverrides.reset(this, packWf.id)
        if (packWf.triggerType == "time") {
            DailyScheduler.scheduleOne(this, packWf)
        }
        LogStore.log(this, "Workflow [${packWf.id}] pack default par reset hua")
        toast("Pack default par reset ho gaya, Boss")
        finish()
    }

    // ---------- chhote UI helpers ----------

    private fun section(root: LinearLayout, title: String) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setPadding(0, 22, 0, 6)
        })
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, 10, 0, 2)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_WF = "edit_wf"
    }
}
