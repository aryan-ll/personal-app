package com.ary.focusguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * Browser kholne se pehle:
 *  - Din: 5 maths sawaal, sahi hue toh 30 min browser free.
 *  - Raat 8 – subah 7: browser band, sirf Home ka button.
 */
class GateActivity : Activity() {

    companion object {
        const val EXTRA_PKG = "pkg"
    }

    private var targetPkg: String? = null
    private var solved = 0
    private var answer = 0

    private lateinit var progress: TextView
    private lateinit var question: TextView
    private lateinit var input: EditText
    private lateinit var feedback: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        targetPkg = intent.getStringExtra(EXTRA_PKG)
        build()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PKG)?.let { targetPkg = it }
        build()
    }

    override fun onResume() {
        super.onResume()
        // Puzzle kahin aur solve ho gaya ho toh yahan rukne ki zarurat nahi
        if (Rules.browserAllowed(this)) openTargetAndFinish()
    }

    private fun build() {
        val c = this
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ui.BG)
            val p = Ui.dp(c, 24)
            setPadding(p, p * 3, p, p)
        }

        if (Rules.isBrowserNight()) {
            col.addView(Ui.text(c, "🌙", 64f).apply { gravity = Gravity.CENTER })
            col.addView(Ui.text(c, "Browser band hai", 26f, Ui.TEXT, true).apply {
                gravity = Gravity.CENTER; setPadding(0, Ui.dp(c, 12), 0, 0)
            })
            col.addView(Ui.text(c, "Raat 8 se subah 7 tak.\nKal subah milte hain.", 16f, Ui.MUTED).apply {
                gravity = Gravity.CENTER; setPadding(0, Ui.dp(c, 8), 0, 0)
            })
            col.addView(Ui.text(c, Me.note(c), 20f, Ui.GOLD, true).apply {
                gravity = Gravity.CENTER; setPadding(0, Ui.dp(c, 32), 0, Ui.dp(c, 16))
            })
            col.addView(Ui.button(c, "🆘  Urge aa raha hai", Ui.SOS) {
                startActivity(Intent(c, SosActivity::class.java)); finish()
            })
            col.addView(Ui.button(c, "Home pe jao", Ui.CARD) { goHome() })
            setContentView(col)
            return
        }

        solved = 0
        col.addView(Ui.text(c, Me.note(c), 20f, Ui.GOLD, true).apply { gravity = Gravity.CENTER })
        col.addView(Ui.text(c, "Browser kholne se pehle ${Rules.PUZZLE_COUNT} sawaal.\nSach me zarurat hai? Toh solve karo.", 15f, Ui.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, Ui.dp(c, 10), 0, 0)
        })

        val box = Ui.card(c).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(c, 32)
        }
        progress = Ui.text(c, "", 14f, Ui.MUTED, true).apply { gravity = Gravity.CENTER }
        question = Ui.text(c, "", 44f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER; setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 12))
        }
        input = EditText(c).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            hint = "Jawab"
            background = Ui.rounded(c, Ui.BG, 12, Ui.LINE)
            val p = Ui.dp(c, 12)
            setPadding(p, p, p, p)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { check(); true } else false
            }
        }
        feedback = Ui.text(c, "", 14f, Ui.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, Ui.dp(c, 8), 0, 0)
        }
        box.addView(progress)
        box.addView(question)
        box.addView(input, LinearLayout.LayoutParams(-1, -2))
        box.addView(feedback)
        box.addView(Ui.button(c, "Check", Ui.BLUE) { check() })
        col.addView(box)

        col.addView(Ui.button(c, "Rehne do, Home pe jao", Ui.CARD) { goHome() })

        setContentView(col)
        newQuestion()
        input.requestFocus()
    }

    private fun newQuestion() {
        val a = Random.nextInt(12, 40)
        val b = Random.nextInt(3, 10)
        answer = a * b
        question.text = "$a × $b"
        progress.text = "Sawaal ${solved + 1} / ${Rules.PUZZLE_COUNT}"
        input.setText("")
    }

    private fun check() {
        val given = input.text.toString().trim().toIntOrNull()
        if (given == answer) {
            solved++
            if (solved >= Rules.PUZZLE_COUNT) {
                Rules.grantBrowser(this)
                GuardService.log(this, "Puzzle solve: browser 30 min free")
                openTargetAndFinish()
                return
            }
            feedback.setTextColor(Ui.GREEN)
            feedback.text = "Sahi ✓"
        } else {
            feedback.setTextColor(Ui.RED)
            feedback.text = "Galat. Naya sawaal."
        }
        newQuestion()
    }

    private fun openTargetAndFinish() {
        targetPkg?.let { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } }
        finish()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    @Deprecated("Back = Home")
    override fun onBackPressed() = goHome()
}
