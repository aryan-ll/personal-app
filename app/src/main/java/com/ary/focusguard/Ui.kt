package com.ary.focusguard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Dark theme ke rang aur chhote helpers. */
object Ui {
    val BG = Color.parseColor("#0E1014")
    val CARD = Color.parseColor("#181B21")
    val LINE = Color.parseColor("#262A33")
    val TEXT = Color.parseColor("#F1F2F4")
    val MUTED = Color.parseColor("#8B919C")
    val GREEN = Color.parseColor("#3DDC84")
    val RED = Color.parseColor("#FF5A5F")
    val GOLD = Color.parseColor("#F5C451")
    val BLUE = Color.parseColor("#5B8CFF")
    val SOS = Color.parseColor("#E5484D")

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(c: Context, fill: Int, radiusDp: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(c, radiusDp).toFloat()
        if (stroke != null) setStroke(dp(c, 1), stroke)
    }

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c, CARD, 18, LINE)
        val p = dp(c, 16)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 12) }
    }

    fun text(c: Context, s: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    /** Gol kinare wala button. */
    fun button(c: Context, s: String, fill: Int, textColor: Int = Color.WHITE, onClick: () -> Unit) = TextView(c).apply {
        text = s
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(textColor)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = rounded(c, fill, 14)
        val v = dp(c, 14)
        setPadding(v, v, v, v)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 10) }
        isClickable = true
        setOnClickListener { onClick() }
    }
}
