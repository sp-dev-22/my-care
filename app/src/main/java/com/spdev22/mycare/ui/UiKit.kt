package com.spdev22.mycare.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * UiKit — 0.2.5 ka shared "minimal, beautiful" design kit.
 *
 * Ek hi shaant palette har screen par: halki si hari-safed
 * background, safed rounded cards, gehara teal (primary) aur
 * garam amber (accent). Framework-only — koi library nahi,
 * Go phone par bojh bhi nahi. Saari screens yahi helpers
 * istemal karti hain taaki poora app ek jaisa dikhe.
 */
object UiKit {

    // ---------- palette ----------
    val BG = Color.rgb(243, 246, 244)
    val CARD = Color.WHITE
    val INK = Color.rgb(23, 35, 32)
    val MUTED = Color.rgb(94, 107, 102)
    val TEAL = Color.rgb(0, 105, 92)
    val TEAL_DARK = Color.rgb(0, 66, 57)
    val AMBER = Color.rgb(239, 108, 0)
    val BORDER = Color.rgb(207, 219, 214)
    val DANGER = Color.rgb(176, 38, 38)

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun rounded(
        context: Context,
        fill: Int,
        radiusDp: Int,
        strokeColor: Int? = null
    ): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(fill)
        d.cornerRadius = dp(context, radiusDp).toFloat()
        if (strokeColor != null) d.setStroke(dp(context, 1), strokeColor)
        return d
    }

    // ---------- screen ka dhancha ----------

    /** Screen ka jad (root) — shaant background + saans-daar padding. */
    fun root(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        val h = dp(context, 18)
        setPadding(h, dp(context, 20), h, dp(context, 24))
    }

    fun scroll(context: Context, child: View): ScrollView = ScrollView(context).apply {
        setBackgroundColor(BG)
        isFillViewport = true
        addView(child)
    }

    fun gap(context: Context, heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)
        )
    }

    // ---------- text ----------

    fun title(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 22f
        setTextColor(INK)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(context, 2))
    }

    fun subtitle(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(MUTED)
        setPadding(0, 0, 0, dp(context, 10))
    }

    /** Section heading — teal bold + upar saans, neeche chhoti amber lakeer. */
    fun section(context: Context, text: String): LinearLayout {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 18) }
        }
        wrap.addView(TextView(context).apply {
            this.text = text
            textSize = 15.5f
            setTextColor(TEAL_DARK)
            typeface = Typeface.DEFAULT_BOLD
        })
        wrap.addView(View(context).apply {
            setBackgroundColor(AMBER)
            layoutParams = LinearLayout.LayoutParams(dp(context, 34), dp(context, 3)).apply {
                topMargin = dp(context, 4)
                bottomMargin = dp(context, 8)
            }
        })
        return wrap
    }

    fun body(context: Context, text: String, sizeSp: Float = 15f): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(INK)
        }

    fun small(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(MUTED)
    }

    fun label(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(MUTED)
        setPadding(0, dp(context, 8), 0, dp(context, 3))
    }

    // ---------- cards ----------

    /** Safed rounded card — sections aur rows ka ghar. */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(context, CARD, 14, BORDER)
        val p = dp(context, 14)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(context, 10) }
    }

    /** Card jaisa dikhne wala dabba kisi bhi view ke peeche lagane ke liye. */
    fun cardBackground(context: Context) = rounded(context, CARD, 14, BORDER)

    // ---------- buttons ----------

    private fun baseButton(context: Context, text: String): Button = Button(context).apply {
        this.text = text
        textSize = 15f
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(context, 8) }
        minHeight = dp(context, 48)
        setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
    }

    /** Primary — gehra teal, safed akshar. */
    fun primaryButton(context: Context, text: String): Button =
        baseButton(context, text).apply {
            background = rounded(context, TEAL, 12)
            setTextColor(Color.WHITE)
        }

    /** Accent — garam amber, safed akshar (khaas nayi cheezon ke liye). */
    fun accentButton(context: Context, text: String): Button =
        baseButton(context, text).apply {
            background = rounded(context, AMBER, 12)
            setTextColor(Color.WHITE)
        }

    /** Secondary — safed, teal kinara aur akshar. */
    fun secondaryButton(context: Context, text: String): Button =
        baseButton(context, text).apply {
            background = rounded(context, CARD, 12, TEAL)
            setTextColor(TEAL_DARK)
        }

    /** Danger — safed, laal kinara (delete jaise kaam). */
    fun dangerButton(context: Context, text: String): Button =
        baseButton(context, text).apply {
            background = rounded(context, CARD, 12, DANGER)
            setTextColor(DANGER)
        }

    // ---------- inputs / controls ----------

    fun input(context: Context, hint: String): EditText = EditText(context).apply {
        this.hint = hint
        textSize = 15f
        setTextColor(INK)
        setHintTextColor(MUTED)
        background = rounded(context, CARD, 10, BORDER)
        val p = dp(context, 12)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(context, 8) }
    }

    /** Switch ko palette se rang do (ON = teal). */
    fun tintSwitch(context: Context, sw: Switch) {
        sw.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(TEAL, Color.rgb(176, 190, 185))
        )
        sw.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.rgb(128, 203, 191), Color.rgb(207, 216, 212))
        )
    }

    /** CheckBox ko palette se rang do (ON = teal). */
    fun tintCheckBox(context: Context, box: CheckBox) {
        box.buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(TEAL, MUTED)
        )
    }
}
