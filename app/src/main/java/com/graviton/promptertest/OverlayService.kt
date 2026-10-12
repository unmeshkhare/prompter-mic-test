package com.graviton.promptertest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Floating "mic test" box. Listens with an offline English Vosk model and shows
 * whether Android is feeding it real audio, even while a camera app is recording.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.graviton.promptertest.STOP"
        private const val CHANNEL = "mictest"
        private const val SAMPLE_RATE = 16000
    }

    private class Src(val label: String, val id: Int)

    private val sources = listOf(
        Src("VOICE_RECOGNITION", MediaRecorder.AudioSource.VOICE_RECOGNITION),
        Src("MIC", MediaRecorder.AudioSource.MIC),
        Src("CAMCORDER", MediaRecorder.AudioSource.CAMCORDER),
        Src("UNPROCESSED", MediaRecorder.AudioSource.UNPROCESSED),
        Src("VOICE_COMMUNICATION", MediaRecorder.AudioSource.VOICE_COMMUNICATION)
    )

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var am: AudioManager
    private var root: LinearLayout? = null
    private lateinit var lp: WindowManager.LayoutParams

    private lateinit var tvMic: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvHeard: TextView
    private lateinit var tvLog: TextView
    private lateinit var bar: ProgressBar

    private var model: Model? = null
    private var srcIdx = 0

    @Volatile private var running = false
    private var audioThread: Thread? = null

    // written by the audio thread, read by the UI tick
    @Volatile private var peak = 0
    @Volatile private var rms = 0.0
    @Volatile private var zeroSince = 0L
    @Volatile private var partial = ""
    @Volatile private var lastFinal = ""
    @Volatile private var audioErr: String? = null

    // written by the system callback (main thread)
    private var sysCount = 0
    private var sysSilenced: Boolean? = null

    private val t0 = SystemClock.elapsedRealtime()
    private val logLines = ArrayDeque<String>()
    private var lastState = ""
    private var uiPeak = 0.0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (root == null) {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            am = getSystemService(AUDIO_SERVICE) as AudioManager
            buildOverlay()
            am.registerAudioRecordingCallback(recordingCallback, main)
            main.post(tick)
            loadModel()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Mic test", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Prompter mic test is running")
            .setContentText("Tap Stop to close the floating box")
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun makeText(size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun buildOverlay() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(10), dp(8), dp(10), dp(10))
        val bg = GradientDrawable()
        bg.setColor(Color.argb(235, 18, 20, 28))
        bg.cornerRadius = dp(14).toFloat()
        box.background = bg

        // header: drag handle + close
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = makeText(13f, Color.rgb(160, 170, 200), true)
        title.text = "=  Mic test  (drag here)"
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = makeText(18f, Color.WHITE, true)
        close.text = "  X  "
        close.setOnClickListener { stopSelf() }
        header.addView(close)
        box.addView(header)

        tvMic = makeText(20f, Color.WHITE, true)
        tvMic.text = "Loading model..."
        box.addView(tvMic)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        box.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        )

        tvInfo = makeText(11f, Color.rgb(190, 195, 210))
        box.addView(tvInfo)

        tvHeard = makeText(18f, Color.rgb(255, 235, 150))
        tvHeard.text = "..."
        tvHeard.setPadding(0, dp(6), 0, dp(6))
        box.addView(tvHeard)

        val switchBtn = makeText(14f, Color.rgb(120, 200, 255), true)
        switchBtn.text = "[ Switch source ]"
        switchBtn.setPadding(0, dp(4), 0, dp(6))
        switchBtn.setOnClickListener {
            srcIdx = (srcIdx + 1) % sources.size
            addLog("Switched to ${sources[srcIdx].label}")
            startAudio()
        }
        box.addView(switchBtn)

        tvLog = makeText(10.5f, Color.rgb(150, 155, 170))
        box.addView(tvLog)

        lp = WindowManager.LayoutParams(
            dp(310),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(12)
        lp.y = dp(120)

        header.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = sx + (e.rawX - tx).toInt()
                        lp.y = sy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(box, lp)
                    }
                }
                return true
            }
        })

        wm.addView(box, lp)
        root = box
        addLog("Overlay started")
    }

    private fun addLog(msg: String) {
        val s = (SystemClock.elapsedRealtime() - t0) / 1000
        logLines.addLast(String.format("%02d:%02d  %s", s / 60, s % 60, msg))
        while (logLines.size > 7) logLines.removeFirst()
        if (root != null) tvLog.text = logLines.joinToString("\n")
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val zeroMs = if (zeroSince > 0) now - zeroSince else 0L
            val err = audioErr
            val state: String
            val color: Int
            when {
                err != null -> { state = "ERROR"; color = Color.rgb(255, 120, 120) }
                model == null -> { state = "LOADING MODEL"; color = Color.rgb(200, 200, 200) }
                !running -> { state = "IDLE"; color = Color.rgb(200, 200, 200) }
                zeroMs > 1000 -> { state = "SILENT (OS blocks mic?)"; color = Color.rgb(255, 90, 90) }
                peak < 300 -> { state = "LIVE but very quiet"; color = Color.rgb(255, 200, 80) }
                else -> { state = "LIVE - hearing you"; color = Color.rgb(110, 230, 130) }
            }
            tvMic.text = state
            tvMic.setTextColor(color)

            uiPeak = max(rms, uiPeak * 0.85)
            bar.progress = min(100, (sqrt(uiPeak / 32768.0) * 150).toInt())

            val silenced = when (sysSilenced) {
                null -> "n/a"
                true -> "YES"
                false -> "no"
            }
            tvInfo.text = "Source: ${sources[srcIdx].label}\n" +
                "Apps recording now: $sysCount   |   Android silenced me: $silenced"
            if (err != null) tvHeard.text = err
            else {
                val shown = if (partial.isNotBlank()) partial else lastFinal
                tvHeard.text = if (shown.isBlank()) "..." else shown
            }

            if (state != lastState) {
                if (lastState.isNotEmpty()) addLog(state)
                lastState = state
            }
            main.postDelayed(this, 200)
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val n = configs.size
            var sil: Boolean? = null
            if (Build.VERSION.SDK_INT >= 29) {
                sil = configs.any { it.isClientSilenced }
            }
            if (n != sysCount || sil != sysSilenced) {
                addLog("System: $n recording, silenced=$sil")
            }
            sysCount = n
            sysSilenced = sil
        }
    }

    // ------------------------------------------------------------- model + audio

    private fun loadModel() {
        StorageService.unpack(
            this, "model-en-us", "model",
            { m: Model ->
                model = m
                addLog("Model ready")
                startAudio()
            },
            { e: IOException ->
                audioErr = "Model load failed: ${e.message}"
            }
        )
    }

    private fun stopAudio() {
        running = false
        try {
            audioThread?.join(1500)
        } catch (ignored: InterruptedException) {
        }
        audioThread = null
    }

    private fun startAudio() {
        stopAudio()
        val m = model ?: return
        audioErr = null
        partial = ""
        lastFinal = ""
        zeroSince = 0L
        peak = 0
        rms = 0.0

        val src = sources[srcIdx]
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec: AudioRecord = try {
            AudioRecord(
                src.id, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SAMPLE_RATE * 2)
            )
        } catch (e: Exception) {
            audioErr = "AudioRecord failed: ${e.message}"
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            audioErr = "AudioRecord not initialised (${src.label})"
            rec.release()
            return
        }

        running = true
        addLog("Listening via ${src.label}")
        val t = Thread {
            val recognizer = Recognizer(m, SAMPLE_RATE.toFloat())
            try {
                rec.startRecording()
                val buf = ShortArray(1600) // 100 ms
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) {
                        if (n < 0) {
                            audioErr = "Read error $n"
                            break
                        }
                        continue
                    }
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
                    if (recognizer.acceptWaveForm(buf, n)) {
                        val text = JSONObject(recognizer.result).optString("text")
                        if (text.isNotBlank()) lastFinal = text
                        partial = ""
                    } else {
                        partial = JSONObject(recognizer.partialResult).optString("partial")
                    }
                }
            } catch (e: Exception) {
                audioErr = "Audio error: ${e.message}"
            } finally {
                try { rec.stop() } catch (ignored: Exception) {}
                rec.release()
                recognizer.close()
            }
        }
        audioThread = t
        t.start()
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        stopAudio()
        try { am.unregisterAudioRecordingCallback(recordingCallback) } catch (ignored: Exception) {}
        root?.let {
            try { wm.removeView(it) } catch (ignored: Exception) {}
        }
        root = null
        model?.close()
        model = null
        super.onDestroy()
    }
}
