package com.spdev22.mycare.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.data.GeminiClient
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.data.UserWorkflowStore
import com.spdev22.mycare.data.WorkflowPrefs
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.DailyScheduler
import com.spdev22.mycare.settings.SettingsActivity
import com.spdev22.mycare.setup.AutoReplyConfirm
import com.spdev22.mycare.setup.WorkflowPermissions

/**
 * "AI se Workflow banwao" (0.2.5) — user apni baat apne shabdon me
 * likhe (Hinglish / Hindi / English), Gemini use Workflow Format v1
 * me badal de, aur saaf-jaanch ke baad woh workflow pack ke saath
 * milkar LIVE ho jaye — Workflows list me dikhega, toggle aur
 * Editor-lite edit bhi chalega.
 *
 * Gemini ki key user ki apni hai (Settings me rakhi) — internet
 * sirf "Banwao" dabane par, background thread me, use hota hai.
 * Kuch gadbad ho to imaandaar wajah dikhegi aur KUCH save nahi hoga.
 */
class AiWorkflowActivity : Activity() {

    private lateinit var descInput: EditText
    private lateinit var makeBtn: Button
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.root(this)
        UiKit.tabScreen(this, root, UiKit.TAB_AI)

        root.addView(UiKit.title(this, "✨ Make a Workflow with AI"))
        root.addView(
            UiKit.subtitle(
                this,
                "Describe the workflow you want in your own words, " +
                    "Boss — Gemini will convert it into My Care's " +
                    "format and it goes live immediately. Describe it " +
                    "in Hindi, Hinglish or English — the workflow " +
                    "itself is always created in English."
            )
        )

        val card = UiKit.card(this)
        card.addView(UiKit.label(this, "What kind of workflow do you want?"))
        descInput = UiKit.input(this, "e.g. Remind me to drink water at 10 pm, every day").apply {
            setSingleLine(false)
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        card.addView(descInput)
        makeBtn = UiKit.primaryButton(this, "✨ Create it")
        makeBtn.setOnClickListener { onMakeClicked() }
        card.addView(makeBtn)
        statusView = UiKit.small(this, "")
        card.addView(statusView)
        root.addView(card)

        val info = UiKit.card(this)
        info.addView(
            UiKit.small(
                this,
                "Note: this needs your Gemini API key (in Settings) " +
                    "and internet. The key stays only on your phone. " +
                    "Created workflows appear in the 'Workflows' tab — " +
                    "from there you can switch them off, edit their " +
                    "message/time/limits, and delete the AI-made ones."
            )
        )
        root.addView(info)
    }

    private fun onMakeClicked() {
        val description = descInput.text.toString().trim()
        if (description.length < 8) {
            toast("Please describe it a bit more, Boss — what, when and why?")
            return
        }
        val apiKey = CareSettings.geminiKey(this)
        if (apiKey == null) {
            statusView.text = "No Gemini API key found. Please add your " +
                "key in Settings first — then come back and tap Create."
            statusView.setTextColor(UiKit.DANGER)
            toast("Add your Gemini API key in Settings first, Boss")
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val model = CareSettings.geminiModel(this)
        makeBtn.isEnabled = false
        makeBtn.text = "⏳ Gemini is thinking…"
        statusView.setTextColor(UiKit.MUTED)
        statusView.text = "Talking to Gemini ($model) — this can take " +
            "a few seconds, depending on your internet speed."

        // Network ka kaam background thread par — Go phone par
        // main thread kabhi block nahi hoga.
        Thread {
            val existing = mutableSetOf<String>()
            existing.addAll(
                WorkflowEngine.loadPack(this).map { it.id }
            )
            existing.addAll(UserWorkflowStore.existingIds(this))
            val result = GeminiClient.generate(apiKey, model, description, existing)
            runOnUiThread { onResult(result) }
        }.start()
    }

    private fun onResult(result: GeminiClient.Result) {
        makeBtn.isEnabled = true
        makeBtn.text = "✨ Create it"
        when (result) {
            is GeminiClient.Result.Failure -> {
                statusView.setTextColor(UiKit.DANGER)
                statusView.text = "❌ " + result.message
                LogStore.log(this, "AI workflow FAILED: ${result.message}")
                toast("The workflow could not be created, Boss — the reason is written below")
            }
            is GeminiClient.Result.Success -> {
                UserWorkflowStore.save(this, result.workflowJson)
                val wf = WorkflowEngine.loadPack(this)
                    .firstOrNull { it.id == result.workflowJson.optString("id") }
                LogStore.log(
                    this,
                    "AI workflow created: '${result.name}' " +
                        "(id=${result.workflowJson.optString("id")})"
                )
                statusView.setTextColor(UiKit.TEAL_DARK)
                statusView.text = "✅ Created: '${result.name}' — it is " +
                    "live now! In the 'Workflows' tab you can switch " +
                    "it off, edit it, or delete it."
                descInput.text.clear()
                toast("Workflow created: ${result.name} 🎉")
                // The "goes live" finishing steps: a time trigger's
                // daily alarm is set right away (other triggers are
                // picked up by CareService / the listener on their
                // own), and 0.2.8's permission-on-enable prompt runs.
                val finishEnable = {
                    if (wf != null && wf.triggerType == "time") {
                        DailyScheduler.scheduleOne(this, wf)
                    }
                    if (wf != null && wf.enabled) {
                        WorkflowPermissions.promptIfMissing(this, wf)
                    }
                }
                if (wf != null && wf.enabled && AutoReplyConfirm.appliesTo(wf)) {
                    // 0.2.9: an auto-reply workflow lands ENABLED —
                    // ask explicitly first. If Boss declines, the
                    // workflow is kept but forced OFF, so it can
                    // never reply silently; he can switch it on
                    // later from the Workflows tab (same question).
                    AutoReplyConfirm.confirmIfNeeded(
                        this, wf,
                        onCancelled = {
                            WorkflowPrefs.setEnabled(this, wf.id, false)
                            LogStore.log(
                                this,
                                "AI auto-reply workflow [${wf.id}] kept OFF — " +
                                    "auto-reply was not confirmed"
                            )
                            statusView.text = "✅ Created: '${result.name}' — " +
                                "but it is OFF, because auto-reply was not " +
                                "confirmed. Turn it on from the 'Workflows' " +
                                "tab whenever you are ready."
                            toast("Saved, but OFF — auto-reply not confirmed")
                        },
                        onConfirmed = { finishEnable() }
                    )
                } else {
                    finishEnable()
                }
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
