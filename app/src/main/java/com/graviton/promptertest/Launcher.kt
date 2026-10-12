package com.graviton.promptertest

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast

object Launcher {
    fun hasMic(c: Context) =
        c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasOverlay(c: Context) = Settings.canDrawOverlays(c)

    fun hasA11y(c: Context): Boolean {
        val s = Settings.Secure.getString(
            c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.contains(c.packageName + "/")
    }

    /** Starts the floating prompter for a script. Returns false if setup is incomplete. */
    fun startPrompter(a: Activity, id: String): Boolean {
        if (!hasMic(a)) {
            Toast.makeText(a, "Allow the microphone first (Setup section)", Toast.LENGTH_LONG).show()
            return false
        }
        if (!hasOverlay(a)) {
            Toast.makeText(a, "Allow 'display over other apps' first (Setup section)", Toast.LENGTH_LONG).show()
            return false
        }
        if (!hasA11y(a)) {
            Toast.makeText(
                a, "Accessibility service is off: the camera app may mute the microphone.",
                Toast.LENGTH_LONG
            ).show()
        }
        a.startForegroundService(
            Intent(a, OverlayService::class.java).putExtra(OverlayService.EXTRA_SCRIPT, id)
        )
        a.moveTaskToBack(true)
        return true
    }
}
