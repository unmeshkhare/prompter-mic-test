package com.graviton.promptertest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
    private lateinit var content: FrameLayout
    private var rootView: FrameLayout? = null

    private val main = Handler(Looper.getMainLooper())
    private val alignExec = Executors.newSingleThreadExecutor()

    private var parsed: ScriptParser.Parsed? = null
    @Volatile private var aligner: Aligner? = null
    private var following = false

    // curPos = what the highlight shows; alignPos = what the matcher believes; curPos = alignPos + lead
    private var curPos = 0
    @Volatile private var lastAlignedPos = 0
    @Volatile private var lead = 2

    // window state
    private var rot = 0          // text rotation in degrees: 0, 90, 180, 270
    private var lastLand = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.post { applyPrefs() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels
    private fun isLand() = screenW() > screenH()

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
        lead = prefs.lead
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

    private fun headerButton(label: String, color: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 12.5f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(color)
        t.setPadding(dp(9), dp(8), dp(9), dp(8))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun barBackground(): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(Color.argb(225, 20, 22, 30))
        return g
    }

    private fun buildUi() {
        val root = FrameLayout(this)

        // everything visible lives in `content`, which is rotated as a whole
        content = FrameLayout(this)
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        // header = drag handle
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(10), dp(2), dp(2), dp(2))
        header.background = barBackground()

        tvStatus = TextView(this)
        tvStatus.textSize = 12f
        tvStatus.setTextColor(Color.WHITE)
        tvStatus.maxLines = 1
        tvStatus.ellipsize = TextUtils.TruncateAt.END
        tvStatus.text = "Loading..."
        header.addView(tvStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(headerButton("Rotate", Color.rgb(255, 214, 120)) { rotateText() })
        tvHide = headerButton("Hide", Color.rgb(140, 200, 255)) {
            val showing = controls.visibility == View.VISIBLE
            controls.visibility = if (showing) View.GONE else View.VISIBLE
            tvHide.text = if (showing) "Menu" else "Hide"
        }
        header.addView(tvHide)
        header.addView(headerButton("X", Color.rgb(255, 130, 130)) { stopSelf() })
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

        content.addView(
            column,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(content, FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER))

        // resize grip: always the bottom-right corner of the screen window
        val grip = TextView(this)
        grip.text = "◢"
        grip.textSize = 22f
        grip.gravity = Gravity.CENTER
        grip.setTextColor(Color.argb(210, 255, 255, 255))
        val gl = FrameLayout.LayoutParams(dp(40), dp(40))
        gl.gravity = Gravity.END or Gravity.BOTTOM
        root.addView(grip, gl)

        lp = WindowManager.LayoutParams(
            dp(300), dp(300),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lastLand = isLand()
        loadGeometry()

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
                        clampPosition()
                        wm.updateViewLayout(root, lp)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> saveGeometry()
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
                        lp.width = sw0 + (e.rawX - tx).toInt()
                        lp.height = sh0 + (e.rawY - ty).toInt()
                        clampSize()
                        clampPosition()
                        applyContentSize()
                        wm.updateViewLayout(root, lp)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> saveGeometry()
                }
                return true
            }
        })

        wm.addView(root, lp)
        rootView = root
    }

    // ------------------------------------------------------------------ geometry + rotation

    private fun defaultGeo(land: Boolean): IntArray {
        val sw = screenW()
        val sh = screenH()
        return if (!land) {
            intArrayOf(-1, dp(36), min((sw * 0.94).toInt(), dp(460)), min((sh * 0.36).toInt(), dp(380)))
        } else {
            intArrayOf(-1, dp(12), min((sw * 0.62).toInt(), dp(660)), min((sh * 0.5).toInt(), dp(300)))
        }
    }

    /** Window size limits in screen coordinates (text area stays usable when rotated 90/270). */
    private fun clampSize() {
        val vertical = rot % 180 != 0
        val minW = if (vertical) dp(150) else dp(230)
        val minH = if (vertical) dp(230) else dp(150)
        lp.width = lp.width.coerceIn(minW, max(minW, screenW()))
        lp.height = lp.height.coerceIn(minH, max(minH, screenH()))
    }

    private fun clampPosition() {
        lp.x = lp.x.coerceIn(0, max(0, screenW() - lp.width))
        lp.y = lp.y.coerceIn(0, max(0, screenH() - lp.height))
    }

    /** The content view is laid out un-rotated (swapped dimensions for 90/270) and then rotated to fit the window. */
    private fun applyContentSize() {
        val vertical = rot % 180 != 0
        val cw = if (vertical) lp.height else lp.width
        val ch = if (vertical) lp.width else lp.height
        content.layoutParams = FrameLayout.LayoutParams(cw, ch, Gravity.CENTER)
        content.rotation = rot.toFloat()
    }

    private fun loadGeometry() {
        val land = isLand()
        val g = prefs.getGeo(land) ?: defaultGeo(land)
        rot = prefs.getRot(land)
        lp.width = g[2]
        lp.height = g[3]
        clampSize()
        lp.x = if (g[0] >= 0) g[0] else (screenW() - lp.width) / 2
        lp.y = g[1]
        clampPosition()
        applyContentSize()
    }

    private fun saveGeometry() {
        val land = isLand()
        prefs.setGeo(land, lp.x, lp.y, lp.width, lp.height)
        prefs.setRot(land, rot)
    }

    private fun rotateText() {
        val root = rootView ?: return
        rot = (rot + 90) % 360
        val w0 = lp.width
        lp.width = lp.height
        lp.height = w0
        clampSize()
        clampPosition()
        applyContentSize()
        wm.updateViewLayout(root, lp)
        saveGeometry()
    }

    /** Called when the screen turns (by the system callback, and by a safety check every 250 ms). */
    private fun checkOrientation() {
        val root = rootView ?: return
        val land = isLand()
        if (land == lastLand) return
        lastLand = land
        loadGeometry()
        wm.updateViewLayout(root, lp)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        checkOrientation()
    }

    // ------------------------------------------------------------------ prefs

    private fun applyPrefs() {
        view.setStyle(
            Themes.get(prefs.theme), prefs.highlight, prefs.fontSp, prefs.readingLine, prefs.opacity
        )
        val newLead = prefs.lead
        if (newLead != lead) {
            lead = newLead
            val ps = parsed
            if (ps != null) {
                curPos = min(ps.n(), lastAlignedPos + lead)
                view.setPos(curPos)
            }
        }
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

    /** Jump the highlight to word idx (button or tap). The matcher is told the matching "heard" position. */
    private fun jump(idx: Int) {
        val ps = parsed ?: return
        val shown = idx.coerceIn(0, ps.n())
        val heard = max(0, shown - lead)
        curPos = shown
        lastAlignedPos = heard
        view.setPos(shown)
        alignExec.execute { aligner?.setPos(heard) }
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
        val tokens = TextProc.tokenize(text).filter { it != "unk" }
        if (tokens.isEmpty()) return
        alignExec.execute {
            val al = aligner ?: return@execute
            val r = al.update(tokens)
            if (r.pos != lastAlignedPos) {
                lastAlignedPos = r.pos
                val p = r.pos
                main.post {
                    val n = parsed?.n() ?: 0
                    curPos = min(n, p + lead)
                    view.setPos(curPos)
                }
            }
        }
    }

    private val statusTick = object : Runnable {
        override fun run() {
            checkOrientation()
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
                    val lag = engine.lagMs
                    val slow = if (lag > 700) "SLOW ${lag / 100 / 10.0}s  " else ""
                    text = slow + (if (prefs.showHeard && heard.isNotBlank()) "Listening: $heard" else "Listening...")
                    color = if (lag > 700) Color.rgb(255, 200, 90) else Color.rgb(120, 230, 140)
                }
            }
            tvStatus.text = text
            tvStatus.setTextColor(color)
            main.postDelayed(this, 250)
        }
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
