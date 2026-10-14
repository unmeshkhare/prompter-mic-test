package com.graviton.promptertest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a restart or an app update, switch the accessibility service back on (if the one-time grant was given). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        A11y.autoFix(context, false)
    }
}
