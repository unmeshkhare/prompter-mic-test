package com.graviton.promptertest

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(28), dp(16), dp(28))

        val title = TextView(this)
        title.text = "Settings"
        title.textSize = 24f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Color.rgb(30, 33, 40))
        col.addView(title)
        col.addView(note("Changes apply instantly to a running prompter."))

        addSeek("Text size", 14, 80, prefs.fontSp, " sp") { prefs.fontSp = it }
        addSeek("Reading line position (from top)", 10, 70, prefs.readingLine, " %") { prefs.readingLine = it }
        addSeek("Window background opacity", 0, 100, prefs.opacity, " %") { prefs.opacity = it }

        addSpinner(
            "Theme", Themes.all.map { it.label },
            Themes.all.indexOfFirst { it.key == prefs.theme }.coerceAtLeast(0)
        ) { prefs.theme = Themes.all[it].key }

        addSpinner(
            "Highlight colour", Themes.highlightPresets.map { it.first },
            Themes.highlightPresets.indexOfFirst { it.second == prefs.highlight }.coerceAtLeast(0)
        ) { prefs.highlight = Themes.highlightPresets[it].second }

        addSpinner(
            "Microphone mode (only change if voice following misbehaves)",
            MicSources.list.map { it.first }, prefs.micIdx.coerceIn(0, MicSources.list.size - 1)
        ) { prefs.micIdx = it }

        addCheck(
            "Script-aware recognition (beta): steer the recogniser towards words in your script",
            prefs.scriptAware
        ) { prefs.scriptAware = it }
        addCheck("Show what I hear in the prompter's top bar", prefs.showHeard) { prefs.showHeard = it }

        val reset = Button(this)
        reset.text = "Reset window position and size"
        reset.isAllCaps = false
        reset.setOnClickListener {
            prefs.resetWindow()
            Toast.makeText(this, "Done. Applies the next time the prompter opens.", Toast.LENGTH_LONG).show()
        }
        col.addView(reset)

        val scroll = ScrollView(this)
        scroll.addView(col)
        setContentView(scroll)
    }

    private fun note(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 13f
        v.setTextColor(Color.rgb(100, 105, 120))
        return v
    }

    private fun heading(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 15f
        v.setTextColor(Color.rgb(30, 33, 40))
        v.setPadding(0, dp(16), 0, dp(2))
        return v
    }

    private fun addSeek(label: String, min: Int, max: Int, value: Int, unit: String, onChange: (Int) -> Unit) {
        val tv = heading("$label: $value$unit")
        col.addView(tv)
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = (value - min).coerceIn(0, max - min)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + min
                tv.text = "$label: $v$unit"
                if (fromUser) onChange(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addSpinner(label: String, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        col.addView(heading(label))
        val sp = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = adapter
        sp.setSelection(selected)
        var first = true
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (first) { first = false; return }
                onPick(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        col.addView(sp)
    }

    private fun addCheck(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val cb = CheckBox(this)
        cb.text = label
        cb.isChecked = value
        cb.setPadding(0, dp(10), 0, dp(4))
        cb.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        col.addView(cb)
    }
}
