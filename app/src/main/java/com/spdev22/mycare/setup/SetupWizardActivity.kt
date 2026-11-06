package com.spdev22.mycare.setup

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.spdev22.mycare.engine.UsageTracker
import com.spdev22.mycare.receiver.CareDeviceAdminReceiver
import com.spdev22.mycare.service.CareNotificationListener
import com.spdev22.mycare.ui.UiKit

/**
 * Setup Wizard (D11) — every permission one by one, each with its
 * REASON. 0.2.8: every step shows ✅ (granted) or ❌ (not granted)
 * in front of its name, computed LIVE — the screen rebuilds on
 * resume, so returning from a system settings screen updates the
 * markers immediately. New step 11: Camera, for the Intruder
 * Alert's photo capture.
 */
class SetupWizardActivity : Activity() {

    private data class PermItem(
        val title: String,
        val why: String,
        val granted: () -> Boolean,
        val statusText: (() -> String)? = null,
        val open: (Activity) -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // Returning from a permission screen -> fresh markers/status
        buildUi()
    }

    private fun buildUi() {
        val root = UiKit.root(this)
        setContentView(UiKit.scroll(this, root))

        root.addView(UiKit.title(this, "Setup Wizard"))
        root.addView(
            UiKit.subtitle(
                this,
                "Boss, My Care needs a few permissions to look after " +
                    "you. Grant them one by one — the reason for each " +
                    "is written with it. ✅ = granted, ❌ = not granted " +
                    "yet. Without them, the workflows that depend on " +
                    "them will stay on paper only."
            )
        )

        for (item in items()) {
            val card = UiKit.card(this)
            val marker = if (item.granted()) "✅" else "❌"
            card.addView(UiKit.body(this, "$marker ${item.title}", 16f).apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            card.addView(UiKit.small(this, item.why))
            item.statusText?.let { status ->
                card.addView(UiKit.body(this, status(), 14f).apply {
                    setPadding(0, UiKit.dp(this@SetupWizardActivity, 4), 0, 0)
                })
            }
            card.addView(UiKit.gap(this, 6))
            val btn = UiKit.primaryButton(this, "Set it up")
            btn.setOnClickListener {
                try {
                    item.open(this@SetupWizardActivity)
                } catch (e: Exception) {
                    Toast.makeText(
                        this@SetupWizardActivity,
                        "This setting did not open on this phone — please grant it manually from Settings",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            card.addView(btn)
            root.addView(card)
        }

        root.addView(UiKit.small(this, "Note: no permission is forced. Whatever you " +
            "don't grant, its workflows stay off — the rest of the app keeps working."))
    }

    private fun runtimeGranted(perm: String): Boolean =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun items(): List<PermItem> = listOf(
        PermItem(
            "1. Usage Access",
            "Which app ran for how long — this permission is the life " +
                "of App Groups, boredom detection and the usage triggers.",
            granted = { UsageTracker.hasUsageAccess(this@SetupWizardActivity) },
            statusText = {
                if (UsageTracker.hasUsageAccess(this@SetupWizardActivity)) {
                    "Status: GRANTED ✅ — Cache Cleanup, Unused Apps and all usage checks can run."
                } else {
                    "Status: NOT GRANTED ❌ — without it, Cache Cleanup, Unused Apps " +
                        "and the usage triggers will stay OFF. Tap the button below " +
                        "and give My Care Usage Access."
                }
            }
        ) { a ->
            a.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        },
        PermItem(
            "2. Notifications",
            "Every care suggestion arrives as a notification — if this " +
                "is off, My Care goes silent.",
            granted = {
                if (Build.VERSION.SDK_INT >= 33) {
                    runtimeGranted(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.areNotificationsEnabled() ?: false
                }
            }
        ) { a ->
            if (Build.VERSION.SDK_INT >= 33) {
                a.requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            } else {
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                i.putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)
                a.startActivity(i)
            }
        },
        PermItem(
            "3. Popup (Display over other apps)",
            "Flows with more than 3 buttons open in a Popup — for that, " +
                "the app needs permission to appear on top of the screen.",
            granted = { Settings.canDrawOverlays(this@SetupWizardActivity) }
        ) { a ->
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + a.packageName)
            )
            a.startActivity(i)
        },
        PermItem(
            "4. Device Admin",
            "For the Lock Phone button and the Intruder Alert (wrong " +
                "password attempts). Note: your PIN can never be read " +
                "by us — Android gives it to no one.",
            granted = { WorkflowPermissions.isDeviceAdminOn(this@SetupWizardActivity) }
        ) { a ->
            val admin = ComponentName(a, CareDeviceAdminReceiver::class.java)
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            i.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "My Care needs Device Admin for Lock Phone and the wrong-password Intruder Alert."
            )
            a.startActivity(i)
        },
        PermItem(
            "5. Accessibility",
            "For the Close App button — with it, My Care can send you " +
                "straight to the Home screen. In Settings, inside " +
                "'Installed services', you will find a service named " +
                "'My Care' — turn it ON. Note: this service does NOT " +
                "read your screen's content.",
            granted = { WorkflowPermissions.isAccessibilityOn(this@SetupWizardActivity) }
        ) { a ->
            a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        PermItem(
            "6. Contacts",
            "So that when a new contact is saved, you get the 'Open on " +
                "WhatsApp' suggestion — only the news of the new " +
                "contact, never your whole address book.",
            granted = { runtimeGranted(Manifest.permission.READ_CONTACTS) }
        ) { a ->
            a.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 102)
        },
        PermItem(
            "7. Photos / Media",
            "For the Screenshot Cleanup flow — My Care watches the " +
                "Screenshots folder, tells you about a new screenshot, " +
                "and deletes it later only when you say so.",
            granted = {
                val perm = if (Build.VERSION.SDK_INT >= 33) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                runtimeGranted(perm)
            }
        ) { a ->
            val perm = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            a.requestPermissions(arrayOf(perm), 103)
        },
        PermItem(
            "8. Remove battery restrictions",
            "Infinix's system puts apps to sleep to save battery — " +
                "then notifications stop. My Care must be kept out of " +
                "that restriction.",
            granted = {
                val pm = getSystemService(PowerManager::class.java)
                pm?.isIgnoringBatteryOptimizations(packageName) ?: false
            }
        ) { a ->
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:" + a.packageName)
            a.startActivity(i)
        },
        PermItem(
            "9. System Settings (Change brightness)",
            "For set_brightness actions — like the evening workflow " +
                "that dims the screen. This is a special SYSTEM-LEVEL " +
                "grant: on the screen that opens, turn ON 'Allow " +
                "modifying system settings' for My Care. Without it, " +
                "brightness actions only inform you, they cannot act.",
            granted = { Settings.System.canWrite(this@SetupWizardActivity) },
            statusText = {
                if (Settings.System.canWrite(this@SetupWizardActivity)) {
                    "Status: GRANTED ✅ — set_brightness actions will work."
                } else {
                    "Status: NOT GRANTED ❌ — set_brightness actions cannot " +
                        "change brightness yet. Tap the button below and " +
                        "grant My Care this permission."
                }
            }
        ) { a ->
            val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            i.data = Uri.parse("package:" + a.packageName)
            a.startActivity(i)
        },
        PermItem(
            "10. Notification Access",
            "For the notification_posted trigger — running a workflow " +
                "when another app's notification arrives. On the screen " +
                "that opens, turn My Care ON. A firm promise: My Care " +
                "never saves any notification's text — only a matching " +
                "workflow fires, and without this grant the trigger " +
                "stays completely off.",
            granted = { CareNotificationListener.isAccessGranted(this@SetupWizardActivity) },
            statusText = {
                if (CareNotificationListener.isAccessGranted(this@SetupWizardActivity)) {
                    "Status: GRANTED ✅ — notification_posted workflows can run."
                } else {
                    "Status: NOT GRANTED ❌ — the notification_posted trigger is " +
                        "OFF right now. Tap the button below and turn My Care " +
                        "ON in the list that opens."
                }
            }
        ) { a ->
            a.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        PermItem(
            "11. Camera (Intruder photo)",
            "NEW in 0.2.8: when someone enters the wrong PIN/password " +
                "repeatedly, the Intruder Alert captures a photo with " +
                "the FRONT camera and attaches it to the alert. The " +
                "photo is taken only at that moment, is stored only " +
                "inside the app on this phone, and you can see all " +
                "captures under System -> Running Services. Without " +
                "this permission you still get the text alert — just " +
                "no photo.",
            granted = { runtimeGranted(Manifest.permission.CAMERA) }
        ) { a ->
            a.requestPermissions(arrayOf(Manifest.permission.CAMERA), 104)
        }
    )
}
