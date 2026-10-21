package com.ary.focusguard

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Urge aaye tab: 2 minute saans (4 sec andar, 4 sec bahar) + apna note.
 * Urge lehar jaisa hai, upar jaata hai aur utarta hai. Bas guzaarna hai.
 */
class SosActivity : Activity() {

    private val totalMs = 2 * 60 * 1000L
    private var timer: CountDownTimer? = null
    private var anim: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ui.BG)
            val p = Ui.dp(this@SosActivity, 24)
            setPadding(p, p * 2, p, p)
        }

        col.addView(Ui.text(this, Me.note(this), 26f, Ui.GOLD, true).apply { gravity = Gravity.CENTER })
        col.addView(Ui.text(this, "Ye urge 10-15 minute me utar jayega. Bas saath me saans lo.", 15f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@SosActivity, 12), 0, 0)
        })

        // Saans wala gola
        val size = Ui.dp(this, 220)
        val holder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { topMargin = Ui.dp(this@SosActivity, 40) }
        }
        val circle = View(this).apply {
            background = Ui.rounded(this@SosActivity, Ui.BLUE, 200)
            alpha = 0.85f
            layoutParams = FrameLayout.LayoutParams(size, size)
        }
        val phase = Ui.text(this, "Saans andar", 20f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(size, size)
        }
        holder.addView(circle)
        holder.addView(phase)
        col.addView(holder)

        val left = Ui.text(this, "", 16f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@SosActivity, 32), 0, 0)
        }
        col.addView(left)

        val done = Ui.button(this, "Main theek hu 💪", Ui.CARD) { finish() }
        col.addView(done)

        setContentView(col)

        // 4 sec bada (andar), 4 sec chhota (bahar)
        anim = ValueAnimator.ofFloat(0.45f, 1f).apply {
            duration = 4000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            var growing = true
            var last = 0f
            addUpdateListener {
                val v = it.animatedValue as Float
                circle.scaleX = v; circle.scaleY = v
                val nowGrowing = v >= last
                if (nowGrowing != growing) {
                    growing = nowGrowing
                    phase.text = if (growing) "Saans andar" else "Saans bahar"
                }
                last = v
            }
            start()
        }

        timer = object : CountDownTimer(totalMs, 1000) {
            override fun onTick(ms: Long) {
                left.text = "Baaki: ${ms / 60000}:%02d".format((ms / 1000) % 60)
            }
            override fun onFinish() {
                left.text = "2 minute ho gaye. Shabaash. Ab uthke paani piyo ya bahar ghoom aao."
                done.background = Ui.rounded(this@SosActivity, Ui.GREEN, 14)
                done.setTextColor(Ui.BG)
            }
        }.start()
    }

    override fun onDestroy() {
        timer?.cancel()
        anim?.cancel()
        super.onDestroy()
    }
}
