package com.graviton.promptertest

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * Keeps track of the accessibility service (the thing that stops Android muting the microphone
 * while the camera records) and, when allowed, switches it back on by itself.
 */
object A11y {
    const val OK = 0        // switched on and running
    const val OFF = 1       // not switched on in Accessibility settings
    const val STALLED = 2   // switched on, but Android has stopped running it

    private const val SETTING = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

    fun isEnabled(c: Context): Boolean {
        val s = Settings.Secure.getString(c.contentResolver, SETTING) ?: return false
        return s.split(':').any { it.startsWith(c.packageName + "/") }
    }

    fun state(c: Context): Int {
        if (!isEnabled(c)) return OFF
        return if (MicPriorityService.connected) OK else STALLED
    }

    /** True once "adb shell pm grant ... WRITE_SECURE_SETTINGS" has been run for this app. */
    fun canWriteSecure(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private var lastFix = 0L

    private fun put(c: Context, list: List<String>) {
        val cr = c.contentResolver
        Settings.Secure.putString(cr, SETTING, list.joinToString(":"))
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    /**
     * Needs the one-time WRITE_SECURE_SETTINGS grant. Switches the service on if it is off and,
     * if allowRestart, switches it off and on again when Android has stopped running it.
     * Returns true if it did something. Rate-limited to once every 15 seconds.
     */
    fun autoFix(context: Context, allowRestart: Boolean = true): Boolean {
        val c = context.applicationContext
        if (!canWriteSecure(c)) return false
        val st = state(c)
        if (st == OK) return false
        if (st == STALLED && !allowRestart) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastFix < 15000) return false
        lastFix = now
        return try {
            val cn = ComponentName(c, MicPriorityService::class.java).flattenToString()
            val cur = Settings.Secure.getString(c.contentResolver, SETTING) ?: ""
            val others = cur.split(':').filter { it.isNotEmpty() && !it.startsWith(c.packageName + "/") }
            if (st == STALLED) {
                put(c, others) // off ...
                Handler(Looper.getMainLooper()).postDelayed({
                    try { put(c, others + cn) } catch (e: Exception) { }
                }, 600) // ... and on again, so Android starts it afresh
            } else {
                put(c, others + cn)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openSettings(c: Context) {
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            c.startActivity(i)
        } catch (e: Exception) {
        }
    }
}
