package com.ary.focusguard

import android.content.Context
import android.os.SystemClock
import java.util.Calendar

/** Browser ke niyam: din me puzzle, raat 8 se subah 7 tak band. */
object Rules {
    const val BROWSER_NIGHT_START = 20   // raat 8 baje
    const val BROWSER_NIGHT_END = 7      // subah 7 baje
    const val BROWSER_FREE_MS = 30 * 60 * 1000L  // puzzle ke baad 30 min free
    const val PUZZLE_COUNT = 5

    private fun prefs(c: Context) = c.getSharedPreferences("rules", Context.MODE_PRIVATE)

    fun isBrowserNight(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= BROWSER_NIGHT_START || h < BROWSER_NIGHT_END
    }

    /** Puzzle solve hua: 30 min browser free. Boot se time ginte hain (ghadi badalne se fark nahi). */
    fun grantBrowser(c: Context) {
        prefs(c).edit()
            .putLong("until", SystemClock.elapsedRealtime() + BROWSER_FREE_MS)
            .putInt("boot", Lock.bootCount(c))
            .apply()
    }

    fun browserFreeLeftMs(c: Context): Long {
        val p = prefs(c)
        if (p.getInt("boot", -1) != Lock.bootCount(c)) return 0L
        return (p.getLong("until", 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    fun browserAllowed(c: Context): Boolean = !isBrowserNight() && browserFreeLeftMs(c) > 0
}
