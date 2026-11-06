package com.spdev22.mycare.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import com.spdev22.mycare.data.BackupManager
import com.spdev22.mycare.data.LogStore
import org.json.JSONObject

/**
 * Backup / Restore (0.2.9) — System tab entry.
 *
 * Export: everything that makes My Care yours (app groups,
 * settings, workflow switches, Editor edits, your AI-made
 * workflows — auto-reply ones included) goes into ONE JSON file
 * the user picks via the system file picker (SAF). The Gemini API
 * key is EXCLUDED unless the "Include Gemini API key" checkbox is
 * ticked; the warning above it says plainly what that means.
 *
 * Import: the chosen file is parsed and validated completely
 * before anything changes (BackupManager). Success shows a
 * summary with counts; a bad file shows an honest error and
 * leaves the app exactly as it was. Both operations are logged.
 */
class BackupRestoreActivity : Activity() {

    private lateinit var includeKeyBox: CheckBox
    private lateinit var statusView: TextView
    private var pendingIncludeKey = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.root(this)
        root.addView(UiKit.title(this, "Backup / Restore"))
        root.addView(
            UiKit.subtitle(
                this,
                "One file with everything that makes My Care yours, " +
                    "Boss — useful before a phone change, a reinstall, " +
                    "or just for safety."
            )
        )

        // ---------- Export ----------
        root.addView(UiKit.section(this, "Export"))
        val exportCard = UiKit.card(this)
        exportCard.addView(
            UiKit.body(
                this,
                "Saves: app groups, all settings, workflow On/Off " +
                    "switches, your workflow edits, and your AI-made " +
                    "workflows (auto-reply ones included)."
            )
        )
        includeKeyBox = CheckBox(this).apply {
            text = "Include Gemini API key"
            textSize = 15f
            setTextColor(UiKit.INK)
            UiKit.tintCheckBox(this@BackupRestoreActivity, this)
            isChecked = false
        }
        exportCard.addView(includeKeyBox)
        exportCard.addView(
            UiKit.small(
                this,
                "⚠️ Warning: if ticked, the backup file will contain " +
                    "your Gemini API key in plain text — anyone who " +
                    "gets the file gets your key. Leave it off unless " +
                    "you keep the file somewhere only you can reach."
            ).apply { setTextColor(UiKit.DANGER) }
        )
        val exportBtn = UiKit.primaryButton(this, "💾 Export backup (.json)")
        exportBtn.setOnClickListener { launchExport() }
        exportCard.addView(exportBtn)
        root.addView(exportCard)

        // ---------- Import ----------
        root.addView(UiKit.section(this, "Import"))
        val importCard = UiKit.card(this)
        importCard.addView(
            UiKit.body(
                this,
                "Pick a My Care backup file. Everything inside it " +
                    "REPLACES the current groups, settings, switches, " +
                    "edits and AI workflows. The file is checked " +
                    "fully first — if it is damaged or not a My Care " +
                    "backup, nothing changes."
            )
        )
        val importBtn = UiKit.primaryButton(this, "📂 Import backup")
        importBtn.setOnClickListener { launchImport() }
        importCard.addView(importBtn)
        root.addView(importCard)

        statusView = UiKit.small(this, "")
        root.addView(statusView)

        setContentView(UiKit.scroll(this, root))
    }

    private fun launchExport() {
        pendingIncludeKey = includeKeyBox.isChecked
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "my_care_backup.json")
        }
        try {
            startActivityForResult(intent, REQ_EXPORT)
        } catch (e: Exception) {
            toast("No file app found for saving, Boss")
            LogStore.log(this, "Backup export FAILED — no document provider: ${e.message}")
        }
    }

    private fun launchImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        try {
            startActivityForResult(intent, REQ_IMPORT)
        } catch (e: Exception) {
            toast("No file app found for opening, Boss")
            LogStore.log(this, "Backup import FAILED — no document provider: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_EXPORT -> writeBackup(uri)
            REQ_IMPORT -> readBackup(uri)
        }
    }

    private fun writeBackup(uri: Uri) {
        // File IO on a background thread — Go phone, no main-thread work
        Thread {
            try {
                val json = BackupManager.buildBackup(this, pendingIncludeKey)
                val bytes = json.toString(2).toByteArray(Charsets.UTF_8)
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("could not open the chosen file")
                LogStore.log(
                    this,
                    "Backup exported (${bytes.size} bytes, " +
                        "Gemini key ${if (pendingIncludeKey) "INCLUDED" else "excluded"})"
                )
                runOnUiThread {
                    statusView.text = "✅ Backup saved. Keep the file somewhere safe, Boss."
                    toast("Backup saved ✅")
                }
            } catch (e: Exception) {
                LogStore.log(this, "Backup export FAILED: ${e.message}")
                runOnUiThread {
                    statusView.text = "❌ Backup failed: ${e.message}"
                    toast("Backup failed, Boss — the reason is written below")
                }
            }
        }.start()
    }

    private fun readBackup(uri: Uri) {
        Thread {
            val outcome: BackupManager.ImportResult = try {
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalStateException("could not read the chosen file")
                val root = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw IllegalStateException("the file is not valid JSON")
                }
                BackupManager.applyBackup(this, root)
            } catch (e: Exception) {
                BackupManager.ImportResult.Error(
                    "The backup could not be restored: ${e.message}"
                )
            }
            runOnUiThread {
                when (outcome) {
                    is BackupManager.ImportResult.Ok -> {
                        val summary = outcome.summary.describe()
                        LogStore.log(this, "Backup imported: $summary")
                        statusView.text = "✅ Backup restored: $summary."
                        AlertDialog.Builder(this)
                            .setTitle("Backup restored ✅")
                            .setMessage("Restored: $summary.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    is BackupManager.ImportResult.Error -> {
                        LogStore.log(this, "Backup import FAILED: ${outcome.message}")
                        statusView.text = "❌ ${outcome.message} Nothing was changed."
                        AlertDialog.Builder(this)
                            .setTitle("Backup not restored")
                            .setMessage(outcome.message + "\n\nNothing was changed.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_EXPORT = 41
        private const val REQ_IMPORT = 42
    }
}
