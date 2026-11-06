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
        UiKit.tabScreen(this, root, UiKit.TAB_SETTINGS)

        root.addView(UiKit.title(this, "Settings"))
        root.addView(
            UiKit.subtitle(
                this,
                "Boss, every time and limit can be changed here. " +
                    "Changes apply immediately."
            )
        )

        sectionLabel(root, "Daily Suggestions")
        timeRow(root, "Study Suggestion", "study_time", CareSettings.DEFAULT_STUDY_TIME)
        timeRow(root, "Daily Account (Hisaab)", "hisaab_time", CareSettings.DEFAULT_HISAAB_TIME)
        timeRow(root, "Alarm Suggestion", "alarm_time", CareSettings.DEFAULT_ALARM_TIME)

        sectionLabel(root, "Limits (minutes)")
        intRow(
            root, "Suggest a break when one app runs this long",
            CareSettings.KEY_LONG_MINUTES, CareSettings.DEFAULT_LONG_MINUTES, "min"
        )
        intRow(
            root, "Rest nudge after continuous phone use of",
            CareSettings.KEY_REST_MINUTES, CareSettings.DEFAULT_REST_MINUTES, "min"
        )

        sectionLabel(root, "Boredom Detection")
        intRow(
            root, "How many apps open-close in the pattern",
            CareSettings.KEY_BOREDOM_APPS, CareSettings.DEFAULT_BOREDOM_APPS, "apps"
        )
        intRow(
            root, "Each app used less than (seconds)",
            CareSettings.KEY_BOREDOM_SECONDS, CareSettings.DEFAULT_BOREDOM_SECONDS, "sec"
        )

        sectionLabel(root, "Phone Care")
        intRow(
            root, "Water Reminder — every how many hours",
            CareSettings.KEY_PAANI_HOURS, CareSettings.DEFAULT_PAANI_HOURS, "hours"
        )
        intRow(
            root, "Battery Low warning — at or below this %",
            CareSettings.KEY_BATTERY_LOW, CareSettings.DEFAULT_BATTERY_LOW, "%"
        )
        intRow(
            root, "Cache Cleanup — when an app's cache grows beyond",
            CareSettings.KEY_CACHE_MB, CareSettings.DEFAULT_CACHE_MB, "MB"
        )
        intRow(
            root, "Intruder Alert — wrong unlock attempts before the alert + photo",
            CareSettings.KEY_INTRUDER_THRESHOLD, CareSettings.DEFAULT_INTRUDER_THRESHOLD, "attempts"
        )

        sectionLabel(root, "Action Target Apps")
        root.addView(TextView(this).apply {
            text = "Choose which app the notification buttons will open. " +
                "The Clock app OPENS when you tap the Alarm Suggestion " +
                "button — you set the alarm there yourself."
            setPadding(0, 0, 0, 4)
        })
        slotRow(root, "Music button", "music")
        slotRow(root, "Videos / YouTube button", "video")
        slotRow(root, "Muse AI / WhatsApp button", "chat")
        slotRow(root, "Clock app for alarms", "clock")

        sectionLabel(root, "Groups that workflows need")
        root.addView(TextView(this).apply {
            text = "Workflows like the Daily Account recognise App " +
                "Groups BY NAME. These two groups should exist — " +
                "otherwise the account cannot separate study from " +
                "entertainment. Create a group here, then add its " +
                "apps from the Home tab."
            setPadding(0, 0, 0, 4)
        })
        requiredGroupRow(root, HisaabBuilder.STUDY_GROUP_NAME, "for study apps")
        requiredGroupRow(root, HisaabBuilder.ENTERTAINMENT_GROUP_NAME, "for entertainment apps")

        sectionLabel(root, "Gemini AI — Make Workflows")
        root.addView(TextView(this).apply {
            text = "'Make a Workflow with AI' needs your own Gemini " +
                "API key. The key stays ONLY on this phone — it is " +
                "never uploaded anywhere. Internet is used only when " +
                "you tap 'Create it'."
            setPadding(0, 0, 0, 4)
        })
        geminiKeyRow(root)
        geminiModelRow(root)

        sectionLabel(root, "Welcome")
        val welcomeCard = UiKit.card(this)
        val welcomeBox = CheckBox(this).apply {
            text = "Welcome Boss — on the first unlock of the day"
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

        root.addView(UiKit.small(this, "Note: changing a value affects only you. " +
            "The pack's other workflows keep running on their defaults."))
    }

    /** Gemini API key wali kataar — sthiti dikhao, tap = badlo/hatayo. */
    private fun geminiKeyRow(root: LinearLayout) {
        val key = CareSettings.geminiKey(this)
        val status = if (key == null) {
            "not set ❌ — tap to add it"
        } else {
            "is set ✅ (…${key.takeLast(4)}) — tap to change"
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
            hint = "Paste your Gemini API key (starts with AIza…) here"
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
                    toast("The key is empty, Boss — use 'Remove key' if you want to remove it")
                } else {
                    CareSettings.setGeminiKey(this, value)
                    toast("Gemini key saved ✅")
                    buildUi()
                }
            }
            .setNeutralButton("Remove key") { _, _ ->
                CareSettings.setGeminiKey(this, null)
                toast("Gemini key removed")
                buildUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Gemini model wali kataar — naam dikhao, tap = badlo. */
    private fun geminiModelRow(root: LinearLayout) {
        val current = CareSettings.geminiModel(this)
        val row = TextView(this).apply {
            text = "Gemini Model — $current  (tap to change)"
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
                    hint = "e.g. gemini-2.0-flash"
                    setSingleLine(true)
                }
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Gemini Model")
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val value = input.text.toString().trim()
                        if (value.isNotEmpty()) {
                            CareSettings.setGeminiModel(this@SettingsActivity, value)
                            toast("Model set: $value")
                            buildUi()
                        } else {
                            toast("The model name cannot be empty, Boss")
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
            text = "$label — $current  (tap to change)"
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
                    toast("$label is now set to $value")
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
            text = "$label — $current $unit  (tap to change)"
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
                    hint = "Type a number"
                }
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(label)
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val value = input.text.toString().toIntOrNull()
                        if (value != null && value > 0) {
                            CareSettings.setInt(this@SettingsActivity, key, value)
                            toast("Set: $value $unit")
                            buildUi()
                        } else {
                            toast("Please type a valid number, Boss")
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
                text = "$name — exists ✅  ($hint)"
                textSize = 16f
                setTextColor(UiKit.INK)
                setPadding(0, 10, 0, 10)
            })
        } else {
            card.addView(TextView(this).apply {
                text = "$name — not created ❌  ($hint)"
                textSize = 16f
                setTextColor(UiKit.INK)
                setPadding(0, 10, 0, 2)
            })
            card.addView(UiKit.primaryButton(this, "Create the '$name' group").apply {
                setOnClickListener {
                    if (GroupStore.addGroup(this@SettingsActivity, name)) {
                        toast("'$name' group created — add its apps from the Home tab")
                    } else {
                        toast("The group already exists, Boss")
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
            text = "$label — $currentLabel  (tap to change)"
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
