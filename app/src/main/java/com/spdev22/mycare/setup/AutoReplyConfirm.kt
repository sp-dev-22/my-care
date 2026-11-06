package com.spdev22.mycare.setup

import android.app.Activity
import android.app.AlertDialog
import com.spdev22.mycare.engine.DirectActions
import com.spdev22.mycare.engine.Workflow

/**
 * AutoReplyConfirm — 0.2.9: the explicit safety confirmation for
 * `auto_reply` workflows.
 *
 * An auto-reply workflow sends messages from the user's own
 * accounts (e.g. WhatsApp) without him tapping anything, so it
 * must never be switched ON silently. Every enable point — the
 * Workflows-list toggle, an AI workflow that lands enabled, and
 * the Editor's save on an already-ON workflow — routes through
 * here first: the warning is shown, and the caller proceeds ONLY
 * when the user taps "Yes, turn it on".
 */
object AutoReplyConfirm {

    const val WARNING =
        "This workflow will automatically send replies from your account."

    /** True when this workflow would auto-reply once it is ON. */
    fun appliesTo(wf: Workflow): Boolean = DirectActions.isAutoReply(wf)

    /**
     * Show the confirmation for [wf] and run [onConfirmed] only if
     * the user agrees; [onCancelled] runs when he declines (Cancel
     * button or dismissing the dialog). When the workflow has no
     * auto_reply action, [onConfirmed] runs immediately (no dialog).
     */
    fun confirmIfNeeded(
        activity: Activity,
        wf: Workflow,
        onCancelled: () -> Unit = {},
        onConfirmed: () -> Unit
    ) {
        if (!appliesTo(wf)) {
            onConfirmed()
            return
        }
        var decided = false
        AlertDialog.Builder(activity)
            .setTitle("Turn on auto-reply?")
            .setMessage("$WARNING\n\nWorkflow: '${wf.name}'")
            .setPositiveButton("Yes, turn it on") { _, _ ->
                decided = true
                onConfirmed()
            }
            .setNegativeButton("Cancel") { _, _ ->
                decided = true
                onCancelled()
            }
            .setOnCancelListener {
                if (!decided) onCancelled()
            }
            .show()
    }
}
