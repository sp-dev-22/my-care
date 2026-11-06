package com.spdev22.mycare.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import com.spdev22.mycare.data.CareSettings
import com.spdev22.mycare.data.IntruderCaptures
import com.spdev22.mycare.data.WorkflowPrefs
import com.spdev22.mycare.engine.DirectActions
import com.spdev22.mycare.engine.Workflow
import com.spdev22.mycare.engine.WorkflowEngine
import com.spdev22.mycare.notify.DailyScheduler
import com.spdev22.mycare.receiver.CareDeviceAdminReceiver
import com.spdev22.mycare.receiver.DeleteReceiver
import com.spdev22.mycare.service.CareNotificationListener
import com.spdev22.mycare.service.CareService
import com.spdev22.mycare.setup.WorkflowPermissions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Running Services (0.2.8) — one page that shows every ACTIVE
 * mechanism with a live status line, each derived from REAL state
 * (prefs stamps, engine/service flags, system grants) — never from
 * guesses. Workflows that are OFF do not appear as running; they
 * are only counted in the summary line at the bottom.
 *
 * It also surfaces the permission map: any ON workflow that is
 * missing a permission gets a ❌ warning row here.
 */
class RunningServicesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        buildUi()
    }

    private fun row(root: LinearLayout, title: String, status: String) {
        val card = UiKit.card(this)
        card.addView(UiKit.body(this, title, 15.5f).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        card.addView(UiKit.small(this, status))
        root.addView(card)
    }

    private fun buildUi() {
        val root = UiKit.root(this)
        setContentView(UiKit.scroll(this, root))

        root.addView(UiKit.title(this, "Running Services"))
        root.addView(
            UiKit.subtitle(
                this,
                "Everything that is genuinely live right now, Boss — " +
                    "each line read from the app's real state."
            )
        )

        val pack = WorkflowEngine.loadPack(this)
        fun enabled(wf: Workflow) = WorkflowPrefs.isEnabled(this, wf)
        val onWorkflows = pack.filter { enabled(it) }
        val offCount = pack.size - onWorkflows.size

        // ---- Care foreground service ----
        row(
            root, "Care foreground service",
            if (CareService.isRunning) {
                "✅ Running — the poll engine checks triggers every 20 seconds."
            } else {
                "❌ Stopped — no trigger can fire. Open the Home tab and tap 'Start Care Service'."
            }
        )

        // ---- Screenshot tracking (folder watch) ----
        val shotWf = onWorkflows.firstOrNull { it.triggerType == "screenshot_taken" }
        if (shotWf != null) {
            val lastScan = CareSettings.stateLong(this, "state_shot_last_scan")
            val tracked = CareSettings.stateLong(this, "state_shot_tracked")
            val scanText = if (lastScan > 0) {
                "last scan ${fmtTime(lastScan)}, $tracked files being watched"
            } else {
                "no scan recorded yet (service may have just started)"
            }
            row(root, "Screenshot tracking (folder watch)", "✅ ON — $scanText.")
        }

        // ---- Next pending screenshot delete ----
        val pending = DeleteReceiver.loadPending(this)
        if (pending.isNotEmpty()) {
            val next = pending.minByOrNull { it.due }!!
            row(
                root, "Next screenshot delete",
                "🕒 '${next.label}' is scheduled for ${fmtDateTime(next.due)} " +
                    "(${pending.size} delete(s) pending in total)."
            )
        }

        // ---- Daily timers (all enabled 'time' workflows) ----
        val timeWorkflows = onWorkflows.filter { it.triggerType == "time" }
        for (wf in timeWorkflows) {
            val hhmm = DailyScheduler.resolveTime(this, wf)
            val next = DailyScheduler.nextOccurrence(hhmm)
            row(
                root, "Daily timer — ${wf.name}",
                "✅ ON — fires daily at $hhmm; next fire ${fmtDateTime(next)}."
            )
        }

        // ---- Interval timers (Water Reminder and friends) ----
        val intervalWorkflows = onWorkflows.filter { it.triggerType == "interval" }
        for (wf in intervalWorkflows) {
            val hours = wf.triggerParams["hours"]?.toLongOrNull()
                ?: wf.settings["hours"]?.toLongOrNull() ?: 2L
            val last = CareSettings.stateLong(this, "state_interval_${wf.id}")
            val nextText = if (last > 0) {
                "next around ${fmtDateTime(last + hours * 60 * 60_000L)} " +
                    "(stays quiet 23:00–07:00)"
            } else {
                "first fire after the interval from service start"
            }
            row(
                root, "Interval timer — ${wf.name}",
                "✅ ON — every $hours hour(s); $nextText."
            )
        }

        // ---- Cache check ----
        val cacheWf = onWorkflows.firstOrNull { it.triggerType == "cache_over" }
        if (cacheWf != null) {
            val lastEval = CareSettings.stateLong(this, "state_cache_eval")
            val lastResult = CareSettings.stateStr(this, "state_cache_last_result")
                ?: "no evaluation recorded yet"
            val evalText = if (lastEval > 0) {
                "last evaluation ${fmtDateTime(lastEval)} — $lastResult; " +
                    "next due around ${fmtDateTime(lastEval + 4 * 60 * 60_000L)}"
            } else {
                "no evaluation yet — $lastResult"
            }
            row(root, "Cache check", "✅ ON — $evalText.")
        }

        // ---- Notification listener ----
        val notifWorkflows = onWorkflows.filter { it.triggerType == "notification_posted" }
        val listenerOn = CareNotificationListener.isAccessGranted(this)
        row(
            root, "Notification listener",
            when {
                listenerOn && notifWorkflows.isNotEmpty() ->
                    "✅ Connected — ${notifWorkflows.size} notification-posted " +
                        "workflow(s) active: " +
                        notifWorkflows.joinToString(", ") { it.name } + "."
                listenerOn ->
                    "✅ Connected (Notification Access granted) — no " +
                        "notification-posted workflow is ON right now."
                notifWorkflows.isNotEmpty() ->
                    "❌ NOT connected — ${notifWorkflows.size} workflow(s) need it: " +
                        notifWorkflows.joinToString(", ") { it.name } +
                        ". Grant Notification Access in the Setup Wizard."
                else ->
                    "⚪ Not connected, and no notification-posted workflow is ON — nothing is missing."
            }
        )

        // ---- Auto-reply (0.2.9 consistency upgrade) ----
        // Count + state of every ON workflow whose direct action is
        // auto_reply. These reply from Boss's own accounts, so a
        // silent failure here matters more than anywhere else: if
        // any is ON while the listener is not connected, that is a
        // hard ❌, never a quiet line.
        val autoReplyOn = onWorkflows.filter { DirectActions.isAutoReply(it) }
        row(
            root, "Auto-reply",
            when {
                autoReplyOn.isEmpty() ->
                    "⚪ No auto-reply workflow is ON — nothing is being " +
                        "replied to automatically."
                listenerOn ->
                    "✅ ${autoReplyOn.size} auto-reply workflow(s) ON — " +
                        "replies go out automatically via: " +
                        autoReplyOn.joinToString(", ") { it.name } + "."
                else ->
                    "❌ ${autoReplyOn.size} auto-reply workflow(s) ON but the " +
                        "notification listener is NOT connected — auto-reply " +
                        "cannot work: " +
                        autoReplyOn.joinToString(", ") { it.name } +
                        ". Grant Notification Access in the Setup Wizard."
            }
        )

        // ---- Accessibility ----
        row(
            root, "Accessibility service (Close App / Home actions)",
            if (WorkflowPermissions.isAccessibilityOn(this)) {
                "✅ ON"
            } else {
                "❌ OFF — 'go_home' buttons cannot work. Enable 'My Care' under " +
                    "Settings → Accessibility → Installed services (via the Setup Wizard)."
            }
        )

        // ---- Intruder guard ----
        val adminOn = WorkflowPermissions.isDeviceAdminOn(this)
        val failCount = CareSettings.stateLong(
            this, CareDeviceAdminReceiver.KEY_FAIL_COUNT
        ).toInt()
        val threshold = CareSettings.intVal(
            this, CareSettings.KEY_INTRUDER_THRESHOLD,
            CareSettings.DEFAULT_INTRUDER_THRESHOLD
        )
        val captures = IntruderCaptures.count(this)
        val lastCapture = CareSettings.stateLong(this, "state_last_capture")
        val captureText = when {
            captures > 0 && lastCapture > 0 ->
                " $captures photo capture(s) stored; latest ${fmtDateTime(lastCapture)}."
            captures > 0 -> " $captures photo capture(s) stored."
            else -> " No photos captured yet."
        }
        row(
            root, "Intruder guard (failed unlock)",
            if (adminOn) {
                "✅ Device Admin ON — alert + front-camera photo after " +
                    "$threshold wrong attempt(s) within 10 minutes. " +
                    "Current window count: $failCount.$captureText"
            } else {
                "❌ Device Admin OFF — failed-unlock detection cannot run. " +
                    "Grant it in the Setup Wizard.$captureText"
            }
        )
        if (captures > 0) {
            val viewBtn = UiKit.primaryButton(this, "📸 View intruder captures ($captures)")
            viewBtn.setOnClickListener {
                startActivity(Intent(this, CapturesActivity::class.java))
            }
            root.addView(viewBtn)
        }

        // ---- Missing-permission warnings (the 0.2.8 permission map) ----
        val warnings = mutableListOf<String>()
        for (wf in onWorkflows) {
            val missing = WorkflowPermissions.missingFor(this, wf)
            if (missing.isNotEmpty()) {
                warnings.add(
                    "❌ ${wf.name} — missing: " +
                        missing.joinToString(", ") { it.name }
                )
            }
        }
        if (warnings.isNotEmpty()) {
            root.addView(UiKit.section(this, "Missing permissions"))
            val card = UiKit.card(this)
            for (w in warnings) {
                card.addView(UiKit.body(this, w, 14.5f).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = UiKit.dp(this@RunningServicesActivity, 6) }
                })
            }
            card.addView(
                UiKit.small(
                    this,
                    "Fix them from the Setup Wizard (System tab) — or switch the workflow ON again in the Workflows tab to get the guided prompt."
                )
            )
            root.addView(card)
        }

        // ---- Summary ----
        root.addView(
            UiKit.small(
                this,
                "Summary: ${onWorkflows.size} workflow(s) ON, $offCount OFF. " +
                    "OFF workflows never appear above as running."
            )
        )
    }

    private fun fmtTime(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

    private fun fmtDateTime(ms: Long): String =
        SimpleDateFormat("dd MMM, HH:mm", Locale.US).format(Date(ms))
}
