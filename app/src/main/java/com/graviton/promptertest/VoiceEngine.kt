package com.graviton.promptertest

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import com.graviton.promptertest.core.TextProc
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Offline English speech recognition (Vosk). Emits the words heard so far in the current phrase,
 * again and again as it grows, which is exactly what the aligner needs.
 */
class VoiceEngine(private val context: Context, private val onText: (String) -> Unit) {

    companion object {
        private const val SR = 16000
    }

    @Volatile var model: Model? = null
    @Volatile var lang: String = ""

    /** Hindi: sound key -> Devanagari words of the model vocabulary that have that key (shortest first). */
    @Volatile private var hiIndex: Map<String, List<String>>? = null
    @Volatile var loadError: String? = null
    @Volatile var audioError: String? = null
    @Volatile var rms = 0.0
    @Volatile var peak = 0
    @Volatile var zeroSince = 0L
    @Volatile var heard = ""
    @Volatile var lagMs = 0L

    @Volatile private var running = false
    private var thread: Thread? = null

    var onModelReady: (() -> Unit)? = null

    /** Makes sure the model for this language ("en" or "hi") is the one loaded (loads it if not). */
    fun ensureLanguage(wanted: String) {
        if (wanted == lang && (model != null || loadError == null)) return
        stop()
        val old = model
        model = null
        old?.close()
        loadError = null
        lang = wanted
        val asset = if (wanted == "hi") "model-hi" else "model-en-us"
        val target = if (wanted == "hi") "model-hi-dir" else "model"
        try {
            StorageService.unpack(
                context, asset, target,
                { m: Model ->
                    if (wanted != lang) {
                        m.close()
                    } else if (wanted == "hi") {
                        // read the model's word list once (off the main thread) so the recogniser can be
                        // steered towards the words of the script, which is what keeps it fast
                        Thread {
                            hiIndex = try { buildHindiIndex() } catch (e: Exception) { null }
                            if (wanted == lang) {
                                model = m
                                onModelReady?.invoke()
                            } else {
                                m.close()
                            }
                        }.start()
                    } else {
                        model = m
                        onModelReady?.invoke()
                    }
                },
                { e: IOException ->
                    if (wanted == lang) loadError = e.message ?: "could not load the speech model"
                }
            )
        } catch (e: Exception) {
            loadError = e.message ?: "could not load the speech model"
        }
    }

    private fun buildHindiIndex(): Map<String, List<String>> {
        val idx = HashMap<String, ArrayList<String>>()
        context.assets.open("model-hi/graph/words.txt").bufferedReader(Charsets.UTF_8).use { r ->
            r.forEachLine { line ->
                val w = line.split(' ', '\t').firstOrNull() ?: ""
                if (w.isNotEmpty() && w[0] in '\u0900'..'\u097F') {
                    val k = TextProc.hiKey(w)
                    if (k.isNotEmpty()) idx.getOrPut(k) { ArrayList() }.add(w)
                }
            }
        }
        for (l in idx.values) l.sortBy { it.length }
        return idx
    }

    /** Devanagari words to listen for: every model word that sounds like a script word. null = no restriction. */
    fun hindiGrammar(keys: Collection<String>, scriptWords: Collection<String>): List<String>? {
        val idx = hiIndex ?: return null
        val out = LinkedHashSet<String>()
        for (w in scriptWords) if (w.isNotEmpty() && w[0] in '\u0900'..'\u097F') out.add(w)
        for (k in keys) {
            val c = idx[k] ?: continue
            for (i in 0 until minOf(60, c.size)) out.add(c[i])
        }
        return if (out.isEmpty()) null else ArrayList(out)
    }

    fun stop() {
        running = false
        try {
            thread?.join(1500)
        } catch (ignored: InterruptedException) {
        }
        thread = null
    }

    fun release() {
        stop()
        model?.close()
        model = null
    }

    /** grammarWords: if given, recognition is steered towards these words (plus [unk]). */
    fun start(sourceId: Int, grammarWords: List<String>?): Boolean {
        stop()
        val m = model ?: return false
        audioError = null
        heard = ""
        zeroSince = 0L
        peak = 0
        rms = 0.0
        lagMs = 0L

        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec: AudioRecord = try {
            AudioRecord(
                sourceId, SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SR * 2)
            )
        } catch (e: Exception) {
            audioError = "Microphone error: ${e.message}"
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            audioError = "Microphone could not start"
            rec.release()
            return false
        }

        running = true
        val t = Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (ignored: Exception) {
            }
            var recognizer: Recognizer? = null
            try {
                val rcg: Recognizer = if (grammarWords != null && grammarWords.isNotEmpty()) {
                    val arr = JSONArray()
                    for (w in grammarWords) arr.put(w)
                    arr.put("[unk]")
                    Recognizer(m, SR.toFloat(), arr.toString())
                } else {
                    Recognizer(m, SR.toFloat())
                }
                recognizer = rcg
                rec.startRecording()
                val buf = ShortArray(1600) // 100 ms
                var lastPartial = ""
                val wall0 = SystemClock.elapsedRealtime()
                var samplesDone = 0L
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) {
                        if (n < 0) {
                            audioError = "Microphone read error $n"
                            break
                        }
                        continue
                    }
                    samplesDone += n
                    var p = 0
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = abs(buf[i].toInt())
                        if (v > p) p = v
                        sum += v.toDouble() * v
                    }
                    peak = p
                    rms = sqrt(sum / n)
                    if (p == 0) {
                        if (zeroSince == 0L) zeroSince = SystemClock.elapsedRealtime()
                    } else {
                        zeroSince = 0L
                    }

                    if (rcg.acceptWaveForm(buf, n)) {
                        val text = JSONObject(rcg.result).optString("text")
                        lastPartial = ""
                        if (text.isNotBlank()) {
                            heard = text
                            onText(text)
                        }
                    } else {
                        val part = if (lagMs > 400L) lastPartial else JSONObject(rcg.partialResult).optString("partial")
                        if (part != lastPartial) {
                            lastPartial = part
                            if (part.isNotBlank()) {
                                heard = part
                                onText(part)
                            }
                        }
                    }
                    // how far processing is behind real time (0 = keeping up)
                    lagMs = max(0L, (SystemClock.elapsedRealtime() - wall0) - samplesDone * 1000L / SR)
                }
            } catch (e: Exception) {
                audioError = "Audio error: ${e.message}"
            } finally {
                try { rec.stop() } catch (ignored: Exception) {}
                rec.release()
                try { recognizer?.close() } catch (ignored: Exception) {}
            }
        }
        thread = t
        t.start()
        return true
    }
}
