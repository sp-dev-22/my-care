package com.spdev22.mycare.engine

/** Workflow Format v1 ka ek button. */
data class WfButton(
    val label: String,
    val action: String,
    val args: Map<String, String> = emptyMap(),
    val next: String? = null
)

/** Multi-step flow ka ek extra step (jaise screenshot flow ka step 2). */
data class WfStep(
    val id: String,
    val title: String,
    val text: String,
    val buttons: List<WfButton> = emptyList()
)

/**
 * Ek workflow record — Workflow Format v1 (PLAN.md).
 * id, trigger, bot_type, message, buttons, steps, settings.
 */
data class Workflow(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val triggerType: String,
    val botType: String,
    val title: String,
    val text: String,
    /** Top-level action (jaise battery workflow ka set_alarm), ho to uska type */
    val primaryAction: String? = null,
    val buttons: List<WfButton> = emptyList(),
    val steps: List<WfStep> = emptyList(),
    val settings: Map<String, String> = emptyMap()
) {
    /**
     * D7 ka 3-button niyam: notification me 3 se zyada buttons nahi aa sakte.
     * Zyada ho to effective type apne aap "popup" ho jata hai.
     */
    val effectiveBotType: String
        get() = if (botType == "notification" && buttons.size > 3) "popup" else botType
}
