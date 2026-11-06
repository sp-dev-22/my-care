package com.spdev22.mycare.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.spdev22.mycare.setup.SetupWizardActivity

/**
 * System tab (0.2.8) — the toolbox hub: Setup Wizard (permissions),
 * Running Services (what is actually live right now) and the
 * Logs / Console. Everything that used to hang off the old main
 * screen's button stack lives here (and in the tab bar), so
 * nothing became unreachable in the tab restructure.
 */
class SystemHubActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.root(this)
        UiKit.tabScreen(this, root, UiKit.TAB_SYSTEM)

        root.addView(UiKit.title(this, "System"))
        root.addView(
            UiKit.subtitle(
                this,
                "Permissions, live services and logs — the toolbox " +
                    "side of My Care."
            )
        )

        val wizardBtn = UiKit.primaryButton(this, "🧭 Setup Wizard — Permissions")
        wizardBtn.setOnClickListener {
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }
        root.addView(wizardBtn)

        val runningBtn = UiKit.primaryButton(this, "🟢 Running Services")
        runningBtn.setOnClickListener {
            startActivity(Intent(this, RunningServicesActivity::class.java))
        }
        root.addView(runningBtn)

        val logsBtn = UiKit.primaryButton(this, "📜 Logs / Console")
        logsBtn.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
        root.addView(logsBtn)

        root.addView(
            UiKit.small(
                this,
                "Tip: Running Services shows only what is genuinely " +
                    "active right now — timers with their next fire " +
                    "time, the screenshot watch, the cache check, the " +
                    "intruder guard and any missing-permission warnings."
            )
        )
    }
}
