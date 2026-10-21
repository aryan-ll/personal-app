package com.ary.focusguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guard:
 *  Settings / installer me ye screens khule toh Home pe bhejta hai:
 *     - Focus Guard ka App info / uninstall / Device admin / Accessibility page
 *     - Private DNS hostname wala dialog
 *     - VPN settings aur VPN apps ka permission dialog (VPN = DNS bypass)
 *
 * Test mode: app install/update ke baad guard pehli baar chalta hai toh 15 minute
 * sirf LOG karta hai, rokta nahi. Taki koi bug ho toh phone phase nahi.
 * Lock "Open" ho tab bhi sirf log.
 */
class GuardService : AccessibilityService() {

    private var lastCheck = 0L
    private var lastToast = 0L
    private var lastLogMsg = ""
    private var launchers: Set<String> = emptySet()

    private fun protectedWords() = listOf(
        getString(R.string.app_name),
        "provider hostname",
        "cloudflare-dns",
        // VPN Private DNS ko bypass karta hai, isliye VPN settings bhi band
        "Add VPN",
        "Always-on VPN",
        "Block connections without VPN"
    )

    /** Kisi bhi VPN app ka "Connection request" (permission) dialog. */
    private val vpnDialogPkg = "com.android.vpndialogs"

    override fun onServiceConnected() {
        super.onServiceConnected()
        launchers = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
            .toSet()

        // Naya install/update? Toh 15 minute test mode.
        val ver = versionCode()
        val p = getSharedPreferences("guard", Context.MODE_PRIVATE)
        if (p.getLong("ver", -1L) != ver) {
            p.edit().putLong("ver", ver).apply()
            Lock.startGrace(this, Lock.TEST_MS)
            log(this, "Guard chalu. 15 min TEST mode (sirf log, rokega nahi).")
        } else {
            log(this, "Guard chalu.")
        }
    }

    private fun versionCode(): Long = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: PackageManager.NameNotFoundException) { 0L }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val evPkg = event.packageName?.toString() ?: return
        if (evPkg == packageName || evPkg in launchers) return // apna app aur home screen kabhi nahi

        val now = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastCheck < 250) return
        lastCheck = now

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        // Event aur screen dono ek hi app ke hone chahiye, warna purani screen pe galat action ho jata hai.
        if (pkg != evPkg || pkg == packageName || pkg in launchers) return

        val reason = when {
            pkg == vpnDialogPkg -> "VPN permission dialog"
            isSystemSettings(pkg) -> protectedHit(root)?.let { "$pkg: \"$it\"" } ?: return
            else -> return
        }

        if (Lock.isOpen(this)) {
            logOnce("TEST/khula, nahi roka: $reason")
            return
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
        logOnce("Roka: $reason")
        toast("Locked. Focus Guard me 1 ghante ki request dalo.")
    }

    private fun isSystemSettings(pkg: String): Boolean =
        pkg.contains("settings") || pkg.contains("packageinstaller") ||
            pkg.contains("securitycenter") || pkg.contains("permissioncontroller")

    /** Sirf screen pe DIKH rahe text ko maante hain. */
    private fun protectedHit(root: AccessibilityNodeInfo): String? =
        protectedWords().firstOrNull { w ->
            root.findAccessibilityNodeInfosByText(w).any { it.isVisibleToUser }
        }

    private fun logOnce(msg: String) {
        if (msg == lastLogMsg) return
        lastLogMsg = msg
        log(this, msg)
    }

    private fun toast(msg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastToast < 3000) return
        lastToast = now
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {}

    companion object {
        /** Aakhri 15 guard actions, app me dikhane ke liye. */
        fun log(c: Context, msg: String) {
            val p = c.getSharedPreferences("guard", Context.MODE_PRIVATE)
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val lines = (listOf("$time  $msg") + (p.getString("log", "") ?: "").lines())
                .filter { it.isNotBlank() }
                .take(15)
            p.edit().putString("log", lines.joinToString("\n")).apply()
        }

        fun readLog(c: Context): String =
            c.getSharedPreferences("guard", Context.MODE_PRIVATE).getString("log", "") ?: ""
    }
}
