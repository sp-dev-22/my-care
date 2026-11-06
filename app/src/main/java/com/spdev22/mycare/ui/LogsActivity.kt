package com.spdev22.mycare.ui

import android.app.Activity
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
                "My Care ke saare kaam yahan record hote hain — " +
                    "workflow fire, button actions, errors."
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

        val clearBtn = UiKit.secondaryButton(this, "Logs saaf karo")
        clearBtn.setOnClickListener {
            LogStore.clear(this)
            LogStore.log(this, "Logs saaf kiye gaye")
            refresh()
            Toast.makeText(this, "Logs saaf ho gaye", Toast.LENGTH_SHORT).show()
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
