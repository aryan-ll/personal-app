package com.ary.focusguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/** Device Admin on ho toh app seedha uninstall nahi hota. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Ruko. Ye urge 1 ghante me utar jayega. Focus Guard app me 'Unlock request' dabao."
}
