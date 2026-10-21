package com.ary.focusguard

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Guard:
 *  1. Instagram me Reels khule toh wapas bhejta hai.
 *  2. Settings / installer me ye screens khule toh Home pe bhejta hai:
 *     - Focus Guard ka App info / uninstall / Device admin / Accessibility page
 *     - Private DNS hostname wala dialog
 * Lock "Open" ho (1 ghanta pura) toh kuch nahi rokta.
 */
class GuardService : AccessibilityService() {

    private var lastCheck = 0L
    private var lastToast = 0L

    private val instagram = "com.instagram.android"

    // Instagram ke Reels viewer ke andar ke view ids me ye shabd aate hain.
    private val reelsIdHints = listOf("clips_viewer", "clips_video", "clips_media", "reel_viewer")

    // Settings ki screens jinpe ye text dikhe, unhe band rakhna hai.
    private fun protectedWords() = listOf(
        getString(R.string.app_name),   // App info, uninstall dialog, Device admin, Accessibility page
        "provider hostname",            // Private DNS dialog
        "cloudflare-dns"                // Private DNS me likha hostname
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val now = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastCheck < 250) return
        lastCheck = now

        if (Lock.isOpen(this)) return

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName) return // apna app hamesha khulega (unlock request ke liye)

        when {
            pkg == instagram -> if (isReels(root)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                toast("Reels band hai. Wapas kaam pe.")
            }
            isSystemSettings(pkg) -> if (isProtected(root)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                toast("Locked. Focus Guard me 1 ghante ki request dalo.")
            }
        }
    }

    private fun isSystemSettings(pkg: String): Boolean =
        pkg.contains("settings") || pkg.contains("packageinstaller") ||
            pkg.contains("securitycenter") || pkg.contains("permissioncontroller")

    private fun isProtected(root: AccessibilityNodeInfo): Boolean =
        protectedWords().any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() }

    /** View tree me Reels viewer dhundho (max 800 nodes, taki phone slow na ho). */
    private fun isReels(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 800) {
            val node = queue.removeFirst()
            seen++
            val id = node.viewIdResourceName
            if (id != null && reelsIdHints.any { id.contains(it) }) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    private fun toast(msg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastToast < 3000) return
        lastToast = now
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {}
}
