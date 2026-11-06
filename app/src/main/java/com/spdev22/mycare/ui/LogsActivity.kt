package com.spdev22.mycare.ui

import android.app.Activity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.spdev22.mycare.data.LogStore

/**
 * Logs / Console screen — My Care ne kab kaun sa workflow fire kiya,
 * kaun sa button daba, kya fail hua — sab yahan dikhta hai.
 * Testing ke time yahi screen sabse kaam ki hai, Boss.
 */
class LogsActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "Logs / Console"
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = "My Care ke saare kaam yahan record hote hain — " +
                "workflow fire, button actions, errors."
            setPadding(0, 6, 0, 12)
        })

        logView = TextView(this).apply {
            textSize = 13f
            movementMethod = ScrollingMovementMethod()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(logView)
        }
        root.addView(scroll)

        val refreshBtn = Button(this).apply { text = "Refresh" }
        refreshBtn.setOnClickListener { refresh() }
        root.addView(refreshBtn)

        val clearBtn = Button(this).apply { text = "Logs saaf karo" }
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
