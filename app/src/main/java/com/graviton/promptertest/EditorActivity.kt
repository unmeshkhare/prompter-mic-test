package com.graviton.promptertest

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

class EditorActivity : Activity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private lateinit var store: ScriptStore
    private lateinit var etTitle: EditText
    private lateinit var etBody: EditText
    private var doc: ScriptDoc? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

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
        val id = intent.getStringExtra(EXTRA_ID)
        if (id != null) doc = store.get(id)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(26), dp(14), dp(14))

        etTitle = EditText(this)
        etTitle.hint = "Title"
        etTitle.setSingleLine(true)
        etTitle.setText(doc?.title ?: "")
        col.addView(etTitle)

        etBody = EditText(this)
        etBody.hint = "Paste or type your script here.\n\nTip: [text in square brackets] and lines starting with // are shown dimly and never need to be spoken."
        etBody.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        etBody.gravity = Gravity.TOP
        etBody.setText(doc?.text ?: "")
        etBody.setTextColor(Color.rgb(25, 28, 35))
        col.addView(etBody, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
                row.addView(button("Paste") { paste() }, weight())
        row.addView(button("Save") { if (save()) finish() }, weight())
        row.addView(button("Save & prompt") {
            val d = saveDoc()
            if (d != null && Launcher.startPrompter(this, d.id)) finish()
        }, weight())
        col.addView(row)
        setContentView(col)
    }

    private fun paste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (t.isNullOrEmpty()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val start = etBody.selectionStart.coerceAtLeast(0)
        val end = etBody.selectionEnd.coerceAtLeast(0)
        etBody.text.replace(minOf(start, end), maxOf(start, end), t)
    }

    private fun saveDoc(): ScriptDoc? {
        val body = etBody.text.toString()
        if (body.isBlank()) {
            Toast.makeText(this, "The script is empty", Toast.LENGTH_SHORT).show()
            return null
        }
        var title = etTitle.text.toString().trim()
        if (title.isEmpty()) title = body.trim().lines().first().take(40)
        val d = doc ?: ScriptDoc(store.newId(), title, body, 0L)
        d.title = title
        d.text = body
        store.save(d)
        doc = d
        return d
    }

    private fun save(): Boolean = saveDoc() != null
}
