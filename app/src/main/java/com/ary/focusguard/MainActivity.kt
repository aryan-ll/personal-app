package com.ary.focusguard

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.InetAddress

class MainActivity : Activity() {

    private val dnsHost = "family.cloudflare-dns.com"

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    /** null = check nahi hua / internet nahi, true = DNS filter chal raha, false = nahi chal raha */
    @Volatile private var dnsOk: Boolean? = null

    private val dpm by lazy { getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    private val adminComp by lazy { ComponentName(this, AdminReceiver::class.java) }

    // Views
    private lateinit var noteText: TextView
    private lateinit var streakNum: TextView
    private lateinit var streakBest: TextView
    private lateinit var rowDns: StatusRow
    private lateinit var rowAdmin: StatusRow
    private lateinit var rowGuard: StatusRow
    private lateinit var rowVpn: StatusRow
    private lateinit var rowBattery: StatusRow
    private lateinit var restrictedHint: TextView
    private lateinit var lockTitle: TextView
    private lateinit var lockTimer: TextView
    private lateinit var lockSub: TextView
    private lateinit var requestBtn: TextView
    private lateinit var cancelBtn: TextView
    private lateinit var removeBtn: TextView
    private lateinit var logText: TextView
    private lateinit var browserText: TextView
    private lateinit var diagBtn: TextView
    private lateinit var statusPill: TextView
    private lateinit var streakUnit: TextView

    /** One status line: colored dot + title + value + optional action button. */
    private inner class StatusRow(parent: LinearLayout, title: String, fixLabel: String?, onFix: (() -> Unit)?) {
        val dot = Ui.text(this@MainActivity, "●", 12f)
        val name = Ui.text(this@MainActivity, title, 15f, Ui.TEXT, true)
        val value = Ui.text(this@MainActivity, "", 13f, Ui.MUTED)
        val fix: TextView? = if (fixLabel != null && onFix != null) Ui.text(this@MainActivity, fixLabel, 13f, Ui.BG, true).apply {
            background = Ui.rounded(this@MainActivity, Ui.GOLD, 20)
            val p = Ui.dp(this@MainActivity, 14)
            setPadding(p, p / 2, p, p / 2)
            setOnClickListener { onFix?.invoke() }
        } else null

        init {
            val c = this@MainActivity
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10))
            }
            val textCol = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 8), 0)
                addView(name)
                addView(value)
            }
            row.addView(dot)
            row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            fix?.let { row.addView(it) }
            parent.addView(row)
        }

        fun set(ok: Boolean?, text: String) {
            dot.setTextColor(when (ok) { true -> Ui.GREEN; false -> Ui.RED; null -> Ui.MUTED })
            value.text = text
            fix?.visibility = if (ok == true) View.GONE else View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.styleWindow(this)
        try {
            buildUi()
        } catch (e: Throwable) {
            showError(e)
        }
    }

    /** Show the error instead of crashing, so it can be reported. */
    private fun showError(e: Throwable) {
        handler.removeCallbacks(tick)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(Ui.text(this@MainActivity, "Something went wrong. Please share this screen:\n\n" + android.util.Log.getStackTraceString(e), 12f).apply {
                setPadding(40, 40, 40, 40)
            })
        })
    }

    /** Small uppercase section label above a card. */
    private fun section(col: LinearLayout, title: String) {
        col.addView(Ui.text(this, title.uppercase(), 12f, Ui.MUTED, true).apply {
            letterSpacing = 0.12f
            setPadding(Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 24), 0, 0)
        })
    }

    private fun buildUi() {
        val c = this
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(c, 20)
            setPadding(p, p + p / 2, p, p * 2)
        }

        // ---- Header ----
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Ui.text(c, "Focus Guard", 26f, Ui.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        statusPill = Ui.text(c, "", 12f, Ui.BG, true).apply {
            val p = Ui.dp(c, 12)
            setPadding(p, p / 2, p, p / 2)
        }
        header.addView(statusPill)
        col.addView(header)

        // ---- Streak ----
        val hero = Ui.card(c).apply {
            background = Ui.glass(c, 28, android.graphics.Color.parseColor("#337B5CFF"))
            val p = Ui.dp(c, 22)
            setPadding(p, p, p, p)
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(c, 20)
        }
        hero.addView(Ui.text(c, "CURRENT STREAK", 12f, Ui.MUTED, true).apply { letterSpacing = 0.12f })
        val numRow = LinearLayout(c).apply { gravity = Gravity.BOTTOM }
        streakNum = Ui.text(c, "0", 64f, Ui.TEXT, true)
        numRow.addView(streakNum)
        streakUnit = Ui.text(c, "days", 18f, Ui.MUTED).apply { setPadding(Ui.dp(c, 8), 0, 0, Ui.dp(c, 16)) }
        numRow.addView(streakUnit)
        hero.addView(numRow)
        streakBest = Ui.text(c, "", 13f, Ui.MUTED)
        hero.addView(streakBest)
        hero.addView(android.view.View(c).apply {
            setBackgroundColor(Ui.LINE)
            layoutParams = LinearLayout.LayoutParams(-1, Ui.dp(c, 1)).apply { topMargin = Ui.dp(c, 16); bottomMargin = Ui.dp(c, 14) }
        })
        noteText = Ui.text(c, "", 18f, Ui.GOLD, true).apply { setOnClickListener { editNote() } }
        hero.addView(noteText)
        val heroLinks = LinearLayout(c).apply { setPadding(0, Ui.dp(c, 12), 0, 0) }
        heroLinks.addView(Ui.text(c, "Edit note", 13f, Ui.BLUE, true).apply { setOnClickListener { editNote() } })
        heroLinks.addView(Ui.text(c, "     ", 13f))
        heroLinks.addView(Ui.text(c, "Reset streak", 13f, Ui.MUTED, true).apply { setOnClickListener { confirmReset() } })
        hero.addView(heroLinks)
        col.addView(hero)

        // ---- SOS ----
        col.addView(Ui.button(c, "I'm feeling an urge", Ui.SOS) {
            startActivity(Intent(c, SosActivity::class.java))
        }.apply {
            textSize = 17f
            val v = Ui.dp(c, 18)
            setPadding(v, v, v, v)
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(c, 16)
        })

        // ---- Rules ----
        section(col, "Today's rules")
        val rules = Ui.card(c).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(c, 8) }
        rules.addView(Ui.text(c, "Browser", 15f, Ui.TEXT, true))
        browserText = Ui.text(c, "", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 14)) }
        rules.addView(browserText)
        rules.addView(Ui.text(c, "Instagram", 15f, Ui.TEXT, true))
        rules.addView(Ui.text(c, "Allowed: Stories, Saved reels, DMs\nBlocked: Feed scrolling, Reels tab, Search", 13f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(c, 2), 0, 0)
        })
        col.addView(rules)

        // ---- Protection ----
        section(col, "Protection")
        val prot = Ui.card(c).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(c, 8) }
        rowDns = StatusRow(prot, "Private DNS", "Set up") { openDns() }
        rowAdmin = StatusRow(prot, "Device admin", "Enable") { openAdmin() }
        rowGuard = StatusRow(prot, "Guard service", "Turn on") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        rowBattery = StatusRow(prot, "Battery", "Allow") { openBattery() }
        rowVpn = StatusRow(prot, "VPN", null, null)
        restrictedHint = Ui.text(c, "Can't turn on the guard? Open Settings → Apps → Focus Guard → ⋮ → Allow restricted settings, then try again.", 12f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(c, 8), 0, 0)
        }
        prot.addView(restrictedHint)
        col.addView(prot)

        // ---- Lock ----
        section(col, "Lock")
        val lock = Ui.card(c).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(c, 8) }
        lockTitle = Ui.text(c, "", 17f, Ui.TEXT, true)
        lockTimer = Ui.text(c, "", 40f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(c, 4), 0, 0) }
        lockSub = Ui.text(c, "", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 4), 0, 0) }
        lock.addView(lockTitle)
        lock.addView(lockTimer)
        lock.addView(lockSub)
        requestBtn = Ui.button(c, "Request unlock", Ui.GLASS_BTN) { askUnlock() }
        cancelBtn = Ui.button(c, "Cancel request", Ui.GREEN, Ui.BG) {
            Lock.cancel(c); toast("Request cancelled. Well done."); refresh()
        }
        removeBtn = Ui.button(c, "Remove device admin", Ui.RED) {
            if (Lock.state(c) is Lock.State.Open) {
                dpm.removeActiveAdmin(adminComp)
                toast("Device admin removed. You can update or uninstall during this window.")
            }
            refresh()
        }
        lock.addView(requestBtn)
        lock.addView(cancelBtn)
        lock.addView(removeBtn)
        col.addView(lock)

        // ---- Diagnostics ----
        section(col, "Diagnostics")
        val logCard = Ui.card(c).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(c, 8) }
        diagBtn = Ui.text(c, "", 14f, Ui.GOLD, true).apply {
            setOnClickListener {
                Lock.startDiagnose(c)
                GuardService.log(c, "Diagnostics started (15 min). Blocking stays on.")
                toast("Diagnostics running. Open the screens you want to check.")
                refresh()
            }
        }
        val logHead = Ui.text(c, "Show activity log", 14f, Ui.BLUE, true).apply { setPadding(0, Ui.dp(c, 14), 0, 0) }
        logText = Ui.text(c, "", 12f, Ui.MUTED).apply { visibility = View.GONE; setPadding(0, Ui.dp(c, 10), 0, 0) }
        val copyLog = Ui.text(c, "Copy log", 14f, Ui.BLUE, true).apply {
            visibility = View.GONE
            setPadding(0, Ui.dp(c, 12), 0, 0)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", GuardService.readLog(c)))
                toast("Log copied.")
            }
        }
        logHead.setOnClickListener {
            val show = logText.visibility != View.VISIBLE
            logText.visibility = if (show) View.VISIBLE else View.GONE
            copyLog.visibility = logText.visibility
            logHead.text = if (show) "Hide activity log" else "Show activity log"
        }
        logCard.addView(diagBtn)
        logCard.addView(logHead)
        logCard.addView(copyLog)
        logCard.addView(logText)
        col.addView(logCard)

        setContentView(ScrollView(c).apply {
            background = Ui.screenBg(c)
            isFillViewport = true
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        checkDns()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun adminOn() = dpm.isAdminActive(adminComp)

    private fun guardOn(): Boolean {
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, GuardService::class.java)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun batteryOk(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)

    /** Realme Guard ko band na kare. Popup khulne ke liye guard 90 sec rukta hai. */
    @android.annotation.SuppressLint("BatteryLife")
    private fun openBattery() {
        if (batteryOk()) { toast("Battery is already unrestricted."); return }
        Lock.startGrace(this)
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")))
    }

    private fun vpnActive(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    } catch (e: Exception) { false }

    /**
     * Cloudflare Family adult sites ko 0.0.0.0 pe bhejta hai.
     * Ek adult domain lookup karke dekhte hain ki filter sach me chal raha hai ya nahi.
     */
    private fun checkDns() {
        Thread {
            dnsOk = try {
                InetAddress.getAllByName("pornhub.com").all { it.isAnyLocalAddress } // 0.0.0.0 ya :: = blocked
            } catch (e: Exception) {
                null // internet nahi / lookup fail
            }
            runOnUiThread { refresh() }
        }.start()
    }

    private fun openDns() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dns", dnsHost))
        when (dnsOk) {
            true -> toast("Private DNS is already active.")
            null -> { toast("Connect to the internet and try again."); checkDns() }
            false -> {
                // DNS nahi laga — guard ko 90 sec rok do taki dialog khul sake.
                Lock.startGrace(this)
                toast("Copied $dnsHost. Paste it into Private DNS.")
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        }
    }

    private fun openAdmin() {
        if (adminOn()) { toast("Device admin is already enabled."); return }
        Lock.startGrace(this) // admin abhi on nahi, toh activation screen khulne do
        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Prevents Focus Guard from being uninstalled directly. Removing it requires a 1-hour unlock.")
        })
    }

    /** Unlock se pehle note padhna padega, aur "Haan" 10 sec baad hi dabega. */
    private fun askUnlock() {
        if (Lock.isNight()) {
            toast("Unlock requests are unavailable from 11 PM to 6 AM.")
            return
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(Me.note(this))
            .setMessage("You have ${Me.streakDays(this)} days of progress.\n\nAn unlock takes 1 hour to start. If this is an urge, try the breathing exercise first.")
            .setPositiveButton("Request (10)", null)
            .setNegativeButton("Never mind", null)
            .create()
        dialog.setOnShowListener {
            val yes: Button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            yes.isEnabled = false
            val t = object : CountDownTimer(10_000, 1000) {
                override fun onTick(ms: Long) { yes.text = "Request (${ms / 1000 + 1})" }
                override fun onFinish() { yes.text = "Request"; yes.isEnabled = true }
            }.start()
            dialog.setOnDismissListener { t.cancel() }
            yes.setOnClickListener {
                if (!Lock.requestUnlock(this)) toast("Unlock requests are unavailable at night.")
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private fun editNote() {
        val input = EditText(this).apply { setText(Me.note(this@MainActivity)) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Your reason")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> Me.setNote(this, input.text.toString()); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Streak reset?")
            .setMessage("A slip is not a failure. Your streak restarts from 0; your best is kept.")
            .setPositiveButton("Reset") { _, _ -> Me.resetStreak(this); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refresh() {
        if (!::noteText.isInitialized) return
        try {
            refreshUnsafe()
        } catch (e: Throwable) {
            showError(e)
        }
    }

    private fun refreshUnsafe() {
        noteText.text = Me.note(this)

        val days = Me.streakDays(this)
        streakNum.text = days.toString()
        streakUnit.text = if (days == 1L) "day" else "days"
        streakBest.text = "Best: ${Me.bestDays(this)} days"

        val free = Rules.browserFreeLeftMs(this)
        browserText.text = when {
            Rules.isBrowserNight() -> "Blocked until 7 AM"
            free > 0 -> "Unlocked · ${Lock.format(free)} left"
            else -> "Puzzle required (${Rules.PUZZLE_COUNT} questions) · Off from 8 PM to 7 AM"
        }

        rowDns.set(dnsOk, when (dnsOk) {
            true -> "Active · adult sites blocked"
            false -> "Not set"
            null -> "Can't check (offline?)"
        })
        val admin = adminOn()
        val guard = guardOn()
        rowAdmin.set(admin, if (admin) "Enabled" else "Disabled")
        rowGuard.set(guard, if (guard) "Running" else "Off")
        val bat = batteryOk()
        rowBattery.set(bat, if (bat) "Unrestricted" else "Restricted · the system may stop the guard")
        val vpn = vpnActive()
        rowVpn.set(!vpn, if (vpn) "Active · DNS filter bypassed" else "Off")
        restrictedHint.visibility = if (guard) View.GONE else View.VISIBLE

        val night = Lock.isNight()
        when (val s = Lock.state(this)) {
            is Lock.State.Locked -> {
                lockTitle.text = "Locked"
                lockTimer.visibility = View.GONE
                lockSub.text = if (night) "Unlock requests are unavailable from 11 PM to 6 AM." else "Uninstall, device admin, DNS and VPN settings are locked."
                requestBtn.visibility = if (night) View.GONE else View.VISIBLE
                cancelBtn.visibility = View.GONE
                removeBtn.visibility = View.GONE
            }
            is Lock.State.Waiting -> {
                lockTitle.text = "Unlock pending"
                lockTimer.visibility = View.VISIBLE
                lockTimer.text = Lock.format(s.leftMs)
                lockTimer.setTextColor(Ui.GOLD)
                lockSub.text = "Feeling better? Cancel the request."
                requestBtn.visibility = View.GONE
                cancelBtn.visibility = View.VISIBLE
                removeBtn.visibility = View.GONE
            }
            is Lock.State.Open -> {
                lockTitle.text = "Unlocked"
                lockTimer.visibility = View.VISIBLE
                lockTimer.text = Lock.format(s.leftMs)
                lockTimer.setTextColor(Ui.RED)
                lockSub.text = "Locks again automatically."
                requestBtn.visibility = View.GONE
                cancelBtn.visibility = View.VISIBLE
                removeBtn.visibility = if (admin) View.VISIBLE else View.GONE
            }
        }

        val test = Lock.graceLeftMs(this)
        if (test > 0) lockSub.text = "Test mode · ${Lock.format(test)} · logging only\n" + lockSub.text

        val diag = Lock.diagnoseLeftMs(this)
        diagBtn.text = if (diag > 0) "Diagnostics running · ${Lock.format(diag)}" else "Run diagnostics (15 min)"
        logText.text = GuardService.readLog(this).ifBlank { "No activity yet." }

        val allOk = dnsOk == true && admin && guard && !vpn
        statusPill.text = if (allOk) "PROTECTED" else "ACTION NEEDED"
        statusPill.background = Ui.rounded(this, if (allOk) Ui.GREEN else Ui.GOLD, 20)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
