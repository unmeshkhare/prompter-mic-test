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

    fun hasA11y(c: Context): Boolean = A11y.state(c) == A11y.OK

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
            A11y.autoFix(a)
            Toast.makeText(
                a, "Accessibility service is not running: the camera app may mute the microphone. " +
                    "The prompter's top bar will show how to fix it.",
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
