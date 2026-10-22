package com.ary.focusguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
 *  1. Settings / installer me ye screens khule toh Home pe bhejta hai:
 *     - Focus Guard ka App info / uninstall / Device admin / Accessibility page
 *     - Private DNS hostname wala dialog
 *     - VPN settings aur VPN apps ka permission dialog (VPN = DNS bypass)
 *  2. Browser (Chrome, Brave, Google app, koi bhi): din me puzzle gate, raat 8–7 band.
 *  3. Instagram: Stories, Saved reels, DMs (aur DM ki reel) chalenge.
 *     Home feed scroll, Reels tab, Search/Explore, aur baaki jagah ki reels band.
 *
 * Test mode: app install/update ke baad 15 minute sirf LOG karta hai, rokta nahi.
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
        "Add VPN",
        "Always-on VPN",
        "Block connections without VPN"
    )

    private val vpnDialogPkg = "com.android.vpndialogs"

    // ---------- Browser ----------
    private val googleApp = "com.google.android.googlequicksearchbox"
    private val browserCache = HashMap<String, Boolean>()
    private var lastGateLaunch = 0L

    // ---------- Instagram ----------
    private val instagram = "com.instagram.android"
    private enum class Ctx { DM, SAVED, OTHER }
    private var igCtx = Ctx.OTHER
    private var inClips = false
    private var clipsEnteredAt = 0L
    private var lastIgCheck = 0L
    private var lastIgAction = 0L
    private var lastIgLabel = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        launchers = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
            .toSet()

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

        if (evPkg == instagram) { handleInstagram(event); return }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val now = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastCheck < 250) return
        lastCheck = now

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != evPkg || pkg == packageName || pkg in launchers) return

        when {
            pkg == vpnDialogPkg -> blockSettings("VPN permission dialog")
            isSystemSettings(pkg) -> protectedHit(root)?.let { blockSettings("$pkg: \"$it\"") }
            isBrowser(pkg) -> handleBrowser(pkg)
        }
    }

    // ================= Settings =================

    private fun blockSettings(reason: String) {
        if (Lock.isOpen(this)) { logOnce("TEST/khula, nahi roka: $reason"); return }
        performGlobalAction(GLOBAL_ACTION_HOME)
        logOnce("Roka: $reason")
        toast("Locked. Focus Guard me 1 ghante ki request dalo.")
    }

    private fun isSystemSettings(pkg: String): Boolean =
        pkg.contains("settings") || pkg.contains("packageinstaller") ||
            pkg.contains("securitycenter") || pkg.contains("permissioncontroller")

    private fun protectedHit(root: AccessibilityNodeInfo): String? =
        protectedWords().firstOrNull { w ->
            root.findAccessibilityNodeInfosByText(w).any { it.isVisibleToUser }
        }

    // ================= Browser =================

    /** Jo bhi app https link khol sakta hai wo browser hai. Plus Google app. */
    private fun isBrowser(pkg: String): Boolean = browserCache.getOrPut(pkg) {
        if (pkg == googleApp) return@getOrPut true
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).setPackage(pkg)
        packageManager.queryIntentActivities(i, 0).isNotEmpty()
    }

    private fun handleBrowser(pkg: String) {
        val night = Rules.isBrowserNight()
        if (!night && Rules.browserFreeLeftMs(this) > 0) return
        val reason = if (night) "Browser raat me ($pkg)" else "Browser gate ($pkg)"
        if (Lock.isOpen(this)) { logOnce("TEST/khula, nahi roka: $reason"); return }

        val now = SystemClock.uptimeMillis()
        if (now - lastGateLaunch < 1500) return
        lastGateLaunch = now
        logOnce("Roka: $reason")
        if (night) performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(Intent(this, GateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(GateActivity.EXTRA_PKG, pkg))
    }

    // ================= Instagram =================

    private class Scan(val ids: Set<String>, val selectedTab: String?)

    private enum class Act { NONE, BACK, HOME_TAB }

    private fun handleInstagram(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        val scrolled = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (!scrolled && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastIgCheck < 300) return
        lastIgCheck = now

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != instagram) return
        val scan = scanIg(root)
        val tab = scan.selectedTab
        val clips = scan.ids.any { it.startsWith("clips_viewer") || it.contains("clips_video") }

        val label: String
        val act: Act
        when {
            tab != null && tab.contains("clips") -> { label = "Reels tab"; act = Act.HOME_TAB }
            tab != null && (tab.contains("search") || tab.contains("explore")) -> { label = "Search tab"; act = Act.HOME_TAB }
            clips -> {
                if (!inClips) { inClips = true; clipsEnteredAt = now }
                when (igCtx) {
                    Ctx.SAVED -> { label = "Saved reel"; act = Act.NONE }
                    Ctx.DM -> if (scrolled && now - clipsEnteredAt > 1500) {
                        label = "DM reel ke baad scroll"; act = Act.BACK
                    } else {
                        label = "DM reel"; act = Act.NONE
                    }
                    Ctx.OTHER -> { label = "Reel (feed/explore se)"; act = Act.BACK }
                }
            }
            else -> {
                inClips = false
                igCtx = when {
                    isDm(scan) -> Ctx.DM
                    isSaved(scan, root) -> Ctx.SAVED
                    else -> Ctx.OTHER
                }
                if (tab != null && tab.contains("feed") && feedScrolled(scan)) {
                    label = "Home feed scroll"; act = Act.HOME_TAB
                } else {
                    label = "ok (${igCtx.name.lowercase()}, tab=${tab ?: "-"})"; act = Act.NONE
                }
            }
        }

        val test = Lock.isOpen(this)
        if (label != lastIgLabel) {
            lastIgLabel = label
            if (test) {
                // Test mode me IDs bhi likho, taki screens sahi pakad sakein.
                val hint = scan.ids.filter { id ->
                    listOf("tab", "tray", "viewer", "saved", "collection", "thread", "direct", "search", "explore", "row_feed", "clips")
                        .any { id.contains(it) }
                }.take(10).joinToString(",")
                log(this, "IG: $label${if (act != Act.NONE) " [rokta]" else ""} | $hint")
            } else if (act != Act.NONE) {
                log(this, "Roka IG: $label")
            }
        }
        if (act == Act.NONE || test) return
        if (now - lastIgAction < 700) return
        lastIgAction = now

        when (act) {
            Act.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            Act.HOME_TAB -> if (!clickId(root, "feed_tab")) performGlobalAction(GLOBAL_ACTION_BACK)
            Act.NONE -> {}
        }
        toast(when (label) {
            "Home feed scroll" -> "Feed band hai. Stories dekho, bas."
            "DM reel ke baad scroll" -> "DM ki reel dekh li. Aage scroll band."
            else -> "Ye band hai. Stories, Saved aur DM chalenge."
        })
    }

    /** Screen pe dikh rahe saare view ids (chhote naam) aur selected tab. */
    private fun scanIg(root: AccessibilityNodeInfo): Scan {
        val ids = HashSet<String>()
        var selected: String? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 1500) {
            val node = queue.removeFirst()
            seen++
            if (node.isVisibleToUser) {
                node.viewIdResourceName?.substringAfter(":id/")?.let { id ->
                    ids.add(id)
                    if (id.endsWith("_tab") && node.isSelected) selected = id
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return Scan(ids, selected)
    }

    private fun isDm(s: Scan) = s.ids.any {
        it.contains("direct_thread") || it.contains("row_thread") || it.contains("thread_title") ||
            it.contains("message_list") || it.contains("direct_inbox") || it.contains("inbox")
    }

    private fun isSaved(s: Scan, root: AccessibilityNodeInfo): Boolean {
        if (s.ids.any { it.contains("saved") || it.contains("collection") }) return true
        return listOf("All posts", "Saved").any { t ->
            root.findAccessibilityNodeInfosByText(t).any { n ->
                n.isVisibleToUser && n.text?.toString() == t &&
                    (n.viewIdResourceName ?: "").let { it.contains("title") || it.contains("action_bar") }
            }
        }
    }

    /** Stories ki line chali gayi aur posts dikh rahe = neeche scroll kiya. */
    private fun feedScrolled(s: Scan): Boolean {
        val tray = s.ids.any { it.contains("tray") }
        val post = s.ids.any { it.startsWith("row_feed") }
        return post && !tray
    }

    /** Us id wale button pe tap (Home tab pe tap = feed upar chala jata hai). */
    private fun clickId(root: AccessibilityNodeInfo, id: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId("$instagram:id/$id")
        var n: AccessibilityNodeInfo? = nodes.firstOrNull() ?: return false
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    // ================= Common =================

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
        /** Aakhri 40 guard actions, app me dikhane ke liye. */
        fun log(c: Context, msg: String) {
            val p = c.getSharedPreferences("guard", Context.MODE_PRIVATE)
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val lines = (listOf("$time  $msg") + (p.getString("log", "") ?: "").lines())
                .filter { it.isNotBlank() }
                .take(40)
            p.edit().putString("log", lines.joinToString("\n")).apply()
        }

        fun readLog(c: Context): String =
            c.getSharedPreferences("guard", Context.MODE_PRIVATE).getString("log", "") ?: ""
    }
}
