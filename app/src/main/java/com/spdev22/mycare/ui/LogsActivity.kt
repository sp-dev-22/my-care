package com.spdev22.mycare.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.spdev22.mycare.data.LogStore

/**
 * Logs / Console screen — My Care ne kab kaun sa workflow fire kiya,
 * kaun sa button daba, kya fail hua — sab yahan dikhta hai.
 * Testing ke time yahi screen sabse kaam ki hai, Boss.
 *
 * 0.2.5: UiKit wala naya look — log ek safed card ke andar.
 */
class LogsActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.root(this)
        root.addView(UiKit.title(this, "Logs / Console"))
        root.addView(
            UiKit.subtitle(
                this,
                "Everything My Care does is recorded here — " +
                    "workflow fires, button actions, errors."
            )
        )

        logView = TextView(this).apply {
            textSize = 13f
            setTextColor(UiKit.INK)
            movementMethod = ScrollingMovementMethod()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(logView)
        }
        val card = UiKit.card(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        card.addView(logScroll)
        root.addView(card)

        val refreshBtn = UiKit.primaryButton(this, "Refresh")
        refreshBtn.setOnClickListener { refresh() }
        root.addView(refreshBtn)

        // 0.2.9: poori log ek tap me clipboard par — kahin paste
        // karke bhejni ho to aasaan.
        val copyBtn = UiKit.secondaryButton(this, "📋 Copy logs")
        copyBtn.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(
                ClipData.newPlainText("My Care logs", LogStore.readAll(this))
            )
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        root.addView(copyBtn)

        val clearBtn = UiKit.secondaryButton(this, "Clear logs")
        clearBtn.setOnClickListener {
            LogStore.clear(this)
            LogStore.log(this, "Logs cleared")
            refresh()
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }
        root.addView(clearBtn)

        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (::logView.isInitialized) {
            logView.text = LogStore.readAll(this)
        }
    }
}
