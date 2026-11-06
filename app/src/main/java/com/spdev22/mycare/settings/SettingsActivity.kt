package com.spdev22.mycare.settings

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.data.GroupStore
import com.spdev22.mycare.engine.HisaabBuilder
import com.spdev22.mycare.notify.DailyScheduler
import com.spdev22.mycare.ui.AppPickerActivity
import com.spdev22.mycare.ui.UiKit

/**
 * Settings (D3) — saari values user ke haath me, defaults plan ke.
 * Time badalne par roz wale alarms turant dobara set ho jaate hain;
 * baaki thresholds engine agle poll (20 sec) me utha leta hai.
 *
 * 0.2.5: UiKit wala naya look (cards + palette) aur naya
 * "Gemini AI" section — API key (sirf phone par) + model naam.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // App Picker se lautne par nayi values dikhni chahiye
        buildUi()
    }

    private fun buildUi() {
        val root = UiKit.root(this)
        setContentView(UiKit.scroll(this, root))

        root.addView(UiKit.title(this, "Settings"))
        root.addView(
            UiKit.subtitle(
                this,
                "Boss, saare time aur limits yahan se badle ja sakte hain. " +
                    "Badlav turant lagu ho jata hai."
            )
        )

        sectionLabel(root, "Roz ke Suggestions")
        timeRow(root, "Study Suggestion", "study_time", CareSettings.DEFAULT_STUDY_TIME)
        timeRow(root, "Aaj Ka Hisaab", "hisaab_time", CareSettings.DEFAULT_HISAAB_TIME)
        timeRow(root, "Alarm Suggestion", "alarm_time", CareSettings.DEFAULT_ALARM_TIME)

        sectionLabel(root, "Limits (minutes)")
        intRow(
            root, "Ek app lagatar chale to suggestion",
            CareSettings.KEY_LONG_MINUTES, CareSettings.DEFAULT_LONG_MINUTES, "min"
        )
        intRow(
            root, "Lagatar phone use par araam ka nudge",
            CareSettings.KEY_REST_MINUTES, CareSettings.DEFAULT_REST_MINUTES, "min"
        )

        sectionLabel(root, "Boredom Detection")
        intRow(
            root, "Kitne apps khul-bund hon",
            CareSettings.KEY_BOREDOM_APPS, CareSettings.DEFAULT_BOREDOM_APPS, "apps"
        )
        intRow(
            root, "Har app itne seconds se kam chale",
            CareSettings.KEY_BOREDOM_SECONDS, CareSettings.DEFAULT_BOREDOM_SECONDS, "sec"
        )

        sectionLabel(root, "Phone Care (Phase 3)")
        intRow(
            root, "Paani Reminder — har itne ghante me",
            CareSettings.KEY_PAANI_HOURS, CareSettings.DEFAULT_PAANI_HOURS, "ghante"
        )
        intRow(
            root, "Battery Low warning — is % par ya neeche",
            CareSettings.KEY_BATTERY_LOW, CareSettings.DEFAULT_BATTERY_LOW, "%"
        )
        intRow(
            root, "Cache Safai — kisi app ka cache isse bada ho to",
            CareSettings.KEY_CACHE_MB, CareSettings.DEFAULT_CACHE_MB, "MB"
        )

        sectionLabel(root, "Action Target Apps")
        root.addView(TextView(this).apply {
            text = "Notification ke buttons kaun sa app kholenge, yahan se chunein. " +
                "Clock wala app Alarm Suggestion par KHULEGA (0.2.5) — " +
                "alarm aap wahi se khud set karte hain."
            setPadding(0, 0, 0, 4)
        })
        slotRow(root, "Music button", "music")
        slotRow(root, "Videos / YouTube button", "video")
        slotRow(root, "Muse AI / WhatsApp button", "chat")
        slotRow(root, "Alarm ke liye Clock app", "clock")

        sectionLabel(root, "Workflows ke liye zaroori Groups")
        root.addView(TextView(this).apply {
            text = "Aaj Ka Hisaab jaise workflows App Groups ko NAAM se " +
                "pehchante hain. Yeh do naam ke group bane hone chahiye — " +
                "warna hisaab me padhai aur manoranjan alag nahi hoga. " +
                "Group banakar Main screen se unme apps jod dijiye."
            setPadding(0, 0, 0, 4)
        })
        requiredGroupRow(root, HisaabBuilder.STUDY_GROUP_NAME, "padhai / study wale apps ke liye")
        requiredGroupRow(root, HisaabBuilder.ENTERTAINMENT_GROUP_NAME, "manoranjan / entertainment wale apps ke liye")

        sectionLabel(root, "Gemini AI — Workflow Banwao")
        root.addView(TextView(this).apply {
            text = "'AI se Workflow banwao' ke liye aapki apni Gemini API " +
                "key chahiye. Key SIRF is phone me rehti hai — kahin " +
                "upload nahi hoti. Internet sirf tab chalta hai jab " +
                "aap 'Banwao' dabate hain."
            setPadding(0, 0, 0, 4)
        })
        geminiKeyRow(root)
        geminiModelRow(root)

        sectionLabel(root, "Welcome")
        val welcomeCard = UiKit.card(this)
        val welcomeBox = CheckBox(this).apply {
            text = "Welcome Boss — din ke pehle unlock par"
            isChecked = CareSettings.welcomeEnabled(this@SettingsActivity)
            textSize = 16f
            setTextColor(UiKit.INK)
            UiKit.tintCheckBox(this@SettingsActivity, this)
            setOnCheckedChangeListener { _, checked ->
                CareSettings.setWelcomeEnabled(this@SettingsActivity, checked)
                toast(if (checked) "Welcome Boss ON" else "Welcome Boss OFF")
            }
        }
        welcomeCard.addView(welcomeBox)
        root.addView(welcomeCard)

        root.addView(UiKit.small(this, "Note: Koi value badalna = sirf aapke liye. Pack ke " +
            "baaki workflows apne defaults par chalte rahenge."))
    }

    /** Gemini API key wali kataar — sthiti dikhao, tap = badlo/hatayo. */
    private fun geminiKeyRow(root: LinearLayout) {
        val key = CareSettings.geminiKey(this)
        val status = if (key == null) {
            "nahi set ✗ — tap karke daalein"
        } else {
            "set hai ✓ (…${key.takeLast(4)}) — tap karke badlein"
        }
        val row = TextView(this).apply {
            text = "Gemini API Key — $status"
            textSize = 16f
            setTextColor(UiKit.INK)
            setPadding(0, 10, 0, 10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { showGeminiKeyDialog() }
        }
        val card = UiKit.card(this)
        card.addView(row)
        root.addView(card)
    }

    private fun showGeminiKeyDialog() {
        val input = EditText(this).apply {
            hint = "AIza… wali Gemini API key yahan chipkayein"
            // Password-shailee ka input — kandhe ke peeche se koi na padhe
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini API Key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isEmpty()) {
                    toast("Key khaali hai, Boss — hatani ho to 'Key hatayein' dabayein")
                } else {
                    CareSettings.setGeminiKey(this, value)
                    toast("Gemini key save ho gayi ✓")
                    buildUi()
                }
            }
            .setNeutralButton("Key hatayein") { _, _ ->
                CareSettings.setGeminiKey(this, null)
                toast("Gemini key hata di gayi")
                buildUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Gemini model wali kataar — naam dikhao, tap = badlo. */
    private fun geminiModelRow(root: LinearLayout) {
        val current = CareSettings.geminiModel(this)
        val row = TextView(this).apply {
            text = "Gemini Model — $current  (badalne ke liye tap karein)"
            textSize = 16f
            setTextColor(UiKit.INK)
            setPadding(0, 10, 0, 10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val input = EditText(this@SettingsActivity).apply {
                    setText(current)
                    hint = "jaise gemini-2.0-flash"
                    setSingleLine(true)
                }
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Gemini Model")
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val value = input.text.toString().trim()
                        if (value.isNotEmpty()) {
                            CareSettings.setGeminiModel(this@SettingsActivity, value)
                            toast("Model set ho gaya: $value")
                            buildUi()
                        } else {
                            toast("Model ka naam khaali nahi ho sakta, Boss")
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        val card = UiKit.card(this)
        card.addView(row)
        root.addView(card)
    }

    private fun sectionLabel(root: LinearLayout, text: String) {
        root.addView(UiKit.section(this, text))
    }

    private fun timeRow(
        root: LinearLayout,
        label: String,
        settingsKey: String,
        default: String
    ) {
        val current = CareSettings.time(this, settingsKey, default)
        val row = TextView(this).apply {
            text = "$label — $current  (badalne ke liye tap karein)"
            textSize = 16f
            setTextColor(UiKit.INK)
            setPadding(0, 10, 0, 10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val parts = current.split(":")
                val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
                val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
                TimePickerDialog(this@SettingsActivity, { _, hour, minute ->
                    val value = "%02d:%02d".format(hour, minute)
                    CareSettings.setTime(this@SettingsActivity, settingsKey, value)
                    DailyScheduler.scheduleAll(this@SettingsActivity)
                    toast("$label ab $value par set hai")
                    buildUi()
                }, h, m, true).show()
            }
        }
        val card = UiKit.card(this)
        card.addView(row)
        root.addView(card)
    }

    private fun intRow(
        root: LinearLayout,
        label: String,
        key: String,
        default: Int,
        unit: String
    ) {
        val current = CareSettings.intVal(this, key, default)
        val row = TextView(this).apply {
            text = "$label — $current $unit  (badalne ke liye tap karein)"
            textSize = 16f
            setTextColor(UiKit.INK)
            setPadding(0, 10, 0, 10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val input = EditText(this@SettingsActivity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(current.toString())
                    hint = "Number likhein"
                }
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(label)
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val value = input.text.toString().toIntOrNull()
                        if (value != null && value > 0) {
                            CareSettings.setInt(this@SettingsActivity, key, value)
                            toast("Set ho gaya: $value $unit")
                            buildUi()
                        } else {
                            toast("Sahi number likhein, Boss")
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        val card = UiKit.card(this)
        card.addView(row)
        root.addView(card)
    }

    /**
     * Ek zaroori group ki live sthiti: bana hai to ✓, na bana ho to
     * ✗ ke saath ek-tap "Banao" button (khaali group GroupStore se
     * ban jata hai; apps baad me Main screen se judenge).
     * Naam ki jaanch case-insensitive hai — "padhai" bhi chalega.
     */
    private fun requiredGroupRow(root: LinearLayout, name: String, hint: String) {
        val exists = GroupStore.loadGroups(this)
            .any { it.name.equals(name, ignoreCase = true) }
        val card = UiKit.card(this)
        if (exists) {
            card.addView(TextView(this).apply {
                text = "$name — bana hai ✓  ($hint)"
                textSize = 16f
                setTextColor(UiKit.INK)
                setPadding(0, 10, 0, 10)
            })
        } else {
            card.addView(TextView(this).apply {
                text = "$name — nahi bana ✗  ($hint)"
                textSize = 16f
                setTextColor(UiKit.INK)
                setPadding(0, 10, 0, 2)
            })
            card.addView(UiKit.primaryButton(this, "'$name' group banao").apply {
                setOnClickListener {
                    if (GroupStore.addGroup(this@SettingsActivity, name)) {
                        toast("'$name' group ban gaya — Main screen se isme apps jod dijiye")
                    } else {
                        toast("Group pehle se hai, Boss")
                    }
                    buildUi()
                }
            })
        }
        root.addView(card)
    }

    private fun slotRow(root: LinearLayout, label: String, slot: String) {
        val chosen = CareSettings.targetApp(this, slot)
        val currentLabel = if (chosen == null) {
            "Auto"
        } else {
            try {
                val pm = packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(chosen, 0)).toString()
            } catch (e: Exception) {
                chosen
            }
        }
        val row = TextView(this).apply {
            text = "$label — $currentLabel  (badalne ke liye tap karein)"
            textSize = 16f
            setTextColor(UiKit.INK)
            setPadding(0, 10, 0, 10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                startActivity(
                    Intent(this@SettingsActivity, AppPickerActivity::class.java)
                        .putExtra(AppPickerActivity.EXTRA_MODE, "slot")
                        .putExtra(AppPickerActivity.EXTRA_SLOT, slot)
                )
            }
        }
        val card = UiKit.card(this)
        card.addView(row)
        root.addView(card)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
