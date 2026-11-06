package com.spdev22.mycare.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.spdev22.mycare.data.IntruderCaptures
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures (0.2.8) — the intruder photos taken by the failed-unlock
 * upgrade, newest first. The images live in app-private storage
 * (see IntruderCaptures); only the last 10 are kept. Decoding is
 * downsampled so even ten photos stay gentle on a 2 GB Go phone.
 */
class CapturesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.root(this)
        setContentView(UiKit.scroll(this, root))

        root.addView(UiKit.title(this, "Intruder Captures 📸"))
        root.addView(
            UiKit.subtitle(
                this,
                "Photos captured by the front camera when the wrong " +
                    "PIN/password was entered repeatedly. Stored only " +
                    "inside this app on your phone — the last " +
                    "${IntruderCaptures.MAX} are kept."
            )
        )

        val files = IntruderCaptures.list(this)
        if (files.isEmpty()) {
            val card = UiKit.card(this)
            card.addView(
                UiKit.body(
                    this,
                    "No captures yet. When the Intruder Alert fires " +
                        "with a photo, it will appear here.",
                    15f
                )
            )
            root.addView(card)
            return
        }
        val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.US)
        for (file in files) {
            val card = UiKit.card(this)
            card.addView(
                UiKit.body(this, fmt.format(Date(file.lastModified())), 14.5f).apply {
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            )
            val bmp = try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                val img = ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = UiKit.dp(this@CapturesActivity, 8) }
                }
                card.addView(img)
            } else {
                card.addView(UiKit.small(this, "This photo could not be decoded."))
            }
            root.addView(card)
        }
    }
}
