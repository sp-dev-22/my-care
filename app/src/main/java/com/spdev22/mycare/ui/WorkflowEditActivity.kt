package com.spdev22.mycare.ui

import android.app.Activity
import android.app.AlertDialog
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
import com.spdev22.mycare.data.UserWorkflowStore
import com.spdev22.mycare.data.WorkflowOverrides
import com.spdev22.mycare.data.WorkflowPrefs
import com.spdev22.mycare.engine.Workflow
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.DailyScheduler
import com.spdev22.mycare.setup.AutoReplyConfirm
import com.spdev22.mycare.setup.WorkflowPermissions

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
 *
 * 0.2.5: UiKit wala naya look. Saath me — user ke APNE (Gemini-se
 * bane) workflows ke liye yahan DELETE button bhi hai; pack ke
 * workflows delete nahi hote (woh app ke saath aate hain).
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
            Toast.makeText(this, "Workflow not found, Boss", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        packWf = found
        val eff = WorkflowOverrides.effective(this, packWf)

        val root = UiKit.root(this)
        setContentView(UiKit.scroll(this, root))

        root.addView(UiKit.title(this, "✏️ ${packWf.name}"))
        root.addView(
            UiKit.subtitle(
                this,
                "Change the message, time and limits here, Boss. The " +
                    "pack itself stays untouched — your edits apply as " +
                    "a layer on top of it, and can be reset any time."
            )
        )
        if (WorkflowOverrides.hasOverrides(this, packWf.id)) {
            root.addView(UiKit.small(this, "✏️ Your custom edits are active on this workflow."))
            root.addView(UiKit.gap(this, 4))
        }
        val isUserWorkflow = UserWorkflowStore.contains(this, packWf.id)
        if (isUserWorkflow) {
            root.addView(UiKit.small(this, "✨ This is your own (AI-created) workflow — you can delete it below."))
            root.addView(UiKit.gap(this, 4))
        }

        // ---------- Message ----------
        section(root, "Message")
        root.addView(label("Title"))
        titleInput = UiKit.input(this, "Title").apply {
            setText(eff.title)
            setSingleLine(true)
        }
        root.addView(titleInput)
        root.addView(label("Text"))
        textInput = UiKit.input(this, "Text").apply {
            setText(eff.text)
            setSingleLine(false)
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(textInput)

        // ---------- Samay (sirf 'time' trigger) ----------
        val packTime = packWf.triggerParams["time"]
        if (packWf.triggerType == "time" && packTime != null) {
            section(root, "Time (daily)")
            chosenTime = eff.triggerParams["time"] ?: packTime
            val timeBtn = UiKit.secondaryButton(this, "⏰ $chosenTime — tap to change")
            timeBtn.setOnClickListener {
                val parts = (chosenTime ?: packTime).split(":")
                val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
                val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
                TimePickerDialog(this, { _, hour, minute ->
                    chosenTime = String.format("%02d:%02d", hour, minute)
                    timeBtn.text = "⏰ $chosenTime — tap to change"
                }, h, m, true).show()
            }
            root.addView(timeBtn)
            if (packWf.triggerParams.containsKey("settings_key")) {
                root.addView(UiKit.small(this, "Note: this is the workflow's own time — " +
                    "it wins over the Settings screen time for this workflow. " +
                    "Resetting brings the Settings time back."))
            }
        }

        // ---------- Limits (numeric trigger params + settings) ----------
        val numericKeys = numericKeys()
        if (numericKeys.isNotEmpty()) {
            section(root, "Limits")
            for (key in numericKeys) {
                root.addView(label(labelFor(key)))
                val et = UiKit.input(this, labelFor(key)).apply {
                    setText(eff.triggerParams[key] ?: eff.settings[key] ?: "")
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_NUMBER
                }
                limitFields[key] = et
                root.addView(et)
            }
        }

        // ---------- Fixed jaankari (read-only) ----------
        section(root, "Fixed things (cannot change)")
        val fixedCard = UiKit.card(this)
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
        fixedCard.addView(UiKit.body(this, fixed.toString()))
        if (packWf.buttons.isNotEmpty()) {
            fixedCard.addView(label("Buttons"))
            for (b in packWf.buttons) {
                // 0.2.6 UI FIX: separator ASCII "->" hai. Unicode teer
                // (U+2192) Infinix ke font me "'n" jaisa bigda hua dikhta
                // tha — source sahi hote hue bhi screen kharab thi.
                fixedCard.addView(UiKit.body(this, "• ${b.label} -> ${b.action}"))
            }
        }
        for (s in packWf.steps) {
            fixedCard.addView(UiKit.body(this, "Step '${s.id}': ${s.title}"))
        }
        root.addView(fixedCard)

        // ---------- Save / Reset ----------
        root.addView(UiKit.gap(this, 6))
        val saveBtn = UiKit.primaryButton(this, "Save")
        saveBtn.setOnClickListener { save() }
        root.addView(saveBtn)

        val resetBtn = UiKit.secondaryButton(this, "Reset to pack default")
        resetBtn.setOnClickListener { resetAll() }
        root.addView(resetBtn)

        // ---------- Delete (sirf user ke apne workflows) ----------
        if (isUserWorkflow) {
            section(root, "Remove your workflow")
            val delBtn = UiKit.dangerButton(this, "🗑️ Delete this workflow")
            delBtn.setOnClickListener { confirmDelete() }
            root.addView(delBtn)
        }
    }

    /** User ke apne workflow ko pakki taur par hatana — pucca karke. */
    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete this workflow?")
            .setMessage(
                "'${packWf.name}' will be removed forever, Boss. " +
                    "All its edits and its On/Off state will also be cleared."
            )
            .setPositiveButton("Delete") { _, _ ->
                if (packWf.triggerType == "time") {
                    DailyScheduler.cancelOne(this, packWf)
                }
                UserWorkflowStore.delete(this, packWf.id)
                WorkflowOverrides.reset(this, packWf.id)
                WorkflowPrefs.clearOverride(this, packWf.id)
                LogStore.log(this, "User workflow [${packWf.id}] deleted (Editor)")
                toast("Workflow deleted, Boss")
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        "apps" -> "Number of apps"
        "seconds" -> "Seconds per app"
        "window_minutes" -> "Window (minutes)"
        "cooldown_minutes" -> "Cooldown (minutes)"
        "hours" -> "Every how many hours"
        "level" -> "Battery level (%)"
        "percent" -> "Percent (%)"
        "after_minutes" -> "Alarm after how many minutes"
        "cache_mb" -> "Cache limit (MB)"
        "days" -> "Days"
        else -> key
    }

    private fun save() {
        val newTitle = titleInput.text.toString().trim()
        val newText = textInput.text.toString().trim()
        if (newTitle.isEmpty() || newText.isEmpty()) {
            toast("Title and text cannot be empty, Boss")
            return
        }
        val trigOvr = mutableMapOf<String, String>()
        val setOvr = mutableMapOf<String, String>()
        for ((key, et) in limitFields) {
            val num = et.text.toString().trim().toIntOrNull()
            if (num == null || num < 0) {
                toast("Please enter a valid number for '$key', Boss")
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
        val doSave = {
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
            LogStore.log(this, "Workflow [${packWf.id}] edit saved (Editor-lite)")
            toast("Saved, Boss ✅")
            // 0.2.8: permission-on-enable also applies to the Editor —
            // if this workflow is ON and permissions are missing, offer
            // them now; the screen finishes when the prompt is dismissed
            // (or immediately when nothing is missing).
            if (WorkflowPrefs.isEnabled(this, packWf)) {
                WorkflowPermissions.promptIfMissing(this, packWf) { finish() }
            } else {
                finish()
            }
        }
        // 0.2.9: saving an ENABLED auto-reply workflow re-confirms
        // auto-reply first — the edits are written only on confirm;
        // on Cancel nothing changes and the screen stays open.
        if (AutoReplyConfirm.appliesTo(packWf) &&
            WorkflowPrefs.isEnabled(this, packWf)
        ) {
            AutoReplyConfirm.confirmIfNeeded(
                this, packWf,
                onCancelled = { toast("Not saved — auto-reply was not confirmed") },
                onConfirmed = { doSave() }
            )
        } else {
            doSave()
        }
    }

    private fun resetAll() {
        WorkflowOverrides.reset(this, packWf.id)
        if (packWf.triggerType == "time") {
            DailyScheduler.scheduleOne(this, packWf)
        }
        LogStore.log(this, "Workflow [${packWf.id}] reset to pack default")
        toast("Reset to pack default, Boss")
        finish()
    }

    // ---------- chhote UI helpers ----------

    private fun section(root: LinearLayout, title: String) {
        root.addView(UiKit.section(this, title))
    }

    private fun label(text: String): TextView = UiKit.label(this, text)

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_WF = "edit_wf"
    }
}
