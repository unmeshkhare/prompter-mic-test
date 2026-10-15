package com.graviton.promptertest

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val reqImport = 11
    private lateinit var store: ScriptStore
    private lateinit var content: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun label(text: String, size: Float, bold: Boolean = false, color: Int = Color.rgb(30, 33, 40)): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ScriptStore(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(28), dp(16), dp(28))
        val scroll = ScrollView(this)
        scroll.addView(content)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (A11y.state(this) != A11y.OK && A11y.autoFix(this)) {
            content.postDelayed({ refresh() }, 1800) // show the result once Android has restarted the service
        }
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
    }

    private fun refresh() {
        content.removeAllViews()
        content.addView(label("Voice Prompter", 26f, true))
        content.addView(label("Offline teleprompter that follows your voice.", 14f, false, Color.rgb(90, 95, 110)))

        // ---- setup
        content.addView(spacer(18))
        content.addView(label("Setup", 18f, true))
        setupRow("Microphone", Launcher.hasMic(this), "Allow") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        setupRow("Display over other apps (floating window)", Launcher.hasOverlay(this), "Allow") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        when (A11y.state(this)) {
            A11y.OK -> setupRow("Accessibility service (keeps the mic alive while the camera records)", true, "") {}
            A11y.OFF -> setupRow("Accessibility service: keeps the mic alive while the camera records", false, "Turn on") {
                A11y.openSettings(this)
            }
            else -> setupRow(
                "Accessibility service is switched on but Android has stopped it. Switch 'Prompter mic priority' off and on again.",
                false, "Open"
            ) { A11y.openSettings(this) }
        }
        if (A11y.canWriteSecure(this)) {
            content.addView(label("Auto-fix is ACTIVE: the app switches accessibility back on by itself.", 13f, false, Color.rgb(30, 130, 70)))
        } else {
            setupInfo("Optional: let the app fix accessibility by itself (one-time, needs a PC)", "Steps") { showAutoFixSteps() }
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        setupRow("Battery: no restrictions (stops Android killing the app)", pm.isIgnoringBatteryOptimizations(packageName), "Allow") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        setupInfo("Xiaomi: turn on Autostart for this app and lock it in the Recents screen", "Open") { openAutostart() }
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            setupRow("Notifications (optional)", ok, "Allow") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }

        // ---- actions
        content.addView(spacer(14))
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
                actions.addView(button("New script") {
            startActivity(Intent(this, EditorActivity::class.java))
        }, weight())
        actions.addView(button("Import file") { pickFile() }, weight())
        actions.addView(button("Settings") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, weight())
        content.addView(actions)

        // ---- scripts
        content.addView(spacer(10))
        content.addView(label("Your scripts", 18f, true))
        val scripts = store.list()
        if (scripts.isEmpty()) {
            content.addView(label("None yet. Tap New script (paste your text) or Import file (.txt or .docx). In the editor you can choose English or Hindi / Hinglish.", 14f, false, Color.rgb(90, 95, 110)))
        }
        for (s in scripts) addScriptRow(s)
    }

    private fun spacer(h: Int): TextView {
        val t = TextView(this)
        t.height = dp(h)
        return t
    }

    private fun setupRow(text: String, ok: Boolean, action: String, onClick: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val t = label((if (ok) "OK   " else "TO DO   ") + text, 14f, false,
            if (ok) Color.rgb(30, 130, 70) else Color.rgb(190, 60, 40))
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!ok) row.addView(button(action, onClick))
        content.addView(row)
    }

    private fun setupInfo(text: String, action: String, onClick: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(label(text, 13f, false, Color.rgb(90, 95, 110)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button(action, onClick))
        content.addView(row)
    }

    private val adbCommand = "adb shell pm grant com.graviton.promptertest android.permission.WRITE_SECURE_SETTINGS"

    private fun showAutoFixSteps() {
        AlertDialog.Builder(this)
            .setTitle("Make accessibility fix itself")
            .setMessage(
                "Do this once. Afterwards the app switches the accessibility service back on whenever Android turns it off.\n\n" +
                    "1. Phone: Settings > About phone > tap the OS version 7 times.\n" +
                    "2. Settings > Additional settings > Developer options: turn on USB debugging. " +
                    "On Xiaomi also turn on \"USB debugging (Security settings)\" (needs your Mi account).\n" +
                    "3. Connect the phone to your PC and allow the debugging prompt.\n" +
                    "4. On the PC, in a terminal with adb installed, run:\n\n" + adbCommand
            )
            .setPositiveButton("Copy command") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb", adbCommand))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openAutostart() {
        try {
            val i = Intent()
            i.component = ComponentName(
                "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            startActivity(i)
        } catch (e: Exception) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun addScriptRow(s: ScriptDoc) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(10), 0, dp(6))
        val words = s.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        box.addView(label(s.title.ifBlank { "Untitled" }, 16f, true))
        box.addView(label("$words words  -  " + (if (s.isHindi) "Hindi / Hinglish" else "English"), 12f, false, Color.rgb(110, 115, 130)))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
                row.addView(button("Prompt") { Launcher.startPrompter(this, s.id) }, weight())
        row.addView(button("Edit") {
            startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, s.id))
        }, weight())
        row.addView(button("Delete") {
            AlertDialog.Builder(this)
                .setTitle("Delete this script?")
                .setMessage(s.title)
                .setPositiveButton("Delete") { _, _ -> store.delete(s.id); refresh() }
                .setNegativeButton("Cancel", null)
                .show()
        }, weight())
        box.addView(row)
        content.addView(box)
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf(
                "text/plain", "text/markdown",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
        )
        startActivityForResult(i, reqImport)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqImport || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val r = Importer.read(this, uri)
        if (r == null || r.second.isBlank()) {
            Toast.makeText(this, "Could not read any text from that file", Toast.LENGTH_LONG).show()
            return
        }
        val doc = ScriptDoc(store.newId(), r.first, r.second, 0L)
        doc.lang = ScriptDoc.guessLang(r.second)
        store.save(doc)
        Toast.makeText(this, "Imported: ${r.first}" + (if (doc.isHindi) " (Hindi)" else ""), Toast.LENGTH_SHORT).show()
        refresh()
    }
}
