package com.ary.focusguard

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * 1 ghante ka lock.
 *
 * Time phone ki ghadi se nahi, "boot se kitna time hua" (elapsedRealtime) se ginte hain,
 * taki date/time badal ke timer jaldi khatam na ho sake.
 * Phone restart hua toh request cancel ho jaati hai — phir se 1 ghanta.
 */
object Lock {
    const val WAIT_MS = 60 * 60 * 1000L      // 1 ghanta intezaar
    const val OPEN_MS = 10 * 60 * 1000L      // phir 10 minute khula
    const val GRACE_MS = 90 * 1000L          // setup ke waqt 90 sec ki chhoot
    const val TEST_MS = 15 * 60 * 1000L      // install/update ke baad 15 min test mode

    sealed class State {
        object Locked : State()
        data class Waiting(val leftMs: Long) : State()
        data class Open(val leftMs: Long) : State()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("lock", Context.MODE_PRIVATE)

    fun bootCount(c: Context): Int =
        Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, 0)

    /** Raat ka strict mode: 11 PM se 6 AM tak unlock request nahi. */
    const val NIGHT_START = 23
    const val NIGHT_END = 6

    fun isNight(): Boolean {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return h >= NIGHT_START || h < NIGHT_END
    }

    /** @return false agar raat hai (request nahi dali). */
    fun requestUnlock(c: Context): Boolean {
        if (isNight()) return false
        if (prefs(c).getLong("req", -1L) >= 0 && state(c) !is State.Locked) return true // pehle se chal raha hai
        prefs(c).edit()
            .putLong("req", SystemClock.elapsedRealtime())
            .putInt("reqBoot", bootCount(c))
            .apply()
        return true
    }

    fun cancel(c: Context) {
        prefs(c).edit().remove("req").remove("reqBoot").apply()
    }

    fun state(c: Context): State {
        val p = prefs(c)
        val req = p.getLong("req", -1L)
        if (req < 0 || p.getInt("reqBoot", -1) != bootCount(c)) return State.Locked
        val passed = SystemClock.elapsedRealtime() - req
        return when {
            passed < 0 -> State.Locked
            passed < WAIT_MS -> State.Waiting(WAIT_MS - passed)
            passed < WAIT_MS + OPEN_MS -> State.Open(WAIT_MS + OPEN_MS - passed)
            else -> { cancel(c); State.Locked }
        }
    }

    /** Sirf setup adhoora ho tab use hota hai (MainActivity decide karta hai). */
    fun startGrace(c: Context, ms: Long = GRACE_MS) {
        prefs(c).edit()
            .putLong("grace", SystemClock.elapsedRealtime() + ms)
            .putInt("graceBoot", bootCount(c))
            .apply()
    }

    fun graceLeftMs(c: Context): Long {
        val p = prefs(c)
        if (p.getInt("graceBoot", -1) != bootCount(c)) return 0L
        return (p.getLong("grace", 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    private fun inGrace(c: Context): Boolean = graceLeftMs(c) > 0

    /**
     * "Jaanch" mode: 15 min tak Guard log me likhega ki screen pe kya dikha.
     * Rokna band NAHI hota — ye bypass nahi hai.
     */
    fun startDiagnose(c: Context) {
        prefs(c).edit()
            .putLong("diag", SystemClock.elapsedRealtime() + TEST_MS)
            .putInt("diagBoot", bootCount(c))
            .apply()
    }

    fun diagnoseLeftMs(c: Context): Long {
        val p = prefs(c)
        if (p.getInt("diagBoot", -1) != bootCount(c)) return 0L
        return (p.getLong("diag", 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    fun diagnoseOn(c: Context) = diagnoseLeftMs(c) > 0

    /** Guard ko rukna chahiye ya nahi. */
    fun isOpen(c: Context): Boolean = state(c) is State.Open || inGrace(c)

    fun format(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
