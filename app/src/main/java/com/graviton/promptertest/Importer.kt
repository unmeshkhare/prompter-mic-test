package com.graviton.promptertest

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Reads .txt / .md / .docx scripts picked with the system file picker (all offline). */
object Importer {

    /** Returns (title, text) or null if the file could not be read. */
    fun read(context: Context, uri: Uri): Pair<String, String>? {
        var name = "Imported script"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) {
                        val n = c.getString(i)
                        if (n != null) name = n
                    }
                }
            }
        } catch (e: Exception) {
            // keep default name
        }
        val title = name.substringBeforeLast('.', name)
        val isDocx = name.lowercase().endsWith(".docx")
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        val text = try {
            stream.use { s -> if (isDocx) docxText(s) else s.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            return null
        }
        return Pair(title, text)
    }

    private fun docxText(input: InputStream): String {
        val zip = ZipInputStream(input)
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == "word/document.xml") {
                val xml = zip.readBytes().toString(Charsets.UTF_8)
                return parseDocumentXml(xml)
            }
            entry = zip.nextEntry
        }
        return ""
    }

    private fun parseDocumentXml(xml: String): String {
        val paraRe = Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL)
        val partRe = Regex("<w:t(?: [^>]*)?>(.*?)</w:t>|<w:tab/>|<w:br/>", RegexOption.DOT_MATCHES_ALL)
        val paragraphs = ArrayList<String>()
        for (p in paraRe.findAll(xml)) {
            val sb = StringBuilder()
            for (m in partRe.findAll(p.value)) {
                val whole = m.value
                if (whole.startsWith("<w:tab")) sb.append(' ')
                else if (whole.startsWith("<w:br")) sb.append('\n')
                else sb.append(unescape(m.groupValues[1]))
            }
            val line = sb.toString().trim()
            if (line.isNotEmpty()) paragraphs.add(line)
        }
        return paragraphs.joinToString("\n\n")
    }

    private fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
}
