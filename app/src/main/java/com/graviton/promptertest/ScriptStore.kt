package com.graviton.promptertest

import android.content.Context
import org.json.JSONObject
import java.io.File

class ScriptDoc(val id: String, var title: String, var text: String, var ts: Long)

/** Scripts are kept as small JSON files inside the app's private folder. No internet involved. */
class ScriptStore(context: Context) {
    private val dir = File(context.filesDir, "scripts").also { it.mkdirs() }

    fun list(): List<ScriptDoc> {
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.name.endsWith(".json") }
            .mapNotNull { read(it) }
            .sortedByDescending { it.ts }
    }

    fun get(id: String): ScriptDoc? {
        val f = File(dir, "$id.json")
        return if (f.exists()) read(f) else null
    }

    fun save(doc: ScriptDoc) {
        doc.ts = System.currentTimeMillis()
        val o = JSONObject()
        o.put("id", doc.id)
        o.put("title", doc.title)
        o.put("text", doc.text)
        o.put("ts", doc.ts)
        File(dir, "${doc.id}.json").writeText(o.toString(), Charsets.UTF_8)
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + (100..999).random()

    private fun read(f: File): ScriptDoc? {
        return try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            ScriptDoc(o.getString("id"), o.optString("title", "Untitled"), o.optString("text", ""), o.optLong("ts", 0L))
        } catch (e: Exception) {
            null
        }
    }
}
