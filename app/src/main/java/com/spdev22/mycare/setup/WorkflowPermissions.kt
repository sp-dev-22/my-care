package com.spdev22.mycare.setup

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import com.spdev22.mycare.data.LogStore
import com.spdev22.mycare.engine.UsageTracker
import com.spdev22.mycare.engine.Workflow
import com.spdev22.mycare.receiver.CareDeviceAdminReceiver
import com.spdev22.mycare.service.CareAccessibilityService
import com.spdev22.mycare.service.CareNotificationListener
import com.spdev22.mycare.ui.UiKit

/**
 * WorkflowPermissions — 0.2.8: the per-workflow permission map.
 *
 * Every workflow's requirements are DERIVED from its trigger and
 * its actions (buttons + direct action):
 *   usage-based triggers / cache_over / unused_apps -> Usage Access
 *   screenshot_taken        -> Photos / Media read
 *   notification_posted     -> Notification Access
 *   auto_reply (direct)     -> Notification Access (0.2.9 — the reply
 *                              travels through the listener, so an
 *                              auto-reply workflow is listed here
 *                              explicitly, not only via its trigger)
 *   failed_unlock           -> Device Admin + Camera (for the photo)
 *   contact_saved           -> Contacts
 *   time                    -> Exact Alarm (Android 12+, where it applies)
 *   set_brightness          -> Modify system settings (WRITE_SETTINGS)
 *   go_home (button)        -> Accessibility
 *   lock_phone (button)     -> Device Admin
 *   popup bot type          -> Display over other apps (Overlay)
 *
 * Whenever a workflow is switched ON (Workflows list toggle, an AI
 * workflow that lands enabled, or the Editor's save), the app
 * checks this map and — for anything missing — prompts with
 * buttons that open the exact system settings screens (the same
 * intents the Setup Wizard uses). The Running Services page lists
 * the same missing permissions as ❌ warnings.
 */
object WorkflowPermissions {

    data class Requirement(
        val name: String,
        val isGranted: (Context) -> Boolean,
        val open: (Activity) -> Unit
    )

    // ---------------- the individual requirements ----------------

    private fun usageAccess() = Requirement(
        "Usage Access",
        { c -> UsageTracker.hasUsageAccess(c) }
    ) { a -> a.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }

    private fun mediaRead() = Requirement(
        "Photos / Media",
        { c ->
            val perm = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        }
    ) { a ->
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        a.requestPermissions(arrayOf(perm), 213)
    }

    private fun notificationAccess() = Requirement(
        "Notification Access",
        { c -> CareNotificationListener.isAccessGranted(c) }
    ) { a -> a.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

    private fun deviceAdmin() = Requirement(
        "Device Admin",
        { c ->
            val dpm = c.getSystemService(DevicePolicyManager::class.java)
            dpm?.isAdminActive(ComponentName(c, CareDeviceAdminReceiver::class.java)) == true
        }
    ) { a ->
        val admin = ComponentName(a, CareDeviceAdminReceiver::class.java)
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
        i.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "My Care needs Device Admin for Lock Phone and the Intruder Alert."
        )
        a.startActivity(i)
    }

    private fun camera() = Requirement(
        "Camera",
        { c -> c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
    ) { a -> a.requestPermissions(arrayOf(Manifest.permission.CAMERA), 214) }

    private fun contacts() = Requirement(
        "Contacts",
        { c -> c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED }
    ) { a -> a.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 215) }

    private fun writeSettings() = Requirement(
        "Modify system settings (Brightness)",
        { c -> Settings.System.canWrite(c) }
    ) { a ->
        val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        i.data = Uri.parse("package:" + a.packageName)
        a.startActivity(i)
    }

