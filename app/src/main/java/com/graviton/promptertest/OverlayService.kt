package com.graviton.promptertest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.graviton.promptertest.core.Aligner
import com.graviton.promptertest.core.ScriptParser
import com.graviton.promptertest.core.TextProc
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** The floating teleprompter window. */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.graviton.promptertest.STOP"
        const val EXTRA_SCRIPT = "script_id"
        private const val CHANNEL = "prompter"
    }

    private lateinit var prefs: Prefs
    private lateinit var store: ScriptStore
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var engine: VoiceEngine
    private lateinit var view: PrompterView
    private lateinit var tvStatus: TextView
    private lateinit var btnPlay: TextView
    private lateinit var controls: LinearLayout
    private lateinit var tvHide: TextView
    private var rootView: FrameLayout? = null

    private val main = Handler(Looper.getMainLooper())
    private val alignExec = Executors.newSingleThreadExecutor()

    private var parsed: ScriptParser.Parsed? = null
    @Volatile private var aligner: Aligner? = null
    private var following = false
    private var curPos = 0
    @Volatile private var lastAlignedPos = 0

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.post { applyPrefs() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (rootView == null) init()
        val id = intent?.getStringExtra(EXTRA_SCRIPT) ?: prefs.lastScript
        if (id.isNotEmpty()) loadScript(id)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Voice Prompter", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Voice Prompter is running")
            .setContentText("Tap Stop to close the floating prompter")
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    // ------------------------------------------------------------------ setup

    private fun init() {
        prefs = Prefs(this)
        store = ScriptStore(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildUi()
        engine = VoiceEngine(this) { text -> onHeard(text) }
        engine.loadModel()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        applyPrefs()
        main.post(statusTick)
    }

    private fun chip(label: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 12.5f
        t.setTextColor(Color.WHITE)
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setPadding(dp(9), dp(7), dp(9), dp(7))
        val bg = GradientDrawable()
        bg.setColor(Color.argb(70, 255, 255, 255))
        bg.cornerRadius = dp(8).toFloat()
        t.background = bg
        t.setOnClickListener { onClick() }
        val l = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        l.setMargins(dp(2), 0, dp(2), 0)
        t.layoutParams = l
        return t
    }

    private fun barBackground(): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(Color.argb(225, 20, 22, 30))
        return g
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        // header = drag handle
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(10), dp(4), dp(4), dp(4))
        header.background = barBackground()

        tvStatus = TextView(this)
        tvStatus.textSize = 12f
        tvStatus.setTextColor(Color.WHITE)
        tvStatus.maxLines = 1
        tvStatus.ellipsize = TextUtils.TruncateAt.END
        tvStatus.text = "Loading..."
        header.addView(tvStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        tvHide = TextView(this)
        tvHide.text = "Hide menu"
        tvHide.textSize = 12f
        tvHide.setTextColor(Color.rgb(140, 200, 255))
        tvHide.setPadding(dp(8), dp(8), dp(8), dp(8))
        tvHide.setOnClickListener {
            val showing = controls.visibility == View.VISIBLE
            controls.visibility = if (showing) View.GONE else View.VISIBLE
            tvHide.text = if (showing) "Menu" else "Hide menu"
        }
        header.addView(tvHide)

        val close = TextView(this)
        close.text = "  X  "
        close.textSize = 16f
        close.typeface = Typeface.DEFAULT_BOLD
        close.setTextColor(Color.rgb(255, 130, 130))
        close.setPadding(dp(8), dp(6), dp(8), dp(6))
        close.setOnClickListener { stopSelf() }
        header.addView(close)
        column.addView(header)

        // controls
        controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER_VERTICAL
        controls.setPadding(dp(4), dp(4), dp(6), dp(5))
        controls.background = barBackground()

        controls.addView(chip("Top") { jump(0) })
        controls.addView(chip("Back") { parsed?.let { jump(it.backSentence(curPos)) } })
        btnPlay = chip("Start") { togglePlay() }
        controls.addView(btnPlay)
        controls.addView(chip("Skip") { parsed?.let { jump(it.forwardSentence(curPos)) } })
        controls.addView(chip("A-") { prefs.fontSp = max(14, prefs.fontSp - 2) })
        controls.addView(chip("A+") { prefs.fontSp = min(80, prefs.fontSp + 2) })

        val bgLabel = TextView(this)
        bgLabel.text = "Bg"
        bgLabel.textSize = 11f
        bgLabel.setTextColor(Color.rgb(190, 195, 210))
        bgLabel.setPadding(dp(6), 0, 0, 0)
        controls.addView(bgLabel)

        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = prefs.opacity
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) prefs.opacity = p
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val sl = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        sl.leftMargin = dp(2)
        controls.addView(seek, sl)
        column.addView(controls)

        view = PrompterView(this)
        view.onWordTap = { idx -> jump(idx) }
        column.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(
            column,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // resize grip, bottom-right
        val grip = TextView(this)
        grip.text = "◢"
        grip.textSize = 22f
        grip.gravity = Gravity.CENTER
        grip.setTextColor(Color.argb(200, 255, 255, 255))
        val gl = FrameLayout.LayoutParams(dp(40), dp(40))
        gl.gravity = Gravity.END or Gravity.BOTTOM
        root.addView(grip, gl)

        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        var w = if (prefs.winW > 0) prefs.winW else min((sw * 0.94).toInt(), dp(460))
        var h = if (prefs.winH > 0) prefs.winH else min((sh * 0.36).toInt(), dp(380))
        w = w.coerceIn(dp(220), sw)
        h = h.coerceIn(dp(150), sh)
        var x = if (prefs.winX >= 0) prefs.winX else (sw - w) / 2
        var y = if (prefs.winY >= 0) prefs.winY else dp(36)
        x = x.coerceIn(0, max(0, sw - w))
        y = y.coerceIn(0, max(0, sh - dp(60)))

        lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y

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
                        val m = resources.displayMetrics
                        lp.x = (sx + (e.rawX - tx).toInt()).coerceIn(0, max(0, m.widthPixels - dp(80)))
                        lp.y = (sy + (e.rawY - ty).toInt()).coerceIn(0, max(0, m.heightPixels - dp(60)))
                        wm.updateViewLayout(root, lp)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        prefs.winX = lp.x
                        prefs.winY = lp.y
                    }
                }
                return true
            }
        })

        grip.setOnTouchListener(object : View.OnTouchListener {
            var sw0 = 0
            var sh0 = 0
            var tx = 0f
            var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sw0 = lp.width; sh0 = lp.height; tx = e.rawX; ty = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val m = resources.displayMetrics
                        lp.width = (sw0 + (e.rawX - tx).toInt()).coerceIn(dp(220), m.widthPixels)
                        lp.height = (sh0 + (e.rawY - ty).toInt()).coerceIn(dp(150), m.heightPixels)
                        wm.updateViewLayout(root, lp)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        prefs.winW = lp.width
                        prefs.winH = lp.height
                    }
                }
                return true
            }
        })

        wm.addView(root, lp)
        rootView = root
    }

    private fun applyPrefs() {
        view.setStyle(
            Themes.get(prefs.theme), prefs.highlight, prefs.fontSp, prefs.readingLine, prefs.opacity
        )
    }

    // ------------------------------------------------------------------ script + following

    private fun loadScript(id: String) {
        val doc = store.get(id) ?: return
        if (following) setFollowing(false)
        val ps = ScriptParser.parse(doc.text)
        parsed = ps
        aligner = Aligner(ps.norms())
        curPos = 0
        lastAlignedPos = 0
        view.setScript(ps)
        prefs.lastScript = id
        if (ps.n() == 0) {
            Toast.makeText(this, "This script has no words yet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun jump(p: Int) {
        val ps = parsed ?: return
        val np = p.coerceIn(0, ps.n())
        curPos = np
        lastAlignedPos = np
        view.setPos(np)
        alignExec.execute { aligner?.setPos(np) }
    }

    private fun setFollowing(on: Boolean) {
        if (on) {
            if (engine.model == null) {
                Toast.makeText(this, "Speech model still loading, one moment", Toast.LENGTH_SHORT).show()
                return
            }
            val ps = parsed
            if (ps == null || ps.n() == 0) {
                Toast.makeText(this, "Open a script first", Toast.LENGTH_SHORT).show()
                return
            }
            val grammar: List<String>? = if (prefs.scriptAware) {
                val set = LinkedHashSet<String>()
                for (w in ps.words) {
                    val n = w.norm
                    if (n.isNotEmpty() && n.all { it in 'a'..'z' || it == '\'' }) set.add(n)
                }
                ArrayList(set)
            } else null
            val idx = prefs.micIdx.coerceIn(0, MicSources.list.size - 1)
            if (!engine.start(MicSources.list[idx].second, grammar)) {
                Toast.makeText(this, engine.audioError ?: "Could not start the microphone", Toast.LENGTH_LONG).show()
                return
            }
            following = true
            btnPlay.text = "Pause"
        } else {
            engine.stop()
            following = false
            btnPlay.text = "Start"
        }
    }

    private fun togglePlay() = setFollowing(!following)

    /** Called on the audio thread with the words heard in the current phrase. */
    private fun onHeard(text: String) {
        if (!following) return
        val tokens = TextProc.tokenize(text)
        if (tokens.isEmpty()) return
        alignExec.execute {
            val al = aligner ?: return@execute
            val r = al.update(tokens)
            if (r.pos != lastAlignedPos) {
                lastAlignedPos = r.pos
                val p = r.pos
                main.post {
                    curPos = p
                    view.setPos(p)
                }
            }
        }
    }

    private val statusTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val err = engine.loadError ?: engine.audioError
            val text: String
            val color: Int
            when {
                err != null -> { text = err; color = Color.rgb(255, 130, 130) }
                engine.model == null -> { text = "Loading speech model..."; color = Color.rgb(210, 210, 210) }
                !following -> { text = "Paused. Tap Start, then read."; color = Color.rgb(210, 210, 210) }
                engine.zeroSince > 0 && now - engine.zeroSince > 1500 -> {
                    text = "Mic muted by Android. Turn on the accessibility service."
                    color = Color.rgb(255, 130, 130)
                }
                else -> {
                    val heard = engine.heard
                    text = if (prefs.showHeard && heard.isNotBlank()) "Listening: $heard" else "Listening..."
                    color = Color.rgb(120, 230, 140)
                }
            }
            tvStatus.text = text
            tvStatus.setTextColor(color)
            main.postDelayed(this, 250)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val root = rootView ?: return
        val m = resources.displayMetrics
        lp.width = min(lp.width, m.widthPixels)
        lp.height = min(lp.height, m.heightPixels)
        lp.x = lp.x.coerceIn(0, max(0, m.widthPixels - lp.width))
        lp.y = lp.y.coerceIn(0, max(0, m.heightPixels - dp(60)))
        wm.updateViewLayout(root, lp)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (rootView != null) {
            prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
            engine.release()
            try { wm.removeView(rootView) } catch (ignored: Exception) {}
            rootView = null
        }
        alignExec.shutdownNow()
        super.onDestroy()
    }
}
