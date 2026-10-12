package com.graviton.promptertest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotif() =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun hasOverlay() = Settings.canDrawOverlays(this)

    private fun hasA11y(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.contains("$packageName/")
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8)
        b.layoutParams = lp
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(24), dp(16), dp(24))

        val title = TextView(this)
        title.text = "Prompter mic test"
        title.textSize = 24f
        col.addView(title)

        val intro = TextView(this)
        intro.text =
            "This little app answers one question: can a floating box keep hearing you " +
                "(offline, English) while your camera app is recording video?\n\n" +
                "1. Give the permissions below (including step 4, the accessibility service).\n" +
                "2. Tap START. Wait until the floating box says the model is ready, then talk. " +
                "You should see your words appear.\n" +
                "3. Press Home, open your camera app, switch to VIDEO, press record and keep talking.\n" +
                "4. Watch the floating box: does it stay LIVE and keep showing your words?\n" +
                "5. If it goes SILENT, tap \"Switch source\" in the box and try again with the next one.\n" +
                "6. Screenshot the box (with its little log) and send it to me."
        intro.textSize = 15f
        intro.setPadding(0, dp(8), 0, dp(8))
        col.addView(intro)

        status = TextView(this)
        status.textSize = 16f
        status.setPadding(0, dp(8), 0, dp(8))
        col.addView(status)

        col.addView(makeButton("1. Allow microphone") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })
        col.addView(makeButton("2. Allow notifications (recommended)") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        })
        col.addView(makeButton("3. Allow display over other apps") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })
        col.addView(makeButton("4. Turn on accessibility service (the mic fix to test)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(makeButton("START test overlay") { startTest() })
        col.addView(makeButton("Stop") {
            startService(
                Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)
            )
        })

        val xiaomi = TextView(this)
        xiaomi.text =
            "\nXiaomi / Poco tips (HyperOS):\n" +
                "- Settings > Apps > Manage apps > Prompter Mic Test > Battery saver > No restrictions.\n" +
                "- Same page: turn ON Autostart.\n" +
                "- Settings > Apps > Manage apps > Prompter Mic Test > Other permissions: allow " +
                "\"Display pop-up windows while running in the background\".\n" +
                "- Lock the app in the Recents screen (long-press the card > lock) so it is not killed."
        xiaomi.textSize = 13f
        col.addView(xiaomi)

        val scroll = ScrollView(this)
        scroll.addView(col)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        fun tick(ok: Boolean) = if (ok) "OK" else "NOT YET"
        status.text =
            "Microphone: ${tick(hasMic())}\n" +
                "Notifications: ${tick(hasNotif())}\n" +
                "Display over other apps: ${tick(hasOverlay())}\n" +
                "Accessibility service: ${tick(hasA11y())}"
    }

    private fun startTest() {
        if (!hasMic()) {
            Toast.makeText(this, "Allow the microphone first (button 1)", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasOverlay()) {
            Toast.makeText(this, "Allow display over other apps first (button 3)", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        moveTaskToBack(true)
    }
}
