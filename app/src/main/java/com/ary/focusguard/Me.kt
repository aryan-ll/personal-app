package com.ary.focusguard

import android.content.Context
import java.util.concurrent.TimeUnit

/** Streak aur "kyun chhodna hai" note. */
object Me {
    const val DEFAULT_NOTE = "Shree ji dekh rahi hai !💎"

    private fun prefs(c: Context) = c.getSharedPreferences("me", Context.MODE_PRIVATE)

    /** Streak kab se shuru hua. Pehli baar app khula tab se. */
    fun streakStart(c: Context): Long {
        val p = prefs(c)
        val start = p.getLong("start", -1L)
        if (start > 0) return start
        val now = System.currentTimeMillis()
        p.edit().putLong("start", now).apply()
        return now
    }

    fun streakDays(c: Context): Long =
        TimeUnit.MILLISECONDS.toDays((System.currentTimeMillis() - streakStart(c)).coerceAtLeast(0))

    fun bestDays(c: Context): Long = maxOf(prefs(c).getLong("best", 0L), streakDays(c))

    /** "Main fisal gaya" — streak 0 se, best yaad rakho. */
    fun resetStreak(c: Context) {
        val best = bestDays(c)
        prefs(c).edit()
            .putLong("best", best)
            .putLong("start", System.currentTimeMillis())
            .apply()
    }

    fun note(c: Context): String = prefs(c).getString("note", DEFAULT_NOTE)?.ifBlank { DEFAULT_NOTE } ?: DEFAULT_NOTE

    fun setNote(c: Context, s: String) = prefs(c).edit().putString("note", s.trim()).apply()
}
