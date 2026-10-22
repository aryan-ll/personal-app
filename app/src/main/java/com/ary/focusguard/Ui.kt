package com.ary.focusguard

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Glassy dark theme: gradient background + aadhe-transparent cards. */
object Ui {
    val TOP = Color.parseColor("#17132E")      // screen ka upar wala rang (status bar bhi)
    val BG = Color.parseColor("#0B0D14")       // neeche wala rang, aur sone ke button pe text
    val CARD = Color.parseColor("#14FFFFFF")   // glass: 8% safed
    val LINE = Color.parseColor("#2BFFFFFF")   // glass ki patli chamakti border
    val GLASS_BTN = Color.parseColor("#1FFFFFFF")
    val TEXT = Color.parseColor("#F4F5F7")
    val MUTED = Color.parseColor("#A3A8B5")
    val GREEN = Color.parseColor("#3DDC84")
    val RED = Color.parseColor("#FF5A5F")
    val GOLD = Color.parseColor("#F5C451")
    val BLUE = Color.parseColor("#6E8BFF")
    val SOS = Color.parseColor("#D9E5484D")

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(c: Context, fill: Int, radiusDp: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(c, radiusDp).toFloat()
        if (stroke != null) setStroke(dp(c, 1), stroke)
    }

    /** Glass card ka background: upar thoda zyada chamak, neeche kam. */
    fun glass(c: Context, radiusDp: Int = 24, tint: Int? = null): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(tint ?: Color.parseColor("#1FFFFFFF"), Color.parseColor("#0AFFFFFF"))
        ).apply {
            cornerRadius = dp(c, radiusDp).toFloat()
            setStroke(dp(c, 1), LINE)
        }

    /** Poori screen ka background: gehra gradient + do rangeen roshni ke gole (glass ke peeche). */
    fun screenBg(c: Context): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(TOP, BG))
        fun blob(color: String, x: Float, y: Float, radiusDp: Int) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(Color.parseColor(color), Color.TRANSPARENT)
            gradientRadius = dp(c, radiusDp).toFloat()
            setGradientCenter(x, y)
        }
        return LayerDrawable(arrayOf(
            base,
            blob("#806C3BFF", 0.1f, 0.08f, 320),   // baingani, upar-left
            blob("#5530C6FF", 0.95f, 0.45f, 300),  // neela, beech-right
            blob("#40FF5A8A", 0.15f, 0.9f, 280)    // gulabi, neeche-left
        ))
    }

    fun styleWindow(a: Activity) {
        a.window.statusBarColor = TOP
        a.window.navigationBarColor = BG
    }

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(c)
        val p = dp(c, 18)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 14) }
    }

    fun text(c: Context, s: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    /** Gol kinare wala button. Glass button par patli border bhi. */
    fun button(c: Context, s: String, fill: Int, textColor: Int = Color.WHITE, onClick: () -> Unit) = TextView(c).apply {
        text = s
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(textColor)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = rounded(c, fill, 16, if (Color.alpha(fill) < 255) LINE else null)
        val v = dp(c, 15)
        setPadding(v, v, v, v)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 10) }
        isClickable = true
        setOnClickListener { onClick() }
    }
}
