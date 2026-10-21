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
    private lateinit var restrictedHint: TextView
    private lateinit var lockTitle: TextView
    private lateinit var lockTimer: TextView
    private lateinit var lockSub: TextView
    private lateinit var requestBtn: TextView
    private lateinit var cancelBtn: TextView
    private lateinit var removeBtn: TextView
    private lateinit var logText: TextView

    /** Ek status line: rang wala dot + text + zarurat ho toh button. */
    private inner class StatusRow(parent: LinearLayout, title: String, fixLabel: String?, onFix: (() -> Unit)?) {
        val dot = Ui.text(this@MainActivity, "●", 16f)
        val label = Ui.text(this@MainActivity, title, 15f)
        val fix: TextView? = if (fixLabel != null && onFix != null) Ui.text(this@MainActivity, fixLabel, 13f, Ui.BG, true).apply {
            background = Ui.rounded(this@MainActivity, Ui.GOLD, 10)
            val p = Ui.dp(this@MainActivity, 8)
            setPadding(p + p / 2, p / 2 + 2, p + p / 2, p / 2 + 2)
            setOnClickListener { onFix?.invoke() }
        } else null

        init {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 6))
            }
            label.setPadding(Ui.dp(this@MainActivity, 10), 0, 0, 0)
            row.addView(dot)
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            fix?.let { row.addView(it) }
            parent.addView(row)
        }

        fun set(ok: Boolean?, text: String) {
            dot.setTextColor(when (ok) { true -> Ui.GREEN; false -> Ui.RED; null -> Ui.MUTED })
            label.text = text
            fix?.visibility = if (ok == true) View.GONE else View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        try {
            buildUi()
        } catch (e: Throwable) {
            showError(e)
        }
    }

    /** Crash ki jagah error screen pe dikhao, taki screenshot bhej sako. */
    private fun showError(e: Throwable) {
        handler.removeCallbacks(tick)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(Ui.text(this@MainActivity, "Error (iska screenshot bhejo):\n\n" + android.util.Log.getStackTraceString(e), 12f).apply {
                setPadding(40, 40, 40, 40)
            })
        })
    }

    private fun buildUi() {
        val c = this
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(c, 16)
            setPadding(p, p * 2, p, p * 2)
        }

        // ---- Header ----
        col.addView(Ui.text(c, "FOCUS GUARD", 13f, Ui.MUTED, true).apply { letterSpacing = 0.2f })
        noteText = Ui.text(c, "", 22f, Ui.GOLD, true).apply {
            setPadding(0, Ui.dp(c, 6), 0, 0)
            setOnClickListener { editNote() }
        }
        col.addView(noteText)
        col.addView(Ui.text(c, "✏️ note badalne ke liye tap karo", 12f, Ui.MUTED))

        // ---- Streak ----
        val streak = Ui.card(c).apply { gravity = Gravity.CENTER_HORIZONTAL }
        streakNum = Ui.text(c, "0", 64f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }
        streak.addView(streakNum)
        streak.addView(Ui.text(c, "din clean", 16f, Ui.MUTED).apply { gravity = Gravity.CENTER })
        streakBest = Ui.text(c, "", 13f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(c, 8), 0, 0)
        }
        streak.addView(streakBest)
        streak.addView(Ui.text(c, "Fisal gaya? Reset karo", 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(c, 10), 0, 0)
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { confirmReset() }
        })
        col.addView(streak)

        // ---- SOS ----
        col.addView(Ui.button(c, "🆘  Urge aa raha hai", Ui.SOS) {
            startActivity(Intent(c, SosActivity::class.java))
        }.apply {
            textSize = 18f
            val v = Ui.dp(c, 20)
            setPadding(v, v, v, v)
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(c, 14)
        })

        // ---- Protection ----
        val prot = Ui.card(c)
        prot.addView(Ui.text(c, "Suraksha", 17f, Ui.TEXT, true).apply { setPadding(0, 0, 0, Ui.dp(c, 6)) })
        rowDns = StatusRow(prot, "Private DNS", "Set karo") { openDns() }
        rowAdmin = StatusRow(prot, "Device Admin", "On karo") { openAdmin() }
        rowGuard = StatusRow(prot, "Guard", "On karo") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        rowVpn = StatusRow(prot, "VPN", null, null)
        restrictedHint = Ui.text(c, "Guard on na ho toh pehle: Settings → Apps → Focus Guard → ⋮ → \"Allow restricted settings\".", 12f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(c, 8), 0, 0)
        }
        prot.addView(restrictedHint)
        col.addView(prot)

        // ---- Lock ----
        val lock = Ui.card(c)
        lockTitle = Ui.text(c, "", 17f, Ui.TEXT, true)
        lockTimer = Ui.text(c, "", 40f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(c, 4), 0, 0) }
        lockSub = Ui.text(c, "", 13f, Ui.MUTED)
        lock.addView(lockTitle)
        lock.addView(lockTimer)
        lock.addView(lockSub)
        requestBtn = Ui.button(c, "Unlock request (1 ghanta wait)", Ui.LINE) { askUnlock() }
        cancelBtn = Ui.button(c, "Request cancel karo  💪", Ui.GREEN, Ui.BG) {
            Lock.cancel(c); toast("Shabaash. Urge haar gaya."); refresh()
        }
        removeBtn = Ui.button(c, "Device Admin hatao", Ui.RED) {
            if (Lock.state(c) is Lock.State.Open) {
                dpm.removeActiveAdmin(adminComp)
                toast("Admin hata diya. Ab 10 min me uninstall/update kar sakte ho.")
            }
            refresh()
        }
        lock.addView(requestBtn)
        lock.addView(cancelBtn)
        lock.addView(removeBtn)
        col.addView(lock)

        // ---- Log (chhupa hua) ----
        val logCard = Ui.card(c)
        val logHead = Ui.text(c, "Guard log ▸", 14f, Ui.MUTED, true)
        logText = Ui.text(c, "", 12f, Ui.MUTED).apply { visibility = View.GONE; setPadding(0, Ui.dp(c, 8), 0, 0) }
        logHead.setOnClickListener {
            val show = logText.visibility != View.VISIBLE
            logText.visibility = if (show) View.VISIBLE else View.GONE
            logHead.text = if (show) "Guard log ▾" else "Guard log ▸"
        }
        logCard.addView(logHead)
        logCard.addView(logText)
        col.addView(logCard)

        setContentView(ScrollView(c).apply {
            setBackgroundColor(Ui.BG)
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
            true -> toast("DNS pehle se laga hai.")
            null -> { toast("Internet on karo, phir try karo."); checkDns() }
            false -> {
                // DNS nahi laga — guard ko 90 sec rok do taki dialog khul sake.
                Lock.startGrace(this)
                toast("Copy ho gaya: $dnsHost — Private DNS me paste karo")
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        }
    }

    private fun openAdmin() {
        if (adminOn()) { toast("Admin pehle se on hai."); return }
        Lock.startGrace(this) // admin abhi on nahi, toh activation screen khulne do
        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Isse app seedha uninstall nahi hoga. Hatane ke liye 1 ghanta wait karna padega.")
        })
    }

    /** Unlock se pehle note padhna padega, aur "Haan" 10 sec baad hi dabega. */
    private fun askUnlock() {
        if (Lock.isNight()) {
            toast("🌙 Raat 11 se subah 6 tak unlock band hai. So jao.")
            return
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(Me.note(this))
            .setMessage("${Me.streakDays(this)} din ki mehnat hai.\nSach me unlock chahiye? 1 ghanta wait hoga.\n\nUrge hai toh pehle 🆘 dabao.")
            .setPositiveButton("Haan (10)", null)
            .setNegativeButton("Nahi, rehne do", null)
            .create()
        dialog.setOnShowListener {
            val yes: Button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            yes.isEnabled = false
            val t = object : CountDownTimer(10_000, 1000) {
                override fun onTick(ms: Long) { yes.text = "Haan (${ms / 1000 + 1})" }
                override fun onFinish() { yes.text = "Haan"; yes.isEnabled = true }
            }.start()
            dialog.setOnDismissListener { t.cancel() }
            yes.setOnClickListener {
                if (!Lock.requestUnlock(this)) toast("🌙 Raat me unlock band hai.")
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private fun editNote() {
        val input = EditText(this).apply { setText(Me.note(this@MainActivity)) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Kyun chhodna hai?")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> Me.setNote(this, input.text.toString()); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Streak reset?")
            .setMessage("Koi baat nahi. Girna haar nahi, rukna haar hai. Phir se 0 se shuru.")
            .setPositiveButton("Reset") { _, _ -> Me.resetStreak(this); refresh() }
            .setNegativeButton("Nahi", null)
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
        streakBest.text = "Best: ${Me.bestDays(this)} din"

        rowDns.set(dnsOk, when (dnsOk) {
            true -> "Private DNS: chal raha"
            false -> "Private DNS: nahi laga"
            null -> "Private DNS: check nahi hua (internet?)"
        })
        val admin = adminOn()
        val guard = guardOn()
        rowAdmin.set(admin, if (admin) "Device Admin: on" else "Device Admin: off")
        rowGuard.set(guard, if (guard) "Guard: on" else "Guard: off")
        val vpn = vpnActive()
        rowVpn.set(!vpn, if (vpn) "VPN chalu hai! DNS bypass ho raha" else "VPN: band")
        restrictedHint.visibility = if (guard) View.GONE else View.VISIBLE

        val night = Lock.isNight()
        when (val s = Lock.state(this)) {
            is Lock.State.Locked -> {
                lockTitle.text = "🔒  Locked"
                lockTimer.visibility = View.GONE
                lockSub.text = if (night) "🌙 Raat 11–6: unlock request band hai." else "DNS, uninstall, admin aur VPN settings band hain."
                requestBtn.visibility = if (night) View.GONE else View.VISIBLE
                cancelBtn.visibility = View.GONE
                removeBtn.visibility = View.GONE
            }
            is Lock.State.Waiting -> {
                lockTitle.text = "⏳  Unlock me baaki"
                lockTimer.visibility = View.VISIBLE
                lockTimer.text = Lock.format(s.leftMs)
                lockTimer.setTextColor(Ui.GOLD)
                lockSub.text = "Urge utar gaya? Cancel daba do."
                requestBtn.visibility = View.GONE
                cancelBtn.visibility = View.VISIBLE
                removeBtn.visibility = View.GONE
            }
            is Lock.State.Open -> {
                lockTitle.text = "🔓  Khula hai"
                lockTimer.visibility = View.VISIBLE
                lockTimer.text = Lock.format(s.leftMs)
                lockTimer.setTextColor(Ui.RED)
                lockSub.text = "Iske baad apne aap lock."
                requestBtn.visibility = View.GONE
                cancelBtn.visibility = View.VISIBLE
                removeBtn.visibility = if (admin) View.VISIBLE else View.GONE
            }
        }

        val test = Lock.graceLeftMs(this)
        if (test > 0) lockSub.text = "🧪 TEST mode ${Lock.format(test)}: guard sirf log karega.\n" + lockSub.text

        logText.text = GuardService.readLog(this).ifBlank { "(abhi kuch nahi)" }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