    private fun accessibility() = Requirement(
        "Accessibility",
        { c -> isAccessibilityOn(c) }
    ) { a -> a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

    private fun overlay() = Requirement(
        "Display over other apps (Popup)",
        { c -> Settings.canDrawOverlays(c) }
    ) { a ->
        val i = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + a.packageName)
        )
        a.startActivity(i)
    }

    private fun exactAlarm() = Requirement(
        "Exact Alarms",
        { c ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                true
            } else {
                try {
                    c.getSystemService(AlarmManager::class.java)
                        ?.canScheduleExactAlarms() ?: true
                } catch (e: Exception) {
                    true
                }
            }
        }
    ) { a ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val i = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                i.data = Uri.parse("package:" + a.packageName)
                a.startActivity(i)
            } catch (e: Exception) {
                a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:" + a.packageName)
                })
            }
        }
    }

    fun isAccessibilityOn(context: Context): Boolean {
        if (CareAccessibilityService.isRunning()) return true
        return try {
            val flat = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val mine = ComponentName(context, CareAccessibilityService::class.java)
            flat.split(":").any {
                it.equals(mine.flattenToString(), ignoreCase = true) ||
                    it.equals(mine.flattenToShortString(), ignoreCase = true)
            }
        } catch (e: Exception) {
            false
        }
    }

    fun isDeviceAdminOn(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return dpm?.isAdminActive(
            ComponentName(context, CareDeviceAdminReceiver::class.java)
        ) == true
    }

    // ---------------- the map ----------------

    /** All permissions a workflow needs, derived from trigger + actions. */
    fun requirementsFor(wf: Workflow): List<Requirement> {
        val out = LinkedHashMap<String, Requirement>()
        fun add(r: Requirement) {
            out[r.name] = r
        }
        when (wf.triggerType) {
            "app_hopping", "single_app_over", "continuous_use_over",
            "app_group_used", "cache_over", "unused_apps" -> add(usageAccess())
            "screenshot_taken" -> add(mediaRead())
            "notification_posted" -> add(notificationAccess())
            "failed_unlock" -> {
                add(deviceAdmin())
                add(camera())
            }
            "contact_saved" -> add(contacts())
            "time" -> add(exactAlarm())
        }
        if (wf.primaryAction == "set_brightness") add(writeSettings())
        // 0.2.9: auto_reply rides on the notification listener —
        // named explicitly so the requirement (and its ❌ warning)
        // survives even if the trigger mapping ever changes.
        if (wf.primaryAction == "auto_reply") add(notificationAccess())
        for (btn in wf.buttons) {
            when (btn.action) {
                "set_brightness" -> add(writeSettings())
                "go_home" -> add(accessibility())
                "lock_phone" -> add(deviceAdmin())
            }
        }
        if (wf.effectiveBotType == "popup") add(overlay())
        return out.values.toList()
    }

    /** The requirements that are NOT granted yet, in map order. */
    fun missingFor(context: Context, wf: Workflow): List<Requirement> =
        requirementsFor(wf).filter { !it.isGranted(context) }

    // ---------------- the prompt ----------------

    /**
     * If the workflow is missing permissions, show a prompt listing
     * them (❌) with a button per permission that opens the exact
     * system settings screen. @return true when a prompt was shown.
     */
    fun promptIfMissing(
        activity: Activity,
        wf: Workflow,
        onDone: () -> Unit = {}
    ): Boolean {
        val missing = missingFor(activity, wf)
        if (missing.isEmpty()) {
            onDone()
            return false
        }
        LogStore.log(
            activity,
            "Workflow [${wf.id}] is ON but missing permissions: " +
                missing.joinToString(", ") { it.name }
        )
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiKit.dp(activity, 18)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        box.addView(
            UiKit.body(
                activity,
                "'${wf.name}' is ON, but it still needs these permissions to actually work:",
                15f
            )
        )
        box.addView(UiKit.gap(activity, 6))
        for (req in missing) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = UiKit.dp(activity, 6) }
            }
            val label = UiKit.body(activity, "❌ ${req.name}", 15f).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            row.addView(label)
            val btn = UiKit.primaryButton(activity, "Open settings").apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    try {
                        req.open(activity)
                    } catch (e: Exception) {
                        LogStore.log(
                            activity,
                            "Permission screen failed to open (${req.name}): ${e.message}"
                        )
                    }
                }
            }
            row.addView(btn)
            box.addView(row)
        }
        AlertDialog.Builder(activity)
            .setTitle("Permissions needed")
            .setView(box)
            .setNegativeButton("Later", null)
            .setOnDismissListener { onDone() }
            .show()
        return true
    }
}
