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
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.DailyScheduler
import com.spdev22.mycare.settings.SettingsActivity

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
        setContentView(UiKit.scroll(this, root))

        root.addView(UiKit.title(this, "✨ AI se Workflow banwao"))
        root.addView(
            UiKit.subtitle(
                this,
                "Apni marzi ka workflow apne shabdon me likh dijiye, Boss — " +
                    "Gemini use My Care ke format me badal dega aur woh " +
                    "turant live ho jayega. Hinglish, Hindi ya English — " +
                    "jaisa mann kare."
            )
        )

        val card = UiKit.card(this)
        card.addView(UiKit.label(this, "Kaisa workflow chahiye?"))
        descInput = UiKit.input(this, "Jaise: raat 10 baje paani peene ki yaad dilao, roz").apply {
            setSingleLine(false)
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        card.addView(descInput)
        makeBtn = UiKit.primaryButton(this, "✨ Banwao")
        makeBtn.setOnClickListener { onMakeClicked() }
        card.addView(makeBtn)
        statusView = UiKit.small(this, "")
        card.addView(statusView)
        root.addView(card)

        val info = UiKit.card(this)
        info.addView(
            UiKit.small(
                this,
                "Note: Iske liye Settings me aapki Gemini API key aur " +
                    "internet chahiye. Key sirf aapke phone me rehti hai. " +
                    "Bane hue workflows 'Workflows — On/Off' list me " +
                    "milenge — wahan se band karna, message/time/limits " +
                    "badalna aur (AI walon ko) delete karna bhi hoga."
            )
        )
        root.addView(info)
    }

    private fun onMakeClicked() {
        val description = descInput.text.toString().trim()
        if (description.length < 8) {
            toast("Thoda vistar se likhiye, Boss — kya, kab aur kyun?")
            return
        }
        val apiKey = CareSettings.geminiKey(this)
        if (apiKey == null) {
            statusView.text = "Gemini API key nahi mili. Pehle Settings me " +
                "apni key daal dijiye — phir yahan aakar Banwao dabaiye."
            statusView.setTextColor(UiKit.DANGER)
            toast("Pehle Settings me Gemini API key daalein, Boss")
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val model = CareSettings.geminiModel(this)
        makeBtn.isEnabled = false
        makeBtn.text = "⏳ Gemini soch raha hai…"
        statusView.setTextColor(UiKit.MUTED)
        statusView.text = "Gemini ($model) se baat ho rahi hai — " +
            "internet ki raftaar par nirbhar hai, kuch second lag sakte hain."

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
        makeBtn.text = "✨ Banwao"
        when (result) {
            is GeminiClient.Result.Failure -> {
                statusView.setTextColor(UiKit.DANGER)
                statusView.text = "❌ " + result.message
                LogStore.log(this, "AI workflow FAIL: ${result.message}")
                toast("Workflow nahi bana, Boss — wajah neeche likhi hai")
            }
            is GeminiClient.Result.Success -> {
                UserWorkflowStore.save(this, result.workflowJson)
                // Time-trigger ho to roz wala alarm turant set karo;
                // baaki triggers CareService apne aap utha lega
                // (version badhte hi uski list taaza ho jaati hai).
                val wf = WorkflowEngine.loadPack(this)
                    .firstOrNull { it.id == result.workflowJson.optString("id") }
                if (wf != null && wf.triggerType == "time") {
                    DailyScheduler.scheduleOne(this, wf)
                }
                LogStore.log(
                    this,
                    "AI workflow bana: '${result.name}' " +
                        "(id=${result.workflowJson.optString("id")})"
                )
                statusView.setTextColor(UiKit.TEAL_DARK)
                statusView.text = "✅ Ban gaya: '${result.name}' — ab yeh " +
                    "live hai! 'Workflows — On/Off' list me ise band karna, " +
                    "edit karna ya delete karna — sab hoga."
                descInput.text.clear()
                toast("Workflow ban gaya: ${result.name} 🎉")
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
