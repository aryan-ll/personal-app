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

    // Sirf naam se nahi rokte (warna Apps list bhi band ho jaati). Naam wale pages appInfoHit dekhta hai.
    private fun protectedWords() = listOf(
        "provider hostname",
        "cloudflare-dns",
        "Add VPN",
        "Always-on VPN",
        "Block connections without VPN",
        // Device admin ki poori screen (list bhi) band
        "Device admin app",
        "Device administrator",
        "Device admin"
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
            log(this, "Guard started. 15 min test mode (log only, nothing blocked).")
        } else {
            log(this, "Guard started.")
        }
        handler.removeCallbacks(poll)
        handler.post(poll) // turant check, phir har 2 sec
    }

    private fun versionCode(): Long = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: PackageManager.NameNotFoundException) { 0L }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val evPkg = event.packageName?.toString() ?: return
        if (evPkg == packageName) return // apna app kabhi nahi

        // Home screen pe kis icon pe long-press hua, ye yaad rakho
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            if (evPkg in launchers) {
                val t = (event.text.joinToString(" ") + " " + (event.contentDescription ?: "") + " " +
                    (event.source?.let { (it.text ?: "").toString() + " " + (it.contentDescription ?: "") } ?: "")).trim()
                lastLongPress = t
                lastLongPressAt = SystemClock.uptimeMillis()
                if (Lock.graceLeftMs(this) > 0 || Lock.diagnoseOn(this)) log(this, "Long-press: ${t.ifBlank { "(no label)" }}")
            }
            return
        }

        if (evPkg == instagram) { handleInstagram(event); return }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val now = SystemClock.uptimeMillis()
        // Settings/home screen pe koi gap nahi (speed zaroori). Baaki apps me 250 ms ka gap, phone slow na ho.
        val sensitive = isSystemSettings(evPkg) || evPkg in launchers || evPkg == vpnDialogPkg
        if (!sensitive && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastCheck < 250) return
        lastCheck = now

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        checkWindow(pkg, root)
    }

    /**
     * Har 2 second me khud screen check karo. Realme Guard ko restart kare toh
     * pehle se khula Settings page bhi pakda jaye (us page pe koi naya event nahi aata).
     */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            try {
                rootInActiveWindow?.let { root ->
                    root.packageName?.toString()?.let { pkg -> if (pkg != instagram) checkWindow(pkg, root) }
                }
            } catch (_: Exception) {}
            handler.postDelayed(this, 300)
        }
    }

    override fun onDestroy() {
        hideShield.run()
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    private var lastPkgLogged = ""
    private var lastTextsLogged = ""

    /**
     * Screen pe jitna text hai (saari windows ka), chhote letters me.
     * Realme Settings kabhi content alag window me dikhata hai, isliye sirf
     * saamne wali window kaafi nahi. Text aur content-description dono padhte hain.
     */
    private fun screenTexts(root: AccessibilityNodeInfo): List<String> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        roots.add(root)
        try {
            windows?.forEach { w -> w.root?.let { r -> if (r.packageName?.toString() != packageName) roots.add(r) } }
        } catch (_: Exception) {}
        val out = ArrayList<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addAll(roots)
        var seen = 0
        while (queue.isNotEmpty() && seen < 2500) {
            val n = queue.removeFirst()
            seen++
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    private fun List<String>.has(word: String): Boolean = any { it.contains(word, ignoreCase = true) }

    private fun checkWindow(pkg: String, root: AccessibilityNodeInfo) {
        if (pkg == packageName) return
        // Install ke baad ka test mode, ya "Jaanch" button (rokta rehta hai, sirf zyada likhta hai)
        val test = Lock.graceLeftMs(this) > 0 || Lock.diagnoseOn(this)
        // Test mode me har naya app likho, taki chhoota hua page pakad sakein.
        if (pkg != lastPkgLogged && test) {
            lastPkgLogged = pkg
            log(this, "App: $pkg")
        }
        val needText = pkg in launchers || isSystemSettings(pkg) || pkg == vpnDialogPkg ||
            ((pkg.startsWith("com.android") || pkg.contains("oplus") || pkg.contains("coloros")) && !isBrowser(pkg))
        val texts = if (needText) screenTexts(root) else emptyList()

        // Test mode: Settings me Guard ko kya dikh raha hai, wo likho.
        if (test && isSystemSettings(pkg)) {
            val sample = texts.distinct().take(12).joinToString(" | ")
            if (sample != lastTextsLogged) { lastTextsLogged = sample; log(this, "Seen: $sample") }
        }

        if (pkg in launchers) { checkLauncher(texts); return }
        appInfoHit(texts)?.let { blockSettings("$pkg: Focus Guard + \"$it\""); return }
        when {
            pkg == vpnDialogPkg -> blockSettings("VPN permission dialog")
            isSystemSettings(pkg) -> protectedHit(texts)?.let { blockSettings("$pkg: \"$it\"") }
            isBrowser(pkg) -> handleBrowser(pkg)
        }
    }

    // ================= Settings =================

    private fun blockSettings(reason: String) {
        if (Lock.isOpen(this)) { logOnce("Test/unlocked, not blocked: $reason"); return }
        showShield() // pehle parda, taki ungli button tak na pahunche
        performGlobalAction(GLOBAL_ACTION_HOME)
        logOnce("Blocked: $reason")
    }

    // ---------- Parda (overlay) ----------
    private var shield: android.view.View? = null

    /**
     * Poori screen pe turant ek parda. Ye saare touch kha leta hai,
     * isliye peeche ka "Uninstall" / "Disable" button dab hi nahi sakta.
     * Accessibility overlay ke liye alag permission nahi chahiye.
     */
    private fun showShield() {
        handler.removeCallbacks(hideShield)
        if (shield == null) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val v = android.widget.TextView(this).apply {
                text = "Locked by Focus Guard"
                textSize = 20f
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(android.graphics.Color.parseColor("#F20B0D14"))
                isClickable = true // touch yahin ruk jaye
            }
            val lp = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            try { wm.addView(v, lp); shield = v } catch (_: Exception) {}
        }
        handler.postDelayed(hideShield, 900)
    }

    private val hideShield = Runnable {
        shield?.let {
            try { (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeView(it) } catch (_: Exception) {}
        }
        shield = null
    }

    /** Settings, installer, aur Realme ke Phone Manager / App management. */
    private fun isSystemSettings(pkg: String): Boolean =
        pkg.contains("settings") || pkg.contains("packageinstaller") ||
            pkg.contains("securitycenter") || pkg.contains("permissioncontroller") ||
            pkg.contains("phonemanager") || pkg.contains("safecenter") || pkg.contains("appmanager")

    /**
     * Kisi bhi system app me: "Focus Guard" + delete/band karne wala button dono dikhe toh band.
     * Doosre apps ke page pe unka naam hota hai, isliye wo nahi rukte.
     */
    private fun appInfoHit(texts: List<String>): String? {
        if (!texts.has(getString(R.string.app_name))) return null
        // App info (Uninstall/Force stop), Device admin (Disable and delete), Accessibility (Use Focus Guard)
        return listOf("Uninstall", "Force stop", "Disable and delete", "Deactivate", "Use Focus Guard", "Focus Guard shortcut")
            .firstOrNull { texts.has(it) }
    }

    /**
     * Home screen pe icon long-press → "Uninstall" ya "App info".
     * Focus Guard aur ye button dono dikhe toh menu band (BACK).
     */
    private var lastLongPress = ""
    private var lastLongPressAt = 0L

    private fun checkLauncher(texts: List<String>) {
        // Sirf tab rokna jab long-press Focus Guard ke icon pe hua ho (8 sec ke andar).
        // Doosre apps ka menu normal khulega.
        val recent = SystemClock.uptimeMillis() - lastLongPressAt < 8000
        if (!recent || !lastLongPress.contains(getString(R.string.app_name), ignoreCase = true)) return
        // Long-press menu me kuch bhi ho (Info, Edit, Share, Uninstall...), turant band
        val hit = listOf("Uninstall", "App info", "Remove", "Info", "Edit", "Share", "Open", "Widgets", "Select")
            .firstOrNull { w -> texts.any { it.equals(w, ignoreCase = true) } } ?: return // exact: "ShareChat" jaise app naam match na hon
        val reason = "Home screen menu: \"$hit\""
        if (Lock.isOpen(this)) { logOnce("Test/unlocked, not blocked: $reason"); return }
        showShield()
        performGlobalAction(GLOBAL_ACTION_BACK)
        logOnce("Blocked: $reason")
    }

    private fun protectedHit(texts: List<String>): String? = protectedWords().firstOrNull { texts.has(it) }

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
        val reason = if (night) "Browser at night ($pkg)" else "Browser gate ($pkg)"
        if (Lock.isOpen(this)) { logOnce("Test/unlocked, not blocked: $reason"); return }

        val now = SystemClock.uptimeMillis()
        if (now - lastGateLaunch < 1500) return
        lastGateLaunch = now
        logOnce("Blocked: $reason")
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
                        label = "Scroll after DM reel"; act = Act.BACK
                    } else {
                        label = "DM reel"; act = Act.NONE
                    }
                    Ctx.OTHER -> { label = "Reel (from feed/explore)"; act = Act.BACK }
                }
            }
            else -> {
                inClips = false
                igCtx = when {
                    isDm(scan) -> Ctx.DM
                    isSaved(scan, root) -> Ctx.SAVED
                    // Neeche tab bar = main screen (home/profile/search), yaad mitao
                    tab != null || "tab_bar" in scan.ids -> Ctx.OTHER
                    // Kisi ka profile khula: uski reels ka raasta band
                    scan.ids.any { it.startsWith("row_profile_header") || it.startsWith("profile_header") } -> Ctx.OTHER
                    // Saved/DM ke andar ki screen (post viewer etc.): yaad rakho kahan se aaye
                    else -> igCtx
                }
                val story = scan.ids.any { it.startsWith("reel_viewer") } // story khuli hai, feed check mat karo
                if (!story && tab != null && tab.contains("feed") && feedScrolled(scan)) {
                    label = "Home feed scroll"; act = Act.HOME_TAB
                } else {
                    label = "ok (${igCtx.name.lowercase()}, tab=${tab ?: "-"})"; act = Act.NONE
                }
            }
        }

        val test = Lock.isOpen(this)
        if (label != lastIgLabel) {
            lastIgLabel = label
            // IDs bhi likho (test me har screen, warna sirf roke hue), taki galat pakda toh theek kar sakein.
            val hint = scan.ids.filter { id ->
                listOf("tab", "tray", "viewer", "saved", "collection", "thread", "direct", "search", "explore", "row_feed", "clips", "title", "profile")
                    .any { id.contains(it) }
            }.take(12).joinToString(",")
            if (test) {
                log(this, "IG: $label${if (act != Act.NONE) " [would block]" else ""} | $hint")
            } else if (act != Act.NONE) {
                log(this, "Blocked IG: $label | $hint")
            } else if (igCtx == Ctx.SAVED || label == "Saved reel") {
                log(this, "IG: $label")
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
            "Home feed scroll" -> "Feed is blocked. Stories only."
            "Scroll after DM reel" -> "Only the shared reel is allowed."
            else -> "Blocked. Stories, Saved and DMs are allowed."
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
