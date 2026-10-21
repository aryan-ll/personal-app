package com.ary.focusguard

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.InetAddress

class MainActivity : Activity() {

    private val dnsHost = "family.cloudflare-dns.com"

    private lateinit var status: TextView
    private lateinit var lockText: TextView
    private lateinit var dnsBtn: Button
    private lateinit var adminBtn: Button
    private lateinit var accBtn: Button
    private lateinit var requestBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var removeBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    /** null = check nahi hua / internet nahi, true = DNS filter chal raha, false = nahi chal raha */
    @Volatile private var dnsOk: Boolean? = null

    private val dpm by lazy { getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    private val adminComp by lazy { ComponentName(this, AdminReceiver::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; setPadding(0, pad / 2, 0, pad / 2)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }.also { col.addView(it) }

        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s; setOnClickListener { onClick() }
        }.also { col.addView(it) }

        text("Focus Guard", 24f, true)
        status = text("")

        text("Setup (isi order me)", 18f, true)
        text("0. Pehle: Settings → Apps → Focus Guard → ⋮ (upar 3 dots) → \"Allow restricted settings\". Android 13+ pe iske bina Accessibility on nahi hoti.", 13f)

        dnsBtn = button("1. Private DNS set karo (hostname copy hoga)") { openDns() }
        adminBtn = button("2. Device Admin on karo") { openAdmin() }
        accBtn = button("3. Accessibility me Focus Guard on karo") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        text("Lock", 18f, true)
        lockText = text("")
        requestBtn = button("Unlock request (1 ghanta wait)") { Lock.requestUnlock(this); refresh() }
        cancelBtn = button("Request cancel karo (shabaash 💪)") { Lock.cancel(this); refresh() }
        removeBtn = button("Device Admin hatao (sirf unlock ke time)") {
            if (Lock.state(this) is Lock.State.Open) {
                dpm.removeActiveAdmin(adminComp)
                toast("Admin hata diya. Ab 10 min me uninstall kar sakte ho.")
            }
            refresh()
        }

        setContentView(ScrollView(this).apply { addView(col) })
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
            true -> { toast("DNS pehle se laga hai. Kuch karne ki zarurat nahi."); return }
            null -> { toast("Internet on karo, phir try karo."); checkDns(); return }
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

    private fun refresh() {
        val dns = when (dnsOk) { true -> "✅ chal raha"; false -> "❌ nahi laga"; null -> "… check nahi hua (internet?)" }
        val admin = if (adminOn()) "✅ on" else "❌ off"
        val guard = if (guardOn()) "✅ on" else "❌ off"
        status.text = "Private DNS filter: $dns\nDevice Admin: $admin\nGuard (Accessibility): $guard"

        dnsBtn.visibility = if (dnsOk == true) View.GONE else View.VISIBLE
        adminBtn.visibility = if (adminOn()) View.GONE else View.VISIBLE
        accBtn.visibility = if (guardOn()) View.GONE else View.VISIBLE

        when (val s = Lock.state(this)) {
            is Lock.State.Locked -> {
                lockText.text = "🔒 Locked. Settings/uninstall/Reels band."
                requestBtn.visibility = View.VISIBLE
                cancelBtn.visibility = View.GONE
                removeBtn.visibility = View.GONE
            }
            is Lock.State.Waiting -> {
                lockText.text = "⏳ Unlock me baaki: ${Lock.format(s.leftMs)}\nUrge utar gaya? Cancel daba do."
                requestBtn.visibility = View.GONE
                cancelBtn.visibility = View.VISIBLE
                removeBtn.visibility = View.GONE
            }
            is Lock.State.Open -> {
                lockText.text = "🔓 Khula hai, ${Lock.format(s.leftMs)} ke liye. Phir apne aap lock."
                requestBtn.visibility = View.GONE
                cancelBtn.visibility = View.VISIBLE
                removeBtn.visibility = if (adminOn()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
